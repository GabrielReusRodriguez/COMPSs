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

import integratedtoolkit.types.Core;
import integratedtoolkit.types.ResourceDescription;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.LinkedList;
import java.util.Set;
import java.util.TreeSet;

public class ResourcePool {

    //Resource Sets:
    //  Physical Ressources (readed from xml)
    private HashMap<String, Resource> physicalSet;
    //  Critical Resources (can't be destroyed by periodical resource policy)
    private HashMap<String, Resource> criticalSet;
    //  Non Critical Resources (can be destroyed by periodical resource policy)
    private HashMap<String, Resource> nonCriticalSet;
    //Map: coreId -> List <names of the resources where core suits>
    private LinkedList<Resource>[] coreToResource;
    //Map: coreId -> maxTaskCount accepted for that core
    private Integer[] coreMaxTaskCount;
    //TreeSet : Pritority on criticalSet based on cost
    private TreeSet<Resource> criticalOrder;

    public static final org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger(integratedtoolkit.log.Loggers.RESOURCES);

    public ResourcePool(int coreCount) {
        physicalSet = new HashMap<String, Resource>();
        criticalSet = new HashMap<String, Resource>();
        nonCriticalSet = new HashMap<String, Resource>();
        coreToResource = new LinkedList[coreCount];
        coreMaxTaskCount = new Integer[coreCount];
        for (int i = 0; i < coreCount; i++) {
            coreToResource[i] = new LinkedList<Resource>();
            coreMaxTaskCount[i] = 0;
        }
        criticalOrder = new TreeSet<Resource>();
    }

    //Adds a new Resource on the Physical list
    public void addPhysical(String resourceName, int maxTaskCount, ResourceDescription description) {
        Resource newResource = new Resource(resourceName, maxTaskCount, description);
        physicalSet.put(resourceName, newResource);
        logger.info(resourceName + " has been added to available resource pool as a physical resource with " + maxTaskCount + " slots");
    }

    //Adds a new Resource on the Critical list
    public void addCritical(String resourceName, int maxTaskCount, ResourceDescription description) {
        Resource newResource = new Resource(resourceName, maxTaskCount, description);
        criticalSet.put(resourceName, newResource);
        criticalOrder.add(newResource);
        logger.info(resourceName + " has been added to available resource pool with " + maxTaskCount + " slots");
    }

    public boolean updateResource(String name, int slotCountVariation, List<Integer> coreIds, ResourceDescription rd) {
        Resource res = getResource(name);
        if (res == null) {//No existia s'ha de crear
            return false;
        }
        res.updateSlots(slotCountVariation);
        res.description.setProcessorCPUCount(res.description.getProcessorCPUCount() + rd.getProcessorCPUCount());

        int coreChanges[] = new int[Core.coreCount];
        for (int i : res.executableCores) {
            coreChanges[i] += 1;
        }
        for (int i : coreIds) {
            coreChanges[i] += 2;
        }

        for (int i = 0; i < Core.coreCount; i++) {
            switch (coreChanges[i]) {
                case 1: // it's in the old ones but not in the new --> remove slots of the core
                    coreMaxTaskCount[i] += slotCountVariation;
                    //Unlink resource of the core
                    List<Resource> resources = coreToResource[i];
                    int j = 0;
                    for (Resource r : resources) {
                        if (r.name.compareTo(name) == 0) {
                            break;
                        }
                        j++;
                    }
                    resources.remove(j);
                    break;
                case 2: // it's in the new ones but not in the old --> add
                    coreMaxTaskCount[i] += slotCountVariation;
                    coreToResource[i].add(res);
                    break;
                case 3: // it's in the old ones and in the new --> update
                    coreMaxTaskCount[i] += slotCountVariation;
                    break;
                default://Resource can't and couldn't run that core
                    break;
            }
        }
        if (res.description.getProcessorCPUCount() == 0) {
            for (int i : res.executableCores) {
                //Unlink resource of the core
                List<Resource> resources = coreToResource[i];
                for (int j = 0; j < resources.size(); j++) {
                    resources.remove(res);
                }

            }
            //Remove it from the sets
            Resource resource = criticalSet.remove(res.name);
            if (resource == null) {
                resource = nonCriticalSet.remove(res.name);
            } else {
                criticalOrder.remove(resource);
            }
            CloudManager.stoppingResource(name);
        }
        return true;
    }

