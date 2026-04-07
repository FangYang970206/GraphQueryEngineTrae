package com.federatedquery.executor;

import com.federatedquery.plan.ExternalQuery;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

@Data
public class BatchRequest {
    private String id;
    private String dataSource;
    private String operator;
    private List<String> inputIds = new ArrayList<>();
    private String inputIdField;
    private List<String> outputFields = new ArrayList<>();
    private List<String> outputVariables = new ArrayList<>();
    private Map<String, Object> filters = new HashMap<>();
    private Map<String, Object> parameters = new HashMap<>();
    private String snapshotName;
    private Object snapshotTime;
    private List<ExternalQuery> originalQueries = new ArrayList<>();
}
