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
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import net.sf.ehcache.Status;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.event.RegisteredEventListeners;
import net.sf.ehcache.util.MemoryEfficientByteArrayOutputStream;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.io.StreamCorruptedException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ConcurrentModificationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A disk store implementation.
 * <p/>
 * As of ehcache-1.2 (v1.41 of this file) DiskStore has been changed to a mix of finer grained locking using synchronized collections
 * and synchronizing on the whole instance, as was the case with earlier versions.
 * <p/>
 * The DiskStore, as of ehcache-1.2.4, supports eviction using an LFU policy, if a maximum disk
 * store size is set. LFU uses statistics held at the Element level which survive moving between
 * maps in the MemoryStore and DiskStores.
 *
 * @author Adam Murdoch
 * @author Greg Luck
 * @author patches contributed: Ben Houston
 * @version $Id: DiskStore.java 1004 2009-07-26 05:26:06Z gregluck $
 */
public class DiskStore implements Store {

    /**
     * If the CacheManager needs to resolve a conflict with the disk path, it will create a
     * subdirectory in the given disk path with this prefix followed by a number. The presence of this
     * name is used to determined whether it makes sense for a persistent DiskStore to be loaded. Loading
     * persistent DiskStores will only have useful semantics where the diskStore path has not changed.
     */
    public static final String AUTO_DISK_PATH_DIRECTORY_PREFIX = "ehcache_auto_created";

    private static final Logger LOG = Logger.getLogger(DiskStore.class.getName());
    private static final int MS_PER_SECOND = 1000;
    private static final int SPOOL_THREAD_INTERVAL = 200;
    private static final int ESTIMATED_MINIMUM_PAYLOAD_SIZE = 512;
    private static final int ONE_MEGABYTE = 1048576;
    private static final int QUARTER_OF_A_SECOND = 250;

    private long expiryThreadInterval;

    private final String name;
    private boolean active;
    private RandomAccessFile randomAccessFile;

    private ConcurrentHashMap diskElements = new ConcurrentHashMap();
    private List freeSpace = Collections.synchronizedList(new ArrayList());
    //todo set concurrency level
    private ConcurrentHashMap spool = new ConcurrentHashMap();

    private Thread spoolAndExpiryThread;

    private Ehcache cache;

    /**
     * If persistent, the disk file will be kept
     * and reused on next startup. In addition the
     * memory store will flush all contents to spool,
     * and spool will flush all to disk.
     */
    private final boolean persistent;

    private final String diskPath;

    private File dataFile;


    /**
     * Used to persist elements
     */
    private File indexFile;

    private Status status;

    /**
     * The size in bytes of the disk elements
     */
    private long totalSize;

    /**
     * The maximum elements to allow in the disk file.
     */
    private final long maxElementsOnDisk;
    /**
     * Whether the cache is eternal
     */
    private boolean eternal;
    private int lastElementSize;
    private int diskSpoolBufferSizeBytes;

    // indicates to the spoolAndExpiryThread that it needs to write the index on next flush to disk.
    private final AtomicBoolean writeIndexFlag;
    private final Object writeIndexFlagLock;

    //The spoolAndExpiryThread keeps running until this is set to false;
    private volatile boolean spoolAndExpiryThreadActive;

    /**
     * Creates a disk store.
     *
     * @param cache    the {@link net.sf.ehcache.Cache} that the store is part of
     * @param diskPath the directory in which to create data and index files
     */
    public DiskStore(Ehcache cache, String diskPath) {
        status = Status.STATUS_UNINITIALISED;
        this.cache = cache;
        name = cache.getName();
        this.diskPath = diskPath;

        CacheConfiguration config = cache.getCacheConfiguration();
        expiryThreadInterval = config.getDiskExpiryThreadIntervalSeconds();
        persistent = config.isDiskPersistent();
        maxElementsOnDisk = config.getMaxElementsOnDisk();
        eternal = config.isEternal();
        diskSpoolBufferSizeBytes = cache.getCacheConfiguration().getDiskSpoolBufferSizeMB() * ONE_MEGABYTE;
        writeIndexFlag = new AtomicBoolean(false);
        writeIndexFlagLock = new Object();


        try {
            initialiseFiles();

            active = true;

            // Always start up the spool thread
            spoolAndExpiryThread = new SpoolAndExpiryThread();
            spoolAndExpiryThread.start();

            status = Status.STATUS_ALIVE;
        } catch (final Exception e) {
            // Cleanup on error
            dispose();
            throw new CacheException(name + "Cache: Could not create disk store. " +
                    "Initial cause was " + e.getMessage(), e);
        }
    }


