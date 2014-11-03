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




package integratedtoolkit.connectors.utils;

import integratedtoolkit.connectors.ConnectorException;
import org.apache.log4j.Logger;
import integratedtoolkit.log.Loggers;

import integratedtoolkit.types.ResourceDescription;
import integratedtoolkit.types.ResourceRequest;
import integratedtoolkit.components.impl.TaskDispatcher;
import integratedtoolkit.types.CloudImageDescription;
import integratedtoolkit.types.ProjectWorker;
import integratedtoolkit.util.CloudManager;
import integratedtoolkit.util.ProjectManager;

public class CreationThread extends Thread {

    private Operations operations;
    private String name; //Id for the CloudProvider or IP if VM is reused
    private String provider;
    private ResourceRequest rr;
    private Object vm;
    private static TaskDispatcher TD;
    private static Integer count = 0;
    private static final Logger logger = Logger.getLogger(Loggers.RESOURCES);
    private CloudImageDescription cid;
    private boolean reused;

    public CreationThread(Operations operations, String name, String provider, ResourceRequest rR, CloudImageDescription cid, boolean reused) {
        this.operations = operations;
        this.provider = provider;
        this.name = name;
        this.rr = rR;
        this.cid = cid;
        this.reused = reused;
        synchronized (count) {
            count++;
        }
    }

    public static int getCount() {
        return count;
    }

    public void run() {
        if (reused) {
            rr.setGranted(rr.getRequested());
            TD.addCloudNode(name, rr, provider, rr.getGranted().getProcessorCPUCount(), operations.getCheck());
            synchronized (count) {
                count--;
            }
            return;
        }
        boolean check = operations.getCheck();
        //ASK FOR THE VIRTUAL RESOURCE
        try {
            //turn on the VM and expects the new mr description
            vm = operations.poweron(name, rr.getRequested(), cid.getName());
        } catch (Exception e) {
            logger.info("Error Creating asking a new Resource to " + provider, e);
            vm = null;
        }
        if (vm == null) {
            logger.info(provider + " can not provide this resource.");
            TD.refuseCloudWorkerRequest(rr.getRequestedTaskCount(), rr.getRequested(), provider);
            synchronized (count) {
                count--;
            }
            return;
        }



        //WAITING FOR THE RESOURCES TO BE RUNNING
        String schedulerName = "";
        try {
            //wait 'till the vm has been created
            schedulerName = operations.waitCreation(vm, rr);
        } catch (ConnectorException e) {
            logger.info("Error waiting for a machine that should be provided by " + provider, e);
            try {
                operations.destroy(vm);
            } catch (ConnectorException ex) {
                logger.info("Can not poweroff the machine");
            }
            TD.refuseCloudWorkerRequest(rr.getRequestedTaskCount(), rr.getRequested(), provider);
            synchronized (count) {
                count--;
            }
            return;
        } catch (Exception e) {
            logger.info("Error waiting for a machine that should be provided by " + provider, e);
            try {
                operations.poweroff(vm, null);
            } catch (ConnectorException ex) {
                logger.info("Can not poweroff the machine");
            }
            TD.refuseCloudWorkerRequest(rr.getRequestedTaskCount(), rr.getRequested(), provider);
            synchronized (count) {
                count--;
            }
            return;
        }
        logger.info(" S'ha arribat a crear la màquina ");

        ResourceDescription r = rr.getRequested();
        logger.debug("Requested: ");
        logger.debug("Proc: " + r.getProcessorCPUCount() + " " + r.getProcessorArchitecture() + " cores @ " + r.getProcessorSpeed());
        logger.debug("OS: " + r.getOperatingSystemType());
        logger.debug("Mem: " + r.getMemoryVirtualSize() + "(" + r.getMemoryPhysicalSize() + ")");

        r = rr.getGranted();
        logger.debug("Granted: ");
        logger.debug("Proc: " + r.getProcessorCPUCount() + " " + r.getProcessorArchitecture() + " cores @ " + r.getProcessorSpeed());
        logger.debug("OS: " + r.getOperatingSystemType());
        logger.debug("Mem: " + r.getMemoryVirtualSize() + "(" + r.getMemoryPhysicalSize() + ")");

        logger.info("++++++++New resource granted " + schedulerName + ". Configuring ...");

        
        ProjectWorker pw;
        try {
            //Copy ssh keys
        	pw = new ProjectWorker(schedulerName, provider, cid.getUser(), rr.getGranted().getProcessorCPUCount(), cid.getiDir(), cid.getwDir());
            ProjectManager.addProjectWorker(pw);
            CloudManager.addCloudMachine(schedulerName, provider, rr.getGranted().getType());
            
        	operations.configureAccess(schedulerName, cid.getUser());
            
            operations.announceCreation(schedulerName, cid.getUser(), ProjectManager.getAllRegisteredMachines());
        } catch (ConnectorException e) {
            logger.info("Error announcing the machine " + schedulerName + " in " + provider, e);
            try {
                operations.poweroff(vm, schedulerName);
                CloudManager.terminate(schedulerName);
                ProjectManager.removeProjectWorker(schedulerName);
                operations.announceDestruction(schedulerName, ProjectManager.getAllRegisteredMachines());
            } catch (ConnectorException ex) {
            }
            TD.refuseCloudWorkerRequest(rr.getRequestedTaskCount(), rr.getRequested(), provider);
            synchronized (count) {
                count--;
            }
            return;
        }

        try {
            operations.prepareMachine(schedulerName, cid);
            logger.info(System.currentTimeMillis() + ": ++++++++New resource " + schedulerName + " ready to execute");

            //add the new machine to ResourceManager
            if (operations.getTerminate()) {
                logger.info("--------New resource " + schedulerName + " has been refused because integratedtoolkit has been stopped");
                operations.poweroff(schedulerName);
                CloudManager.terminate(schedulerName);
                ProjectManager.removeProjectWorker(schedulerName);
                operations.announceDestruction(name, ProjectManager.getAllRegisteredMachines());
                TD.refuseCloudWorkerRequest(rr.getRequestedTaskCount(), rr.getRequested(), provider);
            } else {
                TD.addCloudNode(schedulerName, rr, provider, rr.getGranted().getProcessorCPUCount(), !check && operations.getCheck());
            }
            synchronized (count) {
                count--;
            }
        } catch (Exception e) {
            e.printStackTrace();
            try {
                operations.poweroff(vm, schedulerName);
                operations.announceDestruction(schedulerName, ProjectManager.getAllRegisteredMachines());
            } catch (Exception e2) {
                logger.info("Can not poweroff the new resource", e);
            }
            TD.refuseCloudWorkerRequest(rr.getRequestedTaskCount(), rr.getRequested(), provider);
            synchronized (count) {
                count--;
            }
            logger.info("Exception creating a new Resource ", e);
        }
    }

    public static void setTaskDispatcher(TaskDispatcher td) {
        TD = td;
    }
}