    //Returns if the vm is a cloud machine
    public boolean canBeDeleted(String resourceName) {
        boolean physical = physicalSet.get(resourceName) != null;
        return !(physical);
    }

    //Returns if the vm isn't part of the critical nor physical set
    public boolean isDispensable(String resourceName) {
        return (nonCriticalSet.get(resourceName) != null);
    }

    public boolean canModify(ResourceDescription rd) {
        Resource res = nonCriticalSet.get(rd.getName());
        if (res != null) {
            return true;
        }
        res = criticalSet.get(rd.getName());
        return (res != null && res.description.getProcessorCPUCount() > rd.getProcessorCPUCount());
    }

    //Returns if the resource can execute any core with remaining tasks
    public boolean isUseless(String resourceName, Map<Integer, Integer> coreTasksCounts) {
        try {
            Resource resource = getResource(resourceName);
            if (resource == null) {
                return false;
            }
            Boolean useful = resource.taskCount != 0;
            LinkedList<Integer> executableCores = resource.executableCores;
            for (int i = 0; i < executableCores.size() && !useful; i++) {
                int coreId = executableCores.get(i);
                useful = (coreTasksCounts.get(coreId) != null) && coreTasksCounts.get(coreId) > (coreMaxTaskCount[coreId] - resource.getSlots());
            }
            return !useful;
        } catch (Exception e) {
            logger.info("Error checking if " + resourceName + " is an useful resource", e);
            return false;
        }
    }

    //Returns the number of slots of the core
    public Integer[] getCoreMaxTaskCount() {
        return coreMaxTaskCount;
    }

    public int getCoreMaxTaskCountPerCore(int coreId) {
        return coreMaxTaskCount[coreId];
    }

    //Adds taskCount slots to the Core
    public void addTaskCountToCore(int coreId, int taskCount) {
        coreMaxTaskCount[coreId] += taskCount;
    }

    //Removes taskCount slots to the Core
    public void removeTaskCountFromCore(Integer coreId, int taskCount) {
        coreMaxTaskCount[coreId] -= taskCount;
    }

    //Links a resource to a core (resource resourceName can execute coreId core)
    public void joinResourceToCore(String resourceName, Integer coreId) {
        Resource resource = getResource(resourceName);
        if (resource == null) {
            return;
        }

        resource.addCore(coreId);
        LinkedList<Resource> resources = coreToResource[coreId];
        if (resources == null) {
            resources = new LinkedList<Resource>();
            coreToResource[coreId] = resources;
        }
        resources.add(resource);
        coreMaxTaskCount[coreId] += resource.getSlots();
    }

    //Returns a list with all coreIds that can be executed by the resource res
    public List<Integer> getExecutableCores(String res) {
        Resource resource = getResource(res);
        if (resource == null) {
            return new LinkedList();
        }
        return resource.executableCores;
    }

    //Selects a subset of the critical set able to execute all the cores
    public void defineCriticalSet() {
        boolean[] runnable = new boolean[coreToResource.length];
        for (int i = 0; i < coreToResource.length; i++) {
            runnable[i] = false;
        }

        Object[] physicalResourcesNames = physicalSet.keySet().toArray();
        String resourceName;
        for (int physicalResourceIndex = 0; physicalResourceIndex < physicalResourcesNames.length; physicalResourceIndex++) {
            resourceName = (String) physicalResourcesNames[physicalResourceIndex];
            Resource res = physicalSet.get(resourceName);
            for (int i = 0; i < res.executableCores.size(); i++) {
                runnable[res.executableCores.get(i)] = true;
            }
        }
        for (Resource resource : criticalOrder) {
            resourceName = resource.description.getName();
            boolean needed = false;
            for (int i = 0; i < resource.executableCores.size() && !needed; i++) {
                needed = needed || !runnable[resource.executableCores.get(i)];
            }
            if (needed) {
                for (int i = 0; i < resource.executableCores.size(); i++) {
                    runnable[resource.executableCores.get(i)] = true;
                }
            } else {
                criticalSet.remove(resourceName);
                criticalOrder.remove(resource);
                nonCriticalSet.put(resourceName, resource);
            }
        }
    }

