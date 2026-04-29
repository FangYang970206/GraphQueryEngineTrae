package com.fangyang.federatedquery.executor;

import com.fangyang.federatedquery.model.GraphEntity;
import com.fangyang.federatedquery.plan.PhysicalQuery;
import com.fangyang.federatedquery.plan.ExternalQuery;
import com.fangyang.federatedquery.model.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ResultEnricher {
    private static final Logger log = LoggerFactory.getLogger(ResultEnricher.class);

    public QueryResult enrichExternalResultWithSourceRows(
            ExternalQuery query,
            QueryResult queryResult,
            ExecutionResult executionResult) {
        if (query == null || queryResult == null) {
            return queryResult;
        }
        if (query.getSourceVariableName() == null || query.getSourceVariableName().isEmpty()) {
            return queryResult;
        }
        if (query.getInputIdField() == null || query.getInputIdField().isEmpty()
                || query.getOutputIdField() == null || query.getOutputIdField().isEmpty()) {
            return queryResult;
        }

        Map<String, List<QueryResult.ResultRow>> sourceRowsByJoinValue =
                indexPhysicalSourceRows(executionResult, query.getSourceVariableName(), query.getInputIdField());
        if (sourceRowsByJoinValue.isEmpty()) {
            return queryResult;
        }

        List<QueryResult.ResultRow> baseRows = !queryResult.getRows().isEmpty()
                ? queryResult.getRows()
                : createRowsFromEntities(query, queryResult.getEntities());

        List<QueryResult.ResultRow> mergedRows = new ArrayList<>();
        int rowIndex = 0;
        for (QueryResult.ResultRow baseRow : baseRows) {
            String joinValue = resolveJoinValue(baseRow, query.getOutputIdField());
            if (joinValue == null) {
                continue;
            }

            List<QueryResult.ResultRow> sourceRows = sourceRowsByJoinValue.get(joinValue);
            if (sourceRows == null || sourceRows.isEmpty()) {
                continue;
            }

            for (QueryResult.ResultRow sourceRow : sourceRows) {
                QueryResult.ResultRow mergedRow = new QueryResult.ResultRow();
                mergedRow.setRowId((query.getId() != null ? query.getId() : "external") + "#" + rowIndex++);
                mergedRow.getEntitiesByVariable().putAll(sourceRow.getEntitiesByVariable());
                mergedRow.getEntitiesByVariable().putAll(baseRow.getEntitiesByVariable());
                mergedRows.add(mergedRow);
            }
        }

        if (!mergedRows.isEmpty()) {
            queryResult.setRows(mergedRows);
        }
        return queryResult;
    }

    public QueryResult enrichPhysicalResultWithSourceRows(
            PhysicalQuery query,
            QueryResult queryResult,
            ExecutionResult executionResult) {
        if (query == null || queryResult == null) {
            return queryResult;
        }
        if (query.getSourceVariableName() == null || query.getSourceVariableName().isEmpty()) {
            return queryResult;
        }
        if (query.getDependencySourceField() == null || query.getDependencySourceField().isEmpty()
                || query.getDependencyTargetVariable() == null || query.getDependencyTargetVariable().isEmpty()
                || query.getDependencyTargetField() == null || query.getDependencyTargetField().isEmpty()) {
            return queryResult;
        }

        Map<String, List<QueryResult.ResultRow>> sourceRowsByJoinValue =
                indexExternalSourceRows(executionResult, query.getSourceVariableName(), query.getDependencySourceField());
        if (sourceRowsByJoinValue.isEmpty()) {
            return queryResult;
        }

        List<QueryResult.ResultRow> mergedRows = new ArrayList<>();
        int rowIndex = 0;
        for (QueryResult.ResultRow baseRow : queryResult.getRows()) {
            GraphEntity targetEntity = baseRow.getEntitiesByVariable().get(query.getDependencyTargetVariable());
            if (targetEntity == null) {
                continue;
            }
            Object joinValue = "_id".equals(query.getDependencyTargetField())
                    ? targetEntity.getId()
                    : targetEntity.getProperties().get(query.getDependencyTargetField());
            if (joinValue == null) {
                continue;
            }

            List<QueryResult.ResultRow> sourceRows = sourceRowsByJoinValue.get(String.valueOf(joinValue));
            if (sourceRows == null || sourceRows.isEmpty()) {
                continue;
            }

            for (QueryResult.ResultRow sourceRow : sourceRows) {
                QueryResult.ResultRow mergedRow = new QueryResult.ResultRow();
                mergedRow.setRowId((query.getId() != null ? query.getId() : "physical") + "#" + rowIndex++);
                mergedRow.getEntitiesByVariable().putAll(sourceRow.getEntitiesByVariable());
                mergedRow.getEntitiesByVariable().putAll(baseRow.getEntitiesByVariable());
                mergedRows.add(mergedRow);
            }
        }

        if (!mergedRows.isEmpty()) {
            queryResult.setRows(mergedRows);
        }
        return queryResult;
    }

    private Map<String, List<QueryResult.ResultRow>> indexPhysicalSourceRows(
            ExecutionResult executionResult,
            String sourceVariableName,
            String inputIdField) {
        Map<String, List<QueryResult.ResultRow>> sourceRowsByJoinValue = new LinkedHashMap<>();

        for (List<QueryResult> qrList : executionResult.getPhysicalResults().values()) {
            for (QueryResult qr : qrList) {
                for (QueryResult.ResultRow row : qr.getRows()) {
                    GraphEntity sourceEntity = row.getEntitiesByVariable().get(sourceVariableName);
                    if (sourceEntity == null) {
                        continue;
                    }
                    Object joinValue = "_id".equals(inputIdField)
                            ? sourceEntity.getId()
                            : sourceEntity.getProperties().get(inputIdField);
                    if (joinValue != null) {
                        sourceRowsByJoinValue
                                .computeIfAbsent(String.valueOf(joinValue), key -> new ArrayList<>())
                                .add(row);
                    }
                }
            }
        }

        return sourceRowsByJoinValue;
    }

    private Map<String, List<QueryResult.ResultRow>> indexExternalSourceRows(
            ExecutionResult executionResult,
            String sourceVariableName,
            String sourceField) {
        Map<String, List<QueryResult.ResultRow>> sourceRowsByJoinValue = new LinkedHashMap<>();

        for (QueryResult qr : executionResult.getExternalResults()) {
            List<QueryResult.ResultRow> sourceRows = !qr.getRows().isEmpty()
                    ? qr.getRows()
                    : createRowsFromEntities(sourceVariableName, qr.getEntities());
            for (QueryResult.ResultRow row : sourceRows) {
                GraphEntity sourceEntity = row.getEntitiesByVariable().get(sourceVariableName);
                if (sourceEntity == null) {
                    continue;
                }
                Object joinValue = "_id".equals(sourceField)
                        ? sourceEntity.getId()
                        : sourceEntity.getProperties().get(sourceField);
                if (joinValue != null) {
                    sourceRowsByJoinValue
                            .computeIfAbsent(String.valueOf(joinValue), key -> new ArrayList<>())
                            .add(row);
                }
            }
        }

        return sourceRowsByJoinValue;
    }

    private List<QueryResult.ResultRow> createRowsFromEntities(ExternalQuery query, List<GraphEntity> entities) {
        return createRowsFromEntities(null, entities, query != null && query.getId() != null ? query.getId() : "external");
    }

    private List<QueryResult.ResultRow> createRowsFromEntities(String variableName, List<GraphEntity> entities) {
        return createRowsFromEntities(variableName, entities, "external");
    }

    private List<QueryResult.ResultRow> createRowsFromEntities(
            String variableName,
            List<GraphEntity> entities,
            String rowPrefix) {
        List<QueryResult.ResultRow> rows = new ArrayList<>();
        int rowIndex = 0;
        for (GraphEntity entity : entities) {
            QueryResult.ResultRow row = new QueryResult.ResultRow();
            row.setRowId(rowPrefix + "#entity-" + rowIndex++);
            String entityVariable = entity.getVariableName();
            if ((entityVariable == null || entityVariable.isEmpty())
                    && variableName != null && !variableName.isEmpty()) {
                entityVariable = variableName;
            }
            if (entityVariable != null && !entityVariable.isEmpty()) {
                row.put(entityVariable, entity);
                rows.add(row);
            }
        }
        return rows;
    }

    private String resolveJoinValue(QueryResult.ResultRow row, String outputIdField) {
        for (GraphEntity entity : row.getEntitiesByVariable().values()) {
            Object joinValue = "_id".equals(outputIdField)
                    ? entity.getId()
                    : entity.getProperties().get(outputIdField);
            if (joinValue != null) {
                return String.valueOf(joinValue);
            }
        }
        return null;
    }

    public QueryResult applyExternalQueryMetadata(ExternalQuery query, QueryResult result) {
        if (query == null || result == null || result.getEntities() == null) {
            return result;
        }
        for (GraphEntity entity : result.getEntities()) {
            if (query.getEdgeType() != null && entity.getSourceEdgeType() == null) {
                entity.setSourceEdgeType(query.getEdgeType());
            }
            if (query.getTargetLabel() != null && entity.getLabel() == null) {
                entity.setLabel(query.getTargetLabel());
            }
            if (entity.getVariableName() == null && !query.getOutputVariables().isEmpty()) {
                String targetVarName = query.getOutputVariables().size() > 1 
                        ? query.getOutputVariables().get(query.getOutputVariables().size() - 1)
                        : query.getOutputVariables().get(0);
                entity.setVariableName(targetVarName);
            }
        }
        return result;
    }
}
