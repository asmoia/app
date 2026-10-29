"""Require every packaged ABI to include its source-built Soar executable."""
from pathlib import Path
import sys
import re
import zipfile
import subprocess
from android_go_toolchain import require_supported_go_version

root = Path(__file__).resolve().parents[1]
version = re.search(r'fmt.Println\("(WhiteDNS Soar engine[^"]+)"\)', (root / 'soar-engine/main.go').read_text()).group(1).encode()
for abi in ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'):
    binary = root / f'app/src/main/jniLibs/{abi}/libsoar_client.so'
    metadata = subprocess.check_output(['go', 'version', '-m', str(binary)], text=True)
    # Inspect build info without executing a foreign-architecture binary.
    toolchain = metadata.splitlines()[0].rsplit(': ', 1)[-1].strip()
    require_supported_go_version(toolchain)
directory = Path(sys.argv[1])
apks = list(directory.glob('*.apk'))
assert apks, 'No APKs to verify'
for path in apks:
    with zipfile.ZipFile(path) as apk:
        native = [name for name in apk.namelist() if name.endswith('/libstormdns_client.so')]
        assert native, f'{path.name}: no legacy native engine'
        for legacy in native:
            abi = legacy.split('/')[1]
            data = apk.read(f'lib/{abi}/libsoar_client.so')
            assert data == (root / f'app/src/main/jniLibs/{abi}/libsoar_client.so').read_bytes(), abi
            assert data[:4] == b'\x7fELF'
            assert version in data, f'{path.name}: stale Soar engine version for {abi}'
        assert b'Apache' in apk.read('assets/soar-third-party-notices.txt')
    print(f'{path.name}: Soar present and source-built bytes match ({len(native)} ABIs)')
