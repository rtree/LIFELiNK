# 周辺音の収集: 可否調査と実機検証（ambient）

最終更新: 2026-09-26 / 状態: **Galaxy の手動3者通話・ロック後のマイク到達はユーザー確認済み。キャリア会議＋Twilio AI を次の実験候補とする。Twilio 参加・アプリ自動制御・ローカル録音は未検証／未実装。**

目的は「周囲の音を短いテキスト観測に変えて、通話中の AI と Discord の友人に渡す」こと。
本ファイルは**周辺音の作業を別セッションで再開するための単独の入口**であり、
「そもそも今の状態で録れるのか」「何を実機で確かめるべきか」を集約する生きた文書。
**次セッションは 0 章、とくに 0.4〜0.11 を読むこと。** 電話経由の検証記録は 0.5、
独立したローカル録音の検証記録は 5 章へ追記する。1〜9 章はローカル録音という別案の調査を保存したもの。

不変条件（先に固定）: **生音声は永続化しない。** 解析のための一時送信は許容するが、
保存するのは派生テキストだけ。`updates`・ログ・一時ファイルのどこにも音声バイトを残さない。

---

## 0. このセッションを再開する人へ

**読む順序**: `doc/ja-jp/handover.md`（プロジェクト全体の不変条件・環境・落とし穴）→ 本ファイル → 必要なら
`doc/ja-jp/tasks.md` の P2 節。**`handover.md` を読まずに環境を触らないこと。**

### 現在地（2026-09-26 時点）

| 項目 | 状態 |
| --- | --- |
| 文書更新前のコード基準点 | `d9f43d8`（main、push 済み）。今回の更新は文書だけ。最終文書 commit は Git 履歴を参照 |
| Cloud Run | 引き継ぎ上の基準は `lifelink-backend-00029-pfg`、`maxScale=1`。今回の文書更新ではクラウド状態を再照会・変更していない |
| 実機 | Samsung SM-S942Z / Android 16 / serial `RFGL41GKP0Z`、SoftBank（MCC/MNC `44020`）。SSH alias `beacon-host` 経由 |
| 手動キャリア会議 | **同意済み2番号へ順次発信→追加→統合→3者相互音声→画面OFF・ロック後もGalaxyのマイクが両者へ届く、をユーザーが確認** |
| Twilio AI の会議参加 | **未検証**。手動の2番号テストを Twilio Media Streams 成功と読み替えない |
| デフォルトダイアラー | 観測時は `com.samsung.android.dialer`。LIFELiNK の `ROLE_DIALER` / `InCallService` は未実装 |
| マニフェスト | `RECORD_AUDIO` と `FOREGROUND_SERVICE_MICROPHONE` を宣言済み |
| マイク権限の実行時許可 | 前回確認は `granted=false`。今回は再確認していない。**電話音声ルートの前提条件ではない** |
| ローカル録音コード | **ゼロ**。`AudioRecord` も microphone FGS も未実装。既存 connectedDevice FGS は別物 |
| backend | 既存の outbound 電話・Media Stream・Discord は動作済み。新しい inbound AI 電話用のイベント準備／着信受付は未実装。独立音声チャンク API と `type: "ambient"` も未実装 |

P2-08 は完了。P2-09〜11 のローカル録音案は残すが、**次は P2-12〜16 のキャリア会議実験を先行評価**する。
これは MVP の置き換え決定でも製品化完了でもない。今回行ったのは調査・読み取り専用の端末確認・文書化だけで、
アプリコード、APK、Cloud Run、Twilio 番号／webhook、Firestore スキーマを変更していない。

### 次の一手（この順で）

1. **0.4〜0.11 の結論・境界を確認**。まず候補 Twilio 番号の所有用途と既存設定を読み取り専用で確認し、復旧基準を記録する。
2. 新モードのイベント準備、着信とイベントの照合、終了状態、混合音声の表示を `doc/ja-jp/plan.md` で先に契約化する。
3. 既存 backend に隔離した実験入口を最小追加し、**Samsung 標準ダイアラーのまま AI 番号へ手動発信→相手を追加→手動統合**。
  AI↔Galaxy↔連絡先の相互音声、ロック中の音声、Discord の書き起こし／返信を同じ実イベントで確認する。
4. 成功してから `ROLE_DIALER` / 通話 UI と画面の実験 SOS を実装・検証する。
5. 最後に Settings の **Beacon SOS route（既存／実験、既定は既存）** を追加し、ロック中の自動制御を検証する。

詳細な段階ゲートと戻し方は 0.8〜0.10。最初からダイアラー・Beacon・AI を一括変更しない。
ローカル録音案へ戻る場合だけ、マイク許可→8.1 のスキーマ検討→microphone FGS 実機確認（5 章）→音声チャンク API の順に進む。

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

### 0.3 変更範囲と不変条件

- **このセッションは文書だけ。以下は次セッションの計画であって実施許可済みの設定変更一覧ではない。**
- 既存 Home の SOS、発信 API の既定動作、Safety gate、World ID の発信認可、Discord 同意・配送を保持する。
- `backend/src/voice.ts` の Realtime ライフサイクルを作り替えない。着信対応で必要な共通化は差分を分離してレビューし、
  `pending` / `responseActive` / `response.done` を介した注入待ち行列を保つ。独立録音 PCM を既存入力へ追加する案と、
  **キャリアが混合した1本の電話レッグを通常の Media Stream で受ける今回の案は別物**。
