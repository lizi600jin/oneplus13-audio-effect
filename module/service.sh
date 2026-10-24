#!/system/bin/sh
# ==============================================================================
# 开机自启：常驻 EQ 服务
#
# 为什么需要常驻
#   AudioEffect 的生命周期绑在持有它的进程上，进程一退出 effect 立即销毁。
#   所以必须后台常驻、循环读配置。
#
# 为什么用 app_process 而不是 APK
#   不需要界面、不需要签名、不占通知栏，root 身份直接跑 dex，
#   卸载模块即彻底清除。
# ==============================================================================
MODDIR=${0%/*}
D=/data/adb/spk-eq
LOG=$D/boot.log
mkdir -p "$D"

log() { echo "$(date '+%m-%d %H:%M:%S')  $*" >> "$LOG"; }

log "=== 一加13扬声器音效 启动 ==="

# 等 boot_completed（最多 120 秒）
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 120 ]; do
  sleep 1; i=$((i+1))
done
log "boot_completed 就绪（${i}s）"

# AudioEffect 依赖 audio_flinger，再等 25 秒确保 flinger 已起
sleep 25

# 清理残留实例（模块更新会留下旧进程）
for p in $(ps -A -o PID,ARGS 2>/dev/null | grep app_process | grep -i DynEqService | grep -v grep | awk '{print $1}'); do
  kill -9 "$p" 2>/dev/null
  log "清理旧实例 pid=$p"
done
sleep 1

cd "$D" || { log "ERROR: 无法进入 $D"; exit 1; }
nohup sh -c "CLASSPATH=$MODDIR/spk-eq.dex app_process /system/bin DynEqService" > /data/local/tmp/spkeq.out 2>&1 &
log "服务已拉起 pid=$!"

sleep 3
if ps -A -o ARGS 2>/dev/null | grep -q "app_process /system/bin DynEqService"; then
  log "确认存活 OK"
else
  log "ERROR: 服务没起来"
  cat /data/local/tmp/spkeq.out >> "$LOG" 2>/dev/null
fi
