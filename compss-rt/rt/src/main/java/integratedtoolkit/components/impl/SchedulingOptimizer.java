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

import integratedtoolkit.log.Loggers;
import integratedtoolkit.types.Core;
import integratedtoolkit.types.ScheduleDecisions;
import integratedtoolkit.types.ScheduleState;
import integratedtoolkit.types.Task;
import integratedtoolkit.util.CloudManager;
import integratedtoolkit.util.ProjectManager;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import org.apache.log4j.Logger;

public class SchedulingOptimizer extends Thread {

    private Object alarmClock = new Object();
    private boolean running;
    private Integer maxNumberOfVMs;
    private Integer minNumberOfVMs;
    private TaskDispatcher TD;
    //Number of Graph second level tasks per method
    private static int[] secondLevelGraphCount;
    private static final Logger monitor = Logger.getLogger(Loggers.RESOURCES);
    private static final boolean monitorDebug = monitor.isDebugEnabled();
    private static final Logger logger = Logger.getLogger(Loggers.TS_COMP);
    private static final boolean debug = logger.isDebugEnabled();
    private static boolean cleanUp;
    private static boolean redo;

    SchedulingOptimizer() {
        secondLevelGraphCount = new int[Core.coreCount];
        redo = false;
    }

    public void setCoWorkers(TaskDispatcher td) {
        TD = td;
    }

    public void kill() {
        synchronized (alarmClock) {
            running = false;
            alarmClock.notify();
            cleanUp = true;
        }
    }

