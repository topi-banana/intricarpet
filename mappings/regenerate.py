#!/usr/bin/env python3
"""Regenerate the two generated files per obfuscated release: a mapping set and a Mixin refmap.

    mappings/intermediary-<release>.tiny   what both remap steps read
    mappings/refmap-<release>.json         what Mixin resolves its own annotation strings through

One file per obfuscated release, and the tiny file serves **both** directions:

    [dependencies] remap  intermediary -> Mojang   the Carpet jar, before it reaches `javac`
    [build] remap         Mojang -> intermediary   the mod's own classes, on the way into the jar

That is the whole reason the files are tiny v2 rather than the ProGuard-style text Mojang
publishes. A ProGuard file names one namespace pair implicitly and jals reads it in whichever
direction the step hardcodes; a tiny v2 file names its namespaces in a header, so
`format = { type = "tiny-v2", from = "intermediary", to = "mojang" }` says which renaming is meant
and the two steps read the same table in opposite directions. Descriptors are written explicitly in
the file's first namespace, so nothing has to be re-derived from Java type names.

Neither namespace is published as a pair, so each file is a composition of two that are:

    Mojang name --(official mappings)--> obfuscated name --(intermediary)--> class_NNNN

The join is on the *obfuscated* member, keyed by name **and descriptor**, which is why the official
mappings read here are Mojang's `client.txt` and not the `server.txt` the build used to fetch:
Fabric's intermediary is generated against the merged jar, whose members carry the client jar's
obfuscated names. Composing through `server.txt` silently misses every member the two jars name
differently.

Not the whole game: a committed file is a reviewed file, so both halves are filtered.

  * **Classes** — the Minecraft types the mod names in its own source, every type that appears in
    the descriptor of a member below, every owner of one, and the types the Carpet API this mod
    imports mentions in its signatures.
  * **Members** — everything the mod source names in a member position (`.foo`, `::foo`) or inside
    one of Mixin's own annotation strings, under every class that declares it. Which class
    *declares* an inherited member is not something this script has to work out: jals walks the
    real class hierarchy of the compile classpath, so an entry filed under the true declaring type
    is found from any subtype, and an entry that no lookup reaches costs a line and nothing else.

Mixin's own strings are the second half of the job, and they split in two by *where the name has
to end up*, not by which annotation carries it:

  * `@Inject(method = …)`, `@At(target = …)`, `@Invoker("…")` — read at apply time out of the
    annotation, which the remapper leaves alone because an annotation element is a `String` and not
    a reference. Mixin's answer is the **refmap**: a side table from the string as written to the
    string as it should be read. This script writes the same structure Loom's annotation processor
    did, except that it rewrites each part of a selector in place rather than substituting the
    member it resolved to — a selector that names no owner keeps naming none, so nothing invents an
    owner that the target class then has to match.
  * `@Shadow` — *not* read out of the annotation. `MixinPreProcessorStandard` looks the member up
    by the name the mixin class declares, so the name has to be right in the bytecode, and a refmap
    entry would never be consulted. That one is a **tiny** entry: the mixin class gets an identity
    class line and a copy of its target's mapping for the member it shadows, and `[build] remap`
    renames the declaration and every use of it exactly as it renames a Minecraft member.

Both halves are read from the source tree as text, before the `#[cfg]` lowering, and the `#[cfg]`
predicates are evaluated here against the release's feature set — not to *skip* a branch another
release compiles, but to know which unresolved reference is a mistake and which is simply a branch
this release does not have.

Nothing here is silent when it is wrong. A Carpet type that is missing stops `javac` on the
`class_NNNN` it could not resolve, a mod reference that is missing is what
`mappings/check-remap.py` fails the build on, and a Mixin string that this release should be able
to resolve and cannot fails this script.

    python3 mappings/regenerate.py            # every obfuscated release
    python3 mappings/regenerate.py 1.17.1     # one of them
    python3 mappings/regenerate.py --check    # re-derive the refmaps from what is committed

`--check` is the offline half: a refmap follows from the mapping set and the source tree, so a
source tree that has moved on from its refmaps can be caught without fetching anything.

Needs `javap` (the JDK the build already requires) and nothing beyond the Python standard library.
"""

import functools
import hashlib
import io
import json
import re
import subprocess
import sys
import tempfile
import tomllib
import urllib.request
import zipfile
from pathlib import Path
from typing import NamedTuple

ROOT = Path(__file__).resolve().parent.parent

VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
INTERMEDIARY = (
    "https://maven.fabricmc.net/net/fabricmc/intermediary/{release}"
    "/intermediary-{release}-v2.jar"
)

# The Carpet types the mod imports. Only their signatures can force a Minecraft name onto the
# classpath, so only the names those signatures mention have to be reconciled.
CARPET_CLASSES = [
    "carpet/CarpetExtension",
    "carpet/CarpetServer",
    "carpet/CarpetSettings",
    "carpet/helpers/OptimizedExplosion",
    "carpet/logging/Logger",
    "carpet/logging/LoggerRegistry",
    "carpet/logging/logHelpers/ExplosionLogHelper",
    "carpet/mixins/ExplosionAccessor",
    "carpet/settings/Rule",
    "carpet/settings/SettingsManager",
    "carpet/utils/Messenger",
]

# `.name`, `::name` — a member position, and the only place a member name the compiler will write
# into the constant pool can appear in Java source. Declarations are deliberately not scanned: a
# method this mod declares is its own, and the one case where the two coincide — an override of a
# Minecraft method — writes the name into a `@Shadow`/`@Inject` target or a call as well.
MEMBER_REFERENCE = re.compile(r"(?:\.|::)\s*([A-Za-z_$][A-Za-z0-9_$]*)")

