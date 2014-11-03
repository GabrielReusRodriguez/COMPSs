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


package integratedtoolkit.components.scheduler.impl;

import integratedtoolkit.components.impl.TaskScheduler;
import integratedtoolkit.components.scheduler.SchedulerPolicies;
import integratedtoolkit.types.ScheduleDecisions;
import integratedtoolkit.types.ScheduleState;
import integratedtoolkit.types.Task;
import integratedtoolkit.util.QueueManager;
import integratedtoolkit.util.ResourceManager;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.TreeSet;

public class DefaultTaskScheduler extends TaskScheduler {

    // Object that stores the information about the current project
    private QueueManager queueManager;

    public DefaultTaskScheduler() {
        queueManager = new QueueManager();
        schedulerPolicies = new DefaultSchedulerPolicies();
        logger.info("Initialization finished");
    }

    public void resizeDataStructures() {
        queueManager.resizeDataStructures();
    }

    /**
     ********************************************
     *
     * Pending Work Query
     *
     ********************************************
     */
    public boolean isPendingWork(Integer coreId) {
        return (queueManager.getToRescheduleCount(coreId) + queueManager.getNoResourceCount(coreId) + queueManager.getPriorityCount(coreId) + queueManager.getOrdinaryCount(coreId)) != 0;
    }

    /**
     ********************************************
     *
     * Resource Management
     *
     ********************************************
     */
    public void newConfirmedSlots(String hostName, int slots) {
        queueManager.updateNode(hostName, slots);
        // Assign task to execute

        int alreadyAssigned = 0;
        LinkedList<Task> sortedTasks;

        //Rescheduled Tasks
        if (queueManager.areTasksToReschedule()) {
            sortedTasks = schedulerPolicies.sortTasksForResource(hostName, queueManager.getTasksToReschedule());
            for (; alreadyAssigned < slots; alreadyAssigned++) {
                Task chosenTask = sortedTasks.remove(0);
                if (chosenTask != null) {
                    // Task rescheduled
                    if (debug) {
                        logger.debug("Freed Re-Match: Task(" + chosenTask.getId() + ", "
                                + chosenTask.getCore().getName() + ") "
                                + "Resource(" + hostName + ")");
                    }

                    queueManager.rescheduledTask(chosenTask);
                    queueManager.startsExecution(chosenTask, hostName);
                    sendJobRescheduled(chosenTask, hostName);
                } else {
                    break;
                }
            }
        }

        //Priority Tasks
        if (queueManager.arePriorityTasks() && alreadyAssigned < slots) {
            sortedTasks = schedulerPolicies.sortTasksForResource(hostName, queueManager.getPriorityTasks());
            for (int i = alreadyAssigned; i < slots; i++) {
                //Task chosenTask = assignBestTaskToResource(hostName, queueManager.getPriorityTasks());
                Task chosenTask = sortedTasks.remove(0);
                if (chosenTask != null) {
                    //Task scheduled
                    if (debug) {
                        logger.debug("Freed Prioritary-Match: Task(" + chosenTask.getId() + ", "
                                + chosenTask.getCore().getName() + ") "
                                + "Resource(" + hostName + ")");
                    }
                    queueManager.priorityTaskScheduled(chosenTask);
                    queueManager.startsExecution(chosenTask, hostName);
                    sendJob(chosenTask, hostName);
                } else {
                    break;
                }
            }
        }

        //Tasks without resource
        if (queueManager.isTasksWithoutResource()) {
            sortedTasks = schedulerPolicies.sortTasksForResource(hostName, queueManager.getPendingTasksWithoutNode());
            for (int i = alreadyAssigned; i < slots; i++) {
                //Check if can execute Blocked Tasks
                LinkedList<Task> removedTasks = new LinkedList<Task>();
                for (Task t : sortedTasks) {
                    if (ResourceManager.matches(hostName, t.getCore().getId())) {
                        if (alreadyAssigned < slots) {
                            if (debug) {
                                logger.debug("Unblocked Match: Task(" + t.getId() + ", "
                                        + t.getCore().getName() + ") "
                                        + "Resource(" + hostName + ")");
                            }
                            queueManager.startsExecution(t, hostName);
                            sendJob(t, hostName);
                            alreadyAssigned++;
                        } else {
                            if (debug) {
                                logger.debug("Unblocked Pending: Task(" + t.getId() + ", "
                                        + t.getCore().getName() + ") "
                                        + "Resource(" + hostName + ")");
                            }
                            queueManager.waitOnNode(t, hostName);
                        }
                        removedTasks.add(t);
                    }
                }
                queueManager.resourceFound(removedTasks);
            }
        }

        //Take tasks assigned to the same resource
        for (int i = alreadyAssigned; i < slots; i++) {
            Task chosenTask = queueManager.getNextJob(hostName);
            if (chosenTask != null) {
                if (debug) {
                    logger.debug("Freed Match: Task(" + chosenTask.getId() + ", "
                            + chosenTask.getCore().getName() + ") "
                            + "Resource(" + hostName + ")");
                }
                queueManager.ordinaryTaskScheduled(chosenTask, hostName);
                sendJob(chosenTask, hostName);
                alreadyAssigned++;
            }
        }

        // Steal task from other resources
        if (alreadyAssigned < slots) {
            Task[] stolenTasks = stealTaskFromOtherResourcesToExecute(hostName, slots - alreadyAssigned);
            for (int i = 0; i < stolenTasks.length; i++) {
                if (debug && stolenTasks[i] != null) {
                    logger.debug("Moved Match: Task(" + stolenTasks[i].getId() + ", "
                            + stolenTasks[i].getCore().getName() + ") "
                            + "Resource(" + hostName + ")");
                }
                queueManager.ordinaryTaskScheduled(stolenTasks[i], hostName);
                sendJob(stolenTasks[i], hostName);
                alreadyAssigned++;
            }

        }

        if (monitorDebug) {
            monitor.debug(queueManager.describeCurrentState());
        }
    }

