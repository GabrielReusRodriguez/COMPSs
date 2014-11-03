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


package integratedtoolkit.components.scheduler.impl;

import integratedtoolkit.api.ITExecution;
import integratedtoolkit.components.scheduler.SchedulerPolicies;
import integratedtoolkit.log.Loggers;
import integratedtoolkit.types.Parameter;
import integratedtoolkit.types.Task;
import integratedtoolkit.types.data.DataAccessId;
import integratedtoolkit.types.data.DataInstanceId;
import integratedtoolkit.util.ResourceManager;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.TreeSet;

import org.apache.log4j.Logger;

public class DefaultSchedulerPolicies extends SchedulerPolicies {

    protected static final Logger logger = Logger.getLogger(Loggers.TS_COMP);
    protected static final boolean debug = logger.isDebugEnabled();

    @Override
    public LinkedList<Task> sortTasksForResource(String hostName, List<Task> tasks) {
        PriorityQueue<Pair<Task>> pq = new PriorityQueue<Pair<Task>>();
        for (Task t : tasks) {
            if (ResourceManager.matches(hostName, t.getCore().getId())) {
                String failedHost = t.getExecParams().getHost();
                if (!failedHost.equals(hostName)) {
                    int score = 0;
                    Parameter[] params = t.getCore().getParameters();
                    for (Parameter p : params) {
                        if (p instanceof Parameter.DependencyParameter) {
                            Parameter.DependencyParameter fp = (Parameter.DependencyParameter) p;
                            DataInstanceId dId = null;
                            switch (fp.getDirection()) {
                                case IN:
                                    DataAccessId.RAccessId raId = (DataAccessId.RAccessId) fp.getDataAccessId();
                                    dId = raId.getReadDataInstance();
                                    break;
                                case INOUT:
                                    DataAccessId.RWAccessId rwaId = (DataAccessId.RWAccessId) fp.getDataAccessId();
                                    dId = rwaId.getReadDataInstance();
                                    break;
                                case OUT:
                                    break;
                            }

                            if (dId != null) {
                                TreeSet<String> hosts = FTM.getHosts(dId);
                                for (String host : hosts) {
                                    if (host.compareTo(hostName) == 0) {
                                        score++;
                                    }
                                }
                            }

                        }
                    }
                    pq.add(new Pair(t, score));
                }
            }
        }
        Iterator<Pair<Task>> it = pq.iterator();
        LinkedList<Task> list = new LinkedList();
        while (it.hasNext()) {
            Pair<Task> p = it.next();
            list.add(p.o);
        }
        return list;
    }

    @Override
    public LinkedList<String> sortResourcesForTask(Task t, List<String> resources) {
        PriorityQueue<Pair<String>> pq = new PriorityQueue<Pair<String>>();

        Parameter[] params = t.getCore().getParameters();
        HashMap<String, Integer> hostToScore = new HashMap<String, Integer>(params.length * 2);

        // Obtain the scores for each host: number of task parameters that are located in the host
        for (Parameter p : params) {
            if (p instanceof Parameter.DependencyParameter && p.getDirection() != ITExecution.ParamDirection.OUT) {
                Parameter.DependencyParameter dp = (Parameter.DependencyParameter) p;
                DataInstanceId dId = null;
                switch (dp.getDirection()) {
                    case IN:
                        DataAccessId.RAccessId raId = (DataAccessId.RAccessId) dp.getDataAccessId();
                        dId = raId.getReadDataInstance();
                        break;
                    case INOUT:
                        DataAccessId.RWAccessId rwaId = (DataAccessId.RWAccessId) dp.getDataAccessId();
                        dId = rwaId.getReadDataInstance();
                        break;
                    case OUT:
                        break;
                }

                if (dId != null) {
                    TreeSet<String> hosts = FTM.getHosts(dId);
                    for (String host : hosts) {
                        Integer score;
                        if ((score = hostToScore.get(host)) == null) {
                            score = new Integer(0);
                            hostToScore.put(host, score);
                        }
                        hostToScore.put(host, score + 1);
                    }
                }
            }
        }
        for (String resource : resources) {
            Integer score = hostToScore.get(resource);
            if (score == null) {
                pq.offer(new Pair(resource, 0));
            } else {
                pq.offer(new Pair(resource, score));
            }
        }

        LinkedList<String> list = new LinkedList<String>();
        Iterator<Pair<String>> it = pq.iterator();
        while (it.hasNext()) {
            Pair<String> p = it.next();
            list.add(p.o);
        }
        return list;

    }

    @Override
    public OwnerTask[] stealTasks(String destResource, HashMap<String, LinkedList<Task>> pendingTasks, int numberOfTasks) {

        OwnerTask[] stolenTasks = new OwnerTask[numberOfTasks];
        PriorityQueue<Pair<OwnerTask>> pq = new PriorityQueue<Pair<OwnerTask>>();
        for (java.util.Map.Entry<String, LinkedList<Task>> e : pendingTasks.entrySet()) {
            String ownerName = e.getKey();
            LinkedList<Task> candidates = e.getValue();
            for (Task t : candidates) {
                int score = 0;
                if (t.isSchedulingStrongForced()) {
                    continue;
                } else if (!t.isSchedulingForced()) {
                    score = 10000;
                }
                if (ResourceManager.matches(destResource, t.getCore().getId())) {
                    Parameter[] params = t.getCore().getParameters();
                    for (Parameter p : params) {
                        if (p instanceof Parameter.DependencyParameter) {
                            Parameter.DependencyParameter dp = (Parameter.DependencyParameter) p;
                            DataInstanceId dId = null;
                            switch (dp.getDirection()) {
                                case IN:
                                    DataAccessId.RAccessId raId = (DataAccessId.RAccessId) dp.getDataAccessId();
                                    dId = raId.getReadDataInstance();
                                    break;
                                case INOUT:
                                    DataAccessId.RWAccessId rwaId = (DataAccessId.RWAccessId) dp.getDataAccessId();
                                    dId = rwaId.getReadDataInstance();
                                    break;
                                case OUT:
                                    break;
                            }

                            if (dId != null) {
                                TreeSet<String> hosts = FTM.getHosts(dId);
                                for (String host : hosts) {
                                    if (host.equals(ownerName)) {
                                        score--;
                                        break;
                                    }
                                    if (host.equals(destResource)) {
                                        score += 2;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
                pq.offer(new Pair(new OwnerTask(ownerName, t), score));
            }
        }

        int i = 0;
        while (pq.iterator().hasNext() && i < numberOfTasks) {
            stolenTasks[i] = pq.iterator().next().o;
            i++;
        }
        return stolenTasks;
    }

    private class Pair<T> implements Comparable<Pair> {

        T o;
        int value;

        public Pair(T o, int value) {
            this.o = o;
            this.value = value;
        }

        public int compareTo(Pair o) {
            return o.value - this.value;
        }
    }

}
