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

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.log4j.Logger;

import integratedtoolkit.ITConstants;
import integratedtoolkit.api.ITExecution.ParamType;
import integratedtoolkit.components.DataAccess.AccessMode;
import integratedtoolkit.log.Loggers;
import integratedtoolkit.types.Core;
import integratedtoolkit.types.Method;
import integratedtoolkit.types.Task;
import integratedtoolkit.types.Task.TaskState;
import integratedtoolkit.types.data.AccessParams;
import integratedtoolkit.types.data.DataAccessId.RWAccessId;
import integratedtoolkit.types.data.DataInstanceId;
import integratedtoolkit.types.data.ResultFile;
import integratedtoolkit.types.data.AccessParams.*;
import integratedtoolkit.types.data.DataAccessId;
import integratedtoolkit.types.data.DataAccessId.*;
import integratedtoolkit.types.Parameter;
import integratedtoolkit.types.Parameter.*;
import integratedtoolkit.types.Parameter.DependencyParameter.*;
import integratedtoolkit.types.data.FileInfo;
import integratedtoolkit.types.request.tp.EndOfAppRequest;
import integratedtoolkit.types.request.tp.WaitForTaskRequest;
import integratedtoolkit.util.ElementNotFoundException;
import integratedtoolkit.util.Graph;
import java.util.Map.Entry;
import java.util.concurrent.Semaphore;

public class TaskAnalyser {

    // Constants definition
    private static final String TASK_FAILED = "Task failed: ";
    // Components
    private DataInfoProvider DIP;
    private TaskDispatcher TD;
    // Dependency graph
    private Graph<Integer, Task> depGraph;
    // <File id, Last writer task> table
    private TreeMap<Integer, Integer> writers;
    // Method information
    private HashMap<Integer, Integer> currentTaskCount;
    // Map: app id -> task count
    private HashMap<Long, Integer> appIdToTotalTaskCount;
    // Map: app id -> task count
    private HashMap<Long, Integer> appIdToTaskCount;
    // Map: app id -> semaphore to notify end of app
    private HashMap<Long, Semaphore> appIdToSemaphore;
    // Map: app id -> set of written data ids (for result files)
    private HashMap<Long, TreeSet<Integer>> appIdToWrittenFiles;
    // Tasks being waited on: taskId -> list of semaphores where to notify end of task
    private Hashtable<Integer, List<Semaphore>> waitedTasks;
    private static LinkedList<String> obsoleteRenamings;
    // Component loggers
    private static final Logger resourcesLog = Logger.getLogger(Loggers.RESOURCES);
    private static final boolean resourcesLogInfo = resourcesLog.isInfoEnabled();
    private static final Logger logger = Logger.getLogger(Loggers.TA_COMP);
    private static final boolean debug = logger.isDebugEnabled();
    // Graph drawing
    private static final boolean drawGraph = System.getProperty(ITConstants.IT_GRAPH) != null
            && System.getProperty(ITConstants.IT_GRAPH).equals("true") ? true : false;
    private static java.io.BufferedWriter fw;
    private static int synchronizationId;

    public TaskAnalyser() {
        depGraph = new Graph<Integer, Task>();

        currentTaskCount = new HashMap<Integer, Integer>();
        writers = new TreeMap<Integer, Integer>();
        appIdToTaskCount = new HashMap<Long, Integer>();
        appIdToTotalTaskCount = new HashMap<Long, Integer>();
        appIdToSemaphore = new HashMap<Long, Semaphore>();
        appIdToWrittenFiles = new HashMap<Long, TreeSet<Integer>>();
        waitedTasks = new Hashtable<Integer, List<Semaphore>>();

        obsoleteRenamings = new LinkedList<String>();

        if (drawGraph) {
            synchronizationId = 1;
            try {
                fw = new java.io.BufferedWriter(new java.io.FileWriter(System.getProperty("user.home") + "/" + System.getProperty(ITConstants.IT_APP_NAME) + ".dot"));
                fw.write("digraph {");
                fw.newLine();
                fw.write("ranksep=0.20;");
                fw.newLine();
                fw.write("node[height=0.75];");
            } catch (Exception e) {
                logger.error("Error generating graph file", e);
                System.exit(1);
            }
        }

        logger.info("Initialization finished");
    }

