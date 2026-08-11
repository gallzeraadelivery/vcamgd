# VCamGD

App Android de **camera virtual** inspirado no OVCAM (`com.vcamor.vv`), em Kotlin + Material 3 (Views).

Repositorio: https://github.com/gallzeraadelivery/vcamgd

## O que ja existe

- Telas: **Inicio / Controle / Status** + aviso legal
- Fontes: arquivo local, RTSP/RTMP, USB (configuracao na UI)
- `OverlayService` com janela flutuante
- Checagem de root
- Stub do controlador nativo + esqueleto Magisk (`module/`)
- Persistencia com DataStore

## Limite atual

A injecao real da camera (HAL / Zygisk / hook Camera2) ainda e **stub**. Sem o modulo em `/data/adb/modules/vcamgd`, a UI informa que o modulo nao foi encontrado.

## Build

Requisitos: JDK 17+, Android SDK (API 36).

```bat
gradlew.bat :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Estrutura

```
app/          # aplicativo Android
module/       # stub Magisk
decompiled/   # analise do APK de referencia (local, gitignored)
```

## Uso responsavel

Apenas para estudo/pesquisa. Nao use para fraude ou qualquer atividade ilegal.
