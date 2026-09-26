# LIFELiNK MVP 0.1 — 「It Just Works」時点の再現手順（2026-09-26）

2026-09-26 13 時台に、次の縦断フローが実機・実アカウントで動いた時点のスナップショット。今後リファクタリングで壊れたときに、この状態へ戻せることを目的とする。**秘密値はここに書かない**（Secret 名とバージョンのみ）。

> 物理ボタン（＋Beacon）または画面ボタン → Android Safety gate → Cloud Run → Twilio が登録先へ電話 → OpenAI Realtime の AI が現在地（都道府県）とメモを伝えて会話 → 同時に Discord Bot が同意済み友人へ DM → 通話の書き起こし（相手/AI）を DM に逐次中継 → 友人が「状況を返信」を何度でも送信 → 返信が同じイベントに保存され、通話中の AI が相手に伝える。

## 1. 固定点（この組み合わせで動作確認済み）

| 対象 | 値 |
| --- | --- |
| Git | `main` の `25d9352`（本ファイル追加コミットにタグ `mvp-0.1` を付与） |
| Cloud Run | service `lifelink-backend` / region `asia-northeast1` / revision `lifelink-backend-00025-qb8` |
| コンテナ image | `asia-northeast1-docker.pkg.dev/ethglobaltokyo2026lifelink/cloud-run-source-deploy/lifelink-backend@sha256:e96e3dffedfab516a07ff63aa9cd8b7f0ad4388a3610d8bebd4412076002d7f5` |
| Cloud Run 設定 | SA `lifelink-backend@ethglobaltokyo2026lifelink.iam.gserviceaccount.com`、`maxScale=1`（必須、後述）、`minScale=0`、concurrency 80、timeout 3600s |
| GCP/Firebase project | `ethglobaltokyo2026lifelink`（number `1023311564471`）。**`gcloud config` の既定が別 project のことがあるので常に `--project` を明示** |
| Firestore | `(default)`、`asia-northeast1`、Native。rules/indexes はリポジトリの `firestore.rules` / `firestore.indexes.json`（indexes 4 件） |
| Android | `com.rtree.LIFELiNK`、versionName `0.1.0` / versionCode 1、minSdk 26 / target 36。検証端末 Samsung SM-S942Z（Android 16） |
| debug APK | SHA-256 `bf68da348e14bb8fae35f620b32d7196e3406b4f9dddd32fc9f58c58a3eeaf5b`（`25d9352` 時点のビルド） |
| ビルド環境 | JDK 21（`/opt/homebrew/opt/openjdk@21`）、Android SDK `~/Library/Android/sdk`、Node 22（Dockerfile。ローカルは v24 でも可） |

### Cloud Run 環境変数

| 変数 | 値 / 参照 |
| --- | --- |
| `GOOGLE_CLOUD_PROJECT` | `ethglobaltokyo2026lifelink` |
| `BACKEND_URL` | `https://lifelink-backend-1023311564471.asia-northeast1.run.app` |
| `OPENAI_REALTIME_MODEL` | `gpt-realtime` |
| `WORLD_ID_APP_ID` / `WORLD_ID_RP_ID` | `app_30fbdcf47be73f8a3603f0633b8aeb7c` / `rp_f73bfaa54987b8ce` |
| `WORLD_ID_ACTION` / `WORLD_ID_ENVIRONMENT` | `verify-emergency-caller` / `production` |
| `DISCORD_APPLICATION_ID` / `DISCORD_PUBLIC_KEY` | 未設定（`backend/src/config.ts` の既定値 `1553217776179486882` / `ed97e676…fdb84` を使用） |
| `TWILIO_ACCOUNT_SID` | Secret `key-twilio-sid`（version 1） |
| `TWILIO_AUTH_TOKEN` | Secret `key-twilio-authToken`（version 1） |
| `TWILIO_FROM_NUMBER` | Secret `key-twilio-from-number`（version 1。Phone Number SID `PN25e30a4c7e287953ff4ebce4d33c3771`） |
| `OPENAI_API_KEY` | Secret `key-openai-ethglobaltokyo-nolimit`（version 1） |
| `WORLD_ID_RP_SIGNING_KEY` | Secret `key-world-id-rp-signing`（version 1） |
| `DISCORD_BOT_TOKEN` | Secret `key-discord-bot-token`（version 1） |
| `DISCORD_CLIENT_SECRET` | Secret `key-discord-oauth-client-secret`（version 1） |

