//
// Created by VIP on 2021/4/25.
//

#include "bypass_sig.h"

#include <dlfcn.h>
#include <link.h>
#include <sys/syscall.h>

#include <cstring>
#include <mutex>
#include <set>
#include <string>
#include <string_view>

#include "common/logging.h"
#include "core/native_api.h"
#include "elf/elf_image.h"
#include "jni/jni_bridge.h"
#include "patch_loader.h"
#include "utils/hook_helper.hpp"
#include "utils/jni_helper.hpp"

using lsplant::operator""_sym;
using namespace vector::native;
using namespace vector::native::jni;

namespace lspd {

std::string apkPath;
std::string redirectPath;

inline static constexpr auto kLibCName = "libc.so";

std::unique_ptr<const ElfImage> &GetC(bool release = false) {
    static std::unique_ptr<const ElfImage> kImg = nullptr;
    if (release) {
        kImg.reset();
    } else if (!kImg) {
        kImg = std::make_unique<ElfImage>(kLibCName);
    }
    return kImg;
}

// Level 2: redirect the app reading its own installed apk to the stored original, so a signature
// check recovers the original signer. Covers every libc file entry point, since open/openat/creat/
// fopen/... all funnel to __openat, which we hook by its prologue.
inline static auto __openat_ =
    "__openat"_sym.hook->*[]<lsplant::Backup auto backup>(int fd, const char *pathname, int flag,
                                                          int mode) static -> int {
    if (pathname == nullptr) return backup(fd, pathname, flag, mode);
    // The match is byte-exact on the installed apk path (getPackageResourcePath()). Known limits,
    // documented rather than fixed here:
    //  - A different spelling of the same file is missed -- a /proc/self/fd/N reopen, or a symlinked
    //    / canonicalised path. Closing it would mean resolving realpath or (dev,ino) on every open in
    //    the process (extra syscalls in a hot path), so it is deliberately not done.
    //  - Split apks (split_config.*.apk) are not redirected: LSPatch stores one original, so there is
    //    no per-split original to point at without a storage-format change.
    //  - A read through an fd or mapping that already existed BEFORE this hook was armed (the linker
    //    maps base.apk at load; a check may pread/mmap that pre-existing fd) never calls openat again,
    //    so an open-time hook cannot catch it at all.
    if (pathname == apkPath) {
        LOGD("Redirect openat from {} to {}", pathname, redirectPath);
        return backup(fd, redirectPath.c_str(), flag, mode);
    }
    return backup(fd, pathname, flag, mode);
};

bool HookOpenat(const lsplant::HookHandler &handler) { return handler(__openat_); }

// ---- Level 3: raw-`svc` apk-read redirect --------------------------------------------------------
//
// A packer that reads its own apk with an inline `svc` never touches libc, so level 2's __openat
// hook does not see it. Here every `svc` in the app's OWN native libraries is instrumented; the
// handler rewrites the path argument of a file syscall that targets base.apk to the stored original,
// exactly as __openat does for the libc path. On arm64 there is no `open`/`stat`/`access` syscall --
// only the `*at` forms, all of which carry the path in x1 -- so one register covers every case.
//
// Scope and caveats: only libraries mapped under the app's install dir are scanned (never libc, ART,
// the linker, or our own liblspatch/libdobby -- instrumenting libc's `svc` would route every syscall
// in the process through the handler and recurse). Instrumenting a `svc` rewrites 4 bytes of the
// library's text, so a packer that checksums its own code will notice; this is the inherent price of
// instruction rewriting and why it is an opt-in level, not the default.
//
// arm64 only: the `svc` encoding, the syscall-number/argument register mapping (x8/x1), and the
// DobbyRegisterContext layout are architecture-specific. The 32-bit build keeps level 3 a no-op
// rather than carry a second, near-dead implementation.
#if defined(__aarch64__)

namespace {

std::string g_appDirPrefix;             // the /data/app install dir, e.g. "/data/app/~~hash/pkg-hash"
std::string g_originPrefix;             // the origin-apk cache dir the app actually runs its code from
std::mutex g_svcMutex;                  // guards g_instrumented and one-time dlopen hooking
std::set<uintptr_t> g_instrumented;     // svc addresses already handed to Dobby, to dedup rescans

constexpr uint32_t kSvc0 = 0xd4000001;  // `svc #0` on arm64

// The NDK sysroot may predate openat2; its syscall number is fixed at 437 on arm64 (asm-generic),
// so pin it here rather than let the isPathSyscall case drop out when the header lacks the macro.
#ifndef __NR_openat2
#define __NR_openat2 437
#endif

// Just the open: the fd openat returns is what every later read/mmap of the signing block flows
// through, so redirecting the open covers the whole read. On arm64 openat (and its rare openat2
// sibling) carry the pathname in x1 (dirfd in x0). Nothing else is redirected -- a stat/access of the
// apk is left to report the real file.
bool isPathSyscall(long nr) {
    switch (nr) {
        case __NR_openat:
#ifdef __NR_openat2
        case __NR_openat2:
#endif
            return true;
        default:
            return false;
    }
}

// Runs just before the instrumented `svc` executes; if this is a file syscall opening our apk, point
// it at the original instead. Makes no syscalls itself (only a bounded string compare), so it cannot
// recurse through its own instrumentation. redirectPath is set once before any instrumentation and
// never reassigned, so its c_str() stays valid for the process lifetime.
void svcHandler(void *, DobbyRegisterContext *ctx) {
    long nr = static_cast<long>(ctx->general.regs.x8);
    if (!isPathSyscall(nr)) return;
    auto path = reinterpret_cast<const char *>(ctx->general.regs.x1);
    if (path == nullptr || apkPath.empty()) return;
    // Exact match (length + terminating NUL), so a longer path that merely starts with the apk path
    // is left alone. Same byte-exact strategy -- and the same spelling / split-apk gaps documented on
    // the L2 __openat handler above -- so a raw-svc openat of a differently-spelled path to the apk is
    // not caught. Kept exact on purpose: the handler must make no syscalls (a realpath/stat here would
    // re-enter through its own instrumentation).
    if (std::strncmp(path, apkPath.c_str(), apkPath.size() + 1) != 0) return;
    ctx->general.regs.x1 = reinterpret_cast<uint64_t>(redirectPath.c_str());
    LOGD("Redirect svc {} apk read to {}", nr, redirectPath);
}

// Instrument every `svc #0` (0xd4000001) in [base, base+len). This scans the whole executable
// segment, not just true instruction ranges, so a literal-pool DATA word equal to 0xd4000001 is also
// trampolined. If such a word is only ever executed it never fires (harmless); but if code loads it as
// a constant via a PC-relative ldr, that read now returns Dobby's patched branch bytes instead of the
// constant -- a real, if low-probability, corruption risk. A precise fix needs instruction-range
// restriction (section headers) or a reachability check; documented and accepted for this opt-in level.
int instrumentRange(uintptr_t base, size_t len) {
    auto *words = reinterpret_cast<const uint32_t *>(base);
    size_t count = len / sizeof(uint32_t);
    int done = 0;
    for (size_t i = 0; i < count; ++i) {
        if (words[i] != kSvc0) continue;
        auto addr = base + i * sizeof(uint32_t);
        if (!g_instrumented.insert(addr).second) continue;  // already done in an earlier scan
        if (DobbyInstrument(reinterpret_cast<void *>(addr), &svcHandler) != 0) {
            LOGW("DobbyInstrument failed at {:#x}", addr);
            g_instrumented.erase(addr);
        } else {
            ++done;
        }
    }
    return done;
}

// dl_iterate_phdr visitor: for an app-owned library, scan the executable part of each PT_LOAD.
int phdrCallback(struct dl_phdr_info *info, size_t, void *) {
    const char *name = info->dlpi_name;
    if (name == nullptr || name[0] == '\0') return 0;
    std::string_view n{name};
    // App code lives in two places under LSPatch: the /data/app install dir, and -- because the app
    // is run from the stored original -- the origin-apk cache dir (…/cache/lspatch/origin/x.apk!/lib).
    // Match the origin by the stable "/cache/lspatch/origin/" substring rather than a full prefix,
    // since dl_iterate_phdr canonicalises /data/user/0 to /data/data. A library outside both is a
    // system lib (libc, ART, the linker) and left alone. Our own injected framework libs (liblspatch,
    // libdobby) sit under …/assets/lspatch/so/ and must be skipped, or the scan would instrument the
    // very syscalls the handler runs on.
    bool appOwned = (!g_appDirPrefix.empty() && n.compare(0, g_appDirPrefix.size(), g_appDirPrefix) == 0) ||
                    n.find("/cache/lspatch/origin/") != std::string_view::npos;
    if (!appOwned) return 0;
    if (n.find("/assets/lspatch/so/") != std::string_view::npos) return 0;
    // Scope limits, documented: only linker-mapped objects under the install dir or the origin cache
    // are scanned. A packer library extracted to and dlopen'd from the app DATA dir
    // (/data/data/<pkg>/...) is missed, as is anonymous / JIT-generated or post-scan `svc` code that
    // dl_iterate_phdr never reports. Covering those would need the data dir passed down from Java plus
    // a /proc/self/maps walk (and a rescan on mprotect(PROT_EXEC)); deferred for its perf/scope cost.
    int done = 0;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const auto &ph = info->dlpi_phdr[i];
        if (ph.p_type != PT_LOAD || !(ph.p_flags & PF_X) || ph.p_filesz == 0) continue;
        done += instrumentRange(info->dlpi_addr + ph.p_vaddr, ph.p_filesz);
    }
    if (done > 0) LOGD("svc redirect: instrumented {} site(s) in {}", done, name);
    return 0;
}

void scanAppLibs() {
    std::lock_guard<std::mutex> lock(g_svcMutex);
    if (g_appDirPrefix.empty()) return;
    dl_iterate_phdr(&phdrCallback, nullptr);
}

// The packer library is dlopen'd long after level 3 is armed (it is decrypted at app start), so a
// one-shot scan would miss it. Re-scan after every load.
//
// We hook the linker's INTERNAL loader entry points -- __loader_dlopen and
// __loader_android_dlopen_ext -- not the public libdl wrappers. bionic's public dlopen /
// android_dlopen_ext are thin thunks that capture their caller with __builtin_return_address(0) and
// pass it down so the linker can derive the caller's namespace. Hooking the public wrapper makes the
// relocated original observe this handler's return address instead of the real caller's, so loads
// resolve in the default namespace and any namespace-scoped load -- one whose target is visible only
// in the caller's own namespace -- fails. The internal functions take caller_addr as an explicit
// argument, so forwarding it unchanged leaves namespace derivation intact. A different function from
// the framework's internal do_dlopen hook, so the two still do not collide.
void *(*g_orig_loader_android_dlopen_ext)(const char *, int, const void *, const void *) = nullptr;
void *my_loader_android_dlopen_ext(const char *filename, int flags, const void *extinfo,
                                   const void *caller_addr) {
    void *h = g_orig_loader_android_dlopen_ext(filename, flags, extinfo, caller_addr);
    if (h != nullptr) scanAppLibs();
    return h;
}

void *(*g_orig_loader_dlopen)(const char *, int, const void *) = nullptr;
void *my_loader_dlopen(const char *filename, int flags, const void *caller_addr) {
    void *h = g_orig_loader_dlopen(filename, flags, caller_addr);
    if (h != nullptr) scanAppLibs();
    return h;
}

void hookDlopen() {
    // __loader_* are exported by the linker, not by libdl, so they are resolved from the linker image
    // rather than through dlsym. A resolution miss disables only the rescan for that entry (level 3
    // loses some packer coverage), never the namespace-safe load itself.
    ElfImage linker("linker64");
    if (auto ext = linker.getSymbAddress<void *>("__loader_android_dlopen_ext")) {
        DobbyHook(ext, reinterpret_cast<dobby_dummy_func_t>(my_loader_android_dlopen_ext),
                  reinterpret_cast<dobby_dummy_func_t *>(&g_orig_loader_android_dlopen_ext));
    } else {
        LOGW("could not resolve __loader_android_dlopen_ext; dlopen rescan skipped for it");
    }
    if (auto plain = linker.getSymbAddress<void *>("__loader_dlopen")) {
        DobbyHook(plain, reinterpret_cast<dobby_dummy_func_t>(my_loader_dlopen),
                  reinterpret_cast<dobby_dummy_func_t *>(&g_orig_loader_dlopen));
    } else {
        LOGW("could not resolve __loader_dlopen; dlopen rescan skipped for it");
    }
}

}  // namespace

