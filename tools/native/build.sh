#!/usr/bin/env bash
# Builds libsquishav.so for one Android ABI: FFmpeg with every demuxer and
# every decoder it has, dav1d (AV1), libjxl (JPEG XL), LibRaw (camera RAW)
# and the JNI wrapper in app/src/main/cpp, linked into one library that
# exports nothing but its JNI entry points.
#
#   tools/native/build.sh arm64-v8a [workdir]
#   tools/native/build.sh cli [workdir]      # an ffmpeg command line for an
#                                            # arm64 phone, to make test files
#
# Runs on Linux (the GitHub workflow .github/workflows/native.yml runs it):
# FFmpeg's configure is a shell script of thousands of forks, which the
# Windows desktop's Git Bash takes most of an hour over, and it needs make,
# meson and cmake. Wants ANDROID_NDK_ROOT, and nasm, meson, ninja, cmake and
# pkg-config on the PATH. The result lands in <workdir>/out/<abi>/.
#
# Versions are pinned here and only here; a bump is a rebuild of all four.
set -euo pipefail

ABI=${1:?abi: arm64-v8a, armeabi-v7a, x86_64, x86 or cli}
WORK=$(mkdir -p "${2:-$PWD/native-work}" && cd "${2:-$PWD/native-work}" && pwd)
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
NDK=${ANDROID_NDK_ROOT:?set ANDROID_NDK_ROOT}
API=29
JOBS=$(nproc 2>/dev/null || echo 4)

FFMPEG_VERSION=8.1.3
DAV1D_VERSION=1.5.1
LIBJXL_VERSION=v0.11.1
LIBRAW_VERSION=0.21.4

CLI=0
if [ "$ABI" = cli ]; then CLI=1; ABI=arm64-v8a; fi

case "$ABI" in
  arm64-v8a)   TRIPLE=aarch64-linux-android;     ARCH=aarch64; CPU=armv8-a;  MESON_CPU=aarch64; ASM="--enable-neon" ;;
  armeabi-v7a) TRIPLE=armv7a-linux-androideabi;  ARCH=arm;     CPU=armv7-a;  MESON_CPU=arm;     ASM="--enable-neon" ;;
  x86_64)      TRIPLE=x86_64-linux-android;      ARCH=x86_64;  CPU=x86-64;   MESON_CPU=x86_64;  ASM="" ;;
  # 32-bit x86 assembly is not position-independent; Android refuses text relocations.
  x86)         TRIPLE=i686-linux-android;        ARCH=x86;     CPU=i686;     MESON_CPU=x86;     ASM="--disable-asm" ;;
  *) echo "unknown abi $ABI" >&2; exit 1 ;;
esac

TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
SYSROOT=$TC/sysroot
CC=$TC/bin/${TRIPLE}${API}-clang
CXX=$TC/bin/${TRIPLE}${API}-clang++
AR=$TC/bin/llvm-ar
NM=$TC/bin/llvm-nm
RANLIB=$TC/bin/llvm-ranlib
STRIP=$TC/bin/llvm-strip
TAGNAME=$ABI; [ $CLI = 1 ] && TAGNAME=cli
PREFIX=$WORK/prefix/$TAGNAME
SRC=$WORK/src
BUILD=$WORK/build/$TAGNAME
OUT=$WORK/out/$TAGNAME
mkdir -p "$PREFIX/lib/pkgconfig" "$PREFIX/include" "$SRC" "$BUILD" "$OUT"

# Tools named plainly for the makefiles that call "ar" and "ranlib" by name
# (LibRaw's): the host's binutils do not read Android objects.
BIN=$WORK/bin/$TAGNAME
mkdir -p "$BIN"
ln -sf "$AR" "$BIN/ar"; ln -sf "$RANLIB" "$BIN/ranlib"
export PATH="$BIN:$PATH"
# 16 KB pages, which Android 15 devices may use and Play requires.
LDPAGE="-Wl,-z,max-page-size=16384"
COMMON_CFLAGS="-O2 -fPIC -fdata-sections -ffunction-sections"

fetch() { # url file
  [ -s "$SRC/$2" ] || curl -fsSL --retry 3 -o "$SRC/$2" "$1"
}

