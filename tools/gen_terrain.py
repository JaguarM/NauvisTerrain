#!/usr/bin/env python3
"""
Generate the noise program from Factorio's data.raw plus data/terrain.json (CLAUDE.md, rule 2).

    reference/factorio/data-raw-<version>.json + data/terrain.json
        -> src/main/resources/nauvis_terrain/noise/nauvis.json

Every expression Nauvis's map gen reaches is parsed, its names resolved by Factorio's rules
(docs/NOISE.md), noise functions inlined, constants folded and identical nodes merged into one
graph. A node is an array, an operation and the numbers of earlier nodes:

    ["const", 1.5]                       a number; "inf" and "-inf" as strings
    ["input", "map_seed"]                x, y, or a number from the map settings
    ["points", "starting_positions"]     a list of positions from the map settings
    ["property", "elevation", {"": 7, "elevation_lakes": 9}]
                                         the node the map settings' property_expression_names
                                         pick by value, "" when they name none
    ["add", 3, 4]                        any other operation, on nodes

The roots are the climate properties and the probability, and richness where there is one, of
every tile, entity and decorative Nauvis places, named as Factorio's expressions name them
(`tile:grass-1:probability`). `prototypes` carries what the world needs of each besides its
noise, `cliff` the cliff's.

Usage:
    python tools/gen_terrain.py             summary only
    python tools/gen_terrain.py --check     fail if the program on disk differs
    python tools/gen_terrain.py --write     write the program
"""

from __future__ import annotations

import argparse
import json
import math
import re
import struct
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from factorio_noise import ParseError, parse  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
FACTORIO_VERSION = "2.0.77"
DATA_RAW = REPO / "reference" / "factorio" / f"data-raw-{FACTORIO_VERSION}.json"
TERRAIN = REPO / "data" / "terrain.json"
LOCALE = REPO / "reference" / "factorio" / "locale" / "en" / "base.cfg"
CORE_LOCALE = REPO / "reference" / "factorio" / "locale" / "en" / "core.cfg"
PROGRAM = REPO / "src" / "main" / "resources" / "nauvis_terrain" / "noise" / "nauvis.json"

CLIMATE = ("elevation", "moisture", "aux", "temperature", "cliff_elevation", "cliffiness")

NUMBER_INPUTS = {
    "x", "y", "map_seed", "map_seed_small", "map_seed_normalized", "map_width", "map_height",
    "starting_area_radius", "cliff_elevation_0", "cliff_elevation_interval", "cliff_smoothing",
    "cliff_richness", "peaceful_mode", "no_enemies_mode",
    "control:moisture:frequency", "control:moisture:bias", "control:aux:frequency",
    "control:aux:bias", "control:temperature:frequency", "control:temperature:bias",
}
POINT_INPUTS = {"starting_positions", "starting_lake_positions"}
CONSTANTS = {"true": 1.0, "false": 0.0, "e": math.e, "pi": math.pi, "inf": math.inf}

# Autoplace kinds, as Factorio's variables name them, and the prototype types of each.
TILE, ENTITY, DECORATIVE = "tile", "entity", "decorative"
KIND_TYPES = {TILE: ("tile",), DECORATIVE: ("optimized-decorative",)}

REQUIRED = object()
ABSENT = object()

# Built-in functions: each parameter's name, default, and whether it must be the same everywhere
# on the map ("constant" in Factorio's docs). The order is the node's argument order.
_SCALES = [("input_scale", 1.0, True), ("output_scale", 1.0, True),
           ("offset_x", 0.0, True), ("offset_y", 0.0, True)]
_XY = [("x", REQUIRED, False), ("y", REQUIRED, False)]
_SEEDS = [("seed0", REQUIRED, True), ("seed1", REQUIRED, True)]
BUILTINS = {
    **{name: [("value", REQUIRED, False)] for name in ("abs", "ceil", "cos", "floor", "log2", "sin", "sqrt")},
    "atan2": [("y", REQUIRED, False), ("x", REQUIRED, False)],
    "clamp": [("value", REQUIRED, False), ("min", REQUIRED, False), ("max", REQUIRED, False)],
    "if": [("condition", REQUIRED, False), ("true_branch", REQUIRED, False), ("false_branch", REQUIRED, False)],
    "pow": [("value", REQUIRED, False), ("exponent", REQUIRED, False)],
    "basis_noise": _XY + _SEEDS + _SCALES,
    "multioctave_noise": _XY + [("persistence", REQUIRED, True)] + _SEEDS
                         + [("octaves", REQUIRED, True)] + _SCALES,
    "variable_persistence_multioctave_noise": _XY + [("persistence", REQUIRED, False)] + _SEEDS
                                              + [("octaves", REQUIRED, True)] + _SCALES,
    "quick_multioctave_noise": _XY + _SEEDS + [("octaves", REQUIRED, True)] + _SCALES
                               + [("octave_input_scale_multiplier", 0.5, True),
                                  ("octave_output_scale_multiplier", 2.0, True),
                                  ("octave_seed0_shift", 1.0, True)],
    "distance_from_nearest_point": _XY + [("points", REQUIRED, True), ("maximum_distance", math.inf, True)],
    "distance_from_nearest_point_x": _XY + [("points", REQUIRED, True)],
    "distance_from_nearest_point_y": _XY + [("points", REQUIRED, True)],
    "random_penalty": _XY + [("source", REQUIRED, False), ("seed", 1.0, True), ("amplitude", 1.0, True)],
    "spot_noise": _XY + [
        ("density_expression", REQUIRED, False), ("spot_quantity_expression", REQUIRED, False),
        ("spot_radius_expression", REQUIRED, False), ("spot_favorability_expression", REQUIRED, False),
    ] + _SEEDS + [
        ("basement_value", REQUIRED, True), ("maximum_spot_basement_radius", REQUIRED, True),
        ("region_size", 512.0, True), ("skip_offset", 0.0, True), ("skip_span", 1.0, True),
        ("hard_region_target_quantity", 1.0, True), ("candidate_point_count", ABSENT, True),
        ("candidate_spot_count", ABSENT, True), ("suggested_minimum_candidate_point_spacing", ABSENT, True),
    ],
}
# Positional only, any count.
VARIADIC = {"min", "max", "expression_in_range"}
# Factorio's, but nothing Nauvis places reaches them, so the evaluator does not have them.
NOT_WRITTEN = {"multisample", "ridge", "terrace", "pow_precise", "voronoi_spot_noise",
               "voronoi_facet_noise", "voronoi_pyramid_noise", "voronoi_cell_id"}

