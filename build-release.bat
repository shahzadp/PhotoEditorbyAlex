@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

REM ===========================================================================
REM  RELEASE BUILD - obfuscated, shrunk, signed
REM ===========================================================================
REM
REM  Produces app\build\outputs\apk\release\app-release.apk with R8
REM  obfuscation, resource shrinking and a real signature, plus the
REM  mapping file needed to read a release stack trace.
REM
REM  The DEBUG build is untouched by any of this. `build-apk.bat` and
REM  `deploy.bat` still produce a readable APK with full class and method
REM  names, so normal development and debugging are unaffected.
REM
REM  SIGNING
REM  -------
REM  Signing settings are read from keystore.properties next to this
REM  script, which is git-ignored. Copy keystore.properties.example to get
REM  started.
REM
REM  There is no fallback. If signing is not configured the Gradle build
REM  stops with instructions rather than quietly producing a debug-signed
REM  APK - that APK would install and run, so the problem would not surface
REM  until the app could no longer be updated.
REM
REM  USAGE
REM  -----
REM    build-release.bat                 normal release build
REM    build-release.bat --install       also install onto a connected device
REM
REM ===========================================================================

echo ============================================
echo  PHOTO EDITOR BY ALEX - RELEASE BUILD
echo ============================================
echo.

REM --- Check signing is configured before spending minutes on R8 ----------
if not exist "keystore.properties" (
    echo  [signing] keystore.properties not found.
    echo  [signing] The release build will STOP rather than produce a
    echo  [signing] debug-signed APK. See the header of this script.
    echo.
)

echo [1/2] Building and obfuscating with R8...
echo       This takes noticeably longer than a debug build.
echo.

call gradlew.bat assembleRelease --console=plain %*
set EXITCODE=%ERRORLEVEL%

echo.
echo ============================================
if %EXITCODE% neq 0 (
    echo  RELEASE BUILD FAILED - see errors above.
    echo  Lines starting with "e: " are the compiler errors.
    echo  R8 missing-class warnings appear as "Missing class".
    echo ============================================
    exit /b %EXITCODE%
)

set "APK=app\build\outputs\apk\release\app-release.apk"
set "MAP=app\build\outputs\mapping\release\mapping.txt"

if not exist "%APK%" (
    echo  RELEASE BUILD "SUCCEEDED" BUT NO APK WAS PRODUCED.
    echo  Look above for a packaging error.
    echo ============================================
    exit /b 1
)

echo  RELEASE BUILD SUCCESSFUL
echo.
echo  APK:      %APK%
if exist "%APK%" for %%f in ("%APK%") do echo  Size:     %%~zf bytes
if exist "%MAP%" echo  Mapping:  %MAP%
echo.
echo  Confirm the signature is YOUR key, not the debug one:
echo    apksigner verify --print-certs "%APK%"
echo.
echo  Keep release.jks and its passwords backed up somewhere safe. Losing
echo  them means the app can never be updated again - Android treats a new
echo  key as a different app.
echo ============================================

REM --- Optional: install onto a connected device ----------------------------
REM A differently-signed APK will not install over an existing one; that is
REM Android's rule, not a fault here. The fix is an uninstall, which deletes
REM saved projects, so it is never done automatically.
if /i "%~1"=="--install" goto :install
goto :done

:install
echo.
echo === INSTALLING RELEASE APK ===
call "%~dp0find-adb.bat" || goto :done
echo.
echo  NOTE: if this reports INSTALL_FAILED_UPDATE_INCOMPATIBLE, the debug
echo  build is installed with a different signature. Run:
echo    adb uninstall com.alex.photoeditor
echo  That deletes saved projects, so it is not done for you.
"%ADB%" install -r "%APK%"
"%ADB%" shell am start -n com.alex.photoeditor/.MainActivity
echo.
echo  Cutout check: logcat -s TapSelectEngine:I
echo  You want "Segmenter initialised OK", then a mask line per tap.

:done
endlocal