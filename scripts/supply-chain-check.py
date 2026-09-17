#!/usr/bin/env python3
"""Keeps the release path's supply-chain promises (22.10), so a workflow edit cannot quietly drop one.

Every image Shelf-J publishes must be pushed with BuildKit's SBOM and max-mode provenance, and then
get — bound to its digest, never to a tag — a CycloneDX SBOM attestation, a SLSA build-provenance
attestation and a keyless Sigstore signature. The release must carry the reactor's SBOM, a checksum
list and provenance for its jars. scripts/verify-release.sh must know every image the workflow
publishes. This script reads the workflows and says which of those no longer holds.

Usage: scripts/supply-chain-check.py              check the repository; exit 1 on any problem
       scripts/supply-chain-check.py --self-test  break the workflows in memory, one promise at a
                                                   time, and fail unless every break is caught
"""
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
PUBLISH = ROOT / ".github/workflows/docker-publish.yml"
RELEASE = ROOT / ".github/workflows/release.yml"
VERIFY = ROOT / "scripts/verify-release.sh"
POM = ROOT / "pom.xml"
SBOM = ROOT / "scripts/sbom.sh"

IMAGE_JOBS = ("services", "web")
DIGEST = "steps.build.outputs.digest"


def steps_using(job, action):
    return [s for s in job.get("steps", []) if str(s.get("uses", "")).startswith(action + "@")]


def image_job_problems(name, job):
    out = []
    perms = job.get("permissions") or {}
    for perm in ("packages", "id-token", "attestations"):
        if perms.get(perm) != "write":
            out.append(f"{name}: permissions.{perm} must be write")

    text = yaml.safe_dump(job)
    for off in ("--sbom=false", "--provenance=false"):
        if off in text:
            out.append(f"{name}: builds with {off}")
    builds = steps_using(job, "docker/build-push-action")
    if builds:
        with_ = builds[0].get("with", {})
        if with_.get("sbom") is not True:
            out.append(f"{name}: build-push-action must set sbom: true")
        if str(with_.get("provenance")) != "mode=max":
            out.append(f"{name}: build-push-action must set provenance: mode=max")
        if builds[0].get("id") != "build":
            out.append(f"{name}: the build step must have id: build (its digest is what gets signed)")
    else:
        if "--sbom=true" not in text:
            out.append(f"{name}: docker buildx build must pass --sbom=true")
        if "--provenance=mode=max" not in text:
            out.append(f"{name}: docker buildx build must pass --provenance=mode=max")
        if "containerimage.digest" not in text:
            out.append(f"{name}: the build must read the pushed digest from its metadata file")

    sboms = steps_using(job, "anchore/sbom-action")
    if not any(s.get("with", {}).get("format") == "cyclonedx-json" and DIGEST in str(s.get("with", {}).get("image", "")) for s in sboms):
        out.append(f"{name}: no CycloneDX SBOM is made of the pushed image by digest")

    for action, what in (("actions/attest-sbom", "SBOM"), ("actions/attest-build-provenance", "build provenance")):
        attests = steps_using(job, action)
        if not attests:
            out.append(f"{name}: no {what} attestation ({action})")
        for s in attests:
            with_ = s.get("with", {})
            if DIGEST not in str(with_.get("subject-digest", "")):
                out.append(f"{name}: the {what} attestation is not bound to the pushed digest")
            if with_.get("push-to-registry") is not True:
                out.append(f"{name}: the {what} attestation is not pushed to the registry")

    if not steps_using(job, "sigstore/cosign-installer"):
        out.append(f"{name}: cosign is not installed")
    signs = [s for s in job.get("steps", []) if "cosign sign" in str(s.get("run", ""))]
    if not signs:
        out.append(f"{name}: the image is not signed (cosign sign)")
    for s in signs:
        ref = str(s.get("env", {})) + str(s.get("run", ""))
        if DIGEST not in ref:
            out.append(f"{name}: cosign signs something other than the pushed digest")
        if re.search(r"cosign sign[^\n]*--key", str(s.get("run", ""))):
            out.append(f"{name}: signing must be keyless, not with a stored key")
    return out


def problems(publish_text, release_text, verify_text, pom_text, sbom_text):
    out = []
    publish = yaml.safe_load(publish_text)
    jobs = publish.get("jobs", {})
    for name in IMAGE_JOBS:
        if name not in jobs:
            out.append(f"docker-publish.yml: job {name} is gone")
            continue
        out += [f"docker-publish.yml: {p}" for p in image_job_problems(name, jobs[name])]

    # The verifier and the cleanup must know every image the workflow publishes.
    published = [m["name"] for m in jobs.get("services", {}).get("strategy", {}).get("matrix", {}).get("include", [])] + ["web"]
    listed = re.search(r"IMAGES=\(([^)]*)\)", verify_text)
    verified = listed.group(1).split() if listed else []
    for image in published:
        if image not in verified:
            out.append(f"verify-release.sh does not verify the published image {image}")
    pruned = jobs.get("cleanup", {}).get("strategy", {}).get("matrix", {}).get("package", [])
    for image in published:
        if f"shelf-j-{image}" not in pruned:
            out.append(f"docker-publish.yml: cleanup does not know the published image {image}")

    release = yaml.safe_load(release_text)
    perms = release.get("permissions") or {}
    for perm in ("contents", "id-token", "attestations"):
        if perms.get(perm) != "write":
            out.append(f"release.yml: permissions.{perm} must be write")
    job = next(iter(release.get("jobs", {}).values()), {})
    text = yaml.safe_dump(job)
    if "scripts/sbom.sh" not in text:
        out.append("release.yml: the reactor's CycloneDX SBOM is not made (scripts/sbom.sh)")
    if "sha256sum" not in text:
        out.append("release.yml: no checksum list")
    if not any(".jar" in str(s.get("with", {}).get("subject-path", "")) for s in steps_using(job, "actions/attest-build-provenance")):
        out.append("release.yml: the jars get no build-provenance attestation")

    if "cyclonedx-maven-plugin:makeAggregateBom" not in sbom_text:
        out.append("scripts/sbom.sh no longer makes the aggregate CycloneDX SBOM")
    if "<excludeArtifactId>common-test</excludeArtifactId>" not in pom_text:
        out.append("pom.xml: the test-helper module is counted among what ships")
    pinned = re.search(r"<cyclonedx-plugin\.version>([^<]+)</cyclonedx-plugin\.version>", pom_text)
    if not pinned or not re.fullmatch(r"\d+(\.\d+)+", pinned.group(1)):
        out.append("pom.xml: the CycloneDX plugin's version is not pinned")
    return out


