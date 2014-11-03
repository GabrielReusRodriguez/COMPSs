/*
 *  Copyright 2002-2014 Barcelona Supercomputing Center (www.bsc.es)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */


package integratedtoolkit.util;

import integratedtoolkit.log.Loggers;
import integratedtoolkit.types.Core;
import integratedtoolkit.types.ResourceDescription;
import integratedtoolkit.types.ScheduleState;

import java.util.TreeMap;
import java.util.List;
import java.util.LinkedList;
import integratedtoolkit.types.Task;
import java.util.Map.Entry;
import java.util.PriorityQueue;

import org.apache.log4j.Logger;

/**
 * The QueueManager class is an utility to manage the schedule of all the
 * dependency-free tasks. It controls if they are running, if they have been
 * scheduled in a resource slot queue, if they failed on its previous execution
 * and must be rescheduled or if they have no resource where to run.
 *
 * There are many queues: - tasks without resource where to run - tasks to be
 * rescheduled - one queue for each slot of all the resources
 */
public class QueueManager {

    // Pending tasks
    /**
     * Tasks with no resource where they can be run
     */
    private static LinkedList<Task> noResourceTasks;
    /**
     * Amount of tasks per core that can't be run
     */
    private static int[] noResourceCount;

    /**
     * Task to be rescheduled
     */
    private static LinkedList<Task> tasksToReschedule;
    /**
     * Amount of tasks per core to be rescheduled
     */
    private static int[] toRescheduleCount;

    /**
     * Tasks with priority
     */
    private static LinkedList<Task> priorityTasks;
    /**
     * Amount of priority tasks per core
     */
    private static int[] priorityCount;

    /**
     * Resource name --> slot queues
     */
    private TreeMap<String, ResourceQueue> nodeToSlotQueues;
    /**
     * Amount of tasks waiting on queues per core
     */
    private int[] waitingOnQueuesCount;

    //Task Stats
    /**
     * First task to be executed for that method
     */
    private Task[] firstMethodExecution;
    /**
     * Average execution time per core
     */
    private Long[] coreAverageExecutionTime;
    /**
     * Max execution time per core
     */
    private Long[] coreMaxExecutionTime;
    /**
     * Min execution time per core
     */
    private Long[] coreMinExecutionTime;
    /**
     * Executed Tasks per Core
     */
    private int[] coreExecutedCount;

    private static final Logger logger = Logger.getLogger(Loggers.TS_COMP);

    /**
     * Constructs a new QueueManager
     *
     * @param TS TaskScheduler associated to the manager
     */
    public QueueManager() {
        if (noResourceTasks == null) {
            noResourceTasks = new LinkedList<Task>();
        } else {
            noResourceTasks.clear();
        }

        noResourceCount = new int[Core.coreCount];

        if (tasksToReschedule == null) {
            tasksToReschedule = new LinkedList<Task>();
        } else {
            tasksToReschedule.clear();
        }
        toRescheduleCount = new int[Core.coreCount];

        if (priorityTasks == null) {
            priorityTasks = new LinkedList<Task>();
        } else {
            priorityTasks.clear();
        }
        priorityCount = new int[Core.coreCount];

        if (nodeToSlotQueues == null) {
            nodeToSlotQueues = new TreeMap<String, ResourceQueue>();
        } else {
            for (String resource : nodeToSlotQueues.keySet()) {
                nodeToSlotQueues.get(resource).clear();
            }
        }

        if (coreAverageExecutionTime == null) {
            coreAverageExecutionTime = new Long[Core.coreCount];

            for (int i = 0; i < Core.coreCount; i++) {
                coreAverageExecutionTime[i] = null;
            }
        }

        if (coreMinExecutionTime == null) {
            coreMinExecutionTime = new Long[Core.coreCount];

            for (int i = 0; i < Core.coreCount; i++) {
                coreMinExecutionTime[i] = 0l;
            }
        }

        if (coreMaxExecutionTime == null) {
            coreMaxExecutionTime = new Long[Core.coreCount];

            for (int i = 0; i < Core.coreCount; i++) {
                coreMaxExecutionTime[i] = 0l;
            }
        }
        coreExecutedCount = new int[Core.coreCount];
        waitingOnQueuesCount = new int[Core.coreCount];
        firstMethodExecution = new Task[Core.coreCount];

        // Set the maximum job count for all resources, to achieve a faster matching
        List<String> allResources = ResourceManager.findResources(ResourceManager.ALL_RESOURCES, false);
        for (String res : allResources) {
            newNode(res, ResourceManager.getMaxTaskCount(res));
        }
    }

    /**
     * * SLOTS MANAGEMENT **
     */
    /**
     * Adds new slot queues to the a new managed resource
     *
     * @param resourceName Name of the resource
     * @param slots amount of slots for that resource
     */
    public void newNode(String resourceName, int slots) {
        nodeToSlotQueues.put(resourceName, new ResourceQueue(slots));
    }

