package integratedtoolkit.types.cache;

import integratedtoolkit.log.Loggers;
import integratedtoolkit.types.data.Location;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;

/*
 * Gabriel
 */
public class CacheMemory {
	
	private static final Logger logger = Logger.getLogger(Loggers.FTM_COMP);
	private static final boolean debug = logger.isDebugEnabled();


	public enum CacheType{
		LEVEL1(0),
		LEVEL2(1),
		LEVEL3(2),
		NONE(3);
		
		private int id;

        private CacheType(int value) {
                this.id = value;
        }
	};
	
	private CacheType cacheType;
	private Location location;
	private Long totalSize;
	private Long usedSpace;
	private List<DataCached> dataCached = new ArrayList<DataCached>();
	//private List<DataCached> dataCached = Collections.synchronizedList(new ArrayList<DataCached>());
	private HashMap<String, DataCached> dataCached_Dictionary = new HashMap<String, DataCached>();
	private float speedWeight=1.0f;
	private float powerWeight=1.0f;
	
	
	public CacheMemory(CacheType cacheType, Location location, Long totalSize) {
		super();
		this.cacheType = cacheType;
		this.location = location;
		this.totalSize = totalSize;
		this.usedSpace = (long)0.0f;
	}
	
	public CacheMemory(CacheType cacheType, Location location, Long totalSize,float speedWeight,float powerWeight) {
		super();
		this.cacheType = cacheType;
		this.location = location;
		this.totalSize = totalSize;
		this.usedSpace = (long)0.0f;
		this.speedWeight = speedWeight;
		this.powerWeight = powerWeight;
	}
	
	
	public void  updateSizes(){
		//synchronized (dataCached) {
			
		
		Iterator<DataCached> dataIterator = dataCached.iterator();
		this.usedSpace=(long)0.0d;
		while (dataIterator.hasNext()){
			final DataCached data = dataIterator.next();
			usedSpace+=data.getSize();
		}
	}
	
	public long getFreeSpace(){
		return totalSize-usedSpace;
	}
	
	
	public CacheType getCacheType() {
		return cacheType;
	}
	public Location getLocation() {
		return location;
	}
	public Long getTotalSize() {
		return totalSize;
	}
	public Long getUsedSpace() {
		return usedSpace;
	}
	
	public void addDataCached(String key, DataCached data){
		if(!dataCached_Dictionary.containsKey(key)){
			//synchronized (dataCached) {				
			dataCached_Dictionary.put(key, data);
			//dataCached.add(data);
			//updateSizes();
			this.usedSpace +=data.getSize(); 
			}
		//}
	}
	
	public boolean existsDataInCache(String key){
		return dataCached_Dictionary.containsKey(key);
	}

	public float getSpeedWeight() {
		return speedWeight;
	}

	public float getPowerWeight() {
		return powerWeight;
	}
	
	
	
}
