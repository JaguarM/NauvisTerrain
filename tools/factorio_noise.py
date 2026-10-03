#!/usr/bin/env python3
"""
Factorio's noise expression language: a tokenizer and a parser to a syntax tree (docs/NOISE.md).

An expression in the dump is a number, a boolean or a string; `parse` takes any of the three. The
tree is tuples:

    ("num", float)                  a number; a boolean is 1 or 0
    ("str", str)                    a string literal, as `var` and `seed1` take
    ("id", name)                    a name, resolved later
    ("un", op, a)                   op in + - ~
    ("bin", op, a, b)               op in ^ * / % %% + - < <= > >= == != & ~ |
    ("call", name, args, kwargs)    args a list for f(...), kwargs a dict for f{...}; the other None
"""

from __future__ import annotations

import re


class ParseError(Exception):
    """An expression the grammar does not accept."""


_TOKEN = re.compile(
    r"""
    (?P<ws>[ \n\r\t]+)
  | (?P<num>0x[0-9a-f]+|(?:[0-9]+\.?[0-9]*|\.[0-9]+)(?:e-?[0-9]+)?)
  | (?P<id>[a-zA-Z_][a-zA-Z0-9_:]*)
  | (?P<str>"[^"]*"|'[^']*')
  | (?P<op>%%|<=|>=|==|~=|!=|[-+*/%^<>&~|(){},=])
    """,
    re.VERBOSE,
)

# Binary operators by precedence, loosest first. `^` is handled apart: it is right-associative
# and binds tighter than the unary operators.
_LEVELS = [
    ("|",),
    ("~",),
    ("&",),
    ("==", "~=", "!="),
    ("<", "<=", ">", ">="),
    ("+", "-"),
    ("*", "/", "%", "%%"),
]


def tokenize(text: str) -> list[tuple[str, str]]:
    tokens = []
    pos = 0
    while pos < len(text):
        m = _TOKEN.match(text, pos)
        if not m:
            raise ParseError(f"unexpected {text[pos:pos + 10]!r} at {pos} in {text!r}")
        pos = m.end()
        kind = m.lastgroup
        if kind != "ws":
            tokens.append((kind, m.group()))
    tokens.append(("end", ""))
    return tokens


class _Parser:
    def __init__(self, text: str):
        self.text = text
        self.tokens = tokenize(text)
        self.pos = 0

    def peek(self) -> tuple[str, str]:
        return self.tokens[self.pos]

    def take(self) -> tuple[str, str]:
        token = self.tokens[self.pos]
        self.pos += 1
        return token

    def expect(self, value: str) -> None:
        kind, got = self.take()
        if got != value or kind not in ("op",):
            raise ParseError(f"expected {value!r}, got {got!r} in {self.text!r}")

    def at_op(self, *values: str) -> bool:
        kind, value = self.peek()
        return kind == "op" and value in values

    def parse(self):
        tree = self.binary(0)
        if self.peek()[0] != "end":
            raise ParseError(f"trailing {self.peek()[1]!r} in {self.text!r}")
        return tree

    def binary(self, level: int):
        if level == len(_LEVELS):
            return self.unary()
        left = self.binary(level + 1)
        while self.at_op(*_LEVELS[level]):
            op = self.take()[1]
            right = self.binary(level + 1)
            left = ("bin", "!=" if op == "~=" else op, left, right)
        return left

    def unary(self):
        if self.at_op("+", "-", "~"):
            op = self.take()[1]
            return ("un", op, self.unary())
        return self.power()

    def power(self):
        base = self.primary()
        if self.at_op("^"):
            self.take()
            return ("bin", "^", base, self.unary())
        return base

    def primary(self):
        kind, value = self.take()
        if kind == "num":
            return ("num", float(int(value, 16)) if value.startswith("0x") else float(value))
        if kind == "str":
            return ("str", value[1:-1])
        if kind == "id":
            if self.at_op("("):
                self.take()
                args = []
                if not self.at_op(")"):
                    args.append(self.binary(0))
                    while self.at_op(","):
                        self.take()
                        args.append(self.binary(0))
                self.expect(")")
                return ("call", value, args, None)
            if self.at_op("{"):
                self.take()
                kwargs = {}
                while not self.at_op("}"):
                    name_kind, name = self.take()
                    if name_kind != "id":
                        raise ParseError(f"expected an argument name, got {name!r} in {self.text!r}")
                    if name in kwargs:
                        raise ParseError(f"argument {name!r} given twice in {self.text!r}")
                    self.expect("=")
                    kwargs[name] = self.binary(0)
                    if not self.at_op(","):
                        break
                    self.take()
                self.expect("}")
                return ("call", value, None, kwargs)
            return ("id", value)
        if kind == "op" and value == "(":
            inner = self.binary(0)
            self.expect(")")
            return inner
        raise ParseError(f"unexpected {value!r} in {self.text!r}")


def parse(expression):
    """A dump expression, number, boolean or string, as a syntax tree."""
    if isinstance(expression, bool):
        return ("num", 1.0 if expression else 0.0)
    if isinstance(expression, (int, float)):
        return ("num", float(expression))
    if isinstance(expression, str):
        return _Parser(expression).parse()
    raise ParseError(f"not an expression: {expression!r}")
