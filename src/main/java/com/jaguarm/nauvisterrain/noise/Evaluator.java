package com.jaguarm.nauvisterrain.noise;

import com.jaguarm.nauvisterrain.noise.BasisNoise.Grid;
import com.jaguarm.nauvisterrain.noise.MapSettings.Point;
import com.jaguarm.nauvisterrain.noise.NoiseProgram.Node;
import com.jaguarm.nauvisterrain.noise.NoiseProgram.Op;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A noise program under one set of map settings, run over batches of positions as Factorio runs
 * it (docs/NOISE.md): each node computes the whole batch in float before the next starts, and
 * what the map settings fix is folded once, as Factorio's compiler folds it. Safe to use from
 * several threads at once.
 */
public final class Evaluator {
    /** How far from its starting position the engine's starting lake lies. */
    static final double STARTING_LAKE_DISTANCE = 75;

    public final NoiseProgram program;
    public final MapSettings settings;
    private final Node[] nodes;
    /** Each node's stand-in once the map settings have picked every property's value. */
    private final int[] target;
    private final boolean[] varies;
    /** What a node that does not vary is, as Factorio keeps a constant: a double, a float once folded. */
    private final double[] uniform;
    private final SpotNoise.Call[] spotCalls;
    private final Map<String, List<Point>> points;
    private final SpotNoise spotNoise = new SpotNoise(this);
    private final Map<List<Integer>, int[]> orders = new ConcurrentHashMap<>();
    private final int inputX;
    private final int inputY;

    public Evaluator(NoiseProgram program, MapSettings settings) {
        this.program = program;
        this.settings = settings;
        this.nodes = program.nodes;
        this.points = Map.of("starting_positions", settings.startingPositions(),
                "starting_lake_positions", startingLakes(settings));
        int count = nodes.length;
        target = new int[count];
        varies = new boolean[count];
        uniform = new double[count];
        int x = -1, y = -1;
        for (int n = 0; n < count; n++) {
            target[n] = resolve(n);
            if (nodes[n].op() == Op.INPUT) {
                x = nodes[n].name().equals("x") ? n : x;
                y = nodes[n].name().equals("y") ? n : y;
            }
        }
        inputX = x;
        inputY = y;
        for (int n = 0; n < count; n++) {
            Node node = nodes[n];
            varies[n] = switch (node.op()) {
                case CONST, POINTS -> false;
                case INPUT -> n == inputX || n == inputY;
                case PROPERTY -> varies[target[n]];
                case SPOT_NOISE -> varies[target[node.args()[0]]] || varies[target[node.args()[1]]];
                default -> {
                    boolean any = false;
                    for (int a : node.args()) {
                        any |= varies[target[a]];
                    }
                    yield any;
                }
            };
        }
        spotCalls = new SpotNoise.Call[count];
        for (int n = 0; n < count; n++) {
            if (nodes[n].op() == Op.SPOT_NOISE && target[n] == n) {
                spotCalls[n] = spotCall(n);
            }
            if (!varies[n] && target[n] == n) {
                uniform[n] = constant(n);
            }
        }
    }

    /** The named roots over a list of positions, one array per root. */
    public float[][] evaluate(String[] roots, float[] xs, float[] ys) {
        return evaluateNodes(ids(roots), xs, ys, null);
    }

    public float[] evaluate(String root, float[] xs, float[] ys) {
        return evaluate(new String[]{root}, xs, ys)[0];
    }

    /**
     * The named roots over a grid of positions laid out as Factorio lays out a chunk: rows of x
     * from x0, y0, each step apart. Noise that reads `x` and `y` as they are takes Factorio's grid path.
     */
    public float[][] evaluateGrid(String[] roots, float x0, float y0, int width, int height, float step) {
        Grid grid = new Grid(x0, y0, width, height, step);
        float[] xs = new float[width * height];
        float[] ys = new float[width * height];
        for (int j = 0, k = 0; j < height; j++) {
            float y = (float) j * step + y0;
            for (int i = 0; i < width; i++, k++) {
                xs[k] = (float) i * step + x0;
                ys[k] = y;
            }
        }
        return evaluateNodes(ids(roots), xs, ys, grid);
    }

    /** Nodes by number over one batch of positions. */
    float[][] evaluateNodes(int[] ids, float[] xs, float[] ys, Grid grid) {
        int n = xs.length;
        float[][] values = new float[nodes.length][];
        for (int id : order(ids)) {
            values[id] = compute(id, values, xs, ys, grid);
        }
        float[][] out = new float[ids.length][];
        for (int i = 0; i < ids.length; i++) {
            int t = target[ids[i]];
            out[i] = varies[t] ? values[t] : filled(uniform[t], n);
        }
        return out;
    }

