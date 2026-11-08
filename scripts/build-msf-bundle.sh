#!/usr/bin/env bash
#
# Build the Metasploit bundle: one self-contained aarch64 Alpine (musl) rootfs
# carrying Ruby, the Metasploit Framework source and its vendored gems.
#
# Why a rootfs and not a cross-compiled Ruby:
#
#   Metasploit depends on ~10 gems with C extensions (nokogiri, pg, sqlite3,
#   bcrypt, msgpack, ffi, pcaprub, eventmachine, …). Each must be built for the
#   ABI it will run on. A *bionic* build would need a compiler targeting
#   aarch64-linux-android, i.e. the NDK, and the device has none. A musl build
#   needs no NDK at all: the Android kernel runs an arm64 musl userland
#   unchanged, so the whole thing ships as a chroot.
#
# The archive is rooted at the chroot root (`./bin/busybox`, `./usr/bin/ruby`,
# `./opt/metasploit-framework/…`, `./msfconsole`). Both install paths place it
# under `files/msf/`:
#
#   * BundleSpec.msf()   maps every entry to `msf/<name>` while staging;
#   * CoreManager.installMsf() extracts straight into `files/msf/`.
#
# It is download-only — never bundled in the APK.
#
# Usage:
#   scripts/build-msf-bundle.sh [--msf-version 6.5.9] [--alpine-version 3.20.10]
#                               [--out dist] [--work build/msf-rootfs]
#                               [--signing-key bundle-key.pem]
#
# Works without root: on x86_64 it runs the rootfs under proot + qemu-aarch64
# (both fetched into the work dir on first run); on aarch64 it runs under proot
# alone. CI uses a native arm64 runner, so the qemu hop is skipped there.

set -euo pipefail

MSF_VERSION=6.5.9
ALPINE_VERSION=3.20.10
OUT_DIR=dist
WORK_DIR=build/msf-rootfs
SIGNING_KEY=
PROOT_VERSION=5.5.0
PROOT_SHA256=db185300a9daabbce86aca7632d8c7fdc4249f6f153171954013b70c5f491ec0
QEMU_VERSION=7.2.0

log() { printf '\033[1;32m[msf-bundle]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[msf-bundle]\033[0m %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --msf-version) MSF_VERSION=$2; shift 2 ;;
        --alpine-version) ALPINE_VERSION=$2; shift 2 ;;
        --out) OUT_DIR=$2; shift 2 ;;
        --work) WORK_DIR=$2; shift 2 ;;
        --signing-key) SIGNING_KEY=$2; shift 2 ;;
        -h|--help) sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "unknown argument: $1" ;;
    esac
done

ROOT="$WORK_DIR/rootfs"
CACHE="$WORK_DIR/cache"
# Stage markers live beside the rootfs, never inside it: `pack()` tars the whole
# rootfs, so a marker under $ROOT would ship as cruft in the bundle.
STAGE_DIR="$WORK_DIR/.stages"
TARBALL="msf-bundle.tar.gz"
MANIFEST="msf-bundle.json"
HOST_ARCH=$(uname -m)

# ---------------------------------------------------------------------------
# Rootfs runner: proot everywhere, plus qemu when the host is not aarch64.
# ---------------------------------------------------------------------------

fetch() { # url dest
    [ -s "$2" ] && return 0
    log "fetch $(basename "$2")"
    # Abort a stalled transfer instead of hanging forever; retry on failure.
    curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 \
        --speed-limit 1024 --speed-time 60 \
        -o "$2.part" "$1"
    mv "$2.part" "$2"
}

