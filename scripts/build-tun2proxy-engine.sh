#!/usr/bin/env bash
# Source-build the Android JNI library. The upstream command-line shutdown
# watchdog must not terminate the Android process that owns this JNI runner.
set -Eeuo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
pin=27466222b9261d7ef4a4872fc15111cf0d0e95bb
rust_version=1.99.1
source_dir="${TUN2PROXY_SOURCE_DIR:-$root/.cache/native/tun2proxy-$pin}"
ndk=${NDK_ROOT:-${ANDROID_HOME:?Set ANDROID_HOME or NDK_ROOT}/ndk/29.0.14206865}
host=${NDK_HOST:-linux-x86_64}
api=${ANDROID_API:-26}
toolchain="$ndk/toolchains/llvm/prebuilt/$host/bin"
metadata="$root/third_party/tun2proxy"
patch_file="$metadata/android-host-lifecycle.patch"
lock_file="$metadata/Cargo.lock"
[[ "$api" == 26 ]] || { echo 'tun2proxy builds must retain Android API 26 compatibility' >&2; exit 1; }
[[ -f "$lock_file" ]] || { echo 'Missing reviewed tun2proxy Cargo.lock' >&2; exit 1; }
if [[ ! -d "$source_dir/.git" ]]; then
    mkdir -p "$source_dir"
    git -C "$source_dir" init -q
    git -C "$source_dir" remote add origin https://github.com/tun2proxy/tun2proxy.git
    git -C "$source_dir" fetch --depth=1 origin "$pin"
    git -C "$source_dir" checkout --detach FETCH_HEAD
fi
[[ "$(git -C "$source_dir" rev-parse HEAD)" == "$pin" ]] || { echo 'Unexpected tun2proxy source revision' >&2; exit 1; }
if git -C "$source_dir" apply --check "$patch_file"; then
    git -C "$source_dir" apply "$patch_file"
else
    # A repeated build may reuse only this exact patch, with no other edits.
    git -C "$source_dir" apply --reverse --check "$patch_file"
fi
expected_diff=$(mktemp)
trap 'rm -f -- "$expected_diff"' EXIT
# Verify patch application independently of the hunk headers in the patch file.
git -C "$source_dir" diff --name-only | grep -vx 'src/general_api.rs' > "$expected_diff" || true
[[ ! -s "$expected_diff" ]] || { echo 'Unexpected modified tun2proxy source files' >&2; cat "$expected_diff" >&2; exit 1; }
python3 - "$source_dir/src/general_api.rs" <<'PY'
from pathlib import Path
import sys
source=Path(sys.argv[1]).read_text()
assert source.count('WHITEDNS_ANDROID_HOST_LIFECYCLE_V1') == 1
assert '#[cfg(not(target_os = "android"))]\n        let _h = std::thread::spawn' in source
PY
python3 - "$source_dir" "$metadata" <<'PY'
from pathlib import Path
import hashlib,json,sys
source,metadata=map(Path,sys.argv[1:])
pin=json.loads((metadata/'SOURCE.json').read_text())
for path,key in [(source/'src/general_api.rs','patched_general_api_sha256'),(metadata/'android-host-lifecycle.patch','patch_sha256'),(metadata/'Cargo.lock','cargo_lock_sha256')]:
    assert hashlib.sha256(path.read_bytes().replace(b'\r\n',b'\n')).hexdigest()==pin[key], f'Unreviewed source/build input: {path}'
PY
cp -- "$lock_file" "$source_dir/Cargo.lock"
rustup toolchain install "$rust_version" --profile minimal
targets=(aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android)
rustup target add --toolchain "$rust_version" "${targets[@]}"
export CARGO_TARGET_DIR="${TUN2PROXY_TARGET_DIR:-$root/.cache/native/tun2proxy-target}"
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"
for spec in 'arm64-v8a aarch64-linux-android aarch64-linux-android' 'armeabi-v7a armv7-linux-androideabi armv7a-linux-androideabi' 'x86_64 x86_64-linux-android x86_64-linux-android' 'x86 i686-linux-android i686-linux-android'; do
    read -r abi target cc_target <<< "$spec"
    cc="$toolchain/$cc_target$api-clang"
    target_env=${target//-/_}
    target_env=${target_env^^}
    [[ -x "$cc" ]] || { printf 'Missing Android compiler: %s\n' "$cc" >&2; exit 1; }
    env "CC_${target//-/_}=$cc" "AR_${target//-/_}=$toolchain/llvm-ar" \
        "CARGO_TARGET_${target_env}_LINKER=$cc" \
        cargo +"$rust_version" build --manifest-path "$source_dir/Cargo.toml" --locked --release --lib --target "$target"
    mkdir -p "$root/app/src/main/jniLibs/$abi"
    cp -- "$CARGO_TARGET_DIR/$target/release/libtun2proxy.so" "$root/app/src/main/jniLibs/$abi/libtun2proxy.so"
    "$toolchain/llvm-strip" --strip-unneeded "$root/app/src/main/jniLibs/$abi/libtun2proxy.so"
done
python3 "$root/scripts/verify-tun2proxy-native.py" --write-receipt --ndk "$ndk" --host "$host"