- Cloud Run は既存サービス共用、**`maxScale=1` を維持**。Firestore 共用だけではインメモリのセッション表・Discord キューは分散されない。
- `emergency_events` / `updates` は維持。追加フィールドもコードより先に `doc/ja-jp/plan.md` で凍結する。旧データの移行・削除、8a 章の状況ストア導入をしない。
- Firestore は backend Admin SDK だけが書く。UI は本物のドキュメントを購読し、モックを作らない。
- Twilio の現行発信番号 `key-twilio-from-number`（SID `PN25e30a4c7e287953ff4ebce4d33c3771`）を勝手に変更しない。
  別番号 +1629280xxxx（SID `PNbe25648b5f32bd261cb3ac9039855fd3`）は **2026-09-26 にユーザー承認で AI 着信実験用へ転用済み**。
  旧プロジェクトの Voice URL（`beacontest-backend-…/v1/twilio/incoming`）は外して空、FriendlyName は `LIFELiNK AI inbound (experimental)`。
- gcloud/firebase は必ず `--project=ethglobaltokyo2026lifelink`。World ID の app/RP/action と鍵は変更しない。
- 音声形式 `audio/pcmu`（G.711 μ-law / 8 kHz / mono）、英語の UI・AI・Discord、音声を永続化しない方針を維持する。

### 0.4 結論: 「録音権限の突破」ではなく「電話の参加者になる」

**有望なのは Android Telecom／キャリア IMS Conference による3者通話。Twilio Conference ではない。**
Galaxy から登録連絡先と Twilio AI の番号へ2本の通常電話をかけ、キャリア側で統合する。
Twilio は AI の電話口として1レッグだけ参加し、双方向 Media Streams で OpenAI Realtime へ接続する。

```text
Galaxy（利用者のマイク／受話音声）
            ↕ SIM の通常電話
    キャリア／IMS の3者会議
       ↙                 ↘
登録した連絡先           Twilio の AI 待受番号（1電話レッグ）
                              ↕ <Connect><Stream> / WSS
                        既存 Cloud Run ↔ OpenAI Realtime
                              ↕ 同一 emergency_event_id
                        Firestore updates ↔ Discord DM・返信
```

- キャリアの電話経路が Galaxy のマイクを相手へ運ぶ。アプリが通話 PCM を取得する必要はなく、
  **このルートに `AudioRecord` や microphone FGS は不要**。`RECORD_AUDIO` の事前許可を待ってから試す必要もない。
- ただし `ROLE_DIALER` は「ロック中に自由に録音できる権限」ではない。通話録音の特権 `CAPTURE_AUDIO_OUTPUT` は得られない。
- 既存方式は backend→連絡先だけで、利用者端末が電話に参加しない。新方式は利用者自身も参加するため、
  **近くの発話を AI と連絡先へ運べる可能性が増す**。通話のノイズ抑制、マイク距離、ポケット内、ミュート等により周辺音の品質は変わる。
- 非発話音の識別、遠くの音、話者分離は未確認。「3者で声が聞こえる」から「周囲のあらゆる音を正確に検知できる」へ飛躍しない。
- セキュアロック解除やキーガード回避は不要。**通話 UI をロック上へ表示することと、端末を認証なしで解除することは別**。
- 本方式では AI や連絡先の声が Galaxy 側から出ることがある。スピーカー／受話口／Bluetooth と音量・音漏れの方針は、
  「声を出せない状況」というゴールに照らして実験時に確認する。双方向会議を「無音・秘匿 SOS」とは呼ばない。

### 0.5 証拠: 確認済みと未検証を分ける

**2026-09-26、ユーザーからの実機成功報告（Agent が通話を実行・聴取した記録ではない）**:

| 手順 | 報告結果 |
| --- | --- |
| 1. 同意済みの2番号へ手動で順次発信 | 成功 |
| 2. 1本目の途中で「通話を追加」を確認 | 表示・利用できた |
| 3. 2本目応答後に「統合」を確認 | 表示・利用できた |
| 4. 統合後、3者が相互に聞こえる | 成功 |
| 5. 画面 OFF・ロック後も Galaxy のマイクが両者へ届く | 成功 |

この報告により **「この端末・現在の SIM／キャリアでの手動3者会議は未確認」という以前の留保は解消**。
試験の正確な時刻・通話時間・画面 OFF 継続時間・充電状態・音声ルート・相手側機種は報告されていない。
番号／個人情報を文書へ転載せず、次の試験で条件を補足する。

事前の読み取り専用 ADB 観測:
- `model=SM-S942Z`、`android=16`、`carrier=SoftBank,`、`mccmnc=44020,`、既定電話アプリ `com.samsung.android.dialer`。
- dumpsys 内に `allow_hold_in_ims_call = true`、`ims_conference_size_limit_int = 5` などがあった。
  `carrier_volte_available_bool` は true/false が混在し、複数設定・履歴のどれが実効値かは断定できなかった。
- `CALL_SIMULTANEOUS_DISABLED_SAME_ACCOUNT` などの表示だけで「保留・追加・会議が不可能」と判定しない。
  **設定ダンプの推測より、上記の実際の統合成功を優先**する。IMS 内部方式や最大5者の実動作まで実証したわけではない。

**2026-09-26 19:12〜19:17 JST、E2/E3 実機結果（rev `00030-j5p`、APK `a965dfe`、標準 Samsung dialer で手動）**:

| event | 内容 | 結果 |
| --- | --- | --- |
| `792bfc59-…` | Conference SOS → AI 番号へ発信 → コード照合 → Galaxy↔AI の2者 | コード自動送信（`tel:…,,code`）で join 成功。AI 初回発話、Galaxy の声の transcript、Discord 中継。AI 切断で `completed`。受信 2,419 / 出力 154 フレーム |
| `de1742ee-…` | 同上＋「通話を追加」で連絡先→「統合」 | **3者通話成立（ユーザー確認）**。連絡先の日本語の問いかけに AI が日本語で状況説明。10:17:41 `completed` |

