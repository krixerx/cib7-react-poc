#!/usr/bin/env bash
# Proves a pack's Patchbay layer loads: builds the core patchbay image from this
# checkout, builds the pack's docker/patchbay.Dockerfile on it with a fixture
# package added under patchbay/pack-fixture/, starts it and checks that
# GET /admin/integrations lists the fixture's integration active, the core's
# two integrations active and nothing broken (design D8 of replace-esb-with-patchbay).
#
#   scripts/patchbay-layer-check.sh [pack-dir] [--broken-link]
#
# --broken-link makes the fixture's request link point at a field that does not
# exist; the check must then FAIL. CI runs both and expects the second to fail
# (quality.yml, job patchbay-pack-layer).
#
# The fixture is derived from the core's packages/20-core-pdf at run time, with
# every id renamed to pack-fixture-*, so it always has the layout the pinned
# Patchbay exports. Containers and images are named pb-test-*; the container
# publishes on a random loopback port. Needs docker, node and curl.
set -euo pipefail

core="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
broken=false
pack_arg=""
for arg in "$@"; do
  case "$arg" in
    --broken-link) broken=true ;;
    *) pack_arg="$arg" ;;
  esac
done
pack="$(cd "${pack_arg:-$core/packs/reference}" && pwd)"

if [ ! -f "$pack/docker/patchbay.Dockerfile" ]; then
  echo "patchbay-layer-check: $pack has no docker/patchbay.Dockerfile (docs/platform-api.md, pack layout)" >&2
  exit 2
fi

run="$$-$(date +%s)"
ctx="$(mktemp -d)"
core_image="pb-test-core:$run"
layer_image="pb-test-layer:$run"
container="pb-test-$run"
cleanup() {
  docker rm -f "$container" >/dev/null 2>&1 || true
  docker rmi -f "$layer_image" "$core_image" >/dev/null 2>&1 || true
  rm -rf "$ctx"
}
trap cleanup EXIT

echo "== core patchbay image"
docker build -q -t "$core_image" -f "$core/patchbay/Dockerfile" "$core/patchbay" >/dev/null

echo "== build context: the pack's layer files and patchbay/ plus the fixture"
mkdir -p "$ctx/docker" "$ctx/patchbay"
cp "$pack/docker/patchbay.Dockerfile" "$ctx/docker/"
if [ -f "$pack/docker/patchbay.Dockerfile.dockerignore" ]; then
  cp "$pack/docker/patchbay.Dockerfile.dockerignore" "$ctx/docker/"
fi
if [ -d "$pack/patchbay" ]; then
  cp -R "$pack/patchbay/." "$ctx/patchbay/"
fi
fixture="$ctx/patchbay/pack-fixture"
if [ -e "$fixture" ]; then
  echo "patchbay-layer-check: $pack already has patchbay/pack-fixture; it is reserved for this check" >&2
  exit 2
fi
cp -R "$core/patchbay/packages/20-core-pdf" "$fixture"
(
  cd "$fixture"
  # Longest names first, so pdf-render-result is not caught by pdf-render.
  for f in contracts/pdf-renderer.json contracts/pdf-renderer.openapi.json \
           entities/pdf-render-result.json entities/pdf-render.json \
           services/pdf-render.json services/pdf-render.versions/1.0.0.json manifest.json; do
    sed -i -e 's/pdf-render-result/pack-fixture-result/g' \
           -e 's/pdf-renderer/pack-fixture-system/g' \
           -e 's/pdf-render/pack-fixture/g' "$f"
  done
  mv contracts/pdf-renderer.json contracts/pack-fixture-system.json
  mv contracts/pdf-renderer.openapi.json contracts/pack-fixture-system.openapi.json
  mv entities/pdf-render-result.json entities/pack-fixture-result.json
  mv entities/pdf-render.json entities/pack-fixture.json
  mv services/pdf-render.json services/pack-fixture.json
  mv services/pdf-render.versions services/pack-fixture.versions
  if [ "$broken" = true ]; then
    sed -i 's/"from" : "pr-html"/"from" : "pr-missing-field"/' \
        services/pack-fixture.json services/pack-fixture.versions/1.0.0.json
    grep -q pr-missing-field services/pack-fixture.json
  fi
)

echo "== pack layer"
docker build -q -t "$layer_image" -f "$ctx/docker/patchbay.Dockerfile" \
  --build-arg "PATCHBAY_CORE=$core_image" "$ctx" >/dev/null

echo "== start"
password="pb-test-$(od -An -N12 -tx1 /dev/urandom | tr -d ' \n')"
docker run -d --name "$container" -p 127.0.0.1::8080 --tmpfs /config:uid=10001 \
  -e MAPPER_ADMIN_USER=admin -e "MAPPER_ADMIN_PASSWORD=$password" \
  -e INBOUND_API_KEY=pb-test-key \
  -e MAPPER_URL_MAILPIT=http://127.0.0.1:1 \
  -e MAPPER_URL_PDF_RENDERER=http://127.0.0.1:1 \
  -e MAPPER_URL_PACK_FIXTURE_SYSTEM=http://127.0.0.1:1 \
  "$layer_image" >/dev/null
port="$(docker port "$container" 8080/tcp | head -1 | sed 's/.*://')"

for _ in $(seq 1 60); do
  if curl -fs "http://127.0.0.1:$port/actuator/health/readiness" >/dev/null 2>&1; then break; fi
  sleep 2
done

answer="$(curl -fs -u "admin:$password" "http://127.0.0.1:$port/admin/integrations" || true)"
echo "$answer"
if [ -z "$answer" ]; then
  docker logs "$container" 2>&1 | tail -30 >&2
  echo "patchbay-layer-check: GET /admin/integrations gave no answer" >&2
  exit 1
fi
ANSWER="$answer" node -e '
  const a = JSON.parse(process.env.ANSWER);
  const want = ["mail-send", "pdf-render", "pack-fixture"];
  const missing = want.filter((id) => !(a.active && a.active[id]));
  const broken = Object.keys(a.broken || {});
  if (missing.length || broken.length) {
    console.error("patchbay-layer-check: not active: " + missing.join(", ") +
      "; broken: " + broken.join(", "));
    process.exit(1);
  }
'
echo "patchbay-layer-check: $pack layer loads pack-fixture, mail-send and pdf-render active, nothing broken"