# ---- dav1d -------------------------------------------------------------
build_dav1d() {
  [ -f "$PREFIX/lib/libdav1d.a" ] && return
  fetch "https://code.videolan.org/videolan/dav1d/-/archive/$DAV1D_VERSION/dav1d-$DAV1D_VERSION.tar.gz" dav1d.tar.gz
  rm -rf "$BUILD/dav1d" && mkdir -p "$BUILD/dav1d" && tar xzf "$SRC/dav1d.tar.gz" -C "$BUILD/dav1d" --strip-components=1
  cat > "$BUILD/dav1d/cross.txt" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
[host_machine]
system = 'android'
cpu_family = '$MESON_CPU'
cpu = '$MESON_CPU'
endian = 'little'
[built-in options]
c_args = ['-fPIC']
c_link_args = ['$LDPAGE']
EOF
  local asm=true; [ "$ABI" = x86 ] && asm=false
  (cd "$BUILD/dav1d" && meson setup out --cross-file cross.txt --prefix="$PREFIX" --libdir=lib \
      --default-library=static --buildtype=release -Denable_tools=false -Denable_tests=false \
      -Denable_asm=$asm && ninja -C out -j"$JOBS" && ninja -C out install)
}

# ---- libjxl ------------------------------------------------------------
build_libjxl() {
  [ -f "$PREFIX/lib/libjxl.a" ] && return
  if [ ! -d "$SRC/libjxl" ]; then
    git clone --depth 1 --branch "$LIBJXL_VERSION" --recursive --shallow-submodules \
      https://github.com/libjxl/libjxl.git "$SRC/libjxl"
  fi
  rm -rf "$BUILD/libjxl" && mkdir -p "$BUILD/libjxl"
  cmake -S "$SRC/libjxl" -B "$BUILD/libjxl" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=$API -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_INSTALL_LIBDIR=lib \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DBUILD_SHARED_LIBS=OFF -DBUILD_TESTING=OFF \
    -DJPEGXL_ENABLE_TOOLS=OFF -DJPEGXL_ENABLE_DOXYGEN=OFF -DJPEGXL_ENABLE_MANPAGES=OFF \
    -DJPEGXL_ENABLE_BENCHMARK=OFF -DJPEGXL_ENABLE_EXAMPLES=OFF -DJPEGXL_ENABLE_JNI=OFF \
    -DJPEGXL_ENABLE_SJPEG=OFF -DJPEGXL_ENABLE_OPENEXR=OFF -DJPEGXL_ENABLE_JPEGLI=OFF \
    -DJPEGXL_ENABLE_PLUGINS=OFF -DJPEGXL_ENABLE_VIEWERS=OFF -DJPEGXL_ENABLE_SKCMS=ON \
    -DJPEGXL_BUNDLE_LIBPNG=OFF -DJPEGXL_ENABLE_FUZZERS=OFF -DJPEGXL_ENABLE_DEVTOOLS=OFF \
    -DJPEGXL_STATIC=OFF -DJPEGXL_FORCE_SYSTEM_BROTLI=OFF -DJPEGXL_FORCE_SYSTEM_HWY=OFF \
    -DHWY_ENABLE_TESTS=OFF -DHWY_ENABLE_EXAMPLES=OFF -DHWY_ENABLE_CONTRIB=OFF
  cmake --build "$BUILD/libjxl" -j"$JOBS"
  cmake --install "$BUILD/libjxl"
  # libjxl's own .pc files name its C++ runtime as -lstdc++ or not at all;
  # with the NDK it is libc++, linked statically into the final library.
  for pc in "$PREFIX"/lib/pkgconfig/libjxl*.pc; do
    sed -i 's/-lstdc++//g; s/-lc++//g' "$pc"
  done
}

