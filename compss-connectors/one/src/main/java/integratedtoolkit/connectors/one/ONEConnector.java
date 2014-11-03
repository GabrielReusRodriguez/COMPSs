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



package integratedtoolkit.connectors.one;

import integratedtoolkit.connectors.Connector;
import integratedtoolkit.connectors.ConnectorException;
import integratedtoolkit.connectors.Cost;
import integratedtoolkit.connectors.utils.CreationThread;
import integratedtoolkit.connectors.utils.DeletionThread;
import integratedtoolkit.connectors.utils.KeyManager;
import integratedtoolkit.connectors.utils.Operations;
import integratedtoolkit.log.Loggers;
import integratedtoolkit.types.CloudImageDescription;
import integratedtoolkit.types.ProjectWorker;
import integratedtoolkit.types.ResourceDescription;
import integratedtoolkit.types.ResourceRequest;
import integratedtoolkit.util.ProjectManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;
import org.opennebula.client.Client;
import org.opennebula.client.ClientConfigurationException;
import org.opennebula.client.OneResponse;
import org.opennebula.client.template.Template;
import org.opennebula.client.template.TemplatePool;
import org.opennebula.client.vm.VirtualMachine;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;

public class ONEConnector implements Connector, Operations, Cost {

    private static final String ENDPOINT_PROP = "Server";
    private static final String ONE_USER_PROP = "one-user";
    private static final String ONE_PWD_PROP = "one-password";
    private static final String RUNNING = "runn";
    private static final long DEF_CREATION_TIME = 120000;
    private static final long POLLING_INTERVAL = 5;
    private static final int TIMEOUT = 1800;
    private static final int MAX_ERRORS = 5;
    private static final Integer known_hosts = new Integer(0);
    private static long creationTime = DEF_CREATION_TIME;
    private final String providerName;
    private Client client;
    private boolean terminate;
    private Map<String, Integer> vms;
    private boolean check;
    private static final Logger logger = Logger.getLogger(Loggers.TS_COMP);

    public ONEConnector(String name, Map<String, String> props) throws ConnectorException {
	this.providerName = name;
	this.terminate = false;
	this.check = false;
	this.vms = new ConcurrentHashMap<String, Integer>();

	try {
	    this.client = new Client(props.get(ONE_USER_PROP) + ":" + props.get(ONE_PWD_PROP),
		    props.get(ENDPOINT_PROP));

	} catch (ClientConfigurationException e) {
	    logger.error("Error configuring ONE client.", e);
	    throw new ConnectorException("Error configuring ONE client.");
	}
    }

    public boolean turnON(String name, ResourceRequest rR) {
	if (this.terminate || !isAble(rR.getRequested())) {
	    return false;
	}
	try {
	    logger.info("Applying for an extra worker");

	    CloudImageDescription cid = rR.getRequested().getImage();
	    Float diskSize = cid.getDiskSize();

	    if (diskSize != null && diskSize > rR.getRequested().getStorageElemSize()) {
		rR.getRequested().setStorageElemSize(diskSize);
	    }

	    CreationThread ct;
	    ct = new CreationThread((Operations) this, name, providerName, rR, cid, false);
	    ct.start();

	    return true;

	} catch (Exception e) {
	    logger.error("ResourceRequest failed", e);
	    return false;
	}
    }

    public Object poweron(String name, ResourceDescription requested, String diskImage)
	    throws ConnectorException {

	Template template = this.classifyMachine(requested.getProcessorCPUCount(),
		requested.getMemoryPhysicalSize(), requested.getStorageElemSize());

	try {
	    String pubKey = KeyManager.getPublicKey(KeyManager.getKeyPair());
	    VMTemplate vmTemp = new VMTemplate(template.info().getMessage());
	    vmTemp.setImage(diskImage);
	    vmTemp.setPublicKey(pubKey);

	    OneResponse resp = template.instantiate(name, false, vmTemp.getString());

	    if (resp.isError()) {
		throw new ConnectorException(resp.getErrorMessage());
	    }
	    return resp.getMessage();

	} catch (ClassCastException e) {
	    logger.error("DOM exception when serializing template.", e);
	    throw new ConnectorException(e);
	} catch (Exception e) {
	    logger.error("Unknown exception.", e);
	    throw new ConnectorException(e);
	}
    }

