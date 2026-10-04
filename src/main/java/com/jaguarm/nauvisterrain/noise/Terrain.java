package com.jaguarm.nauvisterrain.noise;

import com.jaguarm.nauvisterrain.noise.MapSettings.Point;
import com.jaguarm.nauvisterrain.noise.NoiseProgram.Placement;
import com.jaguarm.nauvisterrain.noise.NoiseProgram.Prototype;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * What Factorio's map generator puts on the map (docs/ARCHITECTURE.md, autoplace): the tile at each
 * position, chosen and corrected as Factorio does, the trees, rocks, ores and fish its entity pass
 * places, and the decoratives. The program runs once per chunk of 32 by 32 tiles, kept for the
 * areas that share it. Safe to use from several threads at once.
 */
public final class Terrain {
    public static final int BLOCK = 32;
    private static final int KEPT = 2048;
    /** Factorio's area made with the map, in chunks: its chunks are made in Factorio's own order, before any other. */
    private static final int START_LOW = -7;
    private static final int START_HIGH = 6;

    /** A tree, rock, ore, fish or decorative on the tile at x, y; richness is an ore's amount. */
    public record Placed(Prototype prototype, int x, int y, float richness) {
    }

    /** A chunk's noise: its tiles as the probabilities choose them, its climate, and what its entities and decoratives read. */
    private record Block(byte[] tiles, float[] elevation, float[] cliffElevation, float[] cliffiness, float[][] probability,
                         float[][] richness, float[][] decoration) {
    }

    /** A chunk's decorative additions: the decorative, its tile, how many of its attempts came up, and where in the tile. */
    private record Decorations(int[] thing, int[] tx, int[] ty, int[] count, int[] x, int[] y) {
    }

    /** A chunk's entity additions in the order Factorio queues them, by the tile each stands on, and which its apply makes. */
    private static final class Chunk {
        final int cx;
        final int cy;
        final int n;
        final int[] thing;
        final int[] x;
        final int[] y;
        final float[] richness;
        final int[] head = new int[BLOCK * BLOCK];
        final int[] next;
        final Decorations decorations;
        volatile boolean[] placed;

        Chunk(int cx, int cy, int[] thing, int[] x, int[] y, float[] richness, Decorations decorations) {
            this.decorations = decorations;
            this.cx = cx;
            this.cy = cy;
            this.n = thing.length;
            this.thing = thing;
            this.x = x;
            this.y = y;
            this.richness = richness;
            Arrays.fill(head, -1);
            next = new int[n];
            for (int k = n - 1; k >= 0; k--) {
                int cell = ((y[k] >> 8) - cy * BLOCK) * BLOCK + (x[k] >> 8) - cx * BLOCK;
                next[k] = head[cell];
                head[cell] = k;
            }
        }
    }

    public final Evaluator evaluator;
    public final List<Prototype> tiles;
    private final int[] tileOrder;
    private final int defaultTile;
    private final TileCorrection correction;
    /** Factorio's entity autoplacers: by order, then name; things of one order compete for a tile. */
    private final List<Prototype> entities;
    private final boolean[] groupEnd;
    private final int[][] generatorBox;
    private final int[][] collisionBox;
    private final boolean[][] meets;
    private final boolean[][] entityOnTile;
    private final boolean[] tilesOnly;
    private final boolean[] resource;
    private final boolean[] tree;
    private final boolean[] removesDecorations;
    /** How far an entity's boxes reach from its position, in 1/256 tiles. */
    private final int extent;
    /** Factorio's decorative autoplacers: by order, then name; decoratives of one order compete for a tile. */
    private final List<Prototype> decoratives;
    private final boolean[] decorationGroupEnd;
    private final int[][] decorationBox;
    private final boolean[][] decorationMeetsEntity;
    private final boolean[][] decorationOnTile;
    private final boolean[] removable;
    private final int[] nameHash;
    private final String[] roots;
    private final int climateRoot;
    private final int entityRoot;
    private final int decorationRoot;
    private final int[][] starts;
    private final Map<Long, Block> blocks = new ConcurrentHashMap<>();
    private final Map<Long, byte[]> corrected = new ConcurrentHashMap<>();
    private final Map<Long, Chunk> chunks = new ConcurrentHashMap<>();
    private final Map<Long, Chunk> startChunks = new ConcurrentHashMap<>();
    private final Object start = new Object();
    private volatile Map<Long, byte[]> startTiles;
    private volatile boolean startEntities;

