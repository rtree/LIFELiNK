# 引き継ぎ（新しいセッションはここから読む）

最終更新: 2026-09-26

会話履歴を捨てて新しいセッションを始めても作業を続けられるように、チャットの中にしか無かった知識をここへ集めた。**順序**: 本ファイル → `doc/tasks.md`（次にやること） → `doc/plan.md`（設計の正本、必要な章だけ） → `reference/*.md`（実測記録）。

## 1. 現在地

- MVP 主線（P0-01〜P0-21）は完了し、**タグ `mvp-0.1`** と `reference/mvp0.1.md` で保全済み。壊したらここへ戻る。
- 動く縦断フロー: 物理ボタン（+Beacon）or 画面ボタン → Safety gate → Cloud Run → Twilio が登録先へ電話 → OpenAI Realtime の AI が都道府県とメモを話す → 同時に Discord Bot が同意済み友人へ DM → 通話の書き起こしを DM へ逐次中継 → 友人の返信が同じイベントに保存され通話中の AI に伝わる。
- 次にやること: `doc/tasks.md`「次のアクション」の順（P1-16 チャット UI→ 実装済み、実通話での transcript 表示確認が残る → P1-17 段階的英語化/B2C UI → P2-01/02 → P2-07 → P1-18/19）。

## 2. 壊してはいけない不変条件

| 不変条件 | 破ると何が起きるか |
| --- | --- |
| Cloud Run は **`maxScale=1`** | 通話中の Realtime セッション表（`voice.ts`）と Discord 中継キュー（`discord.ts`）はインスタンス内メモリ。2 台以上になると通話中メモ・Discord 返信が AI に届かず、書き起こし中継も欠ける。性能改善のつもりでスケールさせない。直すなら Firestore 購読方式へ設計変更が先。 |
| gcloud/firebase は**必ず `--project=ethglobaltokyo2026lifelink`** | ホストの `gcloud config` 既定は別プロジェクト（旧ハッカソンの `beacontesttokyo`）。付け忘れると無関係な資源を見て誤診する。 |
| World ID の app/RP/action は**再作成しない** | `app_30fbdcf47be73f8a3603f0633b8aeb7c` / `rp_f73bfaa54987b8ce` / action `verify-emergency-caller`（production）は本番登録済み。作り直すと既存の証明・署名鍵が無効になる。鍵は Secret `key-world-id-rp-signing`。 |
| Twilio の発信番号は Secret `key-twilio-from-number`（SID `PN25e30a4c7e287953ff4ebce4d33c3771`）**のみ** | 同一アカウントに旧プロジェクト用の番号（+1629280xxxx）も居る。そちらを触ると無関係な設定を壊す。 |
| Firestore への**書き込みは backend（Admin SDK）だけ** | クライアント書き込みは rules で全面拒否済み。Android から直接書く実装を足さない（読みは直接 OK。P1-16 のライブフィードが実際に直接 read している）。 |
| **モック・ダミーデータを作らない** | `.github/copilot-instructions.md` の方針。UI は最初から本物の Firestore コレクションだけを読む。未実装は「準備中」と表示する。 |
| P0 の `emergency_events`/`updates` の既存データを**移行・削除しない** | MVP 0.1 の実機検証の証跡。P2 は新規イベントから `emergencySessions` を使う。バックアップ: `gs://ethglobaltokyo2026lifelink-firestore-backups/mvp-0.1-2026-09-26`。 |
| 音声は **G.711 μ-law / 8kHz / mono（`audio/pcmu`）** | Twilio Media Streams と OpenAI Realtime の両端でこの形式に揃えてある。片側だけ変えると無音・雑音・書き起こし失敗になる。 |
| スキーマは**コードより先に `doc/plan.md` を直す** | 6 章・6a 章・「P0-16 凍結スキーマ・API」・8a 章が正本。`firestore.rules` / `firestore.indexes.json` はその従属物。 |

## 3. 環境（ホスト・ビルド・デプロイ）

Agent はゲスト VM で動いており、Android ビルドと実機操作は**ホスト macOS**（SSH alias `beacon-host`、`10.211.55.2`）で行う。詳細は `doc/host-setup.md`。

