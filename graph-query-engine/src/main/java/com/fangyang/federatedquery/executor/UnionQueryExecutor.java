package com.fangyang.federatedquery.executor;

import com.fangyang.federatedquery.exception.ErrorCode;
import com.fangyang.federatedquery.exception.GraphQueryException;
import com.fangyang.federatedquery.model.QueryResult;
import com.fangyang.federatedquery.plan.ExecutionPlan;
import com.fangyang.federatedquery.plan.UnionPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

class UnionQueryExecutor {
    private static final Logger log = LoggerFactory.getLogger(UnionQueryExecutor.class);

    private final QueryResultAssembler queryResultAssembler;

    UnionQueryExecutor(QueryResultAssembler queryResultAssembler) {
        this.queryResultAssembler = queryResultAssembler;
    }

    CompletableFuture<QueryResult> execute(
            UnionPart union,
            Function<ExecutionPlan, CompletableFuture<ExecutionResult>> planExecutor) {
        List<CompletableFuture<ExecutionResult>> subFutures = new ArrayList<>();
        for (ExecutionPlan subPlan : union.getSubPlans()) {
            subFutures.add(planExecutor.apply(subPlan));
        }

        return CompletableFuture.allOf(subFutures.toArray(new CompletableFuture[0]))
                .handle((ignored, throwable) -> {
                    if (throwable != null) {
                        return CompletableFuture.<QueryResult>failedFuture(
                                wrapUnionFailure(union, unwrapAsyncFailure(throwable)));
                    }
                    return CompletableFuture.completedFuture(queryResultAssembler.buildUnionResult(subFutures));
                })
                .thenCompose(future -> future);
    }

    private GraphQueryException wrapUnionFailure(UnionPart union, Throwable cause) {
        if (cause instanceof GraphQueryException graphQueryException
                && graphQueryException.getErrorCode() == ErrorCode.UNION_EXECUTION_ERROR) {
            return graphQueryException;
        }

        if (!(cause instanceof GraphQueryException)) {
            log.error("Union sub-query failed [unionId={}]: {}",
                    union.getId(), cause != null ? cause.getMessage() : "Unknown union failure", cause);
        }
        return new GraphQueryException(
                ErrorCode.UNION_EXECUTION_ERROR,
                "Union sub-query failed [unionId=" + union.getId() + "]",
                cause);
    }

    private Throwable unwrapAsyncFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException || current instanceof ExecutionException) {
            if (current.getCause() == null) {
                break;
            }
            current = current.getCause();
        }
        return current;
    }
}
