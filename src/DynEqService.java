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

    public static void main(String[] args) {
        log("=== DynEqService start (DynamicsProcessing, " + MAXB + " bands) ===");
        DynamicsProcessing dp = null;
        String last = null;
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

                if (!cur.equals(last)) {
                    boolean allZero = true;
                    for (int i = 0; i < n; i++) if (Math.abs(gn[i]) > 0.01f) { allZero = false; break; }

                    if (dp != null) { try { dp.release(); } catch (Throwable ignored) {} dp = null; }

                    if (n == 0 || allZero) {
                        log("BYPASS (no bands or all zero)");
                    } else {
                        dp = build(fr, gn, n);
                        dp.setEnabled(true);
                        AudioEffect.Descriptor d = dp.getDescriptor();
                        log("APPLY " + cur + "  (" + d.name + ")");
                    }
                    last = cur;
                }
            } catch (Throwable t) {
                log("loop error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            try { Thread.sleep(200); } catch (Throwable ignored) {}
        }
    }
}
