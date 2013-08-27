package fr.eurecom.hybris.kvs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

import org.jclouds.rest.AuthorizationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fr.eurecom.hybris.Config;
import fr.eurecom.hybris.kvs.drivers.AmazonKvs;
import fr.eurecom.hybris.kvs.drivers.AzureKvs;
import fr.eurecom.hybris.kvs.drivers.GoogleKvs;
import fr.eurecom.hybris.kvs.drivers.Kvs;
import fr.eurecom.hybris.kvs.drivers.RackspaceKvs;
import fr.eurecom.hybris.kvs.drivers.TransientKvs;


/**
 * KvsManager exposes a simple common cloud storage API
 * for saving, retrieving and deleting data
 * on the supported cloud storage services.
 * @author p.viotti
 */
public class KvsManager {

    private static Logger logger = LoggerFactory.getLogger(Config.LOGGER_NAME);

    private final Config conf;
    private final List<Kvs> kvStores;                   // kvStores sorted by cost and latency

    private final int LATENCY_TEST_DATA_SIZE = 100;     // default value: 100kB

    private enum KvsId {
        AMAZON,
        AZURE,
        GOOGLE,
        RACKSPACE,
        TRANSIENT;
    };

    public KvsManager(String accountsFile, String container, boolean testLatency) throws IOException {

        this.conf = Config.getInstance();
        this.conf.loadAccountsProperties(accountsFile);
        this.kvStores = new ArrayList<Kvs>();
        String[] accountIds = this.conf.getAccountsIds();

        Kvs kvStore;
        String accessKey, secretKey;
        boolean enabled;
        int cost;

        for (String accountId : accountIds) {
            accessKey = this.conf.getAccountsProperty( String.format(Config.C_AKEY, accountId));
            secretKey = this.conf.getAccountsProperty( String.format(Config.C_SKEY, accountId) );
            enabled = Boolean.parseBoolean( this.conf.getAccountsProperty( String.format(Config.C_ENABLED, accountId)) );
            cost = Integer.parseInt( this.conf.getAccountsProperty( String.format(Config.C_COST, accountId) ));

            try {
                switch (KvsId.valueOf(accountId.toUpperCase())) {
                    case AMAZON:
                        kvStore = new AmazonKvs(accountId, accessKey, secretKey,
                                                        container, enabled, cost);
                        break;
                    case AZURE:
                        kvStore = new AzureKvs(accountId, accessKey, secretKey,
                                                        container, enabled, cost);
                        break;
                    case GOOGLE:
                        kvStore = new GoogleKvs(accountId, accessKey, secretKey,
                                                        container, enabled, cost);
                        break;
                    case RACKSPACE:
                        kvStore = new RackspaceKvs(accountId, accessKey, secretKey,
                                                            container, enabled, cost);
                        break;
                    case TRANSIENT:
                        kvStore = new TransientKvs(accountId, accessKey, secretKey,
                                                          container, enabled, cost);
                        break;
                    default:
                        logger.error("Hybris could not find any driver for {} KvStore", accountId);
                        continue;
                }
            } catch (IllegalArgumentException e) {
                logger.error("Hybris could not find any driver for {} KvStore", accountId);
                throw new IOException(e);
            }

            this.kvStores.add(kvStore);
        }

        if (testLatency)
            this.testLatencyAndSortClouds(this.LATENCY_TEST_DATA_SIZE);
    }


    public List<Kvs> getKvStores()   { return this.kvStores; }

    /**
     * Worker thread class in charge of asynchronously performing
     * write operation on cloud stores.
     * @author p.viotti
     */
    public class KvsPutWorker implements Callable<Kvs> {

        private final Kvs kvStore;
        private final String key;
        private final byte[] value;

        public KvsPutWorker(Kvs kvStore, String key, byte[] value) {
            this.kvStore = kvStore;
            this.key = key;
            this.value = value;
        }

