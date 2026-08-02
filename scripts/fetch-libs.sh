#!/bin/sh
# Fetch the two jars `jals build` needs and cannot fetch itself: carpet and the Fabric loader.
#
# Everything else on the compile classpath — Minecraft, Mixin, MixinExtras — comes from the
# `minecraft` dependency in `jals.toml`, where every download is pinned by SHA-1. Neither of these
# two is published anywhere jals could pin a digest against, so they are fetched here and read from
# `libs/` by `build.rhai`.
#
#   usage: scripts/fetch-libs.sh <release>      e.g. scripts/fetch-libs.sh 1.21.8
set -eu

release="${1:-}"
if [ -z "$release" ]; then
    echo "usage: $0 <release>" >&2
    exit 2
fi

root=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
release_properties="$root/versions/$release/release.properties"
if [ ! -f "$release_properties" ]; then
    echo "$0: no such release: $release" >&2
    echo "known releases: $(ls "$root/versions")" >&2
    exit 1
fi

# One `key = value` line out of a properties file, whitespace around either side ignored.
property() {
    sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*\\(.*\\)[[:space:]]*$/\\1/p" "$2" | head -n 1
}

carpet_version=$(property carpet_version "$release_properties")
loader_url=$(property loader_jar_url "$root/mod.properties")

# Two forms, exactly as the Gradle build accepted: a jitpack coordinate, or a version on masa's
# maven (where carpet is released).
case "$carpet_version" in
    com.github.*:fabric-carpet:*)
        group=$(echo "$carpet_version" | cut -d: -f1 | tr '.' '/')
        version=$(echo "$carpet_version" | cut -d: -f3)
        carpet_url="https://jitpack.io/$group/fabric-carpet/$version/fabric-carpet-$version.jar"
        ;;
    *)
        carpet_url="https://masa.dy.fi/maven/carpet/fabric-carpet/$carpet_version/fabric-carpet-$carpet_version.jar"
        ;;
esac

libs="$root/libs"
# Replaced wholesale: a jar left from another release is a classpath entry for the wrong Minecraft,
# and `build.rhai` adds everything it finds here.
rm -rf "$libs"
mkdir -p "$libs"

for url in "$carpet_url" "$loader_url"; do
    name=$(basename "$url")
    echo "fetching $name"
    curl --fail --location --silent --show-error --output "$libs/$name" "$url"
done

echo "libs/ now holds:"
ls -1 "$libs"
