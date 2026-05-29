# iter-7 设计：连接持久化 · 起始 offset 选择 · 导出

> 状态：已通过 brainstorming 决策；按 `/loop` 自主推进指令实现（跳过人工 review gate，列入最终测试清单）。
> 日期：2026-05-29 · 前置：iter-6（Producer + Replay）已 tag。

## 目标

补齐 Phase 1 MVP 三个缺口：

- **A. 集群连接持久化**：每次开 App 不再手填 bootstrap.servers + 鉴权；连接列表 CRUD；敏感字段（密码/keystore 密码）存 OS Keystore。
- **B. 起始 offset 选择**：从头 / 从尾 / 每 partition 最近 N 条 / 指定 timestamp / 指定 offset。
- **D. 过滤结果导出**：把当前 filter+topic 命中的**全量**消息流式导出为 CSV / JSON / JSONL。

## 关键决策（brainstorming 已定）

1. 敏感信息 → **OS Keystore**（java-keyring，跨 mac Keychain / win CredMgr / linux libsecret，纯 JVM 无 native 编译）。元数据明文 JSON。
2. 连接入口 → **只保留下拉选已保存连接**（移除旧的临时手填 bootstrap 输入框，breaking change）。首次启动列表为空时提示"点击添加连接"。
3. 导出 → **过滤后全量**，流式写出，不抽空内存。
4. "最近 N 条" → **每 partition 各 N 条**（Kafka 原生 seek 语义）。

## 模块划分（不新增 Gradle 模块，全部复用）

| 模块 | 新增 / 改动 |
|---|---|
| `core-kafka` | 新增 `StartingPosition` sealed interface；`KafkaMessageConsumer` 用 assign+seek 替换 `fromBeginning: Boolean` |
| `core-storage` | 新增 `ConnectionStore` + `SavedConnection`/`StoredAuth`（secret-free）+ `SecretVault` 接口 + `KeyringSecretVault`；新增 `MessageExporter` + `ExportFormat`；`MessageRepository.streamFiltered` |
| `ui-common` | 新增 `ConnectionVM`（含 `AuthFormState`）；`ConnectionForm` 改为下拉 + Manage 按钮；新增 `ConnectionManagerDialog`、`StartFromPicker`、`ExportDialog` |
| `app/MainView` | 顶部连接区改下拉；topic 选中弹 `StartFromPicker`；顶部加 `Export…` 按钮；新增 `ConnectionMapping`（ConnectionVM ↔ SavedConnection + vault 读写） |
| 依赖 | `core-storage` 加 `java-keyring`、`commons-csv`；版本目录新增条目 |

模块边界原则：core-storage **不依赖 ui-common**（避免 UI→存储反向依赖，也不把 DuckDB 拖进 UI 库）。`AuthFormState`↔`StoredAuth` 的映射放在 `app`（组合根），用字符串表示 protocol/mechanism 以免 core-storage 依赖 ui-common 的枚举。

## A. 连接持久化 详细设计

### 数据模型（core-storage）
```kotlin
data class SavedConnection(
    val id: String,                 // UUID
    val name: String,               // 用户可见标签，唯一
    val bootstrapServers: String,
    val auth: StoredAuth,
)
// secret-free：密码类字段不在这里，存 vault
data class StoredAuth(
    val protocol: String,           // SecurityProtocol.name
    val saslMechanism: String,      // SaslMechanism.name
    val username: String,
    val truststorePath: String,
    val keystorePath: String,
    val keytabPath: String,
    val kerberosService: String,
    val kerberosPrincipal: String,
    val verifyHostname: Boolean,
)
```

### 存储
- `ConnectionStore(jsonPath, vault)`：`list()` / `save(conn, secrets)` / `delete(id)`。
  - 非密字段 → Jackson 写 `~/.kafka-desktop/connections.json`（list 形式）。
  - 密字段（`password`/`truststorePassword`/`keystorePassword`/`keyPassword`）→ vault，key = `connId` + 字段名。
- `interface SecretVault { fun put(connId, field, secret); fun get(connId, field): String?; fun deleteAll(connId) }`
- `KeyringSecretVault`：java-keyring，domain=`kafka-desktop`，account=`$connId/$field`。Keychain 访问失败时降级（记日志，返回 null），不让整个 App 崩。

### 映射（app/ConnectionMapping.kt）
- `ConnectionVM → SavedConnection + secretsMap`：拆出 4 个密字段。
- `SavedConnection → ConnectionVM`：非密字段直接填 `AuthFormState`；密字段**懒加载**（连接/编辑时才从 vault 读，减少 Keychain 弹窗）。