    /**
     * Removes a resource to be managed
     *
     * @param resourceName name of the resource
     */
    public void removeNode(String resourceName) {
        nodeToSlotQueues.remove(resourceName);
    }

    /**
     * Adds or removes slots from a resource. If the slots argument is a
     * positive integer then new queues are created. If the argument is negative
     *
     * @param name name of the resource to be updated
     * @param slots amount of slots to add or remove
     */
    public void updateNode(String name, int slots) {
        ResourceQueue rq = nodeToSlotQueues.get(name);
        if (rq == null) {
            newNode(name, slots);
            return;
        } else if (slots > 0) { //resource addition, just add new slots
            rq.addSlots(slots);
        } else if (slots < 0) {//resource removal, remove slots
            rq.deleteSlots(-slots);
        }
        return;
    }

    /**
     * Checks if there is any pending modification (slots removal) for that
     * resource and if all the slot removals have been performed. If the whole
     * modification have been done it commits the modifications and removes it
     * from pending.
     *
     * @param resourceName name of the resource
     * @return true if there the current removal request has no more operations
     */
    public boolean performModification(String resourceName) {
        boolean canPerform = false;
        ResourceQueue rq = nodeToSlotQueues.get(resourceName);
        if (!rq.pendingRemovalsPerRequest.isEmpty()) {
            canPerform = rq.pendingRemovalsPerRequest.get(0) == 0;
            if (canPerform) {
                rq.pendingRemovalsPerRequest.remove(0);
            }
        }
        return canPerform;
    }

    /**
     * returns the amount of queues assciated to the resource
     *
     * @param resourceName name of the resource
     * @return amount of queues associated to the resource
     */
    public int getSafeSlotsCount(String resourceName) {
        ResourceQueue rq = nodeToSlotQueues.get(resourceName);
        return rq.slots;
    }

    /**
     * ** NO RESOURCE TASKs MANAGEMENT *****
     */
    /**
     * Adds a task to the queue of tasks with no resource
     *
     * @param t Task to be added
     */
    public void waitWithoutNode(Task t) {
        noResourceCount[t.getCore().getId()]++;
        noResourceTasks.add(t);
    }

    /**
     * Removes from the queue of tasks with no resources a set of tasks
     *
     * @param removedTasks List of tasks that must be removed
     */
    public void resourceFound(List<Task> removedTasks) {
        for (Task t : removedTasks) {
            noResourceCount[t.getCore().getId()]--;
        }
        noResourceTasks.removeAll(removedTasks);
    }

    /**
     * Checks if there is any tasks without resource
     *
     * @return true if there is some tasks on the queue of tasks without
     * resource
     */
    public boolean isTasksWithoutResource() {
        return !noResourceTasks.isEmpty();
    }

    /**
     * Gets the amount of task without resource that execute a specific core
     *
     * @param coreId identifier of the core
     * @return amount of task without resource that execute a specific core
     */
    public int getNoResourceCount(int coreId) {
        return noResourceCount[coreId];
    }

    /**
     * Returns the whole list of tasks without resource
     *
     * @return The whole list of tasks without resource
     */
    public LinkedList<Task> getPendingTasksWithoutNode() {
        return noResourceTasks;
    }

    /**
     * ** TO RESCHEDULE TASKs MANAGEMENT *****
     */
    /**
     * Adds a task to the queue of tasks to be rescheduled
     *
     * @param t Task to be added
     */
    public void newTaskToReschedule(Task t) {
        toRescheduleCount[t.getCore().getId()]++;
        tasksToReschedule.add(t);
    }

    /**
     * Removes a task from the queue of tasks to reschedule
     *
     * @param t tasks to be removed
     */
    public void rescheduledTask(Task t) {
        toRescheduleCount[t.getCore().getId()]--;
        tasksToReschedule.remove(t);
    }

    /**
     * Checks if there is any tasks to reschedule
     *
     * @return true if there is some tasks on the queue of tasks to reschedule
     */
    public boolean areTasksToReschedule() {
        return !tasksToReschedule.isEmpty();
    }

    /**
     * Gets the amount of task to be rescheduled that execute a specific core
     *
     * @param coreId identifier of the core
     * @return amount of task to be rescheduled that execute a specific core
     */
    public int getToRescheduleCount(int coreId) {
        return toRescheduleCount[coreId];
    }

    /**
     * Returns the whole list of tasks to reschedule
     *
     * @return The whole list of tasks to reschedule
     */
    public LinkedList<Task> getTasksToReschedule() {
        return tasksToReschedule;
    }

    /**
     * ** Priority Tasks Management
     */
    /**
     * Adds a task to the queue of prioritary tasks
     *
     * @param t Task to be added
     */
    public void newPriorityTask(Task t) {
        priorityTasks.add(t);
        priorityCount[t.getCore().getId()]++;

    }

    /**
     * Removes a task from the queue of prioritary tasks
     *
     * @param t tasks to be removed
     */
    public void priorityTaskScheduled(Task t) {
        priorityCount[t.getCore().getId()]--;
        priorityTasks.remove(t);
    }

