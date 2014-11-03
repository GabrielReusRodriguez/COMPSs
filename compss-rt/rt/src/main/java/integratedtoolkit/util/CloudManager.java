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

import integratedtoolkit.types.CloudProvider;
import integratedtoolkit.connectors.ConnectorException;
import integratedtoolkit.log.Loggers;
import integratedtoolkit.types.CloudImageDescription;
import integratedtoolkit.types.ResourceDescription;
import integratedtoolkit.types.ResourceRequest;
import java.util.HashMap;
import java.util.List;
import org.apache.log4j.Logger;

/**
 * The CloudManager class is an utility to manage all the cloud interactions and
 * hide the details of each provider.
 */
public class CloudManager {

    private static boolean useCloud;

    /**
     * Relation between a Cloud provider name and its representation
     */
    private static HashMap<String, CloudProvider> providers;
    /**
     * Relation between a resource name and the representation of the Cloud
     * provider that support it
     */
    private static HashMap<String, CloudProvider> VM2Provider;
    //Logs
    private static final Logger logger = Logger.getLogger(Loggers.RESOURCES);
    private static final boolean debug = logger.isDebugEnabled();

    /**
     * Initilizes the internal data structures
     *
     * @param constraintManager the ConstraintManager to
     */
    public static void initialize() {
        useCloud = false;
        providers = new HashMap();
        VM2Provider = new HashMap();
    }

    /**
     * Configures the runtime to use the Cloud to adapt the resource pool
     * @param useCloud true if enabled
     */
    public static void setUseCloud(boolean useCloud) {
        CloudManager.useCloud = useCloud;
    }

    /**
     * Check if Cloud is used to dynamically adapt the resource pool
     *
     * @return true if it is used
     */
    public static boolean isUseCloud() {
        return useCloud;
    }

    /**
     * Adds a new Provider to the management
     *
     * @param name Identifier of that cloud provider
     * @param connectorPath Package and class name of the connector required to
     * interact with the provider
     * @param limitOfVMs Max amount of VMs that can be running at the same time
     * for that Cloud provider
     * @param connectorProperties Properties to configure the connector
     * @throws Exception Loading the connector by reflection
     */
    public static void newCloudProvider(String name, Integer limitOfVMs, String connectorPath, HashMap connectorProperties)
            throws Exception {
        CloudProvider cp = new CloudProvider(connectorPath, limitOfVMs, connectorProperties, name);
        providers.put(name, cp);
    }

    /**
     * Adds an image description to a Cloud Provider
     *
     * @param providerName Identifier of the Cloud provider
     * @param cid Description of the features offered by that image
     * @throws Exception the cloud provider does not exist
     */
    public static void addImageToProvider(String providerName, CloudImageDescription cid)
            throws Exception {
        CloudProvider cp = providers.get(providerName);
        if (cp == null) {
            throw new Exception("Inexistent Cloud Provider " + providerName);
        }
        cp.addCloudImage(cid);
    }

    /**
     * Adds an instance type description to a Cloud Provider
     *
     * @param providerName Identifier of the Cloud provider
     * @param rd Description of the features offered by that instance type
     * @throws Exception the cloud provider does not exist
     */
    public static void addInstanceTypeToProvider(String providerName, ResourceDescription rd)
            throws Exception {
        CloudProvider cp = providers.get(providerName);
        if (cp == null) {
            throw new Exception("Inexistent Cloud Provider " + providerName);
        }
        cp.addInstanceType(rd);
    }

    /**
     * Prints a list on the log with all the Cloud Information: the cloud
     * providers and which images supports.
     */
    public static void printCloudResources() {
        StringBuilder sb = new StringBuilder("Cloud Providers used during the execution:\n");
        for (java.util.Map.Entry<String, CloudProvider> entry : providers.entrySet()) {
            sb.append("\t *" + entry.getKey() + ":\n");
            for (String image : entry.getValue().getAllImageNames()) {
                sb.append("\t\t Image:" + image + "\n");
            }
            for (String instanceType : entry.getValue().getAllInstanceTypeNames()) {
                sb.append("\t\t Isntance Type:" + instanceType + "\n");
            }
        }
        logger.info(sb);
    }

