# Android 端末認証 (チャレンジ・レスポンス) サンプル

Android 端末を **端末ごとの鍵ペア** で認証し、同期 API を保護するための実装です。
パスワードや API キーのような「共有された秘密」を端末に置かず、Android Keystore の秘密鍵で署名することで本人性を証明します。

| ディレクトリ | 内容 |
|---|---|
| `server/` | Spring Boot (Kotlin) の同期サーバー。認証モジュール(`auth`)とリソースモジュール(`resource`)を論理分離 |
| `android/authclient/` | Android 非依存の純 Kotlin ライブラリ。登録・チャレンジ・レスポンス・トークン保持・オフラインロック判定 |
| `android/app/` | Android アプリ。Keystore 鍵、ネットワーク設定、暗号化 DB の鍵管理、サンプル UI |
| `scripts/gen-private-ca.sh` | 社内(プライベート)CA とサーバー証明書の作成 |

## 認証の全体像

```
管理者(管理画面)            Android端末                      サーバー
   |-- POST /admin/v1/registration-codes ------------------->|  ワンタイム登録コード(30分)
   |<-- code ------------------------------------------------|
   |-- コードを端末へ入力 -->|-- 鍵ペア生成(Keystore, StrongBox優先)
                            |-- POST /auth/v1/devices/register {code, 公開鍵, 端末情報} -->|
                            |<-- {deviceId} ------------------------------------------------|

(同期のたびに)
                            |-- POST /auth/v1/device/challenge {deviceId} ----------------->|
                            |<-- {challengeId, nonce, expiresIn: 60} -----------------------|
                            |-- 署名(秘密鍵)                                                 |
                            |-- POST /auth/v1/token {deviceId, challengeId, signature} ---->|
                            |<-- {accessToken(JWT/ES256), tokenType, expiresIn, serverTime} -|
                            |-- GET/POST /sync/v1/... (Authorization: Bearer) -------------->|
```

### 署名対象文字列

別用途・別端末の署名流用を防ぐため、nonce だけでなく用途と ID を含めます。区切りは `\n`、末尾に改行なし。

```
device-auth-v1
{deviceId}
{challengeId}
{nonce}
```

署名は `SHA256withECDSA` (P-256)。Android / Java が出す **DER 形式** をそのまま Base64 で送ります
(サーバーが .NET などの場合は `ECDsa.VerifyData(..., DSASignatureFormat.Rfc3279DerSequence)` を指定してください)。
公開鍵は `publicKey.encoded` (X.509 SubjectPublicKeyInfo) の Base64。

### サーバーのトークン発行手順 (`TokenService`)

順番どおりに行い、失敗したら即拒否します。

1. `challengeId` が存在し、未使用で、期限内 (`invalid_challenge` / `challenge_expired`)
2. チャレンジの発行先 `deviceId` とリクエストが一致 (`invalid_challenge`、この時点では消費しない)
3. チャレンジを **原子的に使用済み化**(以降の結果にかかわらずリプレイ不可。並行リクエストでも成功は 1 回だけ)
4. 登録済み公開鍵で署名検証 (`invalid_signature`)
5. 端末状態が `active` (`device_revoked`)

アクセストークン (JWT, ES256, ヘッダに `kid`) のクレーム: `iss` `aud` `sub=device:{id}` `device_id` `scope` `iat` `exp`(30分) `jti`。
**リフレッシュトークンは発行しません**。同期のたびにチャレンジ・レスポンスからやり直します。

### リソース側の検証 (`AccessTokenInterceptor`)

`/sync/**` の全リクエストで、署名 / `iss` / `aud` / `exp`(サーバー時刻) / `scope`(`@RequiredScope`) を検証し、
さらに `DeviceAuthApi.isDeviceActive(deviceId)` で端末状態を毎回確認します。
同期中に管理者が端末を停止/紛失扱いにしても、次のリクエストから `403 device_revoked` になります。
リソース側が参照するのは認証モジュールの公開インターフェース `DeviceAuthApi` だけで、認証側のテーブルや内部クラスには触れません。

## API

