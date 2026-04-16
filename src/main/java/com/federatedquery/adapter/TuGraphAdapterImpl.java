package com.federatedquery.adapter;

import com.federatedquery.connector.TuGraphConnector;
import com.federatedquery.exception.ErrorCode;
import com.federatedquery.exception.GraphQueryException;
import com.federatedquery.plan.ExternalQuery;
import org.neo4j.driver.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class TuGraphAdapterImpl implements DataSourceAdapter {
    
    private static final Logger log = LoggerFactory.getLogger(TuGraphAdapterImpl.class);
    
    private final TuGraphConnector connector;
    private final String dataSourceName;
    
    public TuGraphAdapterImpl(TuGraphConnector connector) {
        this(connector, "tugraph");
    }
    
    public TuGraphAdapterImpl(TuGraphConnector connector, String dataSourceName) {
        this.connector = connector;
        this.dataSourceName = dataSourceName;
    }
    
    @Override
    public String getDataSourceType() {
        return "TUGRAPH_BOLT";
    }
    
    @Override
    public CompletableFuture<QueryResult> execute(ExternalQuery query) {
        return CompletableFuture.supplyAsync(() -> executeSync(query));
    }
    
    @Override
    public QueryResult executeSync(ExternalQuery query) {
        long startTime = System.currentTimeMillis();
        
        try {
            String cypher = buildCypherQuery(query);
            Map<String, Object> parameters = query.getParameters();
            
            log.debug("Executing TuGraph query: {} with {} parameters", cypher, 
                    parameters != null ? parameters.size() : 0);
            
            List<Record> records;
            if (parameters != null && !parameters.isEmpty()) {
                Object[] paramArray = convertParametersToArray(parameters);
                records = connector.executeQuery(cypher, paramArray);
            } else {
                records = connector.executeQuery(cypher);
            }
            
            log.info("Query returned {} records", records != null ? records.size() : 0);
            
            QueryResult result = convertRecordsToResult(records, query);
            
            log.info("Converted to {} entities", result.getEntities().size());
            result.setExecutionTimeMs(System.currentTimeMillis() - startTime);
            result.setDataSource(dataSourceName);
            
            log.debug("TuGraph query returned {} entities in {}ms", result.getEntities().size(), result.getExecutionTimeMs());
            
            return result;
            
        } catch (GraphQueryException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to execute TuGraph query: {}", query.getOperator(), e);
            throw new GraphQueryException(ErrorCode.DATASOURCE_QUERY_ERROR, 
                    "TuGraph query failed: " + e.getMessage(), e);
        }
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
    
    @Override
    public boolean isHealthy() {
        return connector != null && connector.isConnected();
    }
    
    @Override
    public String getDataSourceName() {
        return dataSourceName;
    }
    
    private String buildCypherQuery(ExternalQuery query) {
        Map<String, Object> filters = query.getFilters();
        
        if (filters != null && filters.containsKey("cypher")) {
            return String.valueOf(filters.get("cypher"));
        }
        
        StringBuilder cypher = new StringBuilder();
        
        String operator = query.getOperator();
        Map<String, Object> parameters = query.getParameters();
        
        if (operator != null && !operator.isEmpty()) {
            if (operator.startsWith("MATCH") || operator.startsWith("match")) {
                cypher.append(operator);
            } else {
                cypher.append("MATCH ").append(operator);
            }
        } else {
            cypher.append("MATCH (n) RETURN n");
        }
        
        if (filters != null && !filters.isEmpty()) {
            String cypherStr = cypher.toString();
            if (!cypherStr.toUpperCase().contains(" WHERE ")) {
                cypher.append(" WHERE ");
            } else {
                cypher.append(" AND ");
            }
            
            List<String> conditions = new ArrayList<>();
            for (Map.Entry<String, Object> entry : filters.entrySet()) {
                String key = entry.getKey();
                if ("cypher".equals(key)) {
                    continue;
                }
                
                Object value = entry.getValue();
                
                if (key.contains(".")) {
                    conditions.add(String.format("%s = '%s'", key, value));
                } else {
                    conditions.add(String.format("n.%s = '%s'", key, value));
                }
            }
            
            if (!conditions.isEmpty()) {
                cypher.append(String.join(" AND ", conditions));
            }
        }
        
        if (query.hasInputIds()) {
            String cypherStr = cypher.toString();
            String inputIdField = query.getInputIdField();
            if (inputIdField == null || inputIdField.isEmpty()) {
                inputIdField = "id";
            }
            
            List<String> ids = query.getInputIds();
            String idList = String.join("', '", ids);
            
            if (!cypherStr.toUpperCase().contains(" WHERE ")) {
                cypher.append(" WHERE ");
            } else {
                cypher.append(" AND ");
            }
            
            cypher.append(String.format("n.%s IN ['%s']", inputIdField, idList));
        }
        
        String cypherStr = cypher.toString();
        if (!cypherStr.toUpperCase().contains(" RETURN ")) {
            List<String> outputVars = query.getOutputVariables();
            if (outputVars != null && !outputVars.isEmpty()) {
                cypher.append(" RETURN ").append(String.join(", ", outputVars));
            } else {
                cypher.append(" RETURN n");
            }
        }
        
        return cypher.toString();
    }
    
    private QueryResult convertRecordsToResult(List<Record> records, ExternalQuery query) {
        QueryResult result = new QueryResult();
        if (records == null || records.isEmpty()) {
            log.info("Records is null or empty");
            return result;
        }
        
        log.info("Processing {} records", records.size());
        
        List<String> outputFields = query.getOutputFields();
        
        for (int recordIndex = 0; recordIndex < records.size(); recordIndex++) {
            Record record = records.get(recordIndex);
            List<String> keys = new ArrayList<>(record.keys());
            
            log.info("Record keys: {}, size: {}", keys, keys.size());
            
            if (keys.isEmpty()) {
                log.info("Keys is empty, skipping record");
                continue;
            }
            
            log.info("Record keys: {}", keys);
            QueryResult.ResultRow row = new QueryResult.ResultRow();
            row.setRowId(query.getId() + "#" + recordIndex);
            
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i);
                org.neo4j.driver.types.Node node = null;
                
                try {
                    org.neo4j.driver.Value value = record.get(key);
                    log.info("Key: {}, Value type: {}, IsNull: {}", key, value != null ? value.type() : "null", value != null && value.isNull());
                    
                    if (value != null && !value.isNull()) {
                        try {
                            node = value.asNode();
                            log.info("Successfully converted key '{}' to Node", key);
                        } catch (Exception e) {
                            log.info("Value for key '{}' is not a Node: {}", key, e.getMessage());
                            continue;
                        }
                    }
                } catch (Exception e) {
                    log.info("Error getting value for key '{}': {}", key, e.getMessage());
                    continue;
                }
                
                if (node != null) {
                    GraphEntity entity = convertNodeToEntity(node, key, outputFields);
                    if (entity != null) {
                        result.addEntity(entity);
                        row.put(key, entity);
                    }
                }
            }
            
            if (!row.getEntitiesByVariable().isEmpty()) {
                result.addRow(row);
            }
        }
        
        return result;
    }
    
    private GraphEntity convertNodeToEntity(org.neo4j.driver.types.Node node, String variableName, List<String> outputFields) {
        if (node == null) {
            return null;
        }
        
        GraphEntity entity = new GraphEntity();
        
        entity.setVariableName(variableName);
        entity.setType(GraphEntity.EntityType.NODE);
        
        String label = "Unknown";
        Iterator<String> labelIterator = node.labels().iterator();
        if (labelIterator.hasNext()) {
            label = labelIterator.next();
        }
        entity.setLabel(label);
        
        Map<String, Object> properties = new HashMap<>();
        for (String propKey : node.keys()) {
            Object value = node.get(propKey).asObject();
            properties.put(propKey, value);
        }
        entity.setProperties(properties);
        
        if (properties.containsKey("resId")) {
            entity.setId(String.valueOf(properties.get("resId")));
        } else if (properties.containsKey("id")) {
            entity.setId(String.valueOf(properties.get("id")));
        }
        
        if (outputFields != null && !outputFields.isEmpty()) {
            Map<String, Object> filteredProps = new HashMap<>();
            for (String field : outputFields) {
                if (properties.containsKey(field)) {
                    filteredProps.put(field, properties.get(field));
                }
            }
            entity.setProperties(filteredProps);
        }
        
        return entity;
    }
}
