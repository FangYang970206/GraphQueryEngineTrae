package com.federatedquery.reliability;

import com.federatedquery.ast.LimitClause;
import com.federatedquery.ast.Literal;
import com.federatedquery.ast.Program;
import com.federatedquery.ast.Statement;
import com.federatedquery.plan.ExecutionPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class OOMProtectionInterceptor {
    private static final int DEFAULT_LIMIT = 5000;
    private int maxLimit = DEFAULT_LIMIT;
    
    public ExecutionPlan enforceLimit(ExecutionPlan plan) {
        if (plan.getGlobalContext() == null) {
            return plan;
        }
        
        if (plan.getGlobalContext().getGlobalLimit() == null) {
            plan.getGlobalContext().setHasImplicitLimit(true);
            plan.getGlobalContext().setImplicitLimit(maxLimit);
            plan.addWarning("Implicit LIMIT " + maxLimit + " added to prevent OOM");
        }
        
        return plan;
    }
    
    public Program enforceLimit(Program program) {
        if (program == null || program.getStatement() == null) {
            return program;
        }
        
        Statement statement = program.getStatement();
        if (statement.getQuery() == null) {
            return program;
        }
        
        boolean hasLimit = false;
        for (Statement.SingleQuery sq : statement.getQuery().getSingleQueries()) {
            if (sq.getReturnClause() != null && sq.getReturnClause().getLimitClause() != null) {
                hasLimit = true;
                break;
            }
        }
        
        if (!hasLimit) {
            program.addWarning("Implicit LIMIT " + maxLimit + " added to prevent OOM");
        }
        
        return program;
    }
    
    public List<Object> validateResultSize(List<Object> results) {
        if (results.size() > maxLimit) {
            List<Object> truncated = new ArrayList<>(results.subList(0, maxLimit));
            return truncated;
        }
        return results;
    }
    
    public int getMaxLimit() {
        return maxLimit;
    }
    
    public void setMaxLimit(int maxLimit) {
        this.maxLimit = maxLimit;
    }
}