BINARY_OPS = {"+": "add", "-": "sub", "*": "mul", "/": "div", "%": "mod", "%%": "fmod", "^": "pow",
              "<": "lt", "<=": "le", ">": "gt", ">=": "ge", "==": "eq", "!=": "ne",
              "&": "and", "~": "xor", "|": "or"}
COMMUTATIVE = {"add", "mul", "eq", "ne", "and", "xor", "or"}
# The operations NoiseProgram.Op has: what Nauvis reaches once constants are folded.
EVALUATED = {"const", "input", "points", "property", "add", "sub", "mul", "div", "pow", "gt", "ge", "neg", "abs",
             "sqrt", "log2", "clamp", "if", "min", "max", "basis_noise", "multioctave_noise",
             "variable_persistence_multioctave_noise", "quick_multioctave_noise", "distance_from_nearest_point",
             "random_penalty", "spot_noise"}
NOISE_OPS = {"basis_noise", "multioctave_noise", "variable_persistence_multioctave_noise",
             "quick_multioctave_noise", "distance_from_nearest_point", "distance_from_nearest_point_x",
             "distance_from_nearest_point_y", "random_penalty", "spot_noise"}


class GenError(Exception):
    """A fault in the inputs or a name that does not resolve. Always fatal."""


def f32(value: float) -> float:
    """`value` rounded to a 32-bit float, as Factorio's numbers are."""
    if math.isnan(value) or math.isinf(value):
        return value
    try:
        return struct.unpack("<f", struct.pack("<f", value))[0]
    except OverflowError:
        return math.copysign(math.inf, value)


def i32(value: float) -> int:
    """A float cast to a signed 32-bit integer, as the bitwise operators do."""
    n = int(value) & 0xFFFFFFFF
    return n - (1 << 32) if n >= 1 << 31 else n


def _fold(op: str, v: list[float]) -> float:
    """An operation on constants as Factorio's compiler folds it: on the numbers as floats, to a float."""
    try:
        return f32(_folded(op, [f32(a) for a in v]))
    except (ValueError, ZeroDivisionError):
        return math.nan
    except OverflowError:
        return math.inf


def _folded(op: str, v: list[float]) -> float:
    if op == "add": return v[0] + v[1]
    if op == "sub": return v[0] - v[1]
    if op == "mul": return v[0] * v[1]
    if op == "div": return v[0] / v[1] if v[1] else math.copysign(math.inf, v[0]) if v[0] else math.nan
    if op == "mod": return v[0] - math.floor(v[0] / v[1]) * v[1]
    if op == "fmod": return math.fmod(v[0], v[1])
    if op == "pow": return math.inf if v[0] == 0 and v[1] < 0 else math.pow(v[0], v[1])
    if op == "lt": return float(v[0] < v[1])
    if op == "le": return float(v[0] <= v[1])
    if op == "gt": return float(v[0] > v[1])
    if op == "ge": return float(v[0] >= v[1])
    if op == "eq": return float(v[0] == v[1])
    if op == "ne": return float(v[0] != v[1])
    if op == "and": return float(i32(v[0]) & i32(v[1]))
    if op == "xor": return float(i32(v[0]) ^ i32(v[1]))
    if op == "or": return float(i32(v[0]) | i32(v[1]))
    if op == "neg": return -v[0]
    if op == "not": return float(~i32(v[0]))
    if op == "abs": return abs(v[0])
    if op == "ceil": return float(math.ceil(v[0]))
    if op == "floor": return float(math.floor(v[0]))
    if op == "cos": return math.cos(v[0])
    if op == "sin": return math.sin(v[0])
    if op == "sqrt": return math.sqrt(v[0])
    if op == "log2": return math.log2(v[0]) if v[0] else -math.inf
    if op == "atan2": return math.atan2(v[0], v[1])
    if op == "clamp": return min(max(v[0], v[1]), v[2])
    if op == "if": return v[1] if v[0] > 0 else v[2]
    if op == "min": return min(v)
    if op == "max": return max(v)
    raise AssertionError(op)


FOLDABLE = set(BINARY_OPS.values()) | {"neg", "not", "abs", "ceil", "floor", "cos", "sin", "sqrt",
                                       "log2", "atan2", "clamp", "if", "min", "max"}


