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



package integratedtoolkit.connectors.emotivecloud;

import net.emotivecloud.scheduler.drp.client.DRPClient;

import net.emotivecloud.utils.ovf.EmotiveOVF;
import net.emotivecloud.utils.ovf.OVFDisk;
import net.emotivecloud.utils.ovf.OVFNetwork;
import net.emotivecloud.utils.ovf.OVFWrapper;
import net.emotivecloud.utils.ovf.OVFWrapperFactory;

import integratedtoolkit.types.ResourceDescription;
import integratedtoolkit.types.ResourceRequest;
import integratedtoolkit.connectors.ConnectorException;
import integratedtoolkit.connectors.Connector;
import integratedtoolkit.connectors.Cost;
import integratedtoolkit.connectors.utils.CreationThread;
import integratedtoolkit.connectors.utils.DeletionThread;
import integratedtoolkit.connectors.utils.KeyManager;
import integratedtoolkit.connectors.utils.Operations;

import integratedtoolkit.types.CloudImageDescription;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.trilead.ssh2.ChannelCondition;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.DebugLogger;
import com.trilead.ssh2.SCPClient;
import com.trilead.ssh2.Session;

import integratedtoolkit.types.ProjectWorker;
import integratedtoolkit.util.ProjectManager;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.LinkedList;
import java.util.StringTokenizer;
import net.emotivecloud.scheduler.drp.client.DRPSecureClient;

public class DRPSecureClientConnector_2 implements Connector, Operations, Cost {

    private DRPClient rest;
    private String providerName;
    private HashMap<String, String> ipToVmId;
    private HashMap<String, VM> vmIdToInfo;
    private float deletedMachinesCost;
    private int procCount;
    private float memCount;
    private float diskCount;
    private boolean terminate = false;
    private boolean check = false;
    
    
    
    private static final Float procCost=0.012f;//CPU hour
    private static final Float memCost=0.000016f;//MB-hour
    private static final Float diskCost = 0.000083333f;//GB-hour
    
    /*private static final Float procCost = 100f;//CPU - 10 min
    private static final Float memCost = 200f;//MB - 10 min
    private static final Float diskCost = 5.0f;//GB - 10 min*/
    private static String MAX_VM_CREATION_TIME = "10"; //Minutes
    private HashMap<String, String> props;
    private DeadlineThread dead;
    private TreeSet<VM> vmsToDelete;
    private LinkedList<VM> vmsAlive;
    
    private Map<String, Connection> ipToConnection;
    
    private static final long ONE_HOUR = 3600000;
    
    private static final long TEN_MIN = 600000;//= 300000;
    private static final long NINE_MIN = 540000; //270000;//FIFTY_FIVE_MIN = 3300000;
    private static final long EIGHT_MIN = 480000; //240000;//FIFTY_MIN = 3000000;
    private static final long ONE_MIN = 60000; //30000;//FIVE_MIN = 300000;
    

    public DRPSecureClientConnector_2(String providerName, HashMap<String, String> h) throws Exception {
        this.providerName = providerName;
        this.props = h;

        try {

            StringTokenizer st = new StringTokenizer(h.get("cert"), "/");
            String s = null;
            props = h;

            while (st.hasMoreElements()) {
                s = st.nextToken();
            }

            String certName = s.substring(0, s.length() - 4);
            rest = new DRPSecureClient(new URL(h.get("Server")), new FileInputStream(h.get("cert")), certName.toCharArray());



        } catch (Exception e) {
            throw new Exception(e);
        }

        ipToVmId = new HashMap<String, String>();
        vmIdToInfo = new HashMap<String, VM>();
        deletedMachinesCost = 0.0f;
        procCount = 0;
        memCount = 0.0f;
        diskCount = 0.0f;
        
        ipToConnection = Collections.synchronizedMap(new HashMap<String,Connection>());

        terminate = false;
        check = false;

        if (props.get("max-vm-creation-time") != null) {
            MAX_VM_CREATION_TIME = props.get("max-vm-creation-time");
        }

        vmsToDelete = new TreeSet<VM>();
        vmsAlive = new LinkedList<VM>();

        dead = new DeadlineThread();
        dead.start();


    }

