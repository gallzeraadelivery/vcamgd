# VCamGD Magisk/Zygisk module

Camera virtual via **Zygisk + Pine**. LSPosed nao e necessario.

## Build + pack

```bat
powershell -ExecutionPolicy Bypass -File scripts\pack-module.ps1
```

## Install

1. Magisk → Zygisk ON  
2. Modules → Install from storage → `dist\vcamgd-magisk-zygisk.zip`  
3. Reboot  
4. Instalar so o app VCamGD  

## IPC

- Primario: `/data/local/tmp/vcamgd/` (`control.json`, `current.mp4`, `status.json`)  
- Espelho: `/data/adb/vcamgd/`  

## Conteudo

- `zygisk/arm64-v8a.so` — injeta `hook.dex` + carrega `libpine.so`  
- `dex/hook.dex` — hooks Camera2 + feeder MediaPlayer  
- `lib/arm64-v8a/libpine.so` — ART hooks in-process  
