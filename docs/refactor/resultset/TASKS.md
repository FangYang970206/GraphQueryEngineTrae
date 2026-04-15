# 结果集异常语义重构任务清单

## 阶段一：准备阶段

### 1.1 代码审查

- [ ] 审查 `FederatedExecutor` 所有执行方法，标记 `error/partial/warning` 现存用法
- [ ] 审查 `GraphQuerySDK` 的全部公开入口，确认异常契约改动范围
- [ ] 审查 `ExecutionResult`、`QueryResult`、`BatchingStrategy`、`TuGraphAdapterImpl`、`MockExternalAdapter`
- [ ] 审查测试中对错误 JSON、`isSuccess()`、warning 的依赖

### 1.2 决策落地

- [ ] 将已确认口径同步到文档和实现任务
- [ ] 明确 `Union` 失败错误码使用 `5003`
- [ ] 明确 `warning` 语义彻底移除
- [ ] 明确语法类异常映射 HTTP 400，执行类异常映射 HTTP 500

### 1.3 测试准备

- [ ] 创建 `FederatedExecutorExceptionTest` 或等价测试类
- [ ] 准备异步异常解包测试样例
- [ ] 准备依赖查询无输入时返回空结果的测试数据

## 阶段二：核心重构

### 2.1 FederatedExecutor 重构

#### 2.1.1 `executePhysical`

- [ ] 适配器不存在时抛出 `GraphQueryException(3001)`
- [ ] 超时时抛出 `GraphQueryException(3002)`
- [ ] 执行失败时抛出 `GraphQueryException(3002)`
- [ ] 在失败发生点记录错误日志

#### 2.1.2 `executeExternal`

- [ ] 适配器不存在时抛出 `GraphQueryException(3001)`
- [ ] 重试耗尽后抛出 `GraphQueryException(3003)`
- [ ] 超时时抛出 `GraphQueryException(3003)`
- [ ] 移除 `QueryResult.partial(...)` 返回路径
- [ ] 在失败发生点记录错误日志

#### 2.1.3 `executeBatch`

- [ ] 适配器不存在时抛出 `GraphQueryException(3001)`
- [ ] 超时时抛出 `GraphQueryException(5002)`
- [ ] 执行失败时抛出 `GraphQueryException(5002)`
- [ ] 在失败发生点记录错误日志

#### 2.1.4 `executeUnion`

- [ ] 移除吞异常的 `try/catch`
- [ ] 任一子查询失败时统一抛出 `GraphQueryException(5003)`
- [ ] 保留原始 cause 链
- [ ] 在 Union 边界记录失败日志

#### 2.1.5 `executeWithDependencyAwareness`

- [ ] 把“无输入 ID”与“执行失败”分开处理
- [ ] `notReadyQueries` 因无输入时按空结果处理
- [ ] 输入装配异常时抛出 `GraphQueryException(1003)`
- [ ] 保证依赖查询失败继续沿链路传播

#### 2.1.6 并发边界

- [ ] 审视 `executeAllInParallel` 的异常传播链
- [ ] 移除将异常降级为结果对象的逻辑
- [ ] 明确并发语义为“最终整体失败”，而不是强制取消所有任务

### 2.2 GraphQuerySDK 重构

#### 2.2.1 移除结果层失败扫描

- [ ] 删除 `checkExecutionErrors()` 方法
- [ ] 删除全部调用点
- [ ] 删除相关旧注释

#### 2.2.2 统一异常出口

- [ ] 为 `execute()` 添加异步异常解包逻辑
- [ ] 为 `executeRaw()` 添加异步异常解包逻辑
- [ ] 为 `executeRecords()` 添加异步异常解包逻辑
- [ ] 为 `executeWithProfile()` 添加异步异常解包逻辑

#### 2.2.3 移除错误 JSON

- [ ] 删除 `buildErrorResponse()` 方法
- [ ] 删除 `execute()` 中的错误 JSON 回退路径
- [ ] 删除 `executeRaw()` 中的错误 JSON 回退路径
- [ ] 删除 `executeWithProfile()` 中的错误 JSON 回退路径

#### 2.2.4 日志边界收敛

- [ ] 删除 SDK 对同一失败的重复 `log.error(...)`
- [ ] 保证执行层单点记录错误日志

### 2.3 结果对象模型重构

#### 2.3.1 `ExecutionResult`

- [ ] 删除 `success` 字段
- [ ] 删除 `setSuccess()` / `isSuccess()`
- [ ] 调整 `markExecutionSuccess()` 或移除该方法
- [ ] 清理所有对 `ExecutionResult.success` 的引用

#### 2.3.2 `QueryResult`

- [ ] 评估并移除 `success`、`error`、`warnings` 的主路径依赖
- [ ] 删除或废弃 `QueryResult.error()`
- [ ] 删除或废弃 `QueryResult.partial()`
- [ ] 清理 `PARTIAL_RESULT_ERROR(5001)` 的使用

#### 2.3.3 相关组件

- [ ] 更新 `BatchingStrategy`，不再使用 `setSuccess(true)` 表达语义
- [ ] 更新 `TuGraphAdapterImpl`，失败时抛异常而不是返回 `QueryResult.error()`
- [ ] 更新 `MockExternalAdapter`，移除 warning 结果语义

### 2.4 HTTP 状态映射支撑

- [ ] 确认异常模型中能区分语法类与执行类错误
- [ ] 为后续 HTTP 层对接保留状态映射依据
- [ ] 在文档和测试中补齐 400/500 预期

