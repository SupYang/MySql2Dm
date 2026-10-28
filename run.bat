@echo off
rem MySQL -^> DM8 migration tool launcher
rem Usage: run.bat [args...]   e.g. run.bat --config config.properties --mode all
chcp 65001 >nul
setlocal
set "DIR=%~dp0"
set "JAR=%DIR%target\mysql2dm.jar"
if not exist "%JAR%" (
    echo [ERROR] %JAR% not found. Build first: mvn package
    exit /b 2
)

rem Locate java: JAVA_HOME -> PATH -> bundled portable JDK
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE (
    where java >nul 2>nul
    if not errorlevel 1 set "JAVA_EXE=java"
)
if not defined JAVA_EXE if exist "D:\work\_tools\jdk-17.0.20.1+1\bin\java.exe" set "JAVA_EXE=D:\work\_tools\jdk-17.0.20.1+1\bin\java.exe"
if not defined JAVA_EXE (
    echo [ERROR] java not found. Install a JDK or set JAVA_HOME.
    exit /b 2
)

"%JAVA_EXE%" -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar "%JAR%" %*
endlocal & exit /b %ERRORLEVEL%