    public void setCoWorkers(DataInfoProvider DIP, TaskDispatcher TD) {
        this.DIP = DIP;
        this.TD = TD;
    }

    public Task getTask(int taskId) {
        return depGraph.get(taskId);
    }

    public void processTask(Task currentTask) {
        if (drawGraph) {
            drawTask(currentTask, currentTask.getCore() instanceof Method);
        }

        int currentTaskId = currentTask.getId();
        Parameter[] parameters = currentTask.getCore().getParameters();
        depGraph.addNode(currentTaskId, currentTask);

        // Update task count
        Integer methodId = currentTask.getCore().getId();
        Integer actualCount = currentTaskCount.get(methodId);
        if (actualCount == null) {
            actualCount = 0;
        }
        currentTaskCount.put(methodId, actualCount + 1);

        // Update app id task count
        Long appId = currentTask.getAppId();
        Integer taskCount = appIdToTaskCount.get(appId);
        if (taskCount == null) {
            taskCount = 0;
        }
        taskCount++;
        appIdToTaskCount.put(appId, taskCount);
        Integer totalTaskCount = appIdToTotalTaskCount.get(appId);
        if (totalTaskCount == null) {
            totalTaskCount = 0;
        }
        totalTaskCount++;
        appIdToTotalTaskCount.put(appId, totalTaskCount);

        List<AccessParams> accesses = new ArrayList<AccessParams>(parameters.length);
        for (Parameter p : parameters) {
            if (debug) {
                logger.debug("* Parameter : " + p);
            }

            // Conversion: direction -> access mode
            AccessMode am = null;
            switch (p.getDirection()) {
                case IN:
                    am = AccessMode.R;
                    break;
                case OUT:
                    am = AccessMode.W;
                    break;
                case INOUT:
                    am = AccessMode.RW;
                    break;
            }
            switch (p.getType()) {
                case FILE_T:
                    FileParameter fp = (FileParameter) p;
                    accesses.add(new FileAccessParams(am, fp.getName(), fp.getPath(), fp.getHost()));
                    break;

                case OBJECT_T:
                    ObjectParameter op = (ObjectParameter) p;
                    accesses.add(new ObjectAccessParams(am, op.getValue(), op.getCode()));
                    break;

                default:
                    /* Basic types (including String).
                     * The only possible access mode is R (already checked by the API)
                     */
                    break;
            }
        }
        try {
            // Inform the Data Manager about the new accesses
            List<DataAccessId> daIds = DIP.registerDataAccesses(accesses, methodId);
            ListIterator<DataAccessId> lidaIds = daIds.listIterator();
            ListIterator<AccessParams> liacc = accesses.listIterator();
            boolean isWaiting = true;
            for (Parameter p : parameters) {
                if (p instanceof DependencyParameter) {
                    DependencyParameter dp = (DependencyParameter) p;
                    DataAccessId daId = lidaIds.next();
                    dp.setDataAccessId(daId);
                    AccessMode am = liacc.next().getMode();
                    int dataId = daId.getDataId();
                    Integer lastWriterId = writers.get(dataId);
                    switch (am) {
                        case R:
                            isWaiting = checkDependencyForRead(lastWriterId, currentTask, dp) && isWaiting;
                            break;

                        case RW:
                            isWaiting = checkDependencyForRead(lastWriterId, currentTask, dp) && isWaiting;
                            writers.put(dataId, currentTaskId); // update global last writer
                            if (p.getType() == ParamType.FILE_T) { // Objects are not checked, their version will be only get if the main access them
                                TreeSet<Integer> idsWritten = appIdToWrittenFiles.get(appId);
                                if (idsWritten == null) {
                                    idsWritten = new TreeSet<Integer>();
                                    appIdToWrittenFiles.put(appId, idsWritten);
                                }
                                idsWritten.add(dataId);
                            }
                            if (debug) {
                                logger.debug("New writer for datum " + dp.getDataAccessId().getDataId() + " is task " + currentTaskId);
                            }
                            break;

                        case W:
                            writers.put(dataId, currentTaskId); // update global last writer
                            if (p.getType() == ParamType.FILE_T) {
                                TreeSet<Integer> idsWritten = appIdToWrittenFiles.get(appId);
                                if (idsWritten == null) {
                                    idsWritten = new TreeSet<Integer>();
                                    appIdToWrittenFiles.put(appId, idsWritten);
                                }
                                idsWritten.add(dataId);
                            }
                            if (debug) {
                                logger.debug("New writer for datum " + dp.getDataAccessId().getDataId() + " is task " + currentTaskId);
                            }
                            break;
                    }
                }
            }

            Core c = currentTask.getCore();
            if (c.hasTargetObject()) {
                DependencyParameter dependentParameter;
                DataInstanceId dependingDataId = null;
                if (c.hasReturnValue()) {
                    dependentParameter = (DependencyParameter) c.getParameters()[c.getParameters().length - 2];
                } else {
                    dependentParameter = (DependencyParameter) c.getParameters()[c.getParameters().length - 1];
                }

                switch (dependentParameter.getDirection()) {
                    case IN:
                        DataAccessId.RAccessId raId = (DataAccessId.RAccessId) dependentParameter.getDataAccessId();
                        dependingDataId = raId.getReadDataInstance();
                        break;
                    case INOUT:
                        DataAccessId.RWAccessId rwaId = (DataAccessId.RWAccessId) dependentParameter.getDataAccessId();
                        dependingDataId = rwaId.getReadDataInstance();
                        break;
                    case OUT:
                        break;
                }

                if (dependingDataId != null) {
                    currentTask.setEnforcingData(dependingDataId);
                    if (c instanceof integratedtoolkit.types.Service) {
                        currentTask.forceStrongScheduling();
                    }
                }
            }
            try {
                if (!depGraph.hasPredecessors(currentTaskId)) {
                    // No dependencies for this task, schedule
                    if (debug) {
                        logger.debug("Task " + currentTaskId + " has NO dependencies, send for schedule");
                    }

                    List<Task> s = new LinkedList<Task>();
                    s.add(currentTask);
                    currentTaskCount.put(currentTask.getCore().getId(), currentTaskCount.get(currentTask.getCore().getId()) - 1);

                    TD.scheduleTasks(s, false, new int[Core.coreCount], obsoleteRenamings);
                    obsoleteRenamings = null;
                } else if (isWaiting) {
                    TD.newWaitingTask(methodId, obsoleteRenamings);
                    obsoleteRenamings = null;
                }
            } catch (ElementNotFoundException e) {
                logger.error("Error checking dependencies for task " + currentTaskId, e);
                System.exit(1);
                return;
            }
        } catch (Exception e) {
            logger.error("Treating dependencies", e);
        }
    }

