# Scope 收口完成项（03）

## 已完成

- 移除文档中把 `OPTIONAL MATCH`、`UNWIND`、`CALL`、`SKIP`、变长路径、单跳虚拟边当作已实现能力的表述
- 将能力清单统一到 `docs/rules/scope.md`
- 将测试覆盖口径统一为“只验证当前支持范围”，不再为被排除能力补正向测试

## 当前能力结论

### 支持

- `MATCH` / `RETURN` / `WITH` / `WHERE` / `ORDER BY` / `LIMIT`
- `UNION` / `UNION ALL`
- `USING SNAPSHOT` / `PROJECT BY`
- 起点带 `label` 的节点模式
- 命名路径、多关系类型、受限聚合函数

### 明确不支持

- `OPTIONAL MATCH`
- `UNWIND`
- `CALL`
- `SKIP`
- 变长路径
- 单跳虚拟边
- 纯虚拟点
- 虚拟到虚拟路径
- 写语句

## 执行约束

- `WHERE` 统一按变量归属下沉
- 第一跳虚拟边：外部 -> 提取 ID -> TuGraph
- 最后一跳虚拟边：TuGraph -> 提取 ID -> 外部
- 外部请求必须批量合并，禁止 N+1
- 默认限制：外部计划 `1000`，纯 TuGraph 计划 `5000`
- 最终结果上限：`8000`

## 后续仅保留的改进方向

- 在当前范围内补齐边界测试
- 优化执行链可观测性与文档一致性
- 若未来需要扩展语法，必须先同步修订 `scope.md`
