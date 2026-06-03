# iter-14 设计：Islands 主题系统（Light/Dark + 菜单栏/Toolbar + 主题色设置）

> 状态：brainstorming 已通过设计评审（含浏览器可视化样稿确认）。日期：2026-06-03。前置：iter-13 多平台 CI 完成；当前裸 JavaFX Modena 外观。
> 目标：换成 **Islands Light/Dark 双主题**，加**完整菜单栏 + Toolbar**，加 **Settings → Appearance & Behavior** 选主题色（7 预设 + 取色器），运行时即时生效并持久化。
> 路线：**手写 CSS + JavaFX looked-up colors，零新增依赖**（评审拍板，否决 AtlantaFX）。

## brainstorming 决策
- 视觉方向：**Islands**（圆角岛屿卡片 + 缝隙透背景 + 柔和阴影），缝隙收紧到 **5px**（紧凑）。
- **Light + Dark 一起做**；首启默认 Islands Light。
- Toolbar：**完整菜单栏** File/View/Tools/Help + ⚙ Settings 入口。
- 主题色：**7 预设全内置**（Blue 默认）+ JavaFX `ColorPicker` 自定义取色器。
- 实现：手写一份 CSS，颜色全走 looked-up 变量；**无新增依赖**。

## ① 主题机制（核心）

一份 `islands.css`（`ui-common/src/main/resources/com/kdt/ui/common/theme/islands.css`），所有结构样式（岛屿卡片、圆角、留白、按钮/输入/表格/列表/滚动条/标签页/对话框）颜色全部引用语义 looked-up 变量。

- **Light/Dark** = root 上两个样式类 `.theme-light` / `.theme-dark`，各自定义全套调色变量（见 ③）。切主题 = 换 root 的 class。
- **强调色** = `root.style = "-accent: #xxxxxx;"` 内联覆盖；hover/pressed/selected 等用 `derive(-accent, ±k%)` 派生 → 换色**瞬时生效**、无需重载样式表。
- **岛屿+缝隙**：根容器背景 `-sea`；各区域内容套 `.island`（`-island` 背景 + 圆角 11 + 1px `-island-border` + 柔和 dropshadow）。保留 `SplitPane`（仍可拖拽），`.split-pane` 与 `.split-pane-divider` 背景设 `-sea`、divider 做成细缝。

looked-up 变量清单：`-sea -island -island-border -line -text -text-muted -text-faint -control-bg -control-border -chip -selection-bg -selection-text -accent -status-ok -status-ok-bg -status-err -status-err-bg -status-info`。base 规则里给每个变量留默认值，保证样式表单独可用。

## ② 新增组件

| 组件 | 位置 | 职责 |
|---|---|---|
| `ThemeManager` | `ui-common/.../theme/ThemeManager.kt` | 单例：持有 `mode`(Light/Dark) + `accentHex`（均为可观察属性）；`register(Scene)` / `register(DialogPane)` 应用样式表+class+inline accent 并弱引用跟踪；`setMode/setAccent` 刷新所有已注册目标并落盘；`load()` 启动时读设置 |
| `AccentPreset` | 同目录 | 7 个预设（名+hex）枚举/常量 + 默认 Blue |
| `SettingsDialog` | `ui-common/SettingsDialog.kt` | 左导航树（Appearance & Behavior → **Appearance** 功能化）+ 右面板（见 ⑤）；Cancel 还原打开时快照、OK/Apply 落盘 |
| `AppTopBar` | `app/AppTopBar.kt` | App 图标 + `MenuBar`(File/View/Tools/Help) + 右侧主题色指示胶囊 + ⚙ Settings 按钮 |
| `AppSettingsStore` | `core-storage/AppSettingsStore.kt` | 仿 `ConnectionStore`：`{themeMode, accentHex}` 存 `~/.kafka-desktop/appearance.json`（Jackson，读失败降级默认） |
| `AppSettings` | `core-storage/` | data class：`themeMode: String = "light"`, `accentHex: String = "#3574F0"` |

## ③ 配色（最终 hex）

**Light（`.theme-light`）**
`-sea #EDEEF2` · `-island #FFFFFF` · `-island-border #E2E4EA` · `-line #ECEEF2` · `-text #272930` · `-text-muted #7A7E87` · `-text-faint #A0A4AD` · `-control-bg #F6F7F9` · `-control-border #CDD1DA` · `-chip #EEF0F4` · `-selection-bg` = `derive(-accent,80%)` · `-selection-text` = `derive(-accent,-20%)` · `-status-ok #1F9254`/bg`#E7F4EC` · `-status-err #D4374B`/bg`#FBE9EB` · `-status-info #3574F0`

**Dark（`.theme-dark`）**
`-sea #1A1B1E` · `-island #2B2D30` · `-island-border #393B40` · `-line #34363A` · `-text #DFE1E5` · `-text-muted #9DA0A8` · `-text-faint #6F737A` · `-control-bg #363840` · `-control-border #4B4E55` · `-chip #3A3D42` · `-selection-bg` = `derive(-accent,-50%)` · `-selection-text` = `derive(-accent,55%)` · `-status-ok #62B06A` · `-status-err #E16A77` · `-status-info #6BA5F7`

> 选中态（`-selection-bg/-text`）**跟随 `-accent` 派生**，换主题色时选中色一起变；样稿里的 `#E7F0FF`/`#2F3A55`/`#A8C7FA` 是 Blue 默认下的近似效果。`-status-*` 为语义状态色，与 accent 独立。

