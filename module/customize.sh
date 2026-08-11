#!/system/bin/sh
ui_print "- KingVCam / VCamGD Zygisk (sem LSPosed)"
ui_print "- Alvo: Android 12-16 (inclui Moto G60)"
mkdir -p /data/local/tmp/vcamgd /data/adb/vcamgd
chmod 777 /data/local/tmp/vcamgd

if [ -f "$MODPATH/lib/arm64-v8a/libpine.so" ]; then
  cp -f "$MODPATH/lib/arm64-v8a/libpine.so" /data/local/tmp/vcamgd/libpine.so
  chmod 755 /data/local/tmp/vcamgd/libpine.so
fi

# Sempre inicia em mode=real (camera nativa). Virtual so apos ativar no app.
echo '{"enabled":false,"virtual":false,"mode":"real","source":"","uri":"","url":""}' > /data/local/tmp/vcamgd/control.json
echo '{"enabled":false,"virtual":false,"mode":"real","source":"","uri":"","url":""}' > /data/adb/vcamgd/control.json
chmod 666 /data/local/tmp/vcamgd/control.json /data/adb/vcamgd/control.json
touch /data/local/tmp/vcamgd/status.json
chmod 666 /data/local/tmp/vcamgd/status.json

ui_print "- Apos reboot: camera REAL deve abrir (zero hooks)"
ui_print "- Ative a virtual so no app KingVCam"
ui_print "- LSPosed NAO e necessario"
