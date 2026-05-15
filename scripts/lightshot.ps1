# lightshot.ps1 - Lightshot image management script
param(
    [Parameter(Position=0)][string]$Command = '',
    [Parameter(Position=1)][string]$LocalDir = ''
)
$DEVICE_DIR = '/storage/emulated/0/lightshot'
$DEFAULT_LOCAL = 'lightshot_samples'
$PROJECT_ROOT = Split-Path -Parent $PSScriptRoot
function Info($m) { Write-Host "[lightshot] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "[lightshot] $m" -ForegroundColor Yellow }
function Err($m)  { Write-Host "[lightshot] $m" -ForegroundColor Red }
function Check-Adb {
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { Err 'adb not found'; exit 1 }
    $d = adb devices 2>$null | Select-String 'device$'
    if ($null -eq $d -or $d.Count -eq 0) { Err 'No device connected'; exit 1 }
}
function Test-DeviceDir {
    adb shell "test -d '$DEVICE_DIR'" 2>$null
    return ($LASTEXITCODE -eq 0)
}
function Get-ImageCount {
    $r = adb shell "ls '$DEVICE_DIR'/*.jpg 2>/dev/null | wc -l" 2>$null
    if ($null -eq $r) { return 0 }
    $n = $r.Trim() -replace '\D',''
    if ($n -eq '') { return 0 }
    return [int]$n
}
function Cmd-Pull([string]$Dir) {
    $ld = if ($Dir -ne '') { $Dir } else { $DEFAULT_LOCAL }
    if (-not [IO.Path]::IsPathRooted($ld)) { $ld = Join-Path $PROJECT_ROOT $ld }
    Info "target device : $DEVICE_DIR"
    Info "local save dir: $ld"
    if (-not (Test-DeviceDir)) { Warn '设备目录不存在，还没拍摄过图片'; return }
    $cnt = Get-ImageCount
    if ($cnt -eq 0) { Warn '设备上暂无闪拍图片'; return }
    Info "发现 $cnt 张图片，开始抽取..."
    New-Item -ItemType Directory -Force -Path $ld | Out-Null
    adb pull "$DEVICE_DIR/" "$ld\"
    $pulled = (Get-ChildItem -Path $ld -Filter '*.jpg' -File -EA SilentlyContinue).Count
    Info "完成，共 $pulled 张图片保存至：$ld"
}
function Cmd-Delete {
    if (-not (Test-DeviceDir)) { Warn '设备目录不存在，无需删除'; return }
    $cnt = Get-ImageCount
    if ($cnt -eq 0) { Warn '设备上暂无闪拍图片'; return }
    Warn "即将删除设备上 $cnt 张闪拍图片"
    $ok = Read-Host '确认删除? [y/N]'
    if ($ok -match '^[Yy]$') { adb shell "rm -f '$DEVICE_DIR'/*.jpg"; Info '已删除' }
    else { Info '已取消' }
}
function Cmd-PullDelete([string]$Dir) {
    Cmd-Pull -Dir $Dir
    Write-Host ''
    Info '清理设备图片...'
    if (Test-DeviceDir) { adb shell "rm -f '$DEVICE_DIR'/*.jpg"; Info '设备图片已清除' }
}
function Show-Usage {
    Write-Host '用法: .\scripts\lightshot.ps1 <命令> [本地目录]' -ForegroundColor Cyan
    Write-Host ''
    Write-Host '命令:'
    Write-Host '  pull          抽取图片到本地 (默认: lightshot_samples\)'
    Write-Host '  delete        删除设备上的闪拍图片 (需确认)'
    Write-Host '  pull-delete   抽取后自动删除设备图片'
    Write-Host ''
    Write-Host '示例:'
    Write-Host '  .\scripts\lightshot.ps1 pull'
    Write-Host '  .\scripts\lightshot.ps1 pull my_dataset\batch1'
    Write-Host '  .\scripts\lightshot.ps1 delete'
    Write-Host '  .\scripts\lightshot.ps1 pull-delete'
}
if ($Command -in @('','--help','-h')) { Show-Usage; exit 0 }
Check-Adb
switch ($Command) {
    'pull'        { Cmd-Pull -Dir $LocalDir }
    'delete'      { Cmd-Delete }
    'pull-delete' { Cmd-PullDelete -Dir $LocalDir }
    default { Err "未知命令: $Command"; Write-Host ''; Show-Usage; exit 1 }
}
