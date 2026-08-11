# VCamGD / KingVCam

Camera virtual com **APK unico** + **root**: motor nativo **vcplax** / `libvc` / ShadowHook (build liberado), sem depender de LSPosed nem de ZIP Magisk.

**Alvo:** Android 12–16.

Repositorio: https://github.com/gallzeraadelivery/vcamgd  
Releases: https://github.com/gallzeraadelivery/vcamgd/releases

## Instalacao (so o APK)

1. Dispositivo **root** (`su`)  
2. Instalar `vcamgd-app-debug.apk`  
3. Abrir KingVCam → conceder root  
4. O app extrai o motor e sobe `/data/vcplax` (Binder)  
5. Escolher video/URL → ativar virtual → abrir a camera do telefone  

Reboot normalmente **nao** e necessario no motor vcplax.

## Motor (v0.8+)

Fluxo espelhado do APK base liberado:

1. Extrai `libvc.so`, `libshadowhook.so`, `vcplax.so` dos assets  
2. Root: copia para `/data/libvc.so`, `/data/libvc++.so`, `/data/vcplax`  
3. Executa `/data/vcplax <ServerName>&`  
4. Controle via Binder `com.xiaomi.vlive.IMyBinderService` (play/stop)

O modulo Zygisk/Pine antigo permanece no repo como legado opcional.

## Real vs Virtual

| Modo | Comportamento |
|------|----------------|
| desligado / real | `stopPlay` — camera OEM |
| virtual | Daemon injeta feed (mp4 / rtmp/rtsp/http) |

## Build

```bat
gradlew.bat :app:assembleDebug
```

Libs do motor em `app/src/main/assets/vcam-engine/{arm64-v8a,armeabi-v7a}/`.

## Uso responsavel

Apenas estudo/pesquisa. Use apenas engines/binarios que voce tenha direito de usar.
