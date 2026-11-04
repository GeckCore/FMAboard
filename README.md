# FMAboard

Teclado para Android basado en [FUTO Keyboard](https://keyboard.futo.tech/), con extras pensados para escribir en español.
Es un proyecto personal, sin ánimo de lucro, de **FMA**.

> **Versión modificada.** FMAboard es una modificación no comercial de FUTO Keyboard (© FUTO Holdings, Inc.),
> distribuida gratis bajo la [FUTO Source First License 1.1](LICENSE.md). No es una versión oficial de FUTO.
> Los cambios propios están en `java/src/org/futo/inputmethod/latin/fmaboard/`, en las líneas marcadas `// FMAboard:`
> del resto del código y en `lm/`.

## Qué añade

- **Frases sugeridas**: aprende las frases que repites y te ofrece terminarlas con un toque (mantén pulsado para olvidarla).
- **Autocorrector personal**: aprende de las correcciones que deshaces o haces tú.
- **Texto de imagen**: copia texto de una foto o captura sin conexión; reconoce código y conserva la sangría. Con IA es opcional.
- **Descargar enlace**: descarga un vídeo o su audio desde un enlace y lo envía al chat.
- **Mensaje de voz**: convierte lo que escribes en un audio con voz natural (Dora o Alex) y lo envía al chat.
  La voz se genera en el móvil, gratis y sin conexión; la primera vez se descarga (142 MB).
- **Crear sticker**: convierte una foto, vídeo o GIF en sticker de WhatsApp (también animado), con recorte
  a mano o en rectángulo, quitar fondo (en el móvil), borde blanco, forma y texto. Desde *Compartir*,
  «Sticker al momento» lo convierte tal cual. Los estilos de fuente también se envían como sticker.
- **Portapapeles mejorado**: notas propias, búsqueda que ignora tildes, borrar varios clips a la vez, capturas de pantalla e imágenes
  (pegar, sacar texto o hacer sticker), abrir enlaces, quitarles el rastreo (`utm_`, `fbclid`, `si`…)
  y abrir un chat de WhatsApp con un número sin guardarlo en contactos.
- **IA opcional** con modelos gratuitos de [OpenRouter](https://openrouter.ai/) o [Gemini](https://aistudio.google.com/apikey): reescribir, responder con tu estilo y pulir el dictado. Eliges cuál usar, o las dos: si una falla o está saturada, responde la otra.
- **Registro de escritura** (JSONL en el propio móvil) para entrenar un modelo de predicción personal (`lm/`). Esquema: [SCHEMA.md](java/src/org/futo/inputmethod/latin/fmaboard/SCHEMA.md).

## Privacidad

- El registro de escritura se guarda **solo en tu móvil** (y en una copia en `Documentos/FMAboard`). Nunca se envía a ningún sitio.
  Puedes desactivarlo o borrarlo en *Ajustes → FMAboard*.
- Nunca se registra lo que escribes en contraseñas, códigos, campos de incógnito ni en los campos que no son de texto.
- La IA solo se usa cuando la pulsas, y con **tu propia clave** de OpenRouter o de Gemini, que se guarda en el móvil. El código no incluye ninguna clave.

## Instalar

1. Descarga el `.apk` de la última versión en [Releases](https://github.com/GeckCore/FMAboard/releases) e instálalo.
   Android te pedirá permiso para instalar apps de origen desconocido.
2. Abre FMAboard y sigue los pasos para activarlo como teclado.
3. Para la IA (opcional), crea una clave gratuita en OpenRouter o en Google AI Studio (Gemini, sin activar la facturación) y pégala en *Ajustes → FMAboard*.

El español viene listo: la APK incluye el diccionario, el modelo de predicción y el de dictado por voz,
y se configuran solos la primera vez que usas el teclado con español. Lo que ya hubieras importado no se toca.

La APK de prueba está firmada con la clave de desarrollo pública de FUTO (`java/shared.keystore`), por lo que solo sirve para probar.

## Compilar

Igual que FUTO Keyboard (sección *Building*, más abajo), con el sabor `unstable`:

```
git clone --recursive https://github.com/GeckCore/FMAboard.git
./gradlew assembleUnstableRelease
```

Créditos: el parche de pulsaciones Unicode (`lm/external/0001-unicode-keystrokes.patch`) viene de
[futo-keyboard-polyglot](https://github.com/erickgtzh/futo-keyboard-polyglot), basado en stamchry/android-keyboard;
el entrenamiento del modelo (`lm/`) parte de [futo-keyboard-ptbr](https://github.com/barddoo/futo-keyboard-ptbr).

Modelos incluidos en la APK (se descargan al compilar desde la release
[models-v1](https://github.com/GeckCore/FMAboard/releases/tag/models-v1)):
el modelo de predicción `es_typing_Q6_K.gguf` de futo-keyboard-polyglot (MIT), el diccionario español
de AOSP (Apache-2.0) distribuido por FUTO, y el modelo de voz multilingüe de FUTO Voice Input (basado en Whisper, MIT).

Mensaje de voz: [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) (Apache-2.0), en la versión int8 de
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0), que lo ejecuta en el móvil con ONNX Runtime (MIT).
El modelo no va en la APK: se descarga al usarlo por primera vez. La conversión de texto a fonemas
(`SpanishPhonemizer.java`) es propia, para no depender de espeak-ng.

Crear sticker: quitar fondo usa ML Kit Subject Segmentation de Google, que se ejecuta en el móvil
(el modelo lo instala Google Play Services la primera vez).

---

# FUTO Keyboard (README original)

The goal is to make a good modern keyboard that stays offline and doesn't spy on you. This keyboard is a fork of [LatinIME, The Android Open-Source Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME), with significant changes made to it.

Check out the [FUTO Keyboard website](https://keyboard.futo.tech/) for downloads and more information.

The code is licensed under the [FUTO Source First License 1.1](LICENSE.md).

## Nightly builds

Usually, a build close to the current master is available at [https://dl.keyboard.futo.org/nightly.apk](https://dl.keyboard.futo.org/nightly.apk). Read more about it here: https://docs.keyboard.futo.tech/improvements/nightly

The "Check for updates manually" button in the nightly build will check against the latest nightly, allowing you to get updated this way. It's recommended to check it once immediately after first installation in case an older version got downloaded due to CDN cache.

## Issue tracking and contributing

Please check the GitHub repository to report issues: [https://github.com/futo-org/android-keyboard/](https://github.com/futo-org/android-keyboard/)

The source code is hosted on our [internal GitLab](https://gitlab.futo.org/keyboard/latinime) and mirrored to [GitHub](https://github.com/futo-org/android-keyboard/). As registration is closed on our internal GitLab, we use GitHub instead for issues and pull requests.

If you want to help translate the app, please do so via our Pontoon instance: https://i18n-keyboard.futo.org/

Due to custom license, pull requests to this repository require signing a [CLA](https://cla.futo.org/) which you can do after opening a PR. Contributions to the [layouts repo](https://github.com/futo-org/futo-keyboard-layouts) don't require CLA as they're Apache-2.0

Please do not submit AI-generated pull requests, as these tend to make the codebase more difficult to understand. Pull requests made with clear heavy use of AI may be closed without comment. Please also avoid using AI to write issues, and try to explain it in your own words instead.

## Layouts

If you want to contribute layouts, check out the [layouts repo](https://github.com/futo-org/futo-keyboard-layouts).

## Building

When cloning the repository, you must perform a recursive clone to fetch all dependencies:
```
git clone --recursive https://gitlab.futo.org/keyboard/latinime.git
```

If you forgot to specify recursive clone, use this to fetch submodules:
```
git submodule update --init --recursive
```

You can then open the project in Android Studio and build it that way, or use gradle commands:
```
./gradlew assembleUnstableDebug
./gradlew assembleStableRelease
```

## APK signing

For official FUTO Keyboard versions, you can verify the APK's signing key fingerprint for integrity.

```
Signing key fingerprint for all versions except Google Play:

MD5: 3A:BB:71:C6:BB:E4:92:27:B1:E3:5D:81:01:48:6A:B0
SHA1: 5D:15:B3:6E:C9:6A:96:28:41:09:DD:62:93:0D:9C:39:9F:5F:06:43
SHA-256: 74:3F:AD:58:64:AB:C4:26:50:0B:2D:C2:C4:7C:8A:D3:24:CB:CD:16:03:3F:80:16:99:48:41:35:63:74:F9:95

```
