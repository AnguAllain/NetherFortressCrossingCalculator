package nfc;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * calc 的统计与文字输出，口径和 projects/fortress/nfcpack2.py 对齐：
 * 每个方块只算一次，颜色优先级 橙 > 红 > 黄 > 绿。
 */
public final class NfcStats {
    private NfcStats() {}

    private static final int SSD = 0x8B5B32;

    public static void compute(NfcState st) {
        List<Crossing> crs = st.crossings;
        BlockPos c = st.center;
        if (c == null || crs.isEmpty()) {
            return;
        }
        int r = st.radius;

        Map<Crossing, Double> bmax = new HashMap<>();
        for (Crossing cr : crs) {
            bmax.put(cr, cr.maxDist(c));
        }

        List<Crossing> full = new ArrayList<>();
        List<Crossing> part = new ArrayList<>();
        List<Crossing> none = new ArrayList<>();
        for (Crossing cr : crs) {
            double m = bmax.get(cr);
            double d = cr.minDist(c);
            if (m <= r) {
                full.add(cr);
            } else if (d <= r) {
                part.add(cr);
            } else {
                none.add(cr);
            }
        }
        st.fullCnt = full.size();
        st.partCnt = part.size();
        st.noneCnt = none.size();

        // 重叠：两两比包围盒，交集外扩 2 格是画框用的
        Set<Crossing> overSet = new HashSet<>();
        st.overlapBoxes.clear();
        for (int i = 0; i < crs.size(); i++) {
            for (int j = i + 1; j < crs.size(); j++) {
                BlockPos[] box = crs.get(i).overlapBox(crs.get(j));
                if (box != null) {
                    st.overlapBoxes.add(box);
                    overSet.add(crs.get(i));
                    overSet.add(crs.get(j));
                }
            }
        }

        Set<Crossing> fullSet = new HashSet<>(full);
        Set<Crossing> partSet = new HashSet<>(part);
        Set<Crossing> noneSet = new HashSet<>(none);
        for (Crossing cr : crs) {
            if (overSet.contains(cr)) {
                cr.cat = Crossing.CAT_ORANGE;
            } else if (noneSet.contains(cr)) {
                cr.cat = Crossing.CAT_RED;
            } else if (partSet.contains(cr)) {
                cr.cat = Crossing.CAT_YELLOW;
            } else if (fullSet.contains(cr)) {
                cr.cat = Crossing.CAT_GREEN;
            } else {
                cr.cat = Crossing.CAT_RED;
            }
        }

        // 逐方块覆盖：同一个方块被几座路口压住
        Map<BlockPos, Integer> cov = new LinkedHashMap<>();
        Map<BlockPos, Integer> firstCat = new HashMap<>();
        for (Crossing cr : crs) {
            for (BlockPos p : cr.solidBlocks()) {
                cov.merge(p, 1, Integer::sum);
                firstCat.putIfAbsent(p, cr.cat);
            }
        }

        int green = 0, yellow = 0, orange = 0, red = 0, yellowOut = 0, orangeOut = 0;
        int dupIn = 0, dupOut = 0;
        List<BlockPos> inKeys = new ArrayList<>();
        for (Map.Entry<BlockPos, Integer> e : cov.entrySet()) {
            BlockPos p = e.getKey();
            int n = e.getValue();
            double dx = p.getX() - c.getX();
            double dy = p.getY() - c.getY();
            double dz = p.getZ() - c.getZ();
            boolean inside = dx * dx + dy * dy + dz * dz <= r * (double) r;
            if (inside) {
                inKeys.add(p);
            }
            if (n > 1) {
                if (inside) {
                    orange++;
                    dupIn += n - 1;
                } else {
                    orangeOut++;
                    dupOut += n - 1;
                }
                continue;
            }
            int cat = firstCat.getOrDefault(p, Crossing.CAT_GREEN);
            if (inside) {
                if (cat == Crossing.CAT_YELLOW) {
                    yellow++;
                } else if (cat == Crossing.CAT_GREEN) {
                    green++;
                } else {
                    orange++;  // 重叠路口单独被算到的方块
                }
            } else if (cat == Crossing.CAT_YELLOW) {
                yellowOut++;
            } else {
                red++;
            }
        }

        st.greenVol = green;
        st.yellowVol = yellow;
        st.orangeVol = orange;
        st.redVol = red;
        st.yellowOutVol = yellowOut;
        st.orangeOutVol = orangeOut;
        st.dupOver = dupIn + 2 * dupOut;
        st.volIn = green + yellow + orange;
        st.volOut = red + yellowOut;
        st.volAll = st.volIn + st.volOut + st.dupOver;

        ServerLevel lv = st.level;
        int ssd = 0;
        if (lv != null) {
            for (BlockPos p : inKeys) {
                if (NfcFortress.biomeName(lv, p.getX(), p.getY(), p.getZ()).equals("soul_sand_valley")) {
                    ssd++;
                }
            }
        }
        st.ssdVol = ssd;
    }