# The mixin package, the only place the annotations below are written.
MIXINS = ROOT / "src/main/java/me/lntricate/intricarpet/mixins"
# The annotations whose elements select a member of the target class. MixinExtras' injectors are in
# the list for the same reason Mixin's own are: `method` and `at` mean the same thing in both.
INJECTORS = frozenset(
    {
        "Inject",
        "ModifyArg",
        "ModifyArgs",
        "ModifyConstant",
        "ModifyExpressionValue",
        "ModifyReceiver",
        "ModifyReturnValue",
        "ModifyVariable",
        "Redirect",
        "WrapMethod",
        "WrapOperation",
        "WrapWithCondition",
    }
)
# `@Invoker("name")` and `@Accessor("name")` carry their selector as the annotation's value.
ACCESSORS = frozenset({"Accessor", "Invoker"})
# `[Lowner;]name[(arguments)returns|:descriptor]` — Mixin's member selector, as `MemberInfo.parse`
# reads it. Every part is optional but the name, which is why one pattern covers a bare
# `checkFallDamage`, a `checkInsideBlocks(…)V` and a fully qualified `L…;fallOn(…)V` alike.
MEMBER_SELECTOR = re.compile(
    r"^(?:L(?P<owner>[^;]+);)?(?P<name>[^();:]*)(?:(?P<method>\(.*\).+)|:(?P<field>.+))?$"
)

PRIMITIVES = {
    "void": "V",
    "boolean": "Z",
    "byte": "B",
    "char": "C",
    "short": "S",
    "int": "I",
    "long": "J",
    "float": "F",
    "double": "D",
}


def fetch(url: str, sha1: str | None = None) -> bytes:
    print(f"  fetching {url}", file=sys.stderr)
    with urllib.request.urlopen(url) as response:
        body = response.read()
    digest = hashlib.sha1(body).hexdigest()
    if sha1 is not None and digest != sha1:
        raise SystemExit(f"digest mismatch: {digest} != {sha1} ({url})")
    return body


@functools.cache
def manifest() -> str:
    return (ROOT / "jals.toml").read_text()


def releases() -> list[str]:
    """The obfuscated releases, in `jals.toml` order.

    `remap = "intermediary"` on a Carpet dependency is the declaration that the release ships
    obfuscated — it is what says the jar and the game speak different names — so the list needs no
    second copy here and adding a release means editing only the manifest.
    """
    return re.findall(
        r'^"carpet-([\d.]+)" = \{ jar = "[^"]+".*remap = "intermediary"', manifest(), re.M
    )


def carpet_jar(release: str) -> str:
    return re.search(
        rf'^"carpet-{re.escape(release)}" = {{ jar = "([^"]+)"', manifest(), re.M
    ).group(1)


@functools.cache
def versions() -> dict[str, str]:
    return {entry["id"]: entry["url"] for entry in json.loads(fetch(VERSION_MANIFEST))["versions"]}


def official_mappings(release: str) -> tuple[str, str]:
    """Mojang's `client.txt` for the release, and the SHA-1 the version manifest publishes for it.

    Resolved rather than pinned in the repository: the digest comes from Mojang over the same
    connection as the bytes and is checked against them, and it is written into the header of the
    file this script generates, so a changed input shows up as a diff in a committed file rather
    than as nothing at all.
    """
    url = versions().get(release)
    if url is None:
        raise SystemExit(f"the version manifest names no release `{release}`")
    downloads = json.loads(fetch(url))["downloads"]["client_mappings"]
    return fetch(downloads["url"], downloads["sha1"]).decode(), downloads["sha1"]


def field_descriptor(java_type: str) -> str:
    dimensions = 0
    while java_type.endswith("[]"):
        dimensions += 1
        java_type = java_type[:-2]
    base = PRIMITIVES.get(java_type) or f"L{java_type.replace('.', '/')};"
    return "[" * dimensions + base


def parse_official(text: str) -> tuple[dict[str, str], list[tuple[str, bool, str, str, str]]]:
    """The ProGuard-style text Mojang publishes, as a class map and a flat member list.

    Members come out as `(Mojang owner, is method, Mojang descriptor, Mojang name, obfuscated
    name)`. The descriptor is built here rather than downstream because this is the only place the
    Java type names the format writes are still in hand.
    """
    classes: dict[str, str] = {}
    members: list[tuple[str, bool, str, str, str]] = []
    owner = None
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        if not line.startswith(" "):
            official, obfuscated = line.rstrip(":").split(" -> ")
            owner = official.replace(".", "/")
            classes[owner] = obfuscated.replace(".", "/")
            continue
        # A member before any class is not something Mojang emits, and a file that had one would be
        # one this parser is misreading the shape of.
        assert owner is not None
        left, obfuscated = line.strip().rsplit(" -> ", 1)
        # The line-number prefix is present on methods and absent on fields.
        left = re.sub(r"^\d+:\d+:", "", left)
        if "(" in left:
            head, arguments = left.split("(", 1)
            returns, name = head.rsplit(" ", 1)
            parameters = [a for a in arguments.rstrip(")").split(",") if a]
            descriptor = (
                "(" + "".join(field_descriptor(a) for a in parameters) + ")"
                + field_descriptor(returns)
            )
            members.append((owner, True, descriptor, name, obfuscated))
        else:
            declared, name = left.rsplit(" ", 1)
            members.append((owner, False, field_descriptor(declared), name, obfuscated))
    return classes, members


