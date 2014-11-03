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

import java.io.Serializable;
import java.util.Map;
import integratedtoolkit.api.ITExecution.*;
import java.util.HashMap;

public abstract class Core implements Serializable {

    public static Map<String, Integer> signatureToId;
    public static int coreCount = 0;
    public static int nextId = 0;
    protected Integer coreId;
    protected String methodName;
    protected Parameter[] parameters;
    protected boolean priority;
    protected boolean hasTarget;
    protected boolean hasReturn;

    static {
        signatureToId = new HashMap<String, Integer>();
    }

    public Core(String methodName, boolean priority, boolean hasTarget, Parameter[] parameters) {
        this.methodName = methodName;
        this.priority = priority;
        this.hasTarget = hasTarget;
        this.parameters = parameters;
        if (parameters.length == 0) {
            this.hasReturn = false;
        } else {
            Parameter lastParam = parameters[parameters.length - 1];
            this.hasReturn = (lastParam.getDirection() == ParamDirection.OUT && lastParam.getType() == ParamType.OBJECT_T);
        }
    }

    public static Integer getCoreId(String signature) {
        Integer methodId = signatureToId.get(signature);
        if (methodId == null) {
            //coreCount++;
            methodId = nextId++;
            signatureToId.put(signature, methodId);
        }
        return methodId;
    }

    public Integer getId() {
        return coreId;
    }

    public String getName() {
        return methodName;
    }

    public Parameter[] getParameters() {
        return parameters;
    }

    public boolean hasPriority() {
        return priority;
    }

    public boolean hasTargetObject() {
        return hasTarget;
    }

    public boolean hasReturnValue() {
        return hasReturn;
    }

    public abstract String getSignature();

}
