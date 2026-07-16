#!/usr/bin/env bash
#
# Generates all Shelf-J secrets as native Kubernetes Secret objects — the k3s
# equivalent of scripts/redeploy.sh's .env bootstrap (openssl rand, never a
# hardcoded/dev password in production).
#
# Run once per cluster, before applying anything else in k8s/. Safe to re-run:
# any secret that ALREADY exists in the cluster is left untouched — rotating
# shelfj-jwt invalidates every live session, and the per-service DB passwords
# must stay in sync with what Postgres actually GRANTed at first boot.
#
# Usage: ./k8s/generate-secrets.sh
set -euo pipefail
NAMESPACE=shelf-j
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 || kubectl apply -f "$ROOT/k8s/00-namespace.yaml"

secret_exists() { kubectl -n "$NAMESPACE" get secret "$1" >/dev/null 2>&1; }
randpass()      { openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | cut -c1-24; }
randsecret()    { openssl rand -base64 48; }

# ── Core signing/auth secrets ─────────────────────────────────────────────────
if secret_exists shelfj-jwt; then
  echo "shelfj-jwt already exists — leaving it untouched (rotating invalidates all sessions)."
else
  kubectl -n "$NAMESPACE" create secret generic shelfj-jwt \
    --from-literal=shelfj.jwt.secret="$(randsecret)"
  echo "Generated shelfj-jwt."
fi

if secret_exists shelfj-config-token; then
  echo "shelfj-config-token already exists — leaving it untouched."
else
  kubectl -n "$NAMESPACE" create secret generic shelfj-config-token \
    --from-literal=shelfj.config.token="$(randsecret)"
  echo "Generated shelfj-config-token."
fi

# ── Datastore secrets ──────────────────────────────────────────────────────────
if secret_exists shelfj-postgres; then
  echo "shelfj-postgres already exists — leaving it untouched."
else
  POSTGRES_PASSWORD="$(randpass)"
  IAM_DB_PASSWORD="$(randpass)"
  TENANT_DB_PASSWORD="$(randpass)"
  PRODUCT_DB_PASSWORD="$(randpass)"
  INVENTORY_DB_PASSWORD="$(randpass)"
  PURCHASE_DB_PASSWORD="$(randpass)"
  PRICING_DB_PASSWORD="$(randpass)"
  CART_DB_PASSWORD="$(randpass)"
  ORDER_DB_PASSWORD="$(randpass)"
  PAYMENT_DB_PASSWORD="$(randpass)"
  CUSTOMER_DB_PASSWORD="$(randpass)"
  NOTIFICATION_DB_PASSWORD="$(randpass)"
  REPORTING_DB_PASSWORD="$(randpass)"

  kubectl -n "$NAMESPACE" create secret generic shelfj-postgres \
    --from-literal=POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
    --from-literal=IAM_DB_PASSWORD="$IAM_DB_PASSWORD" \
    --from-literal=TENANT_DB_PASSWORD="$TENANT_DB_PASSWORD" \
    --from-literal=PRODUCT_DB_PASSWORD="$PRODUCT_DB_PASSWORD" \
    --from-literal=INVENTORY_DB_PASSWORD="$INVENTORY_DB_PASSWORD" \
    --from-literal=PURCHASE_DB_PASSWORD="$PURCHASE_DB_PASSWORD" \
    --from-literal=PRICING_DB_PASSWORD="$PRICING_DB_PASSWORD" \
    --from-literal=CART_DB_PASSWORD="$CART_DB_PASSWORD" \
    --from-literal=ORDER_DB_PASSWORD="$ORDER_DB_PASSWORD" \
    --from-literal=PAYMENT_DB_PASSWORD="$PAYMENT_DB_PASSWORD" \
    --from-literal=CUSTOMER_DB_PASSWORD="$CUSTOMER_DB_PASSWORD" \
    --from-literal=NOTIFICATION_DB_PASSWORD="$NOTIFICATION_DB_PASSWORD" \
    --from-literal=REPORTING_DB_PASSWORD="$REPORTING_DB_PASSWORD"

  # infra/postgres-init-roles.sql and infra/pgbouncer-userlist.txt ship in the repo with
  # public dev passwords baked in (*_dev_change_me — see those files' headers). Render real
  # copies with the passwords generated above and store them as their own Secret, mounted
  # into postgres's docker-entrypoint-initdb.d and pgbouncer's auth file respectively.
  RENDERED_SQL="$(sed \
    -e "s/iam_dev_change_me/${IAM_DB_PASSWORD}/" \
    -e "s/tenant_dev_change_me/${TENANT_DB_PASSWORD}/" \
    -e "s/product_dev_change_me/${PRODUCT_DB_PASSWORD}/" \
    -e "s/inventory_dev_change_me/${INVENTORY_DB_PASSWORD}/" \
    -e "s/purchase_dev_change_me/${PURCHASE_DB_PASSWORD}/" \
    -e "s/pricing_dev_change_me/${PRICING_DB_PASSWORD}/" \
    -e "s/cart_dev_change_me/${CART_DB_PASSWORD}/" \
    -e "s/order_dev_change_me/${ORDER_DB_PASSWORD}/" \
    -e "s/payment_dev_change_me/${PAYMENT_DB_PASSWORD}/" \
    -e "s/customer_dev_change_me/${CUSTOMER_DB_PASSWORD}/" \
    -e "s/notification_dev_change_me/${NOTIFICATION_DB_PASSWORD}/" \
    -e "s/reporting_dev_change_me/${REPORTING_DB_PASSWORD}/" \
    "$ROOT/infra/postgres-init-roles.sql")"

  RENDERED_USERLIST="$(cat <<EOF
