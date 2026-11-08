@echo off
setlocal
for %%I in ("%~dp0..") do set "ROOT=%%~sI"
set "JAVA_HOME=%ROOT%\.tools\jdk-17.0.21.0.2+1"
set "GRADLE_USER_HOME=%ROOT%\.tools\gradle-user-home"
call "%~dp0gradlew.bat" -p "%~dp0." assembleDebug writeLocalTestClasspath lintDebug --console=plain
if errorlevel 1 exit /b %errorlevel%
powershell.exe -NoProfile -File "%~dp0test-local.ps1"
exit /b %errorlevel%