#endif  // defined(__aarch64__)

VECTOR_DEF_NATIVE_METHOD(void, SigBypass, enableSvcRedirect) {
#if defined(__aarch64__)
    if (apkPath.empty()) {
        LOGE("enableSvcRedirect called before enableOpenatHook");
        return;
    }
    {
        std::lock_guard<std::mutex> lock(g_svcMutex);
        auto slash = apkPath.find_last_of('/');
        g_appDirPrefix = slash == std::string::npos ? apkPath : apkPath.substr(0, slash);
        auto oslash = redirectPath.find_last_of('/');
        g_originPrefix = oslash == std::string::npos ? redirectPath : redirectPath.substr(0, oslash);
        LOGD("svc redirect scope {} | {}", g_appDirPrefix.c_str(), g_originPrefix.c_str());
    }
    // Patch each svc site with a single 4-byte `b` (to a near forwarding stub when the dispatch
    // bridge is out of B range) instead of Dobby's default 12-byte adrp/add/br. A svc is frequently a
    // tiny `svc; ret` wrapper; writing 12 bytes over its 4-byte first instruction overruns it and
    // clobbers the next function's entry, and a later direct `BL` into that entry faults
    // SIGILL/ILL_ILLOPC on the corrupted bytes. Near-branch trampolines keep every origin patch 4
    // bytes wide, so nothing past the instrumented instruction is touched. Enabled once, before any
    // DobbyInstrument/DobbyHook below.
    static std::once_flag nearBranchOnce;
    std::call_once(nearBranchOnce, dobby_enable_near_branch_trampoline);
    hookDlopen();   // catch the packer library, loaded after this point
    scanAppLibs();  // and anything already resident
    LOGD("enableSvcRedirect armed; {} svc site(s) instrumented so far", g_instrumented.size());
#else
    LOGW("enableSvcRedirect: raw-svc apk-read redirect is implemented on arm64 only");
#endif
}

