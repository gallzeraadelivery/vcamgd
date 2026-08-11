# VCamGD Magisk/Zygisk module
#
# Build + pack:
#   powershell -ExecutionPolicy Bypass -File scripts\pack-module.ps1
#
# Install:
#   1. Magisk app -> Modules -> Install from storage -> dist\vcamgd-magisk-zygisk.zip
#   2. Enable Zygisk in Magisk settings
#   3. Reboot
#
# IPC:
#   /data/adb/vcamgd/control.json  (app writes via su)
#   /data/adb/vcamgd/status.json   (zygisk writes last event)
#
# Current stage:
#   - Module loads into app processes when control.enabled=true
#   - Companion serves control state
#   - Camera frame injection hooks are NEXT (Camera2/NDK PLT)
