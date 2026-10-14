#!/usr/bin/env bash
# 社内(プライベート)CA とサーバー証明書を作成する。
#
#   scripts/gen-private-ca.sh [サーバーのホスト名 (既定: sync-server.local)] [追加SAN ...]
#   例) scripts/gen-private-ca.sh sync-server.local localhost 10.0.2.2 192.168.10.5
#   実機は hosts を編集できないので、サーバーの LAN IP を SAN に入れて https://<IP>:8443 で接続する。
#   例) scripts/gen-private-ca.sh 192.168.10.5
#
# 出力 (certs/ は .gitignore 済み。CA 秘密鍵は必ず安全な場所に保管すること):
#   certs/ca.key, certs/ca.pem        ... CA (秘密鍵は外に出さない)
#   certs/server.p12                  ... サーバー用 keystore (パスワードは certs/server.p12.password)
#   android/app/src/main/res/raw/private_ca.pem ... アプリに同梱する CA 証明書 (公開情報)
set -euo pipefail
# Git Bash (Windows) が "/CN=..." のような引数をパスとして変換しないようにする
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"

HOST="${1:-sync-server.local}"
shift || true
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/certs"
mkdir -p "$OUT"
umask 077

# SAN の組み立て (IP アドレスは IP:、それ以外は DNS:)。最初のホストも同様に判定する
SAN=""
for h in "$HOST" "$@"; do
  if [[ "$h" =~ ^[0-9.]+$ || "$h" == *:* ]]; then entry="IP:$h"; else entry="DNS:$h"; fi
  SAN="${SAN:+$SAN,}$entry"
done

openssl ecparam -name prime256v1 -genkey -noout -out "$OUT/ca.key"
openssl req -x509 -new -key "$OUT/ca.key" -sha256 -days 3650 \
  -subj "/CN=Sync Private CA" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -out "$OUT/ca.pem"

openssl ecparam -name prime256v1 -genkey -noout -out "$OUT/server.key"
openssl req -new -key "$OUT/server.key" -subj "/CN=$HOST" -out "$OUT/server.csr"
printf "basicConstraints=CA:FALSE\nkeyUsage=digitalSignature\nextendedKeyUsage=serverAuth\nsubjectAltName=%s\n" "$SAN" > "$OUT/server.ext"
openssl x509 -req -in "$OUT/server.csr" -CA "$OUT/ca.pem" -CAkey "$OUT/ca.key" -CAcreateserial \
  -sha256 -days 825 -extfile "$OUT/server.ext" -out "$OUT/server.pem"

PASS="$(openssl rand -hex 16)"
echo -n "$PASS" > "$OUT/server.p12.password"
openssl pkcs12 -export -inkey "$OUT/server.key" -in "$OUT/server.pem" -certfile "$OUT/ca.pem" \
  -name server -passout "pass:$PASS" -out "$OUT/server.p12"
# docker compose のコンテナ(非 root)から読めるようにする。CA 秘密鍵(ca.key)は 600 のまま。
chmod 644 "$OUT/server.p12" "$OUT/server.p12.password"
rm -f "$OUT/server.csr" "$OUT/server.ext" "$OUT/ca.srl"

mkdir -p "$ROOT/android/app/src/main/res/raw"
cp "$OUT/ca.pem" "$ROOT/android/app/src/main/res/raw/private_ca.pem"

echo "Created CA and server certificate for: $SAN"
echo "Server: docker compose up -d --build   (または README の bootRun 手順)"
echo "App   : rebuild the Android app so that res/raw/private_ca.pem is bundled."
