# VCamGD

App Android de **camera virtual** inspirado no OVCAM, com:

1. App (`:app`) — UI + controle via root  
2. Modulo Magisk Zygisk (`module/`) — IPC  
3. Modulo LSPosed (`:xposed`) — hooks Camera2 + feeder de video  

Repositorio: https://github.com/gallzeraadelivery/vcamgd  
Release: https://github.com/gallzeraadelivery/vcamgd/releases

## Instalacao (ordem)

1. Magisk + **Zygisk ON** → instalar ZIP → reboot  
2. **LSPosed** → instalar xposed APK → **ativar VCamGD Hook no app alvo** → reboot  
3. Instalar app APK  
4. VCamGD → conceder **root** → video → ativar  
5. **Force-stop** no app alvo → abrir a camera de novo  

IPC: `/data/local/tmp/vcamgd/` (e espelho em `/data/adb/vcamgd/`)

## Build

```bat
gradlew.bat :app:assembleDebug :xposed:assembleDebug
powershell -ExecutionPolicy Bypass -File scripts\pack-module.ps1
```

## Troubleshooting

- Status do app sem "Modulo" / root: conceda su ao VCamGD  
- Hook nao ativa: confira escopo no LSPosed + force-stop do app alvo  
- Camera1 / apps com preview proprio: pode nao usar Camera2 session  
- Veja `/data/local/tmp/vcamgd/status.json` e logcat: `VCamGD`

## Limites

- Arquivo local e RTSP/HTTP  
- RTMP: prefira republicar como RTSP  

## Uso responsavel

Apenas estudo/pesquisa. Nao use para fraude ou atividade ilegal.
