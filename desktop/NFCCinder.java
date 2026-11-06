import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

/**
 * NFCC · 十字路口分析器(Minecraft 26.2)
 * 只算不放：给种子和坐标，用内置生成器算出半径内的全部十字路口、统计与中心挂机点。
 */
public class NFCCinder extends JFrame {
    static final java.awt.Font UI_FONT = new java.awt.Font("微软雅黑", java.awt.Font.PLAIN, 14);

    /** 每个十字路口里真实下界砖方块数(不含空气) */
    private static final int CROSSING_BOX = 19 * 10 * 19; // 一个十字路口组件占的空间(19x10x19)

    private JTextField seedField = new JTextField("5535345", 12);
    private final JTextField xField = new JTextField("0", 10);
    private final JTextField zField = new JTextField("0", 10);
    private final JTextField radiusField = new JTextField("256", 6);
    private final JRadioButton crossMode = new JRadioButton("按十字路口算", true);
    private final JRadioButton fortMode = new JRadioButton("按整个堡垒总体积计算", false);
    private int lastWholeVol = 0;
    public Runnable onModeChanged = null;
    public boolean isFortMode() { return fortMode.isSelected(); }
    private final JButton runBtn = new JButton("开始分析");
    private final JButton saveBtn = new JButton("保存结果");
    private final JTextPane out = new ShadowPane();
    private final javax.swing.text.StyledDocument doc = out.getStyledDocument();

    private final JLabel status = new JLabel("就绪");

    private final List<String> rows = new ArrayList<>();
    private File engineDir;

    /** 合并版把种子输入框放到最上面公用。 */
    public void setSharedSeed(JTextField f) {
        this.seedField = f;
    }

    public NFCCinder() {
        super("NFCC · 十字路口分析器 · Minecraft 26.2");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(6, 6));

        xField.setColumns(8);
        zField.setColumns(8);
        radiusField.setColumns(4);
        JPanel north = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        north.add(runBtn);
        north.add(saveBtn);
        north.add(javax.swing.Box.createHorizontalStrut(12));
        north.add(new JLabel("中心点 X"));
        north.add(xField);
        north.add(new JLabel("Z"));
        north.add(zField);
        north.add(new JLabel("搜索半径（格）"));
        north.add(radiusField);
        fortMode.setToolTipText("按「球心 128 格内覆盖到的堡垒，其整座堡垒全部路口体积之和」优先");
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(crossMode);
        modeGroup.add(fortMode);
        north.add(javax.swing.Box.createHorizontalStrut(8));
        java.awt.event.ActionListener modeChg = e -> { if (onModeChanged != null) onModeChanged.run(); };
        crossMode.addActionListener(modeChg);
        fortMode.addActionListener(modeChg);
        north.add(crossMode);
        north.add(fortMode);
        add(north, BorderLayout.NORTH);

