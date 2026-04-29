package com.fangyang.federatedquery.validation;

import com.fangyang.federatedquery.ast.Comparison;
import com.fangyang.federatedquery.ast.Expression;
import com.fangyang.federatedquery.ast.FunctionCall;
import com.fangyang.federatedquery.ast.ListExpression;
import com.fangyang.federatedquery.ast.LogicalExpression;
import com.fangyang.federatedquery.ast.MapExpression;
import com.fangyang.federatedquery.ast.MatchClause;
import com.fangyang.federatedquery.ast.NodePattern;
import com.fangyang.federatedquery.ast.OrderByClause;
import com.fangyang.federatedquery.ast.Pattern;
import com.fangyang.federatedquery.ast.Program;
import com.fangyang.federatedquery.ast.PropertyAccess;
import com.fangyang.federatedquery.ast.RelationshipPattern;
import com.fangyang.federatedquery.ast.ReturnClause;
import com.fangyang.federatedquery.ast.Statement;
import com.fangyang.federatedquery.ast.Variable;
import com.fangyang.federatedquery.ast.WhereClause;
import com.fangyang.federatedquery.ast.WithClause;
import com.fangyang.federatedquery.exception.SyntaxErrorException;
import com.fangyang.federatedquery.exception.VirtualEdgeConstraintException;
import com.fangyang.federatedquery.rewriter.VirtualEdgeDetector;
import com.fangyang.metadata.MetadataQueryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class ScopeValidator {
    private static final String PHYSICAL_SOURCE = "tugraph";

    private final MetadataQueryService metadataQueryService;
    private final VirtualEdgeDetector virtualEdgeDetector;

    public ScopeValidator() {
        this(null, null);
    }

    @Autowired
    public ScopeValidator(MetadataQueryService metadataQueryService, VirtualEdgeDetector virtualEdgeDetector) {
        this.metadataQueryService = metadataQueryService;
        this.virtualEdgeDetector = virtualEdgeDetector;
    }

    public void validate(Program program) {
        if (program == null || program.getStatement() == null || program.getStatement().getQuery() == null) {
            return;
        }

        for (Statement.SingleQuery singleQuery : program.getStatement().getQuery().getSingleQueries()) {
            validateSingleQuery(singleQuery);
        }
    }

    private void validateSingleQuery(Statement.SingleQuery singleQuery) {
        Map<String, String> variableSources = new LinkedHashMap<>();

        for (List<MatchClause> matchClauses : singleQuery.getPrecedingMatchClauses()) {
            for (MatchClause matchClause : matchClauses) {
                validateMatchClause(matchClause, variableSources);
            }
        }

        for (MatchClause matchClause : singleQuery.getMatchClauses()) {
            validateMatchClause(matchClause, variableSources);
        }

        for (WithClause withClause : singleQuery.getPrecedingWithClauses()) {
            validateWithClause(withClause, variableSources);
        }

        if (singleQuery.getReturnClause() != null) {
            validateReturnClause(singleQuery.getReturnClause(), variableSources);
        }
    }

    private void validateMatchClause(MatchClause matchClause, Map<String, String> variableSources) {
        Pattern pattern = matchClause.getPattern();
        if (pattern == null) {
            return;
        }

        for (Pattern.PatternPart patternPart : pattern.getPatternParts()) {
            validatePatternPart(patternPart, variableSources);
        }

        if (matchClause.getWhereClause() != null) {
            validateWhereClause(matchClause.getWhereClause());
        }

        if (virtualEdgeDetector != null) {
            VirtualEdgeDetector.DetectionResult detectionResult = virtualEdgeDetector.detect(pattern);
            if (detectionResult.hasErrors()) {
                throw new VirtualEdgeConstraintException(String.join("; ", detectionResult.getErrors()));
            }
        }
    }

    private void validatePatternPart(Pattern.PatternPart patternPart, Map<String, String> variableSources) {
        if (patternPart == null || patternPart.getPatternElement() == null) {
            return;
        }

        Pattern.PatternElement element = patternPart.getPatternElement();
        NodePattern startNode = element.getNodePattern();
        if (startNode == null || startNode.getLabels().isEmpty()) {
            throw new SyntaxErrorException("MATCH start node must declare a label");
        }

        registerNodeSource(startNode, variableSources);

        if (element.getChains().isEmpty() && isVirtualNode(startNode)) {
            throw new SyntaxErrorException("Pure virtual node patterns are not supported");
        }

        for (Pattern.PatternElementChain chain : element.getChains()) {
            validateRelationshipPattern(chain.getRelationshipPattern());
            registerRelationshipSource(chain.getRelationshipPattern(), variableSources);
            registerNodeSource(chain.getNodePattern(), variableSources);
        }
    }

    private void validateRelationshipPattern(RelationshipPattern relationshipPattern) {
        if (relationshipPattern == null) {
            return;
        }
        if (relationshipPattern.getRangeLiteral() != null) {
            throw new SyntaxErrorException("Variable-length paths are not supported");
        }
    }

    private void validateWithClause(WithClause withClause, Map<String, String> variableSources) {
        if (withClause == null) {
            return;
        }

        for (ReturnClause.ReturnItem returnItem : withClause.getReturnItems()) {
            validateExpression(returnItem.getExpression());
        }

        validateAggregations(withClause.getReturnItems(), variableSources);

        if (withClause.getWhereClause() != null) {
            validateWhereClause(withClause.getWhereClause());
        }
        if (withClause.getOrderByClause() != null) {
            validateOrderByClause(withClause.getOrderByClause());
        }
    }

    private void validateReturnClause(ReturnClause returnClause, Map<String, String> variableSources) {
        for (ReturnClause.ReturnItem returnItem : returnClause.getReturnItems()) {
            validateExpression(returnItem.getExpression());
        }

        validateAggregations(returnClause.getReturnItems(), variableSources);

        if (returnClause.getOrderByClause() != null) {
            validateOrderByClause(returnClause.getOrderByClause());
        }
    }

    private void validateWhereClause(WhereClause whereClause) {
        if (whereClause != null) {
            validateExpression(whereClause.getExpression());
        }
    }

    private void validateOrderByClause(OrderByClause orderByClause) {
        for (OrderByClause.SortItem sortItem : orderByClause.getSortItems()) {
            validateExpression(sortItem.getExpression());
        }
    }

    private void validateAggregations(List<ReturnClause.ReturnItem> returnItems, Map<String, String> variableSources) {
        Set<String> aggregateSources = new LinkedHashSet<>();
        for (ReturnClause.ReturnItem returnItem : returnItems) {
            List<FunctionCall> aggregateFunctions = new ArrayList<>();
            collectAggregateFunctions(returnItem.getExpression(), aggregateFunctions);
            for (FunctionCall aggregateFunction : aggregateFunctions) {
                Set<String> expressionSources = resolveExpressionSources(aggregateFunction, variableSources);
                if (expressionSources.size() > 1) {
                    throw new SyntaxErrorException("Aggregation can only reference a single data source");
                }
                aggregateSources.addAll(expressionSources);
            }
        }

        if (aggregateSources.size() > 1) {
            throw new SyntaxErrorException("Aggregation can only reference a single data source");
        }
    }

    private void collectAggregateFunctions(Expression expression, List<FunctionCall> aggregateFunctions) {
        if (expression == null) {
            return;
        }
        if (expression instanceof FunctionCall) {
            FunctionCall functionCall = (FunctionCall) expression;
            if (isAggregateFunction(functionCall.getFunctionName())) {
                aggregateFunctions.add(functionCall);
            }
            for (Expression argument : functionCall.getArguments()) {
                collectAggregateFunctions(argument, aggregateFunctions);
            }
            return;
        }
        if (expression instanceof PropertyAccess) {
            collectAggregateFunctions(((PropertyAccess) expression).getTarget(), aggregateFunctions);
            return;
        }
        if (expression instanceof Comparison) {
            collectAggregateFunctions(((Comparison) expression).getLeft(), aggregateFunctions);
            collectAggregateFunctions(((Comparison) expression).getRight(), aggregateFunctions);
            return;
        }
        if (expression instanceof LogicalExpression) {
            for (Expression operand : ((LogicalExpression) expression).getOperands()) {
                collectAggregateFunctions(operand, aggregateFunctions);
            }
            return;
        }
        if (expression instanceof ListExpression) {
            for (Expression element : ((ListExpression) expression).getElements()) {
                collectAggregateFunctions(element, aggregateFunctions);
            }
            return;
        }
        if (expression instanceof MapExpression) {
            for (Expression value : ((MapExpression) expression).getEntries().values()) {
                collectAggregateFunctions(value, aggregateFunctions);
            }
        }
    }

    private Set<String> resolveExpressionSources(Expression expression, Map<String, String> variableSources) {
        Set<String> variables = new LinkedHashSet<>();
        collectVariables(expression, variables);

        Set<String> sources = new LinkedHashSet<>();
        for (String variable : variables) {
            String source = variableSources.get(variable);
            if (source != null) {
                sources.add(source);
            }
        }
        return sources;
    }

    private void collectVariables(Expression expression, Set<String> variables) {
        if (expression == null) {
            return;
        }
        if (expression instanceof Variable) {
            String name = ((Variable) expression).getName();
            if (name != null && !"*".equals(name)) {
                variables.add(name);
            }
            return;
        }
        if (expression instanceof PropertyAccess) {
            collectVariables(((PropertyAccess) expression).getTarget(), variables);
            return;
        }
        if (expression instanceof Comparison) {
            collectVariables(((Comparison) expression).getLeft(), variables);
            collectVariables(((Comparison) expression).getRight(), variables);
            return;
        }
        if (expression instanceof LogicalExpression) {
            for (Expression operand : ((LogicalExpression) expression).getOperands()) {
                collectVariables(operand, variables);
            }
            return;
        }
        if (expression instanceof FunctionCall) {
            for (Expression argument : ((FunctionCall) expression).getArguments()) {
                collectVariables(argument, variables);
            }
            return;
        }
        if (expression instanceof ListExpression) {
            for (Expression element : ((ListExpression) expression).getElements()) {
                collectVariables(element, variables);
            }
            return;
        }
        if (expression instanceof MapExpression) {
            for (Expression value : ((MapExpression) expression).getEntries().values()) {
                collectVariables(value, variables);
            }
        }
    }

    private void validateExpression(Expression expression) {
        if (expression == null) {
            return;
        }
        if (expression instanceof FunctionCall) {
            FunctionCall functionCall = (FunctionCall) expression;
            if (!isAggregateFunction(functionCall.getFunctionName())) {
                throw new SyntaxErrorException("Unsupported function: " + functionCall.getFunctionName());
            }
            for (Expression argument : functionCall.getArguments()) {
                validateExpression(argument);
            }
            return;
        }
        if (expression instanceof PropertyAccess) {
            validateExpression(((PropertyAccess) expression).getTarget());
            return;
        }
        if (expression instanceof Comparison) {
            validateExpression(((Comparison) expression).getLeft());
            validateExpression(((Comparison) expression).getRight());
            return;
        }
        if (expression instanceof LogicalExpression) {
            for (Expression operand : ((LogicalExpression) expression).getOperands()) {
                validateExpression(operand);
            }
            return;
        }
        if (expression instanceof ListExpression) {
            for (Expression element : ((ListExpression) expression).getElements()) {
                validateExpression(element);
            }
            return;
        }
        if (expression instanceof MapExpression) {
            for (Expression value : ((MapExpression) expression).getEntries().values()) {
                validateExpression(value);
            }
        }
    }

    private boolean isAggregateFunction(String functionName) {
        if (functionName == null) {
            return false;
        }
        String normalized = functionName.toLowerCase(Locale.ROOT);
        return "count".equals(normalized)
                || "sum".equals(normalized)
                || "avg".equals(normalized)
                || "min".equals(normalized)
                || "max".equals(normalized);
    }

    private void registerNodeSource(NodePattern nodePattern, Map<String, String> variableSources) {
        if (nodePattern == null || nodePattern.getVariable() == null) {
            return;
        }
        variableSources.put(nodePattern.getVariable(), resolveNodeSource(nodePattern).orElse(PHYSICAL_SOURCE));
    }

    private void registerRelationshipSource(RelationshipPattern relationshipPattern, Map<String, String> variableSources) {
        if (relationshipPattern == null || relationshipPattern.getVariable() == null) {
            return;
        }
        variableSources.put(relationshipPattern.getVariable(), resolveRelationshipSource(relationshipPattern).orElse(PHYSICAL_SOURCE));
    }

    private Optional<String> resolveNodeSource(NodePattern nodePattern) {
        if (nodePattern == null) {
            return Optional.empty();
        }
        if (metadataQueryService == null || nodePattern.getLabels().isEmpty()) {
            return Optional.of(PHYSICAL_SOURCE);
        }
        for (String label : nodePattern.getLabels()) {
            if (metadataQueryService.isVirtualLabel(label)) {
                return Optional.ofNullable(metadataQueryService.getDataSourceForLabel(label));
            }
        }
        return Optional.of(PHYSICAL_SOURCE);
    }

    private Optional<String> resolveRelationshipSource(RelationshipPattern relationshipPattern) {
        if (relationshipPattern == null) {
            return Optional.empty();
        }
        if (metadataQueryService == null) {
            return Optional.of(PHYSICAL_SOURCE);
        }
        for (String relationshipType : relationshipPattern.getRelationshipTypes()) {
            if (metadataQueryService.isVirtualEdge(relationshipType)) {
                return Optional.ofNullable(metadataQueryService.getDataSourceForEdge(relationshipType));
            }
        }
        return Optional.of(PHYSICAL_SOURCE);
    }

    private boolean isVirtualNode(NodePattern nodePattern) {
        if (metadataQueryService == null || nodePattern == null) {
            return false;
        }
        for (String label : nodePattern.getLabels()) {
            if (metadataQueryService.isVirtualLabel(label)) {
                return true;
            }
        }
        return false;
    }
}
