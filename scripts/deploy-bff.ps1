<#
.SYNOPSIS
  一键部署 VoiceBook BFF 到 NAS：本地测试+打包 -> scp 上传 -> NAS 上 docker compose 构建并启动。

.EXAMPLE
  .\scripts\deploy-bff.ps1 -NasHost admin@192.168.1.10
  .\scripts\deploy-bff.ps1 -NasHost admin@nas -SshPort 2222 -Sudo -SkipTests

.NOTES
  依赖：本机 JDK 17、Windows 自带 OpenSSH(ssh/scp) 与 tar；NAS 上 docker + compose。
  首次部署会在 NAS 生成 .env 并退出，填好后重跑即可。
#>
param(
    [string]$NasHost = $env:BFF_NAS_HOST,
    [string]$RemoteDir = $(if ($env:BFF_REMOTE_DIR) { $env:BFF_REMOTE_DIR } else { "voicebook-bff" }),
    [int]$SshPort = 22,
    [switch]$Sudo,
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
if (-not $NasHost) { throw "请用 -NasHost user@host 或环境变量 BFF_NAS_HOST 指定 NAS" }

$root = Split-Path $PSScriptRoot -Parent
$build = Join-Path $root "server\build"
$bundle = Join-Path $build "deploy-bundle"
$archive = Join-Path $build "voicebook-bff.tgz"

function Invoke-Native([string]$exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "$exe 退出码 $LASTEXITCODE" }
}

Write-Host "==> 构建" -ForegroundColor Cyan
Push-Location $root
try {
    $tasks = @(":server:installDist", "--console=plain")
    if (-not $SkipTests) { $tasks = @(":server:test") + $tasks }
    Invoke-Native ".\gradlew.bat" $tasks
} finally {
    Pop-Location
}

Write-Host "==> 打包" -ForegroundColor Cyan
if (Test-Path $bundle) { Remove-Item $bundle -Recurse -Force }
New-Item -ItemType Directory $bundle | Out-Null
Copy-Item (Join-Path $build "install\bff") (Join-Path $bundle "bff") -Recurse
foreach ($f in "Dockerfile", "docker-compose.yml", ".env.example", "remote-up.sh") {
    Copy-Item (Join-Path $root "server\deploy\$f") $bundle
}
if (Test-Path $archive) { Remove-Item $archive -Force }
Invoke-Native "tar" @("-czf", $archive, "-C", $bundle, ".")

Write-Host "==> 上传到 ${NasHost}:$RemoteDir" -ForegroundColor Cyan
$sshArgs = @("-p", "$SshPort")
if ($Sudo) { $sshArgs += "-t" }
Invoke-Native "ssh" ($sshArgs + @($NasHost, "mkdir -p '$RemoteDir'"))
Invoke-Native "scp" @("-P", "$SshPort", $archive, "${NasHost}:$RemoteDir/voicebook-bff.tgz")

Write-Host "==> 远端启动" -ForegroundColor Cyan
$sudoArg = if ($Sudo) { "sudo" } else { "" }
$remote = "cd '$RemoteDir' && rm -rf bff && tar -xzf voicebook-bff.tgz && rm -f voicebook-bff.tgz && sh remote-up.sh $sudoArg"
& ssh @sshArgs $NasHost $remote
switch ($LASTEXITCODE) {
    0 { Write-Host "部署完成。验证：.\scripts\bff-smoke.ps1 -BaseUrl https://<域名>:8462 -Token <access_token>" -ForegroundColor Green }
    3 { Write-Host "请 ssh 到 NAS 编辑 $RemoteDir/.env 后重跑本脚本。" -ForegroundColor Yellow; exit 3 }
    default { throw "远端部署失败，退出码 $LASTEXITCODE" }
}
