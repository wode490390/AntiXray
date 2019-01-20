package cn.wode490390.nukkit.antixray;

import cn.nukkit.Player;
import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.blockentity.BlockEntitySpawnable;
import cn.nukkit.level.Level;
import cn.nukkit.level.format.LevelProvider;
import cn.nukkit.level.format.anvil.Anvil;
import cn.nukkit.level.format.anvil.ChunkSection;
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
import cn.nukkit.utils.ThreadCache;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class WorldHandler {

    private final ConcurrentMap<Long, Int2ObjectMap<Player>> chunkSendQueue = new ConcurrentHashMap<>();
    private final LongSet chunkSendTasks = new LongOpenHashSet();

    private final Level level;
    private final Runnable tick = new NukkitRunnable() {
            @Override
            public void run() {
                processChunkRequest();
            }
    };

    public WorldHandler(Level level) {
        this.level = level;
        ((NukkitRunnable) this.tick).runTaskTimer(AntiXray.getInstance(), 0, 1);
    }

    public void requestChunk(int x, int z, Player p) {
        long i = Level.chunkHash(x, z);
        this.chunkSendQueue.putIfAbsent(i, new Int2ObjectOpenHashMap<>());
        this.chunkSendQueue.get(i).put(p.getLoaderId(), p);
    }

    private void processChunkRequest() {
        Iterator<Long> i = this.chunkSendQueue.keySet().iterator();
        while (i.hasNext()) {
            long h = i.next();
            if (this.chunkSendTasks.contains(h)) {
                continue;
            }
            int x = Level.getHashX(h);
            int z = Level.getHashZ(h);
            this.chunkSendTasks.add(h);
            AsyncTask t;
            LevelProvider p = this.level.getProvider();
            if (p instanceof Anvil) {
                t = this.requestAnvilChunkTask(x, z);
            } else if (p instanceof LevelDB) {
                t = this.requestLevelDBChunkTask(x, z);
            } else if (p instanceof McRegion) {
                t = this.requestMcRegionChunkTask(x, z);
            } else {
                BaseFullChunk c = this.level.getChunk(x, z);
                if (c != null) {
                    BatchPacket d = c.getChunkPacket();
                    if (d != null) {
                        this.sendChunk(x, z, h, d);
                        continue;
                    }
                }
                t = p.requestChunkTask(x, z);
            }
            if (t != null) {
                AntiXray.getInstance().getServer().getScheduler().scheduleAsyncTask(t);
            }
        }
    }

    private void sendChunk(int x, int z, long i, DataPacket d) {
        if (this.chunkSendTasks.contains(i)) {
            for (Player p : this.chunkSendQueue.get(i).values()) {
                if (p.isConnected() && p.usedChunks.containsKey(i)) {
                    p.sendChunk(x, z, d);
                }
            }
            this.chunkSendQueue.remove(i);
            this.chunkSendTasks.remove(i);
        }
    }

    private void chunkRequestCallback(long t, int x, int z, byte[] d) {
        long i = Level.chunkHash(x, z);
        if (AntiXray.getInstance().cache) {
            BatchPacket b = Player.getChunkCacheFromData(x, z, d);
            BaseFullChunk chunk = this.level.getChunk(x, z, false);
            if (chunk != null && chunk.getChanges() <= t) {
                chunk.setChunkPacket(b);
            }
            this.sendChunk(x, z, i, b);
            return;
        }
        if (this.chunkSendTasks.contains(i)) {
            for (Player p : this.chunkSendQueue.get(i).values()) {
                if (p.isConnected() && p.usedChunks.containsKey(i)) {
                    p.sendChunk(x, z, d);
                }
            }
            this.chunkSendQueue.remove(i);
            this.chunkSendTasks.remove(i);
        }
    }

    private AsyncTask requestAnvilChunkTask(int cX, int cZ) {
        cn.nukkit.level.format.anvil.Chunk chunk = (cn.nukkit.level.format.anvil.Chunk) this.level.getProvider().getChunk(cX, cZ, false);
        if (chunk == null) {
            return null;
        }
        long t = chunk.getChanges();
        byte[] blockEntities = new byte[0];
        if (!chunk.getBlockEntities().isEmpty()) {
            List<CompoundTag> tagList = new ArrayList<>();
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                if (blockEntity instanceof BlockEntitySpawnable) {
                    tagList.add(((BlockEntitySpawnable) blockEntity).getSpawnCompound());
                }
            }
            try {
                blockEntities = NBTIO.write(tagList, ByteOrder.LITTLE_ENDIAN, true);
            } catch (IOException e) {
                return null;
            }
        }
        Map<Integer, Integer> extra = chunk.getBlockExtraDataArray();
        BinaryStream extraData = null;
        if (!extra.isEmpty()) {
            extraData = ThreadCache.binaryStream.get().reset();
            extraData.putVarInt(extra.size());
            for (Map.Entry<Integer, Integer> entry : extra.entrySet()) {
                extraData.putVarInt(entry.getKey());
                extraData.putLShort(entry.getValue());
            }
        }
        BinaryStream s = ThreadCache.binaryStream.get().reset();
        int count = 0;
        cn.nukkit.level.format.ChunkSection[] sections = chunk.getSections();
        for (int i = sections.length - 1; i >= 0; i--) {
            if (!sections[i].isEmpty()) {
                count = i + 1;
                break;
            }
        }
        s.putByte((byte) count);
        for (int i = 0; i < count; i++) {
            s.putByte((byte) 0);
            if (sections[i].isEmpty()) {
                s.put(new byte[6144]);
            } else {
                try {
                    Field f = Field.class.getDeclaredField("modifiers");
                    f.setAccessible(true);
                    Field f1 = ChunkSection.class.getDeclaredField("storage");
                    f.setInt(f1, f1.getModifiers() & ~Modifier.FINAL);
                    f1.setAccessible(true);
                    BlockStorage storage = (BlockStorage) f1.get(sections[i]);
                    byte[] ids = storage.getBlockIds();
                    Field f2 = BlockStorage.class.getDeclaredField("blockData");
                    f.setInt(f2, f2.getModifiers() & ~Modifier.FINAL);
                    f2.setAccessible(true);
                    NibbleArray blockData = (NibbleArray) f2.get(storage);
                    for (int cx = 0; cx < 16; cx++) {
                        for (int cz = 0; cz < 16; cz++) {
                            for (int sy = 0; sy < (AntiXray.getInstance().height >> 4) + 1; sy++) {
                                int x = (cX << 4) + cx;
                                int y = (sections[i].getY() << 4) + sy;
                                int z = (cZ << 4) + cz;
                                if (!AntiXray.getInstance().filters.contains(this.level.getBlockIdAt(x + 1, y, z)) && !AntiXray.getInstance().filters.contains(this.level.getBlockIdAt(x, y + 1, z)) && !AntiXray.getInstance().filters.contains(this.level.getBlockIdAt(x, y, z + 1)) && !AntiXray.getInstance().filters.contains(this.level.getBlockIdAt(x - 1, y, z)) && !AntiXray.getInstance().filters.contains(this.level.getBlockIdAt(x, y - 1, z)) && !AntiXray.getInstance().filters.contains(this.level.getBlockIdAt(x, y, z - 1))) {
                                    int index = (cx << 8) + (cz << 4) + sy;
                                    if (AntiXray.getInstance().mode) {
                                        ids[index] = (byte) (AntiXray.getInstance().ores.get(index % AntiXray.getInstance().ores.size()) & 0xff);
                                    } else if (AntiXray.getInstance().ores.contains(this.level.getBlockIdAt(x, y, z))) {
                                        switch (this.level.getDimension()) {
                                            case Level.DIMENSION_OVERWORLD:
                                                ids[index] = (byte) (AntiXray.getInstance().fake_o & 0xff);
                                                break;
                                            case Level.DIMENSION_NETHER:
                                                ids[index] = (byte) (AntiXray.getInstance().fake_n & 0xff);
                                                break;
                                            case Level.DIMENSION_THE_END:
                                            default:
                                                ids[index] = 1;
                                                break;
                                        }
                                    } else {
                                        continue;
                                    }
                                    if (this.level.getBlockDataAt(x, y, z) != 0) {
                                        blockData.set(index, (byte) 0);
                                    }
                                }
                            }
                        }
                    }
                    byte[] data = blockData.getData();
                    byte[] merged = new byte[ids.length + data.length];
                    System.arraycopy(ids, 0, merged, 0, ids.length);
                    System.arraycopy(data, 0, merged, ids.length, data.length);
                    s.put(merged);
                } catch (Exception e) {
                    return null;
                }
            }
        }
        for (byte height : chunk.getHeightMapArray()) {
            s.putByte(height);
        }
        s.put(new byte[256]);
        s.put(chunk.getBiomeIdArray());
        s.putByte((byte) 0);
        if (extraData != null) {
            s.put(extraData.getBuffer());
        } else {
            s.putVarInt(0);
        }
        s.put(blockEntities);
        this.chunkRequestCallback(t, cX, cZ, s.getBuffer());
        return null;
    }

    private AsyncTask requestLevelDBChunkTask(int cX, int cZ) {
        cn.nukkit.level.format.leveldb.Chunk chunk = ((LevelDB) this.level.getProvider()).getChunk(cX, cZ, false);
        if (chunk == null) {
            return null;
        }
        long t = chunk.getChanges();
        byte[] tiles = new byte[0];
        if (!chunk.getBlockEntities().isEmpty()) {
            List<CompoundTag> tagList = new ArrayList<>();
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                if (blockEntity instanceof BlockEntitySpawnable) {
                    tagList.add(((BlockEntitySpawnable) blockEntity).getSpawnCompound());
                }
            }
            try {
                tiles = NBTIO.write(tagList, ByteOrder.LITTLE_ENDIAN);
            } catch (IOException e) {
                return null;
            }
        }
        Map<Integer, Integer> extra = chunk.getBlockExtraDataArray();
        BinaryStream extraData = null;
        if (!extra.isEmpty()) {
            extraData = ThreadCache.binaryStream.get().reset();
            extraData.putLInt(extra.size());
            for (Integer key : extra.values()) {
                extraData.putLInt(key);
                extraData.putLShort(extra.get(key));
            }
        }
        BinaryStream s = ThreadCache.binaryStream.get().reset();
        byte[] blocks = chunk.getBlockIdArray();
        byte[] data = chunk.getBlockDataArray();
        for (int cx = 0; cx < 16; cx++) {
            for (int cz = 0; cz < 16; cz++) {
                for (int y = 0; y < AntiXray.getInstance().height + 1; y++) {
                    int x = (cX << 4) + cx;
                    int z = (cZ << 4) + cz;
                    if (!AntiXray.getInstance().filters.contains(this.level.getBlockIdAt(x + 1, y, z)) && !AntiXray.getInstance().filters.contains(this.level.getBlockIdAt(x, y + 1, z)) && !AntiXray.getInstance().filters.contains(this.level.getBlockIdAt(x, y, z + 1)) && !AntiXray.getInstance().filters.contains(this.level.getBlockIdAt(x - 1, y, z)) && !AntiXray.getInstance().filters.contains(this.level.getBlockIdAt(x, y - 1, z)) && !AntiXray.getInstance().filters.contains(this.level.getBlockIdAt(x, y, z - 1))) {
                        int i = (cx << 11) | (cz << 7) | y;
                        if (AntiXray.getInstance().mode) {
                            blocks[i] = (byte) (AntiXray.getInstance().ores.get(i % AntiXray.getInstance().ores.size()) & 0xff);
                        } else if (AntiXray.getInstance().ores.contains(this.level.getBlockIdAt(x, y, z))) {
                            switch (this.level.getDimension()) {
                                case Level.DIMENSION_OVERWORLD:
                                    blocks[i] = (byte) (AntiXray.getInstance().fake_o & 0xff);
                                    break;
                                case Level.DIMENSION_NETHER:
                                    blocks[i] = (byte) (AntiXray.getInstance().fake_n & 0xff);
                                    break;
                                case Level.DIMENSION_THE_END:
                                default:
                                    blocks[i] = 1;
                                    break;
                            }
                        } else {
                            continue;
                        }
                        if (this.level.getBlockDataAt(x, y, z) != 0) {
                            int i2 = (cx << 10) | (cz << 6) | (y >> 1);
                            int old = data[i2] & 0xff;
                            if ((y & 1) == 0) {
                                data[i2] = (byte) (old & 0xf0);
                            } else {
                                data[i2] = (byte) (old & 0x0f);
                            }
                        }
                    }
                }
            }
        }
        s.put(blocks);
        s.put(data);
        s.put(chunk.getBlockSkyLightArray());
        s.put(chunk.getBlockLightArray());
        s.put(chunk.getHeightMapArray());
        s.put(chunk.getBiomeIdArray());
        if (extraData != null) {
            s.put(extraData.getBuffer());
        } else {
            s.putLInt(0);
        }
        s.put(tiles);
        this.chunkRequestCallback(t, cX, cZ, s.getBuffer());
        return null;
    }

    private AsyncTask requestMcRegionChunkTask(int cX, int cZ) {
        BaseFullChunk chunk = this.level.getProvider().getChunk(cX, cZ, false);
        if (chunk == null) {
            return null;
        }
        long t = chunk.getChanges();
        byte[] tiles = new byte[0];
        if (!chunk.getBlockEntities().isEmpty()) {
            List<CompoundTag> tagList = new ArrayList<>();
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                if (blockEntity instanceof BlockEntitySpawnable) {
                    tagList.add(((BlockEntitySpawnable) blockEntity).getSpawnCompound());
                }
            }
            try {
                tiles = NBTIO.write(tagList, ByteOrder.LITTLE_ENDIAN, true);
            } catch (IOException e) {
                return null;
            }
        }
        Map<Integer, Integer> extra = chunk.getBlockExtraDataArray();
        BinaryStream extraData = null;
        if (!extra.isEmpty()) {
            extraData = ThreadCache.binaryStream.get().reset();
            extraData.putLInt(extra.size());
            for (Map.Entry<Integer, Integer> entry : extra.entrySet()) {
                extraData.putLInt(entry.getKey());
                extraData.putLShort(entry.getValue());
            }
        }
        BinaryStream s = ThreadCache.binaryStream.get().reset();
        byte[] blocks = chunk.getBlockIdArray();
        byte[] data = chunk.getBlockDataArray();
        for (int cx = 0; cx < 16; cx++) {
            for (int cz = 0; cz < 16; cz++) {
                for (int y = 0; y < AntiXray.getInstance().height + 1; y++) {
                    int x = (cX << 4) + cx;
                    int z = (cZ << 4) + cz;
                    if (!AntiXray.getInstance().filters.contains(this.level.getBlockIdAt(x + 1, y, z)) && !AntiXray.getInstance().filters.contains(this.level.getBlockIdAt(x, y + 1, z)) && !AntiXray.getInstance().filters.contains(this.level.getBlockIdAt(x, y, z + 1)) && !AntiXray.getInstance().filters.contains(this.level.getBlockIdAt(x - 1, y, z)) && !AntiXray.getInstance().filters.contains(this.level.getBlockIdAt(x, y - 1, z)) && !AntiXray.getInstance().filters.contains(this.level.getBlockIdAt(x, y, z - 1))) {
                        int i = (cx << 11) | (cz << 7) | y;
                        if (AntiXray.getInstance().mode) {
                            blocks[i] = (byte) (AntiXray.getInstance().ores.get(i % AntiXray.getInstance().ores.size()) & 0xff);
                        } else if (AntiXray.getInstance().ores.contains(this.level.getBlockIdAt(x, y, z))) {
                            switch (this.level.getDimension()) {
                                case Level.DIMENSION_OVERWORLD:
                                    blocks[i] = (byte) (AntiXray.getInstance().fake_o & 0xff);
                                    break;
                                case Level.DIMENSION_NETHER:
                                    blocks[i] = (byte) (AntiXray.getInstance().fake_n & 0xff);
                                    break;
                                case Level.DIMENSION_THE_END:
                                default:
                                    blocks[i] = 1;
                                    break;
                            }
                        } else {
                            continue;
                        }
                        if (this.level.getBlockDataAt(x, y, z) != 0) {
                            int i2 = (cx << 10) | (cz << 6) | (y >> 1);
                            int old = data[i2] & 0xff;
                            if ((y & 1) == 0) {
                                data[i2] = (byte) (old & 0xf0);
                            } else {
                                data[i2] = (byte) (old & 0x0f);
                            }
                        }
                    }
                }
            }
        }
        s.put(blocks);
        s.put(data);
        s.put(chunk.getBlockSkyLightArray());
        s.put(chunk.getBlockLightArray());
        s.put(chunk.getHeightMapArray());
        s.put(chunk.getBiomeIdArray());
        if (extraData != null) {
            s.put(extraData.getBuffer());
        } else {
            s.putLInt(0);
        }
        s.put(tiles);
        this.chunkRequestCallback(t, cX, cZ, s.getBuffer());
        return null;
    }
}