class Graph:
    """Numbered nodes, each made once: adding an existing node returns its number."""

    def __init__(self):
        self.nodes: list[list] = []
        self.index: dict = {}
        # Whether a node can differ between two positions on the map.
        self.varies: list[bool] = []

    def _add(self, node: list, varies: bool) -> int:
        key = json.dumps(node)
        if key not in self.index:
            self.index[key] = len(self.nodes)
            self.nodes.append(node)
            self.varies.append(varies)
        return self.index[key]

    def const(self, value: float) -> int:
        """A number as Factorio keeps a constant, in double: a literal's own value, a fold's float."""
        return self._add(["const", value + 0.0], False)

    def value(self, n: int) -> float | None:
        node = self.nodes[n]
        return node[1] if node[0] == "const" else None

    def input(self, name: str) -> int:
        return self._add(["input", name], name in ("x", "y"))

    def points(self, name: str) -> int:
        return self._add(["points", name], False)

    def property(self, name: str, variants: dict[str, int]) -> int:
        return self._add(["property", name, variants], any(self.varies[n] for n in variants.values()))

    def op(self, op: str, *args: int) -> int:
        args = list(args)
        values = [self.value(a) for a in args]
        if op in FOLDABLE and all(v is not None for v in values):
            return self.const(_fold(op, values))
        if op == "if" and values[0] is not None:
            return args[1] if values[0] > 0 else args[2]
        simpler = self._identity(op, args, values)
        if simpler is not None:
            return simpler
        if op in COMMUTATIVE:
            args.sort()
        return self._add([op, *args], any(self.varies[a] for a in args))

    def _identity(self, op: str, args: list[int], values: list) -> int | None:
        """Factorio's arithmetic identities (its docs, the performance tips)."""
        if op == "add":
            if values[0] == 0: return args[1]
            if values[1] == 0: return args[0]
        if op == "sub":
            if values[1] == 0: return args[0]
            if values[0] == 0: return self.op("neg", args[1])
        if op == "mul":
            if values[0] == 1: return args[1]
            if values[1] == 1: return args[0]
            if values[0] == 0 or values[1] == 0: return self.const(0.0)
            if values[0] == -1: return self.op("neg", args[1])
            if values[1] == -1: return self.op("neg", args[0])
        if op == "div":
            if values[1] == 1: return args[0]
            if values[1] == -1: return self.op("neg", args[0])
        if op == "pow":
            if values[1] == 0: return self.const(1.0)
            if values[1] == 1: return args[0]
            if values[1] == 0.5: return self.op("sqrt", args[0])
            if values[1] == 2: return self.op("mul", args[0], args[0])
        return None


class Scope:
    """Names bound inside a noise function, a named expression or an autoplace."""

    def __init__(self, parent: Scope | None, where: str, params: dict[str, int] | None = None,
                 local_expressions: dict | None = None, local_functions: dict | None = None):
        self.parent = parent
        self.where = where
        self.params = params or {}
        self.local_expressions = local_expressions or {}
        self.local_functions = local_functions or {}
        self.compiled: dict[str, int] = {}


