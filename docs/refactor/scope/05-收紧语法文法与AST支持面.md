# 05 收紧语法文法与 AST 支持面

## 目标

从语法入口开始收紧支持范围，让 parser 只接收 `scope.md` 允许的语法。

## 影响文件

- `graph-query-engine/src/main/antlr4/com/fangyang/federatedquery/grammar/Lcypher.g4`
- `graph-query-engine/src/main/java/com/fangyang/federatedquery/parser/CypherASTVisitor.java`
- `graph-query-engine/src/main/java/com/fangyang/federatedquery/ast/Statement.java`
- 相关 parser AST 模型类

## 修改要求

- 禁止 `OPTIONAL MATCH`、`UNWIND`、`CALL`、`CREATE`、`MERGE`、`DELETE`、`DETACH DELETE`、`SET`、`REMOVE` 进入受支持语法面。
- 禁止 `SKIP` 进入受支持语法面。
- 禁止 grammar 接受变长路径，或在 AST 构建阶段统一报错。
- 收紧表达式支持面，只保留 `scope.md` 允许的比较、逻辑、NULL、字符串匹配、`IN` 和列出的聚合函数。
- 保留 `USING SNAPSHOT` 与 `PROJECT BY`，并确保其 AST 表达稳定。

## 验收标准

- 超范围语法在 parse 阶段或 AST 阶段直接报错。
- parser 不再为超范围语法构建正常 AST。
