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


package integratedtoolkit.types.request.td.debug;

import integratedtoolkit.types.request.td.TDRequest;

/**
 * The TDRequest class represents any interacction with the TaskDispatcher
 * component.
 */
public class TDDebugRequest extends TDRequest {

    /**
     * Contains the different types of request that the Task Dispatcher can
     * response.
     */
    public enum DebugRequestType {
        GET_LOCATIONS
    }
    /**
     * Type of the request instance.
     */
    private DebugRequestType debugRequestType;

    /**
     * Cosntructs a new TDRequest for that kind of notification
     *
     * @param requestType new request type name
     *
     */
    public TDDebugRequest(DebugRequestType requestType) {
        super(TDRequestType.DEBUG);
        this.debugRequestType = requestType;
    }

    public DebugRequestType getDebugRequestType() {
        return debugRequestType;
    }

}
