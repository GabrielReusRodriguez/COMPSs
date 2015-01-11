package integratedtoolkit.types.cache;

import integratedtoolkit.types.data.DataInstanceId;
import integratedtoolkit.types.data.Location;


/*
 * Gabriel
 */
public class DataCached {
	
	private DataInstanceId dataInstanceId;
	private long size;
	private String fileName="";
	private Location location;
	
	public DataCached(DataInstanceId dataInstanceId, long size,
			String fileName, Location location) {
		super();
		this.dataInstanceId = dataInstanceId;
		this.size = size;
		this.fileName = fileName;
		this.location = location;
	}
	
	public DataInstanceId getDataInstanceId() {
		return dataInstanceId;
	}
	public long getSize() {
		return size;
	}
	public String getFileName() {
		return fileName;
	}
	public Location getLocation() {
		return location;
	}
	
}
