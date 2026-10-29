"""Reject Android toolchains with the known 32-bit seccomp regression."""
import re
import sys


def require_supported_go_version(version):
    match = re.fullmatch(r'go(\d+)\.(\d+)(?:\.(\d+))?', version.strip())
    if not match:
        raise ValueError(f'Use a released Go toolchain; cannot verify Android compatibility of {version!r}')
    parsed = tuple(int(part or 0) for part in match.groups())
    if parsed < (1, 25, 0):
        raise ValueError('Soar requires Go 1.25 or later')
    # Go 1.26.0-1.26.2 probe futex_time64 before falling back. Android
    # 8-10 app seccomp kills that syscall on 32-bit devices. Fixed in 1.26.3:
    # https://github.com/golang/go/issues/77931
    # https://github.com/golang/go/issues/78936
    if (1, 26, 0) <= parsed < (1, 26, 3):
        raise ValueError('Go 1.26.0-1.26.2 can crash on older 32-bit Android; use Go 1.26.3 or later, or Go 1.25.x')
    return parsed


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: android_go_toolchain.py go1.26.9')
    try:
        require_supported_go_version(sys.argv[1])
    except ValueError as error:
        raise SystemExit(str(error))
    print(f'Android Go toolchain verified: {sys.argv[1]}')
