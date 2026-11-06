package nfc;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * 按区块/按件推进的长任务：clear / place / check 共用。
 *
 * 规矩：每 tick 只吃有限的活；区块没加载就挂起等玩家走近，绝不 forceload。
 * 进度条是紫色 bossbar（++++---- 加百分比），上面那行 actionbar 在有挂起区块时写
 * 「等待加载清理：x y z」。
 */
public class NfcJob {
    /** 一件活：锚在一个区块上（用来判断有没有加载）+ 真正要做的事。 */
    public static final class Unit {
        private final ChunkPos chunk;
        private final Runnable action;

        public Unit(ChunkPos chunk, Runnable action) {
            this.chunk = chunk;
            this.action = action;
        }

        public ChunkPos chunk() {
            return chunk;
        }

        public void run() {
            action.run();
        }
    }

    private final ServerLevel level;
    private final ServerPlayer player;
    private final ServerBossEvent bar;
    private final String title;
    private final String waitVerb;
    private final int budgetPerTick;

    private final Deque<Unit> queue = new ArrayDeque<>();
    private final List<Unit> waiting = new ArrayList<>();
    private int total;
    private int done;
    private boolean finished;
    /** 这个任务结束后接着跑的东西（cross 模式串 place→calc→check） */
    public Runnable after = null;
    private String message;
    private ChunkPos blocked;

    public NfcJob(ServerLevel level, ServerPlayer player, String title, String waitVerb, int budgetPerTick) {
        this.level = level;
        this.player = player;
        this.title = title;
        this.waitVerb = waitVerb;
        this.budgetPerTick = budgetPerTick;
        this.bar = new ServerBossEvent(UUID.randomUUID(), Component.literal(title),
                BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
        this.bar.setVisible(false);
    }

    public ServerPlayer player() {
        return player;
    }

    public void add(Unit u) {
        queue.add(u);
        total++;
    }

    public int total() {
        return total;
    }

    public int done() {
        return done;
    }

    public boolean finished() {
        return finished;
    }

    public void start() {
        bar.setVisible(true);
        bar.addPlayer(player);
        updateBar();
    }

    /** 结束时调一次，收掉 bossbar 并返回聊天栏那句话。 */
    public Component stop() {
        bar.setVisible(false);
        bar.removePlayer(player);
        player.sendOverlayMessage(Component.empty());
        finished = true;
        return Component.literal(message == null ? (title + "结束：" + done + " / " + total) : message);
    }

    /** 每 tick 调一次，返回 true 表示已经全部做完。 */
    public boolean tick() {
        if (finished) {
            return true;
        }
        if (queue.isEmpty() && waiting.isEmpty()) {
            return true;
        }

        int worked = 0;
        for (Iterator<Unit> it = queue.iterator(); it.hasNext() && worked < budgetPerTick; ) {
            Unit u = it.next();
            ChunkPos cp = u.chunk();
            if (!isLoaded(cp)) {
                it.remove();
                waiting.add(u);
                blocked = cp;
                continue;
            }
            it.remove();
            u.run();
            done++;
            worked++;
        }

        if (queue.isEmpty() && !waiting.isEmpty()) {
            // 这一轮剩的全是没加载的：下一轮再试
            queue.addAll(waiting);
            waiting.clear();
        }

        updateBar();
        return queue.isEmpty() && waiting.isEmpty();
    }

    private boolean isLoaded(ChunkPos cp) {
        return level.isLoaded(new BlockPos(cp.getMinBlockX(), 0, cp.getMinBlockZ()));
    }

    private void updateBar() {
        float p = total <= 0 ? 1f : Math.min(1f, done / (float) total);
        bar.setProgress(p);
        int filled = Math.round(p * 8);
        StringBuilder sb = new StringBuilder(title).append(' ');
        for (int i = 0; i < 8; i++) {
            sb.append(i < filled ? '+' : '-');
        }
        sb.append(' ').append(Math.round(p * 100)).append('%');
        bar.setName(Component.literal(sb.toString()));

        ChunkPos cp = !waiting.isEmpty() ? waiting.get(0).chunk() : blocked;
        if (cp != null && player != null && !(queue.isEmpty() && waiting.isEmpty())) {
            player.sendOverlayMessage(Component.literal(waitVerb + "：" + cp.getMinBlockX() + " "
                    + level.getMinY() + " " + cp.getMinBlockZ()));
        }
    }

    /** /nfcc stop：收掉进度条和常驻提示，任务作废。 */
    public void cancel() {
        bar.setVisible(false);
        bar.removeAllPlayers();
        if (player != null) {
            player.sendOverlayMessage(Component.empty());
        }
        finished = true;
        queue.clear();
        waiting.clear();
        blocked = null;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
