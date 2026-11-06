# Icon update 3.5.3

- Se reemplazó únicamente el icono de launcher de la app con la imagen proporcionada por el usuario.
- La imagen no fue regenerada ni rediseñada; solo se redimensionó a las densidades Android mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi.
- `AndroidManifest.xml` ahora usa `@mipmap/ic_launcher` para `android:icon` y `android:roundIcon`.
- `drawable/ic_skc.xml` se conserva sin cambios porque se usa como `smallIcon` de notificaciones, donde Android requiere un icono monocromático compatible.