        public Kvs call() {
            try {
                KvsManager.this.put(this.kvStore, this.key, this.value);
                return this.kvStore;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * Worker thread class in charge of testing read and
     * write latencies of a KvStore.
     * @author p.viotti
     */
    public class LatencyTester implements Runnable {

        private final Kvs kvStore;

        private final String TEST_KEY = "latency_test-";
        private final byte[] testData;
        private final Random random = new Random();

        public LatencyTester(Kvs kvStore, int testDataSize) {
            this.kvStore = kvStore;
            if (testDataSize == 0)
                testDataSize = KvsManager.this.LATENCY_TEST_DATA_SIZE;
            this.testData = this.generatePayload(testDataSize, (byte) 'x');
        }

        private byte[] generatePayload(int size, byte b) {
            byte[] array = new byte[size * 1000];
            this.random.nextBytes(array);
            return array;
        }

        public void run() {

            String testKey = this.TEST_KEY + new Random().nextInt(1000);
            long start, end = 0;

            // Write
            try {
                start = System.currentTimeMillis();
                KvsManager.this.put(this.kvStore, testKey, this.testData);
                end = System.currentTimeMillis();
                this.kvStore.setWriteLatency(end - start);
            } catch (Exception e) {
                this.kvStore.setWriteLatency(Integer.MAX_VALUE);
                if (e instanceof AuthorizationException)
                    this.kvStore.setEnabled(false);
                    return;
            }

            // Read
            byte[] retrieved = null;
            try {
                start = System.currentTimeMillis();
                retrieved = KvsManager.this.get(this.kvStore, testKey);
                end = System.currentTimeMillis();
            } catch (Exception e) {
                this.kvStore.setReadLatency(Integer.MAX_VALUE);
                if (e instanceof AuthorizationException)
                    this.kvStore.setEnabled(false);
            }
            if (retrieved == null || !Arrays.equals(this.testData, retrieved))
                this.kvStore.setReadLatency(Integer.MAX_VALUE);
            else
                this.kvStore.setReadLatency(end - start);

            // Clean up
            try {
                KvsManager.this.delete(this.kvStore, testKey);
            } catch (IOException e) {  }
        }
    }


    /* ---------------------------------------------------------------------------------------
                                            Public APIs
       --------------------------------------------------------------------------------------- */


   public void put(Kvs kvStore, String key, byte[] data) throws IOException {
       try {
           kvStore.put(key, data);
       } catch (IOException e) {
           logger.warn("Could not put " + key + " on " + kvStore.getId(), e);
           throw e;
       }
    }


    public byte[] get(Kvs kvStore, String key) throws IOException {
        try {
            byte[] value = kvStore.get(key);
            if (value == null)
                logger.warn("Could not find key {} in {}", key, kvStore.getId());
            return value;
        } catch (IOException e) {
            logger.warn("Could not get " + key + " from " + kvStore.getId(), e);
            throw e;
        }
    }


    public void delete(Kvs kvStore, String key) throws IOException {
        try {
            kvStore.delete(key);
        } catch (IOException e) {
            logger.warn("Could not delete " + key + " from " + kvStore.getId(), e);
            throw e;
        }
    }


    public List<String> list(Kvs kvStore) throws IOException {
        try {
            return kvStore.list();
        } catch (IOException e) {
            logger.warn("Could not list keys in {}", kvStore.getId(), e);
            throw e;
        }
    }


    public void shutdown(Kvs kvStore) {
        try {
            kvStore.shutdown();
        } catch (IOException e) {
            logger.warn("Error during {} KvStore shutdown", kvStore.getId(), e);
        }
    }


    public void testLatencyAndSortClouds(int testDataSize) {
        logger.info("Performing latency tests on cloud kvStores...");
        this.testLatency(testDataSize);
        Collections.sort(this.kvStores);  // Sort kvStores according to cost and latency (see Driver.compareTo())
        logger.debug("Cloud kvStores sorted by performance/cost metrics:");
        for(Kvs kvs : this.kvStores)
            logger.debug("\t* {}", kvs.toVerboseString());
    }


    /**
     * Empty the data storage root container.
     * ATTENTION: it erases all data stored in the root container!
     * @param kvStore the cloud storage provider
     * @throws IOException
     */
    public void emptyStorageContainer(Kvs kvStore) throws IOException {
        List<String> keys = kvStore.list();
        for (String key : keys)
            try {
                kvStore.delete(key);
            } catch (IOException e) { }
    }

    /* ---------------------------------------------------------------------------------------
                                        Private methods
       --------------------------------------------------------------------------------------- */


    private void testLatency(int testDataSize) {
        ExecutorService executor = Executors.newFixedThreadPool(this.kvStores.size());
        List<FutureTask<Object>> futureLst = new ArrayList<FutureTask<Object>>(this.kvStores.size());
        for (Kvs kvStore : this.kvStores) {
            FutureTask<Object> f = new FutureTask<Object>(new LatencyTester(kvStore, testDataSize), null);
            futureLst.add(f);
            executor.execute(f);
        }

        for (FutureTask<Object> future : futureLst)
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                logger.warn("Exception while running latency test.", e);
            }

        executor.shutdown();
    }
}