    public List<Point> points(String name) {
        List<Point> list = points.get(name);
        if (list == null) {
            throw new IllegalArgumentException("no list of positions is called " + name);
        }
        return list;
    }

    // -- set-up -------------------------------------------------------------------------------

    private int[] ids(String[] roots) {
        int[] ids = new int[roots.length];
        for (int i = 0; i < roots.length; i++) {
            ids[i] = program.root(roots[i]);
        }
        return ids;
    }

    private int resolve(int n) {
        while (nodes[n].op() == Op.PROPERTY) {
            Node node = nodes[n];
            Integer chosen = node.variants().get(settings.property(node.name()));
            if (chosen == null) {
                throw new IllegalArgumentException("the map settings give " + node.name() + " the value '"
                        + settings.property(node.name()) + "', which no preset gives it: " + node.variants().keySet());
            }
            n = chosen;
        }
        return n;
    }

    /**
     * MapGenSettings::getStartingLakePositions: one per starting position, 75 tiles out in a
     * direction the map seed's random generator draws, truncated to a tile.
     */
    private static List<Point> startingLakes(MapSettings settings) {
        RandomGenerator random = new RandomGenerator(settings.seed());
        List<Point> lakes = new ArrayList<>();
        for (Point start : settings.startingPositions()) {
            float angle = (float) (random.next() * 0x1p-32 * 6.283185307179586 + 0.0);
            double turns = angle * 0.15915494309189535;
            lakes.add(new Point((int) (BasisNoise.cosTurns(turns) * STARTING_LAKE_DISTANCE + fixed(start.x()) * 0x1p-8f),
                    (int) (BasisNoise.sinTurns(turns) * STARTING_LAKE_DISTANCE + fixed(start.y()) * 0x1p-8f)));
        }
        return List.copyOf(lakes);
    }

    private double input(String name) {
        return switch (name) {
            case "map_seed" -> settings.seed();
            case "map_seed_small" -> settings.seed() & 0xFFFF;
            case "map_seed_normalized" -> settings.seed() / (double) 0xFFFFFFFFL;
            case "cliff_elevation_0" -> settings.cliffElevation0();
            case "cliff_elevation_interval" -> settings.cliffElevationInterval();
            case "cliff_smoothing" -> settings.cliffSmoothing();
            case "cliff_richness" -> settings.cliffRichness();
            default -> {
                if (name.startsWith("control:")) {
                    yield settings.control(name);
                }
                throw new IllegalArgumentException("the map settings have no number called " + name);
            }
        };
    }

    /** A node that does not vary, folded as Factorio's compiler folds it: on its arguments as floats, to a float. */
    private double constant(int n) {
        Node node = nodes[n];
        int[] a = node.args();
        return switch (node.op()) {
            case CONST -> node.value();
            case INPUT -> input(node.name());
            case POINTS -> 0;
            case ADD -> f(a[0]) + f(a[1]);
            case SUB -> f(a[0]) - f(a[1]);
            case MUL -> f(a[0]) * f(a[1]);
            case DIV -> f(a[0]) / f(a[1]);
            case POW -> (float) Math.pow(f(a[0]), f(a[1]));
            case GT -> f(a[0]) > f(a[1]) ? 1 : 0;
            case GE -> f(a[0]) >= f(a[1]) ? 1 : 0;
            case NEG -> -f(a[0]);
            case ABS -> Math.abs(f(a[0]));
            case SQRT -> (float) Math.sqrt(f(a[0]));
            case LOG2 -> log2(f(a[0]));
            case CLAMP -> clamp(f(a[0]), f(a[1]), f(a[2]));
            case IF -> f(a[0]) > 0 ? f(a[1]) : f(a[2]);
            case MIN, MAX -> {
                float m = f(a[0]);
                for (int i = 1; i < a.length; i++) {
                    m = node.op() == Op.MIN ? min(m, f(a[i])) : max(m, f(a[i]));
                }
                yield m;
            }
            default -> {
                float[] zero = {0};
                yield compute(n, new float[nodes.length][], zero, zero, null)[0];
            }
        };
    }

    /** A constant as the float a register holds. */
    private float f(int n) {
        return (float) uniform[target[n]];
    }

    /** A constant where the engine wants an unsigned 32-bit integer: NaN and below 1 are 0, and it stops at 2^32 - 1. */
    private long uint32(int n) {
        double v = uniform[target[n]];
        if (!(v > 0)) {
            return 0;
        }
        return v >= 4294967295.0 ? 0xFFFFFFFFL : (long) v;
    }

