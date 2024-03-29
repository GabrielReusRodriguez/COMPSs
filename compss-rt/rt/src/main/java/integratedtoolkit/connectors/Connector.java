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



package integratedtoolkit.connectors;

import org.apache.log4j.Logger;

import integratedtoolkit.log.Loggers;
import integratedtoolkit.types.ResourceDescription;
import integratedtoolkit.types.ResourceRequest;

public interface Connector {

	public final Logger logger = Logger.getLogger(Loggers.CONNECTORS);

    public boolean turnON(String name, ResourceRequest rR);

    public void terminate(String workerName);
    
    public void terminateALL()
            throws ConnectorException;
    
    

    public void stopReached();

    public Long getNextCreationTime() throws ConnectorException;

    public void terminate(ResourceDescription rd);

    
}
