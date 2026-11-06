import java.util.*;

/**
 * 下界堡垒拼图生成器（照 26.2 官方 NetherFortressPieces 移植，只算包围盒、不写方块）。
 * 用途：光给种子就能算出每座堡垒里所有十字路口（NEBR）的包围盒与中心点。
 *
 * 移植来源：CFR 反编译 26.2 客户端 jar
 *   net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces
 *   net.minecraft.world.level.levelgen.structure.BoundingBox#orientBox
 *   net.minecraft.world.level.levelgen.structure.structures.NetherFortressStructure#generatePieces
 */
public final class FortressGen {

    public enum D { NORTH, SOUTH, WEST, EAST }

    static final int MAGIC_START_Y = 64;
    static final int MAX_DEPTH = 30;

    /** 房间类型：只有 BRIDGE_CROSSING 和 CASTLE_SMALL_CORRIDOR_CROSSING 是「十字路口」。 */
    public enum T {
        BRIDGE_STRAIGHT, BRIDGE_CROSSING, BRIDGE_END,
        ROOM_CROSSING, STAIRS_ROOM, MONSTER_THRONE, CASTLE_ENTRANCE,
        CASTLE_CORRIDOR, CASTLE_RIGHT_TURN, CASTLE_LEFT_TURN,
        CASTLE_STAIRS, CASTLE_T_BALCONY, CASTLE_SMALL_CROSSING, CASTLE_STALK_ROOM
    }

    public static final class Piece {
        public final T type; public final int depth;
        public final int x0, y0, z0, x1, y1, z1;
        public final D dir;
        Piece(T type, int depth, int[] b, D dir) {
            this.type = type; this.depth = depth;
            this.x0 = b[0]; this.y0 = b[1]; this.z0 = b[2];
            this.x1 = b[3]; this.y1 = b[4]; this.z1 = b[5];
            this.dir = dir;
        }
        public boolean isCrossing() {
            return type == T.BRIDGE_CROSSING || type == T.CASTLE_SMALL_CROSSING;
        }
        public int cx() { return (x0 + x1) / 2; }
        public int cz() { return (z0 + z1) / 2; }
        public String toString() {
            return type + " " + x0 + " " + y0 + " " + z0 + " .. " + x1 + " " + y1 + " " + z1;
        }
    }

    // ---- 权重表（照官方顺序）----
    static final class W {
        final T t; final int weight; final int max; final boolean allowInRow;
        int placed;
        W(T t, int weight, int max, boolean allowInRow) { this.t = t; this.weight = weight; this.max = max; this.allowInRow = allowInRow; }
        W(T t, int weight, int max) { this(t, weight, max, false); }
        boolean doPlace(int depth) { return max == 0 || placed < max; }
        boolean isValid() { return max == 0 || placed < max; }
    }

    static List<W> newBridgeWeights() {
        return new ArrayList<>(List.of(
            new W(T.BRIDGE_STRAIGHT, 30, 0, true),
            new W(T.BRIDGE_CROSSING, 10, 4),
            new W(T.ROOM_CROSSING, 10, 4),
            new W(T.STAIRS_ROOM, 10, 3),
            new W(T.MONSTER_THRONE, 5, 2),
            new W(T.CASTLE_ENTRANCE, 5, 1)
        ));
    }

    static List<W> newCastleWeights() {
        return new ArrayList<>(List.of(
            new W(T.CASTLE_CORRIDOR, 25, 0, true),
            new W(T.CASTLE_SMALL_CROSSING, 15, 5),
            new W(T.CASTLE_RIGHT_TURN, 5, 10),
            new W(T.CASTLE_LEFT_TURN, 5, 10),
            new W(T.CASTLE_STAIRS, 10, 3, true),
            new W(T.CASTLE_T_BALCONY, 7, 2),
            new W(T.CASTLE_STALK_ROOM, 5, 2)
        ));
    }
    final Random rnd;
    final List<W> bridge = newBridgeWeights();
    final List<W> castle = newCastleWeights();
    final List<Piece> all = new ArrayList<>();
    final List<Piece> pending = new ArrayList<>();
    W previous;
    int curIdx = -1;
    int startX, startZ, startMinX, startMinZ;

    public FortressGen(long worldSeed, int chunkX, int chunkZ) {
        this.rnd = new Random(seedForStructure(worldSeed, chunkX, chunkZ));
    }

    /** 结构随机源：MC>1.15 用 chunkGenerateRnd = (nextLong*chunkX) ^ (nextLong*chunkZ) ^ seed */
    static long SALT = Long.getLong("nfc.salt", 0L);

