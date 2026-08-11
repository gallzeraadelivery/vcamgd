# VCamGD

App Android de **camera virtual** inspirado no OVCAM (`com.vcamor.vv`), em Kotlin + Material 3 (Views), com modulo **Magisk Zygisk**.

Repositorio: https://github.com/gallzeraadelivery/vcamgd

## O que ja existe

- Telas: Inicio / Controle / Status + aviso legal
- Fontes: arquivo local, RTSP/RTMP, USB (config na UI)
- Overlay flutuante
- Checagem de root
- Modulo Zygisk (`module/`) com IPC em `/data/adb/vcamgd/`
- Script de pack: `scripts/pack-module.ps1`

## Limite atual

O Zygisk **carrega e recebe o controle** do app. A **injecao de frames** na Camera2/HAL ainda e o proximo passo (hooks PLT/JNI).

## Build do app

```bat
gradlew.bat :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Build do modulo Magisk

```bat
powershell -ExecutionPolicy Bypass -File scripts\pack-module.ps1
```

ZIP: `dist/vcamgd-magisk-zygisk.zip`

Instale no Magisk, ative **Zygisk**, reinicie.

## Uso responsavel

Apenas para estudo/pesquisa. Nao use para fraude ou qualquer atividade ilegal.
