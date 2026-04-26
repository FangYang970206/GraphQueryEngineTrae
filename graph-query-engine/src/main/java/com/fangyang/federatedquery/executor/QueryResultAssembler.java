package com.fangyang.federatedquery.executor;

import com.fangyang.federatedquery.model.GraphEntity;
import com.fangyang.federatedquery.model.QueryResult;
import com.fangyang.federatedquery.plan.ExternalQuery;
import com.fangyang.metadata.LabelMetadata;
import com.fangyang.metadata.MetadataQueryService;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

class QueryResultAssembler {
    private static final Logger log = LoggerFactory.getLogger(QueryResultAssembler.class);

    private final Supplier<MetadataQueryService> metadataQueryServiceSupplier;
    private final Supplier<ResultEnricher> resultEnricherSupplier;

    QueryResultAssembler(
            Supplier<MetadataQueryService> metadataQueryServiceSupplier,
            Supplier<ResultEnricher> resultEnricherSupplier) {
        this.metadataQueryServiceSupplier = metadataQueryServiceSupplier;
        this.resultEnricherSupplier = resultEnricherSupplier;
    }

    QueryResult fromRecords(List<Record> records, String dataSource) {
        QueryResult result = new QueryResult();
        result.setDataSource(dataSource);
        result.setData(records);

        if (records == null) {
            result.setEntities(new ArrayList<>());
            result.setRows(new ArrayList<>());
            return result;
        }

        List<GraphEntity> entities = new ArrayList<>();
        List<QueryResult.ResultRow> rows = new ArrayList<>();
        int rowIndex = 0;
        for (Record record : records) {
            QueryResult.ResultRow row = new QueryResult.ResultRow();
            row.setRowId(dataSource + "#row-" + rowIndex++);
            for (String key : record.keys()) {
                GraphEntity entity = convertValueToEntity(key, record.get(key));
                if (entity != null) {
                    entities.add(entity);
                    row.put(key, entity);
                }
            }
            if (!row.getEntitiesByVariable().isEmpty()) {
                rows.add(row);
            }
        }

        result.setEntities(entities);
        result.setRows(rows);
        return result;
    }

    QueryResult fromMaps(List<Map<String, Object>> maps, String dataSource, String label) {
        QueryResult result = new QueryResult();
        result.setDataSource(dataSource);
        result.setEntities(convertMapsToEntities(maps, label));
        result.setData(maps);
        return result;
    }

    QueryResult normalizeExternalResult(ExternalQuery query, QueryResult result) {
        ResultEnricher resultEnricher = resultEnricherSupplier.get();
        return resultEnricher != null ? resultEnricher.applyExternalQueryMetadata(query, result) : result;
    }

    QueryResult enrichDependentResult(ExternalQuery query, QueryResult queryResult, ExecutionResult executionResult) {
        QueryResult normalized = normalizeExternalResult(query, queryResult);
        ResultEnricher resultEnricher = resultEnricherSupplier.get();
        return resultEnricher != null
                ? resultEnricher.enrichExternalResultWithSourceRows(query, normalized, executionResult)
                : normalized;
    }

    List<QueryResult> unbatchAndEnrich(
            BatchRequest batch,
            QueryResult batchResult,
            ExecutionResult executionResult,
            BatchingStrategy batchingStrategy) {
        List<QueryResult> unbatchedResults = batchingStrategy.unbatch(batch, batchResult);
        List<QueryResult> assembledResults = new ArrayList<>();
        for (int i = 0; i < unbatchedResults.size(); i++) {
            ExternalQuery originalQuery = i < batch.getOriginalQueries().size()
                    ? batch.getOriginalQueries().get(i)
                    : null;
            QueryResult currentResult = originalQuery != null
                    ? enrichDependentResult(originalQuery, unbatchedResults.get(i), executionResult)
                    : unbatchedResults.get(i);
            assembledResults.add(currentResult);
        }
        return assembledResults;
    }

