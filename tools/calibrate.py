#!/usr/bin/env python3
"""
Factorio's values against ours, expression by expression (docs/NEXT.md, the oracle).

    python tools/calibrate.py [--seeds 123,456] [--grid X0,Y0,W,H,STEP] [NAME ...]

Each NAME is a named expression or a root (`elevation`, `nauvis_bridge_billows`,
`tile:grass-1:probability`); with none, the climate and the tiles. Factorio samples them through
tools/oracle.py; ours come from a program with those names as extra roots, which
tools/gen_terrain.py writes to build/calibration/ and CalibrationRun evaluates. Our seed is not
Factorio's map, so what is compared is each value's spread over the grid and the seeds: its
quantiles, and for tiles the share each one wins.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import oracle  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
WORK = REPO / "build" / "calibration"
QUANTILES = (1, 5, 25, 50, 75, 95, 99)


def tile_names(program: dict) -> list[str]:
    return [p["name"] for p in program["prototypes"] if p["kind"] == "tile"]


def ours(names: list[str], seeds: list[int], grid: list[float], area: list[int] | None = None) -> dict[int, dict[str, np.ndarray]]:
    WORK.mkdir(parents=True, exist_ok=True)
    program = WORK / "program.json"
    subprocess.run([sys.executable, str(REPO / "tools" / "gen_terrain.py"), "--calibration", str(program),
                    "--roots", ",".join(n for n in names)], check=True, capture_output=True)
    request = WORK / "request.json"
    spec = {"program": str(program), "roots": names, "seeds": seeds, "grid": grid}
    if area:
        spec["area"] = area
    request.write_text(json.dumps(spec))
    gradle = REPO / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    try:
        subprocess.run([str(gradle), "test", "--tests", "*CalibrationRun*", "--rerun", "-q"], cwd=REPO, check=True,
                       capture_output=True)
    finally:
        request.unlink()
    out = {}
    for seed in seeds:
        values = json.loads((WORK / f"ours-{seed}.json").read_text())
        out[seed] = {k: np.array(v, dtype=float) for k, v in values.items()}
    return out


def placed(seeds: list[int], size: int) -> tuple[dict[int, dict[str, int]], dict[int, dict[str, int]]]:
    """How many of each thing Factorio's preview and our Terrain place in the square around 0,0."""
    program = json.loads((REPO / "src/main/resources/nauvis_terrain/noise/nauvis.json").read_text(encoding="utf-8"))
    things = [p["name"] for p in program["prototypes"] if p["kind"] == "entity" and p["name"] != "fish"]
    factorio = {}
    for seed in seeds:
        out = WORK / f"preview-{seed}.png"
        log = subprocess.run([str(oracle.FACTORIO), "--generate-map-preview", out.as_posix(), "--map-gen-seed",
                              str(seed), "--map-preview-size", str(size), "--report-quantities", ",".join(things)],
                             capture_output=True, text=True, check=True).stdout
        factorio[seed] = {m.group(1): int(m.group(2))
                          for m in re.finditer(r"([\w-]+): totalEntityCount=(\d+)", log)}
    ours(["elevation"], seeds, [0, 0, 1, 1, 1], [-size // 2, -size // 2, size, size])
    mine = {seed: json.loads((WORK / f"placed-{seed}.json").read_text()) for seed in seeds}
    return factorio, mine


def factorios(names: list[str], seeds: list[int], grid: list[float]) -> dict[int, dict[str, np.ndarray]]:
    probes = {f"p{i}": f"var('{n}')" for i, n in enumerate(names)}
    out = {}
    for seed in seeds:
        result = oracle.run({"seed": seed, "probes": probes,
                             "jobs": [{"properties": list(probes), "grid": grid}]})[0]
        out[seed] = {n: np.array(result[f"p{i}"], dtype=float) for i, n in enumerate(names)}
    return out


def pooled(by_seed: dict, name: str) -> np.ndarray:
    return np.concatenate([values[name] for values in by_seed.values()])


def tile_shares(by_seed: dict, tiles: list[str]) -> dict[str, float]:
    counts = dict.fromkeys(tiles, 0)
    total = 0
    for values in by_seed.values():
        probs = np.stack([values[f"tile:{t}:probability"] for t in tiles], 1)
        winners = probs.argmax(1)
        for i in winners:
            counts[tiles[i]] += 1
        total += len(winners)
    return {t: counts[t] / total for t in tiles}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("names", nargs="*")
    parser.add_argument("--seeds", default="123,456,789,1011")
    parser.add_argument("--grid", default="-1024,-1024,128,128,16")
    args = parser.parse_args()
    seeds = [int(s) for s in args.seeds.split(",")]
    grid = [float(v) for v in args.grid.split(",")]
    grid[2], grid[3] = int(grid[2]), int(grid[3])
    program = json.loads((REPO / "src/main/resources/nauvis_terrain/noise/nauvis.json").read_text(encoding="utf-8"))
    tiles = tile_names(program)
    names = args.names or ["elevation", "moisture", "aux", "temperature", "cliff_elevation", "cliffiness"]
    with_tiles = not args.names
    wanted = names + ([f"tile:{t}:probability" for t in tiles] if with_tiles else [])

    factorio = factorios(wanted, seeds, grid)
    mine = ours(wanted, seeds, grid)
    print(f"seeds {seeds}, grid {grid}; quantiles {QUANTILES}")
    for name in names:
        f = pooled(factorio, name)
        o = pooled(mine, name)
        fq = np.percentile(f, QUANTILES)
        oq = np.percentile(o, QUANTILES)
        print(f"{name}")
        print("  factorio " + " ".join(f"{v:9.3f}" for v in fq) + f"   mean {f.mean():9.3f}")
        print("  ours     " + " ".join(f"{v:9.3f}" for v in oq) + f"   mean {o.mean():9.3f}")
    if with_tiles:
        fs = tile_shares(factorio, tiles)
        os_ = tile_shares(mine, tiles)
        print("tile shares, factorio against ours:")
        for t in tiles:
            print(f"  {t:14s} {100 * fs[t]:5.1f}% {100 * os_[t]:5.1f}%")
    return 0


if __name__ == "__main__":
    sys.exit(main())