Cloud Run の参照は `:latest`。すべて version 1 が最新なので、この時点では `:1` と同義。各 Secret には上記 SA の `roles/secretmanager.secretAccessor` を個別付与している。

## 2. 外部サービスの設定

- **Twilio**: アカウント `status: active`、`type: Full`。発信番号は上記 Phone Number SID。TwiML・Media Stream の URL は backend が発信時に指定する（`/v1/twilio/voice`、`/v1/twilio/status`、`wss …/v1/twilio/media`）。別の番号（+1629280xxxx）は旧プロジェクト用なので触らない。
- **OpenAI Realtime**: `gpt-realtime`、音声 `audio/pcmu`、voice `marin`、server VAD。入力音声 transcription は `gpt-4o-mini-transcribe`（`ja`）。
- **World ID**: 上記 app/RP/action（production）。人間性証明で Firebase の custom claim `human_verified` が付き、`POST /v1/emergency-events` の必須条件になる。
- **Firebase Auth**: Google provider 有効。Android の `google-services.json` はリポジトリ内（debug SHA-1 登録済み）。
- **Discord アプリ「LIFELiNK」**（Application ID `1553217776179486882`）
  - OAuth2 Redirects: `https://lifelink-backend-1023311564471.asia-northeast1.run.app/v1/discord/oauth/callback`（Portal で手動設定。Bot token からは変更不可）
  - Interactions Endpoint URL: `https://lifelink-backend-1023311564471.asia-northeast1.run.app/v1/discord/interactions`（Bot token で `PATCH /applications/@me` して設定。Discord の署名付き PING 検証に通過）
  - Bot をテスト用サーバーへ追加済み: `https://discord.com/oauth2/authorize?client_id=1553217776179486882&scope=bot&permissions=0&integration_type=0`。**受信者も同じサーバーにいないと DM は `50278` で失敗する**
- **＋Beacon（PB-BTN-01）**: メーカーアプリで RUNNING・ボタン検知モード・間隔 1 秒・**送信時間 60 秒**・TxPower 0 dBm。詳細は `reference/beacon-verification.md`。

## 3. Firestore のデータ（本物のスキーマ）

| パス | 内容 |
| --- | --- |
| `users/{uid}` | 利用者 |
| `users/{uid}/contacts/{contact_id}` | 電話の登録先（`phone_e164`、`enabled`） |
| `users/{uid}/locations/{id}` | 都道府県と時刻のみ（座標・精度は保存しない） |
| `users/{uid}/discordInvites/{invite_id}` | 招待（トークンは SHA-256 のみ、24 時間、一回限り） |
| `users/{uid}/discordContacts/{discord_user_id}` | 同意済み Discord 連絡先（`status: active/revoked`、`last_test_dm`） |
| `emergency_events/{id}` | 緊急イベント（`uid`、`contact_id`、`trigger_type`、`state`、`location_snapshot`、`initial_note`、`twilio_call_sid`） |
| `emergency_events/{id}/updates/{id}` | 時系列フィード: `note` / `location`（本人）、`friend_comment`（Discord 返信、doc id `discord_{interaction_id}`）、`transcript_contact` / `transcript_ai`（通話書き起こし）、`system`（通話終了）。AI へ伝えたら `delivered_to_ai_at` |
| `emergency_events/{id}/discord_notifications/{discord_user_id}` | DM の宛先スナップショット兼配送状態（`owner_uid`、`status: pending/sent/failed`、`channel_id`、`message_id`、`error_code`） |
| `world_id_nullifiers/…` | World ID の再利用防止 |

書き込みはすべて backend（Admin SDK）経由。クライアント書き込みは rules で全面拒否。スキーマの正本は `doc/plan.md`（6・6a 章と「P0-16 凍結スキーマ・API」）。

## 4. ゼロから再現する手順

1. **コード**: `git checkout mvp-0.1`
2. **Firestore**: `npx -y firebase-tools@latest deploy --only firestore:rules,firestore:indexes --project ethglobaltokyo2026lifelink`
3. **Secret**: 上表の Secret が存在し version が enabled であることを `gcloud secrets versions list <name> --project=…` で確認（値は表示しない）。無ければ Console の「新しいバージョンを追加」に直接貼る。SA へ `roles/secretmanager.secretAccessor` を Secret ごとに付与。
4. **backend**（既存サービスの env/secret は保持される）:
   ```sh
   gcloud run deploy lifelink-backend --source backend --region=asia-northeast1 \
     --project=ethglobaltokyo2026lifelink --quiet \
     --update-secrets=DISCORD_BOT_TOKEN=key-discord-bot-token:latest,DISCORD_CLIENT_SECRET=key-discord-oauth-client-secret:latest
   ```
   サービスを作り直す場合は、上の環境変数表を `--set-env-vars` / `--set-secrets` で全部渡し、`--service-account` と `--max-instances=1`、`--timeout=3600` を付ける。まったく同じ image に戻すだけなら `gcloud run deploy lifelink-backend --image <上記 image@sha256> --region=asia-northeast1 --project=…`。
   確認: `/health` が 200、`POST /v1/discord/interactions` が署名なしで 401。
