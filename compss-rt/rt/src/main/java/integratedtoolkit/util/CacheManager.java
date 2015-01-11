package integratedtoolkit.util;

import integratedtoolkit.ITConstants;
import integratedtoolkit.api.ITExecution.ParamDirection;
import integratedtoolkit.components.cache.CachePolicy;
import integratedtoolkit.components.cache.CachePolicyFactory;
import integratedtoolkit.components.cache.impl.DefaultCachePolicy;
import integratedtoolkit.components.impl.FileTransferManager;
import integratedtoolkit.log.Loggers;
import integratedtoolkit.types.CacheConfigDescription;
import integratedtoolkit.types.Parameter;
import integratedtoolkit.types.Parameter.DependencyParameter;
import integratedtoolkit.types.cache.CacheMemory;
import integratedtoolkit.types.cache.CacheMemory.CacheType;
import integratedtoolkit.types.cache.DataCached;
import integratedtoolkit.types.data.DataAccessId.RAccessId;
import integratedtoolkit.types.data.DataAccessId.RWAccessId;
import integratedtoolkit.types.data.DataAccessId.WAccessId;
import integratedtoolkit.types.data.FileOperation.Copy;
import integratedtoolkit.types.data.Location;
import integratedtoolkit.types.data.PhysicalDataInfo;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;
import org.gridlab.gat.GATInvocationException;
import org.gridlab.gat.URI;

/*
 * Gabriel
 * */
public class CacheManager {

	// Component logger - No need to configure, ProActive does
	private static final Logger logger = Logger.getLogger(Loggers.FTM_COMP);
	private static final boolean debug = logger.isDebugEnabled();

	private List<CacheMemory> levels = new ArrayList<CacheMemory>();

	// Cache Policy
	private CachePolicy cachePolicy = new DefaultCachePolicy();

	public CacheManager(String host, CacheConfigDescription description) {

		for (int i = 0; i < ITConstants.NUM_CACHE_LEVELS; i++) {
			levels.add(null);
		}

		if (description.isCacheLevel_Enabled(CacheType.LEVEL1)) {
			CacheMemory level = new CacheMemory(CacheMemory.CacheType.LEVEL1,
					new Location(host, description
							.getLocation(CacheType.LEVEL1)),
					description.getSize(CacheType.LEVEL1),
					description.getSpeedWeight(CacheType.LEVEL1),
					description.getPowerWeight(CacheType.LEVEL1));
			levels.set(0, level);
		} else {
			if (description.isCacheLevel_Enabled(CacheType.LEVEL2)) {
				CacheMemory level = new CacheMemory(
						CacheMemory.CacheType.LEVEL2, new Location(host,
								description.getLocation(CacheType.LEVEL2)),
						description.getSize(CacheType.LEVEL2),
						description.getSpeedWeight(CacheType.LEVEL2),
						description.getPowerWeight(CacheType.LEVEL2));
				levels.set(1, level);
			} else {
				if (description.isCacheLevel_Enabled(CacheType.LEVEL3)) {
					CacheMemory level = new CacheMemory(
							CacheMemory.CacheType.LEVEL3, new Location(host,
									description.getLocation(CacheType.LEVEL3)),
							description.getSize(CacheType.LEVEL3),
							description.getSpeedWeight(CacheType.LEVEL3),
							description.getPowerWeight(CacheType.LEVEL3));
					levels.set(2, level);
				}
			}
		}
		cachePolicy = CachePolicyFactory.getPolicy(System
				.getProperty(ITConstants.IT_CACHE_POLICY));
	}

	// Shows us if cache mode is enabled.
	public static boolean isCacheMode_Enabled() {

		boolean isCacheEnabled = false;
		if (System.getProperty(ITConstants.IT_CACHE_ENABLED) != null
				&& System.getProperty(ITConstants.IT_CACHE_ENABLED).equals(
						"true")) {
			isCacheEnabled = true;
		}
		return isCacheEnabled;
	}

	public boolean isCacheLevelEnabled(CacheType level) {
		return getCacheMemory(level) != null;
	}

	public void setCachePolicy(CachePolicy policy) {
		this.cachePolicy = policy;
	}

