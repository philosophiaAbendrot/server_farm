package loadbalancerlab.cacheservermanager;

import loadbalancerlab.factory.HttpClientFactory;
import loadbalancerlab.shared.RequestDecoder;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.json.JSONObject;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Used to monitor, record, and process data on CacheServer instances.
 */
public class ServerMonitor {

    /**
     * A hash table which maps server id to a ServerInfo instance which holds info about the server
     * holds info about the port and the record of capacity factor over time.
     */
    ConcurrentMap<Integer, ServerInfo> serverInfoTable;

    /**
     * A factory which is used to generate CloseableHttpClient instances.
     */
    HttpClientFactory clientFactory;

    /**
     * Used to extract JSON parameters from CloseableHttpResponse objects.
     */
    RequestDecoder reqDecoder;

    /**
     * A SortedMap Used to store information about how many servers were active at each moment in time.
     * The keys are timestamps (seconds since 1-Jan-1970). They are in order from earliest to latest.
     * The values are the number of CacheServers active at that timestamp.
     */
    SortedMap<Integer, Integer> serverCount;

    /**
     * Associated CacheServerManager instance which manages lifecycle of cache servers and modulates number of
     * cache servers to meet request load.
     */
    CacheServerManager cacheServerManager;

    /**
     * Variable which is used to control when ServerMonitor thread stops execution.
     */
    boolean stopExecution;

    /**
     * Constructor
     * @param clientFactory     A factory which is used to generate CloseableHttpClient instances.
     * @param reqDecoder        Used to extract information from CloseableHttpResponse objects.
     * @param cacheServerManager    Associated CacheServerManager which manages lifecycle of CacheServer objects
     *                              nad modulates their number to meet request load.
     */
    public ServerMonitor( HttpClientFactory clientFactory, RequestDecoder reqDecoder, CacheServerManager cacheServerManager ) {
        serverInfoTable = new ConcurrentHashMap<>();
        this.clientFactory = clientFactory;
        serverCount = new TreeMap<>();
        this.reqDecoder = reqDecoder;
        this.cacheServerManager = cacheServerManager;
        stopExecution = false;
    }

    /**
     * Adds new CacheServer object to the record of servers 'serverInfoTable' field.
     * @param id    The id of the server.
     * @param port  The port that CacheServer object is running on.
     */
    public void addServer( int id, int port, int currentTime ) {
        if (serverInfoTable.containsKey(id)) {
            throw new IllegalArgumentException("serverInfoTable already contains an entry for id " + id);
        }

        serverInfoTable.put(id, new ServerInfo(id, port, currentTime));
    }

    /**
     * Records that a certain server is no longer active. Also records the time at which a server was deactivated.
     * @param id            The id of the server.
     * @param currentTime   The current time, in seconds since 1-Jan-1970.
     */
    public void deactivateServer( int id, int currentTime ) {
        if (serverInfoTable.containsKey(id)) {
            ServerInfo info = serverInfoTable.get(id);
            info.setActive(false);
            info.setDeactivationTime(currentTime);
        }
    }

    /**
     * Updates the number of active servers at a particular moment in time in 'serverCount' field.
     * @param currentSecond     The current time (seconds since 1-Jan-1970).
     * @param numServers        The number of active CacheServer objects at the time indicated by currentSecond.
     */
    public void updateServerCount( int currentSecond, int numServers ) {
        if (!serverCount.containsKey(currentSecond))
            serverCount.put(currentSecond, numServers);
    }

    /**
     * Returns a copy of 'serverCount' field, which holds information information about how many servers were active
     * at each moment in time.
     * @return      Info about the number of active CahceServer objects as a function of time.
     *              Keys are timestamps (seconds since 1-Jan-1970), ordered in ascending order.
     *              Values are the number of CacheServer objects active at that moment in time.
     */
    public SortedMap<Integer, Integer> deliverServerCountData() {
        SortedMap<Integer, Integer> copyMap = new TreeMap<>();
        copyMap.putAll(serverCount);
        return copyMap;
    }

    /**
     * Returns a copy of 'serverInfoTable' field, which maps ids of CacheServer objects to 'ServerInfo' objects which
     * hold information about the CacheServer.
     * @return      A copy of serverInfoTable which contains the original ServerInfo objects.
     */
    public Map<Integer, ServerInfo> getServerInfo() {
        Map<Integer, ServerInfo> copyMap = new HashMap<>();
        copyMap.putAll(serverInfoTable);
        return copyMap;
    }

