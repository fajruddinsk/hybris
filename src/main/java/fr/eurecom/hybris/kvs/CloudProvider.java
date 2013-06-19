package fr.eurecom.hybris.kvs;

/**
 * Class for storing cloud storage provider details.
 * @author p.viotti
 */
public class CloudProvider implements Comparable<CloudProvider> {
    
    private String id;
    private String accessKey;
    private String secretKey;
    
    private boolean alreadyUsed;   // whether the storage provider has already been used (to initialize the folder/bucket)
    private boolean enabled;

    /* measures to compare the providers */
    private long writeLatency;
    private long readLatency;
    private int cost;          // $ cents per GB

    public CloudProvider(String id, String accessKey, String secretKey,
                            boolean enabled, int cost) {
        super();
        this.id = id;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.enabled = enabled;
        this.cost = cost;
        
        this.readLatency = 0;
        this.writeLatency = 0;
        this.alreadyUsed = false;
    }
    
    public String getId()                           { return id; }
    public void setId(String id)                    { this.id = id; }
    public String getAccessKey()                    { return accessKey; }
    public void setAccessKey(String accessKey)      { this.accessKey = accessKey; }
    public String getSecretKey()                    { return secretKey; }
    public void setSecretKey(String secretKey)      { this.secretKey = secretKey; }
    public boolean isAlreadyUsed()                  { return alreadyUsed; }
    public void setAlreadyUsed(boolean alreadyUsed) { this.alreadyUsed = alreadyUsed; }
    public boolean isEnabled()                      { return enabled; }
    public void setEnabled(boolean enabled)         { this.enabled = enabled; }
    public long getWriteLatency()                   { return writeLatency; }
    public void setWriteLatency(long writeLatency)  { this.writeLatency = writeLatency; }
    public long getReadLatency()                    { return readLatency; }
    public void setReadLatency(long readLatency)    { this.readLatency = readLatency; }
    public int getCost()                            { return cost; }
    public void setCost(int cost)                   { this.cost = cost; }

    /* 
     * TODO should they be comparable both in terms of cost and latency?
     * if so, which weight should be given to each of them
     */
    @Override
    public int compareTo(CloudProvider o) {
        if     (((this.writeLatency + this.readLatency) < 
                (o.getWriteLatency() + o.getReadLatency()))    
                || (!o.isEnabled()))    
            return -1;
        else if (((this.writeLatency + this.readLatency) > 
                    (o.getWriteLatency() + o.getReadLatency())) 
                    || (!this.isEnabled()))
            return 1;
        else
            return 0;
    }
    
    @Override
    public String toString() {
        return "CloudProvider [id=" + id + ", alreadyUsed=" + alreadyUsed
                + ", enabled=" + enabled + ", writeLatency=" + writeLatency
                + ", readLatency=" + readLatency + ", cost=" + cost + "]";
    }
}