        out.setEditable(false);
        out.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        add(new JScrollPane(out), BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));
        south.add(status, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);

        runBtn.addActionListener(e -> runAnalysis());
        saveBtn.addActionListener(e -> saveRows());

        setSize(860, 700);
        setLocationRelativeTo(null);
    }

    private void saveRows() {
        if (rows.isEmpty()) { JOptionPane.showMessageDialog(this, "还没有结果"); return; }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("nfcf-result.txt"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                java.nio.file.Files.write(fc.getSelectedFile().toPath(), rows, StandardCharsets.UTF_8);
                status.setText("已保存 " + fc.getSelectedFile().getName());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "保存失败：" + ex.getMessage());
            }
        }
    }

    /** 算完一次回调：{总体积, 可刷怪体积, 峡谷内可刷怪体积}。合并版用它批量扫前 30 个。 */
    public java.util.function.Consumer<double[]> onAnalyzed = null;
    private long lastVolAll = 0, lastVolIn = 0, lastSoulVol = 0;
    private long lastCx = 0, lastCy = 0, lastCz = 0;
    private int lastCrossings = 0, lastOverlaps = 0, lastFortresses = 0;

    /** 外部（合并版里点 NFF 结果）直接带着种子和中心点跑一次。 */
    private volatile boolean analyzing = false;
    private long[] pendingAt = null;

    public void analyzeAt(long seed, long x, long z) {
        if (analyzing) { pendingAt = new long[]{seed, x, z}; return; }
        xField.setText(Long.toString(x));
        zField.setText(Long.toString(z));
        runAnalysis();
    }

    private void runAnalysis() {
        analyzing = true;
        runBtn.setEnabled(false);
        saveBtn.setEnabled(false);
        out.setText("");
        rows.clear();
        long seed; int px, pz, radius; int runRadius;
        try {
            seed = Long.parseLong(seedField.getText().trim());
            px = Integer.parseInt(xField.getText().trim());
            pz = Integer.parseInt(zField.getText().trim());
            radius = Integer.parseInt(radiusField.getText().trim());
            radius = Math.max(1, Math.min(512, radius));
            radiusField.setText(String.valueOf(radius));
            runRadius = radius;
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "种子和坐标要填整数");
            runBtn.setEnabled(true);
            return;
        }
        status.setText("分析中…");
        new Thread(() -> {
            try { analyze(seed, px, pz, runRadius); }
            catch (Exception ex) { append("出错了：" + ex.getMessage()); }
            finally {
                SwingUtilities.invokeLater(() -> {
                    analyzing = false;
                    runBtn.setEnabled(true);
                    saveBtn.setEnabled(out.getDocument().getLength() > 0);
                    status.setText("完成");
                    if (onAnalyzed != null) onAnalyzed.accept(new double[]{lastVolAll, lastVolIn, lastSoulVol, lastCx, lastCy, lastCz, lastCrossings, lastOverlaps, lastFortresses});
                    if (pendingAt != null) {
                        long[] p = pendingAt;
                        pendingAt = null;
                        analyzeAt(p[0], p[1], p[2]);
                    }
                });
            }
        }, "nfcf").start();
    }

    private static int ufRoot(int[] uf, int i) {
        while (uf[i] != i) {
            uf[i] = uf[uf[i]];
            i = uf[i];
        }
        return i;
    }

    private void analyze(long seed, int px, int pz, int radius) throws Exception {
        List<double[]> fort = findFortresses(seed, px, pz, radius);
        if (fort.isEmpty()) { append("半径 " + radius + " 内没有找到堡垒。"); return; }
        append("以 ( " + px + ", " + pz + " ) 为中心、半径 " + radius + " 格内共 " + fort.size() + " 座堡垒");
        append("");

        List<int[]> crossings = new ArrayList<>();
        List<Integer> groups = new ArrayList<>();
        int i = 0;
        for (double[] f : fort) {
            i++;
            int fx = (int) Math.round(f[0]), fz = (int) Math.round(f[1]);
            List<int[]> cs = FortressGen.crossings(seed, fx >> 4, fz >> 4);
            append("堡垒 " + i + " ( " + fx + ", " + fz + " )  距离 " + (int) Math.round(f[2]) + "  路口 " + cs.size() + " 个");
            for (int[] p : cs) {
                append("    ( " + p[0] + " " + p[1] + " " + p[2] + " )");
                crossings.add(p);
                groups.add(i);
            }
        }
        append("路口合计 " + crossings.size() + " 个");
        append("");
        if (crossings.isEmpty()) return;

        int sx0 = Integer.MAX_VALUE, sx1 = Integer.MIN_VALUE, sz0 = Integer.MAX_VALUE, sz1 = Integer.MIN_VALUE;
        int sy0 = Integer.MAX_VALUE, sy1 = Integer.MIN_VALUE;
        for (int[] p : crossings) {
            sx0 = Math.min(sx0, p[0]); sx1 = Math.max(sx1, p[0]);
            sy0 = Math.min(sy0, p[1]); sy1 = Math.max(sy1, p[1]);
            sz0 = Math.min(sz0, p[2]); sz1 = Math.max(sz1, p[2]);
        }
        int cx = (sx0 + sx1) / 2, cz = (sz0 + sz1) / 2;

        // 多联优先：先看这个球心能覆盖到几座堡垒，再看能覆盖多少路口，最后才比距离
        final boolean wholeMode = fortMode.isSelected();
        long[] fortVol = new long[fort.size() + 2];   // 整座堡垒体积：全部组件包围盒之和
        if (wholeMode) {
            for (int gi = 1; gi <= fort.size(); gi++) {
                double[] f = fort.get(gi - 1);
                int fx = (int) Math.round(f[0]), fz = (int) Math.round(f[1]);
                fortVol[gi] = FortressGen.fortressVolume(seed, fx >> 4, fz >> 4);
            }
        }
        int[] best = null;
        int bestCov = -1;
        long bestIn = -1, bestDist = Long.MAX_VALUE, bestWhole = -1;
        boolean[] seen = new boolean[fort.size() + 2];
        for (int y = Math.max(48, sy0 - 2); y <= Math.min(70, sy1 + 4); y++) {
            for (int x = cx - 96; x <= cx + 96; x += 2) {
                for (int z = cz - 96; z <= cz + 96; z += 2) {
                    long in = 0, dist = 0;
                    int n = 0, cov = 0;
                    java.util.Arrays.fill(seen, false);
                    for (int ci = 0; ci < crossings.size(); ci++) {
                        int[] p = crossings.get(ci);
                        long d2 = d2(x, y, z, p);
                        if (d2 <= 128L * 128L) {   // 搜索阶段只按中心距离筛，快
                            in += CROSSING_BOX; n++;
                            int g = groups.get(ci);
                            if (g >= 0 && g < seen.length && !seen[g]) { seen[g] = true; cov++; }
                        }
                        dist += (long) Math.sqrt(d2);
                    }
                    long whole = 0;   // 覆盖到的这些堡垒，整座堡垒全部路口的体积之和
                    if (wholeMode) {
                        for (int g = 1; g < fortVol.length; g++) if (seen[g]) whole += fortVol[g];
                    }
                    boolean better;
                    if (wholeMode) {
                        if (whole != bestWhole) better = whole > bestWhole;
                        else if (cov != bestCov) better = cov > bestCov;
                        else if (in != bestIn) better = in > bestIn;
                        else better = dist < bestDist;
                    } else {
                        if (cov != bestCov) better = cov > bestCov;
                        else if (in != bestIn) better = in > bestIn;
                        else better = dist < bestDist;
                    }
                    if (better) {
                        bestCov = cov; bestIn = in; bestDist = dist; bestWhole = whole;
                        best = new int[]{x, y, z, n, cov, (int) Math.min(Integer.MAX_VALUE, whole)};
                    }
                }
            }
        }
        if (best == null) return;
        lastWholeVol = best[5];

        int bx = best[0], by = best[1], bz = best[2], nIn = best[3] - 0;
        long precise = 0;
        for (int[] p : crossings) precise += overlapVolume(bx, by, bz, p);
        bestIn = precise;
        long total = (long) CROSSING_BOX * crossings.size();
        int nOut = 0, partCount = 0;
        for (int[] p : crossings) {
            if (d2(bx, by, bz, p) > 128L * 128L) nOut++;
            else if (!boxInside(bx, by, bz, p)) partCount++;
        }
        int[] bids = biomes(seed, crossings);
        int soulCnt = 0;
        for (int k = 0; k < bids.length; k++) if (bids[k] == 170) soulCnt++;

        // ===== 按 check 的口径逐方块统计（整盒 19x19x10 全枚举，不抽样）=====
        java.util.HashMap<Long, int[]> cover = new java.util.HashMap<>(); // key -> {份数, 最高类, 是否峡谷}
        int[] cat = new int[crossings.size()];
        for (int ci = 0; ci < crossings.size(); ci++) {
            int[] c = crossings.get(ci);
            boolean wholeIn = true, anyIn = false;
            for (int dx = -9; dx <= 9; dx += 3)
                for (int dy = -4; dy <= 5; dy += 3)
                    for (int dz = -9; dz <= 9; dz += 3) {
                        double d = dist(c[0] + dx, c[1] + dy, c[2] + dz, bx, by, bz);
                        if (d <= 128) anyIn = true; else wholeIn = false;
                    }
            cat[ci] = wholeIn ? 1 : (anyIn ? 2 : 3); // 1绿 2黄 3红
            boolean soul = ci < bids.length && bids[ci] == 170;
            for (int dx = -9; dx <= 9; dx++)
                for (int dy = -4; dy <= 5; dy++)
                    for (int dz = -9; dz <= 9; dz++) {
                        int qx = c[0] + dx, qy = c[1] + dy, qz = c[2] + dz;
                        long key = pack(qx, qy, qz);
                        int[] v = cover.get(key);
                        if (v == null) cover.put(key, new int[]{1, cat[ci], soul ? 1 : 0});
                        else { v[0]++; if (cat[ci] > v[1]) v[1] = cat[ci]; if (soul) v[2] = 1; }
                    }
        }
        long inGreen = 0, inYellow = 0, inOrange = 0, outRed = 0, outYellow = 0, outOrange = 0, soulIn = 0;
        for (java.util.Map.Entry<Long, int[]> e : cover.entrySet()) {
            long k = e.getKey();
            int x = (int) ((k >>> 36) & 0x3FFFFFFL) - 33554432;
            int y = (int) ((k >>> 26) & 0x3FFL) - 640;
            int z = (int) (k & 0x3FFFFFFL) - 33554432;
            boolean inside = dist(x, y, z, bx, by, bz) <= 128;
            int[] v = e.getValue();
            if (v[0] >= 2) { if (inside) inOrange++; else outOrange++; }
            else if (v[1] == 1) inGreen++;
            else if (v[1] == 2) { if (inside) inYellow++; else outYellow++; }
            else outRed++;
            if (inside && v[2] == 1) soulIn++;
        }
        long volIn = inGreen + inYellow + inOrange;
        long volOut = outRed + outYellow + outOrange;
        // 标准总体积：每座路口按整盒 3610 块算（重叠也照样计入，不合并）
        long volAll = (long) CROSSING_BOX * crossings.size();
        lastVolAll = volAll;
        lastCrossings = crossings.size();
        lastFortresses = rows.size();
        lastVolIn = volIn;
        lastSoulVol = soulIn;
        lastCx = bx; lastCy = by; lastCz = bz;

        java.util.List<String> overLines = new java.util.ArrayList<>();
        lastOverlaps = 0;
        java.util.List<int[]> badLines = new java.util.ArrayList<>();

        // ---- 重叠：19x10x19 盒子相交。串在一起的一堆只报一个中心点 ----
        int cn = crossings.size();
        int[] uf = new int[cn];
        for (int u = 0; u < cn; u++) uf[u] = u;
        for (int a = 0; a < cn; a++) {
            for (int b = a + 1; b < cn; b++) {
                int[] p = crossings.get(a), q = crossings.get(b);
                if (Math.abs(p[0] - q[0]) <= 18 && Math.abs(p[1] - q[1]) <= 9 && Math.abs(p[2] - q[2]) <= 18) {
                    int ra = ufRoot(uf, a), rb = ufRoot(uf, b);
                    if (ra != rb) uf[rb] = ra;
                }
            }
        }
        java.util.Map<Integer, java.util.List<Integer>> ovGroups = new java.util.LinkedHashMap<>();
        for (int u = 0; u < cn; u++) {
            ovGroups.computeIfAbsent(ufRoot(uf, u), k -> new java.util.ArrayList<Integer>()).add(u);
        }
        int ovNo = 0;
        for (java.util.List<Integer> grp : ovGroups.values()) {
            if (grp.size() < 2) continue;
            int[] p = crossings.get(grp.get(0));
            overLines.add((++ovNo) + ". ( " + p[0] + " " + p[1] + " " + p[2] + " )");
            lastOverlaps++;
        }

        // ---- 整盒群系：盒子里每一点都得是灵魂沙峡谷，取中心点是不够的 ----
        int[] pxs = {-9, -3, 3, 9}, pys = {-4, 0, 5};
        int per = pxs.length * pxs.length * pys.length;
        List<int[]> probes = new ArrayList<>();
        for (int[] c : crossings) {
            for (int py : pys) {
                for (int ox : pxs) {
                    for (int oz : pxs) {
                        probes.add(new int[]{c[0] + ox, c[1] + py, c[2] + oz});
                    }
                }
            }
        }
        int[] pb = biomes(seed, probes);
        for (int ci = 0; ci < crossings.size(); ci++) {
            int off = -1;
            for (int k = 0; k < per; k++) {
                int id = pb[ci * per + k];
                if (id != 170) { off = id; break; }
            }
            if (off >= 0) {
                int[] c = crossings.get(ci);
                badLines.add(new int[]{c[0], c[1], c[2], off});
            }
        }
        if (overLines.isEmpty()) { appendN("没有重叠的十字路口", ORG); nl(); }
    else {
        appendN("存在重叠的十字路口", ORG); nl();
        for (String on : overLines) { appendN(on, ORG); nl(); }
    }
    if (badLines.isEmpty()) { appendN("没有不在灵魂沙峡谷的十字路口", BRN); nl(); }
    else {
        appendN("不在灵魂沙峡谷的十字路口：", BRN); nl();
        int badNo = 0;
        for (int[] bl : badLines) {
            appendN((++badNo) + ". ( " + bl[0] + " " + bl[1] + " " + bl[2] + " )", BRN);
            appendN("  " + biomeName(bl[3]), biomeHex(bl[3])); nl();
        }
    }
    nl();
    appendN("计算出来的中心挂机点：", BLK);
    appendN("( " + bx + " " + by + " " + bz + " )", BLK); nl();
    appendC("其128格半径球体刷怪范围内十字路口的情况", BLK);
    int catIn = 0, catPart = 0, catOut = 0;
    for (int ci = 0; ci < cat.length; ci++) {
        if (cat[ci] == 1) catIn++;
        else if (cat[ci] == 2) catPart++;
        else catOut++;
    }
    appendN("完全包含：", GRN); appendN("" + catIn, GRN);
    appendN(" | ", BLK); appendN("不完全包含：", YEL); appendN("" + catPart, YEL);
    appendN(" | ", BLK); appendN("完全不包含：", RED); appendN("" + catOut, RED); nl();
    nl();
    appendN("总体积：", BLK); appendN("" + volAll, BLK); appendN(" 块", BLK); nl();
    appendN("范围内可刷怪：", BLK); appendN("" + volIn, GRN); appendN(" 块", BLK);
    appendN(" ( ", BLK);
    appendN("" + inGreen, GRN); appendN(" + ", BLK);
    appendN("" + inYellow, YEL); appendN(" + ", BLK);
    appendN("" + inOrange, ORG);
    appendN(" )", BLK); nl();
    appendN("       占总体积：", BLK); appendN(pctNum(volIn, volAll), GRN); appendN(" %", BLK); nl();
    appendN("范围内可刷怪且位于灵魂沙峡谷：", BLK);
    appendN("" + lastSoulVol, BRN); appendN(" 块", BLK); nl();
    appendN("       占可刷怪体积：", BLK); appendN(pctNum(lastSoulVol, volIn), BRN); appendN(" %", BLK);
    appendN(" 占总体积：", BLK); appendN(pctNum(lastSoulVol, volAll), BRN); appendN(" %", BLK); nl();
    appendN("不可刷怪：", BLK); appendN("" + volOut, RED); appendN(" 块", BLK);
    appendN(" ( ", BLK);
    appendN("" + outRed, RED); appendN(" + ", BLK);
    appendN("" + outYellow, YEL); appendN(" + ", BLK);
    appendN("" + outOrange, ORG);
    appendN(" )", BLK); nl();
    appendN("       占总体积：", BLK); appendN(pctNum(volOut, volAll), RED); appendN(" %", BLK); nl();

    }

    /** 方块坐标打包成一个 long（x/z 各 26 位，y 10 位）。 */
    private static long pack(int x, int y, int z) {
        return ((long) (x + 33554432) << 36) | ((long) (y + 640) << 26) | (long) (z + 33554432);
    }

    private static String pctNum(double a, double b) { return pct(a, b).replace("%", ""); }

