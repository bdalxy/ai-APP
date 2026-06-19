@echo off
echo ========================================
echo  AI Companion - 从头构建并安装
echo ========================================
echo.

set ADB=F:\Android\SDK\platform-tools\adb.exe
set GRADLE=F:\Trae AI\ai-APP\android\gradlew.bat
set APK=F:\Trae AI\ai-APP\android\app\build\outputs\apk\debug\app-debug.apk
set BUILD_DIR=F:\Trae AI\ai-APP\android\app\build

echo [1] 清理旧构建缓存...
if exist "%BUILD_DIR%" (
    rmdir /s /q "%BUILD_DIR%"
    echo [OK] 已清理 build 目录
) else (
    echo [OK] build 目录不存在，跳过
)
echo.

echo [2] 从源码重新构建 APK（这可能需要 2-3 分钟）...
cd /d F:\Trae AI\ai-APP\android
call "%GRADLE%" assembleDebug --no-daemon --no-build-cache
if %errorlevel% neq 0 (
    echo [错误] 构建失败！
    pause
    exit /b 1
)
echo [OK] 构建成功
echo.

echo [3] 检查设备连接...
%ADB% devices
echo.

echo [4] 卸载旧版本...
%ADB% uninstall com.aicompanion.app
echo.

echo [5] 安装新版本...
%ADB% install -r "%APK%"
echo.

echo [6] 清除旧崩溃日志...
%ADB% shell "run-as com.aicompanion.app rm -f files/crash_logs/*.log" 2>nul
echo.

echo ========================================
echo  安装完成！请打开手机上的 APP 验证
echo  1. 主界面是否正常初始化
echo  2. 点击设置是否闪退
echo  3. 对话功能是否正常
echo ========================================
pause