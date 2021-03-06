package loadbalancerlab.cacheserver;

import loadbalancerlab.shared.Config;
import loadbalancerlab.shared.Logger;

import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Monitors the number of incoming requests, compiles data and delivers reports.
 */
public class RequestMonitor {

    /**
     * A list of RequestDatum objects. Keeps track of the processing times of the most recent requests.
     */
    List<RequestDatum> requestData;

    /**
     * How long RequestMonitor records are kept in memory, in milliseconds.
     */
    static int recordTTL = 10_000;

    /**
     * Object used for logging.
     */
    private Logger logger;

    /**
     * Method used to configure static variables.
     * @param config        Config object used for configuring various classes.
     */
    public static void configure( Config config ) {
        recordTTL = config.getRequestMonitorRecordTTL();
    }

    /**
     * Constructor
     */
    public RequestMonitor() {
        requestData = Collections.synchronizedList(new ArrayList<>());
        logger = new Logger("RequestMonitor");
    }

    /**
     * Adds a request record. This method is called whenever a client request is handled by ClientRequestHandler.
     * @param startTime   When request processing began (milliseconds since Jan 1, 1970).
     * @param endTime     When request processing completed (milliseconds since Jan 1, 1970).
     */
    public void addRecord(long startTime, long endTime) {
        requestData.add(new RequestDatum(startTime, endTime));
    }

    /**
     * Clears out request records which are outdated. Records are considered outdated if they are older than
     * 'recordStorageTime' by the given timestamp 'currentTime'. This method is periodically called by
     * RequestMonitorRunnable to keep capacity factor records up to date.
     * @param currentTime   The time which is used to calculate whether the records are old enough to be deleted.
     */
    public void clearOutData(long currentTime) {
        Iterator<RequestDatum> iterator = requestData.iterator();

        /* Delete request data which is out of date */
        while (iterator.hasNext()) {
            RequestDatum datum = iterator.next();
            if (datum.startTime + recordTTL < currentTime) {
                iterator.remove();
            } else {
                break;
            }
        }
    }

    /**
     * Returns the average recent capacity factor value by processing recent request records, stored in 'requestData'.
     * @param currentTime        a timestamp for the current time (milliseconds since 1-Jan-1970).
     * @return capacityFactor    the 'load' on the CacheServer, in terms of running time / total time.
     */
    public double getCapacityFactor(long currentTime) {
        if (requestData.isEmpty()) {

            /* If records are empty, return 0.0 */
            return 0.0;
        } else {
            long startTime = requestData.get(0).startTime;
            long runningTime = 0;

            for (RequestDatum datum : requestData)
                runningTime += datum.processingTime;

            double capacityFactor = runningTime / (double)(currentTime - startTime);
            logger.log(String.format("CacheServer | capacityFactor = %f", capacityFactor), Logger.LogType.REQUEST_PASSING);

            return capacityFactor;
        }
    }
}
