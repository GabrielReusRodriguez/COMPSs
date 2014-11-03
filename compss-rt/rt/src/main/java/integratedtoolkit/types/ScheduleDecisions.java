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

public class ScheduleDecisions {

    public LinkedList<Integer> mandatory;
    public HashMap<Integer, Integer> extra;
    public boolean forcedToDestroy;
    public HashMap<Integer, Float> terminate;
    private LinkedList<Movement> movements;

    public ScheduleDecisions() {
        movements = new LinkedList();
        forcedToDestroy = false;
    }

    public void addMovement(String sourceName, int sourceSlot, String targetName, int targetSlot, int core, int amount) {
        Movement mov = new Movement();
        mov.sourceName = sourceName;
        mov.sourceSlot = sourceSlot;
        mov.targetName = targetName;
        mov.targetSlot = targetSlot;
        mov.core = core;
        mov.amount = amount;
        movements.add(mov);
    }

    public LinkedList<Object[]> getMovements() {
        LinkedList movs = new LinkedList();
        for (Movement mov : movements) {
            movs.add(new Object[]{mov.sourceName, mov.targetName, mov.sourceSlot, mov.targetSlot, mov.core, mov.amount});
        }
        return movs;
    }
}

class Movement {
    String sourceName;
    int sourceSlot;
    String targetName;
    int targetSlot;
    int core;
    int amount;
}
