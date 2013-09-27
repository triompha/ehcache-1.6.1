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

package net.sf.ehcache.distribution;


import net.sf.ehcache.AbstractCacheTest;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import org.junit.Before;
import org.junit.Test;

import java.rmi.Naming;
import java.util.Date;

/**
 * Note these tests need a live network interface running in multicast mode to work
 *
 * @author Greg Luck
 * @version $Id: RMIDistributedCacheTest.java 943 2009-05-08 03:18:59Z gregluck $
 */
public class RMIDistributedCacheTest {


    /**
     * manager
     */
    protected CacheManager manager;
    /**
     * the cache name we wish to test
     */
    private String cacheName1 = "sampleCache1";
    private String cacheName2 = "sampleCache2";
    /**
     * the cache we wish to test
     */
    private Ehcache sampleCache1;


    private String hostName = "localhost";

    private Integer port = new Integer(40000);
    private Integer remoteObjectPort = new Integer(0);
    private Element element;
    private CachePeer cache1Peer;
    private CachePeer cache2Peer;

    /**
     * {@inheritDoc}
     *
     * @throws Exception
     */
    @Before
    public void setUp() throws Exception {

        manager = CacheManager.create(AbstractCacheTest.TEST_CONFIG_DIR + "distribution/ehcache-distributed1.xml");
        MulticastKeepaliveHeartbeatSender.setHeartBeatInterval(1000);
        sampleCache1 = manager.getCache(cacheName1);
        sampleCache1.removeAll();
        element = new Element("key", new Date());
        sampleCache1.put(element);
        CacheManagerPeerListener cacheManagerPeerListener =
                new RMICacheManagerPeerListener(hostName, port, remoteObjectPort, manager, new Integer(2000));
        cacheManagerPeerListener.init();
        cache1Peer = (CachePeer) Naming.lookup(createNamingUrl() + cacheName1);
        cache2Peer = (CachePeer) Naming.lookup(createNamingUrl() + cacheName2);

    }

    /**
     * Shutdown the cache
     */
    @After
    public void tearDown() throws InterruptedException {

        Thread.sleep(10);
        manager.shutdown();
        int i = 0;
    }

    /**
     * Getting an RMI Server going is a big deal
     */
    @Test
    public void testCreation() throws Exception {
        assertNotNull(cache1Peer);
        assertNotNull(cache2Peer);
    }

    /**
     * The use of one-time registry creation and Naming.rebind should mean we can create as many listeneres as we like.
     * They will simply replace the ones that were there.
     */
    @Test
    public void testMultipleCreationOfRMIServers() throws Exception {
        RMICacheManagerPeerListener[] listeners = new RMICacheManagerPeerListener[100];
        for (int i = 0; i < 100; i++) {
            listeners[i] = new RMICacheManagerPeerListener(hostName, port, remoteObjectPort, manager, new Integer(2000));
        }
        cache1Peer = (CachePeer) Naming.lookup(createNamingUrl() + cacheName1);
        assertNotNull(cache1Peer);

        for (int i = 0; i < 100; i++) {
            listeners[i].dispose();
        }
    }


    /**
     * Same as the above with remoteObjectPort the same.
     */
    @Test
    public void testMultipleCreationOfRMIServersWithSpecificRemoteObjectPort() throws Exception {
        RMICacheManagerPeerListener[] listeners = new RMICacheManagerPeerListener[100];
        for (int i = 0; i < 100; i++) {
            listeners[i] = new RMICacheManagerPeerListener(hostName, port, new Integer(45000), manager, new Integer(2000));
        }
        cache1Peer = (CachePeer) Naming.lookup(createNamingUrl() + cacheName1);
        assertNotNull(cache1Peer);
        cache1Peer.put(new Element(1, 4));

        for (int i = 0; i < 100; i++) {
            listeners[i].dispose();
        }
    }


    private String createNamingUrl() {
        return "//" + hostName + ":" + port + "/";
    }

    /**
     * Attempts to get the cache name
     *
     * @throws java.net.MalformedURLException
     * @throws java.rmi.NotBoundException
     * @throws java.rmi.RemoteException
     */
    @Test
    public void testGetName() throws Exception {
        String lookupCacheName = cache1Peer.getName();
        assertEquals(cacheName1, lookupCacheName);
        lookupCacheName = cache2Peer.getName();
        assertEquals(cacheName2, lookupCacheName);
    }


}
