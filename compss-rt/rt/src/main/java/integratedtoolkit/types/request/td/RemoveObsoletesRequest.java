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


import java.util.LinkedList;

/**
 * The RemoveObsoletesRequest class represents the request to remove a set 
 * of obsolete files.
 */
public class RemoveObsoletesRequest extends TDRequest {

    /**
     * List of obsolete data versions
     */
    private LinkedList<String> obsoletes;

    /**
     * Constructs a new RemoveObsoletes Request
     *
     * @param obsoletes List of obsolete data versions
     */
    public RemoveObsoletesRequest(LinkedList<String> obsoletes) {
        super(TDRequestType.REMOVE_OBSOLETES);
        this.obsoletes = obsoletes;
    }

    /**
     * Returns a list of fileNames of the obsolete data version
     *
     * @return a list of fileNames of the obsolete data version
     */
    public LinkedList<String> getObsoletes() {
        return obsoletes;
    }

    /**
     * Sets a list of fileNames of the obsolete data version
     *
     * @param obsoletes list of fileNames of the obsolete data version
     */
    public void setObsoletes(LinkedList<String> obsoletes) {
        this.obsoletes = obsoletes;
    }
}
