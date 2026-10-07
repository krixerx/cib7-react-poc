#!/usr/bin/env bash
# Checks a service pack against this core's platform API (docs/platform-api.md).
#
#   scripts/pack-check.sh [pack-dir]      # default: packs/test, the core test pack
#
# Runs, with the given pack in place of the core test pack, every check that
# holds for any pack: the JUnit tests tagged `pack` in the engine and the
# backend (manifest compatibility, deployments, specs, variable policies, value
# schemas, templates, documents, registry and consent descriptors and their
# migrations, and the pack's own service examples: decisions, fees, registry
# seeds, submissions, templates, flow scenarios), the frontend's pack tests
# (catalog, form definitions, texts in every language, display names,
# branding, contrast, form behaviour examples) and the MCP sidecar's
# (manifests against the form schemas). A pack repository's check.yml runs it
# from a checkout of the core release it names.
#
# Run from anywhere; needs JDK 21, Maven and Node 24 like the core build.
set -euo pipefail

core="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pack="$(cd "${1:-$core/packs/test}" && pwd)"

for part in pack.yaml engine backend frontend branding; do
  if [ ! -e "$pack/$part" ]; then
    echo "pack-check: $pack has no $part (docs/platform-api.md, pack layout)" >&2
    exit 2
  fi
done

# The pack's specs (the source of every generated file in it).
docs="$pack/docs/business/services"
if [ ! -d "$docs" ]; then
  echo "pack-check: $pack has no docs/business/services (the specs)" >&2
  exit 2
fi

echo "pack-check: $pack"
echo "pack-check: specs from $docs"

cd "$core"
echo "== engine"
mvn -B -q -f cib7/pom.xml test -Dgroups=pack \
  -Dservices.pack.dir="$pack/engine" -Dservices.docs.dir="$docs"
echo "== backend"
mvn -B -q -f backend/pom.xml test -Dgroups=pack \
  -Dservices.pack.dir="$pack/backend" -Dservices.docs.dir="$docs"
echo "== frontend"
(cd frontend && PACK_DIR="$pack" npx vitest run src/pack)
echo "== mcp"
(cd mcp && PACK_DIR="$pack" npx vitest run src/services/pack.test.ts)
echo "pack-check: $pack conforms to platform API $(sed -n 's/.*\*\*Platform API version:\*\* `\([0-9.]*\)`.*/\1/p' docs/platform-api.md)"
