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

package net.sf.ehcache.constructs.asynchronous;

import java.io.IOException;
import java.io.Serializable;

/**
 * A class which purports to be <code>Serializable</code> but will fail to serialize. Used for testing.
 *
 * @author <a href="mailto:gluck@thoughtworks.com">Greg Luck</a>
 * @version $Id: NonSerializable.java 796 2008-10-09 02:39:03Z gregluck $
 */
class NonSerializable implements Serializable {
    private void writeObject(java.io.ObjectOutputStream stream) throws IOException {
        stream.write(1);
        throw new IOException();
    }
}
