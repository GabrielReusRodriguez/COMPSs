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



package integratedtoolkit.connectors.rocci;

import integratedtoolkit.connectors.Connector;
import integratedtoolkit.connectors.ConnectorException;
import integratedtoolkit.connectors.Cost;
import integratedtoolkit.connectors.rocci.types.TemplatesType;
import integratedtoolkit.connectors.rocci.types.InstanceType;
import integratedtoolkit.connectors.utils.CreationThread;
import integratedtoolkit.connectors.utils.DeletionThread;
import integratedtoolkit.connectors.utils.KeyManager;
import integratedtoolkit.connectors.utils.Operations;
import integratedtoolkit.types.CloudImageDescription;
import integratedtoolkit.types.ProjectWorker;
import integratedtoolkit.types.ResourceDescription;
import integratedtoolkit.types.ResourceRequest;
import integratedtoolkit.util.ProjectManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Unmarshaller;

import com.trilead.ssh2.ChannelCondition;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.DebugLogger;
import com.trilead.ssh2.SCPClient;
import com.trilead.ssh2.Session;

import org.apache.log4j.Logger;

public class ROCCI implements Connector, Operations, Cost{

   private static Logger logger = Logger.getLogger(integratedtoolkit.log.Loggers.RESOURCES);
   private static Integer MAX_VM_CREATION_TIME = 10; //Minutes
   private static Integer MAX_ALLOWED_ERRORS = 3;
   private static String ROCCI_CLIENT_VERSION = "4.0.1";
   
   private RocciClient client;
  
   //ROCCI Client variables
   private List<String> cmd_string = null; 
   private String attributes = "Not Defined";
   private String vm_user = "user";
  
   
   private int activeRequests;
   private String providerName;
   private TreeSet<VM> vmsToDelete;
   private LinkedList<VM> vmsAlive;  
   private HashMap<String, String> IPToName;
   private HashMap<String, String> IPToType;
   private HashMap<String, Long> IPToStart;
   private Map<String, Connection> ipToConnection;
   private TemplatesType VMTemplates;
   private Map<String, Integer> VMTypesCount;
   private boolean terminate;
   private boolean check;
   private float accumulatedCost;  
   private DeadlineThread dead;

   //SEMAPHORES
   private Object known_hosts;
   private Object stats;
   