class Compiler:
    """Factorio's expressions, for one planet, as one graph."""

    def __init__(self, raw: dict, properties: dict[str, list]):
        self.raw = raw
        self.graph = Graph()
        self.expressions = raw["noise-expression"]
        self.functions = raw["noise-function"]
        self.controls = raw["autoplace-control"]
        # name -> the values the presets give it; each is a variant of a "property" node.
        self.properties = properties
        self.compiled_globals: dict[str, int] = {}
        self.inlined: dict[tuple, int] = {}
        self.busy: list[str] = []

    # -- names ------------------------------------------------------------------------------

    def name(self, name: str, scope: Scope | None) -> int:
        s = scope
        while s is not None:
            if name in s.params:
                return s.params[name]
            if name in s.local_expressions:
                if name not in s.compiled:
                    s.compiled[name] = self._guarded(
                        f"{s.where}/{name}", lambda: self.expression(s.local_expressions[name], s))
                return s.compiled[name]
            s = s.parent
        if name not in self.compiled_globals:
            self.compiled_globals[name] = self._guarded(name, lambda: self._global(name))
        return self.compiled_globals[name]

    def _guarded(self, key: str, compile_it) -> int:
        if key in self.busy:
            raise GenError(f"{key} refers to itself: {' -> '.join(self.busy + [key])}")
        self.busy.append(key)
        try:
            return compile_it()
        finally:
            self.busy.pop()

    def _global(self, name: str) -> int:
        if name in self.properties:
            variants = {"": self._prototype_or_builtin(name)}
            for value in self.properties[name]:
                key = value if isinstance(value, str) else json.dumps(value)
                variants[key] = self.expression(value, None)
            return self.graph.property(name, variants)
        return self._prototype_or_builtin(name)

    def _prototype_or_builtin(self, name: str) -> int:
        if name in self.expressions:
            proto = self.expressions[name]
            scope = Scope(None, name, local_expressions=proto.get("local_expressions"))
            return self.expression(proto["expression"], scope)
        if name in CONSTANTS:
            return self.graph.const(CONSTANTS[name])
        if name in NUMBER_INPUTS:
            return self.graph.input(name)
        if name in POINT_INPUTS:
            return self.graph.points(name)
        m = re.fullmatch(r"control:(.+):(frequency|size|richness)", name)
        if m:
            if m.group(1) not in self.controls:
                raise GenError(f"{name}: no autoplace control is called {m.group(1)!r}")
            return self.graph.input(name)
        m = re.fullmatch(r"(tile|entity|decorative):(.+):(probability|richness)", name)
        if m:
            kind, proto_name, which = m.groups()
            autoplace = self.autoplace_of(kind, proto_name)
            expression = autoplace.get(f"{which}_expression")
            if expression is None:
                raise GenError(f"{name}: {proto_name}'s autoplace has no {which}_expression")
            scope = Scope(None, name, local_expressions=autoplace.get("local_expressions"),
                          local_functions=autoplace.get("local_functions"))
            return self.expression(expression, scope)
        raise GenError(f"{name!r} is not a named expression, a map setting or a built-in")

    def autoplace_of(self, kind: str, name: str) -> dict:
        for type_name in KIND_TYPES.get(kind, ()) or entity_types(self.raw):
            proto = self.raw.get(type_name, {}).get(name)
            if proto and "autoplace" in proto:
                return proto["autoplace"]
        raise GenError(f"no {kind} called {name!r} has an autoplace")

    def function(self, name: str, scope: Scope | None) -> tuple[dict, Scope | None] | None:
        s = scope
        while s is not None:
            if name in s.local_functions:
                return s.local_functions[name], s
            s = s.parent
        if name in self.functions:
            return self.functions[name], None
        return None

    # -- expressions ------------------------------------------------------------------------

    def expression(self, expression, scope: Scope | None) -> int:
        try:
            tree = parse(expression)
        except ParseError as e:
            raise GenError(f"{scope.where if scope else 'a preset'}: {e}") from None
        return self.tree(tree, scope)

    def tree(self, tree: tuple, scope: Scope | None) -> int:
        kind = tree[0]
        if kind == "num":
            return self.graph.const(tree[1])
        if kind == "str":
            return self.graph.const(noise_layer_id(tree[1]))
        if kind == "id":
            return self.name(tree[1], scope)
        if kind == "un":
            a = self.tree(tree[2], scope)
            return a if tree[1] == "+" else self.graph.op("neg" if tree[1] == "-" else "not", a)
        if kind == "bin":
            return self.graph.op(BINARY_OPS[tree[1]], self.tree(tree[2], scope), self.tree(tree[3], scope))
        return self.call(tree[1], tree[2], tree[3], scope)

    def call(self, name: str, args: list | None, kwargs: dict | None, scope: Scope | None) -> int:
        where = scope.where if scope else "a preset"
        found = self.function(name, scope)
        if found is not None:
            definition, definition_scope = found
            return self.inline(name, definition, definition_scope, args, kwargs, scope)
        if name == "var":
            if not args or len(args) != 1 or args[0][0] != "str":
                raise GenError(f"{where}: var takes one string")
            return self.name(args[0][1], scope)
        if name == "noise_layer_id":
            if not args or len(args) != 1 or args[0][0] != "str":
                raise GenError(f"{where}: noise_layer_id takes one string")
            return self.graph.const(noise_layer_id(args[0][1]))
        if name in NOT_WRITTEN:
            raise GenError(f"{where} reaches the built-in {name}, which is not written (docs/NOISE.md)")
        if name in VARIADIC:
            if kwargs is not None:
                raise GenError(f"{where}: {name} takes positional arguments only")
            return self.variadic(name, [self.tree(a, scope) for a in args], where)
        if name not in BUILTINS:
            raise GenError(f"{where} calls {name!r}, which is not a function or a built-in")
        params = BUILTINS[name]
        given = self.bind(name, [p[0] for p in params], args, kwargs, where, scope)
        values = []
        for param, default, constant in params:
            if param in given:
                n = self.tree(given[param], scope)
                if constant and self.graph.varies[n]:
                    raise GenError(f"{where}: {name}'s {param} must not vary across the map")
            elif default is REQUIRED:
                raise GenError(f"{where}: {name} needs {param}")
            elif default is ABSENT:
                n = None
            else:
                n = self.graph.const(default)
            values.append(n)
        if name == "spot_noise":
            values = self.spot_noise_counts(values, where)
        if name in ("distance_from_nearest_point", "distance_from_nearest_point_x",
                    "distance_from_nearest_point_y") and self.graph.nodes[values[2]][0] != "points":
            raise GenError(f"{where}: {name}'s points must be a list of positions")
        return self.graph.op(name, *values)

    def spot_noise_counts(self, values: list, where: str) -> list[int]:
        """candidate_spot_count is candidate_point_count / skip_span; the node carries the latter."""
        point_count, spot_count, spacing = values[14], values[15], values[16]
        if point_count is None:
            if spot_count is None:
                raise GenError(f"{where}: spot_noise needs candidate_point_count or candidate_spot_count")
            point_count = self.graph.op("mul", spot_count, values[12])
        if spacing is None:
            raise GenError(f"{where}: spot_noise's default suggested_minimum_candidate_point_spacing "
                           "is not written (docs/NOISE.md)")
        return values[:14] + [point_count, spacing]

    def variadic(self, name: str, values: list[int], where: str) -> int:
        if name in ("min", "max"):
            if len(values) < 2:
                raise GenError(f"{where}: {name} needs two arguments or more")
            return self.graph.op(name, *values)
        ranges = len(values) - 2
        if ranges < 3 or ranges % 3:
            raise GenError(f"{where}: expression_in_range needs two numbers and three lists of one length")
        dims = ranges // 3
        numbers = [self.graph.value(n) for n in values[:2] + values[2 + dims:]]
        if any(v is None for v in numbers):
            raise GenError(f"{where}: expression_in_range's bounds must be numbers")
        return self.expression_in_range(numbers[0], numbers[1], values[2:2 + dims], numbers[2:2 + dims],
                                        numbers[2 + dims:])

    def expression_in_range(self, multiplier: float, maximum: float, inputs: list[int], lows: list[float],
                            highs: list[float]) -> int:
        """Factorio's expansion (docs/NOISE.md): per range, its half-width less the distance from its middle."""
        result = None
        for value, low, high in zip(inputs, lows, highs):
            middle = self.graph.const((high + low) * 0.5)
            half = self.graph.const((high - low) * 0.5)
            peak = self.graph.op("sub", half, self.graph.op("abs", self.graph.op("sub", value, middle)))
            if multiplier != 1:
                peak = self.graph.op("mul", peak, self.graph.const(multiplier))
            if maximum < math.inf:
                peak = self.graph.op("min", peak, self.graph.const(maximum))
            result = peak if result is None else self.graph.op("min", result, peak)
        return result

    def bind(self, name: str, params: list[str], args, kwargs, where: str, scope) -> dict:
        if args is not None:
            if len(args) > len(params):
                raise GenError(f"{where}: {name} takes {len(params)} arguments, given {len(args)}")
            return dict(zip(params, args))
        unknown = set(kwargs) - set(params)
        if unknown:
            raise GenError(f"{where}: {name} has no parameter {sorted(unknown)}")
        return kwargs

    def inline(self, name: str, definition: dict, definition_scope: Scope | None,
               args, kwargs, scope: Scope | None) -> int:
        where = scope.where if scope else "a preset"
        params = definition["parameters"]
        given = self.bind(name, params, args, kwargs, where, scope)
        missing = [p for p in params if p not in given]
        if missing:
            raise GenError(f"{where}: {name} needs {missing}")
        bound = {p: self.tree(given[p], scope) for p in params}
        # The scope itself, not id(scope): docs/PITFALLS.md.
        key = (id(definition), definition_scope, tuple(bound[p] for p in params))
        if key not in self.inlined:
            inner = Scope(definition_scope, name, params=bound,
                          local_expressions=definition.get("local_expressions"),
                          local_functions=definition.get("local_functions"))
            self.inlined[key] = self._guarded(f"{name}()", lambda: self.expression(definition["expression"], inner))
        return self.inlined[key]


