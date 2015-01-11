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

package integratedtoolkit.connectors.rocci.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import integratedtoolkit.connectors.rocci.types.json.JSONResources;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URISyntaxException;

import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class ROCCIClientTest {
	
	@Test
	public void actionDescribeTest() {
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		try {
				String jsonInput = readFile(new File(ROCCIClientTest.class.getResource("/describe.json").toURI()));
				jsonInput = "{\"resources\":"+jsonInput+"}";
	
				//convert the json string back to object		
				JSONResources obj = gson.fromJson(jsonInput, JSONResources.class);
				
				String IP = null;
				String network_state = null;
				
				//System.out.println(" - Compute State: "+obj.getResources().get(0).getAttributes().getOcci().getCompute().getState());
				
				for(int i = 0; i< obj.getResources().get(0).getLinks().size(); i++){
				  if(obj.getResources().get(0).getLinks().get(i).getAttributes().getOcci().getNetworkinterface() != null){
				     IP = obj.getResources().get(0).getLinks().get(i).getAttributes().getOcci().getNetworkinterface().getAddress();
				     network_state = obj.getResources().get(0).getLinks().get(i).getAttributes().getOcci().getNetworkinterface().getState();
				     break;
				  }
				}
				
				assertNotNull(obj.getResources().get(0));
				assertEquals("Should be a resources defined", obj.getResources().size(), 1);
				assertNotNull(obj.getResources().get(0).getAttributes());
				assertNotNull(obj.getResources().get(0).getAttributes().getOcci());
				assertNotNull(obj.getResources().get(0).getAttributes().getOcci().getCompute());
				assertNotNull(obj.getResources().get(0).getAttributes().getOcci().getCompute().getState());
				assertEquals("Compute state should be active", obj.getResources().get(0).getAttributes().getOcci().getCompute().getState(), "active");
				
				assertNotNull(obj.getResources().get(0).getLinks());
				assertTrue( obj.getResources().get(0).getLinks().size() > 0);
				
				assertEquals("Network state should be active", network_state, "active");
				assertNotNull(IP);
				
				//System.out.println(" - Network State: "+network_state);
				//System.out.println(" - IP Address: "+IP);
	 		
		} catch (IOException e) {
			e.printStackTrace();
		} catch (URISyntaxException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	private String readFile( File file ) throws IOException {
	    BufferedReader reader = new BufferedReader( new FileReader (file));
	    String         line = null;
	    StringBuilder  stringBuilder = new StringBuilder();
	    String         ls = System.getProperty("line.separator");

	    while( ( line = reader.readLine() ) != null ) {
	        stringBuilder.append( line );
	        stringBuilder.append( ls );
	    }

	    return stringBuilder.toString();
	}

} //End test class
