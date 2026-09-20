#!/usr/bin/env bash
#
# Drives the release path's supply-chain mechanism end to end on this machine (22.10), against a
# throwaway local registry: a real StoreQL image is built with BuildKit's SBOM and max-mode
# provenance, its contents are listed as CycloneDX, the SBOM is attested and the image signed — all
# by digest — and then everything is verified. Then what must NOT verify: a stranger's key, an
# image nobody signed, a tag moved onto that image, and an SBOM attested to a different image.
#
# The one difference from CI: there the signature is keyless (the job's OIDC identity, Sigstore's
# certificate, the public transparency log); a laptop has no such identity, so a key pair made for
# this run stands in. The registry, BuildKit, syft and cosign are the same.
#
# Needs: docker, and tools/{cosign,syft,docker-buildx} (fetched with their published checksums if
# missing), and target/ jars for platform/config (any `mvn install`).
# Usage: scripts/supply-chain-selftest.sh     exit 0 only when every check passed
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="$ROOT/tools"
PORT="${SELFTEST_REGISTRY_PORT:-5005}"
REG="localhost:${PORT}"
IMAGE="${REG}/storeql-config"
NAME="storeql-selftest"
WORK="$(mktemp -d)"
export DOCKER_CONFIG="$WORK/docker"
export COSIGN_PASSWORD=""
export SYFT_REGISTRY_INSECURE_USE_HTTP=true
export SYFT_CHECK_FOR_APP_UPDATE=false

passed=0
failed=0
ok() { printf '  ok    %s\n' "$1"; passed=$((passed + 1)); }
bad() { printf '  FAIL  %s\n' "$1"; failed=$((failed + 1)); }
# expect <yes|no> <label> <command...>: the command must succeed (yes) or must fail (no).
expect() {
  local want="$1" label="$2"
  shift 2
  if "$@" >"$WORK/last.out" 2>&1; then got=yes; else got=no; fi
  if [ "$got" = "$want" ]; then ok "$label"; else bad "$label"; sed 's/^/        /' "$WORK/last.out" | tail -5; fi
}

cleanup() {
  docker buildx rm -f "$NAME" >/dev/null 2>&1
  docker rm -f "${NAME}-registry" >/dev/null 2>&1
  rm -rf "$WORK"
}
trap cleanup EXIT

fetch() { # fetch <file> <url> <checksums-url> <name in the checksum list>
  local file="$1" url="$2" sums="$3" entry="$4"
  [ -x "$TOOLS/$file" ] && return 0
  mkdir -p "$TOOLS"
  echo "fetching $file…"
  curl -fsSL --max-time 600 -o "$WORK/$file.dl" "$url" && curl -fsSL --max-time 60 -o "$WORK/$file.sums" "$sums" || return 1
  local want got
  want="$(grep -E "[ *]${entry}\$" "$WORK/$file.sums" | cut -d' ' -f1)"
  got="$(sha256sum "$WORK/$file.dl" | cut -d' ' -f1)"
  [ -n "$want" ] && [ "$want" = "$got" ] || { echo "$file: checksum mismatch — not installed" >&2; return 1; }
  if [[ "$url" == *.tar.gz ]]; then tar -xzf "$WORK/$file.dl" -C "$TOOLS" "$file"; else mv "$WORK/$file.dl" "$TOOLS/$file"; fi
  chmod +x "$TOOLS/$file"
}
COSIGN_V=v3.1.3
SYFT_V=1.51.1
BUILDX_V=v0.37.1
fetch cosign "https://github.com/sigstore/cosign/releases/download/${COSIGN_V}/cosign-linux-amd64" \
  "https://github.com/sigstore/cosign/releases/download/${COSIGN_V}/cosign_checksums.txt" cosign-linux-amd64 || exit 2
fetch syft "https://github.com/anchore/syft/releases/download/v${SYFT_V}/syft_${SYFT_V}_linux_amd64.tar.gz" \
  "https://github.com/anchore/syft/releases/download/v${SYFT_V}/syft_${SYFT_V}_checksums.txt" "syft_${SYFT_V}_linux_amd64.tar.gz" || exit 2
