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
import cn.nukkit.blockentity.BlockEntity;
import cn.nukkit.blockentity.BlockEntitySpawnable;
import cn.nukkit.level.GlobalBlockPalette;
import cn.nukkit.level.Level;
import cn.nukkit.level.format.ChunkSection;
import cn.nukkit.level.format.anvil.Anvil;
import cn.nukkit.level.format.anvil.Chunk;
import cn.nukkit.level.format.anvil.util.BlockStorage;
import cn.nukkit.level.format.generic.BaseFullChunk;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.network.protocol.BatchPacket;
import cn.nukkit.plugin.Plugin;
import cn.nukkit.scheduler.PluginTask;
import cn.nukkit.utils.BinaryStream;
import com.google.common.collect.Lists;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

public class WorldHandler extends PluginTask<Plugin> {

    private static final Field F_storage;

    private static final byte[] PALETTE_HEADER_V16 = new byte[]{(16 << 1) | 1};
    private static final byte[] PALETTE_HEADER_V8 = new byte[]{(8 << 1) | 1};
    private static final byte[] PALETTE_HEADER_V4 = new byte[]{(4 << 1) | 1};
    private static final byte[] BORDER_BLOCKS_DATA = new byte[]{0}; // size - Education Edition only
    private static final byte[] SECTION_HEADER = new byte[]{8, 2}; // subChunkVersion + storageCount
    private static final byte[] EMPTY_STORAGE = new byte[1 + 32 * 4 * 4 + 1 + 1];
    private static final byte[] EMPTY_SECTION = new byte[1 + 1 + (1 + 32 * 4 * 4 + 1 + 1) * 2];

