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

package net.sf.ehcache.store;

import net.sf.ehcache.CacheException;
import net.sf.ehcache.Element;
import net.sf.ehcache.Status;

import java.io.IOException;

/**
 * This is the interface for all stores. A store is a physical counterpart to a cache, which
 * is a logical concept.
 *
 * @author Greg Luck
 * @version $Id: Store.java 967 2009-06-12 00:55:44Z gregluck $
 */
public interface Store {

    /**
     * Puts an item into the store.
     */
    void put(Element element) throws CacheException;

    /**
     * Gets an item from the cache.
     */
    Element get(Object key);

    /**
     * Gets an {@link Element} from the Store, without updating statistics
     *
     * @return The element
     */
    public Element getQuiet(final Object key);

    /**
     * Gets an Array of the keys for all elements in the disk store.
     *
     * @return An Object[] of {@link java.io.Serializable} keys
     */
    public Object[] getKeyArray();

    /**
     * Removes an item from the cache.
     *
     * @since signature changed in 1.2 from boolean to Element to support notifications
     */
    Element remove(Object key);

    /**
     * Remove all of the elements from the store.
     * <p/>
     * If there are registered <code>CacheEventListener</code>s they are notified of the expiry or removal
     * of the <code>Element</code> as each is removed.
     */
    void removeAll() throws CacheException;

    /**
     * Prepares for shutdown.
     */
    void dispose();

    /**
     * Returns the current store size
     * @return the count of the Elements in the Store
     */
    int getSize();

    /**
     * Gets the size of the store, in bytes.
     * <p/>
     * This method may be expensive to run, depending on implementation. Implementers may choose to return
     * an approximate size.
     *
     * @return the approximate size of the store in bytes
     */
    long getSizeInBytes();

    /**
     * Returns the cache status.
     */
    Status getStatus();


    /**
     * A check to see if a key is in the Store.
     *
     * @param key The Element key
     * @return true if found. No check is made to see if the Element is expired.
     *  1.2
     */
    boolean containsKey(Object key);
    
    /**
     * Expire all elements.
     */
    public void expireElements();
    
    /**
     * Flush elements to persistent store.
     * @throws IOException if any IO error occurs
     */
    void flush() throws IOException;

    /**
     * Some store types, such as the disk stores can fill their write buffers if puts
     * come in too fast. The thread will wait for a short time before checking again.
     * @return true if the store write buffer is backed up.
     */
    boolean bufferFull();

    /**
     * @return the current eviction policy. This may not be the configured policy, if it has been
     *         dynamically set.
     * @see #setEvictionPolicy(Policy)
     */
    Policy getEvictionPolicy();

    /**
     * Sets the eviction policy strategy. The Store will use a policy at startup. The store may allow changing
     * the eviction policy strategy dynamically. Otherwise implementations will throw an exception if this method
     * is called.
     *
     * @param policy the new policy
     */
    void setEvictionPolicy(Policy policy);

}
