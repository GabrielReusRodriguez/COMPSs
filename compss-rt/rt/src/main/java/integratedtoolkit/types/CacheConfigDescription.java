/**
 * 
 */
package integratedtoolkit.types;

import integratedtoolkit.ITConstants;
import integratedtoolkit.types.cache.CacheMemory.CacheType;

import java.util.ArrayList;
import java.util.List;


/**
 * @author gabriel
 *
 */
public class CacheConfigDescription {

	private List<CacheLevelConfig> configLevels =null;
	/*
	long cacheLevel1_Size = (long)0.0d; //In MB
	String cacheLevel1_Location="";
	float cacheLevel1_SpeedWeight = 1.0f;
	float cacheLevel1_PowerWeight = 1.0f;
	
	
	long cacheLevel2_Size = (long)0.0d; //In MB
	String cacheLevel2_Location="";
	float cacheLevel2_SpeedWeight = 1.0f;
	float cacheLevel2_PowerWeight = 1.0f;
	
	
	long cacheLevel3_Size =(long)0.0d; //In MB
	String cacheLevel3_Location="";
	float cacheLevel3_SpeedWeight = 1.0f;
	float cacheLevel3_PowerWeight = 1.0f;
*/
	
	
	/**
	 * 
	 */
	public CacheConfigDescription() {
		// TODO Auto-generated constructor stub
		configLevels = new ArrayList<CacheLevelConfig>();
		for(int i = 0;i < ITConstants.NUM_CACHE_LEVELS;i++){
			//configLevels.add(null);
			
			CacheLevelConfig config = new CacheLevelConfig((long)0.0f,"", 0.0f, 0.0f);
			configLevels.add(config);
		}
	}
	
	private CacheLevelConfig getCacheLevelConfig(CacheType level){
		CacheLevelConfig cacheLevel = null;
		switch(level){
		case LEVEL1:
			cacheLevel= configLevels.get(0);
			break;
		case LEVEL2:
			cacheLevel= configLevels.get(1);
			break;
		case LEVEL3:
			cacheLevel= configLevels.get(2);
			break;
		}
		
		return cacheLevel;
	}
	
	public long getSize(CacheType level){
		CacheLevelConfig cacheLevelConfig = null;
		cacheLevelConfig = getCacheLevelConfig(level);
		if(cacheLevelConfig != null){
			return cacheLevelConfig.getSize();
		}else{
			return (long)0.0d;
		}
	}
	
	public String getLocation(CacheType level){
		CacheLevelConfig cacheLevelConfig = null;
		cacheLevelConfig = getCacheLevelConfig(level);
		if(cacheLevelConfig != null){
			return cacheLevelConfig.getLocation();
		}else{
			return "";
		}
	}

	public float getSpeedWeight(CacheType level){
		CacheLevelConfig cacheLevelConfig = null;
		cacheLevelConfig = getCacheLevelConfig(level);
		if(cacheLevelConfig != null){
			return cacheLevelConfig.getSpeedWeight();
		}else{
			return 1.0f;
		}
	}

	
	public float getPowerWeight(CacheType level){
		CacheLevelConfig cacheLevelConfig = null;
		cacheLevelConfig = getCacheLevelConfig(level);
		if(cacheLevelConfig != null){
			return cacheLevelConfig.getPowerWeight();
		}else{
			return 1.0f;
		}
	}

	
	public void setSize(long size,CacheType level){
		CacheLevelConfig cacheLevelConfig = null;
		cacheLevelConfig = getCacheLevelConfig(level);
		if(cacheLevelConfig != null){
			cacheLevelConfig.setSize(size);
		}
	}
	
	public void setLocation(String  location,CacheType level){
		CacheLevelConfig cacheLevelConfig = null;
		cacheLevelConfig = getCacheLevelConfig(level);
		if(cacheLevelConfig != null){
			cacheLevelConfig.setLocation(location);
		}
	}

	public void setSpeedWeight(float speedWeight,CacheType level){
		CacheLevelConfig cacheLevelConfig = null;
		cacheLevelConfig = getCacheLevelConfig(level);
		if(cacheLevelConfig != null){
			cacheLevelConfig.setSpeedWeight(speedWeight);
		}
	}

	
	public void setPowerWeight(float powerWeight,CacheType level){
		CacheLevelConfig cacheLevelConfig = null;
		cacheLevelConfig = getCacheLevelConfig(level);
		if(cacheLevelConfig != null){
			cacheLevelConfig.setPowerWeight(powerWeight);
		}
	}
	
	public String toString(boolean andUsed){
		boolean internalAndUsed=andUsed;
		StringBuffer strBfr = new StringBuffer();
	
		if(isCacheLevel_Enabled(CacheType.LEVEL1)){
			if(internalAndUsed){
				strBfr.append(" and ");
			}else{
				strBfr.append(" [");
			}
			internalAndUsed=true;
			strBfr.append("Capabilities/CacheLevel1/Size[text()='"+getSize(CacheType.LEVEL1)+"']");
			strBfr.append(" and ");
			strBfr.append("Capabilities/CacheLevel1/Location[text()='"+getLocation(CacheType.LEVEL1)+"']");
			strBfr.append(" and ");
			strBfr.append("Capabilities/CacheLevel1/SpeedWeight[text()='"+getSpeedWeight(CacheType.LEVEL1)+"']");
			strBfr.append(" and ");
			strBfr.append("Capabilities/CacheLevel1/PowerWeight[text()='"+getPowerWeight(CacheType.LEVEL1)+"']");
		}
		
		if(isCacheLevel_Enabled(CacheType.LEVEL2)){
			if(internalAndUsed){
				strBfr.append(" and ");
			}else{
				strBfr.append(" [");
			}
			internalAndUsed=true;
			strBfr.append("Capabilities/CacheLevel2/Size[text()='"+getSize(CacheType.LEVEL2)+"']");
			strBfr.append(" and ");
			strBfr.append("Capabilities/CacheLevel2/Location[text()='"+getLocation(CacheType.LEVEL2)+"']");
			strBfr.append(" and ");
			strBfr.append("Capabilities/CacheLevel1/SpeedWeight[text()='"+getSpeedWeight(CacheType.LEVEL2)+"']");
			strBfr.append(" and ");
			strBfr.append("Capabilities/CacheLevel1/PowerWeight[text()='"+getPowerWeight(CacheType.LEVEL2)+"']");

		}
		
		if(isCacheLevel_Enabled(CacheType.LEVEL3)){
			if(internalAndUsed){
				strBfr.append(" and ");
			}else{
				strBfr.append(" [");
			}
			internalAndUsed=true;
			strBfr.append("Capabilities/CacheLevel3/Size[text()='"+getSize(CacheType.LEVEL3)+"']");
			strBfr.append(" and ");
			strBfr.append("Capabilities/CacheLevel3/Location[text()='"+getLocation(CacheType.LEVEL3)+"']");
			strBfr.append(" and ");
			strBfr.append("Capabilities/CacheLevel1/SpeedWeight[text()='"+getSpeedWeight(CacheType.LEVEL3)+"']");
			strBfr.append(" and ");
			strBfr.append("Capabilities/CacheLevel1/PowerWeight[text()='"+getPowerWeight(CacheType.LEVEL3)+"']");

		}	
		
		return strBfr.toString();
	}
		
	public boolean isCacheLevel_Enabled(CacheType level){
		return this.getSize(level) > (long)0.0f && this.getLocation(level) != null && !this.getLocation(level).trim().equalsIgnoreCase("");
	}
	
}
