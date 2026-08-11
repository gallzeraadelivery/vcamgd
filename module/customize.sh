#!/system/bin/sh
ui_print "- VCamGD Zygisk (sem LSPosed)"
mkdir -p /data/local/tmp/vcamgd /data/adb/vcamgd
chmod 777 /data/local/tmp/vcamgd

# Pine native for in-process ART hooks
if [ -f "$MODPATH/lib/arm64-v8a/libpine.so" ]; then
  cp -f "$MODPATH/lib/arm64-v8a/libpine.so" /data/local/tmp/vcamgd/libpine.so
  chmod 755 /data/local/tmp/vcamgd/libpine.so
fi

[ -f /data/local/tmp/vcamgd/control.json ] || echo '{"enabled":false,"virtual":true,"source":"","uri":"","url":""}' > /data/local/tmp/vcamgd/control.json
chmod 666 /data/local/tmp/vcamgd/control.json
touch /data/local/tmp/vcamgd/status.json
chmod 666 /data/local/tmp/vcamgd/status.json
ui_print "- Instale so o app VCamGD + este ZIP"
ui_print "- LSPosed NAO e necessario"