def noise_layer_id(name: str) -> float:
    """A string as a number, as a seed1 takes it: its CRC32."""
    return float(zlib.crc32(name.encode("utf-8")))


def entity_types(raw: dict) -> list[str]:
    """Every prototype type that is placed as an entity: all but tiles and decoratives."""
    skip = {t for types in KIND_TYPES.values() for t in types}
    return [t for t, protos in raw.items() if t not in skip and isinstance(protos, dict)
            and any(isinstance(p, dict) and "autoplace" in p for p in protos.values())]


# -- what the planet places -------------------------------------------------------------------

def placed(raw: dict, terrain: dict) -> list[dict]:
    """Every tile, entity and decorative the planet places, by ARCHITECTURE's autoplace rule."""
    planet = raw["planet"][terrain["planet"]]["map_gen_settings"]
    settings = planet.get("autoplace_settings", {})
    not_placed = terrain["not_placed"]
    out = []
    seen_skips = set()
    for kind in (TILE, ENTITY, DECORATIVE):
        named = settings.get(kind, {}).get("settings", {})
        for type_name in KIND_TYPES.get(kind) or entity_types(raw):
            for name, proto in raw.get(type_name, {}).items():
                autoplace = proto.get("autoplace") if isinstance(proto, dict) else None
                if autoplace is None:
                    continue
                if name not in named and autoplace.get("default_enabled") is False:
                    continue
                if name in not_placed:
                    seen_skips.add(name)
                    continue
                out.append({"kind": kind, "type": type_name, "name": name, "proto": proto})
    stale = set(not_placed) - seen_skips
    if stale:
        raise GenError(f"data/terrain.json's not_placed names {sorted(stale)}, which the planet does not place")
    out.sort(key=lambda p: ((TILE, ENTITY, DECORATIVE).index(p["kind"]), p["type"], p["name"]))
    return out


def collision_mask(raw: dict, type_name: str, proto: dict) -> dict:
    """A prototype's collision layers, its own or its type's default, with the two flags placing reads."""
    mask = proto.get("collision_mask")
    if mask is None:
        key = "decorative" if type_name == "optimized-decorative" else type_name
        mask = raw["utility-constants"]["default"]["default_collision_masks"].get(key)
        if mask is None:
            raise GenError(f"{proto['name']} has no collision mask and its type {type_name} no default")
    return {
        "layers": sorted(layer for layer, on in mask["layers"].items() if on),
        "tiles_only": bool(mask.get("colliding_with_tiles_only")),
        "not_colliding_with_itself": bool(mask.get("not_colliding_with_itself")),
    }


def load_locale(path: Path) -> dict[str, dict[str, str]]:
    """Factorio's English strings by section and key."""
    sections: dict[str, dict[str, str]] = {}
    current: dict[str, str] | None = None
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("[") and line.endswith("]"):
            current = sections.setdefault(line[1:-1], {})
        elif "=" in line and current is not None:
            key, value = line.split("=", 1)
            current[key] = value
    return sections


def title(proto: dict, kind: str, locale: dict) -> str:
    """A prototype's English name: its `localised_name` key, or its own name in its kind's section."""
    named = proto.get("localised_name")
    if isinstance(named, list) and len(named) == 1 and isinstance(named[0], str) and "." in named[0]:
        section, key = named[0].split(".", 1)
    else:
        section, key = f"{kind}-name", proto["name"]
    found = locale.get(section, {}).get(key)
    if found is None:
        raise GenError(f"{proto['name']} has no English name under [{section}] {key}")
    return found


# Factorio's names for a slider's settings, as its map generator screen labels them, by control category.
SLIDER_LABELS = {
    "resource": {"frequency": "frequency", "size": "size", "richness": "richness"},
    "terrain": {"frequency": "scale", "size": "coverage"},
    "climate": {"frequency": "scale", "bias": "bias"},
}
SLIDER_TOOLTIPS = {
    "resource": {"frequency": "resource-frequency-description", "size": "resource-size-description",
                 "richness": "resource-richness-description"},
    "terrain": {"frequency": "terrain-scale-description", "size": "terrain-coverage-description"},
    "climate": {"frequency": "terrain-scale-description", "bias": "terrain-bias-description"},
}
# Factorio's map generator screen shows the climate of these two; temperature has no slider there.
CLIMATE_TITLES = {"moisture": "moisture", "aux": "aux"}
CLIMATE_ORDER = list(CLIMATE_TITLES)
# A preset's slider value by name (docs/FACTORIO.md, presets).
SLIDER_NAMES = {"none": 0.0, "very-low": 0.5, "very-small": 0.5, "very-poor": 0.5,
                "low": 2 ** -0.5, "small": 2 ** -0.5, "poor": 2 ** -0.5,
                "normal": 1.0, "medium": 1.0, "regular": 1.0,
                "high": 2 ** 0.5, "big": 2 ** 0.5, "good": 2 ** 0.5,
                "very-high": 2.0, "very-big": 2.0, "very-good": 2.0}