観測した問題（次の改善対象）:
- **保留中の自動音声に AI が反応**: 連絡先を追加する間 AI レッグは保留になり、「ただいま保留中です／しばらくお待ちください」が
  `transcript_contact` に入り、AI が相手がいるものとして話し続けた（誰にも聞こえていない）。
- **混合音声の話者が分からない**: 連絡先の発言も Galaxy 本人の発言も同じ `transcript_contact`。AI は連絡先の言う「荒木さん」を本人名として受け取った。
- **根拠の弱い環境音の断定**: AI が「微かに足音のような音が聞こえました」と発話。実際の音かは未確認。誤報のリスク。
- transcription は `language: "en"` 指定だが日本語の発言も日本語で書き起こされた。AI も日本語で応答した（英語固定の方針とずれるが、相手の言語に合わせたこと自体は実害なし）。

**2026-09-26 19:30〜20:40 JST の改善と確認（ユーザー確認）**:
- rev `00031-dfn` の会議用 AI 指示後: 保留中に AI が黙る・話者を推測と言う・画面 OFF でも継続、を確認。
- **E4（自作ダイアラー）成立**: LIFELiNK を既定の電話アプリにし、画面の SOS から AI→連絡先→自動統合。
  IMS の統合は元の 2 本を `IMS_MERGED_SUCCESSFULLY` で切り、新しい会議通話 1 本に置き換える（親子関係は付かない）。
  応答直後は `conferenceableCalls` が空で `conference()` が空振りするため、1 秒ごとに再試行して約 3 秒で成立。
- 発信元端末はマイク ON・受話口・通話音量最小・通話画面を点けない設定で「ほとんど聞こえない」を確認。
- 残り: 60 秒呼び出し打ち切り、LIFELiNK 経由の通常着信、Beacon からの起動（P2-16）。

未検証:
- ~~Twilio AI が3人目として参加し、Galaxy・連絡先の両方を聞けること／両者が AI を聞けること。~~ → 下記 E2/E3 で成立。
- LIFELiNK が役割を取得し、`placeCall()`・`conference()` を第三者ダイアラーとして利用できること。
- 画面 OFF・ロック中の Beacon 起点の発信・追加・統合・着信応答。**通話確立後のロック継続成功とは別の試験**。
- 会議音声からの書き起こし、Discord 中継・返信注入、保留音やエコーへの AI の反応、通話終了のイベント同期。
- 通話音以外の周辺音分類と長時間／通信断の信頼性。

### 0.6 調査結果（10分担の論点を統合、誤った結論は採用しない）

| 論点 | 結論・位置付け |
| --- | --- |
| Default dialer の資格 | 通常の第三者アプリも要件を満たし、ユーザーが選択すれば `ROLE_DIALER` を取得できる。SOS 専用の飾り画面だけでは不足 |
| キャリア会議 API | `Call.conference(otherCall)` を使う。相手の `conferenceableCalls`、状態、キャリア能力に従う。API 呼出成功と統合成功を分ける |
| ロック中の発信 | 管理対象の SIM 電話を Telecom で制御する案。BAL（背景 Activity 起動）・通話状態・OEM 挙動は実機検証が必要。UI の自動タップに依存しない |
| 着信を Beacon で応答 | `InCallService` の ringing `Call` に `answer()` する別案はある。誰からのどの着信か、同意・期限・重複排除が必要 |
| 通話 PCM のローカル取得 | 通常の dialer 権限では不可。**ローカル取得不能と、電話相手である Twilio に音が届かないことを混同しない** |
| Twilio 音声 | キャリア会議の参加レッグ＋双方向 Stream が目的。Twilio 側に `<Conference>` を作る必要はない |
| Self-managed VoIP | `ConnectionService` / Core-Telecom は通話統合・UI の仕組みであって、SIM 電話との任意の音声混合を自動で実現しない |
| OEM／キャリア | この Galaxy＋SoftBank の手動会議はユーザー確認済み。他 SIM、別機種、第三者 dialer API へは未一般化 |
| Play・同意 | 正当な電話アプリとしての責務、着信通知、権限、開示を満たす必要がある。公開審査は未評価。「必ず拒否」「必ず通過」と断定しない |
| 代替構成 | ローカル microphone FGS、通知操作、外部録音機、着信応答などは下記の代替。今回の主実験を置き換えない |

**Android の実装候補 API（まだコード無し）**:

- `RoleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)` で事前にユーザーへ選択を求める。SOS 最中に初回設定を始めない。
- `Intent.ACTION_DIAL`（ダイヤル入力）を処理し、incoming / ongoing の `InCallService` UI を提供する。
  `android.permission.BIND_INCALL_SERVICE` で保護した exported service、`android.telecom.InCallService` の intent-filter、
  UI／ringing の metadata と実装の整合が必要。null bind や資格を失う構成は標準 dialer へのフォールバック／役割喪失を招く。
- SIM 既存の `TelephonyConnectionService` による **managed call** を使う。自作の self-managed `PhoneAccount` に置き換えない。
- `TelecomManager.placeCall(Uri, Bundle)` と `CALL_PHONE`。利用 SIM／PhoneAccount 選択が必要なら事前設定し、確認ダイアログを無視しない。
- `InCallService.canAddCall()` / `onCanAddCallChanged()` → 追加可否を確認。
  `Call.getConferenceableCalls()` / `onConferenceableCallsChanged()` → 実際に統合可能な相手を確認し `Call.conference(otherCall)`。
  state・details・parent/children の callback で結果を観測する。固定秒数を待つだけの実装や `void` 戻り値で完了扱いにしない。
- `TelecomManager.startConference(List<Uri>, Bundle)`（API 31+）は ad-hoc 会議要求だが、キャリア対応は未確認。
  `Call.addConferenceParticipants()` も対応 capability が条件。**最初からこれらの一発 API に依存しない**。
