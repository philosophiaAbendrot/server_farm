package loadbalancerlab.loadbalancer;

import loadbalancerlab.factory.HttpClientFactory;
import loadbalancerlab.shared.Config;
import loadbalancerlab.shared.Logger;
import loadbalancerlab.shared.RequestDecoder;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * Class which acts as a middleman between the LoadBalancerRunnable class above it and the HashRing class below it.
 * Asks associated HashRing for the port to delegate client requests to based on a consistent caching scheme which is
 * managed by the HashRing.
 * Manages an associated HashRing instance to handle this logic.
 * Prompts associated HashRing instance to record snapshots of its angles.
 * Prompts associated HashRing instance to update its delegation logic to balance out loading of CacheServer instances.
 */
public class CacheRedistributor {
    Map<Integer, ServerInfo> serverInfoTable;
    HashRing hashRing;

    private static RequestDecoder reqDecoder;
    private int cacheServerManagerPort;
    private static HttpClientFactory httpClientFactory;
    private static double[] serverLoadCutoffs;
    private Logger logger;

    /**
     * Configures static variables
     * @param config: Config instance used to hold configurations
     */
    public static void configure( Config config ) {
        reqDecoder = config.getRequestDecoder();
        httpClientFactory = config.getHttpClientFactory();
        serverLoadCutoffs = config.getServerLoadCutoffs();
    }

    /**
     * @param cacheServerManagerPort: The port that the CacheServerManager instance is running on
     * @param hashRing: A HashRing instance for selecting the server to handle a request based on consistent hashing
     */
    public CacheRedistributor( int cacheServerManagerPort, HashRing hashRing) {
        serverInfoTable = new HashMap<>();
        this.cacheServerManagerPort = cacheServerManagerPort;
        this.hashRing = hashRing;
        logger = new Logger("CacheRedistributor");
    }

    /**
     * Getter method for associated HashRing instance's 'angleHistory' field
     * @return: returns a reference to the HashRing's 'angleHistory' field
     */
    public SortedMap<Integer, Map<Integer, List<HashRingAngle>>> getHashRingAngleHistory() {
        return hashRing.getHashRingAngleHistory();
    }

    /**
     * Sends a request to the associated CacheServerManager instance for an update on which CacheServer instances are
     * running on which ports and their capacity factors
     * Updates the serverInfoTable field with the results
     */
    public void requestServerInfo() {
        CloseableHttpClient client = httpClientFactory.buildApacheClient();
        HttpGet getReq = new HttpGet("http://127.0.0.1:" + cacheServerManagerPort + "/cache-servers");

        try {
            CloseableHttpResponse res = client.execute(getReq);
            JSONObject resJson = reqDecoder.extractJsonApacheResponse(res);
            for (String serverId : resJson.keySet()) {
                int serverIdInt = Integer.valueOf(serverId);
                JSONObject entry = resJson.getJSONObject(serverId);
                double cf = entry.getDouble("capacityFactor");

                if (serverInfoTable.containsKey(serverIdInt)) {
                    // if serverInfoTable contains entry for this server
                    // update cf
                    serverInfoTable.get(serverIdInt).setCapacityFactor(cf);
                } else {
                    // otherwise, create new entry
                    int port = entry.getInt("port");
                    ServerInfo newInfo = new ServerInfo(serverIdInt, port, cf);
                    serverInfoTable.put(serverIdInt, newInfo);
                    // add server to HashRing
                    hashRing.addServer(serverIdInt);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            logger.log("Failed to send request to cache info server", Logger.LogType.REQUEST_PASSING);
        }
    }

    /**
     * @param resourceName: the name of the resource specified in the URI of the request from the client
     * @return: the port that the CacheServer instance which is responsible for the resource is running on
     * @throws IllegalStateException: Thrown if there is no corresponding server for this resource name
     */
    public int selectPort( String resourceName ) throws IllegalStateException {
        int serverId = hashRing.findServerId(resourceName);

        if (!serverInfoTable.containsKey(serverId))
            throw new IllegalStateException("There is no corresponding server for this resource name");

        return serverInfoTable.get(serverId).getPort();
    }

    /**
     * Makes the associated HashRing instance record a snapshot of its 'angleHistory' field for the current time
     * This builds a record of the positions of the angles on the HashRing for analysis
     * @param currentTime: timestamp for the current time (seconds since 1-Jan-1970)
     */
    public void recordServerAngles(int currentTime) {
        hashRing.recordServerAngles(currentTime);
    }

    /**
     * Adds or removes HashRingAngle instances from the associated HashRing for each CacheServer based on its capacity factor
     * If the server is underloaded, additional HashRingAngle instances are added for it
     * If the server is overloaded, some of its HashRingAngle instances are removed from the HashRing
     */
    public void remapCacheKeys() {
        for (Map.Entry<Integer, ServerInfo> entry : serverInfoTable.entrySet()) {
            int serverId = entry.getKey();
            ServerInfo info = entry.getValue();

            if (info.getCapacityFactor() < serverLoadCutoffs[1]) {
                // cf is lower than target range
                if (info.getCapacityFactor() < serverLoadCutoffs[0]) {
                    hashRing.addAngle(serverId, 3);
                } else {
                    hashRing.addAngle(serverId, 1);
                }
            } else if (info.getCapacityFactor() > serverLoadCutoffs[2]) {
                // cf is higher than target range
                if (info.getCapacityFactor() > serverLoadCutoffs[3]) {
                    hashRing.removeAngle(serverId, 3);
                } else {
                    hashRing.removeAngle(serverId, 1);
                }
            }
        }
    }
}