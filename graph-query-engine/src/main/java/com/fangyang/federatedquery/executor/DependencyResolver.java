package com.fangyang.federatedquery.executor;

import com.fangyang.federatedquery.model.GraphEntity;
import com.fangyang.federatedquery.plan.PhysicalQuery;
import com.fangyang.federatedquery.plan.ExternalQuery;
import com.fangyang.federatedquery.model.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class DependencyResolver {
    private static final Logger log = LoggerFactory.getLogger(DependencyResolver.class);

    public Map<String, Map<String, Set<String>>> extractIdsAndPropertiesFromPhysicalResults(ExecutionResult result) {
        return extractIdsAndProperties(result.getPhysicalResults().values(), Collections.emptyList());
    }

    public Map<String, Map<String, Set<String>>> extractIdsAndPropertiesFromExternalResults(ExecutionResult result) {
        return extractIdsAndProperties(Collections.emptyList(), result.getExternalResults());
    }

    private Map<String, Map<String, Set<String>>> extractIdsAndProperties(
            Collection<List<QueryResult>> physicalResults,
            Collection<QueryResult> directResults) {
        Map<String, Map<String, Set<String>>> dataByVariable = new HashMap<>();

        for (List<QueryResult> qrList : physicalResults) {
            for (QueryResult qr : qrList) {
                for (GraphEntity entity : qr.getEntities()) {
                    String varName = entity.getVariableName();
                    if (varName == null) {
                        varName = entity.getLabel();
                    }
                    if (varName != null) {
                        addEntityData(dataByVariable, varName, entity);
                    }

                    String label = entity.getLabel();
                    if (label != null && !label.equals(varName)) {
                        addEntityData(dataByVariable, label, entity);
                    }
                }
            }
        }

        for (QueryResult qr : directResults) {
            for (GraphEntity entity : qr.getEntities()) {
                String varName = entity.getVariableName();
                if (varName == null) {
                    varName = entity.getLabel();
                }
                if (varName != null) {
                    addEntityData(dataByVariable, varName, entity);
                }

                String label = entity.getLabel();
                if (label != null && !label.equals(varName)) {
                    addEntityData(dataByVariable, label, entity);
                }
            }
        }

        log.debug("Extracted data by variable: {}", dataByVariable.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().keySet())));

        return dataByVariable;
    }

    private void addEntityData(
            Map<String, Map<String, Set<String>>> dataByVariable,
            String key,
            GraphEntity entity) {
        Map<String, Set<String>> propMap = dataByVariable.computeIfAbsent(key, ignored -> new HashMap<>());
        if (entity.getId() != null) {
            propMap.computeIfAbsent("_id", ignored -> new HashSet<>()).add(entity.getId());
        }
        if (entity.getProperties() == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : entity.getProperties().entrySet()) {
            if (entry.getValue() != null) {
                propMap.computeIfAbsent(entry.getKey(), ignored -> new HashSet<>())
                        .add(String.valueOf(entry.getValue()));
            }
        }
    }

    public void populateDependentQueryInputIds(
            List<ExternalQuery> dependentQueries,
            Map<String, Map<String, Set<String>>> dataByVariable) {
        for (ExternalQuery dependentQuery : dependentQueries) {
            String sourceVar = dependentQuery.getSourceVariableName();
            String inputIdField = dependentQuery.getInputIdField();

            if (sourceVar == null || !dataByVariable.containsKey(sourceVar)) {
                log.warn("No data found for source variable: {}", sourceVar);
                continue;
            }

            Map<String, Set<String>> propMap = dataByVariable.get(sourceVar);
            Set<String> ids = resolveInputIds(propMap, inputIdField);
            if (ids == null || ids.isEmpty()) {
                log.warn("No IDs found for source variable: {} with field: {}", sourceVar, inputIdField);
                continue;
            }

            dependentQuery.getInputIds().clear();
            dependentQuery.getInputIds().addAll(ids);
            log.debug("Populated {} IDs for external query {} from variable {} (field: {})",
                    ids.size(), dependentQuery.getId(), sourceVar, inputIdField != null ? inputIdField : "_id");
        }
    }

    public void populateDependentPhysicalQueryInputIds(
            List<PhysicalQuery> dependentQueries,
            Map<String, Map<String, Set<String>>> dataByVariable) {
        for (PhysicalQuery dependentQuery : dependentQueries) {
            String sourceVar = dependentQuery.getSourceVariableName();
            String sourceField = dependentQuery.getDependencySourceField();

            if (sourceVar == null || !dataByVariable.containsKey(sourceVar)) {
                log.warn("No data found for source variable: {}", sourceVar);
                continue;
            }

            Map<String, Set<String>> propMap = dataByVariable.get(sourceVar);
            Set<String> ids = resolveInputIds(propMap, sourceField);
            if (ids == null || ids.isEmpty()) {
                log.warn("No IDs found for source variable: {} with field: {}", sourceVar, sourceField);
                continue;
            }

            dependentQuery.getInputIds().clear();
            dependentQuery.getInputIds().addAll(ids);
            if (dependentQuery.getDependencyParameterName() != null
                    && !dependentQuery.getDependencyParameterName().isEmpty()) {
                dependentQuery.getParameters().put(
                        dependentQuery.getDependencyParameterName(),
                        new ArrayList<>(ids));
            }
            log.debug("Populated {} IDs for physical query {} from variable {} (field: {})",
                    ids.size(), dependentQuery.getId(), sourceVar, sourceField != null ? sourceField : "_id");
        }
    }

    private Set<String> resolveInputIds(Map<String, Set<String>> propMap, String inputIdField) {
        if (propMap == null) {
            return null;
        }
        if (inputIdField != null && propMap.containsKey(inputIdField)) {
            Set<String> ids = propMap.get(inputIdField);
            log.debug("Using property '{}' with {} values", inputIdField, ids.size());
            return ids;
        }
        if (propMap.containsKey("_id")) {
            Set<String> ids = propMap.get("_id");
            log.debug("Using _id with {} values", ids.size());
            return ids;
        }
        return null;
    }

    public DependencyClassification classifyDependentQueries(List<ExternalQuery> dependentQueries) {
        List<ExternalQuery> readyQueries = dependentQueries.stream()
                .filter(ExternalQuery::isReadyToExecute)
                .collect(Collectors.toList());

        List<ExternalQuery> notReadyQueries = dependentQueries.stream()
                .filter(q -> !q.isReadyToExecute())
                .collect(Collectors.toList());

        List<ExternalQuery> directQueries = readyQueries.stream()
                .filter(q -> !q.hasInputIds() || q.getInputIds().size() <= 1)
                .collect(Collectors.toList());

        List<ExternalQuery> batchQueries = readyQueries.stream()
                .filter(q -> q.hasInputIds() && q.getInputIds().size() > 1)
                .collect(Collectors.toList());

        return new DependencyClassification(readyQueries, notReadyQueries, directQueries, batchQueries);
    }

    public PhysicalDependencyClassification classifyDependentPhysicalQueries(List<PhysicalQuery> dependentQueries) {
        List<PhysicalQuery> readyQueries = dependentQueries.stream()
                .filter(PhysicalQuery::isReadyToExecute)
                .collect(Collectors.toList());

        List<PhysicalQuery> notReadyQueries = dependentQueries.stream()
                .filter(query -> !query.isReadyToExecute())
                .collect(Collectors.toList());

        return new PhysicalDependencyClassification(readyQueries, notReadyQueries);
    }

    public static class DependencyClassification {
        private final List<ExternalQuery> readyQueries;
        private final List<ExternalQuery> notReadyQueries;
        private final List<ExternalQuery> directQueries;
        private final List<ExternalQuery> batchQueries;

        public DependencyClassification(
                List<ExternalQuery> readyQueries,
                List<ExternalQuery> notReadyQueries,
                List<ExternalQuery> directQueries,
                List<ExternalQuery> batchQueries) {
            this.readyQueries = readyQueries;
            this.notReadyQueries = notReadyQueries;
            this.directQueries = directQueries;
            this.batchQueries = batchQueries;
        }

        public List<ExternalQuery> getReadyQueries() { return readyQueries; }
        public List<ExternalQuery> getNotReadyQueries() { return notReadyQueries; }
        public List<ExternalQuery> getDirectQueries() { return directQueries; }
        public List<ExternalQuery> getBatchQueries() { return batchQueries; }
    }

    public static class PhysicalDependencyClassification {
        private final List<PhysicalQuery> readyQueries;
        private final List<PhysicalQuery> notReadyQueries;

        public PhysicalDependencyClassification(
                List<PhysicalQuery> readyQueries,
                List<PhysicalQuery> notReadyQueries) {
            this.readyQueries = readyQueries;
            this.notReadyQueries = notReadyQueries;
        }

        public List<PhysicalQuery> getReadyQueries() { return readyQueries; }
        public List<PhysicalQuery> getNotReadyQueries() { return notReadyQueries; }
    }
}