    static long seedForStructure(long worldSeed, int cx, int cz) {
        long s = worldSeed + SALT;
        Random r = new Random(s);
        long a = r.nextLong(), b = r.nextLong();
        return (a * (long) cx) ^ (b * (long) cz) ^ s;
    }

    static int[] orientBox(int fx, int fy, int fz, int ox, int oy, int oz, int w, int h, int d, D dir) {
        switch (dir) {
            case NORTH: return new int[]{ fx + ox, fy + oy, fz - d + 1 + oz, fx + w - 1 + ox, fy + h - 1 + oy, fz + oz };
            case WEST:  return new int[]{ fx - d + 1 + oz, fy + oy, fz + ox, fx + oz, fy + h - 1 + oy, fz + w - 1 + ox };
            case EAST:  return new int[]{ fx + oz, fy + oy, fz + ox, fx + d - 1 + oz, fy + h - 1 + oy, fz + w - 1 + ox };
            default:    return new int[]{ fx + ox, fy + oy, fz + oz, fx + w - 1 + ox, fy + h - 1 + oy, fz + d - 1 + oz };
        }
    }

    static boolean isOkBox(int[] b) { return b[1] > 10; }

    static boolean intersects(int[] a, int[] b) {
        return a[3] >= b[0] && a[0] <= b[3] && a[5] >= b[2] && a[2] <= b[5] && a[4] >= b[1] && a[1] <= b[4];
    }

    boolean collides(int[] box) {
        for (Piece p : all) {
            int[] b = { p.x0, p.y0, p.z0, p.x1, p.y1, p.z1 };
            if (intersects(box, b)) return true;
        }
        return false;
    }

    /** 生成一座堡垒（起点为结构区块的 (x*16+2, 64, z*16+2)） */
    public List<Piece> generate() {
        // Direction.Plane.HORIZONTAL 的顺序是 NORTH, EAST, SOUTH, WEST
        D dir = new D[]{ D.NORTH, D.EAST, D.SOUTH, D.WEST }[rnd.nextInt(4)];
        int[] box = new int[]{ startX, MAGIC_START_Y, startZ, startX + 18, MAGIC_START_Y + 9, startZ + 18 };
        Piece start = new Piece(T.BRIDGE_CROSSING, 0, box, dir);
        all.add(start);
        startMinX = box[0]; startMinZ = box[2];
        addChildren(start);
        while (!pending.isEmpty()) {
            int i = rnd.nextInt(pending.size());
            Piece p = pending.remove(i);
            curIdx = all.indexOf(p);
            addChildren(p);
        }
        // 对应官方 builder.moveInsideHeights(random, 48, 70)
        moveInsideHeights(48, 70);
        return all;
    }

    /** 把所有房间整体上下平移，让结构落在 [minY, maxY] 之间（照官方 moveInsideHeights） */
    void moveInsideHeights(int lowestAllowed, int highestAllowed) {
        if (all.isEmpty()) return;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (Piece p : all) { lo = Math.min(lo, p.y0); hi = Math.max(hi, p.y1); }
        int span = hi - lo;                       // BoundingBox.getYSpan()
        int heightSpan = highestAllowed - lowestAllowed + 1 - span;
        int y0Pos = heightSpan > 1 ? lowestAllowed + rnd.nextInt(heightSpan) : lowestAllowed;
        int dy = y0Pos - lo;
        System.err.println("DBG lo=" + lo + " hi=" + hi + " span=" + span + " heightSpan=" + heightSpan + " y0Pos=" + y0Pos + " dy=" + dy);
        List<Piece> shifted = new ArrayList<>(all.size());
        for (Piece p : all) {
            int[] b = { p.x0, p.y0 + dy, p.z0, p.x1, p.y1 + dy, p.z1 };
            shifted.add(new Piece(p.type, p.depth, b, p.dir));
        }
        all.clear(); all.addAll(shifted);
    }