   //DEADLINE THRESHOLDS
   private static final long TEN_MIN = 600000;//;ONE_HOUR = 3600000;
   //private static final long NINE_MIN = 540000;//FIFTY_FIVE_MIN = 3300000;
   private static final long EIGHT_MIN = 480000;//FIFTY_MIN = 3000000;
   private static final long ONE_MIN = 60000;//FIVE_MIN = 300000;
   
   
   public ROCCI(String name, HashMap<String, String> props) throws ConnectorException {
	   
	   this.cmd_string = new ArrayList<String>();   
	   this.providerName = name;
	   
	 //user@hostnamer:~$ occi -v
	 // 4.1.0
	 //Options:
	   //        -i, --interactive                Run as an interactive client without additional arguments
	   //        -e, --endpoint URI               OCCI server URI, defaults to 'https://localhost:3300/'
	   //        -n, --auth METHOD                Authentication method, defaults to 'none'
	   //	    -u, --username USER              Username for basic or digest authentication, defaults to 'anonymous'
	   //	    -p, --password PASSWORD          Password for basic, digest and x509 authentication
	   //	    -c, --ca-path PATH               Path to CA certificates directory, defaults to '/etc/grid-security/certificates'
	   //	    -f, --ca-file PATH               Path to CA certificates in a file
	   //	    -F, --filter CATEGORY            Category type identifier to filter categories from model, must be used together with the -m option
	   //	    -x, --user-cred FILE             Path to user's x509 credentials, defaults to '/home/pmes/.globus/usercred.pem'
	   //	    -X, --voms                       Using VOMS credentials; modifies behavior of the X509 authN module
	   //	    -y, --media-type MEDIA_TYPE      Media type for client <-> server communication, defaults to 'text/plain,text/occi'
	   //	    -r, --resource RESOURCE          Resource to be queried (e.g. network, compute, storage etc.), required
	   //	    -t, --attributes ATTRS           Comma separated attributes for new resources such as title="Name", required
	   //	    -T, --context CTX_VARS           Comma separated context variables for new compute resources such as SSH_KEY="ssh-rsa dfsdf...adfdf== user@localhost"
	   //	    -a, --action ACTION              Action to be performed on the resource, required
	   //	    -M, --mixin NAME                 Type and name of the mixin as TYPE#NAME (e.g. os_tpl#monitoring, resource_tpl#medium)
	   //	    -j, --link URI                   Link specified resource to the resource being created, only for action CREATE and resource COMPUTE
	   //	    -g, --trigger-action TRIGGER     Action to be triggered on the resource
	   //	    -l, --log-to OUTPUT              Log to the specified device, defaults to 'STDERR'
	   //	    -o, --output-format FORMAT       Output format, defaults to human-readable 'plain'
	   //	    -m, --dump-model                 Contact the endpoint and dump its model, cannot be used with the interactive mode
	   //	    -d, --debug                      Enable debugging messages
	   //	    -b, --verbose                    Be more verbose, less intrusive than debug mode
	   //	    -h, --help                       Show this message
	   //	    -v, --version                    Show version

	      	   
	   // ROCCI client parameters setup
	   //if (props.get("endpoint") != null)
	   if (props.get("Server") != null)
	       cmd_string.add("--endpoint "+props.get("Server"));
	   
	   if (props.get("auth") != null)
		   cmd_string.add("--auth "+props.get("auth"));
	   
	   if (props.get("username") != null)
		   cmd_string.add("--username "+props.get("username"));
	   
	   if (props.get("password") != null)
		   cmd_string.add("--password "+props.get("password"));
	   
	   if (props.get("ca-path") != null)
		   cmd_string.add("--ca-path "+props.get("ca-path"));
	   
	   if (props.get("ca-file") != null)
		   cmd_string.add("--ca-file "+props.get("ca-file"));
	   
	   if (props.get("filter") != null)
		   cmd_string.add("--filter "+props.get("filter"));
	   
	   if (props.get("user-cred") != null)
		   cmd_string.add("--user-cred "+props.get("user-cred"));
	   
	   if (props.get("voms") != null)
		   cmd_string.add("--voms");
	   
	   if (props.get("media-type") != null)
		   cmd_string.add("--media-type "+props.get("media-type"));
	   
	   //if (props.get("resource") != null)
	   //	   cmd_string.add("--resource "+props.get("resource"));
	   
	   //if (props.get("attributes") != null)
	   //   cmd_string.add("--attributes "+props.get("attributes"));
	   
	   if (props.get("context") != null)
		   cmd_string.add("--context "+props.get("context"));
	   
	   //if (props.get("action") != null)
	   //	   cmd_string.add("--action "+props.get("action"));
	   
	   //if (props.get("mixin") != null)
	   //	   cmd_string.add("--mixin "+props.get("mixin"));
	   
	   if (props.get("link") != null)
		   cmd_string.add("--link "+props.get("link"));
	   
	   if (props.get("trigger-action") != null)
		   cmd_string.add("--trigger-action "+props.get("trigger-action"));
	   
	   if (props.get("log-to") != null)
		   cmd_string.add("--log-to "+props.get("log-to"));
	   
	   if (props.get("output-format") != null)
		   cmd_string.add("--output-format "+props.get("output-format"));
	   
	   if (props.get("dump-model") != null)
		   cmd_string.add("--dump-model");
	   
	   if (props.get("debug") != null)
		   cmd_string.add("--debug");
	   
	   if (props.get("verbose") != null)
		   cmd_string.add("--verbose");

	   // ROCCI connector parameters setup
       if (props.get("max-vm-creation-time") != null) {
           MAX_VM_CREATION_TIME = Integer.parseInt(props.get("max-vm-creation-time"));
       }
       
       if (props.get("max-connection-errors") != null) {
           MAX_ALLOWED_ERRORS = Integer.parseInt(props.get("max-connection-errors"));
       }
       
       if (props.get("owner") != null && props.get("jobname") != null)
    	   attributes=props.get("owner")+"-"+props.get("jobname");
       
       if (props.get("vm-user") != null)
    	   vm_user = props.get("vm-user");

       //Connector data init
       activeRequests = 0;
       IPToName = new HashMap<String, String>();
       IPToType = new HashMap<String, String>();
       IPToStart = new HashMap<String, Long>();
       VMTemplates = new TemplatesType();
       VMTypesCount = new HashMap<String, Integer>();
       vmsToDelete = new TreeSet<VM>();
       vmsAlive = new LinkedList<VM>();
       
       check = false;
       terminate = false;

       accumulatedCost = 0;
       known_hosts = 0;
       stats = 0;
       
       ipToConnection = Collections.synchronizedMap(new HashMap<String,Connection>());
       
       client = new RocciClient(cmd_string, attributes);
  
       //Loading VM templates
       loadVMTemplates(props.get("templates"));

       dead = new DeadlineThread();
       dead.start();      
   }   