private static double dist(int x, int y, int z, int cx, int cy, int cz) {
        double dx = x - cx, dy = y - cy, dz = z - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static final String BLK = "#000000", GRN = "#55FF55", YEL = "#FFFF55", ORG = "#FFAA00", RED = "#FF5555", BRN = "#8B5B32";

    private static String pct(double a, double b) { return String.format("%.1f", 100.0 * a / Math.max(1, b)) + "%"; }

    /** 用种子批量查群系(scale=1)，返回每条路口中心点的群系 id */
    private int[] biomes(long seed, List<int[]> pts) {
        int[] res = new int[pts.size()];
        File inFile = null;
        try {
            File exe = new File(engineDir, "biomes_batch.exe");
            inFile = File.createTempFile("nfcc-in-", ".txt");
            try (java.io.BufferedWriter w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(inFile), StandardCharsets.US_ASCII))) {
                for (int[] q : pts) w.write(q[0] + " " + q[1] + " " + q[2] + "\n");
            }
            ProcessBuilder pb = new ProcessBuilder(exe.getAbsolutePath(), Long.toString(seed));
            pb.redirectInput(inFile);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line; int i = 0;
                while ((line = r.readLine()) != null && i < res.length) {
                    line = line.trim();
                    if (!line.isEmpty()) res[i++] = Integer.parseInt(line);
                }
            }
            p.waitFor();
        } catch (Exception e) {
            javax.swing.JOptionPane.showMessageDialog(this, "查群系失败：" + e.getMessage());
        } finally {
            if (inFile != null) inFile.delete();
        }
        return res;
    }

    private static String biomeHex(int id) {
        switch (id) {
            case 8: return "#FF5555";   // 下界荒地 红
            case 171: return "#E67E22"; // 绯红森林 橙
            case 172: return "#2B8B7E"; // 诡异森林 青
            case 173: return "#808080"; // 玄武岩三角洲 灰
            case 170: case 178: return BRN; // 灵魂沙峡谷 棕
            default: return BRN;
        }
    }

    private static String biomeName(int id) {
        switch (id) {
            case 8: return "下界荒地";
            case 170: return "灵魂沙峡谷";
            case 171: return "绯红森林";
            case 172: return "诡异森林";
            case 173: return "玄武岩三角洲";
            default: return "群系 " + id;
        }
    }

    private static long d2(int x, int y, int z, int[] p) {
        long dx = x - p[0], dy = y - p[1], dz = z - p[2];
        return dx * dx + dy * dy + dz * dz;
    }

    private List<double[]> findFortresses(long seed, int px, int pz, int radius) throws Exception {
        List<double[]> res = new ArrayList<>();
        File exe = new File(engine(), "nfclook.exe");
        if (!exe.isFile()) throw new IOException("找不到引擎 nfclook.exe: " + exe);
        ProcessBuilder pb = new ProcessBuilder(exe.getAbsolutePath(),
                Long.toString(seed), Integer.toString(px), Integer.toString(pz), Integer.toString(radius));
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("TOTAL")) continue;
                String[] t = line.split("\\s+");
                if (t.length >= 3) {
                    try { res.add(new double[]{Double.parseDouble(t[0]), Double.parseDouble(t[1]), Double.parseDouble(t[2])}); }
                    catch (NumberFormatException ignore) { }
                }
            }
        }
        p.waitFor();
        return res;
    }

    private File engine() {
        if (engineDir != null && engineDir.isDirectory()) return engineDir;
        File local = new File("engine");
        if (new File(local, "nfclook.exe").isFile()) { engineDir = local; return local; }
        try {
            File tmp = new File(System.getProperty("java.io.tmpdir"), "nfcf-engine-" + System.nanoTime());
            tmp.mkdirs();
            copyResource("/engine/nfclook.exe", new File(tmp, "nfclook.exe"));
            try { copyResource("/engine/biomes_batch.exe", new File(tmp, "biomes_batch.exe")); } catch (Exception ignored2) { }
            engineDir = tmp;
        } catch (Exception e) {
            engineDir = local;
        }
        return engineDir;
    }

    private static void copyResource(String name, File dst) throws IOException {
        try (InputStream in = NFCCinder.class.getResourceAsStream(name)) {
            if (in == null) return;
            try (OutputStream os = new FileOutputStream(dst)) { in.transferTo(os); }
        }
    }

    private void append(String s) { appendC(s, "#333333"); }

    private void appendN(String s, String hex) {
        try {
            javax.swing.text.SimpleAttributeSet a = new javax.swing.text.SimpleAttributeSet();
            javax.swing.text.StyleConstants.setForeground(a, Color.decode(hex));
            javax.swing.text.StyleConstants.setFontFamily(a, Font.MONOSPACED);
            javax.swing.text.StyleConstants.setFontSize(a, 13);
            doc.insertString(doc.getLength(), s, a);
        } catch (Exception ignored) { }
        out.setCaretPosition(doc.getLength());
    }

    /** 给彩色字加一层 MC 那样的右下错位影子：正文底下先画一层更深的同色。 */
    private static final class ShadowPane extends JTextPane {
        private static final float DX = 0.5f;
        private static final float DY = 0.5f;

        ShadowPane() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;
            g2.setColor(getBackground());
            g2.fillRect(0, 0, getWidth(), getHeight());
            try {
                paintShadows(g2);
            } catch (Exception ignored) { }
            super.paintComponent(g);
        }

        private void paintShadows(Graphics2D g2) {
            javax.swing.text.StyledDocument d = getStyledDocument();
            javax.swing.text.Element root = d.getDefaultRootElement();
            Rectangle clip = g2.getClipBounds();
            Font f = getFont();
            if (f == null) return;
            g2.setFont(f);
            FontMetrics fm = getFontMetrics(f);
            int asc = fm.getAscent();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int n = root.getElementCount();
            for (int li = 0; li < n; li++) {
                javax.swing.text.Element line = root.getElement(li);
                int ls = line.getStartOffset();
                int le = line.getEndOffset() - 1;
                if (le <= ls) continue;
                Rectangle lr;
                try {
                    lr = modelToView(ls);
                } catch (Exception e) {
                    continue;
                }
                if (lr == null) continue;
                if (clip != null && lr.y > clip.y + clip.height) break;
                if (clip != null && lr.y + lr.height < clip.y) continue;
                for (int i = ls; i < le; ) {
                    javax.swing.text.Element ce = d.getCharacterElement(i);
                    int s = Math.max(i, ce.getStartOffset());
                    int e2 = Math.min(le, ce.getEndOffset());
                    if (e2 <= s) { i = s + 1; continue; }
                    Color fg = javax.swing.text.StyleConstants.getForeground(ce.getAttributes());
                    if (!isColored(fg)) { i = e2; continue; }
                    try {
                        Rectangle r = modelToView(s);
                        if (r != null) {
                            g2.setColor(shadowOf(fg));
                            g2.drawString(d.getText(s, e2 - s), r.x + DX, r.y + asc + DY);
                        }
                    } catch (Exception ignored) { }
                    i = e2;
                }
            }
        }

        private static boolean isColored(Color c) {
            if (c == null) return false;
            int rgb = c.getRGB() & 0xFFFFFF;
            return rgb != 0x333333 && rgb != 0x000000;
        }

        private static Color shadowOf(Color c) {
            int rgb = c.getRGB() & 0xFFFFFF;
            return new Color(0, 0, 0, rgb == 0xFFFF55 ? 100 : 80);
        }
    }
    private void nl() {
        try { doc.insertString(doc.getLength(), "\n", null); } catch (Exception ignored) { }
    }

    private void appendC(String s, String hex) {
        try {
            javax.swing.text.SimpleAttributeSet a = new javax.swing.text.SimpleAttributeSet();
            javax.swing.text.StyleConstants.setForeground(a, Color.decode(hex));
            javax.swing.text.StyleConstants.setFontFamily(a, Font.MONOSPACED);
            javax.swing.text.StyleConstants.setFontSize(a, 13);
            doc.insertString(doc.getLength(), s + "\n", a);
        } catch (Exception ignored) { }
        out.setCaretPosition(doc.getLength());
    }

    public static void main(String[] args) {
        try { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
        SwingUtilities.invokeLater(() -> new NFCCinder().setVisible(true));
    }

    /** 组件 19x10x19 落在 128 球内的体积(逐格点) */
    private static long overlapVolume(int x, int y, int z, int[] p) {
        long cnt = 0;
        for (int bx = p[0] - 9; bx <= p[0] + 9; bx++)
            for (int by = Math.max(0, p[1] - 5); by <= p[1] + 4; by++)
                for (int bz = p[2] - 9; bz <= p[2] + 9; bz++)
                    if (d2(x, y, z, new int[]{ bx, by, bz }) <= 128L * 128L) cnt++;
        return cnt;
    }

    /** 组件 19x10x19 整个盒子都在 128 球内 */
    private static boolean boxInside(int x, int y, int z, int[] p) {
        for (int bx = p[0] - 9; bx <= p[0] + 9; bx++)
            for (int by = Math.max(0, p[1] - 5); by <= p[1] + 4; by++)
                for (int bz = p[2] - 9; bz <= p[2] + 9; bz++)
                    if (d2(x, y, z, new int[]{ bx, by, bz }) > 128L * 128L) return false;
        return true;
    }

}
