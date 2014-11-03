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



package integratedtoolkit.types.request.td;

import integratedtoolkit.types.ResourceDescription;

/**
 * The RefuseCloudWorkerRequest class represents the notification of the 
 * impossibility to create a requested resource
 */
public class RefuseCloudWorkerRequest extends TDRequest {

    /** Amount of slots expected to receive */
    private int requestedTaskCount;
    /** Features of the requested resource*/
    private ResourceDescription requestedDescription;
    /**/
    private String provider;

    /**
     * Contructs a new RefuseCloudWorkerRequest
     * @param requestedTaskCount Amount of slots expected to requested
     * @param requestedDescription Features of the expected machine
     */
    public RefuseCloudWorkerRequest(int requestedTaskCount, ResourceDescription requestedDescription, String provider) {
        super(TDRequestType.REFUSE_CLOUD);
        this.requestedTaskCount = requestedTaskCount;
        this.requestedDescription = requestedDescription;
        this.provider = provider;
    }

    /**
     * Returns the amount of slots expected to requested
     * @return the amount of slots expected to requested
     */
    public int getRequestedTaskCount() {
        return requestedTaskCount;
    }

    /**
     * Sets the amount of slots expected to receive
     * @param requestedTaskCount Expected number of slots
     */
    public void setRequestedTaskCount(int requestedTaskCount) {
        this.requestedTaskCount = requestedTaskCount;
    }

    /**
     * Describes the expected resource
     * @return features of the expected resource
     */
    public ResourceDescription getRequestedConstraints() {
        return requestedDescription;
    }

    /**
     * Sets the expected resource features
     * @param requestedDescription description of the expected machine
     */
    public void setRequestedConstraints(ResourceDescription requestedDescription) {
        this.requestedDescription = requestedDescription;
    }

    /**
     * Returns the provider associated to the refused request
     * @return name of the provider associated to the redused request
     */
    public String getProvider() {
        return this.provider;
    }

    /**
     * Sets the provider associated to the refused request
     * @param provider  name of the provider
     */
    public void setProvider(String provider) {
        this.provider = provider;
    }
}