	public long getFreeSpace(CacheType cacheType) {
		// TODO: throw an Exception if a cache is not enabled.
		long freeSpace = (long) 0.0f;
		CacheMemory level = null;

		switch (cacheType) {
		case LEVEL1:
			level = getCacheMemory(CacheType.LEVEL1);
			break;
		case LEVEL2:
			level = getCacheMemory(CacheType.LEVEL2);
			break;
		case LEVEL3:
			level = getCacheMemory(CacheType.LEVEL3);
			break;
		default:
			break;

		}

		if (level != null) {
			freeSpace = level.getFreeSpace();
		}
		return freeSpace;
	}

	public CacheMemory getCacheMemory(CacheMemory.CacheType cacheType) {
		// TODO: Throw an exception if the cache is not enabled.
		CacheMemory cache = null;
		switch (cacheType) {
		case LEVEL1:
			cache = levels.get(0);
			break;
		case LEVEL2:
			cache = levels.get(1);
			break;
		case LEVEL3:
			cache = levels.get(2);
			break;
		default:
			break;
		}

		return cache;
	}

	
	public void assignParams2Cache(Parameter[] params,
			List<Parameter> parameters, Location workDirLocation,
			FileTransferManager FTM) {

		for (Parameter param : params) {

			CacheType memoryLevel = cachePolicy.selectCacheLevel(param, this,
					FTM);

			switch (memoryLevel) {
			case LEVEL1:
				((DependencyParameter)param).setTargetLocation(getLocationOfLevel(CacheType.LEVEL1));
				parameters.add(param);
				break;
			case LEVEL2:
				((DependencyParameter)param).setTargetLocation(getLocationOfLevel(CacheType.LEVEL2));
				parameters.add(param);
				break;
			case LEVEL3:
				((DependencyParameter)param).setTargetLocation(getLocationOfLevel(CacheType.LEVEL3));
				parameters.add(param);
				break;
			case NONE:
			default:
				((DependencyParameter)param).setTargetLocation(workDirLocation);
				parameters.add(param);
				break;
			}
		}
	}
	
	public CacheType getCacheLevelByLocation(Location origen) {
		if (getCacheMemory(CacheType.LEVEL1) != null
				&& getCacheMemory(CacheType.LEVEL1).getLocation().getPath()
						.equalsIgnoreCase(origen.getPath())) {
			return CacheType.LEVEL1;
		}

		if (getCacheMemory(CacheType.LEVEL2) != null
				&& getCacheMemory(CacheType.LEVEL2).getLocation().getPath()
						.equalsIgnoreCase(origen.getPath())) {
			return CacheType.LEVEL2;
		}

		if (getCacheMemory(CacheType.LEVEL3) != null
				&& getCacheMemory(CacheType.LEVEL3).getLocation().getPath()
						.equalsIgnoreCase(origen.getPath())) {
			return CacheType.LEVEL3;
		}
		return CacheType.NONE;
	}

	public void addFileToCacheLog(CacheType level, Copy copy) {
		CacheMemory cacheLevel = null;


		cacheLevel = getCacheMemory(level);

		if (cacheLevel != null) {
			DataCached readData = null;
			DataCached writeData = null;
			String keyReadData = "";
			String keyWriteData = "";
			long size = (long) 0.0d;

			//size = sizeOf(copy);

			if (copy.getDependencyParameter().getDirection() == ParamDirection.IN) {
				RAccessId dataAccess = ((RAccessId) copy
						.getDependencyParameter().getDataAccessId());

				keyReadData = dataAccess.getReadDataInstance().getRenaming();
				readData = new DataCached(dataAccess.getReadDataInstance(),
						size, copy.getName(), copy.getTargetLocation());
			} else {
				if (copy.getDependencyParameter().getDirection() == ParamDirection.OUT) {
					WAccessId WDataAccess = ((WAccessId) copy
							.getDependencyParameter().getDataAccessId());
					keyWriteData = WDataAccess.getWrittenDataInstance()
							.getRenaming();
					writeData = new DataCached(
							WDataAccess.getWrittenDataInstance(), size,
							copy.getName(), copy.getTargetLocation());
				} else {
					if (copy.getDependencyParameter().getDirection() == ParamDirection.INOUT) {
						/*
						RAccessId dataAccess = ((RAccessId) copy
								.getDependencyParameter().getDataAccessId());

						keyReadData = dataAccess.getReadDataInstance()
								.getRenaming();
						readData = new DataCached(
								dataAccess.getReadDataInstance(), size,
								copy.getName(), copy.getTargetLocation());
						*/
						
						RWAccessId dataAccess = ((RWAccessId) copy
								.getDependencyParameter().getDataAccessId());

						keyReadData = dataAccess.getReadDataInstance()
								.getRenaming();
						readData = new DataCached(
								dataAccess.getReadDataInstance(), size,
								copy.getName(), copy.getTargetLocation());

					}
				}
			}
			//if (size != -1.0d) {
				if (readData != null) {
					cacheLevel.addDataCached(keyReadData, readData);

				}
				if (writeData != null) {
					cacheLevel.addDataCached(keyWriteData, writeData);
				}
			//}
		}
	}

