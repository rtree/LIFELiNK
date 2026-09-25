# LIFELiNK Tasks

最終更新: 2026-09-25

状態: `TODO` / `IN PROGRESS` / `BLOCKED` / `DONE`

## P0: MVP 主線

| ID | 状態 | タスク | 依存 | 完了条件 |
| --- | --- | --- | --- | --- |
| P0-01 | DONE | Android package name、GCP project、region、ログイン方式を決定する | 人間の意思決定 | 決定を `doc/plan.md` に反映済み |
| P0-02 | DONE | GCP/Firebase project と billing を利用可能にする | P0-01、人間の課金設定 | Agent が対象 project を CLI で参照可能 |
| P0-03 | DONE | Firestore、Cloud Run、Secret Manager、service account、必要 API を構成する | P0-02 | 最小権限の実行環境と空の backend がデプロイ済み |
| P0-04 | DONE | Firebase Android app、Authentication、`google-services.json` を構成する | P0-02 | 2026-09-26: Samsung物理端末（Android 16/API 36）でCredential Manager→Firebase Googleログイン成功、UID発行、再起動後のログイン状態復元を確認 |
| P0-05 | IN PROGRESS | Android の位置取得・保存 UI と backend API を実装する | P0-03、P0-04 | Androidと認証済み保存APIは実装済み。残りは実端末の座標・精度・時刻・住所を再取得する確認 |
| P0-06 | IN PROGRESS | 緊急連絡先登録と `contact_id` 解決を実装する | P0-03、P0-04 | Android登録UIと所有者配下へのbackend保存は実装済み。残りは実端末確認 |
| P0-07 | DONE | Twilio account、発信番号、テスト受電番号を準備する | 人間の契約・同意 | 2026-09-25: Secret Manager 経由で Twilio Account API を確認。`status: active`、`type: Full`（trial 制限なし）、残高 1814.52 JPY、`key-twilio-from-number` が voice 対応の in-use 番号であることを確認済み。Cloud Run `lifelink-backend` に 4 secret（sid/authToken/from-number/openai）が正しく bind 済み。実際のテスト発信自体は発信先の事前同意取得後に P0-15 で実施する |
| P0-08 | DONE | OpenAI API key を Secret Manager から Cloud Run へ割り当てる | P0-03 | Cloud Run のみが Secret を参照可能 |
| P0-08A | DONE | World ID app、RP、action、proof検証と発信認可を実装する | RP登録・署名鍵のSecret Manager保存・Cloud Run割当は完了 | 2026-09-26: Samsung物理端末のWorld AppでProof of Humanを完了し、Congratulations表示、backend status HTTP 200、Firebase `human_verified` claim、LIFELiNKの「World ID人間証明済み」を確認。証明済みユーザーの再証明フローも成功。途中の一時DNS失敗はflow IDを保持してpollを継続し「自動で再試行しています」と表示する |
| P0-09 | IN PROGRESS | `EmergencyTrigger` と Android Safety gate を実装する | P0-04 | 永続event IDと二段階確認の画面ボタンは実装済み。残りはBLE共通interface化と実端末確認 |
| P0-10 | IN PROGRESS | backend の冪等イベント作成と Twilio 発信を実装する | P0-06、P0-07、P0-08A、P0-09 | 連打・HTTP 再送でも実着信が一回だけ |
| P0-11 | IN PROGRESS | Twilio Media Streams と OpenAI Realtime bridge を実装する | P0-08、P0-10 | 実通話で双方向会話が成立 |
| P0-12 | IN PROGRESS | 鮮度付き初回発話を実装する | P0-05、P0-11 | backendの構造化発話は実装済み。残りは実通話で住所、座標、精度、鮮度の順序を確認 |
| P0-13 | IN PROGRESS | Android の通話中メモ・位置更新を AI へ注入する | P0-11、P0-12 | 認証・所有権・Call SID・状態検証、冪等保存、Realtime注入、Android送信は実装済み。残りは実通話確認 |
| P0-14 | IN PROGRESS | BLE Beacon 経路（専用 UUID/Major/Minor 広告、`BeaconReceiver`/Filter/PendingIntent、重複排除）を Safety gate へ接続する | P0-09 | 確定UUID/Major/Minorの完全一致filter、PendingIntent receiver、30秒広告バースト重複排除、共通Safety gate/API接続は実装・debug build済み。残りは実機の長押し一回がAndroidで一回の有効イベントになる確認 |
| P0-15 | TODO | MVP の失敗系と縦断フローを実端末で確認する | P0-10〜P0-14 | 権限拒否・通信断・外部 API 障害で二重発信せず、実通話証跡あり |

