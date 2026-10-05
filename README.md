# SIM US Updater

给 TikTok（国际版，`com.zhiliaoapp.musically`）**一键打补丁并安装**的 Android 工具：
在手机上直接完成「下载官方安装包 → 端上重打包 → 内嵌 Xposed 模块 → 重签名 → 会话安装」，
用来实现 **美区 SIM 伪装**（把 SIM/网络国家码伪装成美国、运营商伪装成 T-Mobile），
并且**保留原有登录状态与数据**。

> 由 灵曦以茗 制作

---

## 它做什么

1. **一键更新并安装**：自动查询 APKMirror 上 TikTok 的最新版本 → 下载 `.apkm` 分卷包 →
   在设备上给 base 打补丁（内嵌 `simspoof` 模块）→ 重建 manifest → 全部重签名 → 会话安装。
2. **本地安装包兜底**：下载不通时，把 `.apkm` 放进 `Download/` 点第二个按钮即可，流程完全一样。
3. **保留数据**：补丁版始终用同一把密钥签名（LSPatch 内置密钥），所以覆盖安装不会掉数据、不掉登录。

模块本身只做一件事：把 `TelephonyManager` 的 SIM/网络国家码与运营商名伪装成美区。
**刻意不改动 TikTok 自己的任何适配逻辑**（屏幕方向、平板判定、布局策略全部保持官方原样）。

## 原理（为什么必须这么做）

| 环节 | 做法 / 踩过的坑 |
|------|----------------|
| 打补丁 | 用 [LSPatch](https://github.com/LSPosed/LSPatch) 的**集成模式**：loader 直接塞进目标 APK，模块内嵌在 `assets/lspatch/modules/` |
| 分卷包 | TikTok 是 AAB 拆出来的多分卷（base + 几十个 config split）。**不能合并成单体 APK**：合并后资源 ID 与 `liblspatch.so` 加载都会出问题，必须走「多 APK 会话」整体安装 |
| 原生库 | 注入的 `.so` 必须**不压缩存放**且按 16 KB 页对齐，否则 `dlopen failed: liblspatch.so not found` |
| manifest | 必须基于**原版 manifest** 重建，不能直接用 LSPatch 写出来的那份（部分 OEM 安装器会判「解析软件包时出现问题」）。只动这几处：`requiredSplitTypes` 清空、`minSdkVersion` 提到 28、`debuggable=false`、`appComponentFactory` 指向 loader Stub、补 `<meta-data name="lspatch">`、`extractNativeLibs=true` |
| 签名 | base 与所有分卷必须**同一密钥**重签，且与已装版本一致，否则只能卸载重装（掉数据） |
| 安装 | 有 [Shizuku](https://github.com/RikkaApps/Shizuku) 时用它的 `installBundle` 会话；没有就走 App 自己的 `PackageInstaller` 会话（会弹系统确认框） |
| 模块 | LSPatch 的 legacy 桥**不接受**把 Xposed API 类打进模块 APK，且不支持 `findAndHookMethod`（会静默失效）——所以模块用 `findMethodExact + XposedBridge.hookMethod`，并且只把桩类当编译期依赖 |

## 仓库结构

```
updater/                      端上工具 App（纯 javac + d8 构建，无需 Gradle）
  src/                        Java 源码（UI、下载、打包、重签、安装、Shizuku 集成）
  res/ assets/               主题/图标；assets 内含内置密钥库与 loader 产物
  libs/                       构建期依赖 jar/aar
  third_party/lspatch/        LSPatch 源码（GPL-3.0，构建打包引擎用）
  third_party/manifesteditor/ pxb android-axml 源码（Apache-2.0，改 manifest 用）
  build-engine.sh             编译 LSPatch 打包引擎 → build/dex/classes.dex
  build-app.sh                编译并签名 App → simusupdater.apk
module/                       Xposed 模块（美区 SIM 伪装）
  src/ stub/ assets/          SpoofModule + 编译期 Xposed API 桩
  build-module.sh             编译模块 → simspoof-US模块.apk（并自动放进 updater/assets）
docs/design.md                架构与实现细节
```

## 构建

需要 **JDK 21**、**Android SDK**（`build-tools;34.0.0` + `platforms;android-34`）、`python3`。
整个工程**不使用 Gradle**，全部走 `aapt2 / javac / d8 / zipalign / apksigner`。

```bash
# 0) 拉取体积较大的构建依赖（r8/D8，未入库）
./scripts/fetch-deps.sh

# 1) 生成自己的签名密钥（千万不要把私钥/口令提交到仓库）
keytool -genkeypair -keystore updater/app.p12 -storetype PKCS12 -alias simus \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KS_PASS" -keypass "$KS_PASS" -dname "CN=simus-updater"

# 2) 构建打包引擎 → 3) 构建 App → 4) 构建模块
export ANDROID_SDK=$HOME/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export KS_PASS='你的口令'
./updater/build-engine.sh
./updater/build-app.sh
./module/build-module.sh     # 产物会自动拷到 updater/assets/simspoof.apk
```

产物：`updater/simusupdater.apk`、`module/simspoof-US模块.apk`。

## 使用

1. 安装 `simusupdater.apk`，授予「安装未知应用」和存储权限（Android 13+ 是「所有文件访问」）。
2. 首次使用如果装的是**官方版 TikTok**，需要先按提示卸载官方版（签名不同，无法覆盖）。
3. 点「一键更新并安装」；下载不动时把 `.apkm` 放进 `Download/` 再点「本地安装包」。
4. 安装过程中系统安装器会弹确认框，按提示点「继续安装」即可。

Shizuku 可选：装了且已授权时，安装走 Shizuku 会话，确认更少、更顺。

## 已知限制

- 需要自备 TikTok 安装包（工具只做「下载 + 打补丁 + 安装」，不分发任何 TikTok 内容）。
- 仅适配 TikTok 国际版包名 `com.zhiliaoapp.musically`（分卷结构变动可能导致需要更新适配）。
- 补丁版与官方版**签名不同**，两者不能互相覆盖安装。
- 个别 OEM 系统（如 vivo 平板）会对特定包名施加系统级窗口策略（例如「横屏时以小窗启动」），
  这是 ROM 行为，App 侧无法改变。

## 免责声明

本项目仅供**学习与技术研究**使用。TikTok 及其商标、客户端版权归 ByteDance 所有；
请自行获取安装包，不要分发修改后的客户端。修改、重签名客户端可能违反其服务条款，
使用风险由使用者自行承担。

## 致谢与许可

- 本项目以 **GPL-3.0** 发布（因为内嵌了 GPL-3.0 的 LSPatch 源码）。
- 第三方组件与许可见 [THIRD_PARTY.md](THIRD_PARTY.md)。

---

### English summary

A no-Gradle Android tool that patches the TikTok app (`com.zhiliaoapp.musically`) on-device:
downloads the official bundle from APKMirror, injects an embedded Xposed module (US SIM/network
spoofing) with LSPatch in integrated mode, rebuilds the manifest, re-signs every split with one
key, and installs them as a split-APK session (optionally through Shizuku) — keeping user data.
Licensed under GPL-3.0. For educational use only; no TikTok binaries are distributed.
