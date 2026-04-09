package com.federatedquery.connector;

import java.util.List;

public interface TuGraphConnector {
    
    List<org.neo4j.driver.Record> executeQuery(String cypher);
    
    List<org.neo4j.driver.Record> executeQuery(String cypher, Object... parameters);
    
    void close();
    
    boolean isConnected();
    
    TuGraphConfig getConfig();
}
