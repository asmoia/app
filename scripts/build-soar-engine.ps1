param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$NdkVersion = '29.0.14206865',
    [string[]]$Abis = @('arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86')
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolchain = Join-Path $SdkRoot "ndk/$NdkVersion/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
if (-not (Test-Path -LiteralPath $toolchain)) { throw 'Android NDK clang.exe was not found.' }
$targets = @{
    'arm64-v8a' = @('arm64', 'aarch64-linux-android26')
    'armeabi-v7a' = @('arm', 'armv7a-linux-androideabi26')
    'x86_64' = @('amd64', 'x86_64-linux-android26')
    'x86' = @('386', 'i686-linux-android26')
}
$saved = @{}
foreach ($name in @('GOOS','GOARCH','GOARM','CGO_ENABLED','CC')) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
Push-Location (Join-Path $projectRoot 'soar-engine')
try {
    $goVersion = (& go env GOVERSION).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot determine the selected Go toolchain.' }
    & python (Join-Path $projectRoot 'scripts/android_go_toolchain.py') $goVersion
    if ($LASTEXITCODE -ne 0) { throw 'The selected Go toolchain does not support older Android devices.' }
    $moduleRoot = (& go list -m -f '{{.Dir}}' github.com/getlantern/soar).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot locate the pinned Soar module.' }
    foreach ($country in @('ir','global','cn','ru')) {
        $copied = Join-Path $projectRoot "soar-engine/data/resolvers/$country.txt"
        $original = Join-Path $moduleRoot "data/resolvers/$country.txt"
        if ((Get-FileHash -LiteralPath $copied).Hash -ne (Get-FileHash -LiteralPath $original).Hash) {
            throw "Resolver snapshot differs from pinned Soar source: $country"
        }
    }
    $env:GOOS = 'android'
    $env:CGO_ENABLED = '1'
    foreach ($abi in $Abis) {
        if (-not $targets.ContainsKey($abi)) { throw "Unknown ABI: $abi" }
        $env:GOARCH = $targets[$abi][0]
        $env:GOARM = '7'
        $env:CC = '"' + $toolchain.Replace('\','/') + '" --target=' + $targets[$abi][1]
        $output = Join-Path $projectRoot "app/src/main/jniLibs/$abi/libsoar_client.so"
        Write-Host "Building Soar for $abi"
        & go build -mod=readonly -trimpath -buildvcs=false -buildmode=pie '-ldflags=-s -w -linkmode external -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384' -o $output .
        if ($LASTEXITCODE -ne 0) { throw "Soar build failed: $abi" }
    }
} finally {
    Pop-Location
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
