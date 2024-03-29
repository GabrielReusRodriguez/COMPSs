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




package integratedtoolkit.components;

import integratedtoolkit.components.DataAccess.AccessMode;
import integratedtoolkit.types.Task;


/* To inform about the end of a task
 * To wait until the last writer task of a given file finishes
 */
public interface TaskStatus {
	
    void notifyTaskEnd(Task task);
    
    void waitForTask(int dataId, AccessMode mode);

}
