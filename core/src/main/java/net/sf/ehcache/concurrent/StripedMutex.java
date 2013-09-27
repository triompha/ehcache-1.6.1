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

package net.sf.ehcache.concurrent;

import net.sf.ehcache.CacheException;

/**
 * Provides a number of mutexes which allow fine-grained concurrency. Rather than locking a cache or a store,
 * the individual elements or constituent objects can be locked. This dramatically increases
 * the possible concurrency.
 * <p/>
 * The more stripes, the higher the concurrency. To be threadsafe, the instance of StripedMutex needs to be
 * maintained for the entire life of the cache or store, so there is some added memory use.
 * <p/>
 * Though a new class, this code has been refactored from <code>BlockingCache</code>, where it has been in use
 * in highly concurrent production environments for years.
 * <p/>
 * Based on the lock striping concept from Brian Goetz. See Java Concurrency in Practice 11.4.3
 * @author Greg Luck
 */
public class StripedMutex {


    /**
     * The default number of locks to use. Must be a power of 2.
     * <p/>
     * The choice of 2048 enables 2048 concurrent operations per cache or cache store, which should be enough for most
     * uses.
     */
    public static final int DEFAULT_NUMBER_OF_MUTEXES = 2048;

    /**
     * The array of mutexes
     */
    protected final Mutex[] mutexes;

    private int numberOfStripes;

    /**
     * Constructs a striped mutex with the default 2048 stripes.
     */
    public StripedMutex() {

        this.numberOfStripes = DEFAULT_NUMBER_OF_MUTEXES;
        mutexes = new Mutex[numberOfStripes];

        for (int i = 0; i < numberOfStripes; i++) {
            mutexes[i] = new Mutex();
        }
    }

    /**
     * Constructs a striped mutex with the default 2048 stripes.
     * <p/>
     * The number of stripes determines the number of concurrent operations per cache or cache store.
     * @param numberOfStripes - must be a factor of two
     */
    public StripedMutex(int numberOfStripes) {
        if (numberOfStripes % 2 != 0) {
            throw new CacheException("Cannot create a StripedMutex with an odd number of stripes");
        }

        if (numberOfStripes == 0) {
            throw new CacheException("A zero size StripedMutex does not have useful semantics.");
        }

        this.numberOfStripes = numberOfStripes;
        mutexes = new Mutex[numberOfStripes];

        for (int i = 0; i < numberOfStripes; i++) {
            mutexes[i] = new Mutex();
        }
    }

    /**
     * Gets the Mutex Stripe to use for a given key.
     * <p/>
     * This lookup must always return the same Mutex for a given key.
     * <p/>
     * @param key the key
     * @return one of a limited number of Mutexes.
     */
    public Mutex getMutexForKey(final Object key) {
        int lockNumber = ConcurrencyUtil.selectLock(key, numberOfStripes);
        return mutexes[lockNumber];
    }
}
