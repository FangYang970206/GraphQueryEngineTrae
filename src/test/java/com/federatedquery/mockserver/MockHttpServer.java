package com.federatedquery.mockserver;

import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MockHttpServer {
    
    private static final Logger logger = LoggerFactory.getLogger(MockHttpServer.class);
    
    private static final String ALARM_ENDPOINT = "/rest/alarm/v1";
    private static final String KPI_ENDPOINT = "/rest/kpi/v1";
    
    private final int port;
    private HttpServer server;
    private final MockDataRepository repository;
    private final ExecutorService executor;
    
    public MockHttpServer(int port) {
        this.port = port;
        this.repository = new MockDataRepository();
        this.executor = Executors.newFixedThreadPool(10);
    }
    
    public MockHttpServer() {
        this(8080);
    }
    
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        
        server.createContext(ALARM_ENDPOINT, new AlarmDataHandler(repository));
        server.createContext(KPI_ENDPOINT, new KpiDataHandler(repository));
        
        server.setExecutor(executor);
        server.start();
        
        logger.info("Mock HTTP Server started on port {}", port);
        logger.info("ALARM endpoint: http://localhost:{}{}", port, ALARM_ENDPOINT);
        logger.info("KPI endpoint: http://localhost:{}{}", port, KPI_ENDPOINT);
    }
    
    public void stop() {
        if (server != null) {
            server.stop(0);
            executor.shutdown();
            logger.info("Mock HTTP Server stopped");
        }
    }
    
    public int getPort() {
        return port;
    }
    
    public String getAlarmEndpoint() {
        return "http://localhost:" + port + ALARM_ENDPOINT;
    }
    
    public String getKpiEndpoint() {
        return "http://localhost:" + port + KPI_ENDPOINT;
    }
    
    public MockDataRepository getRepository() {
        return repository;
    }
    
    public static void main(String[] args) {
        int port = 8080;
        
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                logger.warn("Invalid port number: {}, using default port 8080", args[0]);
            }
        }
        
        MockHttpServer server = new MockHttpServer(port);
        
        try {
            server.start();
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down server...");
                server.stop();
            }));
            
            logger.info("Server is running. Press Ctrl+C to stop.");
            
            Thread.currentThread().join();
            
        } catch (IOException e) {
            logger.error("Failed to start server", e);
            System.exit(1);
        } catch (InterruptedException e) {
            logger.info("Server interrupted");
            Thread.currentThread().interrupt();
        } finally {
            server.stop();
        }
    }
}
