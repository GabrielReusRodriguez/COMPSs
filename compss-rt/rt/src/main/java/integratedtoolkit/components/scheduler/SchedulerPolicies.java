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


package integratedtoolkit.components.scheduler;

import integratedtoolkit.components.impl.FileTransferManager;
import integratedtoolkit.components.impl.JobManager;
import integratedtoolkit.types.Task;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

public abstract class SchedulerPolicies {

    public JobManager JM;
    public FileTransferManager FTM;

    public abstract LinkedList<Task> sortTasksForResource(String hostName, List<Task> tasksToReschedule);

    public abstract LinkedList<String> sortResourcesForTask(Task t, List<String> resources);

    public abstract OwnerTask[] stealTasks(String destResource, HashMap<String, LinkedList<Task>> pendingTasks, int numberOfTasks);

    public class OwnerTask {

        public String owner;
        public Task t;

        public OwnerTask(String owner, Task t) {
            this.owner = owner;
            this.t = t;
        }
    }
}
