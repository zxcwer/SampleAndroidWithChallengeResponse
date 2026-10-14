#!/usr/bin/env bash
# 管理 API で登録コードを発行する (docker compose で起動したサーバー向け)。
#   scripts/new-registration-code.sh [https://host:8443]
# 注: Windows の curl (schannel) は失効確認 (CRL/OCSP) に失敗するため --ssl-no-revoke を付けている。
# 証明書チェーンの検証 (--cacert の社内 CA) は有効のまま。
set -euo pipefail
# Git Bash (Windows) が "/CN=..." のような引数をパスとして変換しないようにする
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"
cd "$(dirname "$0")/.."
URL="${1:-https://localhost:${SYNC_PORT:-8443}}"
TOKEN="$(grep '^ADMIN_API_TOKEN=' .env | cut -d= -f2-)"
curl -sS --ssl-no-revoke --cacert certs/ca.pem -X POST -H "Authorization: Bearer $TOKEN" -H "X-Admin-User: ${USER:-admin}" \
  "$URL/admin/v1/registration-codes"
echo
