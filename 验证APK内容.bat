@echo off
set APK=F:\Trae AI\ai-APP\android\app\build\outputs\apk\debug\app-debug.apk
set OUT=F:\Trae AI\ai-APP\apk_verify

if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"

echo 正在解压 APK 中的 Python 文件...
powershell -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; [System.IO.Compression.ZipFile]::ExtractToDirectory('%APK%', '%OUT%')"

echo ========================================
echo card_parser.py 前15行:
echo ========================================
type "%OUT%\chaquopy\src\chat_engine\card_parser.py" 2>nul
if errorlevel 1 (
    echo [文件不存在于 chaquopy/src/chat_engine/]
    dir /s /b "%OUT%\*card_parser*" 2>nul
)

echo.
echo ========================================
echo 检查是否有 Kotlin 代码混入 Python:
echo ========================================
findstr /M "EncryptedSharedPreferences" "%OUT%\chaquopy\*.py" "%OUT%\chaquopy\*\*.py" "%OUT%\chaquopy\*\*\*.py" 2>nul
if errorlevel 1 echo [未发现 Kotlin 代码混入]

echo.
echo ========================================
echo APK 构建时间:
echo ========================================
dir "%APK%" | findstr app-debug

pause