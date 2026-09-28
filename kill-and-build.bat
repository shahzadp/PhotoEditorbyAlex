@echo off
echo Killing Gradle/Java processes...
taskkill /F /IM java.exe /T 2>nul
taskkill /F /IM javaw.exe /T 2>nul
timeout /t 2 /nobreak >nul

cd /d "%~dp0"
if exist java_pid8412.hprof del /q java_pid8412.hprof

echo.
echo === Building ===
call gradlew.bat assembleDebug --console=plain --no-daemon
echo.
echo ===EXITCODE=%ERRORLEVEL%===
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    echo ===APK READY===
    for %%f in ("app\build\outputs\apk\debug\app-debug.apk") do echo %%~zf bytes at %%~dpf
) else (
    echo ===NO APK===
)
