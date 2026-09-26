# 周辺音の収集: 可否調査と実機検証（ambient）

最終更新: 2026-09-26 / 状態: **調査完了・マイク権限の宣言のみ実装・録音は未実装・実機未検証**

目的は「周囲の音を短いテキスト観測に変えて、通話中の AI と Discord の友人に渡す」こと。
本ファイルは**周辺音の作業を別セッションで再開するための単独の入口**であり、
「そもそも今の状態で録れるのか」「何を実機で確かめるべきか」を集約する生きた文書。
検証するたびに 5 章へ観測値を追記すること。

不変条件（先に固定）: **生音声は永続化しない。** 解析のための一時送信は許容するが、
保存するのは派生テキストだけ。`updates`・ログ・一時ファイルのどこにも音声バイトを残さない。

---

## 0. このセッションを再開する人へ

**読む順序**: `reference/handover.md`（プロジェクト全体の不変条件・環境・落とし穴）→ 本ファイル → 必要なら
`doc/tasks.md` の P2 節。**`handover.md` を読まずに環境を触らないこと。**

### 現在地（2026-09-26 時点）

| 項目 | 状態 |
| --- | --- |
| commit | `dd262a0`（main、push 済み） |
| Cloud Run | `lifelink-backend-00029-pfg`、`maxScale=1` |
| 実機 | Samsung SM-S942Z / Android 16 / serial `RFGL41GKP0Z`（SSH alias `beacon-host` 経由） |
| マニフェスト | `RECORD_AUDIO` と `FOREGROUND_SERVICE_MICROPHONE` を宣言済み |
| マイク権限の実行時許可 | **まだ `granted=false`**。Settings の "Allow microphone" を人間が押す必要がある |
| 録音コード | **ゼロ**。`AudioRecord` も FGS も未実装 |
| backend の取り込み口 | **無い**。音声チャンクを受ける API も `type: "ambient"` の書き込みも未実装 |

つまり **P2-08 まで完了、P2-09 以降は手つかず**。

### 次の一手（この順で）

1. **人間に "Allow microphone" を押してもらう**（Settings タブ最下部の Listening セクション）。
   押されるまで録音の実機検証は一切できない。確認コマンドは下の 0.2。
2. `doc/plan.md` に `updates.type: "ambient"` のスキーマを**先に**書く（コードより先に文書、が本リポジトリの規則）。
   案は 9 章にあるが、まだ凍結していない。
3. `microphone` 種別の FGS を実装し、**5 章のチェックリストを実機で埋める**。
   ここが本当の山場で、「ロック中に録れるか」が未検証のまま設計を積むと後で全部崩れる。
4. 録れることを確認してから、初めて backend の取り込み（P2-10）へ進む。

**順番を飛ばさないこと。** 3 を確かめる前に 4 を作ると、動かない理由が「録れていない」のか
「送れていない」のか切り分けられなくなる。

### 0.1 環境コマンド（`handover.md` 3 章の抜粋）

```sh
# Android ビルド（JDK 21。17 ではない）
cd /Users/araki/operations/LIFELiNK/android
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug

# 実機へ配布
scp app/build/outputs/apk/debug/app-debug.apk beacon-host:/tmp/lifelink-debug.apk
ssh beacon-host '"$HOME/Library/Android/sdk/platform-tools/adb" -s RFGL41GKP0Z install -r /tmp/lifelink-debug.apk'

# backend デプロイ（既存 env/secret は保持される。--project を必ず付ける）
gcloud run deploy lifelink-backend --source backend --region=asia-northeast1 \
  --project=ethglobaltokyo2026lifelink --quiet
```

### 0.2 確認コマンド