    /*
     * 
     *      Creation Process
     * 
     */
    public boolean turnON(String name, ResourceRequest rR) {
        if (terminate || !isAble(rR.getRequested())) {
            return false;
        }

        // Check if we can reuse one of the vms put to delete (but not yet destroyed)
        VM vmInfo = tryToReuseVM(rR.getRequested());
        if (vmInfo != null) {
            //logger.info("MONITORSTATUS TURN ON reusing " + vmInfo.ip);
            //logger.debug("Reusing VM: " + vmInfo);
            
            vmInfo.toDelete = false;
            synchronized (this) {
                vmsToDelete.remove(vmInfo);
            }
            
            // #### PLOT LOG
            synchronized (this) {
                logger.debug("U " + getLastPart(vmInfo.ip) + " " + providerName + " " + getAlive() + " " + getToDelete());
            }
            
            CreationThread ct = new CreationThread((Operations) this, vmInfo.ip, providerName, rR, rR.getRequested().getImage(), true);
            ct.start();
            return true;
        }

        try {
            //logger.info("MONITORSTATUS TURN ON New VM");
            //logger.debug("Applying for an extra VM");
            
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
            CreationThread ct;
            ct = new CreationThread((Operations) this, name, providerName, rR, cid, false);
            ct.start();
        } catch (Exception e) {
            //logger.info("ResourceRequest failed");
            return false;
        }
        return true;
    }

    private VM tryToReuseVM(ResourceDescription requested) {
        String imageReq = requested.getImage().getName();
        synchronized (this) {
                for (VM vm : vmsToDelete) {
                    if (!vm.image.equals(imageReq)) {
                        continue;
                    }
                    if (vm.procs < requested.getProcessorCPUCount()) {
                        continue;
                    }
                    if (vm.mem < requested.getMemoryPhysicalSize()) {
                        continue;
                    }
                    if (vm.disk < requested.getStorageElemSize()) {
                        continue;
                    }
                    return vm;
                }
        }
        return null;
    }

    //REQUEST
    public Object poweron(String name, ResourceDescription requested, String diskImage)
            throws ConnectorException {
        OVFWrapper ovf;
        try {
            int CPUCount = 1;
            int memorySize = 1024;
            int homeSize = 100;

            if (requested.getProcessorCPUCount() != 0) {
                CPUCount = requested.getProcessorCPUCount();
            }

            if (requested.getMemoryPhysicalSize() != 0.0f) {
                memorySize = (int) ((Float) requested.getMemoryPhysicalSize() * (Float) 1024f);
            }

            if (requested.getStorageElemSize() != 0.0f) {
                homeSize = (int) ((Float) requested.getStorageElemSize() * (Float) 1024f);
            }
            OVFWrapper ovfDom = OVFWrapperFactory.create(null,//GLOBAL ID
                    CPUCount,//CPUS
                    memorySize,//RAM MEMORY
                    new OVFDisk[]{
                        new OVFDisk("home", null, Long.valueOf(homeSize)),
                        new OVFDisk("swap", null, 222L)},
                    new OVFNetwork[]{},
                    null);//Additional properties.

            ovfDom.getNetworks().put("public", new OVFNetwork("public", null, null));
            EmotiveOVF ovfDomEmo = new EmotiveOVF(ovfDom);

            ovfDomEmo.setProductProperty(EmotiveOVF.PROPERTYNAME_VM_NAME, name);
            if (props.get("jobname") != null) {
                ovfDomEmo.setProductProperty("VM.jobnametag", props.get("jobname"));
            }
            if (props.get("owner") != null) {
                ovfDomEmo.setProductProperty("VM.owner", props.get("owner"));
            }

            ovfDomEmo.setBaseImage(new OVFDisk("VMImage", diskImage, 1000L));

            ovf = rest.createCompute(ovfDomEmo);
            
        } catch (Exception e) {
            e.printStackTrace();
            throw new ConnectorException(e);
        }

        //logger.debug("Request for VM creation sent");

        return ovf;
    }

