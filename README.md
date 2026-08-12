# VCamGD / KingVCam

Camera virtual **KingEngine** (motor proprio): daemon `kingvd` + hooks soft Zygisk/Pine.

**Alvo:** Android 12–16. Sem LSPosed.

Repositorio: https://github.com/gallzeraadelivery/vcamgd  
Releases: https://github.com/gallzeraadelivery/vcamgd/releases

## Instalacao

1. Magisk com **Zygisk ON** + root  
2. Instalar `vcamgd-app-debug.apk`  
3. Abrir → conceder root → reboot se pedido (1x)  
4. Video → ativar virtual → **reabrir** a camera  

Build offline legado (vcplax, 12–13): release **v0.8.1**.

## Motor v0.9 (proprio)

1. `kingvd` — daemon nativo (unix socket) escreve `control.json`  
2. Zygisk injeta `HookEntry` (soft): preview apos sessao Camera  
3. IPC: `/data/local/tmp/vcamgd/`  

## Build

```bat
powershell -ExecutionPolicy Bypass -File scripts\build-kingvd.ps1
powershell -ExecutionPolicy Bypass -File scripts\pack-module.ps1
gradlew.bat :app:assembleDebug
```

## Uso responsavel

Apenas estudo/pesquisa.
