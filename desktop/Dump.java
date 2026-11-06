import java.util.*;

public final class Dump {
    public static void main(String[] args) {
        long seed = Long.parseLong(args[0]);
        int cx = Integer.parseInt(args[1]), cz = Integer.parseInt(args[2]);
        FortressGen g = new FortressGen(seed, cx, cz);
        g.startX = cx * 16 + 2;
        g.startZ = cz * 16 + 2;
        List<FortressGen.Piece> ps = g.generate();
        int i = 0;
        for (FortressGen.Piece p : ps) {
            System.out.printf("%3d %-16s %d %d %d .. %d %d %d%n", i++, p.type,
                p.x0, p.y0, p.z0, p.x1, p.y1, p.z1);
        }
        System.out.println("pieces=" + ps.size());
    }
}
