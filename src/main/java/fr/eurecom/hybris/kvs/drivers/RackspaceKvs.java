/**
 * Copyright (C) 2013 EURECOM (www.eurecom.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package fr.eurecom.hybris.kvs.drivers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStores;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.jclouds.openstack.swift.v1.blobstore.RegionScopedBlobStoreContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;

import fr.eurecom.hybris.Config;

public class RackspaceKvs extends Kvs {

    private static final Logger logger = LoggerFactory.getLogger(Config.LOGGER_NAME);

    private final static String RACKSPACE_US_ID = "rackspace-cloudfiles-us";
    /*
     * Supported regions for Cloud Files US:
     * - Dallas (DFW)
     * - Chicago (ORD)
     * - North Virginia (IAD)
     * - Hong Kong (HKG)
     * - Sydney, Australia (SYD)
     */
    private final static String REGION_ID = "IAD";
    
    private transient final BlobStore blobStore;

    public RackspaceKvs(String id, String accessKey, String secretKey,
                        String container, boolean enabled, int cost) throws IOException {
        super(id, container, enabled, cost);

        try {
            blobStore = ContextBuilder.newBuilder(RACKSPACE_US_ID)
                    .credentials(accessKey, secretKey)
                    .buildView(RegionScopedBlobStoreContext.class)
                    .blobStoreInRegion(REGION_ID);
        } catch (NoSuchElementException e) {
            logger.error("Could not initialize {} KvStore", id, e);
            throw new IOException(e);
        }

        this.createContainer();
    }

    public void put(String key, byte[] value) throws IOException {
        try {
            Blob blob = this.blobStore.blobBuilder(key)
                    .payload(ByteSource.wrap(value)).build();
            this.blobStore.putBlob(this.rootContainer, blob);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public byte[] get(String key) throws IOException {
        try {
            Blob blob = this.blobStore.getBlob(this.rootContainer, key);
            if (blob == null)
                return null;
            return ByteStreams.toByteArray(blob.getPayload().openStream());
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    public void delete(String key) throws IOException {
        try {
            this.blobStore.removeBlob(this.rootContainer, key);
        } catch (Exception ex) {
            throw new IOException(ex);
        }
    }

    public List<String> list() throws IOException {
        try {
            List<String> keys = new ArrayList<String>();
            for (StorageMetadata resourceMd :
                BlobStores.listAll(this.blobStore,
                        this.rootContainer,
                        ListContainerOptions.NONE))
                keys.add(resourceMd.getName());
            return keys;
        } catch (Exception ex) {
            throw new IOException(ex);
        }
    }

    private void createContainer() throws IOException {
        try {
            this.blobStore.createContainerInLocation(null, this.rootContainer);
        } catch (Exception ex) {
            throw new IOException(ex);
        }
    }

    public void shutdown() throws IOException { }
}
