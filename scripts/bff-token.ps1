<#
.SYNOPSIS
  用与 App 相同的 OIDC PKCE 流程向 Authelia 取一个 access_token，用于验证 BFF。

.DESCRIPTION
  需要在 Authelia 的 voicebook-app 客户端 redirect_uris 中加入 http://localhost:8765/callback
  （见 server/deploy/authelia-client.yml）。脚本会打开浏览器登录，回调后换取 token，
  并写入当前会话的 $env:BFF_TOKEN。

.EXAMPLE
  .\scripts\bff-token.ps1
  .\scripts\bff-smoke.ps1 -BaseUrl https://nas.wangyl.work:8462
#>
param(
    [string]$Issuer = "https://nas.wangyl.work:8461",
    [string]$ClientId = "voicebook-app",
    [int]$Port = 8765,
    [string]$Scope = "openid profile email groups offline_access"
)

$ErrorActionPreference = "Stop"

function ConvertTo-Base64Url([byte[]]$bytes) {
    [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$verifierBytes = New-Object byte[] 32
$rng.GetBytes($verifierBytes)
$verifier = ConvertTo-Base64Url $verifierBytes
$challenge = ConvertTo-Base64Url ([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::ASCII.GetBytes($verifier)))
# Authelia rejects state shorter than 8 characters; 32 hex is what the app uses.
$stateBytes = New-Object byte[] 16
$rng.GetBytes($stateBytes)
$state = -join ($stateBytes | ForEach-Object { $_.ToString("x2") })

$redirect = "http://localhost:$Port/callback"
$query = @{
    client_id             = $ClientId
    redirect_uri          = $redirect
    response_type         = "code"
    scope                 = $Scope
    state                 = $state
    code_challenge        = $challenge
    code_challenge_method = "S256"
}.GetEnumerator() | ForEach-Object { "$($_.Key)=$([Uri]::EscapeDataString($_.Value))" }
$authUrl = "$Issuer/api/oidc/authorization?" + ($query -join "&")

$listener = New-Object Net.HttpListener
$listener.Prefixes.Add("http://localhost:$Port/")
$listener.Start()
try {
    Write-Host "在浏览器中完成登录..." -ForegroundColor Cyan
    Start-Process $authUrl
    $ctx = $listener.GetContext()
    $params = $ctx.Request.QueryString
    $html = [Text.Encoding]::UTF8.GetBytes("<html><meta charset='utf-8'><body>已收到回调，可以关闭此页面。</body></html>")
    $ctx.Response.ContentType = "text/html; charset=utf-8"
    $ctx.Response.OutputStream.Write($html, 0, $html.Length)
    $ctx.Response.Close()
} finally {
    $listener.Stop()
}

if ($params["error"]) { throw "授权失败: $($params["error"]) $($params["error_description"])" }
if ($params["state"] -ne $state) { throw "state 不匹配，放弃" }

$token = Invoke-RestMethod -Method Post -Uri "$Issuer/api/oidc/token" `
    -ContentType "application/x-www-form-urlencoded" `
    -Body @{
        grant_type    = "authorization_code"
        code          = $params["code"]
        redirect_uri  = $redirect
        client_id     = $ClientId
        code_verifier = $verifier
    }

$env:BFF_TOKEN = $token.access_token
Write-Host "access_token 已写入 `$env:BFF_TOKEN（有效期 $($token.expires_in)s，长度 $($token.access_token.Length)）" -ForegroundColor Green
if ($token.refresh_token) { Write-Host "refresh_token: $($token.refresh_token)" }
