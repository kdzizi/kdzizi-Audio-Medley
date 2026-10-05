@echo off
rem Snapnode server launcher: starts Snapcast server + web console (ports 1704/1705/1780)
rem Usage: start-server.bat [wav_file]
cd /d "%~dp0"

set WAV=%~1
if "%WAV%"=="" set WAV=..\ncm.wav

set NODE_EXE=
for /f "delims=" %%i in ('where node 2^>nul') do (if not defined NODE_EXE set "NODE_EXE=%%i")
if not defined NODE_EXE if exist "C:\Program Files\nodejs\node.exe" set NODE_EXE=C:\Program Files\nodejs\node.exe
if not defined NODE_EXE (
  echo [ERROR] node.exe not found in PATH
  pause
  exit /b 1
)

echo Starting snapnode with "%WAV%" ...
echo   Stream : 1704   Control: 1705   Web console: http://192.168.0.105:1780/
echo Press Ctrl+C to stop.
"%NODE_EXE%" server.js "%WAV%" 1704
pause
