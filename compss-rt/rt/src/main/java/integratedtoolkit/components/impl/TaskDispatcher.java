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


package integratedtoolkit.components.impl;

import integratedtoolkit.ITConstants;
import integratedtoolkit.components.scheduler.impl.DefaultTaskScheduler;
import integratedtoolkit.components.JobStatus;
import integratedtoolkit.components.Schedule;
import integratedtoolkit.connectors.utils.CreationThread;
import integratedtoolkit.log.Loggers;
import integratedtoolkit.types.Core;
import integratedtoolkit.types.ResourceDescription;
import integratedtoolkit.types.ResourceRequest;
import integratedtoolkit.types.ScheduleDecisions;
import integratedtoolkit.types.ScheduleState;
import integratedtoolkit.types.Task;
import integratedtoolkit.types.Task.TaskState;
import integratedtoolkit.types.data.DataAccessId;
import integratedtoolkit.types.data.DataInstanceId;
import integratedtoolkit.types.data.Location;
import integratedtoolkit.types.data.ResultFile;
import integratedtoolkit.types.request.td.*;
import integratedtoolkit.util.ConstraintManager;
import integratedtoolkit.util.ProjectManager;
import integratedtoolkit.util.ResourceManager;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import org.apache.log4j.Logger;

public class TaskDispatcher implements Runnable, Schedule, JobStatus {

    // Other supercomponent
    protected TaskProcessor TP;
    // Subcomponents
    protected TaskScheduler TS;
    protected JobManager JM;
    protected FileTransferManager FTM;
    protected SchedulingOptimizer SO;
    // Queue that can contain ready, finished or to-reschedule tasks
    protected LinkedBlockingQueue<TDRequest> requestQueue;
    protected LinkedBlockingQueue<TDRequest> readQueue;
    protected LinkedBlockingQueue<TDRequest> prioritaryTaskQueue;
    // Scheduler thread
    protected Thread dispatcher;
    protected boolean keepGoing;
    //Hostname -> Pending Modifications
    private HashMap<String, LinkedList<ResourceDescription>> host2PendingModifications;
    //End of Execution
    private boolean endRequested;
    //Number of Tasks to execute
    private HashMap<Integer, Integer> taskCountToEnd;
    // Logging
    protected static final Logger logger = Logger.getLogger(Loggers.TD_COMP);
    protected static final boolean debug = logger.isDebugEnabled();
    // Component logger - No need to configure, ProActive does
    protected static final Logger monitor = Logger.getLogger(Loggers.RESOURCES);
    protected static final boolean monitorDebug = monitor.isDebugEnabled();

    private static final String PROJ_LOAD_ERR = "Error loading project information";
    private static final String RES_LOAD_ERR = "Error loading resource information";
    private static final String CREAT_INIT_VM_ERR = "Error creating initial VMs";
    private static final String DEL_VM_ERR = "Error deleting VMs";

    private static final boolean presched = System.getProperty(ITConstants.IT_PRESCHED) != null
            && System.getProperty(ITConstants.IT_PRESCHED).equals("true")
            ? true : false;

    public TaskDispatcher() {
        endRequested = false;
        taskCountToEnd = new HashMap<Integer, Integer>();
        host2PendingModifications = new HashMap<String, LinkedList<ResourceDescription>>();

        ConstraintManager.load();
        if (!ProjectManager.isInit()) {
            try {
                ProjectManager.init();
            } catch (Exception e) {
                logger.fatal(PROJ_LOAD_ERR, e);
                System.exit(1);
            }
        }
        
        try {
            ResourceManager.load();
        } catch (ClassNotFoundException e) {
            logger.fatal(CREAT_INIT_VM_ERR, e);
            System.exit(1);
        } catch (Throwable e) {
            logger.fatal(RES_LOAD_ERR, e);
            System.exit(1);
        }
        try {
            
            String schedulerPath = System.getProperty(ITConstants.IT_SCHEDULER);
            if (schedulerPath == null || schedulerPath.compareTo("default") == 0) {
                TS = new DefaultTaskScheduler();
            } else {
                Class<?> conClass = Class.forName(schedulerPath);
                Constructor<?> ctor = conClass.getDeclaredConstructors()[0];
                TS = (TaskScheduler) ctor.newInstance();
            }

        } catch (Exception e) {
            logger.fatal(CREAT_INIT_VM_ERR, e);
            System.exit(1);
        }
        JM = new JobManager();
        FTM = new FileTransferManager();
        SO = new SchedulingOptimizer();

        JM.setServiceInstances(ResourceManager.getServices());

        requestQueue = new LinkedBlockingQueue<TDRequest>();
        readQueue = requestQueue;
        prioritaryTaskQueue = new LinkedBlockingQueue<TDRequest>();

        keepGoing = true;
        dispatcher = new Thread(this);
        dispatcher.setName("Task Dispatcher");
        dispatcher.start();

        Runtime.getRuntime().addShutdownHook(new Ender());

        logger.info("Initialization finished");
    }

