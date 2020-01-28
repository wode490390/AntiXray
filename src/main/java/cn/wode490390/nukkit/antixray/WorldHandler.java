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
import cn.nukkit.Server;
import cn.nukkit.blockentity.BlockEntitySpawnable;
import cn.nukkit.level.Level;
import cn.nukkit.level.format.ChunkSection;
import cn.nukkit.level.format.LevelProvider;
import cn.nukkit.level.format.anvil.Anvil;
import cn.nukkit.level.format.anvil.Chunk;
import cn.nukkit.level.format.anvil.util.BlockStorage;
import cn.nukkit.level.format.generic.BaseFullChunk;
import cn.nukkit.level.format.leveldb.LevelDB;
import cn.nukkit.level.format.mcregion.McRegion;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.network.protocol.BatchPacket;
import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.scheduler.AsyncTask;
import cn.nukkit.scheduler.PluginTask;
import cn.nukkit.utils.BinaryStream;
import cn.nukkit.utils.ChunkException;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteOrder;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class WorldHandler extends Thread {

    private static final byte[] EMPTY_SECTION = new byte[6144];

    private final Map<Long, Map<Integer, Player>> chunkSendQueue = Maps.newConcurrentMap();
    private final Set<Long> chunkSendTasks = Sets.newConcurrentHashSet();

    private Map<Long, Entry> caches;
    private Set<Long> cacheTasks;

    private final Level level;

    private final AntiXray antixray;

    private final int maxSectionY;
    private final int maxSize;

    public WorldHandler(AntiXray antixray, Level level) {
        this.level = level;
        this.antixray = antixray;
        if (this.antixray.memoryCache) {
            this.caches = Maps.newConcurrentMap();
            this.cacheTasks = Sets.newConcurrentHashSet();
        }
        this.maxSectionY = this.antixray.height >> 4;
        this.maxSize = this.antixray.ores.size() - 1;
        this.setDaemon(true);
        this.setName("AntiXray");
    }

    @Override
    public void run() {
        while (this.antixray.isEnabled()) {
            this.processChunkRequest();
            try {
                sleep(50);
            } catch (InterruptedException ignore) {

            }
        }
    }

    public void requestChunk(int chunkX, int chunkZ, Player player) {
        long index = Level.chunkHash(chunkX, chunkZ);
        this.chunkSendQueue.putIfAbsent(index, Maps.newConcurrentMap());
        this.chunkSendQueue.get(index).put(player.getLoaderId(), player);
    }

    public void clearCache(int chunkX, int chunkZ) {
        long index = Level.chunkHash(chunkX, chunkZ);
        this.caches.remove(index);
        this.cacheTasks.remove(index);
    }

    private void processChunkRequest() {
        Iterator<Long> iterator = this.chunkSendQueue.keySet().iterator();
        while (iterator.hasNext()) {
            long index = iterator.next();
            if (this.chunkSendTasks.contains(index)) {
                continue;
            }
            int chunkX = Level.getHashX(index);
            int chunkZ = Level.getHashZ(index);
            this.chunkSendTasks.add(index);

            if (this.antixray.memoryCache) {
                BaseFullChunk chunk = this.level.getChunk(chunkX, chunkZ);
                Entry entry;
                if (chunk != null && (entry = this.caches.get(index)) != null && chunk.getChanges() <= entry.timestamp) {
                    this.sendChunk(chunkX, chunkZ, index, entry.cache, false);
                    continue;
                }
                this.cacheTasks.add(index);
            }

            AsyncTask task = null;
            LevelProvider provider = this.level.getProvider();
            if (provider instanceof Anvil) {
                task = new AnvilChunkRequestTask(chunkX, chunkZ);
            } else if (provider instanceof LevelDB) {
                task = new LevelDBChunkRequestTask(chunkX, chunkZ);
            } else if (provider instanceof McRegion) {
                task = new McRegionChunkRequestTask(chunkX, chunkZ);
            } else if (provider != null) {
                task = provider.requestChunkTask(chunkX, chunkZ);
            }
            if (task != null) {
                this.antixray.getServer().getScheduler().scheduleAsyncTask(this.antixray, task);
            }
        }
    }

    private void sendChunk(int chunkX, int chunkZ, long index, DataPacket packet, boolean isPrimaryThread) {
        if (this.chunkSendTasks.contains(index)) {
            Map<Integer, Player> queue = this.chunkSendQueue.get(index);
            if (queue != null) {
                Stream<Player> stream = queue.values().stream();
                if (isPrimaryThread) {
                    stream.filter(player -> player.isConnected() && player.usedChunks.containsKey(index))
                            .forEach(player -> player.sendChunk(chunkX, chunkZ, packet));
                    this.chunkSendTasks.remove(index);
                    this.chunkSendQueue.remove(index);
                } else {
                    this.antixray.getServer().getScheduler().scheduleTask(this.antixray, new PluginTask<AntiXray>(this.antixray) {
                        @Override
                        public void onRun(int currentTick) {
                            stream.filter(player -> player.isConnected() && player.usedChunks.containsKey(index))
                                    .forEach(player -> player.sendChunk(chunkX, chunkZ, packet));
                            chunkSendTasks.remove(index);
                            chunkSendQueue.remove(index);
                        }
                    });
                }
            }
        }
    }

    private void chunkRequestCallback(long timestamp, int chunkX, int chunkZ, int subChunkCount, byte[] payload, BatchPacket cachePacket) {
        long index = Level.chunkHash(chunkX, chunkZ);

        if (cachePacket != null) {
            BaseFullChunk chunk = this.level.getChunk(chunkX, chunkZ, false);
            if (chunk != null && chunk.getChanges() <= timestamp && this.cacheTasks.contains(index)) {
                this.caches.put(index, new Entry(timestamp, cachePacket));
                this.cacheTasks.remove(index);
            }
            this.sendChunk(chunkX, chunkZ, index, cachePacket, true);
            return;
        }

        if (this.chunkSendTasks.contains(index)) {
            Map<Integer, Player> queue = this.chunkSendQueue.get(index);
            if (queue != null) {
                queue.values().stream()
                        .filter(player -> player.isConnected() && player.usedChunks.containsKey(index))
                        .forEach(player -> player.sendChunk(chunkX, chunkZ, subChunkCount, payload));
                this.chunkSendTasks.remove(index);
                this.chunkSendQueue.remove(index);
            }
        }
    }

    private void chunkRequestFailed(int chunkX, int chunkZ, Throwable t) {
        long index = Level.chunkHash(chunkX, chunkZ);
        this.chunkSendTasks.remove(index);
        this.chunkSendQueue.remove(index);
        this.antixray.getLogger().debug("Chunk request failed at: " + chunkX + "," + chunkZ, t);
    }

    private class AnvilChunkRequestTask extends AsyncTask {

        private final int chunkX;
        private final int chunkZ;

        private long timestamp;
        private int count;
        private BatchPacket cachePacket;
        private Throwable t;

        private AnvilChunkRequestTask(int chunkX, int chunkZ) throws ChunkException {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        @Override
        public void onRun() {
            Chunk chunk = (Chunk) level.getProvider().getChunk(this.chunkX, this.chunkZ, false);
            if (chunk == null) {
                this.t = new NullPointerException("Chunk cannot be null");
                return;
            }
            this.timestamp = chunk.getChanges();

            byte[] tiles = new byte[0];
            if (!chunk.getBlockEntities().isEmpty()) {
                List<CompoundTag> tagList = new ObjectArrayList<>();
                chunk.getBlockEntities().values().stream()
                        .filter(blockEntity -> blockEntity instanceof BlockEntitySpawnable)
                        .forEach(blockEntity -> tagList.add(((BlockEntitySpawnable) blockEntity).getSpawnCompound()));
                try {
                    tiles = NBTIO.write(tagList, ByteOrder.LITTLE_ENDIAN, true);
                } catch (IOException e) {
                    this.t = e;
                    return;
                }
            }

            Map<Integer, Integer> extra = chunk.getBlockExtraDataArray();
            BinaryStream extraData = null;
            if (!extra.isEmpty()) {
                extraData = new BinaryStream();
                extraData.putVarInt(extra.size());
                for (Map.Entry<Integer, Integer> entry : extra.entrySet()) {
                    extraData.putVarInt(entry.getKey());
                    extraData.putLShort(entry.getValue());
                }
            }

            this.count = 0;
            ChunkSection[] sections = chunk.getSections();
            for (int i = sections.length - 1; i >= 0; i--) {
                if (!sections[i].isEmpty()) {
                    this.count = i + 1;
                    break;
                }
            }
            BinaryStream stream = new BinaryStream(new byte[257]).reset(); //256 + 1
            for (int i = 0; i < this.count; i++) {
                stream.putByte((byte) 0);
                ChunkSection section = sections[i];
                if (section.isEmpty()) {
                    stream.put(EMPTY_SECTION);
                } else if (section.getY() <= maxSectionY) {
                    try {
                        Field field = Field.class.getDeclaredField("modifiers");
                        field.setAccessible(true);
                        Field f = cn.nukkit.level.format.anvil.ChunkSection.class.getDeclaredField("storage");
                        field.setInt(f, f.getModifiers() & ~Modifier.FINAL);
                        f.setAccessible(true);
                        BlockStorage storage = (BlockStorage) f.get(section);
                        byte[] blocks = storage.getBlockIds();
                        long hash = 0;
                        if (antixray.localCache) {
                            hash = antixray.getCacheHash(blocks);
                            if (antixray.hasCache(hash)) {
                                stream.put(antixray.readCache(hash));
                                continue;
                            }
                        }
                        byte[] ids = new byte[4096];
                        System.arraycopy(blocks, 0, ids, 0, 4096);
                        byte[] data = new byte[2048];
                        System.arraycopy(storage.getBlockData(), 0, data, 0, 2048);
                        int y = section.getY() << 4;
                        for (int cx = 1; cx < 15; cx++) {
                            int tx = cx << 8;
                            for (int cz = 1; cz < 15; cz++) {
                                int tz = cz << 4;
                                int xz = tx + tz;
                                for (int cy = 1; cy < 15; cy++) {
                                    if ((y + cy) > antixray.height) {
                                        continue;
                                    }
                                    int xy = tx + cy;
                                    int zy = tz + cy;
                                    int index = xz + cy;
                                    if (!antixray.filters.contains(blocks[((cx + 1) << 8) + zy] & 0xff)
                                            && !antixray.filters.contains(blocks[((cx - 1) << 8) + zy] & 0xff)
                                            && !antixray.filters.contains(blocks[xy + ((cz + 1) << 4)] & 0xff)
                                            && !antixray.filters.contains(blocks[xy + ((cz - 1) << 4)] & 0xff)
                                            && !antixray.filters.contains(blocks[index + 1] & 0xff)
                                            && !antixray.filters.contains(blocks[index - 1] & 0xff)) {
                                        if (antixray.mode) {
                                            ids[index] = (byte) (antixray.ores.getInt(index % maxSize) & 0xff);
                                        } else if (antixray.ores.contains(blocks[index] & 0xff)) {
                                            switch (level.getDimension()) {
                                                case Level.DIMENSION_OVERWORLD:
                                                    ids[index] = (byte) (antixray.fake_o & 0xff);
                                                    break;
                                                case Level.DIMENSION_NETHER:
                                                    ids[index] = (byte) (antixray.fake_n & 0xff);
                                                    break;
                                                case Level.DIMENSION_THE_END:
                                                default:
                                                    ids[index] = 0;
                                                    break;
                                            }
                                        } else {
                                            continue;
                                        }
                                        int halfIndex = index / 2;
                                        byte nibbleData = data[halfIndex];
                                        boolean flag = (index & 1) == 0;
                                        if ((flag ? nibbleData & 0xf : (nibbleData & 0xf0) >>> 4) != 0) {
                                            data[halfIndex] = flag ? (byte) (nibbleData & 0xf0) : (byte) (nibbleData & 0xf);
                                        }
                                    }
                                }
                            }
                        }
                        byte[] merged = new byte[6144];
                        System.arraycopy(ids, 0, merged, 0, 4096);
                        System.arraycopy(data, 0, merged, 4096, 2048);
                        stream.put(merged);
                        if (antixray.localCache) {
                            antixray.createCache(hash, merged);
                        }
                    } catch (Exception e) {
                        stream.put(section.getBytes());
                    }
                } else {
                    stream.put(section.getBytes());
                }
            }
            byte[] merged = new byte[257]; //256 + 1
            System.arraycopy(chunk.getBiomeIdArray(), 0, merged, 0, 256);
            stream.put(merged);
            if (extraData != null) {
                stream.put(extraData.getBuffer());
            } else {
                stream.putVarInt(0); //1
            }
            if (tiles.length != 0) {
                stream.put(tiles);
            }

            byte[] payload = stream.getBuffer();

            if (antixray.memoryCache) {
                this.cachePacket = Player.getChunkCacheFromData(this.chunkX, this.chunkZ, this.count, payload);
            }

            this.setResult(payload);
        }

        @Override
        public void onCompletion(Server server) {
            if (this.hasResult()) {
                chunkRequestCallback(this.timestamp, this.chunkX, this.chunkZ, this.count, (byte[]) this.getResult(), this.cachePacket);
            } else {
                chunkRequestFailed(this.chunkX, this.chunkZ, this.t != null ? this.t : new NullPointerException("Payload cannot be null"));
            }
        }
    }

    private class LevelDBChunkRequestTask extends AsyncTask {

        private final int chunkX;
        private final int chunkZ;

        private long timestamp;
        private BatchPacket cachePacket;
        private Throwable t;

        private LevelDBChunkRequestTask(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        @Override
        public void onRun() {
            cn.nukkit.level.format.leveldb.Chunk chunk = ((LevelDB) level.getProvider()).getChunk(this.chunkX, this.chunkZ, false);
            if (chunk == null) {
                this.t = new NullPointerException("Chunk cannot be null");
                return;
            }
            this.timestamp = chunk.getChanges();

            byte[] tiles = new byte[0];
            if (!chunk.getBlockEntities().isEmpty()) {
                List<CompoundTag> tagList = new ObjectArrayList<>();
                chunk.getBlockEntities().values().stream()
                        .filter(blockEntity -> blockEntity instanceof BlockEntitySpawnable)
                        .forEach(blockEntity -> tagList.add(((BlockEntitySpawnable) blockEntity).getSpawnCompound()));
                try {
                    tiles = NBTIO.write(tagList, ByteOrder.LITTLE_ENDIAN);
                } catch (IOException e) {
                    this.t = e;
                    return;
                }
            }

            Map<Integer, Integer> extra = chunk.getBlockExtraDataArray();
            BinaryStream extraData = null;
            if (!extra.isEmpty()) {
                extraData = new BinaryStream();
                extraData.putLInt(extra.size());
                for (Integer key : extra.values()) {
                    extraData.putLInt(key);
                    extraData.putLShort(extra.get(key));
                }
            }

            byte[] merged = new byte[82432]; //32768 + 16384 + 16384 + 16384 + 256 + 256
            byte[] blocks = chunk.getBlockIdArray();
            boolean hit = false;
            long hash = 0;
            if (antixray.localCache) {
                hash = antixray.getCacheHash(blocks);
                if (antixray.hasCache(hash)) {
                    System.arraycopy(antixray.readCache(hash), 0, merged, 0, 49152);
                    hit = true;
                }
            }
            if (!hit) {
                byte[] ids = new byte[32768];
                System.arraycopy(blocks, 0, ids, 0, 32768);
                byte[] data = new byte[16384];
                System.arraycopy(chunk.getBlockDataArray(), 0, data, 0, 16384);
                for (int cx = 1; cx < 15; cx++) {
                    int tx = cx << 11;
                    int dtx = cx << 10;
                    for (int cz = 1; cz < 15; cz++) {
                        int tz = cz << 7;
                        int dtz = cz << 6;
                        int xz = tx | tz;
                        int dxz = dtx | dtz;
                        for (int y = 1; y <= antixray.height && y < 255; y++) {
                            int xy = tx | y;
                            int zy = tz | y;
                            if (!antixray.filters.contains(blocks[((cx + 1) << 11) | zy] & 0xff)
                                    && !antixray.filters.contains(blocks[((cx - 1) << 11) | zy] & 0xff)
                                    && !antixray.filters.contains(blocks[xy | ((cz + 1) << 7)] & 0xff)
                                    && !antixray.filters.contains(blocks[xy | ((cz - 1) << 7)] & 0xff)
                                    && !antixray.filters.contains(blocks[xz | (y + 1)] & 0xff)
                                    && !antixray.filters.contains(blocks[xz | (y - 1)] & 0xff)) {
                                int index = xz | y;
                                if (antixray.mode) {
                                    blocks[index] = (byte) (antixray.ores.getInt(index % maxSize) & 0xff);
                                } else if (antixray.ores.contains(blocks[index] & 0xff)) {
                                    switch (level.getDimension()) {
                                        case Level.DIMENSION_OVERWORLD:
                                            blocks[index] = (byte) (antixray.fake_o & 0xff);
                                            break;
                                        case Level.DIMENSION_NETHER:
                                            blocks[index] = (byte) (antixray.fake_n & 0xff);
                                            break;
                                        case Level.DIMENSION_THE_END:
                                        default:
                                            blocks[index] = 0;
                                            break;
                                    }
                                } else {
                                    continue;
                                }
                                int dataIndex = dxz | (y >> 1);
                                int blockData = data[dataIndex] & 0xff;
                                boolean flag = (y & 1) == 0;
                                if ((flag ? data[dataIndex] & 0xf : data[dataIndex] >> 4) != 0) {
                                    data[dataIndex] = flag ? (byte) (blockData & 0xf0) : (byte) (blockData & 0xf);
                                }
                            }
                        }
                    }
                }
                System.arraycopy(blocks, 0, merged, 0, 32768);
                System.arraycopy(data, 0, merged, 32768, 16384);
                if (antixray.localCache) {
                    byte[] cache = new byte[49152];
                    System.arraycopy(merged, 0, cache, 0, 49152);
                    antixray.createCache(hash, cache);
                }
            }
            System.arraycopy(chunk.getBlockSkyLightArray(), 0, merged, 49152, 16384);
            System.arraycopy(chunk.getBlockLightArray(), 0, merged, 65536, 16384);
            System.arraycopy(chunk.getHeightMapArray(), 0, merged, 81920, 256);
            System.arraycopy(chunk.getBiomeIdArray(), 0, merged, 82176, 256);
            BinaryStream stream = new BinaryStream(merged);
            if (extraData != null) {
                stream.put(extraData.getBuffer());
            } else {
                stream.putLInt(0); //4
            }
            if (tiles.length != 0) {
                stream.put(tiles);
            }

            byte[] payload = stream.getBuffer();

            if (antixray.memoryCache) {
                this.cachePacket = Player.getChunkCacheFromData(this.chunkX, this.chunkZ, 16, payload);
            }

            this.setResult(payload);
        }

        @Override
        public void onCompletion(Server server) {
            if (this.hasResult()) {
                chunkRequestCallback(this.timestamp, this.chunkX, this.chunkZ, 16, (byte[]) this.getResult(), this.cachePacket);
            } else {
                chunkRequestFailed(this.chunkX, this.chunkZ, this.t != null ? this.t : new NullPointerException("Payload cannot be null"));
            }
        }
    }

    private class McRegionChunkRequestTask extends AsyncTask {

        private final int chunkX;
        private final int chunkZ;

        private long timestamp;
        private BatchPacket cachePacket;
        private Throwable t;

        private McRegionChunkRequestTask(int chunkX, int chunkZ) throws ChunkException {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        @Override
        public void onRun() {
            BaseFullChunk chunk = level.getProvider().getChunk(this.chunkX, this.chunkZ, false);
            if (chunk == null) {
                this.t = new NullPointerException("Chunk cannot be null");
                return;
            }
            this.timestamp = chunk.getChanges();

            byte[] tiles = new byte[0];
            if (!chunk.getBlockEntities().isEmpty()) {
                List<CompoundTag> tagList = new ObjectArrayList<>();
                chunk.getBlockEntities().values().stream()
                        .filter(blockEntity -> blockEntity instanceof BlockEntitySpawnable)
                        .forEach(blockEntity -> tagList.add(((BlockEntitySpawnable) blockEntity).getSpawnCompound()));
                try {
                    tiles = NBTIO.write(tagList, ByteOrder.LITTLE_ENDIAN, true);
                } catch (IOException e) {
                    this.t = e;
                    return;
                }
            }

            Map<Integer, Integer> extra = chunk.getBlockExtraDataArray();
            BinaryStream extraData = null;
            if (!extra.isEmpty()) {
                extraData = new BinaryStream();
                extraData.putLInt(extra.size());
                for (Map.Entry<Integer, Integer> entry : extra.entrySet()) {
                    extraData.putLInt(entry.getKey());
                    extraData.putLShort(entry.getValue());
                }
            }

            byte[] merged = new byte[82432];
            byte[] blocks = chunk.getBlockIdArray();
            boolean hit = false;
            long hash = 0;
            if (antixray.localCache) {
                hash = antixray.getCacheHash(blocks);
                if (antixray.hasCache(hash)) {
                    System.arraycopy(antixray.readCache(hash), 0, merged, 0, 49152);
                    hit = true;
                }
            }
            if (!hit) {
                byte[] ids = new byte[32768];
                System.arraycopy(blocks, 0, ids, 0, 32768);
                byte[] data = new byte[16384];
                System.arraycopy(chunk.getBlockDataArray(), 0, data, 0, 16384);
                for (int cx = 1; cx < 15; cx++) {
                    int tx = cx << 11;
                    int dtx = cx << 10;
                    for (int cz = 1; cz < 15; cz++) {
                        int tz = cz << 7;
                        int dtz = cz << 6;
                        int xz = tx | tz;
                        int dxz = dtx | dtz;
                        for (int y = 1; y <= antixray.height && y < 255; y++) {
                            int xy = tx | y;
                            int zy = tz | y;
                            if (!antixray.filters.contains(blocks[((cx + 1) << 11) | zy] & 0xff)
                                    && !antixray.filters.contains(blocks[((cx - 1) << 11) | zy] & 0xff)
                                    && !antixray.filters.contains(blocks[xy | ((cz + 1) << 7)] & 0xff)
                                    && !antixray.filters.contains(blocks[xy | ((cz - 1) << 7)] & 0xff)
                                    && !antixray.filters.contains(blocks[xz | (y + 1)] & 0xff)
                                    && !antixray.filters.contains(blocks[xz | (y - 1)] & 0xff)) {
                                int index = xz | y;
                                if (antixray.mode) {
                                    blocks[index] = (byte) (antixray.ores.getInt(index % maxSize) & 0xff);
                                } else if (antixray.ores.contains(blocks[index] & 0xff)) {
                                    switch (level.getDimension()) {
                                        case Level.DIMENSION_OVERWORLD:
                                            blocks[index] = (byte) (antixray.fake_o & 0xff);
                                            break;
                                        case Level.DIMENSION_NETHER:
                                            blocks[index] = (byte) (antixray.fake_n & 0xff);
                                            break;
                                        case Level.DIMENSION_THE_END:
                                        default:
                                            blocks[index] = 0;
                                            break;
                                    }
                                } else {
                                    continue;
                                }
                                int dataIndex = dxz | (y >> 1);
                                int blockData = data[dataIndex] & 0xff;
                                boolean flag = (y & 1) == 0;
                                if ((flag ? data[dataIndex] & 0xf : data[dataIndex] >> 4) != 0) {
                                    data[dataIndex] = flag ? (byte) (blockData & 0xf0) : (byte) (blockData & 0xf);
                                }
                            }
                        }
                    }
                }
                System.arraycopy(blocks, 0, merged, 0, 32768);
                System.arraycopy(data, 0, merged, 32768, 16384);
                if (antixray.localCache) {
                    byte[] cache = new byte[49152];
                    System.arraycopy(merged, 0, cache, 0, 49152);
                    antixray.createCache(hash, cache);
                }
            }
            System.arraycopy(chunk.getBlockSkyLightArray(), 0, merged, 49152, 16384);
            System.arraycopy(chunk.getBlockLightArray(), 0, merged, 65536, 16384);
            System.arraycopy(chunk.getHeightMapArray(), 0, merged, 81920, 256);
            System.arraycopy(chunk.getBiomeIdArray(), 0, merged, 82176, 256);
            BinaryStream stream = new BinaryStream(merged);
            if (extraData != null) {
                stream.put(extraData.getBuffer());
            } else {
                stream.putLInt(0); //4
            }
            if (tiles.length != 0) {
                stream.put(tiles);
            }

            byte[] payload = stream.getBuffer();

            if (antixray.memoryCache) {
                this.cachePacket = Player.getChunkCacheFromData(this.chunkX, this.chunkZ, 16, payload);
            }

            this.setResult(payload);
        }

        @Override
        public void onCompletion(Server server) {
            if (this.hasResult()) {
                chunkRequestCallback(this.timestamp, this.chunkX, this.chunkZ, 16, (byte[]) this.getResult(), this.cachePacket);
            } else {
                chunkRequestFailed(this.chunkX, this.chunkZ, this.t != null ? this.t : new NullPointerException("Payload cannot be null"));
            }
        }
    }

    private static class Entry {

        final long timestamp;
        final BatchPacket cache;

        private Entry(long timestamp, BatchPacket cache) {
            this.timestamp = timestamp;
            this.cache = cache;
        }
    }
}
