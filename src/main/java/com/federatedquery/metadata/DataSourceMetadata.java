package com.federatedquery.metadata;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class DataSourceMetadata {
    private String name;
    private DataSourceType type;
    private String endpoint;
    private Map<String, Object> config = new HashMap<>();
    private int timeoutMs = 5000;
    private int maxRetries = 3;
}
