package com.federatedquery.ast;

import java.util.ArrayList;
import java.util.List;

public class LogicalExpression extends Expression {
    private String operator;
    private List<Expression> operands = new ArrayList<>();
    
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    public String getOperator() {
        return operator;
    }
    
    public void setOperator(String operator) {
        this.operator = operator;
    }
    
    public List<Expression> getOperands() {
        return operands;
    }
    
    public void setOperands(List<Expression> operands) {
        this.operands = operands;
    }
    
    public void addOperand(Expression operand) {
        this.operands.add(operand);
    }
    
    @Override
    public String toCypher() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < operands.size(); i++) {
            if (i > 0) {
                sb.append(" ").append(operator).append(" ");
            }
            Expression operand = operands.get(i);
            if (operand instanceof LogicalExpression) {
                sb.append("(").append(operand.toCypher()).append(")");
            } else {
                sb.append(operand.toCypher());
            }
        }
        return sb.toString();
    }
}
