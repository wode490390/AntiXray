package cn.wode490390.nukkit.antixray;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockUpdateEvent;
import cn.nukkit.event.level.ChunkUnloadEvent;
import cn.nukkit.event.player.PlayerChunkRequestEvent;
import cn.nukkit.level.GlobalBlockPalette;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.level.format.FullChunk;
import cn.nukkit.math.Vector3;
import cn.nukkit.network.protocol.UpdateBlockPacket;
import cn.nukkit.plugin.PluginBase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AntiXray extends PluginBase implements Listener {

    public final static String PERMISSION_WHITELIST = "antixray.whitelist";

    int height;
    boolean mode;
    boolean cache;
    int fake_o;
    int fake_n;
    List<Integer> ores;
    List<Integer> filters;
    private List<String> worlds;

    private final Map<Level, WorldHandler> handlers = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        try {
            new MetricsLite(this);
        } catch (Exception ignore) {

        }
        this.saveDefaultConfig();
        String t = "scan-height-limit";
        try {
            this.height = this.getConfig().getInt(t, 64);
        } catch (Exception e) {
            this.height = 64;
            this.logLoadException(t);
        }
        if (this.height < 1) {
            this.height = 1;
        } else if (this.height > 255) {
            this.height = 255;
        }
        t = "cache-chunks";
        try {
            this.cache = this.getConfig().getBoolean(t, true);
        } catch (Exception e) {
            this.cache = true;
            this.logLoadException(t);
        }
        t = "obfuscator-mode";
        try {
            this.mode = this.getConfig().getBoolean(t, true);
        } catch (Exception e) {
            this.mode = true;
            this.logLoadException(t);
        }
        t = "overworld-fake-block";
        try {
            this.fake_o = this.getConfig().getInt(t, 1);
        } catch (Exception e) {
            this.fake_o = 1;
            this.logLoadException(t);
        }
        t = "nether-fake-block";
        try {
            this.fake_n = this.getConfig().getInt(t, 87);
        } catch (Exception e) {
            this.fake_n = 87;
            this.logLoadException(t);
        }
        t = "protect-worlds";
        try {
            this.worlds = this.getConfig().getStringList(t);
        } catch (Exception e) {
            this.worlds = new ArrayList<>();
            this.logLoadException(t);
        }
        t = "ores";
        try {
            this.ores = this.getConfig().getIntegerList(t);
        } catch (Exception e) {
            this.ores = new ArrayList<>();
            this.logLoadException(t);
        }
        t = "filters";
        try {
            this.filters = this.getConfig().getIntegerList(t);
        } catch (Exception e) {
            this.filters = new ArrayList<>();
            this.logLoadException(t);
        }
        if (!this.worlds.isEmpty() && !this.ores.isEmpty()) {
            this.getServer().getPluginManager().registerEvents(this, this);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChunkRequest(PlayerChunkRequestEvent event) {
        AntiXrayTimings.BlockUpdateEventTimer.startTiming();
        Player player = event.getPlayer();
        Level level = player.getLevel();
        if (!player.hasPermission(PERMISSION_WHITELIST) && this.worlds.contains(level.getName()) && player.getLoaderId() > 0) {
            event.setCancelled();
            this.handlers.putIfAbsent(level, new WorldHandler(this, level));
            this.handlers.get(level).requestChunk(event.getChunkX(), event.getChunkZ(), player);
        }
        AntiXrayTimings.BlockUpdateEventTimer.stopTiming();
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        Level level = event.getLevel();
        if (this.worlds.contains(level.getName())) {
            AntiXrayTimings.ChunkUnloadEventTimer.startTiming();
            FullChunk chunk = event.getChunk();
            this.handlers.putIfAbsent(level, new WorldHandler(this, level));
            this.handlers.get(level).clearCache(chunk.getX(), chunk.getZ());
            AntiXrayTimings.ChunkUnloadEventTimer.stopTiming();
        }
    }

    @EventHandler
    public void onBlockUpdate(BlockUpdateEvent event) {
        Position position = event.getBlock();
        Level level = position.getLevel();
        if (this.worlds.contains(level.getName())) {
            AntiXrayTimings.BlockUpdateEventTimer.startTiming();
            List<UpdateBlockPacket> packets = new ArrayList<>();
            for (Vector3 vector : new Vector3[]{
                    position.add(1),
                    position.add(-1),
                    position.add(0, 1),
                    position.add(0, -1),
                    position.add(0, 0, 1),
                    position.add(0, 0, -1)
            }) {
                int y = vector.getFloorY();
                if (y > 255 || y < 0) {
                    continue;
                }
                int x = vector.getFloorX();
                int z = vector.getFloorZ();
                UpdateBlockPacket packet = new UpdateBlockPacket();
                try {
                    packet.blockRuntimeId = GlobalBlockPalette.getOrCreateRuntimeId(level.getFullBlock(x, y, z));
                } catch (NoSuchElementException tryAgain) {
                    try {
                        packet.blockRuntimeId = GlobalBlockPalette.getOrCreateRuntimeId(level.getBlockIdAt(x, y, z), 0);
                    } catch (Exception ex) {
                        continue;
                    }
                }
                packet.x = x;
                packet.y = y;
                packet.z = z;
                packet.flags = UpdateBlockPacket.FLAG_ALL_PRIORITY;
                packets.add(packet);
            }
            if (packets.size() > 0) {
                Set<Player> players = Collections.synchronizedSet(new HashSet<>());
                level.getChunkPlayers(position.getChunkX(), position.getChunkZ()).values().parallelStream()
                        .filter(player -> !player.hasPermission(PERMISSION_WHITELIST))
                        .forEach(player -> players.add(player));
                this.getServer().batchPackets(players.toArray(new Player[0]), packets.toArray(new UpdateBlockPacket[0]));
            }
            AntiXrayTimings.BlockUpdateEventTimer.stopTiming();
        }
    }

    private void logLoadException(String node) {
        this.getLogger().alert("An error occurred while reading the configuration '" + node + "'. Use the default value.");
    }
}