def parse_intermediary(tiny: str) -> tuple[dict[str, str], dict[tuple[str, bool, str, str], str]]:
    """Fabric's tiny v2, as `obfuscated class -> class_NNNN` and `(owner, kind, descriptor, name)`."""
    classes: dict[str, str] = {}
    members: dict[tuple[str, bool, str, str], str] = {}
    owner = None
    for line in tiny.splitlines():
        columns = line.split("\t")
        if line.startswith("c\t"):
            owner = columns[1]
            classes[owner] = columns[2]
        elif line.startswith(("\tm\t", "\tf\t")):
            members[(owner, columns[1] == "m", columns[2], columns[3])] = columns[4]
    return classes, members


def carpet_types(jar: bytes) -> set[str]:
    """Every `class_NNNN` in the signatures of `CARPET_CLASSES`, as `javap` prints them."""
    found: set[str] = set()
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(io.BytesIO(jar)) as archive:
        entries = set(archive.namelist())
        for name in CARPET_CLASSES:
            entry = f"{name}.class"
            if entry not in entries:
                # Carpet's own API moves between releases; a class that is not in this one simply
                # contributes no names.
                continue
            path = Path(tmp) / "entry.class"
            path.write_bytes(archive.read(entry))
            javap = subprocess.run(
                ["javap", "-p", str(path)], capture_output=True, text=True, check=True
            )
            found |= set(re.findall(r"net\.minecraft\.(class_\d+)", javap.stdout))
    return found


@functools.cache
def feature_graph() -> dict[str, list[str]]:
    """`jals.toml`'s `[features]` table.

    Read as TOML rather than with a pattern like the lookups above, because this is the one entry
    that is structured: a feature's value is the list of features it turns on in turn, and the
    walk below is over that graph rather than over a line.
    """
    return tomllib.loads(manifest()).get("features", {})


@functools.cache
def active_features(release: str) -> frozenset[str]:
    """Every feature a `--features <release>` build activates, transitively.

    Cargo's model, which is jals': naming a feature names everything it lists, all the way down. The
    two kinds of entry that are not features of this project — `minecraft/<release>`, which forwards
    the name into the SDK, and `dep:carpet-<release>`, which activates an optional dependency — are
    exactly the two a `#[cfg]` cannot name, so they end the walk rather than continuing it.
    """
    active: set[str] = set()
    queue = [release]
    while queue:
        name = queue.pop()
        if name in active or name not in feature_graph():
            continue
        active.add(name)
        queue += [
            entry
            for entry in feature_graph()[name]
            if "/" not in entry and not entry.startswith("dep:")
        ]
    return frozenset(active)


def split_arguments(text: str) -> list[str]:
    """`text` split on the commas that are not inside brackets.

    A `#[cfg]` predicate's strings are feature names and carry no commas, so nesting is the whole of
    what has to be tracked.
    """
    parts, depth, start = [], 0, 0
    for index, character in enumerate(text):
        if character in "([":
            depth += 1
        elif character in ")]":
            depth -= 1
        elif character == "," and depth == 0:
            parts.append(text[start:index])
            start = index + 1
    parts.append(text[start:])
    return [part for part in (piece.strip() for piece in parts) if part]


def evaluate(predicate: str, active: frozenset[str]) -> bool:
    """A `#[cfg(...)]` predicate against a release's feature set.

    Only the dialect this source writes is accepted. An unknown operator is a predicate this script
    would otherwise answer by guessing, and guessing it wrong is a silently missing mapping.
    """
    predicate = predicate.strip()
    combinator = re.fullmatch(r"(not|all|any)\s*\((.*)\)", predicate, re.S)
    if combinator:
        operator, arguments = combinator.groups()
        parts = split_arguments(arguments)
        if operator == "not":
            return not evaluate(parts[0], active)
        return (all if operator == "all" else any)(evaluate(part, active) for part in parts)
    feature = re.fullmatch(r'feature\s*=\s*"([^"]+)"', predicate, re.S)
    if feature:
        return feature.group(1) in active
    raise SystemExit(f"unsupported `#[cfg]` predicate: {predicate}")


def combine(*predicates: str | None) -> str | None:
    """The predicate that holds when all of `predicates` do."""
    parts = [predicate for predicate in predicates if predicate is not None]
    if not parts:
        return None
    return parts[0] if len(parts) == 1 else "all(" + ", ".join(parts) + ")"


def end_of_literal(text: str, start: int) -> int:
    """The index just past the string or character literal that opens at `start`."""
    quote = text[start]
    index = start + 1
    while index < len(text) and text[index] != quote:
        index += 2 if text[index] == "\\" else 1
    return index + 1


def strip_comments(text: str) -> str:
    """`text` with every comment blanked, length-preservingly.

    Blanked rather than cut so that every offset into the result still names the character the
    author wrote — the same trick the jals frontend plays on a false `#[cfg]`, for the same reason.

    Commented-out code is why this runs at all: `EntityMixin` carries a disabled `@ModifyReturnValue`
    against `isSteppingCarefully`, a member no release has, and a scan that could see it would fail
    every one of them.
    """
    out = list(text)
    index, length = 0, len(text)
    while index < length:
        if text[index] in "\"'":
            index = end_of_literal(text, index)
        elif text.startswith("//", index):
            while index < length and text[index] != "\n":
                out[index] = " "
                index += 1
        elif text.startswith("/*", index):
            end = text.find("*/", index + 2)
            end = length if end < 0 else end + 2
            for position in range(index, end):
                if out[position] != "\n":
                    out[position] = " "
            index = end
        else:
            index += 1
    return "".join(out)


