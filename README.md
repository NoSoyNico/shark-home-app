# 🦈 Shark Home

[![Licencia: GPL-3.0](https://img.shields.io/badge/licencia-GPL--3.0-D4E53A?labelColor=1F1E27)](LICENSE)
[![Descargar](https://img.shields.io/github/v/release/NoSoyNico/shark-home-app?label=descargar&color=D4E53A&labelColor=1F1E27)](https://github.com/NoSoyNico/shark-home-app/releases/latest)

**Pantalla de inicio (launcher) para la BYD Shark mexicana** — mapa en vivo, la troca en 3D, reloj con clima por
horas, efectos de clima y tu música, todo en una sola pantalla.

> ⚠️ Proyecto personal y de código abierto, **no afiliado ni respaldado por BYD**. Úsalo bajo tu propio riesgo y
> configúralo **estacionado**. Lee el [aviso](DISCLAIMER.md).
> Solo **lee** datos; no cambia nada del vehículo.

## ⬇️ Descargar

👉 **[Última versión (APK)](https://github.com/NoSoyNico/shark-home-app/releases/latest)**

Probada en **BYD Shark México**, DiLink 3.0 (Android 10). En otros modelos o firmwares puede no funcionar.

## ✨ Qué trae

- **Mapa en vivo** que sigue tu GPS y gira con tu rumbo (proa arriba). Tres capas: **Calles**, **Topo**
  (curvas de nivel y brechas, ideal para la sierra) y **Vector** (estilo limpio, claro u oscuro).
- **Buscar destino** y trazar la **ruta**; el botón *Navegar* la manda a **Mapas BYD** para la guía por voz.
- **La Shark en 3D** (la toma del sistema de la cámara 360 de la propia troca), en el color de tu camioneta.
  Se gira con el dedo y las llantas ruedan con la velocidad.
- **Reloj** grande con fecha, velocidad, km y **pronóstico por horas**.
- **Efectos de clima** sobre el panel: lluvia que se inclina con la velocidad, nieve, sol, nubes, niebla,
  tormenta y estrellas de noche. Se pueden apagar o cambiar la densidad.
- **Música**: lo que suena (Spotify, radio, Media Center) con controles, y **letras** sincronizadas.
- **Puertas y luces** en vivo (leídas del registro del sistema).
- **Marco de fotos**: el panel derecho puede mostrar tus fotos (carpeta `SharkHome/fotos` de la memoria interna) en lugar del mapa.
- Accesos rápidos: **360°**, Mapas, Música, Radio, Spotify, Cámaras y más.
- Tema **claro u oscuro**.

## 📲 Cómo instalar (por USB)

1. Descarga `SharkHome_vX.XX.apk` de [Releases](https://github.com/NoSoyNico/shark-home-app/releases/latest)
   y cópiala a una memoria USB. **Borra de la USB las versiones viejas** para no instalar una por error.
2. Conecta la USB a la troca, abre el archivo desde el explorador de archivos y toca **Instalar**.
3. Ábrela. La primera vez te pide permisos:
   - **Ubicación** → *Permitir* (velocidad, rumbo, mapa y clima).
   - **"¿Permitir depuración por USB?"** (solo si tu troca tiene ADB activado) → marca *Permitir siempre* y
     *Permitir*. Con eso la app se da sola el resto de permisos (puertas, luces, fotos, música).
4. Para que sea tu pantalla de inicio: ⚙️ **Ajustes → Poner como launcher por defecto**, aprieta el botón de casa
   y elige **Shark Home → Siempre**.

**Para actualizar**, instala la versión nueva encima: no se pierden tus ajustes.
**Para quitarla**, desinstálala desde la lista de apps; el launcher de BYD sigue ahí.

## 🧑‍💻 Código abierto: adáptalo a tu BYD

Shark Home es **software libre** bajo la licencia [GPL-3.0](LICENSE): puedes usarlo, estudiarlo, modificarlo
y compartirlo. Si publicas tu versión, también tienes que compartir su código.

- **Código**: carpeta [`app/`](app) (Java, sin Gradle). Se compila con `app/build.sh`.
- **Cómo aportar**: [CONTRIBUTING.md](CONTRIBUTING.md), con las reglas de seguridad (solo lectura, nada de
  ADAS, CAN ni firmware) y cómo compilarlo.
- **¿Tienes otro BYD?** Las adaptaciones a Song, Seal, Dolphin, Atto 3 y demás son bienvenidas.
  [Reporta fallas o propone ideas](https://github.com/NoSoyNico/shark-home-app/issues/new/choose).

| Archivo | Qué hace |
|---|---|
| `MainActivity.java` | Pantalla principal: paneles, mapa, dock y ajustes |
| `Truck3DView.java` | Modelo 3D (lo lee de la app de cámara 360 del vehículo) |
| `WeatherFx.java` | Efectos de clima |
| `LiveInfo.java` | GPS, clima (Open-Meteo) y música |
| `VehicleLog.java` | Puertas y luces leídas del registro del sistema |
| `AdbClient.java` | Se da sus propios permisos por el ADB local, sin computadora |

## 🙋 Dudas y sugerencias

Hecho por **[@El.BioNiico](https://www.instagram.com/el.bioniico/)** — sugerencias por Instagram.

Mapas © [OpenStreetMap](https://www.openstreetmap.org/copyright), [OpenTopoMap](https://opentopomap.org) (CC-BY-SA),
[OpenFreeMap](https://openfreemap.org) / OpenMapTiles. Clima: [Open-Meteo](https://open-meteo.com).
BYD, DiLink y Shark son marcas de sus dueños.