    //Assigns a number of slots to a resource
    public void setMaxTaskCount(String resourceName, Integer taskCount) {
        Resource modified = getResource(resourceName);
        if (modified == null) {
            return;
        }
        modified.setSlots(taskCount);
    }

    //Releases all the slots of the resource
    public void freeAllResources() {
        Resource[] set;
        if (physicalSet != null && !physicalSet.isEmpty()) {
            set = (Resource[]) physicalSet.values().toArray();
            for (int i = 0; i < set.length; i++) {
                set[i].taskCount = 0;
            }
        }
        if (criticalSet != null && !criticalSet.isEmpty()) {
            set = (Resource[]) criticalSet.values().toArray();
            for (int i = 0; i < set.length; i++) {
                set[i].taskCount = 0;
            }
        }
        if (nonCriticalSet != null && !nonCriticalSet.isEmpty()) {
            set = (Resource[]) nonCriticalSet.values().toArray();
            for (int i = 0; i < set.length; i++) {
                set[i].taskCount = 0;
            }
        }
    }

    //Updates the number of free slots of the resource
    public void modifyTaskCount(String resourceName, int addition) {
        Resource r = getResource(resourceName);
        if (r == null) {
            return;
        }
        r.taskCount += addition;
    }

    //Deletes a resource from the pool
    public void delete(String resourceName) {
        //Remove it from the sets
        Resource resource = physicalSet.remove(resourceName);
        if (resource != null) {
            return;
        }

        resource = criticalSet.remove(resourceName);

        if (resource == null) {
            resource = nonCriticalSet.remove(resourceName);
        } else {
            criticalOrder.remove(resource);
        }

        //Remove all its relations
        for (int i = 0; i < resource.executableCores.size(); i++) {
            //slots of the core
            coreMaxTaskCount[resource.executableCores.get(i)] -= resource.getSlots();
            //Unlink resource of the core
            List<Resource> resources = coreToResource[resource.executableCores.get(i)];
            for (int j = 0; j < resources.size(); j++) {
                if ((resources.get(j)).name.compareTo(resourceName) == 0) {
                    resources.remove(j);
                }
            }
        }
    }

    public Set<ResourceDescription> getAllEditableResources(float[] cores) {
        HashSet<ResourceDescription> resourceNames = new HashSet<ResourceDescription>();
        for (Resource resource : nonCriticalSet.values()) {
            boolean exists = true;
            for (int coreId : resource.executableCores) {
                if (cores[coreId] < 1) {
                    exists = false;
                }
            }
            if (exists) {
                resourceNames.add(resource.description);
            }
        }
        for (Resource resource : criticalSet.values()) {
            boolean exists = true;
            for (int coreId : resource.executableCores) {
                if (cores[coreId] < 1) {
                    exists = false;
                }
            }
            if (exists) {
                resourceNames.add(resource.description);
            }
        }
        return resourceNames;
    }

    //Returns a List with the name of all the non critical resources able to execute the core coreId
    public List<String> findDeletableResources(int coreId) {
        LinkedList<String> resourceNames = new LinkedList<String>();
        for (Resource resource : nonCriticalSet.values()) {
            boolean exists = false;
            for (int i = 0; i < resource.executableCores.size() && !exists; i++) {
                exists |= resource.executableCores.get(i) == coreId;
            }
            if (exists) {
                resourceNames.add(resource.name);
            }
        }
        return resourceNames;
    }

