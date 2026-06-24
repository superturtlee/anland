#!/bin/bash
#
# build.sh — rebuild patched kwin and Xwayland .pkg.tar.zst packages and install them.
#
# Arch Linux counterpart of the Debian/Ubuntu build.sh. Uses asp export + makepkg
# instead of apt-get source + dpkg-buildpackage.
#
# Run this INSIDE an archlinux container. makepkg refuses to run as root, so this
# script creates a non-root builder user and re-execs as it.
#
# The two patches fix hardware acceleration / input on the kgsl(turnip) stack:
#   kwin.patch      -> src 'kwin'      (anland backend + --anland CLI option)
#   xwayland.patch  -> src 'xorg-xwayland'  (kgsl GBM fixes)
#
# The anland backend source is overlaid into the kwin source tree via an
# additional source tarball extracted in the prepare() function.
#
set -u

if [ "$(id -u)" -eq 0 ]; then
    if ! id builder >/dev/null 2>&1; then
        useradd -m -s /bin/bash builder
    fi
    echo "builder ALL=(ALL) NOPASSWD: ALL" > /etc/sudoers.d/builder
    chmod 0440 /etc/sudoers.d/builder
    exec sudo -u builder \
        SCRIPT_DIR="$SCRIPT_DIR" \
        WORKDIR="$WORKDIR" \
        KWIN_PATCH="$KWIN_PATCH" \
        XWAYLAND_PATCH="$XWAYLAND_PATCH" \
        BASH_SOURCE="$0" \
        /bin/bash "$0" "$@"
fi

if [ "$(id -u)" -eq 0 ]; then
    SUDO=""
else
    SUDO="sudo"
fi

SCRIPT_DIR="${SCRIPT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"
WORKDIR="${WORKDIR:-$HOME/anland-pkgbuild}"
JOBS="$(nproc)"

find_patch() {
    local name="$1" explicit="${2:-}"
    if [ -n "$explicit" ] && [ -f "$explicit" ]; then
        printf '%s\n' "$explicit"; return 0
    fi
    local c
    for c in "$SCRIPT_DIR/$name" "./$name" "$SCRIPT_DIR/../$name"; do
        if [ -f "$c" ]; then printf '%s\n' "$c"; return 0; fi
    done
    local hit
    hit="$(find "$SCRIPT_DIR" "$PWD" -maxdepth 3 -name "$name" -type f 2>/dev/null | head -1)"
    if [ -n "$hit" ]; then printf '%s\n' "$hit"; return 0; fi
    return 1
}