    private boolean checkDependencyForRead(Integer lastWriterId, Task currentTask, DependencyParameter dp) {
        if (lastWriterId != null
                && lastWriterId > 0
                && depGraph.get(lastWriterId) != null
                && lastWriterId != currentTask.getId()) { // avoid self-dependencies

            if (debug) {
                logger.debug("Last writer for datum " + dp.getDataAccessId().getDataId() + " is task " + lastWriterId);
                logger.debug("Adding dependency between task " + lastWriterId + " and task " + currentTask.getId());
            }

            if (drawGraph) {
                try {
                    boolean b = true;
                    for (Task t : depGraph.getSuccessors(lastWriterId)) {
                        if (t.getId() == currentTask.getId()) {
                            b = false;
                        }
                    }
                    if (b) {
                        fw.newLine();
                        fw.write(lastWriterId + " -> " + currentTask.getId() + "[ label=\"d" + dp.getDataAccessId().getDataId() + "\" ];");
                    }
                } catch (Exception e) {
                    logger.error("Error drawing dependency in graph", e);
                }
            }

            if (resourcesLogInfo) {
                resourcesLog.info("Adding dependency between task " + lastWriterId + " and task " + currentTask.getId());
            }

            try {
                depGraph.addEdge(lastWriterId, currentTask.getId());
                return !depGraph.hasPredecessors(lastWriterId); // check if it's a second-level task for this predecessor
            } catch (ElementNotFoundException e) {
                logger.fatal("Error when adding a dependency between tasks "
                        + lastWriterId + " and " + currentTask.getId(), e);
                System.exit(1);
            }
        } else if (drawGraph && lastWriterId != null && lastWriterId < 0) {
            try {
                fw.newLine();
                fw.write("Synchro" + (-lastWriterId) + " -> " + currentTask.getId() + ";");
            } catch (Exception e) {
                logger.error("Error drawing dependency in graph", e);
            }
        }
        return true;
    }

