#!/usr/bin/env python3
"""
Factorio's values against ours, bit for bit (docs/FACTORIO.md, the oracle).

    python tools/compare.py [--seeds 123,456] [--tiles X0,Y0,W,H] [--autoplace TILE=EXPR ...] [NAME ...]
    python tools/compare.py --fixture

Each NAME is a root or a named expression (`elevation`, `tile:grass-1:probability`); with none,
every root of the program. Factorio evaluates them over a list of positions through
tools/oracle.py, in one batch; ours come from a program with those names as roots, written to
core/build/compare/, which ComparisonRun evaluates over the same list as one batch. With --tiles,
Factorio generates the chunks over the area and its tiles are set against the ones our Terrain
picks there. Each --autoplace gives a tile a made-up probability in Factorio and in our program
alike, and every tile it does not name -inf, so the generated tiles show where one expression is
above another.

--fixture writes Factorio's values of every root at a few positions to FIXTURE, and its generated
chunks around the start to CHUNKS, which EvaluatorTest holds the evaluator and Terrain to without
Factorio.
"""

from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gen_terrain  # noqa: E402
import oracle  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
WORK = REPO / "core" / "build" / "compare"
PROGRAM = gen_terrain.PROGRAM
# Multiples of 1/256, which Factorio's map positions keep exactly, spread over a few thousand tiles.
GRID = [-2047.5, -1535.25, 48, 48, 85.33203125]
POINTS = [[0, 0], [0.5, 0.5], [-0.25, 3.75], [100000.5, -77777.25], [-123456.75, 54321.5], [31, 31], [32, 0]]
FIXTURE = REPO / "core" / "src" / "test" / "resources" / "factorio-values.json"
# Factorio's map gen settings without the enemies and cliffs this world does not place as entities.
BARE = {"autoplace_controls": {"enemy-base": {"frequency": 0, "size": 0, "richness": 0}}, "cliff_settings": {"richness": 0}}
FIXTURE_SEEDS = [123, 987654321]
FIXTURE_GRID = [-400.5, -350.25, 5, 5, 173.25]
# Factorio's own generated chunks around the start of the first fixture seed: tiles, entities, decoratives.
CHUNKS = REPO / "core" / "src" / "test" / "resources" / "factorio-chunks.json"
CHUNKS_AREA = [-32, -32, 64, 64]


def positions() -> list[list[float]]:
    x0, y0, w, h, step = GRID
    return [[x0 + i * step, y0 + j * step] for j in range(h) for i in range(w)] + POINTS


def tile_expressions(raw: dict, autoplace: dict[str, str]) -> dict[str, str]:
    """Every tile the planet places, with its made-up probability or -inf."""
    planet = raw["planet"][json.loads(gen_terrain.TERRAIN.read_text(encoding="utf-8"))["planet"]]
    return {t: autoplace.get(t, "-inf") for t in planet["map_gen_settings"]["autoplace_settings"]["tile"]["settings"]}


def ours(names: list[str], seeds: list[int], points: list, tiles: list[int] | None,
         autoplace: dict[str, str]) -> dict[int, dict]:
    raw = json.loads(gen_terrain.DATA_RAW.read_text(encoding="utf-8"))
    if autoplace:
        for t, expression in tile_expressions(raw, autoplace).items():
            raw["tile"][t]["autoplace"]["probability_expression"] = expression
    built = gen_terrain.build(raw, json.loads(gen_terrain.TERRAIN.read_text(encoding="utf-8")),
                              gen_terrain.load_locale(gen_terrain.LOCALE), gen_terrain.load_locale(gen_terrain.CORE_LOCALE),
                              tuple(names))
    WORK.mkdir(parents=True, exist_ok=True)
    program = WORK / "program.json"
    program.write_text(gen_terrain.render(built), encoding="utf-8", newline="\n")
    request = WORK / "request.json"
    spec = {"program": str(program), "roots": names, "seeds": seeds, "points": points}
    if tiles:
        spec["tiles"] = tiles
    request.write_text(json.dumps(spec))
    gradle = REPO / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    try:
        subprocess.run([str(gradle), ":core:test", "--tests", "*ComparisonRun*", "--rerun", "-q"], cwd=REPO, check=True,
                       capture_output=True)
    finally:
        request.unlink()
    return {seed: json.loads((WORK / f"ours-{seed}.json").read_text()) for seed in seeds}