    /**
     * Checks if there is any prioritary task
     *
     * @return true if there is some tasks on the queue of prioritary tasks
     */
    public boolean arePriorityTasks() {
        return !priorityTasks.isEmpty();
    }

    /**
     * Gets the amount of priority task that execute a specific core
     *
     * @param coreId identifier of the core
     * @return amount of prioritary task that execute a specific core
     */
    public int getPriorityCount(int coreId) {
        return priorityCount[coreId];
    }

    /**
     * Returns the whole list of prioritary tasks
     *
     * @return The whole list of prioritary tasks
     */
    public LinkedList<Task> getPriorityTasks() {
        return priorityTasks;
    }

    /**
     * RESOURCE SLOTS QUEUE MANAGEMENT *
     */
    /**
     * Adds a task into the shortest slot queue of a resource
     *
     * @param t Task to schedule
     * @param resourceName resource where to schedule the task
     */
    public void waitOnNode(Task t, String resourceName) {
        waitingOnQueuesCount[t.getCore().getId()]++;
        nodeToSlotQueues.get(resourceName).waits(t);
    }

    /**
     * Removes a task from a queue
     *
     * @param t task to be removed
     * @param resourceName resource which queue hosts the task
     */
    public void ordinaryTaskScheduled(Task t, String resourceName) {
        waitingOnQueuesCount[t.getCore().getId()]--;
        nodeToSlotQueues.get(resourceName).removeWaiting(t);
    }

    public int getOrdinaryCount(int coreId) {
        return this.waitingOnQueuesCount[coreId];
    }

    /**
     * Moves a task from on slot queue to the shortest slot queue from another
     * resource
     *
     * @param t Task to move
     * @param oldResourceName Resource where the task is
     * @param newResourceName Resource where the task will be scheduled
     */
    public void migrateTask(Task t, String oldResourceName, String newResourceName) {
        nodeToSlotQueues.get(oldResourceName).removeWaiting(t);
        nodeToSlotQueues.get(newResourceName).waits(t);
    }

    /**
     * The execution of a tasks starts at the specified resource
     *
     * @param t task which is running
     * @param resourceName resource where the task is being executed
     */
    public void startsExecution(Task t, String resourceName) {
        nodeToSlotQueues.get(resourceName).executes(t);
        t.setInitialTimeStamp(System.currentTimeMillis());
        if (firstMethodExecution[t.getCore().getId()] == null) {
            firstMethodExecution[t.getCore().getId()] = t;
        }
    }

    /**
     * Updates the slot queue taking into account that the execution of a tasks
     * has ended at the specified resource
     *
     * @param task the tasks that has been run
     * @param resourceName resource where the task was being executed
     */
    public void endsExecution(Task task, String resourceName) {
        nodeToSlotQueues.get(resourceName).ends(task);
        long initialTime = task.getInitialTimeStamp();
        long duration = System.currentTimeMillis() - initialTime;
        int core = task.getCore().getId();
        Long mean = coreAverageExecutionTime[core];
        if (mean == null) {
            mean = 0l;
        }
        if (coreMaxExecutionTime[core] < duration) {
            coreMaxExecutionTime[core] = duration;
        }
        if (coreMinExecutionTime[core] > duration) {
            coreMinExecutionTime[core] = duration;
        }
        coreAverageExecutionTime[core] = ((mean * coreExecutedCount[core]) + duration) / (coreExecutedCount[core] + 1);
        coreExecutedCount[core]++;
    }

    /**
     * Updates the slot queue taking into account that the execution of a tasks
     * has failed at the specified resource
     *
     * @param task the failed task
     * @param resourceName name of the resource where it was running
     */
    public void cancelExecution(Task task, String resourceName) {
        nodeToSlotQueues.get(resourceName).ends(task);
    }

    /**
     * Checks if any slot of the resource is executing something
     *
     * @param resourceName name of the resource
     * @return true if all slots are idle
     */
    public boolean isExecuting(String resourceName) {
        return nodeToSlotQueues.get(resourceName).isExecuting();
    }

    /**
     * Checks if a slot of a host is executing any task
     *
     * @param host name of the resource
     * @param slot identifier of the slot
     * @return true if the slot has an assigned task and it is running
     */
    public boolean isExecuting(String host, int slot) {
        ResourceQueue hostqueue = nodeToSlotQueues.get(host);
        return hostqueue.onExecution[slot] != null;
    }

    /**
     * Gets a list of all the pending tasks in the slot queues of the specified
     * resource
     *
     * @param resourceName name of the resource
     * @return list of pending tasks on the resource
     */
    public LinkedList<Task> getPendingTasks(String resourceName) {
        return nodeToSlotQueues.get(resourceName).getAllPending();
    }

    /**
     * Looks for the next job that will be executed on the resource.
     *
     * @param resourceName Name of the resource where task run
     * @return
     */
    public Task getNextJob(String resourceName) {
        return nodeToSlotQueues.get(resourceName).getNext();
    }