    public void setTP(TaskProcessor TP) {
        this.TP = TP;
        CreationThread.setTaskDispatcher(this);
        TS.setCoWorkers(JM, FTM);
        JM.setCoWorkers(TP, this, FTM);
        FTM.setCoWorkers(this, JM);
        SO.setCoWorkers(this);
        SO.start();
        if (ResourceManager.useCloud()) {
            //Creates the VM needed for execute the methods
            int alreadyCreated = ResourceManager.addBasicNodes();
            monitor.info(alreadyCreated + " resources have been requested to the Cloud in order to fulfill all core constraints");
            //Distributes the rest of the VM
            ResourceManager.addExtraNodes(alreadyCreated);
        }
        ResourceManager.printCoresResourcesLinks();
    }

    public void cleanup() {
        if (ResourceManager.useCloud()) {
            // Stop all Cloud VM
            try {
                ResourceManager.stopVirtualNodes();
            } catch (Exception e) {
                logger.error(ITConstants.TS + ": " + DEL_VM_ERR, e);
            }
        }
        FTM.cleanup();
        JM.cleanup();
        SO.kill();

        keepGoing = false;
        dispatcher.interrupt();
    }

    // Dispatcher thread
    public void run() {
        while (keepGoing) {

            TDRequest request = null;
            try {
                request = readQueue.take();
            } catch (InterruptedException e) {
                continue;
            }
            dispatchRequest(request);
        }
    }

    private void addRequest(TDRequest request) {
        requestQueue.offer(request);
    }

