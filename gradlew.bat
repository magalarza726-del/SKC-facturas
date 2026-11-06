@echo off
setlocal EnableExtensions
set "APP_HOME=%~dp0"
set "WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"

if not exist "%WRAPPER_JAR%" (
  echo [ERROR] Falta gradle\wrapper\gradle-wrapper.jar
  echo Vuelva a extraer el ZIP completo del proyecto.
  exit /b 2
)

set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_EXE=C:\Program Files\Android\Android Studio\jbr\bin\java.exe"
if not defined JAVA_EXE (
  where java.exe >nul 2>nul
  if not errorlevel 1 set "JAVA_EXE=java.exe"
)

if not defined JAVA_EXE (
  echo [ERROR] No se encontro Java 17+ ni el JBR de Android Studio.
  exit /b 3
)

"%JAVA_EXE%" -jar "%WRAPPER_JAR%" %*
exit /b %ERRORLEVEL%