    /**
     * Looks for the slot with a shorter queue within a set of resources
     *
     * @param resources List of resources to be analysed
     * @return the resource with the shortest slot queue
     */
    public String getMinQueueResource(List<String> resources) {
        long minWait = Long.MAX_VALUE;
        String bestResource = "";
        for (String res : resources) {
            long actualTime = nodeToSlotQueues.get(res).getMinWaitTime();
            if (actualTime < minWait) {
                minWait = actualTime;
                bestResource = res;
            }
        }
        return bestResource;
    }

    /**
     * Seeks and moves a certain amount of tasks of a specific core from one
     * slot queue to another one.
     *
     * @param sourceHost resourceName where the tasks are looked for
     * @param targetHost resourceName where the tasks are leaved
     * @param sourceSlot slot id where the tasks are taken
     * @param targetSlot slot id where the tasks are leaved
     * @param coreId core identifier of the tasks that are being moved
     * @param amount amount of tasks to move
     */
    public void seekAndMove(String sourceHost, String targetHost, int sourceSlot, int targetSlot, int coreId, int amount) {
        ResourceQueue source = nodeToSlotQueues.get(sourceHost);
        ResourceQueue target = nodeToSlotQueues.get(targetHost);
        LinkedList<Task> movedTasks = new LinkedList();
        int foundTasks = 0;
        for (Task t : source.queues[sourceSlot]) {
            if (t.isSchedulingForced()) {
                continue;
            }
            if (foundTasks == amount) {
                break;
            }
            if (coreId == t.getCore().getId()) {
                movedTasks.add(t);
                target.waits(t, targetSlot);
                foundTasks++;
            }
        }
        source.waitingTasksPerCore[sourceSlot][coreId] -= foundTasks;
        source.queues[sourceSlot].removeAll(movedTasks);
    }

    /**
     * Gets the first task on a certain slot queue of a resource
     *
     * @param host name of the resource
     * @param slot identifier of the slot
     * @return the first task on the slot queue
     */
    public Task getNextJobSlot(String host, int slot) {
        ResourceQueue hostqueue = nodeToSlotQueues.get(host);
        if (hostqueue.queues[slot].size() > 0) {
            return hostqueue.queues[slot].get(0);
        } else {
            return null;
        }
    }

    /**
     * Constructs a new ScheduleState and adds the description of the current
     * scheduling.
     *
     * @param ss current schedule state to be complemented
     */
    public ScheduleState getCurrentState() {
        ScheduleState ss = new ScheduleState();
        ss.coreMeanExecutionTime = new long[Core.coreCount];
        ss.waitingOnQueuesCores = waitingOnQueuesCount;
        ss.noResourcePerCore = new int[Core.coreCount];
        ss.noResource = noResourceTasks.size();
        ss.slotCountPerCore = new Integer[Core.coreCount];

        for (int i = 0; i < Core.coreCount; i++) {
            System.arraycopy(ResourceManager.getProcessorsCount(), 0, ss.slotCountPerCore, 0, Core.coreCount);
        }
        System.arraycopy(noResourceCount, 0, ss.noResourcePerCore, 0, Core.coreCount);
        for (int i = 0; i < Core.coreCount; i++) {
            if (coreAverageExecutionTime[i] != null) {
                ss.coreMeanExecutionTime[i] = coreAverageExecutionTime[i];
            } else {
                if (firstMethodExecution[i] != null) {
                    //if any has started --> take the already spent time as the mean.
                    Long initTimeStamp = firstMethodExecution[i].getInitialTimeStamp();
                    if (initTimeStamp != null) {
                        //if the first task hasn't failed
                        long elapsedTime = System.currentTimeMillis() - initTimeStamp;
                        ss.coreMeanExecutionTime[i] = elapsedTime;
                    } else {
                        ss.coreMeanExecutionTime[i] = 100l;
                    }
                } else {
                    ss.coreMeanExecutionTime[i] = 100l;
                }
            }
        }

        for (Entry<String, ResourceQueue> e : nodeToSlotQueues.entrySet()) {
            ResourceQueue rq = e.getValue();
            Integer[] runningMethods = new Integer[rq.slots];
            long[] elapsedTime = new long[rq.slots];
            int[][] waitingCounts = new int[rq.slots][Core.coreCount];
            List<Integer> ableCores = ResourceManager.getExecutableCores(e.getKey());
            for (int slotId = 0; slotId < rq.slots; slotId++) {
                if (rq.onExecution[slotId] == null) {
                    runningMethods[slotId] = null;
                    elapsedTime[slotId] = 0l;
                } else {
                    runningMethods[slotId] = rq.onExecution[slotId].getCore().getId();
                    elapsedTime[slotId] = System.currentTimeMillis() - rq.onExecution[slotId].getInitialTimeStamp();
                }

                try {
                    System.arraycopy(rq.waitingTasksPerCore[slotId], 0, waitingCounts[slotId], 0, Core.coreCount);
                } catch (Exception ex) {
                    logger.error("Error copying array\n waitingTasks lenght: " + rq.waitingTasksPerCore[slotId].length);
                    ex.printStackTrace();
                }
            }
            ss.addHost(e.getKey(), runningMethods, elapsedTime, waitingCounts, ableCores, rq.slotsToReduce);
        }
        return ss;
    }