def blank_literals(text: str) -> str:
    """`text` with the contents of every literal blanked, length-preservingly.

    The result is the *skeleton*: the source's structure with none of its strings. Brackets and
    semicolons in it are the source's own, which is what lets a descriptor — all brackets and
    semicolons — sit inside an annotation without being mistaken for one.
    """
    out = list(text)
    index = 0
    while index < len(text):
        if text[index] in "\"'":
            end = end_of_literal(text, index)
            for position in range(index + 1, end - 1):
                out[position] = " "
            index = end
        else:
            index += 1
    return "".join(out)


def matching(skeleton: str, start: int, brackets: str = "()") -> int:
    """The index just past the bracket that closes the one at `start`."""
    opening, closing = brackets
    depth = 0
    for index in range(start, len(skeleton)):
        if skeleton[index] == opening:
            depth += 1
        elif skeleton[index] == closing:
            depth -= 1
            if depth == 0:
                return index + 1
    raise SystemExit(f"unbalanced `{opening}` at offset {start}")


def literals_between(source: str, skeleton: str, start: int, end: int) -> list[str]:
    """The contents of every string literal between `start` and `end`."""
    found = []
    index = start
    while index < end:
        if skeleton[index] == '"':
            closing = end_of_literal(skeleton, index)
            found.append(source[index + 1 : closing - 1])
            index = closing
        else:
            index += 1
    return found


def skip_annotations(skeleton: str, position: int) -> int:
    """`position` advanced past the annotations that follow it, if any."""
    while True:
        annotation = re.compile(r"\s*@\w+\s*").match(skeleton, position)
        if annotation is None:
            return position
        position = annotation.end()
        if position < len(skeleton) and skeleton[position] == "(":
            position = matching(skeleton, position)


def cfg_spans(source: str, skeleton: str) -> list[tuple[int, str]]:
    """`(offset just past the attribute, predicate)` for every `#[cfg(...)]`, in source order."""
    spans = []
    for attribute in re.finditer(r"#\[\s*cfg\s*\(", skeleton):
        opening = attribute.end() - 1
        closing = matching(skeleton, opening)
        spans.append(
            (matching(skeleton, attribute.start() + 1, "[]"), source[opening + 1 : closing - 1])
        )
    return spans


def governing(spans: list[tuple[int, str]], skeleton: str, position: int) -> str | None:
    """The `#[cfg(...)]` predicate that decides whether the declaration at `position` exists.

    An attribute governs one declaration, so the nearest one that `position` is not separated from
    by a `;`, `{` or `}` is the one that applies. That separator is also what keeps a class-level
    attribute — `ChunkMapAccessor` carries one — from reaching the members inside the class body:
    the brace is in the way, and the caller applies the class's predicate itself.
    """
    found = None
    for end, predicate in spans:
        if end <= position and not re.search(r"[;{}]", skeleton[end:position]):
            found = predicate
    return found


class Reference(NamedTuple):
    """A selector the source writes, and the `#[cfg]` that decides whether a release compiles it."""

    selector: str
    predicate: str | None


class Mixin(NamedTuple):
    """One mixin class: what it is applied to, and every Minecraft name it spells out."""

    name: str
    target: str
    predicate: str | None
    references: tuple[Reference, ...]
    shadows: tuple[Reference, ...]


