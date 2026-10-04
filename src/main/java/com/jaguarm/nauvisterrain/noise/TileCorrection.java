package com.jaguarm.nauvisterrain.noise;

import com.jaguarm.nauvisterrain.noise.NoiseProgram.Prototype;

import java.util.Arrays;
import java.util.List;

/**
 * Factorio's tile correction (TileCorrectionMapGenerationTask): over the 96 by 96 tiles of a chunk
 * and its eight neighbours, a walk from each of the chunk's tiles replaces the tiles that would
 * make one-tile strips, pinches or forbidden neighbours (docs/NOISE.md, tile correction). An area
 * is tile indices at `x * 96 + y`, its corner the corner of the chunk up and left of the middle one.
 */
final class TileCorrection {
    static final int AREA = 96;
    private static final int NONE = -1;
    private static final int[] AROUND_X = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final int[] AROUND_Y = {-1, -1, 0, 1, 1, 1, 0, -1};

    private final int[] layer;
    private final boolean[][] allowed;
    private final int[][] between;

    TileCorrection(List<Prototype> tiles) {
        int n = tiles.size();
        layer = new int[n];
        allowed = new boolean[n][n];
        between = new int[n][n];
        for (int a = 0; a < n; a++) {
            layer[a] = tiles.get(a).tileRules().layer();
            for (int b = 0; b < n; b++) {
                String path = tiles.get(a).tileRules().forbidden().get(tiles.get(b).name());
                allowed[a][b] = path == null;
                between[a][b] = path == null ? b : indexOf(tiles, path);
            }
        }
    }

    /** computeInternal: corrects the area in place and gives the positions apply writes back. */
    int[] correct(byte[] area) {
        return new Task(area).compute();
    }