    /**
     * Creates a description of the current schedule for all the resources. The
     * string pattern is described as follows: On execution: hostName1: taskId
     * taskId ... (all running tasks for hostName1) hostName2: taskId ... (all
     * running tasks for hostName2) ...
     *
     * Pending: taskId taskId taskId ... (all pending tasks in slots, to
     * reschedule or without resource)
     *
     * @return description of the current schedule state
     */
    public String describeCurrentState() {
        List<String> recursos = ResourceManager.findResources(ResourceManager.ALL_RESOURCES);
        String info = "\tOn execution:\n";
        String pending = "";

        for (int i = 0; i < priorityTasks.size(); i++) {
            pending += " " + priorityTasks.get(i).getId() + "p";
        }
        for (int i = 0; i < tasksToReschedule.size(); i++) {
            pending += " " + tasksToReschedule.get(i).getId() + "r";
        }
        for (String hostName : recursos) {
            info += "\t\t" + hostName + ":";
            for (int i = 0; i < nodeToSlotQueues.get(hostName).getOnExecution().size(); i++) {
                info += " " + nodeToSlotQueues.get(hostName).getOnExecution().get(i);
            }
            info += "\n";
            for (int i = 0; i < nodeToSlotQueues.get(hostName).getAllPending().size(); i++) {
                pending += " " + nodeToSlotQueues.get(hostName).getAllPending().get(i).getId();
            }
        }
        for (int i = 0; i < noResourceTasks.size(); i++) {
            pending += " " + noResourceTasks.get(i).getId() + "b";
        }
        return info + "\n\tPending:" + pending;
    }

    /**
     * Obtains the data that must be shown on the monitor
     *
     * @return String with core Execution information in an XML format
     */
    public String getMonitoringInfo() {
        StringBuilder sb = new StringBuilder("\t<CoresInfo>\n");
        for (java.util.Map.Entry<String, Integer> entry : Core.signatureToId.entrySet()) {
            int core = entry.getValue();
            String signature = entry.getKey();
            sb.append("\t\t<Core id=\"").append(core).append("\" signature=\"" + signature + "\">\n");
            if (coreAverageExecutionTime[core] != null) {
                sb.append("\t\t\t<MeanExecutionTime>").append((coreAverageExecutionTime[core] / 1000) + 1).append("</MeanExecutionTime>\n");
                sb.append("\t\t\t<MinExecutionTime>").append((coreAverageExecutionTime[core] / 1000) + 1).append("</MinExecutionTime>\n");
                sb.append("\t\t\t<MaxExecutionTime>").append((coreAverageExecutionTime[core] / 1000) + 1).append("</MaxExecutionTime>\n");
            } else {
                sb.append("\t\t\t<MeanExecutionTime>0</MeanExecutionTime>\n");
                sb.append("\t\t\t<MinExecutionTime>0</MinExecutionTime>\n");
                sb.append("\t\t\t<MaxExecutionTime>0</MaxExecutionTime>\n");
            }
            sb.append("\t\t\t<ExecutedCount>").append(coreExecutedCount[core]).append("</ExecutedCount>\n");
            sb.append("\t\t</Core>\n");
        }
        sb.append("\t</CoresInfo>\n");
        sb.append("\t<ResourceInfo>\n");
        for (java.util.Map.Entry<String, ResourceQueue> entry : nodeToSlotQueues.entrySet()) {
            sb.append("\t\t<Resource id=\"").append(entry.getKey()).append("\">\n");

            if (ResourceManager.getPool().getResourceDescription(entry.getKey()) != null) {
                ResourceDescription description = ResourceManager.getPool().getResourceDescription(entry.getKey());

                sb.append("\t\t\t<CPU>").append(description.getProcessorCPUCount()).append("</CPU>\n");
                sb.append("\t\t\t<Memory>").append(description.getMemoryPhysicalSize()).append("</Memory>\n");
                sb.append("\t\t\t<Disk>").append(description.getStorageElemSize()).append("</Disk>\n");
            }

            if (entry.getValue().slotsToReduce > 0) {
                sb.append("\t\t\t<Status>Removing</Status>\n");
            } else {
                sb.append("\t\t\t<Status>Ready</Status>\n");
            }
            ResourceQueue rq = entry.getValue();
            for (int slot = 0; slot < rq.onExecution.length; slot++) {
                if (rq.onExecution[slot] == null) {
                    sb.append("\t\t\t<Slot id=\"").append(slot).append("\" />\n");
                } else {
                    sb.append("\t\t\t<Slot id=\"").append(slot).append("\">").append(rq.onExecution[slot].getId()).append("</Slot>\n");
                }
            }
            sb.append("\t\t</Resource>\n");
        }
        /*for (String VMName : this.creating) {
         sb.append("\t\t<Resource id=\"").append(VMName).append("\">\n");
         sb.append("\t\t\t<Status>Waiting</Status>\n");
         sb.append("\t\t</Resource>\n");
         }*/
        sb.append("\t</ResourceInfo>\n");
        return sb.toString();
    }