    //WAIT
    public String waitCreation(Object vm, ResourceRequest request)
            throws ConnectorException {
        OVFWrapper ovf = (OVFWrapper) vm;
        String vmId = ovf.getId();
        String ip = "";
        Integer poll_time = 5; //Seconds
        Integer polls = 0;
        int errors = 0;

        vmId = ovf.getId();
        int status;
        try {
            status = rest.getEnvironmentStatus(vmId).compareToIgnoreCase("RUNNING_AVAILABLE");
        } catch (Exception e) {
            status = -1;
            errors++;
        }
        while (status != 0) {
            try {
                //Thread.sleep(2000);
                polls++;
                Thread.sleep(poll_time * 1000);
                if (poll_time * polls >= Integer.parseInt(MAX_VM_CREATION_TIME) * 60) {
                    throw new ConnectorException("Maximum VM creation time reached.");
                }
                status = rest.getEnvironmentStatus(vmId).compareToIgnoreCase("RUNNING_AVAILABLE");
                errors = 0;
            } catch (Exception e) {
                errors++;
                if (errors == 20) {
                        e.printStackTrace();
                    throw new ConnectorException("Error getting the status of the request");
                }
            }
        }

        try {
            do {
                //Thread.sleep(2000);
                polls++;
                Thread.sleep(poll_time * 1000);
                if (poll_time * polls >= Integer.parseInt(MAX_VM_CREATION_TIME) * 60) {
                    throw new ConnectorException("Maximum VM creation time reached.");
                }
                errors = 0;
            } while (rest.getCompute(vmId).getId().compareTo("-1") == 0 || getIP(vmId) == null || getIP(vmId).contains("0.0.0.0"));
            ip = getIP(vmId);
        } catch (Exception e) {
            errors++;
            if (errors == 3) {
                throw new ConnectorException("Error getting the new IP of the machine");
            }
        }

        ResourceDescription requested = request.getRequested();
        ResourceDescription granted = request.getGranted();

        VM vmInfo = new VM(vmId);
        vmInfo.ip = ip;

        int cpuCount = ovf.getCPUsNumber();
        granted.setProcessorCPUCount(cpuCount);
        granted.setProcessorArchitecture(requested.getProcessorArchitecture());
        granted.setProcessorSpeed(requested.getProcessorSpeed());
        vmInfo.procs = cpuCount;
        procCount += cpuCount; // perhaps synchronize?

        float memorySize = ovf.getMemoryMB() / 1024f;
        granted.setMemoryAccessTime(requested.getMemoryAccessTime());
        granted.setMemoryPhysicalSize(memorySize);
        granted.setMemorySTR(requested.getMemorySTR());
        granted.setMemoryVirtualSize(requested.getMemoryVirtualSize());
        vmInfo.mem = memorySize;
        memCount += memorySize; // perhaps synchronize?

        float homeSize = ovf.getDisks().get("home").getCapacityMB() / 1024f;
        granted.setStorageElemAccessTime(requested.getStorageElemAccessTime());
        granted.setStorageElemSTR(requested.getStorageElemSTR());
        granted.setStorageElemSize(homeSize);
        vmInfo.disk = homeSize;
        diskCount += homeSize; // perhaps synchronize?

        String image = requested.getImage().getName();
        vmInfo.image = image;

        //logger.debug("Virtual machine created: " + vmInfo);
        //logger.info("MONITORSTATUS CREATED " + vmInfo.ip + " @ " + System.currentTimeMillis());
        
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

        //logger.debug("Added new VM:\n" + vmInfo);

        return ip;
    }

    //CONFIGURE MASTERs ACCESS
    public void configureAccess(String workerIP, String user) throws ConnectorException {
        //logger.debug("Put in kh");
        putInKnownHosts(workerIP);
        //logger.debug("Configure keys");
        configureKeys(workerIP, user);
        
        //logger.debug("Get connection");
        Connection c;
        try {
                        c = getConnection(workerIP, true, user);
                } catch (Exception e) {
                        throw new ConnectorException(e);
                }
                ipToConnection.put(workerIP, c);
    }
    
