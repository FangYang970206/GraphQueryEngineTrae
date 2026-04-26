package com.fangyang.federatedquery.executor;

import com.fangyang.datasource.DataSourceAdapter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class AdapterRegistry {
    private final Map<String, DataSourceAdapter> adapters = new ConcurrentHashMap<>();

    void register(String name, DataSourceAdapter adapter) {
        adapters.put(name, adapter);
    }

    DataSourceAdapter resolve(String name) {
        if (name == null) {
            return null;
        }

        DataSourceAdapter adapter = adapters.get(name);
        if (adapter == null) {
            adapter = adapters.get(name.toLowerCase());
        }
        if (adapter == null) {
            adapter = adapters.values().stream()
                    .filter(candidate -> candidate.getDataSourceName().equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(null);
        }
        return adapter;
    }
}