    /** The varying nodes the given ones reach, in order. `spot_noise` reaches only its position. */
    private int[] order(int[] ids) {
        List<Integer> key = new ArrayList<>(ids.length);
        for (int id : ids) {
            key.add(id);
        }
        return orders.computeIfAbsent(key, k -> {
            boolean[] seen = new boolean[nodes.length];
            ArrayList<Integer> stack = new ArrayList<>();
            for (int id : ids) {
                stack.add(target[id]);
            }
            while (!stack.isEmpty()) {
                int n = stack.removeLast();
                if (seen[n] || !varies[n]) {
                    continue;
                }
                seen[n] = true;
                int[] args = nodes[n].args();
                if (nodes[n].op() == Op.SPOT_NOISE) {
                    args = Arrays.copyOf(args, 2);
                }
                for (int a : args) {
                    stack.add(target[a]);
                }
            }
            int count = 0;
            for (boolean s : seen) {
                count += s ? 1 : 0;
            }
            int[] order = new int[count];
            for (int n = 0, i = 0; n < seen.length; n++) {
                if (seen[n]) {
                    order[i++] = n;
                }
            }
            return order;
        });
    }

    /** A `spot_noise` node's constants, converted as SpotNoise's constructor converts them. */
    private SpotNoise.Call spotCall(int id) {
        int[] a = nodes[id].args();
        return new SpotNoise.Call(id, target[a[2]], target[a[3]], target[a[4]], target[a[5]], uint32(a[6]), uint32(a[7]),
                f(a[8]), f(a[9]), uint32(a[10]), uint32(a[11]), uint32(a[12]), uniform[target[a[13]]] > 0, uint32(a[14]),
                f(a[15]));
    }

    // -- a batch ----------------------------------------------------------------------------

    private float[] compute(int id, float[][] values, float[] xs, float[] ys, Grid grid) {
        Node node = nodes[id];
        int n = xs.length;
        int[] a = node.args();
        float[] out = new float[n];
        switch (node.op()) {
            case INPUT -> System.arraycopy(id == inputX ? xs : ys, 0, out, 0, n);
            case ADD -> {
                float[] p = v(a[0], values, n), q = v(a[1], values, n);
                for (int i = 0; i < n; i++) out[i] = p[i] + q[i];
            }
            case SUB -> {
                float[] p = v(a[0], values, n), q = v(a[1], values, n);
                for (int i = 0; i < n; i++) out[i] = p[i] - q[i];
            }
            case MUL -> {
                float[] p = v(a[0], values, n), q = v(a[1], values, n);
                for (int i = 0; i < n; i++) out[i] = p[i] * q[i];
            }
            case DIV -> {
                float[] p = v(a[0], values, n), q = v(a[1], values, n);
                for (int i = 0; i < n; i++) out[i] = p[i] / q[i];
            }
            case POW -> {
                float[] p = v(a[0], values, n), q = v(a[1], values, n);
                for (int i = 0; i < n; i++) out[i] = FastApprox.pow(p[i], q[i]);
            }
            case GT -> {
                float[] p = v(a[0], values, n), q = v(a[1], values, n);
                for (int i = 0; i < n; i++) out[i] = p[i] > q[i] ? 1 : 0;
            }
            case GE -> {
                float[] p = v(a[0], values, n), q = v(a[1], values, n);
                for (int i = 0; i < n; i++) out[i] = p[i] >= q[i] ? 1 : 0;
            }
            case NEG -> {
                float[] p = v(a[0], values, n);
                for (int i = 0; i < n; i++) out[i] = -p[i];
            }
            case ABS -> {
                float[] p = v(a[0], values, n);
                for (int i = 0; i < n; i++) out[i] = Math.abs(p[i]);
            }
            case SQRT -> {
                float[] p = v(a[0], values, n);
                for (int i = 0; i < n; i++) out[i] = (float) Math.sqrt(p[i]);
            }
            case LOG2 -> {
                float[] p = v(a[0], values, n);
                for (int i = 0; i < n; i++) out[i] = log2(p[i]);
            }
            case CLAMP -> {
                float[] p = v(a[0], values, n), lo = v(a[1], values, n), hi = v(a[2], values, n);
                for (int i = 0; i < n; i++) out[i] = clamp(p[i], lo[i], hi[i]);
            }
            case IF -> {
                float[] c = v(a[0], values, n), p = v(a[1], values, n), q = v(a[2], values, n);
                for (int i = 0; i < n; i++) out[i] = c[i] > 0 ? p[i] : q[i];
            }
            case MIN, MAX -> {
                System.arraycopy(v(a[0], values, n), 0, out, 0, n);
                boolean min = node.op() == Op.MIN;
                for (int k = 1; k < a.length; k++) {
                    float[] p = v(a[k], values, n);
                    for (int i = 0; i < n; i++) out[i] = min ? min(out[i], p[i]) : max(out[i], p[i]);
                }
            }
            case BASIS_NOISE -> BasisNoise.basis(uint32(a[2]), uint32(a[3]), v(a[0], values, n), v(a[1], values, n),
                    grid(grid, a), f(a[4]), f(a[5]), f(a[6]), f(a[7]), out);
            case MULTIOCTAVE_NOISE -> BasisNoise.multioctave(uint32(a[3]), uint32(a[4]), v(a[0], values, n),
                    v(a[1], values, n), grid(grid, a), f(a[2]), f(a[5]), f(a[6]), f(a[7]), f(a[8]), f(a[9]), out);
            case VARIABLE_PERSISTENCE_MULTIOCTAVE_NOISE -> BasisNoise.variablePersistence(uint32(a[3]), uint32(a[4]),
                    v(a[0], values, n), v(a[1], values, n), grid(grid, a), v(a[2], values, n), uint32(a[5]), f(a[6]), f(a[7]),
                    f(a[8]), f(a[9]), out);
            case QUICK_MULTIOCTAVE_NOISE -> BasisNoise.quickMultioctave(uint32(a[2]), uint32(a[3]), v(a[0], values, n),
                    v(a[1], values, n), grid(grid, a), uint32(a[4]), f(a[5]), f(a[6]), f(a[7]), f(a[8]), f(a[9]), f(a[10]),
                    uint32(a[11]), out);
            case DISTANCE_FROM_NEAREST_POINT -> distance(v(a[0], values, n), v(a[1], values, n),
                    points(nodes[target[a[2]]].name()), f(a[3]), out);
            case RANDOM_PENALTY -> RandomPenalty.apply(v(a[0], values, n), v(a[1], values, n), v(a[2], values, n),
                    uint32(a[3]), f(a[4]), out);
            case SPOT_NOISE -> spotNoise.evaluate(spotCalls[id], v(a[0], values, n), v(a[1], values, n), out);
            default -> throw new IllegalStateException(node.op() + " is not computed per position");
        }
        return out;
    }

