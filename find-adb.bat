@echo off
setlocal enabledelayedexpansion
REM Resolves the path to adb.exe into the ADB variable.
REM
REM Order of preference:
REM   1. ANDROID_HOME / ANDROID_SDK_ROOT
REM   2. adb.exe already on PATH
REM   3. well-known SDK locations
REM
REM local.properties is deliberately NOT parsed: its sdk.dir uses Java
REM properties escaping ("C:\\Users\\..."), and unescaping that in batch is
REM fragile. Probing the standard locations is simpler and covers every
REM real SDK layout. The paths checked below are the ones Android Studio and
REM the command-line tools actually use.
REM
REM Callers must "call" this script and check for an errorlevel of 1.

set "ADB="

if defined ANDROID_HOME call :probe "%ANDROID_HOME%"
if not defined ADB if defined ANDROID_SDK_ROOT call :probe "%ANDROID_SDK_ROOT%"

if not defined ADB (
    for %%p in (adb.exe) do (
        if not "%%~$PATH:p"=="" set "ADB=%%~$PATH:p"
    )
)

if not defined ADB call :probe "%LOCALAPPDATA%\Android\Sdk"
if not defined ADB call :probe "%USERPROFILE%\AppData\Local\Android\Sdk"
if not defined ADB call :probe "%USERPROFILE%\Android\Sdk"
if not defined ADB call :probe "C:\Android\Sdk"

if not defined ADB (
    echo Could not locate adb.exe.
    echo Set ANDROID_HOME, or add the SDK platform-tools directory to PATH.
    endlocal & set "ADB=" & exit /b 1
)

REM setlocal isolates ADB from the caller, so export it explicitly.
endlocal & set "ADB=%ADB%" & exit /b 0

:probe
if exist "%~1\platform-tools\adb.exe" set "ADB=%~1\platform-tools\adb.exe"
exit /b 0
