# Panel Operario (RCMS) — App Android

Abre `http://10.20.1.4:8181/rcms/web/cms/PanelOperario/`, inicia sesión sola con
el usuario configurado y vuelve al Panel Operario. Pantalla completa, pantalla
siempre encendida, reintenta sola si no hay conexión y vuelve a iniciar sesión
si la sesión expira.

Usuario, contraseña y URL están al inicio de
`app/src/main/java/com/grandbay/paneloperario/MainActivity.java`.

## Opción A — Obtener el APK sin instalar nada (GitHub)
1. Cree una cuenta en github.com y un repositorio **privado** nuevo.
2. "Add file → Upload files" y suba TODO el contenido de esta carpeta
   (incluyendo la carpeta `.github` — si el navegador la oculta, arrástrela).
3. Pestaña **Actions** → espere 3–5 min a que termine "Compilar APK" (✔ verde).
4. Abra la ejecución → sección **Artifacts** → descargue `PanelOperario-APK`
   (es un .zip con `PanelOperario.apk` adentro).

## Opción B — Android Studio
Abrir la carpeta → esperar la sincronización → Build → Build APK(s).

## Instalar en la tableta/celular
Copie el APK, ábralo y permita "Instalar apps de origen desconocido".
El equipo debe estar en la red de la planta (10.20.x.x).
