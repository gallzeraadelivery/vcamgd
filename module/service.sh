#!/system/bin/sh
mkdir -p /data/local/tmp/vcamgd /data/adb/vcamgd
chmod 777 /data/local/tmp/vcamgd
MODDIR=${0%/*}
if [ -f "$MODDIR/lib/arm64-v8a/libpine.so" ]; then
  cp -f "$MODDIR/lib/arm64-v8a/libpine.so" /data/local/tmp/vcamgd/libpine.so
  chmod 755 /data/local/tmp/vcamgd/libpine.so
fi
[ -f /data/local/tmp/vcamgd/control.json ] || echo '{"enabled":false,"virtual":false,"mode":"real","source":"","uri":"","url":""}' > /data/local/tmp/vcamgd/control.json
chmod 666 /data/local/tmp/vcamgd/control.json /data/local/tmp/vcamgd/status.json 2>/dev/null