### UI
- `ConnectionVM(id, name, bootstrap, authState: AuthFormState)`（ui-common，可变）。
- `ConnectionForm`：`ComboBox<ConnectionVM>`（显示 name）+ `Manage…` 按钮 + `Connect` 按钮 + 状态标签。暴露 `connections: ObservableList`、`selected` 属性、`onConnect`、`onManage`。
- `ConnectionManagerDialog`：`ListView<ConnectionVM>` + Add / Edit / Delete / Test 按钮。Add/Edit 子表单 = name + bootstrap + 复用 `AuthDialog`。回调 `onSave(vm)` / `onDelete(id)` / `onTest(vm, cb)`，由 app 落到 ConnectionStore；Test 用 `KafkaConnection.listTopics()` 探活。

## B. 起始 offset 详细设计

### core-kafka
```kotlin
sealed interface StartingPosition {
    data object Beginning : StartingPosition
    data object End : StartingPosition
    data class LastN(val n: Long) : StartingPosition          // 每 partition
    data class FromTimestamp(val epochMs: Long) : StartingPosition
    data class FromOffset(val offset: Long) : StartingPosition
}
```
`KafkaMessageConsumer` 构造参数 `fromBeginning: Boolean` → `position: StartingPosition`。
- `Beginning`：assign 全部 partition + `seekToBeginning`。
- `End`：assign + `seekToEnd`。
- `LastN(n)`：assign；逐 partition `end = endOffsets`，`seek(max(beginningOffset, end - n))`。
- `FromTimestamp(ms)`：assign；`offsetsForTimes`；对每个 partition seek 到返回 offset（无匹配则 seekToEnd）。
- `FromOffset(o)`：assign；对每个 partition `seek(o)`（越界由 Kafka 报错，onError 回传）。

实现：start() 里先 `consumer.partitionsFor(topic)` 拿 partition 列表 → `assign` → 按 position seek → 进入 poll 循环。全程不用 subscribe（seek 需要已分配分区）。

### UI
`StartFromPicker`（ui-common）：RadioButton 5 选项 + 各自参数控件（N 的 Spinner 默认 1000、timestamp 的 DatePicker+时间字段默认 now-1h、offset 的数字字段）。返回 `StartingPosition`。MainView topic 选中 → 弹 picker → `startConsuming(topic, position)`，topicHeader 显示所选位置。

## D. 导出 详细设计

### core-storage
- `MessageRepository.streamFiltered(clusterId, topic, filter, rowConsumer: (MessageRow) -> Unit)`：单条 SELECT（无 LIMIT）+ 流式 ResultSet 迭代，逐行回调，含 partition/offset/ts/key_str/value_str/headers。
- `MessageExporter`：
  - `enum ExportFormat { CSV, JSON, JSONL }`
  - `export(format, file, rows: Sequence<MessageRow>)`，列：partition, offset, timestamp(ISO-8601), key, value, headers(JSON 串)。
  - CSV → commons-csv（正确转义引号/换行/逗号）。JSONL → Jackson 每行一对象。JSON → JsonGenerator 流式数组。

### UI / 接线
- `ExportDialog`（ui-common）：format 三选 RadioButton，返回 `ExportFormat`。
- MainView `Export…` 按钮 → ExportDialog 选格式 → JavaFX `FileChooser`（带扩展名过滤）选目标文件 → 后台线程跑 `streamFiltered` → `MessageExporter` → actionLabel 显示 `✓ exported N rows → file`。用当前 `currentFilter` + `currentTopic`。

## 错误处理
- Keychain 不可用 → 降级记日志，连接保存仍成功（仅密码不持久）。
- seek 越界 / timestamp 无匹配 → onError 回传 UI 状态，不崩溃。
- 导出 IO 失败 → actionLabel 红字提示。

## 测试策略（按 /loop 指令统一在最后执行）
- 单元：`StoredAuth` JSON round-trip；`StartingPosition` seek 计算（mock consumer）；`MessageExporter` 三种格式输出 + 转义。
- 集成：连接保存→重启→下拉出现；各起始位置消费验证；导出 N 行文件行数核对。
- E2E：见最终待测试清单。

## YAGNI（明确不做）
- 连接导入/导出文件、连接分组、连接 ping 自动重连。
- 起始位置的"每分区不同 offset"精细编辑。
- 导出字段自定义勾选、导出压缩。

## 任务拆分（≈12）
A：1) 依赖+版本目录 2) StoredAuth/SavedConnection/SecretVault 3) KeyringSecretVault 4) ConnectionStore 5) ConnectionVM+ConnectionForm 改造 6) ConnectionManagerDialog 7) app ConnectionMapping + 接线
B：8) StartingPosition + consumer 改造 9) StartFromPicker + MainView 接线
D：10) streamFiltered + MessageExporter 11) ExportDialog + MainView 接线
收尾：12) 统一编译、单测、E2E 清单、tag iter-7
