#!/usr/bin/env bash
#
# The CycloneDX bill of materials of the whole reactor (22.10), as the release workflow makes it:
# every module and every dependency that ships, in target/storeql-bom.json and .xml. The plugin's
# version and the document's shape are pinned in the parent pom.
#
# Usage: scripts/sbom.sh          then read target/storeql-bom.json
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mvn -B -q -f "$ROOT/pom.xml" org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom
python3 - "$ROOT/target/storeql-bom.json" <<'PY'
import json, sys
bom = json.load(open(sys.argv[1]))
components = bom.get("components", [])
unversioned = [c["name"] for c in components if not c.get("version")]
without_purl = [c["name"] for c in components if not c.get("purl")]
print(f"CycloneDX {bom.get('specVersion')}: {len(components)} components, "
      f"{len(bom.get('dependencies', []))} dependency entries")
if bom.get("bomFormat") != "CycloneDX" or not components or unversioned or without_purl:
    print(f"not a usable SBOM: unversioned={unversioned[:5]} without purl={without_purl[:5]}", file=sys.stderr)
    sys.exit(1)
# What ships, and only that: a test library here means a module that is in no image got counted.
test_only = [c["name"] for c in components
             if any(t in c["name"] for t in ("junit", "mockito", "testcontainers", "archunit", "hamcrest"))]
if test_only:
    print(f"test libraries in the SBOM of what ships: {test_only[:8]}", file=sys.stderr)
    sys.exit(1)
PY