def factorios(names: list[str], seeds: list[int], points: list, tiles: list[int] | None,
              autoplace: dict[str, str] | None = None, settings: dict | None = None) -> dict[int, dict]:
    probes = {f"p{i}": f"var('{n}')" for i, n in enumerate(names)}
    jobs = [{"properties": list(probes), "points": points}]
    if tiles:
        jobs.append({"tiles": tiles})
    data = ""
    if autoplace:
        raw = json.loads(gen_terrain.DATA_RAW.read_text(encoding="utf-8"))
        data = "".join(f"data.raw.tile[{json.dumps(t)}].autoplace.probability_expression = {json.dumps(e)}\n"
                       for t, e in tile_expressions(raw, autoplace).items())
    out = {}
    for seed in seeds:
        spec = {"seed": seed, "probes": probes, "jobs": jobs, "data": data}
        if settings:
            spec["map_gen_settings"] = settings
        result = oracle.run(spec)
        out[seed] = {n: result[0][f"p{i}"] for i, n in enumerate(names)}
        if tiles:
            out[seed].update(result[1])
    return out


def placed(things: list, area: list[int], names: set[str]) -> dict[tuple, int]:
    """Things standing on the area's tiles, by name, tile and amount, counted."""
    x0, y0, w, h = area
    out: dict[tuple, int] = {}
    for name, x, y, amount in things:
        tx, ty = math.floor(x), math.floor(y)
        if name in names and x0 <= tx < x0 + w and y0 <= ty < y0 + h:
            key = (name, tx, ty, int(amount))
            out[key] = out.get(key, 0) + 1
    return out


def differences(theirs: dict, ours: dict) -> tuple[int, list]:
    same = sum(min(n, ours.get(k, 0)) for k, n in theirs.items())
    only = [("factorio", k) for k, n in theirs.items() if n > ours.get(k, 0)] + \
           [("ours", k) for k, n in ours.items() if n > theirs.get(k, 0)]
    return same, only


