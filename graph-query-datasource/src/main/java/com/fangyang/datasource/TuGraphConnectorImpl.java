package com.fangyang.datasource;

import org.neo4j.driver.*;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.AuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
class TuGraphConnectorImpl implements TuGraphConnector {
    private static final Logger log = LoggerFactory.getLogger(TuGraphConnectorImpl.class);
    
    private final Driver driver;
    private final TuGraphConfig config;
    private volatile boolean connected = false;
    private final SessionConfig sessionConfig;
    
    TuGraphConnectorImpl() {
        this(TuGraphConfig.defaultConfig());
    }
    
    TuGraphConnectorImpl(TuGraphConfig config) {
        this.config = config;
        this.driver = createDriver(config);
        this.sessionConfig = createSessionConfig(config);
        testConnection();
    }
    
    private Driver createDriver(TuGraphConfig config) {
        try {
            Config driverConfig = Config.builder()
                    .withMaxConnectionPoolSize(config.getMaxConnectionPoolSize())
                    .withConnectionTimeout(config.getConnectionTimeoutMs(), TimeUnit.MILLISECONDS)
                    .withMaxTransactionRetryTime(config.getMaxTransactionRetryTimeMs(), TimeUnit.MILLISECONDS)
                    .build();
            
            return GraphDatabase.driver(
                    config.getUri(),
                    AuthTokens.basic(config.getUsername(), config.getPassword()),
                    driverConfig
            );
        } catch (Exception e) {
            log.error("Failed to create Neo4j driver for TuGraph: {}", e.getMessage());
            throw new RuntimeException("Failed to create TuGraph connection", e);
        }
    }
    
    private SessionConfig createSessionConfig(TuGraphConfig config) {
        SessionConfig.Builder builder = SessionConfig.builder();
        
        if (config.getGraphName() != null && !config.getGraphName().isEmpty()) {
            builder.withDatabase(config.getGraphName());
            log.info("TuGraph session configured with graph: {}", config.getGraphName());
        }
        
        return builder.build();
    }
    
    private void testConnection() {
        try (Session session = driver.session(sessionConfig)) {
            session.run("RETURN 1").consume();
            this.connected = true;
            log.info("Successfully connected to TuGraph at {} with graph {}", 
                    config.getUri(), config.getGraphName());
        } catch (ServiceUnavailableException e) {
            log.warn("TuGraph service unavailable at {}: {}", config.getUri(), e.getMessage());
            this.connected = false;
        } catch (AuthenticationException e) {
            log.error("Authentication failed for TuGraph: {}", e.getMessage());
            throw new RuntimeException("TuGraph authentication failed", e);
        } catch (Exception e) {
            log.warn("Failed to test TuGraph connection: {}", e.getMessage());
            this.connected = false;
        }
    }
    
    @Override
    public List<org.neo4j.driver.Record> executeQuery(String cypher) {
        return executeQuery(cypher, (Object[]) null);
    }
    
    @Override
    public List<org.neo4j.driver.Record> executeQuery(String cypher, Object... parameters) {
        if (!connected) {
            throw new RuntimeException("TuGraph connection is not available");
        }
        
        List<org.neo4j.driver.Record> records = new ArrayList<>();
        
        try (Session session = driver.session(sessionConfig)) {
            Result result;
            if (parameters != null && parameters.length > 0) {
                result = session.run(cypher, Values.parameters(parameters));
            } else {
                result = session.run(cypher);
            }
            
            while (result.hasNext()) {
                org.neo4j.driver.Record record = result.next();
                log.info("Record keys: {}, values: {}", record.keys(), record.values());
                records.add(record);
            }
            
            log.debug("Executed Cypher: {}, returned {} records", cypher, records.size());
            
        } catch (Exception e) {
            log.error("Failed to execute Cypher query: {}", cypher, e);
            throw new RuntimeException("Query execution failed: " + e.getMessage(), e);
        }
        
        return records;
    }
    
    @Override
    public void close() {
        if (driver != null) {
            driver.close();
            this.connected = false;
            log.info("TuGraph connection closed");
        }
    }
    
    @Override
    public boolean isConnected() {
        if (!connected) {
            return false;
        }
        
        try (Session session = driver.session(sessionConfig)) {
            session.run("RETURN 1").consume();
            return true;
        } catch (Exception e) {
            this.connected = false;
            return false;
        }
    }
    
    @Override
    public TuGraphConfig getConfig() {
        return config;
    }
}