@functools.cache
def scan_mixins() -> tuple[Mixin, ...]:
    """Every mixin class in the source tree, with the selectors and shadows it declares.

    Scanned once for all thirteen releases, like `source_names`: a selector only one release
    compiles is still in the result, carrying the `#[cfg]` that says which, and the caller decides
    per release what to do with it. Constant references are resolved here — `ServerChunkCacheMixin`
    writes `method = targetMethod` against three `#[cfg]`-selected definitions of `targetMethod` —
    by taking every branch of the constant and conjoining its predicate with the reference's own.
    """
    mixins = []
    for path in sorted(MIXINS.rglob("*.java")):
        source = strip_comments(path.read_text())
        skeleton = blank_literals(source)
        spans = cfg_spans(source, skeleton)

        package = re.search(r"\bpackage\s+([\w.]+)\s*;", skeleton)
        declared = re.search(r"@Mixin\s*\(\s*([\w.]+)\.class\s*\)", skeleton)
        if package is None or declared is None:
            raise SystemExit(f"{path}: not a mixin class this script can read")
        imports = {
            qualified.rsplit(".", 1)[1]: qualified
            for qualified in re.findall(r"\bimport\s+(?:static\s+)?([\w.]+)\s*;", skeleton)
        }
        target = imports.get(declared.group(1))
        if target is None:
            raise SystemExit(f"{path}: `@Mixin({declared.group(1)}.class)` names an unimported type")

        # `private static final String x = "…"` — a selector written once and named from several
        # injectors. Collected before the injectors are read, since a reference may precede it.
        constants: dict[str, list[Reference]] = {}
        for constant in re.finditer(r"\bstatic\s+final\s+String\s+(\w+)\s*=\s*", skeleton):
            if not skeleton.startswith('"', constant.end()):
                continue
            closing = end_of_literal(skeleton, constant.end())
            constants.setdefault(constant.group(1), []).append(
                Reference(
                    source[constant.end() + 1 : closing - 1],
                    governing(spans, skeleton, constant.start()),
                )
            )

        def selectors(position: int, predicate: str | None) -> list[Reference]:
            """The selectors an annotation element's value names, starting at `position`."""
            while skeleton[position] in " \t\r\n":
                position += 1
            if skeleton[position] == "{":
                closing = matching(skeleton, position, "{}")
                found = literals_between(source, skeleton, position, closing)
            elif skeleton[position] == '"':
                closing = end_of_literal(skeleton, position)
                found = [source[position + 1 : closing - 1]]
            else:
                named = re.compile(r"[\w.]+").match(skeleton, position)
                if named is None:
                    raise SystemExit(f"{path}: unreadable selector at offset {position}")
                branches = constants.get(named.group(0).rsplit(".", 1)[-1])
                if branches is None:
                    raise SystemExit(f"{path}: `{named.group(0)}` is not a constant of this file")
                return [
                    Reference(branch.selector, combine(predicate, branch.predicate))
                    for branch in branches
                ]
            return [Reference(selector, predicate) for selector in found]

        references: list[Reference] = []
        for annotation in re.finditer(r"@(\w+)\s*\(", skeleton):
            name = annotation.group(1)
            if name not in INJECTORS and name not in ACCESSORS:
                continue
            start, end = annotation.end(), matching(skeleton, annotation.end() - 1) - 1
            predicate = governing(spans, skeleton, annotation.start())
            if name in ACCESSORS:
                references += [
                    Reference(selector, predicate)
                    for selector in literals_between(source, skeleton, start, end)
                ]
                continue

            # `@At` is a nested annotation with a selector and a `remap` of its own, and the two are
            # read separately from the injector's: `ExplosionLogHelperMixin` turns remapping off on
            # an `@At` whose target is a JDK method while leaving the injector's own alone.
            nested = [
                (start + at.end() - 1, matching(skeleton, start + at.end() - 1))
                for at in re.finditer(r"@At\s*\(", skeleton[start:end])
            ]
            outer = list(skeleton[start:end])
            for opening, closing in nested:
                # Blanked out first, so that whatever an `@At` says about itself cannot be read as
                # something the injector said about all of its selectors.
                for position in range(opening, closing):
                    outer[position - start] = " "
            outer = "".join(outer)

            # `remap = false` on the injector answers for every selector it carries, the nested ones
            # included; `remap = false` on an `@At` answers for that one alone.
            if re.search(r"\bremap\s*=\s*false\b", outer):
                continue
            for opening, closing in nested:
                body = skeleton[opening:closing]
                if re.search(r"\bremap\s*=\s*false\b", body):
                    continue
                for element in re.finditer(r"\btarget\s*=\s*", body):
                    references += selectors(opening + element.end(), predicate)
            for element in re.finditer(r"\bmethod\s*=\s*", outer):
                references += selectors(start + element.end(), predicate)

        shadows: list[Reference] = []
        for shadow in re.finditer(r"@Shadow\b", skeleton):
            position = shadow.end()
            arguments = re.compile(r"\s*\(").match(skeleton, position)
            if arguments:
                opening = arguments.end() - 1
                closing = matching(skeleton, opening)
                if re.search(r"\bremap\s*=\s*false\b", skeleton[opening:closing]):
                    continue
                position = closing
            position = skip_annotations(skeleton, position)
            # Modifiers and a type, then the name — and the same read serves a field and a method,
            # because the name is the last identifier before the `;`, `=` or `(` that ends the head.
            head = re.compile(r"[^;({=]*").match(skeleton, position).group(0)
            identifiers = re.findall(r"[A-Za-z_$][\w$]*", head)
            if not identifiers:
                raise SystemExit(f"{path}: unreadable `@Shadow` at offset {shadow.start()}")
            shadows.append(
                Reference(identifiers[-1], governing(spans, skeleton, shadow.start()))
            )

        mixins.append(
            Mixin(
                name=f"{package.group(1)}.{path.stem}".replace(".", "/"),
                target=target.replace(".", "/"),
                predicate=governing(spans, skeleton, declared.start()),
                references=tuple(references),
                shadows=tuple(shadows),
            )
        )
    return tuple(mixins)


def source_names() -> tuple[set[str], set[str]]:
    """`(Minecraft classes the source names, member names it uses)`, over the whole source tree.

    Every release reads the same set. The tree is scanned as text, before the `#[cfg]` lowering, so
    a name that only one release compiles is still in it — which is what makes one scan answer for
    all thirteen, and costs at most a line for a name a given release does not have.
    """
    classes: set[str] = set()
    members: set[str] = set()
    for path in (ROOT / "src/main/java").rglob("*.java"):
        text = path.read_text()
        # A qualified name and every prefix of one. `import net.minecraft.commands.Commands.literal`
        # and `net.minecraft.world.entity.Entity` are the same shape, and which prefix is the class
        # is decided by the official mappings rather than here.
        for match in re.finditer(r"net[./]minecraft(?:[./][A-Za-z_$][A-Za-z0-9_$]*)+", text):
            parts = match.group(0).replace(".", "/").split("/")
            classes |= {"/".join(parts[: end + 1]) for end in range(2, len(parts))}
        members |= set(MEMBER_REFERENCE.findall(text))
    # The names Mixin spells out. A selector's own name is what the refmap has to answer, and a
    # `@Shadow`'s is what the mixin class has to be able to carry, so both are names the mod means
    # even where nothing else in the source writes them: `Block#fallOn` appears in this project as
    # an `@At` target and nowhere else at all.
    #
    # Only where the name means a Minecraft member, which the selector's owner decides and the
    # mixin's target decides where the selector names none. `ExplosionLogHelperMixin` shadows
    # Carpet's `pos`; Minecraft spells a hundred unrelated members that way, none of them the one
    # meant, and every one of them would be a line in the file.
    for mixin in scan_mixins():
        if mixin.target.startswith("net/minecraft/"):
            members |= {shadow.selector for shadow in mixin.shadows}
        for reference in mixin.references:
            selected = MEMBER_SELECTOR.fullmatch(reference.selector)
            if not selected or not selected.group("name"):
                continue
            if (selected.group("owner") or mixin.target).startswith("net/minecraft/"):
                members.add(selected.group("name"))
    return classes, members


