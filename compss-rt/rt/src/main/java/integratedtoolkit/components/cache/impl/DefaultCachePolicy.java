/**
 * 
 */
package integratedtoolkit.components.cache.impl;

import integratedtoolkit.components.cache.CachePolicy;
import integratedtoolkit.components.impl.FileTransferManager;
import integratedtoolkit.types.Parameter;
import integratedtoolkit.types.Parameter.DependencyParameter;
import integratedtoolkit.types.cache.CacheMemory.CacheType;
import integratedtoolkit.util.CacheManager;

/**
 * @author gabriel
 *
 */
public class DefaultCachePolicy implements CachePolicy {

	@Override
	public CacheType selectCacheLevel(Parameter parametro,
			CacheManager cacheManager, FileTransferManager FTM) {
		if (parametro instanceof DependencyParameter) {
			DependencyParameter dp = (DependencyParameter) parametro;

			// Solo si existe el fichero en el disco, buscamos su tamaño. Si no
			// existe lo grabamos en disco para curarnos en salud
			if (cacheManager.existsPhysicalFile(dp, FTM)) {
				return CacheType.LEVEL1;
			} else {
				return CacheType.NONE;
			}
		} else {
			return CacheType.NONE;
		}
	}

}
