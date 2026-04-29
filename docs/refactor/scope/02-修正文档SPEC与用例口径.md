# 02 修正文档 SPEC 与用例口径

## 目标

修正正式规格和用例文档中超出 `scope.md` 的能力描述，避免外部读者误判 SDK 支持范围。

## 影响文件

- `docs/SPEC.md`
- `docs/cases/cypher_case.md`

## 修改要求

- 删除或改写 `OPTIONAL MATCH`、`UNWIND`、`SKIP` 的“已支持”描述。
- 删除或改写 `CALL ... YIELD` 可直通 TuGraph 的描述。
- 将 `UNION`、`UNION ALL` 的支持状态改为与 `scope.md` 一致。
- 修正排序、分页、过滤的执行语义，使之与 `scope.md` 一致。
- 清理所有使用 `SKIP`、`UNWIND`、`CALL` 的正向示例，改成受支持语法示例或改成反例说明。

## 验收标准

- `docs/SPEC.md` 与 `docs/cases/cypher_case.md` 不再声明 `scope.md` 之外的正式能力。
- 文档示例全部落在 `scope.md` 支持范围内。
