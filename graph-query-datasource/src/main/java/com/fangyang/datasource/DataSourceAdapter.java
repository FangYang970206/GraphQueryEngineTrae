package com.fangyang.datasource;

import org.neo4j.driver.Record;

import java.util.List;
import java.util.Map;

public interface DataSourceAdapter {
    String getDataSourceType();
    String getDataSourceName();
    boolean isHealthy();
    
    List<Record> executeTuGraphQuery(String cypher);
    List<Record> executeTuGraphQuery(String cypher, Map<String, Object> params);
    
    List<Map<String, Object>> executeExternalQuery(DataSourceQueryParams params);
}
