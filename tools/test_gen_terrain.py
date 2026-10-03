#!/usr/bin/env python3
"""The parser and the compiler of tools/gen_terrain.py: `python -m unittest tools/test_gen_terrain.py`."""

from __future__ import annotations

import json
import math
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gen_terrain  # noqa: E402
from factorio_noise import ParseError, parse  # noqa: E402
from gen_terrain import Compiler, GenError, Scope  # noqa: E402


def compiler(expressions: dict | None = None, functions: dict | None = None,
             properties: dict | None = None) -> Compiler:
    raw = {
        "noise-expression": {k: {"expression": v} for k, v in (expressions or {}).items()},
        "noise-function": functions or {},
        "autoplace-control": {"water": {}},
    }
    return Compiler(raw, properties or {})


def node(c: Compiler, n: int) -> list:
    """A node with its arguments written out, for comparing shapes."""
    raw = c.graph.nodes[n]
    if raw[0] in ("const", "input", "points"):
        return raw
    if raw[0] == "property":
        return ["property", raw[1], {k: node(c, v) for k, v in raw[2].items()}]
    return [raw[0], *(node(c, a) for a in raw[1:])]


def compile_one(text, **kwargs) -> list:
    c = compiler(**kwargs)
    return node(c, c.expression(text, None))


X, Y = ["input", "x"], ["input", "y"]


class ParserTest(unittest.TestCase):
    def test_power_binds_tighter_than_unary_minus(self):
        self.assertEqual(parse("-x^2"), ("un", "-", ("bin", "^", ("id", "x"), ("num", 2.0))))

    def test_power_is_right_associative_and_takes_a_signed_exponent(self):
        self.assertEqual(parse("2^3^-1"),
                         ("bin", "^", ("num", 2.0), ("bin", "^", ("num", 3.0), ("un", "-", ("num", 1.0)))))

    def test_subtraction_is_left_associative(self):
        self.assertEqual(parse("a - b - c"),
                         ("bin", "-", ("bin", "-", ("id", "a"), ("id", "b")), ("id", "c")))

    def test_precedence_from_comparison_to_or(self):
        self.assertEqual(parse("a | b ~ c & d == e < f + g * h"),
                         ("bin", "|", ("id", "a"), ("bin", "~", ("id", "b"), ("bin", "&", ("id", "c"),
                          ("bin", "==", ("id", "d"), ("bin", "<", ("id", "e"), ("bin", "+", ("id", "f"),
                           ("bin", "*", ("id", "g"), ("id", "h")))))))))

    def test_lua_not_equal_is_not_equal(self):
        self.assertEqual(parse("a ~= b"), parse("a != b"))

    def test_numbers(self):
        self.assertEqual(parse("0x2a"), ("num", 42.0))
        self.assertEqual(parse(".5e-1"), ("num", 0.05))
        self.assertEqual(parse(True), ("num", 1.0))
        self.assertEqual(parse(3), ("num", 3.0))

    def test_a_colon_belongs_to_the_name_and_a_hyphen_does_not(self):
        self.assertEqual(parse("control:water:size"), ("id", "control:water:size"))
        self.assertEqual(parse("my-noise"), ("bin", "-", ("id", "my"), ("id", "noise")))

    def test_calls(self):
        self.assertEqual(parse("clamp(x, -1, 1)"),
                         ("call", "clamp", [("id", "x"), ("un", "-", ("num", 1.0)), ("num", 1.0)], None))
        self.assertEqual(parse("f{a = 1, b = 'two',}"),
                         ("call", "f", None, {"a": ("num", 1.0), "b": ("str", "two")}))

    def test_rejects(self):
        for text in ("1 +", "f(1", "f{1}", "a b", "f{a = 1, a = 2}", "$"):
            with self.assertRaises(ParseError, msg=text):
                parse(text)


