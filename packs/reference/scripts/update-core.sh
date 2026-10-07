#!/usr/bin/env bash
# Moves this pack to another core release: writes CORE_VERSION in
# docker/core.conf and vendors that release's service-builder skill into
# .claude/skills/service-builder/ (the release asset
# service-builder-<version>.tar.gz). The images follow from core.conf; check
# the pack with the core's pack-check.sh afterwards (check.yml does).
#
#   scripts/update-core.sh <x.y.z>
set -euo pipefail

version="${1:-}"
if ! [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "usage: scripts/update-core.sh <x.y.z>" >&2
  exit 2
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=/dev/null
. "$root/docker/core.conf"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
# SERVICE_BUILDER_URL overrides the release asset (a mirror, or a local
# file:// archive built with the core's scripts/package-service-builder.mjs).
url="${SERVICE_BUILDER_URL:-https://github.com/$CORE_REPO/releases/download/v$version/service-builder-$version.tar.gz}"
echo "update-core: $url"
curl -fsSL "$url" -o "$tmp/skill.tar.gz"
tar -xzf "$tmp/skill.tar.gz" -C "$tmp"
if ! grep -qx "core $version" "$tmp/service-builder/VERSION"; then
  echo "update-core: the archive's VERSION does not name core $version" >&2
  exit 1
fi

rm -rf "$root/.claude/skills/service-builder"
mkdir -p "$root/.claude/skills"
mv "$tmp/service-builder" "$root/.claude/skills/service-builder"
sed -i.bak "s/^CORE_VERSION=.*/CORE_VERSION=$version/" "$root/docker/core.conf"
rm -f "$root/docker/core.conf.bak"
echo "update-core: core $version ($(sed -n 's/^platform-api //p' "$root/.claude/skills/service-builder/VERSION")); check pack.yaml's platform"
