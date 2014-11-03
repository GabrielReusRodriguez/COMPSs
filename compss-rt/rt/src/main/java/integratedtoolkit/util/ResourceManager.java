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

import java.util.List;
import java.util.LinkedList;

import integratedtoolkit.types.ResourceDescription;
import integratedtoolkit.types.ServiceInstance;
import integratedtoolkit.ITConstants;

import integratedtoolkit.api.impl.IntegratedToolkitImpl;
import javax.xml.XMLConstants;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.apache.xpath.domapi.XPathEvaluatorImpl;

import org.w3c.dom.xpath.XPathEvaluator;
import org.w3c.dom.xpath.XPathResult;

import org.apache.log4j.Logger;
import integratedtoolkit.log.Loggers;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;

import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;

import integratedtoolkit.connectors.ConnectorException;

import integratedtoolkit.types.CloudImageDescription;
import integratedtoolkit.types.Core;
import integratedtoolkit.types.ResourceRequest;
import java.util.Map;
import java.util.HashMap;
import java.util.Map.Entry;

/**
 * The ResourceManager class is an utility to manage all the resources available
 * for the cores execution. It keeps information about the features of each
 * resource and is used as an endpoint to discover which resources can run a
 * core in a certain moment, the total and the available number of slots.
 *
 */
public class ResourceManager {

    //XML Document
    private static Document resourcesDoc;
    //XPath evaluator for resourcesDoc
    private static XPathEvaluator evaluator;
    //Information about resources
    private static ResourcePool pool;

    public static final int ALL_RESOURCES = -1;
    private static final Logger logger = Logger.getLogger(Loggers.TS_COMP);