def refmap_for(release: str, generated: str) -> tuple[str, int]:
    """The refmap for `release`, and the number of selectors it answers.

    Derived from the finished mapping set rather than from the intermediate state that produced it,
    which is the whole reason `--check` can answer without the network: a refmap is a function of two
    things that are both in the repository — the committed mapping set and the source tree — so
    re-deriving it is how a stale one is caught, and the only thing a check cannot see is a mapping
    set that is itself out of date.

    Each part of a selector is rewritten in place rather than replaced by the member it resolved to.
    A selector that names no owner keeps naming none, so nothing invents an owner that the target
    class then has to match — which matters here, because the official mappings file an inherited
    member under the supertype that declares it, and that is not the class the mixin is applied to.
    """
    classes: dict[str, str] = {}
    by_owner: dict[str, list[tuple[str, str, str]]] = {}
    owner = None
    for line in generated.splitlines():
        columns = line.split("\t")
        if line.startswith("c\t"):
            owner = columns[1]
            classes[owner] = columns[2]
        elif line.startswith(("\tm\t", "\tf\t")):
            by_owner.setdefault(owner, []).append((columns[2], columns[3], columns[4]))

    active = active_features(release)
    mixins = scan_mixins()
    # A mixin class's own entries are copies of its target's, filed under a second owner. Indexing
    # them would make every name they carry look ambiguous to the lookup below.
    written_by_mixins = {mixin.name for mixin in mixins}
    by_member: dict[tuple[str, str], list[tuple[str, str]]] = {}
    by_bare_name: dict[str, list[tuple[str, str]]] = {}
    for name, owned in by_owner.items():
        if name in written_by_mixins:
            continue
        for descriptor, member, renamed in owned:
            by_member.setdefault((name, member), []).append((descriptor, renamed))
            by_bare_name.setdefault(member, []).append((descriptor, renamed))

    def resolve(owner: str, name: str, descriptor: str | None) -> str | None:
        """The intermediary name of `owner#name`, or `None` if the file cannot say which.

        The owner is tried first and the whole file second, because the two namespaces disagree by
        design: the official mappings repeat an inherited member on every implementor and
        intermediary records it once, so `handlePlayerAction` is filed under
        `ServerGamePacketListener` while the mixin naming it is applied to
        `ServerGamePacketListenerImpl`. A name that is unique in the file is the same member either
        way; one that is not, and that the owner does not settle, is left unresolved rather than
        guessed.
        """
        candidates = by_member.get((owner, name)) or by_bare_name.get(name)
        if not candidates:
            return None
        if descriptor is not None:
            exact = [entry for entry in candidates if entry[0] == descriptor]
            if exact:
                candidates = exact
        found = {renamed for _, renamed in candidates}
        return found.pop() if len(found) == 1 else None

    def rewrite(selector: str, target: str) -> str | None:
        """`selector` in the intermediary namespace, unchanged if no part of it moves, `None` if a
        part of it has no intermediary name at all."""
        parsed = MEMBER_SELECTOR.fullmatch(selector)
        if parsed is None or not parsed.group("name"):
            return None
        owner, name = parsed.group("owner"), parsed.group("name")
        descriptor = parsed.group("method") or parsed.group("field")

        rewritten = ""
        if owner is not None:
            if owner.startswith("net/minecraft/") and owner not in classes:
                return None
            rewritten += f"L{classes.get(owner, owner)};"
        # A constructor is `<init>` in every namespace; only its owner and its descriptor move.
        if name.startswith("<"):
            rewritten += name
        else:
            renamed = resolve(owner or target, name, descriptor)
            if renamed is None:
                return None
            rewritten += renamed
        if descriptor is not None:
            named = [match.group(1) for match in re.finditer(r"L([^;]+);", descriptor)]
            if any(one.startswith("net/minecraft/") and one not in classes for one in named):
                return None
            renamed = re.sub(
                r"L([^;]+);",
                lambda match: f"L{classes.get(match.group(1), match.group(1))};",
                descriptor,
            )
            rewritten += renamed if parsed.group("method") else f":{renamed}"
        return rewritten

    refmap: dict[str, dict[str, str]] = {}
    unresolved = []
    for mixin in mixins:
        if mixin.predicate is not None and not evaluate(mixin.predicate, active):
            continue
        copied = {member for _, member, _ in by_owner.get(mixin.name, [])}
        for shadow in mixin.shadows:
            if shadow.predicate is not None and not evaluate(shadow.predicate, active):
                continue
            # `@Shadow` is answered by the mapping set rather than by the refmap, so what there is
            # to check is that the copy of the target's entry is in the file at all. A shadow of a
            # Carpet member keeps its name and needs none.
            if shadow.selector not in copied and mixin.target.startswith("net/minecraft/"):
                unresolved.append(f"@Shadow {mixin.name}#{shadow.selector}")
        for reference in mixin.references:
            if reference.predicate is not None and not evaluate(reference.predicate, active):
                continue
            rewritten = rewrite(reference.selector, mixin.target)
            if rewritten is None:
                # Unresolvable is an error only where the selector means a Minecraft member. The
                # owner decides that, and where it names none the mixin's target does:
                # `doExplosionA` is Carpet's, is spelled the same at runtime, and needs no entry.
                parsed = MEMBER_SELECTOR.fullmatch(reference.selector)
                named = parsed.group("owner") if parsed else None
                if (named or mixin.target).startswith("net/minecraft/"):
                    unresolved.append(f"{mixin.name}: {reference.selector}")
            elif rewritten != reference.selector:
                refmap.setdefault(mixin.name, {})[reference.selector] = rewritten

    if unresolved:
        raise SystemExit(
            f"  {release}: no intermediary name for a Mixin reference:\n    "
            + "\n    ".join(unresolved)
        )

    # Both halves of the file carry the same table. Mixin reads `data` under the obfuscation context
    # the environment names and `mappings` when it names none, and Fabric names none in production —
    # so the table actually consulted is the second, and Loom wrote both for the same reason.
    return (
        json.dumps(
            {"mappings": refmap, "data": {"named:intermediary": refmap}}, indent=2, sort_keys=True
        )
        + "\n",
        sum(len(entries) for entries in refmap.values()),
    )


