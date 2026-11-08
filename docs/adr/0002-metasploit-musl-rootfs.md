# ADR 0002 — Metasploit runs from a self-contained musl rootfs (no NDK)

- **Status**: accepted
- **Date**: 2026-11-08
- **Supersedes**: the "minimal glibc chroot + bionic Ruby" direction sketched in
  `docs/plan/2026-10-28-product-plan.md` (Phase 4 / `Capability.MSF`).

## Context

Metasploit Framework is Ruby plus ~10 C-extension gems (`nokogiri`, `pg`, `sqlite3`,
`bcrypt`, `msgpack`, `ffi`, `pcaprub`, `eventmachine`, …). Every one of those extensions
has to be compiled for the ABI it will actually load on. There are only two ways to do
that on Android:

1. **Build against bionic** (the Android C library). This requires a compiler that
   targets `aarch64-linux-android`, i.e. the **NDK**.
2. **Run a musl userland** and compile the gems for `aarch64-linux-musl`.

The bionic route is a dead end on a *user's* device and on a *developer's host without
the NDK*:

- The device has no compiler at all (`which cc gcc clang make ruby python3` returns
  nothing on the moto g60).
- A host that is not using the NDK emits either a musl or a glibc `.so`; neither loads
  into a bionic process — the dynamic loader rejects the ELF.
- Shipping a prebuilt bionic Ruby is not enough either: the gems still have to be built,
  and RubyGems' precompiled platform gems are glibc/musl only. The "just install
  `metasploit-framework` from rubygems" shortcut is *also* a dead end: the gem on
  rubygems is the abandoned **6.0.33** (2019) whose constraints (`actionpack ~> 5.2.2`,
  `concurrent-ruby = 1.0.5`) can never resolve on a modern Ruby — that is what produced
  the CPU-spinning hang the first attempt is remembered for.

## Decision

Ship Metasploit as **one self-contained aarch64 Alpine (musl) rootfs** and run it with
`chroot`. Nothing is compiled on the device, and nothing needs the NDK:

```
files/msf/                     <- the chroot root, extracted from msf-bundle.tar.gz
├── bin/busybox                <- /bin/sh etc.
├── usr/bin/ruby               <- Ruby 3.3.10 (Alpine 3.20)
├── msfconsole -> /opt/metasploit-framework/msfconsole
├── msfrpcd    -> /opt/metasploit-framework/msfrpcd
├── opt/metasploit-framework/  <- source (6.5.9) + vendor/bundle (205 gems) + .bundle/config
├── root/.msf4                 <- HOME; Metasploit writes its config/logs here
└── tmp
```

The Android kernel runs an arm64 musl userland unchanged, so the whole tree is just a
directory that `su -c 'chroot <root> /bin/sh -c …'` enters. That is the same mechanism the
Alpine engine already uses for its tools, not a new one.

### Why this is not "heavy"

It explicitly is **not** the StrykerOSS shape that was rejected: no QEMU VM, no Debian
rootfs, no SSH transport, no 1.2 GB guest that takes 150 s to boot. There is no
virtual machine, no second kernel, and no daemon inside the guest — `msfrpcd` is a Ruby
script started once, and the chroot is a plain directory. The bundle is download-only, so
the APK does not grow.

### Build

`scripts/build-msf-bundle.sh` produces `msf-bundle.tar.gz` + `msf-bundle.json`:

- Pins Metasploit **6.5.9** (the GitHub tag, whose committed `Gemfile.lock` has 248 gems,
  no `ruby` directive and no lockfile `RUBY VERSION`, so Alpine's Ruby 3.3.10 is accepted)
  and Alpine **3.20.10**.
- `bundle config set --local force_ruby_platform true` (musl has no precompiled platform
  gems, so every C extension is built) and `build.nokogiri --use-system-libraries`.
- Runs the rootfs with `proot` (+ static `qemu-aarch64` on a non-arm host) when it cannot
  use a real `chroot`; a root user on native arm64 uses the kernel's own `chroot`. CI
  therefore uses a native arm64 runner and skips the emulation hop entirely. **No NDK,
  no bionic, no cross-compiler.**
- Trims development packages, caches, docs and host-only payloads, then packs a
  `./`-rooted archive.

Result: **183 MB compressed / 475 MB unpacked**, 31 714 entries (26 797 files), 205 gems.

### Install paths

Both existing installers consume the same archive:

- `BundleSpec.msf()` + `BundleInstaller` → `SafeTarExtractor` (the Bundle Updates channel).
- `CoreManager.installMsf()` → `installTarballArtifact` → `TarExtract` (the Exploit Finder
  install card), with magic-byte compression sniffing (`1f 8b` gzip / `fd 37 7a 58 5a 00` xz).

`BundleSpec.msf()` maps every entry under `msf/`; the sentinels are
`msf/bin/busybox`, `msf/usr/bin/ruby`, `msf/msfconsole`.

### Launch

`MsfLaunch.rpcd` runs the daemon in the chroot's own shell — never `/usr/bin/env`, which a
minimal rootfs may not have — with the chroot's own `PATH` (Android's `/system/bin` does not
exist inside) and `HOME=/root` (Metasploit derives `~/.msf4` from it):

```
chroot <root> /bin/sh -c 'HOME=/root; PATH=…; exec /msfrpcd -p <port> -P msf -U msf -f -S -a 127.0.0.1 -n'
```

`MsfDaemon` health-checks it with `MsfRpcClient` (`auth.login` + `core.version`) using the
**same** credential constants (`MsfLaunch.RPC_USER` / `RPC_PASSWORD`).

## Consequences

- **No NDK dependency for Metasploit.** The app no longer needs the NDK at runtime at all
  (no `System.loadLibrary`, no `jniLibs/`, no `externalNativeBuild`). The NDK is still used
  to build the bundled C tools in `indra/jni` (`./build.sh cores`) and to link the Rust
  daemon; neither is part of the Metasploit path.
- **A real extractor bug was found by validating against the real archive.** `SafeTarExtractor`
  used `File.canonicalFile` to check that a symlink target stays inside the root. That follows
  the link, and an Alpine certificate directory is a two-link chain —
  `0a775a30.0 -> ca-cert-GTS_Root_R3.pem` and
  `ca-cert-GTS_Root_R3.pem -> /usr/share/ca-certificates/…`. Canonicalising the first follows
  the second to the *host's* `/usr/share` and looked like an escape, so the bundle could not be
  installed at all. The check is now **lexical** (`Path.normalize`, no link following); the write
  path still canonicalises, so a link can never be used to write outside the root.
  `SafeTarExtractorTest` pins the chain and keeps the `../../etc/passwd` rejection.
- **Bundle size is honest.** 475 MB unpacked is a Ruby + 205-gem framework; the APK does not
  carry it, and the trim step removed the parts msf cannot use on aarch64 (John-the-Ripper
  charsets, docker/kubernetes/test, build headers).
- **Monitor-mode caveat is unrelated** and unchanged: `iw`/`ip` are not in this rootfs.
