package com.jaguarm.nauvisterrain.world;

import com.jaguarm.nauvisterrain.noise.NoiseProgram.Prototype;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Factorio's Nauvis as a chunk generator: the noise program's tiles laid flat at the preset's
 * surface, and Minecraft's own biome decoration on top (docs/ARCHITECTURE.md, the world). The map
 * seed is the world seed's low 32 bits, as Factorio's is a 32-bit number.
 */
public final class NauvisGenerator extends ChunkGenerator {
    public static final MapCodec<NauvisGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
            NauvisSettings.CODEC.fieldOf("settings").forGetter(NauvisGenerator::settings)
    ).apply(i, i.stable(NauvisGenerator::new)));

    private final NauvisSettings settings;
    private volatile Ground ground;

    public NauvisGenerator(BiomeSource biomeSource, NauvisSettings settings) {
        super(biomeSource);
        this.settings = settings;
    }

    public NauvisSettings settings() {
        return settings;
    }

    /** The world seed arrives here before any chunk is made: the level's chunk map asks for this first. */
    @Override
    public ChunkGeneratorStructureState createState(HolderLookup<StructureSet> structureSets, RandomState randomState, long legacyLevelSeed) {
        Ground current = ground;
        if (current == null || current.mapSeed != (legacyLevelSeed & 0xFFFFFFFFL)) {
            ground = new Ground(settings, legacyLevelSeed);
        }
        return super.createState(structureSets, randomState, legacyLevelSeed);
    }

    private Ground ground() {
        Ground g = ground;
        if (g == null) {
            throw new IllegalStateException("Nauvis was asked for ground before it was given the world seed");
        }
        return g;
    }

    /** The Factorio tile at a column. */
    public Prototype tile(int x, int z) {
        return ground().tile(x, z);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState, StructureManager structureManager,
                                                        ChunkAccess chunk) {
        Ground g = ground();
        return CompletableFuture.supplyAsync(() -> {
            List<LevelChunkSection> sections = new ArrayList<>();
            for (int i = 0; i < chunk.getSectionsCount(); i++) {
                LevelChunkSection section = chunk.getSection(i);
                section.acquire();
                sections.add(section);
            }
            try {
                g.fill(chunk);
            } finally {
                sections.forEach(LevelChunkSection::release);
            }
            return chunk;
        }, Util.backgroundExecutor().forName("nauvis_terrain_fill"));
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager, RandomState randomState, ChunkAccess chunk) {
    }

    /** No caves: Factorio has no underground. */
    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState, BiomeManager biomeManager,
                             StructureManager structureManager, ChunkAccess chunk) {
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        ChunkPos center = region.getCenter();
        Holder<Biome> biome = region.getBiome(center.getWorldPosition().atY(region.getMaxY()));
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        random.setDecorationSeed(region.getSeed(), center.getMinBlockX(), center.getMinBlockZ());
        NaturalSpawner.spawnMobsForChunkGeneration(region, biome, center, random);
    }

    @Override
    public int getGenDepth() {
        return 384;
    }

    @Override
    public int getSeaLevel() {
        return settings.surface();
    }

    @Override
    public int getMinY() {
        return -64;
    }

    @Override
    public int getSpawnHeight(LevelHeightAccessor level) {
        return settings.surface() + 1;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState randomState) {
        return ground().height(x, z, type, level.getMinY());
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState randomState) {
        return new NoiseColumn(level.getMinY(), ground().column(x, z, level.getMinY(), level.getHeight()));
    }

    @Override
    public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feetPos) {
        Ground g = ground;
        if (g == null) {
            return;
        }
        var area = g.terrain.area(feetPos.getX(), feetPos.getZ(), 1, 1);
        Prototype tile = g.terrain.tiles.get(area.tileIndex(feetPos.getX(), feetPos.getZ()));
        result.add(String.format(Locale.ROOT, "Nauvis %s (%s) elevation %.1f cliff level %d, map seed %d", tile.title(),
                tile.name(), area.elevation(feetPos.getX(), feetPos.getZ()),
                area.cliffLevel(feetPos.getX(), feetPos.getZ()), g.mapSeed));
    }
}
