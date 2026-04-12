package com.federatedquery.e2e;

import com.federatedquery.adapter.GraphEntity;

final class E2EGraphEntityFactory {
    private E2EGraphEntityFactory() {
    }

    static GraphEntity createNEEntity(String id, String name, String type) {
        return createNode(id, "NetworkElement", "name", name, "type", type);
    }

    static GraphEntity createLTPEntity(String id, String name, String type) {
        return createNode(id, "LTP", "name", name, "type", type);
    }

    static GraphEntity createPersonEntity(String id, String name) {
        return createNode(id, "Person", "name", name);
    }

    static GraphEntity createCardEntity(String id, String label, String name) {
        return createNode(id, label, "name", name);
    }

    static GraphEntity createKPIEntity(String id, String name, double value) {
        return createNode(id, "KPI", "name", name, "value", value);
    }

    static GraphEntity createAlarmEntity(String id, String severity, String message) {
        return createNode(id, "Alarm", "severity", severity, "message", message);
    }

    static GraphEntity createElementEntity(String id, String name, String type) {
        return createNode(id, "Element", "name", name, "type", type);
    }

    static GraphEntity createPortEntity(String id, String name, String interfaceName) {
        return createNode(id, "Port", "name", name, "interface", interfaceName);
    }

    private static GraphEntity createNode(String id, String label, Object... properties) {
        GraphEntity entity = GraphEntity.node(id, label);
        for (int i = 0; i < properties.length; i += 2) {
            entity.setProperty(String.valueOf(properties[i]), properties[i + 1]);
        }
        return entity;
    }
}
