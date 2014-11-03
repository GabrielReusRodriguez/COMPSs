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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import com.amazonaws.services.ec2.model.CreateSecurityGroupRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.DescribeKeyPairsResult;
import com.amazonaws.services.ec2.model.DescribeSecurityGroupsResult;
import com.amazonaws.services.ec2.model.ImportKeyPairRequest;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceState;
import com.amazonaws.services.ec2.model.IpPermission;
import com.amazonaws.services.ec2.model.KeyPairInfo;
import com.amazonaws.services.ec2.model.Placement;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.SecurityGroup;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;

import com.trilead.ssh2.ChannelCondition;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.DebugLogger;
import com.trilead.ssh2.SCPClient;
import com.trilead.ssh2.Session;

import integratedtoolkit.connectors.Connector;
import integratedtoolkit.connectors.ConnectorException;
import integratedtoolkit.connectors.Cost;
import integratedtoolkit.connectors.utils.CreationThread;
import integratedtoolkit.connectors.utils.DeletionThread;
import integratedtoolkit.connectors.utils.KeyManager;
import integratedtoolkit.connectors.utils.Operations;
import integratedtoolkit.types.CloudImageDescription;
import integratedtoolkit.types.ProjectWorker;
import integratedtoolkit.types.ResourceDescription;
import integratedtoolkit.types.ResourceRequest;
import integratedtoolkit.util.ProjectManager;

public class EC2_2 implements Connector, Operations, Cost {

    // Amazon-related info
    private String accessKeyId;
    private String secretKeyId;
    private String keyPairName;
    private String keyLocation;
    private String securityGroupName;
    private String placementCode;
    private int placement;
    private float currentCostPerHour;
    private float deletedMachinesCost;
    //private Float totalCost;
    private AmazonEC2Client client;
    private String providerName;
    private HashMap<String, String> ipToVmId;
    private HashMap<String, VM> vmIdToInfo;
    private boolean terminate = false;
    private boolean check = false;
    private DeadlineThread dead;
    private TreeSet<VM> vmsToDelete;
    private LinkedList<VM> vmsAlive;
    private Map<String, Connection> ipToConnection;
    private static String MAX_VM_CREATION_TIME = "10"; //Minutes
    private static final long ONE_HOUR = 3600000;
    //private static final long FIFTY_FIVE_MIN = 3300000;
    private static final long FIFTY_FIVE_MIN = 3480000;
    private static final long FIFTY_MIN = 3000000;
    //private static final long FIVE_MIN = 300000;
    private static final long FIVE_MIN = 120000;
    

    public EC2_2(String providerName, HashMap<String, String> props) {
        this.providerName = providerName;

        // Connector parameters
        accessKeyId = props.get("Access Key Id");
        secretKeyId = props.get("Secret key Id");
        keyPairName = props.get("KeyPair name");
        keyLocation = props.get("Key host location");
        securityGroupName = props.get("SecurityGroup Name");
        placementCode = props.get("Placement");
        placement = VM.translatePlacement(placementCode);

        ipToVmId = new HashMap<String, String>();
        //totalCost = 0.0f;
        currentCostPerHour = 0.0f;
        deletedMachinesCost = 0.0f;
        vmIdToInfo = new HashMap<String, VM>();
        terminate = false;
        check = false;

        vmsToDelete = new TreeSet<VM>();
        vmsAlive = new LinkedList<VM>();

        ipToConnection = Collections.synchronizedMap(new HashMap<String,Connection>());
        
        client = new AmazonEC2Client(new BasicAWSCredentials(accessKeyId, secretKeyId));
        // TODO: Only for EU (Ireland)
        client.setEndpoint("https://ec2.eu-west-1.amazonaws.com");

        if (props.get("MaxVMCreationTime") != null) {
            MAX_VM_CREATION_TIME = props.get("MaxVMCreationTime");
        }

        dead = new DeadlineThread();
        dead.start();

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
                ImportKeyPairRequest ikpReq = new ImportKeyPairRequest(keyPairName, KeyManager.getPublicKey(keyLocation));
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
    }

