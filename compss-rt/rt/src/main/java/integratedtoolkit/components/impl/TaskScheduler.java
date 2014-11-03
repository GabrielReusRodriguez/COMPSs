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

import integratedtoolkit.components.scheduler.SchedulerPolicies;
import org.apache.log4j.Logger;

import integratedtoolkit.ITConstants;
import integratedtoolkit.log.Loggers;
import integratedtoolkit.types.ExecutionParams;
import integratedtoolkit.types.Parameter.*;
import integratedtoolkit.types.Task;
import integratedtoolkit.types.data.DataAccessId.*;
import integratedtoolkit.util.ProjectManager;
import integratedtoolkit.util.ResourceManager;
import integratedtoolkit.types.ScheduleDecisions;
import integratedtoolkit.types.ScheduleState;

public abstract class TaskScheduler {

    // Components
    protected JobManager JM;
    protected FileTransferManager FTM;

    protected SchedulerPolicies schedulerPolicies;

    // Component logger - No need to configure, ProActive does
    protected static final Logger monitor = Logger.getLogger(Loggers.RESOURCES);
    protected static final boolean monitorDebug = monitor.isDebugEnabled();
    protected static final Logger logger = Logger.getLogger(Loggers.TS_COMP);
    protected static final boolean debug = logger.isDebugEnabled();

    // Preschedule
    protected static final boolean presched = System.getProperty(ITConstants.IT_PRESCHED) != null
            && System.getProperty(ITConstants.IT_PRESCHED).equals("true")
            ? true : false;

    public void setCoWorkers(JobManager JM, FileTransferManager FTM) {
        this.JM = JM;
        this.schedulerPolicies.JM = JM;
        this.FTM = FTM;
        this.schedulerPolicies.FTM = FTM;
    }

    /**
     * Resizes all the internal data structures to enable them to manage a
     * different number of Core Elements.
     */
    public abstract void resizeDataStructures();

    /**
     ********************************************
     *
     * Pending Work Query
     *
     ********************************************
     */
    /**
     * Checks if there is pending tasks to be executed for a given core
     *
     * @param coreId Identifier of the core whose tasks presence has to be
     * tested
     * @return {@literal true} - if there are pending tasks to be executed
     */
    public abstract boolean isPendingWork(Integer coreId);

    /**
     ********************************************
     *
     * Resource Management
     *
     ********************************************
     */
    /**
     * Adds new slots to the scheduler and assigns tasks to them.
     *
     * Adds new slots to the scheduling system assigned to the resource passed
     * as a parameter. If the resource doesn't exists yet, it creates it with as
     * many slots as indicated in the second parameter. If it already exists,
     * the number of slots assigned bounded to the resource is increased by that
     * amount.
     *
     * Once the slots have been added to the pool, it tries to assign a pending
     * task to the just added slots.
     *
     * @param hostName Name of the resource that will host the slots.
     * @param slots Number of slots added to the Scheduling system.
     */
    public abstract void newConfirmedSlots(String hostName, int slots);

    /**
     * Removes some slots from the scheduling systems.
     *
     * It stores that there is a pending modification on that resource pool.
     *
     * Removes a number of the slots bounded to a given resource from the
     * scheduling system. Potentially, there are not all of the slots can be
     * removed since they can be busy running tasks. If this is the case, the
     * TaskScheduler must store the pending modification.
     *
     * From this method invocation on, the Task Scheduler has to attempt to
     * release enough slots to perform the slots reduction
     *
     * @param hostName Name of the resource that is hosting the slots.
     * @param slots Number of slots that must be removed from the system.
     */
    public abstract void removeSlotsFromNode(String hostName, int slots);

    /**
     * Removes a resource and all its slots from the scheduling system.
     *
     * The resource passed as a parameter and all bounded slots are completely
     * removed from the scheduling systes. Before calling this method, the user
     * must ensure that no tasks are running on the resource slots.
     *
     * @param hostName Name of the resource to be removed
     */
    public abstract void removeNode(String hostName);

    /**
     * Tries to perform a pending modification on a resource.
     *
     * In the case that there exists a pending modification for the specified
     * resource, it checks if there are enough free slots to actually perform
     * the modification.
     *
     * When the amount of free slots is lower than the required by the
     * modification, it returns null and mantains all the modification request
     * in a pending state. Otherwise, if there are enough resources, the method
     * commits the modification
     *
     *
     * @param resource Name of the resource to be modified
     * @return {@literal true} if the pending modification has been commited
     */
    public abstract boolean performModification(String resource);

    /**
     * Checks the computing ability to compute tasks.
     *
     * @param resource Name of the resource whose computing ability has to be
     * tested.
     * @return {@literal true} - if the resource still has some slots to compute
     */
    public abstract boolean canResourceCompute(String resource);

