import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import java.io.*;

/**
 * DynEq 常驻服务 —— 10 段可编程 EQ（DynamicsProcessing）
 *
 * 为什么需要常驻
 *   AudioEffect 的生命周期绑定在持有它的进程上
 *   进程一退出，effect 自动销毁、音效失效
 *   → 必须像 EqService 那样常驻，循环读配置、即时生效
 *
 * 配置文件 /data/adb/spk-eq/conf
 *   每行 "频率Hz:增益dB"，最多 10 行；空行或以 # 开头忽略
 *   例：
 *     120:4
 *     400:2
 *     1500:0
 *     4000:-2
 *     9000:1
 *   文件不存在或全 0 → 自动旁路（关闭 EQ）
 *
 * 日志 /data/adb/spk-eq/service.log
 */
public class DynEqService {

    static final int SESSION = 0;
    static final int CHANNELS = 2;
    static final int MAXB = 14;
    static final String CONF = "/data/adb/spk-eq/conf";
    static final String LOG  = "/data/adb/spk-eq/service.log";

    static void log(String s) {
        String line = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()) + "  " + s;
        System.out.println(line);
        try (FileWriter w = new FileWriter(LOG, true)) { w.write(line + "\n"); } catch (Throwable ignored) {}
    }

    /** 解析配置，返回 [freq[], gain[]]，n 放在 n[0] */
    static int parse(float[] fr, float[] gn) {
        File f = new File(CONF);
        if (!f.exists()) return 0;
        int n = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null && n < MAXB) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int p = line.indexOf(':');
                if (p <= 0) continue;
                fr[n] = Float.parseFloat(line.substring(0, p).trim());
                gn[n] = Float.parseFloat(line.substring(p + 1).trim());
                n++;
            }
        } catch (Throwable t) {
            log("parse error: " + t);
            return 0;
        }
        // 按频率升序排序（DynamicsProcessing 要求严格递增）
        for (int i = 1; i < n; i++) {
            float kf = fr[i], kg = gn[i];
            int j = i - 1;
            while (j >= 0 && fr[j] > kf) { fr[j + 1] = fr[j]; gn[j + 1] = gn[j]; j--; }
            fr[j + 1] = kf; gn[j + 1] = kg;
        }
        // 去重（频点相同会破坏递增）
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (m > 0 && Math.abs(fr[i] - fr[m - 1]) < 1f) { gn[m - 1] = gn[i]; continue; }
            fr[m] = fr[i]; gn[m] = gn[i]; m++;
        }
        return m;
    }

    static String sig(float[] fr, float[] gn, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(String.format("%.0f:%.1f ", fr[i], gn[i]));
        return sb.toString().trim();
    }


    // ==========================================================================
    // 输出路由检测 —— 让 EQ 只作用于内置扬声器
    //
    // 为什么需要
    //   DynamicsProcessing 挂在 SESSION 0（AUDIO_SESSION_OUTPUT_MIX）上，
    //   AOSP 的 createEffect_l() 允许 MIXER 类型线程挂全局 effect。
    //   扬声器和蓝牙 A2DP 都是 MIXER 线程 → effect 会自动跟到当前活跃的那个线程。
    //   实测：插着 OPPO Enco Free4 时 effect 挂在 AudioOut_AD（蓝牙线程），
    //   全频段 -15dB 时耳机里声音几乎消失，确认真实生效。
    //   K1 曲线是补偿这两颗物理喇叭的缺陷（650Hz -7dB 治 f0=714~758Hz 谐振），
    //   叠加到耳机上只会把声音弄坏 → 必须在非扬声器路由上停掉。
    //
    // 实现
    //   AudioManager.getDevicesForAttributes(MEDIA) 返回当前 MEDIA 输出的设备，
    //   元素是 AudioDeviceAttributes，getType() 给出 AudioDeviceInfo.TYPE_* 编号。
    //   实测（插蓝牙）返回 type=8 (TYPE_BLUETOOTH_A2DP)，干净可用。
    //
    //   注意：这个 Context 用反射从 ActivityThread 拿，调用前必须先 Looper.prepare()，
    //   否则 ActivityThread.systemMain() 内部 new Handler() 会抛
    //   "Can't create handler inside thread that has not called Looper.prepare()"。
    //   但只需要 prepare()，不需要 loop()：getDevicesForAttributes 是 binder 同步调用。
    //
    // 开关
    //   /data/adb/spk-eq/all_devices 文件存在 → 对所有设备生效（旧行为/回退用）
    //   不存在 → 只对内置扬声器生效（默认，用户要求）
    // ==========================================================================

    static final int TYPE_BUILTIN_SPEAKER      = 2;
    static final int TYPE_BUILTIN_SPEAKER_SAFE = 24;
    static final String ALL_DEVICES_FLAG = "/data/adb/spk-eq/all_devices";
    static final String OFF_FLAG = "/data/adb/spk-eq/off";

    static Object audioManager = null;
    static java.lang.reflect.Method mGetDevicesForAttr = null;
    static boolean routeInited = false;

    /** 懒初始化 AudioManager（反射拿 SystemContext，避免 VerifyError） */
    static void initRouteProbe() {
        if (routeInited) return;
        routeInited = true;
        try {
            android.os.Looper.prepare();
        } catch (Throwable ignored) {}
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Object at = atCls.getMethod("systemMain").invoke(null);
            android.content.Context ctx =
                    (android.content.Context) atCls.getMethod("getSystemContext").invoke(at);
            audioManager = ctx.getSystemService(android.content.Context.AUDIO_SERVICE);
            if (audioManager != null) {
                mGetDevicesForAttr = audioManager.getClass()
                        .getMethod("getDevicesForAttributes",
                                Class.forName("android.media.AudioAttributes"));
            }
            log("route probe ready (AudioManager=" + (audioManager != null) + ")");
        } catch (Throwable t) {
            Throwable c = t;
            while (c.getCause() != null) c = c.getCause();
            log("route probe init 失败: " + c.getClass().getSimpleName() + ": " + c.getMessage());
        }
    }

    /**
     * 当前 MEDIA 输出设备类型。
     * 返回 -1 表示检测失败（调用方应视为「扬声器」，保持原行为，避免误关）。
     */
    static int routeType() {
        if (!routeInited) initRouteProbe();
        if (audioManager == null || mGetDevicesForAttr == null) return -1;
        try {
            Object aa = Class.forName("android.media.AudioAttributes$Builder")
                    .getConstructor().newInstance();
            Object b = aa;
            b = b.getClass().getMethod("setUsage", int.class).invoke(b, 1);        // USAGE_MEDIA
            b = b.getClass().getMethod("setContentType", int.class).invoke(b, 2);  // CONTENT_TYPE_MUSIC
            Object attrs = b.getClass().getMethod("build").invoke(b);

            Object list = mGetDevicesForAttr.invoke(audioManager, attrs);
            java.util.List<?> devs = (java.util.List<?>) list;
            if (devs == null || devs.isEmpty()) return -1;
            Object d0 = devs.get(0);   // 主输出设备
            Object t = d0.getClass().getMethod("getType").invoke(d0);
            return ((Integer) t).intValue();
        } catch (Throwable t) {
            Throwable c = t;
            while (c.getCause() != null) c = c.getCause();
            log("routeType 失败: " + c.getClass().getSimpleName() + ": " + c.getMessage());
            return -1;
        }
    }

    static String routeName(int t) {
        switch (t) {
            case -1: return "unknown";
            case 0:  return "none";
            case 1:  return "earpiece";
            case 2:  return "speaker";
            case 3:  return "wired_headset";
            case 4:  return "wired_headphones";
            case 7:  return "bt_sco";
            case 8:  return "bt_a2dp";
            case 9:  return "hdmi";
            case 11: return "usb_device";
            case 12: return "usb_accessory";
            case 22: return "usb_headset";
            case 23: return "hearing_aid";
            case 24: return "speaker_safe";
            case 26: return "ble_headset";
            case 27: return "ble_speaker";
            default: return "type" + t;
        }
    }

    static DynamicsProcessing build(float[] fr, float[] gn, int n) {
        // 【关键】band 数量必须等于实际频段数 n
        // 之前用 MAXB=10 并把剩余 band 填成 40*2^i，会造成频点序列非单调
        // （如 20000 后面接 2560），DynamicsProcessing 要求严格递增，
        // 非单调会让滤波器系数算错 → 整个 EQ 输出不可信（出现过 bright 反而变闷）。
        int bands = Math.max(1, Math.min(MAXB, n));

        // ---- MBC（多段压缩）：解决"分离度"的关键 ----
        // EQ 是静态曲线，只能整体抬降；MBC 让每段动态独立工作，
        // 响的频段自动压、轻的频段浮现 → 听感上把糊在一起的声音分开
        java.util.List<float[]> mbc = new java.util.ArrayList<>();
        try {
            File mf = new File("/data/adb/spk-eq/mbc");
            if (mf.exists()) {
                for (String ln : java.nio.file.Files.readAllLines(mf.toPath())) {
                    ln = ln.trim();
                    if (ln.isEmpty() || ln.startsWith("#")) continue;
                    String[] p5 = ln.split(":");
                    if (p5.length < 3) continue;
                    float fq = Float.parseFloat(p5[0].trim());
                    float th = Float.parseFloat(p5[1].trim());
                    float ra = Float.parseFloat(p5[2].trim());
                    float at = p5.length > 3 ? Float.parseFloat(p5[3].trim()) : 5f;
                    float rl = p5.length > 4 ? Float.parseFloat(p5[4].trim()) : 80f;
                    mbc.add(new float[]{fq, th, ra, at, rl});
                }
            }
        } catch (Throwable ignored) {}
        int mbcN = Math.min(mbc.size(), MAXB);
        DynamicsProcessing.Channel[] chs = new DynamicsProcessing.Channel[CHANNELS];
        for (int c = 0; c < CHANNELS; c++) {
            chs[c] = new DynamicsProcessing.Channel(
                    0f, true, bands, mbcN > 0, mbcN, false, 0, true);
            for (int i = 0; i < bands; i++) {
                chs[c].getPreEq().setBand(i, new DynamicsProcessing.EqBand(true, fr[i], gn[i]));
            }
            // MbcBand(enabled, cutoff, attack, release, ratio, threshold,
            //         kneeWidth, noiseGateThreshold, expanderRatio, preGain, postGain)
            for (int i = 0; i < mbcN; i++) {
                float[] m = mbc.get(i);
                chs[c].getMbc().setBand(i, new DynamicsProcessing.MbcBand(
                        true, m[0], m[3], m[4], m[2], m[1],
                        0f, -60f, 1f, 0f, 0f));
            }
            // limiter 常开：这是防削波失真的保险
            // 实测 -2dB / 10:1 并不会把 EQ 抬升压回原状（×2 ×3 都清晰可闻），
            // 之前的"limiter 吃掉 boost"是误判——dump 读到的是系统默认 config，
            // 不是本服务实例的 config。
            // limiter：threshold 由 /data/adb/spk-dyn/limiter 控制（内容为 dB）
            //  不存在 → -1.0（默认）
            //  0     → 基本不限（阀值 0dB，几乎不动作）
            //  -99   → 完全关闭
            float limThr = -1f;
            boolean limOn = true;
            try {
                File lf = new File("/data/adb/spk-eq/limiter");
                if (lf.exists()) {
                    String t = new String(java.nio.file.Files.readAllBytes(lf.toPath())).trim();
                    if (t.equals("-99")) { limOn = false; }
                    else if (!t.isEmpty()) { limThr = Float.parseFloat(t); }
                }
            } catch (Throwable ignored) {}
            chs[c].getLimiter().setEnabled(limOn);
            chs[c].getLimiter().setThreshold(limThr);
            chs[c].getLimiter().setRatio(10f);
            chs[c].getLimiter().setAttackTime(5f);
            chs[c].getLimiter().setReleaseTime(60f);
            chs[c].getLimiter().setPostGain(0f);
        }
        boolean limUse = true;
        DynamicsProcessing.Config.Builder bb = new DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                CHANNELS, true, bands, mbcN > 0, mbcN, false, 0, limUse);
        for (int c = 0; c < CHANNELS; c++) bb.setChannelTo(c, chs[c]);
        return new DynamicsProcessing(0, SESSION, bb.build());
    }

    // ==========================================================================
    // 单实例锁
    //
    // 为什么必须有
    //   每个实例都会往同一个 session 0 上挂一个 DynamicsProcessing。
    //   挂 3 个 = 同一条 EQ 曲线被叠了 3 次，低频能叠到 +21dB。
    //   实测出现过 3 个实例并存（旧开机脚本 + 模块 service.sh + 执行按钮各拉一个），
    //   声音严重失真，而 dumpsys 里只看得到「3 Clients」，很容易被忽略。
    //
    // 实现
    //   FileChannel.tryLock() 由内核持有，进程被 kill -9 时自动释放，不会留下死锁。
    //   拿不到锁 = 已有实例在跑 → 本进程直接退出，绝不重复挂 effect。
    // ==========================================================================
    static final String LOCK = "/data/adb/spk-eq/service.lock";
    static RandomAccessFile lockRaf = null;
    static java.nio.channels.FileLock lockObj = null;

    /** 尝试独占；成功返回 true，已有实例返回 false */
    static boolean acquireLock() {
        try {
            lockRaf = new RandomAccessFile(LOCK, "rw");
            lockObj = lockRaf.getChannel().tryLock();
            if (lockObj == null) {
                // 读出对方 pid 便于排查
                String other = "";
                try {
                    lockRaf.seek(0);
                    byte[] b = new byte[32];
                    int r = lockRaf.read(b);
                    if (r > 0) other = new String(b, 0, r).trim();
                } catch (Throwable ignored) {}
                log("已有实例在运行 (pid=" + (other.isEmpty() ? "?" : other) + ")，本进程退出");
                return false;
            }
            // 拿到锁：写入自己的 pid
            lockRaf.seek(0);
            lockRaf.setLength(0);
            lockRaf.write((android.os.Process.myPid() + "\n").getBytes());
            lockRaf.getChannel().force(true);
            return true;
        } catch (Throwable t) {
            // 锁文件异常不应导致音效失效，降级为无锁运行
            log("lock 异常（降级无锁运行）: " + t.getClass().getSimpleName());
            return true;
        }
    }

    public static void main(String[] args) {
        if (!acquireLock()) {
            System.out.println("已存在运行中的实例，退出");
            return;
        }
        log("=== DynEqService start (DynamicsProcessing, " + MAXB + " bands) pid="
                + android.os.Process.myPid() + " ===");

        boolean allDev = new File(ALL_DEVICES_FLAG).exists();
        if (new File(OFF_FLAG).exists()) {
            log("作用范围: 总开关=关（模块不生效，删 " + OFF_FLAG + " 恢复）");
        } else if (allDev) {
            log("作用范围: 所有输出设备（all_devices 开关存在）");
        } else {
            log("作用范围: 仅内置扬声器（蓝牙/USB 自动旁路）");
        }

        DynamicsProcessing dp = null;
        String last = null;
        int lastRoute = Integer.MIN_VALUE;
        float[] fr = new float[MAXB], gn = new float[MAXB];

        while (true) {
            try {
                int n = parse(fr, gn);
                String cur = sig(fr, gn, n);
                // mbc 文件内容也纳入变化检测
                try {
                    File mf = new File("/data/adb/spk-eq/mbc");
                    if (mf.exists()) cur += " |mbc:" + new String(java.nio.file.Files.readAllBytes(mf.toPath())).replace("\n", ";");
                } catch (Throwable ignored) {}

                // ---- 路由判定：非扬声器就强制旁路 ----
                // 总开关优先：/data/adb/spk-eq/off 存在 → 整个模块停止工作。
                // 用途一：自动识别失效时（罕见 USB DAC）手动兜底。
                // 用途二：KernelSU 模块卡片的「执行」按钮就是切换这个文件。
                boolean forceOff = new File(OFF_FLAG).exists();
                boolean routeOk;
                if (forceOff) {
                    routeOk = false;
                } else if (allDev) {
                    routeOk = true;
                } else {
                    int rt = routeType();
                    routeOk = (rt == TYPE_BUILTIN_SPEAKER
                            || rt == TYPE_BUILTIN_SPEAKER_SAFE
                            || rt == -1);   // -1 检测失败 → 保守视为扬声器，不让音效凭空消失
                    if (rt != lastRoute) {
                        log("路由变化 -> " + routeName(rt) + "(" + rt + ")"
                                + (routeOk ? "  生效" : "  旁路"));
                        lastRoute = rt;
                    }
                }

                // 路由状态 + 总开关状态并入签名，
                // 任一变化（插拔耳机 / 点模块卡片按钮）都立刻重建
                String want = cur + (forceOff ? " |MASTER_OFF" : routeOk ? " |ON" : " |ROUTE_OFF");

                if (!want.equals(last)) {
                    boolean allZero = true;
                    for (int i = 0; i < n; i++) if (Math.abs(gn[i]) > 0.01f) { allZero = false; break; }

                    if (dp != null) { try { dp.release(); } catch (Throwable ignored) {} dp = null; }

                    if (forceOff || !routeOk) {
                        log(forceOff
                                ? "BYPASS (总开关已关闭模块，已卸载 effect)"
                                : "BYPASS (非扬声器输出，已卸载 effect)");
                    } else if (n == 0 || allZero) {
                        log("BYPASS (no bands or all zero)");
                    } else {
                        dp = build(fr, gn, n);
                        dp.setEnabled(true);
                        AudioEffect.Descriptor d = dp.getDescriptor();
                        log("APPLY " + cur + "  (" + d.name + ")");
                    }
                    last = want;
                }
            } catch (Throwable t) {
                log("loop error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            try { Thread.sleep(500); } catch (Throwable ignored) {}
        }
    }
}