    void addChildren(Piece p) {
        switch (p.type) {
            case BRIDGE_STRAIGHT: childForward(p, 1, 3, false); break;
            case BRIDGE_CROSSING:
                childForward(p, 8, 3, false); childLeft(p, 3, 8, false); childRight(p, 3, 8, false); break;
            case ROOM_CROSSING:
                childForward(p, 2, 0, false); childLeft(p, 0, 2, false); childRight(p, 0, 2, false); break;
            case STAIRS_ROOM: childRight(p, 6, 2, false); break;
            case MONSTER_THRONE: break;
            case CASTLE_ENTRANCE: childForward(p, 5, 3, true); break;
            case CASTLE_CORRIDOR: childForward(p, 1, 0, true); break;
            case CASTLE_RIGHT_TURN: childRight(p, 0, 1, true); break;
            case CASTLE_LEFT_TURN: childLeft(p, 0, 1, true); break;
            case CASTLE_STAIRS: childForward(p, 1, 0, true); break;
            case CASTLE_T_BALCONY: {
                int zOff = (p.dir == D.WEST || p.dir == D.NORTH) ? 5 : 1;
                childLeft(p, 0, zOff, rnd.nextInt(8) > 0);
                childRight(p, 0, zOff, rnd.nextInt(8) > 0);
                break;
            }
            case CASTLE_SMALL_CROSSING:
                childForward(p, 1, 0, true); childLeft(p, 0, 1, true); childRight(p, 0, 1, true); break;
            case CASTLE_STALK_ROOM: childForward(p, 5, 3, true); childForward(p, 5, 11, true); break;
            default: break;
        }
    }

    void childForward(Piece p, int xOff, int yOff, boolean isCastle) {
        switch (p.dir) {
            case NORTH: andAdd(p, p.x0 + xOff, p.y0 + yOff, p.z0 - 1, D.NORTH, isCastle); break;
            case SOUTH: andAdd(p, p.x0 + xOff, p.y0 + yOff, p.z1 + 1, D.SOUTH, isCastle); break;
            case WEST:  andAdd(p, p.x0 - 1, p.y0 + yOff, p.z0 + xOff, D.WEST, isCastle); break;
            case EAST:  andAdd(p, p.x1 + 1, p.y0 + yOff, p.z0 + xOff, D.EAST, isCastle); break;
            default: break;
        }
    }

    void childLeft(Piece p, int yOff, int zOff, boolean isCastle) {
        switch (p.dir) {
            case NORTH:
            case SOUTH: andAdd(p, p.x0 - 1, p.y0 + yOff, p.z0 + zOff, D.WEST, isCastle); break;
            case WEST:
            case EAST:  andAdd(p, p.x0 + zOff, p.y0 + yOff, p.z0 - 1, D.NORTH, isCastle); break;
            default: break;
        }
    }

    void childRight(Piece p, int yOff, int zOff, boolean isCastle) {
        switch (p.dir) {
            case NORTH:
            case SOUTH: andAdd(p, p.x1 + 1, p.y0 + yOff, p.z0 + zOff, D.EAST, isCastle); break;
            case WEST:
            case EAST:  andAdd(p, p.x0 + zOff, p.y0 + yOff, p.z1 + 1, D.SOUTH, isCastle); break;
            default: break;
        }
    }

    void andAdd(Piece parent, int fx, int fy, int fz, D dir, boolean isCastle) {
        if (Math.abs(fx - startMinX) > 112 || Math.abs(fz - startMinZ) > 112) {
            makeAndAdd(T.BRIDGE_END, parent.depth, fx, fy, fz, dir, true);
            return;
        }
        Piece np = generatePiece(parent.depth + 1, fx, fy, fz, dir, isCastle ? castle : bridge);
        if (np != null) { all.add(np); pending.add(np); }
    }

    Piece generatePiece(int depth, int fx, int fy, int fz, D dir, List<W> weights) {
        int total = 0; boolean any = false;
        for (W w : weights) { if (w.max > 0 && w.placed < w.max) any = true; total += w.weight; }
        if (!any) total = 0;
        boolean doStuff = total > 0 && depth <= MAX_DEPTH;
        int attempts = 0;
        while (attempts < 5 && doStuff) {
            attempts++;
            int sel = rnd.nextInt(total);
            for (W w : weights) {
                if ((sel -= w.weight) >= 0) continue;
                if (!w.doPlace(depth) || (w == previous && !w.allowInRow)) break;
                Piece p = create(w.t, depth, fx, fy, fz, dir);
                if (p == null) continue;
                w.placed++;
                previous = w;
                if (!w.isValid()) weights.remove(w);
                return p;
            }
        }
        return makeAndAdd(T.BRIDGE_END, depth, fx, fy, fz, dir, false);
    }

    Piece makeAndAdd(T t, int depth, int fx, int fy, int fz, D dir, boolean only) {
        Piece p = create(t, depth, fx, fy, fz, dir);
        return only ? p : p;
    }