    public void updateGraph(Task task) {
        int taskId = task.getId();
        if (task.getStatus() == TaskState.FAILED) {
            logger.fatal(TASK_FAILED + task);
            System.exit(1);
        }

        // Update app id task count
        Long appId = task.getAppId();
        Integer taskCount = appIdToTaskCount.get(appId) - 1;
        appIdToTaskCount.put(appId, taskCount);
        if (taskCount == 0) {
            Semaphore sem = appIdToSemaphore.remove(appId);
            if (sem != null) { // App has notified that no more tasks are coming
                appIdToTaskCount.remove(appId);
                sem.release();
            }
        }

        // Check if task is being waited
        List<Semaphore> sems = waitedTasks.remove(taskId);
        if (sems != null) {
            for (Semaphore sem : sems) {
                sem.release();
            }
        }

        LinkedList<DataAccessId> readedData = new LinkedList<DataAccessId>();
        for (Parameter param : task.getCore().getParameters()) {
            ParamType type = param.getType();

            if (type == ParamType.FILE_T || type == ParamType.OBJECT_T) {
                DependencyParameter dPar = (DependencyParameter) param;
                DataAccessId dAccId = dPar.getDataAccessId();
                readedData.add(dAccId);
            }
        }
        obsoleteData(DIP.dataHasBeenRead(readedData, task.getCore().getId()));

        // Dependency-free tasks
        List<Task> toSchedule = new LinkedList<Task>();

        try {
            Iterator<Task> i = depGraph.getIteratorOverSuccessors(taskId);
            while (i.hasNext()) {
                Task succ = i.next();
                int succId = succ.getId();

                // Remove the dependency
                depGraph.removeEdge(taskId, succId);

                // Schedule if task has no more dependencies
                if (!depGraph.hasPredecessors(succId)) {
                    toSchedule.add(succ);
                }
            }
        } catch (ElementNotFoundException e) {
            logger.fatal("Error removing the dependencies of task " + taskId, e);
            System.exit(1);
        }

        String info = "\tTask " + taskId + " ends. ";
        if (!toSchedule.isEmpty()) {
            info += "Task(s)";

            if (debug) {
                StringBuilder sb = new StringBuilder("All dependencies solved for tasks: ");
                for (Task t : toSchedule) {
                    sb.append(t.getId()).append("(").append(t.getCore().getName()).append(") ");
                }

                logger.debug(sb);
            }

            int[] successors = new int[Core.coreCount];
            for (Task t : toSchedule) {
                info += " " + t.getId();
                try {
                    Iterator<Task> i = depGraph.getIteratorOverSuccessors(t.getId());
                    while (i.hasNext()) {
                        Task succ = i.next();
                        int succId = succ.getId();

                        boolean hasPreds = false;
                        Iterator<Task> j = depGraph.getIteratorOverPredecessors(succId);
                        while (j.hasNext()) {
                            Task pred = j.next();
                            int predId = pred.getId();
                            hasPreds = hasPreds || depGraph.hasPredecessors(predId);
                        }
                        if (!hasPreds) {

                            successors[t.getCore().getId()]++;
                        }
                    }
                    currentTaskCount.put(t.getCore().getId(), currentTaskCount.get(t.getCore().getId()) - 1);
                } catch (Exception e) {
                    logger.fatal("Error updating the waiting tasks of " + taskId, e);
                    System.exit(1);
                }
            }

            TD.scheduleTasks(toSchedule, true, successors, obsoleteRenamings);
            obsoleteRenamings = null;
            info += " is/are ready to be run";
        }

        TD.notifyJobEnd(task);

        // Add the task to the set of finished tasks
        //finishedTasks.add(task);
        // Check if the finished task was the last writer of a file, but only if task generation has finished
        if (appIdToSemaphore.get(appId) != null) {
            checkResultFileTransfer(task);
        }

        if (resourcesLogInfo) {
            resourcesLog.info(info);
        }
        depGraph.removeNode(taskId);
    }