- `Call.answer(VideoProfile.STATE_AUDIO_ONLY)` は着信応答用。`TelecomManager.acceptRingingCall()` は API 29 以降 deprecated。
- API 34+ の音声出力切替は `requestCallEndpointChange()` と available/current endpoint callbacks を検討。
  `setAudioRoute()` の旧 API と minSdk 向け分岐は実装時に整理する。
- compile/targetSdk は 36、minSdk は 26。最新 Web 文書の API 37 追加機能をそのまま使わない。
- 物理 wearable と `CompanionDeviceManager` で association し、`MANAGE_ONGOING_CALLS`＋`InCallService` を使う別の公式経路もある。
  **広告だけのこの Beacon が条件を満たすとは確認していない**。dialer 役割不要の万能策として採用しない。

**Twilio の制約（視点に注意）**:

- `<Connect><Stream>` は双方向、1 Call あたり1本。受信は **Twilio から見た `inbound_track` のみ**。
  キャリアから届く音に Galaxy と連絡先が混合されていれば、それが入力になる。どちらか一方専用の track ではない。
- AI 出力は同じ電話レッグへ返す。Galaxy／連絡先への配信はキャリア会議が担う。
- `<Connect><Stream>` は後続 TwiML をブロックする。後ろへ `<Dial><Conference>` を追記して同時動作させる案は不適切で、今回そもそも Twilio 会議を使わない。
- 双方向 Stream は TwiML で開始する。Stream REST リソースによる開始とは違う。WSS と署名検証が必要。
- Stream URL には query string を付けず、`<Parameter>` で event 相関情報を渡す。HTTP status callback URL の query と混同しない。
- 双方向 Stream の DTMF は Twilio→media server 方向のみ。認可用の番号／コード入力を使うなら、その受付手段を先に設計する。
- 音声形式は現行の `audio/pcmu` のまま。話者別の音声分離や遠距離音を保証しない。録音機能は有効化しない。

**代替案と今回の扱い**:

1. **別案: Beacon→backend→Twilio が利用者の携帯番号へ電話→次の操作で応答**。
   これだけで利用者↔AI の音声経路は作れる可能性があり、Discord は同じイベントで継続できる。
   ただし連絡先を電話参加させるには、さらにキャリア側の追加／統合などが必要。
   未登録の着信や無関係な電話に自動応答してはならず、現在の Beacon の「同一操作の再押下を区別できない」制約（0.9）もある。
   今回の主実験では採らず、発信自動化が詰まった場合の再評価候補とする。
2. **アプリ前面から microphone FGS を開始してロック後も継続**: 独立録音の本命代替。1〜9 章参照、実機未検証。
3. **ロック通知のユーザー操作から開始**: while-in-use の文書化された例外だが追加タップが必要。通知を出すだけでは足りない。
4. **ロック解除／通知表示／省電力除外**: 表示、Activity 起動、FGS 起動、マイク利用はそれぞれ別の条件。
   権限を多く取る、battery optimization を外す、overlay や FCM を使うだけでマイク制限全体が消えるとは考えない。
5. **外部マイク・独立録音機／SD保存**: 別ハードウェアなら Android と異なる制約で録れるが、転送・給電・同意・機器調達が別問題。
   SD への音声保存は現行の「永続化しない」と衝突する。特定機種や即時転送経路の実証はなく、今回は採用しない。
6. **Accessibility／root／OEM 特権**: 消費者向けアプリの通常権限設計の代替にしない。標準電話 UI の自動タップやロック認証の回避を実装しない。

### 0.7 ユーザーの A / B / 画面案を最小変更に落とす

**A. 使っていない Twilio 番号を AI 待受にする案**:
- 同一 Twilio アカウント・既存 Cloud Run を共用できる。別 Cloud Run、別 DB、別 Discord Bot は不要という実験方針。
- 未使用候補の用途／所有者、voice capability、Voice URL/method、fallback、status callback、TwiML App／Studio 等の紐付けを先に確認する。
  旧プロジェクト用番号の転用は明示確認が必要。適切な候補がなければ、番号購入を勝手に行わずユーザー判断へ戻す。
- 現行発信番号の **着信設定だけ**を共用する可能性もあるが、inbound 用途との衝突を確認してから判断する。
  最小の番号数と最小の変更リスクは同じではない。現時点では使用する番号を確定していない。
- 既存 outbound の `TWILIO_FROM_NUMBER` を実験番号へ置換しない。必要な追加設定・番号は Secret Manager の方針で管理する。

**B. Firestore / Discord / backend 共用、着信 webhook のみ分離する案**:
- 「A の番号からの戻り」は **Galaxy→A の番号への着信を Twilio が HTTP webhook で backend に通知する**こと。
  webhook が返す TwiML で Media Stream を既存 WSS へ接続する。AI が backend へ電話をかけ直す意味ではない。
- ただし **webhook 1本の追加だけでは不足**。現在の `POST /v1/emergency-events` はイベントを作ると Discord を起動し、
  `placeEmergencyCall()` で直ちに登録連絡先へ outbound する。実験モードには「イベント／DM を準備するが outbound を行わない」明示的な経路が必要。
  わざと Twilio 発信を失敗させてイベントだけ作る方法は禁止（`failed` になり、意図しない発信や DM を起こす）。
- 既存 bridge は active state（`accepted` / `dialing` / `in_progress`）だけを受け、未設定なら `twilio_call_sid` を bind、
  既存値と異なれば拒否する。status callback も SID に依存する。今回は **AI inbound の1 CallSid** をどう bind するか設計する。
  Galaxy→連絡先の電話はキャリア管理なので、その状態を Twilio callback が報告してくれるわけではない。
- 通話は同じ `emergency_event_id` に紐付け、位置／メモ／Discord DM／返信／transcript を共用する。
  incoming だから Firebase／World ID の Safety gate を迂回してよいわけではない。