    /*
     * 
     *      Creation Process
     * 
     */
    public boolean turnON(String name, ResourceRequest rR) {
    	if (terminate) {
            return false;
        }

        ResourceDescription requested = rR.getRequested();
        String type = VM.classifyMachine(requested.getProcessorCPUCount(),
        								 requested.getMemoryPhysicalSize() * 1024,
        								 requested.getStorageElemSize() * 1024,
        								 requested.getProcessorArchitecture());
        
        if (type == null) {
            return false;
        }

        // Check if we can reuse one of the vms put to delete (but not yet destroyed)
        VM vmInfo = tryToReuseVM(type, rR.getRequested());
        if (vmInfo != null) {
            //logger.debug("Reusing VM: " + vmInfo);
            
            vmInfo.setToDelete(false);
            synchronized (this) {
            	vmsToDelete.remove(vmInfo);
            }
            
            // #### PLOT LOG
            synchronized (this) {
            	logger.debug("U " + getLastPart(vmInfo.getIp()) + " " + providerName + " " + getAlive() + " " + getToDelete());
            }
            
            CreationThread ct = new CreationThread((Operations) this, vmInfo.getIp(), providerName, rR, rR.getRequested().getImage(), true);
            ct.start();
            return true;
        }

        try {
            //logger.info("Applying for an extra VM");
            
            // #### PLOT LOG
            synchronized (this) {
            	logger.debug("R - " + providerName + " " + getAlive() + " " + getToDelete());
            }
            
            CloudImageDescription cid = rR.getRequested().getImage();
            Float diskSize = cid.getDiskSize();
            if (diskSize != null) {
                if (diskSize > rR.getRequested().getStorageElemSize()) {
                    rR.getRequested().setStorageElemSize(cid.getDiskSize());
                }
            }
            CreationThread ct = new CreationThread((Operations) this, name, providerName, rR, cid, false);
            ct.start();
        } catch (Exception e) {
            logger.info("ResourceRequest failed");
            return false;
        }
        return true;
    }

    private VM tryToReuseVM(String type, ResourceDescription requested) {
    	String imageReq = requested.getImage().getName();
        synchronized (this) {
	        for (VM vm : vmsToDelete) {
	            if (!vm.getImage().equals(imageReq)) {
	                continue;
	            }
	            if (vm.isCompatible(type, requested.getImage().getArch())) {
	                return vm;
	            }
	        }
        }
        return null;
    }

    //REQUEST
    public Object poweron(String name, ResourceDescription requested, String diskImage) throws ConnectorException {
    	String arch = requested.getImage().getArch();
    	//logger.debug("Powering on machine " + name + ", with arch " + arch + ", description: " + requested);
        String instanceCode = VM.classifyMachine(requested.getProcessorCPUCount(), requested.getMemoryPhysicalSize() * 1024f, requested.getStorageElemSize() * 1024f, arch);
        RunInstancesResult res;
        try {
            res = createMachine(instanceCode, requested.getImage().getName());
        } catch (Exception e) {
        	logger.error("Error powering on machine " + name, e);
            throw new ConnectorException(e);
        }

        //logger.debug("Request for VM creation sent");

        return res;
    }

    /*private String getImageArchitecture(String diskImage) {
    DescribeImagesRequest dirq = new DescribeImagesRequest();
    LinkedList<String> amiIds = new LinkedList<String>();
    amiIds.add(diskImage);
    dirq.setImageIds(amiIds);
    DescribeImagesResult dirt = client.describeImages(dirq);
    String imageArchitecture = "";
    for (Image image : dirt.getImages()) {
    imageArchitecture = image.getArchitecture();
    }
    return imageArchitecture;
    }*/
    private RunInstancesResult createMachine(String instanceCode, String diskImage) throws Exception {
    	//Create
        RunInstancesRequest runInstancesRequest = new RunInstancesRequest(diskImage, 1, 1);
        Placement placement = new Placement(placementCode);
        runInstancesRequest.setPlacement(placement);
        runInstancesRequest.setInstanceType(instanceCode);
        runInstancesRequest.setKeyName(keyPairName);
        ArrayList<String> groupId = new ArrayList<String>();
        groupId.add(securityGroupName);
        runInstancesRequest.setSecurityGroups(groupId);
        
        //logger.debug("Requesting creation of VM: placement " + placementCode + ", keypair " + keyPairName + ", instance code " + instanceCode + ", security group " + securityGroupName);
        
        return client.runInstances(runInstancesRequest);
    }