    /**
     * Asks for the described resources to a Cloud provider. The CloudManager
     * checks the best resource that each provider can offer. Then it picks one
     * of them and it constructs a resourceRequest describing the resource and
     * which cores can be executed on it. This ResourceRequest will be used to
     * ask for that resource creation to the Cloud Provider and returned if the
     * application is accepted.
     *
     * @param constraints description of the resource expected to receive
     * @return Description of the ResourceRequest sent to the CloudProvider.
     * Null if any of the Cloud Providers can offer a resource like the
     * requested one.
     */
    public static ResourceRequest askForResources(ResourceDescription constraints) {
        CloudProvider bestProvider = null;
        ResourceDescription bestConstraints = null;
        Float bestValue = Float.MAX_VALUE;
        for (CloudProvider cp : providers.values()) {
            ResourceDescription rc = cp.getBestSolution(constraints);
            if (rc != null && rc.getValue() != null && rc.getValue() < bestValue) {
                bestProvider = cp;
                bestConstraints = rc;
                bestValue = rc.getValue();
            }
        }
        if (bestConstraints == null) {
            return null;
        }
        List<Integer> coreIds = ConstraintManager.findExecutableCores(bestConstraints);
        int CPUCount;
        if (bestConstraints.getProcessorCPUCount() == 0) {
            CPUCount = 1;
        } else {
            CPUCount = bestConstraints.getProcessorCPUCount();
        }
        ResourceRequest rR = new ResourceRequest(bestConstraints, CPUCount, coreIds);
        try {
            if (bestProvider.turnON(rR)) {
                return rR;
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The CloudManager ask for resources that can execute certain amount of
     * cores at the same time. It checks the best resource that each provider
     * can offer to execute that amount of cores and picks one of them. It
     * constructs a resourceRequest describing the resource and which cores can
     * be executed on it. This ResourceRequest will be used to ask for that
     * resource creation to the Cloud Provider and returned if the application
     * is accepted.
     *
     * @param amount amount of slots
     * @param constraints festures of the resource
     * @return
     */
    public static ResourceRequest askForResources(Integer amount, ResourceDescription constraints) {
        CloudProvider bestProvider = null;
        ResourceDescription bestConstraints = null;
        Float bestValue = Float.MAX_VALUE;

        for (CloudProvider cp : providers.values()) {
            ResourceDescription rc = cp.getBestIncrease(amount, constraints);
            if (rc != null && rc.getValue() < bestValue) {
                bestProvider = cp;
                bestConstraints = rc;
                bestValue = rc.getValue();
            }
        }
        if (bestConstraints == null) {
            return null;
        }
        List<Integer> coreIds = ConstraintManager.findExecutableCores(bestConstraints);
        int CPUCount;
        if (bestConstraints.getProcessorCPUCount() == 0) {
            CPUCount = 1;
        } else {
            CPUCount = bestConstraints.getProcessorCPUCount();
        }
        ResourceRequest rR = new ResourceRequest(bestConstraints, CPUCount, coreIds);
        try {
            if (bestProvider.turnON(rR)) {
                return rR;
            } else {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The CloudManager looks for the best option to reduce a certain amount of
     * slots from an existing resource (max: slotsToRemove). It looks for the
     * Cloud provider who is offering the resource and asks which is the best
     * option to reduce it. If there is no option to reduce it return null.
     *
     * @param rd description of the resource that should be reduced
     * @param slotsToRemove amount of resources that should be freed
     * @return best option to reduce the described resource
     */
    public static ResourceDescription askForReduction(ResourceDescription rd, int slotsToRemove) {
        CloudProvider cp;
        synchronized (VM2Provider) {
            cp = VM2Provider.get(rd.getName());
        }
        return cp.getBestReduction(rd, slotsToRemove);
    }

    /**
     * The CloudManager looks for the best option to reduce a certain amount of
     * slots from an existing resource (there's no max). It looks for the Cloud
     * provider who is offering the resource and asks which is the best option
     * to reduce it. It always find an option even if it means destroying the
     * whole resource.
     *
     * @param rd description of the resource that should be reduced
     * @param slotsToRemove amount of resources that should be freed
     * @return best option to reduce the described resource
     */
    public static ResourceDescription askForDestruction(ResourceDescription rd, int slotsToRemove) {
        CloudProvider cp;
        synchronized (VM2Provider) {
            cp = VM2Provider.get(rd.getName());
        }
        return cp.getBestDestruction(rd, slotsToRemove);
    }

    /**
     * Cloudmanager terminates all the resource associated to that resource name
     *
     * @param resourceName
     */
    public static void terminate(String resourceName) {
        synchronized (VM2Provider) {
            CloudProvider cp = VM2Provider.get(resourceName);
            if (cp != null) {
                cp.terminate(resourceName);
            }
        }
    }

    /**
     * Cloudmanager terminates all the resources associated under the same name
     *
     * @param rd Description of the resource
     */
    public static void terminate(ResourceDescription rd) {
        synchronized (VM2Provider) {
            CloudProvider cp = VM2Provider.get(rd.getName());
            if (cp != null) {
                cp.terminate(rd);
            }
        }
    }

    /**
     * The cloudmanager knows that the resource with name resourceName has been
     * terminated.
     *
     * @param resourceName name of the resource
     */
    public static void notifyShutdown(String resourceName) {
        synchronized (VM2Provider) {
            CloudProvider cp = VM2Provider.remove(resourceName);
        }
    }

    /**
     * CloudManager terminates all the resources obtained from any provider
     *
     * @throws ConnectorException
     */
    public static void terminateALL() throws ConnectorException {
        for (CloudProvider cp : providers.values()) {
            cp.terminateALL();
        }
    }

    /**
     * Computes the cost per hour of the whole cloud resource pool
     *
     * @return the cost per hour of the whole pool
     */
    public static float currentCostPerHour() {
        float total = 0;
        for (CloudProvider cp : providers.values()) {
            total += cp.getCurrentCostPerHour();
        }
        return total;
    }

    /**
     * The CloudManager notifies to all the connectors the end of generation of
     * new tasks
     */
    public static void stopReached() {
        for (CloudProvider cp : providers.values()) {
            cp.stopReached();
        }
    }

    /**
     * The CloudManager computes the accumulated cost of the execution
     *
     * @return cost of the whole execution
     */
    public static float getTotalCost() {
        float total = 0;
        for (CloudProvider cp : providers.values()) {
            total += cp.getTotalCost();
        }
        return total;
    }

    /**
     * Returns how long will take a resource ro be ready since the CloudManager
     * asks for it.
     *
     * @return time required for a resource to be ready
     * @throws Exception Can not get the creation time for some providers.
     */
    public static long getNextCreationTime() throws Exception {
        long total = 0;
        for (CloudProvider cp : providers.values()) {
            total = Math.max(total, cp.getNextCreationTime());
        }
        return total;
    }

    /**
     * Adds a new association between a new Cloud resource and its provider
     *
     * @param resourceName identifier for the new resource
     * @param providerName name of the Cloud Provider
     * @param requestType identifier of the instance Type
     */
    public static void addCloudMachine(String resourceName, String providerName, String requestType) {
        synchronized (VM2Provider) {
            CloudProvider cp = providers.get(providerName);
            VM2Provider.put(resourceName, cp);
            cp.createdVM(resourceName, requestType);
        }
    }

    /**
     * Gets the currently running machines on the cloud
     *
     * @return amount of machines on the Cloud
     */
    public static int getCurrentVMCount() {
        int total = 0;
        for (CloudProvider cp : providers.values()) {
            total += cp.getUsableVMCount();
        }
        return total;
    }

    /**
     * Notifies to the cloud provider representation the termination of the
     * resource resourceName
     *
     * @param resourcename name of the resource that is being stopped
     */
    public static void stoppingResource(String resourcename) {
        CloudProvider cp;
        synchronized (VM2Provider) {
            cp = VM2Provider.get(resourcename);
        }
        cp.stoppingResource(resourcename);

    }

    public static void refusedWorker(ResourceDescription rd, String provider) {
        CloudProvider cp = providers.get(provider);
        cp.refuseWorker(rd);
    }
}