def fixture() -> None:
    """Factorio's values of every root at the fixture's positions, as the shortest text each float reads back from."""
    names = list(json.loads(PROGRAM.read_text(encoding="utf-8"))["roots"])
    x0, y0, w, h, step = FIXTURE_GRID
    points = [[x0 + i * step, y0 + j * step] for j in range(h) for i in range(w)] + [p for p in POINTS if max(map(abs, p)) < 1000]
    values = factorios(names, FIXTURE_SEEDS, points, None)

    def text(v: float):
        v = np.float32(v)
        if np.isnan(v):
            return "nan"
        if np.isinf(v):
            return "inf" if v > 0 else "-inf"
        return float(np.format_float_positional(v, unique=True, trim="-")) if v else float(v)

    out = {"factorio": gen_terrain.FACTORIO_VERSION, "positions": points,
           "seeds": {str(s): {n: [text(v) for v in values[s][n]] for n in names} for s in FIXTURE_SEEDS}}
    FIXTURE.parent.mkdir(parents=True, exist_ok=True)
    FIXTURE.write_text(json.dumps(out, separators=(",", ":")) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {FIXTURE.relative_to(REPO)}: {len(names)} roots at {len(points)} positions, seeds {FIXTURE_SEEDS}")

    seed = FIXTURE_SEEDS[0]
    made = factorios([], [seed], [[0, 0]], CHUNKS_AREA)[seed]
    program = json.loads(PROGRAM.read_text(encoding="utf-8"))
    runs = []
    for name in made["tiles"]:
        if runs and runs[-1][0] == name:
            runs[-1][1] += 1
        else:
            runs.append([name, 1])
    things = {}
    for kind, prototype_kind in (("entities", "entity"), ("decoratives", "decorative")):
        kinds = {p["name"] for p in program["prototypes"] if p["kind"] == prototype_kind}
        things[kind] = sorted(f"{k[0]} {k[1]} {k[2]} {k[3]}" for k, n in placed(made[kind], CHUNKS_AREA, kinds).items()
                              for _ in range(n))
    CHUNKS.write_text(json.dumps({"factorio": gen_terrain.FACTORIO_VERSION, "seed": seed, "area": CHUNKS_AREA,
                                  "tiles": runs, **things}, separators=(",", ":")) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {CHUNKS.relative_to(REPO)}: {len(things['entities'])} entities, {len(things['decoratives'])} decoratives")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("names", nargs="*")
    parser.add_argument("--seeds", default="123,987654321")
    parser.add_argument("--tiles", help="X0,Y0,W,H: an area whose generated tiles are compared too")
    parser.add_argument("--autoplace", action="append", default=[], metavar="TILE=EXPR",
                        help="a made-up probability for a tile, in Factorio and ours; the others are -inf")
    parser.add_argument("--fixture", action="store_true", help=f"write {FIXTURE.name} and {CHUNKS.name} for EvaluatorTest")
    parser.add_argument("--bare", action="store_true",
                        help="Factorio without what this world does not place either: enemies and cliffs")
    args = parser.parse_args()
    if args.fixture:
        fixture()
        return 0
    seeds = [int(s) for s in args.seeds.split(",")]
    tiles = [int(v) for v in args.tiles.split(",")] if args.tiles else None
    autoplace = dict(a.split("=", 1) for a in args.autoplace)
    names = args.names or list(json.loads(PROGRAM.read_text(encoding="utf-8"))["roots"])
    points = positions()

    factorio = factorios(names, seeds, points, tiles, autoplace, BARE if args.bare else None)
    mine = ours(names, seeds, points, tiles, autoplace)
    exact = True
    for seed in seeds:
        print(f"seed {seed}: {len(points)} positions")
        for name in names:
            theirs = np.array(factorio[seed][name], dtype=np.float32)
            ours_ = np.array(mine[seed][name], dtype=np.float32)
            same = (theirs.view(np.uint32) == ours_.view(np.uint32)) | (np.isnan(theirs) & np.isnan(ours_))
            if same.all():
                continue
            exact = False
            bad = np.flatnonzero(~same)
            worst = np.nanmax(np.abs(theirs.astype(np.float64) - ours_))
            first = bad[0]
            print(f"  {name}: {len(bad)} differ, at most by {worst:.3g}; first at {points[first]}: "
                  f"factorio {theirs[first]!r} ours {ours_[first]!r}")
        if tiles:
            theirs, ours_ = factorio[seed]["tiles"], mine[seed]["tiles"]
            bad = [i for i, (a, b) in enumerate(zip(theirs, ours_)) if a != b]
            print(f"  tiles: {len(theirs) - len(bad)} of {len(theirs)} the same")
            exact &= not bad
            for i in bad[:10]:
                print(f"    at {tiles[0] + i % tiles[2]},{tiles[1] + i // tiles[2]}: factorio {theirs[i]}, ours {ours_[i]}")
            program = json.loads(PROGRAM.read_text(encoding="utf-8"))
            for kind, prototype_kind in (("entities", "entity"), ("decoratives", "decorative")):
                kinds = {p["name"] for p in program["prototypes"] if p["kind"] == prototype_kind}
                theirs_ = placed(factorio[seed][kind], tiles, kinds)
                ours__ = placed(mine[seed][kind], tiles, kinds)
                same, only = differences(theirs_, ours__)
                print(f"  {kind}: {same} of {sum(theirs_.values())} Factorio's the same, {len(only)} differ")
                exact &= not only
                for side, k in sorted(only, key=lambda o: (o[1][1], o[1][2]))[:10]:
                    print(f"    {side} only: {k}")
    print("every value is Factorio's, bit for bit" if exact else "not all the same")
    return 0 if exact else 1


if __name__ == "__main__":
    sys.exit(main())
