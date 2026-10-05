<div align="center">

# LSPatch

**Run Xposed modules without root — by patching the app instead of the system**

[![Build](https://img.shields.io/github/actions/workflow/status/JingMatrix/LSPatch/main.yml?branch=master&logo=github&label=Build&event=push)](https://github.com/JingMatrix/LSPatch/actions/workflows/main.yml?query=event%3Apush+is%3Acompleted+branch%3Amaster)
[![Crowdin](https://img.shields.io/badge/Localization-Crowdin-blueviolet?logo=Crowdin)](https://crowdin.com/project/lspatch_jingmatrix)
[![Download](https://img.shields.io/github/v/release/JingMatrix/LSPatch?color=orange&logoColor=orange&label=Download&logo=DocuSign)](https://github.com/JingMatrix/LSPatch/releases/latest)
[![Total](https://shields.io/github/downloads/JingMatrix/LSPatch/total?logo=Bookmeter&label=Counts&logoColor=yellow&color=yellow)](https://github.com/JingMatrix/LSPatch/releases)

</div>

---

### Introduction

LSPatch is the rootless companion to [**Vector**](https://github.com/JingMatrix/Vector), the Xposed
framework this project is built on. Where Vector hooks the whole system as a Zygisk module and needs
Magisk or KernelSU, LSPatch needs neither: it rewrites a single target app's APK to embed the loader
and framework runtime, so that one app loads your Xposed modules on its own.

The two share a codebase — the same [LSPlant](https://github.com/JingMatrix/LSPlant)-based hook engine
and the same manager UI — and run the same modules. Read Vector's documentation for anything about the
framework itself; this README only covers what is specific to the rootless path.

> [!NOTE]
> LSPatch changes one app at a time and leaves the rest of the system untouched. It cannot hook
> system processes or apps you have not patched — that is what the rootful [Vector](https://github.com/JingMatrix/Vector)
> is for.

---

### Modules

Both generations of Xposed module run through the same engine, so the [module
repository](https://github.com/Xposed-Modules-Repo) and the API references listed under Vector's
[Developer Resources](https://github.com/JingMatrix/Vector#developer-resources) apply unchanged:

- **Modern** — libxposed modules receive a real `IXposedService`.
- **Legacy** — classic `de.robv.android.xposed` modules keep working as-is.

---

### Requirements

- **Android 9 (API 28) or newer.** The upper bound follows Vector — see its
  [compatibility notes](https://github.com/JingMatrix/Vector#compatibility).
- **[Shizuku](https://shizuku.rikka.app/) (optional).** With it, the manager installs patched apps
  silently; without it, grant "install unknown apps" once and install by hand.

---

### Downloads

| Channel | Source |
| :--- | :--- |
| **Stable Releases** | [GitHub Releases](https://github.com/JingMatrix/LSPatch/releases/latest) |
| **Canary (CI) Builds** | [GitHub Actions](https://github.com/JingMatrix/LSPatch/actions/workflows/main.yml?query=branch%3Amaster) |

Each release ships the manager (`manager.apk`) and the command-line patcher (`lspatch.jar`), plus a
`-debug` build of each. When reporting a bug, please reproduce it on the debug build first.

> [!CAUTION]
> GitHub requires you to be **logged in** to download CI artifacts. The link above is filtered to the
> `master` branch; builds from pull requests are unverified.

---

### Usage

**Manager app** — install `manager.apk`, pick a target, patch it, and install the result. From there
you manage each patched app's modules in place, install modules from the store, and read a live
framework log. Two modes:

- *Manager mode* keeps the patched app bound to the manager, so module changes apply without
  re-patching.
- *Integrated mode* bakes the modules into the APK, producing a self-contained app that needs no
  manager afterwards.

**Command line** — download `lspatch.jar` and run `java -jar lspatch.jar` to patch an APK from a shell.

---

### Contributing

- **Translations** are managed on [Crowdin](https://crowdin.com/project/lspatch_jingmatrix).
- **Questions** go to [Discussions](https://github.com/JingMatrix/LSPatch/discussions); bug reports to
  [Issues](https://github.com/JingMatrix/LSPatch/issues).
- **Code style** is enforced by [Spotless](https://github.com/diffplug/spotless) — run
  `./gradlew spotlessApply` before submitting. It formats only the files you changed (ktfmt for
  Kotlin, palantir-java-format for Java), so you never reflow untouched code.

---

### Credits

- [Vector](https://github.com/JingMatrix/Vector) — the Xposed framework and shared manager UI LSPatch
  is built on (and its own [credits](https://github.com/JingMatrix/Vector#credits))
- [Xpatch](https://github.com/WindySha/Xpatch) — the project LSPatch originally forked from
- [apkzlib](https://android.googlesource.com/platform/tools/apkzlib) — APK repacking