- 署名検証は「Twilio から届いた」証明であって、発信者を LIFELiNK の owner と認可する証明ではない。
  **Caller ID の一致だけ、または「最新のイベント」検索だけで機微な状況を読み上げない**。
- 次セッションで、認証済み API による pending event＋短い有効期限＋一回限りの照合手段を設計する。
  PSTN の `tel:` 発信では任意の event ID が自動で着信 webhook に届くわけではない。
  短命のペアリングコード入力などを候補にし、必要なら手動入力から検証する。方式・フィールド・API はまだ未凍結。
  コード原文をログへ残さず、回数制限・期限・一意消費・再送冪等性・不明着信の拒否を契約に含める。
- 認可確認・CallSid bind を済ませてから Stream に渡す。Twilio HTTP と WSS の `X-Twilio-Signature`、公開 URL、
  `To`、想定 account、event と SID の整合を検証する。新入口は既定 OFF／テスト対象を限定する案。
- 同じ AI レッグに対する二重 Stream／再接続時の Realtime session 重複も抑える。いきなりセッション表を分散化しない。
- `transcript_contact` は従来「電話相手」だったが、会議では **Galaxy＋連絡先の混合音声**になり得る。
  既存フィールドを改名せず、実験モードでの意味・UI/Discord のラベル・AI instructions を先に定義する。
  「登録連絡先が言った」「利用者が言った」と根拠なく断定しない。

**C. アプリに約2画面＋α、実験 SOS と Settings の切替**:
- 画面案1: **Dial / Call back**。`ACTION_DIAL` の番号入力、発信／折返し、role と SIM 設定の案内、独立した `Experimental SOS`。
  連絡先の番号は backend が owner の登録先を解決する現行契約を踏まえ、端末発信に必要な安全な受渡しを追加設計する。
  履歴表示を作るなら実データと必要な通話履歴権限を別途評価し、ダミー履歴を作らない。
- 画面案2: **Incoming / In call**。着信・応答・拒否、進行中・保留、追加・統合・終了、ミュート、音声ルート、DTMF などを状態に応じて扱う。
- 「2画面」は見せ方の概算であり実装量の上限ではない。role 保持中は SOS 以外も含め Telecom が渡す通話を扱う責務がある。
  ロック上の着信通知、通知からの応答、通常着信、役割喪失、プロセス再生成の最小対応が必要。
  公的緊急番号への自動発信は非目標。既定 dialer としてのシステム緊急発信の扱いを妨げない。
- Settings に **Beacon SOS route: Existing / Carrier conference (experimental)** を追加する案。
  既定は Existing、明示 opt-in。これは旧新ルートの選択であって、ボタン検出・World ID・Safety gate を無効化するトグルではない。
  旧 Home SOS は温存し、画面の実験 SOS は選択状態にかかわらず実験だと分かる表示にする。
- 設定はイベント開始時に確定し、進行中に変えても通話を途中で別方式へ切り替えない。
  端末ローカル設定から始める案だが、再起動時の読み込みと backend の event mode 契約を明示する。アカウント同期は今回必須にしない。

### 0.8 推奨する実験順序（各段階で止めて戻せる）

| 段階 | 実施内容 | 次へ進む条件／戻し方 |
| --- | --- | --- |
| E0 基準点 | `d9f43d8` と `mvp-0.1`、実験前の APK・Cloud Run revision/image・env/secret 参照・番号設定を確認。必要な非秘密メタデータを記録 | 使用番号と同意、復旧対象を確定。まだ設定を変えない |
| E1 契約と backend 最小追加 | plan にモード・pending event・照合・SID・終了契約を先に凍結。隔離した実験入口と着信 TwiML を追加し、番号の inbound hook のみ設定 | 既存 outbound＋Discord の回帰確認、不明着信拒否、HTTP 再送で二重イベント／DM／AI接続なし。失敗なら実験入口停止＋番号設定復元 |
| E2 手動 AI 単独 | 認証済み実イベントを準備し、標準 Samsung dialer で AI 番号へ手動発信・照合。Galaxy↔AI を確認 | 実 CallSid・StreamSid とイベントが一致、双方向音声・実 transcript・Discord返信が AI へ届く。相手を追加せず原因を切り分ける |
| E3 手動3者＋ロック | 同意済み連絡先を追加し、応答後に手動統合。Galaxy/連絡先/AI の各方向と画面 OFF を確認 | 0.10 の音声・Discord・終了確認を満たす。失敗なら通常電話を全て終了し既存ルートへ戻る |
| E4 画面から自動化 | role と最低限の実通話 UI を実装し、画面の実験 SOS から発信→追加→統合 | callback で統合を確認、通常着信／拒否／終了と役割復旧も実機確認。まだ Beacon 既定は変えない |
| E5 Beacon opt-in | Settings の旧／実験切替を追加し、ロック状態から開始する試験 | 同じ広告で再発信しない、無関係な電話を操作しない、タイムアウト／中止／復旧が機能。失敗なら Existing に戻す |

順次発信は **AI 番号を先に→接続・照合→連絡先を追加→統合**を最初の仮説とする。
この順番は成功確認済みの最適解ではない。AI レッグが保留になる間の音声・保留音・セッション期限・初回発話を確認する。
連絡先が入る前に初回説明を AI が話し切る可能性があるため、統合後の再説明の要否も検討する。
逆順（連絡先→AI）が良ければ観測理由とともに更新する。無期限リトライや固定待ち時間での自動連打はしない。

### 0.9 安全・後戻り・Beacon 固有の落とし穴

- 実験中も既存ルートを残す。ただし **途中失敗時の自動フォールバックは初期実験では OFF**を推奨。
  端末から連絡先へつながったまま backend も同じ人へ電話すると二重発報になる。
  まず実験が所有する電話を終了し、状態を確認してから、人間の操作で既存 SOS を開始する。