    private void putInKnownHosts(String ip) throws ConnectorException {
        try {
            String key = null;
            String[] cmd = {"/bin/sh", "-c", "ssh-keyscan -t rsa,dsa " + ip};
            while (key == null) {
                Process p = Runtime.getRuntime().exec(cmd);
                java.io.InputStream errStream = p.getErrorStream();
                java.io.InputStream outStream = p.getInputStream();
                java.io.InputStreamReader outReader = new java.io.InputStreamReader(outStream);
                java.io.BufferedReader br = new java.io.BufferedReader(outReader);
                p.waitFor();
                String str;
                while ((str = br.readLine()) != null) {
                        //logger.debug("Key read " + str);
                        if (key == null) key = str;
                        else                      key += "\n" + str;
                }
                br.close();
                outReader.close();
                outStream.close();
                errStream.close();
            }
            
            cmd = new String[]{"/bin/sh", "-c", "/bin/echo " + "\"" + key + "\"" + " >> " + System.getProperty("user.home") + "/.ssh/known_hosts"};
            synchronized (knownHosts) {
                int exitValue = -1;
                while (exitValue != 0) {
                    Process p = Runtime.getRuntime().exec(cmd);
                    p.waitFor();
                    p.getErrorStream().close();
                    p.getOutputStream().close();
                    exitValue = p.exitValue();
                }
            }
        } catch (Exception e) {
            //logger.error("ERROR ", e);
            throw new ConnectorException(e);
        }
    }
    
    private void configureKeys(String workerIP, String user) throws ConnectorException {
        try {
            String keypair = KeyManager.getKeyPair();
            String MY_PUBLIC_KEY = KeyManager.getPublicKey(keypair);
            String MY_PRIVATE_KEY = KeyManager.getPrivateKey(keypair);

            /*String command = "/bin/echo \"" + MY_PUBLIC_KEY + "\" >> /home/" + user + "/.ssh/authorized_keys";
            executeTask(workerIP, user, command);

            command = "/bin/echo \"" + MY_PUBLIC_KEY + "\" >> /home/" + user + "/.ssh/" + KeyManager.getKeyType() + ".pub";
            executeTask(workerIP, user, command);

            command = "/bin/echo \"" + MY_PRIVATE_KEY + "\" >> /home/" + user + "/.ssh/" + KeyManager.getKeyType();
            executeTask(workerIP, user, command);

            command = "chmod 600 /home/" + user + "/.ssh/" + KeyManager.getKeyType();
            executeTask(workerIP, user, command);*/
            
            String command = "/bin/echo \"" + MY_PUBLIC_KEY + "\" >> /home/" + user + "/.ssh/authorized_keys"
                                        + "; "
                                        + "/bin/echo \"" + MY_PUBLIC_KEY + "\" >> /home/" + user + "/.ssh/" + KeyManager.getKeyType() + ".pub"
                                        + "; "
                                        + "/bin/echo \"" + MY_PRIVATE_KEY + "\" >> /home/" + user + "/.ssh/" + KeyManager.getKeyType()
                                        + "; "
                                        + "chmod 600 /home/" + user + "/.ssh/" + KeyManager.getKeyType();
            executeTask(workerIP, user, command);
            
        } catch (Exception e) {
            throw new ConnectorException(e);
        }
    }
    