"shelfj" "${POSTGRES_PASSWORD}"
"iam_svc" "${IAM_DB_PASSWORD}"
"tenant_svc" "${TENANT_DB_PASSWORD}"
"product_svc" "${PRODUCT_DB_PASSWORD}"
"inventory_svc" "${INVENTORY_DB_PASSWORD}"
"purchase_svc" "${PURCHASE_DB_PASSWORD}"
"pricing_svc" "${PRICING_DB_PASSWORD}"
"cart_svc" "${CART_DB_PASSWORD}"
"order_svc" "${ORDER_DB_PASSWORD}"
"payment_svc" "${PAYMENT_DB_PASSWORD}"
"customer_svc" "${CUSTOMER_DB_PASSWORD}"
"notification_svc" "${NOTIFICATION_DB_PASSWORD}"
"reporting_svc" "${REPORTING_DB_PASSWORD}"
EOF
)"

  kubectl -n "$NAMESPACE" create secret generic shelfj-postgres-init \
    --from-literal=01-roles.sql="$RENDERED_SQL" \
    --from-literal=userlist.txt="$RENDERED_USERLIST"

  echo "Generated shelfj-postgres + shelfj-postgres-init (per-service DB passwords)."
fi

if secret_exists shelfj-redis; then
  echo "shelfj-redis already exists — leaving it untouched."
else
  kubectl -n "$NAMESPACE" create secret generic shelfj-redis \
    --from-literal=REDIS_PASSWORD="$(randpass)"
  echo "Generated shelfj-redis."
fi

if secret_exists shelfj-grafana; then
  echo "shelfj-grafana already exists — leaving it untouched."
else
  kubectl -n "$NAMESPACE" create secret generic shelfj-grafana \
    --from-literal=GF_SECURITY_ADMIN_USER=admin \
    --from-literal=GF_SECURITY_ADMIN_PASSWORD="$(randpass)"
  echo "Generated shelfj-grafana."
fi

# ── Platform admin bootstrap (one-shot; the bootstrap Job 409s if an admin already
# exists and treats that as success, so regenerating this after first boot is harmless —
# it just won't match whatever password is already live) ─────────────────────────────────
if secret_exists shelfj-platform-admin; then
  echo "shelfj-platform-admin already exists — leaving it untouched."
else
  kubectl -n "$NAMESPACE" create secret generic shelfj-platform-admin \
    --from-literal=PLATFORM_ADMIN_EMAIL="admin@storeql.com" \
    --from-literal=PLATFORM_ADMIN_PASSWORD="$(randpass)"
  echo "Generated shelfj-platform-admin."
fi

echo
echo "Done. Retrieve a generated password later with, e.g.:"
echo "  kubectl -n $NAMESPACE get secret shelfj-platform-admin -o jsonpath='{.data.PLATFORM_ADMIN_PASSWORD}' | base64 -d; echo"
