package com.fangyang.datasource;

import lombok.Data;

import java.util.*;

@Data
public class DataSourceQueryParams {
    private String dataSource;
    private String operator;
    private String targetLabel;
    private Map<String, Object> inputMapping = new HashMap<>();
    private List<String> inputIds = new ArrayList<>();
    private String inputIdField;
    private String outputIdField;
    private List<String> outputVariables = new ArrayList<>();
    private List<String> outputFields = new ArrayList<>();
    private Map<String, Object> filters = new HashMap<>();
    private List<QueryFilter> filterConditions = new ArrayList<>();
    private Map<String, Object> parameters = new HashMap<>();
}
