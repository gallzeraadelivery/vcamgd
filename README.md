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
- Zygisk **so injeta** com `mode=virtual` (mode=real = camera nativa sem hooks)  
- Modo virtual: hard inject (dummy na HAL + video no preview)  
- Overlay **Real / Virtual** + force-stop nos apps de camera  


## Uso responsavel

Apenas estudo/pesquisa.
