# VCamGD / KingVCam

Camera virtual com **APK unico** (como o OVCAM): o motor Zygisk vai **embutido** e instala sozinho com root.

**LSPosed nao e necessario.** Alvo: **Android 12–16**.

Repositorio: https://github.com/gallzeraadelivery/vcamgd  
Releases: https://github.com/gallzeraadelivery/vcamgd/releases

## Instalacao (so o APK)

1. Magisk (ou KernelSU) com **Zygisk ON**  
2. Instalar **apenas** `vcamgd-app-debug.apk`  
3. Abrir KingVCam → conceder **root**  
4. O app instala o motor automaticamente → **reboot uma vez**  
5. Abrir de novo → video → ativar virtual  

Nao precisa baixar/instalar ZIP Magisk manualmente (o ZIP ainda existe no release so para avancados).

## Real vs Virtual

| Modo | Comportamento |
|------|----------------|
| desligado / real | Zero hooks — camera OEM |
| virtual | Inject Camera1/Camera2 + video no preview |

## Build

```bat
powershell -ExecutionPolicy Bypass -File scripts\pack-module.ps1
gradlew.bat :app:assembleDebug
```

O `pack-module.ps1` gera o ZIP e copia para `app/src/main/assets/vcamgd-magisk.zip`.

## Uso responsavel

Apenas estudo/pesquisa.
