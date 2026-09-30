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
REM  With no keystore configured the APK falls back to the debug key so a
REM  fresh clone can still produce a release build. That is a convenience
REM  only - the debug key is public, so such an APK can be replaced by
REM  anyone and still install over this app. For a real release, either:
REM
REM    * pass -D arguments on the command line, or
REM    * put the four values in gradle.properties (git-ignored), or
REM    * export them in RELEASE_ENV.bat (git-ignored, never committed).
REM
REM  USAGE
REM  -----
REM    build-release.bat                 normal release build
REM    build-release.bat -DRELEASE_STORE_FILE=...
REM
REM ===========================================================================

echo ============================================
echo  PHOTO EDITOR BY ALEX - RELEASE BUILD
echo ============================================
echo.

REM --- Pick up a git-ignored keystore config if one exists -------------------
if exist "RELEASE_ENV.bat" (
    echo  Reading RELEASE_ENV.bat for signing settings...
    call "RELEASE_ENV.bat"
    echo.
)

if "%RELEASE_STORE_FILE%"=="" (
    echo  [signing] No RELEASE_STORE_FILE set.
    echo  [signing] Falling back to the DEBUG key - the APK WILL work but is
    echo  [signing] not meaningfully signed. See the header of this script.
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
echo  Verify the signature before distributing:
echo    apksigner verify --print-certs "%APK%"
echo.
echo  If that shows the debug certificate, it was NOT signed with your key.
echo ============================================

REM --- Optional: install onto a connected device ----------------------------
REM Kept separate from the build on purpose. The release APK is signed with a
REM different key from the debug one whenever a real keystore is configured,
REM and Android refuses to install one over the other - "signature mismatch".
REM Installing both therefore needs a uninstall first, which would delete the
REM user's saved projects, so it is never done automatically.
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