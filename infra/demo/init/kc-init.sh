#!/bin/bash
# SPDX-FileCopyrightText: 2026 TRICORE contributors
# SPDX-License-Identifier: Apache-2.0
# Demo (#119, D2–D4): đặt secret client và mật khẩu 3 tài khoản demo từ .env. Chạy lại an toàn.
set -euo pipefail
KC=/opt/keycloak/bin/kcadm.sh
CFG=/tmp/kcadm.config

echo "== cau hinh Keycloak + realm tricore"
trap 'rm -f "$CFG"' EXIT
$KC config credentials --config "$CFG" --server "${KC_SERVER:-http://localhost:8180}" \
    --realm master --user admin --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

set_secret() {
  local id
  id=$($KC get clients -r tricore --config $CFG -q clientId="$1" --fields id --format csv --noquotes)
  $KC update "clients/$id" -r tricore --config $CFG -s "secret=$2"
  echo "secret $1: da dat"
}
# Idempotent for a realm already imported into the existing disposable demo database.
# Updates presentation only; do not reset realm/users/sessions.
$KC update realms/tricore --config $CFG -s "loginTheme=opentrix" \
  -s internationalizationEnabled=true -s 'supportedLocales=["vi"]' -s defaultLocale=vi >/dev/null
echo "login theme: opentrix (locale vi)"

set_secret dxos-portal-bff-local "$PORTAL_OIDC_CLIENT_SECRET"
set_secret gateway-monitor "$GATEWAY_MONITOR_CLIENT_SECRET"
set_secret hr-employee-bot "$EXTERNAL_EMPLOYEE_SECRET"
set_secret hr-approver-bot "$EXTERNAL_APPROVER_SECRET"

$KC set-password -r tricore --config $CFG --username employee-demo --new-password "$DEMO_EMPLOYEE_PASSWORD"
$KC set-password -r tricore --config $CFG --username coordinator-demo --new-password "$DEMO_COORDINATOR_PASSWORD"
$KC set-password -r tricore --config $CFG --username worker-demo --new-password "$DEMO_WORKER_PASSWORD"
echo "mat khau 3 tai khoan demo: da dat"
echo "kc-init OK"
