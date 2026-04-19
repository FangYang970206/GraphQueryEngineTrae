package com.fangyang.federatedquery.mockserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class KpiDataHandler extends BaseDataHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(KpiDataHandler.class);

    public KpiDataHandler(MockDataRepository repository) {
        super(repository);
    }

    @Override
    protected Logger getLogger() {
        return logger;
    }

    @Override
    protected String getRequiredParamName() {
        return "parentResId";
    }

    @Override
    protected String getLogPrefix() {
        return "KPI";
    }

    @Override
    protected List<Map<String, Object>> queryData(String keyParam, Long timestamp, String strategy) {
        if (timestamp != null) {
            return repository.queryKpiByParentResIdAndTime(keyParam, timestamp, strategy);
        }
        return repository.queryKpiByParentResId(keyParam);
    }
}