| メソッド/パス | 用途 | 認証 |
|---|---|---|
| `POST /auth/v1/devices/register` | 端末登録 | 登録コード |
| `POST /auth/v1/device/challenge` | チャレンジ発行 | なし(レート制限あり) |
| `POST /auth/v1/token` | トークン発行 | 署名 |
| `GET /sync/v1/policy` `GET /sync/v1/pull` | 同期(`sync:read`) | Bearer |
| `POST /sync/v1/push` | 同期(`sync:write`) | Bearer |
| `POST /admin/v1/registration-codes` | 登録コード発行 | 管理者トークン |
| `GET /admin/v1/devices` | 端末一覧 | 管理者トークン |
| `PUT /admin/v1/devices/{id}/status` `{"status":"active\|suspended\|lost"}` | 停止/紛失/復帰 | 管理者トークン |

`pull` / `push` は認証・認可の動作確認用のプレースホルダで、業務データの保存は含みません。
`/sync/v1/policy` が同期時に `maxOfflineDays` と `serverTime` を配信します。

### エラーコード

| HTTP | コード | 意味 / アプリ側の扱い |
|---|---|---|
| 400 | `invalid_request` | パラメータ不足・形式不正 |
| 401 | `challenge_expired` | チャレンジ期限切れ → **自動で再試行**(最大 3 回) |
| 401 | `invalid_challenge` | 存在しない / 使用済み / 他端末宛 |
| 401 | `invalid_signature` | 署名検証失敗 |
| 401 | `invalid_token` | アクセストークン不正・期限切れ → **1 回だけ再認証して再送** |
| 403 | `device_not_registered` | 未登録 → 再試行せず画面表示 |
| 403 | `device_revoked` | 停止・紛失 → 再試行せず画面表示、運用ルールでロック/消去 |
| 403 | `insufficient_scope` | scope 不足 (追加) |
| 403 | `invalid_registration_code` | 登録コードが無効・期限切れ・使用済み (追加) |
| 426 | `app_version_unsupported` | アプリ更新が必要 (`X-App-Version` < `auth.min-app-version`) → 再試行せず画面表示 |
| 429 | `rate_limited` | レート制限 (追加) |

レスポンス形式は `{"error": "<コード>", "message": "<説明>"}`。

## 端末側の保護 (オフライン対策)

- **鍵**: Android Keystore の ECDSA P-256 鍵。取り出し不可、StrongBox 優先 → TEE にフォールバック (`AndroidKeystoreDeviceKeyStore`)
- **アクセストークン**: メモリ上のみ。期限は端末時刻ではなく `elapsedRealtime` の経過時間で管理し、401 なら再認証 (`AccessTokenHolder`)
- **ローカル DB**: SQLCipher。パスフレーズはランダム 256bit を Keystore の AES 鍵でラップして保存 (`LocalDbKeyProvider`, `SecureLocalDatabase`)
- **最終同期からの経過ロック** (`OfflineGuard`): サーバーが配信する日数(既定 7 日)を超えたらアプリをロック。
  経過時間 = `max(観測した壁時計の最大値 − 同期時の壁時計, 同一ブート内の elapsedRealtime 差)` とし、時計の巻き戻しでは延長できない。
  時計を進めた場合はロックが早まるだけ (安全側)。最後まで同期に成功したときだけ記録する
- **失効**: `device_revoked` 受信でアプリをロックし自動認証を停止。ローカルデータを消去するかはビルド設定 `-PwipeOnRevoked=true|false` (既定 false) で運用ルールに合わせる
- **通信**: HTTPS のみ (`usesCleartextTraffic=false`)。`network_security_config.xml` で同梱した社内 CA だけを信頼。`allowBackup=false`

## 動かし方

### 1. 証明書 (社内 CA)

```bash
scripts/gen-private-ca.sh sync-server.local localhost 10.0.2.2   # certs/ と android/.../res/raw/private_ca.pem を生成
```

リポジトリ同梱の `private_ca.pem` は秘密鍵が破棄された**プレースホルダ**です。実運用では必ず上のスクリプトで自分の CA を作り直し、
`certs/ca.key` を安全な場所に保管してください(アプリは再ビルドが必要)。

### 2. サーバー

```bash
cd server
ADMIN_API_TOKEN=change-me \
SERVER_SSL_ENABLED=true SERVER_SSL_KEY_STORE=file:../certs/server.p12 \
SERVER_SSL_KEY_STORE_PASSWORD=$(cat ../certs/server.p12.password) \
./gradlew bootRun
```

