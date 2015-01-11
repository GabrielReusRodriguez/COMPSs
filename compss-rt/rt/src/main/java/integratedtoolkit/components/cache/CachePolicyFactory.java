package integratedtoolkit.components.cache;

import integratedtoolkit.components.cache.CachePolicy.CachePolicies;
import integratedtoolkit.components.cache.impl.DefaultCachePolicy;
import integratedtoolkit.components.cache.impl.Power_CachePolicy;
import integratedtoolkit.components.cache.impl.SpeedAndPower_CachePolicy;
import integratedtoolkit.components.cache.impl.Speed_CachePolicy;

//Genera las politicas de cache segun los parámetros.
public abstract class CachePolicyFactory {

	public static CachePolicy getPolicy(String strTypePolicy) {

		CachePolicy policy = null;

		CachePolicies typePolicy = determineCachePolicy(strTypePolicy);

		switch (typePolicy) {

		case SPEED_WEIGHT:
			policy = new Speed_CachePolicy();
			break;
		case POWER_WEIGHT:
			policy = new Power_CachePolicy();
			break;
		case SPEED_AND_POWER_WEIGHT:
			policy = new SpeedAndPower_CachePolicy();
			break;
			default:
				policy = new DefaultCachePolicy();
				break;
		}

		return policy;
	}

	private static CachePolicies determineCachePolicy(String typePolicy) {
		int iPolicy = 0;
		CachePolicies cachePolicy = CachePolicies.DEFAULT;

		if (typePolicy.equalsIgnoreCase("0")) {
			cachePolicy = CachePolicies.DEFAULT;
		} else {
			try {
				iPolicy = Integer.parseInt(typePolicy);
			} catch (NullPointerException e) {
				cachePolicy = CachePolicies.DEFAULT;
			} catch (NumberFormatException e) {
				cachePolicy = CachePolicies.DEFAULT;
			}
		}

		switch (iPolicy) {
		case 1:
			cachePolicy = CachePolicies.SPEED_WEIGHT;
			break;
		case 2:
			cachePolicy = CachePolicies.POWER_WEIGHT;
			break;
		case 3:
			cachePolicy = CachePolicies.SPEED_AND_POWER_WEIGHT;
			break;
		default:
			cachePolicy = CachePolicies.DEFAULT;
			break;
		}
		
		return cachePolicy;
	}
}