def controls(raw: dict, nodes: list, cliff_control: str, locale: dict, core: dict) -> list[dict]:
    """Every slider the program reads, in Factorio's order and words: the map generator screen's rows."""
    gui = core["gui-map-generator"]
    read = {n[1] for n in nodes if n[0] == "input" and n[1].startswith("control:")}
    names = sorted({key.split(":")[1] for key in read}, key=lambda n: (
        ["resource", "terrain", "climate"].index(category(raw, n)) if category(raw, n) else 9,
        CLIMATE_ORDER.index(n) if n in CLIMATE_ORDER else raw["autoplace-control"].get(n, {}).get("order", n)))
    out = []
    for name in names:
        kind = category(raw, name)
        if kind is None:
            continue
        if kind == "climate":
            title = gui[CLIMATE_TITLES[name]]
        elif kind == "resource":
            title = locale["entity-name"][name]
        else:
            title = locale["autoplace-control-names"][name]
        settings = [{"key": f"control:{name}:{setting}", "label": gui[label],
                     "tooltip": gui[SLIDER_TOOLTIPS[kind][setting]]}
                    for setting, label in SLIDER_LABELS[kind].items() if f"control:{name}:{setting}" in read]
        out.append({"name": name, "title": title, "kind": kind, "settings": settings})
    out.append({"name": cliff_control, "title": locale["autoplace-control-names"][cliff_control], "kind": "cliff",
                "settings": [{"key": "cliff_frequency", "label": gui["cliff-frequency"],
                              "tooltip": gui["cliff-frequency-description"]},
                             {"key": "cliff_continuity", "label": gui["cliff-continuity"],
                              "tooltip": gui["cliff-continuity-description"]}]})
    return out


def category(raw: dict, name: str) -> str | None:
    if name in CLIMATE_TITLES:
        return "climate"
    control = raw["autoplace-control"].get(name)
    return control["category"] if control and control["category"] in ("resource", "terrain") else None


def presets(raw: dict, program_controls: list, properties: dict, locale: dict) -> list[dict]:
    """The presets that change what this world places, as slider values and properties; not one with a map size."""
    sliders = {s["key"] for c in program_controls for s in c["settings"]}
    out = []
    for name, preset in sorted((n, p) for n, p in raw["map-gen-presets"]["default"].items() if isinstance(p, dict)):
        basic = preset.get("basic_settings", {})
        if "width" in basic or "height" in basic:
            continue
        values = {}
        for control, settings in basic.get("autoplace_controls", {}).items():
            for setting, value in settings.items():
                key = f"control:{control}:{setting}"
                if key in sliders:
                    values[key] = SLIDER_NAMES[value] if isinstance(value, str) else float(value)
        names = {k: v if isinstance(v, str) else json.dumps(v)
                 for k, v in basic.get("property_expression_names", {}).items() if k in properties}
        smoothing = basic.get("cliff_settings", {}).get("cliff_smoothing", 0)
        if name != "default" and not values and not names and not smoothing:
            continue
        out.append({"name": name, "title": locale["map-gen-preset-name"][name], "order": preset["order"],
                    "controls": values, "properties": names, "cliff_smoothing": smoothing})
    out.sort(key=lambda p: (p["order"], p["name"]))
    return out


def colour(value, scale: float) -> list[int] | None:
    if value is None:
        return None
    if isinstance(value, dict):
        value = [value["r"], value["g"], value["b"]]
    return [round(c * scale) for c in value[:3]]


def preset_properties(raw: dict) -> dict[str, list]:
    """Each property a preset swaps through property_expression_names, and the values it takes."""
    out: dict[str, list] = {}
    for name, preset in raw["map-gen-presets"]["default"].items():
        if not isinstance(preset, dict):
            continue
        for key, value in preset.get("basic_settings", {}).get("property_expression_names", {}).items():
            values = out.setdefault(key, [])
            if value not in values:
                values.append(value)
    return {k: v for k, v in sorted(out.items())}


def every_expression(raw: dict):
    """(where, expression) for every expression in the dump: the parser's test."""
    def with_locals(where, holder):
        for name, expression in holder.get("local_expressions", {}).items():
            yield f"{where}/{name}", expression
        for name, function in holder.get("local_functions", {}).items():
            yield f"{where}/{name}()", function["expression"]
            yield from with_locals(f"{where}/{name}()", function)

    for name, proto in raw["noise-expression"].items():
        yield name, proto["expression"]
        yield from with_locals(name, proto)
    for name, proto in raw["noise-function"].items():
        yield f"{name}()", proto["expression"]
        yield from with_locals(f"{name}()", proto)
    for type_name, protos in raw.items():
        if not isinstance(protos, dict):
            continue
        for name, proto in protos.items():
            autoplace = proto.get("autoplace") if isinstance(proto, dict) else None
            if autoplace:
                for which in ("probability_expression", "richness_expression"):
                    if which in autoplace:
                        yield f"{name}.{which}", autoplace[which]
                yield from with_locals(name, autoplace)
    for key, values in preset_properties(raw).items():
        for value in values:
            yield f"preset {key}", value


