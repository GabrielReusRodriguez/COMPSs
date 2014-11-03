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


package integratedtoolkit.util;

import integratedtoolkit.ITConstants;
import integratedtoolkit.log.Loggers;
import integratedtoolkit.types.Core;
import integratedtoolkit.types.annotations.Parameter;
import integratedtoolkit.types.annotations.Service;
import integratedtoolkit.types.ResourceDescription;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedList;

import org.apache.log4j.Logger;

/**
 * The ConstraintManager is an utility to manage the relations between resource
 * features and the constraints imposed by each core.
 */
public class ConstraintManager {

    public static final String NO_CONSTR = "/ResourceList/Resource";
    // Constants definition
    private static final String CONSTR_LOAD_ERR = "Error loading constraints";
    private static final String LANG_UNSUPPORTED_ERR = "Error loading constraints: Language not supported";

    private static final Logger logger = Logger.getLogger(Loggers.TS_COMP);

    private static Constraints[] constraintsAnnot;
    /**
     * Xpath constraints per core
     */
    private static String[] constraintsXPath;
    /**
     * Description of the resource able to run the core
     */
    private static ResourceDescription[] resourceConstraints;

    public static void load() {
        String l = System.getProperty(ITConstants.IT_LANG);
        ITConstants.Lang lang = ITConstants.Lang.JAVA;
        if (l != null) {
            if (l.equalsIgnoreCase("c")) {
                lang = ITConstants.Lang.C;
            } else if (l.equalsIgnoreCase("python")) {
                lang = ITConstants.Lang.PYTHON;
            }
        }
        switch (lang) {
            case JAVA:
                String appName = System.getProperty(ITConstants.IT_APP_NAME);
                try {
                    loadJava(Class.forName(appName + "Itf"));
                } catch (ClassNotFoundException ex) {
                    throw new UndefinedConstraintsSourceException(appName + "Itf class cannot be found.");
                }

                break;
            case C:
                String constraintsFile = System.getProperty(ITConstants.IT_CONSTR_FILE);
                loadC(constraintsFile);
                break;
            case PYTHON:
                loadPython();
                break;
            default:
                throw new LangNotDefinedException();
        }
    }

    /**
     * Loads the annotated class and initilizes the data structures that contain
     * the constraints. For each method found in the annotated interface creates
     * its signature and adds the constraints to the structures.
     *
     * @param annotatedItf package and name of the Annotated Interface class
     * @return 
     */
    public static LinkedList<Integer> loadJava(Class<?> annotItfClass) {
    	LinkedList<Integer> newMethods = new LinkedList<Integer>();
    	int coreCount = annotItfClass.getDeclaredMethods().length;
        logger.debug("Detected methods " +coreCount);
    	if (Core.coreCount == 0){
           	//Core.coreCount = coreCount;
        	constraintsAnnot = new Constraints[coreCount];
        	constraintsXPath = new String[coreCount];
        	resourceConstraints = new ResourceDescription[coreCount];
        }else{
        	updateArrays(Core.coreCount+coreCount);
        }
        
        for (Method m : annotItfClass.getDeclaredMethods()) {
            //Computes the method's signature
        	logger.debug("Evaluating method " +m.getName());
        	StringBuilder buffer = new StringBuilder();
            buffer.append(m.getName()).append("(");
            int numPars = m.getParameterAnnotations().length;
            String type;
            if (numPars > 0) {
                type = inferType(m.getParameterTypes()[0], ((Parameter) m.getParameterAnnotations()[0][0]).type());
                buffer.append(type);
                for (int i = 1; i < numPars; i++) {
                    type = inferType(m.getParameterTypes()[i], ((Parameter) m.getParameterAnnotations()[i][0]).type());
                    buffer.append(",").append(type);
                }
            }
            buffer.append(")");
            if (m.isAnnotationPresent(integratedtoolkit.types.annotations.Method.class)) {
                Annotation methodAnnot = m.getAnnotation(integratedtoolkit.types.annotations.Method.class);
                buffer.append(((integratedtoolkit.types.annotations.Method) methodAnnot).declaringClass());
                String signature = buffer.toString();
                Integer method_id = Core.getCoreId(signature);
                logger.debug(" Method Id: "+ method_id+"; coreCount: "+Core.coreCount);
                if (method_id==Core.coreCount){
                	Core.coreCount++;
                	newMethods.add(method_id);
                }
                loadMethodConstraints(method_id, new Constraints(m.getAnnotation(integratedtoolkit.types.annotations.Constraints.class)));
            } else { // Service
                Service serviceAnnot = m.getAnnotation(Service.class);
                buffer.append(serviceAnnot.namespace()).append(',').append(serviceAnnot.name()).append(',').append(serviceAnnot.port());
                String signature = buffer.toString();
                Integer method_id = Core.getCoreId(signature);
                logger.debug(" Method Id: "+ method_id+"; coreCount: "+Core.coreCount);
                if (method_id==Core.coreCount){
                	Core.coreCount++;
                	newMethods.add(method_id);
                }
                loadServiceConstraints(method_id, serviceAnnot);
            }
            
        }
        return newMethods;
    }