    public void resizeDataStructures() {
        int[] noResourceCountTmp = new int[Core.coreCount];
        System.arraycopy(noResourceCount, 0, noResourceCountTmp, 0, noResourceCount.length);
        noResourceCount = noResourceCountTmp;

        int[] toRescheduleCountTmp = new int[Core.coreCount];
        System.arraycopy(toRescheduleCount, 0, noResourceCountTmp, 0, toRescheduleCount.length);
        toRescheduleCount = toRescheduleCountTmp;

        Task[] firstMethodExecutionTmp = new Task[Core.coreCount];
        System.arraycopy(firstMethodExecution, 0, firstMethodExecutionTmp, 0, firstMethodExecution.length);
        firstMethodExecution = firstMethodExecutionTmp;

        Long[] coreAverageExecutionTimeTmp = new Long[Core.coreCount];
        System.arraycopy(coreAverageExecutionTime, 0, coreAverageExecutionTimeTmp, 0, coreAverageExecutionTime.length);
        for (int i = coreAverageExecutionTime.length; i < Core.coreCount; i++) {
            coreAverageExecutionTimeTmp[i] = 0l;
        }
        coreAverageExecutionTime = coreAverageExecutionTimeTmp;

        Long[] coreMaxExecutionTimeTmp = new Long[Core.coreCount];
        System.arraycopy(coreMaxExecutionTime, 0, coreMaxExecutionTimeTmp, 0, coreMaxExecutionTime.length);
        for (int i = coreMaxExecutionTime.length; i < Core.coreCount; i++) {
            coreMaxExecutionTimeTmp[i] = 0l;
        }
        coreMaxExecutionTime = coreMaxExecutionTimeTmp;

        Long[] coreMinExecutionTimeTmp = new Long[Core.coreCount];
        System.arraycopy(coreMinExecutionTime, 0, coreMinExecutionTimeTmp, 0, coreMinExecutionTime.length);
        for (int i = coreMinExecutionTime.length; i < Core.coreCount; i++) {
            coreMinExecutionTimeTmp[i] = 0l;
        }
        coreMinExecutionTime = coreMinExecutionTimeTmp;

        int[] coreExecutedCountTmp = new int[Core.coreCount];
        System.arraycopy(coreExecutedCount, 0, coreExecutedCountTmp, 0, coreExecutedCount.length);
        coreExecutedCount = coreExecutedCountTmp;

        int[] waitingOnQueuesCountTmp = new int[Core.coreCount];
        System.arraycopy(waitingOnQueuesCount, 0, waitingOnQueuesCountTmp, 0, waitingOnQueuesCount.length);
        waitingOnQueuesCount = waitingOnQueuesCountTmp;

        for (ResourceQueue rq : nodeToSlotQueues.values()) {
            rq.resizeDataStructures();
        }

    }

    class ResourceQueue {

        /**
         * Amount of slots for the resource
         */
        int slots;
        /**
         * Amont of tasks being executed
         */
        int executing = 0;
        /**
         * Task Queue for each slot
         */
        LinkedList<Task>[] queues;
        /**
         * Amount of tasks waiting on the queue per slot & core
         */
        int[][] waitingTasksPerCore;
        /**
         * task that is running on each slots
         */
        Task[] onExecution;
        /**
         * total amount of slots to be reduced for that resource
         */
        int slotsToReduce = 0;
        /**
         * List of pending slots removal per request
         */
        LinkedList<Integer> pendingRemovalsPerRequest;

        ResourceQueue(int slots) {
            this.slots = slots;
            this.waitingTasksPerCore = new int[slots][Core.coreCount];
            this.queues = new LinkedList[slots];
            this.onExecution = new Task[slots];
            this.executing = 0;
            this.slotsToReduce = 0;
            pendingRemovalsPerRequest = new LinkedList();
            for (int i = 0; i < slots; i++) {
                this.queues[i] = new LinkedList<Task>();
                this.onExecution[i] = null;
            }
        }

        public void clear() {
            this.executing = 0;
            this.waitingTasksPerCore = new int[slots][Core.coreCount];
            for (int i = 0; i < slots; i++) {
                this.queues[i] = new LinkedList<Task>();
                this.onExecution[i] = null;
            }
        }

        private void addSlots(int newCount) {
            int[][] waitingMethods = new int[slots + newCount][Core.coreCount];
            LinkedList<Task>[] queues = new LinkedList[slots + newCount];
            Task[] onExecution = new Task[slots + newCount];
            int i;
            for (i = 0; i < slots; i++) {
                waitingMethods[i] = this.waitingTasksPerCore[i];
                queues[i] = this.queues[i];
                onExecution[i] = this.onExecution[i];
            }
            for (; i < slots + newCount; i++) {
                waitingMethods[i] = new int[Core.coreCount];
                queues[i] = new LinkedList();
                onExecution[i] = null;
            }

            this.waitingTasksPerCore = waitingMethods;
            this.queues = queues;
            this.onExecution = onExecution;

            this.slots++;
        }

