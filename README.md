# Kafka Desktop

一个自研的 JavaFX 桌面 Kafka 工具，用来替代 IDEA BigDataTool 自带 Kafka 插件的两个痛点：**不支持多条件过滤**、**数据量大时卡死**。

## 下载

各平台自包含安装包见 [Releases](https://github.com/NineSu/Kafka-Desktop/releases)（无需自己装 Java）：

| 平台 | 文件 |
|---|---|
| macOS | `Kafka.Desktop-*.dmg` |
| Windows | `Kafka.Desktop-*.msi` |
| Linux | `kafka-desktop_*_amd64.deb` |

macOS 首次打开若被 Gatekeeper 拦（"身份不明的开发者"）：**右键 →「打开」** 确认一次即可。

## 功能

**消费 / 浏览**
- 多条件可视化 AND/OR 过滤器，实时 SQL 预览；过滤器可命名保存复用（全局或绑定 topic）
- 起始位置：从头 / 从尾 / 每分区最近 N 条 / 指定时间戳 / 指定 offset
- 消息详情面板：Text / Hex / JSON Tree
- 本地 DuckDB 缓存 + 背压（有界队列）+ LRU 淘汰（每 topic 50 万条上限），大数据量不卡死

**连接 / 认证**
- 已保存连接的增删改查 + 连接测试；敏感信息（密码 / keystore 密码）存 OS Keychain
- 全部认证方式：无认证 / SASL(PLAIN·SCRAM) / SSL / mTLS / Kerberos

**生产 / 管理**
- 单条发送、右键 replay、文件批量发送（CSV / JSON / JSONL）
- 过滤结果导出（CSV / JSON / JSONL，全量流式）
- Topic 管理：创建 / 详情 / 增加分区 / 删除（破坏性操作输名二次确认）
- Consumer Group：查看 lag、reset offset（earliest/latest/timestamp/offset，活跃组保护）

## 技术栈

Kotlin 1.9 · JDK 17 · Gradle 8.10（Kotlin DSL）· JavaFX 21 · Koin · kafka-clients 3.7 · DuckDB · Jackson · JUnit5/kotest

多模块：`app`（入口 + DI）· `core-kafka` · `core-filter` · `core-storage` · `core-auth` · `ui-common`

## 构建 / 运行

```bash
./gradlew check        # 编译 + 全部单元测试
./gradlew :app:run     # 启动应用
```

需要本地 JDK 17。

## 打包（macOS .dmg）

```bash
./gradlew :app:jpackage
# 产物：app/build/jpackage/dist/Kafka Desktop-<version>.dmg
```

打出的 `.dmg` **自带 JDK 运行时**——同事拿到后双击装、直接用，无需安装 Java / Gradle。

注意：
- **只能在对应系统上打对应包**：`jpackage` 在 macOS 上产出 `.dmg`，在 Windows 上产出 `.msi`，在 Linux 上产出 `.deb`。
- **多平台一键发版**：推一个 `v*` tag（如 `v1.0.0`）即触发 `.github/workflows/release.yml`，在 macOS/Windows/Linux 三个 runner 上各打一个安装包并发布到 GitHub Release。macOS 版本号须以非零整数开头，故 tag 用 `v1.0.0` 这类（不要 `v0.x`）。
- **macOS 版本号**：必须以非零整数开头（CFBundleVersion 规则），故当前用 `1.0.0`（见 `app/build.gradle.kts` 的 `appVersion`，CI 用 `-PappVersion` 传入 tag 版本）。
- **未签名 / 未公证**：把 `.dmg` 传给同事后，对方首次打开会被 Gatekeeper 拦（"身份不明的开发者"）。解决：**右键 →「打开」** 确认一次，或终端执行
  `xattr -dr com.apple.quarantine "/Applications/Kafka Desktop.app"`。
  （正式签名 / 公证需 Apple 开发者账号，内部工具一般不做。）

## 设计文档

每个迭代的设计 spec 见 `docs/superpowers/specs/`；进度见 git tag `iter-1` … `iter-12`。
