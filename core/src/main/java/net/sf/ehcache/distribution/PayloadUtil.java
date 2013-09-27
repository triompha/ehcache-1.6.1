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


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * This class provides utility methods for assembling and disassembling a heartbeat payload.
 * <p/>
 * Care is taken to fit the payload into the MTU of ethernet, which is 1500 bytes.
 * The algorithms in this class are capable of creating payloads for CacheManagers containing
 * approximately 500 cache peers to be replicated.
 *
 * @author <a href="mailto:gluck@thoughtworks.com">Greg Luck</a>
 * @version $Id: PayloadUtil.java 978 2009-06-16 23:29:59Z gregluck $
 */
final class PayloadUtil {

    /**
     * The maximum transmission unit. This varies by link layer. For ethernet, fast ethernet and
     * gigabit ethernet it is 1500 bytes, the value chosen.
     * <p/>
     * Payloads are limited to this so that there is no fragmentation and no necessity for a complex
     * reassembly protocol.
     */
    public static final int MTU = 1500;

    /**
     * Delmits URLS sent via heartbeats over sockets
     */
    public static final String URL_DELIMITER = "|";

    private static final Logger LOG = Logger.getLogger(PayloadUtil.class.getName());


    /**
     * Utility class therefore precent construction
     */
    private PayloadUtil() {
        //noop
    }

    /**
     * Assembles a list of URLs
     *
     * @param localCachePeers
     * @return an uncompressed payload with catenated rmiUrls.
     */
    public static byte[] assembleUrlList(List localCachePeers) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < localCachePeers.size(); i++) {
            CachePeer cachePeer = (CachePeer) localCachePeers.get(i);
            String rmiUrl = null;
            try {
                rmiUrl = cachePeer.getUrl();
            } catch (RemoteException e) {
                LOG.log(Level.SEVERE, "This should never be thrown as it is called locally");
            }
            if (i != localCachePeers.size() - 1) {
                sb.append(rmiUrl).append(URL_DELIMITER);
            } else {
                sb.append(rmiUrl);
            }
        }
        if (LOG.isLoggable(Level.FINE)) {
            LOG.log(Level.FINE, "Cache peers for this CacheManager to be advertised: " + sb);
        }
        return sb.toString().getBytes();
    }

    /**
     * Gzips a byte[]. For text, approximately 10:1 compression is achieved.
     * @param ungzipped the bytes to be gzipped
     * @return gzipped bytes
     */
    public static byte[] gzip(byte[] ungzipped) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            final GZIPOutputStream gzipOutputStream = new GZIPOutputStream(bytes);
            gzipOutputStream.write(ungzipped);
            gzipOutputStream.close();
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Could not gzip " + ungzipped);
        }
        return bytes.toByteArray();
    }

    /**
     * The fastest Ungzip implementation. See PageInfoTest in ehcache-constructs.
     * A high performance implementation, although not as fast as gunzip3.
     * gunzips 100000 of ungzipped content in 9ms on the reference machine.
     * It does not use a fixed size buffer and is therefore suitable for arbitrary
     * length arrays.
     *
     * @param gzipped
     * @return a plain, uncompressed byte[]
     */
    public static byte[] ungzip(final byte[] gzipped) {
        byte[] ungzipped = new byte[0];
        try {
            final GZIPInputStream inputStream = new GZIPInputStream(new ByteArrayInputStream(gzipped));
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(gzipped.length);
            final byte[] buffer = new byte[PayloadUtil.MTU];
            int bytesRead = 0;
            while (bytesRead != -1) {
                bytesRead = inputStream.read(buffer, 0, PayloadUtil.MTU);
                if (bytesRead != -1) {
                    byteArrayOutputStream.write(buffer, 0, bytesRead);
                }
            }
            ungzipped = byteArrayOutputStream.toByteArray();
            inputStream.close();
            byteArrayOutputStream.close();
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Could not ungzip. Heartbeat will not be working. " + e.getMessage());
        }
        return ungzipped;
    }

    

}