    //WAIT
    public String waitCreation(Object vm, ResourceRequest request) throws ConnectorException {
        Integer poll_time = 5; //Seconds
        Integer polls = 0;
        int errors = 0;

        DescribeInstancesResult dir = null;

        InstanceState status = ((RunInstancesResult) vm).getReservation().getInstances().get(0).getState();
        //Thread.sleep(30000);
        //Thread.sleep(poll_time * 1000);
        
        //Valid values: 0 (pending) | 16 (running) | 32 (shutting-down) | 48 (terminated) | 64 (stopping) | 80 (stopped)
        while (status.getCode() == 0) {
            try {
                //Thread.sleep(10000);
                Thread.sleep(poll_time * 1000);
                if (poll_time * polls >= Integer.parseInt(MAX_VM_CREATION_TIME) * 60) {
                    throw new ConnectorException("Maximum VM creation time reached.");
                }
                polls++;
                DescribeInstancesRequest describeInstancesRequest = new DescribeInstancesRequest();
                ArrayList<String> l = new ArrayList<String>();
                l.add(((RunInstancesResult) vm).getReservation().getInstances().get(0).getInstanceId());
                describeInstancesRequest.setInstanceIds(l);
                dir = client.describeInstances(describeInstancesRequest);
                status = dir.getReservations().get(0).getInstances().get(0).getState();
                errors = 0;
            } catch (Exception e) {	
            	errors++;
                if (errors == 3) {
                    throw new ConnectorException(e);
                }
            }
        }

        Instance instance = dir.getReservations().get(0).getInstances().get(0);

        ResourceDescription requested = request.getRequested();
        ResourceDescription granted = request.getGranted();
        String instanceType = instance.getInstanceType();
        String instanceId = instance.getInstanceId();
        String ip = instance.getPublicIpAddress();

        VM vmInfo = new VM(instanceId, ip, instanceType, requested.getImage().getName(), placement);
        //logger.debug("Virtual machine created: " + vmInfo);

        float oneHourCost = VM.getPrice(instanceType, vmInfo.getPlacement());
        currentCostPerHour += oneHourCost;
        //totalCost += oneHourCost;

        int cpuCount = vmInfo.getType().getCpucount();
        granted.setProcessorCPUCount(cpuCount);
        granted.setProcessorArchitecture(requested.getProcessorArchitecture());
        granted.setProcessorSpeed(requested.getProcessorSpeed());

        float memorySize = vmInfo.getType().getMemory() / 1024f;
        granted.setMemoryAccessTime(requested.getMemoryAccessTime());
        granted.setMemoryPhysicalSize(memorySize);
        granted.setMemorySTR(requested.getMemorySTR());
        granted.setMemoryVirtualSize(requested.getMemoryVirtualSize());

        float homeSize = vmInfo.getType().getDisk() / 1024f;
        granted.setStorageElemAccessTime(requested.getStorageElemAccessTime());
        granted.setStorageElemSTR(requested.getStorageElemSTR());
        granted.setStorageElemSize(homeSize);

        granted.setOperatingSystemType("Linux");
        granted.setSlots(requested.getSlots());
        List<String> apps = requested.getAppSoftware();
        for (int i = 0; i < apps.size(); i++) {
            granted.addAppSoftware(apps.get(i));
        }
        granted.setType(requested.getType());
        granted.setImage(requested.getImage());
        granted.setValue(getMachineCostPerHour(granted));
        
        addMachine(vmInfo);

        return ip;
    }

    //CONFIGURE MASTERs ACCESS
    public void configureAccess(String workerIP, String user) throws ConnectorException {
    	//logger.debug("Put in kh " + workerIP);
    	putInKnownHosts(workerIP);
    	
    	//logger.debug("Get connection " + workerIP);
    	Connection c;
    	try {
			c = getConnection(workerIP, true, user);
		} catch (Exception e) {
			throw new ConnectorException(e);
		}
		ipToConnection.put(workerIP, c);
		
		//logger.debug("Configure keys " + workerIP);
    	configureKeys(workerIP, user, c);
    }	
    	
