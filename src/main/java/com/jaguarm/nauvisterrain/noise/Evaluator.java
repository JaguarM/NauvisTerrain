package com.jaguarm.nauvisterrain.noise;

import com.jaguarm.nauvisterrain.noise.MapSettings.Point;
import com.jaguarm.nauvisterrain.noise.NoiseProgram.Node;
import com.jaguarm.nauvisterrain.noise.NoiseProgram.Op;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A noise program under one set of map settings, run over batches of positions. Each node computes
 * the whole batch before the next starts; what does not vary across the map is computed once.
 * Safe to use from several threads at once.
 */
public final class Evaluator {
    /** How far from its starting position the engine's starting lake lies, in tiles. */
    static final double STARTING_LAKE_DISTANCE = 75;

    public final NoiseProgram program;
    public final MapSettings settings;
    private final Node[] nodes;
    /** Each node's stand-in once the map settings have picked every property's value. */
    private final int[] target;
    private final boolean[] varies;
    private final double[] uniform;
    private final Map<String, List<Point>> points;
    private final SpotNoise spotNoise = new SpotNoise(this);
    private final Map<List<Integer>, int[]> orders = new ConcurrentHashMap<>();

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
        for (int n = 0; n < count; n++) {
            target[n] = resolve(n);
        }
        for (int n = 0; n < count; n++) {
            Node node = nodes[n];
            varies[n] = switch (node.op()) {
                case CONST, POINTS -> false;
                case INPUT -> node.name().equals("x") || node.name().equals("y");
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
        for (int n = 0; n < count; n++) {
            if (!varies[n] && target[n] == n) {
                uniform[n] = constant(n);
            }
        }
    }

    /** The named roots over a batch of positions, one array per root. */
    public float[][] evaluate(String[] roots, float[] xs, float[] ys) {
        int[] ids = new int[roots.length];
        for (int i = 0; i < roots.length; i++) {
            ids[i] = program.root(roots[i]);
        }
        return evaluateNodes(ids, xs, ys);
    }

    public float[] evaluate(String root, float[] xs, float[] ys) {
        return evaluate(new String[]{root}, xs, ys)[0];
    }

    /** Nodes by number over a batch of positions. */
    float[][] evaluateNodes(int[] ids, float[] xs, float[] ys) {
        int n = xs.length;
        float[][] values = new float[nodes.length][];
        for (int id : order(ids)) {
            values[id] = compute(id, values, xs, ys);
        }
        float[][] out = new float[ids.length][];
        for (int i = 0; i < ids.length; i++) {
            int t = target[ids[i]];
            out[i] = varies[t] ? values[t] : filled(uniform[t], n);
        }
        return out;
    }

    /** A value the map settings fix: the same at every position. */
    public double uniformValue(String root) {
        int t = target[program.root(root)];
        if (varies[t]) {
            throw new IllegalArgumentException(root + " varies across the map");
        }
        return uniform[t];
    }

    public List<Point> points(String name) {
        List<Point> list = points.get(name);
        if (list == null) {
            throw new IllegalArgumentException("no list of positions is called " + name);
        }
        return list;
    }

    // -- set-up -------------------------------------------------------------------------------

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

    /** The engine's starting lakes: one per starting position, in a direction the seed picks. */
    private static List<Point> startingLakes(MapSettings settings) {
        List<Point> lakes = new ArrayList<>();
        for (int i = 0; i < settings.startingPositions().size(); i++) {
            Point start = settings.startingPositions().get(i);
            double angle = 2 * Math.PI * Hash.unit(Hash.mix(settings.seed(), i));
            lakes.add(new Point(start.x() + STARTING_LAKE_DISTANCE * Math.cos(angle),
                    start.y() + STARTING_LAKE_DISTANCE * Math.sin(angle)));
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

    /** A node that does not vary, in double precision so a seed stays exact. */
    private double constant(int n) {
        Node node = nodes[n];
        int[] a = node.args();
        return switch (node.op()) {
            case CONST -> node.value();
            case INPUT -> input(node.name());
            case POINTS -> 0;
            case ADD -> u(a[0]) + u(a[1]);
            case SUB -> u(a[0]) - u(a[1]);
            case MUL -> u(a[0]) * u(a[1]);
            case DIV -> u(a[0]) / u(a[1]);
            case MOD -> mod(u(a[0]), u(a[1]));
            case FMOD -> u(a[0]) % u(a[1]);
            case POW -> Math.pow(u(a[0]), u(a[1]));
            case LT -> bool(u(a[0]) < u(a[1]));
            case LE -> bool(u(a[0]) <= u(a[1]));
            case GT -> bool(u(a[0]) > u(a[1]));
            case GE -> bool(u(a[0]) >= u(a[1]));
            case EQ -> bool(u(a[0]) == u(a[1]));
            case NE -> bool(u(a[0]) != u(a[1]));
            case AND -> (int) u(a[0]) & (int) u(a[1]);
            case XOR -> (int) u(a[0]) ^ (int) u(a[1]);
            case OR -> (int) u(a[0]) | (int) u(a[1]);
            case NEG -> -u(a[0]);
            case NOT -> ~(int) u(a[0]);
            case ABS -> Math.abs(u(a[0]));
            case CEIL -> Math.ceil(u(a[0]));
            case FLOOR -> Math.floor(u(a[0]));
            case COS -> Math.cos(u(a[0]));
            case SIN -> Math.sin(u(a[0]));
            case SQRT -> Math.sqrt(u(a[0]));
            case LOG2 -> Math.log(u(a[0])) / Math.log(2);
            case ATAN2 -> Math.atan2(u(a[0]), u(a[1]));
            case CLAMP -> Math.min(Math.max(u(a[0]), u(a[1])), u(a[2]));
            case IF -> u(a[0]) > 0 ? u(a[1]) : u(a[2]);
            case MIN -> {
                double m = u(a[0]);
                for (int i = 1; i < a.length; i++) {
                    m = Math.min(m, u(a[i]));
                }
                yield m;
            }
            case MAX -> {
                double m = u(a[0]);
                for (int i = 1; i < a.length; i++) {
                    m = Math.max(m, u(a[i]));
                }
                yield m;
            }
            default -> {
                float[] zero = {0};
                float[][] values = new float[nodes.length][];
                yield compute(n, values, zero, zero)[0];
            }
        };
    }

    private double u(int n) {
        return uniform[target[n]];
    }

    private long seed(int n) {
        return (long) u(n) & 0xFFFFFFFFL;
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

    // -- a batch ----------------------------------------------------------------------------

    private float[] compute(int id, float[][] values, float[] xs, float[] ys) {
        Node node = nodes[id];
        int n = xs.length;
        int[] a = node.args();
        float[] out = new float[n];
        switch (node.op()) {
            case INPUT -> System.arraycopy(node.name().equals("x") ? xs : ys, 0, out, 0, n);
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
            case MOD -> {
                float[] p = v(a[0], values, n), q = v(a[1], values, n);
                for (int i = 0; i < n; i++) out[i] = (float) mod(p[i], q[i]);
            }
            case FMOD -> {
                float[] p = v(a[0], values, n), q = v(a[1], values, n);
                for (int i = 0; i < n; i++) out[i] = p[i] % q[i];
            }
            case POW -> {
                float[] p = v(a[0], values, n), q = v(a[1], values, n);
                for (int i = 0; i < n; i++) out[i] = (float) Math.pow(p[i], q[i]);
            }
            case LT, LE, GT, GE, EQ, NE -> {
                float[] p = v(a[0], values, n), q = v(a[1], values, n);
                Op op = node.op();
                for (int i = 0; i < n; i++) {
                    boolean r = switch (op) {
                        case LT -> p[i] < q[i];
                        case LE -> p[i] <= q[i];
                        case GT -> p[i] > q[i];
                        case GE -> p[i] >= q[i];
                        case EQ -> p[i] == q[i];
                        default -> p[i] != q[i];
                    };
                    out[i] = r ? 1 : 0;
                }
            }
            case AND -> {
                float[] p = v(a[0], values, n), q = v(a[1], values, n);
                for (int i = 0; i < n; i++) out[i] = (int) p[i] & (int) q[i];
            }
            case XOR -> {
                float[] p = v(a[0], values, n), q = v(a[1], values, n);
                for (int i = 0; i < n; i++) out[i] = (int) p[i] ^ (int) q[i];
            }
            case OR -> {
                float[] p = v(a[0], values, n), q = v(a[1], values, n);
                for (int i = 0; i < n; i++) out[i] = (int) p[i] | (int) q[i];
            }
            case NEG -> {
                float[] p = v(a[0], values, n);
                for (int i = 0; i < n; i++) out[i] = -p[i];
            }
            case NOT -> {
                float[] p = v(a[0], values, n);
                for (int i = 0; i < n; i++) out[i] = ~(int) p[i];
            }
            case ABS -> {
                float[] p = v(a[0], values, n);
                for (int i = 0; i < n; i++) out[i] = Math.abs(p[i]);
            }
            case CEIL -> {
                float[] p = v(a[0], values, n);
                for (int i = 0; i < n; i++) out[i] = (float) Math.ceil(p[i]);
            }
            case FLOOR -> {
                float[] p = v(a[0], values, n);
                for (int i = 0; i < n; i++) out[i] = (float) Math.floor(p[i]);
            }
            case COS -> {
                float[] p = v(a[0], values, n);
                for (int i = 0; i < n; i++) out[i] = (float) Math.cos(p[i]);
            }
            case SIN -> {
                float[] p = v(a[0], values, n);
                for (int i = 0; i < n; i++) out[i] = (float) Math.sin(p[i]);
            }
            case SQRT -> {
                float[] p = v(a[0], values, n);
                for (int i = 0; i < n; i++) out[i] = (float) Math.sqrt(p[i]);
            }
            case LOG2 -> {
                float[] p = v(a[0], values, n);
                for (int i = 0; i < n; i++) out[i] = (float) (Math.log(p[i]) / Math.log(2));
            }
            case ATAN2 -> {
                float[] p = v(a[0], values, n), q = v(a[1], values, n);
                for (int i = 0; i < n; i++) out[i] = (float) Math.atan2(p[i], q[i]);
            }
            case CLAMP -> {
                float[] p = v(a[0], values, n), lo = v(a[1], values, n), hi = v(a[2], values, n);
                for (int i = 0; i < n; i++) out[i] = Math.min(Math.max(p[i], lo[i]), hi[i]);
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
                    for (int i = 0; i < n; i++) out[i] = min ? Math.min(out[i], p[i]) : Math.max(out[i], p[i]);
                }
            }
            case BASIS_NOISE -> {
                float[] x = v(a[0], values, n), y = v(a[1], values, n);
                long s0 = seed(a[2]), s1 = seed(a[3]);
                double in = u(a[4]), os = u(a[5]), ox = u(a[6]), oy = u(a[7]);
                for (int i = 0; i < n; i++) out[i] = (float) BasisNoise.basis(s0, s1, x[i], y[i], in, os, ox, oy);
            }
            case MULTIOCTAVE_NOISE, VARIABLE_PERSISTENCE_MULTIOCTAVE_NOISE -> {
                float[] x = v(a[0], values, n), y = v(a[1], values, n), p = v(a[2], values, n);
                long s0 = seed(a[3]), s1 = seed(a[4]);
                int octaves = (int) u(a[5]);
                double in = u(a[6]), os = u(a[7]), ox = u(a[8]), oy = u(a[9]);
                for (int i = 0; i < n; i++) {
                    out[i] = (float) BasisNoise.multioctave(s0, s1, x[i], y[i], p[i], octaves, in, os, ox, oy);
                }
            }
            case QUICK_MULTIOCTAVE_NOISE -> {
                float[] x = v(a[0], values, n), y = v(a[1], values, n);
                long s0 = seed(a[2]), s1 = seed(a[3]);
                int octaves = (int) u(a[4]);
                double in = u(a[5]), os = u(a[6]), ox = u(a[7]), oy = u(a[8]);
                double im = u(a[9]), om = u(a[10]);
                long shift = (long) u(a[11]);
                for (int i = 0; i < n; i++) {
                    out[i] = (float) BasisNoise.quickMultioctave(s0, s1, x[i], y[i], octaves, in, os, ox, oy, im, om, shift);
                }
            }
            case DISTANCE_FROM_NEAREST_POINT, DISTANCE_FROM_NEAREST_POINT_X, DISTANCE_FROM_NEAREST_POINT_Y -> {
                float[] x = v(a[0], values, n), y = v(a[1], values, n);
                List<Point> list = points(nodes[target[a[2]]].name());
                double maximum = node.op() == Op.DISTANCE_FROM_NEAREST_POINT ? u(a[3]) : Double.POSITIVE_INFINITY;
                for (int i = 0; i < n; i++) {
                    double best = Double.POSITIVE_INFINITY, bx = 0, by = 0;
                    for (Point p : list) {
                        double d = Math.hypot(x[i] - p.x(), y[i] - p.y());
                        if (d < best) {
                            best = d;
                            bx = x[i] - p.x();
                            by = y[i] - p.y();
                        }
                    }
                    out[i] = (float) switch (node.op()) {
                        case DISTANCE_FROM_NEAREST_POINT -> Math.min(best, maximum);
                        case DISTANCE_FROM_NEAREST_POINT_X -> bx;
                        default -> by;
                    };
                }
            }
            case RANDOM_PENALTY -> {
                float[] x = v(a[0], values, n), y = v(a[1], values, n), source = v(a[2], values, n);
                long seed = (long) u(a[3]);
                double amplitude = u(a[4]);
                for (int i = 0; i < n; i++) {
                    out[i] = source[i] > 0
                            ? (float) (source[i] - amplitude * RandomPenalty.unit(settings.seed(), seed, x[i], y[i]))
                            : source[i];
                }
            }
            case SPOT_NOISE -> spotNoise.evaluate(new SpotNoise.Call(id, target[a[2]], target[a[3]], target[a[4]],
                    target[a[5]], seed(a[6]), seed(a[7]), u(a[8]), u(a[9]), u(a[10]), (int) u(a[11]), (int) u(a[12]),
                    u(a[13]) > 0, (int) u(a[14]), u(a[15])), v(a[0], values, n), v(a[1], values, n), out);
            case EXPRESSION_IN_RANGE -> {
                int dims = (a.length - 2) / 3;
                double multiplier = u(a[0]), maximum = u(a[1]);
                float[][] inputs = new float[dims][];
                for (int d = 0; d < dims; d++) {
                    inputs[d] = v(a[2 + d], values, n);
                }
                for (int i = 0; i < n; i++) {
                    double inside = Double.POSITIVE_INFINITY;
                    for (int d = 0; d < dims; d++) {
                        double value = inputs[d][i];
                        inside = Math.min(inside, Math.min(value - u(a[2 + dims + d]), u(a[2 + 2 * dims + d]) - value));
                    }
                    out[i] = (float) Math.min(maximum, multiplier * inside);
                }
            }
            default -> throw new IllegalStateException(node.op() + " is not computed per position");
        }
        return out;
    }

    private float[] v(int arg, float[][] values, int n) {
        int t = target[arg];
        return varies[t] ? values[t] : filled(uniform[t], n);
    }

    private static float[] filled(double value, int n) {
        float[] out = new float[n];
        Arrays.fill(out, (float) value);
        return out;
    }

    private static double mod(double x, double y) {
        return x - Math.floor(x / y) * y;
    }

    private static double bool(boolean b) {
        return b ? 1 : 0;
    }
}
