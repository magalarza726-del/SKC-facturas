@echo off
chcp 65001 >nul
setlocal EnableExtensions
cd /d "%~dp0"

echo =====================================================
echo   SKC FACTURAS - GENERAR APK DEBUG
echo =====================================================
echo.

call "%~dp0gradlew.bat" --no-daemon clean testDebugUnitTest lintDebug assembleDebug
if errorlevel 1 (
  echo.
  echo [ERROR] Android/Gradle no pudo compilar el proyecto.
  echo Abra el proyecto en Android Studio y revise la ventana Build.
  echo.
  pause
  exit /b 1
)

set "APK=%~dp0app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
  echo [ERROR] No se encontro el APK generado:
  echo %APK%
  pause
  exit /b 2
)

if not exist "%~dp0SALIDA" mkdir "%~dp0SALIDA"
copy /y "%APK%" "%~dp0SALIDA\SKC_Facturas_Piloto.apk" >nul

for /f "tokens=*" %%H in ('powershell.exe -NoProfile -Command "(Get-FileHash -LiteralPath '%~dp0SALIDA\SKC_Facturas_Piloto.apk' -Algorithm SHA256).Hash.ToLowerInvariant()"') do set "APK_SHA=%%H"
>"%~dp0SALIDA\SKC_Facturas_Piloto.apk.sha256" echo %APK_SHA%  SKC_Facturas_Piloto.apk

echo.
echo =====================================================
echo [OK] APK GENERADO
echo =====================================================
echo %~dp0SALIDA\SKC_Facturas_Piloto.apk
echo SHA-256: %APK_SHA%
echo.
pause
exit /b 0
