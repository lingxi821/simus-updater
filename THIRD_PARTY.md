# 第三方组件

本仓库自身以 **GPL-3.0** 发布（见 `LICENSE`），因为 `updater/third_party/lspatch/` 内嵌了
GPL-3.0 的 LSPatch 源码，编译产物与运行期资产也派生自它。

| 组件 | 用途 | 位置 | 许可 |
|------|------|------|------|
| [LSPatch](https://github.com/JingMatrix/LSPatch)（上游 `0dc50f4`，Release v1.2） | 端上给 APK 打补丁（集成模式）、loader | `updater/third_party/lspatch/`、`updater/assets/lspatch/` | GPL-3.0 |
| [ManifestEditor](https://github.com/WindySha/ManifestEditor)（上游 `ee68565`，含 pxb android-axml） | 二进制 AndroidManifest 的读取/改写 | `updater/third_party/manifesteditor/` | Apache-2.0 |
| [Shizuku](https://github.com/RikkaApps/Shizuku) | 以 shell 权限执行安装会话（可选） | `updater/libs/shizuku-*.jar` | Apache-2.0 |
| [apksig](https://android.googlesource.com/platform/tools/apksig) | APK 签名（v1/v2） | `updater/libs/apksig-8.0.2.jar` | Apache-2.0 |
| [R8 / D8](https://r8.googlesource.com/r8) | dex 生成 | 未入库，从 Android SDK 复制 | BSD-3-Clause |
| [Guava](https://github.com/google/guava) / [failureaccess](https://github.com/google/guava) | 依赖 | `updater/libs/` | Apache-2.0 |
| [Gson](https://github.com/google/gson) | JSON | `updater/libs/gson-*.jar` | Apache-2.0 |
| [Apache Commons IO](https://commons.apache.org/proper/commons-io/) | IO 工具 | `updater/libs/commons-io-*.jar` | Apache-2.0 |
| [AutoValue](https://github.com/google/auto) | 注解处理器（LSPatch 用） | `updater/libs/auto-value-*.jar` | Apache-2.0 |
| [JSR-305](https://github.com/google/jsr305) | 注解 | `updater/libs/jsr305-*.jar` | BSD-3-Clause |

`updater/assets/keystore.bks` 是 LSPatch 自带的**公开测试密钥库**（口令 `123456`，别名 `key0`），
仅用于给**目标 App** 签名，以便补丁版之间能互相覆盖安装。它不用于本项目自身 APK 的签名——
后者请用自己生成的 keystore（见 README 的构建步骤）。

Xposed API 桩（`module/stub/`）只用于编译期，**不会**被打进模块 APK
（LSPatch 的 legacy 桥会拒绝加载包含 Xposed API 类的模块）。
