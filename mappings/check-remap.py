#!/usr/bin/env python3
"""Fail if a jar still names Minecraft the way this mod's source does.

`[build] remap` rewrites the compiled classes into the names Fabric loads a mod through, and it
does it against a mapping set that is deliberately not the whole game (see `regenerate.py`). A name
the set does not cover is not an error there — the remapper leaves what it cannot rename — so the
jar builds, ships, and dies at the first reference. This is what turns that into a build failure.

In the intermediary namespace the check is exact rather than heuristic: every Minecraft class is
`net/minecraft/class_NNNN` and every member Fabric renames is `method_NNNN` or `field_NNNN`. What
is left over is what the mapping file itself records as left over, so that file is read here as the
list of what a Mojang-shaped name is still allowed to be:

  * a class the mapping maps onto itself — intermediary does not rename `MinecraftServer`
  * a member it maps onto a name Fabric left obfuscated — `below` → `m`, a synthetic bridge

Two things it deliberately does not look at. **Annotations** are skipped whole: Mixin's `method =`
and `target =` strings are still Mojang-named, which needs a refmap nobody generates yet, and
skipping the attribute is what keeps that known gap from drowning this signal. **Superseded
constant-pool entries** are not read, because they are not reachable: a rename interns a new `Utf8`
and repoints the entry at it, so the old string survives in the pool of a perfectly remapped class.
Class and reference entries are read from the pool anyway — those the remapper rewrites in place,
so one left Mojang-named is one it could not rename. Descriptors are read from the class structure,
where a superseded string cannot be reached.

    python3 mappings/check-remap.py 1.17.1 target/jals/remap/intricarpet-2.0.7.jar
"""

import re
import struct
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

MINECRAFT = re.compile(r"net/minecraft/[A-Za-z0-9_$/]+")
INTERMEDIARY_CLASS = re.compile(r"net/minecraft/class_\d+(\$(class_\d+|\d+))*\Z")
INTERMEDIARY_MEMBER = re.compile(r"(method|field)_\d+\Z")

# Declared by `java.lang.Object`, so a call on a Minecraft receiver resolves there and nothing
# renames it. Everything else a Minecraft class inherits from outside Minecraft —
# `Comparable.compareTo`, `Iterable.iterator` — reaches this check through the mapping file
# instead, because the official mappings name the Minecraft override.
OBJECT_MEMBERS = frozenset(
    "<init> <clinit> clone equals finalize getClass hashCode notify notifyAll toString wait".split()
)

# Constant pool tags by the width of what follows them. Tags 5 and 6 additionally consume the
# following index, which is the format's one irregularity.
WIDTHS = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4, 15: 3, 16: 2, 17: 4,
          18: 4, 19: 2, 20: 2}
UTF8, CLASS, NAME_AND_TYPE, METHOD_TYPE = 1, 7, 12, 16
# Fieldref, Methodref, InterfaceMethodref: an owner and a name-and-type.
OWNED = (9, 10, 11)
# Dynamic, InvokeDynamic: a name-and-type and no owner.
DYNAMIC = (17, 18)


