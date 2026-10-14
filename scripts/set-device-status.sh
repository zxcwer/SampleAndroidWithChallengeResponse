#!/usr/bin/env bash
# 端末の状態を変更する。紛失・停止にすると次の同期から device_revoked になる。
#   scripts/set-device-status.sh <deviceId> <active|suspended|lost> [https://host:8443]
#   scripts/set-device-status.sh list [https://host:8443]      ... 端末一覧
# 注: Windows の curl (schannel) は失効確認 (CRL/OCSP) に失敗するため --ssl-no-revoke を付けている。
# 証明書チェーンの検証 (--cacert の社内 CA) は有効のまま。
set -euo pipefail
# Git Bash (Windows) が "/CN=..." のような引数をパスとして変換しないようにする
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"
cd "$(dirname "$0")/.."
TOKEN="$(grep '^ADMIN_API_TOKEN=' .env | cut -d= -f2-)"
if [[ "${1:?}" == list ]]; then
  URL="${2:-https://localhost:${SYNC_PORT:-8443}}"
  curl -sS --ssl-no-revoke --cacert certs/ca.pem -H "Authorization: Bearer $TOKEN" "$URL/admin/v1/devices"
else
  URL="${3:-https://localhost:${SYNC_PORT:-8443}}"
  curl -sS --ssl-no-revoke -w '\nHTTP %{http_code}\n' --cacert certs/ca.pem -X PUT -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d "{\"status\":\"${2:?}\"}" "$URL/admin/v1/devices/$1/status"
fi
echo