    private void putInKnownHosts(String workerIP) throws ConnectorException { 
    	// Put id of new machine in master machine known hosts
    	try {
    		String key = null;
            String[] cmd = {"/bin/sh", "-c", "ssh-keyscan -t rsa,dsa " + workerIP};
            while (key == null) {
            	try {
                    Thread.sleep(1000);
                } catch (Exception e) {}
                
            	Process p = Runtime.getRuntime().exec(cmd);
                java.io.InputStream errStream = p.getErrorStream();
                java.io.InputStream outStream = p.getInputStream();
                java.io.InputStreamReader outReader = new java.io.InputStreamReader(outStream);
                java.io.BufferedReader br = new java.io.BufferedReader(outReader);
                p.waitFor();
                String str;
                while ((str = br.readLine()) != null) {
                	if (key == null) key = str;
                	else 			  key += "\n" + str;
                }
                br.close();
                outReader.close();
                outStream.close();
                errStream.close();
                //logger.debug("Key is " + key);
            }

            cmd = new String[]{"/bin/sh", "-c", "/bin/echo " + "\"" + key + "\"" + " >> " + System.getProperty("user.home") + "/.ssh/known_hosts"};
            /*String command ="";
            for (String s : cmd) command += " " + s;*/
            //logger.debug("Running " + command);
            synchronized (knownHosts) {
            	int exitValue = -1;
                while (exitValue != 0) {
                	Process p = Runtime.getRuntime().exec(cmd);
                    p.getErrorStream().close();
                    p.getOutputStream().close();
                    p.waitFor();
                    exitValue = p.exitValue();
                }
            }
    	} catch (Exception e) {
            throw new ConnectorException(e);
        }
    }
           
    private void configureKeys(String workerIP, String user, Connection c) throws ConnectorException {
        try {
            String keypair = KeyManager.getKeyPair();
            String MY_PUBLIC_KEY = KeyManager.getPublicKey(keypair);
            String MY_PRIVATE_KEY = KeyManager.getPrivateKey(keypair);

            //String command = "/bin/echo \"" + MY_PUBLIC_KEY + "\" >> /home/" + user + "/.ssh/authorized_keys";
            //executeTask(workerIP, user, command);

            /*String command = "/bin/echo \"" + MY_PUBLIC_KEY + "\" >> /home/" + user + "/.ssh/" + KeyManager.getKeyType() + ".pub";
            executeTask(workerIP, user, command);

            command = "/bin/echo \"" + MY_PRIVATE_KEY + "\" >> /home/" + user + "/.ssh/" + KeyManager.getKeyType();
            executeTask(workerIP, user, command);

            command = "chmod 600 /home/" + user + "/.ssh/" + KeyManager.getKeyType();
            executeTask(workerIP, user, command);*/
            
            String command = "/bin/echo \"" + MY_PUBLIC_KEY + "\" >> /home/" + user + "/.ssh/authorized_keys";
							 //+ "; "
							 //+ "/bin/echo \"" + MY_PUBLIC_KEY + "\" >> /home/" + user + "/.ssh/" + KeyManager.getKeyType() + ".pub"
							 //+ "; "
							 //+ "/bin/echo \"" + MY_PRIVATE_KEY + "\" >> /home/" + user + "/.ssh/" + KeyManager.getKeyType()
							 //+ "; "
							 //+ "chmod 600 /home/" + user + "/.ssh/" + KeyManager.getKeyType();
            executeTask(workerIP, user, command, c);
            
        } catch (Exception e) {
            throw new ConnectorException(e);
        }
    }

    //CONFIGURE VMs ACCESS
    public void announceCreation(String ip, String user, LinkedList<ProjectWorker> existingMachines) throws ConnectorException {
    	if (existingMachines.size() == 0) {
            return;
        }
    	try {
            int ci = 0, si = 0;
            Connection[] connections = new Connection[existingMachines.size()];

            // keyscans
            Session[] sessions = new Session[existingMachines.size() * 2 - 1];
            for (ProjectWorker pw : existingMachines) {
                try {
                	connections[ci] = ipToConnection.get(pw.getName());
                	//logger.debug("Reusing connection in announce creation for " + pw.getName() + ":" + connections[ci]);
                	if (connections[ci] == null)
                		connections[ci] = getConnection(pw.getName(), true, pw.getUser());
                    
                    if (pw.getName().equals(ip)) {
                        for (ProjectWorker pw2 : existingMachines) {
                            String command = "ssh-keyscan -t rsa,dsa " + pw2.getName() + " >> /home/" + pw.getUser() + "/.ssh/known_hosts";
                            sessions[si++] = executeTaskAsynch(connections[ci], command);
                        }
                    } else {
                        String command = "ssh-keyscan -t rsa,dsa " + ip + " >> /home/" + pw.getUser() + "/.ssh/known_hosts";
                        sessions[si++] = executeTaskAsynch(connections[ci], command);
                    }
                    ci++;
                } catch (Exception e) {
                    throw new ConnectorException(e);
                }
            }
            for (Session s : sessions) {
                waitForTask(s);
            }

            // authorized keys TODO
            /*ci = 0;
            si = 0;
            for (ProjectWorker pw : existingMachines) {
            try {
            if (pw.getName().equals(ip)) {
            for (ProjectWorker pw2 : existingMachines) {
            String command = "ssh-keyscan " + pw2.getName() + " >> /home/" + pw.getUser() + "/.ssh/known_hosts";
            sessions[si++] = executeTaskAsynch(connections[ci], command);
            }
            }
            else {
            String command = "ssh-keyscan " + ip + " >> /home/" + pw.getUser() + "/.ssh/known_hosts";
            sessions[si++] = executeTaskAsynch(connections[ci], command);
            }
            ci++;
            } catch (Exception e) {
            throw new ConnectorException(e);
            }
            }
            for (Session s : sessions)
            waitForTask(s);
             */

            /*for (Connection c : connections) {
                c.close();
            }*/


        } catch (Exception e) {
        	logger.error("Exception in announce creation", e);
            throw new ConnectorException(e);
        }
    }