**Accent 预设**（与明暗无关，用户选；默认 Blue）
Blue `#3574F0` · Purple `#8A5CF6` · Teal `#16A394` · Green `#1F9254` · Amber `#E0871E` · Red `#E0556B` · Graphite `#5B6470`

**度量**：圆角 岛屿 11 / 控件 7 / chip 6；缝隙/间距 5px；字体 `Inter, "SF Pro Text", -apple-system, "Segoe UI", system-ui`，等宽 `"JetBrains Mono", ui-monospace, monospace`。

## ④ 菜单栏 + Toolbar

`MainView` 根 `BorderPane.top` 改为 `VBox(AppTopBar, connectionForm)`。`AppTopBar` 用窗口内 `MenuBar`（**macOS 不开 `useSystemMenuBar`**，保持 Islands 外观）。菜单项**仅路由到已有动作**：

- **File**：New Topic… / Import… / Export… / —— / Exit
- **View**：Refresh Topics / —— / Theme ▸ (Light · Dark 快捷切换)
- **Tools**：Manage Connections… / Consumer Groups… / Send Message… / —— / Settings…
- **Help**：About / Open GitHub repo

右侧：主题色指示胶囊（●+名）+ ⚙ 按钮 → 二者都打开 `SettingsDialog`。禁用态沿用现状（未连接时相应项 disable）。

## ⑤ Settings → Appearance & Behavior

`SettingsDialog`：左 nav 树（`Appearance & Behavior ▾ → Appearance`[选中] / Behavior；Connections / Keymap 占位灰显）+ 右 `Appearance` 面板：
- **Theme**：Light / Dark 分段（或 ToggleGroup）→ `ThemeManager.setMode` 即时预览
- **Accent**：7 预设色板（选中带 ✓ 环）+ `＋自定义` 触发 JavaFX `ColorPicker`（即"取色器"）+ HEX 文本框 → `ThemeManager.setAccent` 即时预览
- **Preview**：示例主按钮 + 选中行 + 链接，随 accent/mode 实时变
- 底部 Cancel / Apply / OK。打开时存快照；Cancel 还原；Apply/OK 落盘

## ⑥ 改动已有代码

- `MainView.kt`：加 `AppTopBar`；`Scene` 经 `ThemeManager.register(scene)` 挂主题；**~25 处内联状态样式**（`#c0392b/#16a085/#2c3e50` + padding）→ 改 style class（`.status-ok` / `.status-err` / `.status-info` / `.status-label` / `.mono`）；菜单与 Settings 接线；打开各对话框时 `ThemeManager.register(dialog.dialogPane)`
- `ConnectionForm.kt`：`setStatus` 内联色 → 切 `.status-err` / `.status-info` class（`status-label` 已有）
- 各对话框（~12，app + ui-common）：经一个 ui-common 扩展 `Dialog<*>.applyTheme()` 在 `init` 末尾注册 dialogPane；ProducerDialog 等内联 `monospace` → `.mono`
- `KafkaDesktopApp` / `Main.kt`：`init`/启动时 `ThemeManager.load(settingsStore)` 后再 `MainView().show()`
- Koin `app/di/AppModule.kt`：注册 `AppSettingsStore`、`ThemeManager`（沿用现有 DI 模式）
- **build 文件不动**（无新依赖）

## ⑦ 持久化 & 启动

`AppSettingsStore` 仿 `ConnectionStore`：`load(): AppSettings`（无文件/损坏 → 默认 Light+Blue）/ `save(AppSettings)`（`Files.createDirectories` + Jackson pretty）。`defaultPath()` = `~/.kafka-desktop/appearance.json`。启动序：`startKoin` → `ThemeManager.load()` 读出 mode+accent → `MainView.show()` 内 register 时即套用 → 后续每个 register 的 scene/dialog 自动继承。

## ⑧ 错误处理 / 边界

- 设置文件缺失/损坏 → try/catch 回落默认（仿 ConnectionStore）。
- HEX 校验：`^#[0-9A-Fa-f]{6}$`，非法则忽略保留旧色。
- 预设/取色器变更**立即预览**；Cancel 还原打开时快照；OK/Apply 才落盘。
- 运行中开的新对话框 register 时即应用当前主题（不会回到 Modena）。

## ⑨ 测试

- `AppSettingsStore`：存取往返、缺失/损坏降级、临时路径隔离（JUnit5 + Kotest，沿用现风格）。
- `ThemeManager`：mode/accent 状态切换、HEX 校验（合法/非法）、预设列表完整性、`setAccent` 触发监听。
- 不引入 TestFX（YAGNI）；CSS/视觉两套主题 + 换色手动验证（`./gradlew :app:run`）。

## 不在本迭代
- 跟随系统深浅色；自定义字体/字号；Behavior/Keymap/Connections 页功能化（仅占位）。
- 每连接独立主题、动画过渡。

## 任务拆分（≈6）
1) `AppSettings` + `AppSettingsStore`（core-storage）+ 单测
2) `islands.css`（base + light + dark 调色 + 全控件样式）
3) `ThemeManager` + `AccentPreset` + `Dialog.applyTheme()`（ui-common）+ 单测
4) `MainView` 接主题：Scene register + ~25 内联样式改 class + 各对话框 register
5) `AppTopBar`（菜单栏+Toolbar）接现有动作 + Koin 注册 + 启动序
6) `SettingsDialog`（Appearance：Theme/Accent/取色器/预览）+ 菜单接线；两套主题 + 换色手动回归
