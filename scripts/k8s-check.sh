#!/usr/bin/env bash
# Validates the Kubernetes manifests: schema (kubeconform, strict; CRDs without a public schema are
# skipped) and the hardening the platform promises (scripts/k8s-hardening-check.py). Run before a
# deploy and in CI.
set -euo pipefail
cd "$(dirname "$0")/.."
KUBECONFORM="$(command -v kubeconform || echo ./tools/kubeconform)"
if [ -x "$KUBECONFORM" ]; then
  "$KUBECONFORM" -strict -ignore-missing-schemas -summary k8s/*.yaml
else
  echo "kubeconform not found (PATH or ./tools/kubeconform): schema validation skipped" >&2
fi
python3 scripts/k8s-hardening-check.py