    //PACKAGE PREPARATION
    public void prepareMachine(String ip, CloudImageDescription cid) throws ConnectorException {
        try {
            LinkedList<String[]> packages = cid.getPackages();
            SCPClient client = null;
            Connection c = null;
            if (!packages.isEmpty()) {
            	c = ipToConnection.get(ip);
            	//logger.debug("Reusing connection in prepare machine for " + ip + ":" + c);
            	if (c == null)
            		c = getConnection(ip, true, cid.getUser()); 
                client = c.createSCPClient();
            } else {
                return;
            }

            for (String[] p : packages) {
                String[] path = p[0].split("/");
                String name = path[path.length - 1];

                if (client == null) {
                    throw new ConnectorException("Can not connect to " + ip);
                }

                client.put(p[0], name, p[1], "0700");

                //Extracting Worker package
                /*String command = "/bin/tar xzf " + p[1] + "/" + name;
                executeTask(ip, cid.getUser(), command);
                executeTask(ip, cid.getUser(), "/bin/echo \"\nfor i in " + p[1] + "/*.jar ; do\n\texport CLASSPATH=\\$CLASSPATH:\\$i\ndone\" >> /home/" + cid.getUser() + "/.bashrc");*/
                String command = "/bin/tar xzf " + p[1] + "/" + name
                				 + "; "
                				 + "/bin/echo \"\nfor i in " + p[1] + "/*.jar ; do\n\texport CLASSPATH=\\$CLASSPATH:\\$i\ndone\" >> /home/" + cid.getUser() + "/.bashrc"
                				 + "; "
                				 + "rm " + p[1] + "/" + name;
                executeTask(ip, cid.getUser(), command, c);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new ConnectorException("Failed preparing the Machine " + ip + ": " + e.getMessage());
        }

        // #### PLOT LOG
        synchronized (this) {
        	logger.debug("N " + getLastPart(ip) + " " + providerName + " " + getAlive() + " " + getToDelete());
        	//logger.debug("N " + ip + " " + providerName + " " + getAlive() + " " + getToDelete());
        }
        
    }

    //FAILED DURING RESOURCE WAIT
    public void destroy(Object vm) throws ConnectorException {
        String vmId = ((RunInstancesResult) vm).getReservation().getInstances().get(0).getInstanceId();
        connectionOnPowerOff(vmId);
    }

    //FAILED DURING STATS UPDATE DURING THE RESOURCE WAIT
    public void poweroff(Object vm, String ip) throws ConnectorException {
        String vmId;
        synchronized (this) {
            vmId = ipToVmId.get(ip);
        }
        connectionOnPowerOff(vmId);
        removeMachine(vmId);
    }

    public void poweroff(String ip) throws ConnectorException {
        String vmId;
        synchronized (this) {
            vmId = ipToVmId.get(ip);
        }
        connectionOnPowerOff(vmId);
        removeMachine(vmId);
    }

    private void connectionOnPowerOff(String vmId) throws ConnectorException {
        try {
            ArrayList<String> instanceIds = new ArrayList<String>();
            instanceIds.add(vmId);
            TerminateInstancesRequest tir = new TerminateInstancesRequest(instanceIds);
            
            long now = System.currentTimeMillis();
            
            client.terminateInstances(tir);
            
            VM vmInfo;
            synchronized (this) {
            	vmInfo = vmIdToInfo.get(vmId);
            }
            
            float pricePerHour = VM.getPrice(vmInfo.getType().getCode(), vmInfo.getPlacement());
            int numSlots = getNumSlots(now, vmInfo.getStartTime());
            currentCostPerHour -= pricePerHour;
            deletedMachinesCost += numSlots * pricePerHour;

            //logger.debug("Virtual machine terminated: " + vmInfo);
        } catch (Exception e) {
            throw new ConnectorException(e);
        }
    }

    private int getNumSlots(long time1, long time2) {
        long dif = time1 - time2;
        int numSlots = (int) (dif / ONE_HOUR);
        if (dif % ONE_HOUR > 0) {
            numSlots++;
        }
        return numSlots;
    }

    private synchronized void addMachine(VM vmInfo) {
        String vmId = vmInfo.getEnvId();
        String ip = vmInfo.getIp();
        ipToVmId.put(ip, vmId);
        vmIdToInfo.put(vmId, vmInfo);
        vmsAlive.add(vmInfo);
    }

    public synchronized void removeMachine(String vmId) {
        VM vmInfo = vmIdToInfo.remove(vmId);
        ipToVmId.remove(vmInfo.getIp());
        vmsAlive.remove(vmInfo);
        
        // #### PLOT LOG
        logger.debug("D " + getLastPart(vmInfo.getIp()) + " " + providerName + " " + getAlive() + " " + getToDelete());
    }

    /*public Float getTotalCost() {
    return totalCost;
    }*/
    public Float getTotalCost() {
        float aliveMachinesCost = 0;
        long now = System.currentTimeMillis();
        for (VM vm : vmsAlive) {
            int numSlots = getNumSlots(now, vm.getStartTime());
            float pricePerHour = VM.getPrice(vm.getType().getCode(), vm.getPlacement());
            aliveMachinesCost += numSlots * pricePerHour;
        }
        float totalCost = aliveMachinesCost + deletedMachinesCost;

        logger.debug("\n##################\n + " + providerName + " COST :"
                + "\n\tAlive machines (" + vmsAlive.size() + "): " + aliveMachinesCost
                + "\n\tDeleted machines: " + deletedMachinesCost
                + "\n\tTotal: " + totalCost 
                + "\n##################");

        return totalCost;
    }

    public boolean getTerminate() {
        return terminate;
    }

    public boolean getCheck() {
        return check;
    }

    public Float currentCostPerHour() {
        return currentCostPerHour;
    }

    public Float getMachineCostPerHour(ResourceDescription rc) {
    	//logger.debug("Image is " + rc.getImage());
        String type = VM.classifyMachine(rc.getProcessorCPUCount(),
                rc.getMemoryPhysicalSize() * 1024,
                rc.getStorageElemSize() * 1024,
                rc.getImage().getArch());

        return VM.getPrice(type, placement);
    }

    public Long getNextCreationTime()
            throws ConnectorException {
        try {
            //return rest.getCreationTime() + 20000;
        	return 90000l;
        } catch (Exception e) {
            throw new ConnectorException(e);
        }
    }

    public void terminate(String ip) {
        String vmId = ipToVmId.get(ip);
        VM vmInfo = vmIdToInfo.get(vmId);
        if (canBeSaved(vmInfo)) {
            //logger.debug("Virtual machine saved: " + vmInfo);

            vmInfo.setToDelete(true);
            synchronized (this) {
            	vmsToDelete.add(vmInfo);
            }
            
            // #### PLOT LOG
            synchronized (this) {
            	logger.debug("S " + getLastPart(vmInfo.getIp()) + " " + providerName + " " + getAlive() + " " + getToDelete());
            }
        } else {
            DeletionThread dt;
            dt = new DeletionThread((Operations) this, ip);
            dt.start();
        }
    }

    private boolean canBeSaved(VM vmInfo) {
        long now = System.currentTimeMillis();
        long vmStart = vmInfo.getStartTime();

        long dif = now - vmStart;
        long mod = dif % ONE_HOUR;
        return mod < FIFTY_FIVE_MIN; // my deadline is less than 5 min away  
    }

    public void terminate(ResourceDescription rd) {
        terminate(rd.getName());
    }

    public void stopReached() {
        check = true;
    }

    public void terminateALL()
            throws ConnectorException {
        if (terminate) {
            return;
        }
        
        // #### PLOT LOG
        synchronized (this) {
        	logger.debug("T - " + providerName + " " + getAlive() + " " + getToDelete());
        }
        
        terminate = true;
        while (CreationThread.getCount() > 0 || DeletionThread.getCount() > 0) {
            try {
            	//logger.debug("Creating " + CreationThread.getCount() + " and deleting " + DeletionThread.getCount());
                Thread.sleep(1000);
            } catch (Exception e) {
            }
        }
        
        //logger.debug("No creations or deletions now for " + providerName);
        
        synchronized (this) {
        	vmsToDelete.clear();
        }

        LinkedList<ProjectWorker> activeVMs = ProjectManager.getProviderRegisteredMachines(providerName);
        try {
            for (ProjectWorker pw : activeVMs) {
                DeletionThread dt;
                String IP = (String) pw.getName();
                dt = new DeletionThread((Operations) this, IP);
                dt.start();
            }
        } catch (Exception e) {
            throw new ConnectorException(e);
        }

        dead.terminate();
        
        //logger.debug("ALL terminated for " + providerName);
    }

    public void executeTask(String ip, String user, String command, Connection c) throws ConnectorException {
        Session session = null;
        try {
            //Connection c = getConnection(ip, true, user);
            session = c.openSession();
            session.execCommand(command);
            session.waitForCondition(ChannelCondition.EXIT_STATUS, 0);
            int exitStatus = session.getExitStatus();
            if (exitStatus != 0) {
                throw new ConnectorException("Failed to execute command " + command + " in " + ip);
            }
            session.close();
            //c.close();
        } catch (Exception e) {
        	logger.error("Exception connecting to " + user + "@" + ip + " to run command " + command, e);
            throw new ConnectorException(e);
        }
    }

    public Session executeTaskAsynch(Connection c, String command) throws ConnectorException {
        Session session = null;
        try {
            session = c.openSession();
            session.execCommand(command);
            return session;
        } catch (Exception e) {
            throw new ConnectorException(e);
        }
    }

    public void waitForTask(Session session) throws ConnectorException {
        try {
            session.waitForCondition(ChannelCondition.EXIT_STATUS, 0);
            int exitStatus = session.getExitStatus();
            if (exitStatus != 0) {
                throw new ConnectorException("Failed to wait on session " + session);
            }
            session.close();
        } catch (Exception e) {
            throw new ConnectorException(e);
        }
    }

    public void announceDestruction(String ip, LinkedList<ProjectWorker> existingMachines) throws ConnectorException {
        /*try {
            String[] cmd = {"/bin/sh", "-c", "grep -vw " + ip + " " + System.getProperty("user.home") + "/.ssh/known_hosts > known && mv known " + System.getProperty("user.home") + "/.ssh/known_hosts"};
            synchronized (knownHosts) {
                Process p = Runtime.getRuntime().exec(cmd);
                p.waitFor();
                p.getErrorStream().close();
                p.getOutputStream().close();
            }

            int ci = 0, si = 0;
            Connection[] connections = new Connection[existingMachines.size()];
            Session[] sessions = new Session[existingMachines.size()];
            for (ProjectWorker pw : existingMachines) {
                connections[ci] = getConnection(pw.getName(), true, pw.getUser());

                String command = "mv /home/" + pw.getUser() + "/.ssh/known_hosts known "
                        + "&& grep -vw " + ip + " known > /home/" + pw.getUser() + "/.ssh/known_hosts"
                        + "&& rm known";

                sessions[si++] = executeTaskAsynch(connections[ci], command);
                ci++;
            }
            for (Session s : sessions) {
                waitForTask(s);
            }
        } catch (Exception e) {
            throw new ConnectorException(e);
        }*/
    }

    private static Connection getConnection(String host, boolean tcpNoDelay, String user) throws Exception {
    	String[] client2server = ("aes256-ctr,aes192-ctr,aes128-ctr,blowfish-ctr,aes256-cbc,aes192-cbc,aes128-cbc,blowfish-cbc").split(",");
        String[] server2client = ("aes256-ctr,aes192-ctr,aes128-ctr,blowfish-ctr,aes256-cbc,aes192-cbc,aes128-cbc,blowfish-cbc").split(",");

        Connection newConnection = new Connection(host, 22);
        newConnection.setClient2ServerCiphers(client2server);
        newConnection.setServer2ClientCiphers(server2client);
        newConnection.setTCPNoDelay(tcpNoDelay);
        int connectTimeout = 0;
        int kexTimeout = 0;
        
        newConnection.connect(null, connectTimeout, kexTimeout);
        
        boolean connected = false;
        TrileadDebug debug = new TrileadDebug();
        newConnection.enableDebugging(true, debug);
        connected = newConnection.authenticateWithPublicKey(user, new java.io.File(KeyManager.getKeyPair()), null);
        newConnection.enableDebugging(false, debug);

        if (!connected) {
            return null;
        }
        return newConnection;
    }

    
        private class DeadlineThread extends Thread {

        private boolean keepGoing;

        public DeadlineThread() {
            keepGoing = true;
        }

        public void run() {

        	long sleepTime = 1000l;
            while (keepGoing) {
                try {
                    //logger.info("MONITORSTATUS DEAD sleeps " + sleepTime / 1000);
                    Thread.sleep(sleepTime);
                } catch (Exception e) {
                }
                synchronized (EC2_2.this) {
                    if (vmsAlive.isEmpty()) {
                        //logger.info("MONITORSTATUS DEAD no VMs alive");
                        sleepTime = FIFTY_MIN; // Sleep 50 min
                        continue;
                    } else {
                        VM vmInfo = vmsAlive.getFirst();

                        long timeLeft = timeLeft(vmInfo.getStartTime());
                        //logger.info("MONITORSTATUS DEAD next VM " + vmInfo.ip + " @ " + vmInfo.startTime + " --> " + timeLeft);
                        if (timeLeft < FIVE_MIN) {
                            //logger.info("MONITORSTATUS DEAD <30seconds");
                            boolean checkNext = false;
                            if (vmInfo.isToDelete()) {
                                vmsAlive.pollFirst();
                                vmsToDelete.remove(vmInfo); //vmsToDelete.pollLast();
                                ipToConnection.remove(vmInfo.getIp()).close();

                                //logger.debug("Virtual machine is at deadline, starting removal: " + vmInfo);

                                //logger.info("MONITORSTATUS DEAD DeletionThread " + vmInfo.ip);
                                
                                DeletionThread dt;
                                dt = new DeletionThread((Operations) EC2_2.this, vmInfo.getIp());
                                dt.start();
                                checkNext = !vmsAlive.isEmpty();
                            } else {
                                //logger.info("MONITORSTATUS DEAD Alive " + vmInfo.ip);
                                vmsAlive.add(vmsAlive.pollFirst()); // put at the end (the 10-min slot will be renewed)
                                //logger.debug("A time slot will be renewed in " + timeLeft / 1000 + " seconds for " + vmInfo);
                                checkNext = vmsAlive.size() > 1;

                            }
                            if (checkNext) {
                                vmInfo = vmsAlive.getFirst();
                                timeLeft = timeLeft(vmInfo.getStartTime());
                                if (timeLeft>FIVE_MIN){
                                    sleepTime = timeLeft - FIVE_MIN;
                                }else{
                                    sleepTime = 0l;
                                }
                            } else {
                                sleepTime = FIFTY_MIN; // Sleep 50 min
                            }

                        } else {
                            //logger.info("MONITORSTATUS DEAD More than 1 minut to go");
                            sleepTime = timeLeft - FIVE_MIN;
                            continue;
                        }
                    }
                }
            }
        }

        public void terminate() {
            keepGoing = false;
            this.interrupt();
        }

        private long timeLeft(long time) {
            long now = System.currentTimeMillis();

            long result= ONE_HOUR - ((now - time) % ONE_HOUR);
            //logger.info("MONITORSTATUS DEAD Started at "+time+" now is "+now+" remaining --> "+(now - time)+" " +result+" ms to deadline");
            return result;
        }
    }
        
        

    private static class TrileadDebug implements DebugLogger {

        public void log(int level, String className, String message) {
        }
    }
    
    
    private String getLastPart(String ip) {
    	int i = ip.lastIndexOf('.') + 1;
    	return ip.substring(i);
    }
    
    private String getAlive() {
    	String sAlive = vmsAlive.size() + " [";
    	for (VM alive : vmsAlive) sAlive += getLastPart(alive.getIp()) + ",";
    	if (sAlive.endsWith(",")) sAlive = sAlive.substring(0, sAlive.length() - 1);
    	sAlive += "]";
    	return sAlive;
    }
    
    private String getToDelete() {
    	String sToDelete = vmsToDelete.size() + " [";
    	for (VM toDelete : vmsToDelete) sToDelete += getLastPart(toDelete.getIp()) + ",";
    	if (sToDelete.endsWith(",")) sToDelete = sToDelete.substring(0, sToDelete.length() - 1);
    	sToDelete += "]";
    	return sToDelete;
    }
    
}
