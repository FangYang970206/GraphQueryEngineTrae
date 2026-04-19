package com.fangyang.federatedquery.ast;

public interface AstVisitor<T> {
    T visit(Program program);
    T visit(Statement statement);
    T visit(Statement.Query query);
    T visit(Statement.SingleQuery singleQuery);
    T visit(MatchClause matchClause);
    T visit(UnwindClause unwindClause);
    T visit(ReturnClause returnClause);
    T visit(WithClause withClause);
    T visit(WhereClause whereClause);
    T visit(OrderByClause orderByClause);
    T visit(LimitClause limitClause);
    T visit(SkipClause skipClause);
    T visit(UnionClause unionClause);
    T visit(NodePattern nodePattern);
    T visit(RelationshipPattern relationshipPattern);
    T visit(Pattern pattern);
    T visit(UsingSnapshot usingSnapshot);
    T visit(ProjectBy projectBy);
    T visit(Expression expression);
}