class ClassFile:
    """As much of a class file as it takes to tell a live name from a superseded one."""

    def __init__(self, data: bytes):
        if data[:4] != b"\xca\xfe\xba\xbe":
            raise ValueError("not a class file")
        self.data = data
        self.pool: dict[int, tuple[int, tuple]] = {}
        count = struct.unpack_from(">H", data, 8)[0]
        offset = 10
        index = 1
        while index < count:
            tag = data[offset]
            offset += 1
            if tag == UTF8:
                length = struct.unpack_from(">H", data, offset)[0]
                text = data[offset + 2 : offset + 2 + length].decode("utf-8", "replace")
                self.pool[index] = (tag, (text,))
                offset += 2 + length
            elif tag in WIDTHS:
                width = WIDTHS[tag]
                indices = (
                    struct.unpack_from(">" + "H" * (width // 2), data, offset)
                    if width in (2, 4)
                    else ()
                )
                self.pool[index] = (tag, indices)
                offset += width
                if tag in (5, 6):
                    index += 1
            else:
                raise ValueError(f"unknown constant tag {tag}")
            index += 1
        self.body = offset

    def utf8(self, index: int) -> str:
        tag, values = self.pool[index]
        assert tag == UTF8, f"constant {index} is tag {tag}, not Utf8"
        return values[0]

    def classes(self) -> set[str]:
        return {self.utf8(values[0]) for tag, values in self.pool.values() if tag == CLASS}

    def references(self) -> set[tuple[str | None, str, str]]:
        """`(owner, name, descriptor)` per member reference; `owner` is `None` for a dynamic one."""
        found = set()
        for tag, values in self.pool.values():
            if tag in OWNED:
                owner = self.utf8(self.pool[values[0]][1][0])
            elif tag in DYNAMIC:
                owner = None
            else:
                continue
            name, descriptor = self.pool[values[1]][1]
            found.add((owner, self.utf8(name), self.utf8(descriptor)))
        return found

    def descriptors(self) -> set[str]:
        """Every descriptor and signature the class *structure* points at.

        Walked rather than swept out of the pool because a descriptor is a bare `Utf8`, and a
        superseded one is indistinguishable from a live one anywhere but here. `MethodType`
        constants ride along from the pool: those the remapper rewrites in place.
        """
        data = self.data
        found = {self.utf8(values[0]) for tag, values in self.pool.values() if tag == METHOD_TYPE}
        # access_flags, this_class, super_class, interfaces_count, interfaces.
        offset = self.body + 8 + 2 * struct.unpack_from(">H", data, self.body + 6)[0]

        def attributes(offset: int) -> int:
            count = struct.unpack_from(">H", data, offset)[0]
            offset += 2
            for _ in range(count):
                name = self.utf8(struct.unpack_from(">H", data, offset)[0])
                length = struct.unpack_from(">I", data, offset + 2)[0]
                if name == "Signature":
                    found.add(self.utf8(struct.unpack_from(">H", data, offset + 6)[0]))
                offset += 6 + length
            return offset

        for _ in range(2):  # fields, then methods: the two have the same shape.
            count = struct.unpack_from(">H", data, offset)[0]
            offset += 2
            for _ in range(count):
                # access_flags, name_index, descriptor_index, then the member's attributes.
                found.add(self.utf8(struct.unpack_from(">H", data, offset + 4)[0]))
                offset = attributes(offset + 6)
        attributes(offset)
        return found


def obfuscated_release(release: str) -> bool:
    """Whether the release ships obfuscated, read from the manifest that decides it.

    `remap = "intermediary"` on the release's Carpet dependency is that declaration — 26.1 onward
    carries none, because there the Mojang name already is the runtime name and `[build] remap`
    activates no alternative. Read rather than listed here so the two cannot drift.
    """
    return bool(
        re.search(
            rf'^"carpet-{re.escape(release)}" = \{{ jar = "[^"]+".*remap = "intermediary"',
            (ROOT / "jals.toml").read_text(),
            re.M,
        )
    )


def mapping(release: str) -> tuple[set[str], set[tuple[str, str]]]:
    """`(classes the mapping leaves Mojang-named, (owner, member) pairs it leaves obfuscated)`."""
    path = ROOT / "mappings" / f"intermediary-{release}.tiny"
    if not path.exists():
        raise SystemExit(f"no mapping file for `{release}`: {path}")
    identities: set[str] = set()
    obfuscated: set[tuple[str, str]] = set()
    owner = None
    for line in path.read_text().splitlines():
        columns = line.split("\t")
        if line.startswith("c\t"):
            owner = columns[2]
            if columns[1] == columns[2]:
                identities.add(columns[1])
        elif line.startswith(("\tm\t", "\tf\t")) and not INTERMEDIARY_MEMBER.match(columns[4]):
            obfuscated.add((owner, columns[4]))
    return identities, obfuscated


def check(entry: str, classfile: ClassFile, identities: set[str],
          obfuscated: set[tuple[str, str]]) -> list[str]:
    findings = []
    for name in sorted(classfile.classes()):
        if name.startswith("net/minecraft/") and not INTERMEDIARY_CLASS.match(name):
            if name not in identities:
                findings.append(f"{entry}: class not remapped: `{name}`")
    seen = classfile.descriptors() | {d for _, _, d in classfile.references()}
    for descriptor in sorted(seen):
        for name in sorted(set(MINECRAFT.findall(descriptor))):
            if not INTERMEDIARY_CLASS.match(name) and name not in identities:
                findings.append(f"{entry}: class not remapped in `{descriptor}`: `{name}`")
    for owner, name, _ in sorted(classfile.references(), key=lambda ref: (ref[0] or "", ref[1])):
        if owner is None or not owner.startswith("net/minecraft/"):
            continue
        if name in OBJECT_MEMBERS or INTERMEDIARY_MEMBER.match(name):
            continue
        if (owner, name) in obfuscated:
            continue
        findings.append(f"{entry}: member of `{owner}` not remapped: `{name}`")
    return findings


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        raise SystemExit(f"usage: {argv[0]} <release> <jar>")
    release, jar = argv[1], argv[2]
    if not obfuscated_release(release):
        # Nothing was renamed and nothing should have been: the check would be that the jar still
        # says what the source says, which the compiler already guaranteed.
        print(f"{jar}: {release} ships deobfuscated, nothing to check")
        return 0
    identities, obfuscated = mapping(release)

    findings: list[str] = []
    with zipfile.ZipFile(jar) as archive:
        entries = sorted(name for name in archive.namelist() if name.endswith(".class"))
        for entry in entries:
            findings += check(entry, ClassFile(archive.read(entry)), identities, obfuscated)

    if not findings:
        print(f"{jar}: {len(entries)} classes, every Minecraft reference intermediary-named")
        return 0
    for finding in findings:
        print(finding, file=sys.stderr)
    print(
        f"\n{len(findings)} reference(s) the mapping set does not cover. Re-run "
        f"`python3 mappings/regenerate.py {release}`; if that does not add them, the name is one "
        "its filters drop — see the docstring there.",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
