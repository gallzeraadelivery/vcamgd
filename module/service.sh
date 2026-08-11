#!/system/bin/sh
# Late-start service: ensure control directory exists with open perms for app+zygisk IPC.
mkdir -p /data/adb/vcamgd
chmod 755 /data/adb/vcamgd
[ -f /data/adb/vcamgd/control.json ] || echo '{"enabled":false,"virtual":true,"source":"","uri":"","url":""}' > /data/adb/vcamgd/control.json
[ -f /data/adb/vcamgd/status.json ] || echo '{}' > /data/adb/vcamgd/status.json
chmod 666 /data/adb/vcamgd/control.json /data/adb/vcamgd/status.json
