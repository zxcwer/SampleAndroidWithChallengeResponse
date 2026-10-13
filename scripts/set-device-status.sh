#!/usr/bin/env bash
# 端末の状態を変更する。紛失・停止にすると次の同期から device_revoked になる。
#   scripts/set-device-status.sh <deviceId> <active|suspended|lost> [https://host:8443]
#   scripts/set-device-status.sh list [https://host:8443]      ... 端末一覧
set -euo pipefail
cd "$(dirname "$0")/.."
TOKEN="$(grep '^ADMIN_API_TOKEN=' .env | cut -d= -f2-)"
if [[ "${1:?}" == list ]]; then
  URL="${2:-https://localhost:${SYNC_PORT:-8443}}"
  curl -sS --cacert certs/ca.pem -H "Authorization: Bearer $TOKEN" "$URL/admin/v1/devices"
else
  URL="${3:-https://localhost:${SYNC_PORT:-8443}}"
  curl -sS -w '\nHTTP %{http_code}\n' --cacert certs/ca.pem -X PUT -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d "{\"status\":\"${2:?}\"}" "$URL/admin/v1/devices/$1/status"
fi
echo