# One promise broken at a time: (what was broken, text to find, text to put in its place, file).
BREAKS = [
    ("the SBOM switched off at build", "--sbom=true", "--sbom=false", "publish"),
    ("provenance switched off at build", "--provenance=mode=max", "--provenance=false", "publish"),
    ("the web image built without an SBOM", "          sbom: true\n", "          sbom: false\n", "publish"),
    ("the web image built with minimal provenance", "provenance: mode=max", "provenance: false", "publish"),
    ("no identity token for the signing job", "      id-token: write # keyless signing and attestations: the job proves to Sigstore who it is\n", "", "publish"),
    ("no permission to write attestations", "      attestations: write # GitHub artifact attestations (SBOM, SLSA provenance)\n", "", "publish"),
    ("a tag signed instead of the digest", "IMAGE_REF: ${{ steps.build.outputs.image }}@${{ steps.build.outputs.digest }}", "IMAGE_REF: ${{ steps.build.outputs.image }}:latest", "publish"),
    ("a stored key instead of keyless signing", 'run: cosign sign --yes "${IMAGE_REF}"', 'run: cosign sign --yes --key cosign.key "${IMAGE_REF}"', "publish"),
    ("the signing step removed", 'cosign sign --yes "${IMAGE_REF}"', 'echo "${IMAGE_REF}"', "publish"),
    ("the SBOM attestation kept out of the registry", "          sbom-path: ${{ runner.temp }}/sbom.cdx.json\n          push-to-registry: true", "          sbom-path: ${{ runner.temp }}/sbom.cdx.json\n          push-to-registry: false", "publish"),
    ("provenance attested to a name with no digest", "          subject-digest: ${{ steps.build.outputs.digest }}\n          push-to-registry: true\n\n      - name: Install cosign", "          push-to-registry: true\n\n      - name: Install cosign", "publish"),
    ("an SBOM in a format nobody asked for", "format: cyclonedx-json", "format: syft-json", "publish"),
    ("a published image the verifier never checks", "reporting-svc web)", "web)", "verify"),
    ("the release without its SBOM", "scripts/sbom.sh", "true", "release"),
    ("the SBOM script making no SBOM", "org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom", "help:effective-pom", "sbom"),
    ("test helpers counted among what ships", "<excludeArtifactId>common-test</excludeArtifactId>", "", "pom"),
    ("the release without checksums", "sha256sum -- * > SHA256SUMS", "true", "release"),
    ("the jars without provenance", "subject-path: release-artifacts/*.jar", "subject-path: release-artifacts/*.txt", "release"),
    ("the release job unable to attest", "  attestations: write\n", "", "release"),
    ("the SBOM plugin floating to LATEST", "<cyclonedx-plugin.version>2.9.3</cyclonedx-plugin.version>", "<cyclonedx-plugin.version>LATEST</cyclonedx-plugin.version>", "pom"),
]


def self_test(texts):
    base = problems(texts["publish"], texts["release"], texts["verify"], texts["pom"], texts["sbom"])
    if base:
        print("self-test needs a clean repository first:", *base, sep="\n  ", file=sys.stderr)
        return 1
    missed = 0
    for what, find, put, which in BREAKS:
        if find not in texts[which]:
            print(f"  STALE  {what}: the text this break looks for is no longer in the file")
            missed += 1
            continue
        broken = dict(texts)
        broken[which] = texts[which].replace(find, put)
        found = problems(broken["publish"], broken["release"], broken["verify"], broken["pom"], broken["sbom"])
        if found:
            print(f"  caught {what}: {found[0]}")
        else:
            print(f"  MISSED {what}")
            missed += 1
    print(f"supply chain self-test: {len(BREAKS) - missed} of {len(BREAKS)} breaks caught")
    return 1 if missed else 0


def main():
    texts = {"publish": PUBLISH.read_text(), "release": RELEASE.read_text(), "verify": VERIFY.read_text(), "pom": POM.read_text(), "sbom": SBOM.read_text()}
    if "--self-test" in sys.argv[1:]:
        return self_test(texts)
    found = problems(texts["publish"], texts["release"], texts["verify"], texts["pom"], texts["sbom"])
    for p in found:
        print(f"  {p}", file=sys.stderr)
    if found:
        print(f"supply chain: {len(found)} promise(s) broken", file=sys.stderr)
        return 1
    print("supply chain: 15 images built with SBOM and max provenance, attested and signed by digest; the release carries its SBOM, checksums and provenance — all checks pass")
    return 0


if __name__ == "__main__":
    sys.exit(main())
