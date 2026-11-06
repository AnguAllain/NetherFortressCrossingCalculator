package nfc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class NfcCommands {
    private NfcCommands() {
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState BRICK = Blocks.NETHER_BRICKS.defaultBlockState();
    private static final BlockState BRICK_RED = Blocks.RED_NETHER_BRICKS.defaultBlockState();
    private static final BlockState C_ORANGE = Blocks.STAINED_GLASS.orange().defaultBlockState();
    private static final BlockState C_RED = Blocks.STAINED_GLASS.red().defaultBlockState();
    private static final BlockState C_YELLOW = Blocks.STAINED_GLASS.yellow().defaultBlockState();
    private static final BlockState C_GREEN = Blocks.STAINED_GLASS.lime().defaultBlockState();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("nfcc")
                .requires(src -> src.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER));

        // /nfcc stop 停掉当前一切操作
        root.then(Commands.literal("stop").executes(ctx -> {
            CommandSourceStack src = ctx.getSource();
            NfcJob j = NfcServer.running;
            if (j == null) {
                src.sendSuccess(() -> Component.literal("现在没有在跑的任务"), false);
                return 0;
            }
            NfcServer.running = null;
            j.cancel();
            if (j.player() != null) {
                j.player().sendSystemMessage(Component.literal("已停止"));
            }
            src.sendSuccess(() -> Component.literal("已停止"), false);
            return 1;
        }));

        // /nfc seed [种子]
        root.then(Commands.literal("seed")
                .executes(ctx -> {
                    NfcState st = NfcServer.STATE;
                    if (st.seed == null) {
                        ctx.getSource().sendFailure(Component.literal("缺少种子，无法计算堡垒组件"));
                        return 0;
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal("当前种子：" + st.seed), false);
                    return 1;
                })
                .then(Commands.argument("value", LongArgumentType.longArg())
                        .executes(ctx -> {
                            long seed = LongArgumentType.getLong(ctx, "value");
                            NfcServer.STATE.seed = seed;
                            ctx.getSource().sendSuccess(() -> Component.literal("种子已设为 " + seed + "，请核对是否正确"), false);
                            return 1;
                        })));

        // /nfc clear <半径> <normal|complete>
        // /nfc clear <pos1> <pos2> <normal|complete>
        root.then(Commands.literal("clear")
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 4096))
                        .executes(ctx -> runClear(ctx, 143, false, true))
                        .then(modeNode("complete", 255, false)))
                .then(Commands.argument("pos1", BlockPosArgument.blockPos())
                        .then(Commands.argument("pos2", BlockPosArgument.blockPos())
                                .executes(ctx -> runClear(ctx, 143, true, true))
                                .then(modeNode("complete", 255, true)))));

        // /nfcc calc < cross | normal >
        root.then(Commands.literal("calc")
                .then(Commands.literal("cross")
                        .then(crossNode("soul", true))
                        .then(crossNode("normal", false)))
                .then(Commands.literal("normal")
                        .then(calcNormalNode("normal", false))
                        .then(calcNormalNode("soul", true))));
        dispatcher.register(root);
    }

    // ---------------------------------------------------------------- clear

    private static LiteralArgumentBuilder<CommandSourceStack> modeNode(String name, int topY, boolean twoPos) {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(name);
        node.executes(ctx -> runClear(ctx, topY, twoPos, true));
        return node;
    }

    /** strict = 连流动的流体一起按死，不给邻居更新，避免清完又被岩浆灌回来。 */
    private static int runClear(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                                int topY, boolean twoPos, boolean strict) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!twoPos) {
            int radius = IntegerArgumentType.getInteger(ctx, "radius");
            return startClear(ctx.getSource(), radius, topY, strict);
        }
        BlockPos a = BlockPosArgument.getLoadedBlockPos(ctx, "pos1");
        BlockPos b = BlockPosArgument.getLoadedBlockPos(ctx, "pos2");
        int x1 = Math.min(a.getX(), b.getX());
        int z1 = Math.min(a.getZ(), b.getZ());
        int x2 = Math.max(a.getX(), b.getX());
        int z2 = Math.max(a.getZ(), b.getZ());
        return startClearBox(ctx.getSource(), x1, z1, x2, z2, topY, strict);
    }

    private static int startClear(CommandSourceStack src, int radius, int topY, boolean strict) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("请在游戏内执行"));
            return 0;
        }
        BlockPos center = player.blockPosition();
        return startClearBox(src, center.getX() - radius, center.getZ() - radius,
                center.getX() + radius, center.getZ() + radius, topY, strict);
    }

    private static int startClearBox(CommandSourceStack src, int x1, int z1, int x2, int z2, int topY, boolean strict) {
        NfcState st = NfcServer.STATE;
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("请在游戏内执行"));
            return 0;
        }
        ServerLevel level = (ServerLevel) player.level();
        if (st.seed == null) {
            st.seed = level.getSeed();
        }
        BlockPos min = new BlockPos(x1, level.getMinY(), z1);
        BlockPos max = new BlockPos(x2, topY, z2);

        st.resetForNewClear(level, min, max);

        int cx = (x1 + x2) / 2;
        int cz = (z1 + z2) / 2;
        int rad = Math.max((x2 - x1) / 2, (z2 - z1) / 2) + 16;
        for (Crossing c : NfcFortress.collect(level, st.seed, cx, cz, rad, st.fortressChunks)) {
            if (c.center().getX() < x1 || c.center().getX() > x2 || c.center().getZ() < z1 || c.center().getZ() > z2) {
                continue;
            }
            st.crossings.add(c);
        }

        NfcJob job = new NfcJob(level, player, "清空进度", "等待加载清理", 1);
        for (int chx = min.getX() >> 4; chx <= max.getX() >> 4; chx++) {
            for (int chz = min.getZ() >> 4; chz <= max.getZ() >> 4; chz++) {
                final ChunkPos cp = new ChunkPos(chx, chz);
                job.add(new NfcJob.Unit(cp, () -> clearChunk(level, min, max, cp, strict)));
            }
        }
        job.setMessage("清空完毕，共 " + st.crossings.size() + " 个十字路口");
        NfcServer.startJob(job);
        src.sendSuccess(() -> Component.literal("正在清空"), false);
        return 1;
    }

    private static void clearChunk(ServerLevel level, BlockPos min, BlockPos max, ChunkPos cp, boolean strict) {
        int x0 = Math.max(min.getX(), cp.getMinBlockX());
        int x1 = Math.min(max.getX(), cp.getMaxBlockX());
        int z0 = Math.max(min.getZ(), cp.getMinBlockZ());
        int z1 = Math.min(max.getZ(), cp.getMaxBlockZ());
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = max.getY(); y >= min.getY(); y--) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (level.getBlockState(p).isAir()) {
                        continue;
                    }
                    level.setBlock(p, AIR, strict ? (2 | 16) : 2);
                }
            }
        }
    }

    // ---------------------------------------------------------------- place

    private static int startPlace(CommandSourceStack src, NfcState st) {
        ServerPlayer player = src.getPlayer();
        if (player == null || st.level == null) {
            src.sendFailure(Component.literal("请在游戏内执行"));
            return 0;
        }
        List<Crossing> order = new ArrayList<>(st.crossings);
        // 先贴不在峡谷的（红色版），再贴峡谷内的，避免被抢先占位
        order.sort(Comparator.comparingInt((Crossing c) -> c.soulSandValley ? 1 : 0));

        NfcJob job = new NfcJob(st.level, player, "放置进度", "等待加载放置", 1);
        for (Crossing c : order) {
            int[] off = firstOffset(c);
            final ChunkPos cp = new ChunkPos(c.min.getX() >> 4, c.min.getZ() >> 4);
            job.add(new NfcJob.Unit(cp, () -> placeCrossing(st.level, c, c.soulSandValley ? BRICK : BRICK_RED)));
            if (off == null) {
                continue;
            }
        }
        job.setMessage("补齐完毕");
        NfcServer.startJob(job);
        src.sendSuccess(() -> Component.literal("正在补齐十字路口"), false);
        return 1;
    }

    private static int[] firstOffset(Crossing c) {
        return NfcTemplate.OFFSETS.length == 0 ? null : new int[]{c.min.getX(), c.min.getY(), c.min.getZ()};
    }

    private static void placeCrossing(ServerLevel level, Crossing c, BlockState state) {
        for (int v : NfcTemplate.OFFSETS) {
            BlockPos p = new BlockPos(c.min.getX() + NfcTemplate.x(v),
                    c.min.getY() + NfcTemplate.y(v),
                    c.min.getZ() + NfcTemplate.z(v));
            if (level.getBlockState(p).isAir()) {
                level.setBlock(p, state, 2);
            }
        }
    }

    // ---------------------------------------------------------------- calc

    private static int runCalc(CommandSourceStack src, boolean soulOnly) {
            NfcState st = NfcServer.STATE;
            if (!st.cleared()) {
                src.sendFailure(Component.literal("请先 /nfc clear"));
                return 0;
            }
            ServerPlayer player = src.getPlayer();
            if (player == null) {
                src.sendFailure(Component.literal("请在游戏内执行"));
                return 0;
            }
            if (st.crossings.isEmpty()) {
                src.sendFailure(Component.literal("这片区域里没有十字路口"));
                return 0;
            }

            List<NfcCenter.Box> all = new ArrayList<>();
            List<NfcCenter.Box> use = new ArrayList<>();
            List<Integer> useGroups = new ArrayList<>();
            for (Crossing c : st.crossings) {
                NfcCenter.Box b = new NfcCenter.Box(c.min.getX(), c.min.getY(), c.min.getZ(),
                        c.max.getX(), c.max.getY(), c.max.getZ());
                all.add(b);
                if (!soulOnly || c.soulSandValley) {
                    use.add(b);
                    useGroups.add(c.group);
                }
            }
            if (use.isEmpty()) {
                src.sendFailure(Component.literal("没有在灵魂沙峡谷里的十字路口"));
                return 0;
            }

            int[] groups = new int[useGroups.size()];
            for (int k = 0; k < groups.length; k++) {
                groups[k] = useGroups.get(k);
            }
            int[] center = NfcCenter.bestCenter(use, all, groups);
            if (center == null) {
                src.sendFailure(Component.literal("算不出球心"));
                return 0;
            }
            st.center = new BlockPos(center[0], center[1], center[2]);
            NfcStats.compute(st);
            st.stage = NfcState.STAGE_CALCED;

            NfcStats.report(player, st);

            // 3x3 平台：正中红色染色玻璃，四周普通玻璃（只往空气里放）
            if (st.level != null) {
                BlockPos c = st.center;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos p = new BlockPos(c.getX() + dx, c.getY() - 1, c.getZ() + dz);
                        if (!st.level.getBlockState(p).isAir()) {
                            continue;
                        }
                        st.level.setBlock(p, dx == 0 && dz == 0
                                ? Blocks.STAINED_GLASS.red().defaultBlockState()
                                : Blocks.GLASS.defaultBlockState(), 2);
                    }
                }
            }

            return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> calcNode(String name, boolean soulOnly) {
        return Commands.literal(name).executes(ctx -> runCalc(ctx.getSource(), soulOnly));
    }

    // ---------------------------------------------------------------- calc cross（soul / normal）

    private static LiteralArgumentBuilder<CommandSourceStack> crossNode(String name, boolean soulOnly) {
        return Commands.literal(name).executes(ctx -> {
            NfcState st = NfcServer.STATE;
            CommandSourceStack src = ctx.getSource();
            if (!st.cleared()) {
                src.sendFailure(Component.literal("请先 /nfcc clear"));
                return 0;
            }
            runCalc(src, soulOnly);
            int r = startPlace(src, st);
            if (r == 0) {
                return 0;
            }
            if (NfcServer.running != null) {
                NfcServer.running.after = () -> startCheck(src, st);
            }
            return 1;
        });
    }

    // ---------------------------------------------------------------- calc normal（整座堡垒体积）

    private static LiteralArgumentBuilder<CommandSourceStack> calcNormalNode(String name, boolean soulOnly) {
        return Commands.literal(name).executes(ctx -> {
            NfcState st = NfcServer.STATE;
            CommandSourceStack src = ctx.getSource();
            if (!st.cleared()) {
                src.sendFailure(Component.literal("请先 /nfcc clear"));
                return 0;
            }
            ServerPlayer player = src.getPlayer();
            if (player == null) {
                src.sendFailure(Component.literal("请在游戏内执行"));
                return 0;
            }
            if (st.fortressChunks.isEmpty()) {
                src.sendFailure(Component.literal("这片区域里没有堡垒"));
                return 0;
            }

            List<NfcCenter.Box> all = new ArrayList<>();
            List<NfcCenter.Box> use = new ArrayList<>();
            List<Integer> useGroups = new ArrayList<>();
            for (Crossing c : st.crossings) {
                NfcCenter.Box b = new NfcCenter.Box(c.min.getX(), c.min.getY(), c.min.getZ(),
                        c.max.getX(), c.max.getY(), c.max.getZ());
                all.add(b);
                if (!soulOnly || c.soulSandValley) {
                    use.add(b);
                    useGroups.add(c.group);
                }
            }
            if (use.isEmpty()) {
                src.sendFailure(Component.literal("没有可用的十字路口来定位球心"));
                return 0;
            }
            int[] groups = new int[useGroups.size()];
            for (int k = 0; k < groups.length; k++) {
                groups[k] = useGroups.get(k);
            }
            int[] center = NfcCenter.bestCenter(use, all, groups);
            if (center == null) {
                src.sendFailure(Component.literal("算不出球心"));
                return 0;
            }
            st.center = new BlockPos(center[0], center[1], center[2]);

            long total = 0L;
            for (ChunkPos fc : st.fortressChunks) {
                total += FortressGen.fortressVolume(st.seed, fc.x(), fc.z());
            }
            long soul = 0L;
            for (Crossing c : st.crossings) {
                if (c.soulSandValley) {
                    soul += 19L * 10L * 19L;
                }
            }
            double pct = total > 0 ? soul * 100.0 / total : 0.0;


            player.sendSystemMessage(Component.literal("计算出来的中心挂机点：").withStyle(net.minecraft.ChatFormatting.GOLD)
                    .append(Component.literal("( " + st.center.getX() + " " + st.center.getY() + " " + st.center.getZ() + " )")
                            .withStyle(net.minecraft.ChatFormatting.AQUA)));
            player.sendSystemMessage(Component.literal("其128格半径球体刷怪范围内堡垒的情况").withStyle(net.minecraft.ChatFormatting.GOLD));
            player.sendSystemMessage(Component.literal("总体积：").withStyle(net.minecraft.ChatFormatting.GOLD)
                    .append(Component.literal(String.valueOf(total)).withStyle(net.minecraft.ChatFormatting.GREEN))
                    .append(Component.literal(" 块").withStyle(net.minecraft.ChatFormatting.WHITE)));
            player.sendSystemMessage(Component.literal("灵魂沙体积：").withStyle(net.minecraft.ChatFormatting.GOLD)
                    .append(Component.literal(String.valueOf(soul)).withStyle(net.minecraft.ChatFormatting.YELLOW))
                    .append(Component.literal(" 块").withStyle(net.minecraft.ChatFormatting.WHITE)));
            player.sendSystemMessage(Component.literal("灵魂沙占总体积：").withStyle(net.minecraft.ChatFormatting.GOLD)
                    .append(Component.literal(String.format("%.1f", pct)).withStyle(net.minecraft.ChatFormatting.AQUA))
                    .append(Component.literal(" %").withStyle(net.minecraft.ChatFormatting.WHITE)));

            st.stage = NfcState.STAGE_CALCED;
            return 1;
        });
    }
    // ---------------------------------------------------------------- check

    private static int startCheck(CommandSourceStack src, NfcState st) {
        ServerPlayer player = src.getPlayer();
        if (player == null || st.level == null || st.center == null) {
            src.sendFailure(Component.literal("请在游戏内执行"));
            return 0;
        }
        List<Crossing> order = new ArrayList<>(st.crossings);
        order.sort(Comparator.comparingInt(c -> c.cat)); // 绿黄红→倒着贴：先橙后红后黄后绿

        NfcJob job = new NfcJob(st.level, player, "检查进度", "等待加载检查", 1);

        // 橙色：重叠交集（最先画，重叠处留优先级高的颜色）
        for (BlockPos[] b : st.overlapBoxes) {
            final ChunkPos cp = new ChunkPos(b[0].getX() >> 4, b[0].getZ() >> 4);
            job.add(new NfcJob.Unit(cp, () -> frame(st.level, b[0], b[1], C_ORANGE)));
        }

        for (int cat = Crossing.CAT_RED; cat >= Crossing.CAT_GREEN; cat--) {
            for (Crossing c : order) {
                if (c.cat != cat) {
                    continue;
                }
                BlockState state = switch (cat) {
                    case Crossing.CAT_RED -> C_RED;
                    case Crossing.CAT_YELLOW -> C_YELLOW;
                    default -> C_GREEN;
                };
                final BlockPos lo = c.min.offset(-1, -1, -1);
                final BlockPos hi = c.max.offset(1, 1, 1);
                final ChunkPos cp = new ChunkPos(lo.getX() >> 4, lo.getZ() >> 4);
                job.add(new NfcJob.Unit(cp, () -> frame(st.level, lo, hi, state)));
            }
        }

        job.setMessage("画框完毕");
        NfcServer.startJob(job);
        src.sendSuccess(() -> Component.literal("正在画框"), false);
        return 1;
    }

    /** 画一个盒子的 12 条棱，只往空气里画。 */
    private static void frame(ServerLevel level, BlockPos a, BlockPos b, BlockState state) {
        int x1 = Math.min(a.getX(), b.getX()), x2 = Math.max(a.getX(), b.getX());
        int y1 = Math.min(a.getY(), b.getY()), y2 = Math.max(a.getY(), b.getY());
        int z1 = Math.min(a.getZ(), b.getZ()), z2 = Math.max(a.getZ(), b.getZ());
        for (int x = x1; x <= x2; x++) {
            put(level, x, y1, z1, state);
            put(level, x, y1, z2, state);
            put(level, x, y2, z1, state);
            put(level, x, y2, z2, state);
        }
        for (int y = y1; y <= y2; y++) {
            put(level, x1, y, z1, state);
            put(level, x1, y, z2, state);
            put(level, x2, y, z1, state);
            put(level, x2, y, z2, state);
        }
        for (int z = z1; z <= z2; z++) {
            put(level, x1, y1, z, state);
            put(level, x2, y1, z, state);
            put(level, x1, y2, z, state);
            put(level, x2, y2, z, state);
        }
    }

    private static void put(ServerLevel level, int x, int y, int z, BlockState state) {
        BlockPos p = new BlockPos(x, y, z);
        if (level.getBlockState(p).isAir()) {
            level.setBlock(p, state, 2);
        }
    }
}
