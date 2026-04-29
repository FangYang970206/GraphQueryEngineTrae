# 06 新增统一 Scope 校验层

## 目标

新增统一校验入口，把 `scope.md` 中“应直接报错”的规则集中实现，避免约束散落在 rewriter 和执行阶段。

## 影响文件

- `graph-query-engine/src/main/java/com/fangyang/federatedquery/parser/CypherParserFacade.java`
- `graph-query-engine/src/main/java/com/fangyang/federatedquery/sdk/GraphQuerySDK.java`
- 新增统一校验类，建议放在 `graph-query-engine/src/main/java/com/fangyang/federatedquery/validation/`

## 修改要求

- 新增单一入口校验器，负责语法范围、只读约束、起点约束、聚合来源约束、虚拟边约束等统一校验。
- 将校验时机前移到 rewrite 和 execute 之前。
- 统一错误类型与错误信息风格，避免同类违规在不同阶段抛出不同异常。
- 让 parser、rewriter、executor 不再各自维护重复的范围判断。

## 验收标准

- `scope.md` 中每条“禁止”或“应报错”规则都有唯一校验入口。
- 同一违规查询无论是否进入 rewrite，都能得到稳定错误。