- 「所有する電話」は実験開始時に追跡した `Call`／event に限定。別の通常通話を勝手に切る・統合する・応答する実装は禁止。
  開始前に無関係な通話があれば実験を開始しない案を既定とする。
- caller が切った、相手不応答、AI leg 切断、キャリア統合拒否、ネット断、role 喪失でそれぞれ有限の終了手順を定義する。
  Twilio 側の `completed` は「3者が助け合えた」証明ではない。AI レッグ終了と carrier 会議全体終了は区別する。
- timeout の秒数・全体上限・1ユーザー同時イベント上限は契約化時に決める。CallSid が不明なまま追加発信で埋め合わせない。
- **＋Beacon は長押し bit14（`0x4000`）を実測済み**。一般的な iBeacon 全てに長押しがあるわけではないが、
  この機種で「長押し識別は未確認」と書き戻さない。現行仕様は短押し／長押し・ボタン1/2の全4パターンを候補にする（`doc/ja-jp/beacon-verification.md`）。
- 広告送信60秒、途切れ判定75秒。**同じボタン・同じ押し方の再押下は広告上で区別できない**。
  パケット数を押下回数にしてはならない。別ボタン／短長切替は別状態だが、応答や終了に割り当てるなら別の状態機械と実測が必要。
  したがって「もう一回押せば着信に応答」は現状のままでは保証できない。ルート切替だけのために受信閾値を短くしない。
- まず合成 trigger／既存 dry-run で「選択ルートのログだけ、電話ゼロ」を確認し、その後同意済み番号で実通話する。
  実データ UI のための架空イベントを作る意味ではない。APK 更新後は Beacon 見守りの再開と設定保持を確認する。
- 戻す順序: **実験開始を停止→実験所有の電話／Stream を終了→Beacon を Existing→必要なら既定電話アプリを Samsung に戻す
  →変更した番号設定を復元→必要なら実験前 backend revision／APK を復旧→既存 SOS＋Discord を1回確認**。
  ロールバック対象は実験差分のみ。他作業の更新を消す `reset --hard` や既存 Firestore 削除はしない。
- 同一 Cloud Run なので branch や feature flag だけで障害が完全隔離されるわけではない。デプロイは通話の無い時間に行い、
  既存 env/secret と `maxScale=1` を保持する。`mvp-0.1` は最後の退避基準で、通常はより新しい実験直前 revision に戻す。
- 通話参加者には AI、利用者音声の電話送信、書き起こしの Discord 共有を事前説明して同意を得る。
  キャリア・Twilio・OpenAI を音声が通る。「保存しない」から「端末外へ出ない」とは説明しない。
  音声を Twilio recording、ファイル、Firestore、ログへ保存しない。外部サービス側の保持条件は別途確認する。

### 0.10 次の実機検証チェックリストと記録様式

電話は同意済みのテスト番号だけ。公的緊急番号には発信しない。

- [ ] E2: 認証済み実験イベントにだけ着信を bind。不明／期限切れ／コード不一致／再利用を拒否し、機微情報を話さない。
- [ ] E2: Galaxy の発話→AI 応答→Galaxy の受聴。Stream open だけで合格にしない。
- [ ] E3: Galaxy→連絡先、連絡先→Galaxy、Galaxy→AI、連絡先→AI、AI→Galaxy、AI→連絡先を別々に確認。
- [ ] E3: 画面 OFF・ロック後も上記音声が続く。ミュートと出力 endpoint を記録。ポケット内／距離は後続の品質試験として分離。
- [ ] E3: 混合音声の transcript を特定個人の発言と誤表示しない。聞き取れない音を断定しない。
- [ ] E3: 同一イベントの Discord DM に transcript が届き、同意済み友人の返信が保存され AI に伝わる。AI 発話中の返信でも応答競合を起こさない。
- [ ] E3: 保留中・統合直後の AI の振る舞い、エコー・二重読み上げ、初回説明の聞き逃しを確認。
- [ ] E3/E4: 切断時に端末・Twilio・event 状態が整合し、次の SOS が始められる。残った通話が勝手に継続しない。
- [ ] E4: role 取得／拒否／返却、通常着信応答・拒否・折返し、画面実験 SOS、統合不可時の停止を確認。
- [ ] E5: ロック中の Beacon 開始→自動発信／統合を確認。重複広告・再押下・途中設定変更で二重発信しない。
- [ ] 回帰: Existing を選べば従来の backend→連絡先＋Discord が同じように動く。

記録するもの: 日時、実験段階、コード commit / APK、Cloud Run revision、端末・SIM／キャリア、画面／ロック／充電状態、
audio endpoint、発信順、保留・応答・統合・終了の時刻と callback、event ID / CallSid / StreamSid、
方向ごとの音声結果、Discord 配送・返信結果、残課題。**番号全文・秘密・音声 payload は記録しない**。
Twilio のフレーム数は送受信の補助証拠であり、キャリア先の人が聞けた証明ではない。

### 0.11 次セッションへの依頼文と未決事項

> `doc/ja-jp/handover.md` → `doc/ja-jp/ambient-verification.md` 0章 → `doc/ja-jp/tasks.md` P2-12〜16 を読む。
> Galaxy＋SoftBank の手動3者通話とロック後マイク到達はユーザー確認済み。Twilio AI 参加は未検証。
> 既存 Cloud Run / Firestore / Discord と通常 SOS を温存し、まず番号用途の read-only 確認と inbound 実験の契約化をする。
> 最初の縦断試験は標準 Samsung dialer の手動会議。成功後にだけ自作 dialer UI→画面 SOS→Beacon opt-in を進める。
> Twilio Conference、録音特権、8a 状況ストア移行は使わない。コードはこの文書化セッションでは未変更。

