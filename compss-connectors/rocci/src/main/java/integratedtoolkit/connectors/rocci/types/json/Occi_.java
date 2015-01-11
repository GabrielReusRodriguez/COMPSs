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


package integratedtoolkit.connectors.rocci.types.json;

import javax.annotation.Generated;
import com.google.gson.annotations.Expose;

@Generated("org.jsonschema2pojo")
public class Occi_ {

    @Expose
    private Core_ core;
    @Expose
    private Networkinterface networkinterface;

    public Core_ getCore() {
        return core;
    }

    public void setCore(Core_ core) {
        this.core = core;
    }

    public Networkinterface getNetworkinterface() {
        return networkinterface;
    }

    public void setNetworkinterface(Networkinterface networkinterface) {
        this.networkinterface = networkinterface;
    }

}
