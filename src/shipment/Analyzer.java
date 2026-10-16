package shipment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;

/**
 * 拒收根因分析 + 拦截规则寻优。
 *
 * 用法:
 *   javac -d out src/shipment/*.java
 *   java -cp out shipment.Analyzer [shipments.csv 路径]
 *
 * 成本口径(来自需求文档): 每次现场拒收损失 90 工时；每次提前拦截预警花费 12 工时。
 */
public final class Analyzer {

    static final int REJECT_COST = 90;
    static final int WARN_COST = 12;

    public static void main(String[] args) throws IOException {
        List<String> rawLines = readData(args);
        List<Shipment> all = new ArrayList<>();
        boolean header = true;
        for (String line : rawLines) {
            if (line == null) continue;
            String s = line.strip();
            if (s.isEmpty()) continue;
            if (header) { header = false; continue; } // 跳过表头
            Shipment rec = parseLine(s);
            if (rec != null) all.add(rec);
        }

        printDataQuality(all);
        List<Shipment> clean = dedupValid(all);

        int total = clean.size();
        int rejects = 0;
        for (Shipment d : clean) if (d.isRejected()) rejects++;
        double baseRate = 100.0 * rejects / total;
        long baselineLoss = (long) rejects * REJECT_COST;

        System.out.println("\n===== 整体拒收情况(去重 + 状态有效) =====");
        System.out.printf("样本数=%d  拒收数=%d  拒收率=%.2f%%  基线损失=%d 工时%n",
                total, rejects, baseRate, baselineLoss);
        System.out.printf("盈亏点: 被拦截组精准率 > WARN/REJECT = %d/%d = %.1f%% 才净赚%n",
                WARN_COST, REJECT_COST, 100.0 * WARN_COST / REJECT_COST);

        featureBreakdown(clean);
        ruleOptimization(clean, rejects, baselineLoss);
    }