```sh
# Android ビルド（JDK 21。17 ではない）
cd android
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug

# 実機へ配布（Samsung SM-S942Z / Android 16、serial RFGL41GKP0Z）
scp app/build/outputs/apk/debug/app-debug.apk beacon-host:/tmp/lifelink-debug.apk
ssh beacon-host '"$HOME/Library/Android/sdk/platform-tools/adb" -s RFGL41GKP0Z install -r /tmp/lifelink-debug.apk'

# backend デプロイ（既存 env/secret は保持される）
gcloud run deploy lifelink-backend --source backend --region=asia-northeast1 \
  --project=ethglobaltokyo2026lifelink --quiet

# Firestore rules と indexes（indexes を忘れるとクエリが失敗する）
npx -y firebase-tools@latest deploy --only firestore:rules,firestore:indexes \
  --project ethglobaltokyo2026lifelink
```

## 4. 確認コマンド（「動いているか」を証明する）

```sh
# backend 生存とデプロイ内容
curl -s https://lifelink-backend-1023311564471.asia-northeast1.run.app/health
gcloud run services describe lifelink-backend --region=asia-northeast1 \
  --project=ethglobaltokyo2026lifelink \
  --format='value(spec.template.metadata.annotations."autoscaling.knative.dev/maxScale",status.latestReadyRevisionName)'

# Discord Interactions が署名検証している（署名なしは 401）
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  https://lifelink-backend-1023311564471.asia-northeast1.run.app/v1/discord/interactions

# Secret のバージョンが ENABLED か（値は表示しない）
for s in key-twilio-sid key-twilio-authToken key-twilio-from-number \
  key-openai-ethglobaltokyo-nolimit key-world-id-rp-signing \
  key-discord-bot-token key-discord-oauth-client-secret; do
  echo "$s $(gcloud secrets versions list $s --project=ethglobaltokyo2026lifelink \
    --filter='state=ENABLED' --sort-by=~name --limit=1 --format='value(name,state)')"
done

# 直近の緊急イベントと配送状態（Firestore）
gcloud firestore databases describe --database='(default)' \
  --project=ethglobaltokyo2026lifelink --format='value(locationId,type)'

# backend ログ（通話・DM・Beacon の実行痕跡）
gcloud logging read \
  'resource.type="cloud_run_revision" AND resource.labels.service_name="lifelink-backend"' \
  --project=ethglobaltokyo2026lifelink --limit=50 --format='value(timestamp,textPayload,jsonPayload.msg)'

# Android 側の Beacon 受信ログ
ssh beacon-host '"$HOME/Library/Android/sdk/platform-tools/adb" -s RFGL41GKP0Z logcat -s LIFELiNK.BeaconLog'
```

合格条件のチェックリストは `reference/mvp0.1.md` 5 章。

## 5. 既知の落とし穴（知らないと 1 時間溶ける）

- **Discord DM は Bot と受信者が同じサーバーにいないと必ず `50278` で失敗する**。OAuth 同意だけでは届かない。LIFELiNK 用テストサーバーに Bot と受信者の両方を入れる運用（手順はアプリ内にも表示している）。
- **+Beacon が IDLE だと何も広告しない**。メーカーアプリ（NFC 必要）で RUNNING・ボタン検知モード・間隔 1 秒・**送信時間 60 秒**・TxPower 0 dBm。ボタンを押しても無反応なとき、まずここを疑う。詳細 `reference/beacon-verification.md`。
- **Beacon のリンク情報は端末ローカル（SharedPreferences）**。APK を入れ直すと見守りサービスが止まり、リンクもやり直しになる。アカウント同期は未実装（`doc/plan.md` 1a 章 1 の「残る不足」）。
- **Galaxy のバッテリー最適化から除外しないとロック中の受信が不安定**。アプリのバッテリーを「制限なし」にする。
- **画面 OFF 中は BLE の受信が OS に間引かれる**（60 秒あたり 5〜14 パケット、空白 11〜23 秒）。送信時間 60 秒・途切れ判定 75 秒はこの実測に基づく決定であり、短くすると取りこぼす。代償として同じボタンの連打は 75 秒間 1 回として扱われる。
- **Firestore の indexes は rules と別デプロイ**。忘れると一覧クエリが "requires an index" で落ちる。
- **通話中に「AI の声だけ聞こえない」片方向音声が起きることがあるが、多くは一過性で backend 側の不具合ではない**。2026-09-26 14:57 の通話（`04e6fb02-…`）で相手が「あなたの声が聞こえない。私の声は聞こえてるみたい」と発言。直後 15:06 の通話（`b53d0cea-…`）はコード変更なしで正常、ロック中・ロック解除の両方で正常。切り分け方:
  1. `Twilio Media Stream closed` ログの `outputAudioFrames` が 0 でなければ backend は Twilio へ音声を送っている（失敗時も 480 あった）。
  2. `Twilio Media Stream status` の `streamError` が null なら Stream 自体は正常。
  3. `updates` に `transcript_contact` が並んでいれば相手→AI の上り方向は生きている。
  この 3 つが揃ったらコードを触らずに掛け直す。直らないときの次の容疑は `input_audio_buffer.speech_started` ごとの Twilio `clear`（`voice.ts`）による自己割り込みだが、上記の事件では相手が無言の区間でも聞こえていなかったためこれでは説明できなかった。