fetch docker-buildx "https://github.com/docker/buildx/releases/download/${BUILDX_V}/buildx-${BUILDX_V}.linux-amd64" \
  "https://github.com/docker/buildx/releases/download/${BUILDX_V}/checksums.txt" "buildx-${BUILDX_V}.linux-amd64" || exit 2
mkdir -p "$DOCKER_CONFIG/cli-plugins"
ln -sf "$TOOLS/docker-buildx" "$DOCKER_CONFIG/cli-plugins/docker-buildx"
cosign() { "$TOOLS/cosign" "$@"; }
syft() { "$TOOLS/syft" "$@"; }

ls "$ROOT"/platform/config/target/config-svc*.jar >/dev/null 2>&1 || { echo "no platform/config jar: run mvn install first" >&2; exit 2; }

echo "== a throwaway registry and a BuildKit that can reach it"
docker rm -f "${NAME}-registry" >/dev/null 2>&1
docker run -d --name "${NAME}-registry" -p "127.0.0.1:${PORT}:5000" registry:2 >/dev/null || exit 2
printf '[registry."%s"]\n  http = true\n' "$REG" > "$WORK/buildkitd.toml"
docker buildx rm -f "$NAME" >/dev/null 2>&1
docker buildx create --name "$NAME" --driver docker-container --driver-opt network=host \
  --buildkitd-config "$WORK/buildkitd.toml" >/dev/null || exit 2

build() { # build <tag> <label value> → prints the pushed digest
  docker buildx build --builder "$NAME" --push --provenance=mode=max --sbom=true \
    --metadata-file "$WORK/meta-$1.json" --file "$ROOT/Dockerfile.svc" \
    --build-arg SVC_PATH=platform/config --build-arg SVC_JAR=config-svc \
    --label "storeql.selftest=$2" -t "${IMAGE}:$1" "$ROOT" >"$WORK/build-$1.log" 2>&1 || { tail -20 "$WORK/build-$1.log" >&2; return 1; }
  python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['containerimage.digest'])" "$WORK/meta-$1.json"
}

echo "== build, as docker-publish.yml builds"
DIGEST="$(build release released)" || exit 2
REF="${IMAGE}@${DIGEST}"
echo "   $REF"
[[ "$DIGEST" == sha256:* ]] && ok "the push answers with a digest, and everything below is bound to it" || bad "no digest"

docker buildx imagetools inspect "$REF" --format '{{ json .SBOM }}' >"$WORK/buildkit-sbom.json" 2>/dev/null
expect yes "BuildKit attached an SBOM to the image (SPDX, with packages in it)" \
  python3 -c "import json,sys; d=json.load(open('$WORK/buildkit-sbom.json')); s=d.get('SPDX') or next(iter(d.values()))['SPDX']; sys.exit(0 if len(s.get('packages',[]))>10 else 1)"
docker buildx imagetools inspect "$REF" --format '{{ json .Provenance }}' >"$WORK/buildkit-prov.json" 2>/dev/null
expect yes "and max-mode SLSA provenance: the build's definition, its arguments, its materials" \
  python3 -c "import json,sys; d=json.load(open('$WORK/buildkit-prov.json')); s=d.get('SLSA') or next(iter(d.values()))['SLSA']; t=json.dumps(s); sys.exit(0 if 'SVC_JAR' in t and ('materials' in t or 'resolvedDependencies' in t) else 1)"

echo "== what the image contains, as CycloneDX"
expect yes "syft lists the pushed image by digest" syft "registry:${REF}" -q -o "cyclonedx-json=$WORK/sbom.cdx.json"
expect yes "the SBOM names the service's own jar, Helidon and the OS packages, each with a version and a purl" \
  python3 - "$WORK/sbom.cdx.json" <<'PY'
import json, sys
bom = json.load(open(sys.argv[1]))
cs = bom.get("components", [])
names = " ".join(c.get("name", "") for c in cs).lower()
libs = [c for c in cs if c.get("type") == "library"]
ok = (bom.get("bomFormat") == "CycloneDX" and "helidon" in names and "config-svc" in names
      and any(c.get("type") == "operating-system" for c in cs)
      and len(libs) > 50 and all(c.get("purl") for c in libs if c.get("version")))