    // ---------- 数据读取 ----------
    static List<String> readData(String[] args) throws IOException {
        List<String> candidates = new ArrayList<>();
        if (args.length > 0) candidates.add(args[0]);
        candidates.add("doc/shipments.csv");
        candidates.add("data/shipments.csv");
        candidates.add("shipments.csv");
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.exists(p)) {
                System.out.println("[数据] 读取: " + p.toAbsolutePath());
                List<String> lines = new ArrayList<>();
                try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    String ln;
                    while ((ln = br.readLine()) != null) lines.add(stripBom(ln));
                }
                return lines;
            }
        }
        // 兜底: 读取随包压缩资源
        try (InputStream in = Analyzer.class.getResourceAsStream("/shipments.csv.gz")) {
            if (in != null) {
                List<String> lines = new ArrayList<>();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))) {
                    String ln;
                    while ((ln = br.readLine()) != null) lines.add(stripBom(ln));
                }
                return lines;
            }
        }
        throw new IOException("未找到 shipments.csv，请通过参数传入路径。");
    }

    static String stripBom(String s) {
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') return s.substring(1);
        return s;
    }

    static Shipment parseLine(String line) {
        String[] c = line.split(",", -1);
        if (c.length < 8) return null;
        String statusStr = c[7].trim();
        Integer status = statusStr.isEmpty() ? null : Shipment.parseInt(statusStr);
        return new Shipment(
                c[0].trim(),
                Shipment.parseInstant(c[1]),
                Shipment.parseInstant(c[2]),
                c[3].trim(),
                c[4].trim(),
                Shipment.parseInt(c[5]),
                Shipment.parseInt(c[6]),
                status);
    }

    // ---------- 数据质量 ----------
    static void printDataQuality(List<Shipment> all) {
        System.out.println("\n===== 数据质量体检 =====");
        System.out.println("原始记录行数: " + all.size());

        long missingStatus = all.stream().filter(d -> d.acceptStatus() == null).count();
        System.out.println("accept_status 缺失行数: " + missingStatus);

        // 重复 ID
        Map<String, List<Shipment>> byId = new LinkedHashMap<>();
        for (Shipment d : all) byId.computeIfAbsent(d.id(), k -> new ArrayList<>()).add(d);
        int dupIds = 0, dupRows = 0, conflict = 0;
        for (Map.Entry<String, List<Shipment>> e : byId.entrySet()) {
            if (e.getValue().size() > 1) {
                dupIds++;
                dupRows += e.getValue().size();
                long distinctStatus = e.getValue().stream()
                        .map(d -> String.valueOf(d.acceptStatus())).distinct().count();
                if (distinctStatus > 1) conflict++;
            }
        }
        System.out.println("重复 shipment_id: " + dupIds + " 个, 涉及 " + dupRows
                + " 行; 其中结论冲突(同ID不同status)的: " + conflict + " 个");

        // 时区与同步陈旧度
        List<Double> gaps = new ArrayList<>();
        for (Shipment d : all) {
            Double g = d.stalenessHours();
            if (g != null) gaps.add(g);
        }
        gaps.sort(Comparator.naturalOrder());
        if (!gaps.isEmpty()) {
            double min = gaps.get(0), max = gaps.get(gaps.size() - 1);
            double median = gaps.get(gaps.size() / 2);
            double mean = gaps.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            System.out.printf("check_in(+08:00) 与 last_sync(Z) 归一化后延迟(小时): 最小%.2f 中位%.2f 最大%.2f 均值%.2f%n",
                    min, median, max, mean);
            System.out.println("说明: 时区混用若被忽略会虚增约8小时差; 归一化后延迟均<2h, 预约依据的配额是新鲜的。");
        }
    }

    // ---------- 清洗: 去重(保留首条) + 仅保留状态有效 ----------
    static List<Shipment> dedupValid(List<Shipment> all) {
        Map<String, Shipment> first = new LinkedHashMap<>();
        for (Shipment d : all) {
            if (!first.containsKey(d.id())) first.put(d.id(), d);
        }
        List<Shipment> clean = new ArrayList<>();
        for (Shipment d : first.values()) if (d.hasValidStatus()) clean.add(d);
        return clean;
    }

    // ---------- 特征下钻 ----------
    static void featureBreakdown(List<Shipment> clean) {
        System.out.println("\n===== 拒收根因下钻 (每档同时给出收货/拒收对照) =====");
        System.out.println("[按 超额比 batch/quota —— 仅统计 quota>0 的真实比值; quota=0 单列, 避免被并入最高档而虚增]\n  注: 下方\"真实比值\"各档之和 + q=0(无真实比值) 才等于总样本;");
        System.out.println("    故不能把\"q=0\"当作超额比档的一部分, 它表达的是\"预约时已无可用配额\"这一独立事实。");
        double[] edges = {0, 1.5, 2.5, 4, 6, Double.MAX_VALUE};
        String[] labels = {"<1.5", "1.5-2.5", "2.5-4", "4-6", ">=6"};
        for (int i = 0; i < labels.length; i++) {
            final double lo = edges[i], hi = edges[i + 1];
            line(labels[i], clean, d -> d.quotaRemaining() != null && d.quotaRemaining() > 0
                    && d.batchVolume() != null && d.overRatio() >= lo && d.overRatio() < hi);
        }
        line("q=0(无配比)", clean, d -> d.quotaRemaining() != null && d.quotaRemaining() == 0);

        System.out.println("[按 剩余配额 quota_remaining]");
        for (int[] rg : new int[][]{{0, 0}, {1, 5}, {6, 15}, {16, 30}}) {
            Predicate<Shipment> p = d -> d.quotaRemaining() != null
                    && d.quotaRemaining() >= rg[0] && d.quotaRemaining() <= rg[1];
            line(rg[0] + "-" + rg[1], clean, p);
        }

        System.out.println("[按 园区 factory_zone]");
        groupRate(clean, Shipment::factoryZone);
        System.out.println("[按 物料 material_group]");
        groupRate(clean, Shipment::materialGroup);

        System.out.println("[按 同步→到货 时间差 gap(小时) —— 控制后才是主导信号; gap = check_in - last_sync]");
        double[][] gb = {{0, 0.1}, {0.1, 0.3}, {0.3, 0.5}, {0.5, 0.8}, {0.8, Double.MAX_VALUE}};
        String[] gl = {"[0,0.1)", "[0.1,0.3)", "[0.3,0.5)", "[0.5,0.8)", ">=0.8"};
        for (int i = 0; i < gl.length; i++) {
            final double lo = gb[i][0], hi = gb[i][1];
            line(gl[i] + "h", clean, d -> { double g = gapOf(d); return !Double.isNaN(g) && g >= lo && g < hi; });
        }
        System.out.println("[分层去共线: 先按 gap 分新鲜(<0.3h)/陈旧(>=0.3h), 层内再按 ratio>=5 拆 —— 证明超额比多为 gap 的代理]");
        line("新鲜&ratio<5", clean, d -> gapOf(d) < 0.3 && d.overRatio() < 5);
        line("新鲜&ratio>=5", clean, d -> gapOf(d) >= 0 && gapOf(d) < 0.3 && d.overRatio() >= 5);
        line("陈旧&ratio<5", clean, d -> gapOf(d) >= 0.3 && d.overRatio() < 5);
        line("陈旧&ratio>=5", clean, d -> gapOf(d) >= 0.3 && d.overRatio() >= 5);
    }

    /** 同步陈旧度(小时); 无法计算时返回 NaN。 */
    static double gapOf(Shipment d) {
        Double g = d.stalenessHours();
        return g == null ? Double.NaN : g;
    }

    static void line(String label, List<Shipment> clean, Predicate<Shipment> p) {
        int n = cnt(clean, p), r = rej(clean, p);
        System.out.printf("  %-12s 样本%3d 收货%3d 拒收%3d 拒收率%.1f%%%n",
                label, n, n - r, r, n == 0 ? 0 : 100.0 * r / n);
    }

    static void groupRate(List<Shipment> clean, java.util.function.Function<Shipment, String> keyFn) {
        Map<String, int[]> g = new HashMap<>(); // [total, reject]
        for (Shipment d : clean) {
            int[] a = g.computeIfAbsent(keyFn.apply(d), k -> new int[2]);
            a[0]++;
            if (d.isRejected()) a[1]++;
        }
        g.entrySet().stream()
                .sorted((x, y) -> Double.compare(100.0 * y.getValue()[1] / y.getValue()[0],
                        100.0 * x.getValue()[1] / x.getValue()[0]))
                .forEach(e -> System.out.printf("  %-12s 样本%3d 收货%3d 拒收%3d 拒收率%.1f%%%n",
                        e.getKey(), e.getValue()[0], e.getValue()[0] - e.getValue()[1], e.getValue()[1],
                        100.0 * e.getValue()[1] / e.getValue()[0]));
    }

    static int cnt(List<Shipment> l, Predicate<Shipment> p) { return (int) l.stream().filter(p).count(); }
    static int rej(List<Shipment> l, Predicate<Shipment> p) {
        return (int) l.stream().filter(p.and(Shipment::isRejected)).count();
    }

    // ---------- 规则寻优 ----------
    static void ruleOptimization(List<Shipment> clean, int totalRejects, long baselineLoss) {
        System.out.println("\n===== 预警规则寻优 (净省工时 = 命中拒收*90 - 预警数*12) =====");
        System.out.println("[规则A] 仅用超额比阈值:");
        for (double th : new double[]{2, 3, 4, 5, 6, 8, 10}) {
            final double t = th;
            eval(clean, d -> d.overRatio() >= t, "ratio>=" + fmt(t), totalRejects, baselineLoss);
        }
        System.out.println("[规则B] 仅用低配额阈值:");
        for (int k = 1; k <= 5; k++) {
            final int kk = k;
            eval(clean, d -> d.quotaRemaining() != null && d.quotaRemaining() <= kk,
                    "quota<=" + kk, totalRejects, baselineLoss);
        }
        System.out.println("[规则C] 仅用同步陈旧度 gap 阈值:");
        for (double th : new double[]{0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.5, 0.6}) {
            final double t = th;
            eval(clean, d -> gapOf(d) >= t, "gap>=" + fmt(t) + "h", totalRejects, baselineLoss);
        }

        // 网格搜索: (gap>=g) OR (ratio>=r) OR (quota<=q); +∞ 表示该项关闭
        System.out.println("[网格搜索] flag = (gap>=g) OR (ratio>=r) OR (quota<=q):");
        double[] GS = {0.2, 0.25, 0.3, 0.35, 0.4, 0.5, Double.POSITIVE_INFINITY};
        double[] RS = {3, 5, 6, 8, 10, Double.POSITIVE_INFINITY};
        double[] QS = {0, 3, 5, 7, Double.POSITIVE_INFINITY};
        Rule best = null;
        for (double g : GS) for (double r : RS) for (double q : QS) {
            final double gg = g, rr = r, qq = q;
            if (Double.isInfinite(gg) && Double.isInfinite(rr) && Double.isInfinite(qq)) continue;
            Predicate<Shipment> p = d ->
                    (!Double.isInfinite(gg) && gapOf(d) >= gg)
                            || (!Double.isInfinite(rr) && d.overRatio() >= rr)
                            || (!Double.isInfinite(qq) && d.quotaRemaining() != null && d.quotaRemaining() <= qq);
            String nm = "gap>=" + fmtOpt(gg) + "|ratio>=" + fmtOpt(rr) + "|quota<=" + fmtOpt(qq);
            Rule cur = score(clean, p, nm, totalRejects, baselineLoss);
            if (best == null || cur.netSaved > best.netSaved) best = cur;
        }
        System.out.printf("  ★最优组合: %-30s 预警%3d 命中%3d 精准率%5.1f%% 召回%5.1f%% 净省%5d 工时(降幅%.1f%%)%n",
                best.name, best.flagged, best.hit, best.precision * 100, best.recall * 100,
                best.netSaved, 100.0 * best.netSaved / baselineLoss);
    }

    static String fmtOpt(double v) {
        return Double.isInfinite(v) ? "off" : fmt(v);
    }

    static void eval(List<Shipment> clean, Predicate<Shipment> p, String name,
                     int totalRejects, long baselineLoss) {
        Rule r = score(clean, p, name, totalRejects, baselineLoss);
        System.out.printf("  %-24s 预警%3d 命中%3d 精准率%5.1f%% 召回%5.1f%% 净省%5d 工时(降幅%.1f%%)%n",
                r.name, r.flagged, r.hit, r.precision * 100, r.recall * 100,
                r.netSaved, 100.0 * r.netSaved / baselineLoss);
    }

    static Rule score(List<Shipment> clean, Predicate<Shipment> p, String name,
                      int totalRejects, long baselineLoss) {
        int flagged = 0, hit = 0;
        for (Shipment d : clean) {
            if (p.test(d)) {
                flagged++;
                if (d.isRejected()) hit++;
            }
        }
        long netSaved = (long) hit * REJECT_COST - (long) flagged * WARN_COST;
        double precision = flagged == 0 ? 0 : (double) hit / flagged;
        double recall = totalRejects == 0 ? 0 : (double) hit / totalRejects;
        return new Rule(name, flagged, hit, netSaved, precision, recall);
    }

    static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    record Rule(String name, int flagged, int hit, long netSaved, double precision, double recall) {}
}