prepare_runner() {
    mkdir -p "$CACHE"
    QEMU=
    if [ "$HOST_ARCH" != "aarch64" ] && [ "$HOST_ARCH" != "arm64" ]; then
        QEMU="$CACHE/qemu-aarch64-static"
        if [ ! -x "$QEMU" ]; then
            fetch "https://github.com/multiarch/qemu-user-static/releases/download/v${QEMU_VERSION}-1/qemu-aarch64-static" "$QEMU"
            chmod +x "$QEMU"
        fi
    fi
    # Root on a matching architecture can use the kernel's own chroot: no
    # ptrace, no emulation — the fast path CI takes on a native arm64 runner.
    # proot is for everyone else (no sudo locally, or an x86_64 host).
    USE_CHROOT=0
    if [ "$(id -u)" = "0" ] && [ -z "$QEMU" ]; then USE_CHROOT=1; fi
    if [ "$USE_CHROOT" = "1" ]; then
        log "runner: chroot (native $HOST_ARCH, uid 0)"
        return
    fi
    PROOT="$CACHE/proot"
    if [ ! -x "$PROOT" ]; then
        fetch "https://github.com/proot-me/proot/releases/download/v${PROOT_VERSION}/proot" "$PROOT"
        # Pinned digest (proot's own SHA256SUMS for v5.5.0) so the tool that runs
        # the rootfs is a known binary.
        echo "$PROOT_SHA256  $PROOT" | sha256sum -c --status \
            || die "proot does not match its pinned sha256"
        chmod +x "$PROOT"
    fi
    log "runner: proot${QEMU:+ + qemu-aarch64} (host $HOST_ARCH)"
}

mount_kernel_fs() {
    [ "$USE_CHROOT" = "1" ] || return 0
    mkdir -p "$ROOT/proc" "$ROOT/dev" "$ROOT/sys"
    mount --bind /proc "$ROOT/proc"
    mount --bind /dev "$ROOT/dev"
    mount --bind /sys "$ROOT/sys"
    # proot binds the host's resolver; a real chroot needs its own copy.
    cp /etc/resolv.conf "$ROOT/etc/resolv.conf" 2>/dev/null || true
    trap unmount_kernel_fs EXIT
}

# Must run before pack(): a mounted /proc inside the rootfs would be archived.
unmount_kernel_fs() {
    [ "$USE_CHROOT" = "1" ] || return 0
    for m in sys dev proc; do umount "$ROOT/$m" 2>/dev/null || true; done
}

# run_in_rootfs <shell script>
run_in_rootfs() {
    # The host's TMPDIR must not leak in: it is a host path that does not exist
    # inside the rootfs, and `mktemp -d` then fails ("cannot create a temporary
    # directory in /home/...") — sqlite3's packaged-source build dies exactly
    # there, halfway through the gem install. The rootfs's own /tmp is 1777 and
    # lives on the same disk as the rootfs, so it is the right answer for both
    # runners.
    local script="TMPDIR=/tmp
TMP=/tmp
TEMP=/tmp
export TMPDIR TMP TEMP
cd /root || exit 1
$1"
    if [ "$USE_CHROOT" = "1" ]; then
        chroot "$ROOT" /bin/sh -c "$script"
        return
    fi
    local qemu_args=()
    [ -n "$QEMU" ] && qemu_args=(-q "$QEMU")
    "$PROOT" "${qemu_args[@]}" -r "$ROOT" -0 \
        -b /proc -b /dev -b /sys -b /etc/resolv.conf \
        -w /root \
        /bin/sh -c "$script"
}

# ---------------------------------------------------------------------------
# 1. Alpine base
# ---------------------------------------------------------------------------

bootstrap_alpine() {
    local name="alpine-minirootfs-${ALPINE_VERSION}-aarch64.tar.gz"
    local base="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION%.*}/releases/aarch64"
    fetch "$base/$name" "$CACHE/$name"
    fetch "$base/$name.sha256" "$CACHE/$name.sha256"

    log "verify $name"
    ( cd "$CACHE" && sha256sum -c "$name.sha256" --status ) \
        || die "$name does not match its published sha256"

    if [ -d "$ROOT" ]; then
        log "reusing existing rootfs at $ROOT (delete it to start over)"
        return
    fi
    log "extract $name"
    mkdir -p "$ROOT"
    tar -xzf "$CACHE/$name" -C "$ROOT"
}

# ---------------------------------------------------------------------------
# 2. Packages
# ---------------------------------------------------------------------------

# Runtime packages stay; everything in BUILD_PACKAGES is removed before packing.
# The *-dev packages are needed because musl has no precompiled platform gems,
# so every C extension is built from source against these headers.
RUNTIME_PACKAGES="ruby ruby-bundler ruby-ffi ruby-eventmachine ruby-nokogiri ruby-pg ruby-rake ruby-rexml libffi libpcap libstdc++ openssl readline libxml2 libxslt postgresql-client sqlite-libs zlib ca-certificates"
BUILD_PACKAGES="build-base ruby-dev libffi-dev libpcap-dev libxml2-dev libxslt-dev openssl-dev postgresql-dev readline-dev sqlite-dev zlib-dev"