- 初回起動で JWT 署名鍵 (`signing-keys/<kid>.jwk`) を自動生成。DB は既定で H2 ファイル (`./data`)。本番は `spring.datasource.*` を差し替え
- `ADMIN_API_TOKEN` 未設定なら `/admin/**` は 503 (無効)
- 主な設定は `server/src/main/resources/application.yml` (トークン/チャレンジ/登録コードの有効期間、レート制限、`sync.max-offline-days` など)

```bash
# 登録コード発行 → 端末で入力
curl --cacert certs/ca.pem -X POST -H "Authorization: Bearer change-me" https://sync-server.local:8443/admin/v1/registration-codes
# 紛失扱いにする
curl --cacert certs/ca.pem -X PUT -H "Authorization: Bearer change-me" -H 'Content-Type: application/json' \
  -d '{"status":"lost"}' https://sync-server.local:8443/admin/v1/devices/<deviceId>/status
```

### 2'. サーバーを docker compose で起動する (推奨)

```bash
scripts/gen-private-ca.sh 192.168.10.5          # サーバーの LAN IP (または名前) を SAN に入れて CA・サーバー証明書を作成
cp .env.example .env                              # ADMIN_API_TOKEN を変更
docker compose up -d --build
```

- `certs/server.p12` は読み取り専用で mount、keystore のパスワードは Docker secret (Spring の `configtree`) で渡します
- DB (`/app/data`) と JWT 署名鍵 (`/app/signing-keys`) は named volume。コンテナを作り直しても残ります
- コンテナは非 root (uid 10001)。healthcheck は TLS で応答できるかを確認します
- Docker Hub の取得制限 (429) が出る環境では `.env` に `BASE_REGISTRY=mirror.gcr.io/library/` を追加
- 管理操作: `scripts/new-registration-code.sh`(登録コード発行)、`scripts/set-device-status.sh <deviceId> lost`(紛失扱い)、`scripts/set-device-status.sh list`(端末一覧)

> **JDK は 17〜21 を使ってください。** Gradle 8.14 / Kotlin 2.1 は JDK 22 以降(例: 25)では動かず、`What went wrong: 25` のようなエラーで落ちます。
> macOS: `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew ...`、または Android Studio 同梱の JDK
> (`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`) を指定。android/ では起動時にチェックして案内を出します。
> `-PsyncServerUrl` は `https://<IP>:8443` の形式 (`192.168.11.5` のようにスキームやポートを省略した場合は `https://` と `:8443` を補います)。

### エミュレータで試す

エミュレータからホスト PC には **`10.0.2.2`** で届きます。`10.0.2.2` を証明書の SAN と接続先 URL に使います。

```bash
# 1. AVD を用意。API 28 以上、Google APIs イメージ。
#    ※ sdkmanager が無い場合 (Android Studio 既定では未インストール) は下の「Device Manager で作る」を使うか、
#      Android Studio > Settings > Languages & Frameworks > Android SDK > SDK Tools で
#      「Android SDK Command-line Tools (latest)」にチェックして Apply
#    Apple Silicon は arm64-v8a、Intel Mac / Windows / Linux は x86_64
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "system-images;android-34;google_apis;arm64-v8a"
$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd -n sync-test -k "system-images;android-34;google_apis;arm64-v8a"
$ANDROID_HOME/emulator/emulator -avd sync-test &

# 2. 証明書作成 → サーバー起動 → APK ビルド → エミュレータへインストール (JDK は 17〜21)
export ANDROID_HOME=/path/to/android-sdk
scripts/dev-up.sh 10.0.2.2

# 3. 登録コードを発行し、アプリに入力して「登録する」
scripts/new-registration-code.sh
```

- **Device Manager で作る場合 (コマンド不要)**: Android Studio > Tools > Device Manager > Create Device > Pixel 系 > システムイメージ API 34 (Google APIs) をダウンロード > Finish > ▶ で起動。起動後に `scripts/dev-up.sh 10.0.2.2` を実行
- 手動でやる場合: `scripts/gen-private-ca.sh 10.0.2.2` → `docker compose up -d --build` → `cd android && ./gradlew :app:installDebug -PsyncServerUrl=https://10.0.2.2:8443`
- エミュレータに StrongBox は無いため、鍵は自動で通常の Keystore にフォールバックします
- 動作確認の例: `scripts/set-device-status.sh list` で端末を確認 → `lost` に変更 → アプリで「今すぐ同期」→ ロック表示。
  オフラインロックは `.env` に `SYNC_MAX_OFFLINE_DAYS=0` を入れて `docker compose up -d` し直し、同期後に画面を開き直すとロックされる
