@echo off
setlocal enabledelayedexpansion
REM Shows app PID, memory usage and device SDK levels.
REM adb is located by find-adb.bat, so no machine-specific paths.

call "%~dp0find-adb.bat" || exit /b 1

echo === PROCESS ===
"%ADB%" shell pidof com.alex.photoeditor
echo.
echo === MEMORY (KB) ===
"%ADB%" shell dumpsys meminfo com.alex.photoeditor 2>nul | findstr /I "TOTAL Pss Native Heap"
echo.
echo === SDK / DEVICE ===
"%ADB%" shell getprop ro.build.version.release
"%ADB%" shell getprop ro.build.version.sdk