def parse_everything(raw: dict) -> int:
    count = 0
    for where, expression in every_expression(raw):
        try:
            parse(expression)
        except ParseError as e:
            raise GenError(f"{where}: {e}") from None
        count += 1
    return count


# -- the program ------------------------------------------------------------------------------

def build(raw: dict, terrain: dict, locale: dict, core: dict, extra_roots: tuple[str, ...] = ()) -> dict:
    compiler = Compiler(raw, preset_properties(raw))
    roots: dict[str, int] = {}
    for name in CLIMATE + tuple(extra_roots):
        roots[name] = compiler.name(name, None)
    prototypes = []
    for p in placed(raw, terrain):
        autoplace = p["proto"]["autoplace"]
        base = f"{p['kind']}:{p['name']}"
        roots[f"{base}:probability"] = compiler.name(f"{base}:probability", None)
        has_richness = "richness_expression" in autoplace
        if has_richness:
            roots[f"{base}:richness"] = compiler.name(f"{base}:richness", None)
        proto = p["proto"]
        entry = {
            "kind": p["kind"],
            "type": p["type"],
            "name": p["name"],
            "title": title(proto, p["kind"], locale),
            "order": autoplace.get("order", ""),
            "control": autoplace.get("control"),
            "placement_density": autoplace.get("placement_density", 1),
            "richness": has_richness,
            "collision_box": proto.get("collision_box"),
            "collision_mask": collision_mask(raw, p["type"], proto),
            "map_color": colour(proto.get("map_color"), 255 if p["type"] == "resource" else 1),
            "effect_color": colour(proto.get("effect_color"), 1),
            "decal": proto.get("render_layer") == "decals",
        }
        if p["kind"] != TILE:
            entry.update(placement(proto, p["type"]))
        prototypes.append(entry)
    tile_rules(raw, prototypes)

    nodes, roots = compact(compiler.graph, roots)
    for name, root in roots.items():
        missing = sorted({nodes[n][0] for n in cone(nodes, [root])} - EVALUATED)
        if missing:
            raise GenError(f"{name} reaches {', '.join(missing)}, which the evaluator does not have (docs/NOISE.md)")
    cliff_name =raw["planet"][terrain["planet"]]["map_gen_settings"]["cliff_settings"]["name"]
    cliff = raw["cliff"][cliff_name]
    program = {
        "factorio": FACTORIO_VERSION,
        "planet": terrain["planet"],
        "properties": compiler.properties,
        "roots": roots,
        "prototypes": prototypes,
        "cliff": {
            "name": cliff_name,
            "title": title(cliff, "entity", locale),
            "control": raw["planet"][terrain["planet"]]["map_gen_settings"]["cliff_settings"].get("control"),
            "grid_size": cliff["grid_size"],
            "grid_offset": cliff["grid_offset"],
            "collision_box": cliff["collision_box"][:2],
            "collision_mask": collision_mask(raw, "cliff", cliff),
            "map_color": colour(cliff["map_color"], 1),
        },
        "controls": controls(raw, nodes, raw["planet"][terrain["planet"]]["map_gen_settings"]["cliff_settings"]["control"],
                             locale, core),
        "chart_colors": {kind: [round(c * 255) for c in rgba[:3]] + rgba[3:]
                         for kind, rgba in raw["utility-constants"]["default"]["chart"]["default_color_by_type"].items()},
        "nodes": nodes,
    }
    program["presets"] = presets(raw, program["controls"], compiler.properties, locale)
    return program


LAYER_GROUP_BASE = {"zero": 0, "water": 64, "water-overlay": 80, "ground-natural": 144, "ground-artificial": 400,
                    "top": 528}


# Entities whose decoratives removal is "automatic" and so on (EntityPrototype, vtable slot 0x168).
REMOVES_DECORATIVES = {"simple-entity", "unit-spawner", "turret"}


def placement(proto: dict, type_name: str) -> dict:
    """What Factorio's placement reads of an entity or decorative besides the noise: the jitter flag, the map
    generator's box (the collision box unless given), the build size (the box's, rounded up, unless given), a
    resource's tree removal, whether an entity removes the decoratives under it and whether a decorative is one
    that is removed (the object render layer)."""
    box = proto.get("collision_box") or [[0, 0], [0, 0]]
    removes = proto.get("remove_decoratives", "automatic")
    return {
        "off_grid": "placeable-off-grid" in proto.get("flags", []),
        "map_generator_box": proto.get("map_generator_bounding_box") or box,
        "tile_size": [proto.get("tile_width") or math.ceil(box[1][0] - box[0][0]),
                      proto.get("tile_height") or math.ceil(box[1][1] - box[0][1])],
        "tree_removal": [proto.get("tree_removal_probability", 0), proto.get("tree_removal_max_distance", 0)],
        "removes_decoratives": removes == "true" or (removes == "automatic" and type_name in REMOVES_DECORATIVES),
        "removable": type_name == "optimized-decorative" and proto.get("render_layer") == "object"
                     and not proto.get("grows_through_rail_path", False),
    }


def tile_rules(raw: dict, prototypes: list[dict]) -> None:
    """Each placed tile's render layer and, for each placed tile it may not touch, the one tile between them, as the
    tile correction reads them (docs/NOISE.md)."""
    tiles = raw["tile"]

    def listed(name):
        return tiles[name].get("allowed_neighbors")

    def allowed(a, b):
        la, lb = listed(a), listed(b)
        return a == b or (la is not None and b in la) or (lb is not None and a in lb) or (la is None and lb is None)

    placed = [p["name"] for p in prototypes if p["kind"] == TILE]
    for p in prototypes:
        if p["kind"] != TILE:
            continue
        t = tiles[p["name"]]
        p["layer"] = (LAYER_GROUP_BASE[t.get("layer_group", "ground-natural")] + t.get("layer", 0)) % 528
        p["forbidden"] = {}
        for other in placed:
            if allowed(p["name"], other):
                continue
            between = [m for m in tiles if m not in (p["name"], other) and allowed(p["name"], m) and allowed(m, other)]
            if len(between) != 1 or between[0] not in placed:
                raise GenError(f"{p['name']} and {other} may not touch, and the path between them is not one placed tile")
            p["forbidden"][other] = between[0]


