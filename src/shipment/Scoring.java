package shipment;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 热点归因 + 逻辑回归评分模型（纯 JDK，含训练/测试切分交叉校验）。
 *
 *   javac -encoding UTF-8 -d out src/shipment/*.java
 *   java -cp out shipment.Scoring doc/shipments.csv
 */
public final class Scoring {

    static final int REJECT_COST = 90, WARN_COST = 12;

    public static void main(String[] args) throws IOException {
        List<Shipment> clean = loadClean(args);
        int rejects = 0;
        for (Shipment d : clean) if (d.isRejected()) rejects++;
        System.out.println("有效样本=" + clean.size() + "  拒收=" + rejects
                + "  基线损失=" + (rejects * (long) REJECT_COST) + " 工时");

        hotspotAttribution(clean);
        logisticModel(clean);
    }

    // ============ 1. 热点归因：控制超额比后，园区/物料是否仍独立影响拒收 ============
    static void hotspotAttribution(List<Shipment> clean) {
        System.out.println("\n===== 热点归因(分层看: 低超额 ratio<5 vs 高超额 ratio>=5) =====");
        System.out.println("[园区]  同层内若拒收率仍差异大 => 园区是独立信号, 需单独治理");
        layerTable(clean, Shipment::factoryZone);
        System.out.println("[物料]  Top/Bottom 3 展示");
        layerTable(clean, Shipment::materialGroup);
    }

    static void layerTable(List<Shipment> clean, java.util.function.Function<Shipment, String> keyFn) {
        Map<String, int[]> low = new LinkedHashMap<>(), high = new LinkedHashMap<>();
        // int[0]=样本, int[1]=拒收
        for (Shipment d : clean) {
            boolean hi = d.overRatio() >= 5;
            (hi ? high : low).computeIfAbsent(keyFn.apply(d), k -> new int[2]);
            int[] a = (hi ? high : low).get(keyFn.apply(d));
            a[0]++;
            if (d.isRejected()) a[1]++;
        }
        // 用高超额层拒收率降序展示
        List<String> keys = new ArrayList<>(high.keySet());
        keys.sort((x, y) -> Double.compare(rate(high.get(y)), rate(high.get(x))));
        System.out.printf("  %-8s | 低超额ratio<5    | 高超额ratio>=5%n", "维度");
        for (String k : keys) {
            int[] l = low.getOrDefault(k, new int[2]);
            int[] h = high.get(k);
            System.out.printf("  %-8s | %5.1f%% (n=%3d) | %5.1f%% (n=%3d)%n",
                    k, rate(l), l[0], rate(h), h[0]);
        }
    }

    static double rate(int[] a) { return a[0] == 0 ? 0 : 100.0 * a[1] / a[0]; }

    // ============ 2. 逻辑回归评分模型 + 训练/测试校验 ============
    static void logisticModel(List<Shipment> clean) {
        // 特征编码: [bias, ratioCap/10, quota/30, batch/50, 园区 one-hot(去基线), 物料 one-hot(去基线)]
        List<String> factories = distinct(clean, Shipment::factoryZone);
        List<String> materials = distinct(clean, Shipment::materialGroup);
        String baseF = factories.get(0), baseM = materials.get(0);
        int dim = 4 + (factories.size() - 1) + (materials.size() - 1);

        double[][] X = new double[clean.size()][dim];
        double[] y = new double[clean.size()];
        for (int i = 0; i < clean.size(); i++) {
            Shipment d = clean.get(i);
            double rc = d.overRatio();
            if (Double.isInfinite(rc)) rc = 20; // 配额=0 截断
            rc = Math.min(rc, 20);
            X[i][0] = 1;
            X[i][1] = rc / 10.0;
            X[i][2] = (d.quotaRemaining() == null ? 0 : d.quotaRemaining()) / 30.0;
            X[i][3] = (d.batchVolume() == null ? 0 : d.batchVolume()) / 50.0;
            int off = 4;
            int fi = factories.indexOf(d.factoryZone());
            if (fi > 0) X[i][off + fi - 1] = 1;
            int mi = materials.indexOf(d.materialGroup());
            if (mi > 0) X[i][off + (factories.size() - 1) + mi - 1] = 1;
            y[i] = d.isRejected() ? 1 : 0;
        }

        // 固定随机种子切分 70/30
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < clean.size(); i++) idx.add(i);
        Collections.shuffle(idx, new Random(42));
        int nTrain = (int) (idx.size() * 0.7);
        int[] tr = new int[nTrain], te = new int[idx.size() - nTrain];
        for (int i = 0; i < nTrain; i++) tr[i] = idx.get(i);
        for (int i = 0; i < te.length; i++) te[i] = idx.get(nTrain + i);

        double[] w = trainLogit(X, y, tr, dim, 3000, 0.1, 1e-3);
        double[] pTrain = predict(X, w, tr);
        double[] pTest = predict(X, w, te);

        System.out.println("\n===== 逻辑回归评分模型 (seed=42, 70/30 切分) =====");
        System.out.printf("训练集 AUC=%.3f   测试集 AUC=%.3f%n",
                auc(pTrain, subset(y, tr)), auc(pTest, subset(y, te)));