    //Returns the name of all the resources able to execute coreId
    public List<String> findResources(int coreId) {
        LinkedList<String> resourceNames = new LinkedList<String>();
        if (coreId == ResourceManager.ALL_RESOURCES) {
            if (physicalSet != null && !physicalSet.isEmpty()) {
                for (int i = 0; i < physicalSet.size(); i++) {
                    resourceNames.add(((Resource) physicalSet.values().toArray()[i]).name);
                }
            }
            if (criticalSet != null && !criticalSet.isEmpty()) {
                for (int i = 0; i < criticalSet.size(); i++) {
                    resourceNames.add(((Resource) criticalSet.values().toArray()[i]).name);
                }
            }
            if (nonCriticalSet != null && !nonCriticalSet.isEmpty()) {
                for (int i = 0; i < nonCriticalSet.size(); i++) {
                    resourceNames.add(((Resource) nonCriticalSet.values().toArray()[i]).name);
                }
            }
            return resourceNames;
        }
        LinkedList<Resource> resources = coreToResource[coreId];

        for (int res = 0; res < resources.size(); res++) {
            resourceNames.add(resources.get(res).name);
        }
        return resourceNames;
    }

    //return all the rescources able to execute a task of the core taking care the slots
    public List<String> findResources(int coreId, boolean preschedule) {
        LinkedList<String> resourceNames = new LinkedList<String>();
        if (coreId == ResourceManager.ALL_RESOURCES) {
            if (physicalSet != null && !physicalSet.isEmpty()) {
                for (int i = 0; i < physicalSet.size(); i++) {
                    resourceNames.add(((Resource) physicalSet.values().toArray()[i]).name);
                }
            }
            if (criticalSet != null && !criticalSet.isEmpty()) {
                for (int i = 0; i < criticalSet.size(); i++) {
                    resourceNames.add(((Resource) criticalSet.values().toArray()[i]).name);
                }
            }
            if (nonCriticalSet != null && !nonCriticalSet.isEmpty()) {
                for (int i = 0; i < nonCriticalSet.size(); i++) {
                    resourceNames.add(((Resource) nonCriticalSet.values().toArray()[i]).name);
                }
            }
            return resourceNames;
        }
        LinkedList<Resource> resources = coreToResource[coreId];
        for (int res = 0; res < resources.size(); res++) {
            Resource resource = resources.get(res);
            if (preschedule) {
                if (resource.taskCount < 2 * resource.getSlots()) {
                    resourceNames.add(resource.name);
                }
            } else {
                if (resource.taskCount < resource.getSlots()) {
                    resourceNames.add(resources.get(res).name);
                }
            }

        }
        return resourceNames;
    }

    //Checks if resourcename can execute coreId
    public boolean matches(String resourceName, int coreId) {
        Resource resource = getResource(resourceName);
        if (resource == null) {
            return false;
        }
        boolean exists = false;
        for (int i = 0; i < resource.executableCores.size() && !exists; i++) {
            exists |= resource.executableCores.get(i) == coreId;
        }
        return exists;
    }

    //Checks if resourcename can execute coreId taking care the slots
    public boolean matches(String resourceName, int coreId, boolean presched) {
        Resource resource = getResource(resourceName);
        if (resource == null) {
            return false;
        }
        for (int i = 0; i < resource.executableCores.size(); i++) {
            if (resource.executableCores.get(i) == coreId) {
                if (presched) {
                    return resource.taskCount < 2 * resource.getSlots();
                } else {
                    return resource.taskCount < resource.getSlots();
                }
            }
        }
        return false;
    }

    //returns the number of slots of a resource
    public int getMaxTaskCount(String resourceName) {
        Resource resource = getResource(resourceName);
        if (resource == null) {
            return 0;
        }
        return resource.getSlots();
    }

