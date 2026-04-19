package com.fangyang.datasource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
class TuGraphAdapterImpl implements DataSourceAdapter {
    
    private static final Logger log = LoggerFactory.getLogger(TuGraphAdapterImpl.class);
    
    private final TuGraphConnector connector;
    private final String dataSourceName;
    
    TuGraphAdapterImpl(TuGraphConnector connector) {
        this(connector, "tugraph");
    }
    
    TuGraphAdapterImpl(TuGraphConnector connector, String dataSourceName) {
        this.connector = connector;
        this.dataSourceName = dataSourceName;
    }
    
    @Override
    public String getDataSourceType() {
        return "TUGRAPH_BOLT";
    }
    
    @Override
    public String getDataSourceName() {
        return dataSourceName;
    }
    
    @Override
    public boolean isHealthy() {
        return connector != null && connector.isConnected();
    }
    
    @Override
    public List<org.neo4j.driver.Record> executeTuGraphQuery(String cypher) {
        log.debug("Executing TuGraph query: {}", cypher);
        List<org.neo4j.driver.Record> records = connector.executeQuery(cypher);
        log.info("Query returned {} records", records != null ? records.size() : 0);
        return records;
    }
    
    @Override
    public List<org.neo4j.driver.Record> executeTuGraphQuery(String cypher, Map<String, Object> params) {
        log.debug("Executing TuGraph query: {} with {} parameters", cypher, 
                params != null ? params.size() : 0);
        
        if (params != null && !params.isEmpty()) {
            Object[] paramArray = convertParametersToArray(params);
            List<org.neo4j.driver.Record> records = connector.executeQuery(cypher, paramArray);
            log.info("Query returned {} records", records != null ? records.size() : 0);
            return records;
        } else {
            return executeTuGraphQuery(cypher);
        }
    }
    
    @Override
    public List<Map<String, Object>> executeExternalQuery(DataSourceQueryParams params) {
        throw new UnsupportedOperationException("TuGraphAdapter does not support external data source queries");
    }
    
    private Object[] convertParametersToArray(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return null;
        }
        
        List<Object> paramList = new ArrayList<>();
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            paramList.add(entry.getKey());
            paramList.add(entry.getValue());
        }
        
        return paramList.toArray();
    }
}
