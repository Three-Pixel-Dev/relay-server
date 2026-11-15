@echo off
setlocal EnableExtensions DisableDelayedExpansion

:: Ensure working directory is the relay-server folder
cd /d "%~dp0"

echo ===================================================
echo   Starting Relay Server
echo ===================================================

:: Ensure JAVA_HOME points to JDK 21+ if not already set or invalid
if not defined JAVA_HOME (
    if exist "C:\Program Files\Java\jdk-21" (
        set "JAVA_HOME=C:\Program Files\Java\jdk-21"
    )
)

:: Locate .env file (check local directory first, then parent directory)
set "ENV_FILE="
if exist "%~dp0.env" (
    set "ENV_FILE=%~dp0.env"
) else if exist "%~dp0..\.env" (
    set "ENV_FILE=%~dp0..\.env"
)

:: Load environment variables from .env if found
if defined ENV_FILE (
    echo [run.bat] Loading environment variables from: %ENV_FILE%
    for /f "usebackq eol=# tokens=1* delims==" %%A in ("%ENV_FILE%") do (
        if not "%%A"=="" (
            set "%%A=%%~B"
        )
    )
    echo [run.bat] Environment variables loaded successfully.
) else (
    echo [run.bat] WARNING: No .env file found.
    echo [run.bat] Please ensure database credentials are configured in .env.
)

:: Determine server port (defaults to 8080 if not set)
if not defined SERVER_PORT (
    set "SERVER_PORT=8080"
)

:: Check if port is in use and stop existing process if necessary
echo [run.bat] Checking port %SERVER_PORT% availability...
set "PORT_OCCUPIED=0"
for /f "tokens=5" %%P in ('netstat -ano -p tcp ^| findstr /R /C:":%SERVER_PORT%  *.*LISTENING"') do (
    if not "%%P"=="0" if not "%%P"=="" (
        set "PORT_OCCUPIED=1"
        echo [run.bat] Port %SERVER_PORT% is in use by PID %%P. Stopping process...
        taskkill /F /PID %%P >nul 2>&1
    )
)

if "%PORT_OCCUPIED%"=="1" (
    :: Wait 1 second for socket release
    ping -n 2 127.0.0.1 >nul 2>&1
    echo [run.bat] Port %SERVER_PORT% was freed successfully.
) else (
    echo [run.bat] Port %SERVER_PORT% is free.
)

echo [run.bat] Launching Spring Boot application on port %SERVER_PORT%...
echo ===================================================

call "%~dp0mvnw.cmd" spring-boot:run %*
set "EXIT_CODE=%ERRORLEVEL%"

exit /b %EXIT_CODE%