def compact(graph: Graph, roots: dict[str, int]) -> tuple[list[list], dict[str, int]]:
    """Only the nodes the roots reach, renumbered in order."""
    reached = set()
    stack = list(roots.values())
    while stack:
        n = stack.pop()
        if n in reached:
            continue
        reached.add(n)
        stack.extend(arguments(graph.nodes[n]))
    order = sorted(reached)
    renumber = {old: new for new, old in enumerate(order)}
    nodes = []
    for old in order:
        node = graph.nodes[old]
        if node[0] == "property":
            nodes.append([node[0], node[1], {k: renumber[v] for k, v in node[2].items()}])
        elif node[0] in ("const", "input", "points"):
            nodes.append(node)
        else:
            nodes.append([node[0], *(renumber[a] for a in node[1:])])
    return nodes, {name: renumber[n] for name, n in roots.items()}


def arguments(node: list) -> list[int]:
    if node[0] in ("const", "input", "points"):
        return []
    if node[0] == "property":
        return list(node[2].values())
    return node[1:]


def cone(nodes: list[list], starts) -> set[int]:
    seen = set()
    stack = list(starts)
    while stack:
        n = stack.pop()
        if n not in seen:
            seen.add(n)
            stack.extend(arguments(nodes[n]))
    return seen


def number(value: float):
    if math.isinf(value):
        return "inf" if value > 0 else "-inf"
    if value == int(value) and abs(value) < 1 << 53:
        return int(value)
    return value


def render(program: dict) -> str:
    """The program as JSON, one node to a line, so a diff reads node by node."""
    def node_text(node):
        if node[0] == "const":
            return json.dumps(["const", number(node[1])])
        return json.dumps(node, separators=(",", ":"))

    head = {k: v for k, v in program.items() if k not in ("prototypes", "controls", "presets", "nodes")}
    text = json.dumps(head, indent=2)[:-2]
    for key, line in (("controls", json.dumps), ("presets", json.dumps), ("prototypes", json.dumps), ("nodes", node_text)):
        text += f',\n  "{key}": [\n' + ",\n".join(f"    {line(item)}" for item in program[key]) + "\n  ]"
    return text + "\n}\n"


def summary(program: dict, parsed: int) -> str:
    nodes = program["nodes"]
    roots = program["roots"]
    out = [f"Factorio {program['factorio']}, {program['planet']}: parsed {parsed} expressions; "
           f"{len(nodes)} nodes, {len(roots)} roots."]
    groups = {
        "climate": [r for r in roots if r in CLIMATE],
        "tiles": [r for r in roots if r.startswith("tile:")],
        "entities": [r for r in roots if r.startswith("entity:")],
        "decoratives": [r for r in roots if r.startswith("decorative:")],
        "cliffs": ["cliff_elevation", "cliffiness"],
    }
    for group, names in groups.items():
        reach = cone(nodes, (roots[r] for r in names))
        ops = sorted({nodes[n][0] for n in reach} & NOISE_OPS)
        out.append(f"  {group}: {len(names)} roots, {len(reach)} nodes; {', '.join(ops) or 'no noise'}")
    out.append("  properties a preset swaps: " + ", ".join(
        f"{k} ({', '.join(map(str, v))})" for k, v in program["properties"].items()))
    out.append("  per root: " + ", ".join(
        f"{name.split(':')[1] if ':' in name else name}{'.r' if name.endswith(':richness') else ''} "
        f"{len(cone(nodes, [n]))}" for name, n in roots.items()))
    out.append("  operations: " + ", ".join(sorted({n[0] for n in nodes})))
    return "\n".join(out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="fail if the program on disk differs")
    mode.add_argument("--write", action="store_true", help="write the program")
    args = parser.parse_args()

    if not DATA_RAW.exists():
        print(f"{DATA_RAW} is missing; reference/README.md says how to make it.", file=sys.stderr)
        return 2
    for path in (LOCALE, CORE_LOCALE):
        if not path.exists():
            print(f"{path} is missing; reference/README.md says how to make it.", file=sys.stderr)
            return 2
    raw = json.loads(DATA_RAW.read_text(encoding="utf-8"))
    terrain = json.loads(TERRAIN.read_text(encoding="utf-8"))
    try:
        parsed = parse_everything(raw)
        program = build(raw, terrain, load_locale(LOCALE), load_locale(CORE_LOCALE))
    except GenError as e:
        print(f"gen_terrain: {e}", file=sys.stderr)
        return 1
    text = render(program)
    print(summary(program, parsed))

    if args.write:
        PROGRAM.parent.mkdir(parents=True, exist_ok=True)
        PROGRAM.write_text(text, encoding="utf-8", newline="\n")
        print(f"wrote {PROGRAM.relative_to(REPO)}")
    elif args.check:
        on_disk = PROGRAM.read_text(encoding="utf-8") if PROGRAM.exists() else None
        if on_disk != text:
            print(f"{PROGRAM.relative_to(REPO)} differs from what the generator makes: "
                  "python tools/gen_terrain.py --write", file=sys.stderr)
            return 1
        print(f"{PROGRAM.relative_to(REPO)} is current")
    return 0


if __name__ == "__main__":
    sys.exit(main())
