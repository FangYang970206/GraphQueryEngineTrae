# 08 补齐起点 label 与变长路径约束

## 目标

落实 `scope.md` 对路径模式的基本边界约束。

## 影响文件

- `graph-query-engine/src/main/java/com/fangyang/federatedquery/parser/CypherASTVisitor.java`
- `graph-query-engine/src/main/java/com/fangyang/federatedquery/rewriter/QueryRewriter.java`
- 新增或复用统一校验层

## 修改要求

- 对 `MATCH` 中第一个节点未声明 `label` 的查询直接报错。
- 对变长路径统一直接报错。
- 检查多关系类型、命名路径、属性过滤的实现是否仍在允许范围内，并补齐必要校验。

## 验收标准

- `MATCH (n) RETURN n` 不再被接受。
- 任意 `*` 范围路径在进入执行计划前被拒绝。
