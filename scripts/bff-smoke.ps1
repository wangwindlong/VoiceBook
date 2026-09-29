<#
.SYNOPSIS
  逐条验证 BFF 的认证与三条上游通道。

.EXAMPLE
  .\scripts\bff-smoke.ps1 -BaseUrl https://nas.wangyl.work:8462                       # 仅匿名检查
  .\scripts\bff-smoke.ps1 -BaseUrl https://nas.wangyl.work:8462 -Username alice -Password 'xxx'   # 经 BFF 登录后全量检查，最后刷新并退出
  .\scripts\bff-token.ps1; .\scripts\bff-smoke.ps1 -BaseUrl https://nas.wangyl.work:8462   # 用浏览器 PKCE 拿到的 token
  .\scripts\bff-smoke.ps1 -BaseUrl ... -RegisterUser smoke01 -RegisterPassword 'Passw0rd123'
  .\scripts\bff-smoke.ps1 -BaseUrl ... -PostComment                                   # 会真实发一条评论
#>
param(
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [string]$Token = $env:BFF_TOKEN,
    [string]$Username,
    [string]$Password,
    [string]$RegisterUser,
    [string]$RegisterPassword,
    [string]$RegisterEmail,
    [switch]$PostComment
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd('/')
$results = New-Object System.Collections.Generic.List[object]

function Invoke-Bff([string]$Method, [string]$Path, [string]$Bearer, $Body) {
    $headers = @{}
    if ($Bearer) { $headers["Authorization"] = "Bearer $Bearer" }
    $req = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $headers; UseBasicParsing = $true }
    if ($null -ne $Body) {
        $req["ContentType"] = "application/json; charset=utf-8"
        $req["Body"] = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Compress))
    }
    try {
        $r = Invoke-WebRequest @req
        return [pscustomobject]@{ Status = [int]$r.StatusCode; Body = $r.Content }
    } catch {
        $resp = $_.Exception.Response
        if ($null -eq $resp) { return [pscustomobject]@{ Status = 0; Body = $_.Exception.Message } }
        return [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $_.ErrorDetails.Message }
    }
}

function Check([string]$Name, [int[]]$Expected, $Response) {
    $ok = $Expected -contains $Response.Status
    $body = "$($Response.Body)"
    if ($body.Length -gt 160) { $body = $body.Substring(0, 160) + "..." }
    $results.Add([pscustomobject]@{ Result = $(if ($ok) { "PASS" } else { "FAIL" }); Check = $Name; Status = $Response.Status; Body = $body })
    return $Response
}

Check "healthz" @(200) (Invoke-Bff GET "/healthz") | Out-Null
Check "未带 token 被拒" @(401) (Invoke-Bff GET "/api/auth/me") | Out-Null
Check "auth/config" @(200) (Invoke-Bff GET "/api/auth/config") | Out-Null

$refreshToken = $null
if ($Username) {
    Check "错误密码被拒" @(401) (Invoke-Bff POST "/api/auth/login" $null @{ username = $Username; password = "definitely-wrong-1" }) | Out-Null
    $login = Check "BFF 登录 $Username" @(200) (Invoke-Bff POST "/api/auth/login" $null @{ username = $Username; password = $Password })
    if ($login.Status -eq 200) {
        $tokens = ($login.Body | ConvertFrom-Json).tokens
        $Token = $tokens.accessToken
        $refreshToken = $tokens.refreshToken
    }
}

if ($RegisterUser) {
    $email = if ($RegisterEmail) { $RegisterEmail } else { "$RegisterUser@example.com" }
    Check "注册 $RegisterUser" @(201) (Invoke-Bff POST "/api/auth/register" $null @{
        username = $RegisterUser; email = $email; password = $RegisterPassword
    }) | Out-Null
}

if ($Token) {
    Check "auth/me" @(200) (Invoke-Bff GET "/api/auth/me" $Token) | Out-Null
    Check "miniflux me（首次会自动建号）" @(200) (Invoke-Bff GET "/api/miniflux/me" $Token) | Out-Null
    Check "miniflux entries" @(200) (Invoke-Bff GET "/api/miniflux/entries?limit=1" $Token) | Out-Null
    Check "miniflux feeds" @(200) (Invoke-Bff GET "/api/miniflux/feeds" $Token) | Out-Null
    Check "miniflux 管理接口被屏蔽" @(404) (Invoke-Bff GET "/api/miniflux/users" $Token) | Out-Null

    $books = Check "calibre books" @(200) (Invoke-Bff GET "/api/calibre/books?limit=1" $Token)
    $first = $null
    if ($books.Status -eq 200) { $first = ($books.Body | ConvertFrom-Json).items | Select-Object -First 1 }
    if ($first) {
        Check "calibre book detail" @(200) (Invoke-Bff GET "/api/calibre/books/$($first.id)" $Token) | Out-Null
        if ($first.hasCover) { Check "calibre cover" @(200) (Invoke-Bff GET "/api/calibre/books/$($first.id)/cover" $Token) | Out-Null }
        # 409 = calibre 本地账号未初始化，需 POST /api/calibre/activate
        Check "calibre progress（200 或 409）" @(200, 409) (Invoke-Bff GET "/api/calibre/progress/$($first.id)" $Token) | Out-Null
    }

    Check "artalk comments" @(200) (Invoke-Bff GET "/api/artalk/comments?page_key=/voicebook/smoke" $Token) | Out-Null
    Check "artalk 换票" @(200) (Invoke-Bff POST "/api/auth/token/exchange" $Token @{ component = "artalk" }) | Out-Null
    if ($PostComment) {
        Check "artalk 发评论" @(200, 201) (Invoke-Bff POST "/api/artalk/comments" $Token @{
            pageKey = "/voicebook/smoke"; content = "BFF smoke test $(Get-Date -Format s)"; pageTitle = "BFF smoke"
        }) | Out-Null
    }
    if ($refreshToken) {
        $refreshed = Check "刷新 token" @(200) (Invoke-Bff POST "/api/auth/refresh" $null @{ refreshToken = $refreshToken })
        if ($refreshed.Status -eq 200) {
            $t = $refreshed.Body | ConvertFrom-Json
            $Token = $t.accessToken
            if ($t.refreshToken) { $refreshToken = $t.refreshToken }
        }
        Check "退出登录" @(204) (Invoke-Bff POST "/api/auth/logout" $Token @{ refreshToken = $refreshToken }) | Out-Null
        Check "退出后 token 失效" @(401) (Invoke-Bff GET "/api/auth/me" $Token) | Out-Null
    }
} else {
    Write-Host "未提供账号或 token（-Username/-Password、-Token 或 `$env:BFF_TOKEN），跳过需要登录的检查。" -ForegroundColor Yellow
}

$results | Format-Table -AutoSize -Wrap
$failed = @($results | Where-Object { $_.Result -eq "FAIL" }).Count
if ($failed -gt 0) { Write-Host "$failed 项失败" -ForegroundColor Red; exit 1 }
Write-Host "全部通过" -ForegroundColor Green