    QueryResult buildUnionResult(List<CompletableFuture<ExecutionResult>> subFutures) {
        List<ExecutionResult> subResults = new ArrayList<>();
        for (CompletableFuture<ExecutionResult> future : subFutures) {
            subResults.add(future.join());
        }
        return buildUnionResultFromResults(subResults);
    }

    QueryResult buildUnionResultFromResults(List<ExecutionResult> subResults) {
        QueryResult combined = new QueryResult();
        for (ExecutionResult subResult : subResults) {
            for (List<QueryResult> results : subResult.getPhysicalResults().values()) {
                for (QueryResult result : results) {
                    combined.getEntities().addAll(result.getEntities());
                }
            }
            for (QueryResult result : subResult.getBatchResults().values()) {
                combined.getEntities().addAll(result.getEntities());
            }
            for (QueryResult result : subResult.getExternalResults()) {
                combined.getEntities().addAll(result.getEntities());
            }
        }
        combined.setData(subResults);
        return combined;
    }

    private GraphEntity convertValueToEntity(String key, Value value) {
        if (value == null || value.isNull()) {
            return null;
        }

        try {
            if (value.asEntity() instanceof Node) {
                Node node = value.asNode();
                String label = !node.labels().iterator().hasNext() ? "Node" : node.labels().iterator().next();
                Map<String, Object> props = new HashMap<>();
                node.asMap().forEach(props::put);

                GraphEntity entity = GraphEntity.node(
                        resolvePhysicalEntityId(label, props, String.valueOf(node.id())),
                        label
                );
                entity.setProperties(props);
                entity.setVariableName(key);
                return entity;
            }
            if (value.asEntity() instanceof Relationship) {
                Relationship rel = value.asRelationship();
                GraphEntity entity = GraphEntity.edge(
                        String.valueOf(rel.id()),
                        rel.type(),
                        String.valueOf(rel.startNodeId()),
                        String.valueOf(rel.endNodeId())
                );
                Map<String, Object> props = new HashMap<>();
                rel.asMap().forEach(props::put);
                entity.setProperties(props);
                entity.setVariableName(key);
                return entity;
            }
        } catch (Exception e) {
            log.debug("Could not convert value for key {}: {}", key, e.getMessage());
        }

        return null;
    }

    private String resolvePhysicalEntityId(String label, Map<String, Object> props, String fallbackId) {
        if (props == null || props.isEmpty()) {
            return fallbackId;
        }

        String idProperty = null;
        MetadataQueryService metadataQueryService = metadataQueryServiceSupplier.get();
        if (metadataQueryService != null && label != null) {
            idProperty = metadataQueryService.getLabel(label)
                    .map(LabelMetadata::getIdProperty)
                    .orElse(null);
        }

        if (idProperty != null) {
            Object mappedId = props.get(idProperty);
            if (mappedId != null) {
                return String.valueOf(mappedId);
            }
        }

        Object resId = props.get("resId");
        if (resId != null) {
            return String.valueOf(resId);
        }

        Object explicitId = props.get("id");
        if (explicitId != null) {
            return String.valueOf(explicitId);
        }

        return fallbackId;
    }

    private List<GraphEntity> convertMapsToEntities(List<Map<String, Object>> maps, String label) {
        List<GraphEntity> entities = new ArrayList<>();
        if (maps == null) {
            return entities;
        }

        for (Map<String, Object> map : maps) {
            String id = map.containsKey("id") ? String.valueOf(map.get("id")) : UUID.randomUUID().toString();
            String entityLabel = map.containsKey("label")
                    ? String.valueOf(map.get("label"))
                    : (label != null ? label : "Node");

            GraphEntity entity = GraphEntity.node(id, entityLabel);
            Map<String, Object> props = new HashMap<>(map);
            props.remove("id");
            props.remove("label");
            entity.setProperties(props);
            entities.add(entity);
        }

        return entities;
    }
}
