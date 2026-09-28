@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ============================================
echo  OFFLINE PHOTO EDITOR - CLEAN BUILD
echo ============================================
echo.

echo [1/4] Stopping leftover Java processes...
taskkill /F /IM java.exe /T >nul 2>&1
taskkill /F /IM javaw.exe /T >nul 2>&1
timeout /t 3 /nobreak >nul

echo [2/4] Clearing stale caches, locks and previous outputs...
taskkill /F /IM java.exe /T >nul 2>&1
timeout /t 2 /nobreak >nul
del /q "java_pid*.hprof" >nul 2>&1
if exist ".gradle" rd /s /q ".gradle" >nul 2>&1
if exist "build" rd /s /q "build" >nul 2>&1
if exist "app\build" rd /s /q "app\build" >nul 2>&1
if exist ".kotlin" rd /s /q ".kotlin" >nul 2>&1
timeout /t 1 /nobreak >nul

echo [3/4] Compiling - this takes several minutes on a clean cache...
echo.
call gradlew.bat assembleDebug --console=plain
set EXITCODE=%ERRORLEVEL%

echo.
echo ============================================
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    echo  BUILD SUCCESSFUL
    echo  APK: app\build\outputs\apk\debug\app-debug.apk
    for %%f in ("app\build\outputs\apk\debug\app-debug.apk") do echo  Size: %%~zf bytes
    echo ============================================
) else (
    echo  BUILD FAILED - see errors above
    echo  Lines starting with "e: " are the compiler errors.
    echo ============================================
)

endlocal
exit /b %EXITCODE%
