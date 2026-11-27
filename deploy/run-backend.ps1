# 在 Windows 本机直接起后端（无 Docker）：与 deploy/run-backend.sh 做法相同，读 deploy\.env，连本机 MySQL 8.4，用 JDK 17 运行打好的 jar。
#   powershell -ExecutionPolicy Bypass -File deploy\run-backend.ps1
#   （或双击 deploy\run-backend.cmd）
# 第一次先 copy deploy\.env.example deploy\.env 并按说明改好；没有 .env 时全部用默认值
# （本机 127.0.0.1:3306 的 egg 库，账号 egg/egg，模拟好生意，带演示数据）。
# 还没打包、或拉了新代码（backend\src、pom.xml 比 jar 新）时自动重新打包（跳过测试）。
# 本文件存为带 BOM 的 UTF-8，Windows PowerShell 5.1 才能正确读中文。
# 验证级别：未在 Windows 实机运行过（开发方只有 Linux 环境）。
$ErrorActionPreference = 'Stop'

$here = $PSScriptRoot
$root = Split-Path -Parent $here
$envFile = Join-Path $here '.env'

if (Test-Path $envFile) {
  # 代替 bash 的 set -a; . .env：逐行读 KEY=VALUE，跳过空行和 # 注释，按 UTF-8 读（管理员姓名可能是中文）
  foreach ($line in Get-Content -LiteralPath $envFile -Encoding UTF8) {
    $t = $line.Trim()
    if ($t -eq '' -or $t.StartsWith('#')) { continue }
    $i = $t.IndexOf('=')
    if ($i -lt 1) { continue }
    $name = $t.Substring(0, $i).Trim()
    $value = $t.Substring($i + 1).Trim()
    if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
      $value = $value.Substring(1, $value.Length - 2)
    }
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
  }
  # 与 run-backend.sh 一致：有 .env 但没写这一项时按正式部署处理，不写演示数据
  if (-not $env:EGG_SEED_DEMO) { $env:EGG_SEED_DEMO = 'false' }
}

# 后端只认 EGG_DB_URL；没直接给时用 .env 里的主机、端口、库名拼出来
if (-not $env:EGG_DB_URL) {
  $dbHost = if ($env:EGG_DB_HOST) { $env:EGG_DB_HOST } else { '127.0.0.1' }
  $dbPort = if ($env:EGG_DB_PORT) { $env:EGG_DB_PORT } else { '3306' }
  $dbName = if ($env:EGG_DB_NAME) { $env:EGG_DB_NAME } else { 'egg' }
  $env:EGG_DB_URL = "jdbc:mysql://${dbHost}:${dbPort}/${dbName}?characterEncoding=utf8&connectionTimeZone=Asia/Shanghai"
}
$env:TZ = 'Asia/Shanghai'

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }

function Find-Jar {
  Get-ChildItem -Path (Join-Path $root 'backend\target') -Filter 'egg-backend-*.jar' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1
}

# 没打包过，或 backend\src、pom.xml 比 jar 新（拉了新代码）时，重新打包（跳过测试）
$jar = Find-Jar
$stale = $false
if ($jar) {
  $newest = Get-ChildItem -Path (Join-Path $root 'backend\src'), (Join-Path $root 'backend\pom.xml') -Recurse -File |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  $stale = $newest -and $newest.LastWriteTime -gt $jar.LastWriteTime
}
if (-not $jar -or $stale) {
  if ($stale) { Write-Host '代码比打好的后端新，重新打包（mvnw.cmd -DskipTests package）…' }
  else { Write-Host '还没有打包好的后端，先打包一次（mvnw.cmd -DskipTests package）…' }
  Push-Location (Join-Path $root 'backend')
  try {
    & .\mvnw.cmd -B -q -DskipTests clean package
    if ($LASTEXITCODE -ne 0) { throw "打包失败（退出码 $LASTEXITCODE）" }
  } finally { Pop-Location }
  $jar = Find-Jar
  if (-not $jar) { throw '打包后仍找不到 backend\target\egg-backend-*.jar' }
}

& $java '-Duser.timezone=Asia/Shanghai' -jar $jar.FullName
exit $LASTEXITCODE
