# 10 修正 WHERE 下沉与执行顺序

## 目标

使过滤条件下沉和虚拟边执行顺序符合 `scope.md` 的定义。

## 影响文件

- `graph-query-engine/src/main/java/com/fangyang/federatedquery/reliability/WhereConditionPushdown.java`
- `graph-query-engine/src/main/java/com/fangyang/federatedquery/rewriter/QueryRewriter.java`
- `graph-query-engine/src/main/java/com/fangyang/federatedquery/rewriter/MixedPatternRewriter.java`
- `graph-query-engine/src/main/java/com/fangyang/federatedquery/executor/FederatedExecutor.java`
- `graph-query-engine/src/main/java/com/fangyang/federatedquery/executor/DependencyResolver.java`

## 修改要求

- 按 `scope.md` 统一收敛 `WHERE` 的下沉策略，不能保留与规范相反的“仅部分下沉”逻辑。
- 修正第一跳虚拟边的执行顺序，使之符合“外部数据源 -> 提取 ID -> TuGraph”。
- 核对最后一跳虚拟边、混合边 `|` 组合的执行顺序，确保与 `scope.md` 描述一致。
- 清理任何与新顺序冲突的依赖判定和 ready-to-execute 逻辑。

## 验收标准

- `WHERE` 的处理路径可直接映射到 `scope.md` 文案。
- 第一跳、最后一跳、混合边三类场景的执行顺序与规范一致。
