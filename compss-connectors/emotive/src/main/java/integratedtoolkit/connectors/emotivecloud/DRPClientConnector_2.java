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
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;

import com.trilead.ssh2.ChannelCondition;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.DebugLogger;
import com.trilead.ssh2.SCPClient;
import com.trilead.ssh2.Session;

import integratedtoolkit.types.ProjectWorker;
import integratedtoolkit.util.ProjectManager;
import java.util.LinkedList;

public class DRPClientConnector_2 implements Connector, Operations, Cost {

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
    private static final Float procCost = 100f;//CPU - 10 min
    private static final Float memCost = 200f;//MB - 10 min
    private static final Float diskCost = 5.0f;//GB - 10 min
    private static String MAX_VM_CREATION_TIME = "10"; //Minutes
    private HashMap<String, String> props;
    private DeadlineThread dead;
    private TreeSet<VM> vmsToDelete;
    private LinkedList<VM> vmsAlive;
    private static final long TEN_MIN = 600000;//;ONE_HOUR = 3600000;
    private static final long NINE_MIN = 540000;//FIFTY_FIVE_MIN = 3300000;
    private static final long EIGHT_MIN = 480000;//FIFTY_MIN = 3000000;
    private static final long ONE_MIN = 60000;//FIVE_MIN = 300000;

    public DRPClientConnector_2(String providerName, HashMap<String, String> h) {
        this.providerName = providerName;
        this.props = h;
        rest = new DRPClient(h.get("Server"), Integer.parseInt(h.get("Port")));
        ipToVmId = new HashMap<String, String>();
        vmIdToInfo = new HashMap<String, VM>();
        deletedMachinesCost = 0.0f;
        procCount = 0;
        memCount = 0.0f;
        diskCount = 0.0f;

        terminate = false;
        check = false;

        if (props.get("MaxVMCreationTime") != null) {
            MAX_VM_CREATION_TIME = props.get("MaxVMCreationTime");
        }

        dead = new DeadlineThread();
        dead.start();

        vmsToDelete = new TreeSet<VM>();
        vmsAlive = new LinkedList<VM>();
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
            logger.debug("Reusing VM: " + vmInfo);
            vmInfo.toDelete = false;
            vmsToDelete.remove(vmInfo);
            
            CreationThread ct= new CreationThread((Operations) this, vmInfo.ip, providerName, rR, rR.getRequested().getImage(),true);
            ct.start();
            return true;
        }