    /**
     * Pings every active CacheServer object with a Http request for a telemetry update and updates information in
     * serverInfoTable field.
     */
    public void pingCacheServers() {
        serverInfoTable.forEach((serverId, info) -> {
            CloseableHttpClient httpClient = this.clientFactory.buildApacheClient();
            if (info.getActive()) {
                HttpGet req = new HttpGet("http://127.0.0.1:" + info.getPort() + "/capacity-factor");

                try {
                    CloseableHttpResponse res = httpClient.execute(req);
                    JSONObject resJson = reqDecoder.extractJsonApacheResponse(res);
                    info.updateCapacityFactor((int)(System.currentTimeMillis() / 1_000), resJson.getDouble("capacity_factor"));
                    httpClient.close();
                } catch (IOException e) {
                    System.out.println("IOException throws in ServerMonitorImpl#pingCacheServer");
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * Returns average capacity factor of every CacheServer.
     * @return      Average capacity factor of every cache server as a double.
     */
    public double getAverageCf() {
        double cfSum = 0;
        int numEntries = 0;

        /* Find average capacity factor among all active CacheServers */
        for (ServerInfo info : serverInfoTable.values()) {
            if (info.getCurrentCapacityFactor() != 0.0 && info.getActive()) {
                cfSum += info.getCurrentCapacityFactor();
                numEntries++;
            }
        }

        return cfSum / numEntries;
    }

    /**
     * Returns data on the capacity factor of each server at each moment in time.
     * @return      Returns cap factor at each moment in time.
     *              The data is a 2d String array representation of a csv file.
     *              The leftmost column has all timestamps, in ascending order from top to bottom.
     *              The topmost row has all CacheServer ids, in ascending order from left to right.
     */
    public String[][] deliverCfData() {

        /* Figure out dimensions of data */
        SortedMap<Integer, ServerInfo> serverInfoTableCopy = new TreeMap<>();

        /* Copy server info table */
        for (Map.Entry<Integer, ServerInfo> entry : serverInfoTable.entrySet()) {
            try {
                serverInfoTableCopy.put(entry.getKey(), (ServerInfo)entry.getValue().clone());
            } catch (CloneNotSupportedException e) {
                e.printStackTrace();
            }
        }

        Integer[] serverIds = serverInfoTableCopy.keySet().stream().toArray(Integer[]::new);
        Arrays.sort(serverIds);
        int numServers = serverIds.length;

        int[] timeRange = findTimeRange(serverInfoTableCopy);
        int earliestTime = timeRange[0];
        int latestTime = timeRange[1];

        double[][] entryRowsDouble = new double[latestTime - earliestTime + 1][numServers];
        String[] headerRow = new String[numServers];
        String[] timestampColumn = new String[latestTime - earliestTime + 1];
        int currentTime = earliestTime;

        /* Add timestamps to leftmost column */
        for (int row = 0; row < timestampColumn.length; row++)
            timestampColumn[row] = String.valueOf(currentTime++);

        /* Fill out header row */
        for (int col = 0; col < numServers; col++)
            headerRow[col] = String.valueOf(serverIds[col]);

        for (int i = 0; i < serverIds.length; i++) {
            int serverId = serverIds[i];
            ServerInfo info = serverInfoTableCopy.get(serverId);
            SortedMap<Integer, Double> cfRecord = info.getCapacityFactorRecord();

            for (Integer timestamp : cfRecord.keySet()) {
                entryRowsDouble[timestamp - earliestTime][i] = cfRecord.get(timestamp);
            }
        }

        /* Interpolate missing entries */
        interpolateMissingEntries(entryRowsDouble, serverIds, serverInfoTableCopy, earliestTime);

        /* Round values in table to two digits past the decimal */
        roundEntries(entryRowsDouble);

        return constructOutputGrid(headerRow, timestampColumn, entryRowsDouble);
    }

    /**
     * Interpolates missing entries in the provided 2d capacity-factor array 'entryFields'.
     * @param entryFields           2d double array detailing capacity factor of servers.
     * @param serverIds             Array of cache server ids (sorted in ascending order).
     * @param serverInfoTableCopy   A copy of the serverInfoTable field.
     * @param earliestTime          The earliest timestamp in serverInfoTable field (in seconds since 1-Jan-1970).
     */
    private void interpolateMissingEntries(double[][] entryFields, Integer[] serverIds, SortedMap<Integer, ServerInfo> serverInfoTableCopy, int earliestTime) {
        for (int col = 0; col < entryFields[0].length; col++) {

            /* Iterate through columns and interpolate */
            int prevEntryIdx = -1;
            int nextEntryIdx;


            while (true) {
                nextEntryIdx = findNextEntryIdx(entryFields, col, prevEntryIdx);
                int serverId = serverIds[col];
                ServerInfo info = serverInfoTableCopy.get(serverId);
                int serverStartTime = info.getStartTime();
                int deactivationTime = info.getDeactivationTime();

                if (prevEntryIdx == -1) {

                    /* Fill in all entries between earliest time and server start time with "0.0" */
                    for (int row = 0; row + earliestTime < serverStartTime; row++) {
                        entryFields[row][col] = 0.0d;
                    }

                    /* Fill in all entries between server start time and first non-null entry with the value of the
                       first non-null entry */
                    double nextEntry = entryFields[nextEntryIdx][col];
                    double fillInValue = nextEntry;

                    for (int row = Math.max(serverStartTime - earliestTime, 0); row < nextEntryIdx; row++)
                        entryFields[row][col] = fillInValue;

                    prevEntryIdx = nextEntryIdx;
                } else if (nextEntryIdx == -1) {

                    /* Fill in all entries between current entry and server deactivation time if there are no
                       subsequent filled entries */
                    double fillInValue = entryFields[prevEntryIdx][col];

                    /* Fill in all entries between current entry and last entry if there are no subsequent
                       filled entries */
                    for (int row = prevEntryIdx; row <= deactivationTime - earliestTime; row++) {
                        entryFields[row][col] = fillInValue;
                    }

                    if (deactivationTime != -1) {

                        /* Fill in all entries between server deactivation time and the latest time with "0.0" */
                        for (int row = deactivationTime - earliestTime + 1; row < entryFields.length; row++) {
                            entryFields[row][col] = 0.0d;
                        }
                    }

                    /* Terminate since all entries up to the last have been filled */
                    break;
                } else {

                    /* For entries between two points, use interpolation */
                    double dist = nextEntryIdx - prevEntryIdx;
                    double delta = entryFields[nextEntryIdx][col] - entryFields[prevEntryIdx][col];
                    double slope = (delta / dist);

                    for (int row = prevEntryIdx; row < nextEntryIdx; row++) {
                        double res = (row - prevEntryIdx) * slope + entryFields[prevEntryIdx][col];
                        entryFields[row][col] = res;
                    }

                    prevEntryIdx = nextEntryIdx;
                }
            }
        }
    }

    /**
     * Helper method for rounding all entries in provided 2d double array 'entryRows' to two digits.
     * Used by 'interpolateMissingEntries()' method.
     * @param entryRows     The 2d double array holding the capacity factor values per time.
     */
    private void roundEntries(double[][] entryRows) {
        for (int row = 0; row < entryRows.length; row++) {
            for (int col = 0; col < entryRows[0].length; col++) {
                entryRows[row][col] = Math.round(entryRows[row][col] * 100) / 100.0;
            }
        }
    }

    /**
     * A helper method for deliverCfData which Constructs a 2d String grid by stitching together a header row,
     * a timestamp column and a grid of capacity factors.
     * @param headerRow          A String array holding server ids.
     * @param timestampColumn    A String array holding timestamps.
     * @param entryRowsDouble    A 2d double array holding capacity factors.
     * @return   Returns a 2d String grid for output by the deliverCfData function, which is meant to be printed to csv.
     */
    private String[][] constructOutputGrid(String[] headerRow, String[] timestampColumn, double[][] entryRowsDouble) {
        String[][] outputGrid = new String[entryRowsDouble.length + 1][entryRowsDouble[0].length + 1];
        outputGrid[0][0] = "";

        /* Fill out header row */
        for (int col = 1; col < outputGrid[0].length; col++)
            outputGrid[0][col] = headerRow[col - 1];

        /* Fill out leftmost column using timestampColumn */
        for (int row = 1; row < outputGrid.length; row++)
            outputGrid[row][0] = timestampColumn[row - 1];

        /* Fill out rest of output grid using entryRowsDouble */
        for (int row = 1; row < outputGrid.length; row++) {
            for (int col = 1; col < outputGrid[0].length; col++) {
                outputGrid[row][col] = String.valueOf(entryRowsDouble[row - 1][col - 1]);
            }
        }

        return outputGrid;
    }

    /**
     * Helper method for finding the column index of the next entry which is non-null.
     * Employed by 'interpolateMissingEntries' method.
     *
     * @param entryFields   The 2d capacity factor array.
     * @param col           The column on which the interpolation logic is being run.
     * @param startRow      The row after which the next entry is found.
     * @return  The next entry index which is not null. -1 is returned if there is no next entry which is non-null.
     */
    private int findNextEntryIdx(double[][] entryFields, int col, int startRow) {
         int row = startRow;
         int nextIdx;

         while (true) {
             row++;

             if (row == entryFields.length) {

                 /* Return -1 if there is no next non-null entry */
                 nextIdx = -1;
                 break;
             }

             if (entryFields[row][col] != 0.0d) {

                 /* If a non-null row is found, return the index */
                 nextIdx = row;
                 break;
             }
         }

         return nextIdx;
    }

    /**
     * Takes a copy of the 'serverInfoTable' field and returns the earliest and latest timestamps within it.
     * Helper method used by 'deliverCfData()' method.
     * @param serverInfoTableCopy   A copy of 'serverInfoTable' field.
     * @return      An integer array of length 2. The first element is earliest time. The second element is the
     *              latest time.
     */
    private int[] findTimeRange(SortedMap<Integer, ServerInfo> serverInfoTableCopy) {
        int earliestTime = Integer.MAX_VALUE;
        int latestTime = Integer.MIN_VALUE;

        /* Find the earliest and latest timestamps */
        for (ServerInfo info : serverInfoTableCopy.values()) {
            SortedMap<Integer, Double> capFactorRecord = info.getCapacityFactorRecord();

            if (!capFactorRecord.isEmpty()) {
                earliestTime = Math.min(earliestTime, capFactorRecord.firstKey());
                latestTime = Math.max(latestTime, capFactorRecord.lastKey());
            }
        }

        return new int[] { earliestTime, latestTime };
    }
}