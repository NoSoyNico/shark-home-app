#!/bin/zsh
# Compila Shark Home sin Gradle (directo con las build-tools del SDK): más simple y rápido.
# Uso: ./build.sh            -> genera build/SharkHome.apk
#      ./build.sh install IP -> además lo instala y lo abre en la troca (no toca el launcher de BYD)
# La primera vez crea debug.keystore (TU llave de firma). Guárdala: sin ella no podrás actualizar encima.
set -e
cd "${0:A:h}"
SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
JAVA_HOME="${JAVA_HOME:-$(/opt/homebrew/bin/brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home}"
KS="$PWD/debug.keystore"

rm -rf build && mkdir -p build/classes build/dex build/stubs
[ -f "$KS" ] || "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storepass android -keypass android \
  -alias shark -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Shark Home" >/dev/null 2>&1

# Recursos (ícono): se compilan con aapt2 si existe la carpeta res/.
RES=()
if [ -d res ]; then
  "$BT/aapt2" compile --dir res -o build/res.zip
  RES=(build/res.zip)
fi
# assets/: tablas del cifrado de la nube de BYD y avisos de licencia de terceros.
ASSETS=()
[ -d assets ] && ASSETS=(-A assets)
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml "${RES[@]}" "${ASSETS[@]}" \
  --min-sdk-version 29 --target-sdk-version 29 --version-code 41 --version-name 0.41
# Stubs SOLO para compilar (clases del framework BYD que viven en la troca, no en el APK).
# Se compilan aparte y se usan como classpath; NO se dexean, así que no entran al APK:
# en la troca se enlazan las clases reales del sistema. No contienen código de BYD.
STUBCP=()
if [ -d stubs ] && [ -n "$(find stubs -name '*.java')" ]; then
  "$JAVA_HOME/bin/javac" -nowarn -source 8 -target 8 -bootclasspath "$JAR" -d build/stubs \
    -Xlint:-options $(find stubs -name '*.java')
  STUBCP=(-classpath build/stubs)
fi
"$JAVA_HOME/bin/javac" -nowarn -source 8 -target 8 -bootclasspath "$JAR" "${STUBCP[@]}" -d build/classes \
  -Xlint:-options $(find src -name '*.java')
# d8 SOLO las clases de la app (build/classes). Los stubs quedan fuera a propósito.
"$BT/d8" --min-api 29 --release --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
# Firma v1 (JAR) PRIMERO con jarsigner: apksigner con minSdk 29 la omite si no viene ya puesta, y el explorador
# de archivos de BYD (y head units viejos) la piden para instalar por USB. Luego apksigner añade v2+v3 y la conserva.
"$JAVA_HOME/bin/jarsigner" -keystore "$KS" -storepass android -keypass android \
  -sigalg SHA256withRSA -digestalg SHA-256 build/unsigned.apk shark >/dev/null
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
# v2 + v3 (conservando la v1). --min-sdk-version 19 hace que apksigner mantenga la v1 clásica.
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 19 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out build/SharkHome.apk build/aligned.apk
"$BT/apksigner" verify build/SharkHome.apk
echo "APK -> $PWD/build/SharkHome.apk ($(du -h build/SharkHome.apk | cut -f1))"

if [ "$1" = "install" ]; then
  IP="${2:?Uso: ./build.sh install <IP-de-la-troca>}"
  adb connect "$IP:5555" >/dev/null
  adb -s "$IP:5555" install -r build/SharkHome.apk
  adb -s "$IP:5555" shell am start -n mx.nico.sharkhome/.MainActivity
fi
