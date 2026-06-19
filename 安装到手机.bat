@echo off
echo ========================================
echo  AI Companion - 从头构建安装
echo ========================================
echo.

set ADB=F:\Android\SDK\platform-tools\adb.exe
set APK=F:\Trae AI\ai-APP\android\app\build\outputs\apk\debug\app-debug.apk

echo [1] 检查 APK 是否存在...
if not exist "%APK%" (
    echo [错误] APK 文件不存在！
    pause
    exit /b 1
)
echo [OK] APK 已找到
echo.

echo [2] 检查设备连接...
%ADB% devices
echo.

echo [3] 卸载旧版本...
%ADB% uninstall com.aicompanion.app
echo.

echo [4] 安装新版本...
%ADB% install -r "%APK%"
echo.

echo [5] 清除旧数据...
%ADB% shell "run-as com.aicompanion.app rm -f files/crash_logs/*.log" 2>nul
echo.

echo ========================================
echo  安装完成！请打开手机上的 APP 验证
echo  1. 主界面是否正常初始化
echo  2. 点击设置是否闪退
echo ========================================
pause