    // Private method to check if a finished task is the last writer of its file parameters and eventually order the necessary transfers
    private void checkResultFileTransfer(Task t) {
        LinkedList<DataInstanceId> fileIds = new LinkedList<DataInstanceId>();
        for (Parameter p : t.getCore().getParameters()) {
            switch (p.getType()) {
                case FILE_T:
                    FileParameter fp = (FileParameter) p;
                    switch (fp.getDirection()) {
                        case IN:
                            break;
                        case INOUT:
                            DataInstanceId dId = ((RWAccessId) fp.getDataAccessId()).getWrittenDataInstance();
                            if (writers.get(dId.getDataId()) == t.getId()) {
                                fileIds.add(dId);
                            }
                            break;
                        case OUT:
                            dId = ((WAccessId) fp.getDataAccessId()).getWrittenDataInstance();
                            if (writers.get(dId.getDataId()) == t.getId()) {
                                fileIds.add(dId);
                            }
                            break;
                    }
                    break;
                default:
                    break;
            }
        }
        // Order the transfer of the result files
        final int numFT = fileIds.size();
        if (numFT > 0) {
            List<ResultFile> resFiles = new ArrayList<ResultFile>(numFT);
            for (DataInstanceId fileId : fileIds) {
                int id = fileId.getDataId();
                ResultFile resFile = new ResultFile(fileId, DIP.getOriginalName(id), DIP.getOriginalLocation(id));
                resFiles.add(resFile);
            }
            TD.transferBackResultFiles(resFiles, false);
        }
    }

    public void findWaitedTask(WaitForTaskRequest request) {
        int dataId = request.getDataId();
        AccessMode am = request.getAccessMode();
        Semaphore sem = request.getSemaphore();
        Integer lastWriterId = writers.get(dataId);
        if (drawGraph && lastWriterId != null) {
            if (am == AccessMode.RW) {
                writers.put(dataId, -synchronizationId);
            }
            try {
                fw.newLine();
                fw.write("Synchro" + synchronizationId + "[label=\"sync\", shape=octagon, style=filled fillcolor=\"#ff0000\" fontcolor=\"#FFFFFF\"];");
                fw.newLine();
                fw.write(lastWriterId + " -> Synchro" + synchronizationId + "[ label=\"d" + dataId + "\" ];");
            } catch (Exception e) {
                logger.error("Error adding task to graph file", e);
            }
            synchronizationId++;
        }
        if (lastWriterId == null || depGraph.get(lastWriterId) == null) {
            sem.release();
        } else {
            List<Semaphore> list = waitedTasks.get(lastWriterId);
            if (list == null) {
                list = new LinkedList<Semaphore>();
                waitedTasks.put(lastWriterId, list);
            }
            list.add(sem);
        }
    }

    public void noMoreTasks(EndOfAppRequest request) {
        Long appId = request.getAppId();
        Integer count = appIdToTaskCount.get(appId);

        if (count == null || count == 0) {
            appIdToTaskCount.remove(appId);
            request.getSemaphore().release();
        } else {
            appIdToSemaphore.put(appId, request.getSemaphore());
        }
    }

    public TreeSet<Integer> getAndRemoveWrittenFiles(Long appId) {
        return appIdToWrittenFiles.remove(appId);
    }

    public void shutdown() {
        if (drawGraph) {
            closeGraphFile();
        }
        TD.shutdown(currentTaskCount, obsoleteRenamings);
    }

