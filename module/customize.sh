#!/system/bin/sh
ui_print "- VCamGD Zygisk module"
mkdir -p /data/local/tmp/vcamgd
mkdir -p /data/adb/vcamgd
chmod 777 /data/local/tmp/vcamgd
chmod 755 /data/adb/vcamgd
[ -f /data/local/tmp/vcamgd/control.json ] || echo '{"enabled":false,"virtual":true,"source":"","uri":"","url":""}' > /data/local/tmp/vcamgd/control.json
[ -f /data/adb/vcamgd/control.json ] || echo '{"enabled":false,"virtual":true,"source":"","uri":"","url":""}' > /data/adb/vcamgd/control.json
chmod 666 /data/local/tmp/vcamgd/control.json /data/adb/vcamgd/control.json
touch /data/local/tmp/vcamgd/status.json /data/adb/vcamgd/status.json
chmod 666 /data/local/tmp/vcamgd/status.json /data/adb/vcamgd/status.json
ui_print "- Enable Zygisk + install LSPosed hook APK"
ui_print "- IPC: /data/local/tmp/vcamgd"
