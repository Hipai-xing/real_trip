package shipment;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 临时诊断: 同步陈旧度(check_in - last_sync)是否解释 quota=0/低配额却被收货。用完即删。 */
public final class Scratch {
    public static void main(String[] a) throws Exception {
        List<Shipment> all = new ArrayList<>();
        List<String> lines = Files.readAllLines(Path.of(a[0]), StandardCharsets.UTF_8);
        boolean header = true;
        for (String ln : lines) {
            String s = ln.strip();
            if (s.isEmpty()) continue;
            if (header) { header = false; continue; }
            Shipment r = Analyzer.parseLine(s);
            if (r != null) all.add(r);
        }
        // 去重 + 有效状态
        Map<String, Shipment> first = new LinkedHashMap<>();
        for (Shipment d : all) first.putIfAbsent(d.id(), d);
        List<Shipment> clean = new ArrayList<>();
        for (Shipment d : first.values()) if (d.hasValidStatus()) clean.add(d);

        System.out.println("有效样本: " + clean.size());

        // 1) 全局陈旧度: 是否存在 last_sync 晚于 check_in(负差, 配额在到货后才同步)
        long neg = 0, pos = 0, zero = 0, nullg = 0;
        double maxNeg = 0, maxPos = 0;
        for (Shipment d : clean) {
            Double g = d.stalenessHours();
            if (g == null) { nullg++; continue; }
            if (g < 0) { neg++; maxNeg = Math.min(maxNeg, g); }
            else if (g > 0) { pos++; maxPos = Math.max(maxPos, g); }
            else zero++;
        }
        System.out.printf("陈旧度符号: 正常(check_in>=last_sync) pos=%d zero=%d ; 异常(last_sync晚于check_in) neg=%d ; null=%d%n", pos, zero, neg, nullg);
        System.out.printf("最大正差(小时)=%.2f  最大负差(小时)=%.2f%n", maxPos, maxNeg);

        // 2) quota=0 记录: 收货 vs 拒收 的 gap 对比
        statGroup(clean, "quota=0 全部", d -> d.quotaRemaining() != null && d.quotaRemaining() == 0);
        statGroup(clean, "quota=0 且收货", d -> d.quotaRemaining() != null && d.quotaRemaining() == 0 && !d.isRejected());
        statGroup(clean, "quota=0 且拒收", d -> d.quotaRemaining() != null && d.quotaRemaining() == 0 && d.isRejected());

        // 3) 低配额(1-5) 收货 vs 拒收 gap
        statGroup(clean, "q1-5 且收货", d -> d.quotaRemaining() != null && d.quotaRemaining() >= 1 && d.quotaRemaining() <= 5 && !d.isRejected());
        statGroup(clean, "q1-5 且拒收", d -> d.quotaRemaining() != null && d.quotaRemaining() >= 1 && d.quotaRemaining() <= 5 && d.isRejected());

        // 4) gap 是否跨自然日(Asia/Shanghai)——跨天才可能有配额重置/放量
        long crossDay = 0, sameDay = 0;
        for (Shipment d : clean) {
            if (d.checkInTime() == null || d.lastSyncTime() == null) continue;
            String cd = d.checkInTime().atZone(java.time.ZoneOffset.ofHours(8)).toLocalDate().toString();
            String sd = d.lastSyncTime().atZone(java.time.ZoneOffset.ofHours(8)).toLocalDate().toString();
            if (cd.equals(sd)) sameDay++; else crossDay++;
        }
        System.out.printf("同步→到货是否跨自然日(+08): 同日=%d 跨日=%d%n", sameDay, crossDay);

        // 5) 相关性: 全体 gap 与 拒收 是否有关系(点二列粗略: 收货均值 vs 拒收均值)
        statGroup(clean, "全体 收货", d -> !d.isRejected());
        statGroup(clean, "全体 拒收", d -> d.isRejected());

        // 6) 按时间差分档看拒收率(是否越陈旧越易拒收)
        System.out.println("[按 同步→到货 时间差 gap 分档的拒收率]");
        double[][] bands = {{0, 0.1}, {0.1, 0.3}, {0.3, 0.5}, {0.5, 0.8}, {0.8, 99}};
        for (double[] b : bands) {
            int n = 0, rj = 0;
            for (Shipment d : clean) {
                Double g = d.stalenessHours();
                if (g == null) continue;
                if (g >= b[0] && g < b[1]) { n++; if (d.isRejected()) rj++; }
            }
            System.out.printf("  gap[%.1f,%.1f)h  样本%3d 拒收%3d 拒收率%.1f%%%n", b[0], b[1], n, rj, n == 0 ? 0 : 100.0 * rj / n);
        }

        // 7) 经湁术: gap 阈值预警 vs 现有最优规则(同口径 净省=命中*90-预警*12)
        int totalRejects = 0;
        for (Shipment d : clean) if (d.isRejected()) totalRejects++;
        long baseLoss = (long) totalRejects * 90;
        System.out.println("[规则经济账 对比]");
        evalGap(clean, 0.2, totalRejects, baseLoss);
        evalGap(clean, 0.3, totalRejects, baseLoss);
        evalGap(clean, 0.5, totalRejects, baseLoss);
        // 现有最优: ratio>=5 OR quota<=7
        int fl = 0, hit = 0;
        for (Shipment d : clean) {
            boolean f = d.overRatio() >= 5 || (d.quotaRemaining() != null && d.quotaRemaining() <= 7);
            if (f) { fl++; if (d.isRejected()) hit++; }
        }
        System.out.printf("  旧最优 ratio>=5 OR quota<=7  预警%3d 命中%3d 精准%5.1f%% 召回%5.1f%% 净省%5d%n",
                fl, hit, 100.0 * hit / fl, 100.0 * hit / totalRejects, hit * 90L - fl * 12L);

        // 8) 控制 gap 后, 超额比是否还有独立信号(查共线性)
        System.out.println("[分层: 新鲜gap(<0.3h) vs 陈旧gap(>=0.3h) 内部, 按 ratio>=5 拆]");
        strata(clean, "新鲜且ratio<5", d -> d.stalenessHours() != null && d.stalenessHours() < 0.3 && d.overRatio() < 5);
        strata(clean, "新鲜且ratio>=5", d -> d.stalenessHours() != null && d.stalenessHours() < 0.3 && d.overRatio() >= 5);
        strata(clean, "陈旧且ratio<5", d -> d.stalenessHours() != null && d.stalenessHours() >= 0.3 && d.overRatio() < 5);
        strata(clean, "陈旧且ratio>=5", d -> d.stalenessHours() != null && d.stalenessHours() >= 0.3 && d.overRatio() >= 5);
    }

