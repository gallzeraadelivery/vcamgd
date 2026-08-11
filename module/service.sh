#!/system/bin/sh
# Boot: always restore REAL camera (zero Zygisk hooks).
# Prevents Motorola 03400001 from sticky virtual mode after reboot.
MODDIR=${0%/*}
mkdir -p /data/local/tmp/vcamgd /data/adb/vcamgd
chmod 777 /data/local/tmp/vcamgd

if [ -f "$MODDIR/lib/arm64-v8a/libpine.so" ]; then
  cp -f "$MODDIR/lib/arm64-v8a/libpine.so" /data/local/tmp/vcamgd/libpine.so
  chmod 755 /data/local/tmp/vcamgd/libpine.so
fi

# Force passthrough every boot (Android 12-16 / Moto G60 safe default)
echo '{"enabled":false,"virtual":false,"mode":"real","source":"","uri":"","url":""}' > /data/local/tmp/vcamgd/control.json
echo '{"enabled":false,"virtual":false,"mode":"real","source":"","uri":"","url":""}' > /data/adb/vcamgd/control.json
chmod 666 /data/local/tmp/vcamgd/control.json /data/adb/vcamgd/control.json
touch /data/local/tmp/vcamgd/status.json
chmod 666 /data/local/tmp/vcamgd/status.json 2>/dev/null

# Recover camera HAL if previous virtual session left it wedged (Moto 03400001)
killall cameraserver 2>/dev/null
killall android.hardware.camera.provider@2.4-service 2>/dev/null
killall android.hardware.camera.provider@2.4-service_64 2>/dev/null
killall android.hardware.camera.provider@2.5-service 2>/dev/null
killall android.hardware.camera.provider@2.6-service-google 2>/dev/null
killall vendor.qti.camera.provider@2.4-service_64 2>/dev/null
killall vendor.qti.camera.provider-service_64 2>/dev/null