    private void initialiseFiles() throws Exception {
        // Make sure the cache directory exists
        final File diskDir = new File(diskPath);
        if (diskDir.exists() && !diskDir.isDirectory()) {
            throw new Exception("Store directory \"" + diskDir.getCanonicalPath() + "\" exists and is not a directory.");
        }
        if (!diskDir.exists() && !diskDir.mkdirs()) {
            throw new Exception("Could not create cache directory \"" + diskDir.getCanonicalPath() + "\".");
        }

        dataFile = new File(diskDir, getDataFileName());
        indexFile = new File(diskDir, getIndexFileName());

        deleteIndexIfNoData();

        if (persistent) {
            //if diskpath contains auto generated string
            if (diskPath.indexOf(AUTO_DISK_PATH_DIRECTORY_PREFIX) != -1) {
                LOG.log(Level.WARNING, "Data in persistent disk stores is ignored for stores from automatically created directories"
                        + " (they start with " + AUTO_DISK_PATH_DIRECTORY_PREFIX + ").\n"
                        + "Remove diskPersistent or resolve the conflicting disk paths in cache configuration.\n"
                        + "Deleting data file " + getDataFileName());
                dataFile.delete();
            } else if (!readIndex()) {
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.log(Level.FINE, "Index file dirty or empty. Deleting data file " + getDataFileName());
                }
                dataFile.delete();
            }
        } else {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE, "Deleting data file " + getDataFileName());
            }
            dataFile.delete();
            indexFile = null;
        }

        // Open the data file as random access. The dataFile is created if necessary.
        randomAccessFile = new RandomAccessFile(dataFile, "rw");
    }

    private void deleteIndexIfNoData() {
        boolean dataFileExists = dataFile.exists();
        boolean indexFileExists = indexFile.exists();
        if (!dataFileExists && indexFileExists) {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE, "Matching data file missing for index file. Deleting index file " + getIndexFileName());
            }
            indexFile.delete();
        }
    }

    /**
     * Asserts that the store is active.
     */
    private void checkActive() throws CacheException {
        if (!active) {
            throw new CacheException(name + " Cache: The Disk store is not active.");
        }
    }

    /**
     * Gets an {@link Element} from the Disk Store.
     *
     * @return The element
     */
    public final synchronized Element get(final Object key) {
        try {
            checkActive();

            // Check in the spool.  Remove if present
            Element element;
            element = (Element) spool.remove(key);
            if (element != null) {
                element.updateAccessStatistics();
                return element;
            }

            // Check if the element is on disk
            final DiskElement diskElement = (DiskElement) diskElements.get(key);
            if (diskElement == null) {
                // Not on disk
                return null;
            }

            element = loadElementFromDiskElement(diskElement);
            if (element != null) {
                element.updateAccessStatistics();
            }
            return element;
        } catch (Exception exception) {
            LOG.log(Level.SEVERE, name + "Cache: Could not read disk store element for key " + key + ". Error was "
                    + exception.getMessage(), exception);
        }
        return null;
    }

    /**
     * An unsynchronized and very low cost check to see if a key is in the Store. No check is made to see if the Element is expired.
     *
     * @param key The Element key
     * @return true if found. If this method return false, it means that an Element with the given key is definitely not in the MemoryStore.
     *         If it returns true, there is an Element there. An attempt to get it may return null if the Element has expired.
     */
    public final boolean containsKey(Object key) {
        return diskElements.containsKey(key) || spool.containsKey(key);
    }

    private Element loadElementFromDiskElement(DiskElement diskElement) throws IOException, ClassNotFoundException {
        Element element;
        final byte[] buffer;
        synchronized (randomAccessFile) {
            // Load the element
            randomAccessFile.seek(diskElement.position);
            buffer = new byte[diskElement.payloadSize];
            randomAccessFile.readFully(buffer);
        }
        final ByteArrayInputStream instr = new ByteArrayInputStream(buffer);

        final ObjectInputStream objstr = new ObjectInputStream(instr) {
            /**
             * Overridden because of:
             * Bug 1324221 ehcache DiskStore has issues when used in Tomcat
             */
            protected Class resolveClass(ObjectStreamClass clazz) throws ClassNotFoundException, IOException {
                try {
                    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
                    return Class.forName(clazz.getName(), false, classLoader);
                } catch (ClassNotFoundException e) {
                    // Use the default as a fallback because of
                    // bug 1517565 - DiskStore loadElementFromDiskElement
                    return super.resolveClass(clazz);
                }
            }
        };
        element = (Element) objstr.readObject();
        objstr.close();
        return element;
    }

    /**
     * Gets an {@link Element} from the Disk Store, without updating statistics
     *
     * @return The element
     */
    public final synchronized Element getQuiet(final Object key) {
        try {
            checkActive();

            // Check in the spool.  Remove if present
            Element element;
            element = (Element) spool.remove(key);
            if (element != null) {
                //element.updateAccessStatistics(); Don't update statistics
                return element;
            }

            // Check if the element is on disk
            final DiskElement diskElement = (DiskElement) diskElements.get(key);
            if (diskElement == null) {
                // Not on disk
                return null;
            }

            element = loadElementFromDiskElement(diskElement);
            //element.updateAccessStatistics(); Don't update statistics
            return element;
        } catch (Exception e) {
            LOG.log(Level.SEVERE, name + "Cache: Could not read disk store element for key " + key
                    + ". Initial cause was " + e.getMessage(), e);
        }
        return null;
    }


    /**
     * Gets an Array of the keys for all elements in the disk store.
     *
     * @return An Object[] of {@link Serializable} keys
     */
    public final synchronized Object[] getKeyArray() {
        Set elementKeySet;
        elementKeySet = diskElements.keySet();
        Set spoolKeySet;
        spoolKeySet = spool.keySet();
        Set allKeysSet = new HashSet(elementKeySet.size() + spoolKeySet.size());
        allKeysSet.addAll(elementKeySet);
        allKeysSet.addAll(spoolKeySet);
        return allKeysSet.toArray();
    }

    /**
     * Returns the current store size in number of Elements.
     * This method may not measure data in transit from the spool to the disk.
     *
     * @see #getDataFileSize()
     */
    public final synchronized int getSize() {
        try {
            checkActive();
            int spoolSize = spool.size();
            int diskSize = diskElements.size();
            return spoolSize + diskSize;
        } catch (Exception e) {
            LOG.log(Level.SEVERE, name + "Cache: Could not determine size of disk store.. Initial cause was " + e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Gets the size of the store, in bytes.
     * <p/>
     * This method may be expensive to run, depending on implementation. Implementers may choose to return
     * an approximate size.
     * <p/>
     * This implementation returns the size of the data file, but does not take into account the memory in the spool.
     *
     * @return the approximate size of the store in bytes
     */
    public long getSizeInBytes() {
        return getDataFileSize();
    }

    /**
     * Returns the store status.
     */
    public final Status getStatus() {
        return status;
    }

    /**
     * Puts an element into the disk store.
     * <p/>
     * This method is not synchronized. It is however threadsafe. It uses fine-grained
     * synchronization on the spool.
     */
    public final void put(final Element element) {
        try {
            checkActive();

            // Spool the element
            if (spoolAndExpiryThread.isAlive()) {
                spool.put(element.getObjectKey(), element);
            } else {
                LOG.log(Level.SEVERE, name + "Cache: Elements cannot be written to disk store because the" +
                        " spool thread has died.");
                spool.clear();
            }

        } catch (Exception e) {
            LOG.log(Level.SEVERE, name + "Cache: Could not write disk store element for " + element.getObjectKey()
                    + ". Initial cause was " + e.getMessage(), e);
        }
    }

    /**
     * In some circumstances data can be written so quickly to the spool that the VM runs out of memory
     * while waiting for the spooling to disk.
     * <p/>
     * This is a very simple and quick test which estimates the spool size based on the last element's written size.
     *
     * @return true if the spool is not being cleared fast enough
     */
    public boolean bufferFull() {
        long estimatedSpoolSize = spool.size() * lastElementSize;
        boolean backedUp = estimatedSpoolSize > diskSpoolBufferSizeBytes;
        if (backedUp && LOG.isLoggable(Level.FINE)) {
            LOG.log(Level.FINE, "A back up on disk store puts occurred. Consider increasing diskSpoolBufferSizeMB for cache " + name);
        }
        return backedUp;

    }

    /**
     * Removes an item from the disk store.
     */
    public final synchronized Element remove(final Object key) {
        Element element;
        try {
            checkActive();

            // Remove the entry from the spool
            element = (Element) spool.remove(key);

            // Remove the entry from the file. Could be in both places.
            final DiskElement diskElement = (DiskElement) diskElements.remove(key);
            if (diskElement != null) {
                element = loadElementFromDiskElement(diskElement);
                freeBlock(diskElement);
            }
        } catch (Exception exception) {
            String message = name + "Cache: Could not remove disk store entry for key " + key
                    + ". Error was " + exception.getMessage();
            LOG.log(Level.SEVERE, message, exception);
            throw new CacheException(message);
        }
        return element;
    }

    /**
     * Marks a block as free.
     *
     * @param diskElement the DiskElement to move to the free space list
     */
    private void freeBlock(final DiskElement diskElement) {
        totalSize -= diskElement.payloadSize;
        diskElement.payloadSize = 0;

        //reset Element meta data
        diskElement.key = null;
        diskElement.hitcount = 0;
        diskElement.expiryTime = 0;

        freeSpace.add(diskElement);
    }

    /**
     * Remove all of the elements from the store.
     * <p/>
     * If there are registered <code>CacheEventListener</code>s they are notified of the expiry or removal
     * of the <code>Element</code> as each is removed.
     */
    public final synchronized void removeAll() {
        try {
            checkActive();

            // Ditch all the elements, and truncate the file
            spool = new ConcurrentHashMap();
            diskElements = new ConcurrentHashMap();
            freeSpace = Collections.synchronizedList(new ArrayList());
            totalSize = 0;
            synchronized (randomAccessFile) {
                randomAccessFile.setLength(0);
            }
            if (persistent) {
                indexFile.delete();
                indexFile.createNewFile();
            }
        } catch (Exception e) {
            // Clean up
            LOG.log(Level.SEVERE, name + " Cache: Could not rebuild disk store. Initial cause was " + e.getMessage(), e);
            dispose();
        }
    }

    /**
     * Shuts down the disk store in preparation for cache shutdown
     * <p/>
     * If a VM crash happens, the shutdown hook will not run. The data file and the index file
     * will be out of synchronisation. At initialisation we always delete the index file
     * after we have read the elements, so that it has a zero length. On a dirty restart, it still will have
     * and the data file will automatically be deleted, thus preserving safety.
     */
    public final void dispose() {

        if (!active) {
            return;
        }

        // Close the cache
        try {

            //set the write index flag. Ignored if not persistent
            flush();

            //tell the spool thread to spool down. It will loop one last time if flush was caled.
            spoolAndExpiryThreadActive = false;

            //interrupt the spoolAndExpiryThread if it is waiting to run again to get it to run now
            // Then wait for it to write
            spoolAndExpiryThread.interrupt();
            if (spoolAndExpiryThread != null) {
                spoolAndExpiryThread.join();
            }

            //Clear in-memory data structures
            spool.clear();
            diskElements.clear();
            freeSpace.clear();
            synchronized (randomAccessFile) {
                if (randomAccessFile != null) {
                    randomAccessFile.close();
                }
            }
            deleteFilesInAutoGeneratedDirectory();
            if (!persistent) {
                LOG.log(Level.FINE, "Deleting file " + dataFile.getName());
                dataFile.delete();
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, name + "Cache: Could not shut down disk cache. Initial cause was " + e.getMessage(), e);
        } finally {
            active = false;
            randomAccessFile = null;

            //release reference to cache
            cache = null;
        }
    }

    /**
     * Delete files in the auto generated directory. These are one time only for those who create multiple
     * CacheManagers with the same disk path settings. 
     */
    protected void deleteFilesInAutoGeneratedDirectory() {
        if (diskPath.indexOf(AUTO_DISK_PATH_DIRECTORY_PREFIX) != -1) {
            if (dataFile != null && dataFile.exists()) {
                LOG.log(Level.FINE, "Deleting file " + dataFile.getName());
                dataFile.delete();
            }
            if (indexFile != null && indexFile.exists()) {
                LOG.log(Level.FINE, "Deleting file " + indexFile.getName());
                indexFile.delete();
            }
            //try to delete the auto_createtimestamp directory. Will work when the last Disk Store deletes
            //the last files and the directory becomes empty.
            File dataDirectory = new File(diskPath);
            if (dataDirectory != null && dataDirectory.exists()) {
                if (dataDirectory.delete()) {
                    LOG.log(Level.FINE, "Deleted directory " + dataDirectory.getName());
                }
            }

        }
    }

    /**
     * Flush the spool if persistent, so we don't lose any data.
     * <p/>
     * This, as of ehcache-1.6.0, is an asynchronous operation. It will write the next time the spoolAndExpiryThread
     * runs. By default this is every 200ms.
     */
    public final void flush() {
        if (persistent) {
            synchronized (writeIndexFlagLock) {
                writeIndexFlag.set(true);
            }
        }
    }

    /**
     * both flushing and expiring contend for the same lock on diskElement, so
     * might as well do them sequentially in the one thread.
     * <p/>
     * This thread is protected from Throwables by only calling methods that guard
     * against these.
     */
    private void spoolAndExpiryThreadMain() {
        long nextExpiryTime = System.currentTimeMillis();
        while (spoolAndExpiryThreadActive || writeIndexFlag.get()) {

            try {
                //don't wait when we want to flish
                if (!writeIndexFlag.get()) {
                    Thread.sleep(SPOOL_THREAD_INTERVAL);
                }
            } catch (InterruptedException e) {
                //expected on shutdown
            }

            throwableSafeFlushSpoolIfRequired();

            if (!spoolAndExpiryThreadActive) {
                return;
            }
            nextExpiryTime = throwableSafeExpireElementsIfRequired(nextExpiryTime);
        }
    }

    private long throwableSafeExpireElementsIfRequired(long nextExpiryTime) {

        long updatedNextExpiryTime = nextExpiryTime;

        // Expire elements
        if (!eternal && System.currentTimeMillis() > nextExpiryTime) {
            try {
                updatedNextExpiryTime += expiryThreadInterval * MS_PER_SECOND;
                expireElements();
            } catch (Throwable e) {
                LOG.log(Level.SEVERE, name + " Cache: Could not expire elements from disk due to "
                        + e.getMessage() + ". Continuing...", e);
            }
        }
        return updatedNextExpiryTime;
    }

    private void throwableSafeFlushSpoolIfRequired() {
        synchronized (writeIndexFlagLock) {
            if (spool != null && (spool.size() != 0 || writeIndexFlag.get())) {
                // Write elements to disk
                try {
                    flushSpool();
                    if (persistent && writeIndexFlag.get()) {
                        try {
                            writeIndex();
                        } finally {
                            //make sure we set this to false to stop an infinite loop it if keeps failing
                            writeIndexFlag.set(false);
                        }

                    }
                } catch (Throwable e) {
                    LOG.log(Level.SEVERE, name + " Cache: Could not flush elements to disk due to "
                            + e.getMessage() + ". Continuing...", e);
                }
            }
        }
    }


    /**
     * Flushes all spooled elements to disk.
     */
    private synchronized void flushSpool() throws IOException {
        if (spool.size() == 0) {
            return;
        }

        Map copyOfSpool = swapSpoolReference();

        //does not guarantee insertion order
        Iterator valuesIterator = copyOfSpool.values().iterator();
        while (valuesIterator.hasNext()) {
            writeOrReplaceEntry(valuesIterator.next());
            valuesIterator.remove();
        }
    }

    private Map swapSpoolReference() {
        Map copyOfSpool = null;
        // Copy the reference of the old spool, not the contents. Avoid potential spike in memory usage
        copyOfSpool = spool;

        // use a new map making the reference swap above SAFE
        spool = new ConcurrentHashMap();
        return copyOfSpool;
    }


    private void writeOrReplaceEntry(Object object) throws IOException {
        Element element = (Element) object;
        if (element == null) {
            return;
        }
        final Serializable key = (Serializable) element.getObjectKey();
        removeOldEntryIfAny(key);
        if (maxElementsOnDisk > 0 && diskElements.size() >= maxElementsOnDisk) {
            evictLfuDiskElement();
        }
        writeElement(element, key);
    }


    private void writeElement(Element element, Serializable key) throws IOException {
        try {
            int bufferLength;
            long expirationTime = element.getExpirationTime();

            //try two times to Serialize. A ConcurrentModificationException can occur because Java's serialization
            //mechanism is not threadsafe and POJOs are seldom implemented in a threadsafe way.
            //e.g. we are serializing an ArrayList field while another thread somewhere in the application is appending to it.
            //The best we can do is try again and then give up.
            int retryCount = 0;
            MemoryEfficientByteArrayOutputStream buffer = null;
            try {
                buffer = MemoryEfficientByteArrayOutputStream.serialize(element, estimatedPayloadSize());
                retryCount++;
            } catch (ConcurrentModificationException e) {
                if (retryCount == 2) {
                    //give up.
                    if (LOG.isLoggable(Level.FINE)) {
                        LOG.log(Level.FINE, "Gave up trying to Serialize " + key, e);
                    }
                    return;
                } else {
                    //wait for the other thread(s) to finish
                    Thread.sleep(QUARTER_OF_A_SECOND);
                }
            }
            bufferLength = buffer.size();

            try {

                DiskElement diskElement = checkForFreeBlock(bufferLength);

                // Write the record
                synchronized (randomAccessFile) {
                    randomAccessFile.seek(diskElement.position);
                    randomAccessFile.write(buffer.toByteArray(), 0, bufferLength);
                }
                buffer = null;

                // Add to index, update stats
                diskElement.payloadSize = bufferLength;
                diskElement.key = key;
                diskElement.expiryTime = expirationTime;
                diskElement.hitcount = element.getHitCount();
                totalSize += bufferLength;
                lastElementSize = bufferLength;
                diskElements.put(key, diskElement);
            } catch (OutOfMemoryError e) {
                LOG.log(Level.SEVERE, "OutOfMemoryError on serialize: " + key);

            }

        } catch (Exception e) {
            // Catch any exception that occurs during serialization
            LOG.log(Level.SEVERE, name + "Cache: Failed to write element to disk '" + key
                    + "'. Initial cause was " + e.getMessage(), e);
        }

    }

    private int estimatedPayloadSize() {
        int size = 0;
        try {
            size = (int) (totalSize / diskElements.size());
        } catch (Exception e) {
            //
        }
        if (size <= 0) {
            size = ESTIMATED_MINIMUM_PAYLOAD_SIZE;
        }
        return size;
    }

    /**
     * Remove the old entry, if any
     *
     * @param key
     */
    private void removeOldEntryIfAny(Serializable key) {

        final DiskElement oldBlock;
        oldBlock = (DiskElement) diskElements.remove(key);
        if (oldBlock != null) {
            freeBlock(oldBlock);
        }
    }

    private DiskElement checkForFreeBlock(int bufferLength) throws IOException {
        DiskElement diskElement = findFreeBlock(bufferLength);
        if (diskElement == null) {
            diskElement = new DiskElement();
            synchronized (randomAccessFile) {
                diskElement.position = randomAccessFile.length();
            }
            diskElement.blockSize = bufferLength;
        }
        return diskElement;
    }

    /**
     * Writes the Index to disk on shutdown or flush
     * <p/>
     * The index consists of the elements Map and the freeSpace List
     * <p/>
     * Note that the store is locked for the entire time that the index is being written
     */
    private synchronized void writeIndex() throws IOException {

        ObjectOutputStream objectOutputStream = null;
        try {
            FileOutputStream fout = new FileOutputStream(indexFile);
            objectOutputStream = new ObjectOutputStream(fout);
            objectOutputStream.writeObject(diskElements);
            objectOutputStream.writeObject(freeSpace);
        } finally {
            if (objectOutputStream != null) {
                objectOutputStream.close();
            }
        }
    }

    /**
     * Reads Index to disk on startup.
     * <p/>
     * if the index file does not exist, it creates a new one.
     * <p/>
     * Note that the cache is locked for the entire time that the index is being written
     *
     * @return True if the index was read successfully, false otherwise
     */
    private synchronized boolean readIndex() throws IOException {
        ObjectInputStream objectInputStream = null;
        FileInputStream fin = null;
        boolean success = false;
        if (indexFile.exists()) {
            try {
                fin = new FileInputStream(indexFile);
                objectInputStream = new ObjectInputStream(fin);
                diskElements = (ConcurrentHashMap) objectInputStream.readObject();
                freeSpace = (List) objectInputStream.readObject();
                success = true;
            } catch (StreamCorruptedException e) {
                LOG.log(Level.SEVERE, "Corrupt index file. Creating new index.");
            } catch (IOException e) {
                //normal when creating the cache for the first time
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.log(Level.FINE, "IOException reading index. Creating new index. ");
                }
            } catch (ClassNotFoundException e) {
                LOG.log(Level.SEVERE, "Class loading problem reading index. Creating new index. Initial cause was " + e.getMessage(), e);
            } finally {
                try {
                    if (objectInputStream != null) {
                        objectInputStream.close();
                    } else if (fin != null) {
                        fin.close();
                    }
                } catch (IOException e) {
                    LOG.log(Level.SEVERE, "Problem closing the index file.");
                }

                if (!success) {
                    createNewIndexFile();
                }
            }
        } else {
            createNewIndexFile();
        }

        //Return the success flag
        return success;

    }

    private void createNewIndexFile() throws IOException {
        if (indexFile.exists()) {
            if (indexFile.delete()) {
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.log(Level.FINE, "Index file " + indexFile + " deleted.");
                }
            } else {
                throw new IOException("Index file " + indexFile + " could not deleted.");
            }
        }
        if (indexFile.createNewFile()) {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE, "Index file " + indexFile + " created successfully");
            }
        } else {
            throw new IOException("Index file " + indexFile + " could not created.");
        }
    }

    /**
     * Removes expired elements.
     * <p/>
     * Note that the DiskStore cannot efficiently expire based on TTI. It does it on TTL. However any gets out
     * of the DiskStore are check for both before return.
     */
    public void expireElements() {
        final long now = System.currentTimeMillis();

        // Clean up the spool
        for (Iterator iterator = spool.values().iterator(); iterator.hasNext();) {
            final Element element = (Element) iterator.next();
            if (element.isExpired()) {
                // An expired element
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.log(Level.FINE, name + "Cache: Removing expired spool element " + element.getObjectKey());
                }
                iterator.remove();
                notifyExpiryListeners(element);
            }
        }

        Element element = null;
        RegisteredEventListeners listeners = cache.getCacheEventNotificationService();
        // Clean up disk elements
        for (Iterator iterator = diskElements.entrySet().iterator(); iterator.hasNext();) {
            final Map.Entry entry = (Map.Entry) iterator.next();
            final DiskElement diskElement = (DiskElement) entry.getValue();

            if (now >= diskElement.expiryTime) {
                // An expired element
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.log(Level.FINE, name + "Cache: Removing expired spool element " + entry.getKey() + " from Disk Store");
                }

                iterator.remove();

                // only load the element from the file if there is a listener interested in hearing about its expiration
                if (listeners.hasCacheEventListeners()) {
                    try {
                        element = loadElementFromDiskElement(diskElement);
                        notifyExpiryListeners(element);
                    } catch (Exception exception) {
                        LOG.log(Level.SEVERE, name + "Cache: Could not remove disk store entry for " + entry.getKey()
                                + ". Error was " + exception.getMessage(), exception);
                    }
                }
                freeBlock(diskElement);
            }
        }
    }

    /**
     * It is enough that an element is expiring here. Notify even though there might be another
     * element with the same key elsewhere in the stores.
     *
     * @param element
     */
    private void notifyExpiryListeners(Element element) {
        cache.getCacheEventNotificationService().notifyElementExpiry(element, false);
    }

    /**
     * Allocates a free block.
     */
    private DiskElement findFreeBlock(final int length) {
        for (int i = 0; i < freeSpace.size(); i++) {
            final DiskElement element = (DiskElement) freeSpace.get(i);
            if (element.blockSize >= length) {
                freeSpace.remove(i);
                return element;
            }
        }
        return null;
    }

    /**
     * Returns a {@link String} representation of the {@link DiskStore}
     */
    public final String toString() {
        StringBuffer sb = new StringBuffer();
        sb.append("[ dataFile = ").append(dataFile.getAbsolutePath())
                .append(", active=").append(active)
                .append(", totalSize=").append(totalSize)
                .append(", status=").append(status)
                .append(", expiryThreadInterval = ").append(expiryThreadInterval)
                .append(" ]");
        return sb.toString();
    }

    /**
     * Generates a unique directory name for use in automatically creating a diskStorePath where there is a conflict.
     *
     * @return a path consisting of {@link #AUTO_DISK_PATH_DIRECTORY_PREFIX} followed by "_" followed by the current
     *         time as a long e.g. ehcache_auto_created_1149389837006
     */
    public static String generateUniqueDirectory() {
        return DiskStore.AUTO_DISK_PATH_DIRECTORY_PREFIX + "_" + System.currentTimeMillis();
    }


    /**
     * A reference to an on-disk elements.
     * <p/>
     * Copies of expiryTime and hitcount are held here as a performance optimisation, so
     * that we do not need to load the data from Disk to get this often used information.
     */
    private static final class DiskElement implements Serializable {

        private static final long serialVersionUID = -717310932566592289L;

        /**
         * the file pointer
         */
        private long position;

        /**
         * The size used for data.
         */
        private int payloadSize;

        /**
         * the size of this element.
         */
        private int blockSize;

        /**
         * The key this element is mapped with in DiskElements. This is only a reference
         * to the key. It is used in DiskElements and therefore the only memory cost is the
         * reference.
         */
        private Object key;

        /**
         * The expiry time in milliseconds
         */
        private long expiryTime;

        /**
         * The numbe of times the element has been requested and found in the cache.
         */
        private long hitcount;


        /**
         * @return the key of this object
         */
        public Object getObjectKey() {
            return key;
        }

        /**
         * @return the hit count for the element
         */
        public long getHitCount() {
            return hitcount;
        }
    }

    /**
     * A background daemon thread that writes objects to the file.
     */
    private final class SpoolAndExpiryThread extends Thread {

        public SpoolAndExpiryThread() {
            super("Store " + name + " Spool Thread");
            setDaemon(true);
            setPriority(Thread.NORM_PRIORITY);
            spoolAndExpiryThreadActive = true;
        }

        /**
         * RemoteDebugger thread method.
         */
        public final void run() {
            spoolAndExpiryThreadMain();
        }
    }

    /**
     * @return the total size of the data file and the index file, in bytes.
     */
    public final long getTotalFileSize() {
        return getDataFileSize() + getIndexFileSize();
    }

    /**
     * @return the size of the data file in bytes.
     */
    public final long getDataFileSize() {
        return dataFile.length();
    }

    /**
     * The design of the layout on the data file means that there will be small gaps created when DiskElements
     * are reused.
     *
     * @return the sparseness, measured as the percentage of space in the Data File not used for holding data
     */
    public final float calculateDataFileSparseness() {
        return 1 - ((float) getUsedDataSize() / (float) getDataFileSize());
    }

    /**
     * When elements are deleted, spaces are left in the file. These spaces are tracked and are reused
     * when new elements need to be written.
     * <p/>
     * This method indicates the actual size used for data, excluding holes. It can be compared with
     * {@link #getDataFileSize()} as a measure of fragmentation.
     */
    public final long getUsedDataSize() {
        return totalSize;
    }

    /**
     * @return the size of the index file, in bytes.
     */
    public final long getIndexFileSize() {
        if (indexFile == null) {
            return 0;
        } else {
            return indexFile.length();
        }
    }

    /**
     * Creates a file system safe data file. Any incidences of or <code>/</code> are replaced with <code>_</code>
     * so there are no unwanted side effects.
     *
     * @return the file name of the data file where the disk store stores data, without any path information.
     */
    public final String getDataFileName() {
        String safeName = name.replace('/', '_');
        return safeName + ".data";
    }

    /**
     * @return the disk path, which will be dependent on the operating system
     */
    public final String getDataFilePath() {
        return diskPath;
    }

    /**
     * @return the file name of the index file, which maintains a record of elements and their addresses
     *         on the data file, without any path information.
     */
    public final String getIndexFileName() {
        return name + ".index";
    }

    /**
     * The spool thread is started when the disk store is created.
     * <p/>
     * It will continue to run until the {@link #dispose()} method is called,
     * at which time it should be interrupted and then die.
     *
     * @return true if the spoolThread is still alive.
     */
    public final boolean isSpoolThreadAlive() {
        if (spoolAndExpiryThread == null) {
            return false;
        } else {
            return spoolAndExpiryThread.isAlive();
        }
    }

    private void evictLfuDiskElement() {
        synchronized (diskElements) {
            DiskElement diskElement = findRelativelyUnused();
            diskElements.remove(diskElement.key);
            notifyEvictionListeners(diskElement);
            freeBlock(diskElement);
        }
    }

    /**
     * Find a "relatively" unused disk element, but not the element just added.
     *
     * @return a DiskElement likely not to be in the bottom quartile of use
     */
    private DiskElement findRelativelyUnused() {
        DiskElement[] elements = sampleElements(diskElements);
        return leastHit(elements, null);
    }


    /**
     * Finds the least hit of the sampled elements provided
     *
     * @param sampledElements this should be a random subset of the population
     * @param justAdded       we never want to select the element just added. May be null.
     * @return the least hit
     */
    public static DiskElement leastHit(DiskElement[] sampledElements, DiskElement justAdded) {
        //edge condition when Memory Store configured to size 0
        if (sampledElements.length == 1 && justAdded != null) {
            return justAdded;
        }
        DiskElement lowestElement = null;
        for (DiskElement diskElement : sampledElements) {
            if (lowestElement == null) {
                if (!diskElement.equals(justAdded)) {
                    lowestElement = diskElement;
                }
            } else {
                if (diskElement.getHitCount() < lowestElement.getHitCount() && !diskElement.equals(justAdded)) {
                    lowestElement = diskElement;
                }
            }
        }
        return lowestElement;
    }

    /**
     * Uses random numbers to sample the entire map.
     *
     * @param map
     * @return an array of sampled elements
     */
    private DiskElement[] sampleElements(Map map) {
        int[] offsets = AbstractPolicy.generateRandomSample(map.size());
        DiskElement[] elements = new DiskElement[offsets.length];
        Iterator iterator = map.values().iterator();
        for (int i = 0; i < offsets.length; i++) {
            for (int j = 0; j < offsets[i]; j++) {
                iterator.next();
            }
            elements[i] = (DiskElement) iterator.next();
        }
        return elements;
    }

    private void notifyEvictionListeners(DiskElement diskElement) {
        RegisteredEventListeners listeners = cache.getCacheEventNotificationService();
        // only load the element from the file if there is a listener interested in hearing about its expiration
        if (listeners.hasCacheEventListeners()) {
            Element element = null;
            try {
                element = loadElementFromDiskElement(diskElement);
                cache.getCacheEventNotificationService().notifyElementEvicted(element, false);
            } catch (Exception exception) {
                LOG.log(Level.SEVERE, name + "Cache: Could not notify disk store eviction of " + element.getObjectKey() +
                        ". Error was " + exception.getMessage(), exception);
            }
        }

    }


    /**
     * @return the current eviction policy. This may not be the configured policy, if it has been
     *         dynamically set.
     * @see #setEvictionPolicy(Policy)
     */
    public Policy getEvictionPolicy() {
        return new LfuPolicy();
    }

    /**
     * Sets the eviction policy strategy. The Store will use a policy at startup. The store may allow changing
     * the eviction policy strategy dynamically. Otherwise implementations will throw an exception if this method
     * is called.
     *
     * @param policy the new policy
     */
    public void setEvictionPolicy(Policy policy) {
        throw new UnsupportedOperationException("Disk store only uses LFU.");
    }


}
