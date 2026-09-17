# Release process

How Shelf-J cuts a version, and what happens automatically once you do. Read this before pushing a `v*` tag.

## Tag format

One git tag versions the **whole monorepo snapshot** — not per-service. Strict SemVer, always 3 parts:

```
vMAJOR.MINOR.PATCH        e.g. v0.1.0, v1.4.2
```

`vMAJOR.MINOR.PATCH` is the **only** pattern both [`docker-publish.yml`](../.github/workflows/docker-publish.yml) and [`release.yml`](../.github/workflows/release.yml) trigger on. They must stay in sync — a tag that only matches one of them cuts a GitHub Release with no matching Docker images, or vice versa. Don't push a loose tag like `v1` or `v1.0` expecting either workflow to fire.

- **MAJOR** — a breaking API/event-contract change, or a change that requires coordinated redeploy across services (rare; this is an internal platform, not a published library).
- **MINOR** — a new service, a new endpoint surface, a new event, a notable feature.
- **PATCH** — a bug fix, a dependency bump, a docs/manifest-only change.

Maven module versions are **not** bumped to match — every `pom.xml` stays `0.1.0-SNAPSHOT` regardless of what git tag is cut. The tag drives Docker image tags and the GitHub Release; it's intentionally decoupled from Maven's own versioning so cutting a release never means a 20-module version-bump commit.

## Cutting a release

1. Confirm `main`'s latest CI run and Docker publish run are both green (`gh run list --branch main --limit 5`).
2. Tag and push:
   ```
   git tag v0.2.0 -m "Delivery area management + fulfilment resolution"
   git push origin v0.2.0
   ```
3. Two workflows fire off that tag push:
   - **`release.yml`** — rebuilds, deploys module JARs to GitHub Packages (Maven), collects every `target/*.jar` and attaches them to a new GitHub Release named after the tag.
   - **`docker-publish.yml`** — builds and pushes every service + the web image to GHCR, each tagged with `<version>`, `<major>.<minor>`, `<major>`, and `sha-<short>` (in addition to what every push already gets: `sha-<short>`, plus `latest` on `main`). See [Image tags](#image-tags-per-push) below.
   Both also say what the release is made of and where it was built — see [Verifying a release](#verifying-a-release-what-it-is-made-of-and-where-it-was-built).
4. Watch it: `gh run watch --repo red2n/shelf-j <run-id>`, or check the Releases page / GHCR packages once both finish.

## Image tags per push

Every push to `main` and every `v*.*.*` tag push publish images — the tag *shape* differs by trigger:

| Trigger | Tags applied to each image |
|---|---|
| Push to `main` | `sha-<short>`, `latest` |
| Push to a feature branch (`workflow_dispatch`) | `sha-<short>`, `<branch-name>` |
| Push tag `vX.Y.Z` | `sha-<short>`, `X.Y.Z`, `X.Y`, `X` |

## Verifying a release: what it is made of, and where it was built

Every image `docker-publish.yml` pushes is built with BuildKit's own SBOM and max-mode provenance, and then gets three things bound to its **digest** (a tag can be moved; a digest cannot):

| What | Made by | Answers |
|---|---|---|
| CycloneDX SBOM attestation | syft (`anchore/sbom-action`) + `actions/attest-sbom` | what is in this image — every OS package and every jar (the web image also carries the app's Dart packages from `pubspec.lock`) |
| SLSA build-provenance attestation | `actions/attest-build-provenance` | which repository, workflow, commit and runner built it |
| Sigstore signature, keyless | `cosign sign` | that this repository's publish workflow vouches for it — no private key exists to steal; the ten-minute certificate names the workflow and ref, and the signature is in the public transparency log |

`release.yml` attaches `shelf-j-<tag>.cdx.json` / `.cdx.xml` (one CycloneDX 1.6 SBOM for the whole reactor: every module and every dependency that ships — `scripts/sbom.sh` makes the same document locally) and `SHA256SUMS`, and attests build provenance and the SBOM to every jar.

To check a release before running it:

```
scripts/verify-release.sh 0.2.0            # all 15 images of that version
scripts/verify-release.sh latest gateway   # one image
gh attestation verify gateway.jar --repo red2n/shelf-j
```

The script resolves each tag to a digest once, then requires the signature to come from `red2n/shelf-j`'s `docker-publish.yml` (GitHub's OIDC issuer) and both attestations to verify against that digest; it exits non-zero if anything did not. A cluster can enforce the same rule at admission (Kyverno `verifyImages` or Sigstore's policy-controller) with that identity and issuer.

`scripts/supply-chain-check.py` runs in CI and fails the build if either workflow stops doing any of this — an SBOM switched off, a tag signed instead of a digest, a stored key instead of keyless, an image the verifier does not know; `--self-test` breaks each promise in memory and fails unless the check notices. `scripts/supply-chain-selftest.sh` drives the mechanism end to end against a local registry (build with attestations, sign, attest, verify; then a wrong key, an unsigned image and a moved tag, each refused).

## Registry retention (why GHCR doesn't fill up)

`docker-publish.yml`'s `cleanup` job runs after every publish and keeps only the **2 most recent tagged versions** per package (`keep-n-tagged: 2`), deleting older versions and any untagged/dangling manifests. That means:

- You can always roll back **one** build (the previous `latest`/tag).
- Going back further than that requires re-tagging from source (`git tag` + re-push, or `workflow_dispatch` off an older commit) — older GHCR versions are not kept indefinitely, by design.
- Signatures and attestations are stored in GHCR as children of the image they belong to. The cleanup action treats them that way: they stay while their image stays and go when it goes (`delete-orphaned-images` sweeps any whose image is already gone), so keeping two versions keeps two *verifiable* versions.
- Cleanup runs with `if: always()`, so one flaky service image in a publish run doesn't leave the other 14 packages un-pruned.

## Why `docker-publish.yml` builds per-service, not per-run

Each service gets its own matrix job (its own fresh runner, its own disk) instead of all 15 images being built sequentially in one job off a single full-reactor build. The old single-job design accumulated Maven `target/` output and Docker layers across all 15 builds on one runner with nothing freed mid-job, and eventually exhausted the runner's disk — failing the publish for every service, not just the one that tipped it over. See the comment block at the top of `docker-publish.yml` for the full rationale.
