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


package integratedtoolkit.loader;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Random;

import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;
import javassist.expr.MethodCall;

import integratedtoolkit.types.annotations.Service;

public class LoaderUtils {

    // Return the called method if it is in the remote list
    public static Method checkRemote(CtMethod method, Method[] remoteMethods)
            throws NotFoundException {
        for (Method remote : remoteMethods) {
            if (remote.isAnnotationPresent(integratedtoolkit.types.annotations.Method.class)) {
                if (isSelectedMethod(method, remote)) {
                    return remote;
                }
            } else { // Service
                if (isSelectedService(method, remote)) {
                    return remote;
                }
            }
        }

        // The method is not in the remote list
        return null;
    }

    private static boolean isSelectedMethod(CtMethod method, Method remote) throws NotFoundException {
        integratedtoolkit.types.annotations.Method methodAnnot = remote.getAnnotation(integratedtoolkit.types.annotations.Method.class);

        // Check if methods have the same name
        String nameRemote = methodAnnot.name();
        if (nameRemote.equals("[unassigned]")) {
            nameRemote = remote.getName();
        }

        if (!nameRemote.equals(method.getName())) {
            return false;
        }

        // Check if methods belong to the same class
        if (!methodAnnot.declaringClass().equals(method.getDeclaringClass().getName())) {
            return false;
        }

        // Check that methods have the same number of parameters
        CtClass[] paramClassCurrent = method.getParameterTypes();
        Class<?>[] paramClassRemote = remote.getParameterTypes();
        if (paramClassCurrent.length != paramClassRemote.length) {
            return false;
        }

        // Check that parameter types match
        for (int i = 0; i < paramClassCurrent.length; i++) {
            if (!paramClassCurrent[i].getName().equals(paramClassRemote[i].getCanonicalName())) {
                return false;
            }
        }

        // Methods match!
        return true;
    }

    private static boolean isSelectedService(CtMethod method, Method remote) throws NotFoundException {
        Service serviceAnnot = remote.getAnnotation(Service.class);

        // Check if methods have the same name
        String nameRemote = serviceAnnot.operation();
        if (nameRemote.equals("[unassigned]")) {
            nameRemote = remote.getName();
        }

        if (!nameRemote.equals(method.getName())) {
            return false;
        }

        // Check that methods have the same number of parameters
        CtClass[] paramClassCurrent = method.getParameterTypes();
        Class<?>[] paramClassRemote = remote.getParameterTypes();
        if (paramClassCurrent.length != paramClassRemote.length) {
            return false;
        }

        // Check that parameter types match
        for (int i = 0; i < paramClassCurrent.length; i++) {
            if (!paramClassCurrent[i].getName()
                    .equals(paramClassRemote[i].getCanonicalName())) {
                return false;
            }
        }

        // Check that return types match
        //if (!method.getReturnType().getName().equals(remote.getReturnType().getName()))
        //	return false;
        // Check if the package of the class which implements the called method matches the pattern namespace.service.port of the interface method
        String packName = method.getDeclaringClass().getPackageName();
        String nsp = combineServiceMetadata(serviceAnnot);
        if (!packName.equals(nsp)) {
            return false;
        }

        // Methods match!
        return true;
    }

    private static String combineServiceMetadata(Service annot) {
        String namespace = annot.namespace(),
                service = annot.name()/*.toLowerCase()*/,
                port = annot.port()/*.toLowerCase()*/;

        int startIndex = namespace.indexOf("//www.");
        if (startIndex < 0) {
            startIndex = namespace.indexOf("http://");
            if (startIndex >= 0) {
                startIndex += "http://".length();
            } else {
                startIndex = 0;
            }
        } else {
            startIndex += "//www.".length();
        }

        namespace = namespace//.substring(0, namespace.indexOf(".xsd")) 	  // remove .xsd at the end
                .substring(startIndex) // remove http://www.
                .replace('/', '.') // replace / by .
                .replace('-', '.') // replace - by .
                .replace(':', '.'); 						  // replace : by .

        return "dummy." + namespace + '.' + service + '.' + port;
    }

