/**
 * wodeTeam is pleased to support the open source community by making AntiXray available.
 * 
 * Copyright (C) 2019  Woder
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0>.
 */

package cn.wode490390.nukkit.antixray;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockUpdateEvent;
import cn.nukkit.event.level.LevelUnloadEvent;
import cn.nukkit.event.player.PlayerChunkRequestEvent;
import cn.nukkit.level.GlobalBlockPalette;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.math.Vector3;
import cn.nukkit.network.protocol.UpdateBlockPacket;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class AntiXray extends PluginBase implements Listener {

    int height = 4;
    int maxY;
    boolean obfuscatorMode = true;
    boolean memoryCache = false;
    private List<String> worlds;

    final boolean[] filter = new boolean[256];
    final boolean[] ore = new boolean[256];
    final int[] dimension = new int[]{Block.STONE, Block.NETHERRACK, Block.AIR, Block.AIR};

    private final Map<Level, WorldHandler> handlers = Maps.newHashMap();

    @Override
    public void onEnable() {
        try {
            new MetricsLite(this, 5123);
        } catch (Throwable ignore) {

        }

        this.saveDefaultConfig();
        Config config = this.getConfig();

        String node = "scan-chunk-height-limit";
        if (config.exists(node)) {
            try {
                this.height = config.getInt(node, this.height);
            } catch (Exception e) {
                this.logLoadException(node, e);
            }
        } else { //compatible
            node = "scan-height-limit";
            try {
                this.height = config.getInt(node, 64) >> 4;
            } catch (Exception e) {
                this.logLoadException("scan-chunk-height-limit", e);
            }
        }
        this.height = Math.max(Math.min(this.height, 15), 1);
        this.maxY = this.height << 4;

        node = "memory-cache";
        if (config.exists(node)) {
            try {
                this.memoryCache = config.getBoolean(node, this.memoryCache);
            } catch (Exception e) {
                this.logLoadException(node, e);
            }
        } else { //compatible
            node = "cache-chunks";
            try {
                this.memoryCache = config.getBoolean(node, this.memoryCache);
            } catch (Exception e) {
                this.logLoadException("memory-cache", e);
            }
        }

        node = "obfuscator-mode";
        try {
            this.obfuscatorMode = config.getBoolean(node, this.obfuscatorMode);
        } catch (Exception e) {
            this.logLoadException(node, e);
        }
        node = "overworld-fake-block";
        try {
            this.dimension[0] = config.getInt(node, this.dimension[0]) & 0xff;
            GlobalBlockPalette.getOrCreateRuntimeId(this.dimension[0], 0);
        } catch (Exception e) {
            this.dimension[0] = Block.STONE;
            this.logLoadException(node, e);
        }
        node = "nether-fake-block";
        try {
            this.dimension[1] = config.getInt(node, this.dimension[1]) & 0xff;
            GlobalBlockPalette.getOrCreateRuntimeId(this.dimension[1], 0);
        } catch (Exception e) {
            this.dimension[1] = Block.NETHERRACK;
            this.logLoadException(node, e);
        }
        node = "protect-worlds";
        try {
            this.worlds = config.getStringList(node);
        } catch (Exception e) {
            this.logLoadException(node, e);
        }
        node = "ores";
        List<Integer> ores = null;
        try {
            ores = config.getIntegerList(node);
        } catch (Exception e) {
            this.logLoadException(node, e);
        }

        if (this.worlds != null && !this.worlds.isEmpty() && (this.obfuscatorMode || ores != null && !ores.isEmpty())) {
            node = "filters";
            List<Integer> filters;
            try {
                filters = config.getIntegerList(node);
            } catch (Exception e) {
                filters = Collections.emptyList();
                this.logLoadException(node, e);
            }

            for (int id : filters) {
                if (id > -1 && id < 256) {
                    this.filter[id] = true;
                }
            }
            if (!this.obfuscatorMode) {
                for (int id : ores) {
                    if (id > -1 && id < 256) {
                        this.ore[id] = true;
                    }
                }
            }

            WorldHandler.init();

            this.getServer().getPluginManager().registerEvents(this, this);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChunkRequest(PlayerChunkRequestEvent event) {
        Player player = event.getPlayer();
        Level level = player.getLevel();
        if (this.worlds.contains(level.getName()) && player.getLoaderId() > 0) {
            event.setCancelled();
            WorldHandler handler = this.handlers.get(level);
            if (handler == null) {
                handler = new WorldHandler(this, level);
                this.handlers.put(level, handler);
            }
            handler.requestChunk(event.getChunkX(), event.getChunkZ(), player);
        }
    }

    @EventHandler
    //TODO: Use BlockBreakEvent instead of BlockUpdateEvent
    public void onBlockUpdate(BlockUpdateEvent event) {
        Position position = event.getBlock();
        Level level = position.getLevel();
        if (this.worlds.contains(level.getName())) {
            List<UpdateBlockPacket> packets = Lists.newArrayList();
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
                } catch (Exception tryAgain) {
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
                this.getServer().batchPackets(level.getChunkPlayers(position.getChunkX(), position.getChunkZ()).values().toArray(new Player[0]), packets.toArray(new UpdateBlockPacket[0]));
            }
        }
    }

    @EventHandler
    public void onLevelUnload(LevelUnloadEvent event) {
        this.handlers.remove(event.getLevel());
    }

    private void logLoadException(String node, Exception ex) {
        this.getLogger().alert("Failed to get the configuration '" + node + "'. Use the default value.", ex);
    }
}