    public static void report(ServerPlayer player, NfcState st) {
        BlockPos c = st.center;
        List<Crossing> crs = st.crossings;

        line(player, comp("计算出来的中心挂机点： ", 0xFFFFFF).append(tp(c.getX(), c.getY(), c.getZ())));
        line(player, comp("其 128 格半径球体刷怪范围内十字路口的情况", 0xFFFFFF));

        if (st.overlapBoxes.isEmpty()) {
            line(player, comp("没有重叠的十字路口", 0xFFAA00));
        } else {
            line(player, comp("存在重叠的十字路口", 0xFFA500));
            int i = 0;
            for (BlockPos[] b : st.overlapBoxes) {
                int x = (b[0].getX() + b[1].getX()) / 2;
                int y = (b[0].getY() + b[1].getY()) / 2;
                int z = (b[0].getZ() + b[1].getZ()) / 2;
                line(player, comp((++i) + ". ", 0x55FFFF).append(tp(x, y, z)));
            }
        }

        List<Crossing> notSoul = new ArrayList<>();
        for (Crossing cr : crs) {
            if (!cr.soulSandValley) {
                notSoul.add(cr);
            }
        }
        if (notSoul.isEmpty()) {
            line(player, comp("没有不在灵魂沙峡谷的十字路口", SSD));
        } else {
            line(player, comp("不在灵魂沙峡谷的十字路口：", SSD));
            int i = 0;
            for (Crossing cr : notSoul) {
                BlockPos p = cr.center();
                String key = NfcFortress.biomeName(st.level, p.getX(), p.getY(), p.getZ());
                line(player, comp((++i) + ". ", 0x55FFFF).append(tp(p.getX(), p.getY(), p.getZ()))
                        .append(comp("  " + biomeCn(key), biomeColor(key))));
            }
        }

        line(player, comp("完全包含： ", 0x55FF55).append(comp("" + st.fullCnt, 0x55FF55))
                .append(comp("  |  ", 0xFFFFFF))
                .append(comp("不完全包含： ", 0xFFFF55)).append(comp("" + st.partCnt, 0xFFFF55))
                .append(comp("  |  ", 0xFFFFFF))
                .append(comp("完全不包含： ", 0xFF5555)).append(comp("" + st.noneCnt, 0xFF5555)));

        int yellowOut = st.yellowOutVol;
        int orangeOut = st.orangeOutVol;
        line(player, comp("总体积： ", 0xFFFFFF).append(comp("" + st.volAll, 0xFFFFFF)).append(comp(" 块", 0xFFFFFF)));
        line(player, comp("范围内可刷怪： ", 0xFFFFFF)
                .append(comp("" + st.volIn, 0x55FF55)).append(comp(" 块", 0xFFFFFF))
                .append(comp(" ( ", 0xFFFFFF))
                .append(comp("" + (st.volIn - st.yellowVol - st.orangeVol), 0x55FF55))
                .append(comp(" + ", 0xFFFFFF)).append(comp("" + st.yellowVol, 0xFFFF55))
                .append(comp(" + ", 0xFFFFFF)).append(comp("" + st.orangeVol, 0xFFAA00))
                .append(comp(" )", 0xFFFFFF)));
        line(player, comp("       占总体积： ", 0xFFFFFF)
                .append(comp(pct(st.volIn, st.volAll), 0x55FF55)).append(comp(" %", 0xFFFFFF)));
        line(player, comp("范围内可刷怪且位于灵魂沙峡谷： ", 0xFFFFFF)
                .append(comp("" + st.ssdVol, SSD)).append(comp(" 块", 0xFFFFFF)));
        line(player, comp("       占可刷怪体积： ", 0xFFFFFF).append(comp(pct(st.ssdVol, st.volIn), SSD))
                .append(comp(" %", 0xFFFFFF))
                .append(comp("  占总体积： ", 0xFFFFFF)).append(comp(pct(st.ssdVol, st.volAll), SSD))
                .append(comp(" %", 0xFFFFFF)));
        line(player, comp("不可刷怪： ", 0xFFFFFF)
                .append(comp("" + st.volOut, 0xFF5555)).append(comp(" 块", 0xFFFFFF))
                .append(comp(" ( ", 0xFFFFFF))
                .append(comp("" + st.redVol, 0xFF5555))
                .append(comp(" + ", 0xFFFFFF)).append(comp("" + yellowOut, 0xFFFF55))
                .append(comp(" + ", 0xFFFFFF)).append(comp("" + (orangeOut + st.dupOver), 0xFFAA00))
                .append(comp(" )", 0xFFFFFF)));
        line(player, comp("       占总体积： ", 0xFFFFFF)
                .append(comp(pct(st.volOut, st.volAll), 0xFF5555)).append(comp(" %", 0xFFFFFF)));
    }

    private static String biomeCn(String key) {
        return switch (key) {
            case "soul_sand_valley" -> "灵魂沙峡谷";
            case "crimson_forest" -> "绯红森林";
            case "warped_forest" -> "诡异森林";
            case "basalt_deltas" -> "玄武岩三角洲";
            case "nether_wastes" -> "下界荒地";
            default -> key;
        };
    }

    private static String pct(int a, int b) {
        if (b <= 0) {
            return "0.0";
        }
        return String.format("%.1f", 100.0 * a / b);
    }

    private static int biomeColor(String biome) {
        return switch (biome) {
            case "soul_sand_valley" -> 0x8B5B32;
            case "crimson_forest" -> 0xE67E22;
            case "warped_forest" -> 0x2B8B7E;
            case "basalt_deltas" -> 0x808080;
            default -> 0xFF5555; // 下界荒地
        };
    }

    private static MutableComponent comp(String text, int rgb) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)));
    }

    /** 坐标可点：点一下把 /tp 填进聊天框，回车才走。 */
    private static MutableComponent tp(int x, int y, int z) {
        return Component.literal("( " + x + " " + y + " " + z + " )")
                .withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF))
                        .withClickEvent(new ClickEvent.SuggestCommand("/tp " + x + " " + y + " " + z))
                        .withUnderlined(false));
    }

    private static void line(ServerPlayer player, Component c) {
        player.sendSystemMessage(c);
    }

    static final ChatFormatting UNUSED = ChatFormatting.WHITE;
}
