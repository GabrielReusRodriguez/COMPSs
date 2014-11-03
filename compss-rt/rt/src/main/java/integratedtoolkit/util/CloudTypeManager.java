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

import integratedtoolkit.types.ResourceDescription;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Set;

public class CloudTypeManager {

    /**
     * Relation between the name of an image and its features
     */
    private HashMap<String, ResourceDescription> types;
    /**
     * Relation between VM and their composing types
     */
    private HashMap<String, HashMap<String, Integer>> vmToType;

    /**
     * Constructs a new CloudImageManager
     */
    public CloudTypeManager() {
        types = new HashMap<String, ResourceDescription>();
        vmToType = new HashMap<String, HashMap<String, Integer>>();
    }

    /**
     * Adds a new instance type which can be used by the Cloud Provider
     *
     * @param rd Description of the resource
     */
    public void add(ResourceDescription rd) {
        types.put(rd.getName(), rd);
    }

    /**
     * Finds all the types provided by the Cloud Provider which fulfill the
     * resource description.
     *
     * @param requested description of the features that the image must provide
     * @return The best intance type provided by the Cloud Provider which
     * fulfills the resource description
     */
    public LinkedList<ResourceDescription> getCompatibleTypes(ResourceDescription requested) {

        LinkedList<ResourceDescription> compatiblesList = new LinkedList<ResourceDescription>();
        if (types.isEmpty()) {
            compatiblesList.add(requested);
        }
        for (ResourceDescription rd : types.values()) {
            ResourceDescription mixedDescription = new ResourceDescription(rd);
            // TODO: CHECK  constraints
            if (mixedDescription.getProcessorArchitecture().compareTo("[unassigned]") == 0) {
                mixedDescription.setProcessorArchitecture(requested.getProcessorArchitecture());
            } else if ((requested.getProcessorArchitecture().compareTo("[unassigned]") != 0)
                    && requested.getProcessorArchitecture().compareTo(mixedDescription.getProcessorArchitecture()) != 0) {
                continue;
            }

            if (mixedDescription.getProcessorCPUCount() == 0) {
                mixedDescription.setProcessorCPUCount(requested.getProcessorCPUCount());
            } else if (requested.getProcessorCPUCount() > 0
                    && requested.getProcessorCPUCount() > mixedDescription.getProcessorCPUCount()) {
                continue;
            }

            if (mixedDescription.getMemoryPhysicalSize() == 0.0f) {
                mixedDescription.setMemoryPhysicalSize(requested.getMemoryPhysicalSize());
            } else if (requested.getMemoryPhysicalSize() > 0.0f
                    && requested.getMemoryPhysicalSize() > mixedDescription.getMemoryPhysicalSize()) {
                continue;
            }

            if (mixedDescription.getStorageElemSize() == 0.0f) {
                mixedDescription.setStorageElemSize(requested.getStorageElemSize());
            } else if (requested.getStorageElemSize() > 0.0f
                    && requested.getStorageElemSize() > mixedDescription.getStorageElemSize()) {
                continue;
            }

            if (mixedDescription.getProcessorSpeed() == 0.0f) {
                mixedDescription.setProcessorSpeed(requested.getProcessorSpeed());
            } else if (requested.getProcessorSpeed() > 0.0f
                    && requested.getProcessorSpeed() > mixedDescription.getProcessorSpeed()) {
                continue;
            }

            if (mixedDescription.getMemoryVirtualSize() == 0.0f) {
                mixedDescription.setMemoryVirtualSize(requested.getMemoryVirtualSize());
            } else if (requested.getMemoryVirtualSize() > 0.0f
                    && requested.getMemoryVirtualSize() > mixedDescription.getMemoryVirtualSize()) {
                continue;
            }

            if (mixedDescription.getMemorySTR() == 0.0f) {
                mixedDescription.setMemorySTR(requested.getMemorySTR());
            } else if (requested.getMemorySTR() > 0.0f
                    && requested.getMemorySTR() > mixedDescription.getMemorySTR()) {
                continue;
            }

            if (mixedDescription.getMemoryAccessTime() == 0.0f) {
                mixedDescription.setMemoryAccessTime(requested.getMemoryAccessTime());
            } else if (requested.getMemoryAccessTime() > 0.0f
                    && requested.getMemoryAccessTime() > mixedDescription.getMemoryAccessTime()) {
                continue;
            }

            if (mixedDescription.getStorageElemAccessTime() == 0.0f) {
                mixedDescription.setStorageElemAccessTime(requested.getStorageElemAccessTime());
            } else if (requested.getStorageElemAccessTime() > 0.0f
                    && requested.getStorageElemAccessTime() > mixedDescription.getStorageElemAccessTime()) {
                continue;
            }

            if (mixedDescription.getStorageElemSTR() == 0.0f) {
                mixedDescription.setStorageElemSTR(requested.getStorageElemSTR());
            } else if (requested.getStorageElemSTR() > 0.0f
                    && requested.getStorageElemSTR() > mixedDescription.getStorageElemSTR()) {
                continue;
            }
            compatiblesList.add(mixedDescription);
        }
        return compatiblesList;
    }

    /**
     * Return all the image names offered by that Cloud Provider
     *
     * @return set of image names offered by that Cloud Provider
     */
    public Set<String> getAllInstanceTypeNames() {
        return types.keySet();
    }

    public void createdVM(String resourceName, String requestType) {
        //System.out.println("VM created " + resourceName + " type " + requestType);
        HashMap<String, Integer> vm = vmToType.get(resourceName);
        if (vm == null) {

            vm = new HashMap<String, Integer>();
            for (String type : types.keySet()) {
                vm.put(type, 0);
            }
            vm.put(requestType, 1);
            //System.out.println("\t Adding new VM to vmToType "+vm);
            vmToType.put(resourceName, vm);
            //System.out.println("Adding to the structure " + resourceName + "-->" + vm);
        } else {
            vm.put(requestType, vm.get(requestType) + 1);
            //System.out.println("Updating structure " + resourceName + "-->" + vm);
        }
    }

    public void removeVM(String resourceName, String requestType) {
        HashMap<String, Integer> vm = vmToType.get(resourceName);
        vm.put(requestType, vm.get(requestType) - 1);
    }

    public void clearAll() {
        vmToType.clear();
    }

    public void removeVM(String name) {
        vmToType.remove(name);
    }

    public LinkedList<ResourceDescription> getPossibleTerminations(ResourceDescription rd) {
        LinkedList<ResourceDescription> candidates = new LinkedList<ResourceDescription>();
        if (types.isEmpty()) {
            candidates.add(new ResourceDescription(rd));
        } else {
            String resourceName = rd.getName();
            HashMap<String, Integer> vm = vmToType.get(resourceName);
            for (java.util.Map.Entry<String, Integer> instances : vm.entrySet()) {
                if (instances.getValue() > 0) {
                    ResourceDescription candidate = new ResourceDescription(types.get(instances.getKey()));
                    //TODO CHECK ALL CONSTRAINTS
                    candidate.setName(resourceName);
                    candidates.add(candidate);
                }
            }
        }
        return candidates;
    }
}