def compose(
    release: str, official: str, digest: str, tiny: str, carpet: bytes
) -> tuple[str, str]:
    official_classes, official_members = parse_official(official)
    intermediary_classes, intermediary_members = parse_intermediary(tiny)
    named_classes, named_members = source_names()

    def obfuscate(descriptor: str) -> str | None:
        """A Mojang descriptor in the obfuscated namespace, or `None` if a name is not covered."""
        missing = False

        def one(match: re.Match[str]) -> str:
            nonlocal missing
            name = match.group(1)
            if name in official_classes:
                return f"L{official_classes[name]};"
            # A type the official mappings do not name is a type that was never obfuscated —
            # `java/…`, `com/mojang/…`, `it/unimi/…`. Anything under `net/minecraft` is not that,
            # and translating it to itself would key the lookup below on a descriptor nothing can
            # match, so the member is dropped instead of being silently mistranslated.
            missing |= name.startswith("net/minecraft/")
            return match.group(0)

        rewritten = re.sub(r"L([^;]+);", one, descriptor)
        return None if missing else rewritten

    # Members first: their owners and their descriptors are two of the four things that decide
    # which classes the file has to carry.
    intermediary_signatures = {key[1:] for key in intermediary_members}
    by_owner: dict[str, list[tuple[bool, str, str, str]]] = {}
    wanted: set[str] = set()
    unnamed = inherited = 0
    for owner, is_method, descriptor, name, obfuscated in official_members:
        if name not in named_members:
            continue
        obfuscated_owner = official_classes.get(owner)
        obfuscated_descriptor = obfuscate(descriptor)
        if obfuscated_owner is None or obfuscated_descriptor is None:
            continue
        key = (obfuscated_owner, is_method, obfuscated_descriptor, obfuscated)
        target = intermediary_members.get(key)
        if target is None:
            # The two formats disagree about which type declares a member: the official mappings
            # repeat an interface method on every implementor, intermediary records it once, on the
            # interface. So "no entry under this owner" is usually "the entry is under a supertype",
            # and the obfuscated name is emphatically not the answer — the jar carries the
            # intermediary one, inherited.
            #
            # The exception is a member intermediary names nowhere at all: a synthetic bridge, or
            # something Fabric leaves alone. That one the jar really does carry obfuscated, and the
            # signature is what tells the two apart.
            if (is_method, obfuscated_descriptor, obfuscated) in intermediary_signatures:
                inherited += 1
                continue
            if obfuscated == name:
                continue
            target = obfuscated
            unnamed += 1
        if target == name:
            continue
        by_owner.setdefault(owner, []).append((is_method, descriptor, name, target))
        wanted.add(owner)
        wanted |= {
            match.group(1)
            for match in re.finditer(r"L([^;]+);", descriptor)
            if match.group(1).startswith("net/minecraft/")
        }

    # `@Shadow` is not answered by the refmap — `MixinPreProcessorStandard` looks the member up by
    # the name the mixin class declares, and never consults one — so the name has to be right in the
    # mixin class's own bytecode. Copying the target's mapping under the mixin class is what makes
    # `[build] remap` rename the declaration and every use of it, exactly as Loom's remapper did.
    active = active_features(release)
    identities: set[str] = set()
    for mixin in scan_mixins():
        if mixin.predicate is not None and not evaluate(mixin.predicate, active):
            continue
        for shadow in mixin.shadows:
            if shadow.predicate is not None and not evaluate(shadow.predicate, active):
                continue
            copied = [
                entry for entry in by_owner.get(mixin.target, []) if entry[2] == shadow.selector
            ]
            if copied:
                by_owner.setdefault(mixin.name, []).extend(copied)
                identities.add(mixin.name)
    # A shadow that finds nothing to copy is not reported here. `refmap_for` reads the finished file
    # and sees the same absence, and a check the committed file can answer on its own is the one
    # that also runs without the network.

    # Then the classes: what the mod names itself, and what Carpet's API forces onto the classpath.
    named = named_classes & official_classes.keys()
    # A nested type is written `Outer.Inner` in source and `Outer$Inner` in the mappings, so the
    # scan above cannot see one. Taking every nested type of a class the mod does name is both
    # cheaper than parsing Java and a superset of what the compiler can emit.
    prefixes = tuple(f"{name}$" for name in named)
    wanted |= named
    wanted |= {name for name in official_classes if name.startswith(prefixes)}
    wanted |= identities

    intermediary_to_official = {
        intermediary_classes[obfuscated]: name
        for name, obfuscated in official_classes.items()
        if obfuscated in intermediary_classes
    }
    unresolved = []
    for name in sorted(carpet_types(carpet)):
        official_name = intermediary_to_official.get(f"net/minecraft/{name}")
        if official_name is None:
            # A client-only type has no mapping in the release's official file and cannot reach
            # this mod's classpath either, so it is reported and skipped rather than failing.
            unresolved.append(name)
            continue
        wanted.add(official_name)

    lines = [
        "tiny\t2\t0\tmojang\tintermediary",
        "\tgenerated-by\tmappings/regenerate.py",
        f"\tminecraft\t{release}",
        f"\tclient-mappings-sha1\t{digest}",
    ]
    written = 0
    missing = []
    for owner in sorted(wanted):
        if owner in identities:
            # A mixin class. Its own name does not move — the remapper is being told about its
            # members, not about it — so the class line is an identity and the member lines under it
            # are the copies taken from its target above.
            lines.append(f"c\t{owner}\t{owner}")
            written += 1
            for is_method, descriptor, name, member in sorted(set(by_owner[owner])):
                lines.append(f"\t{'m' if is_method else 'f'}\t{descriptor}\t{name}\t{member}")
            continue
        obfuscated_owner = official_classes.get(owner)
        target = intermediary_classes.get(obfuscated_owner) if obfuscated_owner else None
        if target is None:
            # Absent is not the identity, and must not be written as one: a class the file leaves
            # under its Mojang name is exactly the class that is not there at load time.
            #
            # For a class the mod *names* that is fatal, and it is fatal here rather than in
            # `check-remap.py`, which cannot see it: a `@Mixin(Foo.class)` target appears in the jar
            # only as an annotation element, and the check skips annotations whole. This is the one
            # place that knows the mod means the name.
            if owner in named:
                missing.append(owner)
            elif owner in by_owner:
                print(f"  no intermediary name: {owner}", file=sys.stderr)
            continue
        # An identity — intermediary leaves a handful of types alone, `MinecraftServer` among them —
        # is written out like any other line rather than omitted. Two things need it there: a member
        # lookup walks the hierarchy through the class table and skips an owner the table misses,
        # and `check-remap.py` reads this file as the list of what a Mojang name in the jar is
        # allowed to be.
        lines.append(f"c\t{owner}\t{target}")
        written += 1
        for is_method, descriptor, name, member in sorted(by_owner.get(owner, [])):
            lines.append(f"\t{'m' if is_method else 'f'}\t{descriptor}\t{name}\t{member}")

    if missing:
        raise SystemExit(
            f"  {release}: intermediary names no class the mod does: {' '.join(missing)}"
        )

    text = "\n".join(lines) + "\n"
    refmap, selectors = refmap_for(release, text)

    total = sum(len(entries) for entries in by_owner.values())
    print(
        f"  {written} classes, {total} members, {selectors} refmap selectors"
        + (f", {inherited} left to a supertype" if inherited else "")
        + (f", {unnamed} left obfuscated" if unnamed else "")
        + (f"; unresolved Carpet types: {' '.join(unresolved)}" if unresolved else ""),
        file=sys.stderr,
    )
    return text, refmap


