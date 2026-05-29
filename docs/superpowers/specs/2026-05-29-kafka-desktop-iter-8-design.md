# iter-8 设计：Topic 管理 + Consumer Group 管理（Phase 2）

> 状态：brainstorming 已通过设计评审。日期：2026-05-29。前置：iter-7（连接持久化/起始offset/导出）已 E2E 验证并 tag。
> Phase 2 第三项「Producer 文件批量发送」留到 iter-9。

## 目标

补齐 Phase 2 的管理类功能：

- **Topic 管理（全集）**：创建（指定 partition/replication + 可选 config）、查看详情/config、增加 partition、删除。破坏性操作（删除、增 partition）需输 topic 名二次确认。
- **Consumer Group 管理**：查看组列表/状态/成员、每 partition 的 current-offset / log-end / lag、reset offset（earliest / latest / timestamp / 指定 offset）。reset 需二次确认 + 活跃组保护。

## 关键边界决策

1. **后端 admin 逻辑** → core-kafka 新增 `TopicAdmin` / `ConsumerGroupAdmin`，各包一个 `AdminClient`，**返回纯领域 DTO**（不外泄 `org.apache.kafka.*` 类型）。比堆进 `KafkaConnection` 更清晰、可测。kafka-clients 3.7.1 提供全部所需 API。
2. **admin 对话框** → 放在 `app`（已依赖 core-kafka + ui-common），直接消费 core-kafka DTO。它们是绑定 AdminClient 的编排，不是通用 widget；避免在 ui-common 做大量 DTO 映射，保持 ui-common 纯 widget 库（延续 iter-7 的 ui-common 不依赖 core-kafka 的边界）。

## 架构 / 组件

| 层 | 新增 / 改动 |
|---|---|
| `core-kafka` | `TopicAdmin`、`ConsumerGroupAdmin`；DTO：`TopicDetail`、`PartitionLag`、`ConsumerGroupInfo`、`OffsetResetSpec` |
| `app/MainView` | 左侧 topic 列表上方工具条：`＋ New topic`、`Consumer Groups…`、`⟳ Refresh`；topic 右键菜单：Describe / Add partitions / Delete |
| `app`（新对话框） | `CreateTopicDialog`、`TopicDetailDialog`、`ConsumerGroupDialog`、`ResetOffsetDialog`、`ConfirmNameDialog`（输名确认通用件） |

不新增 Gradle 模块；不新增第三方依赖（全走现有 kafka-clients AdminClient）。

## core-kafka API 设计

```kotlin
// DTO（纯数据，无 kafka-clients 类型）
data class TopicDetail(
    val name: String,
    val partitions: Int,
    val replicationFactor: Int,
    val configs: Map<String, String>,          // 仅非默认 + 关键项
    val partitionInfos: List<PartitionInfo>,    // partition -> leader/replicas/isr
)
data class PartitionInfo(val partition: Int, val leader: Int, val replicas: List<Int>, val isr: List<Int>)

data class ConsumerGroupInfo(
    val groupId: String,
    val state: String,                          // STABLE/EMPTY/DEAD/...
    val members: Int,
    val lags: List<PartitionLag>,
)
data class PartitionLag(
    val topic: String,
    val partition: Int,
    val committed: Long,                        // 组提交位点；-1 表示无提交
    val logEnd: Long,
    val lag: Long,                              // max(0, logEnd - committed)
)

sealed interface OffsetResetSpec {
    data object Earliest : OffsetResetSpec
    data object Latest : OffsetResetSpec
    data class AtTimestamp(val epochMs: Long) : OffsetResetSpec
    data class AtOffset(val offset: Long) : OffsetResetSpec
}

class TopicAdmin(private val admin: AdminClient) {
    fun list(): List<String>
    fun describe(topic: String): TopicDetail                       // describeTopics + describeConfigs
    fun create(name: String, partitions: Int, replication: Short, configs: Map<String,String> = emptyMap())
    fun addPartitions(topic: String, newTotal: Int)                // createPartitions
    fun delete(topic: String)
}

class ConsumerGroupAdmin(private val admin: AdminClient) {
    fun list(): List<String>                                       // listConsumerGroups
    fun describe(groupId: String): ConsumerGroupInfo               // describe + committed(listConsumerGroupOffsets) + logEnd(listOffsets) → lag
    fun resetOffsets(groupId: String, topic: String, spec: OffsetResetSpec)  // 计算目标 offset → alterConsumerGroupOffsets
}
```

`AdminClient` 由 `KafkaConnection.adminClient`（已是 public val）提供，对话框用 `connection.adminClient` 构造 admin 类。

## 数据流

- **Topic CRUD**：工具条/右键 → 后台 `javafx.concurrent.Task` 调 `TopicAdmin` → 成功后 `onConnectClicked` 同款逻辑刷新 topic 列表 + actionLabel 反馈。
- **Consumer Group**：`Consumer Groups…` → 后台 `list()` 填左侧 ListView → 选组 → 后台 `describe(group)` 填右侧表（topic/partition/committed/logEnd/lag）。`Reset offsets…` → ResetOffsetDialog → `resetOffsets(...)`。

## 错误处理（破坏性操作）

- **reset 活跃组保护**：执行前 `describe` 检查 `state`；非 `EMPTY`/`DEAD` → 弹警告「组 X 有活跃消费者（state=STABLE, N members），reset 会被 broker 拒绝，请先停止消费者」，不发起调用。broker 仍拒绝（如 rebalancing）→ 异常回显对话框。
- **删除 / 增 partition**：`ConfirmNameDialog` 要求输入完整 topic 名，匹配才启用 OK；增 partition 额外校验 newTotal > 当前数（Kafka 只能增不能减）。
- 所有 admin 调用在后台线程；异常 → actionLabel 红字 / 对话框内红字提示，UI 不崩。
- reset 到 timestamp 无匹配位点的 partition → 回退 logEnd（与 iter-7 消费 FromTimestamp 一致语义）。

## 测试策略（按既定习惯，开发完统一测）

- 单元：`PartitionLag.lag` 计算（committed=-1 → lag=logEnd；committed>logEnd → 0）；`OffsetResetSpec` → 目标 offset 解析（earliest/latest 走 listOffsets，offset 直传，timestamp 走 forTimestamp）；DTO 映射。AdminClient 用 mockk。
- E2E（Docker `kdt-kafka`）：见最终待测清单。

## YAGNI（不做）

- topic config 的可视化全量编辑（只读展示 + 仅创建时设 config；改 config 留待需要时）。
- consumer group 删除、成员级 assignment 详情。
- ACL / quota 管理。

## 任务拆分（≈9）

core-kafka：1) DTO + TopicAdmin（list/describe/create/addPartitions/delete） 2) ConsumerGroupAdmin（list/describe + lag 计算） 3) ConsumerGroupAdmin.resetOffsets（含目标 offset 解析）
app-topic：4) MainView 工具条 + CreateTopicDialog 5) TopicDetailDialog + addPartitions/delete + ConfirmNameDialog
app-cg：6) ConsumerGroupDialog（列表 + lag 表） 7) ResetOffsetDialog + 活跃组保护
收尾：8) 统一编译 + 单测 9) E2E 待测清单 + tag iter-8
