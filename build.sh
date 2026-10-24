#!/usr/bin/env bash
# 一键编译 + 打包
set -e
cd "$(dirname "$0")"

AJ=${ANDROID_JAR:-$HOME/.local/opt/android-sdk/platforms/android-36/android.jar}
[ -f "$AJ" ] || { echo "找不到 android.jar，设 ANDROID_JAR 环境变量"; exit 1; }

# 自动挂上 SDK 工具链（build-tools 里才有 d8）
export PATH="$HOME/.local/opt/android-sdk/build-tools/$(ls "$HOME/.local/opt/android-sdk/build-tools/" 2>/dev/null | sort -V | tail -1):$PATH"
command -v d8 >/dev/null || { echo "找不到 d8，装 Android SDK build-tools"; exit 1; }

echo "[1/4] 编译 Java"
rm -rf /tmp/spkeq-out && mkdir -p /tmp/spkeq-out
javac --release 17 -nowarn -cp "$AJ" -d /tmp/spkeq-out src/DynEqService.java

echo "[2/4] 转 dex"
d8 --min-api 30 --output /tmp/spkeq-out /tmp/spkeq-out/DynEqService.class

echo "[3/4] 组装模块"
rm -rf /tmp/spkeq-pkg && mkdir -p /tmp/spkeq-pkg
cp module/*.sh module/module.prop /tmp/spkeq-pkg/
cp /tmp/spkeq-out/classes.dex /tmp/spkeq-pkg/spk-eq.dex
chmod 755 /tmp/spkeq-pkg/*.sh

echo "[4/4] 打包"
VER=$(grep '^version=' module/module.prop | cut -d= -f2)
cd /tmp/spkeq-pkg
zip -r -q "/tmp/spk-eq-${VER}.zip" ./*
cd - >/dev/null
mv "/tmp/spk-eq-${VER}.zip" .
echo "完成: spk-eq-${VER}.zip"
sha256sum "spk-eq-${VER}.zip"
