# iter-9 设计：Producer 文件批量发送（Phase 2 收尾）

> 状态：brainstorming 已通过设计评审。日期：2026-05-29。前置：iter-8（topic/CG 管理）已 E2E 验证并 tag。
> 完成后 Phase 2 全部交付（单条发送/replay = iter-6，topic/CG 管理 = iter-8，文件批量 = iter-9）。

## 目标

从文件批量读取消息并发送到一个 topic。与 iter-7 导出**镜像对称**：导出的 CSV/JSON/JSONL 可直接回灌（export → import 闭环）。

## brainstorming 决策

1. 输入格式：**CSV / JSON / JSONL 三种**，列/字段与导出同构。
2. 目标 topic：**对话框选单一 topic**，整个文件发到该 topic（文件无需 topic 列）。
3. 错误行：**跳过 + 计数**，不中断整批。

## 组件（不新增 Gradle 模块 / 第三方依赖）

| 层 | 新增 |
|---|---|
| `core-storage` | `MessageImporter`（解析三格式，复用 `ExportFormat` 枚举 + 现有 commons-csv / Jackson）；DTO `ImportedMessage(key, value, headers)` |
| `app` | `ImportDialog`（格式 ChoiceBox + 目标 topic ComboBox）；MainView 头部 `Import…` 按钮 + 接线 |

## core-storage API

```kotlin
data class ImportedMessage(
    val key: String?,
    val value: String?,
    val headers: Map<String, String?>,
)

class MessageImporter {
    /**
     * 解析 [file]，对每个合法行调用 [onRow]；返回**跳过的非法行数**。
     * 只取 key/value/headers；忽略 offset/timestamp/partition（broker 重新分配）。
     */
    fun parse(format: ExportFormat, file: Path, onRow: (ImportedMessage) -> Unit): Long
}
```

- **CSV**：commons-csv 读表头（partition,offset,timestamp,key,value,headers），仅用 key/value/headers 列；headers 单元格是 JSON 串 → 宽松解析为 map，解析失败按空 headers，不整行丢。读取行抛异常 → skipped++。
- **JSONL**：逐行 `readTree`；非 JSON 行 → skipped++。取 key/value 字段（缺省 null）、headers（对象 → map）。
- **JSON array**：整体 `readTree` 为数组；逐元素转 ImportedMessage，非对象元素 → skipped++。

## 数据流（app）

1. `Import…`（连接后可用）→ `FileChooser`（过滤 *.csv/*.json/*.jsonl）选文件 → **按扩展名推断 ExportFormat**。
2. `ImportDialog(topics, defaultTopic, inferredFormat)`：格式 ChoiceBox（预填推断值，可改）+ 目标 topic ComboBox。返回 (format, topic) 或取消。
3. 后台 `Task`：
   - `producer = currentProducer ?: KafkaMessageProducer(currentBootstrap, currentAuth)`
   - `val futures = mutableListOf<Future<SendResult>>()`
   - `skipped = importer.parse(format, file) { row -> futures += producer.send(topic, row.key, row.value, row.headers, null) }`
   - await 每个 future，统计 sent / failed。
4. actionLabel：`✓ sent N · skipped M · failed K`（M=解析跳过，K=发送失败）。

## 错误处理

- 解析失败行 → skipped++，继续。
- 发送失败 future → failed++，继续。
- 文件不可读 / 格式不识别 → actionLabel 红字；ImportDialog 中格式 ChoiceBox 兜底（推断不出时默认 JSONL）。

## 测试策略（统一最后做）

- 单元（core-storage）：
  - **round-trip**：用 MessageExporter 导出若干 MessageRow 到临时文件，再 MessageImporter.parse 出来，逐条核对 key/value/headers 一致（CSV/JSON/JSONL 各一）。
  - malformed JSONL 行 → skipped 计数正确。
  - CSV 含逗号/引号/换行的 value → 还原一致。
- E2E（Docker broker）：导出 kdt-e2e → Import 回灌到新建 topic → 消费验证条数 + 内容。

## YAGNI

- 不做 per-row topic 列、不做发送限速/节流、不做 dry-run 预览、不做导入时的 schema 校验。

## 任务拆分（≈5）
1) core-storage `MessageImporter` + `ImportedMessage`
2) app `ImportDialog`
3) MainView `Import…` 按钮 + FileChooser + 后台 parse+send 统计
4) 单测（round-trip + malformed + CSV 转义）
5) 统一编译 + 单测跑通 + E2E 清单 + tag iter-9