    public Terrain(Evaluator evaluator) {
        this.evaluator = evaluator;
        NoiseProgram program = evaluator.program;
        tiles = program.prototypes.stream().filter(p -> p.kind().equals("tile")).toList();
        if (tiles.size() > Byte.MAX_VALUE) {
            throw new IllegalStateException("more tiles than a byte holds");
        }
        Integer[] order = new Integer[tiles.size()];
        for (int t = 0; t < order.length; t++) {
            order[t] = t;
        }
        Arrays.sort(order, Comparator.comparing((Integer t) -> tiles.get(t).order()).thenComparing(t -> tiles.get(t).name()));
        tileOrder = Arrays.stream(order).mapToInt(Integer::intValue).toArray();
        int grass = 0;
        for (int t = 0; t < tiles.size(); t++) {
            grass = tiles.get(t).name().equals("grass-1") ? t : grass;
        }
        defaultTile = grass;
        correction = new TileCorrection(tiles);

        entities = program.prototypes.stream().filter(p -> p.kind().equals("entity"))
                .sorted(Comparator.comparing(Prototype::order).thenComparing(Prototype::name)).toList();
        int e = entities.size();
        groupEnd = new boolean[e];
        generatorBox = new int[e][];
        collisionBox = new int[e][];
        meets = new boolean[e][e];
        entityOnTile = new boolean[e][tiles.size()];
        tilesOnly = new boolean[e];
        resource = new boolean[e];
        tree = new boolean[e];
        removesDecorations = new boolean[e];
        int far = 0;
        for (int k = 0; k < e; k++) {
            Prototype p = entities.get(k);
            groupEnd[k] = k + 1 == e || !entities.get(k + 1).order().equals(p.order());
            generatorBox[k] = fixed(p.placement().mapGeneratorBox());
            collisionBox[k] = fixed(p.collisionBox() == null ? new double[4] : p.collisionBox());
            tilesOnly[k] = p.collisionMask().tilesOnly();
            resource[k] = p.type().equals("resource");
            tree[k] = p.type().equals("tree");
            removesDecorations[k] = p.placement().removesDecoratives();
            for (int t = 0; t < tiles.size(); t++) {
                entityOnTile[k][t] = !p.collisionMask().blockedBy(tiles.get(t).collisionMask());
            }
            for (int v : generatorBox[k]) {
                far = Math.max(far, Math.abs(v));
            }
            for (int v : collisionBox[k]) {
                far = Math.max(far, Math.abs(v));
            }
        }
        for (int a = 0; a < e; a++) {
            for (int b = 0; b < e; b++) {
                meets[a][b] = !tilesOnly[a] && !tilesOnly[b]
                        && entities.get(a).collisionMask().blockedBy(entities.get(b).collisionMask());
            }
        }
        extent = far + 256;

        decoratives = program.prototypes.stream().filter(p -> p.kind().equals("decorative"))
                .sorted(Comparator.comparing(Prototype::order).thenComparing(Prototype::name)).toList();
        int d = decoratives.size();
        decorationGroupEnd = new boolean[d];
        decorationBox = new int[d][];
        decorationMeetsEntity = new boolean[d][e];
        decorationOnTile = new boolean[d][tiles.size()];
        removable = new boolean[d];
        nameHash = new int[d];
        for (int k = 0; k < d; k++) {
            Prototype p = decoratives.get(k);
            decorationGroupEnd[k] = k + 1 == d || !decoratives.get(k + 1).order().equals(p.order());
            decorationBox[k] = fixed(p.collisionBox() == null ? new double[4] : p.collisionBox());
            removable[k] = p.placement().removable();
            CRC32 crc = new CRC32();
            crc.update(p.name().getBytes(StandardCharsets.UTF_8));
            nameHash[k] = (int) crc.getValue();
            for (int t = 0; t < tiles.size(); t++) {
                decorationOnTile[k][t] = !p.collisionMask().blockedBy(tiles.get(t).collisionMask());
            }
            for (int j = 0; j < e; j++) {
                decorationMeetsEntity[k][j] = !p.collisionMask().tilesOnly() && !tilesOnly[j]
                        && p.collisionMask().blockedBy(entities.get(j).collisionMask());
            }
        }

        List<String> names = new ArrayList<>();
        tiles.forEach(t -> names.add(t.probabilityRoot()));
        climateRoot = names.size();
        names.add("elevation");
        names.add("cliff_elevation");
        names.add("cliffiness");
        entityRoot = names.size();
        for (Prototype p : entities) {
            names.add(p.probabilityRoot());
            names.add(p.hasRichness() ? p.richnessRoot() : p.probabilityRoot());
        }
        decorationRoot = names.size();
        decoratives.forEach(p -> names.add(p.probabilityRoot()));
        roots = names.toArray(new String[0]);

        List<Point> points = evaluator.settings.startingPositions();
        starts = new int[points.size()][];
        for (int i = 0; i < starts.length; i++) {
            starts[i] = new int[]{(int) (points.get(i).x() * 256), (int) (points.get(i).y() * 256)};
        }
    }

    /** The tiles and the things placed on them over an area of the map. */
    public Area area(int x0, int y0, int width, int height) {
        return new Area(x0, y0, width, height);
    }

