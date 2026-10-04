#!/usr/bin/env python3
"""
Factorio as the oracle (docs/NEXT.md): its own values for any noise expression, and its own map.

    python tools/oracle.py run probes.json      sample named expressions, print a JSON result
    python tools/oracle.py preview SEED [SIZE]  Factorio's map preview of a seed, as a PNG

A run writes a throwaway mod and a config whose write-data is build/oracle/, so the game's own
settings, mods and saves are never touched. The mod defines each probe as a named noise
expression and, in on_init of a map made with `--create`, samples them with
`LuaSurface.calculate_tile_properties` and writes the values with `helpers.write_file`. A map is
made in about two seconds and nothing is drawn.

A probe file:

    {"seed": 123,
     "map_gen_settings": {...},                 optional, Factorio's format
     "probes": {"name": "expression", ...},
     "data": "data.raw.tile['grass-1'].autoplace = ...",   optional Lua for the mod's data stage
     "jobs": [{"properties": ["name", "elevation"],
               "grid": [x0, y0, width, height, step]},     positions x0 + i·step, y0 + j·step
              {"properties": [...], "points": [[x, y], ...]},
              {"tiles": [x0, y0, width, height]}]}        the generated chunks over an area

The result is a list with one entry per job: {"name": [values...]} in row order, or for an area
{"tiles": [names...], "entities": [[name, x, y, amount], ...], "decoratives": [[name, x, y, amount], ...]},
the entities those whose boxes reach into the area.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
FACTORIO = Path("F:/Steam/steamapps/common/Factorio/bin/x64/factorio.exe")
WORK = REPO / "build" / "oracle"
MOD = "nauvis-terrain-probe"

CONTROL = r"""
local jobs = require("jobs")

