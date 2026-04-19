package com.fangyang.datasource;

public final class DataSourceFactory {
    private DataSourceFactory() {
    }
    
    public static DataSourceAdapter createTuGraphAdapter(TuGraphConnector connector) {
        return new TuGraphAdapterImpl(connector);
    }
    
    public static DataSourceAdapter createTuGraphAdapter(TuGraphConnector connector, String dataSourceName) {
        return new TuGraphAdapterImpl(connector, dataSourceName);
    }
    
    public static TuGraphConnector createTuGraphConnector(TuGraphConfig config) {
        return new TuGraphConnectorImpl(config);
    }
    
    public static TuGraphConnector createTuGraphConnector() {
        return createTuGraphConnector(TuGraphConfig.defaultConfig());
    }
}