    // Check whether the method call is a close of a stream
    public static boolean isStreamClose(MethodCall mc) {
        if (mc.getMethodName().equals("close")) {
            String fullName = mc.getClassName();
            if (fullName.startsWith("java.io.")) {
                String className = fullName.substring(8);
                if (className.equals("FileInputStream")//58,700
                        || className.equals("FileOutputStream")//57,700
                        || className.equals("InputStreamReader")//61,200
                        || className.equals("BufferedReader")//36,400
                        || className.equals("FileWriter")//33,900
                        || className.equals("PrintWriter")//35,200
                        || className.equals("FileReader")//16,800
                        || className.equals("OutputStreamWriter")//15,700
                        || className.equals("BufferedInputStream")//15,100
                        || className.equals("BufferedOutputStream")//10,500
                        || className.equals("BufferedWriter")//11,800
                        || className.equals("PrintStream")//6,000
                        || className.equals("RandomAccessFile")//5,000
                        || className.equals("DataInputStream")//7,000
                        || className.equals("DataOutputStream")) {//7,000
                    return true;
                }
            }
        }
        return false;
    }

    // Return a random numeric string
    public static String randomName(int length, String prefix) {
        if (length < 1) {
            return prefix;
        }

        Random r = new Random();
        StringBuilder buffer = new StringBuilder();
        int gap = ('9' + 1) - '0';

        for (int i = 0; i < length; i++) {
            char c = (char) (r.nextInt(gap) + '0');
            buffer.append(c);
        }

        return prefix + buffer.toString();
    }

    // Check if the method is the main method
    public static boolean isMainMethod(CtMethod m) throws NotFoundException {
        return (m.getName().equals("main")
                && m.getParameterTypes().length == 1
                && m.getParameterTypes()[0].getName().equals(String[].class.getCanonicalName()));
    }

    public static boolean isOrchestration(CtMethod m) {
        return m.hasAnnotation(integratedtoolkit.types.annotations.Orchestration.class);
    }

    public static boolean contains(CtMethod[] methods, CtMethod method) {
        for (CtMethod m : methods) {
            if (m.equals(method)) {
                return true;
            }
        }
        return false;
    }

    // Add WithUR to the method name parameter of the executeTask call
    public static StringBuilder replaceMethodName(StringBuilder executeTask, String methodName) {
        String patternStr = ",\"" + methodName + "\",";
        int start = executeTask.toString().indexOf(patternStr);
        int end = start + patternStr.length();
        return executeTask.replace(start, end, ",\"" + methodName + "WithUR\",");
    }

    // Add SLA params to the executeTask call
    public static StringBuilder modifyString(StringBuilder executeTask,
            int numParams,
            String appNameParam,
            String slaIdParam,
            String urNameParam,
            String primaryHostParam,
            String transferIdParam) {

        // Number of new params we add
        int newParams = 5;

        // String of new params
        StringBuilder params = new StringBuilder(appNameParam);
        params.append(",");
        params.append(slaIdParam);
        params.append(",");
        params.append(urNameParam);
        params.append(",");
        params.append(primaryHostParam);
        params.append(",");
        params.append(transferIdParam);
        params.append("});");

        String patternStr;

        if (numParams == 0) {
            patternStr = 0 + ",null);";
        } else {
            patternStr = "," + numParams + ",";
        }

        int start = executeTask.toString().indexOf(patternStr);
        int end = start + patternStr.length();

        if (numParams == 0) {
            return executeTask.replace(start, end, newParams + ",new Object[]{" + params);
        } else {
            executeTask.replace(start + 1, end - 1, Integer.toString(numParams + newParams));
            executeTask.replace(executeTask.length() - 3, executeTask.length(), "");
            return executeTask.append("," + params);
        }
    }

    public static Object runMethodOnObject(Object o, Class<?> methodClass, String methodName, Object[] values, Class<?>[] types) throws Throwable {
        // Use reflection to get the requested method
        Method method = null;
        try {
            method = methodClass.getMethod(methodName, types);
        } catch (SecurityException e) {
            System.err.println("Security exception");
            e.printStackTrace();
            System.exit(1);
        } catch (NoSuchMethodException e) {
            System.err.println("Requested method " + methodName + " of " + methodClass + " not found");
            System.err.println("Types length is " + types.length);
            for (Class<?> type : types) {
                System.err.println("Type is " + type);
            }
            System.exit(1);
        }

        // Invoke the requested method
        Object retValue = null;
        try {
            retValue = method.invoke(o, values);
        } catch (IllegalArgumentException e) {
            System.err.println("Wrong argument passed to method " + methodName);
            e.printStackTrace();
            System.exit(1);
        } catch (IllegalAccessException e) {
            System.err.println("Cannot access method " + methodName);
            e.printStackTrace();
            System.exit(1);
        } catch (InvocationTargetException e) {
            throw e.getCause(); // re-throw the user exception thrown by the method
        }

        return retValue;
    }

    public static boolean isFileDelete(MethodCall mc) {
        if (mc.getMethodName().equals("delete")) {
            return (mc.getClassName().compareTo("java.io.File") == 0);
        }
        return false;
    }
}
