# ============================================================
# VITS AISHELL-3 中文 TTS 模型下载脚本
# ============================================================
# 用途：从 Sherpa-ONNX 官方仓库下载 VITS 中文语音合成模型
# 使用方法：在项目根目录下运行此脚本
#   powershell -ExecutionPolicy Bypass -File scripts/download_vits_model.ps1
# ============================================================

$ErrorActionPreference = "Stop"

# ── 配置 ──
$AssetsDir = "android\app\src\main\assets"
$VitsDir = "$AssetsDir\vits-aishell3"
$DownloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-aishell3.tar.bz2"
$TempArchive = "$env:TEMP\vits-aishell3.tar.bz2"
$TempExtract = "$env:TEMP\vits-aishell3_extract"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  VITS AISHELL-3 中文 TTS 模型下载工具" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

# ── 检查目标目录 ──
if (-not (Test-Path $AssetsDir)) {
    Write-Error "错误：找不到 assets 目录：$AssetsDir"
    Write-Host "请确保在项目根目录（ai-APP）下运行此脚本。" -ForegroundColor Yellow
    exit 1
}

# ── 创建目标目录 ──
if (-not (Test-Path $VitsDir)) {
    New-Item -ItemType Directory -Path $VitsDir -Force | Out-Null
    Write-Host "[OK] 创建目录：$VitsDir" -ForegroundColor Green
}

# ── 检查 7-Zip（用于解压 .tar.bz2）──
$SevenZip = "C:\Program Files\7-Zip\7z.exe"
$HasSevenZip = Test-Path $SevenZip
if (-not $HasSevenZip) {
    # 尝试使用 tar 命令（Windows 10 1803+ 内置）
    $tarAvailable = Get-Command tar -ErrorAction SilentlyContinue
    if (-not $tarAvailable) {
        Write-Error "错误：需要 7-Zip 或 tar 命令来解压 .tar.bz2 文件。"
        Write-Host "请安装 7-Zip（https://7-zip.org/）或使用 Windows 10 1803+ 系统。" -ForegroundColor Yellow
        exit 1
    }
    Write-Host "[INFO] 使用 Windows 内置 tar 命令解压" -ForegroundColor Yellow
}

# ── 下载模型 ──
Write-Host ""
Write-Host "[1/3] 正在下载 VITS 模型（约 116MB）..." -ForegroundColor Yellow
Write-Host "  下载地址：$DownloadUrl" -ForegroundColor Gray

try {
    # 使用 .NET WebClient 下载（比 Invoke-WebRequest 更快）
    $webClient = New-Object System.Net.WebClient
    $webClient.DownloadFile($DownloadUrl, $TempArchive)
    $webClient.Dispose()
    
    $fileSize = (Get-Item $TempArchive).Length / 1MB
    Write-Host "[OK] 下载完成，文件大小：$([math]::Round($fileSize, 1)) MB" -ForegroundColor Green
} catch {
    Write-Error "下载失败：$($_.Exception.Message)"
    Write-Host ""
    Write-Host "备选方案：" -ForegroundColor Yellow
    Write-Host "  1. 手动下载：浏览器打开 $DownloadUrl" -ForegroundColor Gray
    Write-Host "  2. 解压后将 vits-aishell3/ 目录下的所有文件复制到：" -ForegroundColor Gray
    Write-Host "     $VitsDir" -ForegroundColor Gray
    Write-Host "  3. 重新运行此脚本验证" -ForegroundColor Gray
    exit 1
}

# ── 解压模型 ──
Write-Host ""
Write-Host "[2/3] 正在解压模型文件..." -ForegroundColor Yellow

# 清理临时解压目录
if (Test-Path $TempExtract) {
    Remove-Item -Recurse -Force $TempExtract
}
New-Item -ItemType Directory -Path $TempExtract -Force | Out-Null