- `sanitizeLocationForPersistence()` は名前に反して **`accuracy_m` は意図的に残す**（位置を明かさない数値のため）。消すのは緯度・経度と番地レベルの住所だけ。
- **Compose の `LazyColumn` を親の `verticalScroll` の中に置くときは `heightIn(max = ...)` を付ける**。無制限だとクラッシュし、`height` 固定だと発言が少ないときに大きな空白が残る（P1-16 で実際に踏んだ）。
- **ライブフィードは「自分が owner の最新イベント 1 件」だけを表示する**。画面ボタン発信も Beacon 発信も同じ経路で拾えるが、過去イベントの選択 UI はない。実機検証で transcript が見たいときは新しい実通話を 1 回行う必要がある。

## 6. 既知のギャップ（バグではなく、把握済みの未対応）

| 項目 | 状態 | 追跡先 |
| --- | --- | --- |
| Beacon リンク情報のアカウント同期 | 設計のみ（`users/{uid}/linkedTriggers` 案） | `doc/plan.md` 6 章・1a 章 1 |
| `GET /v1/contacts` / `GET /v1/emergency-events`（一覧系） | **実装しないと決定**（P1-16）。ライブフィードは Android から Firestore を直接 read する | `doc/plan.md` 6a 章「Android ライブフィードの実装方針」 |
| P2（`emergencySessions`/`facts`/`timeline`/`delegations`） | スキーマ凍結・rules と indexes はデプロイ済み、実装ゼロ | `doc/tasks.md` P2-01〜P2-07 |
| Full UI（`doc/uimock/` の 20 画面） | 設計凍結済み、実装は検証用 1 画面のみ | `doc/plan.md` 4a 章、P1-16/P1-17 |
| 英語化 | 文言は Kotlin 内にハードコードされた日本語（約 80 箇所、`MainActivity.kt` に集中）。`values-en` なし | P1-17（触る画面から段階的に） |
| 失敗系（権限拒否・通信断・外部 API 障害）の網羅 | 未実施 | `doc/tasks.md` P3-01〜P3-04 |
| 通話相手への「内容を友人と共有している」告知 | デモのため意図的に入れていない | `doc/plan.md` 4a 章、製品化時に再検討 |

## 7. 直近で直した設計ドリフト（再発させない）

- 2026-09-26: `emergency_events` に `trigger_source` と `participant_uids` を書いていなかった（`doc/plan.md` 6 章・`firestore.rules` は前提にしていた）。backend で `trigger_source`（`ble` のとき既定 `beacon`）と `participant_uids: []` を書くよう修正。
- 2026-09-26: `doc/plan.md` 6b 章に「全ユーザー共通の固定 UUID」という誤った記述が残っていたため削除。正しくは利用者ごとのスロット登録で、`BB192440-…`/Major `11665`/Minor `31295` は**待機広告**であってボタン押下ではない。

## 8. セッションを畳むときにやること

1. 作業で得た「ここにしか無い知識」を本ファイル 2・5・6 節へ追記する。
2. 決定と理由を `doc/plan.md` へ、次の一手を `doc/tasks.md`「次のアクション」へ反映する。
3. 実機で確認した事実は `reference/` の該当ファイルへ観測値付きで残す。
4. コミットして push し、作業ツリーを clean にしてから終わる。
