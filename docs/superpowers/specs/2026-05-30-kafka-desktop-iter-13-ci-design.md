# iter-13 设计：多平台 CI（GitHub Actions 出 .dmg / .msi / .deb）

> 状态：brainstorming 已通过设计评审。日期：2026-05-30。前置：iter-12（本地 .dmg）已 tag；repo 已推到 github.com/NineSu/Kafka-Desktop（main + iter-1~12 tags + LICENSE）。
> 目标：push 一个 `v*` tag 即自动在三平台构建自包含安装包并发 GitHub Release。

## brainstorming 决策
- 触发：**push `v*` tag**（如 v1.0.0）。
- Windows：**.msi**（CI 装 WiX 3.x）。
- 产物：**上传 artifact + 建 GitHub Release** 挂三个包。

## ① Gradle `:app:jpackage` 跨平台化

现状：mac 专用（调 `scripts/package-mac-dmg.sh`）。改为按 OS 分支（`copyRuntimeDeps` 不变；各 OS runner 的 `runtimeClasspath` 自动含该平台 JavaFX native jar）。

| OS（`OperatingSystem.current()`） | jpackage 调用 | 产物 |
|---|---|---|
| macOS | `bash scripts/package-mac-dmg.sh`（现有，app-image + hdiutil dmg） | `.dmg` |
| Windows | `jpackage --type msi --name "Kafka Desktop" --app-version <v> --input … --dest … --main-jar … --main-class com.kdt.app.MainKt --win-shortcut --win-menu --win-dir-chooser` | `.msi` |
| Linux | `jpackage --type deb --name "Kafka Desktop" --linux-package-name kafka-desktop --app-version <v> --input … --dest … --main-jar … --main-class com.kdt.app.MainKt --linux-shortcut` | `.deb` |

- 版本：`val appVersion = (findProperty("appVersion") as String?) ?: "1.0.0"`，CI 用 `-PappVersion=<tag 去掉 v>` 传入；deb/msi/dmg 版本一致。
- deb 包名须小写无空格 → `--linux-package-name kafka-desktop`，应用显示名仍 `Kafka Desktop`。
- mac 脚本接收 appVersion 参数（已支持）。
- 回归：改完在本机重跑 `:app:jpackage` 确认 .dmg 仍正常（不破坏 iter-12）。

## ② Workflow `.github/workflows/release.yml`

```yaml
name: Release installers
on:
  push:
    tags: ['v*']
permissions:
  contents: write            # 建 Release 需要
jobs:
  package:
    strategy:
      fail-fast: false        # 三 OS 独立，一次看全失败
      matrix:
        include:
          - { os: macos-latest,   glob: 'app/build/jpackage/dist/*.dmg' }
          - { os: windows-latest, glob: 'app/build/jpackage/dist/*.msi' }
          - { os: ubuntu-latest,  glob: 'app/build/jpackage/dist/*.deb' }
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4   # temurin 17，自带 jpackage
        with: { distribution: temurin, java-version: '17' }
      - if windows: choco install wixtoolset --no-progress   # jpackage msi 需 WiX 3.x
      - run: ./gradlew :app:jpackage -PappVersion=${TAG#v}
        shell: bash             # 三平台统一（windows 用 git-bash）
      - uses: actions/upload-artifact@v4   # 上传 matrix.glob
  release:
    needs: package
    runs-on: ubuntu-latest
    steps:
      - uses: actions/download-artifact@v4   # 取全部
      - uses: softprops/action-gh-release@v2   # 建 Release 挂全部包
```

- `TAG=${GITHUB_REF_NAME}`，版本 `${TAG#v}`。
- `shell: bash` 让 `./gradlew` 在 windows runner 上也能跑（git-bash 自带）。
- upload-artifact 名字带 OS 区分；release 任务 download 全部再发布。

## 风险（CI 无法在本环境验证）

- **WiX 3.x（Windows msi 最大风险）**：jpackage 用 `candle/light`（WiX 3，非 4/5）。windows-latest 可能预装；保险 `choco install wixtoolset`。首跑最可能在此失败 → 退路：该 OS 暂时改 `--type app-image` 打 zip。
- **windows 上 `./gradlew`**：靠 `shell: bash`。若仍有问题，退路用 `gradle/actions/setup-gradle` 或 `gradlew.bat`。
- **deb 依赖**：ubuntu-latest 有 dpkg/fakeroot，jpackage deb 一般直接可用。
- **首跑迭代**：`fail-fast:false` + 一次看全；按报错调 1–2 轮属正常。

## 验证方式

AI 写好 workflow + 改 Gradle，本机回归 .dmg，提交推 main。**用户推 `v1.0.0` tag 触发首次构建**（建真 Release）。AI 用 `gh run list/view`（若本机装了 gh 并登录）读日志协助排错；否则用户贴 Actions 报错。

## 不在本迭代
- 代码签名/公证（mac）、Authenticode（win）。
- 自动更新、Homebrew tap、winget。

## 任务拆分（≈4）
1) Gradle `jpackage` 跨平台化 + `-PappVersion` + 本机 mac 回归
2) 写 `.github/workflows/release.yml`
3) 提交推送 + 用户推 v1.0.0 触发 + 读日志迭代到三平台绿
4) README 加 CI/下载说明 + tag iter-13
