# VCamGD / KingVCam

Camera virtual **v0.11** — motor **B** (o que a internet documenta):

**Zygisk + Pine hooks no app da Camera** (Camera1/Camera2), igual VCAM/xCam.

Nao depende de inject `libvc` no `cameraserver` para o preview funcionar.

Repositorio: https://github.com/gallzeraadelivery/vcamgd  
Releases: https://github.com/gallzeraadelivery/vcamgd/releases

## Instalacao

1. Root **Magisk** com **Zygisk ON** (ou KernelSU + ZygiskNext)
2. Instalar `KingVCam-0.11.0.apk`
3. Abrir → conceder root permanente
4. Selecionar video → **ativar virtual**
5. Na **primeira vez** o app instala o modulo Magisk → **reinicie 1 vez**
6. Ative de novo → feche Camera Xiaomi (recentes) → abra de novo
7. Status deve mostrar `feeder=feeding:...` (nao `inject=true`)

## Como funciona (v0.11)

1. Modulo Zygisk injeta `hook.dex` no processo do app Camera
2. `HookEntry` (hard) troca Surfaces do HAL e toca o mp4 no preview
3. `control.json` em `/data/local/tmp/vcamgd/` liga/desliga
4. Video em `/data/local/tmp/vcamgd/current.mp4` (+ espelho `DCIM/Camera1/virtual.mp4`)

`vcplax`/`libvc` permanece no APK como motor legado, **nao** e usado no enable.

## Build

```bat
gradlew.bat :app:assembleRelease
```

## Uso responsavel

Apenas estudo/pesquisa.
