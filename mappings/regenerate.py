#!/usr/bin/env python3
"""Regenerate `mappings/intermediary-*.tiny`, the mapping sets both remap steps read.

One file per obfuscated release, and one file serves **both** directions:

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
  * **Members** — everything the mod source names in a member position (`.foo`, `::foo`), under
    every class that declares it. Which class *declares* an inherited member is not something this
    script has to work out: jals walks the real class hierarchy of the compile classpath, so an
    entry filed under the true declaring type is found from any subtype, and an entry that no
    lookup reaches costs a line and nothing else.

Nothing here is silent when it is wrong. A Carpet type that is missing stops `javac` on the
`class_NNNN` it could not resolve, and a mod reference that is missing is what
`mappings/check-remap.py` fails the build on.

    python3 mappings/regenerate.py            # every obfuscated release
    python3 mappings/regenerate.py 1.17.1     # one of them

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
import urllib.request
import zipfile
from pathlib import Path

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
# Mixin's own strings. Not remapped in the jar (that needs a refmap, which is not generated yet),
# but a name that appears only here is still a name the mod means, and one line costs nothing.
MIXIN_TARGET = re.compile(r'"([A-Za-z_$][A-Za-z0-9_$]*)"')

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
        members |= set(MIXIN_TARGET.findall(text))
    return classes, members


def compose(release: str, official: str, digest: str, tiny: str, carpet: bytes) -> str:
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

    # Then the classes: what the mod names itself, and what Carpet's API forces onto the classpath.
    named = named_classes & official_classes.keys()
    # A nested type is written `Outer.Inner` in source and `Outer$Inner` in the mappings, so the
    # scan above cannot see one. Taking every nested type of a class the mod does name is both
    # cheaper than parsing Java and a superset of what the compiler can emit.
    prefixes = tuple(f"{name}$" for name in named)
    wanted |= named
    wanted |= {name for name in official_classes if name.startswith(prefixes)}

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

    total = sum(len(entries) for entries in by_owner.values())
    print(
        f"  {written} classes, {total} members"
        + (f", {inherited} left to a supertype" if inherited else "")
        + (f", {unnamed} left obfuscated" if unnamed else "")
        + (f"; unresolved Carpet types: {' '.join(unresolved)}" if unresolved else ""),
        file=sys.stderr,
    )
    return "\n".join(lines) + "\n"


def main(argv: list[str]) -> int:
    selected = argv[1:] or releases()
    unknown = set(selected) - set(releases())
    if unknown:
        raise SystemExit(f"not obfuscated releases: {' '.join(sorted(unknown))}")

    for release in selected:
        print(f"{release}:", file=sys.stderr)
        official, digest = official_mappings(release)
        tiny = zipfile.ZipFile(
            io.BytesIO(fetch(INTERMEDIARY.format(release=release)))
        ).read("mappings/mappings.tiny").decode()
        carpet = fetch(carpet_jar(release))
        text = compose(release, official, digest, tiny, carpet)
        (ROOT / "mappings" / f"intermediary-{release}.tiny").write_text(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
