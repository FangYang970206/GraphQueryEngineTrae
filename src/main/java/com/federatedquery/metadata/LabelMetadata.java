package com.federatedquery.metadata;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class LabelMetadata {
    private String label;
    private boolean virtual = false;
    private String dataSource;
    private Map<String, String> propertyMapping = new HashMap<>();
    private List<String> requiredProperties = new ArrayList<>();
    private String idProperty = "id";
}