```sh
ADB='"$HOME/Library/Android/sdk/platform-tools/adb" -s RFGL41GKP0Z'

# マイク権限が許可されたか
ssh beacon-host "$ADB shell dumpsys package com.rtree.LIFELiNK | grep 'RECORD_AUDIO: granted'"

# 今マイクを掴んでいるアプリ（録音中かを OS 側から確認する）
ssh beacon-host "$ADB shell dumpsys media.audio_flinger | grep -i -A5 'Input thread'"
ssh beacon-host "$ADB shell dumpsys audio | grep -i -A10 'recording'"

# FGS が生きているか・種別は何か
ssh beacon-host "$ADB shell dumpsys activity services com.rtree.LIFELiNK | grep -iE 'ServiceRecord|foreground|type'"

# 画面 OFF / ロックの制御（検証用）
ssh beacon-host "$ADB shell input keyevent KEYCODE_SLEEP"   # 画面 OFF
ssh beacon-host "$ADB shell input keyevent KEYCODE_WAKEUP"  # 画面 ON
ssh beacon-host "$ADB shell dumpsys window | grep -m2 -E 'mCurrentFocus|mDreamingLockscreen'"

# アプリのログ（既存の Beacon ログと同じ仕組みに相乗りするのが楽）
ssh beacon-host "$ADB logcat -s LIFELiNK.BeaconLog"
```

**zsh の罠**: `UID` は読み取り専用変数なので `UID=$(...)` は失敗する。`OWNER` 等に変えること。
`echo ===` も展開エラーになるのでクォートする。

### 0.3 触ってよい / 触ってはいけない

触ってよい:
- 新規ファイル（`AmbientAudioService.kt` 等）
- `AndroidManifest.xml`（サービス追加）
- `MainActivity.kt` の Settings タブ内 Listening セクション
- `backend/src/server.ts` への新エンドポイント追加

**触ってはいけない**:
- `backend/src/voice.ts` の Realtime セッション周り。**既存の通話セッションに周辺音を混ぜない**（6 章）。
  rev `00029-pfg` で直した `response.create` 競合を再燃させる。
- Cloud Run の `maxScale=1`。通話中セッションと Discord キューがインスタンス内メモリのため。
- `emergency_events`/`updates` の既存フィールド名。追加は可、変更・削除は不可。

---

## 1. 結論（先に）

| 問い | 答え |
| --- | --- |
| 今の APK で音を録れるか | **録れない。** 権限もサービス種別も録音コードも存在しない（2 章） |
| 実装すれば画面 ON・アプリ前面で録れるか | **録れる。** 通常の `RECORD_AUDIO` + `microphone` FGS で足りる |
| 画面 OFF・ロック中も録り続けられるか | **FGS が正しく起動済みなら、禁止する規定は無い。** ただし保証も無く、Samsung の省電力が最大のリスク。実機検証が必要（5 章） |
| ロック中に Beacon 押下で**録音を開始**できるか | **そのままでは無音になる。** while-in-use 制約（3 章）。ただし**ロック画面の通知をユーザーがタップする経路なら公式に許可されている** |
| Twilio 通話中だから録れない、のでは？ | **その心配は当たらない。**通話するのは backend → 緊急連絡先であり、**ユーザーの端末は通話に参加していない**（4 章） |
| マイク使用中インジケータを消せるか | **消せない。** API は存在しない。消そうとすること自体が Play 規約違反 |

---

## 2. 現在地（コード監査、2026-09-26）