   //CONNECTOR INTERFACE IMPLEMENTATION  
   public String getId() {
       return "integratedtoolkit.connectors.rocci.ROCCI";
   }
   
   public String getDefaultUser() {
       return vm_user;
   }
   
	@Override
	public Float getTotalCost() {
		  float runningCost = 0.0f;
	        for (java.util.Map.Entry<String, Long> e : IPToStart.entrySet()) {
	            float machineCost = 0.0f;
	            String IP = e.getKey();
	            String instanceType = IPToType.get(IP);
	            
	            for(InstanceType instance: VMTemplates.getInstance()){
	            	if((instance.getType()).equals(instanceType)){
	            		machineCost = (((System.currentTimeMillis() - e.getValue()) / 3600000) + 1) * instance.getPrice();
		            	break;
	            	}

	            }

	            runningCost += machineCost;
	        }
	        return accumulatedCost + runningCost;
	}

	@Override
	public Float currentCostPerHour() {
		 Float price = (float) 0;
		 
		 for(InstanceType instance: VMTemplates.getInstance()){
			 price+= VMTypesCount.get(instance.getType()) * instance.getPrice();
		 }
		 
		 return price;
	}
	
	@Override
	public Float getMachineCostPerHour(ResourceDescription rc) {	        
	        String instanceCode = classifyMachine(rc.getProcessorCPUCount(),
	                rc.getMemoryPhysicalSize() * 1024,
	                rc.getStorageElemSize() * 1024);
	        
	        for(InstanceType instance: VMTemplates.getInstance()){
	        	if(instanceCode.equals(instance.getType())){
	        		return instance.getPrice();
	        	}
	        }
	     
	        return null;
	}
	
    /*public void executeTask(String ip, String user, String command, Connection c) throws ConnectorException {
        Session session = null;
        try {
	            session = c.openSession();
	            session.execCommand(command);
	            session.waitForCondition(ChannelCondition.EXIT_STATUS, 0);
	            int exitStatus = session.getExitStatus();
	            if (exitStatus != 0) {
	                throw new ConnectorException("Failed to execute command " + command + " in " + ip);
	            }
	            session.close();
        } catch (Exception e) {
        	logger.error("Exception connecting to " + user + "@" + ip + " to run command " + command, e);
            throw new ConnectorException(e);
        }
    }*/
	
	public void executeTask(String ip, String user, String command, Connection c) throws ConnectorException {
		
		Integer poll_time = 5; //Seconds
    	Integer failures = 0;
		boolean available = false;
		Session session = null;
		
		while (!available) {
			try{
		        Thread.sleep(poll_time*1000);
		        session = c.openSession();
	            session.execCommand(command);
	            session.waitForCondition(ChannelCondition.EXIT_STATUS, 0);
	            int exitStatus = session.getExitStatus();
	            if (exitStatus != 0) {
	            	failures++;
		           	available = false;
		           	if(failures == MAX_ALLOWED_ERRORS){
		           		session.close();
		           		logger.error("Exception connecting to " + user + "@" + ip + " to run command " + command);
		           	    throw new ConnectorException("Exception connecting to " + user + "@" + ip + " to run command " + command);
		            }
	            }
			 }catch(Exception e){
				throw new ConnectorException("Exception connecting to " + user + "@" + ip + " to run command " + command);
			 }
	         
			 session.close();
	    	 available = true;
		}		
    }
    
    private String createMachine(String instanceCode, String diskImage) throws InterruptedException {
       //Create
       String resource_id = null;
       resource_id = client.create_compute(diskImage, instanceCode);
       return resource_id;   
    }

    
    //OPERATIONS INTERFACE IMPLEMENTATION
    @Override
    public Object poweron(String name, ResourceDescription requested, String diskImage)
            throws ConnectorException {
        try {
            String instanceCode = classifyMachine(requested.getProcessorCPUCount(), requested.getMemoryPhysicalSize() * 1024f, requested.getStorageElemSize() * 1024f);
            logger.debug("Request for "+instanceCode+" VM creation sent.");
            return createMachine(instanceCode, diskImage);
        } catch (Exception e) {
            
        	e.printStackTrace();
        	throw new ConnectorException(e);
        }
    }
    
