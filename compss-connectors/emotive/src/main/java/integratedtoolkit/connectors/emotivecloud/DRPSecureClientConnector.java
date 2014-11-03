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

import net.emotivecloud.scheduler.drp.client.*;


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
import java.util.StringTokenizer;
import java.io.FileInputStream;
import java.net.URL;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.DebugLogger;
import com.trilead.ssh2.SCPClient;
import integratedtoolkit.types.ProjectWorker;
import integratedtoolkit.util.ProjectManager;
import java.util.LinkedList;

public class DRPSecureClientConnector implements Connector, Operations, Cost {

    private DRPClient rest;
    private String providerName;
    private HashMap<String, String> IPToName;
    private HashMap<String, VM> vmIdToStats;
    private Float totalCost;
    private int procCount;
    private float memCount;
    private float diskCount;
    private int vmCount;
    private boolean terminate = false;
    private boolean check = false;

    /*private static final Float procCost=0.012f;//CPU hout
    private static final Float memCost=0.000016f;//MB-hour
    private static final Float diskCost = 0.000083333f;//GB-hour*/
    private static final Float procCost = 100f;//CPU hour
    private static final Float memCost = 200f;//MB-hour
    private static final Float diskCost = 5.0f;//GB-hour
    private static String MAX_VM_CREATION_TIME = "10"; //Minutes
    private Long lastUpdate;
    private HashMap<String, String> props;
    //private int cloudLimit;

    public DRPSecureClientConnector(String providerName, HashMap<String, String> h) throws Exception {
        this.providerName = providerName;

        try {

            StringTokenizer st = new StringTokenizer(h.get("Cert"), "/");
            String s = null;
            props = h;

            while (st.hasMoreElements()) {
                s = st.nextToken();
            }

            String certName = s.substring(0, s.length() - 4);
            rest = new DRPSecureClient(new URL(h.get("Server")), new FileInputStream(h.get("Cert")), certName.toCharArray());

	    if (props.get("MaxVMCreationTime") != null) {
                MAX_VM_CREATION_TIME= props.get("MaxVMCreationTime");
            }

	} catch (Exception e) {
            throw new Exception(e);
        }

        IPToName = new HashMap<String, String>();
        totalCost = 0.0f;
        procCount = 0;
        memCount = 0.0f;
        diskCount = 0.0f;
        lastUpdate = System.currentTimeMillis() / 1000;
        vmIdToStats = new HashMap();
        vmCount = 0;
        terminate = false;
        check = false;
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
        org.apache.log4j.Logger logger = org.apache.log4j.Logger.getLogger(integratedtoolkit.log.Loggers.TS_COMP);
        try {
            logger.info("Applying for an extra worker");
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
            logger.info("ResourceRequest failed");
            return false;
        }
        return true;
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
        return ovf;
    }

