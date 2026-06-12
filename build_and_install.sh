#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PREFIX="/opt/weston-vdrm"
BUILD_DIR="$SCRIPT_DIR/weston/builddir"
LIB_BUILD_DIR="$SCRIPT_DIR/build"

echo "=== Installing build dependencies ==="
apt-get update -qq
apt-get install -y -qq \
    build-essential meson ninja-build pkg-config \
    libwayland-dev libpixman-1-dev libxkbcommon-dev \
    libinput-dev libevdev-dev libdrm-dev libgbm-dev \
    libudev-dev libseat-dev libcairo2-dev \
    libjpeg-dev libwebp-dev libpam0g-dev \
    libgles-dev libegl-dev libvulkan-dev glslang-tools \
    libxcb-composite0-dev libxcb-shape0-dev libxcb-xfixes0-dev \
    libxcursor-dev libxcb1-dev \
    libpango1.0-dev libglib2.0-dev \
    libwayland-cursor0 wayland-protocols libwayland-bin \
    libpng-dev libfontconfig-dev libfreetype-dev \
    hwdata

echo "=== Building libdisplay_producer + libsocket_utils ==="
mkdir -p "$LIB_BUILD_DIR"

cc -c -fPIC -O2 -Wall \
    -I"$SCRIPT_DIR/common" \
    "$SCRIPT_DIR/common/socket_utils.c" \
    -o "$LIB_BUILD_DIR/socket_utils.o"

ar rcs "$LIB_BUILD_DIR/libsocket_utils.a" "$LIB_BUILD_DIR/socket_utils.o"

cc -shared -fPIC -O2 -Wall \
    -I"$SCRIPT_DIR/common" \
    -I"$SCRIPT_DIR/libdisplay_producer" \
    "$SCRIPT_DIR/libdisplay_producer/display_producer.c" \
    -L"$LIB_BUILD_DIR" -lsocket_utils -lpthread \
    -o "$LIB_BUILD_DIR/libdisplay_producer.so"

echo "=== Patching wayland-protocols ==="
cp -v "$SCRIPT_DIR/wayland-protocols-override/staging/color-representation/color-representation-v1.xml" \
    /usr/share/wayland-protocols/staging/color-representation/color-representation-v1.xml

echo "=== Configuring weston ==="
MESON_OPTS=(
    --prefix="$PREFIX"
    -Dbackend-drm=false
    -Dbackend-headless=false
    -Dbackend-pipewire=false
    -Dbackend-rdp=false
    -Dbackend-vnc=false
    -Dbackend-wayland=false
    -Dbackend-x11=false
    -Dbackend-virtual-drm=true
    -Dbackend-default=auto
    -Dvdrm-lib-dir=build
    -Drenderer-gl=true
    -Drenderer-vulkan=true
    -Dxwayland=true
    -Dcolor-management-lcms=false
    -Dimage-jpeg=true
    -Dimage-webp=true
    -Dshell-desktop=true
    -Dshell-kiosk=false
    -Dshell-ivi=false
    -Dshell-lua=false
    -Ddemo-clients=false
    -Dsimple-clients=[]
    -Dtools=terminal
    -Dsystemd=false
    -Dtests=false
    -Ddoc=false
    -Dperfetto=false
    -Ddeprecated-remoting=false
    -Ddeprecated-pipewire=false
)

if [ -d "$BUILD_DIR" ]; then
    meson setup --reconfigure "$BUILD_DIR" "$SCRIPT_DIR/weston" "${MESON_OPTS[@]}"
else
    meson setup "$BUILD_DIR" "$SCRIPT_DIR/weston" "${MESON_OPTS[@]}"
fi

echo "=== Building weston ==="
ninja -C "$BUILD_DIR" -j$(nproc)

echo "=== Installing to $PREFIX ==="
ninja -C "$BUILD_DIR" install

cp "$LIB_BUILD_DIR/libdisplay_producer.so" "$PREFIX/lib/aarch64-linux-gnu/"
ldconfig "$PREFIX/lib/aarch64-linux-gnu"

LIBDIR="$PREFIX/lib/aarch64-linux-gnu"
cat > "$PREFIX/start.sh" << EOF
#!/bin/bash
SOCK="\${1:-/run/display.sock}"

export LD_LIBRARY_PATH="$LIBDIR:$LIBDIR/libweston-16:$LIBDIR/weston:\$LD_LIBRARY_PATH"
export XDG_RUNTIME_DIR="\${XDG_RUNTIME_DIR:-/tmp}"
export WESTON_MODULE_MAP="virtual-drm-backend.so=$LIBDIR/libweston-16/virtual-drm-backend.so;gl-renderer.so=$LIBDIR/libweston-16/gl-renderer.so;vulkan-renderer.so=$LIBDIR/libweston-16/vulkan-renderer.so;desktop-shell.so=$LIBDIR/weston/desktop-shell.so;xwayland.so=$LIBDIR/libweston-16/xwayland.so"

shift 2>/dev/null || true
exec $PREFIX/bin/weston -Bvirtual-drm-backend.so --disp-sock="\$SOCK" --xwayland "\$@"
EOF
chmod +x "$PREFIX/start.sh"

echo ""
echo "=== Done ==="
echo "  Installed to: $PREFIX"
echo "  Start:        $PREFIX/start.sh [socket-path]"
echo "  Default sock: /run/display.sock"