	@Override
	public String waitCreation(Object vm, ResourceRequest request) throws ConnectorException {
        try {
        	
        	Integer poll_time = 5; //Seconds
        	Integer polls = 0;
        	int errors = 0;
        	
            String vm_id = vm.toString();
        	String status = null;

            status = client.get_resource_status(vm_id);
            
            
            Thread.sleep(poll_time * 1000);
            while (status.compareTo("inactive") == 0) {
                try {
                    polls++;
                    Thread.sleep(poll_time * 1000);
                    if (poll_time * polls >= MAX_VM_CREATION_TIME * 60) {
                        throw new ConnectorException("Maximum VM creation time reached.");
                    }
                    status = client.get_resource_status(vm_id);
                    errors = 0;
                } catch (Exception e) {
                    errors++;
                    if (errors == MAX_ALLOWED_ERRORS) {
                    	logger.error("Error -> "+e.getMessage());
                        throw new ConnectorException("Error getting the status of the request");
                    }
                }
            }

            ResourceDescription requested = request.getRequested();
            ResourceDescription granted = request.getGranted();
                        
            String instanceType = requested.getType();
            String instanceId = vm_id;
            
            String IP = client.get_resource_address(vm_id);
            
            //Thread.sleep(20 * 1000);
            
            /*try {
                do {
                    polls++;
                    Thread.sleep(poll_time * 1000);
                    if (poll_time * polls >= Integer.parseInt(MAX_VM_CREATION_TIME) * 60) {
                        throw new ConnectorException("Maximum VM creation time reached.");
                    }
                    errors = 0;
                } while (IP.length() == 0);
            IP = client.get_resource_address(vm_id);
            } catch (Exception e) {
                errors++;
                if (errors == MAX_ALLOWED_ERRORS) {
                	logger.error("Error -> "+e.getMessage());
                    throw new ConnectorException("Error getting the new IP of the machine");
                }
            }
            */
            
            synchronized (stats) {
                synchronized (IPToName) {
                    IPToName.put(IP, instanceId);
                }
                	IPToType.put(IP, instanceType);
                	IPToStart.put(IP, System.currentTimeMillis());
                	
                for(InstanceType instance: VMTemplates.getInstance()){
                	if(instanceType.equals(instance.getType())){
                		int count = VMTypesCount.get(instance.getType());
                		count++;
                		VMTypesCount.put(instance.getType(), count);               		
                		granted.setProcessorCPUCount(instance.getCPU());
                        granted.setMemoryPhysicalSize(instance.getMemory() / 1024f);
                        granted.setStorageElemSize(instance.getDisk() / 1024f);
                        break;
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
            granted.setImage(requested.getImage());
            granted.setType(requested.getType());
            granted.setValue(getMachineCostPerHour(granted));
            
            VM vmInfo = new VM(vm_id);
            vmInfo.ip = IP;
            vmInfo.procs = granted.getProcessorCPUCount();
            vmInfo.mem = granted.getMemoryPhysicalSize();
            vmInfo.disk = granted.getStorageElemSize();
            vmInfo.image = granted.getImage().getName();

            vmsAlive.add(vmInfo);
            
            logger.debug("Virtual machine "+vm+" created with " + IP+" address.");
            
            return IP;
            
        } catch (Exception e) {
        	e.printStackTrace();
            throw new ConnectorException(e);
        }
    
	}

	//CONFIGURE MASTERs ACCESS
    public void configureAccess(String workerIP, String user) throws ConnectorException {
    	putInKnownHosts(workerIP);
    	Connection c;
    	try {
			c = getConnection(workerIP, true, user);
		} catch (Exception e) {
			throw new ConnectorException(e);
		}
		ipToConnection.put(workerIP, c);
    	configureKeys(workerIP, user, c);
    }
	
	@Override
	public void announceCreation(String IP, String user,
			LinkedList<ProjectWorker> existingMachines) throws ConnectorException {
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
			logger.error("ERROR ", e);
		    throw new ConnectorException(e);
		}	
	}

	@Override
	public void prepareMachine(String ip, CloudImageDescription cid)
			throws ConnectorException {
	 try{
        LinkedList<String[]> packages = cid.getPackages();
        SCPClient scp_client = null;
        Connection c = null;
        if (!packages.isEmpty()) {
        	c = ipToConnection.get(ip);
        	//logger.debug("Reusing connection in prepare machine for " + ip + ":" + c);
        	if (c == null)
        		c = getConnection(ip, true, cid.getUser()); 
            scp_client = c.createSCPClient();
        } else {
            return;
        }

        for (String[] p : packages) {
            String[] path = p[0].split("/");
            String name = path[path.length - 1];

            if (scp_client == null) {
                throw new ConnectorException("Can not connect to " + ip);
            }
            //System.out.println("Copying " + p[0] + " to: " + p[1]);
            try {
            scp_client.put(p[0], name, p[1], "0700");
            }
            catch(Exception e) {
            	e.printStackTrace();
            }
    
            //Extracting Worker package
            String command = "cd " + p[1] + ";" + "/bin/tar xzf " + p[1] + "/" + name
                    + "; "
                    + "/bin/echo \"\nfor i in " + p[1] + "/*.jar ; do\n\texport CLASSPATH=\\$CLASSPATH:\\$i\ndone\" >> /home/" + cid.getUser() + "/.bashrc"
                    + "; ";
            executeTask(ip, cid.getUser(), command, c);
        }
    } catch (Exception e) {
        e.printStackTrace();
        throw new ConnectorException("Failed preparing the Machine " + ip + ": " + e.getMessage());
    }
	    }

	@Override
	public void poweroff(String workerIP) throws ConnectorException {

		synchronized (stats) {
			String instanceId = null;
			synchronized (IPToName) {
				instanceId = IPToName.remove(workerIP);
				vmsAlive.remove(instanceId);
			}
			ArrayList<String> instanceIds = new ArrayList<String>();
			instanceIds.add(instanceId);
			
			if(instanceId.compareTo("null")!=0)	
				client.delete_compute(instanceId);
			
			String instanceType = IPToType.remove(workerIP);
			Long creationTime = IPToStart.remove(workerIP);
			
			 for(InstanceType instance: VMTemplates.getInstance()){
				 if(instanceType.equals(instance.getType())){
					 int count = VMTypesCount.get(instance.getType());
					 count--;
					 VMTypesCount.put(instance.getType(), count);
					 accumulatedCost += (((System.currentTimeMillis() - creationTime) / 3600000) + 1) * instance.getPrice();
					 break;
				 }
			 }
			
			activeRequests--;			
			logger.debug("Virtual machine terminated: " + workerIP);
		}
	}

	@Override
	public void poweroff(Object description, String workerId)
			throws ConnectorException {
		synchronized (stats) {
			String instanceId = IPToName.remove(workerId);
			vmsAlive.remove(instanceId);
			client.delete_compute(instanceId);
			activeRequests--;
			logger.debug("Virtual machine terminated: " + instanceId);
		}
	}

	@Override
	public void announceDestruction(String IP,
			LinkedList<ProjectWorker> existingIPs) throws ConnectorException {
		try {
			Process p;
			String[] cmd = {
					"/bin/sh",
					"-c",
					"grep -vw " + IP + " " + System.getProperty("user.home")
							+ "/.ssh/known_hosts > known && mv known "
							+ System.getProperty("user.home")
							+ "/.ssh/known_hosts" };
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
				cmd = new String[] {
						"ssh",
						getDefaultUser() + "@" + vm,
						"mv /home/" + getDefaultUser()
								+ "/.ssh/known_hosts known " + "&& grep -vw "
								+ IP + " known > /home/" + getDefaultUser()
								+ "/.ssh/known_hosts" + "&& rm known" };
				p = Runtime.getRuntime().exec(cmd);
				p.waitFor();
			}
		} catch (Exception e) {
			throw new ConnectorException(e);
		}
	}

	@Override
	public boolean getTerminate() {
		// TODO Auto-generated method stub
		return terminate;
	}

	@Override
	public boolean getCheck() {
		// TODO Auto-generated method stub
		return check;
	}

	@Override
	public void destroy(Object vm) throws ConnectorException {
		//throw new UnsupportedOperationException("Not supported yet.");
		String vmId = (String) vm;
		poweroff(vm, client.get_resource_address(vmId));	
	}

	@Override
	public boolean turnON(String name, ResourceRequest rR) {
        
		 CloudImageDescription cid = rR.getRequested().getImage();
		
		// Check if we can reuse one of the vms put to delete (but not yet destroyed)
        VM vmInfo = tryToReuseVM(rR.getRequested());
        
        if (vmInfo != null) {
            logger.debug("Reusing VM: " + vmInfo);
            vmInfo.toDelete = false;
            vmsToDelete.remove(vmInfo);           
            CreationThread ct= new CreationThread((Operations) this, name, providerName, rR, cid, true);
            ct.start();
            return true;
        }

        try {
        	  activeRequests++;

              if (terminate) {
                  activeRequests--;
                  return false;
              }
              logger.info("Applying for an extra VM.");
              CreationThread ct;
              ct = new CreationThread((Operations) this, name, this.providerName, rR, cid, false);
              ct.start();     
        } catch (Exception e) {
            logger.info("ResourceRequest failed");
            return false;
        }
        return true;
	}
	
	@Override
	public void terminate(String workerIP) {
        DeletionThread dt;
        dt = new DeletionThread((Operations) this, workerIP);
        dt.start();		
	}

	@Override
	 public void terminateALL()	throws ConnectorException {
		 
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

	@Override
	public void stopReached() {
		// TODO Auto-generated method stub
		check = true;
	}

	@Override
	public Long getNextCreationTime() throws ConnectorException {
		return VMTemplates.getEstimatedCreationTime() * 1000l;
	}

	@Override
	public void terminate(ResourceDescription rd) {
		   DeletionThread dt;
	        dt = new DeletionThread((Operations) this, rd.getName());
	        dt.start();		
	}
	
	//PRIVATE METHODS		
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
   
    private static class TrileadDebug implements DebugLogger {

        public void log(int level, String className, String message) {
        }
    }

    private void configureKeys(String workerIP, String user, Connection c) throws ConnectorException {
        try {
            String keypair = KeyManager.getKeyPair();
            String MY_PUBLIC_KEY = KeyManager.getPublicKey(keypair);
            String MY_PRIVATE_KEY = KeyManager.getPrivateKey(keypair);

            String command =        "/bin/echo \"" + MY_PUBLIC_KEY + "\" > /home/" + user + "/.ssh/" + KeyManager.getKeyType() + ".pub"
                                        + "; "
                                        + "/bin/echo \"" + MY_PUBLIC_KEY + "\" >> /home/" + user + "/.ssh/authorized_keys"
                                        + "; "
                                        + "/bin/echo \"" + MY_PRIVATE_KEY + "\" > /home/" + user + "/.ssh/" + KeyManager.getKeyType()
                                        + "; "
                                        + "chmod 600 /home/" + user + "/.ssh/" + KeyManager.getKeyType();
            executeTask(workerIP, user, command, c);
            
        } catch (Exception e) {
            throw new ConnectorException(e);
        }
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
 	
 	 private String classifyMachine(int CPU, float memory, float disk) {

 		String code = "";
 		
 		for (InstanceType instance: VMTemplates.getInstance()){
 			 if (CPU <= instance.getCPU() && memory <= instance.getMemory() && disk <= instance.getDisk()) {
 				 code = instance.getType();
 				 break;
 			 }
 		}
      
        return code;
        
     }
 	
 	   private void loadVMTemplates(String vm_templates_file) throws ConnectorException {
 		   	
 		    JAXBContext jbc;
 			try {
 				  logger.info("Loading VM templates from: "+vm_templates_file);
 				  jbc = JAXBContext.newInstance(integratedtoolkit.connectors.rocci.types.ObjectFactory.class.getPackage().getName());
 		          Unmarshaller m = jbc.createUnmarshaller();
 		          File f = new File (vm_templates_file);
 		          JAXBElement element = (JAXBElement) m.unmarshal (f);
 		          
 		          //Loading templates 	
 		          VMTemplates = (TemplatesType) element.getValue();
 		          
 		         for (InstanceType instance: VMTemplates.getInstance()){
 		        	 instance.setMemory(instance.getMemory() * 1024);
 		        	 instance.setDisk(instance.getDisk() * 1024);
 		        	 VMTypesCount.put(instance.getType(), 0);
 		         }
 		         
 		    } catch (Exception e) {
 		    	logger.error("Exception loading VM templates from: "+vm_templates_file);
 		    	e.printStackTrace();
 		        throw new ConnectorException(e);
 		    }	
 		}
	
	//PRIVATE CLASSES	
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
	                synchronized (ROCCI.this) {
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
	                                dt = new DeletionThread((Operations) ROCCI.this, vmInfo.ip);
	                                dt.start();
	                            } else {
	                                vmsAlive.add(vmsAlive.pollFirst()); // put at the end (the 10-min slot will be renewed)
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
}