	// http://chepech.blogspot.com.es/2012/03/java-getting-memory-size-of-object.html
	public long sizeOf(Copy copy) {
		long size = (long) -1.0d;

		List<URI> listaURIS = null;
		try {
			listaURIS = copy.getLogicalFile().getURIs();
		} catch (GATInvocationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		size = sizeOf(listaURIS);
		return size;
	}

	public long sizeOf(PhysicalDataInfo pdi) {

		long size = (long) -1.0d;

		List<URI> listaURIS = null;
		try {
			listaURIS = pdi.getLogicalFile().getURIs();
		} catch (GATInvocationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (NullPointerException e) {
			e.printStackTrace();
		}

		size = sizeOf(listaURIS);
		return size;
	}

	private long sizeOf(List<URI> listaURIS) {
		long size = (long) 0.0d;
		String filePath = "";
		if (listaURIS != null) {
			URI uri = listaURIS.get(0);
			filePath = uri.getPath();
		}
		File f = new File(filePath);
		if (f.exists() && !f.isDirectory()) {
			double bytes = f.length();
			double kilobytes = (bytes / 1024.0d);
			double megabytes = (kilobytes / 1024.0d);
			size = (long) megabytes;
		}
		return size;
	}

	public boolean existsPhysicalFile(DependencyParameter dp,
			FileTransferManager FTM) {
		PhysicalDataInfo pdi = null;
		if (dp.getDirection() == ParamDirection.IN) {
			RAccessId raId = (RAccessId) dp.getDataAccessId();
			pdi = FTM.getPhysicalDataInfoFromDataInstance(raId
					.getReadDataInstance());
		} else {
			if (dp.getDirection() == ParamDirection.OUT) {
				WAccessId waId = (WAccessId) dp.getDataAccessId();
				pdi = FTM.getPhysicalDataInfoFromDataInstance(waId
						.getWrittenDataInstance());
			} else {

				if (dp.getDirection() == ParamDirection.INOUT) {

					RWAccessId rwaId = (RWAccessId) dp.getDataAccessId();
					pdi = FTM.getPhysicalDataInfoFromDataInstance(rwaId
							.getWrittenDataInstance());
					//IMPORTANTISIMO.
					//pdi = null;
				}
			}
		}

		if (pdi == null || pdi.getLogicalFile() == null) {
			return false;

		} else {
			return true;
		}

	}

	public boolean existsDataInLevel(CacheType level, String key) {
		boolean existe = false;
		switch (level) {
		case LEVEL1:
			if (getCacheMemory(CacheType.LEVEL1) != null) {
				existe = getCacheMemory(CacheType.LEVEL1)
						.existsDataInCache(key);
			} else {
				existe = false;
			}
			break;
		case LEVEL2:
			if (getCacheMemory(CacheType.LEVEL2) != null) {
				existe = getCacheMemory(CacheType.LEVEL2)
						.existsDataInCache(key);
			} else {
				existe = false;
			}
			break;
		case LEVEL3:
			if (getCacheMemory(CacheType.LEVEL3) != null) {
				existe = getCacheMemory(CacheType.LEVEL3)
						.existsDataInCache(key);
			} else {
				existe = false;
			}
			break;
		default:
			existe = false;
			break;
		}

		return existe;
	}

	public Location getLocationOfLevel(CacheType level) {
		Location location = null;
		if (getCacheMemory(level) != null) {
			location = getCacheMemory(level).getLocation();
		}
		return location;
	}
}
