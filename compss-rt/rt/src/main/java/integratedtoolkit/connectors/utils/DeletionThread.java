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

import org.apache.log4j.Logger;
import integratedtoolkit.log.Loggers;
import integratedtoolkit.util.CloudManager;
import integratedtoolkit.util.ProjectManager;

public class DeletionThread extends Thread {

    private Operations connector;
    private String name;
    private static final Logger logger = Logger.getLogger(Loggers.RESOURCES);
    private static Integer count = 0;

    public DeletionThread(Operations connector, String name) {
        synchronized (count) {
            count++;
        }
        this.connector = connector;
        this.name = name;
    }

    public static int getCount() {
        return count;
    }

    public void run() {
        ProjectManager.removeProjectWorker(name);
        CloudManager.notifyShutdown(name);
        try {
            logger.info("--------Shutting down cloud resource " + name);
            connector.poweroff(name);
            connector.announceDestruction(name, ProjectManager.getAllRegisteredMachines());
            logger.info(System.currentTimeMillis() + ": --------Cloud resource " + name + " has been shut down successfully");
        } catch (Exception e) {
            logger.info("Error while trying to shut down the virtual machine " + name);
        }
        synchronized (count) {
            count--;
        }
    }
}
