package nfc;

import java.util.List;

/**
 * 球心计算：在候选网格里找「128 格半径内能塞进最多十字路口」的那一点，
 * 并列时取离全部路口重心最近的那个（y 偏差权重加倍，避免垂直往下掉）。
 *
 * 和数据包离线脚本 projects/fortress/nfcpack2.py 里的 best_center() 同一套规则：
 * 能刷怪 = 整块都在 128 球内，并且挂机空腔（3x3x3）离这块路口最近也超过 24 格。
 */
public final class NfcCenter {
    private NfcCenter() {}

    public static final int RADIUS = 128;

    /** 路口包围盒：x1 y1 z1 x2 y2 z2 */
    public record Box(int x1, int y1, int z1, int x2, int y2, int z2) {
        public double dmax(int x, int y, int z) {
            double m = 0;
            for (int px : new int[]{x1, x2}) {
                for (int py : new int[]{y1, y2}) {
                    for (int pz : new int[]{z1, z2}) {
                        double dx = px - x, dy = py - y, dz = pz - z;
                        m = Math.max(m, Math.sqrt(dx * dx + dy * dy + dz * dz));
                    }
                }
            }
            return m;
        }

        public double cavityGap(int x, int y, int z) {
            // 空腔 3x3x3 不含脚下那层玻璃：x±1, y..y+2, z±1
            double dx = Math.max(Math.max(x1 - (x + 1), 0), (x - 1) - x2);
            double dy = Math.max(Math.max(y1 - (y + 2), 0), y - y2);
            double dz = Math.max(Math.max(z1 - (z + 1), 0), (z - 1) - z2);
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        public boolean hitsHollow(int x, int y, int z) {
            return x1 <= x + 1 && x - 1 <= x2
                    && y1 <= y + 2 && y <= y2
                    && z1 <= z + 1 && z - 1 <= z2;
        }
    }

    /** cov = 这个球心能覆盖到几座堡垒（多联优先）。 */
    private record Scored(int cov, int count, double dev, int x, int y, int z) implements Comparable<Scored> {
        @Override
        public int compareTo(Scored o) {
            if (cov != o.cov) return Integer.compare(cov, o.cov);
            if (count != o.count) return Integer.compare(count, o.count);
            if (dev != o.dev) return Double.compare(o.dev, dev); // dev 越小越好
            return 0;
        }
    }

    public static int[] bestCenter(List<Box> boxes, List<Box> blockers) {
        return bestCenter(boxes, blockers, null);
    }

    /**
     * @param boxes    参与打分的路口（soul 口径时只放灵魂沙峡谷的）
     * @param blockers 所有路口，用来判挂机空腔有没有被占到
     * @param groups   与 boxes 一一对应的堡垒编号；null 表示不按多联优先
     */
    public static int[] bestCenter(List<Box> boxes, List<Box> blockers, int[] groups) {
        if (boxes.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        double cx = 0, cy = 0, cz = 0;
        for (Box b : boxes) {
            minX = Math.min(minX, b.x1);
            maxX = Math.max(maxX, b.x2);
            minY = Math.min(minY, b.y1);
            maxY = Math.max(maxY, b.y2);
            minZ = Math.min(minZ, b.z1);
            maxZ = Math.max(maxZ, b.z2);
            cx += (b.x1 + b.x2) / 2.0;
            cy += (b.y1 + b.y2) / 2.0;
            cz += (b.z1 + b.z2) / 2.0;
        }
        cx /= boxes.size();
        cy /= boxes.size();
        cz /= boxes.size();

        Scored best = score(boxes, blockers, groups, (int) Math.round(cx), (int) Math.round(cy), (int) Math.round(cz), cx, cy, cz);

        for (int gx = minX - 16; gx <= maxX + 16; gx += 8) {
            for (int gz = minZ - 16; gz <= maxZ + 16; gz += 8) {
                for (int gy = Math.max(0, minY - 8); gy <= Math.min(255, maxY + 8); gy += 4) {
                    Scored s = score(boxes, blockers, groups, gx, gy, gz, cx, cy, cz);
                    if (s.compareTo(best) > 0) best = s;
                }
            }
        }

        int[] cur = {best.x, best.y, best.z};
        for (int step = 4; step >= 1; step /= 2) {
            boolean improved = false;
            for (int dx = -step; dx <= step; dx += step) {
                for (int dy = -step; dy <= step; dy += step) {
                    for (int dz = -step; dz <= step; dz += step) {
                        Scored s = score(boxes, blockers, groups, cur[0] + dx, cur[1] + dy, cur[2] + dz, cx, cy, cz);
                        if (s.compareTo(best) > 0) {
                            best = s;
                            cur = new int[]{s.x, s.y, s.z};
                            improved = true;
                        }
                    }
                }
            }
            if (!improved) {
                continue;
            }
        }
        return cur;
    }

    private static Scored score(List<Box> boxes, List<Box> blockers, int[] groups, int x, int y, int z,
                                double cx, double cy, double cz) {
        boolean hollowFree = true;
        for (Box b : blockers) {
            if (b.hitsHollow(x, y, z)) {
                hollowFree = false;
                break;
            }
        }
        int count = 0, cov = 0;
        if (hollowFree) {
            java.util.HashSet<Integer> hit = (groups == null) ? null : new java.util.HashSet<>();
            for (int i = 0; i < boxes.size(); i++) {
                Box b = boxes.get(i);
                if (b.dmax(x, y, z) <= RADIUS && b.cavityGap(x, y, z) > 24) {
                    count++;
                    if (hit != null && i < groups.length) hit.add(groups[i]);
                }
            }
            if (hit != null) cov = hit.size();
        }
        double dev = Math.abs(x - cx) + Math.abs(y - cy) * 2 + Math.abs(z - cz);
        return new Scored(cov, count, dev, x, y, z);
    }
}