人間の確認が必要: 使用してよい待受番号、テスト相手と時間帯、音声出力／音漏れの許容、role 変更の影響と同意。
実装前に決める契約: inbound event 照合方式、モード／SID／状態の追加フィールド、端末への登録先番号の受渡し、
AI 初回発話タイミング、transcript の混合話者表現、timeout／終了／再試行。
これらは**今回凍結していない**。マイク録音案の `updates.type: ambient` を電話ルートの必須依存にしない。

公式参照（今回の電話調査で確認した仕様の入口）:
- Default phone app / InCallService: <https://developer.android.com/develop/connectivity/telecom/dialer-app>
- `InCallService`: <https://developer.android.com/reference/android/telecom/InCallService>
- `Call`（conferenceable calls / conference / answer）: <https://developer.android.com/reference/android/telecom/Call>
- `TelecomManager`（placeCall / startConference）: <https://developer.android.com/reference/android/telecom/TelecomManager>
- 通話と録音の競合: <https://developer.android.com/media/platform/sharing-audio-input>
- `CAPTURE_AUDIO_OUTPUT`: <https://developer.android.com/reference/android/Manifest.permission#CAPTURE_AUDIO_OUTPUT>
- 音源の権限制約: <https://developer.android.com/reference/android/media/MediaRecorder.AudioSource>
- 背景 Activity 起動: <https://developer.android.com/guide/components/activities/background-starts>
- Twilio Media Streams: <https://www.twilio.com/docs/voice/media-streams>
- TwiML Stream: <https://www.twilio.com/docs/voice/twiml/stream>
- Media Streams messages: <https://www.twilio.com/docs/voice/media-streams/websocket-messages>
- Twilio webhook 検証: <https://www.twilio.com/docs/usage/security#validating-requests>
- 比較用 Twilio Conference（今回は不使用）: <https://www.twilio.com/docs/voice/twiml/conference>

---

## 1. 別案: ローカル録音についての結論

以下は電話経由の 0 章とは別経路。過去の「権限も未宣言」「端末が通話すると周辺音を伝えられない」という記述は訂正した。

| 問い | 答え |
| --- | --- |
| 今の APK で音を録れるか | **録れない。** 権限宣言と Settings の許可導線はあるが、microphone FGS と録音コードが無い（2 章） |
| 実装すれば画面 ON・アプリ前面で録れるか | **録れる。** 通常の `RECORD_AUDIO` + `microphone` FGS で足りる |
| 画面 OFF・ロック中も録り続けられるか | **FGS が正しく起動済みなら、禁止する規定は無い。** ただし保証も無く、Samsung の省電力が最大のリスク。実機検証が必要（5 章） |
| ロック中に Beacon 押下で**録音を開始**できるか | 通常の背景起動は while-in-use 制約に当たり、API 34+ では `SecurityException` の対象。通知操作等の例外は条件と実機確認が必要（3 章） |
| Twilio 通話中だから録れない、のでは？ | 既存 outbound 方式では端末は電話不参加。0 章の会議方式では電話自体がマイク音を運ぶが、別のローカル録音の可否は別問題（4 章） |
| マイク使用中インジケータを消せるか | 通常アプリが消すための公開 API はない。ユーザーへの表示・開示を前提にする |

---

## 2. ローカル録音の現在地（2026-09-26、P2-08 後へ訂正）

