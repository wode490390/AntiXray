package cn.wode490390.nukkit.antixray;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockUpdateEvent;
import cn.nukkit.event.player.PlayerChunkRequestEvent;
import cn.nukkit.level.GlobalBlockPalette;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.math.Vector3;
import cn.nukkit.network.protocol.UpdateBlockPacket;
import cn.nukkit.plugin.PluginBase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class AntiXray extends PluginBase implements Listener {

    private final static String PERMISSION_WHITELIST = "antixray.whitelist";

    protected int height = 255;
    protected boolean mode = false;
    protected boolean cache = false;
    protected int fake_o = 1;
    protected int fake_n = 87;
    protected List<Integer> ores = new ArrayList<>();
    protected List<Integer> filters = new ArrayList<>();
    protected List<String> worlds = new ArrayList<>();

    private final ConcurrentMap<Level, WorldHandler> handlers = new ConcurrentHashMap<>();

    private static AntiXray instance;

    public static AntiXray getInstance() {
        return instance;
    }

    @Override
    public void onLoad() {
        instance = this;
    }

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        String t = "scan-height-limit";
        try {
            this.height = this.getConfig().getInt(t);
        } catch (Exception e) {
            this.logLoadException(t);
        }
        if (this.height < 1) {
            this.height = 1;
        } else if (this.height > 255) {
            this.height = 255;
        }
        t = "cache-chunks";
        try {
            this.cache = this.getConfig().getBoolean(t);
        } catch (Exception e) {
            this.logLoadException(t);
        }
        t = "obfuscator-mode";
        try {
            this.mode = this.getConfig().getBoolean(t);
        } catch (Exception e) {
            this.logLoadException(t);
        }
        t = "overworld-fake-block";
        try {
            this.fake_o = this.getConfig().getInt(t);
        } catch (Exception e) {
            this.logLoadException(t);
        }
        t = "nether-fake-block";
        try {
            this.fake_n = this.getConfig().getInt(t);
        } catch (Exception e) {
            this.logLoadException(t);
        }
        t = "protect-worlds";
        try {
            this.worlds = this.getConfig().getStringList(t);
        } catch (Exception e) {
            this.logLoadException(t);
        }
        t = "ores";
        try {
            this.ores = this.getConfig().getIntegerList(t);
        } catch (Exception e) {
            this.logLoadException(t);
        }
        t = "filters";
        try {
            this.filters = this.getConfig().getIntegerList(t);
        } catch (Exception e) {
            this.logLoadException(t);
        }
        if (!this.worlds.isEmpty() && !this.ores.isEmpty()) {
            this.getServer().getPluginManager().registerEvents(this, this);
            for (String w : this.worlds) {
                Level l = this.getServer().getLevelByName(w);
                if (l != null) {
                    this.handlers.putIfAbsent(l, new WorldHandler(l));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChunkRequest(PlayerChunkRequestEvent e) {
        Player p = e.getPlayer();
        Level l = p.getLevel();
        if (!p.hasPermission(PERMISSION_WHITELIST) && this.worlds.contains(l.getName()) && p.getLoaderId() > 0) {
            e.setCancelled();
            this.handlers.get(l).requestChunk(e.getChunkX(), e.getChunkZ(), p);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockUpdate(BlockUpdateEvent e) {
        Position v = e.getBlock();
        Level l = v.getLevel();
        if (this.worlds.contains(l.getName())) {
            List<UpdateBlockPacket> a = new ArrayList<>();
            for (Vector3 b : new Vector3[]{
                    v.add(1),
                    v.add(-1),
                    v.add(0, 1),
                    v.add(0, -1),
                    v.add(0, 0, 1),
                    v.add(0, 0, -1)
            }) {
                int y = b.getFloorY();
                if (y > 255 || y < 0) {
                    continue;
                }
                int x = b.getFloorX();
                int z = b.getFloorZ();
                UpdateBlockPacket d = new UpdateBlockPacket();
                try {
                    d.blockRuntimeId = GlobalBlockPalette.getOrCreateRuntimeId(l.getFullBlock(x, y, z));
                } catch (Exception t) {
                    continue;
                }
                d.x = x;
                d.y = y;
                d.z = z;
                d.flags = UpdateBlockPacket.FLAG_ALL_PRIORITY;
                a.add(d);
            }
            int n = a.size();
            if (n > 0) {
                Set<Player> s = new HashSet<>(l.getChunkPlayers(v.getFloorX() >> 4, v.getFloorZ() >> 4).values());
                for (Player p : new HashSet<>(s)) {
                    if (p.hasPermission(PERMISSION_WHITELIST)) {
                        s.remove(p);
                    }
                }
                this.getServer().batchPackets(s.toArray(new Player[s.size()]), a.toArray(new UpdateBlockPacket[n]));
            }
        }
    }

    private void logLoadException(String t) {
        this.getLogger().alert("An error occurred while reading the configuration '" + t + "'. Use the default value.");
    }
}