    public void removeSlotsFromNode(String hostName, int slots) {
        queueManager.updateNode(hostName, -slots);
    }

    public void removeNode(String hostName) {
        queueManager.removeNode(hostName);
    }

    public boolean performModification(String resource) {
        return queueManager.performModification(resource);
    }

    public boolean canResourceCompute(String resource) {
        return queueManager.getSafeSlotsCount(resource) > 0;
    }

    /**
     ********************************************
     *
     * Task Scheduling
     *
     ********************************************
     */
    public void scheduleTask(Task currentTask) {
        String chosenResource = "";
        //ADDED because fails when service with state
        currentTask.unforceScheduling();

        if (currentTask.isSchedulingForced()) {
            //Task is forced to run in a given resource
            TreeSet<String> hosts = FTM.getHosts(currentTask.getEnforcingData());

            chosenResource = hosts.first();

            if (ResourceManager.hasFreeSlots(chosenResource, presched)) {
                if (debug) {
                    logger.debug("Match: Task(" + currentTask.getId() + ", "
                            + currentTask.getCore().getName() + ") "
                            + "Resource(" + chosenResource + ")");
                }
                // Request the creation of a job for the task
                queueManager.startsExecution(currentTask, chosenResource);
                sendJob(currentTask, chosenResource);
            } else {
                queueManager.waitOnNode(currentTask, chosenResource);
                if (debug) {
                    logger.debug("Pending: Task(" + currentTask.getId() + ", "
                            + currentTask.getCore().getName() + ") "
                            + "Resource(" + chosenResource + ")");
                }
            }
        } else {
            // Schedule task
            List<String> validResources = ResourceManager.findResources(currentTask.getCore().getId());
            if (validResources.isEmpty()) {
                //There's no point on getting scores, any existing machines can run this task <- score=0
                queueManager.waitWithoutNode(currentTask);
                if (debug) {
                    logger.debug("Blocked: Task(" + currentTask.getId() + ", "
                            + currentTask.getCore().getName() + ") ");
                }
            } else {
                // Try to assign task to available resources
                List<String> resources = ResourceManager.findResources(currentTask.getCore().getId(), presched);
                if (debug) {
                    StringBuilder sb = new StringBuilder("Available suitable resources for task ");
                    sb.append(currentTask.getId()).append(", ").append(currentTask.getCore().getName()).append(":");
                    for (String s : resources) {
                        sb.append(" ").append(s);
                    }
                    logger.debug(sb);
                }

                if (!resources.isEmpty()) {
                    resources = schedulerPolicies.sortResourcesForTask(currentTask, resources);
                    chosenResource = resources.get(0);
                    if (debug) {
                        logger.debug("Match: Task(" + currentTask.getId() + ", "
                                + currentTask.getCore().getName() + ") "
                                + "Resource(" + chosenResource + ")");
                    }
                    // Request the creation of a job for the task
                    queueManager.startsExecution(currentTask, chosenResource);
                    sendJob(currentTask, chosenResource);
                } else {
                    if (currentTask.hasPriority()) {
                        queueManager.newPriorityTask(currentTask);
                    } else {
                        chosenResource = queueManager.getMinQueueResource(validResources);
                        queueManager.waitOnNode(currentTask, chosenResource);
                    }
                    if (debug) {
                        logger.debug("Pending: Task(" + currentTask.getId() + ", "
                                + currentTask.getCore().getName() + ") "
                                + "Resource(" + chosenResource + ")");
                    }
                }

                if (monitorDebug) {
                    monitor.debug(queueManager.describeCurrentState());
                }
            }
        }
    }

