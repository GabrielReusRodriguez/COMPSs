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



package integratedtoolkit.connectors.amazon;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import com.amazonaws.services.ec2.model.CreateSecurityGroupRequest;
import com.amazonaws.services.ec2.model.DescribeImagesRequest;
import com.amazonaws.services.ec2.model.DescribeImagesResult;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.DescribeKeyPairsResult;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsResult;
import com.amazonaws.services.ec2.model.Image;
import com.amazonaws.services.ec2.model.ImportKeyPairRequest;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceState;
import com.amazonaws.services.ec2.model.IpPermission;
import com.amazonaws.services.ec2.model.KeyPairInfo;
import com.amazonaws.services.ec2.model.Placement;
import com.amazonaws.services.ec2.model.RebootInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.SecurityGroup;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;

import integratedtoolkit.connectors.Connector;
import integratedtoolkit.connectors.ConnectorException;
import integratedtoolkit.connectors.Cost;
import integratedtoolkit.connectors.utils.CreationThread;
import integratedtoolkit.connectors.utils.DeletionThread;
import integratedtoolkit.connectors.utils.Operations;
import integratedtoolkit.types.CloudImageDescription;
import integratedtoolkit.types.ProjectWorker;
import integratedtoolkit.types.ResourceDescription;
import integratedtoolkit.types.ResourceRequest;
import java.util.ArrayList;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

public class EC2 implements Connector, Operations, Cost {

    private static String accessKeyId;
    private static String secretKeyId;
    private static String keyPairName;
    private static String keyLocation;
    private static String securityGroupName;
    private static String placementCode;
    private static int placement;
    private static int limitVMs;
    /*private static String image;
    private static String imageArchitecture;*/
    private int activeRequests;
    private String providerName;
    private static AmazonEC2Client client;
    private HashMap<String, String> IPToName;
    private HashMap<String, String> IPToType;
    private HashMap<String, Long> IPToStart;
    private static boolean terminate;
    private static boolean check;
    private float accumulatedCost;
    //SEMAPHORES
    private Object known_hosts;
    private Object stats;
    //PLACEMENTS
    private static final int PLACEMENT_US_EAST = 0;
    private static final int PLACEMENT_US_WEST = 1;
    private static final int PLACEMENT_EUROPE = 2;
    private static final int PLACEMENT_PACIFIC_SINGAPUR = 3;
    private static final int PLACEMENT_PACIFIC_TOKIO = 4;
    //INSTANCES FEATURES
    int smallCount; // TODO: UPDATE WITH NEW VALUES
    private static final String smallCode = "m1.small";
    private static final float smallMemory = 1740.8f;
    private static final int smallCPUCount = 1;
    private static final float smallDisk = 163840f;
    private static final float[] smallPrice = {0.085f, 0.095f, 0.095f, 0.095f, 0.1f};
    int largeCount;
    private static final String largeCode = "m1.large";
    private static final float largeMemory = 7680;
    private static final int largeCPUCount = 4;
    private static final float largeDisk = 870400;
    private static final float[] largePrice = {0.34f, 0.38f, 0.38f, 0.38f, 0.4f};
    int xlargeCount;
    private static final String xlargeCode = "m1.xlarge";
    private static final float xlargeMemory = 15360f;
    private static final int xlargeCPUCount = 8;
    private static final float xlargeDisk = 1730560;
    private static final float[] xlargePrice = {0.68f, 0.76f, 0.76f, 0.76f, 0.8f};