        try {
            logger.debug("Applying for an extra VM");
            CloudImageDescription cid = rR.getRequested().getImage();
            Float diskSize = cid.getDiskSize();
            if (diskSize != null) {
                if (diskSize > rR.getRequested().getStorageElemSize()) {
                    rR.getRequested().setStorageElemSize(cid.getDiskSize());
                }
            }
            CreationThread ct;
            ct = new CreationThread((Operations) this, name, providerName, rR, cid,false);
            ct.start();
        } catch (Exception e) {
            logger.info("ResourceRequest failed");
            return false;
        }
        return true;
    }

    private VM tryToReuseVM(ResourceDescription requested) {
        String imageReq = requested.getImage().getName();
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
            if (props.get("JobNameTag") != null) {
                ovfDomEmo.setProductProperty("VM.jobnametag", props.get("JobNameTag"));
            }
            if (props.get("Owner") != null) {
                ovfDomEmo.setProductProperty("VM.owner", props.get("Owner"));
            }

            ovfDomEmo.setBaseImage(new OVFDisk("VMImage", diskImage, 1000L));

            ovf = rest.createCompute(ovfDomEmo);
        } catch (Exception e) {
            throw new ConnectorException(e);
        }

        logger.debug("Request for VM creation sent");

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
                if (errors == 3) {
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

        logger.debug("Virtual machine created: " + vmInfo);

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

        logger.debug("Added new VM:\n" + vmInfo);

        return ip;
    }

    //CONFIGURE MASTERs ACCESS
    public void configureAccess(String workerIP, String user) throws ConnectorException {
        try {
            String keypair = KeyManager.getKeyPair();
            String MY_PUBLIC_KEY = KeyManager.getPublicKey(keypair);
            String MY_PRIVATE_KEY = KeyManager.getPrivateKey(keypair);

            String command = "/bin/echo \"" + MY_PUBLIC_KEY + "\" >> /home/" + user + "/.ssh/authorized_keys";
            executeTask(workerIP, user, command);


            command = "/bin/echo \"" + MY_PUBLIC_KEY + "\" >> /home/" + user + "/.ssh/" + KeyManager.getKeyType() + ".pub";
            executeTask(workerIP, user, command);

            command = "/bin/echo \"" + MY_PRIVATE_KEY + "\" >> /home/" + user + "/.ssh/" + KeyManager.getKeyType();
            executeTask(workerIP, user, command);

            command = "chmod 600 /home/" + user + "/.ssh/" + KeyManager.getKeyType();
            executeTask(workerIP, user, command);

        } catch (Exception e) {
            throw new ConnectorException(e);
        }
    }

    //CONFIGURE VMs ACCESS
    public void announceCreation(String ip, String user, LinkedList<ProjectWorker> existingMachines) throws ConnectorException {
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
                key = br.readLine();
                br.close();
                outReader.close();
                outStream.close();
                errStream.close();
            }
            
            cmd = new String[]{"/bin/sh", "-c", "/bin/echo " + key + " >> " + System.getProperty("user.home") + "/.ssh/known_hosts"};
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
            logger.error("ERROR ", e);
            throw new ConnectorException(e);
        }
        if (existingMachines.size() == 0){
            return;
        }
        int ci = 0, si = 0;
        Connection[] connections = new Connection[existingMachines.size()];

        // keyscans
        Session[] sessions = new Session[existingMachines.size() * 2 - 1];
        for (ProjectWorker pw : existingMachines) {
            try {
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

        for (Connection c : connections) {
            c.close();
        }
    }

    //PACKAGE PREPARATION
    public void prepareMachine(String IP, CloudImageDescription cid)
            throws ConnectorException {
        try {
            LinkedList<String[]> packages = cid.getPackages();
            SCPClient client = null;
            if (!packages.isEmpty()) {
                client = getConnection(IP, true, cid.getUser()).createSCPClient();
            } else {
                return;
            }

            for (String[] p : packages) {
                String[] path = p[0].split("/");
                String name = path[path.length - 1];

                if (client == null) {
                    throw new ConnectorException("Can not connect to " + IP);
                }
                
                executeTask(IP, cid.getUser(), "mkdir -p " +p[1]);
                client.put(p[0], name, p[1], "0700");

                //Extracting Worker package
                executeTask(IP, cid.getUser(), "cd "+p[1] +" && /bin/tar xzf " + name+" -C "+p[1]);
                executeTask(IP, cid.getUser(), "/bin/echo \"\nfor i in " + p[1] + "/*.jar ; do\n\texport CLASSPATH=\\$CLASSPATH:\\$i\ndone\" >> /home/" + cid.getUser() + "/.bashrc");
                executeTask(IP, cid.getUser(), "rm " + p[1] + "/" + name);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new ConnectorException("Failed preparing the Machine " + IP + ": " + e.getMessage());
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

        VM vmInfo = vmIdToInfo.get(vmId);

        logger.debug("Virtual machine terminated: " + vmInfo);

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
        logger.debug(vmInfo.ip + " adding machine");
        String vmId = vmInfo.envId;
        String ip = vmInfo.ip;
        logger.info("ipToVmId.put (" + ip + "," + vmId + ") ");
        ipToVmId.put(ip, vmId);
        logger.info("VMIdToInfo.put (" + vmId + "," + vmInfo + ") ");
        vmIdToInfo.put(vmId, vmInfo);
        vmsAlive.add(vmInfo);
    }

    public synchronized void removeMachine(String vmId) {
        logger.info("VMIdToInfo.remove (" + vmId + ") ");
        VM vmInfo = vmIdToInfo.remove(vmId);
        ipToVmId.remove(vmInfo.ip);
        vmsAlive.remove(vmInfo);
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

        logger.debug("COST:"
                + "\nAlive machines (" + vmsAlive.size() + "): " + aliveMachinesCost
                + "\nDeleted machines: " + deletedMachinesCost
                + "\nTotal: " + totalCost);

        return totalCost;
    }

    public boolean getTerminate() {
        return terminate;
    }

    public boolean getCheck() {
        return check;
    }

    public Float currentCostPerHour() {
        // Current cost per 10 min
        return procCount * procCost + memCount * memCost + diskCost * diskCount;
    }

    public Float getMachineCostPerHour(ResourceDescription rc) {
        int procs = rc.getProcessorCPUCount();
        float mem = rc.getMemoryPhysicalSize();
        float disk = rc.getStorageElemSize();
        //CloudImageDescription diskImage = rc.getImage();
        return procs * procCost + mem * memCost + diskCost * disk;
    }

    public Long getNextCreationTime()
            throws ConnectorException {
        try {
            //return rest.getCreationTime() + 20000;
            return 130000l;
        } catch (Exception e) {
            throw new ConnectorException(e);
        }
    }

    public void terminate(String ip) {
        String vmId = ipToVmId.get(ip);
        logger.info("ipToVmId.get (" + ip + ")=" + vmId);
        VM vmInfo = vmIdToInfo.get(vmId);
        logger.info("VMIdToInfo.get (" + vmId + ")=" + vmInfo);
        if (canBeSaved(vmInfo)) {
            logger.debug("Virtual machine saved: " + vmInfo);

            vmInfo.toDelete = true;
            vmsToDelete.add(vmInfo);
        } else {
            DeletionThread dt;
            dt = new DeletionThread((Operations) this, ip);
            dt.start();
        }
    }

    private boolean canBeSaved(VM vmInfo) {
        long now = System.currentTimeMillis();
        long vmStart = vmInfo.startTime;

        long dif = now - vmStart;
        long mod = dif % TEN_MIN;
        return mod < NINE_MIN; // my deadline is less than 1 min away  
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
        terminate = true;
        while (CreationThread.getCount() > 0 || DeletionThread.getCount() > 0) {
            try {
                Thread.sleep(1000);
            } catch (Exception e) {
            }
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
        String deleteJSDL;
        deleteJSDL = "false";
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

    private boolean isAble(ResourceDescription request) {
        return true;
    }

    public void announceDestruction(String ip, LinkedList<ProjectWorker> existingMachines) throws ConnectorException {
        try {
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
        }
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

    private class VM {

        Integer procs;
        Float mem;
        Float disk;
        String envId;
        long startTime;
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
        }

        public String toString() {
            return "VM " + envId + " (ip = " + ip + ", start time = " + startTime + ", image = " + image + ", procs = " + procs + ", memory = " + mem + ", disk = " + disk + ", to delete = " + toDelete + ")";
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
                    Thread.sleep(sleepTime);
                } catch (Exception e) {
                }
                synchronized (DRPClientConnector_2.this) {
                    if (vmsAlive.isEmpty()) {
                        sleepTime = EIGHT_MIN; // Sleep 50 min
                        continue;
                    } else {
                        VM vmInfo = vmsAlive.getFirst();
                        long timeLeft = timeLeft(vmInfo.startTime);
                        if (timeLeft < ONE_MIN) {
                            if (vmInfo.toDelete) {
                                vmsAlive.pollFirst();
                                vmsToDelete.remove(vmInfo); //vmsToDelete.pollLast();

                                logger.debug("Virtual machine is at deadline, starting removal: " + vmInfo);

                                DeletionThread dt;
                                dt = new DeletionThread((Operations) DRPClientConnector_2.this, vmInfo.ip);
                                dt.start();
                            } else {
                                vmsAlive.add(vmsAlive.pollFirst()); // put at the end (the 10-min slot will be renewed)

                                logger.debug("A time slot will be renewed in " + timeLeft / 1000 + " seconds for " + vmInfo);
                            }
                            vmInfo = vmsAlive.getFirst();
                            if (vmsAlive.size() == 1) {
                                sleepTime = EIGHT_MIN; // Sleep 50 min
                                continue;
                            } else {
                                timeLeft = timeLeft(vmInfo.startTime);
                                sleepTime = timeLeft - ONE_MIN;
                                continue;
                            }
                        } else {
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
            return (now - time) % TEN_MIN;
        }
    }

    private static class TrileadDebug implements DebugLogger {

        public void log(int level, String className, String message) {
        }
    }
}