VECTOR_DEF_NATIVE_METHOD(void, SigBypass, enableOpenatHook, jstring origApkPath,
                         jstring cacheApkPath) {
    // Populate the globals before the hook goes live: the hook reads apkPath/redirectPath, and they
    // are written once here and never reassigned, so it only ever observes populated, immutable values
    // -- closing both the empty-match window and the write/read data race.
    lsplant::JUTFString str1(env, origApkPath);
    lsplant::JUTFString str2(env, cacheApkPath);
    apkPath = str1.get();
    redirectPath = str2.get();
    LOGD("apkPath {}", apkPath.c_str());
    LOGD("redirectPath {}", redirectPath.c_str());
    auto r = HookOpenat(lsplant::InitInfo{
        .inline_hooker =
            [](auto t, auto r) {
                void *bk = nullptr;
                return HookInline(t, r, &bk) == 0 ? bk : nullptr;
            },
        .art_symbol_resolver = [](auto symbol) { return GetC()->getSymbAddress(symbol); },
    });
    if (!r) {
        LOGE("Hook __openat fail");
        return;
    }
    GetC(true);  // stays after HookOpenat: the art_symbol_resolver lambda above uses GetC()
}

static JNINativeMethod gMethods[] = {
    VECTOR_NATIVE_METHOD(SigBypass, enableOpenatHook, "(Ljava/lang/String;Ljava/lang/String;)V"),
    VECTOR_NATIVE_METHOD(SigBypass, enableSvcRedirect, "()V")};

void RegisterBypass(JNIEnv *env) { REGISTER_VECTOR_NATIVE_METHODS(SigBypass); }

}  // namespace lspd