        private int deleteSlots(int delCount) {
            LinkedList<Integer> removableSlots = new LinkedList();
            if (slots == executing) {
                slotsToReduce += delCount;
                pendingRemovalsPerRequest.add(delCount);
                return delCount;
            }

            int i;
            for (i = 0; i < this.slots && removableSlots.size() < delCount; i++) {
                if (onExecution[i] == null) {
                    removableSlots.add(i);
                }
            }
            deleteSlots(removableSlots);
            slotsToReduce += delCount - removableSlots.size();
            pendingRemovalsPerRequest.add(delCount - removableSlots.size());
            return delCount - removableSlots.size();
        }

        private void deleteSlot(int slotId) {
            int[][] waitingMethods = new int[slots - 1][Core.coreCount];
            LinkedList<Task>[] queues = new LinkedList[slots - 1];
            Task[] onExecution = new Task[slots - 1];

            for (int i = 0, j = 0; i < slots; i++) {
                if (slotId == i) {
                    continue;
                }
                waitingMethods[j] = this.waitingTasksPerCore[i];
                queues[j] = this.queues[i];
                onExecution[j] = this.onExecution[i];
                j++;
            }
            if (slots - 1 > 0) {
                int i = 0;
                for (Task t : this.queues[slotId]) {
                    queues[i % queues.length].add(t);
                    waitingMethods[i % queues.length][t.getCore().getId()]++;
                }
            } else {
                for (Task t : this.queues[slotId]) {
                    String resource = ResourceManager.findResources(t.getCore().getId()).get(0);
                    nodeToSlotQueues.get(resource).waits(t);
                }
            }
            this.waitingTasksPerCore = waitingMethods;
            this.queues = queues;
            this.onExecution = onExecution;

            this.slots--;
        }

        private void deleteSlots(LinkedList<Integer> removableSlots) {
            if (removableSlots.isEmpty()) {
                return;
            }
            int removeCount = removableSlots.size();
            int[][] waitingMethods = new int[slots - removeCount][Core.coreCount];
            LinkedList<Task>[] queues = new LinkedList[slots - removeCount];
            Task[] onExecution = new Task[slots - removeCount];
            int i = 0;
            int j = 0;

            for (int removedSlot : removableSlots) {
                while (i != removedSlot) {
                    waitingMethods[j] = this.waitingTasksPerCore[i];
                    queues[j] = this.queues[i];
                    onExecution[j] = this.onExecution[i];
                    i++;
                    j++;
                }
                i++;
            }

            for (; i < slots; i++) {
                waitingMethods[j] = this.waitingTasksPerCore[i];
                queues[j] = this.queues[i];
                onExecution[j] = this.onExecution[i];
                j++;
            }

            for (int slotId : removableSlots) {
                if (slots - removeCount > 0) {
                    for (Task t : this.queues[slotId]) {
                        queues[i % queues.length].add(t);
                        waitingMethods[i % queues.length][t.getCore().getId()]++;
                    }
                } else {
                    for (Task t : this.queues[slotId]) {
                        String resource = ResourceManager.findResources(t.getCore().getId()).get(0);
                        nodeToSlotQueues.get(resource).waits(t);
                    }
                }
            }

            this.waitingTasksPerCore = waitingMethods;
            this.queues = queues;
            this.onExecution = onExecution;

            this.slots -= removeCount;
        }

        public void waits(Task t) {
            int minIndex = 0;
            long minTime = Long.MAX_VALUE;
            for (int i = 0; i < slots; i++) {
                long waitingTime = getSlotWaitingTime(i, coreAverageExecutionTime);
                if (waitingTime < minTime) {
                    minIndex = i;
                    minTime = waitingTime;
                }
            }
            queues[minIndex].add(t);
            waitingTasksPerCore[minIndex][t.getCore().getId()]++;
        }

        public void waits(Task t, int slotId) {
            queues[slotId].add(t);
            waitingTasksPerCore[slotId][t.getCore().getId()]++;
        }

        public void removeWaiting(Task t) {
            int foundIndex = 0;
            boolean found = false;
            for (int i = 0; i < slots & !found; i++) {
                if (queues[i].remove(t)) {
                    found = true;
                    foundIndex = i;
                }
            }
            waitingTasksPerCore[foundIndex][t.getCore().getId()]--;
        }

        public void executes(Task t) {
            this.executing++;
            for (int i = 0; i < slots; i++) {
                if (onExecution[i] == null) {
                    onExecution[i] = t;
                    return;
                }
            }
        }

        public void ends(Task task) {
            this.executing--;
            for (int i = 0; i < slots; i++) {
                if (onExecution[i] != null && (onExecution[i].getId()) == task.getId()) {
                    onExecution[i] = null;
                    if (slotsToReduce > 0) {
                        deleteSlot(i);
                        slotsToReduce--;
                        Integer slotCount = pendingRemovalsPerRequest.remove(0);
                        slotCount--;
                        pendingRemovalsPerRequest.addFirst(slotCount);
                    }
                }
            }
        }

