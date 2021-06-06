package loadbalancerlab.cacheservermanager;

import loadbalancerlab.factory.CacheServerFactory;
import loadbalancerlab.cacheserver.RequestMonitor;
import loadbalancerlab.cacheserver.CacheServer;
import loadbalancerlab.shared.Config;
import loadbalancerlab.factory.HttpClientFactory;
import loadbalancerlab.shared.RequestDecoder;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class CacheServerManager {
    static double growthRate;
    static double targetCf;
    volatile ConcurrentMap<Integer, Thread> serverThreadTable = new ConcurrentHashMap<>();
    ServerMonitor serverMonitor;
    static int cacheServerIdCounter;
    int[] selectablePorts = new int[100];
    CacheInfoRequestHandler cacheInfoRequestHandler;

    private CacheServerFactory cacheServerFactory;
    private HttpClientFactory clientFactory;
    private RequestDecoder reqDecoder;
    private int port;


    static {
        cacheServerIdCounter = 0;
    }

    public static void configure( Config config ) {
        targetCf = config.getTargetCf();
        growthRate = config.getCacheServerGrowthRate();
    }

    public CacheServerManager( CacheServerFactory _cacheServerFactory, HttpClientFactory _clientFactory, RequestDecoder _reqDecoder ) {
        port = -1;
        cacheServerFactory = _cacheServerFactory;
        clientFactory = _clientFactory;
        reqDecoder = _reqDecoder;

        // reserve ports 37000 through 37099 as usable ports
        for (int i = 0; i < selectablePorts.length; i++)
            selectablePorts[i] = 37100 + i;

        serverMonitor = new ServerMonitor(clientFactory, reqDecoder, this);
        cacheInfoRequestHandler = new CacheInfoRequestHandler(serverMonitor);
    }

    public int getPort() {
        return this.port;
    }

    public SortedMap<Integer, Integer> deliverData() {
        return this.serverMonitor.deliverData();
    }

    public void startupCacheServer(int num) {
        for (int i = 0; i < num; i++) {
            CacheServer cacheServer = cacheServerFactory.produceCacheServer(new RequestMonitor("CacheServer"));
            Thread cacheServerThread = cacheServerFactory.produceCacheServerThread(cacheServer);
            cacheServerThread.start();
            serverThreadTable.put(cacheServerIdCounter, cacheServerThread);
            serverMonitor.addServer(cacheServerIdCounter, cacheServer.getPort());
            cacheServerIdCounter++;
        }
    }

    public void modulateCapacity() {
        double averageCapacityFactor = serverMonitor.getAverageCf();
        double diff = averageCapacityFactor - targetCf;
        int intDiff = (int)(Math.round(diff * growthRate));

        if (diff == 0) {
            return;
        } else if (diff > 0) {
            startupCacheServer(intDiff);
        } else {
            shutdownCacheServer(-intDiff);
        }
    }

    public void shutdownCacheServer(int num) {
        List<Integer> serverIds = new ArrayList<>(serverThreadTable.keySet());
        Random rand = new Random();
        num = Math.min(serverThreadTable.size(), num);

        for (int i = 0; i < num; i++) {
            int randIdx = rand.nextInt(serverIds.size());
            int selectedId = serverIds.get(randIdx);
            Thread selectedThread = serverThreadTable.get(selectedId);
            selectedThread.interrupt();
            serverThreadTable.remove(selectedId);
            serverMonitor.removeServer(selectedId);
            serverIds.remove(randIdx);
        }
    }

    public int numServers() {
        return this.serverThreadTable.size();
    }
}
