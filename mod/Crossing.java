package nfc;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** 一个十字路口（堡垒组件里的 Crossing / nebcr 那一块）。 */
public class Crossing {
    public static final int CAT_GREEN = 0;   // 完整在球内
    public static final int CAT_YELLOW = 1;  // 部分包含
    public static final int CAT_RED = 2;     // 完全在球外
    public static final int CAT_ORANGE = 3;  // 有重叠

    public final BlockPos min;
    public final BlockPos max;
    public boolean soulSandValley = true;
    public int cat = CAT_GREEN;
    /** 属于哪座堡垒（多联优先用）。 */
    public int group = 0;

    public Crossing(BlockPos min, BlockPos max) {
        this.min = min;
        this.max = max;
    }

    public int cx() {
        return (min.getX() + max.getX()) / 2;
    }

    public int cy() {
        return (min.getY() + max.getY()) / 2;
    }

    public int cz() {
        return (min.getZ() + max.getZ()) / 2;
    }

    public BlockPos center() {
        return new BlockPos(cx(), cy(), cz());
    }

    public boolean overlaps(Crossing o) {
        return min.getX() <= o.max.getX() && o.min.getX() <= max.getX()
                && min.getY() <= o.max.getY() && o.min.getY() <= max.getY()
                && min.getZ() <= o.max.getZ() && o.min.getZ() <= max.getZ();
    }

    /** 两个包围盒的交集（已向外扩 2 格），没有交集返回 null。 */
    public BlockPos[] overlapBox(Crossing o) {
        if (!overlaps(o)) {
            return null;
        }
        int x1 = Math.max(min.getX(), o.min.getX()) - 2;
        int y1 = Math.max(min.getY(), o.min.getY()) - 2;
        int z1 = Math.max(min.getZ(), o.min.getZ()) - 2;
        int x2 = Math.min(max.getX(), o.max.getX()) + 2;
        int y2 = Math.min(max.getY(), o.max.getY()) + 2;
        int z2 = Math.min(max.getZ(), o.max.getZ()) + 2;
        return new BlockPos[]{new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2)};
    }

    /** 这座路口的真实方块（模板偏移 + 包围盒最小角），不能用包围盒代替。 */
    public List<BlockPos> solidBlocks() {
        List<BlockPos> out = new ArrayList<>(NfcTemplate.OFFSETS.length);
        for (int v : NfcTemplate.OFFSETS) {
            out.add(new BlockPos(min.getX() + NfcTemplate.x(v),
                    min.getY() + NfcTemplate.y(v),
                    min.getZ() + NfcTemplate.z(v)));
        }
        return out;
    }

    /** 真实方块里离 c 最远的那块的距离。 */
    public double maxDist(BlockPos c) {
        double m = 0;
        for (int v : NfcTemplate.OFFSETS) {
            double dx = min.getX() + NfcTemplate.x(v) - c.getX();
            double dy = min.getY() + NfcTemplate.y(v) - c.getY();
            double dz = min.getZ() + NfcTemplate.z(v) - c.getZ();
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > m) {
                m = d;
            }
        }
        return m;
    }

    /** 包围盒到点 c 的最近距离。 */
    public double minDist(BlockPos c) {
        double dx = Math.max(Math.max(min.getX() - c.getX(), 0), c.getX() - max.getX());
        double dy = Math.max(Math.max(min.getY() - c.getY(), 0), c.getY() - max.getY());
        double dz = Math.max(Math.max(min.getZ() - c.getZ(), 0), c.getZ() - max.getZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