    static void strata(List<Shipment> clean, String label, java.util.function.Predicate<Shipment> p) {
        int n = 0, rj = 0;
        for (Shipment d : clean) { if (p.test(d)) { n++; if (d.isRejected()) rj++; } }
        System.out.printf("  %-16s 样本%3d 拒收%3d 拒收率%.1f%%%n", label, n, rj, n == 0 ? 0 : 100.0 * rj / n);
    }

    static void evalGap(List<Shipment> clean, double th, int totalRejects, long baseLoss) {
        int fl = 0, hit = 0;
        for (Shipment d : clean) {
            Double g = d.stalenessHours();
            if (g != null && g >= th) { fl++; if (d.isRejected()) hit++; }
        }
        System.out.printf("  新 gap>=%.1fh               预警%3d 命中%3d 精准%5.1f%% 召回%5.1f%% 净省%5d (降幅%.1f%%)%n",
                th, fl, hit, 100.0 * hit / fl, 100.0 * hit / totalRejects,
                hit * 90L - fl * 12L, 100.0 * (hit * 90L - fl * 12L) / baseLoss);
    }

    static void statGroup(List<Shipment> clean, String label, java.util.function.Predicate<Shipment> p) {
        List<Double> gs = new ArrayList<>();
        int n = 0;
        for (Shipment d : clean) {
            if (!p.test(d)) continue;
            n++;
            Double g = d.stalenessHours();
            if (g != null) gs.add(g);
        }
        if (gs.isEmpty()) { System.out.printf("  %-14s 样本%3d gap无%n", label, n); return; }
        Collections.sort(gs);
        double mean = gs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double median = gs.get(gs.size() / 2);
        System.out.printf("  %-14s 样本%3d gap(小时): 最小%.2f 中位%.2f 最大%.2f 均值%.2f%n",
                label, n, gs.get(0), median, gs.get(gs.size() - 1), mean);
    }
}