## 阶段三：测试更新

### 3.1 单元测试更新

#### 3.1.1 `ExecutorTest`

- [ ] 删除对 `ExecutionResult.isSuccess()` 的断言
- [ ] 将旧的“返回错误结果”测试迁移为异常断言
- [ ] 增加 `Union` 失败断言，验证错误码为 `5003`
- [ ] 增加依赖查询无输入时返回空结果的断言

#### 3.1.2 新增异常专项测试

- [ ] 测试物理查询失败场景
- [ ] 测试外部查询失败场景
- [ ] 测试批量查询失败场景
- [ ] 测试 `Union` 查询失败场景
- [ ] 测试异步异常解包场景
- [ ] 测试超时场景

### 3.2 集成测试更新

#### 3.2.1 `DependencyAwareExecutionE2ETest`

- [ ] 将 `externalServiceUnavailable` 改为异常断言
- [ ] 保留 `physicalQueryReturnsEmpty` 的空结果断言
- [ ] 保留 `scenario1_NoLTPs` 的空结果断言
- [ ] 增加“SDK 不暴露 CompletionException”的断言

#### 3.2.2 `E2ETest`

- [ ] 检查字符串接口调用点，确认失败场景改为异常断言
- [ ] 检查路径、排序、分页等成功场景保持不变

#### 3.2.3 其他 E2E 测试

- [ ] 检查 `MissingCoverageE2ETest`
- [ ] 检查 `VirtualGraphCaseE2ETest`
- [ ] 检查 `LcypherFullCoverageE2ETest`
- [ ] 检查所有对错误 JSON 的断言

### 3.3 Mock 与夹具修复

- [ ] 修复 `MockExternalAdapter` 中的 warning 语义
- [ ] 更新测试中对 `QueryResult.error()` / `partial()` 的依赖
- [ ] 验证共享夹具与实体工厂用例仍成立

## 阶段四：验证阶段

### 4.1 功能验证

- [ ] 运行所有单元测试
- [ ] 运行所有集成测试
- [ ] 运行 E2E 测试
- [ ] 手动验证依赖查询无输入返回空结果
- [ ] 手动验证外部失败时字符串接口直接抛异常

### 4.2 代码质量检查

- [ ] 代码审查
- [ ] 静态分析
- [ ] 日志输出检查
- [ ] 检查 `5001` 是否已无引用
- [ ] 检查 `buildErrorResponse()` 是否已无引用

### 4.3 性能验证

- [ ] 对比重构前后正常路径性能
- [ ] 对比异常路径日志量变化
- [ ] 确认无明显性能退化

## 阶段五：文档更新

### 5.1 代码注释

- [ ] 更新 `FederatedExecutor` 类注释
- [ ] 更新 SDK 异常契约说明
- [ ] 更新异常解包说明

### 5.2 架构与 API 文档

- [ ] 更新错误处理流程文档
- [ ] 更新 API 失败契约
- [ ] 更新错误码与 HTTP 状态映射说明
- [ ] 明确记录破坏性变更

### 5.3 升级说明

- [ ] 说明旧错误 JSON 已移除
- [ ] 说明调用方需要捕获 `GraphQueryException`
- [ ] 说明 `Union` 错误码改为 `5003`

## 阶段六：发布与回滚准备

### 6.1 回滚准备

- [ ] 准备回滚检查清单
- [ ] 明确回滚点：错误 JSON、结果层扫描、partial/warning 语义

### 6.2 发布准备

- [ ] 准备发布说明
- [ ] 准备调用方升级指南
- [ ] 标注为破坏性变更

## 任务优先级

### P0

- 2.1.1 `executePhysical`
- 2.1.2 `executeExternal`
- 2.1.4 `executeUnion`
- 2.2.2 统一异常出口
- 2.2.3 移除错误 JSON
- 2.3.1 `ExecutionResult` 清理
- 3.1 `ExecutorTest` 更新
- 3.2.1 `DependencyAwareExecutionE2ETest` 更新

### P1

- 2.1.3 `executeBatch`
- 2.1.5 `executeWithDependencyAwareness`
- 2.3.2 `QueryResult` 清理
- 2.3.3 相关组件迁移
- 3.2.2 `E2ETest` 更新
- 3.2.3 其他 E2E 测试更新

### P2

- 2.4 HTTP 状态映射支撑
- 4.3 性能验证
- 5. 文档与升级说明
- 6. 发布与回滚准备

## 时间估算

| 阶段 | 预估时间 |
|------|----------|
| 阶段一：准备阶段 | 0.5-1 天 |
| 阶段二：核心重构 | 3-4 天 |
| 阶段三：测试更新 | 2-3 天 |
| 阶段四：验证阶段 | 1 天 |
| 阶段五：文档更新 | 0.5 天 |
| 阶段六：发布准备 | 0.5 天 |
| **总计** | **7.5-10 天** |

## 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| 测试大量失效 | 高 | 提前扫描所有错误 JSON 与 `isSuccess()` 断言 |
| 异步异常解包遗漏 | 高 | 增加 SDK 边界专项测试 |
| 把空结果误改成异常 | 高 | 单独覆盖依赖查询无输入场景 |
| `QueryResult` 清理范围超预期 | 中 | 先全局搜索主路径引用，再分批迁移 |
| 兼容性破坏影响调用方 | 中 | 补齐升级说明并明确破坏性变更 |
