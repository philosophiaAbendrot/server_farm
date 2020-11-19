import org.apache.commons.io.IOUtils;
import org.apache.http.*;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.config.SocketConfig;
import org.apache.http.impl.bootstrap.HttpServer;
import org.apache.http.impl.bootstrap.ServerBootstrap;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.*;

import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class LoadBalancer implements Runnable {
    int port;
    List<HttpRequestInterceptor> requestInterceptors = new ArrayList<HttpRequestInterceptor>();
    List<HttpResponseInterceptor> responseInterceptors = new ArrayList<HttpResponseInterceptor>();
    HttpProcessor httpProcessor;
    Map<BackEnd.Type, List<Integer>> backendPortIndex = new HashMap<>();
    private static final int BACKEND_INITIATOR_PORT = 3000;
    private static final int STARTUP_BACKEND_DYNO_COUNT = 5;

    public LoadBalancer(int port) {
        this.port = port;
        httpProcessor = new ImmutableHttpProcessor(requestInterceptors, responseInterceptors);
        backendPortIndex.put(BackEnd.Type.HOME_PAGE_SERVER, new ArrayList<Integer>());
        backendPortIndex.put(BackEnd.Type.IMAGE_FILE_SERVER, new ArrayList<Integer>());
    }

    @Override
    public void run() {
        SocketConfig config = SocketConfig.custom()
                .setSoTimeout(15000)
                .setTcpNoDelay(true)
                .build();
        try {
            InetAddress hostAddress = InetAddress.getByName("127.0.0.1");
            final HttpServer server = ServerBootstrap.bootstrap()
                    .setLocalAddress(hostAddress)
                    .setListenerPort(port)
                    .setHttpProcessor(httpProcessor)
                    .setSocketConfig(config)
                    .registerHandler("/order", new ClientOrderRequestHandler())
                    .create();

            server.start();
            startupBackendCluster();
            server.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    server.shutdown(5, TimeUnit.SECONDS);
                }
            });

        } catch(IOException e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        } catch(InterruptedException e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }
    }

    private void startupBackendCluster() {
        for (int i = 0; i < STARTUP_BACKEND_DYNO_COUNT; i++) {
            if (i % 2 == 0) {
                startupBackend(BackEnd.Type.IMAGE_FILE_SERVER);
            } else {
                startupBackend(BackEnd.Type.HOME_PAGE_SERVER);
            }
        }
    }

    private void startupBackend(BackEnd.Type type) {
        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpPost httpPost = new HttpPost("http://127.0.0.1:" + BACKEND_INITIATOR_PORT + "/backend/start");
        List<NameValuePair> params = new ArrayList<NameValuePair>();

        while(true) {
            try {
                Thread.sleep(100);
                httpPost.setEntity(new UrlEncodedFormEntity(params));
                System.out.println("LoadBalancer | sent request to startup a backend");
                CloseableHttpResponse response = httpClient.execute(httpPost);
                System.out.println("LoadBalancer | received response");
                HttpEntity responseBody = response.getEntity();
                InputStream responseStream = responseBody.getContent();
                String responseString = IOUtils.toString(responseStream, StandardCharsets.UTF_8.name());
                responseStream.close();
                System.out.println("LoadBalancer | new backend port = " + responseString);
                backendPortIndex.get(type).add(Integer.valueOf(responseString));
                System.out.println("LoadBalancer | backend ports:");
                for (Map.Entry<BackEnd.Type, List<Integer>> entry : backendPortIndex.entrySet()) {
                    System.out.printf("%s : %s\n", entry.getKey(), entry.getValue());
                }
                break;
            } catch (UnsupportedEncodingException | UnsupportedOperationException | ClientProtocolException e) {
                System.out.println(e.getMessage());
                e.printStackTrace();
            } catch (InterruptedException e) {
                System.out.println(e.getMessage());
                e.printStackTrace();
            } catch (IOException e) {
                System.out.println(e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private class ClientOrderRequestHandler implements HttpRequestHandler {
        @Override
        public void handle(HttpRequest httpRequest, HttpResponse httpResponse, HttpContext httpContext) throws HttpException, IOException {
            Random rand = new Random();
            List<Integer> availablePorts = backendPortIndex.get(BackEnd.Type.HOME_PAGE_SERVER);
            int backendPort = availablePorts.get(rand.nextInt(availablePorts.size()));
            CloseableHttpClient httpClient = HttpClients.createDefault();
            System.out.printf("LoadBalancer | relaying message to port %d\n", backendPort);
            HttpGet httpget = new HttpGet("http://127.0.0.1:" + backendPort);
            CloseableHttpResponse response = httpClient.execute(httpget);
            HttpEntity responseBody = response.getEntity();
            httpResponse.setEntity(responseBody);
        }
    }

    public static void main(String[] args) {
        new LoadBalancer(8080).run();
    }
}