class CompilerTest(unittest.TestCase):
    def test_constants_fold(self):
        self.assertEqual(compile_one("1 + 2 * 3 - pi / pi"), ["const", 6.0])

    def test_folded_numbers_are_floats(self):
        self.assertEqual(compile_one("1 / 3"), ["const", gen_terrain.f32(1 / 3)])

    def test_a_boolean_is_positive_not_nonzero(self):
        self.assertEqual(compile_one("if(-0.5, 1, 2)"), ["const", 2.0])
        self.assertEqual(compile_one("if(-0.5, x, y)"), Y)

    def test_identities(self):
        self.assertEqual(compile_one("0 + x * 1"), X)
        self.assertEqual(compile_one("0 - x"), ["neg", X])
        self.assertEqual(compile_one("x ^ 0.5"), ["sqrt", X])

    def test_commuted_operands_are_one_node(self):
        c = compiler()
        self.assertEqual(c.expression("x + y", None), c.expression("y + x", None))
        self.assertNotEqual(c.expression("x - y", None), c.expression("y - x", None))

    def test_a_local_shadows_a_global_and_a_parameter_shadows_both(self):
        c = compiler(expressions={"a": "x"},
                     functions={"f": {"parameters": ["a"], "expression": "a",
                                      "local_expressions": {"a": "y"}}})
        self.assertEqual(node(c, c.expression("a", Scope(None, "t", local_expressions={"a": "y"}))), Y)
        self.assertEqual(node(c, c.expression("a", None)), X)
        self.assertEqual(node(c, c.expression("f(2)", None)), ["const", 2.0])

    def test_a_function_sees_its_definition_not_its_caller(self):
        c = compiler(expressions={"b": "x"}, functions={"f": {"parameters": [], "expression": "b"}})
        caller = Scope(None, "t", local_expressions={"b": "y"})
        self.assertEqual(node(c, c.expression("f()", caller)), X)

    def test_named_and_positional_arguments_agree(self):
        self.assertEqual(compile_one("clamp(x, 0, 1)"), compile_one("clamp{max = 1, value = x, min = 0}"))

    def test_var_reaches_a_hyphenated_name(self):
        self.assertEqual(compile_one("var('control:water:size')"), ["input", "control:water:size"])

    def test_a_string_is_its_crc32(self):
        self.assertEqual(compile_one("'tree-01'"), ["const", gen_terrain.f32(gen_terrain.noise_layer_id("tree-01"))])

    def test_defaults_are_filled_in_order(self):
        self.assertEqual(compile_one("basis_noise{x = x, y = y, seed0 = map_seed, seed1 = 3}"),
                         ["basis_noise", X, Y, ["input", "map_seed"], ["const", 3.0],
                          ["const", 1.0], ["const", 1.0], ["const", 0.0], ["const", 0.0]])

    def test_a_constant_parameter_must_not_vary(self):
        with self.assertRaises(GenError):
            compile_one("basis_noise{x = x, y = y, seed0 = map_seed, seed1 = x}")

    def test_unwritten_and_unknown_names_are_refused(self):
        for text in ("terrace(x, 1, 2, 3)", "nonsense", "nonsense(1)", "var('control:lava:size')"):
            with self.assertRaises(GenError, msg=text):
                compile_one(text)

    def test_a_cycle_is_refused(self):
        with self.assertRaises(GenError):
            compile_one("a", expressions={"a": "b + 1", "b": "a"})

    def test_a_local_function_answers_for_its_own_call(self):
        functions = {"f": {"parameters": ["a"], "expression": "g(x)",
                           "local_functions": {"g": {"parameters": ["b"], "expression": "a * b"}}}}
        c = compiler(functions=functions)
        results = {json.dumps(node(c, c.expression(f"f({k})", None))) for k in range(2, 40)}
        self.assertEqual(len(results), 38)

    def test_a_property_is_one_node_per_value(self):
        self.assertEqual(compile_one("e", expressions={"e": "x", "e2": "y"}, properties={"e": ["e2", 1]}),
                         ["property", "e", {"": X, "e2": Y, "1": ["const", 1.0]}])

    def test_spot_noise_counts_points_from_spots(self):
        tree = compile_one("spot_noise{x = x, y = y, density_expression = 1, spot_quantity_expression = 1, "
                           "spot_radius_expression = 1, spot_favorability_expression = 1, seed0 = 1, "
                           "seed1 = 2, basement_value = 0, maximum_spot_basement_radius = 1, skip_span = 3, "
                           "candidate_spot_count = 5, suggested_minimum_candidate_point_spacing = 9}")
        self.assertEqual(tree[-2:], [["const", 15.0], ["const", 9.0]])


@unittest.skipUnless(gen_terrain.DATA_RAW.exists(), "Factorio's dump is absent (reference/README.md)")
class DumpTest(unittest.TestCase):
    raw: dict

    @classmethod
    def setUpClass(cls):
        cls.raw = json.loads(gen_terrain.DATA_RAW.read_text(encoding="utf-8"))

    def test_every_expression_in_the_dump_parses(self):
        self.assertGreater(gen_terrain.parse_everything(self.raw), 120 + 92)

    def test_the_planet_places_twenty_trees_and_no_enemies(self):
        terrain = json.loads(gen_terrain.TERRAIN.read_text(encoding="utf-8"))
        placed = gen_terrain.placed(self.raw, terrain)
        self.assertEqual(sum(p["type"] == "tree" for p in placed), 20)
        self.assertEqual(sum(p["kind"] == "tile" for p in placed), 21)
        self.assertEqual(sum(p["kind"] == "decorative" for p in placed), 34)
        self.assertFalse({p["type"] for p in placed} & {"unit-spawner", "turret"})

    def test_the_program_is_finite_where_it_folded(self):
        program = gen_terrain.build(self.raw, json.loads(gen_terrain.TERRAIN.read_text(encoding="utf-8")))
        for n in program["nodes"]:
            if n[0] == "const":
                self.assertFalse(math.isnan(n[1]))


if __name__ == "__main__":
    unittest.main()