    //deletes all the resources that are not going to execute any task
    public List terminateZeros(Map<Integer, Integer> count) {
        LinkedList<String> toShutdown = new LinkedList();
        for (int coreIndex = 0; coreIndex < coreToResource.length; coreIndex++) {
            if (count.get(coreIndex) == null || count.get(coreIndex) == 0) { //si no tinc tasques del tipus al graf
                LinkedList<Resource> candidates = coreToResource[coreIndex];
                for (int canIndex = 0; canIndex < candidates.size(); canIndex++) {
                    boolean needed = false;
                    Resource actualCandidate = candidates.get(canIndex);
                    if (nonCriticalSet.containsKey(actualCandidate.name)) {
                        for (int executableIndex = 0; executableIndex < actualCandidate.executableCores.size() && !needed; executableIndex++) {
                            needed = ((count.get(actualCandidate.executableCores.get(executableIndex)) != null && count.get(actualCandidate.executableCores.get(executableIndex)) != 0) || actualCandidate.executableCores.get(executableIndex) < coreIndex);
                        }
                        if (!needed) {
                            toShutdown.add(actualCandidate.name);
                            delete(actualCandidate.name);
                        }
                    }
                }
            }
        }
        return toShutdown;
    }

    //Returns a critical machine able to execute the core
    public String getSafeResource(int coreId) {
        LinkedList<Resource> resources = coreToResource[coreId];
        String ret = "";
        for (Resource r : resources) {
            //Recurs a poder ser crític
            if (criticalSet.containsKey(r.name)) {
                return r.name;
            }
            //Sinó físic
            if (physicalSet.containsKey(r.name)) {
                ret = r.name;
            }
        }
        return ret;
    }

    //deletes all the resources that are not going to execute any task
    public List stopZeros(Map<Integer, Integer> count) {
        LinkedList<String> toShutdown = new LinkedList();
        for (int coreIndex = 0; coreIndex < coreToResource.length; coreIndex++) {
            if (count.get(coreIndex) == null || count.get(coreIndex) == 0) { //si no tinc tasques del tipus al graf
                LinkedList<Resource> candidates = coreToResource[coreIndex];
                for (int canIndex = 0; canIndex < candidates.size(); canIndex++) {
                    boolean needed = false;
                    Resource actualCandidate = candidates.get(canIndex);
                    if (!physicalSet.containsKey(actualCandidate.name)) {
                        for (int executableIndex = 0; executableIndex < actualCandidate.executableCores.size() && !needed; executableIndex++) {
                            needed = ((count.get(actualCandidate.executableCores.get(executableIndex)) != null && count.get(actualCandidate.executableCores.get(executableIndex)) != 0) || actualCandidate.executableCores.get(executableIndex) < coreIndex);
                        }
                        if (!needed) {
                            toShutdown.add(actualCandidate.name);
                            delete(actualCandidate.name);
                        }
                    }
                }
            }
        }
        return toShutdown;
    }

    //returns all the resource information
    private Resource getResource(String resourceName) {
        Resource resource = null;
        resource = physicalSet.get(resourceName);
        if (resource == null) {
            resource = criticalSet.get(resourceName);
        }
        if (resource == null) {
            resource = nonCriticalSet.get(resourceName);
        }
        return resource;
    }

    boolean hasFreeSlots(String chosenResource, boolean preschedule) {
        logger.debug("Chosen Resource: "+ chosenResource);
    	Resource resource = getResource(chosenResource);
    	logger.debug("Resource: "+ resource);
        if (preschedule) {
            return (resource.taskCount < 2 * resource.getSlots());
        } else {
            
        	return resource.taskCount < resource.getSlots();
        }
    }

//Print all links between resources and cores
    public void relacions(List<String> allResources) {

        for (int coreId = 0; coreId < coreToResource.length; coreId++) {
            logger.info("Core " + coreId + " can be executed on " + coreMaxTaskCount[coreId] + " slots");
            LinkedList<Resource> resources = coreToResource[coreId];
            for (int res = 0; res < resources.size(); res++) {
                logger.info("\t|-" + resources.get(res).name + "   (" + resources.get(res).getSlots() + " slots)");
            }
        }

        for (String res : allResources) {
            Resource resource = getResource(res);

            logger.info("- Resource " + res + " can execute the cores:");
            LinkedList<Integer> resources = resource.executableCores;
            for (int j = 0; j < resources.size(); j++) {
                logger.info("\t|-" + resources.get(j));
            }
        }

    }