# ---- LibRaw ------------------------------------------------------------
build_libraw() {
  [ -f "$PREFIX/lib/libraw.a" ] && return
  fetch "https://www.libraw.org/data/LibRaw-$LIBRAW_VERSION.tar.gz" libraw.tar.gz
  rm -rf "$BUILD/libraw" && mkdir -p "$BUILD/libraw" && tar xzf "$SRC/libraw.tar.gz" -C "$BUILD/libraw" --strip-components=1
  (cd "$BUILD/libraw" && mkdir -p object lib && make -f Makefile.dist -j"$JOBS" lib/libraw.a \
      CXX="$CXX" CC="$CC" CFLAGS="$COMMON_CFLAGS -I. -w -DUSE_ZLIB -DLIBRAW_NOTHREADS")
  cp "$BUILD/libraw/lib/libraw.a" "$PREFIX/lib/"
  mkdir -p "$PREFIX/include/libraw" && cp "$BUILD/libraw"/libraw/*.h "$PREFIX/include/libraw/"
}

# ---- FFmpeg ------------------------------------------------------------
build_ffmpeg() {
  local marker=$PREFIX/lib/libavcodec.a
  [ $CLI = 0 ] && [ -f "$marker" ] && return
  fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" ffmpeg.tar.xz
  rm -rf "$BUILD/ffmpeg" && mkdir -p "$BUILD/ffmpeg" && tar xJf "$SRC/ffmpeg.tar.xz" -C "$BUILD/ffmpeg" --strip-components=1
  local libs=(--enable-zlib --enable-libdav1d --enable-libjxl)
  local scope=(--disable-programs --disable-encoders --disable-muxers --disable-avdevice --disable-avfilter)
  if [ $CLI = 1 ]; then
    # The test-file maker: every native encoder and muxer, and the program.
    scope=(--enable-ffmpeg --disable-ffplay --disable-ffprobe)
    libs=(--enable-zlib)
  fi
  (cd "$BUILD/ffmpeg" && PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig" PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" ./configure \
      --prefix="$PREFIX" --target-os=android --arch="$ARCH" --cpu="$CPU" --enable-cross-compile \
      --cc="$CC" --cxx="$CXX" --ar="$AR" --nm="$NM" --ranlib="$RANLIB" --strip="$STRIP" \
      --sysroot="$SYSROOT" --pkg-config=pkg-config --pkg-config-flags=--static \
      --extra-cflags="$COMMON_CFLAGS -I$PREFIX/include" \
      --extra-ldflags="-L$PREFIX/lib $LDPAGE" \
      --extra-libs="-lc++_static -lc++abi -lm" \
      --enable-static --disable-shared --enable-pic --enable-gpl --enable-version3 \
      --disable-doc --disable-debug --disable-network --disable-devices --disable-hwaccels \
      --disable-mediacodec --disable-jni --disable-vulkan --disable-iconv --disable-autodetect \
      "${scope[@]}" "${libs[@]}" $ASM) || { tail -60 "$BUILD/ffmpeg/ffbuild/config.log"; exit 1; }
  make -C "$BUILD/ffmpeg" -j"$JOBS"
  make -C "$BUILD/ffmpeg" install
  if [ $CLI = 1 ]; then
    cp "$BUILD/ffmpeg/ffmpeg" "$OUT/ffmpeg"
    "$STRIP" "$OUT/ffmpeg"
  fi
}

# ---- the library -------------------------------------------------------
build_library() {
  local cpp=$ROOT/app/src/main/cpp
  local o=$BUILD/jni && mkdir -p "$o"
  "$CC" $COMMON_CFLAGS -Wall -Wextra -Wno-unused-parameter -Werror=implicit-function-declaration \
    -I"$PREFIX/include" -c "$cpp/squishav.c" -o "$o/squishav.o"
  "$CXX" $COMMON_CFLAGS -Wall -I"$PREFIX/include" -c "$cpp/squishraw.cpp" -o "$o/squishraw.o"
  local jxl
  jxl=$(PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig" PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" \
        pkg-config --static --libs libjxl libjxl_threads)
  "$CXX" -shared -o "$OUT/libsquishav.so" "$o/squishav.o" "$o/squishraw.o" \
    -L"$PREFIX/lib" -lavformat -lavcodec -lswscale -lswresample -lavutil -ldav1d $jxl -lraw \
    -lz -lm -llog -ljnigraphics -landroid -static-libstdc++ \
    -Wl,--version-script="$cpp/exports.map" -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-Bsymbolic \
    -Wl,--no-undefined -Wl,-z,noexecstack $LDPAGE
  "$STRIP" --strip-unneeded "$OUT/libsquishav.so"
  ls -l "$OUT/libsquishav.so"
}

if [ $CLI = 1 ]; then
  build_ffmpeg
  ls -l "$OUT/ffmpeg"
else
  build_dav1d
  build_libjxl
  build_libraw
  build_ffmpeg
  build_library
fi