5. **Discord**: Redirect URL と Interactions Endpoint URL が上記どおりか確認（`GET /applications/@me` を Bot token で。token は表示しない）。Bot と受信者が同じサーバーにいること。
6. **Android**:
   ```sh
   cd android
   export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
   export ANDROID_HOME="$HOME/Library/Android/sdk"
   ./gradlew assembleDebug
   # 端末は beacon-host 経由（doc/host-setup.md）
   scp app/build/outputs/apk/debug/app-debug.apk beacon-host:/tmp/lifelink-debug.apk
   ssh beacon-host '"$HOME/Library/Android/sdk/platform-tools/adb" -s <serial> install -r /tmp/lifelink-debug.apk'
   ```
7. **アプリの初期設定**（入れ直し後は毎回「見守りを開始」が必要）:
   Google ログイン → World ID で人間証明 → 現在位置を取得して保存 → 緊急連絡先（電話）を登録 → Discord 欄の手順（サーバー作成・Bot 追加・友人を招待・招待 URL→同意・テスト DM→「受信を確認」）→ ボタン1/2 を短押ししてからカードの「このBeaconをリンク」（BB192440 が「待機」になること）→ 送信時間「60秒」→ バッテリー最適化から除外・アプリのバッテリーを「制限なし」→「見守りを開始（常駐）」→ 通知欄に「LIFELiNK 見守り中」→ ドライランを OFF。

## 5. 動作確認のチェックリスト（MVP 0.1 の合格条件）

- [ ] ボタン1/2・短押し/長押しのどれでも（画面 ON で約 2 秒、ロック中は数秒遅れ得る）電話が 1 本だけ着信する
- [ ] AI が冒頭で「LIFELiNK からの自動電話」「都道府県」「位置の取得からの経過時間」「メモ」を話す
- [ ] 通話中の Android「通話中メモを送信」を AI が伝える
- [ ] Discord の友人に 🚨 緊急 DM が 1 通届く
- [ ] 通話の発話が `📞 電話の相手:` / `🤖 AI:` として DM に順に流れ、終了時に「通話が終了しました」
- [ ] 友人が「状況を返信」「続けて返信」で複数回返信でき、通話中の AI がそのたびに伝える
- [ ] Firestore の `emergency_events/{id}` に `updates`（note/transcript_*/friend_comment/system）と `discord_notifications`（`sent`）が残る

調査用コマンド（イベント、Twilio 状態）は `doc/plan.md` と会話ログの手順を参照。Twilio の通話状態は Secret を環境変数に読み込んで `GET /2010-04-01/Accounts/{sid}/Calls/{CallSid}.json`（値は表示しない）。

## 6. この時点の既知の制約（リファクタリング時に注意）

- **`maxScale=1` 前提**: 通話中の Realtime セッション表（`voice.ts` の `activeRealtimeSessions`）と書き起こし中継キュー（`discord.ts`）はインスタンス内メモリ。台数を増やすと、別インスタンスに届いたメモ/Discord 返信が AI に届かない。スケールさせるなら Firestore の `updates` を購読して注入する方式へ変える。
- Android は 1 画面（`MainActivity.kt`）に全機能が載った検証用 UI。フル UI（`doc/uimock/`、4a 章）は未着手。
- データは P0 の `emergency_events`/`updates`。P2 の `emergencySessions`/`facts`/`timeline`（8a 章）へは未移行。
- Beacon のリンク情報・ドライラン・送信時間設定は端末ローカル（`SharedPreferences`）。アカウント同期は未実装。
- APK 更新で見守りサービスが止まる。画面 OFF 中は BLE の受信が間引かれる（`reference/beacon-verification.md`）。
- 電話の相手への「通話内容を友人と共有する」告知はデモのため入れていない。
- 失敗系（権限拒否・通信断・外部 API 障害）の網羅確認は P3 に後回し。
- GPS 座標・精度・詳細住所は保存・発話・DM しない（都道府県のみ）。
