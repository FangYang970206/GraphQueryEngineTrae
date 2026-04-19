package com.fangyang.metadata;

public final class MetadataFactory {
    private static MetadataRegistryImpl INSTANCE;
    
    private MetadataFactory() {
    }
    
    public static synchronized MetadataRegistryImpl createRegistry() {
        if (INSTANCE == null) {
            INSTANCE = new MetadataRegistryImpl();
        }
        return INSTANCE;
    }
    
    public static synchronized MetadataQueryService createQueryService() {
        return createRegistry();
    }
    
    public static synchronized MetadataRegistrar createRegistrar() {
        return createRegistry();
    }
    
    public static synchronized void reset() {
        INSTANCE = null;
    }
}
