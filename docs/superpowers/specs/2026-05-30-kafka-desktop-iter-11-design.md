# iter-11 设计：过滤器保存复用（Phase 1 收尾）

> 状态：brainstorming 已通过设计评审。日期：2026-05-30。前置：iter-10（背压/LRU）已 E2E 验证并 tag。
> 这是 Phase 1 MVP 最后一个可选项「可视化过滤器 + 保存复用」的保存复用部分。完成后 Phase 1 全部交付。

## 目标

把可视化过滤器命名保存到本地、下次从下拉复用。保存时可选**全局**或**绑定当前 topic**；加载下拉显示「全局 + 当前 topic」的过滤器。

## brainstorming 决策
- 范围：**全局 + per-topic 二选一**（保存时选）。

## 关键技术点

`FilterBuilder` 是扁平结构（单层 Group + 一组 RuleRow）。保存不序列化富 AST（`FilterNode` sealed 多态序列化麻烦），而是序列化**与表单行同构的简单 DTO**——既好 Jackson round-trip，又天然能反向渲染回 UI 行（解决 iter-7 记录的"AST 无法反渲染"问题，因为 DTO 就是行表示）。

## 数据模型（core-storage，照搬 ConnectionStore 模式）

```kotlin
data class SavedFilter(
    val name: String,            // 唯一标签（保存 key）
    val topic: String?,          // null = 全局；否则绑定该 topic
    val logic: String,           // "AND" / "OR"
    val conditions: List<SavedCondition>,
)
data class SavedCondition(       // 与一个 RuleRow 同构
    val fieldKind: String,       // KEY / VALUE_RAW / JSON_PATH / HEADER / PARTITION / OFFSET / TIMESTAMP
    val aux: String = "",        // JSON path 或 header 名
    val operator: String,        // Operator 名
    val value: String = "",      // 原始值文本
)
```

## 组件

| 层 | 改动 |
|---|---|
| `core-storage` | 新增 `FilterStore(jsonPath = ~/.kafka-desktop/filters.json)`：`list()` / `save(SavedFilter)`（按 name upsert）/ `delete(name)`。纯 Jackson，无 vault。 |
| `ui-common` | `FilterBuilder`：新增 `snapshot(): FilterSnapshot`、`restore(FilterSnapshot)`；`RuleRow` 支持初始值构造；本地 DTO `FilterSnapshot(logic: String, rows: List<FilterRowData>)` 和 `FilterRowData(fieldKind, aux, operator, value)`。header 第二行加保存区：`Saved: ▼ComboBox(savedFilters)` + `Save…` + `Load` + `Delete`。回调 `onSaveRequested: () -> Unit` / `onLoadFilter: (String) -> Unit` / `onDeleteFilter: (String) -> Unit`；`savedFilters: ObservableList<String>`。 |
| `app` | `FilterStore` 实例；`SaveFilterDialog`（名字输入 + 范围单选：全局 / 仅当前 topic "<t>"，无 currentTopic 时只能全局）；MainView 映射 `FilterRowData ↔ SavedCondition`（1:1）、填充下拉、切 topic 时刷新可见集合。 |

ui-common 不依赖 core-storage（延续既定边界）：FilterBuilder 用本地 `FilterSnapshot/FilterRowData`，app 做与 `SavedFilter/SavedCondition` 的映射。

## 数据流

- **保存**：`Save…` → `onSaveRequested` → app 读 `builder.snapshot()` → `SaveFilterDialog`（名字 + 范围）→ 建 `SavedFilter(name, topic = if 仅当前 then currentTopic else null, logic, conditions)` → `store.save` → 刷新下拉。
- **加载**：选中下拉名字 → `Load` → `onLoadFilter(name)` → app 查 `SavedFilter` → `builder.restore(snapshot)` → **自动 Apply**（调用现有 onApply 立即生效）。
- **删除**：选中 + `Delete` → `onDeleteFilter(name)` → `store.delete` → 刷新下拉。
- **可见集合**：`topic == null || topic == currentTopic`；在 `startConsuming`（切 topic）后刷新。

## 边界 / 错误处理

- 与 iter-7「切 topic reset 过滤」共存：reset 只清当前编辑行，不动已保存列表；切 topic 后下拉刷新为「全局 + 新 topic」。
- `restore` 的行按字符串原样填回；Apply 时由现有 `RuleRow.toCondition()` 校验，非法行产生 null 被忽略（与手填一致）。
- 同名保存 = upsert（按 name 覆盖）。
- `filters.json` 读失败 → 记日志、按空列表起步（同 ConnectionStore 容错）。

## 测试

- 单元（core-storage `FilterStoreTest`）：save→list round-trip（topic=null 与具体 topic 都覆盖）；同名 upsert；delete；空文件/缺文件起步为空。
- E2E（Docker broker）：建 `$.status==FAILED` → 保存为全局 → 切 topic 仍在下拉 → 加载 → 自动过滤生效；再存一个绑定 kdt-e2e 的 → 仅 kdt-e2e 下出现、别的 topic 不出现。

## YAGNI

- 不做：重命名、导入/导出文件、跨设备同步、嵌套 group 保存（builder 本就扁平）、保存"应用顺序/历史"。

## 任务拆分（≈6）
1) core-storage `FilterStore` + `SavedFilter`/`SavedCondition` + 单测
2) ui-common `FilterBuilder.snapshot()/restore()` + `RuleRow` 初始值 + DTO
3) ui-common 保存区 UI + 回调 + `savedFilters`
4) app `SaveFilterDialog` + MainView 接线（映射/填充/切 topic 刷新）
5) 统一编译 + check
6) E2E + tag iter-11