`android/app/src/main/AndroidManifest.xml` に宣言されている権限は
`INTERNET` / `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` / `BLUETOOTH`(maxSdk 30) /
`BLUETOOTH_ADMIN`(maxSdk 30) / `BLUETOOTH_SCAN` / `FOREGROUND_SERVICE` /
`FOREGROUND_SERVICE_CONNECTED_DEVICE` / `POST_NOTIFICATIONS` /
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` のみ。

| 必要なもの | 現状 |
| --- | --- |
| `RECORD_AUDIO` | **無い** |
| `FOREGROUND_SERVICE_MICROPHONE` | **無い** |
| `foregroundServiceType="microphone"` | **無い**（`BeaconMonitorService` は `connectedDevice` 単独） |
| `AudioRecord` / `MediaRecorder` / `SpeechRecognizer` | **コードに一切存在しない** |
| マイク権限の実行時リクエスト | **無い**（位置・BLE と同じ `RequestMultiplePermissions` パターンは既にあるので流用できる） |

`compileSdk 36` / `targetSdk 36` / `minSdk 26`。targetSdk 36 なので Android 14/15 の
FGS 規制はすべて適用対象。

---

## 3. 最大の制約: while-in-use（バックグラウンドからは起動できない）

`RECORD_AUDIO` は while-in-use 権限であり、**背景起動制限の除外に当てはまっても、なお別枠で禁止される**。
公式文言: 「if an app wants to launch a foreground service that needs while-in-use permissions
(for example, body sensor, camera, microphone, or location permissions), it cannot create the service
while the app is in the background, **even if the app falls into one of the exemptions from background
start restrictions**」
（<https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>）

挙動は API レベルで異なる:

- API 30〜33: FGS は起動するが**マイクが無音**になる。logcat に
  `Foreground service started from background can not have location/camera/microphone access` が出る。
- **API 34+（＝我々）: `startForeground()` 自体が `SecurityException`**。
  しかも `PermissionChecker.checkSelfPermission()` はバックグラウンドでも `PERMISSION_GRANTED` を返すので、
  **自前チェックでは防げない**。
- API 34+: `microphone` FGS は `BOOT_COMPLETED` からは起動できない。

### while-in-use 制約の除外（＝ロック中でも録音開始できる唯一の道）

公式に列挙されている除外のうち、ロック画面から到達できるのは次の 2 つ:

- **ユーザーが通知を操作して起動する場合**（notification / notification action）
- ユーザーがアプリウィジェットを操作して起動する場合

→ **「ロック画面に通知を出しておけば可能か」という問いの答えは「はい、ただしユーザーのタップが要る」。**
通知を出しておくだけでは足りず、`Start listening` のようなアクションを**押してもらう**必要がある。
full-screen intent でロック画面に大きく出す設計は取りうる。

したがって経路ごとの可否:

| 経路 | 録音開始できるか |
| --- | --- |
| アプリを開いて画面の SOS を押す | **できる**（アプリが前面＝ while-in-use を満たす） |
| ロック中に Beacon ボタン押下 → 自動で録音開始 | **できない**（`SecurityException`） |
| ロック中に Beacon ボタン押下 → 通知を出し、ユーザーがアクションをタップ | **できる**（公式の除外） |

`systemExempted` FGS 種別は `RoleManager.ROLE_EMERGENCY`（Safety Apps）保持者に限られ、
通常アプリには付与されない。**LIFELiNK では不可能**として扱う。

---

## 4. 「通話中はマイクを取れない」は本件に当たらない（前提の訂正）

`AudioManager.getMode()` が `MODE_IN_CALL` / `MODE_IN_COMMUNICATION` のとき、
「The call always receives audio. The app can capture audio if it is an accessibility service.」であり、
通常アプリは無音になる（<https://developer.android.com/media/platform/sharing-audio-input>）。

**しかし LIFELiNK の設計では、Twilio は backend から緊急連絡先へ発信しており、
ユーザーの Android 端末は通話に参加していない。**端末にテレフォニー通話は存在しないので、この規定に当たらない。

**将来ユーザー端末を通話に参加させる（conference leg / VoIP）設計へ変えると、
端末側の周辺音収集は原理的に成立しなくなる。**その判断とセットで扱うこと。ここは忘れやすい。

残る競合は「通常アプリ同士は同時キャプチャできない（前面 UI のあるアプリが勝つ）」という規則。
`AudioRecord.registerAudioRecordingCallback()` を張って `isClientSilenced()` を監視しないと、
**無音を掴んだまま「動いているつもり」になる**。実装時に必須。

---

## 5. 実機で確かめること（未実施・ここに観測値を書く）

Samsung SM-S942Z / Android 16 / serial `RFGL41GKP0Z`。手順は `reference/handover.md` 3 章。

- [ ] マイク権限を許可した状態で、アプリ前面から `microphone` FGS を起動して非無音の PCM が取れるか
- [ ] **画面 OFF・ロック中に録音が継続するか**（`isClientSilenced()` を毎回ログ）。ここが最大の未知数
- [ ] ロック中に Beacon 押下 → 通知アクションのタップで FGS を起動できるか（3 章の除外が実機で通るか）
- [ ] 背景から直接起動を試し、API 34+ で本当に `SecurityException` になるか（負の確認）
- [ ] Samsung の「スリープ中のアプリ」に入れた場合／除外した場合の差。One UI の省電力は Google の文書に無く、
      **正しく実装した FGS が落ちる最大の実世界要因**
- [ ] バッテリー最適化除外の有無での差（Doze はマイクを止めないが**ネットワークは止まる**ので、
      録れているのに送れない、という状態が起きうる）
- [ ] 録音中のバッテリー消費（%/時）

計測の勘所: 「録れているか」と「送れているか」を分けて記録すること。混同すると原因を取り違える。
Doze はマイクを止めないがネットワークは止めるので、この 2 つは実際に別々に壊れる。
使うコマンドは 0.2 にまとめてある。観測値は日時・画面状態・ロック状態・充電の有無とセットで下に追記すること。

### 観測ログ（ここに追記していく）

まだ 1 件も計測していない。

---

## 6. 音→テキストの方式比較

| 方式 | 生音声が端末外へ出るか | 遅延 | 非発話音（悲鳴・ガラス） | コスト | 実装コスト |
| --- | --- | --- | --- | --- | --- |
| A. MediaPipe + YAMNet（端末内） | **出ない** | 〜12 ms | **○（唯一）** | **$0** | 中 |
| B. `SpeechRecognizer`（on-device） | 保証なし | 〜1 s | × | $0 | 中〜高 |
| C. 既存 Realtime セッションに相乗り | 出る | 〜1 s | × | トークン課金 | 低（**が通話を壊す**） |
| D. 別 Realtime transcription セッション | 出る | 〜1 s | × | $0.017/分 | 中 |
| **E. チャンク書き起こし（HTTP）** | 出る | 10〜15 s | × | **$0.003/分** | **低** |

コスト出典: <https://developers.openai.com/api/docs/pricing>（Transcription models）。

**推奨: E を主線、A を余裕があれば第二段。**
15 秒の PCM チャンクを backend へ POST し、`gpt-4o-mini-transcribe` に投げてテキストだけ保存する。
無音チャンク（RMS 閾値以下）は送らない。これで帯域とコストが桁で落ちる。
A を足すと「Screaming」「Glass」等が**追加コスト $0** でカバーできる。

**やってはいけないのは C（既存の通話セッションに混ぜる）。**
Realtime の入力バッファは単一ストリームで話者分離の手段が無く、通話の書き起こしが壊れたら復旧できない。
さらに rev `00029-pfg` で直したばかりの `response.create` 競合を再燃させる。

B も避ける。公式リファレンスが「this API is not intended to be used for continuous recognition」と明言し、
`EXTRA_PREFER_OFFLINE` も segmented session も「may have no effect」と書かれている
（<https://developer.android.com/reference/android/speech/SpeechRecognizer>）。

Android プラットフォームには**第三者アプリが使える公式の音響イベント分類 API は存在しない**。
SoundTrigger 系はユーザーが選択した `VoiceInteractionService` 専用で、対象もホットワードに限られる。

---

## 7. プライバシーと規約

- **「保存しない」と「送らない」は別の約束**。E/D を採ると生音声は一時的に端末外へ出る。
  OpenAI 側の保持ポリシーは我々の管理外である。`doc/plan.md` 12 章に明記すること。
- マイク使用中インジケータ（Android 12+）は**消せない**。`WindowInsets.getPrivacyIndicatorBounds()` は
  「どこに出るか」を知るためだけの API。
- `VIEW_PERMISSION_USAGE` の intent filter を持つ Activity を用意すると、
  プライバシーダッシュボードから「なぜ聞いているのか」の説明画面を開ける。緊急アプリでは入れる価値が高い。
- 端末全体のマイクトグルが OFF のとき、アプリは**エラーではなく無音**を受け取る。
  `SensorPrivacyManager.supportsSensorToggle()` で対応可否を判定できる。
- バッテリー最適化の除外は Play 規約上「Safety app」が明示的に許容ユースケースとされている。LIFELiNK は該当する。

---

## 8. 実装の設計案（**未凍結**）と未決事項

### 8.1 スキーマ案（`doc/plan.md` へ書いてから実装すること）

`emergency_events/{id}/updates/{update_id}` に `type: "ambient"` を足す案。6a 章の既存スキーマに乗る形:

```yaml
type: ambient
author_type: system
author_uid: null
author_name: "Ambient"
text: string            # 例 "Two people are arguing loudly." / "Glass breaking (0.82)"
payload:
  provider: string      # "openai:gpt-4o-mini-transcribe" | "yamnet"
  captured_at: string   # ISO8601、チャンクの開始時刻
  confidence: number | null
