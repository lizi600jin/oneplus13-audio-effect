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
#
# 为什么不用 ps 找进程
#   busybox 的 ps 不支持 `-o PID,ARGS`，会报
#   `ps: bad -o argument 'PID'`，导致旧实例杀不掉、多实例叠加。
#   这里统一读 /proc/<pid>/cmdline。
# ==============================================================================
MODDIR=${0%/*}
D=/data/adb/spk-eq
LOG=$D/boot.log
mkdir -p "$D"

log() { echo "$(date '+%m-%d %H:%M:%S')  $*" >> "$LOG"; }

eq_pids() {
  # 关键细节：匹配模式绝不能出现在本进程自己的 cmdline 里，否则会 kill 到自己。
  # 真身进程的 cmdline 是 app_process\0/system/bin\0DynEqService，
  # 而本脚本/shell/grep 的 cmdline 都以别的字符开头，用 '^app_process' 天然排除。
  # 两次 grep 就是全部开销（约 0.1 秒），不做逐进程 fork。
  grep -la '^app_process' /proc/[0-9]*/cmdline 2>/dev/null \
    | xargs -r grep -la 'DynEqService' 2>/dev/null \
    | while read -r f; do
        p=${f#/proc/}; echo "${p%/cmdline}"
      done
}

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
for round in 1 2 3; do
  pids=$(eq_pids)
  [ -z "$pids" ] && break
  for p in $pids; do
    kill -9 "$p" 2>/dev/null && log "清理旧实例 pid=$p"
  done
  sleep 1
done

cd "$D" || { log "ERROR: 无法进入 $D"; exit 1; }
nohup sh -c "CLASSPATH=$MODDIR/spk-eq.dex app_process /system/bin DynEqService" \
  > /data/local/tmp/spkeq.out 2>&1 < /dev/null &
log "服务已拉起 pid=$!"

sleep 3
if [ -n "$(eq_pids)" ]; then
  log "确认存活 OK"
else
  log "ERROR: 服务没起来"
  cat /data/local/tmp/spkeq.out >> "$LOG" 2>/dev/null
fi
