# Compatibilidad de actualizaciones APK de SKC Facturas

## ¿Por qué Android indica "Conflicto con el paquete"?

El proyecto original v3.15.4 distribuía `SKC_Facturas_Piloto.apk` mediante
`GENERAR_APK.cmd`, que ejecuta `assembleDebug`. El identificador de ese APK
es **`com.skcingenieria.facturas.pilot`**.

Para instalar una actualización encima de esa app, Android exige:

1. **El mismo identificador de paquete** (se conserva `.pilot`).
2. **La misma clave de firma** (certificado original).
3. Un `versionCode` superior (la revisión compatible usa `31507` y se presenta como v1.5.1).

Hasta ahora, GitHub Actions compilaba `assembleDebug` en runners nuevos. Android
generaba una nueva clave de depuración en cada máquina temporal. Por tanto,
esas APK podían instalarse por separado, pero **no actualizar la app previamente
instalada**. La fuente ZIP del usuario no contiene ninguna clave de firma ni APK.

## Cómo reutilizar la firma ORIGINAL

**No desinstales la app antigua si necesitas conservar sus datos.**

Si generaste la APK original con Android Studio o `GENERAR_APK.cmd`
en un ordenador Windows, localiza su clave original en:

`%USERPROFILE%\.android\debug.keystore`

Puedes confirmar la ruta desde Android Studio → Gradle → app → Tasks → android →
`signingReport`. Debe ser el almacén del **mismo ordenador** usado para
compilar la APK instalada.

1. Abre PowerShell en el ordenador original y ejecuta lo siguiente:
   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore")) | Set-Clipboard
   ```
   Esto copia el archivo a Base64 en el portapapeles. **No lo publiques**.
2. Abre los secretos del repositorio en
   https://github.com/magalarza726-del/SKC-facturas/settings/secrets/actions .
3. Crea un **Repository secret** llamado
   `SKC_SIGNING_KEYSTORE_BASE64` y pega el contenido del portapapeles.
4. Si se trata del keystore debug predeterminado de Android Studio, el flujo
   utiliza `android` como contraseña, `androiddebugkey` como alias y
   `android` como contraseña de clave. Si usaste claves personalizadas,
   crea también los secretos
   `SKC_SIGNING_STORE_PASSWORD`, `SKC_SIGNING_KEY_ALIAS`,
   `SKC_SIGNING_KEY_PASSWORD`.
5. Desde GitHub → **Actions** → **Compilar y publicar APK** →
   **Run workflow**, ejecuta una compilación nueva.

El flujo **rechaza** la publicación sin este secreto. El almacén se crea solo en
el directorio temporal del runner, se firma la APK y se verifica con
`apksigner` antes de crear la Release. No se debe añadir ningún
`.keystore`, `.jks` ni contraseña al repositorio público.

## Si tu APK antiguo era de tipo release, no piloto

Una APK release normalmente usa
`com.skcingenieria.facturas` (sin `.pilot`) y otra clave de firma.
En ese caso, este flujo debug no es el indicado: habría que preparar una
compilación release con el **almacén de firmas release original**.
Compara el identificador del APK instalado antes de proceder.

## ¿Y si perdiste la clave?

No se puede recuperar una clave privada de firma a partir de la fuente ZIP
o de un APK firmado. Sin la clave original, Android no aceptará una actualización
que conserve los datos privados de la instalación antigua.

Antes de cambiar de instalación, exporta tus respaldos desde la app antigua
y comprueba que realmente puedas restaurarlos. **No desinstales sin respaldo.**
