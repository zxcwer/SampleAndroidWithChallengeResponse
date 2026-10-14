#!/usr/bin/env bash
# 実機で試すためのワンショット: 証明書作成 → サーバーを docker compose で起動 → APK をビルド → (接続中なら) adb install
#
#   scripts/dev-up.sh <サーバーのLAN IP または ホスト名> [追加SAN ...]
#   例) scripts/dev-up.sh 192.168.10.5
#
# 前提: docker compose、(APK をビルドするなら) JDK 17+ と Android SDK (ANDROID_HOME)、(インストールするなら) adb。
# 実機はこのホストと同じネットワークにいて、ポート 8443 に届くこと(ファイアウォール注意)。
set -euo pipefail
# Git Bash (Windows) が "/CN=..." のような引数をパスとして変換しないようにする
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*"
cd "$(dirname "$0")/.."

HOST="${1:?usage: $0 <server LAN IP or hostname> [extra SAN ...]}"
shift || true
PORT="${SYNC_PORT:-8443}"

# 1. CA とサーバー証明書。アプリに同梱する CA は証明書に紐づくので、作り直したら APK も再ビルドする
if [[ ! -f certs/server.p12 ]]; then
  scripts/gen-private-ca.sh "$HOST" localhost "$@"
else
  echo "certs/ already exists - reusing (delete certs/ to regenerate, SAN must contain $HOST)"
fi

# 2. .env (管理者トークンはランダム生成)
if [[ ! -f .env ]]; then
  printf 'ADMIN_API_TOKEN=%s\n' "$(openssl rand -hex 24)" > .env
  chmod 600 .env
  echo "Created .env with a random ADMIN_API_TOKEN"
fi

# 3. サーバー起動
docker compose up -d --build
echo "Waiting for the server to become healthy..."
for _ in $(seq 1 30); do
  [[ "$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q sync-server)")" == healthy ]] && break
  sleep 2
done

# 4. APK (CA 証明書と接続先 URL を埋め込む)
if [[ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]]; then
  (cd android && ./gradlew :app:assembleDebug -PsyncServerUrl="https://$HOST:$PORT")
  APK=android/app/build/outputs/apk/debug/app-debug.apk
  echo "APK: $APK"
  ADB="$(command -v adb || true)"
  [[ -z "$ADB" ]] && ADB="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platform-tools/adb"
  if "$ADB" devices 2>/dev/null | sed 1d | tr -d '\r' | grep -qw device; then
    "$ADB" install -r "$APK"
  else
    echo "No adb device/emulator found - start the emulator or connect the phone (USB debugging), then run: adb install -r $APK"
  fi
else
  echo "ANDROID_HOME is not set - skipped building the APK"
fi

cat <<MSG

Server : https://$HOST:$PORT
Next   : scripts/new-registration-code.sh   # 登録コードを発行して、アプリの「登録コード」欄に入力
MSG