    /** The tiles, climate and things over one rectangle of the map; the things are placed when first asked for. */
    public final class Area {
        public final int x0;
        public final int y0;
        public final int width;
        public final int height;
        private List<Placed> standing;
        private List<Placed> decorations;
        private final int cx0;
        private final int cy0;
        private final int cw;
        private final byte[][] tileCache;
        private final Block[] blockCache;

        private Area(int x0, int y0, int width, int height) {
            this.x0 = x0;
            this.y0 = y0;
            this.width = width;
            this.height = height;
            cx0 = Math.floorDiv(x0, BLOCK) - 1;
            cy0 = Math.floorDiv(y0, BLOCK) - 1;
            cw = Math.floorDiv(x0 + width - 1, BLOCK) - cx0 + 2;
            int ch = Math.floorDiv(y0 + height - 1, BLOCK) - cy0 + 2;
            tileCache = new byte[cw * ch][];
            blockCache = new Block[cw * ch];
        }

        /** The trees, rocks, ores and fish that stand in the area, in the order Factorio makes them. */
        public List<Placed> entities() {
            if (standing == null) {
                standing = standing(this);
            }
            return standing;
        }

        /** The decoratives that stand in the area, in the order Factorio makes them. */
        public List<Placed> decoratives() {
            if (decorations == null) {
                decorations = decorations(this);
            }
            return decorations;
        }

        public Prototype tile(int x, int y) {
            return tiles.get(tileIndex(x, y));
        }

        public float elevation(int x, int y) {
            return block(x, y).elevation[inBlock(x, y)];
        }

        public float cliffElevation(int x, int y) {
            return block(x, y).cliffElevation[inBlock(x, y)];
        }

        public float cliffiness(int x, int y) {
            return block(x, y).cliffiness[inBlock(x, y)];
        }

        /** Which of the levels `cliff_elevation_0 + k · cliff_elevation_interval` a position is above. */
        public int cliffLevel(int x, int y) {
            MapSettings s = evaluator.settings;
            return (int) Math.floor((cliffElevation(x, y) - s.cliffElevation0()) / s.cliffElevationInterval());
        }

        /** The tile at a position, as its index in {@link #tiles}. */
        public int tileIndex(int x, int y) {
            int cx = Math.floorDiv(x, BLOCK), cy = Math.floorDiv(y, BLOCK);
            int at = slot(cx, cy);
            if (at < 0) {
                return finalTiles(cx, cy)[inBlock(x, y)];
            }
            byte[] t = tileCache[at];
            if (t == null) {
                t = finalTiles(cx, cy);
                tileCache[at] = t;
            }
            return t[inBlock(x, y)];
        }

        private Block block(int x, int y) {
            int cx = Math.floorDiv(x, BLOCK), cy = Math.floorDiv(y, BLOCK);
            int at = slot(cx, cy);
            if (at < 0) {
                return Terrain.this.block(cx, cy);
            }
            Block b = blockCache[at];
            if (b == null) {
                b = Terrain.this.block(cx, cy);
                blockCache[at] = b;
            }
            return b;
        }

        private int slot(int cx, int cy) {
            int i = cx - cx0, j = cy - cy0;
            if (i < 0 || j < 0 || i >= cw || j * cw + i >= tileCache.length) {
                return -1;
            }
            return j * cw + i;
        }
    }

    // -- tiles ----------------------------------------------------------------------------------

    /**
     * A chunk's tiles after Factorio's correction. In and around the area made with the map they
     * are what Factorio makes it in its own order; elsewhere the chunk's eight neighbours and
     * itself are corrected in turn, from their chosen tiles.
     */
    private byte[] finalTiles(int cx, int cy) {
        long key = key(cx, cy);
        byte[] t = corrected.get(key);
        if (t == null) {
            t = within(cx, cy, 1) ? startTiles().get(key) : correctedAround(cx, cy);
            trim(corrected);
            byte[] raced = corrected.putIfAbsent(key, t);
            t = raced == null ? t : raced;
        }
        return t;
    }

