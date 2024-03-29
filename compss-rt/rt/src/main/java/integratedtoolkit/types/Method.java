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

public class Method extends Core {

    private String declaringClass;

    public Method(String methodClass, String methodName, boolean priority, boolean hasTarget, Parameter[] parameters) {
        super(methodName, priority, hasTarget, parameters);
        this.declaringClass = methodClass;
        String signature = getSignature();
        this.coreId = getCoreId(signature);
    }

    public String getDeclaringClass() {
        return declaringClass;
    }

    public String toString() {
        StringBuilder buffer = new StringBuilder();

        buffer.append("[Core id: ").append(getId()).append("]");
        buffer.append(", [Method class: ").append(getDeclaringClass()).append("]");
        buffer.append(", [Method name: ").append(getName()).append("]");
        buffer.append(", [Parameters:");

        for (Parameter p : getParameters()) {
            buffer.append(" [").append(p.toString()).append("]");
        }

        buffer.append("]");

        return buffer.toString();
    }

    public String getSignature() {
        StringBuilder buffer = new StringBuilder();

        buffer.append(methodName).append("(");
        int numPars = parameters.length;
        if (hasTarget) {
            numPars--;
        }
        if (hasReturn) {
            numPars--;
        }
        if (numPars > 0) {
            buffer.append(parameters[0].getType());
            for (int i = 1; i < numPars; i++) {
                buffer.append(",").append(parameters[i].getType());
            }
        }
        buffer.append(")").append(declaringClass);
        return buffer.toString();
    }

}
