#!/usr/bin/env bash
set -Eeuo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
go_command=${GO:-go}
ndk=${NDK_ROOT:-${ANDROID_HOME:?Set ANDROID_HOME or NDK_ROOT}/ndk/29.0.14206865}
host=${NDK_HOST:-linux-x86_64}
api=${ANDROID_API:-26}
toolchain="$ndk/toolchains/llvm/prebuilt/$host/bin"
cd "$root/soar-engine"
python3 "$root/scripts/android_go_toolchain.py" "$("$go_command" env GOVERSION)"
"$go_command" mod download
"$go_command" mod verify
module=$("$go_command" list -m -f '{{.Dir}}' github.com/getlantern/soar)
for country in ir global cn ru; do
    cmp -- "data/resolvers/$country.txt" "$module/data/resolvers/$country.txt"
done
for spec in 'arm64-v8a arm64 aarch64-linux-android' 'armeabi-v7a arm armv7a-linux-androideabi' 'x86_64 amd64 x86_64-linux-android' 'x86 386 i686-linux-android'; do
    read -r abi arch target <<< "$spec"
    cc="$toolchain/$target$api-clang"
    [[ -x $cc ]] || { printf 'Missing Android compiler: %s\n' "$cc" >&2; exit 1; }
    mkdir -p "$root/app/src/main/jniLibs/$abi"
    printf 'Building Soar for %s\n' "$abi"
    CGO_ENABLED=1 GOOS=android GOARCH="$arch" GOARM=7 CC="$cc" \
        "$go_command" build -mod=readonly -trimpath -buildvcs=false -buildmode=pie \
        -ldflags '-s -w -linkmode external -extldflags "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"' \
        -o "$root/app/src/main/jniLibs/$abi/libsoar_client.so" .
done
