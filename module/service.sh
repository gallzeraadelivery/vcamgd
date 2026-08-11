#!/system/bin/sh
mkdir -p /data/local/tmp/vcamgd /data/adb/vcamgd
chmod 777 /data/local/tmp/vcamgd
chmod 755 /data/adb/vcamgd
[ -f /data/local/tmp/vcamgd/control.json ] || echo '{"enabled":false,"virtual":true,"source":"","uri":"","url":""}' > /data/local/tmp/vcamgd/control.json
[ -f /data/adb/vcamgd/control.json ] || cp /data/local/tmp/vcamgd/control.json /data/adb/vcamgd/control.json
chmod 666 /data/local/tmp/vcamgd/control.json /data/adb/vcamgd/control.json 2>/dev/null
touch /data/local/tmp/vcamgd/status.json /data/adb/vcamgd/status.json
chmod 666 /data/local/tmp/vcamgd/status.json /data/adb/vcamgd/status.json 2>/dev/null