    private static void updateArrays(int newCoreCount) {
    	Constraints[] newConstraintsAnnot = new Constraints[newCoreCount];
    	System.arraycopy(constraintsAnnot, 0, newConstraintsAnnot, 0, constraintsAnnot.length);
        constraintsAnnot = newConstraintsAnnot;
        
        String[] newConstraintsXPath = new String[newCoreCount];
        System.arraycopy(constraintsXPath, 0, newConstraintsXPath, 0, constraintsXPath.length);
        constraintsXPath = newConstraintsXPath;
		
        ResourceDescription[] newResourceConstraints = new ResourceDescription[newCoreCount];
        System.arraycopy(resourceConstraints, 0, newResourceConstraints, 0, resourceConstraints.length);
        resourceConstraints = newResourceConstraints;
	}

	// C constructor
    private static void loadC(String constraintsFile) {

        try {
            BufferedReader br = new BufferedReader(new FileReader(constraintsFile));

            String line;
            int n = 0;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("//"))
                	continue;
                StringBuilder buffer = new StringBuilder();
                if (line.matches(".*[(].*[)].*;")) {
                    line = line.replaceAll("[(|)|,|;]", " ");
                    String[] splits = line.split("\\s+");
                    String returnType = splits[0];
                    String methodName = splits[1];
                    //String methodName = String.valueOf(n);                    
                    //Computes the method's signature                    
                    buffer.append(methodName).append("(");
                    for (int i = 2; i < splits.length; i++) {
                        String paramDirection = splits[i++];                        
                        String paramType = splits[i++];
                        String type = "OBJECT_T";
                        if (paramDirection.toUpperCase().compareTo("INOUT") == 0 ) {
                        	type = "FILE_T";
                        } else if (paramDirection.toUpperCase().compareTo("OUT") == 0 ) {
                        	type = "FILE_T";                        	
                        } else if (paramType.toUpperCase().compareTo("FILE") == 0) {
                            type = "FILE_T";
                        } else if (paramType.compareTo("boolean") == 0) {
                            type = "BOOLEAN_T";
                        } else if (paramType.compareTo("char") == 0) {
                            type = "CHAR_T";
                        } else if (paramType.compareTo("int") == 0) {
                            type = "INT_T";
                        } else if (paramType.compareTo("float") == 0) {
                            type = "FLOAT_T";
                        } else if (paramType.compareTo("double") == 0) {
                            type = "DOUBLE_T";
                        } else if (paramType.compareTo("byte") == 0) {
                            type = "BYTE_T";
                        } else if (paramType.compareTo("short") == 0) {
                            type = "SHORT_T";
                        } else if (paramType.compareTo("long") == 0) {
                            type = "LONG_T";
                        } else if (paramType.compareTo("string") == 0) {
                            type = "STRING_T";
                        }
                        buffer.append(type).append(",");
                        String paramName = splits[i];
                    }
                    buffer.deleteCharAt(buffer.lastIndexOf(","));
                    buffer.append(")");
                    String declaringClass = "NULL";
                    buffer.append(declaringClass);

                    String signature = buffer.toString();
                    //Adds a new Signature-Id if not exists in the TreeMap
                    Integer methodId = Core.getCoreId(signature);
                    n++;
                }
            }
            constraintsAnnot = new Constraints[n];
            constraintsXPath = new String[n];
            resourceConstraints = new ResourceDescription[n];
            for (int i = 0; i < n; i++) {
                constraintsAnnot[i] = new Constraints();
                constraintsXPath[i] = NO_CONSTR;
                resourceConstraints[i] = new ResourceDescription();
            }
            Core.coreCount = n;

        } catch (Exception e) {
            logger.fatal(CONSTR_LOAD_ERR, e);
        }

    }

    // Python constructor
    private static void loadPython() {
        String countProp = System.getProperty(ITConstants.IT_CORE_COUNT);
        Integer count;
        if (countProp == null) {
            count = 50;
            logger.debug("Warning: using " + count + " as default for number of task types");
        } else {
            count = Integer.parseInt(countProp);
            logger.debug("Core count is " + count);
        }
        constraintsAnnot = new Constraints[count];
        constraintsXPath = new String[count];
        resourceConstraints = new ResourceDescription[count];
        Core.coreCount = count;
        for (int i = 0; i < count; i++) {
            constraintsAnnot[i] = new Constraints();
            constraintsXPath[i] = NO_CONSTR;
            resourceConstraints[i] = new ResourceDescription();
        }
    }

    /**
     * Gets the already stored constraints for the core in XPath format
     *
     * @param coreId Identifier of the core
     * @return The already stored constraints for that core in XPath format
     */
    public static String getConstraints(int coreId) {
        if (constraintsXPath[coreId] == null) {
            return NO_CONSTR;
        } else {
            return constraintsXPath[coreId];
        }
    }

    /**
     * Infers the type of a parameter. If the parameter is annotated as a FILE
     * or a STRING, the type is taken from the annotation. If the annotation is
     * UNSPECIFIED, the type is taken from the formal type.
     *
     * @param formalType Formal type of the parameter
     * @param annotType Annotation type of the parameter
     * @return A String representing the type of the parameter
     */
    private static String inferType(Class<?> formalType, Parameter.Type annotType) {
        if (annotType.equals(Parameter.Type.UNSPECIFIED)) {
            if (formalType.isPrimitive()) {
                if (formalType.equals(boolean.class)) {
                    return "BOOLEAN_T";
                } else if (formalType.equals(char.class)) {
                    return "CHAR_T";
                } else if (formalType.equals(byte.class)) {
                    return "BYTE_T";
                } else if (formalType.equals(short.class)) {
                    return "SHORT_T";
                } else if (formalType.equals(int.class)) {
                    return "INT_T";
                } else if (formalType.equals(long.class)) {
                    return "LONG_T";
                } else if (formalType.equals(float.class)) {
                    return "FLOAT_T";
                } else //if (formalType.equals(double.class))
                {
                    return "DOUBLE_T";
                }
            } /*else if (formalType.isArray()) { // If necessary
             }*/ else { // Object
                return "OBJECT_T";
            }
        } else {
            return annotType + "_T";
        }
    }

    /**
     * Loads the Constraints in case that core is a service. Only in Xpath
     * format since there are no resource where its tasks can run
     *
     * @param methodId identifier for that core
     * @param service Servive annotation describing the core
     */
    private static void loadServiceConstraints(int methodId, Service service) {
        StringBuilder constrXPath = new StringBuilder().append("/ResourceList/Service[Name[text()='");
        constrXPath.append(service.name());
        constrXPath.append("'] and Namespace[text()='");
        constrXPath.append(service.namespace());
        constrXPath.append("'] and Port[text()='");
        constrXPath.append(service.port());
        constrXPath.append("']]");

        String serviceConstraints = constrXPath.toString();
        constraintsXPath[methodId] = serviceConstraints;
    }

    /**
     * Loads the Constraints in case that core is a service in XPath format and
     * describing the features of the resources able to run its tasks
     *
     * @param coreId identifier for that core
     * @param service Method annotation describing the core
     */
    private static void loadMethodConstraints(int coreId, Constraints cts) {
        constraintsAnnot[coreId] = cts;
        ResourceDescription rm = new ResourceDescription();
        if (cts == null) {
            constraintsXPath[coreId] = NO_CONSTR;
        } else {
            List<String> requirements = buildXPathConstraints(cts);
            //Adds the Constraints to constraints

            StringBuilder constrXPath = new StringBuilder();
            for (Iterator<String> i = requirements.iterator(); i.hasNext();) {
                constrXPath.append(i.next());
                if (i.hasNext()) {
                    constrXPath.append(" and ");
                }
            }
            if (constrXPath.length() > 0) {
                constrXPath.insert(0, "[");
                constrXPath.append("]");
            }

            constrXPath.insert(0, NO_CONSTR);

            String methodConstr = constrXPath.toString();
            constraintsXPath[coreId] = methodConstr;

            //specifies the Resources needed to execute the task
            rm.addHostQueue(cts.hostQueue());
            rm.setProcessorCPUCount(Integer.parseInt("" + cts.processorCPUCount()));
            rm.setProcessorSpeed(Float.parseFloat("" + cts.processorSpeed()));
            rm.setProcessorArchitecture(cts.processorArchitecture());
            rm.setOperatingSystemType(cts.operatingSystemType());
            rm.setStorageElemSize(Float.parseFloat("" + cts.storageElemSize()));
            rm.setStorageElemAccessTime(Float.parseFloat("" + cts.storageElemAccessTime()));
            rm.setStorageElemSTR(Float.parseFloat("" + cts.storageElemSTR()));
            rm.setMemoryPhysicalSize(Float.parseFloat("" + cts.memoryPhysicalSize()));
            rm.setMemoryVirtualSize(Float.parseFloat("" + cts.memoryVirtualSize()));
            rm.setMemoryAccessTime(Float.parseFloat("" + cts.memoryAccessTime()));
            rm.setMemorySTR(cts.memorySTR());
            String software = cts.appSoftware();
            if (software.compareTo("[unassigned]") != 0) {
                int last = 0;
                while (software.length() > 0) {
                    last = software.lastIndexOf(",");
                    rm.addAppSoftware(software.substring(last + 1, software.length()));
                    if (last == -1) {
                        software = "";
                    } else {
                        software = software.substring(0, last);
                    }
                }
            }
        }
        resourceConstraints[coreId] = rm;
    }

    /**
     * Returns the description of the resources that are able to run that core
     *
     * @param coreId identifier of the core
     * @return the description of the resources that are able to run that core
     */
    public static ResourceDescription getResourceConstraints(int coreId) {
        if (resourceConstraints == null) {
            return ResourceDescription.EMPTY;
        } else {
            return resourceConstraints[coreId];
        }
    }

    /**
     * Looks for all the cores from in the annotated Interface which constraint
     * are fullfilled by the resource description passed as a parameter
     *
     * @param rd description of the resource
     * @return the list of cores which constraints are fulfilled by rd
     */
    public static List<Integer> findExecutableCores(ResourceDescription rd) {
        LinkedList<Integer> executableList = new LinkedList<Integer>();
        for (int method_id = 0; method_id < Core.coreCount; method_id++) {
            Constraints mc = constraintsAnnot[method_id];
            boolean executable = true;
            if (mc != null) {
                //Check processor
                if (executable && mc.processorCPUCount() != 0) {
                    executable = (mc.processorCPUCount() <= rd.getProcessorCPUCount());
                }
                if (executable && mc.processorSpeed() != 0.0f) {
                    executable = (mc.processorSpeed() <= rd.getProcessorSpeed());
                }
                if (executable && mc.processorArchitecture().compareTo("[unassigned]") != 0) {
                    executable = (rd.getProcessorArchitecture().compareTo(mc.processorArchitecture()) == 0);
                }

                //Check Memory
                if (executable && mc.memoryPhysicalSize() != 0.0f) {
                    executable = ((int) ((Float) mc.memoryPhysicalSize() * (Float) 1024f) <= (int) ((Float) rd.getMemoryPhysicalSize() * (Float) 1024f));
                }
                if (executable && mc.memoryVirtualSize() != 0.0f) {
                    executable = ((int) ((Float) mc.memoryVirtualSize() * (Float) 1024f) <= (int) ((Float) rd.getMemoryVirtualSize() * (Float) 1024f));
                }
                if (executable && mc.memoryAccessTime() != 0.0f) {
                    executable = (mc.memoryAccessTime() >= rd.getMemoryAccessTime());
                }
                if (executable && mc.memorySTR() != 0.0f) {
                    executable = (mc.memorySTR() <= rd.getMemorySTR());
                }

                //Check disk
                if (executable && mc.storageElemSize() != 0.0f) {
                    executable = ((int) ((Float) mc.storageElemSize() * (Float) 1024f) <= (int) ((Float) rd.getStorageElemSize() * (Float) 1024f));
                }
                if (executable && mc.storageElemAccessTime() != 0.0f) {
                    executable = (mc.storageElemAccessTime() >= rd.getStorageElemAccessTime());
                }
                if (executable && mc.storageElemSTR() != 0.0f) {
                    executable = (mc.storageElemSTR() <= rd.getStorageElemSTR());
                }

                //Check OS
                if (executable && mc.operatingSystemType().compareTo("[unassigned]") != 0) {
                    executable = (rd.getOperatingSystemType().compareTo(mc.operatingSystemType()) == 0);
                }
            }
            if (executable) {
                executableList.add(method_id);
            }
        }
        return executableList;
    }

    /**
     * Creates the Xpath constraints taking into account the annaotation of the
     * core
     *
     * @param constrAnnot Constraints annotation to convert to XPath
     * @return the constraints in XPath format
     */
    private static List<String> buildXPathConstraints(Annotation constrAnnot) {
        ArrayList<String> requirements = new ArrayList<String>();

        String procArch = ((Constraints) constrAnnot).processorArchitecture();
        float cpuSpeed = ((Constraints) constrAnnot).processorSpeed();
        int cpuCount = ((Constraints) constrAnnot).processorCPUCount();
        String osType = ((Constraints) constrAnnot).operatingSystemType();
        float physicalMemSize = ((Constraints) constrAnnot).memoryPhysicalSize();
        float virtualMemSize = ((Constraints) constrAnnot).memoryVirtualSize();
        float memoryAT = ((Constraints) constrAnnot).memoryAccessTime();
        float memorySTR = ((Constraints) constrAnnot).memorySTR();
        float diskSize = ((Constraints) constrAnnot).storageElemSize();
        float diskAT = ((Constraints) constrAnnot).storageElemAccessTime();
        float diskSTR = ((Constraints) constrAnnot).storageElemSTR();
        //String queue	   		= ((Constraints)constrAnnot).hostQueue();
        String appSoftware = ((Constraints) constrAnnot).appSoftware();

        // Translation of constraints : Java Annotations -> XPath expression
        if (!procArch.equals("[unassigned]")) {
            requirements.add("Capabilities/Processor/Architecture[text()='" + procArch + "']");
        }
        if (cpuSpeed > 0) {
            requirements.add("Capabilities/Processor/Speed[text()>=" + cpuSpeed + "]");
        }
        if (cpuCount > 0) {
            requirements.add("Capabilities/Processor/CPUCount[text()>=" + cpuCount + "]");
        }
        if (!osType.equals("[unassigned]")) {
            requirements.add("Capabilities/OS/OSType[text()='" + osType + "']");
        }
        if (physicalMemSize > 0) {
            requirements.add("Capabilities/Memory/PhysicalSize[text()>=" + physicalMemSize + "]");
        }
        if (virtualMemSize > 0) {
            requirements.add("Capabilities/Memory/VirtualSize[text()>=" + virtualMemSize + "]");
        }
        if (memoryAT > 0) {
            requirements.add("Capabilities/Memory/AccessTime[text()<=" + memoryAT + "]");
        }
        if (memorySTR > 0) {
            requirements.add("Capabilities/Memory/STR[text()>=" + memorySTR + "]");
        }
        if (diskSize > 0) {
            requirements.add("Capabilities/StorageElement/Size[text()>=" + diskSize + "]");
        }
        if (diskAT > 0) {
            requirements.add("Capabilities/StorageElement/AccessTime[text()<=" + diskAT + "]");
        }
        if (diskSTR > 0) {
            requirements.add("Capabilities/StorageElement/STR[text()>=" + diskSTR + "]");
        }
        if (!appSoftware.equals("[unassigned]")) {
            String[] software = appSoftware.split(",");
            for (String s : software) {
                requirements.add("Capabilities/ApplicationSoftware/Software[text()='" + s + "']");
            }
        }

        return requirements;
    }

    public static class LangNotDefinedException extends RuntimeException {

        public LangNotDefinedException() {
            super(LANG_UNSUPPORTED_ERR);
        }
    }

    public static class UndefinedConstraintsSourceException extends RuntimeException {

        public UndefinedConstraintsSourceException(String message) {
            super(message);
        }
    }

    private static class Constraints implements integratedtoolkit.types.annotations.Constraints {

        String processorArchitecture = "[unassigned]";
        int processorCPUCount = 0;
        float processorSpeed = 0;
        float memoryPhysicalSize = 0;       // in GB
        float memoryVirtualSize = 0;        // in GB
        float memoryAccessTime = 0;         // in ns
        float memorySTR = 0;                // in GB/s
        float storageElemSize = 0;          // in GB
        float storageElemAccessTime = 0;    // in ms
        float storageElemSTR = 0;           // in MB/s
        String operatingSystemType = "[unassigned]";
        String hostQueue = "[unassigned]";
        String appSoftware = "[unassigned]";

        private Constraints() {
        }

        private Constraints(integratedtoolkit.types.annotations.Constraints m) {
            if (m != null) {
                this.processorArchitecture = m.processorArchitecture();
                this.processorSpeed = m.processorSpeed();
                this.processorArchitecture = m.processorArchitecture();
                this.memoryPhysicalSize = m.memoryPhysicalSize();
                this.memoryVirtualSize = m.memoryVirtualSize();
                this.memoryAccessTime = m.memoryAccessTime();
                this.memorySTR = m.memorySTR();
                this.storageElemSize = m.storageElemSize();
                this.storageElemAccessTime = m.storageElemAccessTime();
                this.storageElemSTR = m.storageElemSTR();
                this.operatingSystemType = m.operatingSystemType();
                this.hostQueue = m.hostQueue();
                this.appSoftware = m.appSoftware();
            }
        }

        public String processorArchitecture() {
            return this.processorArchitecture;
        }

        public int processorCPUCount() {
            return this.processorCPUCount;
        }

        public float processorSpeed() {
            return this.processorSpeed;
        }

        public float memoryPhysicalSize() {
            return this.memoryPhysicalSize;
        }

        public float memoryVirtualSize() {
            return this.memoryVirtualSize;
        }

        public float memoryAccessTime() {
            return this.memoryAccessTime;
        }

        public float memorySTR() {
            return this.memorySTR;
        }

        public float storageElemSize() {
            return this.storageElemSize;
        }

        public float storageElemAccessTime() {
            return this.storageElemAccessTime;
        }

        public float storageElemSTR() {
            return this.storageElemSTR;
        }

        public String operatingSystemType() {
            return this.operatingSystemType;
        }

        public String hostQueue() {
            return this.hostQueue;
        }

        public String appSoftware() {
            return this.appSoftware;
        }

        public Class<? extends Annotation> annotationType() {
            return integratedtoolkit.types.annotations.Constraints.class;
        }
    }
}
