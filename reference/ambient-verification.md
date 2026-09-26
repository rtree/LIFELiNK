# 周辺音の収集: 可否調査と実機検証（ambient）

最終更新: 2026-09-26 / 状態: **調査完了・実装ゼロ・実機未検証**

目的は「周囲の音を短いテキスト観測に変えて、通話中の AI と Discord の友人に渡す」こと。
本ファイルは「そもそも今の状態で録れるのか」「何を実機で確かめるべきか」を集約する生きた文書。
検証するたびに 5 章へ観測値を追記すること。

不変条件（先に固定）: **生音声は永続化しない。** 解析のための一時送信は許容するが、
保存するのは派生テキストだけ。`updates`・ログ・一時ファイルのどこにも音声バイトを残さない。

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

## 8. 出典

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
