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



package integratedtoolkit.connectors.rocci;

import integratedtoolkit.connectors.ConnectorException;

import java.io.IOException;
import java.util.List;

public class RocciClient {
	
	private String cmd_line="";
	private String attributes="";
	
	public RocciClient(List<String> cmd_string, String attr) {				
		for (String s : cmd_string){
		  cmd_line+=s+" ";		
		}
		attributes = attr;
	}
	
	public String describe_resource(String resource_id) {
		
		//EXAMPLE DESCRIBE WITHOUT VOMS:
		 //occi --endpoint https://bscgrid20.bsc.es:11443 --auth x509 --ca-path /etc/grid-security/certificates --user-cred rrafanel.pem --password gr1dgr0uppwd --action describe --resource /compute/a85e28d3-f41f-4fa8-9334-dd6b4776f918

		//EXAMPLE DESCRIBE WITH VOMS:
		 //occi  --endpoint https://carach5.ics.muni.cz:11443/ --voms --auth x509 --ca-path /etc/grid-security/certificates --user-cred /home/pmes/certs/egi/usercert_with_key.pem --password d4n13l3egipwd --action describe --resource /compute/a85e28d3-f41f-4fa8-9334-dd6b4776f918	   
		
		String res_desc = "";
		String cmd = cmd_line +"--action describe" + " --resource " + resource_id;
		
		//System.out.println("Describe resource CMD -> "+cmd);
		
		try {
			res_desc = execute_cmd(cmd);
		} catch (ConnectorException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return res_desc;
				
	}
	
	public String get_resource_status(String resource_id) {
		
		String res_status = null;
		String res_desc = describe_resource(resource_id);

		String [] splitted;
		splitted = res_desc.split(System.getProperty("line.separator"));
		
		for(String s:splitted){
			if(s.indexOf("STATE:")!=-1) {
				res_status = s.substring(s.indexOf("STATE")+6).replaceAll("\\s", "");
	      	    //System.out.println("res_status -> "+ res_status);
			}
		}

		return res_status;
		
	}
	
	public String get_resource_address(String resource_id) {

		 String res_ip = null;
         String res_desc = describe_resource(resource_id);
         String [] splitted;
         String [] ip_splitted;

         splitted = res_desc.split(System.getProperty("line.separator"));
         for(String s:splitted){
              if(s.indexOf("IP ADDRESS:")!=-1) {
                  res_ip = s.substring(s.indexOf("IP ADDRESS")+11).replaceAll("\\s", "");
                  ip_splitted = res_ip.split("\\.");
                  if(ip_splitted.length > 0)
                     if(ip_splitted[0].compareTo("10") != 0 && ip_splitted[0].compareTo("192") !=0){
                         return res_ip;
                     }
              }
         }
         return res_ip;
	}
	
	public void delete_compute(String resource_id) {
		
		String cmd = cmd_line + "--action delete" + " --resource " + resource_id;
		//System.out.println("Delete resource CMD -> "+cmd);
		try {
			execute_cmd(cmd);
		} catch (ConnectorException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		catch (Exception e) {		
			e.printStackTrace();
		}

	}
	
	public String create_compute(String os_tpl, String resource_tpl) {
		String s = "";
		
		String cmd = cmd_line + " --action create" + " --resource compute -M os_tpl#" + 
		os_tpl + " -M resource_tpl#" + resource_tpl + " --attributes title=\""+attributes+"\"";
		
		//System.out.println("Create resource CMD -> "+cmd);
		
		try {
			s = execute_cmd(cmd);			
		} catch (ConnectorException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		/*String [] splitted = s.split(System.getProperty("line.separator"));
		
		if(splitted.length > 1)
			return splitted[1];
		else
			return null;
		*/
		return s;
	}
	
	
	public String execute_cmd(String cmd_args) throws ConnectorException, InterruptedException {
		String return_string = "";
		String buff = null;		
		
		String [] cmd_line = {"/bin/bash", "-c", "occi " + cmd_args};
		
		//System.out.println("Executing -> "+cmd_args);

		try {
	        Process p = Runtime.getRuntime().exec(cmd_line);
			p.waitFor();
			java.io.BufferedReader is = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
	      while((buff = is.readLine())!=null) {
	    	  return_string += buff +"\n";
	      }
	      return return_string;
			 
		} catch (IOException e) {
			// TODO Auto-generated catch block
            throw new ConnectorException(e);
		}
       

	}
}
