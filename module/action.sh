#!/system/bin/sh
# ==============================================================================
# KernelSU 模块卡片「执行」按钮：重启 EQ 服务
# 改完曲线不想重启手机时用这个
# ==============================================================================
D=/data/adb/spk-eq
MODDIR=${0%/*}

echo "--- 重启 EQ 服务 ---"
for p in $(ps -A -o PID,ARGS 2>/dev/null | grep app_process | grep -i DynEqService | grep -v grep | awk '{print $1}'); do
  kill -9 "$p" 2>/dev/null; echo "已停止旧实例 pid=$p"
done
sleep 1

cd "$D" 2>/dev/null || { echo "! $D 不存在，请重新安装模块"; exit 1; }
nohup sh -c "CLASSPATH=$MODDIR/spk-eq.dex app_process /system/bin DynEqService" > /data/local/tmp/spkeq.out 2>&1 &
sleep 3

if ps -A -o ARGS 2>/dev/null | grep -q "app_process /system/bin DynEqService"; then
  echo "服务已启动"
  echo
  echo "当前 EQ 曲线："
  cat "$D/conf" 2>/dev/null | tr '\n' ' '
  echo
  if [ -s "$D/mbc" ]; then
    echo "当前 MBC："
    cat "$D/mbc" | tr '\n' ' '
    echo
  fi
else
  echo "启动失败，日志："
  cat /data/local/tmp/spkeq.out 2>/dev/null
fi
