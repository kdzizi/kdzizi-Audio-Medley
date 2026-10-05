@echo off
rem PC snapclient launcher (JBL Bluetooth needs ~200ms latency compensation)
rem Usage: start-pc-client.bat [latency_ms]
cd /d "%~dp0..\snapclient"

set LAT=%~1
if "%LAT%"=="" set LAT=200

echo Starting snapclient tcp://192.168.0.105:1704 with latency %LAT% ms ...
snapclient.exe tcp://192.168.0.105:1704 --latency %LAT%
pause