        public LinkedList<Integer> getOnExecution() {
            LinkedList<Integer> ret = new LinkedList<Integer>();
            for (int i = 0; i < slots; i++) {
                if (onExecution[i] != null) {
                    ret.add(onExecution[i].getId());
                }
            }
            return ret;
        }

        public LinkedList<Task> getAllPending() {
            LinkedList<Task> ret = new LinkedList<Task>();
            for (int i = 0; i < slots; i++) {
                ret.addAll(queues[i]);
            }
            return ret;
        }

        public Task getNext() {
            PriorityQueue<Pair<Integer>> pq = new PriorityQueue<Pair<Integer>>();
            for (int i = 0; i < slots; i++) {
                long waitingTime = getSlotQueueTime(i, coreAverageExecutionTime);
                pq.offer(new Pair(i, waitingTime));
            }
            while (pq.iterator().hasNext()) {
                int index = pq.iterator().next().o;
                return queues[index].peekFirst();
            }
            return null;
        }

        public long getMinWaitTime() {
            long minTime = 0l;
            for (int i = 0; i < slots; i++) {
                long waitingTime = getSlotWaitingTime(i, coreAverageExecutionTime);
                if (minTime > waitingTime) {
                    minTime = waitingTime;
                }
            }
            return minTime;
        }

        public long getMaxWaitTime() {
            long maxTime = 0l;
            for (int i = 0; i < slots; i++) {
                long waitingTime = getSlotWaitingTime(i, coreAverageExecutionTime);
                if (maxTime < waitingTime) {
                    maxTime = waitingTime;
                }
            }
            return maxTime;
        }

        private long getSlotWaitingTime(int slotId, Long[] coreAverageExecutionTime) {

            long waitingTime = 0l;
            long now = System.currentTimeMillis();

            //No hi ha res en execució
            if (onExecution[slotId] != null) {
                //Temps de la tasca en execucio
                Task t = onExecution[slotId];
                Long coreTime = coreAverageExecutionTime[t.getCore().getId()];
                if (coreTime == null) {
                    if (firstMethodExecution[t.getCore().getId()] != null) {
                        Long elapsedTime = firstMethodExecution[t.getCore().getId()].getInitialTimeStamp();
                        if (elapsedTime != null) {
                            coreTime = now - firstMethodExecution[t.getCore().getId()].getInitialTimeStamp();
                        } else {
                            coreTime = 1l;
                        }
                    } else {
                        coreTime = 1l;
                    }
                }
                coreTime -= (now - t.getInitialTimeStamp());
                if (coreTime < 0) {
                    coreTime = 0l;
                }
                waitingTime = coreTime;
            }
            //Temps de les tasques en espera
            for (int coreIndex = 0; coreIndex < Core.coreCount; coreIndex++) {
                long meanTime = 0l;
                if (coreAverageExecutionTime[coreIndex] == null) {
                    if (firstMethodExecution[coreIndex] != null) {
                        meanTime = now - firstMethodExecution[coreIndex].getInitialTimeStamp();
                    } else {
                        meanTime = 1l;
                    }
                } else {
                    meanTime = coreAverageExecutionTime[coreIndex];
                }
                waitingTime += waitingTasksPerCore[slotId][coreIndex] * meanTime;
            }
            return waitingTime;

        }

        private long getSlotQueueTime(int slotId, Long[] coreAverageExecutionTime) {

            long waitingTime = 0l;
            long now = System.currentTimeMillis();
            //Temps de les tasques en espera
            for (int coreIndex = 0; coreIndex < Core.coreCount; coreIndex++) {
                long meanTime = 0l;
                if (coreAverageExecutionTime[coreIndex] == null) {
                    if (firstMethodExecution[coreIndex] != null) {
                        meanTime = now - firstMethodExecution[coreIndex].getInitialTimeStamp();
                    } else {
                        meanTime = 1l;
                    }
                } else {
                    meanTime = coreAverageExecutionTime[coreIndex];
                }
                waitingTime += waitingTasksPerCore[slotId][coreIndex] * meanTime;
            }
            return waitingTime;

        }

        public boolean isExecuting() {
            return executing > 0;
        }

        public void resizeDataStructures() {

            /**
             * Amount of tasks waiting on the queue per slot & core
             */
            for (int i = 0; i < waitingTasksPerCore.length; i++) {
                int[] waitingTasksPerCoreTmp = new int[Core.coreCount];
                System.arraycopy(waitingTasksPerCore[i], 0, waitingTasksPerCoreTmp, 0, waitingTasksPerCore[i].length);
                waitingTasksPerCore[i] = waitingTasksPerCoreTmp;
            }

        }

        private class Pair<T> implements Comparable<Pair> {

            T o;
            long value;

            public Pair(T o, long value) {
                this.o = o;
                this.value = value;
            }

            public int compareTo(Pair o) {
                return (int) (o.value - this.value);
            }
        }
    }
}
