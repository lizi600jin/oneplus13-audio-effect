#!/system/bin/sh
# ==============================================================================
# KernelSU 模块卡片「执行」按钮：重启 EQ 服务
# 改完曲线不想重启手机时用这个
#
# 为什么不用 ps 找进程
#   本机 /system/bin/ps 是 toybox，KernelSU 某些环境里 PATH 前面的却是
#   busybox 的 ps。busybox ps 不支持 `-o PID,ARGS`，会直接报
#   `ps: bad -o argument 'PID'`，命令列表为空 → 旧实例一个都杀不掉，
#   结果每点一次「执行」就多挂一份 EQ，低频叠加到失真。
#   所以这里改成直接读 /proc/<pid>/cmdline，不依赖任何 ps 实现。
# ==============================================================================
D=/data/adb/spk-eq
MODDIR=${0%/*}

# 列出所有 DynEqService 进程号（含 sh -c 外壳，一并清理）
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

echo "--- 重启 EQ 服务 ---"

# 1. 停旧实例，并确认真的停干净
for round in 1 2 3; do
  pids=$(eq_pids)
  [ -z "$pids" ] && break
  for p in $pids; do
    kill -9 "$p" 2>/dev/null && echo "已停止旧实例 pid=$p"
  done
  sleep 1
done

if [ -n "$(eq_pids)" ]; then
  echo "! 仍有实例无法停止：$(eq_pids | tr '\n' ' ')"
  echo "! 请重启手机后重试"
  exit 1
fi

# 2. 起新实例
cd "$D" 2>/dev/null || { echo "! $D 不存在，请重新安装模块"; exit 1; }
nohup sh -c "CLASSPATH=$MODDIR/spk-eq.dex app_process /system/bin DynEqService" \
  > /data/local/tmp/spkeq.out 2>&1 < /dev/null &

# 3. 等它拿到进程锁（dex 内有独占锁，多启动也只会有一个真的生效）
sleep 3
n=$(eq_pids | wc -l | tr -d ' ')
if [ "$n" -lt 1 ]; then
  echo "! 启动失败，日志："
  cat /data/local/tmp/spkeq.out 2>/dev/null
  exit 1
fi

# 4. 用 effect 客户端数做真正的健康检查（1 Clients 才是正常）
sleep 1
clients=$(timeout 8 dumpsys media.audio_flinger 2>/dev/null \
  | grep -A6 'name: DynamicsProcessing' \
  | grep -oE '[0-9]+ Clients' | head -1 | cut -d' ' -f1)

echo "服务已启动"
[ -n "$clients" ] && echo "当前 effect 客户端数：$clients（正常应为 1）"
echo
echo "当前 EQ 曲线："
cat "$D/conf" 2>/dev/null | tr '\n' ' '
echo
if [ -s "$D/mbc" ]; then
  echo "当前 MBC："
  cat "$D/mbc" | tr '\n' ' '
  echo
fi
