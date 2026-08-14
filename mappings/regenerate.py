#!/usr/bin/env python3
"""Regenerate `mappings/intermediary-*.txt`, the mapping sets `[dependencies] remap` reads.

The Carpet jar for an obfuscated release is intermediary-named; the game jar the Minecraft SDK
puts beside it is Mojang-named. `jals` deobfuscates Carpet before it reaches the classpath, and
these files are what it deobfuscates *with*.

Each one is a composition of two published mapping sets:

    Mojang name --(official mappings)--> obfuscated name --(intermediary)--> class_NNNN

restricted to the Minecraft types that appear in the signatures of the Carpet classes this mod
imports. The classpath only has to agree about the types the two jars actually exchange, which is
about twenty names rather than the whole game. A name that is missing is not silent: `javac`
reports the `class_NNNN` it could not resolve, and adding the class to `CARPET_CLASSES` below (or
to the mod's imports) brings it in on the next run.

Everything else is read from `jals.toml`, so a new release needs no edit here: declare its Carpet
jar and its `[[mappings.mojmap]]` entry and run

    python3 mappings/regenerate.py

Needs `javap` (the JDK the build already requires) and nothing beyond the Python standard library.
"""

import hashlib
import io
import re
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

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


def fetch(url: str) -> bytes:
    print(f"  fetching {url}", file=sys.stderr)
    with urllib.request.urlopen(url) as response:
        return response.read()


def parse_manifest() -> tuple[dict[str, str], dict[str, tuple[str, str]]]:
    """`jals.toml`'s Carpet jar URLs and mojmap entries, both keyed by release.

    Only the obfuscated releases appear: 26.1 onward declares no `[[mappings.mojmap]]` because it
    ships deobfuscated, and the intersection of the two tables is exactly the set that needs a
    mapping file.
    """
    text = (ROOT / "jals.toml").read_text()
    carpet = dict(re.findall(r'^"carpet-([\d.]+)" = \{ jar = "([^"]+)"', text, re.M))
    mojmap: dict[str, tuple[str, str]] = {}
    # Anchored to a line of its own: the same table name appears inside the comments above, and a
    # plain substring split would hand the first "block" a comment with no `url` in it.
    for block in re.split(r"^\[\[mappings\.mojmap\]\]$", text, flags=re.M)[1:]:
        release = re.search(r'required-features = \["reobf", "([\d.]+)"\]', block)
        url = re.search(r'url = "([^"]+)"', block)
        sha1 = re.search(r'sha1 = "([0-9a-f]+)"', block)
        mojmap[release.group(1)] = (url.group(1), sha1.group(1))
    return carpet, mojmap


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


def intermediary_index(release: str) -> dict[str, str]:
    """intermediary simple name -> obfuscated internal name, from Fabric's Tiny v2."""
    url = (
        f"https://maven.fabricmc.net/net/fabricmc/intermediary/{release}"
        f"/intermediary-{release}-v2.jar"
    )
    with zipfile.ZipFile(io.BytesIO(fetch(url))) as archive:
        tiny = archive.read("mappings/mappings.tiny").decode()
    index: dict[str, str] = {}
    for line in tiny.splitlines():
        if not line.startswith("c\t"):
            continue
        _, obfuscated, intermediary = line.split("\t")[:3]
        index[intermediary.rsplit("/", 1)[-1]] = obfuscated
    return index


def mojmap_index(url: str, sha1: str) -> dict[str, str]:
    """obfuscated internal name -> Mojang dotted name, from the ProGuard text Mojang publishes.

    Checked against the digest `jals.toml` pins, so this script authenticates the bytes the same
    way the build does.
    """
    text = fetch(url)
    digest = hashlib.sha1(text).hexdigest()
    if digest != sha1:
        raise SystemExit(f"mojmap digest mismatch: {digest} != {sha1} ({url})")
    index: dict[str, str] = {}
    for line in text.decode().splitlines():
        # Class lines only: members are indented, and nothing here renames a member.
        if line.startswith(("#", " ")) or " -> " not in line:
            continue
        mojang, obfuscated = line.rstrip(":").split(" -> ")
        index[obfuscated.replace(".", "/")] = mojang
    return index


def main() -> int:
    carpet_urls, mojmaps = parse_manifest()
    releases = sorted(mojmaps, key=lambda v: [int(part) for part in v.split(".")])
    missing_anywhere = False

    for release in releases:
        print(f"{release}:", file=sys.stderr)
        wanted = carpet_types(fetch(carpet_urls[release]))
        intermediary = intermediary_index(release)
        mojang = mojmap_index(*mojmaps[release])

        rows: list[tuple[str, str]] = []
        unresolved: list[str] = []
        for name in sorted(wanted, key=lambda n: int(n.split("_")[1])):
            obfuscated = intermediary.get(name)
            resolved = mojang.get(obfuscated) if obfuscated else None
            if resolved is None:
                # A client-only type has no server mapping. It cannot reach this mod's classpath
                # either, so it is reported and skipped rather than failing the run.
                unresolved.append(name)
                continue
            rows.append((resolved, name))

        if unresolved:
            missing_anywhere = True
            print(f"  unresolved, skipped: {' '.join(unresolved)}", file=sys.stderr)

        header = (
            f"# Mojang name -> Fabric intermediary name, for `[dependencies] carpet-{release}`.\n"
            "#\n"
            "# Generated by `mappings/regenerate.py`; do not edit. It composes Fabric's\n"
            f"# intermediary mappings for Minecraft {release} with the official mappings\n"
            "# `[[mappings.mojmap]]` pins for this release, restricted to the Minecraft types the\n"
            "# Carpet classes this mod imports mention in their signatures.\n"
        )
        body = "".join(f"{name} -> net.minecraft.{inter}:\n" for name, inter in rows)
        (ROOT / "mappings" / f"intermediary-{release}.txt").write_text(header + body)
        print(f"  {len(rows)} classes", file=sys.stderr)

    return 1 if missing_anywhere else 0


if __name__ == "__main__":
    sys.exit(main())