    /**
     * Obtains a description of all the resources
     *
     * @return String with an XML with data about the current resources
     */
    /*public String getMonitoringData() {
     StringBuilder sb = new StringBuilder();
     for (Resource resource : physicalSet.values()) {
     sb.append(resource.getMonitoringData());
     }
     for (Resource resource : criticalSet.values()) {
     sb.append(resource.getMonitoringData());
     }
     for (Resource resource : nonCriticalSet.values()) {
     sb.append(resource.getMonitoringData());
     }
     return sb.toString();
     }
     */
    ResourceDescription getResourceDescription(String name) {
        Resource r = getResource(name);
        if (r != null) {
            return r.description;
        } else {
            return null;
        }
    }

    public void resizeDataStructures() {
        LinkedList[] coreToResourceTmp = new LinkedList[Core.coreCount];
        System.arraycopy(coreToResource, 0, coreToResourceTmp, 0, coreToResource.length);
        for (int i = coreToResource.length; i < Core.coreCount; i++) {
            coreToResourceTmp[i] = new LinkedList<String>();
        }
        coreToResource = coreToResourceTmp;

        Integer[] coreMaxTaskCountTmp = new Integer[Core.coreCount];
        System.arraycopy(coreMaxTaskCount, 0, coreMaxTaskCountTmp, 0, coreMaxTaskCount.length);
        for (int i = coreMaxTaskCount.length; i < Core.coreCount; i++) {
            coreMaxTaskCountTmp[i] = 0;
        }
        coreMaxTaskCount = coreMaxTaskCountTmp;

    }
}

class Resource implements Comparable<Resource> {
    // Name of the resource

    String name;
    //number of slots to execute tasks
    private int slotsCount;
    //number of tasks assigned to the resource
    int taskCount;
    //CoreId that can be executed by this resource
    LinkedList<Integer> executableCores;
    ResourceDescription description;

    public Resource(String name, Integer maxTaskCount, ResourceDescription description) {
        this.name = name;
        this.slotsCount = maxTaskCount;
        this.taskCount = 0;
        this.executableCores = new LinkedList<Integer>();
        this.description = description;
        if (description != null) {
            this.description.setSlots(maxTaskCount);
            this.description.setName(name);
        }
    }

    public void addCore(int id) {
        executableCores.add(id);
    }

    public int getSlots() {
        return slotsCount;
    }

    public void setSlots(int qty) {
        slotsCount = qty;
        if (description != null) {
            description.setSlots(slotsCount);
        }
    }

    public void updateSlots(int variation) {
        slotsCount += variation;
        if (description != null) {
            description.setSlots(slotsCount);
        }
    }

    /*public String getMonitoringData() {
     //StringBuilder sb = new StringBuilder("\t\t<Resource id=\"").append(name).append("\">\n");
     StringBuilder sb = new StringBuilder("\t\t\t<TotalSlots>").append(slotsCount).append("</TotalSlots>\n");
     sb.append("\t\t\t<FreeSlots>").append(slotsCount - taskCount).append("</FreeSlots>\n");
     if (description != null) {
     sb.append("\t\t\t<CPU>").append(description.getProcessorCPUCount()).append("</CPU>\n");
     sb.append("\t\t\t<Memory>").append(description.getMemoryPhysicalSize()).append("</Memory>\n");
     sb.append("\t\t\t<Disk>").append(description.getStorageElemSize()).append("</Disk>\n");
     }
     //sb.append("\t\t</Resource>\n");
     return sb.toString();
     }
     */
    public int compareTo(Resource t) {
        if (t == null) {
            throw new NullPointerException();
        }
        if (description.getValue() == null) {
            if (t.description.getValue() == null) {
                return t.description.getName().compareTo(this.description.getName());
            }
            return 1;
        }
        if (t.description.getValue() == null) {
            return -1;
        }
        float dif = t.description.getValue() - this.description.getValue();
        if (dif > 0) {
            return -1;
        }
        if (dif < 0) {
            return 1;
        }
        return t.description.getName().compareTo(this.description.getName());
    }
}
