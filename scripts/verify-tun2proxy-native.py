"""Validate the reviewed JNI runner and byte-identical APK packaging."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import struct
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('apk_directory', nargs='?')
parser.add_argument('--native-only', action='store_true')
parser.add_argument('--write-receipt', action='store_true')
parser.add_argument('--ndk', type=Path)
parser.add_argument('--host', default='linux-x86_64')
args = parser.parse_args()
source = json.loads((root / 'third_party/tun2proxy/SOURCE.json').read_text())
receipt_path = root / 'app/src/main/assets/tun2proxy-build.json'
targets = {
    'arm64-v8a': ('aarch64-linux-android', 183),
    'armeabi-v7a': ('arm-linux-androideabi', 40),
    'x86_64': ('x86_64-linux-android', 62),
    'x86': ('i686-linux-android', 3),
}
marker = b'WHITEDNS_ANDROID_HOST_LIFECYCLE_V1'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def validate_elf(data, abi):
    assert data[:4] == b'\x7fELF', f'{abi}: not ELF'
    assert data[5] == 1, f'{abi}: unexpected byte order'
    assert struct.unpack_from('<H', data, 16)[0] == 3, f'{abi}: not ET_DYN'
    assert struct.unpack_from('<H', data, 18)[0] == targets[abi][1], f'{abi}: wrong architecture'
    is64 = data[4] == 2
    offset = struct.unpack_from('<Q' if is64 else '<I', data, 32 if is64 else 28)[0]
    size, count = struct.unpack_from('<HH', data, 54 if is64 else 42)
    aligns = []
    for i in range(count):
        entry = offset + size * i
        if struct.unpack_from('<I', data, entry)[0] == 1:
            aligns.append(struct.unpack_from('<Q' if is64 else '<I', data, entry + (48 if is64 else 28))[0])
    assert aligns and min(aligns) >= 16384, f'{abi}: less than 16 KiB alignment'
    for symbol in [b'Java_com_github_shadowsocks_bg_Tun2proxy_run', b'Java_com_github_shadowsocks_bg_Tun2proxy_stop', marker]:
        assert symbol in data, f'{abi}: missing reviewed JNI/lifecycle marker {symbol!r}'


def api_imports(abi, path):
    if args.ndk is None:
        return None
    toolchain = args.ndk / 'toolchains/llvm/prebuilt' / args.host
    executable = toolchain / 'bin' / ('llvm-readelf.exe' if args.host.startswith('windows') else 'llvm-readelf')
    def readelf(*options):
        return subprocess.check_output([str(executable), *map(str, options)], text=True)
    needed = re.findall(r'Shared library: \[(.*?)\]', readelf('-dW', path))
    imports = set()
    for line in readelf('--dyn-syms', '-W', path).splitlines():
        fields = line.split()
        if len(fields) >= 8 and fields[6] == 'UND' and fields[4] != 'WEAK':
            imports.add(fields[7].split('@')[0])
    exports = set()
    for name in needed:
        library = toolchain / f'sysroot/usr/lib/{targets[abi][0]}/26/{name}'
        if not library.exists():
            library = toolchain / f'sysroot/usr/lib/{targets[abi][0]}/{name}'
        assert library.exists(), f'{abi}: unsupported dependency {name}'
        for line in readelf('--dyn-syms', '-W', library).splitlines():
            fields = line.split()
            if len(fields) >= 8 and fields[6] != 'UND':
                exports.add(fields[7].split('@')[0])
    assert not imports - exports, f'{abi}: symbols unavailable at API 26: {imports - exports}'
    return {'needed': needed, 'api26_unresolved_symbols': []}


native = {}
for abi in targets:
    path = root / f'app/src/main/jniLibs/{abi}/libtun2proxy.so'
    data = path.read_bytes()
    validate_elf(data, abi)
    native[abi] = {'sha256': sha(data), 'bytes': len(data)}
    checked = api_imports(abi, path)
    if checked:
        native[abi].update(checked)

if args.write_receipt:
    assert args.ndk, 'NDK is required to record API compatibility'
    receipt = {'source': source, 'native': native, 'host_lifecycle_patch': 'WHITEDNS_ANDROID_HOST_LIFECYCLE_V1'}
    receipt_path.write_text(json.dumps(receipt, indent=2) + '\n')
else:
    receipt = json.loads(receipt_path.read_text())
    assert receipt['source'] == source, 'Stale source receipt'
    for abi in targets:
        assert receipt['native'][abi]['sha256'] == native[abi]['sha256'], f'{abi}: stale native library'

print('Native tun2proxy: all four reviewed API 26 JNI libraries verified')
if args.apk_directory:
    apks = list(Path(args.apk_directory).glob('*.apk'))
    assert apks, 'No APKs to verify'
    for path in apks:
        with zipfile.ZipFile(path) as apk:
            packaged_receipt = json.loads(apk.read('assets/tun2proxy-build.json'))
            assert packaged_receipt == receipt, f'{path.name}: stale native receipt'
            libraries = [name for name in apk.namelist() if name.endswith('/libtun2proxy.so')]
            assert libraries, f'{path.name}: no VPN JNI library'
            for name in libraries:
                abi = name.split('/')[1]
                data = apk.read(name)
                validate_elf(data, abi)
                assert sha(data) == receipt['native'][abi]['sha256'], f'{path.name}/{abi}: packaged library differs'
        print(f'{path.name}: reviewed tun2proxy bytes match ({len(libraries)} ABIs)')