    public void rescheduleTask(Task task, String failedResource) {
        //Rescheduling the failed Task
        // Find available resources that match user constraints for this task
        List<String> resources = ResourceManager.findResources(task.getCore().getId(), presched);

        /* Get the host where the task failed and remove it from the list
         * so that it will not be chosen again
         */
        // Notify the job end to free the resource, in case another task can be scheduled in it
        resources.remove(failedResource);
        // Reschedule task
        if (!resources.isEmpty()) {
            resources = schedulerPolicies.sortResourcesForTask(task, resources);
            String newResource = resources.get(0);
            if (newResource == null) {
                return;
            }
            if (debug) {
                logger.debug("Re-Match: Task(" + task.getId() + ", "
                        + task.getCore().getName() + ") "
                        + "Resource(" + newResource + ")");
            }

            // Request the creation of a job for the task
            queueManager.startsExecution(task, newResource);
            sendJobRescheduled(task, newResource);
        } else {
            queueManager.newTaskToReschedule(task);
            if (debug) {
                logger.debug("To Reschedule: Task(" + task.getId() + ", "
                        + task.getCore().getName() + ") ");
            }
        }

    }

    public void taskEnd(Task task) {
        // Obtain freed resource
        String hostName = task.getExecParams().getHost();
        switch (task.getStatus()) {
            case FINISHED:
                queueManager.endsExecution(task, hostName);
                break;
            case TO_RESCHEDULE:
                queueManager.cancelExecution(task, hostName);
                break;
            case FAILED:
                queueManager.cancelExecution(task, hostName);
                break;
            default: //This Task should not be here
                logger.fatal("INVALID KIND OF TASK ENDED: " + task.getStatus());
                System.exit(1);
                break;
        }
    }

    public boolean scheduleToResource(String hostName) {
        LinkedList<Task> sortedTasks;
        Task chosenTask = null;
        // First check if there is some task to reschedule
        if (queueManager.areTasksToReschedule()) {
            sortedTasks = schedulerPolicies.sortTasksForResource(hostName, queueManager.getTasksToReschedule());
            chosenTask = sortedTasks.peekFirst();
            if (chosenTask != null) {
                // Task rescheduled
                if (debug) {
                    logger.debug("Freed Re-Match: Task(" + chosenTask.getId() + ", "
                            + chosenTask.getCore().getName() + ") "
                            + "Resource(" + hostName + ")");
                }
                queueManager.rescheduledTask(chosenTask);
                queueManager.startsExecution(chosenTask, hostName);
                sendJobRescheduled(chosenTask, hostName);
                return true;
            }
        }

        // Now assign, if possible, one of the pending tasks to the resource
        if (queueManager.arePriorityTasks()) {
            sortedTasks = schedulerPolicies.sortTasksForResource(hostName, queueManager.getPriorityTasks());
            chosenTask = sortedTasks.peekFirst();
            if (chosenTask != null) {
                //Task scheduled
                if (debug) {
                    logger.debug("Freed Prioritary-Match: Task(" + chosenTask.getId() + ", "
                            + chosenTask.getCore().getName() + ") "
                            + "Resource(" + hostName + ")");
                }
                queueManager.priorityTaskScheduled(chosenTask);
            }
        }

        //If no priority task found, get an ordinary one from the same resource
        if (chosenTask == null) {
            chosenTask = queueManager.getNextJob(hostName);
            if (chosenTask != null) {
                if (debug) {
                    logger.debug("Freed Match: Task(" + chosenTask.getId() + ", "
                            + chosenTask.getCore().getName() + ") "
                            + "Resource(" + hostName + ")");
                }
                queueManager.ordinaryTaskScheduled(chosenTask, hostName);
            }
        }

        if (chosenTask == null) {
            Task[] stolenTasks = stealTaskFromOtherResourcesToExecute(hostName, 1);
            if (stolenTasks[0] != null) {
                chosenTask = stolenTasks[0];
                if (debug) {
                    logger.debug("Moved Match: Task(" + chosenTask.getId() + ", "
                            + chosenTask.getCore().getName() + ") "
                            + "Resource(" + hostName + ")");
                }
                queueManager.ordinaryTaskScheduled(chosenTask, hostName);
            }
        }

        if (chosenTask != null) {
            queueManager.startsExecution(chosenTask, hostName);
            sendJob(chosenTask, hostName);
            return true;
        } else {
            if (debug) {
                logger.debug("Resource " + hostName + " FREE");
            }
        }
        return false;
    }