    /**
     ********************************************
     *
     * Task Scheduling
     *
     ********************************************
     */
    /**
     * Schedules a task execution on an available resource.
     *
     * Given a task passed as a parameter, it looks for an available resource
     * where to execute it. If there is no slot able to host the execution
     * (because of the task constraints or the slot occupation) the tasks is
     * stored to be executed later. Otherwise, if there is some resource that
     * fulfills the task constraints and one free slot, the task execution is
     * submitted to the resource via the Job Manager.
     *
     * @param task Task whose execution has to be scheduled
     */
    public abstract void scheduleTask(Task task);

    /**
     *
     * Reschedules a task execution on an available resource different from the
     * one where it already failed.
     *
     * Given a task passed as a parameter, it looks for an available resource
     * where to execute it. If there is no slot able to host the execution
     * (because of the task constraints or the slot occupation) the tasks is
     * stored to be executed later. Otherwise, if there is some resource that
     * fulfills the task constraints and one free slot, the task execution is
     * submitted to the resource via the Job Manager.
     *
     * The host where the task already run is ignored during the resource
     * selecting process.
     *
     * @param task Task whose execution has to be scheduled
     * @param failedResource Resource where the task already failed
     */
    public abstract void rescheduleTask(Task task, String failedResource);

    /**
     * Releases the slot where a task was running.
     *
     * Releases the slot where the task was running so another task can be
     * assigned to it. In addition, if the task execution finished properly, the
     * slot is released and the Task Scheduler updates core monitoring data.
     *
     * @param task Task whose execution has ended
     */
    public abstract void taskEnd(Task task);

    /**
     * Tries to find a pending task to run in a given resource and submits its
     * execution.
     *
     * It looks for a pending tasks that can be submitted to the resource passed
     * as a parameter. If there's no pending task that can run in the resource,
     * the method does nothing and returns false. Otherwise, if a pending task
     * whose constraints match the resource features is found, its execution is
     * submitted via the Job Manager.
     *
     * The method does not check the number of available slots on the resource.
     * It's responsibility of the user to check it.
     *
     * @param hostName Resource where to run the chosen task.
     * @return {@literal true} if a pending task execution is submitted to the
     * resource
     */
    public abstract boolean scheduleToResource(String hostName);

    /**
     ********************************************
     *
     * Scheduling state operations
     *
     ********************************************
     */
    /**
     * Describes the current load of the scheduling system.
     *
     *
     *
     * @return descrription of the current load of the sceduling system.
     */
    public abstract ScheduleState getSchedulingState();

    /**
     * Applies some improvents to the task scheduling
     *
     * @param newState Scheduling improvements recommendations
     */
    public abstract void setSchedulingState(ScheduleDecisions newState);

    /**
     * Returns the current state that should be printed in the monitor
     *
     * @return current state in an XML format
     */
    public abstract String getMonitoringState();

    public void notifyTaskEnd(Task task) {
        ResourceManager.freeResource(task.getExecParams().getHost());
        this.taskEnd(task);
    }

    protected void sendJob(Task task, String resource) {

        String installDir = ProjectManager.getResourceProperty(resource, ITConstants.INSTALL_DIR);
        String workingDir = ProjectManager.getResourceProperty(resource, ITConstants.WORKING_DIR);
        String appDir = ProjectManager.getResourceProperty(resource, ITConstants.APP_DIR);
        String libPath = ProjectManager.getResourceProperty(resource, ITConstants.LIB_PATH);
        String user = ProjectManager.getResourceProperty(resource, ITConstants.USER);

        // Prepare the execution parameters
        // TODO: Cost
        ExecutionParams execParams = new ExecutionParams(user,
                resource,
                installDir,
                workingDir,
                appDir,
                libPath);
        ResourceManager.reserveResource(resource);
        // Request the creation of the job
        task.setExecParams(execParams);
        JM.newJob(task);
    }

    protected void sendJobRescheduled(Task task, String resource) {
        String installDir = ProjectManager.getResourceProperty(resource, ITConstants.INSTALL_DIR);
        String workingDir = ProjectManager.getResourceProperty(resource, ITConstants.WORKING_DIR);
        String appDir = ProjectManager.getResourceProperty(resource, ITConstants.APP_DIR);
        String libPath = ProjectManager.getResourceProperty(resource, ITConstants.LIB_PATH);
        String user = ProjectManager.getResourceProperty(resource, ITConstants.USER);

        // Prepare the execution parameters
        // TODO: Cost
        ExecutionParams newExecParams = new ExecutionParams(user,
                resource,
                installDir,
                workingDir,
                appDir,
                libPath);
        ResourceManager.reserveResource(resource);
        // Request the creation of the job
        task.setExecParams(newExecParams);
        JM.jobRescheduled(task);
    }
}