    //CONFIGURE VMs ACCESS
    public void announceCreation(String ip, String user, LinkedList<ProjectWorker> existingMachines) throws ConnectorException {
        //logger.debug("Announcing creation of machine " + ip);
        
        if (existingMachines.size() == 0) {
            return;
        }
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
                        //logger.debug("PUTTING ids of other machines in known hosts of the new machine " + ip);
                    for (ProjectWorker pw2 : existingMachines) {
                        String ipOther = pw2.getName();
                        String cmd = "ssh-keyscan -t rsa,dsa " + ipOther + " >> /home/" + pw.getUser() + "/.ssh/known_hosts";
                            sessions[si++] = executeTaskAsynch(connections[ci], cmd);
                            //logger.debug("Launched command " + cmd);
                    }
                } else {
                        String ipOther = pw.getName();
                        //logger.debug("PUTTING id of new machine " + ip + " in known hosts of this other machine " + ipOther);
                        String cmd = "ssh-keyscan -t rsa,dsa " + ip + " >> /home/" + pw.getUser() + "/.ssh/known_hosts";
                        sessions[si++] = executeTaskAsynch(connections[ci], cmd);
                        //logger.debug("Launched command " + cmd);
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
    }
    

    //PACKAGE PREPARATION
    public void prepareMachine(String IP, CloudImageDescription cid)
            throws ConnectorException {
        try {
            LinkedList<String[]> packages = cid.getPackages();
            SCPClient client = null;
            Connection c = null;
            if (!packages.isEmpty()) {
                c = ipToConnection.get(IP);
                //logger.debug("Reusing connection in prepare machine for " + IP + ":" + c);
                if (c == null)
                        c = getConnection(IP, true, cid.getUser()); 
                client = c.createSCPClient();
            } else {
                return;
            }

            for (String[] p : packages) {
                String[] path = p[0].split("/");
                String name = path[path.length - 1];

                if (client == null) {
                    throw new ConnectorException("Can not connect to " + IP);
                }

                //executeTask(IP, cid.getUser(), "mkdir -p " + p[1], c);
                client.put(p[0], name, p[1], "0700");

                //Extracting Worker package
                /*executeTask(IP, cid.getUser(), "cd " + p[1] + " && /bin/tar xzf " + name);
                executeTask(IP, cid.getUser(), "/bin/echo \"\nfor i in " + p[1] + "/*.jar ; do\n\texport CLASSPATH=\\$CLASSPATH:\\$i\ndone\" >> /home/" + cid.getUser() + "/.bashrc");
                executeTask(IP, cid.getUser(), "rm " + p[1] + "/" + name);*/
                
                executeTask(IP, cid.getUser(), "cd " + p[1] + " && /bin/tar xzf " + name +" -C "+p[1]
                                                                                + "; "
                                                                                + "/bin/echo \"\nfor i in " + p[1] + "/*.jar ; do\n\texport CLASSPATH=\\$CLASSPATH:\\$i\ndone\" >> /home/" + cid.getUser() + "/.bashrc"
                                                                                + "; "
                                                                                + "rm " + p[1] + "/" + name);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new ConnectorException("Failed preparing the Machine " + IP + ": " + e.getMessage());
        }

        // #### PLOT LOG
        synchronized (this) {
                logger.debug("N " + getLastPart(IP) + " " + providerName + " " + getAlive() + " " + getToDelete());
        }
        
    }

    //FAILED DURING RESOURCE WAIT
    public void destroy(Object vm)
            throws ConnectorException {
        String vmId = (new EmotiveOVF((OVFWrapper) vm)).getId();
        connectionOnPowerOff(vmId);
    }

    //FAILED DURING STATS UPDATE DURING THE RESOURCE WAIT
    public void poweroff(Object vm, String ip)
            throws ConnectorException {
        String vmId = (new EmotiveOVF((OVFWrapper) vm)).getId();
        connectionOnPowerOff(vmId);
        removeMachine(vmId);
    }

    public synchronized void poweroff(String ip) throws ConnectorException {
        String vmId = ipToVmId.get(ip);
        connectionOnPowerOff(vmId);
        removeMachine(vmId);
    }

    private void connectionOnPowerOff(String vmId) throws ConnectorException {
        long now = System.currentTimeMillis();
        try {
            rest.deleteCompute(vmId);
        } catch (Exception e) {
            throw new ConnectorException(e);
        }

        VM vmInfo;
        synchronized (this) {
                vmInfo = vmIdToInfo.get(vmId);
        }

        //logger.debug("Virtual machine terminated: " + vmInfo);

        long dif = now - vmInfo.startTime;
        int numSlots = (int) (dif / TEN_MIN);
        if (dif % TEN_MIN > 0) {
            numSlots++;
        }

        deletedMachinesCost += numSlots * (vmInfo.procs * procCost + vmInfo.mem * memCost + vmInfo.disk * diskCost);
        // Perhaps synchronize
        procCount -= vmInfo.procs;
        memCount -= vmInfo.mem;
        diskCount -= vmInfo.disk;
    }

    private synchronized void addMachine(VM vmInfo) {
        //logger.debug(vmInfo.ip + " adding machine");
        String vmId = vmInfo.envId;
        String ip = vmInfo.ip;
        //logger.info("ipToVmId.put (" + ip + "," + vmId + ") ");
        ipToVmId.put(ip, vmId);
        //logger.info("VMIdToInfo.put (" + vmId + "," + vmInfo + ") ");
        vmIdToInfo.put(vmId, vmInfo);
        vmsAlive.add(vmInfo);
    }

    public synchronized void removeMachine(String vmId) {
        //logger.info("VMIdToInfo.remove (" + vmId + ") ");
        VM vmInfo = vmIdToInfo.remove(vmId);
        ipToVmId.remove(vmInfo.ip);
        vmsAlive.remove(vmInfo);
        
        // #### PLOT LOG
        logger.debug("D " + getLastPart(vmInfo.ip) + " " + providerName + " " + getAlive() + " " + getToDelete());
    }

    public Float getTotalCost() {
        float aliveMachinesCost = 0;
        long now = System.currentTimeMillis();
        for (VM vm : vmsAlive) {
            long dif = now - vm.startTime;
            int numSlots = (int) (dif / TEN_MIN);
            if (dif % TEN_MIN > 0) {
                numSlots++;
            }
            aliveMachinesCost += numSlots * (vm.procs * procCost + vm.mem * memCost + vm.disk * diskCost);
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
        return procCount * procCost + memCount * memCost + diskCost * diskCount * (ONE_HOUR / TEN_MIN);
    }

    public Float getMachineCostPerHour(ResourceDescription rc) {
        int procs = rc.getProcessorCPUCount();
        float mem = rc.getMemoryPhysicalSize();
        float disk = rc.getStorageElemSize();
        //CloudImageDescription diskImage = rc.getImage();
        return procs * procCost + mem * memCost + diskCost * disk * (ONE_HOUR / TEN_MIN);
    }

    public Long getNextCreationTime()
            throws ConnectorException {
        try {
            //return rest.getCreationTime() + 20000;
            //return 120000l;
                return 90000l;
                //return 5000l;
        } catch (Exception e) {
            throw new ConnectorException(e);
        }
    }

    public void terminate(String ip) {
        //logger.info("MONITORSTATUS terminate " + ip);
        VM vmInfo;
        synchronized (this) {
                String vmId = ipToVmId.get(ip);
                //logger.info("ipToVmId.get (" + ip + ")=" + vmId);
                vmInfo = vmIdToInfo.get(vmId);
                //logger.info("VMIdToInfo.get (" + vmId + ")=" + vmInfo);
        }
        if (canBeSaved(vmInfo)) {
            //logger.info("\tMONITORSTATUS canBeSaved");
            //logger.debug("Virtual machine saved: " + vmInfo);
            
                vmInfo.saveTime = System.currentTimeMillis();
                
            vmInfo.toDelete = true;
            synchronized (this) {
                vmsToDelete.add(vmInfo);
                vmsAlive.remove(vmInfo);
                vmsAlive.add(vmInfo);
            }
            
            // #### PLOT LOG
            synchronized (this) {
                logger.debug("S " + getLastPart(vmInfo.ip) + " " + providerName + " " + getAlive() + " " + getToDelete());
            }
        } else {
            //logger.info("\tMONITORSTATUS DeletionThread");
            DeletionThread dt;
            dt = new DeletionThread((Operations) this, ip);
            dt.start();
        }
    }

    private boolean canBeSaved(VM vmInfo) {
        return true;
        /*long now = System.currentTimeMillis();
        long vmStart = vmInfo.startTime;

        long dif = now - vmStart;
        long mod = dif % TEN_MIN;
        return mod < NINE_MIN; // save me if my deadline is more than 1 min away*/  
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
                String ip = (String) pw.getName();
                dt = new DeletionThread((Operations) this, ip);
                dt.start();
            }
        } catch (Exception e) {
            throw new ConnectorException(e);
        }

        dead.terminate();
        
        //logger.debug("ALL terminated for " + providerName);
    }

