#!/system/bin/sh
# ==============================================================================
# 安装脚本（KernelSU / Magisk 刷入时执行一次）
# ==============================================================================
SKIPUNZIP=0

ui_print "- 一加13扬声器音效"
ui_print "- 作者：600斤的立子"

if [ ! -f "$MODPATH/spk-eq.dex" ]; then
  ui_print "! 缺少 spk-eq.dex，安装终止"
  abort "! 安装包不完整"
fi
ui_print "- 引擎文件 spk-eq.dex 就位"

CONF_DIR=/data/adb/spk-eq
mkdir -p "$CONF_DIR"

# EQ 曲线：每行 "频率Hz:增益dB"，最多 14 段
if [ ! -f "$CONF_DIR/conf" ]; then
  cat > "$CONF_DIR/conf" <<'CONFEOF'
200:7
420:1
650:-7
1200:1
2400:4
3600:5
4500:1
5500:-1
8115:6
20000:2
CONFEOF
  ui_print "- 已写入默认 EQ 曲线（术力口调音）"
else
  ui_print "- 检测到已有 EQ 曲线，保留不覆盖"
fi

# 可选：MBC 多段压缩（每行 频率:阈值dB:压缩比:启动ms:释放ms）
# 不存在或为空 = 关闭 MBC
if [ ! -f "$CONF_DIR/mbc" ]; then
  : > "$CONF_DIR/mbc"
  ui_print "- 已创建 mbc（默认关闭，可自行填参数）"
fi

# 可选：limiter 阈值（单个数字，dB）
# 不存在 = -1（默认）；写 -99 = 完全关闭
[ -f "$CONF_DIR/limiter" ] || printf -- '-1\n' > "$CONF_DIR/limiter"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
for f in conf mbc limiter; do
  [ -f "$CONF_DIR/$f" ] && chmod 644 "$CONF_DIR/$f"
done

ui_print "- 安装完成，重启后生效"
ui_print "- 改曲线：编辑 $CONF_DIR/conf，1 秒内自动应用"
ui_print "- 不想重启：模块卡片点「执行」重启服务"