- ログ: `adb logcat | grep -i com.example.sync`、サーバー: `docker compose logs -f sync-server`
- 登録済みの状態をやり直すには、エミュレータで「設定 > アプリ > 端末認証サンプル > ストレージ > データを消去」(Keystore の鍵も消えます)

### 実機 (本物の Android 端末) で試す

実機は hosts を編集できないため、**サーバーの LAN IP を証明書の SAN に入れ、`https://<IP>:8443` で接続**します。
上の手順をまとめたスクリプトがあります。

```bash
export ANDROID_HOME=/path/to/android-sdk         # APK をビルドするホストで
scripts/dev-up.sh 192.168.10.5                    # 証明書作成 → docker compose 起動 → APK ビルド → adb install
scripts/new-registration-code.sh                  # 登録コードを発行
```

1. 端末を USB デバッグで接続しておくと `adb install` まで行います(未接続なら `android/app/build/outputs/apk/debug/app-debug.apk` を端末へコピーしてインストール)
2. 端末はサーバーと同じネットワークに接続し、ホストのファイアウォールでポート 8443 を許可
3. アプリを開き、発行した登録コードを入力して「登録する」→ 続けて初回同期が走ります
4. 動作確認: `scripts/set-device-status.sh list` で端末が見える → `scripts/set-device-status.sh <deviceId> lost` の後に「今すぐ同期」で `device_revoked` が表示されロックされる → `active` に戻して再登録なしで復帰(ただし revoked フラグが立った端末は再登録が必要)
5. 機内モード等でサーバーに届かない状態のまま、サーバーが配信した日数(既定 7 日)を超えるとロックされます(`sync.max-offline-days` を 0 や 1 にして確認可能)

証明書を作り直したら **APK も必ず再ビルド**してください(アプリは同梱した CA だけを信頼します)。

### 3. Android アプリ

```bash
cd android
ANDROID_HOME=/path/to/sdk ./gradlew :app:assembleDebug -PsyncServerUrl=https://sync-server.local:8443
```

端末(エミュレータ)からサーバーのホスト名 (`sync-server.local`) を引けること、サーバー証明書の SAN に含まれていることが必要です。

### 4. テスト

```bash
(cd android && ./gradlew :authclient:test)   # プロトコル実装・オフラインロック判定 (JVM)
(cd server  && ./gradlew test)               # サーバー + 実際の authclient を HTTP で繋いだ E2E
```

サーバーのテストは `android/authclient` のソースをそのまま取り込み、本物のクライアントで登録→認証→同期まで通します
(Keystore の代わりにソフトウェア鍵を使用。署名は Android と同じ DER 形式)。

## 運用メモ

- **サーバー署名鍵のローテーション**: 新しい `<kid>.jwk` を `signing-keys/` に置き、`AUTH_ACTIVE_KID` を新 kid にして再起動。
  旧鍵ファイルは検証専用として残り、発行済みトークンは期限まで有効。最大有効期間(30 分)経過後に旧鍵ファイルを削除
- **端末の入れ替え・初期化**: Keystore の鍵はアンインストール/初期化で消えるので、旧端末を `suspended` にして新しい登録コードで再登録
- **監査ログ**: 登録・チャレンジ・トークン発行の成功/失敗を `auth_audit_logs` に記録 (端末ID・イベント・結果・エラーコード・IP)
- **IP の扱い**: レート制限と監査ログは `remoteAddr` を使用。リバースプロキシ配下に置く場合は信頼できるプロキシからの `X-Forwarded-For` だけを採用するよう設定を追加してください
- **管理画面の認証**: 本サンプルの管理 API は静的トークンによる簡易保護です。実運用では管理画面側のユーザー認証と組み合わせてください

## 未実装 (意図的)

- **Key Attestation** (登録時に鍵がハードウェア内で生成されたことの証明)。初期段階では必須にしない前提のため未実装
- **QR コードでの登録コード入力** (現状は手入力)
- **操作者(PIN/生体)のローカル認証と、push された操作者 ID のサーバー側許可判定**。`PushRecord.operatorId` を運ぶ口だけ用意
- **クレードル接続をトリガーにした自動同期** (`SyncRunner` は UI から切り離してあるので、WorkManager 等から呼べる)
- 業務データの保存・配信