    private void drawTask(Task task, boolean isMethod) {
        String fillColor = null, fontColor = "#000000"; // black
        switch (task.getCore().getId() % 12) {
            case 0:
                fillColor = "#0000ff"; // dark blue
                fontColor = "#ffffff"; // white
                break;
            case 1:
                fillColor = "#ffff00"; // yellow
                break;
            case 2:
                fillColor = "#00ff00"; // light green
                break;
            case 3:
                fillColor = "#00ffff"; // light blue
                break;
            case 4:
                fillColor = "#ff00ff"; // pink
                fontColor = "#ffffff"; // white
                break;
            case 5:
                fillColor = "#c0c0c0"; // grey
                break;
            case 6:
                fillColor = "#6600ff"; // violet
                fontColor = "#ffffff"; // white
                break;
            case 7:
                fillColor = "#9900ff"; // purple
                fontColor = "#ffffff"; // white
                break;
            case 8:
                fillColor = "#990000"; // dark red
                fontColor = "#ffffff"; // white
                break;
            case 9:
                fillColor = "#999900"; // dark green
                fontColor = "#ffffff"; // white
                break;
            case 10:
                fillColor = "#996600"; // brown
                fontColor = "#ffffff";
                break;
            case 11:
                fillColor = "#ff0000"; // red
                fontColor = "#ffffff"; // white
                break;
            default:
                fillColor = "#000000"; // white                
                fontColor = "#ffffff"; // white
                break;
        }

        String shape;
        if (isMethod) {
            shape = "circle";
        } else {
            shape = "diamond"; // service -- "triangle" "square" "pentagon"
        }
        try {
            fw.newLine();
            fw.write(task.getId() + "[shape=" + shape + ", style=filled fillcolor=\"" + fillColor + "\" fontcolor=\"" + fontColor + "\"]; ");
        } catch (Exception e) {
            logger.error("Error adding task to graph file", e);
        }
    }

    private void closeGraphFile() {
        try {
            if (fw != null) {
                fw.newLine();
                fw.write("}");
                fw.close();
                fw = null;
            }
        } catch (Exception e) {
            logger.error("Error closing graph file", e);
        }
    }

    public String getGraphDOTFormat() {
        return depGraph.getGraphDotFormat();
    }

    public String getTaskStateRequest() {
        StringBuilder sb = new StringBuilder("\t<TasksInfo>\n");
        for (Entry<Long, Integer> e : appIdToTotalTaskCount.entrySet()) {
            Long appId = e.getKey();
            Integer totalTaskCount = e.getValue();
            Integer taskCount = appIdToTaskCount.get(appId);
            if (taskCount == null) {
                taskCount = 0;
            }
            int completed = totalTaskCount - taskCount;
            sb.append("\t\t<Application id=\"").append(appId).append("\">\n");
            sb.append("\t\t\t<TotalCount>").append(totalTaskCount).append("</TotalCount>\n");
            sb.append("\t\t\t<InProgress>").append(taskCount).append("</InProgress>\n");
            sb.append("\t\t\t<Completed>").append(completed).append("</Completed>\n");
            sb.append("\t\t</Application>\n");
        }
        sb.append("\t</TasksInfo>\n");
        return sb.toString();
    }

    public void deleteFile(FileInfo fileInfo) {
        int dataId = fileInfo.getDataId();
        Integer taskId = writers.get(dataId);
        if (taskId != null) {
            Task task = depGraph.get(taskId);
            if (task != null) {
                return;
            }
        }
        if (fileInfo.getReadersForVersion(fileInfo.getLastVersionId()) == 0) {
            LinkedList<String> obsoletes = new LinkedList<String>();
            obsoletes.add(fileInfo.getLastDataInstanceId().getRenaming());
            obsoleteData(obsoletes);
        }
        for (TreeSet<Integer> files : appIdToWrittenFiles.values()) {
            files.remove(fileInfo.getDataId());
        }
    }

    public void obsoleteData(LinkedList<String> obsoletes) {
        if (obsoleteRenamings == null) {
            obsoleteRenamings = obsoletes;
        } else {
            obsoleteRenamings.addAll(obsoletes);
        }
    }

    public void forceObsoleteRemoval() {
        TD.removeObsoletes(obsoleteRenamings);
        obsoleteRenamings = null;
    }
}