    //WAIT
    public String waitCreation(Object vm, ResourceRequest request)
            throws ConnectorException {
        OVFWrapper ovf = (OVFWrapper) vm;
        String vmId = ovf.getId();
        String IP = "";
        Integer poll_time = 5; //Seconds
        Integer polls = 0;
        int errors = 0;
        try {

            vmId = ovf.getId();
            while (rest.getEnvironmentStatus(vmId).compareToIgnoreCase("RUNNING_AVAILABLE") != 0) {
                //Thread.sleep(2000);
                polls++;
                Thread.sleep(poll_time * 1000);
                if (poll_time * polls >= Integer.parseInt(MAX_VM_CREATION_TIME) * 60) {
                    throw new ConnectorException("Maximum VM creation time reached.");
                }
                errors = 0;
            }
        } catch (Exception e) {
            errors++;
            if (errors == 3) {
                throw new ConnectorException("Error get the status of the request");
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
            IP = getIP(vmId);
        } catch (Exception e) {
            errors++;
            if (errors == 3) {
                throw new ConnectorException("Error get the new IP of the machine");
            }
        }


        ResourceDescription requested = request.getRequested();
        ResourceDescription granted = request.getGranted();

        int CPUCount = ovf.getCPUsNumber();
        granted.setProcessorCPUCount(CPUCount);
        granted.setProcessorArchitecture(requested.getProcessorArchitecture());
        granted.setProcessorSpeed(requested.getProcessorSpeed());

        float memorySize = ovf.getMemoryMB() / 1024f;
        granted.setMemoryAccessTime(requested.getMemoryAccessTime());
        granted.setMemoryPhysicalSize(memorySize);
        granted.setMemorySTR(requested.getMemorySTR());
        granted.setMemoryVirtualSize(requested.getMemoryVirtualSize());

        float homeSize = ovf.getDisks().get("home").getCapacityMB() / 1024f;
        granted.setStorageElemAccessTime(requested.getStorageElemAccessTime());
        granted.setStorageElemSTR(requested.getStorageElemSTR());
        granted.setStorageElemSize(homeSize);

        granted.setOperatingSystemType("Linux");
        granted.setSlots(requested.getSlots());
        List<String> apps = requested.getAppSoftware();
        for (int i = 0; i < apps.size(); i++) {
            granted.addAppSoftware(apps.get(i));
        }
        granted.setImage(requested.getImage());
        granted.setType(requested.getType());
        granted.setValue(getMachineCostPerHour(granted));
        increaseStats(vmId, IP, CPUCount, memorySize, homeSize);
        return IP;
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
    public void announceCreation(String IP, String user, LinkedList<ProjectWorker> existingMachines) throws ConnectorException {
        try {
            Process[] p = new Process[existingMachines.size() * 2 + 1];
            String key = null;
            String[] cmd = {"/bin/sh", "-c", "ssh-keyscan " + IP};
            while (key == null) {
                p[0] = Runtime.getRuntime().exec(cmd);
                java.io.InputStream errStream = p[0].getErrorStream();
                java.io.InputStream outStream = p[0].getInputStream();
                java.io.InputStreamReader outReader = new java.io.InputStreamReader(outStream);
                java.io.BufferedReader br = new java.io.BufferedReader(outReader);
                p[0].waitFor();
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
                    p[0] = Runtime.getRuntime().exec(cmd);
                    p[0].waitFor();
                    exitValue = p[0].exitValue();
                    p[0].getErrorStream().close();
                    p[0].getOutputStream().close();
                }
            }
            int i = 1;

            for (ProjectWorker pw : existingMachines) {
                try {
                    cmd = new String[]{"ssh", pw.getUser() + "@" + pw.getName(), "ssh-keyscan " + IP + " >> /home/" + user + "/.ssh/known_hosts"};
                    p[i] = Runtime.getRuntime().exec(cmd);
                    cmd = new String[]{"ssh", user + "@" + IP, "ssh-keyscan " + pw.getName() + " >> /home/" + pw.getUser() + "/.ssh/known_hosts"};
                    p[i + 1] = Runtime.getRuntime().exec(cmd);
                    i += 2;
                } catch (Exception e) {
                    throw new ConnectorException(e);
                }
            }
            i = 1;
            while (i < p.length) {
                p[i].waitFor();
                p[i].getErrorStream().close();
                p[i].getOutputStream().close();
                i++;
            }
        } catch (Exception e) {
            throw new ConnectorException(e);
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

                client.put(p[0], name, p[1], "0700");

                //Extracting Worker package
                String command = "/bin/tar xzf " + p[1] + "/" + name+" -C "+p[1];
                executeTask(IP, cid.getUser(), command);
                executeTask(IP, cid.getUser(), "/bin/echo \"\nfor i in " + p[1] + "/*.jar ; do\n\texport CLASSPATH=\\$CLASSPATH:\\$i\ndone\" >> /home/" + cid.getUser() + "/.bashrc");
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
    public void poweroff(Object vm, String IP)
            throws ConnectorException {
        String vmId = (new EmotiveOVF((OVFWrapper) vm)).getId();
        connectionOnPowerOff(vmId);
        decreaseStats(vmId, IP);
    }

    public synchronized void poweroff(String IP)
            throws ConnectorException {
        String vmId = IPToName.get(IP);
        connectionOnPowerOff(vmId);
        decreaseStats(vmId, IP);
    }

    private void connectionOnPowerOff(String vmId)
            throws ConnectorException {
        try {
            rest.deleteCompute(vmId);
        } catch (Exception e) {
            throw new ConnectorException(e);
        }
    }

    private synchronized void increaseStats(String vmId, String IP, int CPUCount, float memorySize, float homeSize) {
        vmCount++;
        IPToName.put(IP, vmId);
        VM actual = new VM(vmId);
        actual.procs = CPUCount;
        actual.mem = memorySize;
        actual.disk = homeSize;
        procCount += CPUCount;
        memCount += memorySize;
        diskCount += homeSize;
        vmIdToStats.put(vmId, actual);
        updateCost();
    }

    public synchronized void decreaseStats(String vmId, String IP) {
        vmCount--;
        VM actual = vmIdToStats.remove(vmId);
        if (actual != null) {
            procCount -= actual.procs;
            memCount -= actual.mem;
            diskCount -= actual.disk;
        }
        updateCost();
        if (IP == null) {
            for (java.util.Map.Entry<String, String> e : IPToName.entrySet()) {
                if (e.getValue().compareTo(vmId) == 0) {
                    IP = e.getKey();
                    break;
                }
            }
        }
        if (IP != null) {
            IPToName.remove(IP);
        }
    }

    public Float getTotalCost() {
        updateCost();
        return totalCost;
    }

    public boolean getTerminate() {
        return terminate;
    }

    public boolean getCheck() {
        return check;
    }

    public Float currentCostPerHour() {
        return procCount * procCost + memCount * memCost + diskCost * diskCount;
    }

    public Float getMachineCostPerHour(ResourceDescription rc) {
        int procs = rc.getProcessorCPUCount();
        float mem = rc.getMemoryPhysicalSize();
        float disk = rc.getStorageElemSize();
        CloudImageDescription diskImage = rc.getImage();
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

    public void terminate(String workerName) {
        DeletionThread dt;
        dt = new DeletionThread((Operations) this, workerName);
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
                String IP = (String) pw.getName();
                dt = new DeletionThread((Operations) this, IP);
                dt.start();
            }
        } catch (Exception e) {
            throw new ConnectorException(e);
        }
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

    public void executeTask(String name, String user, String command)
            throws ConnectorException {
        String vmId;
        synchronized (this) {
            vmId = IPToName.get(name);
        }
        if (vmId == null) {
            throw new ConnectorException("There is no machine with name: " + name);
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

    private void updateCost() {
        long diff = lastUpdate;
        lastUpdate = System.currentTimeMillis() / 1000;
        diff = lastUpdate - diff;
        totalCost += (procCount * procCost + memCount * memCost + diskCost * diskCount) * diff / (3600);
    }

    private boolean isAble(ResourceDescription request) {
        return true;
    }

    public void announceDestruction(String IP, LinkedList<ProjectWorker> existingMachines) throws ConnectorException {
        try {
            Process[] p = new Process[existingMachines.size() + 1];
            String[] cmd = {"/bin/sh", "-c", "grep -vw " + IP + " " + System.getProperty("user.home") + "/.ssh/known_hosts > known && mv known " + System.getProperty("user.home") + "/.ssh/known_hosts"};
            synchronized (knownHosts) {
                p[0] = Runtime.getRuntime().exec(cmd);
                p[0].waitFor();
                p[0].getErrorStream().close();
                p[0].getOutputStream().close();
            }
            int i = 1;
            for (ProjectWorker pw : existingMachines) {
                cmd = new String[]{"ssh",
                    pw.getUser() + "@" + pw.getName(),
                    "mv /home/" + pw.getUser() + "/.ssh/known_hosts known "
                    + "&& grep -vw " + IP + " known > /home/" + pw.getUser() + "/.ssh/known_hosts"
                    + "&& rm known"};
                p[i] = Runtime.getRuntime().exec(cmd);
                i++;
            }
            i = 1;
            while (i < p.length) {
                p[i].waitFor();
                p[i].getErrorStream().close();
                p[i].getOutputStream().close();
                i++;
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

        VM(String envId) {

            procs = null;
            mem = null;
            disk = null;
            this.envId = envId;
        }
    }

    private static class TrileadDebug implements DebugLogger {

        public void log(int level, String className, String message) {
        }
    }
}
