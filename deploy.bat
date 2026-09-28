@echo off
setlocal enabledelayedexpansion
REM Installs the debug APK onto a connected device and launches it.
REM adb is located by find-adb.bat, so no machine-specific paths here.

call "%~dp0find-adb.bat" || exit /b 1

set "APK=%~dp0app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
    echo APK not found: %APK%
    echo Run build-apk.bat first.
    exit /b 1
)

echo === CLEARING OLD CRASH LOG ===
"%ADB%" logcat -c

echo.
echo === INSTALLING ===
"%ADB%" install -r %APK%
echo.
echo === LAUNCHING ===
"%ADB%" shell am start -n com.alex.photoeditor/.MainActivity
echo.
echo === CRASH CHECK (after 6s) ===
timeout /t 6 /nobreak >nul
"%ADB%" logcat -d AndroidRuntime:E *:S
echo.
echo === DONE - if you saw no crash above, test cropping now ===