configure_apk() {
    # Full mirror URLs, main + community: `apk` resolves a bare domain as a
    # relative directory and then finds no index.
    local repo_dir="$ROOT/etc/apk"
    mkdir -p "$repo_dir"
    cat > "$repo_dir/repositories" <<EOF
https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION%.*}/main
https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION%.*}/community
EOF
}

install_packages() {
    local marker="$STAGE_DIR/packages-installed"
    [ -f "$marker" ] && { log "packages already installed"; return; }
    log "apk add runtime + build packages"
    # shellcheck disable=SC2086
    run_in_rootfs "
        set -e
        apk update
        apk add --no-cache $RUNTIME_PACKAGES
        apk add --no-cache $BUILD_PACKAGES
    "
    touch "$marker"
}

# ---------------------------------------------------------------------------
# 3. Metasploit source + gems
# ---------------------------------------------------------------------------

MSF_DIR="$ROOT/opt/metasploit-framework"

fetch_msf() {
    local marker="$STAGE_DIR/msf-source"
    [ -f "$marker" ] && { log "msf source already staged"; return; }
    local name="metasploit-framework-${MSF_VERSION}.tar.gz"
    fetch "https://github.com/rapid7/metasploit-framework/archive/refs/tags/${MSF_VERSION}.tar.gz" "$CACHE/$name"
    log "extract msf $MSF_VERSION"
    mkdir -p "$ROOT/opt"
    # spec/, documentation/ and external/ are development-only and ~200 MB.
    tar -xzf "$CACHE/$name" -C "$ROOT/opt" \
        --exclude='*/spec' --exclude='*/documentation' --exclude='*/external'
    mv "$ROOT/opt/metasploit-framework-${MSF_VERSION}" "$MSF_DIR"
    touch "$marker"
}

install_gems() {
    local marker="$STAGE_DIR/bundle-installed"
    [ -f "$marker" ] && { log "gems already installed"; return; }
    log "bundle install (pinned Gemfile.lock, source gems)"
    # force_ruby_platform: musl has no precompiled platform gems, so bundler must
    # build every C extension locally.
    # build.nokogiri --use-system-libraries: link the Alpine libxml2/libxslt
    # instead of downloading Nokogiri's own vendored copies.
    run_in_rootfs "
        set -e
        export HOME=/root
        cd /opt/metasploit-framework
        bundle config set --local without 'development test coverage'
        bundle config set --local force_ruby_platform true
        bundle config set --local path vendor/bundle
        bundle config set --local build.nokogiri --use-system-libraries
        bundle install --jobs 2 --retry 3
    "
    touch "$marker"
}

# ---------------------------------------------------------------------------
# 4. Chroot shape + smoke test
# ---------------------------------------------------------------------------

shape_rootfs() {
    log "link msfconsole/msfrpcd at the chroot root"
    # config/boot.rb derives BUNDLE_GEMFILE from msfconsole's *realpath*, so the
    # link may live at / while the script lives under /opt.
    ln -sfn /opt/metasploit-framework/msfconsole "$ROOT/msfconsole"
    ln -sfn /opt/metasploit-framework/msfrpcd "$ROOT/msfrpcd"

    mkdir -p "$ROOT/root/.msf4" "$ROOT/root/.cache" "$ROOT/tmp"
    chmod 1777 "$ROOT/tmp"
    # The app writes the real resolver at run time (ChrootEngine.setupScriptFor);
    # a placeholder keeps apk/tools from complaining if it is ever missing.
    [ -f "$ROOT/etc/resolv.conf" ] || echo "nameserver 1.1.1.1" > "$ROOT/etc/resolv.conf"
}

smoke_test() {
    log "smoke test: msfconsole -q -x version"
    run_in_rootfs "
        set -e
        export HOME=/root
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        cd /root
        /msfconsole -q -x 'version; exit' 2>&1 | tail -5
    " | tee "$WORK_DIR/smoke.log"
    grep -q "Framework" "$WORK_DIR/smoke.log" \
        || die "msfconsole did not report a framework version — see $WORK_DIR/smoke.log"
}

