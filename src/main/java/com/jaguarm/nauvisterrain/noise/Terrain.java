package com.jaguarm.nauvisterrain.noise;

import com.jaguarm.nauvisterrain.noise.NoiseProgram.Prototype;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What Factorio's autoplace puts on the map (docs/ARCHITECTURE.md, autoplace): the tile at each
 * position, and the trees, rocks, ores, fish and decoratives that win their places. The program
 * runs once per block of 32 by 32 tiles, which is kept for the areas that share it. Safe to use
 * from several threads at once.
 */
public final class Terrain {
    public static final int BLOCK = 32;
    private static final int BLOCKS_KEPT = 2048;

    /** A tree, rock, ore, fish or decorative on the tile at x, y; richness is an ore's amount. */
    public record Placed(Prototype prototype, int x, int y, float richness) {
    }

    /**
     * A block's tiles and climate, and its candidates in priority order: the rolls that came up,
     * each a thing that stands unless something earlier is in its way.
     */
    private record Block(byte[] tiles, float[] elevation, float[] cliffElevation, float[] cliffiness,
                         int[] thing, int[] x, int[] y, long[] priority, float[] richness) {
    }

    public final Evaluator evaluator;
    public final List<Prototype> tiles;
    /** Trees, rocks, ores, fish, then decoratives: everything placed on tiles. */
    private final List<Prototype> things;
    private final int[] thingGroup;
    private final String[] roots;
    private final int climateRoot;
    private final int thingRoot;
    private final double[][] boxes;
    private final boolean[][] collide;
    private final boolean[][] blockedByTile;
    /** How far from a thing another can stand and still be in its way, in tiles. */
    private final int[] scan;
    private final int reach;
    /** How far a thing's box reaches past its own tile. */
    private final int tileReach;
    private final Map<Long, Block> blocks = new ConcurrentHashMap<>();

