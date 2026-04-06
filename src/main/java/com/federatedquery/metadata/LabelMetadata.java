package com.federatedquery.metadata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LabelMetadata {
    private String label;
    private boolean virtual = false;
    private String dataSource;
    private Map<String, String> propertyMapping = new HashMap<>();
    private List<String> requiredProperties = new ArrayList<>();
    private String idProperty = "id";
    
    public String getLabel() {
        return label;
    }
    
    public void setLabel(String label) {
        this.label = label;
    }
    
    public boolean isVirtual() {
        return virtual;
    }
    
    public void setVirtual(boolean virtual) {
        this.virtual = virtual;
    }
    
    public String getDataSource() {
        return dataSource;
    }
    
    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }
    
    public Map<String, String> getPropertyMapping() {
        return propertyMapping;
    }
    
    public void setPropertyMapping(Map<String, String> propertyMapping) {
        this.propertyMapping = propertyMapping;
    }
    
    public List<String> getRequiredProperties() {
        return requiredProperties;
    }
    
    public void setRequiredProperties(List<String> requiredProperties) {
        this.requiredProperties = requiredProperties;
    }
    
    public String getIdProperty() {
        return idProperty;
    }
    
    public void setIdProperty(String idProperty) {
        this.idProperty = idProperty;
    }
}
