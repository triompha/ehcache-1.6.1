/**
 *  Copyright 2003-2008 Luck Consulting Pty Ltd
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
package net.sf.ehcache.hibernate;

import net.sf.ehcache.CacheManager;
import net.sf.ehcache.util.ClassLoaderUtil;
import org.hibernate.cache.Cache;
import org.hibernate.cache.CacheException;
import org.hibernate.cache.CacheProvider;
import org.hibernate.cache.Timestamper;

import java.net.URL;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Cache Provider plugin for Hibernate 3.2 and ehcache-1.2. New in this provider are ehcache support for multiple
 * Hibernate session factories, each with its own ehcache configuration, and non Serializable keys and values.
 * Ehcache-1.2 also has many other features such as cluster support and listeners, which can be used seamlessly simply
 * by configurion in ehcache.xml.
 * <p/>
 * Use <code>hibernate.cache.provider_class=net.sf.ehcache.hibernate.EhCacheProvider</code> in the Hibernate configuration
 * to enable this provider for Hibernate's second level cache.
 * <p/>
 * When configuring multiple ehcache CacheManagers, as you would where you have multiple Hibernate Configurations and
 * multiple SessionFactories, specify in each Hibernate configuration the ehcache configuration using
 * the property <code>net.sf.ehcache.configurationResourceName</code> An example to set an ehcach configuration
 * called ehcache-2.xml would be <code>net.sf.ehcache.configurationResourceName=/ehcache-2.xml</code>. If the leading
 * slash is not there one will be added. The configuration file will be looked for in the root of the classpath.
 * <p/>
 * Updated for ehcache-1.2. Note this provider requires ehcache-1.2.jar. Make sure ehcache-1.1.jar or earlier
 * is not in the classpath or it will not work.
 * <p/>
 * See http://ehcache.sf.net for documentation on ehcache
 * <p/>
 *
 * @author Greg Luck
 * @author Emmanuel Bernard
 * @version $Id: EhCacheProvider.java 978 2009-06-16 23:29:59Z gregluck $
 */
public final class EhCacheProvider implements CacheProvider {

    /**
     * The Hibernate system property specifying the location of the ehcache configuration file name.
     * <p/
     * If not set, ehcache.xml will be looked for in the root of the classpath.
     * <p/>
     * If set to say ehcache-1.xml, ehcache-1.xml will be looked for in the root of the classpath.
     */
    public static final String NET_SF_EHCACHE_CONFIGURATION_RESOURCE_NAME = "net.sf.ehcache.configurationResourceName";

    private static final Logger LOG = Logger.getLogger(EhCacheProvider.class.getName());

    private CacheManager manager;


    /**
     * Builds a Cache.
     * <p/>
     * Even though this method provides properties, they are not used.
     * Properties for EHCache are specified in the ehcache.xml file.
     * Configuration will be read from ehcache.xml for a cache declaration
     * where the name attribute matches the name parameter in this builder.
     *
     * @param name       the name of the cache. Must match a cache configured in ehcache.xml
     * @param properties not used
     * @return a newly built cache will be built and initialised
     * @throws org.hibernate.cache.CacheException
     *          inter alia, if a cache of the same name already exists
     */
    public final Cache buildCache(String name, Properties properties) throws CacheException {
        try {
            net.sf.ehcache.Ehcache cache = manager.getEhcache(name);
            if (cache == null) {
                LOG.log(Level.WARNING, "Could not find a specific ehcache configuration for cache named [" + name + "]; using defaults.");
                manager.addCache(name);
                cache = manager.getEhcache(name);
                EhCacheProvider.LOG.log(Level.FINE, "started EHCache region: " + name);
            }
            return new net.sf.ehcache.hibernate.EhCache(cache);
        } catch (net.sf.ehcache.CacheException e) {
            throw new CacheException(e);
        }
    }

    /**
     * Returns the next timestamp.
     */
    public final long nextTimestamp() {
        return Timestamper.next();
    }

    /**
     * Callback to perform any necessary initialization of the underlying cache implementation
     * during SessionFactory construction.
     * <p/>
     *
     * @param properties current configuration settings.
     */
    public final void start(Properties properties) throws CacheException {
        if (manager != null) {
            LOG.log(Level.WARNING, "Attempt to restart an already started EhCacheProvider. Use sessionFactory.close() " +
                    " between repeated calls to buildSessionFactory. Using previously created EhCacheProvider." +
                    " If this behaviour is required, consider using SingletonEhCacheProvider.");
            return;
        }
        try {
            String configurationResourceName = null;
            if (properties != null) {
                configurationResourceName = (String) properties.get(NET_SF_EHCACHE_CONFIGURATION_RESOURCE_NAME);
            }
            if (configurationResourceName == null || configurationResourceName.length() == 0) {
                manager = new CacheManager();
            } else {
                URL url = loadResource(configurationResourceName);
                manager = new CacheManager(url);
            }
        } catch (net.sf.ehcache.CacheException e) {
            if (e.getMessage().startsWith("Cannot parseConfiguration CacheManager. Attempt to create a new instance of " +
                    "CacheManager using the diskStorePath")) {
                throw new CacheException("Attempt to restart an already started EhCacheProvider. Use sessionFactory.close() " +
                        " between repeated calls to buildSessionFactory. Consider using SingletonEhCacheProvider. Error from " +
                        " ehcache was: " + e.getMessage());
            } else {
                throw e;
            }
        }
    }


    private URL loadResource(String configurationResourceName) {
        ClassLoader standardClassloader = ClassLoaderUtil.getStandardClassLoader();
        URL url = null;
        if (standardClassloader != null) {
            url = standardClassloader.getResource(configurationResourceName);
        }
        if (url == null) {
            url = this.getClass().getResource(configurationResourceName);
        }
        if (LOG.isLoggable(Level.FINE)) {
            LOG.log(Level.FINE, "Creating EhCacheProvider from a specified resource: "
                    + configurationResourceName + " Resolved to URL: " + url);
        }
        if (url == null) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "A configurationResourceName was set to " + configurationResourceName +
                        " but the resource could not be loaded from the classpath." +
                        "Ehcache will configure itself using defaults.");
            }
        }
        return url;
    }

    /**
     * Callback to perform any necessary cleanup of the underlying cache implementation
     * during SessionFactory.close().
     */
    public final void stop() {
        if (manager != null) {
            manager.shutdown();
            manager = null;
        }
    }


    /**
     * Not sure what this is supposed to do.
     *
     * @return false to be safe
     */
    public final boolean isMinimalPutsEnabledByDefault() {
        return false;
    }

}

