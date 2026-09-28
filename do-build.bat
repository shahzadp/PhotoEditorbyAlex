@echo off
REM Quick incremental build. For a full clean build use build-apk.bat.
cd /d "%~dp0"
call gradlew.bat assembleDebug --console=plain
echo.
echo ===EXITCODE=%ERRORLEVEL%===
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    echo ===APK READY===
) else (
    echo ===NO APK===
)
