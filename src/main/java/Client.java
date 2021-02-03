import org.apache.commons.io.IOUtils;
import org.apache.http.*;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class Client implements Runnable {
    CloseableHttpClient httpClient;
    long maxDemandTime;
    private static final int LOAD_BALANCER_PORT = 8080;
    private static final int NUM_REQUESTS = 3;
    int resourceId;
    String name;
    List<Integer> requestTimestamps;

    public Client(String _name, long maxDemandTime) {
        this.name = _name;
        Random random = new Random();
        this.maxDemandTime = maxDemandTime;
        this.resourceId = random.nextInt(3000);
        this.requestTimestamps = Collections.synchronizedList(new ArrayList<>());
    }

    @Override
    public void run() {
        Logger.log("Client | Started Client thread", "threadManagement");

        try {
            start();
        } catch(IOException e) {
            System.out.println("IOException thrown in Client#run");
            e.printStackTrace();
        }

        Logger.log("Client | Terminated Client thread", "threadManagement");
    }

    void start() throws IOException {
        while (true) {
            httpClient = HttpClients.createDefault();
            String path;
            path = "/api/" + resourceId;
            HttpGet httpget = new HttpGet("http://127.0.0.1:" + LOAD_BALANCER_PORT + path);
            Logger.log(String.format("Client %s | path: %s", name, path), "clientStartup");
            long timestamp = System.currentTimeMillis();
            CloseableHttpResponse response = sendRequest(httpget);
            // record timestamp at which request was fired off to the load balancer
            this.requestTimestamps.add((int)(timestamp / 1000));
            printResponse(response);
            response.close();
            httpClient.close();

            try {
                int freq = requestFrequency();
                Logger.log("Client | frequency = " + freq, "loadModulation");
                TimeUnit.MILLISECONDS.sleep(freq);
            } catch (InterruptedException e) {
                System.out.println("InterruptedException thrown in Client#start");
                e.printStackTrace();
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // return a hash table mapping seconds since 1970 to number of requests sent
    public Map<Integer, Integer> deliverData() {
        Map<Integer, Integer> requestsBySecond = new HashMap<>();

        for (Integer timestamp : this.requestTimestamps) {
            if (requestsBySecond.containsKey(timestamp)) {
                int prev = requestsBySecond.get(timestamp);
                requestsBySecond.put(timestamp, prev + 1);
            } else {
                requestsBySecond.put(timestamp, 1);
            }
        }

        return requestsBySecond;
    }

    private int requestFrequency() {
        long x = System.currentTimeMillis();

        // demand function
        // -0.05(x - 20)^2 + 20

        if (Math.abs(x - maxDemandTime) >= 19500) {
            return Integer.MAX_VALUE;
        } else {
            double delta = (x - maxDemandTime) / 1000.0;
            double demand = -0.05 * Math.pow(delta, 2) + 20;
            int waitTime = (int)Math.round(1000 / demand);
            return waitTime;
        }
    }



    private CloseableHttpResponse sendRequest(HttpGet httpget) throws IOException {
        return httpClient.execute(httpget);
    }

    private void printResponse(CloseableHttpResponse response) throws IOException {
        HttpEntity responseBody = response.getEntity();
        InputStream bodyStream = responseBody.getContent();
        String responseString = IOUtils.toString(bodyStream, StandardCharsets.UTF_8.name());
        Logger.log(String.format("Client %s | response body: %s", name, responseString), "requestPassing");
        bodyStream.close();
    }
}
