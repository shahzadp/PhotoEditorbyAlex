REM ===========================================================================
REM  Verifies the cutout / MediaPipe path on a RELEASE build.
REM ===========================================================================
REM
REM  The cutout is the one feature that fails silently: TapSelectEngine
REM  catches every throwable and returns null, so a broken release build
REM  shows "nothing selected" rather than a crash. This script installs the
REM  obfuscated APK and then dumps the engine's own log, which is the only
REM  place the real cause appears.
REM
REM  USAGE
REM  -----
REM    verify-cutout.bat            install (keeping app data) and verify
REM    verify-cutout.bat --clean    uninstall first, DELETING SAVED PROJECTS
REM
REM  App data is preserved by default. The release and debug builds are signed
REM  with different keys whenever a real keystore is configured, and Android
REM  refuses to install one over the other - but that is a situation to be told
REM  about, not one to resolve by silently deleting the user's work. Pass
REM  --clean deliberately if you actually want a fresh install.
REM ===========================================================================

setlocal enabledelayedexpansion
cd /d "%~dp0"

call "%~dp0find-adb.bat" || exit /b 1

set "CLEAN=0"
if /i "%~1"=="--clean" set "CLEAN=1"

set "APK=app\build\outputs\apk\release\app-release.apk"
if not exist "%APK%" (
    echo Release APK not found: %APK%
    echo Run build-release.bat first.
    exit /b 1
)

if "%CLEAN%"=="1" (
    echo === UNINSTALLING - saved projects WILL be deleted ===
    "%ADB%" uninstall com.alex.photoeditor
) else (
    echo === INSTALLING OVER THE EXISTING BUILD ===
)

echo.
echo === INSTALLING RELEASE APK ===
"%ADB%" install -r "%APK%"

echo.
echo === LAUNCHING ===
"%ADB%" logcat -c
"%ADB%" shell am start -n com.alex.photoeditor/.MainActivity

echo.
echo ============================================
echo  Installed. Now, on the phone:
echo.
echo    1. Open a photo with a clear subject
echo    2. Tap the Cutout tool
echo    3. Tap the object you want
echo
echo  Then press any key here to read the log.
echo ============================================
echo.

set /p "_=Press any key to dump the log..."

echo.
echo === TapSelectEngine LOG ===
echo (empty here means the engine never ran - check you tapped the object)
"%ADB%" logcat -d -s TapSelectEngine:V

echo.
echo === CRASHES ===
"%ADB%" logcat -d -t 300 AndroidRuntime:E *:S

echo.
echo === HOW TO READ THIS ===
echo   "Segmenter initialised OK"       model loaded, cutout should work
echo   "mask WxH min=.. above50%=..%%"  a tap produced a real mask
echo   "Failed to initialise..."        model could not load - cause is
echo                                    printed above that line
echo   nothing at all                   engine never reached initialise

endlocal