    public String waitCreation(Object vm, ResourceRequest request) throws ConnectorException {
	int vmId = Integer.parseInt((String) vm);
	VirtualMachine virtualMachine = new VirtualMachine(vmId, client);
	virtualMachine.info();
	int tries = 0;

	long start = System.currentTimeMillis();

	while (virtualMachine.status() == null || !virtualMachine.status().equals(RUNNING)) {

	    if (tries * POLLING_INTERVAL > TIMEOUT) {
		throw new ConnectorException("Maximum VM creation time reached.");
	    }

	    tries++;

	    try {
		Thread.sleep(POLLING_INTERVAL * 1000);
	    } catch (InterruptedException e) {
		// ignore
	    }
	    virtualMachine.info();
	}
	long end = System.currentTimeMillis();

	synchronized (this) {
	    creationTime = end - start;
	}

	String ip = virtualMachine.xpath("//NIC/IP");

	// TODO Change to values from monitoring, not template
	ResourceDescription requested = request.getRequested();
	ResourceDescription granted = request.getGranted();

	granted.setType(requested.getType());

	String cpu = virtualMachine.xpath("//TEMPLATE/VCPU");

	granted.setProcessorCPUCount(Integer.parseInt(cpu));
	granted.setProcessorArchitecture(requested.getProcessorArchitecture());
	granted.setProcessorSpeed(requested.getProcessorSpeed());

	String memory = virtualMachine.xpath("//TEMPLATE/MEMORY");

	granted.setMemoryPhysicalSize(Integer.parseInt(memory) / 1024f);
	granted.setMemoryAccessTime(requested.getMemoryAccessTime());
	granted.setMemorySTR(requested.getMemorySTR());
	granted.setMemoryVirtualSize(requested.getMemoryVirtualSize());

	String homeSize = virtualMachine.xpath("//TEMPLATE/DISK[TYPE=\"fs\"]/SIZE");

	granted.setStorageElemSize(Integer.parseInt(homeSize) / 1024f);
	granted.setStorageElemAccessTime(requested.getStorageElemAccessTime());
	granted.setStorageElemSTR(requested.getStorageElemSTR());

	granted.setOperatingSystemType("Linux");
	granted.setSlots(requested.getSlots());

	granted.getAppSoftware().addAll(requested.getAppSoftware());
	granted.setImage(requested.getImage());
	granted.setValue(getMachineCostPerHour(granted));

	vms.put(ip, vmId);

	return ip;
    }

    public void configureAccess(String IP, String user) throws ConnectorException {
	String keypair = KeyManager.getKeyPair();
	int errors = 0;
	boolean done = false;
	JSch jsch = new JSch();
	Session session = null;
	ChannelSftp sftp = null;
	ChannelExec exec = null;
	String publicKey = keypair + ".pub";
	String privateKey = keypair;
	String keyType = KeyManager.getKeyType();
	String path = "/home/" + user + "/.ssh/";
	java.util.Properties config = new java.util.Properties();
	config.put("StrictHostKeyChecking", "no");

	while (!done) {
	    try {
		jsch.addIdentity(keypair);

		session = jsch.getSession(user, IP, 22);
		session.setConfig(config);
		session.connect(30000);

		sftp = (ChannelSftp) session.openChannel("sftp");
		sftp.connect();

		sftp.put(publicKey, path + keyType + ".pub", ChannelSftp.OVERWRITE);
		sftp.put(privateKey, path + keyType, ChannelSftp.OVERWRITE);

		exec = (ChannelExec) session.openChannel("exec");
		exec.setCommand("chmod 600 " + path + keyType);
		exec.connect();

		done = true;

	    } catch (JSchException e) {
		errors++;

		if (errors > MAX_ERRORS) {
		    logger.error("JSch error.", e);
		    throw new ConnectorException(e);
		}
		try {
		    Thread.sleep(POLLING_INTERVAL * 1000);
		} catch (InterruptedException e1) {
		    // ignore
		}
	    } catch (SftpException e) {
		logger.error("Sftp error.", e);
		throw new ConnectorException(e);
	    } catch (Exception e) {
		logger.error("Unknown exception.", e);
		throw new ConnectorException(e);
	    } finally {
		if (session != null && session.isConnected()) {
		    session.disconnect();
		}
		if (exec != null && exec.isConnected()) {
		    exec.disconnect();
		}
		if (sftp != null && sftp.isConnected()) {
		    sftp.disconnect();
		}
	    }
	}
    }

