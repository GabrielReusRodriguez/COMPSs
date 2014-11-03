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




package integratedtoolkit.types.request.tp;

import java.util.concurrent.Semaphore;

import integratedtoolkit.types.data.DataInstanceId;


public class GetLastDataAccessRequest extends TPRequest {

	private int code;
	private Semaphore sem;
	
	private DataInstanceId response;

	public GetLastDataAccessRequest(int code, Semaphore sem) {
		super(TPRequestType.GET_LAST_DATA_ACCESS);
		this.code = code;
		this.sem = sem;
	}

	public int getCode() {
		return code;
	}

	public void setCode(int code) {
		this.code = code;
	}

	public Semaphore getSemaphore() {
		return sem;
	}

	public void setSemaphore(Semaphore sem) {
		this.sem = sem;
	}

	public DataInstanceId getResponse() {
		return response;
	}

	public void setResponse(DataInstanceId response) {
		this.response = response;
	}

}
