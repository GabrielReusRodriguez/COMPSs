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



package integratedtoolkit.connectors.azure;

import integratedtoolkit.connectors.Connector;
import integratedtoolkit.connectors.ConnectorException;
import integratedtoolkit.connectors.Cost;
import integratedtoolkit.connectors.utils.Operations;
import integratedtoolkit.types.CloudImageDescription;
import integratedtoolkit.types.ProjectWorker;
import integratedtoolkit.types.ResourceDescription;
import integratedtoolkit.types.ResourceRequest;
import java.util.HashMap;
import java.util.LinkedList;

public class AzureConnector implements Connector, Operations, Cost {

    public AzureConnector(String name, HashMap<String, String> h) {
    }

    public String getId() {
        return "azure.AzureConnector";
    }

    public Long getNextCreationTime() throws ConnectorException {
        return 200000L;
    }

    public boolean turnON(String name, ResourceRequest rR) {
        return false;
    }

    public void reInitialize() {
    }

    public void terminate(String workerName) {
    }

    public void terminate(ResourceDescription rd) {
    }

    public void stopReached() {
    }

    public void terminateALL() throws ConnectorException {
    }

    public void executeTask(String vmId, String user, String command) throws ConnectorException {
    }

    public Object poweron(String name, ResourceDescription constraintsRequested, String diskImage) throws ConnectorException {
        return null;
    }

    public String waitCreation(Object vm, ResourceRequest request) throws ConnectorException {
        return "IP";
    }

    public void configureAccess(String IP, String user) throws ConnectorException {
    }

    public void announceCreation(String IP, String user, LinkedList<ProjectWorker> existingIPs) throws ConnectorException {
    }

    public void announceDestruction(String IP, LinkedList<ProjectWorker> existingVMs) throws ConnectorException {
    }

    public void prepareMachine(String IP, CloudImageDescription cid) throws ConnectorException {
    }

    public void poweroff(String workerId) throws ConnectorException {
    }

    public void poweroff(Object description, String IP) throws ConnectorException {
    }

    public boolean getTerminate() {
        return false;
    }

    public boolean getCheck() {
        return false;
    }

    public boolean worksVirtually() {
        return true;
    }

    public Float getTotalCost() {
        return new Float(0);
    }

    public Float currentCostPerHour() {
        return new Float(0);
    }

    public Float getMachineCostPerHour(ResourceDescription rc) {
        int procs = rc.getProcessorCPUCount();
        float mem = rc.getMemoryPhysicalSize();
        float disk = rc.getStorageElemSize();
        CloudImageDescription diskImage = rc.getImage();
        return new Float(0.10);
    }

    public void destroy(Object vm) throws ConnectorException {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}
