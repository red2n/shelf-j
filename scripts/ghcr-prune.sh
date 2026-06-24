#!/usr/bin/env bash
# ghcr-prune.sh — manual backup for the CI `cleanup` job in
# .github/workflows/docker-publish.yml.
#
# Keeps the N newest TAGGED versions of each Shelf-J GHCR package and deletes
# everything older, plus ALL untagged versions. This mirrors what
# dataaxiom/ghcr-cleanup-action does in CI (keep-n-tagged + delete-untagged) —
# run it from your machine if that job ever fails (e.g. a token-permission
# hiccup), then re-run the GitHub Action.
#
# Why this is needed: pushing an image never deletes the old one. The latest/
# main tags just move; the previous version lingers (still carrying its unique
# sha-<commit> tag) and piles up one-per-merge forever. This prunes the pile.
#
# Usage:
#   ./scripts/ghcr-prune.sh                 # DRY RUN (default): show what would go
#   ./scripts/ghcr-prune.sh --apply         # actually delete
#   KEEP=3 ./scripts/ghcr-prune.sh --apply  # keep 3 instead of 2
#   ./scripts/ghcr-prune.sh --apply gateway web   # only these (suffix after shelf-j-)
#   OWNER=red2n ./scripts/ghcr-prune.sh     # override owner (default: red2n)
#
# Requires: gh CLI + jq, with a token that has read:packages + delete:packages.
# If the scope check below fails, run:
#   gh auth refresh -h github.com -s read:packages,delete:packages
set -euo pipefail

OWNER="${OWNER:-red2n}"
KEEP="${KEEP:-2}"
APPLY=false

# Must stay in sync with the matrix in docker-publish.yml.
PACKAGES=(
  shelf-j-gateway shelf-j-config
  shelf-j-iam-svc shelf-j-tenant-svc shelf-j-product-svc shelf-j-inventory-svc shelf-j-purchase-svc
  shelf-j-pricing-svc shelf-j-cart-svc shelf-j-order-svc shelf-j-payment-svc
  shelf-j-customer-svc shelf-j-notification-svc shelf-j-reporting-svc
  shelf-j-web
)

# ── args ──────────────────────────────────────────────────────────────────
filter=()
for a in "$@"; do
  case "$a" in
    --apply)   APPLY=true ;;
    --dry-run) APPLY=false ;;
    -h|--help) grep '^#' "$0" | sed 's/^#\!.*//; s/^# \?//'; exit 0 ;;
    -*)        echo "unknown flag: $a" >&2; exit 2 ;;
    *)         filter+=("shelf-j-${a#shelf-j-}") ;;  # accept 'gateway' or 'shelf-j-gateway'
  esac
done
[[ ${#filter[@]} -gt 0 ]] && PACKAGES=("${filter[@]}")
[[ "$KEEP" =~ ^[0-9]+$ && "$KEEP" -ge 1 ]] || { echo "KEEP must be a positive integer" >&2; exit 2; }

# ── preflight ─────────────────────────────────────────────────────────────
command -v gh >/dev/null || { echo "gh CLI not found — https://cli.github.com" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq not found" >&2; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "gh not authenticated — run: gh auth login" >&2; exit 1; }

scopes=$(gh api -i /user 2>/dev/null | tr -d '\r' | awk -F': ' 'tolower($1)=="x-oauth-scopes"{print $2}')
if ! grep -qi 'delete:packages' <<<"$scopes"; then
  echo "Your gh token is missing the delete:packages scope (have: ${scopes:-none})." >&2
  echo "Grant it once with:" >&2
  echo "  gh auth refresh -h github.com -s read:packages,delete:packages" >&2
  exit 1
fi

# user vs org changes the API path
owner_type=$(gh api "/users/$OWNER" --jq '.type' 2>/dev/null || echo User)
if [[ "$owner_type" == "Organization" ]]; then base="/orgs/$OWNER"; else base="/users/$OWNER"; fi

mode="DRY RUN (no deletes — pass --apply to delete)"; $APPLY && mode="APPLYING DELETES"
echo "owner=$OWNER ($owner_type)  keep=$KEEP newest tagged  packages=${#PACKAGES[@]}"
echo ">>> $mode"
echo

total_deleted=0
# ── per package ───────────────────────────────────────────────────────────
for pkg in "${PACKAGES[@]}"; do
  echo "── $pkg ──────────────────────────────────────────"
  if ! all=$(gh api --paginate "$base/packages/container/$pkg/versions?per_page=100" 2>/dev/null); then
    echo "  (not found / no access — skipping; fine if it hasn't been published yet)"
    echo
    continue
  fi

  # The versions we keep: the KEEP newest that carry at least one tag.
  jq -r --argjson keep "$KEEP" '
    [ .[] | select((.metadata.container.tags // []) | length > 0) ]
    | sort_by(.created_at) | reverse | .[:$keep][]
    | "  keep    \(.id)  \(.created_at)  [\(.metadata.container.tags | join(", "))]"' <<<"$all"

  # Delete = every untagged version + tagged versions older than the KEEP newest.
  to_delete=$(jq -r --argjson keep "$KEEP" '
    ( [ .[] | select((.metadata.container.tags // []) | length == 0) ] ) as $untagged
    | ( [ .[] | select((.metadata.container.tags // []) | length > 0) ]
        | sort_by(.created_at) | reverse | .[$keep:] ) as $oldtagged
    | ($untagged + $oldtagged) | .[].id' <<<"$all")

  if [[ -z "${to_delete//[$'\n\t ']/}" ]]; then
    echo "  nothing to prune."
    echo
    continue
  fi

  while read -r id; do
    [[ -z "$id" ]] && continue
    label=$(jq -r --argjson id "$id" '.[] | select(.id==$id)
      | (.metadata.container.tags // []) | if length==0 then "<untagged>" else join(", ") end' <<<"$all")
    if $APPLY; then
      if gh api -X DELETE "$base/packages/container/$pkg/versions/$id" >/dev/null 2>&1; then
        echo "  deleted $id  [$label]"
        total_deleted=$((total_deleted + 1))
      else
        echo "  FAILED  $id  [$label]  — check token has delete:packages and owns the package" >&2
      fi
    else
      echo "  would delete $id  [$label]"
    fi
  done <<<"$to_delete"
  echo
done

if $APPLY; then
  echo ">>> done — deleted $total_deleted version(s). You can now re-run the GitHub Action."
else
  echo ">>> dry run complete — re-run with --apply to delete the versions listed above."
fi