sys.exit(0 if ok else 1)
PY

echo "== sign and attest, by digest"
(cd "$WORK" && cosign generate-key-pair >/dev/null 2>&1 && mv cosign.key release.key && mv cosign.pub release.pub \
  && cosign generate-key-pair >/dev/null 2>&1 && mv cosign.key stranger.key && mv cosign.pub stranger.pub) || exit 2
# No transparency log on a laptop: a signing config that names no services.
cosign signing-config create --out "$WORK/signing-config.json" >/dev/null 2>&1
SIGN=(--key "$WORK/release.key" --signing-config "$WORK/signing-config.json" --allow-http-registry --yes)
VERIFY=(--key "$WORK/release.pub" --insecure-ignore-tlog=true --allow-http-registry)
expect yes "the image is signed" cosign sign "${SIGN[@]}" "$REF"
expect yes "the CycloneDX SBOM is attested to it" cosign attest "${SIGN[@]}" --type cyclonedx --predicate "$WORK/sbom.cdx.json" "$REF"

echo "== what must verify"
expect yes "[+] the signature verifies against the release key" cosign verify "${VERIFY[@]}" "$REF"
expect yes "[+] the SBOM attestation verifies, and is the SBOM that was attested" \
  bash -c "'$TOOLS/cosign' verify-attestation ${VERIFY[*]} --type cyclonedx '$REF' 2>/dev/null | python3 -c \"
import base64, json, sys
line = sys.stdin.readline()
env = json.loads(line)
payload = env.get('payload') or env.get('dsseEnvelope', {}).get('payload')
st = json.loads(base64.b64decode(payload))
names = ' '.join(c.get('name', '') for c in st['predicate'].get('components', [])).lower()
digest = st['subject'][0]['digest']['sha256']
sys.exit(0 if 'helidon' in names and digest == '${DIGEST#sha256:}' else 1)\""

echo "== what must not"
expect no "[-] a stranger's key does not verify the signature" \
  cosign verify --key "$WORK/stranger.pub" --insecure-ignore-tlog=true --allow-http-registry "$REF"
expect no "[-] nor the attestation" \
  cosign verify-attestation --key "$WORK/stranger.pub" --insecure-ignore-tlog=true --allow-http-registry --type cyclonedx "$REF"
expect no "[-] an attestation of another kind is not there to be found" \
  cosign verify-attestation "${VERIFY[@]}" --type spdxjson "$REF"

OTHER="$(build unsigned tampered)" || exit 2
OTHER_REF="${IMAGE}@${OTHER}"
[ "$OTHER" != "$DIGEST" ] && ok "a second build with one label changed is a different digest" || bad "the second build has the same digest"
expect no "[-] an image nobody signed does not verify" cosign verify "${VERIFY[@]}" "$OTHER_REF"
expect no "[-] and carries no SBOM attestation" cosign verify-attestation "${VERIFY[@]}" --type cyclonedx "$OTHER_REF"

# The tag moved onto the unsigned image: what a registry compromise or a careless push looks like.
docker buildx imagetools create --builder "$NAME" -t "${IMAGE}:release" "$OTHER_REF" >/dev/null 2>&1
expect no "[abuse] the release tag moved onto the unsigned image: verifying the tag now fails" cosign verify "${VERIFY[@]}" "${IMAGE}:release"
expect yes "[abuse] while the digest that was signed still verifies — a digest cannot be moved" cosign verify "${VERIFY[@]}" "$REF"

# A stranger signs the unsigned image with their own key: still not ours.
cosign sign --key "$WORK/stranger.key" --signing-config "$WORK/signing-config.json" --allow-http-registry --yes "$OTHER_REF" >/dev/null 2>&1
expect no "[abuse] a stranger signing their own image does not make it verify against the release key" cosign verify "${VERIFY[@]}" "$OTHER_REF"
expect yes "[abuse] (it does verify against the stranger's key: the check is about who, not whether)" \
  cosign verify --key "$WORK/stranger.pub" --insecure-ignore-tlog=true --allow-http-registry "$OTHER_REF"

echo
echo "supply chain self-test: ${passed} passed, ${failed} failed"
[ "$failed" -eq 0 ]
