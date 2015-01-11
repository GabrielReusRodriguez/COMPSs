package integratedtoolkit.util;

import integratedtoolkit.types.CacheConfigDescription;
import integratedtoolkit.types.CacheLevelConfig;
import integratedtoolkit.types.cache.CacheMemory.CacheType;

import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/*
 * Gabriel
 */
public class CacheConfigFactory {

	public CacheConfigDescription buildCacheConfig(Node node) {

		CacheConfigDescription cacheConfigDescription = new CacheConfigDescription();
		NodeList children = node.getChildNodes();

		for (int x = 0; x < children.getLength(); x++) {

			Node child = children.item(x);
            if (child.getNodeName().compareTo("#text") == 0) {
            	continue;
            } 

			
			NodeList cacheDetails = child.getChildNodes();
			/*
			 * This is a cache Entry, so I proccess their detail (size and
			 * location)
			 */
			
			Long sizeCache = null;
			String locationCache = null;
			Float speedWeight = 1.0f;
			Float powerWeight = 1.0f;
			for (int y = 0; y < cacheDetails.getLength(); y++) {
				Node cacheDetail = cacheDetails.item(y);
	            if (cacheDetail.getNodeName().compareTo("#text") == 0) {
	            	continue;
	            } 
				
				if (cacheDetail.getNodeName().compareTo("Size") == 0) {
					String size = cacheDetail.getTextContent();
					try {
						sizeCache = Long.parseLong(size);
					} catch (NullPointerException e) {
						sizeCache = new Long((long) 0.0f);
					} catch (NumberFormatException e) {
						sizeCache = new Long((long) 0.0f);
					}
					continue;
				}
				if (cacheDetail.getNodeName().compareTo("Location") == 0) {
					locationCache = cacheDetail.getTextContent();
					continue;
				}

				if (cacheDetail.getNodeName().compareTo("SpeedWeight") == 0) {
					String strSpeedWeight = cacheDetail.getTextContent();
					try {
						speedWeight = Float.parseFloat(strSpeedWeight);
					} catch (NullPointerException e) {
						speedWeight = new Float(1.0f);
					} catch (NumberFormatException e) {
						speedWeight = new Float(1.0f);
					}
					continue;
				}

				if (cacheDetail.getNodeName().compareTo("PowerWeight") == 0) {
					String strPowerWeight = cacheDetail.getTextContent();
					try {
						powerWeight = Float.parseFloat(strPowerWeight);
					} catch (NullPointerException e) {
						powerWeight = new Float(1.0f);
					} catch (NumberFormatException e) {
						powerWeight = new Float(1.0f);
					}
					continue;
				}
			}

			/*
			 * It depends the kind of cache...
			 */
			if (child.getNodeName().compareTo("CacheLevel1") == 0) {
				cacheConfigDescription.setSize(sizeCache, CacheType.LEVEL1);
				cacheConfigDescription.setLocation(locationCache,
						CacheType.LEVEL1);
				cacheConfigDescription.setSpeedWeight(speedWeight,
						CacheType.LEVEL1);
				cacheConfigDescription.setPowerWeight(powerWeight,
						CacheType.LEVEL1);
				continue;
			}
			if (child.getNodeName().compareTo("CacheLevel2") == 0) {
				cacheConfigDescription.setSize(sizeCache, CacheType.LEVEL2);
				cacheConfigDescription.setLocation(locationCache,
						CacheType.LEVEL2);
				cacheConfigDescription.setSpeedWeight(speedWeight,
						CacheType.LEVEL2);
				cacheConfigDescription.setPowerWeight(powerWeight,
						CacheType.LEVEL2);
				continue;
			}
			if (child.getNodeName().compareTo("CacheLevel3") == 0) {
				cacheConfigDescription.setSize(sizeCache, CacheType.LEVEL3);
				cacheConfigDescription.setLocation(locationCache,
						CacheType.LEVEL3);
				cacheConfigDescription.setSpeedWeight(speedWeight,
						CacheType.LEVEL3);
				cacheConfigDescription.setPowerWeight(powerWeight,
						CacheType.LEVEL3);
				continue;
			}

		}
		return cacheConfigDescription;
	}
}
