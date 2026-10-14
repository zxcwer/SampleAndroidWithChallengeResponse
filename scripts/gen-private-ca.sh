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
# 有効期間(日)。環境変数で変更できる。CA はアプリに同梱されるため交換が大変 → 長め、サーバー証明書は短めが基本。
#   例) CA_DAYS=36500 SERVER_CERT_DAYS=36500 scripts/gen-private-ca.sh 192.168.10.5
CA_DAYS="${CA_DAYS:-3650}"
SERVER_CERT_DAYS="${SERVER_CERT_DAYS:-825}"
if (( SERVER_CERT_DAYS > CA_DAYS )); then
  echo "SERVER_CERT_DAYS ($SERVER_CERT_DAYS) must not exceed CA_DAYS ($CA_DAYS): the server cert would outlive its CA" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/certs"
mkdir -p "$OUT"
umask 077

# SAN の組み立て (IP アドレスは IP:、それ以外は DNS:)。最初のホストも同様に判定する。
# 管理スクリプト(curl https://localhost:8443)用に localhost / 127.0.0.1 は常に含める。重複は除く。
SAN=""
for h in "$HOST" "$@" localhost 127.0.0.1; do
  if [[ "$h" =~ ^[0-9.]+$ || "$h" == *:* ]]; then entry="IP:$h"; else entry="DNS:$h"; fi
  case ",$SAN," in *",$entry,"*) continue ;; esac
  SAN="${SAN:+$SAN,}$entry"
done

# openssl には絶対パスを渡さず、certs/ に移動して相対パスだけを使う。
# (Git Bash では /c/Users/... を Windows ネイティブの openssl が解釈できず "No such file" になるため)
cd "$OUT"

openssl ecparam -name prime256v1 -genkey -noout -out ca.key
openssl req -x509 -new -key ca.key -sha256 -days "$CA_DAYS" \
  -subj "/CN=Sync Private CA" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -out ca.pem

openssl ecparam -name prime256v1 -genkey -noout -out server.key
openssl req -new -key server.key -subj "/CN=$HOST" -out server.csr
printf "basicConstraints=CA:FALSE\nkeyUsage=digitalSignature\nextendedKeyUsage=serverAuth\nsubjectAltName=%s\n" "$SAN" > server.ext
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial \
  -sha256 -days "$SERVER_CERT_DAYS" -extfile server.ext -out server.pem

PASS="$(openssl rand -hex 16)"
printf '%s' "$PASS" > server.p12.password
openssl pkcs12 -export -inkey server.key -in server.pem -certfile ca.pem \
  -name server -passout "pass:$PASS" -out server.p12
# docker compose のコンテナ(非 root)から読めるようにする。CA 秘密鍵(ca.key)は 600 のまま。
chmod 644 server.p12 server.p12.password
rm -f server.csr server.ext ca.srl

mkdir -p "$ROOT/android/app/src/main/res/raw"
cp ca.pem "$ROOT/android/app/src/main/res/raw/private_ca.pem"

echo "Created CA (${CA_DAYS} days) and server certificate (${SERVER_CERT_DAYS} days) for: $SAN"
echo "Server: docker compose up -d --build   (または README の bootRun 手順)"
echo "App   : rebuild the Android app so that res/raw/private_ca.pem is bundled."
