package integratedtoolkit.components.cache;

import integratedtoolkit.components.impl.FileTransferManager;
import integratedtoolkit.types.Parameter;
import integratedtoolkit.types.cache.CacheMemory.CacheType;
import integratedtoolkit.util.CacheManager;

/*Gabriel */
public interface CachePolicy {

	public enum CachePolicies{
		DEFAULT(0),
		SPEED_WEIGHT(1),
		POWER_WEIGHT(2),
		SPEED_AND_POWER_WEIGHT(3);
		
		private int id;

        private CachePolicies(int value) {
                this.id = value;
        }
	}
	
	//public CacheMemory selectCacheLevel(Parameter parametro,CacheManager cacheManager);
	public CacheType selectCacheLevel(Parameter parametro,CacheManager cacheManager,FileTransferManager FTM);
}
