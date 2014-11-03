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

import integratedtoolkit.connectors.Connector;
import integratedtoolkit.connectors.ConnectorException;
import integratedtoolkit.connectors.Cost;
import integratedtoolkit.util.CloudImageManager;
import integratedtoolkit.util.CloudTypeManager;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Set;
import java.util.UUID;

public class CloudProvider {

    public String name;
    private Connector connector;
    private Cost cost;
    private CloudImageManager imgManager;
    private CloudTypeManager typeManager;
    private int currentVMCount;
    private int endingVMCount;
    private Integer limitOfVMs;

    public CloudProvider(String connectorPath, Integer limitOfVMs, HashMap<String, String> connectorProperties, String name)
            throws Exception {
        this.name = name;
        this.limitOfVMs = limitOfVMs;
        imgManager = new CloudImageManager();
        Class<?> conClass = Class.forName(connectorPath);
        Constructor<?> ctor = conClass.getDeclaredConstructors()[0];
        Object conector = ctor.newInstance(name, connectorProperties);
        connector = (Connector) conector;
        cost = (Cost) conector;
        typeManager = new CloudTypeManager();
        currentVMCount = 0;
        endingVMCount = 0;
    }

    public void addCloudImage(CloudImageDescription cid) {
        imgManager.add(cid);
    }

    public void addInstanceType(ResourceDescription rd) {
        typeManager.add(rd);
    }

    public void terminateALL() throws ConnectorException {
        currentVMCount = 0;
        typeManager.clearAll();
        connector.terminateALL();
    }

    public void stopReached() {
        connector.stopReached();
    }

    public float getCurrentCostPerHour() {
        return cost.currentCostPerHour();
    }

    public float getTotalCost() {
        return cost.getTotalCost();
    }

    public void terminate(String vmId) {
        connector.terminate(vmId);
        typeManager.removeVM(name);
        endingVMCount--;
        currentVMCount--;
    }

    public void terminate(ResourceDescription rd) {
        currentVMCount--;
        connector.terminate(rd);
        typeManager.removeVM(rd.getName(), rd.getType());
    }

    public ResourceDescription getBestSolution(ResourceDescription constraints) {
        if (limitOfVMs != null && currentVMCount >= limitOfVMs) {
            return null;
        }

        LinkedList<ResourceDescription> instances = typeManager.getCompatibleTypes(constraints);
        LinkedList<CloudImageDescription> images = imgManager.getCompatibleImages(constraints);
        if (instances.isEmpty()) {
            return null;
        }
        ResourceDescription result = new ResourceDescription(instances.get(0));
        int bestDistance = result.processorCPUCount - constraints.processorCPUCount;
        for (ResourceDescription rd : instances) {
            int distance = rd.processorCPUCount - constraints.processorCPUCount;
            if (bestDistance < 0) {
                if (distance > 0) {
                    result = new ResourceDescription(rd);
                    bestDistance = distance;
                }
            } else if (bestDistance > 0) {
                if (distance > 0 && bestDistance > distance) {
                    result = new ResourceDescription(rd);
                    bestDistance = distance;
                }
            } else { //és el numero d'slots demanat
                result = new ResourceDescription(rd);
                bestDistance = distance;
            }
        }
        //TODO: select best combination
        result.setImage(images.get(0));
        result.setValue(cost.getMachineCostPerHour(result));

        return result;
    }

    public boolean turnON(ResourceRequest rR) {
        currentVMCount++;
        return connector.turnON("compss" + UUID.randomUUID().toString(), rR);
    }

    public long getNextCreationTime() throws Exception {
        return connector.getNextCreationTime();
    }

    public Set<String> getAllImageNames() {
        return imgManager.getAllImageNames();
    }

    public Set<String> getAllInstanceTypeNames() {
        return typeManager.getAllInstanceTypeNames();
    }

    public ResourceDescription getBestIncrease(Integer amount, ResourceDescription constraints) {
        if (limitOfVMs != null && currentVMCount >= limitOfVMs) {
            return null;
        }
        LinkedList<ResourceDescription> instances = typeManager.getCompatibleTypes(constraints);
        LinkedList<CloudImageDescription> images = imgManager.getCompatibleImages(constraints);
        if (instances.isEmpty()) {
            return null;
        }
        ResourceDescription result = instances.get(0);
        
        
        //TODO: select best combination
        int bestDistance = result.processorCPUCount - amount;
        for (ResourceDescription rd : instances) {
            
            int distance = rd.processorCPUCount - amount;
            if (bestDistance < 0) {
                if (distance > 0) {
                    result = new ResourceDescription(rd);
                    bestDistance=distance;
                }
            } else if (bestDistance > 0) {
                if (distance >= 0 && bestDistance > distance) {
                    result = new ResourceDescription(rd);
                    bestDistance=distance;
                }
            }else{//Son iguals
                //Consultar cost de les 2 opcions ????
            }
            
        }
        result.setImage(images.get(0));
        
        
        result.setValue(cost.getMachineCostPerHour(result));
        return result;
    }

    public ResourceDescription getBestReduction(ResourceDescription rd, int slotsToRemove) {
        LinkedList<ResourceDescription> candidates = typeManager.getPossibleTerminations(rd);
        int bestDistance = Integer.MAX_VALUE;
        ResourceDescription result = null;
        for (ResourceDescription candidate : candidates) {
            if (candidate.getProcessorCPUCount() <= slotsToRemove) {
                int distance = slotsToRemove - candidate.getProcessorCPUCount();
                if (distance < bestDistance) {
                    candidate.setValue(cost.getMachineCostPerHour(candidate));
                    result = candidate;
                    bestDistance = distance;
                }
            }
        }
        return result;
    }

    public ResourceDescription getBestDestruction(ResourceDescription rd, int slotsToRemove) {
        LinkedList<ResourceDescription> candidates = typeManager.getPossibleTerminations(rd);
        int bestDistance = Integer.MAX_VALUE;
        ResourceDescription result = null;
        for (ResourceDescription candidate : candidates) {
            int distance = slotsToRemove - candidate.getProcessorCPUCount();
            if (bestDistance < 0) {
                if (distance <= 0 && distance > bestDistance) {
                    candidate.setValue(cost.getMachineCostPerHour(candidate));
                    result = candidate;
                    bestDistance = distance;
                }
            } else {
                if (distance < bestDistance) {
                    candidate.setValue(cost.getMachineCostPerHour(candidate));
                    result = candidate;
                    bestDistance = distance;
                }
                
            }
        }
        return result;
    }

    public int getCurrentVMCount() {
        return currentVMCount;
    }

    public int getUsableVMCount() {
        return currentVMCount - endingVMCount;
    }

    public void stoppingResource(String vmId) {
        endingVMCount++;
    }

    public void refuseWorker(ResourceDescription rd) {
        typeManager.removeVM(rd.getName(), rd.getType());
        currentVMCount--;
    }

    public void createdVM(String resourceName, String requestType) {
        typeManager.createdVM(resourceName, requestType);
    }
}