created_at: timestamp
delivered_to_ai_at: timestamp | null
```

Android 側のライブフィードは `EmergencyFeed.kt` の mapper に `"ambient" -> ...` を 1 行足すだけで出せる。
`EmergencyFeedKind` に `AMBIENT` を追加し、`SYSTEM` と同じ中央寄せか、専用の淡色バブルにする。

### 8.2 AI への注入は必ず間引く

`injectEmergencyUpdate` をそのまま毎チャンク呼ぶと、**通話が実況中継になって肝心のやりとりが埋まる**。
位置更新だけでも発信中は 10 秒ごとに入っている。最低限:

- 直前の観測とテキストの意味が変わったときだけ注入する
- 最短間隔を設ける（30 秒など）
- 「悲鳴」「ガラス」等の重大ラベルだけは即時注入の例外にする

この方針自体もまだ人間に確認していない。実装前に合意を取ること。

### 8.3 未決事項（人間に聞く / 決める）

- **同意の取り方と文言**。「緊急時にマイクで周囲を聞き、テキスト化して連絡先と友人に共有する」ことへの
  明示同意をどの画面でどう取るか。Play の目立つ開示（prominent disclosure）要件に関わる。
- **「保存しない」と「送らない」は別の約束**（7 章）。チャンク送信方式を採るなら生音声は一時的に
  OpenAI へ渡る。この差を `doc/plan.md` 12 章へ明記し、同意文言にも反映するか。
- **録音を開始する条件**。SOS 発信中だけか、見守り中も常時か。常時なら電池とプライバシーの影響が桁違いになる。
- **録音を止める条件**。通話終了で止めるか、一定時間で必ず止めるか。止め忘れは最悪の事故になる。
- `AudioRecord` を `setPrivacySensitive(true)` にするか。true にすると他アプリ（アシスタント含む）に
  横取りされないが、こちらも他アプリと同時キャプチャできなくなる。緊急用途では true が妥当か。
- transcription セッションで `audio/pcmu` が通るか（通れば端末→backend も 8 kHz μ-law で統一でき帯域 1/4）。
  ただし 6 章の推奨は HTTP チャンクなので、当面は不要。
- OpenAI 側の音声データ保持ポリシーを確認する。

---

## 9. 出典

- Foreground service types / 背景起動制限 / while-in-use 除外:
  <https://developer.android.com/develop/background-work/services/fgs/service-types>,
  <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>,
  <https://developer.android.com/about/versions/14/changes/fgs-types-required>
- 同時キャプチャと通話中の規則: <https://developer.android.com/media/platform/sharing-audio-input>
- インジケータ・マイクトグル: <https://developer.android.com/training/permissions/explaining-access>
- Doze / App Standby: <https://developer.android.com/training/monitoring-device-state/doze-standby>
- MediaPipe Audio Classifier（YAMNet）:
  <https://developers.google.com/edge/mediapipe/solutions/audio/audio_classifier/android>
- OpenAI Realtime transcription: <https://developers.openai.com/api/docs/guides/realtime-transcription>
