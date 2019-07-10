package cn.wode490390.nukkit.antixray;

import cn.nukkit.level.Level;
import co.aikar.timings.Timing;
import co.aikar.timings.TimingsManager;

public class AntiXrayTimings {

    public static final Timing PlayerChunkRequestEventTimer;
    public static final Timing ChunkUnloadEventTimer;
    public static final Timing BlockUpdateEventTimer;

    static {
        PlayerChunkRequestEventTimer = TimingsManager.getTiming("AntiXray PlayerChunkRequest");
        ChunkUnloadEventTimer = TimingsManager.getTiming("AntiXray ChunkUnload");
        BlockUpdateEventTimer = TimingsManager.getTiming("AntiXray BlockUpdate");
    }

    public final Timing ChunkSendTimer;
    public final Timing ChunkSendPrepareTimer;

    public AntiXrayTimings(Level level) {
        String name = level.getFolderName() + " - ";

        this.ChunkSendTimer = TimingsManager.getTiming(name + "AntiXray ChunkSend");
        this.ChunkSendPrepareTimer = TimingsManager.getTiming(name + "AntiXray ChunkSendPrepare");
    }
}
