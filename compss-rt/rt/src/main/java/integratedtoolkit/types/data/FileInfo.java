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


package integratedtoolkit.types.data;

public class FileInfo extends DataInfo {
    // Original name and location of the file

    private String origName;
    private Location origLocation;

    public FileInfo(String origName, String origHost, String origPath) {
        super();
        this.origName = origName;
        this.origLocation = new Location(origHost, origPath);
    }

    public String getOriginalName() {
        return origName;
    }

    public Location getOriginalLocation() {
        return origLocation;
    }
}