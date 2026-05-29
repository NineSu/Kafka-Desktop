# iter-12 设计：本地打包 macOS .dmg（jpackage）

> 状态：brainstorming 已通过设计评审。日期：2026-05-30。前置：iter-11（过滤器保存复用）已 E2E 验证并 tag，Phase 1+2 完成。
> 目标：让工具能作为自包含安装包分发给同事（无需对方装 JDK/Gradle）。本迭代只做本地 macOS .dmg；多平台 CI 留 iter-13。

## brainstorming 决策
- macOS 格式：**.dmg**（拖拽到 Applications）
- 版本号：**0.1.0**
- 图标：**默认**（YAGNI，后续有设计稿再换 .icns）

## 关键事实（已核对）
- 本机：macOS + JDK 17（Temurin），`jpackage` 在 `/usr/bin/jpackage`（17）。
- `app` 用 `application` + javafx-gradle-plugin（modules controls/fxml），mainClass `com.kdt.app.MainKt`。
- **Launcher 类模式已就位**：`Main.kt` 的 `main()` 调 `Application.launch(KafkaDesktopApp::class.java)`，`MainKt` 本身非 `Application` 子类 → **JavaFX 放 classpath 也能启动**（JavaFX 11+ 的已知约束：主类不是 Application 子类即可）。
- 依赖含非模块化 jar（kafka-clients / duckdb_jdbc / jackson / koin / commons-csv / java-keyring）。

## 方案选型

| 方案 | 取舍 |
|---|---|
| Badass JLink (`org.beryx.jlink`) | 包小(~80M)，但非模块化依赖多，merged-module 易触发 split-package / `META-INF/services` 冲突，要调 `forceMerge` |
| jpackage + classpath + 完整运行时（**选用**） | app + 全部依赖 + JavaFX **走 classpath**，捆绑完整 JDK 运行时。包大(~200M) 但几乎必成——已有 launcher 模式，完全绕开模块化 |

内部工具"能双击跑"优先于体积 → **jpackage + classpath**。

**实现用法**：不引第三方 Gradle 插件（如 Panteleyev），而是**直接写一个 `Exec` 任务调 `jpackage` CLI**——避免插件版本/DSL 解析风险，参数完全可控，本地与 CI 行为一致。`jpackage` 由 JDK 自带（本机 `/usr/bin/jpackage`，CI 的 setup-java 也会放到 PATH）。

## 实现（只动 `app` 模块 build.gradle.kts）

- 任务 `copyRuntimeDeps`(Copy)：`from(configurations.runtimeClasspath); from(tasks.named("jar"))` → `layout.buildDirectory.dir("jpackage/input")`。收集 app jar + 全部运行时 jar（含当前平台 JavaFX jar，由 javafx 插件按平台提供）。
- 任务 `jpackage`(Exec)：`dependsOn(copyRuntimeDeps)`，删除旧产物后执行：
  ```
  jpackage --type dmg --name "Kafka Desktop" --app-version 0.1.0 \
    --input build/jpackage/input --dest build/jpackage/dist \
    --main-jar <app jar 名> --main-class com.kdt.app.MainKt
  ```
  app jar 名从 `tasks.jar` 的 archiveFileName 取，避免硬编码。
- 命令：`./gradlew :app:jpackage` → `app/build/jpackage/dist/Kafka Desktop-0.1.0.dmg`。

## 风险 / 注意

- **未签名/未公证**：`.dmg`/`.app` 无 Apple 代码签名。同事首次打开被 Gatekeeper 拦（"身份不明的开发者"）。解决：右键→打开（确认一次），或 `xattr -dr com.apple.quarantine "/Applications/Kafka Desktop.app"`。正式签名需 Apple 开发者账号，内部工具不做。
- **DuckDB / java-keyring 原生库**：均在 jar 内自带、classpath 下自解压加载，无碍。
- **JavaFX 平台 jar**：javafx 插件按当前平台拉对应 classifier 的 jar（含 native），所以本机打的 .dmg 仅含 mac native——这正是"每个 OS 各打各的"的前提，与多平台 CI 计划一致。
- **包体积**：完整 JDK 运行时 → .dmg 约 150–250MB，内部分发可接受。

## 验证

- AI 跑 `./gradlew :app:jpackage`，确认产物存在、检查 .dmg 内 `.app` 结构（`Contents/runtime` 有 JVM、`Contents/app` 有 jar）。
- **"双击能跑"由用户手动确认**（GUI 无法在本环境自动点）。

## 不在本迭代（留 iter-13）
- GitHub Actions 三平台 matrix（macos→.dmg / windows→.msi / ubuntu→.deb）。
- 应用图标、代码签名/公证、自动更新。

## 任务拆分（≈4）
1) app build.gradle.kts 加 `copyRuntimeDeps`(Copy) + `jpackage`(Exec) 任务
2) 跑 `:app:jpackage` 产出 .dmg + 检查 .app 结构（含构建踩坑修复）
3) 用户双击验证
4) 写 README 打包说明（含 Gatekeeper 提示）+ commit + tag iter-12