    private void addPrioritaryRequest(TDRequest request) {
        prioritaryTaskQueue.offer(request);
        readQueue = prioritaryTaskQueue;
        dispatcher.interrupt();
        while (prioritaryTaskQueue.size() > 0) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
            }
        }
        readQueue = requestQueue;
        dispatcher.interrupt();
    }

    protected void dispatchRequest(TDRequest request) {
        Task task;
        int coreId;
        int taskId;
        String resource;
        switch (request.getRequestType()) {
            case UPDATE_LOCAL_CEI:
                UpdateLocalCEIRequest uCEIReq = (UpdateLocalCEIRequest) request;
                logger.info("Treating request to update core elements");
                LinkedList<Integer> newCores = ConstraintManager.loadJava(uCEIReq.getCeiClass());
                logger.debug("New methods: " + newCores);
                ResourceManager.resizeDataStructures();
                TS.resizeDataStructures();
                SO.resizeDataStructures();
                logger.debug("Data structures resized");
                for (int core : newCores) {
                    logger.debug("Linking core " + core);
                    ResourceManager.linkCoreToMachines(core);
                }
                uCEIReq.getSemaphore().release();
                break;
            case SCHEDULE_TASKS:
                ScheduleTasksRequest stRequest = (ScheduleTasksRequest) request;
                List<Task> toSchedule = stRequest.getToSchedule();
                LinkedList<String> obsoletes = stRequest.getObsoletes();
                if (obsoletes != null) {
                    FTM.obsoleteVersions(obsoletes);
                }
                SO.updateWaitingCounts(toSchedule, stRequest.getWaiting(), stRequest.getWaitingCount());
                for (Task currentTask : toSchedule) {
                    currentTask.setStatus(TaskState.TO_SCHEDULE);
                    TS.scheduleTask(currentTask);
                }
                break;
            case FINISHED_TASK:
                NotifyTaskEndRequest nte = (NotifyTaskEndRequest) request;
                task = nte.getTask();
                coreId = task.getCore().getId();
                Integer coreCount = taskCountToEnd.get(coreId);
                if (coreCount != null) {
                    taskCountToEnd.put(coreId, coreCount - 1);
                }
                TS.notifyTaskEnd(task);

                resource = task.getExecParams().getHost();
                if (!checkResourcePendingModifications(resource)) {
                    if (!TS.scheduleToResource(resource)) {
                        mayTerminateEnvironment(resource);
                    }
                }
                break;
            case RESCHEDULE_TASK:
                // Get the corresponding task to reschedule
                task = ((RescheduleTaskRequest) request).getTask();
                taskId = task.getId();
                resource = task.getExecParams().getHost();
                logger.debug("Reschedule: Task " + taskId + " failed to run in " + resource);
                //register task execution end 
                TS.notifyTaskEnd(task);
                if (!checkResourcePendingModifications(resource)) {
                    //schedule another task in the failed resource
                    if (!TS.scheduleToResource(resource)) {
                        mayTerminateEnvironment(resource);
                    }
                }
                TS.rescheduleTask(task, resource);
                break;
            case NEW_WAITING_TASK:
                NewWaitingTaskRequest nwtRequest = (NewWaitingTaskRequest) request;
                obsoletes = nwtRequest.getObsoletes();
                if (obsoletes != null) {
                    FTM.obsoleteVersions(obsoletes);
                }
                SO.newWaitingTask(nwtRequest.getMethodId());
                break;
            case NEW_DATA_VERSION:
                NewDataVersionRequest ndvRequest = (NewDataVersionRequest) request;
                FTM.newDataVersion(ndvRequest.getLastDID().getRenaming(), ndvRequest.getFileName(), ndvRequest.getLocation());
                break;
            case TRANSFER_OPEN_FILE:
                TransferOpenFileRequest tofRequest = (TransferOpenFileRequest) request;
                FTM.transferFileForOpen(tofRequest.getFaId(), tofRequest.getLocation(), tofRequest.getSemaphore());
                break;
            case TRANSFER_RAW_FILE:
                TransferRawFileRequest trfRequest = (TransferRawFileRequest) request;
                FTM.transferFileRaw(trfRequest.getFaId(), trfRequest.getLocation(), trfRequest.getSemaphore());
                break;
            case TRANSFER_OBJECT:
                FTM.transferObjectValue((TransferObjectRequest) request);
                break;
            case TRANSFER_RESULT_FILES:
                TransferResultFilesRequest tresfRequest = (TransferResultFilesRequest) request;
                FTM.transferBackResultFiles(tresfRequest.getResFiles(), tresfRequest.getSemaphore());
                break;
            case TRANSFER_TRACE_FILES:
                TransferTraceFilesRequest ttracefRequest = (TransferTraceFilesRequest) request;
                FTM.transferTraceFiles(ttracefRequest.getLocation(), ttracefRequest.getSemaphore());
                break;
            case DELETE_INTERMEDIATE_FILES:
                FTM.deleteIntermediateFiles(((DeleteIntermediateFilesRequest) request).getSemaphore());
                break;
            case GET_STATE:
                ScheduleState state = TS.getSchedulingState();
                ((GetCurrentScheduleRequest) request).setResponse(state);
                ((GetCurrentScheduleRequest) request).getSemaphore().release();
                break;
            case SET_STATE:
                ScheduleDecisions decisions = ((SetNewScheduleRequest) request).getNewState();
                TS.setSchedulingState(decisions);
                applyResourceChanges(decisions);
                break;
            case ADD_CLOUD:
                AddCloudNodeRequest acnRequest = (AddCloudNodeRequest) request;
                ResourceRequest rr = acnRequest.getResourceRequest();
                List<Integer> methodIds = ConstraintManager.findExecutableCores(rr.getGranted());
                addCloudNode(acnRequest.getName(), rr.getRequestedTaskCount(), rr.getRequestedMethodIds(), methodIds, rr, acnRequest.getCheck(), acnRequest.getProvider(), acnRequest.getLimitOfTasks());
                if (presched) {
                    TS.newConfirmedSlots(acnRequest.getName(), 2 * acnRequest.getLimitOfTasks());
                } else {
                    TS.newConfirmedSlots(acnRequest.getName(), acnRequest.getLimitOfTasks());
                }
                SO.optimizeNow();
                break;
            case REMOVE_OBSOLETES:
                RemoveObsoletesRequest ror = (RemoveObsoletesRequest) request;
                obsoletes = ror.getObsoletes();
                if (obsoletes != null) {
                    FTM.obsoleteVersions(obsoletes);
                }
                break;
            case REFUSE_CLOUD:
                RefuseCloudWorkerRequest rcwRequest = (RefuseCloudWorkerRequest) request;
                refuseCloudWorker(rcwRequest.getRequestedTaskCount(), rcwRequest.getRequestedConstraints(), rcwRequest.getProvider());
                break;
            case MONITOR_DATA:
                String monitorData = TS.getMonitoringState();
                ((MonitoringDataRequest) request).setResponse(monitorData);
                ((MonitoringDataRequest) request).getSemaphore().release();
                break;
            case SHUTDOWN:
                ShutdownRequest sRequest = (ShutdownRequest) request;
                obsoletes = sRequest.getObsoletes();
                if (obsoletes != null) {
                    FTM.obsoleteVersions(obsoletes);
                }
                taskCountToEnd = sRequest.getCurrentTaskCount();
                SO.cleanUp();
                this.endRequested = true;
                if (ResourceManager.useCloud()) {
                    List<String> toDelete = ResourceManager.terminateUnbounded(taskCountToEnd);
                    for (String resourceDel : toDelete) {
                        TS.removeNode(resourceDel);
                        FTM.transferStopFiles(resourceDel, ResourceManager.getBestSafeResourcePerCore());
                    }
                }
                FTM.waitForTransfers(sRequest.getSemaphore());
                break;
        }
    }

    private void addCloudNode(String name, int requestedTaskCount, List<Integer> oldMethodIds, List<Integer> methodIds, ResourceRequest rr, boolean check, String provider, int limitOfTasks) {
        if (check) {
            boolean moreJobs = false;
            for (Integer methodId : methodIds) {
                Integer stats = taskCountToEnd.get(methodId);
                boolean pendingGraph = (stats != null && stats != 0);
                moreJobs = pendingGraph || TS.isPendingWork(methodId);
                if (moreJobs) {
                    break;
                }
            }
            if (!moreJobs) {
                if (debug) {
                    logger.debug("There are no more Tasks for resource " + name);
                }
                ResourceManager.terminate(rr.getGranted());
                refuseCloudWorker(rr.getRequestedTaskCount(), rr.getGranted(), provider);
                return;
            }
        }
        ResourceManager.updateNode(name, oldMethodIds, requestedTaskCount, limitOfTasks, methodIds, rr.getGranted());
    }

    private void refuseCloudWorker(int oldTaskCount, ResourceDescription gR, String provider) {
        List<Integer> methodsId = ConstraintManager.findExecutableCores(gR);
        ResourceManager.refuseCloudWorker(oldTaskCount, methodsId, gR, provider);
    }

    private void applyResourceChanges(ScheduleDecisions newState) {
        try {
            if (ResourceManager.useCloud()) {
                if (newState.mandatory.size() != 0) {
                    ResourceManager.increaseResources(newState.mandatory);
                    return;
                }

                if (!newState.extra.isEmpty()) {
                    ResourceManager.increaseResources(newState.extra);
                    return;
                }

                if (!newState.terminate.isEmpty()) {
                    float[] slotsToReducePerCore = new float[Core.coreCount];
                    if (presched) {
                        for (java.util.Map.Entry<Integer, Float> e : newState.terminate.entrySet()) {
                            slotsToReducePerCore[e.getKey()] = e.getValue() / 2;
                        }
                    } else {
                        for (java.util.Map.Entry<Integer, Float> e : newState.terminate.entrySet()) {
                            slotsToReducePerCore[e.getKey()] = e.getValue();
                        }
                    }
                    ResourceDescription rd;
                    if (newState.forcedToDestroy) {
                        rd = ResourceManager.chooseDestruction(slotsToReducePerCore);
                    } else {
                        rd = ResourceManager.checkDestruction(slotsToReducePerCore);
                    }
                    if (rd != null) {
                        //We have decided to remove some resource
                        String resource = rd.getName();
                        //Store pending modification
                        LinkedList<ResourceDescription> list = host2PendingModifications.get(resource);
                        if (list == null) {
                            list = new LinkedList<ResourceDescription>();
                            host2PendingModifications.put(resource, list);
                        }
                        list.add(rd);

                        //Remove slots from ResourceManager and update queues
                        if (presched) {
                            ResourceManager.updateNode(resource, null, 0, -2 * rd.getProcessorCPUCount(), ResourceManager.getExecutableCores(resource), rd);
                            TS.removeSlotsFromNode(resource, 2 * rd.getProcessorCPUCount());
                        } else {
                            ResourceManager.updateNode(resource, null, 0, -rd.getProcessorCPUCount(), ResourceManager.getExecutableCores(resource), rd);
                            TS.removeSlotsFromNode(resource, rd.getProcessorCPUCount());
                        }
                        checkResourcePendingModifications(resource);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("CAN NOT UPDATE THE CURRENT STATE", e);
        }
    }

    private void mayTerminateEnvironment(String hostName) {
        //Wait unil we have created all tasks
        if (endRequested) {
            //intentar eliminar la màquina
            if (ResourceManager.tryToTerminate(hostName, taskCountToEnd)) {
                TS.removeNode(hostName);
                //TODO: afegir la gestio de la cua
                FTM.transferStopFiles(hostName, ResourceManager.getBestSafeResourcePerCore());
            }
        }
    }

    public void safeResourceEnd(String resource) {
        ResourceManager.terminate(resource);
    }

    private boolean checkResourcePendingModifications(String resource) {
        LinkedList<ResourceDescription> modifications = host2PendingModifications.get(resource);
        if (modifications != null && !modifications.isEmpty()) { //if there are pending modifications
            ResourceDescription modification = modifications.get(0);
            if (TS.performModification(resource)) {  //if there are enough free slots to perform the modification
                if (TS.canResourceCompute(resource)) { // if the machine will be alive after performing the modification
                    //perform modification
                    ResourceManager.terminate(modification);
                    return true;
                } else { // the resource will be unaccessible after performing the modification
                    //Remove the resource from the TS and save unique data hosted by that resource
                    TS.removeNode(resource);
                    monitor.info("--------Saving all unique data before powering off " + resource);
                    FTM.transferStopFiles(resource, ResourceManager.getBestSafeResourcePerCore());
                }
                //Remove the pending modification 
                modifications.remove();
                if (modifications.isEmpty()) {
                    host2PendingModifications.remove(resource);
                }
            } else {
                monitor.info("--------" + resource + " can't be powered off because it is still executing some core elements");
            }
        }
        return false;
    }

    /**
     * ************************************************************
     *
     *
     ********* Public methods to enqueue requests *************
     *
     *
     **************************************************************
     */
    // TP (TA)
    public void scheduleTasks(List<Task> toSchedule, boolean waiting, int[] waitingCount, LinkedList<String> obsoletes) {
        if (debug) {
            StringBuilder sb = new StringBuilder("Schedule tasks: ");
            for (Task t : toSchedule) {
                sb.append(t.getCore().getName()).append("(").append(t.getId()).append(") ");
            }
            logger.debug(sb);
        }
        addRequest(new ScheduleTasksRequest(toSchedule, waiting, waitingCount, obsoletes));
    }

    // Notification thread (JM)
    public void notifyJobEnd(Task task) {
        addRequest(new NotifyTaskEndRequest(task));
    }

    // Notification thread (JM) / Transfer threads (FTM)
    public void rescheduleJob(Task task) {
        task.setStatus(TaskState.TO_RESCHEDULE);
        addRequest(new RescheduleTaskRequest(task));
    }

    // TP (TA)
    public void newWaitingTask(int methodId, LinkedList<String> obsoletes) {
        addRequest(new NewWaitingTaskRequest(methodId, obsoletes));
    }

    // TP (DIP)
    public void newDataVersion(DataInstanceId lastDID, String fileName, Location location) {
        addRequest(new NewDataVersionRequest(lastDID, fileName, location));
    }

    // App
    public void transferFileForOpen(DataAccessId faId, Location location) {
        Semaphore sem = new Semaphore(0);
        addRequest(new TransferOpenFileRequest(faId, location, sem));

        try {
            sem.acquire();
        } catch (InterruptedException e) {
        }

        if (debug) {
            logger.debug("File for open transferred");
        }
    }

    // App
    public void transferFileRaw(DataAccessId faId, Location location) {
        Semaphore sem = new Semaphore(0);
        TransferRawFileRequest request = new TransferRawFileRequest(faId, location, sem);
        addRequest(request);

        try {
            sem.acquire();
        } catch (InterruptedException e) {
        }

        if (debug) {
            logger.debug("Raw file transferred");
        }
    }

    // App
    public Object transferObject(DataAccessId daId, String path, String host, String wRename) {
        Semaphore sem = new Semaphore(0);
        TransferObjectRequest request = new TransferObjectRequest(daId, path, host, sem);
        addRequest(request);

        try {
            sem.acquire();
        } catch (InterruptedException e) {
        }

        if (debug) {
            logger.debug("Object transferred");
        }

        return request.getResponse();
    }

    // App and TP (TA)
    public void transferBackResultFiles(List<ResultFile> resFiles, boolean wait) {
        Semaphore sem = new Semaphore(0);
        TransferResultFilesRequest request = new TransferResultFilesRequest(resFiles, sem);
        addRequest(request);

        if (wait) {
            try {
                sem.acquire();
            } catch (InterruptedException e) {
            }

            if (debug) {
                logger.debug("Result files transferred");
            }
        }
    }

    // App
    public void transferTraceFiles(Location loc) {
        Semaphore sem = new Semaphore(0);
        TransferTraceFilesRequest request = new TransferTraceFilesRequest(loc, sem);
        addRequest(request);

        try {
            sem.acquire();
        } catch (InterruptedException e) {
        }

        if (debug) {
            logger.debug("Trace files transferred");
        }
    }

    // App
    public void deleteIntermediateFiles() {
        Semaphore sem = new Semaphore(0);
        DeleteIntermediateFilesRequest request = new DeleteIntermediateFilesRequest(sem);
        addRequest(request);

        try {
            sem.acquire();
        } catch (InterruptedException e) {
        }

        if (debug) {
            logger.debug("Intermediate files deleted");
        }
    }

    // Scheduling optimizer thread
    public ScheduleState getCurrentSchedule() {
        Semaphore sem = new Semaphore(0);
        GetCurrentScheduleRequest request = new GetCurrentScheduleRequest(sem);
        addPrioritaryRequest(request);
        try {
            sem.acquire();
        } catch (InterruptedException e) {
        }

        return request.getResponse();
    }

    // Scheduling optimizer thread
    public void setNewSchedule(ScheduleDecisions newSchedule) {
        SetNewScheduleRequest request = new SetNewScheduleRequest(newSchedule);
        addPrioritaryRequest(request);
    }

    // Creation thread
    public void addCloudNode(String ip, ResourceRequest rr, String provider, int limitOfTasks, boolean check) {
        logger.info("Adding to priority queue");
        AddCloudNodeRequest request = new AddCloudNodeRequest(ip, provider, rr, limitOfTasks, check);
        addPrioritaryRequest(request);
        logger.info("Added to priority queue");
    }

    // Creation thread
    public void refuseCloudWorkerRequest(int requestedTaskCount, ResourceDescription requestedConstraints, String provider) {
        RefuseCloudWorkerRequest request = new RefuseCloudWorkerRequest(requestedTaskCount, requestedConstraints, provider);
        addPrioritaryRequest(request);
    }

    // TP (TA)
    public void shutdown(HashMap<Integer, Integer> currentTaskCount, LinkedList<String> obsoletes) {
        Semaphore sem = new Semaphore(0);
        ShutdownRequest request = new ShutdownRequest(currentTaskCount, obsoletes, sem);
        addRequest(request);
        try {
            sem.acquire();
        } catch (InterruptedException e) {
        }
    }

    public void removeObsoletes(LinkedList<String> obsoleteRenamings) {
        if (obsoleteRenamings != null) {
            addRequest(new RemoveObsoletesRequest(obsoleteRenamings));
        }
    }

    /**
     * Returs a string with the description of the tasks in the graph
     *
     * @return description of the current tasks in the graph
     */
    public String getCurrentMonitoringData() {
        Semaphore sem = new Semaphore(0);
        MonitoringDataRequest request = new MonitoringDataRequest(sem);
        requestQueue.offer(request);
        try {
            sem.acquire();
        } catch (InterruptedException e) {
        }
        return (String) request.getResponse();
    }

    class Ender extends Thread {

        public void run() {
            if (ResourceManager.useCloud()) {
                // Stop all Cloud VM
                try {
                    ResourceManager.stopVirtualNodes();
                } catch (Exception e) {
                    logger.error(ITConstants.TS + ": " + DEL_VM_ERR, e);
                }
            }
            try {

                SO.kill();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void addInterface(Class<?> forName) {
        if (debug) {
            logger.debug("Updating CEI " + forName.getName());
        }
        Semaphore sem = new Semaphore(0);
        addRequest(new UpdateLocalCEIRequest(forName, sem));

        try {
            sem.acquire();
        } catch (InterruptedException e) {
        }

        if (debug) {
            logger.debug("Updated CEI " + forName.getName());
        }
    }
}
