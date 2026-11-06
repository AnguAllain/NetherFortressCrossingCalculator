package nfc;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 按种子复现下界堡垒的十字路口（26.2，纯计算，不读存档里的结构数据）。
 *
 * <p>两步：</p>
 * <ol>
 *   <li>28 区块一格的区域格子 → 区域种子抽结构区块（getFeaturePos）。</li>
 *   <li>1.18+ 堡垒和堡垒遗迹抢同一个位：遗迹先过 nextInt(5)>=2 的门，
 *       抽中了再看遗迹的群系取样点，落在四种群系里就算遗迹占位，堡垒不存在。</li>
 * </ol>
 *
 * <p>组件布局交给 {@link FortressGen}（照官方 NetherFortressPieces 移植），
 * 结构随机源是 chunkGenerateRnd。</p>
 */
public final class NfcFortress {
    /** 堡垒 / 堡垒遗迹共用 salt 与格子尺寸。 */
    static final long SALT_FORTRESS = 30084232L;
    static final int REGION_SIZE = 27;
    static final int CHUNK_RANGE = 23;

    private NfcFortress() {
    }

    /** 区域里堡垒所在区块。 */
    public static ChunkPos fortressChunkInRegion(long worldSeed, int rx, int rz) {
        long s = worldSeed + (long) rx * 341873128712L + (long) rz * 132897987541L + SALT_FORTRESS;
        Random r = new Random(s);
        int cx = rx * REGION_SIZE + r.nextInt(CHUNK_RANGE);
        int cz = rz * REGION_SIZE + r.nextInt(CHUNK_RANGE);
        return new ChunkPos(cx, cz);
    }

    /**
     * 这个位置是不是被堡垒遗迹占了（占了就没有堡垒）。
     * 遗迹和堡垒同一个结构位；先看遗迹自己的 nextInt(5) 门，再看群系取样点。
     */
    public static boolean bastionTakes(ServerLevel level, long worldSeed, ChunkPos fc) {
        long rnd = FortressGen.seedForStructure(worldSeed, fc.x(), fc.z());
        Random gate = new Random(rnd);
        if (gate.nextInt(5) < 2) {
            return false; // 遗迹没抽中，堡垒留着
        }
        Random vr = new Random(rnd);
        int rotation = vr.nextInt(4);
        int start = vr.nextInt(4);
        int sx;
        int sz;
        switch (start) {
            case 0: sx = 46; sz = 46; break;  // units/air_base
            case 1: sx = 30; sz = 48; break;  // hoglin_stable/air_base
            case 2: sx = 38; sz = 38; break;  // treasure/big_air_full
            default: sx = 16; sz = 32; break; // bridge/entrance_base
        }
        int ox;
        int oz;
        int osx;
        int osz;
        switch (rotation) {
            case 0: ox = 0; oz = 0; osx = sx; osz = sz; break;
            case 1: ox = 1 - sz; oz = 0; osx = sz; osz = sx; break;
            case 2: ox = 1 - sx; oz = 1 - sz; osx = sx; osz = sz; break;
            default: ox = 0; oz = 1 - sx; osx = sz; osz = sx; break;
        }
        // 遗迹的群系取样点在结构中心附近（quart 坐标）
        int sampleX = (fc.x() * 32 + 2 * ox + osx - 1) / 2 >> 2;
        int sampleZ = (fc.z() * 32 + 2 * oz + osz - 1) / 2 >> 2;
        String biome = biomeName(level, sampleX * 4, 64, sampleZ * 4);
        return biome.equals("nether_wastes")
                || biome.equals("soul_sand_valley")
                || biome.equals("warped_forest")
                || biome.equals("crimson_forest");
    }

    
    /**
     * 收集以 (cx, cz)（方块坐标）为中心、半径 radius（方块）内的全部十字路口。
     */
    public static List<Crossing> collect(ServerLevel level, long worldSeed, int cx, int cz, int radius) {
        return collect(level, worldSeed, cx, cz, radius, null);
    }

    /** 同上，chunks 非空时按 group 顺序队进每座堡垒所在的区块 */
    public static List<Crossing> collect(ServerLevel level, long worldSeed, int cx, int cz, int radius,
                                         java.util.List<ChunkPos> chunks) {
        List<Crossing> out = new ArrayList<>();
        int perRegion = REGION_SIZE * 16;
        int gi = 0;
        int rx0 = Math.floorDiv(cx - radius, perRegion);
        int rx1 = Math.floorDiv(cx + radius, perRegion);
        int rz0 = Math.floorDiv(cz - radius, perRegion);
        int rz1 = Math.floorDiv(cz + radius, perRegion);
        for (int rx = rx0; rx <= rx1; rx++) {
            for (int rz = rz0; rz <= rz1; rz++) {
                ChunkPos fc = fortressChunkInRegion(worldSeed, rx, rz);
                if (bastionTakes(level, worldSeed, fc)) {
                    continue;
                }
                List<int[]> boxes = FortressGen.crossingBoxes(worldSeed, fc.x(), fc.z());
                int before = out.size();
                for (int[] b : boxes) {
                    BlockPos min = new BlockPos(b[0], b[1], b[2]);
                    BlockPos max = new BlockPos(b[3], b[4], b[5]);
                    Crossing c = new Crossing(min, max);
                    if (Math.abs(c.cx() - cx) > radius || Math.abs(c.cz() - cz) > radius) {
                        continue;
                    }
                    c.soulSandValley = allSoulSandValley(level, min, max);
                    c.group = gi;
                    out.add(c);
                }
                if (out.size() > before) {
                    if (chunks != null) chunks.add(fc);
                    gi++;
                }
            }
        }
        return out;
    }

    /** 判定一块区域是否全在灵魂沙峡谷（逐方块判，不取平均）。 */
    public static boolean allSoulSandValley(ServerLevel level, BlockPos min, BlockPos max) {
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    if (!biomeName(level, x, y, z).equals("soul_sand_valley")) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** 群系 id（只要路径名，不带命名空间）。 */
    static String biomeName(ServerLevel level, int x, int y, int z) {
        return level.getBiome(new BlockPos(x, y, z)).unwrapKey()
                .map(k -> k.identifier().getPath())
                .orElse("");
    }
}
