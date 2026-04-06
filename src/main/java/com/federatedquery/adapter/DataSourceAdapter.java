package com.federatedquery.adapter;

import com.federatedquery.plan.ExternalQuery;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface DataSourceAdapter {
    String getDataSourceType();
    
    CompletableFuture<QueryResult> execute(ExternalQuery query);
    
    QueryResult executeSync(ExternalQuery query);
    
    boolean isHealthy();
    
    String getDataSourceName();
}
