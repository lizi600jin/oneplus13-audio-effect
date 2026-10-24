#!/system/bin/sh
# 卸载时清理进程与配置
for p in $(ps -A -o PID,ARGS 2>/dev/null | grep app_process | grep -i DynEqService | grep -v grep | awk '{print $1}'); do
  kill -9 "$p" 2>/dev/null
done
rm -rf /data/adb/spk-eq
rm -f /data/local/tmp/spkeq.out
