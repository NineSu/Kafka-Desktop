# iter-10 设计：背压 + LRU 淘汰 + DuckDB 写出 UI 线程

> 状态：brainstorming 已通过设计评审。日期：2026-05-29。前置：iter-9（文件批量发送）已 E2E 验证并 tag，Phase 2 完成。
> 这是 Phase 1 MVP 的最后两个缺口（背压 / LRU）+ 修一处架构遗留（DuckDB 写在 UI 线程）。兑现工具立项动机「大数据量不卡死」。

## 现状三问题

1. `MainView.inboxQueue` = 无界 `ConcurrentLinkedQueue` → 消费快于落库时无限增长 → OOM。
2. `drainInboxIntoRepo()` 在 `AnimationTimer`（**JavaFX UI 线程**）里调用 `repo.insertBatch` → DuckDB 写阻塞 FX 线程，高吞吐下 UI jank。
3. DuckDB `messages` 表每 topic 无上限增长。

## brainstorming 决策

1. 上限到达 → **LRU 淘汰最早（滑动窗口，保留最新 N）**。
2. 上限值 **固定 50 万/topic**，不做配置 UI（YAGNI）。

## 解决方案

### ① 有界队列 + 背压
- `inboxQueue`：`ConcurrentLinkedQueue` → `LinkedBlockingQueue<ConsumedMessage>(QUEUE_CAPACITY)`。
- `onMessage = { msg -> inboxQueue.put(msg) }`：队列满则 **put 阻塞** → consumer 的 poll 循环自然降速到 writer 落库节奏。无丢失、无 OOM（经典生产者-消费者背压）。
- 配套停止语义：消费线程可能阻塞在 `put()`，`consumer.wakeup()` 只能解 poll 不解 put。故 `KafkaMessageConsumer.close()` 增加 `thread.interrupt()`；`runLoop` 捕获 `InterruptedException`，当 `running==false` 时按正常停止处理（不报错）。

### ② 专用 writer 线程（移出 UI 线程）
- 新增后台 writer 线程：循环 `val first = queue.take(); val batch = [first] + queue.drainTo(≤999)` → `repo.insertBatch(clusterId, currentTopic, batch)` → LRU 检查。**全程后台线程**。
- `AnimationTimer` 删除 `drainInboxIntoRepo` 调用，只保留每 500ms `refreshTable()`（查询本就在 bg 线程 Task 里跑；timer 只在 FX 线程创建并启动 Task，开销小）。
- 生命周期：`show()` 启动 writer（守护线程）；`tearDown()` 置停止标志 + interrupt writer。

### ③ LRU 淘汰（cap 50 万/topic）
- 新增 `MessageRepository.evictOldest(clusterId, topic, keepNewest: Int): Int`：删除按 `(ts, partition, "offset")` 升序（最早）的多余行，保留最新 `keepNewest`，返回删除数。
  ```sql
  DELETE FROM messages WHERE rowid IN (
    SELECT rowid FROM messages WHERE cluster_id=? AND topic=?
    ORDER BY ts, partition, "offset" LIMIT ?   -- excess = count - keepNewest
  )
  ```
  （DuckDB 表有隐式 `rowid` 伪列，支持 `DELETE ... WHERE rowid IN (subquery)`。）`@Synchronized` 与其他 repo 方法一致。
- writer 维护 `insertedSinceCheck`；累计 ≥ `EVICT_CHECK_INTERVAL` 才 `count` 一次，若 `count > ROW_CAP` 则 `evictOldest(clusterId, topic, ROW_CAP)`，重置计数。避免每批都查 count。

## 组件

| 层 | 改动 |
|---|---|
| `core-storage` | 新增 `MessageRepository.evictOldest(clusterId, topic, keepNewest)` |
| `core-kafka` | `KafkaMessageConsumer.close()` 加 `thread.interrupt()`；`runLoop` 容错 `InterruptedException` |
| `app/MainView` | `inboxQueue` → 有界阻塞队列；新增 writer 线程（`startWriter`/`stopWriter`）；`onMessage` 改阻塞 put；`AnimationTimer` 去掉 drain；`drainInboxIntoRepo` 逻辑并入 writer；`tearDown` 停 writer |

常量（MainView 私有）：`QUEUE_CAPACITY = 10_000`、`ROW_CAP = 500_000`、`EVICT_CHECK_INTERVAL = 10_000`。

## 边界 / 错误处理

- **topic 切换**：现有顺序 `currentConsumer?.close()`（停止入队）→ 清队列 → `repo.clear(topic)` → 设 `currentTopic`。`currentTopic` 标 `@Volatile`（writer 跨线程读）。切换瞬间残留消息已被 queue.clear 清掉，consumer 已关无新入队 → 无串 topic 风险。
- **writer 异常**：捕获并记日志，不崩 UI；遇 `InterruptedException`（停止信号）则退出循环。
- **空闲**：`queue.take()` 阻塞等待，无消息时 writer 不空转。

## 测试策略

- 单元（core-storage `MessageRepositoryTest` 扩展）：
  - 插入 M 条，`evictOldest(keepNewest=K)`（K<M）→ 剩 K 条，且剩下的是**最新的 K 条**（按 ts/partition/offset），返回删除数 M−K。
  - `count ≤ keepNewest` 时 evict 不删任何行、返回 0。
- E2E（Docker broker）：50 万难现灌，cap 淘汰由单测覆盖；E2E 验证**线程改造无回归**——消费已知条数（如 stress 的 1万）全部入库不丢、UI 流畅、切 topic 正常、backpressure 下消费不卡死。

## YAGNI
- 不做配置 UI、不做按字节/时间的淘汰策略、不做暂停消费选项、不做 per-topic 不同 cap。

## 任务拆分（≈5）
1) core-storage `evictOldest` + 单测
2) core-kafka consumer close/interrupt 容错
3) MainView 有界队列 + writer 线程 + AnimationTimer 改造 + tearDown
4) 统一编译 + check
5) E2E（无回归 + 不丢数据）+ tag iter-10