local function positions(job)
  if job.points then
    local out = {}
    for i, p in ipairs(job.points) do out[i] = {p[1], p[2]} end
    return out
  end
  local g = job.grid or job.tiles
  local step = g[5] or 1
  local out = {}
  for j = 0, g[4] - 1 do
    for i = 0, g[3] - 1 do
      out[#out + 1] = {g[1] + i * step, g[2] + j * step}
    end
  end
  return out
end

script.on_init(function()
  local surface = game.surfaces.nauvis
  local results = {}
  for n, job in ipairs(jobs) do
    if job.tiles then
      local t = job.tiles
      surface.request_to_generate_chunks({t[1] + t[3] / 2, t[2] + t[4] / 2}, math.ceil(math.max(t[3], t[4]) / 64) + 1)
      surface.force_generate_chunk_requests()
      local names = {}
      for _, p in ipairs(positions(job)) do
        names[#names + 1] = surface.get_tile(p[1], p[2]).name
      end
      local area = {{t[1], t[2]}, {t[1] + t[3], t[2] + t[4]}}
      local entities = {}
      for _, e in ipairs(surface.find_entities_filtered{area = area}) do
        entities[#entities + 1] = {e.name, e.position.x, e.position.y, e.type == "resource" and e.amount or 0}
      end
      local decoratives = {}
      for _, d in ipairs(surface.find_decoratives_filtered{area = area}) do
        decoratives[#decoratives + 1] = {d.decorative.name, d.position.x, d.position.y, d.amount}
      end
      results[n] = {tiles = names, entities = entities, decoratives = decoratives}
    else
      local all = positions(job)
      local merged = {}
      local chunk = 16384
      for first = 1, #all, chunk do
        local part = {}
        for i = first, math.min(#all, first + chunk - 1) do part[#part + 1] = all[i] end
        local values = surface.calculate_tile_properties(job.properties, part)
        for name, list in pairs(values) do
          merged[name] = merged[name] or {}
          local into = merged[name]
          for _, v in ipairs(list) do into[#into + 1] = v end
        end
      end
      results[n] = merged
    end
  end
  helpers.write_file("probe.json", helpers.table_to_json(results))
end)
"""


def lua(value) -> str:
    """A JSON value as a Lua literal."""
    if isinstance(value, dict):
        return "{" + ", ".join(f"[{json.dumps(k)}] = {lua(v)}" for k, v in value.items()) + "}"
    if isinstance(value, list):
        return "{" + ", ".join(lua(v) for v in value) + "}"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, str):
        return json.dumps(value)
    return repr(value)


def setup(probes: dict, jobs: list, data_lua: str = "") -> Path:
    data = WORK / "data"
    mods = data / "mods"
    if mods.exists():
        shutil.rmtree(mods)
    mod = mods / MOD
    mod.mkdir(parents=True)
    (mods / "mod-list.json").write_text(json.dumps(
        {"mods": [{"name": "base", "enabled": True}, {"name": MOD, "enabled": True}]}), encoding="utf-8")
    (mod / "info.json").write_text(json.dumps(
        {"name": MOD, "version": "0.1.0", "title": MOD, "author": "nauvis_terrain",
         "factorio_version": "2.0", "dependencies": ["base"]}), encoding="utf-8")
    expressions = [{"type": "noise-expression", "name": name, "expression": expression}
                   for name, expression in probes.items()]
    (mod / "data.lua").write_text(("data:extend(" + lua(expressions) + ")\n" if expressions else "") + data_lua,
                                  encoding="utf-8")
    (mod / "jobs.lua").write_text("return " + lua(jobs) + "\n", encoding="utf-8")
    (mod / "control.lua").write_text(CONTROL, encoding="utf-8")
    config = WORK / "config.ini"
    config.write_text(f"[path]\nread-data=__PATH__system-read-data__\nwrite-data={data.as_posix()}\n",
                      encoding="utf-8")
    return config


def run(spec: dict) -> list:
    """Factorio's values for a probe file's jobs."""
    config = setup(spec.get("probes", {}), spec["jobs"], spec.get("data", ""))
    output = WORK / "data" / "script-output" / "probe.json"
    if output.exists():
        output.unlink()
    args = [str(FACTORIO), "--config", config.as_posix(), "--create", (WORK / "probe.zip").as_posix(),
            "--map-gen-seed", str(spec.get("seed", 123))]
    if "map_gen_settings" in spec:
        settings = WORK / "map-gen-settings.json"
        settings.write_text(json.dumps(spec["map_gen_settings"]), encoding="utf-8")
        args += ["--map-gen-settings", settings.as_posix()]
    done = subprocess.run(args, capture_output=True, text=True)
    if not output.exists():
        sys.stderr.write(done.stdout[-4000:] + done.stderr[-2000:])
        raise RuntimeError("Factorio wrote no probe output; its log is above")
    # Factorio writes infinities and NaN as bare words, which JSON has no spelling for, and -0.0 as
    # -0, which JSON reads as the integer 0.
    text = re.sub(r"([\[,:])(-?)(inf|nan)\b",
                  lambda m: m.group(1) + m.group(2) + ("Infinity" if m.group(3) == "inf" else "NaN"),
                  output.read_text(encoding="utf-8"))
    return json.loads(re.sub(r"([\[,:])-0(?=[,\]}])", r"\1-0.0", text))


def preview(seed: int, size: int = 512, out: Path | None = None, settings: dict | None = None) -> Path:
    """Factorio's own map preview of a seed."""
    out = out or WORK / f"preview-{seed}-{size}.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    args = [str(FACTORIO), "--generate-map-preview", out.as_posix(), "--map-gen-seed", str(seed),
            "--map-preview-size", str(size)]
    if settings:
        path = WORK / "map-gen-settings.json"
        path.write_text(json.dumps(settings), encoding="utf-8")
        args += ["--map-gen-settings", path.as_posix()]
    subprocess.run(args, capture_output=True, text=True, check=True)
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    r = sub.add_parser("run")
    r.add_argument("spec")
    p = sub.add_parser("preview")
    p.add_argument("seed", type=int)
    p.add_argument("size", type=int, nargs="?", default=512)
    args = parser.parse_args()
    if args.command == "run":
        print(json.dumps(run(json.loads(Path(args.spec).read_text(encoding="utf-8")))))
    else:
        print(preview(args.seed, args.size))
    return 0


if __name__ == "__main__":
    sys.exit(main())
