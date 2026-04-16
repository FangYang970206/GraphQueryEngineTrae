package com.federatedquery.mockserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class AlarmDataHandler extends BaseDataHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(AlarmDataHandler.class);

    public AlarmDataHandler(MockDataRepository repository) {
        super(repository);
    }

    @Override
    protected Logger getLogger() {
        return logger;
    }

    @Override
    protected String getRequiredParamName() {
        return "medn";
    }

    @Override
    protected String getLogPrefix() {
        return "ALARM";
    }

    @Override
    protected List<Map<String, Object>> queryData(String keyParam, Long timestamp, String strategy) {
        if (timestamp != null) {
            return repository.queryAlarmsByMednAndTime(keyParam, timestamp, strategy);
        }
        return repository.queryAlarmsByMedn(keyParam);
    }
}
