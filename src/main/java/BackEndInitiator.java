import org.apache.commons.io.IOUtils;
import org.apache.http.*;
import org.apache.http.config.SocketConfig;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.impl.bootstrap.HttpServer;
import org.apache.http.impl.bootstrap.ServerBootstrap;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.protocol.ImmutableHttpProcessor;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class BackEndInitiator implements Runnable {
    int port;
    List<HttpRequestInterceptor> requestInterceptors = new ArrayList<HttpRequestInterceptor>();
    List<HttpResponseInterceptor> responseInterceptors = new ArrayList<HttpResponseInterceptor>();
    Map<Integer, Thread> portsToBackendThreads = new ConcurrentHashMap<>();
    HttpProcessor httpProcessor;
    SortedMap<Integer, Integer> serverCount;
    int[] selectablePorts = new int[100];

    public BackEndInitiator() {
        port = 3000;
        abstractConstructor();
    }

    public BackEndInitiator(int port) {
        port = 2250;
        abstractConstructor();
    }

    private void abstractConstructor() {
        for (int i = 0; i < selectablePorts.length; i++) {
            selectablePorts[i] = 37100 + i;
        }

        httpProcessor = new ImmutableHttpProcessor(requestInterceptors, responseInterceptors);
        this.serverCount = new TreeMap<>();
    }

    class ServerMonitor implements Runnable {
        public SortedMap<Integer, Integer> serverCount;

        public ServerMonitor() {
            this.serverCount = new TreeMap<>();
        }

        @Override
        public void run() {
            Logger.log("BackendInitiator | Starting ServerMonitor", "threadManagement");

            while (true) {
                try {
                    Thread.sleep(100);
                    int currentSecond = (int)(System.currentTimeMillis() / 1000);

                    if (!serverCount.containsKey(currentSecond)) {
                        BackEndInitiator.this.serverCount.put(currentSecond, portsToBackendThreads.size());
                    }
                } catch (InterruptedException e) {
                    Logger.log("BackEndInitiator | Shutting down ServerMonitor", "threadManagement");
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @Override
    public void run() {
        Logger.log("BackEndInitiator | Started BackendInitiator thread", "threadManagement");
        InetAddress hostAddress = null;

        ServerMonitor serverMonitor = new ServerMonitor();
        Thread serverMonitorThread = new Thread(serverMonitor);
        serverMonitorThread.start();

        try {
            hostAddress = InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException e) {
            System.out.println("UnknownHostException within BackendInitiator#run");
            e.printStackTrace();
        }

        SocketConfig config = SocketConfig.custom()
                .setSoTimeout(15000)
                .setTcpNoDelay(true)
                .build();

        final HttpServer server = ServerBootstrap.bootstrap()
                .setLocalAddress(hostAddress)
                .setListenerPort(port)
                .setHttpProcessor(httpProcessor)
                .setSocketConfig(config)
                .registerHandler("/backends", new ServerStartHandler())
                .registerHandler("/backend/*", new ServerUpdateHandler())
                .create();


        try {
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    server.shutdown(5, TimeUnit.SECONDS);
                }
            });
            server.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        } catch(IOException e) {
            System.out.println("IOException Within BackEndInitiator#run");
            e.printStackTrace();
        } catch (InterruptedException e) {
            Logger.log("BackEndInitiator | BackEndInitiator thread interrupted", "threadManagement");
            // shutdown server
            server.shutdown(5, TimeUnit.SECONDS);
            // shutdown all backend servers spawned by this server

            for (Map.Entry<Integer, Thread> entry : this.portsToBackendThreads.entrySet()) {
                Thread backendThread = entry.getValue();
                int threadId = (int)backendThread.getId();
                backendThread.interrupt();
                Logger.log("BackEndInitiator | Terminating backend thread " + threadId, "threadManagement");
            }

            // terminate server monitor thread
            serverMonitorThread.interrupt();
            Logger.log("BackEndInitiator | Terminated server monitor thread", "threadManagement");
            Thread.currentThread().interrupt();
        }
        Logger.log("BackEndInitiator | Terminated BackEndInitiator thread", "threadManagement");
    }

    public SortedMap<Integer, Integer> deliverData() {
        SortedMap<Integer, Integer> serverCount = this.serverCount;
        return this.serverCount;
    }

    private class ServerStartHandler implements HttpRequestHandler {
        @Override
        public void handle(HttpRequest httpRequest, HttpResponse httpResponse, HttpContext httpContext) throws IOException {
            Logger.log("BackendInitiator | received backend initiate request", "capacityModulation");
            BackEnd backend = new BackEnd();
            Thread backendThread = new Thread(backend);
            Logger.log("BackendInitiator | started backend thread", "capacityModulation");
            backendThread.start();

            // wait for backend port to be selected by backend
            int tryNumber = 1;
            while (backend.port == 0) {
                // check periodically for the backend port
                try {
                    Thread.sleep(20);
                } catch(InterruptedException e) {
                    Logger.log("BackEndInitiator | initiateRequestHandler thread interrupted", "threadManagement");
                }
            }

            BackEndInitiator.this.portsToBackendThreads.put(backend.port, backendThread);
            Logger.log("chosen backend port = " + backend.port, "capacityModulation");
            BasicHttpEntity responseEntity = new BasicHttpEntity();
            InputStream responseStream = IOUtils.toInputStream(String.valueOf(backend.port), StandardCharsets.UTF_8.name());
            responseEntity.setContent(responseStream);
            responseStream.close();
            httpResponse.setEntity(responseEntity);
        }
    }

    private class ServerUpdateHandler implements HttpRequestHandler {
        @Override
        public void handle(HttpRequest httpRequest, HttpResponse httpResponse, HttpContext httpContext) {
            String method = httpRequest.getRequestLine().getMethod();
            String uri = httpRequest.getRequestLine().getUri();
            String[] parsedUri = uri.split("/");
            int port = Integer.valueOf(parsedUri[parsedUri.length - 1]);
            Logger.log("BackEndInitiator | received request to shutdown backend on port " + port, "capacityModulation");

            if (method.equals("DELETE")) {
                BackEndInitiator.this.portsToBackendThreads.get(port).interrupt();
                BackEndInitiator.this.portsToBackendThreads.remove(port);
                Logger.log("BackEndInitiator | Shut down backend server running on port " + port, "capacityModulation");
            }
        }
    }
}
