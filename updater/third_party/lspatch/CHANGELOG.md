LSPatch 1.2 adds an opt-in level 3 to the signature bypass, reaches a module's companion the moment its settings open, and fixes several crashes carried over from 1.1.

- 🔏 **Signature bypass, level 3.** Levels 1 and 2 miss an app that reads its own apk through an inline `svc` or parses it with `getPackageArchiveInfo`. Level 3 redirects both to the original signer. It rewrites four bytes of the app's own code, which a self-checksumming packer can notice, so it stays opt-in and arm64 only; level 2 remains the default.
- 🔌 **A companion is reached when its settings open.** A value changed in a module's settings app now reaches the running hook without force-stopping the target first — over Shizuku where it is granted, or through a service the companion binds at its own start where it is not.
- ⬆️ **Version history and canaries on the update page.** Pick any past or prerelease build from the title-bar switcher and follow its notes, channel and install.
- 🩹 **Crash fixes.** Choosing a language, running a local-mode app under a renamed manager, or re-patching a patched app in a release build no longer crashes.
