# VCamGD / KingVCam

Camera virtual **UniversalEngine** (v0.10.3+): estilo apps base (**APK + root**), alvo **Android 12–16**.

**Sem** Zygisk / **sem** reboot de módulo Magisk no fluxo normal.

Repositorio: https://github.com/gallzeraadelivery/vcamgd  
Releases: https://github.com/gallzeraadelivery/vcamgd/releases

## Instalacao

1. Root (Magisk ou KernelSU)  
2. Instalar `KingVCam-0.10.3.apk`  
3. Abrir → conceder root **permanente**  
4. Video → ativar virtual → Status deve mostrar `inject=true`  
5. Abrir a camera do sistema / app alvo  

## Motor v0.10.3 (inject endurecido)

1. **SELinux live** + domains HyperOS/MediaTek/Qualcomm  
2. `ptrace_scope=0` + denylist Magisk liberando `cameraserver`  
3. **vcplax** + `libvc` + `shadowhook` (bins 16KB)  
4. Bounce HAL OEM + confirma `libvc` nas maps  
5. **Watchdog** re-inject se o HyperOS derrubar o map  


## Build

```bat
gradlew.bat :app:assembleDebug
```

## Uso responsavel

Apenas estudo/pesquisa.
