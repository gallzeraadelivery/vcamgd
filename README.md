# VCamGD

Camera virtual **so com Magisk/Zygisk** (Pine ART hooks). **LSPosed nao e necessario.**

Repositorio: https://github.com/gallzeraadelivery/vcamgd  
Releases: https://github.com/gallzeraadelivery/vcamgd/releases

## Instalacao

1. Magisk + **Zygisk ON**  
2. Instalar `vcamgd-magisk-zygisk.zip` → reboot  
3. Instalar **apenas** `vcamgd-app-debug.apk`  
4. Abrir VCamGD → root → video/URL → ativar  
5. Force-stop no app alvo → abrir a camera  

IPC: `/data/local/tmp/vcamgd/`

## Build

```bat
gradlew.bat :app:assembleDebug
powershell -ExecutionPolicy Bypass -File scripts\pack-module.ps1
```

## Como funciona

- Zygisk injeta `hook.dex` + `libpine.so` nos apps (inclui camera padrao do sistema)  
- Hooks **Camera1** + **Camera2** com **injeção suave** (não quebra a camera real)  
- Intercala **Real ↔ Virtual** via overlay (force-stop nos apps de camera)  
- MediaPlayer alimenta o Surface de preview com arquivo/RTSP/HTTP  


## Uso responsavel

Apenas estudo/pesquisa.