`android/app/src/main/AndroidManifest.xml` に宣言されている権限は
`INTERNET` / `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION` / `BLUETOOTH`(maxSdk 30) /
`BLUETOOTH_ADMIN`(maxSdk 30) / `BLUETOOTH_SCAN` / `FOREGROUND_SERVICE` /
`FOREGROUND_SERVICE_CONNECTED_DEVICE` / `POST_NOTIFICATIONS` /
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` に加え、P2-08 で `RECORD_AUDIO` /
`FOREGROUND_SERVICE_MICROPHONE` を追加済み。

| 必要なもの | 現状 |
| --- | --- |
| `RECORD_AUDIO` | **宣言済み**。実行時許可の前回観測は未許可 |
| `FOREGROUND_SERVICE_MICROPHONE` | **宣言済み** |
| `foregroundServiceType="microphone"` | **無い**（`BeaconMonitorService` は `connectedDevice` 単独） |
| `AudioRecord` / `MediaRecorder` / `SpeechRecognizer` | **コードに一切存在しない** |
| マイク権限の実行時リクエスト | **Settings の Listening / Allow microphone に実装済み** |

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

### while-in-use 制約の除外（ユーザー操作を伴う候補）

公式に列挙されている除外のうち、今回検討するのは次の 2 つ（例外全体の網羅ではない）:

- **ユーザーが通知を操作して起動する場合**（notification / notification action）
- ユーザーがアプリウィジェットを操作して起動する場合

→ **「ロック画面に通知を出しておけば可能か」という問いの答えは「はい、ただしユーザーのタップが要る」。**
通知を出しておくだけでは足りず、`Start listening` のようなアクションを**押してもらう**必要がある。
full-screen intent は通話・アラーム用途や許可状態など別の制約を持つため、一般の録音開始を正当化する万能策にはしない。

したがって経路ごとの可否:

| 経路 | 録音開始できるか |
| --- | --- |
| アプリを開いて画面の SOS を押す | **できる**（アプリが前面＝ while-in-use を満たす） |
| ロック中に Beacon ボタン押下 → 自動で録音開始 | **できない**（`SecurityException`） |
| ロック中に Beacon ボタン押下 → 通知を出し、ユーザーがアクションをタップ | **できる**（公式の除外） |

`systemExempted` FGS にはシステム連携等の資格条件があり、`ROLE_EMERGENCY` はその一例。
LIFELiNK が該当すると確認した事実はなく、宣言だけでマイク制約を回避できるものとして使わない。

---

## 4. ローカル録音と、電話が音声を運ぶことは別

`AudioManager.getMode()` が `MODE_IN_CALL` / `MODE_IN_COMMUNICATION` のとき、
「The call always receives audio. The app can capture audio if it is an accessibility service.」であり、
通常アプリは無音になる（<https://developer.android.com/media/platform/sharing-audio-input>）。

**しかし LIFELiNK の設計では、Twilio は backend から緊急連絡先へ発信しており、
ユーザーの Android 端末は通話に参加していない。**端末にテレフォニー通話は存在しないので、この規定に当たらない。

**0 章の新実験では端末が電話に参加する**。この場合、通常アプリの独立した `AudioRecord` は無音化され得るが、
電話そのものには音声が届く。キャリア会議の Twilio レッグを通じて AI が聞くことは別経路であり、
「端末が通話中だから周囲の声を AI へ運ぶことも原理的に不可能」とする以前の表現は誤り。

残る競合は「通常アプリ同士は同時キャプチャできない（前面 UI のあるアプリが勝つ）」という規則。
`AudioRecord.registerAudioRecordingCallback()` を張って `isClientSilenced()` を監視しないと、
**無音を掴んだまま「動いているつもり」になる**。実装時に必須。

---

## 5. ローカル録音を実機で確かめること（未実施）

Samsung SM-S942Z / Android 16 / serial `RFGL41GKP0Z`。手順は `doc/ja-jp/handover.md` 3 章。

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
Doze・省電力によるネットワーク制限や OEM 挙動もあるため、取得と送信は別々に観測する。
使うコマンドは 0.2 にまとめてある。観測値は日時・画面状態・ロック状態・充電の有無とセットで下に追記すること。

### 観測ログ（ここに追記していく）

ローカル録音はまだ計測していない。手動3者通話のユーザー成功報告は **0.5** に記録済み。

---

## 6. 独立したローカル録音を採る場合の音→テキスト比較

以下は当初の録音案の比較。料金・遅延は調査時の目安であり採用時に再確認する。
キャリア会議では Twilio から届く1本の通常音声を既存 Realtime が扱うため、この別チャンク方式は必須ではない。

| 方式 | 生音声が端末外へ出るか | 遅延 | 非発話音（悲鳴・ガラス） | コスト | 実装コスト |
| --- | --- | --- | --- | --- | --- |
| A. MediaPipe + YAMNet（端末内） | **出ない** | 〜12 ms | **○（唯一）** | **$0** | 中 |
| B. `SpeechRecognizer`（on-device） | 保証なし | 〜1 s | × | $0 | 中〜高 |
| C. 既存 Realtime セッションに相乗り | 出る | 〜1 s | × | トークン課金 | 低（**が通話を壊す**） |
| D. 別 Realtime transcription セッション | 出る | 〜1 s | × | $0.017/分 | 中 |
| **E. チャンク書き起こし（HTTP）** | 出る | 10〜15 s | × | **$0.003/分** | **低** |

コスト出典: <https://developers.openai.com/api/docs/pricing>（Transcription models）。

**ローカル録音案での推奨: E を先行、A を余裕があれば第二段。現在の実験優先順位は 0 章。**
15 秒の PCM チャンクを backend へ POST し、`gpt-4o-mini-transcribe` に投げてテキストだけ保存する。
無音チャンク（RMS 閾値以下）は送らない。これで帯域とコストが桁で落ちる。
A を足すと「Screaming」「Glass」等が**追加コスト $0** でカバーできる。

**避けるのは C（別途取得した PCM を既存の通話入力へ無秩序に混ぜる）。**
Realtime の入力バッファは単一ストリームで話者分離の手段が無く、通話の書き起こしが壊れたら復旧できない。
注入の実装を不用意に変更すると rev `00029-pfg` の `response.create` 競合対策も損ね得る。
今回のキャリア会議レッグの受信を C と混同しない。ただし混合音声の話者識別限界は残る。

B も避ける。公式リファレンスが「this API is not intended to be used for continuous recognition」と明言し、
`EXTRA_PREFER_OFFLINE` も segmented session も「may have no effect」と書かれている
（<https://developer.android.com/reference/android/speech/SpeechRecognizer>）。

Android プラットフォームには**第三者アプリが使える公式の音響イベント分類 API は存在しない**。
SoundTrigger 系はユーザーが選択した `VoiceInteractionService` 専用で、対象もホットワードに限られる。

---

## 7. プライバシーと規約

- **「保存しない」と「送らない」は別の約束**。E/D を採ると生音声は一時的に端末外へ出る。
  OpenAI 側の保持ポリシーは我々の管理外である。`doc/ja-jp/plan.md` 12 章に明記すること。
- マイク使用中インジケータ（Android 12+）は**消せない**。`WindowInsets.getPrivacyIndicatorBounds()` は
  「どこに出るか」を知るためだけの API。
- `VIEW_PERMISSION_USAGE` の intent filter を持つ Activity を用意すると、
  プライバシーダッシュボードから「なぜ聞いているのか」の説明画面を開ける。緊急アプリでは入れる価値が高い。
- 端末全体のマイクトグルが OFF のとき、アプリは**エラーではなく無音**を受け取る。
  `SensorPrivacyManager.supportsSensorToggle()` で対応可否を判定できる。
- バッテリー最適化の除外は Play 規約上「Safety app」が明示的に許容ユースケースとされている。LIFELiNK は該当する。

---

## 8. 実装の設計案（**未凍結**）と未決事項

### 8.1 スキーマ案（`doc/ja-jp/plan.md` へ書いてから実装すること）

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
  OpenAI へ渡る。この差を `doc/ja-jp/plan.md` 12 章へ明記し、同意文言にも反映するか。
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
