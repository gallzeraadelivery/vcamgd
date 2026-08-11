# VCamGD / KingVCam

Camera virtual **so com Magisk/Zygisk** (Pine). **LSPosed nao e necessario.**

Alvo: **Android 12–16** (API 31–36), incluindo **Motorola Moto G60**.

Repositorio: https://github.com/gallzeraadelivery/vcamgd  
Releases: https://github.com/gallzeraadelivery/vcamgd/releases

## Instalacao

1. Magisk + **Zygisk ON**  
2. Instalar `vcamgd-magisk-zygisk.zip` → **reboot**  
3. Instalar `vcamgd-app-debug.apk` (KingVCam)  
4. Abrir o app (root) — **nao** ligue a virtual ainda  
5. Teste a **camera nativa** (deve abrir)  
6. So entao: video → ativar virtual → reabrir camera  

IPC: `/data/local/tmp/vcamgd/`

## Real vs Virtual

| Modo | Comportamento |
|------|----------------|
| `mode=real` / desligado | Zygisk **nao injeta** — camera OEM intacta |
| `mode=virtual` | Hard inject com Surfaces via **ImageReader** (evita `03400001` da Moto) |

A cada **boot**, o modulo forca `mode=real` (seguro). Virtual precisa ser reativada no app.

## Build

```bat
gradlew.bat :app:assembleDebug
powershell -ExecutionPolicy Bypass -File scripts\pack-module.ps1
```

## Uso responsavel

Apenas estudo/pesquisa.