注記(2026-09-26、解消済み): `requireHumanVerification` が要求する `human_verified` custom claimはWorld ID proof成功後に設定され、物理端末で発信認可へ利用できる状態を確認済み。

## P0.5: 先回り設計（主線をブロックしない）

| ID | 状態 | タスク | 依存 | 完了条件 |
| --- | --- | --- | --- | --- |
| P0.5-01 | DONE | 友人共有・Discord 風フィード・OpenAI Realtime 注入を前提にしたデータモデル（`friend_links`、`emergency_events.participant_uids`、`updates` の拡張スキーマ）と UI 画面遷移を確定する | なし | 決定を `doc/plan.md` の 4 章・6a 章・5 章・9 章に反映済み。P0-13 は拡張スキーマの `note`/`location` サブセットのみ実装すればよく、P1 で作り直しが不要 |
| P0.5-02 | DONE | `firestore.rules`/`firestore.indexes.json` を実プロジェクトへデプロイする | P0.5-01、P0-03 | 2026-09-26: `firebase deploy --only firestore:rules,firestore:indexes` を実行。それまで release が 0 件（default rules のまま）だったことを Firebase Rules API で確認した上でデプロイし、`projects/.../releases/cloud.firestore` が有効化されたことを確認済み |
| P0.5-03 | DONE | GPT Live 状況ストア（Firestore 正本 / Realtime 作業メモリ / Responses delegation の三層構成）のデータ構造と役割分担を確定する | P0.5-01 | 決定を `doc/plan.md` 8a 章に反映済み。`emergencySessions`/`facts`/`state/current`/`timeline`/`delegations` のスキーマと、P0 の `emergency_events`/`updates` との対応関係（8a 章「P0 の emergency_events/updates との関係」）を明記し、P0 実装への変更は不要 |
| P0.5-04 | DONE | `emergencySessions` 系サブコレクションと `world_id_nullifiers` の Firestore security rules を先行デプロイする | P0.5-03、P0.5-02 | 2026-09-26: P2 実装が始まる前に、これらのコレクションが default rules で開いたままにならないよう `firestore.rules` へ owner/participant 読み取り・全書き込み拒否のルールを追加し `firebase deploy --only firestore:rules` で反映済み |
| P0.5-05 | DONE | フル UI モック（`doc/uimock/`）を最終系から逆算し、画面インベントリと未決の 3 論点を確定する | なし | 決定を `doc/plan.md` 4a 章に反映済み。1-1〜3-8 の全画面を P0/P1/P2 にマッピングし、`nickname`/`area` フィールド追加とローカル 2 段階音声アナウンスという 2 つの新規要件、および「警察自動通報」「通話録音共有」「友人 UI を自作するか実 Discord を使うか」という 3 つの未決論点を明記した。Android UI 実装自体は他チームが P0 で進行中のため、ここでは着手しない |
| P0.5-06 | DONE | P2 の `emergencySessions` のコレクション配置を確定する | P0.5-03、P0.5-04 | 2026-09-26: 一度 `users/{uid}` 配下にネストする案を検討したが、友人共有（`participant_uids`）が主要ユースケースであり collectionGroup クエリと webhook 経由の owner_uid 伝達が必要になる点を重く見て、**ルート直下の `emergencySessions/{session_id}`（P0 の `emergency_events` と同じ配置）に確定**した。`doc/plan.md` 8a 章と `firestore.rules` をルート直下パターンへ戻し `firebase deploy --only firestore:rules` で再デプロイ・compile成功を確認済み |

注記: P0-13（通話中メモ・位置更新）を実装する際は、`updates` ドキュメントのフィールド名を `doc/plan.md` 6a 章の拡張スキーマ（`type`、`author_type`、`author_uid` などを含む）に合わせること。P1 での friend_comment / transcript 追加時にフィールド追加のみで済ませるため。

## P1: 実通話成立後