# ---------------------------------------------------------------------------
# 5. Trim
# ---------------------------------------------------------------------------

trim() {
    log "remove build packages"
    # shellcheck disable=SC2086
    run_in_rootfs "apk del $BUILD_PACKAGES" || log "apk del reported leftovers (continuing)"

    log "remove proot guest artifacts"
    # proot plants these inside the guest rootfs (its own docs describe
    # `/host-rootfs in the guest environment`); none are part of Alpine, and the
    # 000-mode ones make a non-root `tar` fail because the owner has no read bit
    # either. A real chroot (CI, native arm64) never creates them.
    rm -rf "$ROOT/host-rootfs"
    [ -s "$ROOT/etc/ld.so.preload" ] || rm -f "$ROOT/etc/ld.so.preload"
    # Keep the chroot's own placeholder resolver, never the build host's.
    rm -f "$ROOT/etc/resolv.conf"
    echo "nameserver 1.1.1.1" > "$ROOT/etc/resolv.conf"

    log "strip docs, caches and host-only payloads"
    rm -rf "$ROOT/var/cache/apk"/* "$ROOT/root/.gem" "$ROOT/root/.bundle/cache" 2>/dev/null || true
    rm -rf "$MSF_DIR/docs" "$MSF_DIR/docker" "$MSF_DIR/kubernetes" "$MSF_DIR/test" "$MSF_DIR/.github" 2>/dev/null || true
    # data/jtr is the charset/wordlist set for the John-the-Ripper *binary* msf
    # does not ship for aarch64; nothing on the device can invoke it.
    rm -rf "$MSF_DIR/data/jtr" 2>/dev/null || true
    # Nokogiri's build leftovers are large and only needed to compile.
    rm -rf "$MSF_DIR/vendor/bundle/ruby"/*/gems/nokogiri-*/tmp 2>/dev/null || true
    rm -rf "$MSF_DIR/vendor/bundle/ruby"/*/cache 2>/dev/null || true
    find "$ROOT/usr/share/man" "$ROOT/usr/share/doc" -type f -delete 2>/dev/null || true
    find "$ROOT" -name '*.a' -path '*/usr/lib/*' -delete 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# 6. Pack + manifest
# ---------------------------------------------------------------------------

pack() {
    mkdir -p "$OUT_DIR"
    log "pack $OUT_DIR/$TARBALL"
    # `./`-rooted, exactly what both install paths expect.
    tar -czf "$OUT_DIR/$TARBALL" -C "$ROOT" .

    local file="$OUT_DIR/$TARBALL"
    local size sha unpacked built version
    size=$(stat -c%s "$file")
    sha=$(sha256sum "$file" | awk '{print $1}')
    unpacked=$(gzip -dc "$file" | wc -c)
    built=$(date +%s)
    version="${MSF_VERSION}+alpine${ALPINE_VERSION}"

    log "manifest $OUT_DIR/$MANIFEST"
    printf '{"format":1,"id":"msf","version":"%s","file":"%s","sha256":"%s","size":%s,"unpacked":%s,"minApp":1,"built":%s}' \
        "$version" "$TARBALL" "$sha" "$size" "$unpacked" "$built" > "$OUT_DIR/$MANIFEST"

    if [ -n "$SIGNING_KEY" ]; then
        [ -f "$SIGNING_KEY" ] || die "signing key not found: $SIGNING_KEY"
        log "sign manifest"
        openssl dgst -sha256 -sign "$SIGNING_KEY" -out "$OUT_DIR/$MANIFEST.sig" "$OUT_DIR/$MANIFEST"
    fi

    log "done: $version  $(numfmt --to=iec "$size") compressed / $(numfmt --to=iec "$unpacked") unpacked"
}

main() {
    command -v curl >/dev/null || die "curl is required"
    command -v tar >/dev/null || die "tar is required"
    mkdir -p "$WORK_DIR" "$CACHE" "$STAGE_DIR"
    prepare_runner
    bootstrap_alpine
    mount_kernel_fs
    configure_apk
    install_packages
    fetch_msf
    install_gems
    shape_rootfs
    smoke_test
    trim
    unmount_kernel_fs
    pack
}

main "$@"
