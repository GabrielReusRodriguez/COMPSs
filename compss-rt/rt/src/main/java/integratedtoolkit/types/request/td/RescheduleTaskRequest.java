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



package integratedtoolkit.types.request.td;

import integratedtoolkit.types.Task;

/**
 * The RefuseCloudWorkerRequest class represents the notification of an error
 * during a task execution that must be rescheduled in another resource.
 */
public class RescheduleTaskRequest extends TDRequest {

    /** Task that must be rescheduled*/
    private Task task;

    /**
     * Constructs a new RescheduleTaskRequest for the task task
     * @param task Task that must be rescheduled
     */
    public RescheduleTaskRequest(Task task) {
        super(TDRequestType.RESCHEDULE_TASK);
        this.task = task;
    }

    /**
     * Returns the task that must be rescheduled
     * @return Task that must be rescheduled
     */
    public Task getTask() {
        return task;
    }

    /** 
     * Sets the task that must be rescheduled
     * @param task Task that must be rescheduled
     */
    public void setTask(Task task) {
        this.task = task;
    }
}
