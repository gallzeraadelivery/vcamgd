# VCamGD / KingVCam

Camera virtual **UniversalEngine** (v0.10+): estilo apps base (**APK + root**), alvo **Android 12–16**.

**Sem** pedir reboot de módulo Magisk no fluxo normal.

Repositorio: https://github.com/gallzeraadelivery/vcamgd  
Releases: https://github.com/gallzeraadelivery/vcamgd/releases

## Instalacao

1. Root (Magisk ou KernelSU)  
2. Instalar `KingVCam-0.10.0.apk`  
3. Abrir → conceder root **permanente**  
4. Video → ativar virtual → abrir a camera  

Nao precisa instalar zip Zygisk nem reiniciar por causa de modulo.

## Motor v0.10 (universal)

1. **SELinux live** (`magiskpolicy` / `ksud`) — sem reboot  
2. **vcplax** + `libvc` + `shadowhook` (bins ja 16KB)  
3. Restart do **cameraserver** apos inject  
4. Fallback Zygisk so se o modulo ja existir  

## Build

```bat
gradlew.bat :app:assembleDebug
```

## Uso responsavel

Apenas estudo/pesquisa.
