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
import cn.nukkit.level.format.anvil.util.NibbleArray;
import cn.nukkit.level.format.generic.BaseFullChunk;
import cn.nukkit.level.format.leveldb.LevelDB;
import cn.nukkit.level.format.mcregion.McRegion;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.network.protocol.BatchPacket;
import cn.nukkit.network.protocol.DataPacket;
import cn.nukkit.scheduler.AsyncTask;
import cn.nukkit.scheduler.NukkitRunnable;
import cn.nukkit.utils.BinaryStream;
import cn.nukkit.utils.ChunkException;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class WorldHandler extends NukkitRunnable {

    private static final byte[] PAD_256 = new byte[256];
    private static final byte[] EMPTY_SECTION = new byte[6144];

    private final Map<Long, Int2ObjectMap<Player>> chunkSendQueue = new ConcurrentHashMap<>();
    private final LongSet chunkSendTasks = new LongOpenHashSet();

    private Map<Long, Entry> caches;

    private final Level level;

    private final AntiXray antixray;
    //private final AntiXrayTimings timings;

    private final int maxSectionY;
    private final int maxSize;

    WorldHandler(AntiXray antixray, Level level) {
        this.level = level;
        this.antixray = antixray;
        //this.timings = new AntiXrayTimings(this.level);
        if (this.antixray.cache) {
            this.caches = new ConcurrentHashMap<>();
        }
        this.maxSectionY = this.antixray.height >> 4;
        this.maxSize = this.antixray.ores.size() - 1;
        this.runTaskTimerAsynchronously(this.antixray, 0, 1);
    }

    @Override
    public void run() {
        this.processChunkRequest();
    }

    public void requestChunk(int chunkX, int chunkZ, Player player) {
        long index = Level.chunkHash(chunkX, chunkZ);
        this.chunkSendQueue.putIfAbsent(index, new Int2ObjectOpenHashMap<>());
        this.chunkSendQueue.get(index).put(player.getLoaderId(), player);
    }

    public void clearCache(int chunkX, int chunkZ) {
        this.caches.remove(Level.chunkHash(chunkX, chunkZ));
    }

    private void processChunkRequest() {
        //this.timings.ChunkSendTimer.startTiming();
        Iterator<Long> iterator = this.chunkSendQueue.keySet().iterator();
        while (iterator.hasNext()) {
            long index = iterator.next();
            if (this.chunkSendTasks.contains(index)) {
                continue;
            }
            int chunkX = Level.getHashX(index);
            int chunkZ = Level.getHashZ(index);
            this.chunkSendTasks.add(index);
            BaseFullChunk chunk = this.level.getChunk(chunkX, chunkZ);
            if (chunk != null) {
                Entry entry = this.caches.get(index);
                if (entry != null && chunk.getChanges() <= entry.timestamp) {
                    this.sendChunk(chunkX, chunkZ, index, entry.cache);
                    continue;
                }
            }
            //this.timings.ChunkSendPrepareTimer.startTiming();
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
            //this.timings.ChunkSendPrepareTimer.stopTiming();
        }
        //this.timings.ChunkSendTimer.stopTiming();
    }

    private void sendChunk(int chunkX, int chunkZ, long index, DataPacket packet) {
        if (this.chunkSendTasks.contains(index)) {
            this.chunkSendQueue.get(index).values().parallelStream()
                    .filter(player -> player.isConnected() && player.usedChunks.containsKey(index))
                    .forEach(player -> player.sendChunk(chunkX, chunkZ, packet));
            this.chunkSendQueue.remove(index);
            this.chunkSendTasks.remove(index);
        }
    }

    private void chunkRequestCallback(long timestamp, int chunkX, int chunkZ, byte[] payload) {
        //this.timings.ChunkSendTimer.startTiming();
        long index = Level.chunkHash(chunkX, chunkZ);
        if (this.antixray.cache) {
            BatchPacket packet = Player.getChunkCacheFromData(chunkX, chunkZ, payload);
            BaseFullChunk chunk = this.level.getChunk(chunkX, chunkZ, false);
            if (chunk != null && chunk.getChanges() <= timestamp) {
                this.caches.put(index, new Entry(timestamp, packet));
            }
            this.sendChunk(chunkX, chunkZ, index, packet);
            //this.timings.ChunkSendTimer.stopTiming();
            return;
        }
        if (this.chunkSendTasks.contains(index)) {
            this.chunkSendQueue.get(index).values().parallelStream()
                    .filter(player -> player.isConnected() && player.usedChunks.containsKey(index))
                    .forEach(player -> player.sendChunk(chunkX, chunkZ, payload));
            this.chunkSendQueue.remove(index);
            this.chunkSendTasks.remove(index);
        }
        //this.timings.ChunkSendTimer.stopTiming();
    }

    private void chunkRequestFailed(int chunkX, int chunkZ, Throwable t) {
        long index = Level.chunkHash(chunkX, chunkZ);
        this.chunkSendQueue.remove(index);
        this.chunkSendTasks.remove(index);
        this.antixray.getLogger().debug("Chunk request failed at: " + chunkX + "," + chunkZ, t);
    }

    private class AnvilChunkRequestTask extends AsyncTask {

        private final int chunkX;
        private final int chunkZ;

        private long timestamp;
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
                List<CompoundTag> tagList = Collections.synchronizedList(new ArrayList<>());
                chunk.getBlockEntities().values().parallelStream()
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
            int count = 0;
            ChunkSection[] sections = chunk.getSections();
            for (int i = sections.length - 1; i >= 0; i--) {
                if (!sections[i].isEmpty()) {
                    count = i + 1;
                    break;
                }
            }
            BinaryStream stream = new BinaryStream(new byte[771]).reset(); //1 + 256 + 256 + 256 + 1 + 1
            stream.putByte((byte) count); //1
            for (int i = 0; i < count; i++) {
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
                        byte[] ids = storage.getBlockIds();
                        byte[] data = new byte[storage.getBlockData().length];
                        System.arraycopy(storage.getBlockData(), 0, data, 0, data.length);
                        NibbleArray blockData = new NibbleArray(data);
                        for (int cx = 0; cx < 16; cx++) {
                            for (int cz = 0; cz < 16; cz++) {
                                for (int cy = 0; cy < 16; cy++) {
                                    int x = (this.chunkX << 4) + cx;
                                    int y = (section.getY() << 4) + cy;
                                    int z = (this.chunkZ << 4) + cz;
                                    if (!antixray.filters.contains(level.getBlockIdAt(x + 1, y, z))
                                            && !antixray.filters.contains(level.getBlockIdAt(x, y + 1, z))
                                            && !antixray.filters.contains(level.getBlockIdAt(x, y, z + 1))
                                            && !antixray.filters.contains(level.getBlockIdAt(x - 1, y, z))
                                            && !antixray.filters.contains(level.getBlockIdAt(x, y - 1, z))
                                            && !antixray.filters.contains(level.getBlockIdAt(x, y, z - 1))) {
                                        int index = (cx << 8) + (cz << 4) + cy;
                                        if (antixray.mode) {
                                            ids[index] = (byte) (antixray.ores.get(index % maxSize) & 0xff);
                                        } else if (antixray.ores.contains(level.getBlockIdAt(x, y, z))) {
                                            switch (level.getDimension()) {
                                                case Level.DIMENSION_OVERWORLD:
                                                    ids[index] = (byte) (antixray.fake_o & 0xff);
                                                    break;
                                                case Level.DIMENSION_NETHER:
                                                    ids[index] = (byte) (antixray.fake_n & 0xff);
                                                    break;
                                                case Level.DIMENSION_THE_END:
                                                default:
                                                    ids[index] = 1;
                                                    break;
                                            }
                                        } else {
                                            continue;
                                        }
                                        if (level.getBlockDataAt(x, y, z) != 0) {
                                            blockData.set(index, (byte) 0);
                                        }
                                    }
                                }
                            }
                        }
                        byte[] merged = new byte[ids.length + data.length];
                        System.arraycopy(ids, 0, merged, 0, ids.length);
                        System.arraycopy(data, 0, merged, ids.length, data.length);
                        stream.put(merged);
                    } catch (Exception e) {
                        stream.put(section.getBytes());
                    }
                } else {
                    stream.put(section.getBytes());
                }
            }
            for (byte height : chunk.getHeightMapArray()) { //256
                stream.putByte(height);
            }
            stream.put(PAD_256); //256
            stream.put(chunk.getBiomeIdArray()); //256
            stream.putByte((byte) 0); //1
            if (extraData != null) {
                stream.put(extraData.getBuffer());
            } else {
                stream.putVarInt(0); //1
            }
            if (tiles.length != 0) {
                stream.put(tiles);
            }
            this.setResult(stream.getBuffer());
        }

        @Override
        public void onCompletion(Server server) {
            if (this.hasResult()) {
                chunkRequestCallback(this.timestamp, this.chunkX, this.chunkZ, (byte[]) this.getResult());
            } else {
                chunkRequestFailed(this.chunkX, this.chunkZ, this.t != null ? this.t : new NullPointerException("Payload cannot be null"));
            }
        }
    }

    private class LevelDBChunkRequestTask extends AsyncTask {

        private final int chunkX;
        private final int chunkZ;

        private long timestamp;
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
                List<CompoundTag> tagList = Collections.synchronizedList(new ArrayList<>());
                chunk.getBlockEntities().values().parallelStream()
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
            byte[] blocks = new byte[chunk.getBlockIdArray().length];
            System.arraycopy(chunk.getBlockIdArray(), 0, blocks, 0, blocks.length);
            byte[] data = new byte[chunk.getBlockDataArray().length];
            System.arraycopy(chunk.getBlockDataArray(), 0, data, 0, data.length);
            for (int cx = 0; cx < 16; cx++) {
                for (int cz = 0; cz < 16; cz++) {
                    for (int y = 0; y <= antixray.height; y++) {
                        int x = (this.chunkX << 4) + cx;
                        int z = (this.chunkZ << 4) + cz;
                        if (!antixray.filters.contains(level.getBlockIdAt(x + 1, y, z))
                                && !antixray.filters.contains(level.getBlockIdAt(x, y + 1, z))
                                && !antixray.filters.contains(level.getBlockIdAt(x, y, z + 1))
                                && !antixray.filters.contains(level.getBlockIdAt(x - 1, y, z))
                                && !antixray.filters.contains(level.getBlockIdAt(x, y - 1, z))
                                && !antixray.filters.contains(level.getBlockIdAt(x, y, z - 1))) {
                            int index = (cx << 11) | (cz << 7) | y;
                            if (antixray.mode) {
                                blocks[index] = (byte) (antixray.ores.get(index % maxSize) & 0xff);
                            } else if (antixray.ores.contains(level.getBlockIdAt(x, y, z))) {
                                switch (level.getDimension()) {
                                    case Level.DIMENSION_OVERWORLD:
                                        blocks[index] = (byte) (antixray.fake_o & 0xff);
                                        break;
                                    case Level.DIMENSION_NETHER:
                                        blocks[index] = (byte) (antixray.fake_n & 0xff);
                                        break;
                                    case Level.DIMENSION_THE_END:
                                    default:
                                        blocks[index] = 1;
                                        break;
                                }
                            } else {
                                continue;
                            }
                            if (level.getBlockDataAt(x, y, z) != 0) {
                                int dataIndex = (cx << 10) | (cz << 6) | (y >> 1);
                                int old = data[dataIndex] & 0xff;
                                if ((y & 1) == 0) {
                                    data[dataIndex] = (byte) (old & 0xf0);
                                } else {
                                    data[dataIndex] = (byte) (old & 0x0f);
                                }
                            }
                        }
                    }
                }
            }
            BinaryStream stream = new BinaryStream(new byte[82436]).reset(); //32768 + 16384 + 16384 + 16384 + 256 + 256 + 4
            stream.put(blocks); //32768
            stream.put(data); //16384
            stream.put(chunk.getBlockSkyLightArray()); //16384
            stream.put(chunk.getBlockLightArray()); //16384
            stream.put(chunk.getHeightMapArray()); //256
            stream.put(chunk.getBiomeIdArray()); //256
            if (extraData != null) {
                stream.put(extraData.getBuffer());
            } else {
                stream.putLInt(0); //4
            }
            if (tiles.length != 0) {
                stream.put(tiles);
            }
            this.setResult(stream.getBuffer());
        }

        @Override
        public void onCompletion(Server server) {
            if (this.hasResult()) {
                chunkRequestCallback(this.timestamp, this.chunkX, this.chunkZ, (byte[]) this.getResult());
            } else {
                chunkRequestFailed(this.chunkX, this.chunkZ, this.t != null ? this.t : new NullPointerException("Payload cannot be null"));
            }
        }
    }

    private class McRegionChunkRequestTask extends AsyncTask {

        private final int chunkX;
        private final int chunkZ;

        private long timestamp;
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
                List<CompoundTag> tagList = Collections.synchronizedList(new ArrayList<>());
                chunk.getBlockEntities().values().parallelStream()
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
            byte[] blocks = new byte[chunk.getBlockIdArray().length];
            System.arraycopy(chunk.getBlockIdArray(), 0, blocks, 0, blocks.length);
            byte[] data = new byte[chunk.getBlockDataArray().length];
            System.arraycopy(chunk.getBlockDataArray(), 0, data, 0, data.length);
            for (int cx = 0; cx < 16; cx++) {
                for (int cz = 0; cz < 16; cz++) {
                    for (int y = 0; y <= antixray.height; y++) {
                        int x = (this.chunkX << 4) + cx;
                        int z = (this.chunkZ << 4) + cz;
                        if (!antixray.filters.contains(level.getBlockIdAt(x + 1, y, z))
                                && !antixray.filters.contains(level.getBlockIdAt(x, y + 1, z))
                                && !antixray.filters.contains(level.getBlockIdAt(x, y, z + 1))
                                && !antixray.filters.contains(level.getBlockIdAt(x - 1, y, z))
                                && !antixray.filters.contains(level.getBlockIdAt(x, y - 1, z))
                                && !antixray.filters.contains(level.getBlockIdAt(x, y, z - 1))) {
                            int index = (cx << 11) | (cz << 7) | y;
                            if (antixray.mode) {
                                blocks[index] = (byte) (antixray.ores.get(index % maxSize) & 0xff);
                            } else if (antixray.ores.contains(level.getBlockIdAt(x, y, z))) {
                                switch (level.getDimension()) {
                                    case Level.DIMENSION_OVERWORLD:
                                        blocks[index] = (byte) (antixray.fake_o & 0xff);
                                        break;
                                    case Level.DIMENSION_NETHER:
                                        blocks[index] = (byte) (antixray.fake_n & 0xff);
                                        break;
                                    case Level.DIMENSION_THE_END:
                                    default:
                                        blocks[index] = 1;
                                        break;
                                }
                            } else {
                                continue;
                            }
                            if (level.getBlockDataAt(x, y, z) != 0) {
                                int dataIndex = (cx << 10) | (cz << 6) | (y >> 1);
                                int old = data[dataIndex] & 0xff;
                                if ((y & 1) == 0) {
                                    data[dataIndex] = (byte) (old & 0xf0);
                                } else {
                                    data[dataIndex] = (byte) (old & 0x0f);
                                }
                            }
                        }
                    }
                }
            }
            BinaryStream stream = new BinaryStream(new byte[82436]).reset(); //32768 + 16384 + 16384 + 16384 + 256 + 256 + 4
            stream.put(blocks); //32768
            stream.put(data); //16384
            stream.put(chunk.getBlockSkyLightArray()); //16384
            stream.put(chunk.getBlockLightArray()); //16384
            stream.put(chunk.getHeightMapArray()); //256
            stream.put(chunk.getBiomeIdArray()); //256
            if (extraData != null) {
                stream.put(extraData.getBuffer());
            } else {
                stream.putLInt(0); //4
            }
            if (tiles.length != 0) {
                stream.put(tiles);
            }
            this.setResult(stream.getBuffer());
        }

        @Override
        public void onCompletion(Server server) {
            if (this.hasResult()) {
                chunkRequestCallback(this.timestamp, this.chunkX, this.chunkZ, (byte[]) this.getResult());
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