def main(argv: list[str]) -> int:
    arguments = [argument for argument in argv[1:] if argument != "--check"]
    selected = arguments or releases()
    unknown = set(selected) - set(releases())
    if unknown:
        raise SystemExit(f"not obfuscated releases: {' '.join(sorted(unknown))}")

    if "--check" in argv[1:]:
        # The refmaps alone, re-derived from the committed mapping sets. What this catches is a
        # source tree that has moved on from them — a renamed selector, a new injector, a `@Shadow`
        # with nothing to copy — and it catches it without fetching a byte, which is what lets CI
        # run it in the job that already has the tree rather than in one that needs the network.
        stale = []
        for release in selected:
            generated = (ROOT / "mappings" / f"intermediary-{release}.tiny").read_text()
            refmap, selectors = refmap_for(release, generated)
            path = ROOT / "mappings" / f"refmap-{release}.json"
            if not path.exists() or path.read_text() != refmap:
                stale.append(release)
                print(f"{release}: refmap is not what the source says", file=sys.stderr)
            else:
                print(f"{release}: {selectors} refmap selectors, up to date", file=sys.stderr)
        if stale:
            raise SystemExit(
                "re-run `python3 mappings/regenerate.py` for: " + " ".join(stale)
            )
        return 0

    for release in selected:
        print(f"{release}:", file=sys.stderr)
        official, digest = official_mappings(release)
        tiny = zipfile.ZipFile(
            io.BytesIO(fetch(INTERMEDIARY.format(release=release)))
        ).read("mappings/mappings.tiny").decode()
        carpet = fetch(carpet_jar(release))
        text, refmap = compose(release, official, digest, tiny, carpet)
        (ROOT / "mappings" / f"intermediary-{release}.tiny").write_text(text)
        (ROOT / "mappings" / f"refmap-{release}.json").write_text(refmap)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
