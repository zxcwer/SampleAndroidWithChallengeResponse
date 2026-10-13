#!/usr/bin/env bash
# 管理 API で登録コードを発行する (docker compose で起動したサーバー向け)。
#   scripts/new-registration-code.sh [https://host:8443]
set -euo pipefail
cd "$(dirname "$0")/.."
URL="${1:-https://localhost:${SYNC_PORT:-8443}}"
TOKEN="$(grep '^ADMIN_API_TOKEN=' .env | cut -d= -f2-)"
curl -sS --cacert certs/ca.pem -X POST -H "Authorization: Bearer $TOKEN" -H "X-Admin-User: ${USER:-admin}" \
  "$URL/admin/v1/registration-codes"
echo
