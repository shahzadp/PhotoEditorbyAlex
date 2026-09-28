@echo off
setlocal enabledelayedexpansion
REM Dumps recent AndroidRuntime crashes. adb is located by find-adb.bat.

call "%~dp0find-adb.bat" || exit /b 1

echo === DEVICES ===
"%ADB%" devices -l
echo.
echo === CRASHES ===
"%ADB%" logcat -d -t 500 AndroidRuntime:E *:S
