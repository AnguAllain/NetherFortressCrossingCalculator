package nfc;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/** 一次 clear 之后缓存的现场数据，place / calc / check 都读它。 */
public class NfcState {
    public static final int STAGE_NONE = 0;
    public static final int STAGE_CLEARED = 1;
    public static final int STAGE_CALCED = 2;

    public int stage = STAGE_NONE;
    public Long seed = null;
    public ServerLevel level = null;

    // 本轮的清理区域
    public BlockPos areaMin = null;
    public BlockPos areaMax = null;

    // 数据准备
    public final List<Crossing> crossings = new ArrayList<>();
    /** 收集到的堡垒所在区块，下标 = Crossing.group */
    public final List<net.minecraft.world.level.ChunkPos> fortressChunks = new ArrayList<>();
    public final List<BlockPos[]> overlapBoxes = new ArrayList<>(); // 交集盒子，已外扩 2 格

    // calc 的产物
    public BlockPos center = null;
    public final int radius = 128;

    public int fullCnt = 0;
    public int partCnt = 0;
    public int noneCnt = 0;

    public int greenVol = 0;
    public int yellowVol = 0;
    public int orangeVol = 0;
    public int redVol = 0;
    public int yellowOutVol = 0;
    public int orangeOutVol = 0;
    public int dupOver = 0;
    public int volIn = 0;
    public int volOut = 0;
    public int volAll = 0;
    public int ssdVol = 0;

    public void resetForNewClear(ServerLevel level, BlockPos min, BlockPos max) {
        this.stage = STAGE_CLEARED;
        this.level = level;
        this.areaMin = min;
        this.areaMax = max;
        this.crossings.clear();
        this.fortressChunks.clear();
        this.overlapBoxes.clear();
        this.center = null;
        this.fullCnt = 0;
        this.partCnt = 0;
        this.noneCnt = 0;
        this.greenVol = 0;
        this.yellowVol = 0;
        this.orangeVol = 0;
        this.redVol = 0;
        this.yellowOutVol = 0;
        this.orangeOutVol = 0;
        this.dupOver = 0;
        this.volIn = 0;
        this.volOut = 0;
        this.volAll = 0;
        this.ssdVol = 0;
    }

    public boolean cleared() {
        return stage >= STAGE_CLEARED;
    }

    public boolean calced() {
        return stage >= STAGE_CALCED;
    }
}