log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[warn] %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m[error] %s\033[0m\n' "$*" >&2; exit 1; }

build_pkg_makepkg() {
    local src="$1" patch="$2" overlay_dir="$3"

    log "Installing build tools"
    $SUDO pacman -Sy --noconfirm archlinux-keyring glibc 2>/dev/null
    $SUDO pacman -Su --noconfirm 2>/dev/null
    $SUDO pacman -S --noconfirm --needed base-devel git patch tar 2>/dev/null

    log "Fetching PKGBUILD for '$src'"
    rm -rf "${WORKDIR:?}/$src"
    mkdir -p "$WORKDIR/$src"
    ( cd "$WORKDIR/$src" && git clone --depth=1 "https://gitlab.archlinux.org/archlinux/packaging/packages/$src.git" ) \
        || die "git clone for $src failed"

    local pkgdir
    pkgdir="$(find "$WORKDIR/$src" -maxdepth 2 -name PKGBUILD -printf '%h\n' | head -1)"
    [ -n "$pkgdir" ] || die "could not find PKGBUILD for $src"
    log "PKGBUILD dir: $pkgdir"

    if grep -q '^arch=(.*x86_64' "$pkgdir/PKGBUILD" && ! grep -q 'aarch64' "$pkgdir/PKGBUILD"; then
        sed -i 's/^arch=(\(.*\)x86_64\(.*\))/arch=(\1x86_64 aarch64\2)/' "$pkgdir/PKGBUILD"
        log "Extended arch array to include aarch64"
    fi

    local patchbase
    patchbase="$(basename "$patch")"
    cp "$patch" "$pkgdir/"

    if [ -d "$overlay_dir" ]; then
        log "Packing overlay '$overlay_dir' into source tarball"
        tar cf "$pkgdir/anland-overlay.tar" -C "$overlay_dir" .
    fi

    cd "$pkgdir"

    if [ "$src" = "kwin" ] && [ -d "$overlay_dir" ]; then
        if grep -q '^prepare()' PKGBUILD; then
            sed -i "/^prepare()/a \  cd \"\$srcdir/\$pkgname-\$pkgver\"\n  patch -p1 < ../$patchbase\n  mkdir -p src/backends/anland\n  tar xf ../anland-overlay.tar -C src/backends/anland/" PKGBUILD
        else
            sed -i "/^build()/i prepare() {\n  cd \"\$srcdir/\$pkgname-\$pkgver\"\n  patch -p1 < ../$patchbase\n  mkdir -p src/backends/anland\n  tar xf ../anland-overlay.tar -C src/backends/anland/\n}\n" PKGBUILD
        fi
        if grep -q '^source=' PKGBUILD; then
            sed -i "s|^source=(|source=(\"$patchbase\" \"anland-overlay.tar\"\n        |" PKGBUILD
        else
            sed -i "/^sha256sums=/i source=(\"$patchbase\" \"anland-overlay.tar\")" PKGBUILD
        fi
    else
        if grep -q '^prepare()' PKGBUILD; then
            sed -i "/^prepare()/a \  cd \"\$srcdir/\$pkgname-\$pkgver\"\n  patch -p1 < ../$patchbase" PKGBUILD
        else
            sed -i "/^build()/i prepare() {\n  cd \"\$srcdir/\$pkgname-\$pkgver\"\n  patch -p1 < ../$patchbase\n}\n" PKGBUILD
            sed -i "/^sha256sums=/i source=(\"$patchbase\")" PKGBUILD
        fi
    fi

    log "Building '$src' (makepkg -si --skipinteg)"
    makepkg -si --noconfirm --skipinteg --nocheck \
        || die "makepkg failed for $src"

    log "Collecting built .pkg.tar.zst for '$src'"
    local pkgs
    pkgs="$(find "$pkgdir" -maxdepth 1 -name '*.pkg.tar.zst' -type f 2>/dev/null)"
    [ -n "$pkgs" ] || die "no .pkg.tar.zst produced for $src"
    printf '%s\n' "$pkgs"
    find "$pkgdir" -maxdepth 1 -name '*.pkg.tar.zst' -type f -exec cp {} "$WORKDIR/$src/" \;

    log "Locking '$src' against pacman upgrades"
    if ! $SUDO grep -q "^IgnorePkg = $src" /etc/pacman.conf 2>/dev/null; then
        if $SUDO grep -q '^IgnorePkg' /etc/pacman.conf 2>/dev/null; then
            $SUDO sed -i "s/^IgnorePkg = /IgnorePkg = $src /" /etc/pacman.conf
        else
            $SUDO sed -i "/^\[options\]/a IgnorePkg = $src" /etc/pacman.conf
        fi
    fi
}

main() {
    local kwin_patch xwl_patch
    kwin_patch="$(find_patch kwin.patch "${KWIN_PATCH:-}")" \
        || die "kwin.patch not found (set KWIN_PATCH=... to override)"
    xwl_patch="$(find_patch xwayland.patch "${XWAYLAND_PATCH:-}")" \
        || die "xwayland.patch not found (set XWAYLAND_PATCH=... to override)"

    log "kwin.patch     : $kwin_patch"
    log "xwayland.patch : $xwl_patch"
    log "work dir       : $WORKDIR"

    build_pkg_makepkg kwin          "$kwin_patch" "$SCRIPT_DIR/kwin/src/backends/anland"
    build_pkg_makepkg xorg-xwayland "$xwl_patch"  ""

    log "Done. Patched kwin and Xwayland built and installed."
    echo "Built packages are under: $WORKDIR/{kwin,xorg-xwayland}/"
    echo "Restart the compositor session for the changes to take effect."
}

main "$@"