| ID | 状態 | タスク | 依存 | 完了条件 |
| --- | --- | --- | --- | --- |
| P1-01 | TODO | フル UI モックの残る未決 2 論点（`doc/plan.md` 4a 章、論点 1（警察自動通報の文言）は 2026-09-26 に人間が「登録済み緊急連絡先に限定し、警察を目標にしない」と決定済み）を確定する: (1) 通話録音・書き起こしを友人へ共有するか、(2) 友人共有 UI をアプリ内自作（Option A）にするか実 Discord bot（Option B）にするか | P0-15 | 決定を `doc/plan.md` 4a 章に反映済み。P1-02〜P1-06 は決定後の方式に合わせて着手する |
| P1-01a | TODO | Discord 個別連絡先方式の可否を決定する（4a 章の候補設計）。電話先と DM 受信者の関係・人数・通知同意・返信の AI 注入有無・Social SDK 申請要否を確定する | P1-01、受信者の事前同意 | 友人一覧に通常 OAuth ではアクセスできない制約を踏まえ、招待・本人確認・公開範囲・フォールバックを合意して `doc/plan.md` に記録 |
| P1-01b | TODO | P1-01a で Discord 個別連絡を選んだ場合、招待→受信者 opt-in→Bot テスト DM→モーダル返信→`updates` 保存の最小縦断フローを実装・実測する | P1-01a、P0-15 | 事前同意済みの相手で配送成功/失敗が区別され、返信が許可したイベントに一回だけ記録される。電話発信は Discord 障害でも継続する |
| P1-02 | TODO | `friend_links` コレクションと招待コード発行・承認 API（`/v1/friends/*`）を実装する（P1-01 で Option A を選んだ場合） | P0-15、P0.5-01、P1-01 | 相互承認済みの友人一覧が取得でき、`pending`/`accepted`/`blocked` を切り替えられる |
| P1-03 | TODO | `emergency_events.participant_uids` のスナップショット生成と Firestore security rules を実装する | P1-02 | イベント作成時点の友人だけが該当イベントを読み取れ、後から友人になった uid はアクセスできないことを確認 |
| P1-04 | TODO | `updates` フィードへ `friend_comment`・`transcript_contact`・`transcript_ai` を書き込む処理を実装する（入力音声 transcription 有効化を含む） | P1-03、P0-11 | 通話中の両者の発話と友人コメントが同一フィードに時系列で保存される |
| P1-05 | TODO | 友人コメントを `conversation.item.create` + `response.create` で進行中の Realtime セッションへ注入する | P1-04 | 通話を切らずに友人コメントの内容が相手へ音声で伝わる |
| P1-06 | TODO | 友人共有 UI を実装する（Option A: アプリ内 Discord 風画面 / Option B: 実 Discord bot 連携。P1-01 の決定に従う） | P1-01、P1-02〜P1-05（Option A）または Discord bot 基盤構築（Option B） | 自分と共有されたイベントを時系列表示し、通話中にコメント投稿できる。Option B の場合はアプリ未インストールの友人が Discord だけで受信・返信できる |
| P1-06a | TODO | `users/{uid}` へ `nickname`/`area` フィールドを追加し、プロフィール設定画面（モック 1-5）を実装する | P0-15 | ニックネームとエリアを保存・再取得でき、エリアを住所表示や連絡先の文脈情報に利用できる |
| P1-06b | TODO | 端末ローカルの 2 段階音声アナウンス（送信時 stage1・接続時 stage2、JP/EN/両方、Silent SOS トグル）を実装する | P0-09、P0-13（`in-progress`/`answered` を判定する Twilio status） | 送信直後に stage1 が即時発話され、Twilio status が `answered`/`in-progress` を報告した時だけ stage2 が発話される。Silent SOS 有効時は両方無音になる |
| P1-07 | TODO | Android 周辺音声の扱いを設計・実装する | P0-15、同意・法務判断 | 明示同意と状態表示のもとで音声を通話へ追加可能 |
| P1-08 | TODO | GATT 統合前の決定事項（`doc/plan.md` 13 章）を人間と確定する（minSdk/対象 Samsung 機種、firmware MAC 方式、Beacon 広告への event identity 同居可否、location FGS 採用有無、Companion Device/battery optimization の Play policy 方針、2 時間後の停止方針） | P0-15 完了（Beacon 完動） | 決定を `doc/plan.md` 6c 章・13 章に反映済み |
| P1-09 | TODO | `ble-core`: GATT/Beacon 共通の protocol parser、`connectionGeneration`、状態 reducer、重複排除を実装する（Android API 非依存、unit test 付き） | P1-08 | epoch/eventId/generation の妥当性判定が unit test で再現できる |
| P1-10 | TODO | `gatt-android`: `GattController` と直列化された GATT operation queue を実装する（Service/UI/backend に依存しない） | P1-09 | service discovery→CCCD write→`READY` が一つの owner・一つの `BluetoothGatt` で完結する |
| P1-11 | TODO | `monitoring-service`: `connectedDevice` FGS、常駐通知、`PARTIAL_WAKE_LOCK`、2 時間の session deadline、共通停止 path を実装する | P1-10 | 6c 章の停止手順（generation 無効化→disarm→scan 停止→GATT close→WakeLock 解放→stopSelf）を満たす |
| P1-12 | TODO | `companion-android`: Companion Device association/presence を feature flag 付きで実装する（採用条件を満たす場合のみ有効化） | P1-11 | presence 復帰時に `MonitoringService` へ再接続契機が渡り、Companion 側は GATT/arm を生成しない |
| P1-13 | TODO | `firmware`: `firmware/xiao_gatt_button/` に address 方針、advertising 再開、Notify/ACK、event identity を実装する | P1-08、host のツールチェーン（`doc/host-setup.md`） | 6b/6c 章のプロトコル（17/16 byte, big-endian, epoch/eventId 単調増加）を満たす |
| P1-14 | TODO | `device-test`: adb ハーネスとログ収集で 6c 章の実機試験 matrix・シナリオ・合格指標を実施する | P1-09〜P1-13 | `READY` 時間率・押下受信率・再接続時間が観測値として記録され、初期合格案を満たすか判定できる |
| P1-15 | TODO | GATT 経路を `trigger_source: gatt` として Safety gate へ接続し、Beacon へのフォールバックを実装する | P1-09〜P1-14 | `CONNECTED -> SUBSCRIBED -> READY` を維持し、長押し一回が Notify 一回・受信一回・ACK 一回になる。GATT 不通時は Beacon 経路にフォールバックする |

