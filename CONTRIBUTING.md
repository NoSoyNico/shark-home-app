# Cómo aportar a Shark Home

¡Gracias por querer ayudar! La idea es que Shark Home sirva en más BYD: si lo adaptaste a tu Song, Seal,
Dolphin, Atto 3 u otro, compártelo.

## Reglas de seguridad (no negociables)

Shark Home **solo lee** información. No se aceptan cambios que:

- **Escriban al vehículo**: nada de ADAS, frenos, ESC, airbags, dirección, clima, seguros ni modos de manejo.
- Inyecten tramas **CAN** o usen servicios internos del clúster (por ejemplo `com.byd.cluster.spi`).
- Modifiquen el **firmware**, borren o desactiven apps del sistema, o cambien la región/país.
- Se salten las protecciones de permisos de BYD (permisos de firma `BYDAUTO_*`).
- Incluyan archivos, modelos o código **de BYD** dentro del APK. El modelo 3D se lee del vehículo de cada quien.

Todo lo que se configure, se prueba **estacionado**.

## Compilar

Requisitos (macOS o Linux): **Java 17** y las **Android command-line tools** con `platforms;android-34` y
`build-tools;34.0.0`. No se usa Gradle.

```bash
cd app
ANDROID_HOME=/ruta/al/sdk ./build.sh                 # genera app/build/SharkHome.apk
ANDROID_HOME=/ruta/al/sdk ./build.sh install <IP>    # además lo instala y abre por ADB
```

- La primera vez se crea `app/debug.keystore`, **tu** llave de firma. No la subas a GitHub (está en `.gitignore`)
  y guárdala: sin ella no podrás instalar tus versiones encima de las anteriores.
- Una APK firmada por ti **no se instala encima** de la oficial (ni al revés): hay que desinstalar primero.
- Java sin lambdas: `d8` falla con `android.jar`; usa clases anónimas.

## Probar en el vehículo

Con ADB por Wi-Fi activo en el DiLink: `adb connect <IP>:5555`, luego `./build.sh install <IP>`.
Verifica con una captura (`adb exec-out screencap -p > pantalla.png`); que un comando diga *Success* no
significa que se vea bien.

## Mandar cambios

1. Haz un *fork*, crea una rama y haz cambios pequeños, uno por tema.
2. En el *pull request* di **qué modelo de BYD, versión de DiLink y Android** usaste, y agrega una captura.
3. Si es para otro modelo, que no rompa la Shark: detecta el modelo y usa la ruta que corresponda.

Al aportar, aceptas que tu código se publique bajo la licencia **GPL-3.0** del proyecto.