    /** 各房间的包围盒（照官方 createPiece 的 orientBox 参数） */
    Piece create(T t, int depth, int fx, int fy, int fz, D dir) {
        int[] b = null;
        switch (t) {
            case BRIDGE_STRAIGHT: b = orientBox(fx, fy, fz, -1, -3, 0, 5, 10, 19, dir); break;
            case BRIDGE_CROSSING: b = orientBox(fx, fy, fz, -8, -3, 0, 19, 10, 19, dir); break;
            case BRIDGE_END:      b = orientBox(fx, fy, fz, -1, -3, 0, 5, 10, 8, dir); break;
            case ROOM_CROSSING:   b = orientBox(fx, fy, fz, -2, 0, 0, 7, 9, 7, dir); break;
            case STAIRS_ROOM:     b = orientBox(fx, fy, fz, -2, 0, 0, 7, 11, 7, dir); break;
            case MONSTER_THRONE:  b = orientBox(fx, fy, fz, -2, 0, 0, 7, 8, 9, dir); break;
            case CASTLE_ENTRANCE: b = orientBox(fx, fy, fz, -5, -3, 0, 13, 14, 13, dir); break;
            case CASTLE_CORRIDOR:
            case CASTLE_RIGHT_TURN:
            case CASTLE_LEFT_TURN: b = orientBox(fx, fy, fz, -1, 0, 0, 5, 7, 5, dir); break;
            case CASTLE_STAIRS:    b = orientBox(fx, fy, fz, -1, -7, 0, 5, 14, 10, dir); break;
            case CASTLE_T_BALCONY: b = orientBox(fx, fy, fz, -3, 0, 0, 9, 7, 9, dir); break;
            case CASTLE_SMALL_CROSSING: b = orientBox(fx, fy, fz, -1, 0, 0, 5, 7, 5, dir); break;
            case CASTLE_STALK_ROOM: b = orientBox(fx, fy, fz, -5, -3, 0, 13, 14, 13, dir); break;
            default: return null;
        }
        if (!isOkBox(b) || collides(b)) return null;
        // 官方构造函数本身也吃随机数（isNeedingChest / selfSeed），漏了会让后面的随机流整体错位
        switch (t) {
            case CASTLE_RIGHT_TURN:
            case CASTLE_LEFT_TURN: rnd.nextInt(3); break;
            case BRIDGE_END: rnd.nextInt(); break;
            default: break;
        }
        return new Piece(t, depth, b, dir);
    }


    /** 整座堡垒的体积：全部组件包围盒体积之和（体积块数，不扣重叠） */
    public static long fortressVolume(long seed, int cx, int cz) {
        FortressGen g = new FortressGen(seed, cx, cz);
        g.startX = cx * 16 + 2;
        g.startZ = cz * 16 + 2;
        long v = 0;
        for (Piece p : g.generate()) {
            long dx = p.x1 - p.x0 + 1, dy = p.y1 - p.y0 + 1, dz = p.z1 - p.z0 + 1;
            if (dx > 0 && dy > 0 && dz > 0) v += dx * dy * dz;
        }
        return v;
    }

    /** 给定种子与结构区块，返回全部十字路口 {x, y, z} */
    public static java.util.List<int[]> crossings(long seed, int cx, int cz) {
        java.util.List<int[]> res = new java.util.ArrayList<>();
        FortressGen g = new FortressGen(seed, cx, cz);
        g.startX = cx * 16 + 2;
        g.startZ = cz * 16 + 2;
        for (Piece p : g.generate()) {
            if (p.isCrossing() && p.type.name().equals("BRIDGE_CROSSING")) res.add(new int[]{ p.cx(), (p.y0 + p.y1) / 2, p.cz() });
        }
        return res;
    }

    /** 自测：给定世界种子与结构区块坐标，打印十字路口 */
    public static void main(String[] args) {
        long seed = Long.parseLong(args[0]);
        int cx = Integer.parseInt(args[1]), cz = Integer.parseInt(args[2]);
        FortressGen g = new FortressGen(seed, cx, cz);
        g.startX = cx * 16 + 2;
        g.startZ = cz * 16 + 2;
        List<Piece> pieces = g.generate();
        int n = 0;
        for (Piece p : pieces) {
            if (p.isCrossing() && p.type.name().equals("BRIDGE_CROSSING")) { n++; System.out.printf("NEBR %d %d %d  (%s)%n", p.cx(), (p.y0 + p.y1) / 2, p.cz(), p.type); }
        }
        System.out.println("pieces=" + pieces.size() + " crossings=" + n);
    }
}