    private String getIP(Object vm)
            throws ConnectorException {
        String vmId = (String) vm;
        try {
            return (new EmotiveOVF(rest.getCompute(vmId))).getNetworks().get("public").getIp();
        } catch (Exception e) {
            throw new ConnectorException(e);
        }
    }

    public void executeTask(String ip, String user, String command) throws ConnectorException {
        String vmId;
        synchronized (this) {
            vmId = ipToVmId.get(ip);
        }
        if (vmId == null) {
            throw new ConnectorException("There is no machine with ip: " + ip);
        }
        boolean sentTask = false;
        String deleteJSDL = "false";
        while (!sentTask) {
            try {
                try {
                    String taskId = rest.submitActivity(vmId, user, deleteJSDL, command);
                } catch (Throwable e) {
                    throw new ConnectorException((Exception) e);
                }
                Thread.sleep(500);
                sentTask = true;
            } catch (Exception e) {
                if (e.getMessage().compareTo("Error 424") != 0) {
                    throw new ConnectorException(e);
                }
            }
        }
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
	    if(session == null){
	     System.out.println("SESSION NULL");
	    }
	    else{
	     System.out.println("SESSION NOT NULL");
            }
            session.waitForCondition(ChannelCondition.EXIT_STATUS, 0);
            int exitStatus = session.getExitStatus();
            if (exitStatus != 0) {
                throw new ConnectorException("Failed to wait on session " + session);
            }

            /*
            InputStream stdout = session.getStdout();
            InputStream stderr = session.getStderr();
            BufferedReader br = new BufferedReader(new InputStreamReader(stdout));
            StringBuffer out = new StringBuffer();
            while (true) {
                String line = br.readLine();
                if (line == null) {
                    break;
                }
                out.append(line);
                out.append("\n");
            }
            logger.debug("WAIT for task, OUT: " + out.toString());
            
            br = new BufferedReader(new InputStreamReader(stderr));
            StringBuffer err = new StringBuffer();
            while (true) {
                String line = br.readLine();
                if (line == null) {
                    break;
                }
                err.append(line);
                err.append("\n");
            }
            logger.debug("WAIT for task, ERR: " + err.toString());
	*/

            session.close();
        } catch (Exception e) {
	    e.printStackTrace();
            throw new ConnectorException(e);
        }
    }

    private boolean isAble(ResourceDescription request) {
        return true;
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

    private class VM implements Comparable<VM> {

        Integer procs;
        Float mem;
        Float disk;
        String envId;
        long startTime;
        long saveTime;
        String image;
        String ip;
        boolean toDelete;

        VM(String envId) {

            procs = null;
            mem = null;
            disk = null;
            this.envId = envId;

            startTime = System.currentTimeMillis();
            image = null;
            ip = null;
            toDelete = false;
            saveTime = 0;
        }

        public String toString() {
            return "VM " + envId + " (ip = " + ip + ", start time = " + startTime + ", image = " + image + ", procs = " + procs + ", memory = " + mem + ", disk = " + disk + ", to delete = " + toDelete + ")";
        }

        public long getStartTime() {
            return this.startTime;
        }

        public int compareTo(VM vm) throws NullPointerException {
            if (vm == null) {
                throw new NullPointerException();
            }
            
            if (vm.ip.equals(ip)) {
                return 0;
            }

            long now = System.currentTimeMillis();
            int mod1 = (int) (now - getStartTime()) % 3600000;  // 1 h in ms
            int mod2 = (int) (now - vm.getStartTime()) % 3600000;  // 1 h in ms

            return mod2 - mod1;
        }
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
                    //logger.info("DL - MONITORSTATUS DEAD sleeps " + sleepTime / 1000);
                    Thread.sleep(sleepTime);
                } catch (Exception e) {
                }
                synchronized (DRPSecureClientConnector_2.this) {
                    if (vmsAlive.isEmpty() || vmsToDelete.size() == 0) {
                        //logger.info("DL - MONITORSTATUS go to sleep " + vmsAlive.isEmpty() + ", " + vmsToDelete.size());
                        sleepTime = EIGHT_MIN; // Sleep 50 min
                        continue;
                    }
                    else {
                        long saveTime = vmsAlive.getFirst().saveTime;
                        while (saveTime == 0) {
                                vmsAlive.add(vmsAlive.pollFirst());
                                saveTime = vmsAlive.getFirst().saveTime;
                        }
                        
                        VM vmInfo = vmsAlive.getFirst();
                        long timeLeft = timeLeft(vmInfo.saveTime);
                        //logger.info("DL - MONITORSTATUS DEAD next VM " + vmInfo.ip + " @ " + vmInfo.startTime + " --> " + timeLeft);
                        if (timeLeft < ONE_MIN) {
                            //logger.info("DL - MONITORSTATUS <<<  1 min " + vmInfo.ip);
                            boolean checkNext = false;
                            if (vmInfo.toDelete) {
                                vmsAlive.pollFirst();
                                vmsToDelete.remove(vmInfo); //vmsToDelete.pollLast();
                                ipToConnection.remove(vmInfo.ip).close();

                                //logger.debug("DL - Virtual machine is at deadline, starting removal: " + vmInfo.ip);

                                //logger.info("MONITORSTATUS DEAD DeletionThread " + vmInfo.ip);
                                
                                DeletionThread dt;
                                dt = new DeletionThread((Operations) DRPSecureClientConnector_2.this, vmInfo.ip);
                                dt.start();
                                checkNext = !vmsAlive.isEmpty();
                            } else {
                                //logger.info("DL - MONITORSTATUS DEAD Alive " + vmInfo.ip);
                                vmsAlive.add(vmsAlive.pollFirst()); // put at the end (the 10-min slot will be renewed)
                                //logger.debug("DL - A time slot will be renewed in " + timeLeft / 1000 + " seconds for " + vmInfo.ip);
                                checkNext = vmsAlive.size() > 1;

                            }
                            if (checkNext) {
                                vmInfo = vmsAlive.getFirst();
                                timeLeft = timeLeft(vmInfo.saveTime);
                                if (timeLeft>ONE_MIN){
                                    sleepTime = timeLeft - ONE_MIN;
                                }else{
                                    sleepTime = 0l;
                                }
                            } else {
                                sleepTime = EIGHT_MIN; // Sleep 50 min
                            }
                            //logger.info("DL - sleep time " + sleepTime / 1000);

                        } else {
                            //logger.info("DL - MONITORSTATUS >>>>  1 min " + vmInfo.ip);
                            sleepTime = timeLeft - ONE_MIN;
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

            long result = TEN_MIN - ((now - time) % TEN_MIN);
            //logger.info("DL - MONITORSTATUS DEAD Started at " + time + " now is " + now + " remaining --> " + (now - time) + " " + result + " ms to deadline");
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
        for (VM alive : vmsAlive) sAlive += getLastPart(alive.ip) + ",";
        if (sAlive.endsWith(",")) sAlive = sAlive.substring(0, sAlive.length() - 1);
        sAlive += "]";
        return sAlive;
    }
    
    private String getToDelete() {
        String sToDelete = vmsToDelete.size() + " [";
        for (VM toDelete : vmsToDelete) sToDelete += getLastPart(toDelete.ip) + ",";
        if (sToDelete.endsWith(",")) sToDelete = sToDelete.substring(0, sToDelete.length() - 1);
        sToDelete += "]";
        return sToDelete;
    }
        
    
}