    public void run() {

        running = true;
        ScheduleState oldSchedule;
        ScheduleDecisions newSchedule;

        while (running) {
            try {
                do {
                    redo = false;
                    oldSchedule = TD.getCurrentSchedule();
                    if (oldSchedule.useCloud) {
                        newSchedule = applyPolicies(oldSchedule);
                    } else {
                        ScheduleDecisions sd = new ScheduleDecisions();
                        newSchedule = new ScheduleDecisions();
                        loadBalance(oldSchedule, newSchedule);
                    }
                } while (redo);
                TD.setNewSchedule(newSchedule);
                try {
                    synchronized (alarmClock) {
                        alarmClock.wait(20000);
                    }
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void optimizeNow() {
        synchronized (alarmClock) {
            alarmClock.notify();
            redo = true;
        }
    }

    public void updateWaitingCounts(List<Task> tasks, boolean waiting, int[] waitingSet) {
        for (int i = 0; i < Core.coreCount; i++) {
            secondLevelGraphCount[i] += waitingSet[i];
        }
        for (Task currentTask : tasks) {
            if (waiting) {
                secondLevelGraphCount[currentTask.getCore().getId()]--;
            }
        }
    }

    public void newWaitingTask(int methodId) {
        secondLevelGraphCount[methodId]++;
    }

    //APPLYING POLICIES
    private ScheduleDecisions applyPolicies(ScheduleState oldSchedule) {
    	int currentCloudVMCount = CloudManager.getCurrentVMCount();
        try {
            ProjectManager.refresh();
            String maxAmountString = ProjectManager.getCloudProperty("maxVMCount");
            if (maxAmountString != null) {
                maxNumberOfVMs = Integer.parseInt(maxAmountString);
            }
            String minAmountString = ProjectManager.getCloudProperty("minVMCount");
            if (minAmountString != null) {
                minNumberOfVMs = Integer.parseInt(minAmountString);
            }
        } catch (Exception e) {
        }

        long limitTime = oldSchedule.creationTime;
        int[] watingOnQueuesCores = oldSchedule.waitingOnQueuesCores;
        long[] coreMeanExecutionTime = oldSchedule.coreMeanExecutionTime;
        int noResourceCount = oldSchedule.noResource;
        int[] noResourceCountPerCore = oldSchedule.noResourcePerCore;
        long[] limitedMethodMeanExecutionTime = new long[coreMeanExecutionTime.length];
        Integer[] slotCountPerCore = oldSchedule.slotCountPerCore;
        Integer[] realSlotsPerCore = new Integer[Core.coreCount];
        long[] totalMethodTimes = new long[Core.coreCount];

        for (int i = 0; i < Core.coreCount; i++) {
            if (coreMeanExecutionTime[i] > limitTime) {
                limitedMethodMeanExecutionTime[i] = limitTime;
            } else {
                limitedMethodMeanExecutionTime[i] = coreMeanExecutionTime[i];
            }
            realSlotsPerCore[i] = 0;
        }

        if (noResourceCount > 0) {
            for (int core = 0; core < Core.coreCount; core++) {
                totalMethodTimes[core] += noResourceCountPerCore[core] * limitedMethodMeanExecutionTime[core];
            }
        }

        for (String res : oldSchedule.getHostNames()) {
            long[] slotsTime = oldSchedule.getSlotsWaitingTime(res, limitedMethodMeanExecutionTime);
            long totalResourceTime = 0l;
            for (int i = 0; i < slotsTime.length; i++) {
                totalResourceTime += slotsTime[i];
            }
            List<Integer> cores = oldSchedule.getLastReadAbleCores();
            for (int core : cores) {
                realSlotsPerCore[core] += oldSchedule.getLastReadActiveSlotsCount();
                totalMethodTimes[core] += totalResourceTime;
            }
        }
        ScheduleDecisions sd = new ScheduleDecisions();
        if (!cleanUp && (maxNumberOfVMs == null || maxNumberOfVMs > currentCloudVMCount)) {
            if (minNumberOfVMs != null && minNumberOfVMs > currentCloudVMCount) {
                sd.mandatory = forcedCreationPolicy(watingOnQueuesCores, totalMethodTimes, slotCountPerCore);
                if (sd.mandatory.size() != 0) {
                    sd.extra = new HashMap();
                    sd.terminate = new HashMap<Integer, Float>();
                    loadBalance(oldSchedule, sd);
                    return sd;
                }
            }
            sd.mandatory = checkNeededMachines(noResourceCount, noResourceCountPerCore, slotCountPerCore);
            if (sd.mandatory.size() != 0) {
                sd.extra = new HashMap();
                sd.terminate = new HashMap<Integer, Float>();
                loadBalance(oldSchedule, sd);
                return sd;
            }
            sd.extra = shortTermCreationPolicy(limitTime, totalMethodTimes, slotCountPerCore);
            if (!sd.extra.isEmpty()) {
                sd.terminate = new HashMap<Integer, Float>();
                loadBalance(oldSchedule, sd);
                return sd;
            }
        } else {
            sd.mandatory = new LinkedList();
            sd.extra = new HashMap();
        }

        //Add waiting Time for next step tasks;
        for (int i = 0; i < Core.coreCount; i++) {
            totalMethodTimes[i] += secondLevelGraphCount[i] * limitedMethodMeanExecutionTime[i];
        }

        HashMap<Integer, Float> toDelete;
        if (maxNumberOfVMs != null && currentCloudVMCount > maxNumberOfVMs) {
            toDelete = this.destructionPolicy(limitTime, totalMethodTimes, realSlotsPerCore);
            sd.forcedToDestroy = true;
        } else {
            if (minNumberOfVMs == null || currentCloudVMCount > minNumberOfVMs) {
                sd.forcedToDestroy = false;
                toDelete = this.destructionPolicy(limitTime, totalMethodTimes, realSlotsPerCore);
            } else {
                toDelete = new HashMap();
            }
        }
        sd.terminate = toDelete;
        loadBalance(oldSchedule, sd);
        return sd;
    }

    private LinkedList<Integer> forcedCreationPolicy(int[] watingOnQueuesCores, long[] totalMethodTimes, Integer[] slotCountPerCore) {
        int maxI = 0;
        float maxRatio = 0;
        for (int i = 0; i < Core.coreCount; i++) {
            if (watingOnQueuesCores[i] > 0) {
                float ratio = totalMethodTimes[i] / slotCountPerCore[i];
                if (ratio > maxRatio) {
                    maxI = i;
                    maxRatio = ratio;
                }
            }
        }
        LinkedList creation = new LinkedList();
        creation.add(maxI);
        return creation;
    }

    private HashMap<Integer, Integer> shortTermCreationPolicy(long limitTime, long[] totalMethodTimes, Integer[] slotCountPerCore) {

        long totalTime = limitTime * slotCountPerCore[0];
        /*if (totalTime == 0l) {
         System.out.println("Core 0 -%");
         } else {
         System.out.println("Core 0 " + ((totalMethodTimes[0] * 100) / totalTime) +"%");
         }
         */
        HashMap<Integer, Integer> neededResources = new HashMap<Integer, Integer>();
        for (int coreIndex = 0; coreIndex < Core.coreCount; coreIndex++) {
            /*long*/ totalTime = limitTime * slotCountPerCore[coreIndex];
            if (totalTime == 0l) {
                continue;
            } else {
                if ((totalMethodTimes[coreIndex] * 100) / totalTime > 100) {
                    neededResources.put(coreIndex, (int) ((totalMethodTimes[coreIndex] - totalTime) / limitTime));
                }
            }
        }
        return neededResources;
    }

    private HashMap<Integer, Float> destructionPolicy(long limitTime, long[] totalMethodTimes, Integer[] slotCountPerCore) {
        HashMap<Integer, Float> toDelete = new HashMap();
        for (int methodIndex = 0; methodIndex < Core.coreCount; methodIndex++) {
            long totalTime = limitTime * (slotCountPerCore[methodIndex]);
            if (totalTime == 0l) {
                continue;
            } else {
                toDelete.put(methodIndex, (float) ((double) ((3 * totalTime) / 4 - totalMethodTimes[methodIndex]) / (double) limitTime));
            }
        }
        return toDelete;
    }

    private LinkedList<Integer> checkNeededMachines(int noResourceCount, int[] noResourceCountPerCore, Integer[] slotCountPerCore) {
        LinkedList<Integer> needed = new LinkedList<Integer>();
        if (noResourceCount == 0) {
            return needed;
        }
        for (int i = 0; i < Core.coreCount; i++) {
            if (noResourceCountPerCore[i] > 0 && slotCountPerCore[i] == 0) {
                needed.add(i);
            }
        }
        return needed;
    }

    private void loadBalance(ScheduleState ss, ScheduleDecisions sd) {

        LinkedList<HostSlotsTime>[] hostListPerCore = new LinkedList[Core.coreCount];
        for (int coreId = 0; coreId < Core.coreCount; coreId++) {
            hostListPerCore[coreId] = new LinkedList();
        }

        Set<String> resources = ss.getHostNames();
        for (String res : resources) {
            HostSlotsTime hst = new HostSlotsTime();
            hst.hostName = res;
            hst.slotsTime = ss.getSlotsWaitingTime(res, ss.coreMeanExecutionTime);
            hst.slotCoreCount = ss.getLastReadSlotsCoreCount();
            for (int coreId : ss.getLastReadAbleCores()) {
                hostListPerCore[coreId].add(hst);
            }
        }

        for (int coreId = 0; coreId < Core.coreCount; coreId++) {
            //Busquem els recursos que poden executar pel core
            LinkedList<CoreTransferRequest> sender = new LinkedList();
            LinkedList<CoreTransferRequest> receiver = new LinkedList();
            LinkedList<HostSlotsTime> resourceList = hostListPerCore[coreId];
            int slotCount = 0;
            long slotTime = 0l;

            //Fem la suma del nombre de slots i del temps total
            for (HostSlotsTime hst : resourceList) {
                for (int i = 0; i < hst.slotsTime.length; i++) {
                    slotCount++;
                    slotTime += hst.slotsTime[i];
                }
            }
            if (slotCount == 0) {
                continue;
            }
            //Fem la mitja del temps ocupat      
            long average = slotTime / (long) slotCount;

            //Calculem la  donacio/recepció
            for (HostSlotsTime hst : resourceList) {
                for (int i = 0; i < hst.slotsTime.length; i++) {
                    double ratio = (double) (hst.slotsTime[i] - average) / (double) ss.coreMeanExecutionTime[coreId];
                    if (ratio < 0) {
                        ratio -= 0.5;
                        int change = (int) ratio;
                        receiver.add(new CoreTransferRequest(hst, i, Math.abs(change)));
                    } else if (ratio > 0) {
                        ratio += 0.5;
                        int change = (int) ratio;
                        change = Math.min(change, hst.slotCoreCount[i][coreId]);
                        sender.add(new CoreTransferRequest(hst, i, change));
                    }
                }
            }
            //Fem el moviment
            for (CoreTransferRequest sndr : sender) {
                if (receiver.isEmpty()) {
                    break;
                }
                while (sndr.amount > 0) {
                    if (receiver.isEmpty()) {
                        break;
                    }
                    CoreTransferRequest rcvr = receiver.get(0);
                    int move = Math.min(rcvr.amount, sndr.amount);
                    moveTask(sndr, rcvr, move, coreId, ss.coreMeanExecutionTime[coreId]);
                    sd.addMovement(sndr.hst.hostName, sndr.slot, rcvr.hst.hostName, rcvr.slot, coreId, move);
                    if (rcvr.amount == 0) {
                        receiver.remove(0);
                    }
                }
            }
        }
    }

    private void moveTask(CoreTransferRequest sndr, CoreTransferRequest rcvr, int amount, int coreId, long coreTime) {
        sndr.amount -= amount;
        rcvr.amount -= amount;
        sndr.hst.slotCoreCount[sndr.slot][coreId] -= amount;
        rcvr.hst.slotCoreCount[rcvr.slot][coreId] += amount;
        sndr.hst.slotsTime[sndr.slot] -= coreTime * amount;
        rcvr.hst.slotsTime[rcvr.slot] += coreTime * amount;
    }

    public void cleanUp() {
        cleanUp = true;
    }

    public void resizeDataStructures() {
        int[] secondLevelGraphCountTmp = new int[Core.coreCount];
        System.arraycopy(secondLevelGraphCount, 0, secondLevelGraphCountTmp, 0, secondLevelGraphCount.length);
        secondLevelGraphCount = secondLevelGraphCountTmp;
    }

    private class HostSlotsTime {

        String hostName;
        long[] slotsTime;
        int[][] slotCoreCount;
    }

    private class CoreTransferRequest {

        HostSlotsTime hst;
        int slot;
        int amount;

        public CoreTransferRequest(HostSlotsTime hst, int slot, int amount) {
            this.hst = hst;
            this.slot = slot;
            this.amount = amount;
        }
    }

}
