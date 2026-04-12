package com.federatedquery.executor;

import com.federatedquery.plan.ExternalQuery;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

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
    private List<ExternalQuery> originalQueries = new ArrayList<>();
}