try {
    if ($HasSevenZip) {
        # 使用 7-Zip 两步解压：.tar.bz2 -> .tar -> 文件
        & $SevenZip x $TempArchive -o"$TempExtract" -y | Out-Null
        $tarFile = Get-ChildItem $TempExtract -Filter "*.tar" | Select-Object -First 1
        if ($tarFile) {
            & $SevenZip x $tarFile.FullName -o"$TempExtract" -y | Out-Null
        }
    } else {
        # 使用 Windows 内置 tar
        tar -xjf $TempArchive -C $TempExtract
    }
    Write-Host "[OK] 解压完成" -ForegroundColor Green
} catch {
    Write-Error "解压失败：$($_.Exception.Message)"
    exit 1
}

# ── 复制模型文件到 assets ──
Write-Host ""
Write-Host "[3/3] 正在复制模型文件到 assets 目录..." -ForegroundColor Yellow

# 查找解压后的 vits-aishell3 目录
$extractedVitsDir = Get-ChildItem $TempExtract -Directory -Filter "vits-aishell3*" -Recurse | Select-Object -First 1
if (-not $extractedVitsDir) {
    # 可能直接在 TempExtract 下
    $extractedVitsDir = $TempExtract
}

# 复制所有文件（保留 README.md 不动）
$copiedCount = 0
$totalSize = 0
Get-ChildItem $extractedVitsDir -File -Recurse | ForEach-Object {
    $relativePath = $_.FullName.Substring($extractedVitsDir.FullName.Length + 1)
    $destPath = Join-Path $VitsDir $relativePath
    $destDir = Split-Path $destPath -Parent
    if (-not (Test-Path $destDir)) {
        New-Item -ItemType Directory -Path $destDir -Force | Out-Null
    }
    Copy-Item $_.FullName -Destination $destPath -Force
    $copiedCount++
    $totalSize += $_.Length
}
Write-Host "[OK] 已复制 $copiedCount 个文件，总计 $([math]::Round($totalSize / 1MB, 1)) MB" -ForegroundColor Green

# ── 清理临时文件 ──
Remove-Item -Recurse -Force $TempExtract -ErrorAction SilentlyContinue
Remove-Item $TempArchive -ErrorAction SilentlyContinue

# ── 验证关键文件 ──
Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  验证模型文件" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

$requiredFiles = @(
    @{Path = "$VitsDir\model.onnx"; Name = "VITS 主模型"}
    @{Path = "$VitsDir\tokens.txt"; Name = "音素映射表"}
    @{Path = "$VitsDir\lexicon.txt"; Name = "发音词典"}
)

$allOk = $true
foreach ($file in $requiredFiles) {
    if (Test-Path $file.Path) {
        $size = (Get-Item $file.Path).Length / 1MB
        Write-Host "  [OK] $($file.Name)：$([math]::Round($size, 1)) MB" -ForegroundColor Green
    } else {
        Write-Host "  [MISSING] $($file.Name)：文件不存在" -ForegroundColor Red
        $allOk = $false
    }
}

Write-Host ""
if ($allOk) {
    Write-Host "============================================================" -ForegroundColor Green
    Write-Host "  模型下载并部署成功！" -ForegroundColor Green
    Write-Host "============================================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "下一步：" -ForegroundColor Yellow
    Write-Host "  1. 在 Android Studio 中执行 Build -> Clean Project" -ForegroundColor Gray
    Write-Host "  2. 执行 Build -> Rebuild Project" -ForegroundColor Gray
    Write-Host "  3. 运行 APP，进入「设置 -> 语音设置」" -ForegroundColor Gray
    Write-Host "  4. 确认 TTS 模型类型显示为「VITS (aishell3)」" -ForegroundColor Gray
    Write-Host ""
    Write-Host "注意：首次启动时 TTS 引擎需要加载模型（约 1-2 秒），请耐心等待。" -ForegroundColor Yellow
} else {
    Write-Host "============================================================" -ForegroundColor Red
    Write-Host "  部分文件缺失，请检查下载是否完整。" -ForegroundColor Red
    Write-Host "============================================================" -ForegroundColor Red
    Write-Host ""
    Write-Host "手动下载地址：" -ForegroundColor Yellow
    Write-Host "  $DownloadUrl" -ForegroundColor Gray
}

# ── 暂停以查看结果 ──
Write-Host ""
Write-Host "按任意键退出..." -ForegroundColor Gray
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")