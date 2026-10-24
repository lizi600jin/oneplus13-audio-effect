# GitHub 发布文案

---

## 仓库名

```
oneplus13-spk-eq
```

---

## About（仓库简介，140 字符内）

```
一加13 双扬声器全局调音｜KernelSU 模块｜从功放寄存器实测出发设计的 10 段频域 EQ｜改配置即时生效
```

English：

```
Global speaker tuning for OnePlus 13 | KernelSU module | 10-band FFT-domain EQ designed from measured amp-register data | hot-reload
```

---

## README.md

**直接用仓库根目录的 `README.md`**（与本文件同目录，已按最新版写好）。

核心结构：

1. 先说结论 —— 三条硬件实测（衰减量 64/48、越小越响、f0 714~758 Hz）
2. 这三条实测怎么决定曲线
3. 默认曲线表
4. 实现机制（FFT 分段，不是钟形滤波器）
5. 安装 / 改曲线 / 可选 MBC / 可选限幅器
6. 卸载 / 目录 / 编译 / FAQ

---

## Release Notes（v1.1 · 首发）

> 这是**第一版**，文案里不出现「这次更新 / 本次变更」这类说法。

```markdown
首发版本。

## 这个模块和一个普通 EQ 模块的区别

曲线不是拖滑块拖出来的，是从一加13 的功放芯片上量出数据后反推画的。

### 实测一：两颗喇叭出厂衰减量本来就不同

功放 AW88461（上扬、下扬各一颗）的寄存器 `0x05` 低 10 位是衰减量：

| 喇叭 | 寄存器读回 | 衰减量 |
|---|---|---|
| 上扬（顶部） | `0xc040` | 64 |
| 下扬（底部） | `0xc030` | 48 |

### 实测二：这个数字越小，声音越大

```
0    → 最大
48   → 原厂下扬
64   → 原厂上扬
1023 → 完全静音（那颗喇叭彻底不响）
```

拉到 1023 时喇叭会完全闭嘴、只剩另一颗在响，这个可以直接听出来。
所以「加大数字」是把声音调小，方向别弄反。

### 实测三：喇叭谐振点在 714~758 Hz

```
上扬 758 Hz    下扬 714 Hz
```

这个位置正好夹在人声基频下沿和低频乐器基频上沿之间，两边能量在这儿互相
掩蔽 —— 这就是「糊」「分不开」「杂糅」的来源。

**所以单纯拉高低频没用**：拉的是 200 Hz，糊在 700 Hz，越拉越糊。
正确做法是先把掩蔽区挖掉，再补回该补的地方。

## 默认曲线怎么来的

| 实测结论 | 曲线处理 |
|---|---|
| f0 714~758 Hz 是掩蔽区 | 削 469~750 Hz（−7 dB），把糊的根挖掉 |
| 挖掉后偏单薄 | 补 200 Hz 冲击 + 1.2~3.6 kHz 人声主体 |
| 中高频有空隙会「脱节」 | 在 4500 Hz 补 1 dB 衔接 |
| 虚拟歌姬/女声齿音在 5~9 kHz | 抬 8115 Hz 做亮度，5.6 kHz 稍压防刺 |

默认曲线按虚拟歌姬 / 女声向内容调校，但**所有参数都能改**，不绑定曲风。

## 功能

- 10 段参数 EQ，全局混音输出生效（所有 App 都走它）
- FFT 频域分段，`blockSize = 512`，最小可控宽度 93.75 Hz
- 可选多段压缩（MBC），改善静态 EQ 治不了的分离度问题
- 常开限幅器（默认 −1 dB）
- 改 `/data/adb/spk-eq/conf` **1 秒内生效**，不用重启
- 开机自启，无常驻 APK（`app_process` 直接跑 dex）

## 安装

1. 下载 `spk-eq-v1.1.zip`
2. KernelSU / Magisk → 模块 → 从本地安装
3. 重启

**环境要求**

- 一加13（PJZ110）
- KernelSU 或 Magisk（root）
- Android 14 以上
- 装之前先卸掉其他音效模块（全局混音层只能挂一个音效）

## 已知限制

- 全局生效，不能只对单个 App
- 与其他音效模块互斥（session 0）
- 参数极端时，段间台阶状硬跳变可能产生瞬态毛刺
- 曲线是针对一加13 调的，换机型需要重量 f0 重新设计
```

---

## Topics

```
kernelsu  magisk  magisk-module  audio  equalizer  android  oneplus  oneplus13  dsp  audioeffect  dynamicsprocessing
```

---

## 项目结构

```
oneplus13-spk-eq/
├── README.md
├── LICENSE                     MIT
├── build.sh                    一键编译打包
├── module/
│   ├── module.prop
│   ├── customize.sh
│   ├── service.sh
│   ├── action.sh
│   └── uninstall.sh
├── src/
│   └── DynEqService.java
└── docs/
    ├── 方案说明.md
    └── eq-curve.png
```

---

## 首条 Release 标题

```
v1.1 —— 一加13 扬声器音效（首个版本）
```