    static {
        try {
            Field f = Field.class.getDeclaredField("modifiers");
            f.setAccessible(true);

            F_storage = cn.nukkit.level.format.anvil.ChunkSection.class.getDeclaredField("storage");
            f.setInt(F_storage, F_storage.getModifiers() & ~Modifier.FINAL);
            F_storage.setAccessible(true);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        EMPTY_STORAGE[0] = 3; //paletteVersion
        EMPTY_STORAGE[513] = 2;

        EMPTY_SECTION[0] = 8; //subChunkVersion
        EMPTY_SECTION[1] = 2; //storageCount
        EMPTY_SECTION[2] = 3;
        EMPTY_SECTION[515] = 2;
        EMPTY_SECTION[517] = 3;
        EMPTY_SECTION[1030] = 2;
    }

    private static final int[] MAGIC_BLOCKS = {
            Block.GOLD_ORE,
            Block.IRON_ORE,
            Block.COAL_ORE,
            Block.LAPIS_ORE,
            Block.DIAMOND_ORE,
            Block.REDSTONE_ORE,
            Block.EMERALD_ORE,
            Block.QUARTZ_ORE
    };
    private static final int MAGIC_NUMBER = 0b111;

    private final Long2ObjectOpenHashMap<Int2ObjectMap<Player>> chunkSendQueue = new Long2ObjectOpenHashMap<>();

    private final AntiXray antixray;
    private final Level level;

    private final boolean isAnvil;
    private final int fakeBlock;

    public WorldHandler(AntiXray antixray, Level level) {
        super(antixray);
        this.antixray = antixray;
        this.level = level;
        this.isAnvil = level.getProvider() instanceof Anvil;
        this.fakeBlock = this.antixray.dimension[this.level.getDimension() & 3];

        if (this.isAnvil) {
            antixray.getServer().getScheduler().scheduleRepeatingTask(antixray, this, 1);
        } else {
            antixray.getLogger().debug("The provider of '" + level.getName() + "' is not supported");
        }
    }

    public void requestChunk(int chunkX, int chunkZ, Player player) {
        if (this.isAnvil) {
            long hash = Level.chunkHash(chunkX, chunkZ);
            Int2ObjectMap<Player> queue = this.chunkSendQueue.get(hash);
            if (queue == null) {
                queue = new Int2ObjectOpenHashMap<>();
                this.chunkSendQueue.put(hash, queue);
            }
            queue.put(player.getLoaderId(), player);
        } else {
            this.level.requestChunk(chunkX, chunkZ, player);
        }
    }

    @Override
    public void onRun(int currentTick) {
        this.level.timings.syncChunkSendTimer.startTiming();
        ObjectIterator<Long2ObjectMap.Entry<Int2ObjectMap<Player>>> iterator = this.chunkSendQueue.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<Int2ObjectMap<Player>> entry = iterator.next();
            long hash = entry.getLongKey();
            Int2ObjectMap<Player> queue = entry.getValue();
            int chunkX = Level.getHashX(hash);
            int chunkZ = Level.getHashZ(hash);

            BaseFullChunk levelChunk = this.level.getChunk(chunkX, chunkZ);
            if (levelChunk != null) {
                BatchPacket packet = levelChunk.getChunkPacket();
                if (packet != null) {
                    for (Player player : queue.values()) {
                        if (player.usedChunks.containsKey(hash)) {
                            player.sendChunk(chunkX, chunkZ, packet);
                        }
                    }

                    iterator.remove();
                    continue;
                }
            }

            this.level.timings.syncChunkSendPrepareTimer.startTiming();
            Chunk chunk = (Chunk) this.level.getProvider().getChunk(chunkX, chunkZ, false);
            if (chunk == null) {
                this.antixray.getLogger().warning("Invalid Chunk Set (" + this.level.getName() + "|" + chunkX + "," + chunkZ + ")");
                this.level.timings.syncChunkSendPrepareTimer.stopTiming();
                continue;
            }
            long timestamp = chunk.getChanges();

            int count = 0;
            ChunkSection[] sections = chunk.getSections();
            for (int i = sections.length - 1; i >= 0; i--) {
                if (!sections[i].isEmpty()) {
                    count = i + 1;
                    break;
                }
            }

            BinaryStream stream = cacheBinaryStream.reset();
            for (int i = 0; i < count; i++) {
                ChunkSection section = sections[i];
                if (section.isEmpty()) {
                    stream.put(EMPTY_SECTION);
                } else if (section.getY() <= this.antixray.height) {
                    stream.put(SECTION_HEADER); // Paletted chunk because Mojang messed up the old one

                    try {
                        BlockStorage storage = (BlockStorage) F_storage.get(section);
                        byte[] blocks = storage.getBlockIds();
                        byte[] data = storage.getBlockData();

                        boolean resized = false;
                        int bits = 2;
                        int maxEntryValue = (1 << 4) - 1;
                        int[] words = cacheIntArray_V4;
                        byte[] header = PALETTE_HEADER_V4;
                        IntList palette = new IntArrayList(16) {
                            {
                                this.size++; // Air is at the start of every palette
                            }
                        };

                        for (int cx = 0; cx < 16; cx++) {
                            int tx = cx << 8;
                            for (int cz = 0; cz < 16; cz++) {
                                int tz = cz << 4;
                                int xz = tx + tz;
                                for (int cy = 0; cy < 16; cy++) {
                                    int xy = tx + cy;
                                    int zy = tz + cy;
                                    int index = xz + cy;

                                    int id = -1;
                                    int meta = 0;

                                    if (cx != 0 && cx != 15 && cz != 0 && cz != 15 && cy != 0 && cy != 15 // skip chunk border
                                            && !this.antixray.filter[blocks[((cx + 1) << 8) + zy] & 0xff]
                                            && !this.antixray.filter[blocks[((cx - 1) << 8) + zy] & 0xff]
                                            && !this.antixray.filter[blocks[xy + ((cz + 1) << 4)] & 0xff]
                                            && !this.antixray.filter[blocks[xy + ((cz - 1) << 4)] & 0xff]
                                            && !this.antixray.filter[blocks[index + 1] & 0xff]
                                            && !this.antixray.filter[blocks[index - 1] & 0xff]) {
                                        if (this.antixray.obfuscatorMode) {
                                            id = MAGIC_BLOCKS[index & MAGIC_NUMBER];
                                        } else if (this.antixray.ore[blocks[index] & 0xff]) {
                                            id = this.fakeBlock;
                                        }
                                    }

                                    if (id == -1) {
                                        id = blocks[index] & 0xff;

                                        byte nibbleData = data[index >>> 1];
                                        meta = (index & 1) == 0 ? nibbleData & 0xf : (nibbleData & 0xf0) >>> 4;
                                    }

                                    int runtimeId = GlobalBlockPalette.getOrCreateRuntimeId(id, meta);
                                    int paletteIndex = palette.indexOf(runtimeId);
                                    if (paletteIndex == -1) {
                                        paletteIndex = palette.size();
                                        if (paletteIndex > maxEntryValue) { // need to resize
                                            int[] newWords;
                                            if (resized) {
                                                newWords = cacheIntArray_V16;
                                                for (int oldIndex = 0; oldIndex < index; oldIndex++) {
                                                    int bitIndex = oldIndex << 3;
                                                    int arrayIndex = bitIndex >> 5;
                                                    int offset = bitIndex & 31;
                                                    int value = words[arrayIndex] >>> offset & ((1 << 8) - 1);

                                                    bitIndex = oldIndex << 4;
                                                    arrayIndex = bitIndex >> 5;
                                                    offset = bitIndex & 31;
                                                    newWords[arrayIndex] = newWords[arrayIndex] & ~(((1 << 16) - 1) << offset) | value << offset;
                                                }
                                                bits = 4;
                                                maxEntryValue = (1 << 16) - 1;
                                                header = PALETTE_HEADER_V16;
                                            } else {
                                                resized = true;
                                                newWords = cacheIntArray_V8;
                                                for (int oldIndex = 0; oldIndex < index; oldIndex++) {
                                                    int bitIndex = oldIndex << 2;
                                                    int arrayIndex = bitIndex >> 5;
                                                    int offset = bitIndex & 31;
                                                    int value = words[arrayIndex] >>> offset & ((1 << 4) - 1);

                                                    bitIndex = oldIndex << 3;
                                                    arrayIndex = bitIndex >> 5;
                                                    offset = bitIndex & 31;
                                                    newWords[arrayIndex] = newWords[arrayIndex] & ~(((1 << 8) - 1) << offset) | value << offset;
                                                }
                                                bits = 3;
                                                maxEntryValue = (1 << 8) - 1;
                                                header = PALETTE_HEADER_V8;
                                            }
                                            words = newWords;
                                        }
                                        palette.add(runtimeId);
                                    }
                                    int bitIndex = index << bits;
                                    int arrayIndex = bitIndex >> 5;
                                    int offset = bitIndex & 31;
                                    words[arrayIndex] = words[arrayIndex] & ~(maxEntryValue << offset) | paletteIndex << offset;
                                }
                            }
                        }

                        stream.put(header);
                        for (int word : words) {
                            stream.putLInt(word);
                        }
                        stream.putVarInt(palette.size());
                        palette.forEach((IntConsumer) stream::putVarInt);

                        stream.put(EMPTY_STORAGE);
                    } catch (Exception e) {
                        stream.reset();
                        for (ChunkSection subChunk : sections) {
                            subChunk.writeTo(stream);
                        }
                        this.antixray.getLogger().debug("An error occurred while calculating chunk data", e);
                        break;
                    }
                } else {
                    section.writeTo(stream);
                }
            }

            stream.put(chunk.getBiomeIdArray());

            stream.put(BORDER_BLOCKS_DATA);

            Map<Integer, Integer> extraData = chunk.getBlockExtraDataArray();
            stream.putUnsignedVarInt(extraData.size()); //1
            if (!extraData.isEmpty()) {
                for (Map.Entry<Integer, Integer> ent : extraData.entrySet()) {
                    stream.putVarInt(ent.getKey());
                    stream.putLShort(ent.getValue());
                }
            }

            Map<Long, BlockEntity> blockEntities = chunk.getBlockEntities();
            if (!blockEntities.isEmpty()) {
                List<CompoundTag> tagList = Lists.newArrayList();
                blockEntities.values().stream()
                        .filter(blockEntity -> blockEntity instanceof BlockEntitySpawnable)
                        .forEach(blockEntity -> tagList.add(((BlockEntitySpawnable) blockEntity).getSpawnCompound()));
                if (!tagList.isEmpty()) {
                    try {
                        stream.put(NBTIO.write(tagList, ByteOrder.LITTLE_ENDIAN, true));
                    } catch (IOException e) {
                        this.antixray.getLogger().debug("An error occurred while calculating chunk data", e);
                    }
                }
            }

            byte[] payload = stream.getBuffer();
            if (antixray.memoryCache) {
                BatchPacket packet = Player.getChunkCacheFromData(chunkX, chunkZ, count, payload);
                BaseFullChunk ck = this.level.getChunk(chunkX, chunkZ, false);
                if (ck != null && ck.getChanges() <= timestamp) {
                    ck.setChunkPacket(packet);
                }

                for (Player player : queue.values()) {
                    if (player.usedChunks.containsKey(hash)) {
                        player.sendChunk(chunkX, chunkZ, packet);
                    }
                }
            } else {
                for (Player player : queue.values()) {
                    if (player.usedChunks.containsKey(hash)) {
                        player.sendChunk(chunkX, chunkZ, count, payload);
                    }
                }
            }

            iterator.remove();
            this.level.timings.syncChunkSendPrepareTimer.stopTiming();
        }
        this.level.timings.syncChunkSendTimer.stopTiming();
    }

    private static final BinaryStream cacheBinaryStream = new BinaryStream(new byte[32768]);
    private static final int[] cacheIntArray_V16 = new int[4096 / 2];
    private static final int[] cacheIntArray_V8 = new int[4096 / 4];
    private static final int[] cacheIntArray_V4 = new int[4096 / 8];

    public static void init() {
        //NOOP
    }
}
