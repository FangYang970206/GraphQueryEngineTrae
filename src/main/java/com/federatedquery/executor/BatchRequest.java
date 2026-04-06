package com.federatedquery.executor;

import com.federatedquery.plan.ExternalQuery;

import java.util.*;

public class BatchRequest {
    private String id;
    private String dataSource;
    private String operator;
    private List<String> inputIds = new ArrayList<>();
    private String inputIdField;
    private List<String> outputFields = new ArrayList<>();
    private List<ExternalQuery> originalQueries = new ArrayList<>();
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getDataSource() {
        return dataSource;
    }
    
    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }
    
    public String getOperator() {
        return operator;
    }
    
    public void setOperator(String operator) {
        this.operator = operator;
    }
    
    public List<String> getInputIds() {
        return inputIds;
    }
    
    public void setInputIds(List<String> inputIds) {
        this.inputIds = inputIds;
    }
    
    public String getInputIdField() {
        return inputIdField;
    }
    
    public void setInputIdField(String inputIdField) {
        this.inputIdField = inputIdField;
    }
    
    public List<String> getOutputFields() {
        return outputFields;
    }
    
    public void setOutputFields(List<String> outputFields) {
        this.outputFields = outputFields;
    }
    
    public List<ExternalQuery> getOriginalQueries() {
        return originalQueries;
    }
    
    public void setOriginalQueries(List<ExternalQuery> originalQueries) {
        this.originalQueries = originalQueries;
    }
}
