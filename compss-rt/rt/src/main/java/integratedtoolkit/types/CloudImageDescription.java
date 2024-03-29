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




package integratedtoolkit.types;

import java.util.HashMap;
import java.util.LinkedList;
import org.w3c.dom.Node;


public class CloudImageDescription {

    private String name;
    private String iDir;
    private String wDir;
    private String user;
    private LinkedList<String[]> packages;
    private LinkedList<String> softwareApps;
    private Float diskSize;
    private Integer CPUCount;
    private Float memorySize;
    private String arch;

    private HashMap<String, String> sharedDisks;

    public CloudImageDescription(Node resourcesNode, Node projectNode) {
        name = projectNode.getAttributes().getNamedItem("name").getTextContent();
        diskSize = null;
        packages = new LinkedList();
        sharedDisks=new HashMap<String, String>();
        for (int i = 0; i < projectNode.getChildNodes().getLength(); i++) {
            Node child = projectNode.getChildNodes().item(i);
            if (child.getNodeName().compareTo("InstallDir") == 0) {
                iDir = child.getTextContent();
            } else if (child.getNodeName().compareTo("WorkingDir") == 0) {
                wDir = child.getTextContent();
            } else if (child.getNodeName().compareTo("User") == 0) {
                user = child.getTextContent();
            } else if (child.getNodeName().compareTo("Package") == 0) {
                String[] p = new String[2];
                for (int j = 0; j < child.getChildNodes().getLength(); j++) {
                    Node packageChild = child.getChildNodes().item(j);
                    if (packageChild.getNodeName().compareTo("Source") == 0) {
                        p[0] = packageChild.getTextContent();
                    } else if (packageChild.getNodeName().compareTo("Target") == 0) {
                        p[1] = packageChild.getTextContent();
                    }

                }
                packages.add(p);
            } else if (child.getNodeName().compareTo("DiskSize") == 0) {
                diskSize = Float.parseFloat(child.getTextContent());
           
	    }else if (child.getNodeName().compareTo("CPUs") == 0) {
                CPUCount = Integer.parseInt(child.getTextContent());
            
	    }else if (child.getNodeName().compareTo("Memory") == 0) {
                memorySize = Float.parseFloat(child.getTextContent());
            }
        }

        for (int i = 0; i < resourcesNode.getChildNodes().getLength(); i++) {
            Node child = resourcesNode.getChildNodes().item(i);
            if (child.getNodeName().compareTo("ApplicationSoftware") == 0) {
                for (int app = 0; app < child.getChildNodes().getLength(); app++) {
                    Node appNode = child.getChildNodes().item(app);
                    if (appNode.getNodeName().compareTo("Software") == 0) {
                        if (softwareApps == null) {
                            softwareApps = new LinkedList();
                        }
                        softwareApps.add(appNode.getTextContent());
                    }
                }
            } else if (child.getNodeName().compareTo("SharedDisks") == 0) {
                for (int diskIndex = 0; diskIndex < child.getChildNodes().getLength(); diskIndex++) {
                    Node sharedDisk = child.getChildNodes().item(diskIndex);
                    if (sharedDisk.getNodeName().compareTo("Disk") == 0) {
                        String diskName = sharedDisk.getAttributes().getNamedItem("name").getTextContent();
                        String mountPoint = "";
                        for (int j = 0; j < sharedDisk.getChildNodes().getLength(); j++) {
                            if (sharedDisk.getChildNodes().item(j).getNodeName().compareTo("MountPoint") == 0) {
                                mountPoint = sharedDisk.getChildNodes().item(j).getTextContent();
                            }
                        }
                        sharedDisks.put(diskName, mountPoint);
                    }
                }
            }
        }
    }

    public String getName() {
        return name;
    }

    public String getiDir() {
        return iDir;
    }

    public String getwDir() {
        return wDir;
    }

    public String getUser() {
        return user;
    }

    public Float getDiskSize() {
        return diskSize;
    }

    public Integer getCPUCount() {
        return CPUCount;
    }

    public Float getMemorySize() {
        return memorySize;
    }

    public String getArch() {
		return arch;
	}

	public LinkedList<String[]> getPackages() {
        return packages;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setiDir(String iDir) {
        this.iDir = iDir;
    }

    public void setwDir(String wDir) {
        this.wDir = wDir;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public void setDiskSize(float GB) {
        this.diskSize = GB;
    }

    public void setCPUCount(int CPUs) {
        this.CPUCount = CPUs;
    }

    public void setMemorySize(float GB) {
        this.memorySize = GB;
    }

    public void addSharedDisks(HashMap<String, String> sharedDisks) {
        this.sharedDisks = sharedDisks;
    }

    public HashMap<String, String> getSharedDisks() {
        return this.sharedDisks;
    }
}
