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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpStatus;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.Region;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.model.StorageClass;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerConfiguration;
import com.amazonaws.services.s3.transfer.Upload;
import com.google.common.io.ByteStreams;

public class AmazonKvs extends Kvs {

    private transient final AmazonS3 s3;
    private transient final TransferManager tm;

    public AmazonKvs(String id, final String accessKey, final String secretKey,
            String container, boolean enabled, int cost) throws IOException {
        super(id, container, enabled, cost);

        BasicAWSCredentials credentials = new BasicAWSCredentials(accessKey, secretKey);
        this.s3 = new AmazonS3Client(credentials);

        this.tm = new TransferManager(credentials);
        TransferManagerConfiguration tmc = new TransferManagerConfiguration();
        tmc.setMultipartUploadThreshold(30000000L);  // 30 MB
        tmc.setMinimumUploadPartSize(10000000);     // 10 MB
        this.tm.setConfiguration(tmc);

        this.createContainer();
    }

    public void put(String key, byte[] value) throws IOException {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(value);

            ObjectMetadata om = new ObjectMetadata();
            om.setContentLength(value.length);

            PutObjectRequest request = new PutObjectRequest(this.rootContainer, key, bais, om);
            request.setStorageClass(StorageClass.ReducedRedundancy);

            Upload upload = this.tm.upload(request);   // NB: asynchronous, returns immediately
            upload.waitForCompletion();
        } catch (AmazonClientException | InterruptedException e) {
            throw new IOException(e);
        }
    }

    public byte[] get(String key) throws IOException {
        try {
            S3Object object = this.s3.getObject(new GetObjectRequest(this.rootContainer, key));
            return ByteStreams.toByteArray(object.getObjectContent());
        } catch (AmazonClientException e) {

            if (e instanceof AmazonS3Exception) {
                AmazonS3Exception as3e = (AmazonS3Exception) e;
                if (as3e.getStatusCode() == HttpStatus.SC_NOT_FOUND)
                    return null;
            }

            throw new IOException(e);
        }
    }

    public void delete(String key) throws IOException {
        try {
            this.s3.deleteObject(this.rootContainer, key);
        } catch (AmazonClientException e) {
            throw new IOException(e);
        }
    }

    public List<String> list() throws IOException {
        try {
            List<String> keys = new ArrayList<String>();
            ObjectListing objectListing = this.s3.listObjects(this.rootContainer);
            boolean loop = false;

            do {
                for (S3ObjectSummary objectSummary : objectListing.getObjectSummaries())
                    keys.add(objectSummary.getKey());

                if (objectListing.isTruncated()) {
                    objectListing = this.s3.listNextBatchOfObjects(objectListing);
                    loop = true;
                } else
                    loop = false;

            } while (loop);

            return keys;
        } catch (AmazonClientException e) {
            throw new IOException(e);
        }
    }

    private void createContainer() throws IOException {
        try {
            if (!this.s3.doesBucketExist(this.rootContainer))
                this.s3.createBucket(this.rootContainer, Region.EU_Ireland);    // XXX hardcoded bucket location
        } catch (AmazonClientException e) {
            throw new IOException(e);
        }
    }

    public void shutdown() throws IOException {
        this.tm.shutdownNow();
    }
}