    public Terrain(Evaluator evaluator) {
        this.evaluator = evaluator;
        NoiseProgram program = evaluator.program;
        tiles = program.prototypes.stream().filter(p -> p.kind().equals("tile")).toList();
        if (tiles.size() > Byte.MAX_VALUE) {
            throw new IllegalStateException("more tiles than a byte holds");
        }
        List<Prototype> placed = new ArrayList<>(program.prototypes.stream().filter(p -> !p.kind().equals("tile")).toList());
        // Entities before decoratives, each by order; Factorio places an earlier order first.
        placed.sort(Comparator.comparing((Prototype p) -> p.kind().equals("decorative")).thenComparing(Prototype::order)
                .thenComparing(Prototype::name));
        things = List.copyOf(placed);
        int count = things.size();
        thingGroup = new int[count];
        int group = -1;
        for (int i = 0; i < count; i++) {
            // Of things with the same kind and order, only the most probable is tried on a tile.
            if (i == 0 || !things.get(i).kind().equals(things.get(i - 1).kind())
                    || !things.get(i).order().equals(things.get(i - 1).order())) {
                group++;
            }
            thingGroup[i] = group;
        }

        List<String> names = new ArrayList<>();
        tiles.forEach(t -> names.add(t.probabilityRoot()));
        climateRoot = names.size();
        names.add("elevation");
        names.add("cliff_elevation");
        names.add("cliffiness");
        thingRoot = names.size();
        for (Prototype p : things) {
            names.add(p.probabilityRoot());
            names.add(p.hasRichness() ? p.richnessRoot() : p.probabilityRoot());
        }
        roots = names.toArray(new String[0]);

        boxes = new double[count][];
        collide = new boolean[count][count];
        blockedByTile = new boolean[count][tiles.size()];
        scan = new int[count];
        double widest = 0;
        for (int i = 0; i < count; i++) {
            double[] box = things.get(i).collisionBox();
            boxes[i] = box == null ? new double[4] : box;
            widest = Math.max(widest, extent(boxes[i]));
            for (int t = 0; t < tiles.size(); t++) {
                blockedByTile[i][t] = things.get(i).collisionMask().blockedBy(tiles.get(t).collisionMask());
            }
        }
        int farthest = 0;
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < count; j++) {
                collide[i][j] = things.get(i).collisionMask().collidesWith(things.get(j).collisionMask());
                if (collide[i][j]) {
                    scan[i] = Math.max(scan[i], (int) Math.ceil(extent(boxes[i]) + extent(boxes[j])));
                }
            }
            farthest = Math.max(farthest, scan[i]);
        }
        reach = farthest;
        tileReach = (int) Math.ceil(widest) + 1;
    }

    /** The tiles and the things placed on them over an area of the map. */
    public Area area(int x0, int y0, int width, int height) {
        return new Area(x0, y0, width, height);
    }

    /** The tiles, climate and things over one rectangle of the map. */
    public final class Area {
        public final int x0;
        public final int y0;
        public final int width;
        public final int height;
        public final List<Placed> entities = new ArrayList<>();
        public final List<Placed> decoratives = new ArrayList<>();
        private final int bx0;
        private final int by0;
        private final int bw;
        private final Block[] local;

        private Area(int x0, int y0, int width, int height) {
            this.x0 = x0;
            this.y0 = y0;
            this.width = width;
            this.height = height;
            int margin = reach + tileReach + 1;
            bx0 = Math.floorDiv(x0 - margin, BLOCK);
            by0 = Math.floorDiv(y0 - margin, BLOCK);
            bw = Math.floorDiv(x0 + width - 1 + margin, BLOCK) - bx0 + 1;
            int bh = Math.floorDiv(y0 + height - 1 + margin, BLOCK) - by0 + 1;
            local = new Block[bw * bh];
            place();
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

        private int tileIndex(int x, int y) {
            return block(x, y).tiles[inBlock(x, y)];
        }

        private Block block(int x, int y) {
            int bx = Math.floorDiv(x, BLOCK) - bx0;
            int by = Math.floorDiv(y, BLOCK) - by0;
            if (bx < 0 || by < 0 || bx >= bw || by * bw + bx >= local.length) {
                return Terrain.this.block(Math.floorDiv(x, BLOCK), Math.floorDiv(y, BLOCK));
            }
            Block b = local[by * bw + bx];
            if (b == null) {
                b = Terrain.this.block(bx + bx0, by + by0);
                local[by * bw + bx] = b;
            }
            return b;
        }

        /**
         * Every candidate within reach, then each in the area that stands on tiles it may stand on
         * and meets nothing earlier that could stand there too.
         */
        private void place() {
            int left = x0 - reach;
            int top = y0 - reach;
            int w = width + 2 * reach;
            int h = height + 2 * reach;
            int size = 0;
            int[] thing = new int[256];
            int[] xs = new int[256];
            int[] ys = new int[256];
            long[] priority = new long[256];
            float[] richness = new float[256];
            for (int by = Math.floorDiv(top, BLOCK); by <= Math.floorDiv(top + h - 1, BLOCK); by++) {
                for (int bx = Math.floorDiv(left, BLOCK); bx <= Math.floorDiv(left + w - 1, BLOCK); bx++) {
                    Block b = block(bx * BLOCK, by * BLOCK);
                    for (int i = 0; i < b.thing.length; i++) {
                        int cx = b.x[i] - left;
                        int cy = b.y[i] - top;
                        if (cx < 0 || cy < 0 || cx >= w || cy >= h) {
                            continue;
                        }
                        if (size == thing.length) {
                            int grow = size * 2;
                            thing = Arrays.copyOf(thing, grow);
                            xs = Arrays.copyOf(xs, grow);
                            ys = Arrays.copyOf(ys, grow);
                            priority = Arrays.copyOf(priority, grow);
                            richness = Arrays.copyOf(richness, grow);
                        }
                        thing[size] = b.thing[i];
                        xs[size] = b.x[i];
                        ys[size] = b.y[i];
                        priority[size] = b.priority[i];
                        richness[size] = b.richness[i];
                        size++;
                    }
                }
            }
            int[] head = new int[w * h];
            Arrays.fill(head, -1);
            int[] next = new int[size];
            for (int k = 0; k < size; k++) {
                int cell = (ys[k] - top) * w + (xs[k] - left);
                next[k] = head[cell];
                head[cell] = k;
            }
            byte[] fits = new byte[size];
            List<Integer> standing = new ArrayList<>();
            for (int k = 0; k < size; k++) {
                if (xs[k] < x0 || xs[k] >= x0 + width || ys[k] < y0 || ys[k] >= y0 + height || !fits(k, thing, xs, ys, fits)) {
                    continue;
                }
                if (!blocked(k, thing, xs, ys, priority, fits, head, next, left, top, w, h)) {
                    standing.add(k);
                }
            }
            long[] order = priority;
            standing.sort(Comparator.comparingLong(k -> order[k]));
            for (int k : standing) {
                Prototype p = things.get(thing[k]);
                (p.kind().equals("decorative") ? decoratives : entities).add(new Placed(p, xs[k], ys[k], richness[k]));
            }
        }

        private boolean blocked(int k, int[] thing, int[] xs, int[] ys, long[] priority, byte[] fits,
                                int[] head, int[] next, int left, int top, int w, int h) {
            int t = thing[k];
            int r = scan[t];
            boolean[] meets = collide[t];
            double[] box = boxes[t];
            for (int cy = Math.max(0, ys[k] - r - top); cy <= Math.min(h - 1, ys[k] + r - top); cy++) {
                for (int cx = Math.max(0, xs[k] - r - left); cx <= Math.min(w - 1, xs[k] + r - left); cx++) {
                    for (int o = head[cy * w + cx]; o >= 0; o = next[o]) {
                        if (priority[o] < priority[k] && meets[thing[o]]
                                && overlap(box, xs[k], ys[k], boxes[thing[o]], xs[o], ys[o])
                                && fits(o, thing, xs, ys, fits)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /** Whether a candidate's box touches no tile its layers meet. */
        private boolean fits(int k, int[] thing, int[] xs, int[] ys, byte[] memo) {
            if (memo[k] != 0) {
                return memo[k] > 0;
            }
            double[] box = boxes[thing[k]];
            boolean[] blocked = blockedByTile[thing[k]];
            double cx = xs[k] + 0.5;
            double cy = ys[k] + 0.5;
            int tx0 = (int) Math.floor(cx + box[0]);
            int ty0 = (int) Math.floor(cy + box[1]);
            int tx1 = Math.max(tx0, (int) Math.ceil(cx + box[2]) - 1);
            int ty1 = Math.max(ty0, (int) Math.ceil(cy + box[3]) - 1);
            boolean fits = true;
            for (int x = tx0; x <= tx1 && fits; x++) {
                for (int y = ty0; y <= ty1 && fits; y++) {
                    fits = !blocked[tileIndex(x, y)];
                }
            }
            memo[k] = (byte) (fits ? 1 : -1);
            return fits;
        }
    }

    // -- blocks -------------------------------------------------------------------------------

    private Block block(int bx, int by) {
        long key = (long) bx << 32 | (by & 0xFFFFFFFFL);
        Block b = blocks.get(key);
        if (b == null) {
            b = compute(bx, by);
            if (blocks.size() >= BLOCKS_KEPT) {
                Iterator<Long> it = blocks.keySet().iterator();
                for (int i = 0; i < BLOCKS_KEPT / 2 && it.hasNext(); i++) {
                    it.next();
                    it.remove();
                }
            }
            Block raced = blocks.putIfAbsent(key, b);
            if (raced != null) {
                b = raced;
            }
        }
        return b;
    }

    /** The program over one block, its tiles chosen and its things rolled for. */
    private Block compute(int bx, int by) {
        int n = BLOCK * BLOCK;
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = bx * BLOCK + i % BLOCK;
            ys[i] = by * BLOCK + i / BLOCK;
        }
        float[][] v = evaluator.evaluate(roots, xs, ys);
        byte[] tileOf = new byte[n];
        for (int i = 0; i < n; i++) {
            int best = 0;
            for (int t = 1; t < tiles.size(); t++) {
                if (v[t][i] > v[best][i]) {
                    best = t;
                }
            }
            tileOf[i] = (byte) best;
        }

        List<long[]> rolled = new ArrayList<>();
        long seed = evaluator.settings.seed();
        int first = 0;
        while (first < things.size()) {
            int group = thingGroup[first];
            int last = first;
            while (last + 1 < things.size() && thingGroup[last + 1] == group) {
                last++;
            }
            for (int i = 0; i < n; i++) {
                int chosen = first;
                for (int t = first + 1; t <= last; t++) {
                    if (v[thingRoot + 2 * t][i] > v[thingRoot + 2 * chosen][i]) {
                        chosen = t;
                    }
                }
                float probability = v[thingRoot + 2 * chosen][i];
                if (probability <= 0) {
                    continue;
                }
                for (int attempt = 0; attempt < things.get(chosen).placementDensity(); attempt++) {
                    long roll = Hash.of(seed, group * 64L + attempt, (long) xs[i], (long) ys[i]);
                    if (Hash.unit(roll) < probability) {
                        long priority = (long) group << 40 | (long) attempt << 32 | (Hash.mix(roll, 1) >>> 32);
                        rolled.add(new long[]{priority, chosen, i});
                    }
                }
            }
            first = last + 1;
        }
        rolled.sort(Comparator.comparingLong(r -> r[0]));
        int m = rolled.size();
        int[] thing = new int[m];
        int[] cx = new int[m];
        int[] cy = new int[m];
        long[] priority = new long[m];
        float[] richness = new float[m];
        for (int k = 0; k < m; k++) {
            long[] r = rolled.get(k);
            int i = (int) r[2];
            priority[k] = r[0];
            thing[k] = (int) r[1];
            cx[k] = (int) xs[i];
            cy[k] = (int) ys[i];
            richness[k] = v[thingRoot + 2 * thing[k] + 1][i];
        }
        return new Block(tileOf, v[climateRoot], v[climateRoot + 1], v[climateRoot + 2], thing, cx, cy, priority, richness);
    }

    // -- geometry -----------------------------------------------------------------------------

    private static double extent(double[] box) {
        return Math.max(Math.max(-box[0], box[2]), Math.max(-box[1], box[3]));
    }

    private static boolean overlap(double[] a, int ax, int ay, double[] b, int bx, int by) {
        return ax + a[0] < bx + b[2] && bx + b[0] < ax + a[2] && ay + a[1] < by + b[3] && by + b[1] < ay + a[3];
    }

    private static int inBlock(int x, int y) {
        return Math.floorMod(y, BLOCK) * BLOCK + Math.floorMod(x, BLOCK);
    }
}
