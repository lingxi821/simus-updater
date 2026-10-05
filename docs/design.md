# 设计说明

## 1. 目标

在**手机本地**把官方 TikTok（`com.zhiliaoapp.musically`）升级/重装成「内嵌 Xposed 模块」的
补丁版，实现美区 SIM 伪装，并且**不掉数据、不掉登录**。全程不需要电脑、不需要 root。

## 2. 总体流程

```
点「一键更新并安装」
   │
   ├─ ① 检查    APKMirror 查最新版本（与已装版本比较）
   ├─ ② 下载    .apkm 分卷包（BUNDLE 变体，含全部 config split）
   ├─ ③ 打包    解出 base + 各分卷
   │             └ base → LSPatch 集成模式打补丁（内嵌 simspoof 模块）
   │             └ 用**原版 manifest** 重建补丁包的 manifest
   │             └ base 与所有分卷用同一密钥重签
   └─ ④ 安装    Shizuku 的 installBundle 会话；没有 Shizuku 就用 PackageInstaller 会话
```

下载不通时，「本地安装包」按钮走同一套 ③④，只是安装包来自 `Download/` 里的 `.apkm`。

## 3. 关键实现与踩坑记录

### 3.1 必须走「多分卷会话」，不能合并单体

TikTok 是 AAB 拆包：base + 几十个 config split（ABI / 密度 / 语言 / 功能）。
早期尝试把 ABI 分卷的 `.so` 合并进 base 做成单体 APK，结果是能装但**启动即崩**
（`No package ID 6b found for resource ID 0x6b0b0013`，InflateException）：
资源 ID 与 split 家族不匹配。结论：所有分卷整体会话安装。

### 3.2 注入的原生库要「不压缩 + 16 KB 对齐」

`liblspatch.so` 以压缩方式放进 APK 会导致 `dlopen failed: ... liblspatch.so not found`。
写入 zip 时对 `.so` 用 `STORED` 且页对齐 16 KB（`origin.apk` 用 4 KB），并保持
`extractNativeLibs=true`。

### 3.3 manifest 必须用**原版**重建

部分 OEM 安装器（实测 vivo 的 `com.android.packageinstaller.PackageInterceptActivity`）
会对 LSPatch 重写出来的 manifest 一律判「解析软件包时出现问题」。
改为：读原始 base 的 `AndroidManifest.xml`，用 pxb 的 axml 读写器只施加这几处改动 ——

| 改动 | 原因 |
|------|------|
| `requiredSplitTypes` 置空 | AOSP 的 `ApkLiteParseUtils` 把空值当「没有这个属性」，否则单体包会被判 `INSTALL_FAILED_MISSING_SPLIT` |
| `minSdkVersion` → 28 | loader 是按 API 28 编译的 |
| `debuggable=false` | 与官方一致 |
| `appComponentFactory` → `org.lsposed.lspatch.metaloader.LSPAppComponentFactoryStub` | 进入 loader |
| 补 `<meta-data name="lspatch" value=base64(config.json)>` | 签名绕过与模块加载配置 |
| `extractNativeLibs=true` | 注入的 `.so` 需要在安装时解压 |

> 注意 pxb 的 visitor 是**逐层回调**的：每一层都必须把自己继续包下去，否则嵌套元素上的改动会静默失效。

### 3.4 签名一致性

base 与所有分卷必须同一密钥，且与已装版本一致才能覆盖安装保数据。
补丁版固定用 `assets/keystore.bks`（LSPatch 内置公开测试密钥库）。
如果设备上装的是官方版（Play 签名），只能先卸载官方版再装补丁版（会掉数据）——
App 里对此有检测与引导。

### 3.5 模块为什么这么写

- LSPatch 的 legacy 桥**拒绝加载**包含 Xposed API 类的模块（`The Xposed API classes are
  compiled into the module's APK`）→ `stub/` 只作为编译期 classpath，不打进 dex。
- `XposedHelpers.findAndHookMethod` 在 legacy 桥下会**静默失效** → 一律用
  `findMethodExact` + `XposedBridge.hookMethod`。
- 模块只 hook `TelephonyManager` 的 6 个方法（SIM/网络国家码、运营商码、运营商名），
  不改动 App 的适配逻辑。

### 3.6 中间产物指纹

「复用上次打好的分卷」这个优化必须绑定**补丁指纹**（补丁逻辑版本号 + 内嵌模块的
长度与 SHA-256）：否则改了打包规则或换了模块之后，会把旧中间产物当新的装上去，
表现为「代码明明改了却没生效」。

## 4. 目录职责

| 路径 | 说明 |
|------|------|
| `updater/src/.../MainActivity.java` | 单页 UI（卡片式）、主流程编排、Shizuku 状态检测 |
| `updater/src/.../ApkMirror.java` | APKMirror 查询与下载（Cookie/Referer 处理、版本比较） |
| `updater/src/.../Patcher.java` | 调用 LSPatch 引擎给 base 打补丁 |
| `updater/src/.../ApkMerger.java` | 合并/重写 manifest、zip 对齐写入 |
| `updater/src/.../ApkResigner.java` | 用 apksig 做 v1+v2 重签 |
| `updater/src/.../AppInstaller.java` / `IInstaller.java` | PackageInstaller 会话安装（含多 APK） |
| `module/src/.../SpoofModule.java` | Xposed 模块实现 |

## 5. 已知限制

- 只适配 `com.zhiliaoapp.musically` 的分卷结构，官方改结构可能需要跟进。
- 不提供任何 TikTok 安装包；需用户自备。
- OEM 系统级窗口/后台策略（例如某些平板的「横屏时以小窗启动」）不受本工具影响。

## 6. 许可

GPL-3.0（内嵌 LSPatch 源码）。第三方组件见 [../THIRD_PARTY.md](../THIRD_PARTY.md)。
