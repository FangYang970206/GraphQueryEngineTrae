package com.fangyang.federatedquery.executor;

import com.fangyang.datasource.QueryFilter;
import com.fangyang.federatedquery.plan.ExternalQuery;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class BatchRequest {
    private String id;
    private String dataSource;
    private String operator;
    private List<String> inputIds = new ArrayList<>();
    private String inputIdField;
    private String outputIdField;
    private List<String> outputFields = new ArrayList<>();
    private List<String> outputVariables = new ArrayList<>();
    private Map<String, Object> filters = new LinkedHashMap<>();
    private List<QueryFilter> filterConditions = new ArrayList<>();
    private List<ExternalQuery> originalQueries = new ArrayList<>();
}