    public Long getNextCreationTime() throws ConnectorException {
	return creationTime;
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

    public void terminateALL() throws ConnectorException {

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

	LinkedList<ProjectWorker> activeVMs = ProjectManager
		.getProviderRegisteredMachines(providerName);
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

    public void stopReached() {
	check = true;
    }

    public void poweroff(Object vm, String workerId) throws ConnectorException {
	int vmId = Integer.parseInt((String) vm);
	VirtualMachine virtualMachine = new VirtualMachine(vmId, client);
	virtualMachine.shutdown();
    }

    public void poweroff(String workerId) throws ConnectorException {
	int vmId = vms.get(workerId);
	VirtualMachine virtualMachine = new VirtualMachine(vmId, client);
	virtualMachine.shutdown();
    }

    public void destroy(Object vm) throws ConnectorException {
	int vmId = Integer.parseInt((String) vm);
	VirtualMachine virtualMachine = new VirtualMachine(vmId, client);
	virtualMachine.shutdown();
    }

    public boolean getTerminate() {
	return terminate;
    }

    public boolean getCheck() {
	return check;
    }

    public void announceCreation(String IP, String user, LinkedList<ProjectWorker> existingMachines)
	    throws ConnectorException {
	try {
	    Process[] p = new Process[existingMachines.size() * 2 + 1];
	    String key = null;
	    String[] cmd = { "/bin/sh", "-c", "ssh-keyscan " + IP };
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

	    cmd = new String[] {
		    "/bin/sh",
		    "-c",
		    "/bin/echo " + key + " >> " + System.getProperty("user.home")
			    + "/.ssh/known_hosts" };
	    synchronized (known_hosts) {
		int exitValue = -1;
		while (exitValue != 0) {
		    p[0] = Runtime.getRuntime().exec(cmd);
		    p[0].waitFor();
		    p[0].getErrorStream().close();
		    p[0].getOutputStream().close();
		    exitValue = p[0].exitValue();
		}
	    }
	    int i = 1;

	    for (ProjectWorker pw : existingMachines) {
		try {
		    cmd = new String[] { "ssh", pw.getUser() + "@" + pw.getName(),
			    "ssh-keyscan " + IP + " >> /home/" + user + "/.ssh/known_hosts" };
		    p[i] = Runtime.getRuntime().exec(cmd);
		    cmd = new String[] {
			    "ssh",
			    user + "@" + IP,
			    "ssh-keyscan " + pw.getName() + " >> /home/" + pw.getUser()
				    + "/.ssh/known_hosts" };
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

    public void prepareMachine(String IP, CloudImageDescription cid) throws ConnectorException {

	JSch jsch = new JSch();

	java.util.Properties config = new java.util.Properties();
	config.put("StrictHostKeyChecking", "no");

	LinkedList<String[]> packages = cid.getPackages();
	Session session = null;
	ChannelSftp sftp = null;
	ChannelExec exec = null;

	try {
	    jsch.addIdentity(KeyManager.getKeyPair());
	    session = jsch.getSession(cid.getUser(), IP, 22);
	    // session.setPassword(password);
	    session.setConfig(config);
	    session.connect(30000);

	    sftp = (ChannelSftp) session.openChannel("sftp");
	    exec = (ChannelExec) session.openChannel("exec");
	    sftp.connect();

	    for (String[] p : packages) {
		String[] path = p[0].split("/");
		String name = path[path.length - 1];
		String target = p[1] + "/" + name;

		sftp.put(p[0], target);

		String bashrc = "\nfor i in " + p[1]
			+ "/*.jar ; do\n\texport CLASSPATH=$CLASSPATH:$i\ndone";

		InputStream is = new ByteArrayInputStream(bashrc.getBytes());
		sftp.put(is, "/home/" + cid.getUser() + "/.bashrc", ChannelSftp.APPEND);
		is.close();

		exec.setCommand("/bin/tar xzf " + target);
		exec.connect();
		exec.disconnect();
	    }
	} catch (JSchException e) {
	    logger.error("JSch error.", e);
	    throw new ConnectorException(e);
	} catch (SftpException e) {
	    logger.error("Sftp error.", e);
	    throw new ConnectorException(e);
	} catch (IOException e) {
	    logger.error("I/O error.", e);
	    throw new ConnectorException(e);
	} catch (Exception e) {
	    logger.error("Unknown exception.", e);
	    throw new ConnectorException(e);
	} finally {
	    if (session != null && session.isConnected()) {
		session.disconnect();
	    }
	    if (exec != null && exec.isConnected()) {
		exec.disconnect();
	    }
	    if (sftp != null && sftp.isConnected()) {
		sftp.disconnect();
	    }
	}
    }

    public void announceDestruction(String IP, LinkedList<ProjectWorker> existingMachines)
	    throws ConnectorException {
	try {
	    Process[] p = new Process[existingMachines.size() + 1];
	    String[] cmd = {
		    "/bin/sh",
		    "-c",
		    "grep -vw " + IP + " " + System.getProperty("user.home")
			    + "/.ssh/known_hosts > known && mv known "
			    + System.getProperty("user.home") + "/.ssh/known_hosts" };

	    synchronized (known_hosts) {
		p[0] = Runtime.getRuntime().exec(cmd);
		p[0].waitFor();
		p[0].getErrorStream().close();
		p[0].getOutputStream().close();
	    }
	    int i = 1;
	    for (ProjectWorker pw : existingMachines) {
		cmd = new String[] {
			"ssh",
			pw.getUser() + "@" + pw.getName(),
			"mv /home/" + pw.getUser() + "/.ssh/known_hosts known " + "&& grep -vw "
				+ IP + " known > /home/" + pw.getUser() + "/.ssh/known_hosts"
				+ "&& rm known" };
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

    private boolean isAble(ResourceDescription rd) {
	// TODO
	return true;
    }

    public Float getTotalCost() {
	// TODO Auto-generated method stub
	return 0f;
    }

    public Float currentCostPerHour() {
	// TODO Auto-generated method stub
	return 0f;
    }

    public Float getMachineCostPerHour(ResourceDescription rc) {
	// TODO Auto-generated method stub
	return 0f;
    }

    private Template classifyMachine(int cpu, float memory, float disk) {
	if (logger.isDebugEnabled()) {
	    logger.debug("Classifying machine with CPU=" + cpu + ", Mem=" + memory + ", Disk="
		    + disk);
	}

	// Inputs in MB
	TemplatePool pool = new TemplatePool(this.client);
	int minCPU = Integer.MAX_VALUE;
	float minMem = Float.MAX_VALUE;
	int minDisk = Integer.MAX_VALUE;
	Template minTemplate = null;

	pool.info();

	if (logger.isDebugEnabled()) {
	    logger.debug("There are a total of " + pool.getLength() + " templates.");
	}
	for (Template temp : pool) {
	    temp.info();
	    int tmpCPU = Integer.valueOf(temp.xpath("//CPU"));
	    float tmpMem = Integer.valueOf(temp.xpath("//MEMORY"));
	    int tmpDisk = Integer.valueOf(temp.xpath("//DISK[TYPE=\"fs\"]/SIZE"));

	    if (logger.isDebugEnabled()) {
		logger.debug("Comparing with template " + temp.getName() + ": CPU=" + tmpCPU
			+ ", Mem=" + tmpMem + ", Disk=" + tmpDisk);
	    }

	    if (cpu <= tmpCPU && tmpCPU <= minCPU && memory <= tmpMem && tmpMem <= minMem
		    && disk <= tmpDisk && tmpDisk <= minDisk) {
		if (tmpCPU < minCPU || tmpMem < minMem || tmpDisk < minDisk) {
		    minCPU = tmpCPU;
		    minMem = tmpMem;
		    minDisk = tmpDisk;
		    minTemplate = temp;
		}
	    }
	}
	if (logger.isDebugEnabled() && minTemplate != null) {
	    logger.debug("Found a matching template: " + minTemplate.getName());
	}
	return minTemplate;
    }
}