    private static int indexOf(List<Prototype> tiles, String name) {
        for (int i = 0; i < tiles.size(); i++) {
            if (tiles.get(i).name().equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("no tile " + name);
    }

    private static boolean inside(int x, int y) {
        return x >= 0 && y >= 0 && x < AREA && y < AREA;
    }

    /** One correction's state: the area, the tiles a walk has fixed, and the shared position buffer. */
    private final class Task {
        private final byte[] area;
        /** The walk a tile was fixed in: fixed in this one when it is the current walk's number. */
        private final int[] fixedIn = new int[AREA * AREA];
        private int walk;
        private int[] buffer = new int[64];
        private int buffered;
        private int[] changed = new int[64];
        private int changes;
        private boolean flag;
        private int suggestion;

        Task(byte[] area) {
            this.area = area;
        }

        int[] compute() {
            for (int x = 32; x < 64; x++) {
                for (int y = 32; y < 64; y++) {
                    correctFrom(x * AREA + y, false);
                }
            }
            int first = changes;
            int[] snapshot = Arrays.copyOf(changed, first);
            for (int p : snapshot) {
                if (!consistent(p / AREA, p % AREA, area[p], true)) {
                    correctFrom(p, true);
                }
            }
            int[] out = Arrays.copyOf(snapshot, first + buffered);
            System.arraycopy(buffer, 0, out, first, buffered);
            return out;
        }

        /**
         * correctFromTile: a breadth-first walk from the start, which stays as it is, each tile
         * reached fixed as it is or as the neighbour it fails suggests. In the second pass the
         * buffer is its own output, as in Factorio.
         */
        private void correctFrom(int start, boolean intoBuffer) {
            buffered = 0;
            walk++;
            push(start);
            fixedIn[start] = walk;
            for (int i = 0; i < buffered; i++) {
                int px = buffer[i] / AREA, py = buffer[i] % AREA;
                for (int d = 0; d < 8; d++) {
                    int nx = px + AROUND_X[d], ny = py + AROUND_Y[d];
                    if (!inside(nx, ny)) {
                        continue;
                    }
                    int k = nx * AREA + ny;
                    if (fixedIn[k] == walk) {
                        continue;
                    }
                    int original = area[k];
                    int current = original;
                    if (!consistent(nx, ny, current, false)) {
                        long seen = 0;
                        while (true) {
                            seen |= 1L << current;
                            current = suggestion == current || allowed[current][suggestion]
                                    ? suggestion : between[suggestion][current];
                            if (current == NONE || (seen & 1L << current) != 0) {
                                break;
                            }
                            if (consistent(nx, ny, current, false)) {
                                break;
                            }
                        }
                    }
                    if (current != NONE && current != original) {
                        if (intoBuffer) {
                            push(k);
                        } else {
                            record(k);
                        }
                        area[k] = (byte) current;
                        push(k);
                    } else if (flag) {
                        push(k);
                    }
                    fixedIn[k] = walk;
                }
            }
        }

        private void push(int p) {
            if (buffered == buffer.length) {
                buffer = Arrays.copyOf(buffer, buffered * 2);
            }
            buffer[buffered++] = p;
        }

        private void record(int p) {
            if (changes == changed.length) {
                changed = Arrays.copyOf(changed, changes * 2);
            }
            changed[changes++] = p;
        }

        private boolean held(int x, int y, boolean all) {
            return all || fixedIn[x * AREA + y] == walk;
        }

        private int at(int x, int y) {
            return area[x * AREA + y];
        }

        /**
         * isTileConsistentWithFixedTiles: whether a tile fits its fixed neighbours, or all of them;
         * leaves whether the walk should go on from it, and the neighbour last looked at.
         */
        private boolean consistent(int px, int py, int tile, boolean all) {
            flag = false;
            suggestion = NONE;
            int tl = layer[tile];
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    int nx = px + dx, ny = py + dy;
                    if (!inside(nx, ny) || !held(nx, ny, all)) {
                        continue;
                    }
                    int neighbour = at(nx, ny);
                    suggestion = neighbour;
                    if (tile != neighbour && !allowed[neighbour][tile]) {
                        return false;
                    }
                    if (tile == neighbour) {
                        continue;
                    }
                    int nl = layer[neighbour];
                    if (tl == nl) {
                        continue;
                    }
                    boolean diagonal = dx != 0 && dy != 0;
                    if (tl > nl) {
                        int fx = px + 2 * dx, fy = py + 2 * dy;
                        if (inside(fx, fy)) {
                            int far = at(fx, fy);
                            if (far != neighbour && layer[far] > nl) {
                                if (!held(fx, fy, all)) {
                                    if (!diagonal && count(px, py, neighbour) > count(fx, fy, neighbour)) {
                                        return false;
                                    }
                                } else {
                                    if (!diagonal || !weak(nx, ny, dx != dy, nl, all)) {
                                        return false;
                                    }
                                }
                            }
                        }
                        if (diagonal) {
                            for (int side = 0; side < 2; side++) {
                                int qx = side == 0 ? nx : nx + dx, qy = side == 0 ? ny + dy : ny;
                                int rx = side == 0 ? px : nx, ry = side == 0 ? ny : py;
                                if (!inside(qx, qy) || !held(qx, qy, all)) {
                                    continue;
                                }
                                int q = at(qx, qy);
                                if (q == neighbour || !inside(rx, ry) || layer[q] <= nl) {
                                    continue;
                                }
                                if (!held(rx, ry, all)) {
                                    flag = true;
                                } else if (layer[at(rx, ry)] <= nl) {
                                    return false;
                                }
                            }
                        }
                    } else {
                        int ox = px - dx, oy = py - dy;
                        if (inside(ox, oy)) {
                            int opposite = at(ox, oy);
                            if (opposite != tile && layer[opposite] > tl) {
                                if (diagonal && weak(px, py, dx != dy, tl, all)) {
                                    if (!strong(px, py, dx != dy, tl, all)) {
                                        flag = true;
                                    }
                                } else if (all || fixedIn[ox * AREA + oy] == walk) {
                                    return false;
                                } else {
                                    flag = true;
                                }
                            }
                        }
                        if (diagonal) {
                            for (int side = 0; side < 2; side++) {
                                int ax = side == 0 ? px - dx : px, ay = side == 0 ? py : py - dy;
                                int bx = side == 0 ? px : px + dx, by = side == 0 ? py + dy : py;
                                if (!inside(ax, ay) || !inside(bx, by)) {
                                    continue;
                                }
                                int a = at(ax, ay);
                                if (a == tile || layer[a] <= tl) {
                                    continue;
                                }
                                if (!held(bx, by, all)) {
                                    flag = true;
                                } else if (layer[at(bx, by)] > tl) {
                                    continue;
                                } else if (all) {
                                    return false;
                                } else if (fixedIn[ax * AREA + ay] != walk) {
                                    flag = true;
                                } else {
                                    return false;
                                }
                            }
                        }
                    }
                }
            }
            return true;
        }

        /** countFixedNeighborsOfKind: the eight neighbours holding the tile, fixed or not. */
        private int count(int x, int y, int tile) {
            int n = 0;
            for (int d = 0; d < 8; d++) {
                int cx = x + AROUND_X[d], cy = y + AROUND_Y[d];
                if (inside(cx, cy) && at(cx, cy) == tile) {
                    n++;
                }
            }
            return n;
        }

        /** checkForWeakDiagonalSupport: false when both pairs of neighbours hold a fixed tile at or below the layer. */
        private boolean weak(int x, int y, boolean flip, int level, boolean all) {
            return support(x, y, flip, level, all, false);
        }

        /** checkForStrongDiagonalSupport: as weak, but a tile not yet fixed counts as low. */
        private boolean strong(int x, int y, boolean flip, int level, boolean all) {
            return support(x, y, flip, level, all, true);
        }

        private boolean support(int x, int y, boolean flip, int level, boolean all, boolean openIsLow) {
            boolean first = low(x - 1, y, level, all, openIsLow) || low(x, flip ? y - 1 : y + 1, level, all, openIsLow);
            boolean second = low(x + 1, y, level, all, openIsLow) || low(x, flip ? y + 1 : y - 1, level, all, openIsLow);
            return !(first && second);
        }

        private boolean low(int x, int y, int level, boolean all, boolean openIsLow) {
            if (!inside(x, y)) {
                return false;
            }
            if (!held(x, y, all)) {
                return openIsLow;
            }
            return layer[at(x, y)] <= level;
        }
    }
}
