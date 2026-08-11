# VCamGD

App Android de **camera virtual** inspirado no OVCAM, com:

1. App (`:app`) — UI + controle via root  
2. Modulo Magisk Zygisk (`module/`) — IPC `/data/adb/vcamgd/`  
3. Modulo LSPosed (`:xposed`) — hooks Camera2 + feeder de video no Surface  

Repositorio: https://github.com/gallzeraadelivery/vcamgd

## Instalacao (ordem)

1. Root + Magisk com **Zygisk** ativo  
2. Instalar `dist/vcamgd-magisk-zygisk.zip` no Magisk e reiniciar  
3. Instalar **LSPosed** (Zygisk) e reiniciar  
4. Instalar APKs:
   - `app/build/outputs/apk/debug/app-debug.apk` (app)
   - `xposed/build/outputs/apk/debug/xposed-debug.apk` (hook)
5. No LSPosed Manager: ativar **VCamGD Hook** para os apps alvo (ou tudo)  
6. Abrir VCamGD → selecionar video → ativar camera virtual (aceitar root)  
7. Abrir o app alvo e usar a camera  

## Build

```bat
gradlew.bat :app:assembleDebug :xposed:assembleDebug
powershell -ExecutionPolicy Bypass -File scripts\pack-module.ps1
```

## Como funciona a injecao

- O app copia o video para `/data/adb/vcamgd/current.mp4` e seta `control.json`  
- O hook LSPosed intercepta `CameraDevice.createCaptureSession`  
- O preview Surface recebe o video (MediaPlayer loop)  
- A camera real e redirecionada para Surfaces dummy  

## Limites atuais

- Fonte **arquivo local** suportada no feeder  
- RTSP/RTMP e USB: controle ja existe; decode de rede ainda nao  
- Nem todos os apps usam Camera2 da mesma forma; pode precisar ampliar hooks  

## Uso responsavel

Apenas estudo/pesquisa. Nao use para fraude ou atividade ilegal.
