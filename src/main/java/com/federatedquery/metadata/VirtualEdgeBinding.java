package com.federatedquery.metadata;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class VirtualEdgeBinding {
    private String edgeType;
    private String targetDataSource;
    private String targetLabel;
    private String operatorName;
    private Map<String, String> idMapping = new HashMap<>();
    private List<String> outputFields = new ArrayList<>();
    private boolean firstHopOnly = false;
    private boolean lastHopOnly = false;
}
