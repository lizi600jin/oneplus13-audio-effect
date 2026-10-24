# 一加13 扬声器音效

KernelSU / Magisk 模块 · 全局生效 · 改配置 1 秒热更新 · 重启自动恢复

![EQ 曲线](docs/eq-curve.png)

---

## 这个模块做什么

Android 默认的音效链对手机扬声器几乎没有频段层面的干预。而手机扬声器的硬件
谐振点（f0）通常在 700 Hz 附近，这个位置正好压在**人声基频下沿**和
**低频乐器基频上沿**之间，两者互相掩蔽，结果是听感上「糊」「分不开」。

本模块通过 `DynamicsProcessing` 在全局混音输出上挂一个 10 段参数 EQ，
把能量从掩蔽区搬到谐波密集区。

默认曲线针对虚拟歌姬 / 女声向内容调校，但**所有参数都可以自行修改**，
不绑定任何特定曲风。

---

## 默认曲线

| 覆盖范围 | 增益 | 意图 |
|---|---|---|
| 0 ~ 281 Hz | +7 dB | 低频冲击与厚度 |
| 281 ~ 469 Hz | +1 dB | 低中频过渡 |
| 469 ~ 750 Hz | **−7 dB** | 掩蔽区衰减 |
| 750 ~ 1312 Hz | +1 dB | 中频身体感 |
| 1312 ~ 2531 Hz | +4 dB | 人声 presence |
| 2531 ~ 3656 Hz | +5 dB | 咬字清晰度 |
| 3656 ~ 4594 Hz | +1 dB | 中高频衔接 |
| 4594 ~ 5625 Hz | −1 dB | 抑制刺耳 |
| 5625 ~ 8250 Hz | +6 dB | 亮度 |
| 8250 ~ 20062 Hz | +2 dB | 空气感 |

> 上表的边界是**实际生效的边界**，不是滤波器中心频率。
> DynamicsProcessing 采用 FFT 频域分段，`blockSize = 512`，bin 宽 93.75 Hz。

---

## 实现机制

**不是**多个钟形滤波器叠加。

是 `FFT → 按 bin 分段乘增益 → IFFT`：

```
把频谱切成连续块 → 每块乘一个固定增益 → 块与块之间硬跳变
```

由此带来三个特性：

- 最小可控带宽 = 93.75 Hz（48000 Hz ÷ 512）
- 段与段之间没有平滑过渡，是台阶状
- 没有 Q 值参数，带宽由频点间距决定

想要更细的频率分辨率，需要调整 `setPreferredFrameDuration`
（默认 10 ms，对应 512 采样点）。

实现细节见 [docs/方案说明.md](docs/方案说明.md)。

---

## 安装

1. 从 [Releases](../../releases) 下载 `spk-eq-v1.1.zip`
2. KernelSU / Magisk → 模块 → 从本地安装
3. 重启设备

**环境要求：**

- OnePlus 13（PJZ110）
- KernelSU 或 Magisk（root）
- Android 14 及以上
- **安装前请卸载其他音效模块**（AudioEffect 的 session 0 只能挂一个）

---

## 使用

### 修改 EQ 曲线

```bash
su
vi /data/adb/spk-eq/conf
```

格式：每行 `频率Hz:增益dB`，最多 14 行，`#` 开头为注释。

```
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
```

保存后 **1 秒内自动生效**，服务每 200 ms 轮询一次配置文件。

### 可选：多段压缩（MBC）

编辑 `/data/adb/spk-eq/mbc`，每行 `频率Hz:阈值dB:压缩比:启动ms:释放ms`：

```
220:-18:4:8:90
800:-22:4:8:90
3000:-24:4:6:70
8000:-26:3:5:60
```

EQ 是静态曲线，只能整体抬降。MBC 让各频段独立动态工作——
响的频段自动压缩、轻的频段浮现，可以改善分离度。

留空即关闭。

### 可选：限幅器阈值

编辑 `/data/adb/spk-eq/limiter`，单个数字（dB）。默认 `-1`，写 `-99` 完全关闭。

### 重启服务

模块卡片点「执行」按钮，或：

```bash
su -c "sh /data/adb/modules/spk_eq_master/action.sh"
```

---

## 卸载

KernelSU / Magisk → 模块 → 移除 → 重启。

配置文件位于 `/data/adb/spk-eq/`，卸载脚本会自动清理。

---

## 目录结构

```
/data/adb/spk-eq/
├── conf           EQ 曲线（主要修改对象）
├── mbc            多段压缩（可选）
├── limiter        限幅阈值（可选）
├── boot.log       开机启动日志
└── service.log    运行日志，每次应用配置都会记录
```

---

## 从源码构建

```bash
# 需要 Android SDK 的 android.jar 与 build-tools（提供 d8）
export ANDROID_JAR=~/android-sdk/platforms/android-36/android.jar
./build.sh
```

产物为 `spk-eq-v<版本>.zip`。

---

## 常见问题

**装了没效果？**

先确认服务是否在运行：

```bash
su -c "ps -A | grep DynEqService"
su -c "tail -20 /data/adb/spk-eq/service.log"
su -c "cat /data/adb/spk-eq/boot.log"
```

`service.log` 里应能看到 `APPLY ...` 记录。

**会不会和别的音效模块冲突？**

会。全局混音层只能挂一个 AudioEffect。安装前先卸载其他音效类模块。

**会不会损坏扬声器？**

默认曲线最大提升 +7 dB，配合常开的限幅器（阈值 −1 dB）。
长期使用无异常，但**任何 EQ 模块都存在风险**，请自行判断。

**能只对某个 App 生效吗？**

不能。这是全局混音层。

**支持其他机型吗？**

代码本身通用（`DynamicsProcessing` 是 AOSP 公开 API）。
但默认曲线是针对 OnePlus 13 的扬声器实测调校的。
换机型建议先用扫频测出该机喇叭的 f0，再重新设计曲线。

---

## 技术栈

- `DynamicsProcessing`（AOSP `libeffects`，Apache-2.0）
- FFT 频域处理，`blockSize = 512`，bin 宽 93.75 Hz
- `app_process` 执行 dex，无常驻 APK
- KernelSU 模块框架

---

## 免责声明

修改系统音频输出存在风险。使用前请确认你具备刷机与恢复的能力。
作者不对任何设备损坏承担责任。

---

## License

[MIT](LICENSE) © 2026 600斤的立子