    public EC2(String name, HashMap<String, String> h) {
        this.providerName = name;
        // Connector parameters
        accessKeyId = h.get("Access Key Id");
        secretKeyId = h.get("Secret key Id");
        keyPairName = h.get("KeyPair name");
        keyLocation = h.get("Key host location");
        securityGroupName = h.get("SecurityGroup Name");
        placementCode = h.get("Placement");
        placement = getPlacement(placementCode);

        //Connector data init
        activeRequests = 0;
        IPToName = new HashMap<String, String>();
        IPToType = new HashMap<String, String>();
        IPToStart = new HashMap<String, Long>();
        check = false;
        terminate = false;
        smallCount = 0;
        largeCount = 0;
        xlargeCount = 0;
        accumulatedCost = 0.0f;
        known_hosts = new Integer(0);
        stats = new Integer(0);

        //Prepare Amazon for first use
        client = new AmazonEC2Client(new BasicAWSCredentials(accessKeyId, secretKeyId));
        boolean found = false;

        DescribeKeyPairsResult dkpr = client.describeKeyPairs();
        for (KeyPairInfo kp : dkpr.getKeyPairs()) {
            if (kp.getKeyName().compareTo(keyPairName) == 0) {
                found = true;
                break;
            }
        }
        if (!found) {
            try {
                ImportKeyPairRequest ikpReq = new ImportKeyPairRequest(keyPairName, getPublicKey());
                client.importKeyPair(ikpReq);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        found = false;
        DescribeSecurityGroupsResult dsgr = client.describeSecurityGroups();
        for (SecurityGroup sg : dsgr.getSecurityGroups()) {
            if (sg.getGroupName().compareTo(securityGroupName) == 0) {
                found = true;
                break;
            }
        }
        if (!found) {
            try {
                CreateSecurityGroupRequest sg = new CreateSecurityGroupRequest(securityGroupName, "description");
                client.createSecurityGroup(sg);
                IpPermission ipp = new IpPermission();
                ipp.setToPort(22);
                ipp.setFromPort(22);
                ipp.setIpProtocol("tcp");
                ArrayList<String> ipranges = new ArrayList<String>();
                ipranges.add("0.0.0.0/0");
                ipp.setIpRanges(ipranges);
                ArrayList<IpPermission> list_ipp = new ArrayList<IpPermission>();
                list_ipp.add(ipp);
                AuthorizeSecurityGroupIngressRequest asgi = new AuthorizeSecurityGroupIngressRequest(securityGroupName, list_ipp);
                client.authorizeSecurityGroupIngress(asgi);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        /*DescribeImagesRequest dirq = new DescribeImagesRequest();
        LinkedList<String> amiIds = new LinkedList<String>();
        amiIds.add(image);
        dirq.setImageIds(amiIds);
        DescribeImagesResult dirt = client.describeImages(dirq);
        for (Image image : dirt.getImages()) {
        imageArchitecture = image.getArchitecture();
        }*/
    }

    private int getPlacement(String placeCode) {
        if (placeCode.substring(0, 2).compareTo("ap") == 0) {
            if (placeCode.substring(3, 12).compareTo("northeast") == 0) {
                return PLACEMENT_PACIFIC_TOKIO;
            } else {//placeCode.substring(3,7).compareTo("southeast")==0
                return PLACEMENT_PACIFIC_SINGAPUR;
            }
        } else if (placeCode.substring(0, 2).compareTo("eu") == 0) {
            return PLACEMENT_EUROPE;
        } else {//(placeCode.substring(2).compareTo("us")==0)
            if (placeCode.substring(3, 7).compareTo("east") == 0) {
                return PLACEMENT_US_EAST;
            } else {//placeCode.substring(3,7).compareTo("west")==0
                return PLACEMENT_US_WEST;
            }
        }
    }

    //CONNECTOR INTERFACE IMPLEMENTATION
    public String getId() {
        return "amazon.ec2";
    }

    public String getDefaultUser() {
        return "ec2-user";
    }

    public String getDefaultWDir() {
        return "/home/ec2-user/wDir/";
    }

    public String getDefaultIDir() {
        return "/home/ec2-user/iDir/";
    }

    public Long getNextCreationTime() throws ConnectorException {
        return 120000l;
    }

    public boolean turnON(String name, ResourceRequest rR) {
        activeRequests++;
        CloudImageDescription cid = null;//CloudImageManager.getBestImage(rR.getRequested());
        if (terminate || !isAble(rR.getRequested(), cid.getName())) {
            activeRequests--;
            return false;
        }
        org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger(integratedtoolkit.log.Loggers.TS_COMP);
        logger.info("Applying for an extra worker");
        CreationThread ct;
        ct = new CreationThread((Operations) this, name, this.providerName, rR, cid, false);
        ct.start();
        return true;
    }

    public void reInitialize() {
        terminate = false;
        check = false;
    }

    public void terminate(String workerIP) {
        DeletionThread dt;
        dt = new DeletionThread((Operations) this, workerIP);
        dt.start();
    }

    public void terminate(ResourceDescription rd) {
        DeletionThread dt;
        dt = new DeletionThread((Operations) this, rd.getName());
        dt.start();
    }

    public void stopReached() {
        check = true;
    }

    public void terminateALL() throws ConnectorException {
        client = new AmazonEC2Client(new BasicAWSCredentials(accessKeyId, secretKeyId));
        terminate = true;
        DeletionThread[] dt;
        while (CreationThread.getCount() > 0) {
            try {
                Thread.sleep(1000);
            } catch (Exception e) {
            }
        }
        synchronized (stats) {
            int size;
            try {
                size = IPToName.keySet().toArray().length;
                dt = new DeletionThread[size];
                Process[] p = new Process[size];
                for (int i = 0; i < size; i++) {
                    String IP = (String) IPToName.keySet().toArray()[i];
                    dt[i] = new DeletionThread((Operations) this, IP);
                    dt[i].start();
                    synchronized (known_hosts) {
                        String[] cmd = {"/bin/sh", "-c", "grep -vw " + IP + " " + System.getProperty("user.home") + "/.ssh/known_hosts > known && mv known " + System.getProperty("user.home") + "/.ssh/known_hosts"};
                        p[i] = Runtime.getRuntime().exec(cmd);
                        p[i].waitFor();
                    }
                }
            } catch (Exception e) {
                throw new ConnectorException(e);
            }
        }
    }

    public void executeTask(String vmIP, String user, String command) throws ConnectorException {
        try {
            String[] cmd = new String[]{"ssh", user + "@" + vmIP, command};
            Process p = Runtime.getRuntime().exec(cmd);
            p.waitFor();
        } catch (Exception e) {
            throw new ConnectorException(e);
        }
    }

    //OPERATIONS INTERFACE IMPLEMENTATION
    public Object poweron(String name, ResourceDescription requested, String diskImage)
            throws ConnectorException {
        try {
            String instanceCode = classifyMachine(requested.getProcessorCPUCount(), requested.getMemoryPhysicalSize() * 1024f, requested.getStorageElemSize() * 1024f, diskImage);
            return createMachine(instanceCode, diskImage);
        } catch (Exception e) {
            throw new ConnectorException(e);
        }
    }

    public String waitCreation(Object vm, ResourceRequest request) throws ConnectorException {
        try {
            InstanceState status = ((RunInstancesResult) vm).getReservation().getInstances().get(0).getState();
            DescribeInstancesResult dir = null;
            Thread.sleep(30000);
            while (status.getCode() == 0) {
                Thread.sleep(10000);
                DescribeInstancesRequest describeInstancesRequest = new DescribeInstancesRequest();
                ArrayList<String> l = new ArrayList<String>();
                l.add(((RunInstancesResult) vm).getReservation().getInstances().get(0).getInstanceId());
                describeInstancesRequest.setInstanceIds(l);
                dir = client.describeInstances(describeInstancesRequest);
                status = dir.getReservations().get(0).getInstances().get(0).getState();
            }
            Instance instance = dir.getReservations().get(0).getInstances().get(0);

            ResourceDescription requested = request.getRequested();
            ResourceDescription granted = request.getGranted();
            String instanceType = instance.getInstanceType();
            String instanceId = instance.getInstanceId();
            String IP = instance.getPublicIpAddress();
            synchronized (stats) {
                synchronized (IPToName) {
                    IPToName.put(IP, instanceId);
                }
                IPToType.put(IP, instanceType);
                IPToStart.put(IP, System.currentTimeMillis());

                if (instanceType.compareTo(smallCode) == 0) {
                    smallCount++;
                    granted.setProcessorCPUCount(smallCPUCount);
                    granted.setMemoryPhysicalSize(smallMemory / 1024f);
                    granted.setStorageElemSize(smallDisk / 1024f);
                } else if (instanceType.compareTo(largeCode) == 0) {
                    largeCount++;
                    granted.setProcessorCPUCount(largeCPUCount);
                    granted.setMemoryPhysicalSize(largeMemory / 1024f);
                    granted.setStorageElemSize(largeDisk / 1024f);
                } else {
                    xlargeCount++;
                    if (requested.getProcessorCPUCount() > xlargeCPUCount) {
                        granted.setProcessorCPUCount(requested.getProcessorCPUCount());
                    } else {
                        granted.setProcessorCPUCount(xlargeCPUCount);
                    }

                    if (requested.getMemoryPhysicalSize() > xlargeMemory) {
                        granted.setMemoryPhysicalSize(requested.getMemoryPhysicalSize());
                    } else {
                        granted.setMemoryPhysicalSize(xlargeMemory);
                    }

                    if (requested.getStorageElemSize() > xlargeDisk) {
                        granted.setStorageElemSize(requested.getStorageElemSize());
                    } else {
                        granted.setStorageElemSize(xlargeDisk);
                    }
                }
            }
            if (requested.getProcessorArchitecture().compareTo("[unassigned]") != 0) {
                granted.setProcessorArchitecture(requested.getProcessorArchitecture());
            } else {
                granted.setProcessorArchitecture("[unassigned]");
            }

            granted.setProcessorSpeed(requested.getProcessorSpeed());
            granted.setMemoryVirtualSize(requested.getMemoryVirtualSize());
            granted.setMemorySTR(requested.getMemorySTR());
            granted.setMemoryAccessTime(requested.getMemoryAccessTime());
            granted.setStorageElemAccessTime(requested.getStorageElemAccessTime());
            granted.setStorageElemSTR(requested.getStorageElemSTR());
            granted.setOperatingSystemType("Linux");
            granted.setSlots(requested.getSlots());
            List<String> apps = requested.getAppSoftware();
            for (int i = 0; i < apps.size(); i++) {
                granted.addAppSoftware(apps.get(i));
            }
            granted.setValue(getMachineCostPerHour(granted));



            String[] cmd = {"/bin/sh", "-c", "ssh-keyscan " + IP};
            String[] cmdSSH = {"ssh", "-o ConnectTimeout 30", "ec2-user@" + IP, "ps -ef "};
            boolean machineReady = false;
            boolean hasKey = false;
            long initTime = System.currentTimeMillis();
            while (!machineReady) {
                if (System.currentTimeMillis() < (initTime + 60000)) {
                    if (!hasKey) {
                        String key = execCommand(cmd);
                        if (key != null) {
                            //S'afegeix a la llista de known_Hosts                            int exitValue2 = -1;
                            synchronized (known_hosts) {
                                String[] cmdKnown = new String[]{"/bin/sh", "-c", "echo " + key + " >> " + System.getProperty("user.home") + "/.ssh/known_hosts"};
                                Process p = Runtime.getRuntime().exec(cmdKnown);
                                p.waitFor();
                                String checkCommand[] = {"/bin/sh", "-c", "grep " + IP + " " + System.getProperty("user.home") + "/.ssh/known_hosts"};
                                key = execCommand(checkCommand);
                                if (key != null) {
                                    hasKey = true;
                                }
                            }
                        }
                    }
                    if (hasKey) {
                        String key = execCommand(cmdSSH);
                        if (key != null) {
                            machineReady = true;
                        }
                    }

                } else {
                    org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger(integratedtoolkit.log.Loggers.TS_COMP);
                    logger.info("There are some connection issues with " + IP + ". Rebooting VM...");
                    reboot(instanceId);
                    initTime = System.currentTimeMillis();
                }
            }


            return IP;
        } catch (Exception e) {
            throw new ConnectorException(e);
        }
    }

    private String execCommand(String[] cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(cmd);
        p.waitFor();
        java.io.BufferedReader is = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
        return is.readLine();
    }

    private void reboot(String instanceId) throws Exception {
        RebootInstancesRequest rirq = new RebootInstancesRequest();
        LinkedList<String> ids = new LinkedList<String>();
        ids.add(instanceId);
        rirq.setInstanceIds(ids);
        client.rebootInstances(rirq);
        Thread.sleep(5000);

        DescribeInstancesRequest describeInstancesRequest = new DescribeInstancesRequest();
        ArrayList<String> l = new ArrayList<String>();
        l.add(instanceId);
        describeInstancesRequest.setInstanceIds(l);
        DescribeInstancesResult dir = client.describeInstances(describeInstancesRequest);

        InstanceState status = dir.getReservations().get(0).getInstances().get(0).getState();
        while (status.getCode() == 0) {
            Thread.sleep(5000);
            dir = client.describeInstances(describeInstancesRequest);
            status = dir.getReservations().get(0).getInstances().get(0).getState();
        }
    }

    public void configureAccess(String workerIP, String user) throws ConnectorException {
        try {
            String MY_PUBLIC_KEY = getPublicKey();
            String MY_PRIVATE_KEY = getPrivateKey();

            String command = "/bin/echo \"" + MY_PUBLIC_KEY + "\" >> /home/" + getDefaultUser() + "/.ssh/id_rsa.pub && /bin/echo \"" + MY_PRIVATE_KEY + "\" >> /home/" + getDefaultUser() + "/.ssh/id_rsa && chmod 600 /home/" + getDefaultUser() + "/.ssh/id_rsa";
            boolean done = false;
            while (!done) {
                try {
                    executeTask(workerIP, getDefaultUser(), command);
                    done = true;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new ConnectorException(e);
        }
    }

    public void prepareMachine(String IP, CloudImageDescription cid)
            throws ConnectorException {
    }

    public void poweroff(String workerIP) throws ConnectorException {
        synchronized (stats) {
            String instanceId = null;
            synchronized (IPToName) {
                instanceId = IPToName.remove(workerIP);
            }
            ArrayList<String> instanceIds = new ArrayList<String>();
            instanceIds.add(instanceId);
            TerminateInstancesRequest tir = new TerminateInstancesRequest(instanceIds);
            client.terminateInstances(tir);
            String instanceType = IPToType.remove(workerIP);
            Long creationTime = IPToStart.remove(workerIP);
            if (instanceType.compareTo(smallCode) == 0) {
                smallCount--;
                accumulatedCost += (((System.currentTimeMillis() - creationTime) / 3600000) + 1) * smallPrice[placement];
            } else if (instanceType.compareTo(largeCode) == 0) {
                largeCount--;
                accumulatedCost += (((System.currentTimeMillis() - creationTime) / 3600000) + 1) * largePrice[placement];
            } else if (instanceType.compareTo(xlargeCode) == 0) {
                xlargeCount--;
                accumulatedCost += (((System.currentTimeMillis() - creationTime) / 3600000) + 1) * xlargePrice[placement];
            }
            activeRequests--;
        }
    }

    public void poweroff(Object worker, String IP) throws ConnectorException {
        RunInstancesResult workerInstance = (RunInstancesResult) worker;
        synchronized (stats) {
            String instanceId = null;
            instanceId = workerInstance.getReservation().getInstances().get(0).getInstanceId();
            ArrayList<String> instanceIds = new ArrayList<String>();
            instanceIds.add(instanceId);
            TerminateInstancesRequest tir = new TerminateInstancesRequest(instanceIds);
            client.terminateInstances(tir);
            activeRequests--;
        }
    }

    public boolean getTerminate() {
        return terminate;
    }

    public boolean getCheck() {
        return check;
    }

    public boolean worksVirtually() {
        return false;
    }

    public void announceCreation(String IP, String user, LinkedList<ProjectWorker> VMs) throws ConnectorException {
        try {
            String[] cmd;
            for (ProjectWorker pw : VMs) {
                boolean done = false;
                while (!done) {
                    try {
                        Process[] p = new Process[2];
                        cmd = new String[]{"ssh", pw.getUser() + "@" + pw.getName(), "ssh-keyscan " + IP + " >> /home/" + user + "/.ssh/known_hosts"};
                        p[0] = Runtime.getRuntime().exec(cmd);

                        cmd = new String[]{"ssh", user + "@" + IP, "ssh-keyscan " + pw.getName() + " >> /home/" + pw.getUser() + "/.ssh/known_hosts"};
                        p[1] = Runtime.getRuntime().exec(cmd);

                        p[0].waitFor();
                        p[1].waitFor();
                        done = true;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new ConnectorException(e);
        }
    }

    public void announceDestruction(String IP, LinkedList<ProjectWorker> existingVMs) throws ConnectorException {
        try {
            Process p;
            String[] cmd = {"/bin/sh", "-c", "grep -vw " + IP + " " + System.getProperty("user.home") + "/.ssh/known_hosts > known && mv known " + System.getProperty("user.home") + "/.ssh/known_hosts"};
            synchronized (known_hosts) {
                p = Runtime.getRuntime().exec(cmd);
                p.waitFor();
            }
            LinkedList<String> VMs = new LinkedList<String>();
            synchronized (IPToName) {
                for (String vm : IPToName.keySet()) {
                    VMs.add(vm);
                }
            }

            for (String vm : VMs) {
                cmd = new String[]{"ssh",
                    getDefaultUser() + "@" + vm,
                    "mv /home/" + getDefaultUser() + "/.ssh/known_hosts known "
                    + "&& grep -vw " + IP + " known > /home/" + getDefaultUser() + "/.ssh/known_hosts"
                    + "&& rm known"};
                p = Runtime.getRuntime().exec(cmd);
                p.waitFor();
            }
        } catch (Exception e) {
            throw new ConnectorException(e);
        }
    }

    //COST INTERFACE IMPLEMENTATION
    public Float getTotalCost() {
        float runningCost = 0.0f;
        for (java.util.Map.Entry<String, Long> e : IPToStart.entrySet()) {
            float machineCost = 0.0f;
            String IP = e.getKey();
            String instanceType = IPToType.get(IP);
            if (instanceType.compareTo(smallCode) == 0) {
                machineCost = (((System.currentTimeMillis() - e.getValue()) / 3600000) + 1) * smallPrice[placement];
            } else if (instanceType.compareTo(largeCode) == 0) {
                machineCost = (((System.currentTimeMillis() - e.getValue()) / 3600000) + 1) * largePrice[placement];
            } else if (instanceType.compareTo(xlargeCode) == 0) {
                machineCost = (((System.currentTimeMillis() - e.getValue()) / 3600000) + 1) * xlargePrice[placement];
            }
            runningCost += machineCost;
        }
        return accumulatedCost + runningCost;
    }

    public Float currentCostPerHour() {
        return smallCount * smallPrice[placement]
                + largeCount * largePrice[placement]
                + xlargeCount * xlargePrice[placement];
    }

    public Float getMachineCostPerHour(ResourceDescription rc) {
        int procs = rc.getProcessorCPUCount();
        float mem = rc.getMemoryPhysicalSize();
        float disk = rc.getStorageElemSize();
        CloudImageDescription diskImage = rc.getImage();
        String instanceCode = classifyMachine(procs, mem, disk, diskImage.getName());
        if (instanceCode.compareTo(smallCode) == 0) {
            return smallPrice[placement];
        }
        if (instanceCode.compareTo(largeCode) == 0) {
            return largePrice[placement];
        }
        if (instanceCode.compareTo(xlargeCode) == 0) {
            return xlargePrice[placement];
        }
        return null;
    }

    private static String getPublicKey() throws Exception {
        java.io.BufferedReader input = new java.io.BufferedReader(new java.io.FileReader(keyLocation + ".pub"));
        StringBuilder key = new StringBuilder();
        String sb = input.readLine();
        while (sb != null) {
            key.append(sb).append("\n");
            sb = input.readLine();
        }
        return key.toString();
    }

    private static String getPrivateKey() throws Exception {
        java.io.BufferedReader input = new java.io.BufferedReader(new java.io.FileReader(keyLocation));
        StringBuilder key = new StringBuilder();
        String sb = input.readLine();
        while (sb != null) {
            key.append(sb).append("\n");
            sb = input.readLine();
        }
        return key.toString();
    }

    private String classifyMachine(int CPU, float memory, float disk, String diskImage) {
        DescribeImagesRequest dirq = new DescribeImagesRequest();
        LinkedList<String> amiIds = new LinkedList<String>();
        amiIds.add(diskImage);
        dirq.setImageIds(amiIds);
        DescribeImagesResult dirt = client.describeImages(dirq);
        String imageArchitecture = "";
        for (Image image : dirt.getImages()) {
            imageArchitecture = image.getArchitecture();
        }
        if (imageArchitecture.compareTo("i386") == 0) {
            if (CPU <= smallCPUCount && memory <= smallMemory && disk <= smallDisk) {
                return smallCode;
            }
            return null;
        }

        if (CPU <= largeCPUCount && memory <= largeMemory && disk <= largeDisk) {
            return largeCode;
        }
        return xlargeCode;
    }

    private RunInstancesResult createMachine(String instanceCode, String diskImage) throws InterruptedException {
        //Create
        RunInstancesRequest runInstancesRequest = new RunInstancesRequest(diskImage, 1, 1);
        Placement placement = new Placement(placementCode);
        runInstancesRequest.setPlacement(placement);
        runInstancesRequest.setInstanceType(instanceCode);
        runInstancesRequest.setKeyName(keyPairName);
        ArrayList<String> groupId = new ArrayList<String>();
        groupId.add(securityGroupName);
        runInstancesRequest.setSecurityGroups(groupId);

        return client.runInstances(runInstancesRequest);
    }

    private boolean isAble(ResourceDescription request, String diskImage) {
        if (activeRequests <= limitVMs && classifyMachine(request.getProcessorCPUCount(), request.getMemoryPhysicalSize() * 1024f, request.getStorageElemSize() * 1024f, diskImage) != null) {
            return true;
        }
        return false;
    }

    @Override
    public void destroy(Object vm) throws ConnectorException {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}
