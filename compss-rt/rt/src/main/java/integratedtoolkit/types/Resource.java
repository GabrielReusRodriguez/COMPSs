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

package integratedtoolkit.types;

import integratedtoolkit.util.CoreManager;

import java.util.LinkedList;


public abstract class Resource implements Comparable<Resource> {

    public enum Type {

        WORKER,
        SERVICE
    }

    // Max number of tasks
    int maxTaskCount;
    // Number of tasks assigned to the resource
    int taskCount;
    // CoreIds that can be executed by this resource
    LinkedList<Integer> executableCores;
    // ImplIds per core that can be executed by this resource
    int[][] executableImpls;
    // Number of tasks that can be run simultaneously per core id
    int[] coreSimultaneousTasks;
    // Number of tasks that can be run simultaneously per core id (maxTaskCount not considered)
    int[] idealSimultaneousTasks;

    public Resource() {
        this.coreSimultaneousTasks = new int[CoreManager.coreCount];
        this.idealSimultaneousTasks = new int[CoreManager.coreCount];
        this.executableCores = new LinkedList<Integer>();
        this.taskCount = 0;
        this.maxTaskCount = 0;
        this.executableImpls = new int[CoreManager.coreCount][];
        for (int coreId = 0; coreId < CoreManager.coreCount; ++coreId) {
        	executableImpls[coreId] = new int[CoreManager.getCoreImplementations(coreId).length];
        }
        
    }

    public Resource(Integer maxTaskCount) {
        this.coreSimultaneousTasks = new int[CoreManager.coreCount];
        this.idealSimultaneousTasks = new int[CoreManager.coreCount];
        this.maxTaskCount = maxTaskCount;
        this.taskCount = 0;
        this.executableCores = new LinkedList<Integer>();
        this.executableImpls = new int[CoreManager.coreCount][];
        for (int coreId = 0; coreId < CoreManager.coreCount; ++coreId) {
        	executableImpls[coreId] = new int[CoreManager.getCoreImplementations(coreId).length];
        }
    }

    public abstract void setName(String name);

    public abstract String getName();

    public void setMaxTaskCount(int count) {
        maxTaskCount = count;
    }

    public void setTaskCount(int taskCount) {
        this.taskCount = taskCount;
    }

    public int getTaskCount() {
        return taskCount;
    }

    public int getMaxTaskCount() {
        return maxTaskCount;
    }

    public void setExecutableCores(LinkedList<Integer> executableCores) {
        this.executableCores = executableCores;
    }

    public LinkedList<Integer> getExecutableCores() {
        return executableCores;
    }
    
    public void setExecutableImpls(int[][] executableImpls) {
    	this.executableImpls = executableImpls;
    	this.executableCores = new LinkedList<Integer> ();
    	for (int coreId = 0; coreId < executableImpls.length; ++coreId) {
    		boolean found = false;
    		for (int implId = 0; implId < executableImpls[coreId].length && !found; ++implId) {
    			if (executableImpls[coreId][implId] > 0) {
    				found = true;
    				executableCores.add(coreId);
    			}
    		}
    	}
    }
    
    public int[][] getExecutableImpls() {
    	return executableImpls;
    }
    
    public LinkedList<Implementation>[] getExecutableImplsList() {
    	LinkedList<Implementation>[] res = new LinkedList [executableImpls.length];
    	for (int coreId = 0; coreId < executableImpls.length; ++coreId) {
    		res[coreId] = new LinkedList<Implementation>();
    		for (int implId = 0; implId < executableImpls[coreId].length; ++implId) {
    			if (executableImpls[coreId][implId] > 0) {
    				res[coreId].add(CoreManager.getCoreImplementations(coreId)[implId]);
    			}
    		}
    	}
    	return res;
    }
    
    public LinkedList<Implementation> getExecutableImplsList(int coreId) {
    	LinkedList<Implementation> res = new LinkedList<Implementation>();
    	for (int implId = 0; implId < executableImpls[coreId].length; ++implId) {
    		if (executableImpls[coreId][implId] > 0) {
    			res.add(CoreManager.getCoreImplementations(coreId)[implId]);
    		}
    	}
    	return res;
    }
    
    public int[] getExecutableImpls(int coreId) {
    	return executableImpls[coreId];
    }

