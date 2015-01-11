/**
 * 
 */
package integratedtoolkit.components.cache.impl;

import integratedtoolkit.api.ITExecution.ParamDirection;
import integratedtoolkit.components.cache.CachePolicy;
import integratedtoolkit.components.impl.FileTransferManager;
import integratedtoolkit.log.Loggers;
import integratedtoolkit.types.Parameter;
import integratedtoolkit.types.Parameter.DependencyParameter;
import integratedtoolkit.types.cache.CacheMemory;
import integratedtoolkit.types.cache.CacheMemory.CacheType;
import integratedtoolkit.types.data.DataAccessId.RAccessId;
import integratedtoolkit.types.data.DataAccessId.RWAccessId;
import integratedtoolkit.types.data.DataAccessId.WAccessId;
import integratedtoolkit.types.data.PhysicalDataInfo;
import integratedtoolkit.util.CacheManager;

import java.util.Comparator;
import java.util.PriorityQueue;

import org.apache.log4j.Logger;

/**
 * @author gabriel
 *
 */
public class SpeedAndPower_CachePolicy implements CachePolicy {

	private static final Logger logger = Logger.getLogger(Loggers.FTM_COMP);

	@Override
	public CacheType selectCacheLevel(Parameter parametro,
			CacheManager cacheManager, FileTransferManager FTM) {
		
		long size = (long) -1.0d;

		PriorityQueue<PriorityQueueNode> cacheLevels = new PriorityQueue<SpeedAndPower_CachePolicy.PriorityQueueNode>(
				3);

		if (parametro instanceof DependencyParameter) {
			DependencyParameter dp = (DependencyParameter) parametro;

			// Solo si existe el fichero en el disco, buscamos su tamaño. Si no
			// existe lo grabamos en disco para curarnos en salud
			if (cacheManager.existsPhysicalFile(dp, FTM)) {
				//size = getSizeOfFile(dp, cacheManager, FTM);
				// Si el tamanyo es -1.0 es que no se ha podido calcular o no
				// existe eñ fichero ene l disco, por lo tanto lo muevo al disco
				// duro.
				/*if (size == (long) -1.0d) {
					
					return CacheType.NONE;
				}*/

				if (cacheManager.isCacheLevelEnabled(CacheType.LEVEL1)) {
					
					calculateCacheScore(cacheManager, size, cacheLevels,
							CacheType.LEVEL1);
				} else {

					if (cacheManager.isCacheLevelEnabled(CacheType.LEVEL2)) {
						
						calculateCacheScore(cacheManager, size, cacheLevels,
								CacheType.LEVEL2);
					} else {

						if (cacheManager.isCacheLevelEnabled(CacheType.LEVEL3)) {
							
							calculateCacheScore(cacheManager, size,
									cacheLevels, CacheType.LEVEL3);
						}
					}
				}

			} else {
					
				return CacheType.NONE;

			}

		}
		if (cacheLevels.size() > 0) {
			
			return cacheLevels.peek().level;
		} else {
			
			return CacheType.NONE;
		}

	}

	private void calculateCacheScore(CacheManager cacheManager, long size,
			PriorityQueue<PriorityQueueNode> cacheLevels, CacheType level) {
		/*
		
		float ratio = calculateRatio(level, size, cacheManager);
		if (ratio < 0.9) {*/
			// el fichero es mayor que el 90% de la memoria cache
			// disponible, probamos en otro nivel.
			CacheMemory memorylevel = cacheManager.getCacheMemory(level);
			float speedWeight = memorylevel.getSpeedWeight();
			float powerWeight = memorylevel.getPowerWeight();
			float score = speedWeight * powerWeight;
			PriorityQueueNode node = new PriorityQueueNode(level, score);
			cacheLevels.offer(node);
		//}
	}

	private class PriorityQueueNode implements Comparator<PriorityQueueNode>,
			Comparable<PriorityQueueNode> {
		CacheType level = null;
		float score = 0;

		PriorityQueueNode(CacheType level, float score) {
			this.level = level;
			this.score = score;
		}

		@Override
		public int compareTo(PriorityQueueNode o) {
			if (this.score == o.score) {
				return 0;
			} else {
				if (this.score > o.score) {
					return 1;
				} else {
					return -1;
				}
			}
		}

		@Override
		public int compare(PriorityQueueNode o1, PriorityQueueNode o2) {
			if (o1.score == o2.score) {
				return 0;
			} else {
				if (o1.score > o2.score) {
					return 1;
				} else {
					return -1;
				}
			}

		}

	}

	// Calcula que % ocuparia un fichero en el espacio libre que hay
	// actualmente.
	private float calculateRatio(CacheType level, long fileSize,
			CacheManager cacheManager) {
		float ratio = 0.0f;
		long freeSpace = cacheManager.getFreeSpace(level);
		if (freeSpace > 0 && freeSpace > fileSize) {
			ratio = (float) (fileSize / freeSpace);
		} else {
			ratio = 100.0f;
		}
		return ratio;
	}

	private long getSizeOfFile(DependencyParameter dp,
			CacheManager cacheManager, FileTransferManager FTM) {
		long size = (long) -1.0d;
		if (dp.getDirection() == ParamDirection.IN) {
			RAccessId raId = (RAccessId) dp.getDataAccessId();
			PhysicalDataInfo pdi = FTM.getPhysicalDataInfoFromDataInstance(raId
					.getReadDataInstance());
			size = cacheManager.sizeOf(pdi);
		} else {

			if (dp.getDirection() == ParamDirection.OUT) {
				WAccessId waId = (WAccessId) dp.getDataAccessId();
				PhysicalDataInfo pdi = FTM
						.getPhysicalDataInfoFromDataInstance(waId
								.getWrittenDataInstance());
				size = cacheManager.sizeOf(pdi);
			} else {

				if (dp.getDirection() == ParamDirection.INOUT) {
					RWAccessId rwaId = (RWAccessId) dp.getDataAccessId();
					PhysicalDataInfo pdi = FTM
							.getPhysicalDataInfoFromDataInstance(rwaId
									.getWrittenDataInstance());
					size = cacheManager.sizeOf(pdi);
				}
			}
		}

		return size;
	}

}