    /**
     * Constructs a new ResourceManager using the Resources xml file content.
     * First of all, an empty resource pool is created and the Cloud Manager is
     * initialized without any providers. Secondly the resource file is
     * validated and parsed and the toplevel xml nodes are processed in
     * different ways depending on its type: - Resource: a new Physical resource
     * is added to the resource pool with the same id as its Name attribute and
     * as many slots as indicated in the project file. If it has 0 slots or it
     * is not on the project xml, the resource is not included.
     *
     * - Service: a new Physical resource is added to the resource pool with the
     * same id as its wsdl attribute and as many slots as indicated in the
     * project file. If it has 0 slots or it is not on the project xml, the
     * resource is not included.
     *
     * - Cloud Provider: if there is any CloudProvider in the project file with
     * the same name, a new Cloud Provider is added to the CloudManager with its
     * name attribute value as identifier. The CloudManager is configured as
     * described between the project xml and the resources file. From the
     * resource file it gets the properties which describe how to connect with
     * it: the connector path, the endpoint, ... Other properties required to
     * manage the resources are specified on the project file: i.e. the maximum
     * amount of resource dpeloyed on that provider. Some configurations depend
     * on both files. One of them is the list of usable images. The images
     * offered by the cloud provider are on a list on the resources file, where
     * there are specified the name and the software description of that image.
     * On the project file there is a description of how the resources created
     * with that image must be used: username, working directory,... Only the
     * images that have been described in both files are added to the
     * CloudManager
     *
     * @param constraintManager constraint Manager with the constraints of the
     * core that will be run in the managed resources
     * @throws Exception Parsing the xml file or creating new instances for the
     * Cloud providers connectors
     */
    public static void load() throws Exception {
        CloudManager.initialize();
        SharedDiskManager.addMachine(IntegratedToolkitImpl.appHost);
        pool = new ResourcePool(Core.coreCount);

        String resourceFile = System.getProperty(ITConstants.IT_RES_FILE);
        // Parse the XML document which contains resource information
        DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
        docFactory.setNamespaceAware(true);

        resourcesDoc = docFactory.newDocumentBuilder().parse(resourceFile);

        // Validate the document against an XML Schema
        SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Source schemaFile = new StreamSource(System.getProperty(ITConstants.IT_RES_SCHEMA));
        Schema schema = schemaFactory.newSchema(schemaFile);
        Validator validator = schema.newValidator();
        validator.validate(new DOMSource(resourcesDoc));

        // Create an XPath evaluator to solve queries
        evaluator = new XPathEvaluatorImpl(resourcesDoc);
        // resolver = evaluator.createNSResolver(resourcesDoc);
        NodeList nl = resourcesDoc.getChildNodes().item(0).getChildNodes();
        int numRes = 0;
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeName().equals("Resource")) {
                numRes++;
                ResourceDescription rd = new ResourceDescription(n);
                String name = rd.getName();
                SharedDiskManager.addMachine(name);
                String taskCount = ProjectManager.getResourceProperty(name, ITConstants.LIMIT_OF_TASKS);
                if (taskCount != null && Integer.parseInt(taskCount) > 0) {
                    pool.addPhysical(name, Integer.parseInt(taskCount), rd);
                }
                for (int j = 0; j < n.getChildNodes().getLength(); j++) {
                    if (n.getChildNodes().item(j).getNodeName().compareTo("Disks") == 0) {
                        Node disks = n.getChildNodes().item(j);
                        for (int k = 0; k < disks.getChildNodes().getLength(); k++) {
                            if (disks.getChildNodes().item(k).getNodeName().compareTo("Disk") == 0) {
                                Node disk = disks.getChildNodes().item(k);
                                String diskName = disk.getAttributes().getNamedItem("Name").getTextContent();
                                String diskMountpoint = "";
                                for (int ki = 0; ki < disk.getChildNodes().getLength(); ki++) {

                                    if (disk.getChildNodes().item(ki).getNodeName().compareTo("MountPoint") == 0) {
                                        diskMountpoint = disk.getChildNodes().item(ki).getTextContent();
                                    }
                                }
                                SharedDiskManager.addSharedToMachine(diskName, diskMountpoint, name);
                            }
                        }
                    }
                }
            } else if (n.getNodeName().equals("Service")) {
                numRes++;
                String name = n.getAttributes().getNamedItem("wsdl").getTextContent();
                String taskCount = ProjectManager.getResourceProperty(name, ITConstants.LIMIT_OF_TASKS);
                if (taskCount != null && Integer.parseInt(taskCount) > 0) {
                    pool.addPhysical(name, Integer.parseInt(taskCount), null);
                }
            } else if (n.getNodeName().equals("CloudProvider")) {
                String cloudProviderName = n.getAttributes().getNamedItem("name").getTextContent();
                if (!ProjectManager.existsCloudProvider(cloudProviderName)) {
                    continue;
                }
                String connectorPath = "";
                HashMap<String, String> h = new HashMap<String, String>();
                LinkedList<CloudImageDescription> images = new LinkedList();
                LinkedList<ResourceDescription> instanceTypes = new LinkedList<ResourceDescription>();
                for (int ki = 0; ki < n.getChildNodes().getLength(); ki++) {
                    if (n.getChildNodes().item(ki).getNodeName().compareTo("#text") == 0) {
                    } else if (n.getChildNodes().item(ki).getNodeName().compareTo("Connector") == 0) {
                        connectorPath = n.getChildNodes().item(ki).getTextContent();
                    } else if (n.getChildNodes().item(ki).getNodeName().compareTo("ImageList") == 0) {
                        Node imageList = n.getChildNodes().item(ki);
                        for (int image = 0; image < imageList.getChildNodes().getLength(); image++) {
                            Node resourcesImageNode = imageList.getChildNodes().item(image);
                            if (resourcesImageNode.getNodeName().compareTo("Image") == 0) {
                                String imageName = resourcesImageNode.getAttributes().getNamedItem("name").getTextContent();
                                Node projectImageNode = ProjectManager.existsImageOnProvider(cloudProviderName, imageName);
                                if (projectImageNode != null) {
                                    CloudImageDescription cid = new CloudImageDescription(resourcesImageNode, projectImageNode);
                                    images.add(cid);
                                }
                            }
                        }
                    } else if (n.getChildNodes().item(ki).getNodeName().compareTo("InstanceTypes") == 0) {
                        Node instanceTypesList = n.getChildNodes().item(ki);
                        for (int image = 0; image < instanceTypesList.getChildNodes().getLength(); image++) {
                            Node resourcesInstanceTypeNode = instanceTypesList.getChildNodes().item(image);
                            if (resourcesInstanceTypeNode.getNodeName().compareTo("Resource") == 0) {
                                String instanceCode = resourcesInstanceTypeNode.getAttributes().getNamedItem("Name").getTextContent();
                                Node projectTypeNode = ProjectManager.existsInstanceTypeOnProvider(cloudProviderName, instanceCode);
                                if (projectTypeNode != null) {
                                    ResourceDescription rd = new ResourceDescription(resourcesInstanceTypeNode);
                                    instanceTypes.add(rd);
                                }
                            }
                        }
                    } else {
                        h.put(n.getChildNodes().item(ki).getNodeName(), n.getChildNodes().item(ki).getTextContent());
                    }
                }

                HashMap<String, String> properties = ProjectManager.getCloudProviderProperties(cloudProviderName);
                for (Entry<String, String> e : properties.entrySet()) {
                    h.put(e.getKey(), e.getValue());
                }
                CloudManager.newCloudProvider(cloudProviderName, ProjectManager.getCloudProviderLimitOfVMs(cloudProviderName), connectorPath, h);
                try {
                    for (CloudImageDescription cid : images) {
                        CloudManager.addImageToProvider(cloudProviderName, cid);
                    }
                    for (ResourceDescription instance : instanceTypes) {
                        CloudManager.addInstanceTypeToProvider(cloudProviderName, instance);
                    }
                } catch (Exception e) { /* will never be thrown here, we just added the provider */ }
                CloudManager.setUseCloud(true);
            } else if (n.getNodeName().equals("Disk")) {
                String diskName = n.getAttributes().getNamedItem("Name").getTextContent();
                String mountPoint = "";
                for (int j = 0; j < n.getChildNodes().getLength(); j++) {
                    if (n.getChildNodes().item(j).getNodeName().compareTo("Name") == 0) {
                        diskName = n.getChildNodes().item(j).getTextContent();
                    } else if (n.getChildNodes().item(j).getNodeName().compareTo("MountPoint") == 0) {
                        mountPoint = n.getChildNodes().item(j).getTextContent();
                    }
                }
                SharedDiskManager.addSharedToMachine(diskName, mountPoint, IntegratedToolkitImpl.appHost);
            } else if (n.getNodeName().equals("DataNode")) {
                String host = "";
                String path = "";
                for (int j = 0; j < n.getChildNodes().getLength(); j++) {
                    if (n.getChildNodes().item(j).getNodeName().compareTo("Host") == 0) {
                        host = n.getChildNodes().item(j).getTextContent();
                    } else if (n.getChildNodes().item(j).getNodeName().compareTo("Path") == 0) {
                        path = n.getChildNodes().item(j).getTextContent();
                    }
                }
            }
        }
        linkCoresToMachines();
        CloudManager.printCloudResources();
        logger.info(SharedDiskManager.getSharedStatus());
    }

    /**
     * Parses from the Resources XML file the different Services that can be
     * found on the document and returns a list of all the services and the
     * properties required to execute a task there
     *
     * @return list of serviceInstances with the parameters to execute all the
     * services included on the XML file.
     */
    public static List<ServiceInstance> getServices() {
        List<ServiceInstance> services = new LinkedList<ServiceInstance>();
        NodeList nl = resourcesDoc.getChildNodes().item(0).getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            ServiceInstance service = new ServiceInstance();
            if (n.getNodeName().equals("Service")) {
                String wsdl = n.getAttributes().getNamedItem("wsdl").getTextContent();
                service.setWsdl(wsdl);
                if (ProjectManager.containsServiceInstance(wsdl)) {
                    NodeList fields = n.getChildNodes();
                    for (int j = 0; j < fields.getLength(); j++) {
                        Node field = fields.item(j);
                        if (field.getNodeName().equals("Name")) {
                            service.setName(field.getTextContent());
                        } else if (field.getNodeName().equals("Namespace")) {
                            service.setNamespace(field.getTextContent());
                        } else if (field.getNodeName().equals("Port")) {
                            //service.addPort(field.getTextContent());
                        }
                    }
                    services.add(service);
                }
            }
        }
        return services;
    }

    /**
     * Asks for the vm needed for the runtime to be able to execute all method
     * cores.
     *
     * First it groups the constraints of all the methods per Architecture and
     * tries to merge included resource descriptions in order to reduce the
     * amount of required VMs. It also tries to join the unassigned architecture
     * methods with the closer constraints of a defined one. After that it
     * distributes the Initial Vm Count among the architectures taking into
     * account the number of methods that can be run in each architecture.
     *
     * If the amount of different constraints is higher than the Initial VM
     * count it applies an agressive merge method to each architecture in order
     * to fulfill the initial Constraint. It creates a single VM for each final
     * method constraint.
     *
     * Although these agressive merges, the amount of different constraints can
     * be higher than the initial VM Count constraint. In this case, it violates
     * the initial Vm contraint and asks for more resources.
     *
     * @return the amount of requested VM
     */
    public static int addBasicNodes() {

        LinkedList<ResourceDescription> unfulfilledConstraints = getUnfulfilledConstraints();

        if (unfulfilledConstraints.size() == 0) {
            return 0;
        }
        String initialVMs = ProjectManager.getCloudProperty("InitialVMs");
        int initialVMsCount = 0;
        if (initialVMs != null) {
            initialVMsCount = Integer.parseInt(initialVMs);
        }
        int machineCount = initialVMsCount;
        LinkedList<LinkedList<ResourceDescription>> constraintsPerArquitecture = new LinkedList<LinkedList<ResourceDescription>>();
        /*
         * constraintsPerArquitecture has loaded all constraint for each task.
         * architectures has a list of all the architecture names.
         *
         * architectures                     MRlist
         * Intel                =               |  | --> |MR|--|MR|
         * AMD                  =               |  | --> |MR|
         * [unassigned]         =               |  | --> |MR|--|MR|
         */
        LinkedList<String> architectures = classifyArchitectures(constraintsPerArquitecture, unfulfilledConstraints);

        /*
         * Tries to reduce the number of machines per architecture by
         * entering constraints in another core's constraints
         *
         */
        constraintsPerArquitecture = reduceArchitecturesConstraints(constraintsPerArquitecture);


        /*
         * Checks if there are enough Vm for a Unassigned Arquitecture
         * If not it set each unassigned task into the architecture with the most similar task
         *
         * MRList
         * |  | --> |MR|--|MR|--|MR|
         * |  | --> |MR|--|MR|
         *
         */
        constraintsPerArquitecture = reassignUnassignedConstraints(architectures, constraintsPerArquitecture, machineCount);

        /*
         * Tries to reduce the number of machines per architecture by
         * entering constraints in another core's constraints
         *
         */
        constraintsPerArquitecture = reduceArchitecturesConstraints(constraintsPerArquitecture);

        /*
         * Distributes all VMs among all the architectures
         *
         * Total Vm = 2  Intel --> 1   AMD --> 1
         * or
         * Total Vm = 10 Intel -->6    AMD --> 4
         *
         */
        int numArchitectures = constraintsPerArquitecture.size();
        int[] machinesCountPerArchitecture = new int[numArchitectures];
        int[] constraintsCountPerArchitecture = new int[numArchitectures];
        for (int index = 0; index < numArchitectures; index++) {
            machinesCountPerArchitecture[index] = 1;
            constraintsCountPerArchitecture[index] = 0;
            for (int constraintIndex = 0; constraintIndex < constraintsPerArquitecture.get(index).size(); constraintIndex++) {
                constraintsCountPerArchitecture[index] += constraintsPerArquitecture.get(index).get(constraintIndex).getSlots();
            }
        }

        for (int i = numArchitectures; i < machineCount; i++) {
            int opcio = 0;
            float millor = (float) constraintsCountPerArchitecture[0] / (float) machinesCountPerArchitecture[0];
            for (int index = 1; index < constraintsPerArquitecture.size(); index++) {
                if (millor < (float) constraintsCountPerArchitecture[index] / (float) machinesCountPerArchitecture[index]) {
                    millor = (float) constraintsCountPerArchitecture[index] / (float) machinesCountPerArchitecture[index];
                    opcio = index;
                }
            }
            machinesCountPerArchitecture[opcio]++;
        }

        // Asks for the necessary VM
        int createdCount = 0;
        for (int index = 0; index < numArchitectures; index++) {
            createdCount += createBasicType(constraintsPerArquitecture.get(index), machinesCountPerArchitecture[index]);
        }

        logger.info("In order to be able to execute all cores, Resource Manager has asked for " + createdCount + " Cloud resources");
        return createdCount;
    }

    /**
     * Asks for the rest of VM that user wants to start with.
     *
     * After executing the addBasicNodes, it might happen that the number of
     * initial VMs contrained by the user is still not been fulfilled. The
     * addBasicNodes creates up to as much VMs as different methods. If the
     * initial VM Count is higher than this number of methods then there will be
     * still some VM requests missing.
     *
     * The addExtraNodes creates this difference of VMs. First it tries to merge
     * the method constraints that are included into another methods. And
     * performs a less aggressive and more equal distribution.
     *
     * @param alreadyCreated number of already requested VMs
     * @return the number of extra VMs created to fulfill the Initial VM Count
     * constaint
     */
    public static int addExtraNodes(int alreadyCreated) {
        String initialVMs = ProjectManager.getCloudProperty("InitialVMs");
        int initialVMsCount = 0;
        if (initialVMs != null) {
            initialVMsCount = Integer.parseInt(initialVMs);
        }
        int vmCount = initialVMsCount - alreadyCreated;
        if (vmCount <= 0) {
            return 0;
        }

        logger.info("Resource manager still can ask for " + vmCount + " Cloud resources more");

        /*
         * Tries to reduce the number of machines by
         * entering methodConstraints in another method's machine
         *
         */
        LinkedList<ResourceDescription> constraints = new LinkedList<ResourceDescription>();
        for (int i = 0; i < Core.coreCount; i++) {
            ResourceDescription rc = ConstraintManager.getResourceConstraints(i);
            if (rc != null) {
                constraints.add(new ResourceDescription(rc));
            }
        }
        if (constraints.size() == 0) {
            return 0;
        }
        constraints = reduceConstraints(constraints);

        int numTasks = constraints.size();
        int[] vmCountPerContraint = new int[numTasks];
        int[] coreCountPerConstraint = new int[numTasks];

        for (int index = 0; index < numTasks; index++) {
            vmCountPerContraint[index] = 1;
            coreCountPerConstraint[index] = constraints.get(index).getSlots();
        }

        for (int i = 0; i < vmCount; i++) {
            float millor = 0.0f;
            int opcio = 0;
            for (int j = 0; j < constraints.size(); j++) {
                if (millor < ((float) coreCountPerConstraint[j] / (float) vmCountPerContraint[j])) {
                    opcio = j;
                    millor = ((float) coreCountPerConstraint[j] / (float) vmCountPerContraint[j]);
                }
            }
            ResourceRequest rR = CloudManager.askForResources(constraints.get(opcio));
            if (rR != null) {
                for (int coreId = 0; coreId < rR.getRequestedMethodIds().size(); coreId++) {
                    pool.addTaskCountToCore(rR.getRequestedMethodIds().get(coreId), rR.getRequestedTaskCount());
                }
            }
            vmCountPerContraint[opcio]++;
        }

        return vmCount;
    }

    /**
     * Notifies that a request to create a new VM has been refused. The method
     * makes a rollback of that request and removes all the information
     * regarding to it.
     *
     * @param oldTaskCount Amount of slots that that VM should have apported
     * @param coresId Cores that that Vm should be able to run
     * @param rd Description of the VM
     * @param provider Cloud Provider Id who has refused the request
     */
    public static void refuseCloudWorker(int oldTaskCount, List<Integer> coresId, ResourceDescription rd, String provider) {
        for (int i = 0; i < coresId.size(); i++) {
            pool.removeTaskCountFromCore(coresId.get(i), oldTaskCount);
        }
        CloudManager.refusedWorker(rd, provider);
    }

    /**
     * Updates the features of a resource after the response of the Cloud
     * Provider to a request. If the resource doesn't exist, it adds a new
     * resource to the Reosurce Pool.
     *
     * @param name resource Id
     * @param oldCoreIds Core Ids that the VM was expected to be able to run
     * when the request was done
     * @param oldSlotCount amount of slots that the VM was expected to provide
     * when the request was done
     * @param slotVariation variation of slots
     * @param coreIds Core Ids that the new VM is able to run
     * @param rd Description of the received VM
     */
    public static void updateNode(String name, List<Integer> oldCoreIds, int oldSlotCount, int slotVariation, List<Integer> coreIds, ResourceDescription rd) {
        if (oldCoreIds == null) {
            oldCoreIds = pool.getExecutableCores(name);
        }
        for (int i = 0; i < oldCoreIds.size(); i++) {
            pool.removeTaskCountFromCore(oldCoreIds.get(i), oldSlotCount);
        }
        if (!pool.updateResource(name, slotVariation, coreIds, rd)) {
            pool.addCritical(name, slotVariation, rd);
            for (int i = 0; i < coreIds.size(); i++) {
                pool.joinResourceToCore(name, coreIds.get(i));
            }
            pool.defineCriticalSet();
            SharedDiskManager.addMachine(name);
            for (java.util.Map.Entry<String, String> e : rd.getImage().getSharedDisks().entrySet()) {
                SharedDiskManager.addSharedToMachine(e.getKey(), e.getValue(), name);
            }
        }
        printCoresResourcesLinks();
        logger.info("The current Cloud resources set costs " + CloudManager.currentCostPerHour());
    }

    /**
     * Links all the cores with all the resource able to run it (only done for
     * the Physical Machines stored in the XML file)
     */
    private static void linkCoresToMachines() {
        for (int coreId = 0; coreId < Core.coreCount; coreId++) {
            String constraints = ConstraintManager.getConstraints(coreId);
            XPathResult matchingRes = (XPathResult) evaluator.evaluate(constraints,
                    resourcesDoc,
                    /*resolver*/ null,
                    XPathResult.UNORDERED_NODE_ITERATOR_TYPE,
                    null);
            Node n;
            String resourceName;
            while ((n = matchingRes.iterateNext()) != null) {
                // Get current resource and add it to the list
                if (n.getNodeName().equals("Resource")) {
                    resourceName = n.getAttributes().getNamedItem("Name").getTextContent();
                    pool.joinResourceToCore(resourceName, coreId);
                } else if (n.getNodeName().equals("Service")) {
                    resourceName = n.getAttributes().getNamedItem("wsdl").getTextContent();
                    pool.joinResourceToCore(resourceName, coreId);
                }
            }
        }
    }

    /**
     * Returns the name of all the resources able to execute coreId
     *
     * @param coreId Id of the core
     * @return list of all the resource names able to run that core
     */
    public static List<String> findResources(int coreId) {
        return pool.findResources(coreId);
    }

    /**
     * Returns a list with the name of all the non critical resources able to
     * execute a core
     *
     * @param coreId Identifier of the core
     * @return a list with the name of all the non critical resources able to
     * execute a core
     */
    public static List<String> findDeletableResources(int coreId) {
        return pool.findDeletableResources(coreId);
    }

    /**
     * Return a list of all the resources name able to run a task at that moment
     * of the core coreId taking into accoutn the prescheduling
     *
     * @param coreId Id of the task's core
     * @param preschedule true if prescheduling is allowed
     * @return list of all the resources name able to run a task at that moment
     * of the core coreId taking into accoutn the prescheduling
     */
    public static List<String> findResources(int coreId, boolean preschedule) {
        List<String> resources;
        resources = pool.findResources(coreId, false);
        if (preschedule && resources.isEmpty()) {
            resources = pool.findResources(coreId, true);
        }

        return resources;
    }

    /**
     * Checks if a resource can execute a certain core
     *
     * @param resourceName name of the resource
     * @param coreId Id of the core
     * @return true if it can run the core
     */
    public static boolean matches(String resourceName, int coreId) {
        return pool.matches(resourceName, coreId);
    }

    //Checks if resourceName can execute coreId taking care the slots
    public static boolean matches(String resourceName, int coreId, boolean presched) {
        return pool.matches(resourceName, coreId, presched);

    }

    //Assigns a number of slots to a resource
    public static void setMaxTaskCount(String resourceName, String taskCount) {
        pool.setMaxTaskCount(resourceName, Integer.parseInt(taskCount));
    }

    //Occupies a free slot
    public static void reserveResource(String resourceName) {
        pool.modifyTaskCount(resourceName, 1);
    }

    //Releases a busy slot
    public static void freeResource(String resourceName) {
        pool.modifyTaskCount(resourceName, -1);
    }

    //Releases all the slots of the resource
    public void freeAllResources() {
        pool.freeAllResources();
    }

    //Returns a list with all coreIds that can be executed by the resource res
    public static List<Integer> getExecutableCores(String resourceName) {
        return pool.getExecutableCores(resourceName);
    }

    //returns the number of slots of a resource
    public static int getMaxTaskCount(String resourceName) {
        return pool.getMaxTaskCount(resourceName);
    }

    //Returns the number of slots of the core
    public static Integer[] getProcessorsCount() {
        return pool.getCoreMaxTaskCount();
    }

    public static int getProcessorsCount(int coreId) {
        return pool.getCoreMaxTaskCountPerCore(coreId);
    }

    public static boolean isDispensable(String resourceName) {
        return pool.isDispensable(resourceName);
    }
    //Removes a resource from the pool if its an useless noncritical cloud resource

    public static boolean tryToTerminate(String resourceName, Map<Integer, Integer> counts) {
        if (ResourceManager.useCloud()) {
            return false;
        }

        if (!pool.isDispensable(resourceName)) {
            return false;
        }
        return pool.isUseless(resourceName, counts);
    }

    //Removes from the pool all useless resources
    public static List<String> terminateUnbounded(Map<Integer, Integer> counts) {
        //Indicates to the cloud that runtime is stopped
        CloudManager.stopReached();

        List<String> toDelete = pool.terminateZeros(counts);
        return toDelete;
    }

    //Shuts down all cloud resources
    public static void stopVirtualNodes()
            throws Exception {
        CloudManager.terminateALL();
        logger.info("Total Execution Cost :" + CloudManager.getTotalCost());
    }

    //Shuts down all the machine associated to the name name
    public static void terminate(String name) {
        CloudManager.terminate(name);
        logger.info("The current Cloud resources set costs " + CloudManager.currentCostPerHour());
    }

    //Shuts down the resources described in rd
    public static void terminate(ResourceDescription rd) {
        CloudManager.terminate(rd);
        logger.info("The current Cloud resources set costs " + CloudManager.currentCostPerHour());
    }

    //Removes from the list all the Constraints fullfilled by existing resources
    private static LinkedList<ResourceDescription> getUnfulfilledConstraints() {
        LinkedList<ResourceDescription> unfulfilledConstraints = new LinkedList<ResourceDescription>();
        for (int i = 0; i < Core.coreCount; i++) {
            ResourceDescription rc = ConstraintManager.getResourceConstraints(i);
            if (pool.getCoreMaxTaskCountPerCore(i) == 0 && rc != null) {
                unfulfilledConstraints.add(new ResourceDescription(rc));
            }
        }
        return unfulfilledConstraints;
    }

    //classifies the constraints  depending on their arquitecture and leaves it on coreResourceList
    //Return a list with all the Architectures Names
    private static LinkedList<String> classifyArchitectures(LinkedList<LinkedList<ResourceDescription>> constraintsPerArquitecture, LinkedList<ResourceDescription> constraints) {
        LinkedList<String> architectures = new LinkedList<String>();

        //For each core
        for (int i = 0; i < constraints.size(); i++) {
            ResourceDescription mr = constraints.get(i);

            //checks the architecture
            //   Not exists --> creates a new List
            Boolean found = false;
            int indexArchs;
            for (indexArchs = 0; indexArchs < architectures.size() && !found; indexArchs++) {
                found = (architectures.get(indexArchs).compareTo(mr.getProcessorArchitecture()) == 0);
            }
            indexArchs--;

            LinkedList<ResourceDescription> assignedList;
            if (!found) {
                architectures.add(mr.getProcessorArchitecture());
                assignedList = new LinkedList<ResourceDescription>();
                constraintsPerArquitecture.add(assignedList);
            } else {
                assignedList = constraintsPerArquitecture.get(indexArchs);
            }
            //AssignedList has  the list for the resource constraints

            assignedList.add(mr);
            mr.addSlot();
        }

        return architectures;
    }

    private static LinkedList<LinkedList<ResourceDescription>> reduceArchitecturesConstraints(LinkedList<LinkedList<ResourceDescription>> mrList) {
        LinkedList<LinkedList<ResourceDescription>> reduced = new LinkedList<LinkedList<ResourceDescription>>();
        for (int i = 0; i < mrList.size(); i++) {
            reduced.add(reduceConstraints(mrList.get(i)));
        }
        return reduced;
    }

    private static LinkedList<ResourceDescription> reduceConstraints(LinkedList<ResourceDescription> architecture) {

        LinkedList<ResourceDescription> reducedArchitecture = new LinkedList<ResourceDescription>();
        LinkedList<boolean[]> values = new LinkedList<boolean[]>();

        for (int j = 0; j < architecture.size(); j++) {
            boolean[] taskCount = new boolean[architecture.size()];
            values.add(taskCount);
        }

        boolean[] invalid = new boolean[architecture.size()];
        for (int j = 0; j < architecture.size(); j++) {
            for (int k = j + 1; k < architecture.size(); k++) {
                switch (architecture.get(j).into(architecture.get(k))) {
                    case 2: //el segon és mes gran --> invalidem el primer i segon[j]=true
                        invalid[j] = true;
                        values.get(k)[j] = true;
                        break;
                    case 1: //el primer és mes gran --> invalidem el segon i primer[k]=true
                        invalid[k] = true;
                        values.get(j)[k] = true;
                        break;
                    case 0: //Són el mateix-> m'invalido primer pero primer[k]=true i segon[j]=true
                        invalid[j] = true;
                        values.get(k)[j] = true;
                        values.get(j)[k] = true;
                        break;
                    default:

                        break;
                }
            }
        }

        for (int j = 0; j < architecture.size(); j++) {
            if (!invalid[j]) {
                reducedArchitecture.add(architecture.get(j));
                int count = 1;
                for (int k = 0; k < architecture.size(); k++) {
                    if (values.get(j)[k]) {
                        count++;
                    }
                }
                architecture.get(j).setSlots(count);
            }
        }

        return reducedArchitecture;
    }

    private static LinkedList<LinkedList<ResourceDescription>> reassignUnassignedConstraints(LinkedList<String> architectures, LinkedList<LinkedList<ResourceDescription>> mrList, int machineCount) {
        LinkedList<ResourceDescription> unassigned = new LinkedList<ResourceDescription>();
        LinkedList<LinkedList<ResourceDescription>> assigned = new LinkedList<LinkedList<ResourceDescription>>();
        for (int i = 0; i < architectures.size(); i++) {
            if (architectures.get(i).compareTo("[unassigned]") == 0) {
                unassigned.addAll(mrList.get(i));
            } else {
                assigned.add(mrList.get(i));
            }
        }

        if ((assigned.size() < machineCount || assigned.size() == 0) && unassigned.size() != 0) {
            assigned.add(unassigned);
        } else {
            for (int unassignedIndex = 0; unassignedIndex < unassigned.size(); unassignedIndex++) {
                Boolean posat = false;

                int optionsBestArchitecture = 0;
                Float optionsBestDifference = Float.MAX_VALUE;

                ResourceDescription candidate = unassigned.get(unassignedIndex);

                for (int architecturesIndex = 0; architecturesIndex < assigned.size() && !posat; architecturesIndex++) {
                    for (int taskIndex = 0; taskIndex < assigned.get(architecturesIndex).size() && !posat; taskIndex++) {
                        float difference = candidate.difference(assigned.get(architecturesIndex).get(taskIndex));
                        if (optionsBestDifference < 0) {
                            if (difference < 0) {
                                if (difference > optionsBestDifference) {
                                    optionsBestArchitecture = architecturesIndex;
                                    optionsBestDifference = difference;
                                }
                            }
                        } else {
                            if (difference < optionsBestDifference) {
                                optionsBestArchitecture = architecturesIndex;
                                optionsBestDifference = difference;
                            }
                        }
                    }
                }
                assigned.get(optionsBestArchitecture).add(candidate);
            }
        }
        return assigned;
    }

    public static String[] getBestSafeResourcePerCore() {
        int coreCount = Core.coreCount;
        String[] bestResource = new String[coreCount];
        for (int i = 0; i < coreCount; i++) {
            if (ConstraintManager.getResourceConstraints(i) != null) {
                bestResource[i] = pool.getSafeResource(i);
            } else {
                bestResource[i] = null;
            }
        }
        return bestResource;

    }

    //retorna el número de màquines creades.
    private static int createBasicType(LinkedList<ResourceDescription> resourceConstraintsList, int vmCount) {
        LinkedList<ResourceDescription> sortedList = new LinkedList<ResourceDescription>();
        LinkedList newList = new LinkedList();
        LinkedList<Integer> bestChoice = new LinkedList<Integer>();
        LinkedList<Float> choiceRatio = new LinkedList<Float>();
        sortedList.add(resourceConstraintsList.get(0));

        //sort Resources by Processor & MemoryPhysicalSize
        for (int originalIndex = 1; originalIndex < resourceConstraintsList.size(); originalIndex++) {
            int sortedIndex;
            boolean minimum = false;
            for (sortedIndex = 0; sortedIndex < sortedList.size() && !minimum; sortedIndex++) {
                Float difference = resourceConstraintsList.get(originalIndex).difference(sortedList.get(sortedIndex));
                minimum = difference >= 0;
            }
            if (minimum) {
                sortedIndex--;
            }
            sortedList.add(sortedIndex, resourceConstraintsList.get(originalIndex));
        }

        // join Resources if needed
        LinkedList<Float> differences = new LinkedList<Float>();
        for (int i = 1; i < sortedList.size(); i++) {
            differences.add(sortedList.get(i - 1).difference(sortedList.get(i)));
        }

        while (sortedList.size() > vmCount) {
            int index = 0;
            float min = differences.get(0);
            for (int i = 0; i < differences.size(); i++) {
                if (differences.get(i) <= min) {
                    min = differences.get(i);
                    index = i;
                }
            }

            sortedList.get(index).join(sortedList.get(index + 1));
            sortedList.remove(index + 1);
            if (index - 1 >= 0) {
                differences.set(index - 1, sortedList.get(index - 1).difference(sortedList.get(index)));
            }
            if (index + 1 < sortedList.size()) {
                differences.set(index + 1, sortedList.get(index).difference(sortedList.get(index + 1)));
            }
            differences.remove(index);
        }

        //distribute vm by user number
        int typeCount = sortedList.size();
        int[] machinesCountPerType = new int[typeCount];

        //For each kind of machine must create a new machine with certain requirements
        for (int resourceIndex = 0; resourceIndex < sortedList.size(); resourceIndex++) {
            ResourceRequest rR = CloudManager.askForResources(sortedList.get(resourceIndex));
            if (rR != null) {
                for (int i = 0; i < rR.getRequestedMethodIds().size(); i++) {
                    pool.addTaskCountToCore(rR.getRequestedMethodIds().get(i), rR.getRequestedTaskCount());
                }
            }
        }

        for (int resourceIndex = 0; resourceIndex < sortedList.size(); resourceIndex++) {
            machinesCountPerType[resourceIndex] = 1;
            float ratio = (float) sortedList.get(resourceIndex).getSlots() / (float) machinesCountPerType[resourceIndex];
            int i;
            for (i = 0; i < choiceRatio.size() && ratio < choiceRatio.get(i); i++) {
            }
            bestChoice.add(i, resourceIndex);
            choiceRatio.add(i, ratio);
            newList.add(resourceConstraintsList.get(resourceIndex));
        }

        return newList.size();
    }

    public static Long getCreationTime()
            throws Exception {
        try {
            return CloudManager.getNextCreationTime();
        } catch (ConnectorException e) {
            throw new Exception(e);
        }
    }

    public static boolean useCloud() {
        return CloudManager.isUseCloud();
    }

    public static void printCoresResourcesLinks() {
        List<String> allResources = findResources(ALL_RESOURCES, false);
        pool.relacions(allResources);
    }

    public static void increaseResources(LinkedList<Integer> neededResources) {
        //Needed resources could be joined in a single request
        ResourceDescription finalRequest = ConstraintManager.getResourceConstraints(neededResources.get(0));
        ResourceRequest rR = CloudManager.askForResources(finalRequest);
        if (rR != null) {
            for (int coreId = 0; coreId < rR.getRequestedMethodIds().size(); coreId++) {
                pool.addTaskCountToCore(rR.getRequestedMethodIds().get(coreId), rR.getRequestedTaskCount());
            }
        }
    }

    public static void increaseResources(HashMap<Integer, Integer> neededResources) {
        //Needed resources could be joined in a single request
        //LinkedList<Integer> amount = new LinkedList();
        //LinkedList<ResourceDescription> constraints = new LinkedList();
        int maxAmount = -1;
        ResourceDescription bestDescription = null;
        for (java.util.Map.Entry<Integer, Integer> e : neededResources.entrySet()) {
            //amount.add(e.getValue());
            if (e.getValue() > maxAmount) {
                bestDescription = ConstraintManager.getResourceConstraints(e.getKey());
                maxAmount = e.getValue();
            }
            //constraints.add(constrManager.getResourceConstraints(e.getKey()));
        }
        ResourceRequest rR = CloudManager.askForResources(maxAmount, bestDescription);
        if (rR != null) {
            for (int coreId = 0; coreId < rR.getRequestedMethodIds().size(); coreId++) {
                pool.addTaskCountToCore(rR.getRequestedMethodIds().get(coreId), rR.getRequestedTaskCount());
            }
        }
    }

    public static ResourceDescription chooseDestruction(float[] slotsToReducePerCore) {
        java.util.Set<ResourceDescription> resources = pool.getAllEditableResources(slotsToReducePerCore);
        ResourceDescription bestCandidate = null;
        float bestValue = Float.MIN_VALUE;
        for (ResourceDescription res : resources) {
            float slotsToRemove = Float.MAX_VALUE;
            List<Integer> executableCores = pool.getExecutableCores(res.getName());
            for (int coreId : executableCores) {
                if (slotsToRemove > slotsToReducePerCore[coreId]) {
                    slotsToRemove = slotsToReducePerCore[coreId];
                }
            }
            ResourceDescription candidate = CloudManager.askForDestruction(res, (int) slotsToRemove);
            if (pool.canModify(candidate)) {
                float value = 0;
                for (Integer core : executableCores) {
                    value += slotsToReducePerCore[core];
                    value += candidate.getProcessorCPUCount();
                }
                if (bestValue < value) {
                    bestValue = candidate.getValue();
                    bestCandidate = candidate;
                }
            }
        }
        return bestCandidate;
    }

    public static ResourceDescription checkDestruction(float[] slotsToReducePerCore) {
        java.util.Set<ResourceDescription> resources = pool.getAllEditableResources(slotsToReducePerCore);
        ResourceDescription bestCandidate = null;
        float bestValue = Float.MIN_VALUE;
        for (ResourceDescription res : resources) {
            float slotsToRemove = Float.MAX_VALUE;
            for (int coreId : pool.getExecutableCores(res.getName())) {
                if (slotsToRemove > slotsToReducePerCore[coreId]) {
                    slotsToRemove = slotsToReducePerCore[coreId];
                }
            }
            ResourceDescription candidate = CloudManager.askForReduction(res, (int) slotsToRemove);
            if (candidate != null) {
                if (candidate.getValue() > bestValue && pool.canModify(candidate)) {
                    bestValue = candidate.getValue();
                    bestCandidate = candidate;
                }
            }
        }
        return bestCandidate;
    }

    public static boolean hasFreeSlots(String chosenResource, boolean presched) {
        return pool.hasFreeSlots(chosenResource, presched);

    }

    /**
     * Returns all the data to be printed in the monitor
     *
     * @return data to be monitorized
     */
    //public String getMonitoringData() {
    //    return pool.getMonitoringData();
    //}
    public static ResourcePool getPool() {
        return pool;
    }

    public static void resizeDataStructures() {
        pool.resizeDataStructures();

    }

    public static void linkCoreToMachines(int coreId) {
        String constraints = ConstraintManager.getConstraints(coreId);
        logger.debug(" Constraints for core " + coreId + ": " + constraints);
        XPathResult matchingRes = (XPathResult) evaluator.evaluate(constraints,
                resourcesDoc,
                /*resolver*/ null,
                XPathResult.UNORDERED_NODE_ITERATOR_TYPE,
                null);
        Node n;
        String resourceName;
        while ((n = matchingRes.iterateNext()) != null) {
            // Get current resource and add it to the list
            if (n.getNodeName().equals("Resource")) {
                resourceName = n.getAttributes().getNamedItem("Name").getTextContent();
                logger.debug("Join resource name to the pool: " + resourceName);
                pool.joinResourceToCore(resourceName, coreId);
            } else if (n.getNodeName().equals("Service")) {
                resourceName = n.getAttributes().getNamedItem("wsdl").getTextContent();
                logger.debug("Join service name to the pool: " + resourceName);
                pool.joinResourceToCore(resourceName, coreId);
            }
        }

    }

}