    public int[][] newCoreElementsDetected(LinkedList<Integer> newCores) {
        int[] coreSimultaneousTasks = new int[CoreManager.coreCount];
        System.arraycopy(this.coreSimultaneousTasks, 0, coreSimultaneousTasks, 0, this.coreSimultaneousTasks.length);
        int[] idealSimultaneousTasks = new int[CoreManager.coreCount];
        System.arraycopy(this.idealSimultaneousTasks, 0, idealSimultaneousTasks, 0, this.idealSimultaneousTasks.length);

        for (Integer coreId : newCores) {
            for (Implementation impl : CoreManager.getCoreImplementations(coreId)) {
                if (canRun(impl)) {
                    idealSimultaneousTasks[coreId] = Math.max(idealSimultaneousTasks[coreId], simultaneousCapacity(impl));
                    executableImpls[coreId][impl.getImplementationId()] = simultaneousCapacity(impl);
                }
            }
            coreSimultaneousTasks[coreId] = Math.min(maxTaskCount, idealSimultaneousTasks[coreId]);
            if (coreSimultaneousTasks[coreId] > 0) {
                executableCores.add(coreId);
            }
        }
        this.idealSimultaneousTasks = idealSimultaneousTasks;
        this.coreSimultaneousTasks = coreSimultaneousTasks;
        return this.executableImpls;
    }

    public void setResourceCoreLinks(int[] idealSimTasks) {
        idealSimultaneousTasks = idealSimTasks;
        executableCores.clear();
        for (int coreId = 0; coreId < CoreManager.coreCount; coreId++) {
            coreSimultaneousTasks[coreId] = Math.min(maxTaskCount, idealSimTasks[coreId]);
            if (coreSimultaneousTasks[coreId] > 0) {
                executableCores.add(coreId);
            }
        }
    }
    
    public void setResourceImplsLinks(int[][] idealRunnableImpls) {
    	int[] idealSimTasks = new int[CoreManager.coreCount];
    	executableCores.clear();
    	for (int coreId = 0; coreId < CoreManager.coreCount; ++coreId) {
    		int maxTasks = 0;
    		for (int implId = 0; implId < idealRunnableImpls[coreId].length; ++implId) {
    			executableImpls[coreId][implId] = idealRunnableImpls[coreId][implId];
    			if (idealRunnableImpls[coreId][implId] > maxTasks) {
    				maxTasks = idealRunnableImpls[coreId][implId];
    			}
    		}
    		idealSimTasks[coreId] = maxTasks;
    		coreSimultaneousTasks[coreId] = Math.min(maxTaskCount, idealSimTasks[coreId]);
            if (coreSimultaneousTasks[coreId] > 0) {
                executableCores.add(coreId);
            }
    	}
    	idealSimultaneousTasks = idealSimTasks;
    }

    public int[] getIdealSimultaneousTasks() {
        return this.idealSimultaneousTasks;
    }

    public int[] getSimultaneousTasks() {
        return coreSimultaneousTasks;
    }

    public boolean canRunNow(ResourceDescription consumption) {
        return taskCount < maxTaskCount && this.checkResource(consumption);
    }

    public void endTask(ResourceDescription consumption) {
        taskCount--;
        releaseResource(consumption);
    }

    public void runTask(ResourceDescription consumption) {
        taskCount++;
        reserveResource(consumption);
    }

    public Integer simultaneousCapacity(Implementation impl) {
        return Math.min(fitCount(impl), maxTaskCount);
    }

    public abstract int compareTo(Resource t);

    public abstract Type getType();

    public abstract String getMonitoringData(String prefix);

    public abstract boolean canRun(Implementation implementation);

    public abstract void update(ResourceDescription resDesc);

    public abstract boolean isAvailable(ResourceDescription rd);

    public abstract boolean markToRemove(ResourceDescription rd);

    public abstract void confirmRemoval(ResourceDescription modification);

    //Internal private methods depending on the resourceType
    abstract Integer fitCount(Implementation impl);

    abstract boolean checkResource(ResourceDescription consumption);

    abstract void reserveResource(ResourceDescription consumption);

    abstract void releaseResource(ResourceDescription consumption);

    public String getResourceLinks(String prefix) {
        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append(getName()).append("\n");
        sb.append(prefix).append("\t Executable Cores:").append(executableCores).append("\n");
        sb.append(prefix).append("\t coreSimultaneousTasks: [").append(coreSimultaneousTasks[0]);
        for (int i = 1; i < CoreManager.coreCount; i++) {
            sb.append(", ").append(coreSimultaneousTasks[i]);
        }
        sb.append("]\n");
        sb.append(prefix).append("\t executableImpls Core-Impl\n");
        for (int i = 0; i < executableImpls.length; ++i) {
        	sb.append(prefix).append("\t\t");
        	for (int j = 0; j < executableImpls[i].length; ++j) {
        		sb.append(executableImpls[i][j]).append(" ");
        	}
        	sb.append("\n");
        }
        return sb.toString();
    }

}
