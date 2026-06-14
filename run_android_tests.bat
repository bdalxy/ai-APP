@echo off
chcp 65001 >nul
echo ============================================
echo   Android UI 测试执行脚本 (Espresso + JUnit)
echo ============================================
echo.
echo 前提条件：
echo   1. 已连接 Android 设备或启动模拟器
echo   2. 设备已解锁屏幕
echo   3. 已安装被测 APK
echo.
echo 正在切换到 android 项目目录...
cd /d "%~dp0android"

echo.
echo 正在执行 androidTest...
echo 目标包名: com.aicompanion.app
echo.

call gradlew.bat connectedAndroidTest

echo.
echo ============================================
echo  测试执行完毕，请查看上方结果。
echo  通过: BUILD SUCCESSFUL
echo  失败: BUILD FAILED (请查看具体 test 报告)
echo ============================================
pause