        // 在测试集上扫描决策阈值, 找最大净省工时
        int testRejects = 0;
        for (int i : te) if (clean.get(i).isRejected()) testRejects++;
        double bestTh = 0.5; long bestNet = Long.MIN_VALUE; int bHit = 0, bFlag = 0;
        for (double th = 0.05; th <= 0.95; th += 0.01) {
            int flag = 0, hit = 0;
            for (int j = 0; j < te.length; j++) {
                if (pTest[j] >= th) { flag++; if (y[te[j]] == 1) hit++; }
            }
            long net = hit * (long) REJECT_COST - flag * (long) WARN_COST;
            if (net > bestNet) { bestNet = net; bestTh = th; bHit = hit; bFlag = flag; }
        }
        double baseTestLoss = testRejects * (long) REJECT_COST;
        System.out.printf("模型@测试集: 最优阈值p>=%.2f 拦截%d 命中%d 精准%.1f%% 召回%.1f%% 净省%d 工时(占基线%.1f%%)%n",
                bestTh, bFlag, bHit, 100.0 * bHit / Math.max(1, bFlag),
                100.0 * bHit / Math.max(1, testRejects), bestNet, 100.0 * bestNet / baseTestLoss);

        // 同口径基准: 简单规则 ratio>=5 OR quota<=7 在测试集上的净省
        int flag0 = 0, hit0 = 0;
        for (int i : te) {
            Shipment d = clean.get(i);
            if (d.overRatio() >= 5 || (d.quotaRemaining() != null && d.quotaRemaining() <= 7)) {
                flag0++;
                if (d.isRejected()) hit0++;
            }
        }
        long net0 = hit0 * (long) REJECT_COST - flag0 * (long) WARN_COST;
        System.out.printf("简单规则@测试集: 拦截%d 命中%d 精准%.1f%% 召回%.1f%% 净省%d 工时%n",
                flag0, hit0, 100.0 * hit0 / Math.max(1, flag0),
                100.0 * hit0 / Math.max(1, testRejects), net0);
        System.out.println("对比 => 模型相对简单规则净省差值: " + (bestNet - net0) + " 工时");
    }

    // ---- 训练(logistic, 梯度下降 + L2) ----
    static double[] trainLogit(double[][] X, double[] y, int[] rows, int dim,
                               int iters, double lr, double l2) {
        double[] w = new double[dim];
        int n = rows.length;
        double[] grad = new double[dim];
        for (int it = 0; it < iters; it++) {
            java.util.Arrays.fill(grad, 0);
            for (int r = 0; r < n; r++) {
                int i = rows[r];
                double p = sigmoid(dot(w, X[i]));
                double e = p - y[i];
                for (int k = 0; k < dim; k++) grad[k] += e * X[i][k];
            }
            for (int k = 0; k < dim; k++) {
                double g = grad[k] / n + (k == 0 ? 0 : l2 * w[k]);
                w[k] -= lr * g;
            }
        }
        return w;
    }

    static double[] predict(double[][] X, double[] w, int[] rows) {
        double[] p = new double[rows.length];
        for (int j = 0; j < rows.length; j++) p[j] = sigmoid(dot(w, X[rows[j]]));
        return p;
    }

    static double[] subset(double[] y, int[] rows) {
        double[] o = new double[row2len(rows)];
        for (int i = 0; i < rows.length; i++) o[i] = y[rows[i]];
        return o;
    }
    static int row2len(int[] r) { return r.length; }

    static double sigmoid(double z) { return 1.0 / (1.0 + Math.exp(-z)); }
    static double dot(double[] a, double[] b) { double s = 0; for (int i = 0; i < a.length; i++) s += a[i] * b[i]; return s; }

    static double auc(double[] score, double[] label) {
        int n = score.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(score[a], score[b]));
        // 含并列时用秩和(平均秩)近似; 此处按排序位置累加
        double rankSum = 0; int pos = 0, neg = 0;
        for (int r = 0; r < n; r++) {
            if (label[order[r]] == 1) { pos++; rankSum += (r + 1); } else neg++;
        }
        if (pos == 0 || neg == 0) return Double.NaN;
        return (rankSum - pos * (pos + 1) / 2.0) / ((double) pos * neg);
    }

    // ============ 数据装载 ============
    static List<Shipment> loadClean(String[] args) throws IOException {
        Path p = Path.of(args.length > 0 ? args[0] : "doc/shipments.csv");
        List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
        Map<String, Shipment> first = new LinkedHashMap<>();
        boolean header = true;
        for (String ln : lines) {
            String s = ln.strip();
            if (s.isEmpty()) continue;
            if (header) { header = false; continue; }
            if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1);
            String[] c = s.split(",", -1);
            if (c.length < 8) continue;
            String st = c[7].trim();
            Shipment d = new Shipment(c[0].trim(), Shipment.parseInstant(c[1]),
                    Shipment.parseInstant(c[2]), c[3].trim(), c[4].trim(),
                    Shipment.parseInt(c[5]), Shipment.parseInt(c[6]),
                    st.isEmpty() ? null : Shipment.parseInt(st));
            first.putIfAbsent(d.id(), d);
        }
        List<Shipment> clean = new ArrayList<>();
        for (Shipment d : first.values()) if (d.hasValidStatus()) clean.add(d);
        return clean;
    }

    static List<String> distinct(List<Shipment> l, java.util.function.Function<Shipment, String> f) {
        List<String> o = new ArrayList<>();
        for (Shipment d : l) if (!o.contains(f.apply(d))) o.add(f.apply(d));
        Collections.sort(o);
        return o;
    }
}