## P2: GPT Live 状況ストアと Responses delegation

`doc/plan.md` 8a 章の設計に対応する実装タスク。P0/P1 の実通話・友人共有が安定してから着手する。

| ID | 状態 | タスク | 依存 | 完了条件 |
| --- | --- | --- | --- | --- |
| P2-01 | TODO | `situationStore.ts`: `facts` の append・`state/current` の materialization・`sequence` 採番・認可を実装する（ルート直下の `emergencySessions/{session_id}` 配下、2026-09-26 にネスト案からルート直下に差し戻し済み） | P0-15、P1-03 | Android fact が一度だけ保存され、`state/current` の `version` が単調増加する |
| P2-02 | TODO | `timelineStore.ts`: `timeline` への transcript/UI 履歴書き込みを実装する（8a 章の `delivery` 状態遷移を含む） | P2-01 | 割り込み時に `conversation.item.truncate` と連動して `interrupted` が記録される |
| P2-03 | TODO | `realtimeTools.ts`: `get_current_situation`/`get_session_history` の同期 tool と routing 規則を実装する | P2-01、P2-02 | 「今どこ」「さっき何と言ったか」に根拠 `fact_id` 付きで即答できる |
| P2-04 | TODO | `delegationStore.ts` + `responsesDelegate.ts`: `delegate_investigation` の非同期委譲（`background: true`、poll、`call_id` 冪等化）を実装する | P2-03 | 保留発話が一回だけ発話され、Responses 完了後に同じ通話へ結果が音声で返る |
| P2-05 | TODO | `realtimeBridge.ts`: 既存 `voice.ts` の Media Stream bridge を tool event 処理・OOB 保留・結果注入・truncation 込みで発展させる | P2-01〜P2-04 | 通話終了後の delegation 結果は音声注入されず履歴のみに保存される |
| P2-06 | TODO | 認可・rate limit・ログ非記録の横断実装（8a 章「認可・安全」）と障害時のフォールバック文言を実装する | P2-01〜P2-05 | stale/unknown/timeout/failure を捏造せず明示し、重複 event/tool call/delegation で二重発話・二重発信しない |

## 次のアクション

Google provider、OAuth client、Twilio account、発信番号、OpenAI Secret、Cloud Run音声bridgeは構成済み。2026-09-25 にTwilio(`status: active`/`type: Full`)とGoogle provider(`enabled: true`)を Secret Manager 経由のAPI呼び出しで実測確認済み。主線の外部ボトルネックはテスト受電番号の受電者からの事前同意である。2026-09-26: GCP billing budgets（Monthly 2,000円/2nd limit 20,000円/Alert 10,000円、billing account 全体に適用）が既に設定済みであることを確認し、Twilio/OpenAI Realtimeの誤課金に対する安全網はすでにあると判断した（追加設定は不要）。

1. 物理端末でP0-05（位置保存）、P0-06（連絡先登録）、P0-09（Safety gate）を確認する。
2. 同意済みテスト受電番号をアプリへ登録し、P0-10〜P0-13の実通話を縦断確認する。
3. 物理Beacon長押しでP0-14の一回性を確認する。
4. GATT（P1-08〜P1-15）は Beacon（P0-14）が主線完了後に完動してから着手する。着手前に P1-08 の決定事項を先に固める。