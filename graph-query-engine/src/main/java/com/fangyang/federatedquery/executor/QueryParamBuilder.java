package com.fangyang.federatedquery.executor;

import com.fangyang.datasource.DataSourceQueryParams;
import com.fangyang.federatedquery.plan.ExternalQuery;

class QueryParamBuilder {
    DataSourceQueryParams build(ExternalQuery query) {
        DataSourceQueryParams params = new DataSourceQueryParams();
        params.setDataSource(query.getDataSource());
        params.setOperator(query.getOperator());
        params.setTargetLabel(query.getTargetLabel());
        params.setInputIds(query.getInputIds());
        params.setInputIdField(query.getInputIdField());
        params.setOutputIdField(query.getOutputIdField());
        params.setOutputVariables(query.getOutputVariables());
        params.setOutputFields(query.getOutputFields());
        params.setFilters(query.getFilters());
        params.setFilterConditions(query.getFilterConditions());
        params.setParameters(query.getParameters());
        if (query.getInputMapping() != null) {
            params.setInputMapping(query.getInputMapping());
        }
        return params;
    }

    DataSourceQueryParams build(BatchRequest batch) {
        DataSourceQueryParams params = new DataSourceQueryParams();
        params.setDataSource(batch.getDataSource());
        params.setOperator(batch.getOperator());
        params.setInputIds(batch.getInputIds());
        params.setInputIdField(batch.getInputIdField());
        params.setOutputIdField(batch.getOutputIdField());
        params.setOutputFields(batch.getOutputFields());
        params.setOutputVariables(batch.getOutputVariables());
        params.setFilters(batch.getFilters());
        params.setFilterConditions(batch.getFilterConditions());
        return params;
    }
}
