@echo off
setlocal
cd /d "%~dp0"

set "JAR=target\cinebook-1.0.0.jar"

if not exist "%JAR%" (
    echo CineBook has not been packaged yet.
    echo Run: mvn -DskipTests package
    pause
    exit /b 1
)

if not exist "app.properties" (
    echo app.properties was not found next to run.bat.
    echo Copy app.properties.example to app.properties and fill in jdbc.password.
    pause
    exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
    echo Java 21 or newer was not found on PATH.
    pause
    exit /b 1
)

set "JAVA_VERSION="
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VERSION=%%~v"
if not defined JAVA_VERSION (
    echo CineBook needs Java 21 or newer. Check the java on PATH.
    pause
    exit /b 1
)
for /f "tokens=1 delims=." %%m in ("%JAVA_VERSION%") do set "JAVA_MAJOR=%%m"
if not defined JAVA_MAJOR (
    echo CineBook needs Java 21 or newer. Check the java on PATH.
    pause
    exit /b 1
)
if %JAVA_MAJOR% LSS 21 (
    echo CineBook needs Java 21 or newer. This java is %JAVA_VERSION%.
    pause
    exit /b 1
)

where javaw >nul 2>nul
if errorlevel 1 (
    start "" java -jar "%JAR%"
) else (
    start "" javaw -jar "%JAR%"
)