    /**
     ********************************************
     *
     * Scheduling state operations
     *
     ********************************************
     */
    public ScheduleState getSchedulingState() {
        try {
            ScheduleState ss = queueManager.getCurrentState();
            ss.useCloud = ResourceManager.useCloud();
            if (ss.useCloud) {
                ss.creationTime = ResourceManager.getCreationTime();
            }
            return ss;
        } catch (Exception e) {
            logger.fatal("Can not get the current schedule", e);
            System.exit(1);
        }
        return null;
    }

    public void setSchedulingState(ScheduleDecisions newState) {
        try {
            LinkedList<Object[]> movements = newState.getMovements();
            if (!movements.isEmpty()) {
                for (Object[] movement : movements) {
                    String sourceHost = (String) movement[0];
                    String targetHost = (String) movement[1];
                    int sourceSlot = (Integer) movement[2];
                    int targetSlot = (Integer) movement[3];
                    int coreId = (Integer) movement[4];
                    int amount = (Integer) movement[5];
                    queueManager.seekAndMove(sourceHost, targetHost, sourceSlot, targetSlot, coreId, amount);
                    if (!queueManager.isExecuting(targetHost, targetSlot)) {
                        Task[] chosenTask = new Task[1];
                        // Now assign, if possible, one of the pending tasks to the resource
                        chosenTask[0] = queueManager.getNextJobSlot(targetHost, targetSlot);
                        if (chosenTask[0] != null) {
                            if (debug) {
                                logger.debug("Freed Match: Task(" + chosenTask[0].getId() + ", "
                                        + chosenTask[0].getCore().getName() + ") "
                                        + "Resource(" + targetHost + ")");
                            }
                            sendJob(chosenTask[0], targetHost);
                        }
                    }
                }
            }
            logger.debug(queueManager.describeCurrentState());
        } catch (Exception e) {
            logger.error("CAN NOT UPDATE THE CURRENT STATE", e);
        }

    }

    public String getMonitoringState() {
        StringBuilder sb = new StringBuilder();
        sb.append(queueManager.getMonitoringInfo());
        return sb.toString();
    }

    // Private methods
    // Schedule decision - number of files
    private Task[] stealTaskFromOtherResourcesToExecute(String resourceName, int numberOfTasks) {
        TreeSet<String> compatibleResources = new TreeSet<String>();
        List<Integer> executableMethods = ResourceManager.getExecutableCores(resourceName);
        for (Integer methodId : executableMethods) {
            compatibleResources.addAll(ResourceManager.findResources(methodId));
        }
        compatibleResources.remove(resourceName);
        HashMap<String, LinkedList<Task>> pendingTasks = new HashMap<String, LinkedList<Task>>();
        for (String ownerName : compatibleResources) {
            pendingTasks.put(ownerName, queueManager.getPendingTasks(ownerName));
        }
        SchedulerPolicies.OwnerTask[] stolenPairs = schedulerPolicies.stealTasks(resourceName, pendingTasks, numberOfTasks);

        Task[] stolenTasks = new Task[numberOfTasks];
        for (int i = 0; i < numberOfTasks; i++) {
            if (stolenPairs[i] != null) {
                stolenTasks[i] = stolenPairs[i].t;
                queueManager.migrateTask(stolenPairs[i].t, stolenPairs[i].owner, resourceName);
            }
        }
        return stolenTasks;
    }
}
