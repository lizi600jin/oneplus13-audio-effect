#!/system/bin/sh
# ==============================================================================
# KernelSU 模块卡片「执行」按钮 = 模块总开关（开 ↔ 关）
#
# 关 = 创建 /data/adb/spk-eq/off，服务 1 秒内旁路并卸载 effect
# 开 = 删除该文件，服务 1 秒内恢复（若是扬声器）；插着耳机则保持旁路
#
# 为什么需要这个按钮
#   模块默认只对内置扬声器生效（服务内检测输出路由，蓝牙/USB 自动旁路），
#   日常不需要手动切换。这个开关是给「自动识别万一失效」准备的兜底，
#   例如某些少见的 USB DAC 被识别成扬声器。
#
# 为什么不重启服务
#   服务主循环每 500ms 读一次 off 文件，状态一变立刻重建 effect。
#   重启服务反而会中断 3~4 秒、并且有杀错进程的风险。
#   所以这里只改文件，让服务自己响应。
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
OFF=$D/off

eq_pids() {
  # 关键细节：匹配模式绝不能出现在本进程自己的 cmdline 里，否则会 kill 到自己。
  # 真身进程的 cmdline 是 app_process\0/system/bin\0DynEqService，
  # 而本脚本/shell/grep 的 cmdline 都以别的字符开头，用 '^app_process' 天然排除。
  grep -la '^app_process' /proc/[0-9]*/cmdline 2>/dev/null \
    | xargs -r grep -la 'DynEqService' 2>/dev/null \
    | while read -r f; do
        p=${f#/proc/}; echo "${p%/cmdline}"
      done
}

# 服务没在跑时拉起它（dex 内有进程锁，重复启动只会有一个真生效）
ensure_running() {
  [ -n "$(eq_pids)" ] && return 0
  cd "$D" 2>/dev/null || return 1
  nohup sh -c "CLASSPATH=$MODDIR/spk-eq.dex app_process /system/bin DynEqService" \
    > /data/local/tmp/spkeq.out 2>&1 < /dev/null &
  sleep 3
  [ -n "$(eq_pids)" ]
}

# 等服务把状态落地（主循环 500ms，留 3 秒余量）
wait_apply() {
  i=0
  while [ $i -lt 6 ]; do
    sleep 1
    line=$(tail -1 "$D/service.log" 2>/dev/null)
    case "$line" in
      *"$1"*) echo "  $line"; return 0;;
    esac
    i=$((i+1))
  done
  echo "  (日志未出现预期状态，最后一行：$line)"
  return 1
}

if [ -f "$OFF" ]; then
  echo "--- 模块当前【关闭】 → 正在开启 ---"
  rm -f "$OFF"
  ensure_running || { echo "! 服务启动失败"; cat /data/local/tmp/spkeq.out 2>/dev/null; exit 1; }
  wait_apply "APPLY" || wait_apply "BYPASS"
  echo "模块已开启"
else
  echo "--- 模块当前【开启】 → 正在关闭 ---"
  mkdir -p "$D"; : > "$OFF"
  ensure_running || { echo "! 服务启动失败"; exit 1; }
  wait_apply "总开关已关闭"
  echo "模块已关闭（音效全部旁路，恢复原厂听感）"
  echo "再点一次「执行」即可重新开启"
fi

echo
echo "作用范围：仅内置扬声器，蓝牙/USB 耳机自动旁路"
echo "当前 EQ 曲线："
tr '\n' ' ' < "$D/conf" 2>/dev/null
echo
[ -s "$D/mbc" ] && { echo "当前 MBC："; tr '\n' ' ' < "$D/mbc"; echo; }