    /** The batch's grid, for a noise that reads `x` and `y` as they are. */
    private Grid grid(Grid grid, int[] a) {
        return grid != null && target[a[0]] == inputX && target[a[1]] == inputY ? grid : null;
    }

    /** DistanceFromNearestPoint: the distance to the nearest point, which are map positions, up to the maximum. */
    private static void distance(float[] xs, float[] ys, List<Point> list, float maximum, float[] out) {
        float maximum2 = maximum * maximum;
        float[] best = new float[xs.length];
        Arrays.fill(best, maximum2);
        for (Point p : list) {
            float px = fixed(p.x()) * 0x1p-8f;
            float py = fixed(p.y()) * 0x1p-8f;
            for (int i = 0; i < xs.length; i++) {
                float dx = xs[i] - px;
                float dy = ys[i] - py;
                float d2 = dy * dy + dx * dx;
                if (best[i] > d2) {
                    best[i] = d2;
                }
            }
        }
        for (int i = 0; i < xs.length; i++) {
            out[i] = best[i] >= maximum2 ? maximum : (float) Math.sqrt(best[i]);
        }
    }

    /** A coordinate as a map position holds it: 24.8 fixed point, truncated. */
    private static int fixed(double v) {
        return (int) (v * 256);
    }

    /** An argument over the batch; a constant one is filled once per batch and kept with the values. */
    private float[] v(int arg, float[][] values, int n) {
        int t = target[arg];
        if (values[t] == null) {
            values[t] = filled(uniform[t], n);
        }
        return values[t];
    }

    private static float[] filled(double value, int n) {
        float[] out = new float[n];
        Arrays.fill(out, (float) value);
        return out;
    }

    /** Clamp as the engine runs it: maxss, then minss. */
    private static float clamp(float v, float lo, float hi) {
        float t = v > lo ? v : lo;
        return t < hi ? t : hi;
    }

    /** min, which Factorio's compiler makes a clamp from below at -inf. */
    private static float min(float a, float b) {
        return clamp(a, Float.NEGATIVE_INFINITY, b);
    }

    private static float max(float a, float b) {
        return clamp(a, b, Float.POSITIVE_INFINITY);
    }

    /** Math::log2Precise, in double, to a float. */
    private static float log2(float v) {
        return (float) (Math.log(v) / Math.log(2));
    }
}