    private byte[] correctedAround(int cx, int cy) {
        boolean nearStart = false;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                nearStart |= within(cx + dx, cy + dy, 3);
            }
        }
        Map<Long, byte[]> made = nearStart ? startTiles() : Map.of();
        Map<Long, byte[]> area = new HashMap<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                long key = key(cx + dx, cy + dy);
                byte[] from = made.get(key);
                area.put(key, (from == null ? block(cx + dx, cy + dy).tiles : from).clone());
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (!within(cx + dx, cy + dy, 2)) {
                    correct(area, cx + dx, cy + dy);
                }
            }
        }
        return area.get(key(cx, cy));
    }

    /**
     * The tiles of the area made with the map, as Factorio makes them: each of its chunks asked for
     * in turn, which asks for the chunks around it, theirs chosen and then corrected in the order asked.
     */
    private Map<Long, byte[]> startTiles() {
        Map<Long, byte[]> made = startTiles;
        if (made != null) {
            return made;
        }
        synchronized (start) {
            if (startTiles != null) {
                return startTiles;
            }
            Map<Integer, List<Long>> queues = new HashMap<>();
            Set<String> asked = new HashSet<>();
            for (int cx = START_LOW; cx <= START_HIGH; cx++) {
                for (int cy = START_LOW; cy <= START_HIGH; cy++) {
                    request(cx, cy, 50, queues, asked);
                }
            }
            Map<Long, byte[]> area = new HashMap<>();
            for (long key : queues.get(20)) {
                area.put(key, block((int) (key >> 32), (int) key).tiles.clone());
            }
            for (long key : queues.get(30)) {
                correct(area, (int) (key >> 32), (int) key);
            }
            startTiles = area;
            return area;
        }
    }

    /** MapGenerationManager::request: the chunks around first, a status below, then the chunk itself. */
    private static void request(int cx, int cy, int status, Map<Integer, List<Long>> queues, Set<String> asked) {
        if (!asked.add(cx + "," + cy + "," + status)) {
            return;
        }
        if (status > 20) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    request(cx + dx, cy + dy, status - 10, queues, asked);
                }
            }
        }
        queues.computeIfAbsent(status, s -> new ArrayList<>()).add(key(cx, cy));
    }

    /** TileCorrectionMapGenerationTask::apply: the chunk and its neighbours copied, corrected, the listed tiles written back. */
    private void correct(Map<Long, byte[]> chunks, int cx, int cy) {
        int size = TileCorrection.AREA;
        byte[] area = new byte[size * size];
        byte[][] parts = new byte[9][];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                byte[] part = chunks.get(key(cx + i - 1, cy + j - 1));
                parts[i * 3 + j] = part;
                for (int x = 0; x < BLOCK; x++) {
                    for (int y = 0; y < BLOCK; y++) {
                        area[(i * BLOCK + x) * size + j * BLOCK + y] = part[y * BLOCK + x];
                    }
                }
            }
        }
        for (int p : correction.correct(area)) {
            int x = p / size, y = p % size;
            parts[(x / BLOCK) * 3 + y / BLOCK][(y % BLOCK) * BLOCK + x % BLOCK] = area[p];
        }
    }

    /** Whether a chunk is within the given number of chunks of the area made with the map. */
    private static boolean within(int cx, int cy, int ring) {
        return cx >= START_LOW - ring && cx <= START_HIGH + ring && cy >= START_LOW - ring && cy <= START_HIGH + ring;
    }

    // -- entities -------------------------------------------------------------------------------

    /** EntityMapGenerationTask::generateEntities: a chunk's additions, before any other entity is looked at. */
    private Chunk chunk(int cx, int cy) {
        long key = key(cx, cy);
        Map<Long, Chunk> cache = within(cx, cy, 0) ? startChunks : chunks;
        Chunk c = cache.get(key);
        if (c == null) {
            c = additions(cx, cy);
            if (cache == chunks) {
                trim(chunks);
            }
            Chunk raced = cache.putIfAbsent(key, c);
            c = raced == null ? c : raced;
        }
        return c;
    }

    private Chunk additions(int cx, int cy) {
        Block block = block(cx, cy);
        byte[][] around = new byte[9][];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                around[i * 3 + j] = finalTiles(cx + i - 1, cy + j - 1);
            }
        }
        byte[] own = around[4];
        RandomGenerator random = new RandomGenerator((cx * 0x1EEF + cy * 0x1EE3 + 0x3FBE2C) & 0xFFFFFFFFL);
        int n = BLOCK * BLOCK;
        float[] best = new float[n];
        float[] bestRichness = new float[n];
        int[] chosen = new int[n];
        Arrays.fill(best, Float.NEGATIVE_INFINITY);
        Arrays.fill(chosen, -1);
        List<int[]> added = new ArrayList<>();
        List<Float> addedRichness = new ArrayList<>();
        int x0 = cx * BLOCK, y0 = cy * BLOCK;
        for (int k = 0; k < entities.size(); k++) {
            float[] probability = block.probability[k];
            float[] richness = block.richness[k];
            for (int i = n - 1; i >= 0; i--) {
                if (!entityOnTile[k][own[i]]) {
                    continue;
                }
                float p = probability[i];
                if (p > best[i] || (p == best[i] && richness[i] > bestRichness[i])) {
                    best[i] = p;
                    chosen[i] = k;
                    bestRichness[i] = richness[i];
                }
            }
            if (!groupEnd[k]) {
                continue;
            }
            for (int i = n - 1; i >= 0; i--) {
                int c = chosen[i];
                if (c >= 0) {
                    Placement placement = entities.get(c).placement();
                    int tx = x0 + (i & 31), ty = y0 + (i >> 5);
                    double p = best[i];
                    float r = bestRichness[i];
                    for (int attempt = 0; attempt < entities.get(c).placementDensity(); attempt++) {
                        if (!(p > random.next() * 0x1p-32)) {
                            continue;
                        }
                        int x = tx << 8, y = ty << 8;
                        if (resource[c]) {
                            x += 128;
                            y += 128;
                            if (!(r > 0)) {
                                continue;
                            }
                        } else if (placement.offGrid()) {
                            double u1 = random.next() * 0x1p-32;
                            double u2 = random.next() * 0x1p-32;
                            x = (x - (int) (u1 * -256.0)) & ~15;
                            y = (y - (int) (u2 * -256.0)) & ~15;
                        } else {
                            x = (((x + (placement.tileWidth() % 2 == 0 ? 128 : 0)) >> 8) << 8) + (placement.tileWidth() % 2 == 1 ? 128 : 0);
                            y = (((y + (placement.tileHeight() % 2 == 0 ? 128 : 0)) >> 8) << 8) + (placement.tileHeight() % 2 == 1 ? 128 : 0);
                        }
                        if (!onTiles(generatorBox[c], entityOnTile[c], x, y, around, x0 - BLOCK, y0 - BLOCK)) {
                            added.add(new int[]{c, x, y});
                            addedRichness.add(r);
                        }
                    }
                }
                best[i] = Float.NEGATIVE_INFINITY;
                chosen[i] = -1;
            }
        }
        Decorations decorations = decorations(block, own, around, cx, cy);
        int m = added.size();
        int[] thing = new int[m];
        int[] xs = new int[m];
        int[] ys = new int[m];
        float[] richness = new float[m];
        for (int k = 0; k < m; k++) {
            thing[k] = added.get(k)[0];
            xs[k] = added.get(k)[1];
            ys[k] = added.get(k)[2];
            richness[k] = addedRichness.get(k);
        }
        return new Chunk(cx, cy, thing, xs, ys, richness, decorations);
    }

    /**
     * EntityMapGenerationTask::generateDecoratives: from a generator of its own, each tile's most
     * probable decorative of each order, with as many of its attempts as come up, where in the tile
     * a hash of the tile and the name puts it, unless a tile under it keeps it off.
     */
    private Decorations decorations(Block block, byte[] own, byte[][] around, int cx, int cy) {
        RandomGenerator random = new RandomGenerator((cx * 0x1EEF + cy * 0x1EE3 + 0x3FBE2C) & 0xFFFFFFFFL);
        int n = BLOCK * BLOCK;
        float[] best = new float[n];
        int[] who = new int[n];
        Arrays.fill(best, Float.NEGATIVE_INFINITY);
        Arrays.fill(who, -1);
        List<int[]> added = new ArrayList<>();
        int x0 = cx * BLOCK, y0 = cy * BLOCK;
        for (int k = 0; k < decoratives.size(); k++) {
            float[] probability = block.decoration[k];
            for (int i = n - 1; i >= 0; i--) {
                if (decorationOnTile[k][own[i]] && probability[i] > best[i]) {
                    best[i] = probability[i];
                    who[i] = k;
                }
            }
            if (!decorationGroupEnd[k]) {
                continue;
            }
            for (int i = n - 1; i >= 0; i--) {
                int w = who[i];
                if (w >= 0) {
                    int count = 0;
                    double p = best[i];
                    for (int attempt = 0; attempt < decoratives.get(w).placementDensity(); attempt++) {
                        if (p > random.next() * 0x1p-32) {
                            count++;
                        }
                    }
                    if (count > 0) {
                        int tx = x0 + (i & 31), ty = y0 + (i >> 5);
                        int seed = jenkins(tx) + jenkins(ty) + nameHash[w];
                        int x = (tx << 8) + (jenkins(seed) >>> 24), y = (ty << 8) + (jenkins(seed + 1) >>> 24);
                        if (!onTiles(decorationBox[w], decorationOnTile[w], x, y, around, x0 - BLOCK, y0 - BLOCK)) {
                            added.add(new int[]{w, tx, ty, count, x, y});
                        }
                    }
                }
                best[i] = Float.NEGATIVE_INFINITY;
                who[i] = -1;
            }
        }
        int m = added.size();
        int[][] columns = new int[6][m];
        for (int k = 0; k < m; k++) {
            for (int c = 0; c < 6; c++) {
                columns[c][k] = added.get(k)[c];
            }
        }
        return new Decorations(columns[0], columns[1], columns[2], columns[3], columns[4], columns[5]);
    }

    /** DecorativePrototype's position hash: Bob Jenkins' 32-bit integer hash. */
    private static int jenkins(int a) {
        a = (a + 0x7ED55D16) + (a << 12);
        a = (a ^ 0xC761C23C) ^ (a >>> 19);
        a = (a + 0x165667B1) + (a << 5);
        a = (a + 0xD3A2646C) ^ (a << 9);
        a = (a + 0xFD7046C5) + (a << 3);
        a = (a ^ 0xB55A4F09) ^ (a >>> 16);
        return a;
    }

    /** EntityMapGenerationTask::wouldCollide: whether a tile under the box, both ends counted, keeps it off. */
    private static boolean onTiles(int[] box, boolean[] allowedOn, int x, int y, byte[][] around, int ax0, int ay0) {
        int l = ((x + box[0]) >> 8) - ax0, t = ((y + box[1]) >> 8) - ay0;
        int r = ((x + box[2]) >> 8) - ax0, b = ((y + box[3]) >> 8) - ay0;
        int size = 3 * BLOCK;
        if (l < 0 || t < 0 || r >= size || b >= size) {
            return true;
        }
        for (int i = l; i <= r; i++) {
            for (int j = t; j <= b; j++) {
                if (!allowedOn[around[(i / BLOCK) * 3 + j / BLOCK][(j % BLOCK) * BLOCK + i % BLOCK]]) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Which of a chunk's additions its apply makes. The area made with the map is applied in
     * Factorio's order, column by column; outside it, a chunk is applied after the neighbours whose
     * place in the 2 by 2 pattern of chunks comes earlier.
     */
    private boolean[] placed(Chunk c) {
        boolean[] done = c.placed;
        if (done != null) {
            return done;
        }
        if (within(c.cx, c.cy, 0)) {
            synchronized (start) {
                if (!startEntities) {
                    for (int cx = START_LOW; cx <= START_HIGH; cx++) {
                        for (int cy = START_LOW; cy <= START_HIGH; cy++) {
                            Chunk s = chunk(cx, cy);
                            s.placed = apply(s);
                        }
                    }
                    startEntities = true;
                }
            }
            return c.placed;
        }
        done = apply(c);
        c.placed = done;
        return done;
    }

    /** EntityMapGenerationTask::applyEntities: each addition in order, unless it meets an entity already there. */
    private boolean[] apply(Chunk c) {
        boolean[] placed = new boolean[c.n];
        Chunk[] earlier = new Chunk[8];
        boolean[][] earlierPlaced = new boolean[8][];
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if ((dx != 0 || dy != 0) && rank(c.cx + dx, c.cy + dy) < rank(c.cx, c.cy)) {
                    earlier[count] = chunk(c.cx + dx, c.cy + dy);
                    earlierPlaced[count] = placed(earlier[count]);
                    count++;
                }
            }
        }
        for (int k = 0; k < c.n; k++) {
            int t = c.thing[k];
            if (tilesOnly[t]) {
                placed[k] = true;
                continue;
            }
            int[] g = generatorBox[t];
            int l = c.x[k] + g[0], top = c.y[k] + g[1], r = c.x[k] + g[2], b = c.y[k] + g[3];
            boolean blocked = meetsAny(c, placed, k, t, l, top, r, b, null);
            for (int i = 0; i < count && !blocked; i++) {
                blocked = meetsAny(earlier[i], earlierPlaced[i], earlier[i].n, t, l, top, r, b, c);
            }
            if (!blocked && (!resource[t] || hasAmount(c.richness[k]))) {
                placed[k] = true;
            }
        }
        return placed;
    }

    /**
     * Whether one of a chunk's made entities before `before` meets the box: shares a layer and
     * touches it, as Factorio's boxes collide, and, made before `applying`, still stands.
     */
    private boolean meetsAny(Chunk d, boolean[] placed, int before, int thing, int l, int t, int r, int b, Chunk applying) {
        int[] cells = cells(d, l, t, r, b);
        for (int cy = cells[1]; cy <= cells[3]; cy++) {
            for (int cx = cells[0]; cx <= cells[2]; cx++) {
                for (int j = d.head[cy * BLOCK + cx]; j >= 0; j = d.next[j]) {
                    int o = d.thing[j];
                    if (j < before && placed[j] && meets[thing][o] && touches(l, t, r, b, d, j)
                            && (applying == null || !tree[o] || !removed(d, j, applying))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The cells of a chunk, by the tile an addition stands on, whose additions could reach a box. */
    private int[] cells(Chunk d, int l, int t, int r, int b) {
        int x0 = d.cx * BLOCK, y0 = d.cy * BLOCK;
        return new int[]{Math.max(0, ((l - extent) >> 8) - x0), Math.max(0, ((t - extent) >> 8) - y0),
                Math.min(BLOCK - 1, ((r + extent) >> 8) - x0), Math.min(BLOCK - 1, ((b + extent) >> 8) - y0)};
    }

    private boolean touches(int l, int t, int r, int b, Chunk d, int j) {
        int[] c = collisionBox[d.thing[j]];
        int x = d.x[j], y = d.y[j];
        return l <= x + c[2] && t <= y + c[3] && r >= x + c[0] && b >= y + c[1];
    }

    /**
     * ResourceEntity::postSetup: whether an ore made after the tree and, when `before` is given,
     * before that chunk is applied, removed it. The chance falls with the ore's distance from the
     * nearest start; Factorio draws it from the game's own generator, so the draw here is a hash.
     */
    private boolean removed(Chunk d, int tree, Chunk before) {
        int treeRank = rank(d.cx, d.cy);
        int limit = before == null ? Integer.MAX_VALUE : rank(before.cx, before.cy);
        int[] c = collisionBox[d.thing[tree]];
        int l = d.x[tree] + c[0], t = d.y[tree] + c[1], r = d.x[tree] + c[2], b = d.y[tree] + c[3];
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int ox = d.cx + dx, oy = d.cy + dy;
                int rank = rank(ox, oy);
                boolean same = dx == 0 && dy == 0;
                if (!same && (rank <= treeRank || rank >= limit)) {
                    continue;
                }
                Chunk o = chunk(ox, oy);
                int[] cells = cells(o, l - 256, t - 256, r + 256, b + 256);
                for (int cy = cells[1]; cy <= cells[3]; cy++) {
                    for (int cx = cells[0]; cx <= cells[2]; cx++) {
                        for (int j = o.head[cy * BLOCK + cx]; j >= 0; j = o.next[j]) {
                            if ((!same || j > tree) && resource[o.thing[j]] && hasAmount(o.richness[j])
                                    && removes(o, j, l, t, r, b, d.x[tree], d.y[tree])) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean removes(Chunk o, int ore, int l, int t, int r, int b, int treeX, int treeY) {
        int ox = o.x[ore], oy = o.y[ore];
        int tl = (ox >> 8) << 8, tt = (oy >> 8) << 8;
        if (!(tl <= r && tt <= b && tl + 256 >= l && tt + 256 >= t)) {
            return false;
        }
        Placement placement = entities.get(o.thing[ore]).placement();
        double d2 = Double.POSITIVE_INFINITY;
        for (int[] s : starts) {
            double dx = (s[0] - ox) / 256.0, dy = (s[1] - oy) / 256.0;
            d2 = Math.min(d2, dx * dx + dy * dy);
        }
        double max2 = placement.treeRemovalDistance() * placement.treeRemovalDistance();
        if (placement.treeRemovalProbability() == 0 || max2 < d2) {
            return false;
        }
        double chance = (1 - d2 / max2) * placement.treeRemovalProbability();
        return chance >= Hash.unit(Hash.of(evaluator.settings.seed(), key(ox, oy), key(treeX, treeY), 0x7EE));
    }

    /** Factorio's order of applying chunks: the area made with the map column by column, then the others. */
    private static int rank(int cx, int cy) {
        if (within(cx, cy, 0)) {
            return (cx - START_LOW) * (START_HIGH - START_LOW + 1) + cy - START_LOW;
        }
        return (START_HIGH - START_LOW + 1) * (START_HIGH - START_LOW + 1) + (cx & 1) + 2 * (cy & 1);
    }

    /** ResourceEntityPrototype::createInternal: an amount of at least 1, the richness truncated. */
    private static boolean hasAmount(float richness) {
        return Float.isInfinite(richness) || (long) richness >= 1;
    }

    private List<Placed> standing(Area area) {
        List<Placed> out = new ArrayList<>();
        for (int cx = Math.floorDiv(area.x0, BLOCK); cx <= Math.floorDiv(area.x0 + area.width - 1, BLOCK); cx++) {
            for (int cy = Math.floorDiv(area.y0, BLOCK); cy <= Math.floorDiv(area.y0 + area.height - 1, BLOCK); cy++) {
                Chunk c = chunk(cx, cy);
                boolean[] placed = placed(c);
                for (int k = 0; k < c.n; k++) {
                    int x = c.x[k] >> 8, y = c.y[k] >> 8;
                    if (placed[k] && x >= area.x0 && x < area.x0 + area.width && y >= area.y0 && y < area.y0 + area.height
                            && (!tree[c.thing[k]] || !removed(c, k, null))) {
                        out.add(new Placed(entities.get(c.thing[k]), x, y, c.richness[k]));
                    }
                }
            }
        }
        return out;
    }

    // -- decoratives ----------------------------------------------------------------------------

    /**
     * EntityMapGenerationTask::apply for decoratives: a chunk's decoratives are made before its
     * entities, kept off by the entities of chunks applied earlier, and those rocks remove go under
     * the rocks of the chunk and of chunks applied later.
     */
    private List<Placed> decorations(Area area) {
        List<Placed> out = new ArrayList<>();
        for (int cx = Math.floorDiv(area.x0, BLOCK); cx <= Math.floorDiv(area.x0 + area.width - 1, BLOCK); cx++) {
            for (int cy = Math.floorDiv(area.y0, BLOCK); cy <= Math.floorDiv(area.y0 + area.height - 1, BLOCK); cy++) {
                Chunk c = chunk(cx, cy);
                Decorations d = c.decorations;
                for (int k = 0; k < d.thing().length; k++) {
                    int tx = d.tx()[k], ty = d.ty()[k], t = d.thing()[k];
                    if (tx < area.x0 || tx >= area.x0 + area.width || ty < area.y0 || ty >= area.y0 + area.height) {
                        continue;
                    }
                    int[] b = decorationBox[t];
                    int l = d.x()[k] + b[0], top = d.y()[k] + b[1], r = d.x()[k] + b[2], bottom = d.y()[k] + b[3];
                    if (!keptOff(c, t, l, top, r, bottom) && !(removable[t] && underRock(c, l, top, r, bottom))) {
                        out.add(new Placed(decoratives.get(t), tx, ty, d.count()[k]));
                    }
                }
            }
        }
        return out;
    }

    /** Whether an entity of a chunk applied before this one, still standing then, shares a layer with the box and touches it. */
    private boolean keptOff(Chunk c, int thing, int l, int t, int r, int b) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if ((dx == 0 && dy == 0) || rank(c.cx + dx, c.cy + dy) >= rank(c.cx, c.cy)) {
                    continue;
                }
                Chunk d = chunk(c.cx + dx, c.cy + dy);
                int[] cells = cells(d, l, t, r, b);
                if (cells[0] > cells[2] || cells[1] > cells[3]) {
                    continue;
                }
                boolean[] placed = placed(d);
                for (int y = cells[1]; y <= cells[3]; y++) {
                    for (int x = cells[0]; x <= cells[2]; x++) {
                        for (int j = d.head[y * BLOCK + x]; j >= 0; j = d.next[j]) {
                            if (placed[j] && decorationMeetsEntity[thing][d.thing[j]] && touches(l, t, r, b, d, j)
                                    && (!tree[d.thing[j]] || !removed(d, j, c))) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Entity::postSetup: whether a rock of this chunk, or of one applied after it, touches the box. */
    private boolean underRock(Chunk c, int l, int t, int r, int b) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if ((dx != 0 || dy != 0) && rank(c.cx + dx, c.cy + dy) <= rank(c.cx, c.cy)) {
                    continue;
                }
                Chunk d = chunk(c.cx + dx, c.cy + dy);
                int[] cells = cells(d, l, t, r, b);
                if (cells[0] > cells[2] || cells[1] > cells[3]) {
                    continue;
                }
                boolean[] placed = placed(d);
                for (int y = cells[1]; y <= cells[3]; y++) {
                    for (int x = cells[0]; x <= cells[2]; x++) {
                        for (int j = d.head[y * BLOCK + x]; j >= 0; j = d.next[j]) {
                            if (placed[j] && removesDecorations[d.thing[j]] && touches(l, t, r, b, d, j)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    // -- blocks ---------------------------------------------------------------------------------

    private Block block(int bx, int by) {
        long key = key(bx, by);
        Block b = blocks.get(key);
        if (b == null) {
            b = compute(bx, by);
            trim(blocks);
            Block raced = blocks.putIfAbsent(key, b);
            b = raced == null ? b : raced;
        }
        return b;
    }

    /** The program over one chunk as Factorio runs it, its tiles chosen. */
    private Block compute(int bx, int by) {
        int n = BLOCK * BLOCK;
        float[][] v = evaluator.evaluateGrid(roots, bx * BLOCK, by * BLOCK, BLOCK, BLOCK, 1);
        byte[] chosen = new byte[n];
        for (int i = 0; i < n; i++) {
            float best = Float.NEGATIVE_INFINITY;
            int tile = defaultTile;
            for (int t : tileOrder) {
                if (v[t][i] > best) {
                    best = v[t][i];
                    tile = t;
                }
            }
            chosen[i] = (byte) tile;
        }
        float[][] probability = new float[entities.size()][];
        float[][] richness = new float[entities.size()][];
        for (int k = 0; k < entities.size(); k++) {
            probability[k] = v[entityRoot + 2 * k];
            richness[k] = v[entityRoot + 2 * k + 1];
        }

        float[][] decoration = new float[decoratives.size()][];
        for (int k = 0; k < decoratives.size(); k++) {
            decoration[k] = v[decorationRoot + k];
        }
        return new Block(chosen, v[climateRoot], v[climateRoot + 1], v[climateRoot + 2], probability, richness, decoration);
    }

    // -- geometry -------------------------------------------------------------------------------

    private static int[] fixed(double[] box) {
        return new int[]{(int) (box[0] * 256), (int) (box[1] * 256), (int) (box[2] * 256), (int) (box[3] * 256)};
    }

    private static int inBlock(int x, int y) {
        return Math.floorMod(y, BLOCK) * BLOCK + Math.floorMod(x, BLOCK);
    }

    private static long key(int x, int y) {
        return (long) x << 32 | (y & 0xFFFFFFFFL);
    }

    private static void trim(Map<Long, ?> cache) {
        if (cache.size() >= KEPT) {
            Iterator<Long> it = cache.keySet().iterator();
            for (int i = 0; i < KEPT / 2 && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
    }
}
