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
| P0-05 | DONE | Android の位置取得・保存 UI と backend API を実装する | P0-03、P0-04 | 2026-09-26: Samsung物理端末で位置取得に成功し、都道府県と取得時刻だけを認証済みAPIがHTTP 201で保存することを確認。座標・精度・詳細住所は永続化しない |
| P0-06 | DONE | 緊急連絡先登録と `contact_id` 解決を実装する | P0-03、P0-04 | 2026-09-26: 同意済み番号を物理端末から登録し、マスク表示と認証済みAPI HTTP 201を確認 |
| P0-07 | DONE | Twilio account、発信番号、テスト受電番号を準備する | 人間の契約・同意 | 2026-09-25: Secret Manager 経由で Twilio Account API を確認。`status: active`、`type: Full`（trial 制限なし）、残高 1814.52 JPY、`key-twilio-from-number` が voice 対応の in-use 番号であることを確認済み。Cloud Run `lifelink-backend` に 4 secret（sid/authToken/from-number/openai）が正しく bind 済み。実際のテスト発信自体は発信先の事前同意取得後に P0-15 で実施する |
| P0-08 | DONE | OpenAI API key を Secret Manager から Cloud Run へ割り当てる | P0-03 | Cloud Run のみが Secret を参照可能 |
| P0-08A | DONE | World ID app、RP、action、proof検証と発信認可を実装する | RP登録・署名鍵のSecret Manager保存・Cloud Run割当は完了 | 2026-09-26: Samsung物理端末のWorld AppでProof of Humanを完了し、Congratulations表示、backend status HTTP 200、Firebase `human_verified` claim、LIFELiNKの「World ID人間証明済み」を確認。証明済みユーザーの再証明フローも成功。途中の一時DNS失敗はflow IDを保持してpollを継続し「自動で再試行しています」と表示する |
| P0-09 | DONE | `EmergencyTrigger` と Android Safety gate を実装する | P0-04 | 2026-09-26: 物理端末で二段階確認、永続event ID、画面/Beacon共通trigger interface、terminal state時のgate解除を確認 |
| P0-10 | DONE | backend の冪等イベント作成と Twilio 発信を実装する | P0-06、P0-07、P0-08A、P0-09 | 2026-09-26: 物理端末から完了済みevent IDを再送し、HTTP 200、UI completed、Twilio Call SID不変、Safety gate解除を確認 |
| P0-11 | DONE | Twilio Media Streams と OpenAI Realtime bridge を実装する | P0-08、P0-10 | 2026-09-26: 159秒の実通話でStream started/stopped、受話7,931フレーム、相手発話26ターン、AI出力1,163フレームを確認。相手発話時にTwilio出力bufferをclearする割り込み処理を実装 |
| P0-12 | DONE | 鮮度付き初回発話を実装する | P0-05、P0-11 | 物理端末で取得した都道府県と情報の鮮度を実通話の初回AI音声で再生確認。座標・精度・詳細住所は発話しない |
| P0-13 | DONE | Android の通話中メモ・位置更新を AI へ注入する | P0-11、P0-12 | 2026-09-26: 167秒の実通話中にAndroidから追加メモを送信し、API HTTP 202、Firestore保存・AI配達時刻、受電側でのAI音声読み上げを確認。受話8,315フレーム、AI出力511フレーム、発話4ターンを記録 |
| P0-14 | DONE | BLE Beacon 経路を Safety gate へ接続する（旧 Beacon0 トリガーは P0-14a で修正済み） | P0-09 | 2026-09-26: 物理広告から `trigger_type: ble` のイベントと 25 秒の実通話を確認。ただし後の実測で Beacon0 は待機広告と判明したため、この通話だけでは長押し検証完了とはみなさない。修正後の実機確認は P0-14a |
| P0-14a | IN PROGRESS | ＋Beaconのボタン1/2・短押し/長押しと広告UUID/Major/Minorの対応をドライランで実測し、発信トリガーにする識別値を確定する | P0-14 | 2026-09-26: 実測で Beacon0=待機、Beacon1/2=ボタン1/2、Major bit14=長押しと確定（`doc/plan.md` 6b 章）。旧トリガーが待機広告だった不具合を修正し、長押し bit のみで発信候補にする実装を Samsung 実機へ導入済み。残り: ドライランで短押し→候補なし・長押し→候補1回を実機確認し、その後ドライランOFFで実通話1回を再確認 |
| P0-15 | TODO | 電話・修正後の iBeacon 連動を含む MVP の失敗系と縦断フローを実端末で確認する | P0-10〜P0-14a | P0-14a の短押し/待機では発信 0、長押しで実通話 1 回の証跡があり、権限拒否・通信断・外部 API 障害でも二重発信しない。Discord 着手のゲート |

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
| P0.5-07 | DONE | GPS 座標・位置精度を Firestore へ保存しない、住所は都道府県レベルまでしか解決・発話しないハッカソン向けプライバシー方針を実装する | なし | 2026-09-26: `android/.../MainActivity.kt` の逆ジオコーディングを `getAddressLine(0)`（番地まで含む全体住所）から `adminArea`（都道府県のみ）へ変更。`backend/src/server.ts` に `sanitizeLocationForPersistence()` を追加し `/v1/locations`・`emergency_events.location_snapshot`・`updates.payload` の3箇所で緯度経度・精度を保存前に除去。`backend/src/voice.ts` の `buildInitialMessage` から座標・精度の読み上げ文を削除。Fastify logger の `redact` に `latitude`/`longitude`/`accuracy_m` 系パスを追加しログにも残さないようにした。typecheck/build green。`doc/plan.md` 6・8・12 章に方針と理由（デモでの実位置公開を避けるため）を明記 |
| P0.5-08 | DONE | P2（8a章）のスキーマを「以後変更しない」ものとして厳密化・凍結し、想定クエリ向けの Firestore 複合インデックスを先行デプロイする | P0.5-06 | 2026-09-26: `facts.value` の `kind` 別 shape、`state/current.active_alerts`、`delegations.result` の型を明記（曖昧な `map`/無型フィールドを解消）。Android が Firestore を直接 read・write は必ず backend API 経由という契約を 8a 章「認可・安全」冒頭に固定契約として明記。`emergencySessions`/`emergency_events` の `owner_uid`/`participant_uids` × 日時ソート用の複合インデックスを `firestore.indexes.json` に追加し `firebase deploy --only firestore:indexes,firestore:rules` で反映済み。これにより「モックを作らず本物のスキーマに実装を積み上げる」戦略の前提（スキーマが実装中に動かない）が整った |
| P0.5-09 | DONE | API/データ設計を見直す前に主要ユースケースを列挙し、現行設計との整合を確認する | P0.5-08 | `doc/plan.md` 1a 章に 7 個のユースケースと現状の対応状況を記録。3 件の不足を発見: (1) 物理ボタンの初期リンク・再登録フロー未設計（Beacon は個体識別を `users/{uid}` に紐付けないと他ユーザーの物理ボタンにも誤反応する実害あり）、(2) プライバシー方針変更で `accuracy_m` まで一律破棄したのは行き過ぎで復活させるべき、(3) 友人リンクは「Option A か B」の二択ではなく Discord identity と LIFELiNK Google アカウントの両方を同時サポートする一般化が必要。スキーマ自体はまだ変更せず、次回の API/データ設計見直しへ引き継ぐ |
| P0.5-10 | DONE | Discord 公式資料で Bot 作成・`identify`・Interactions・DM・審査条件と Secret の扱いを確認する | なし | 2026-09-26: `reference/discord-integration.md` に出典付き調査、ユーザーの Portal 操作と Secret Manager 事前登録手順を記載。Discord アプリ/Secret 自体の作成・DM 送信は未実施 |
| P0.5-11 | TODO | 人間が Discord Portal アプリを作成し、Bot token と必要なら OAuth Client Secret を GCP Secret Manager に直接登録する（任意の先行準備） | P0.5-10、Discord アカウント | 秘密値を会話/Git/ログに載せずに登録。非秘密の Application ID/Public Key と Secret 名だけ確認。Interactions endpoint 設定/DM テストは P0-15 後 |

注記: P0-13（通話中メモ・位置更新）を実装する際は、`updates` ドキュメントのフィールド名を `doc/plan.md` 6a 章の拡張スキーマ（`type`、`author_type`、`author_uid` などを含む）に合わせること。P1 での friend_comment / transcript 追加時にフィールド追加のみで済ませるため。

## P1: 実通話成立後

**2026-09-26 優先順位更新**: **P0-14a と P0-15 の実機完了 → Discord 個別 DM の実アカウント縦断検証（P1-01a〜P1-01f）→ P2 最小書き込みパス（P2-01/P2-02）→ Full UI と delegations を並行**。Discord 検証は既存の本物の `emergency_events`/`updates` を使い、P2 の完成を待たず、モックも作らない。P2 のスキーマが変更になる場合はコードより先に `doc/plan.md` を更新。電話/iBeacon 検証が終わる前に Bot DM 送信はしない。Discord が相手の同意や DM 配信条件で詰まれば失敗を記録し、電話+iBeacon の動く主線を守る。

| ID | 状態 | タスク | 依存 | 完了条件 |
| --- | --- | --- | --- | --- |
| P1-01 | TODO | 通話録音/書き起こし共有の可否を別途決める。アプリ同士の友人リンクは将来の任意機能に延期済み | Discord 個別連絡の縦断検証、同意/保持期間の判断 | 録音の有無・公開範囲を `doc/plan.md` に記録。Discord DM の実装を妨げない |
| P1-01a | DONE | Discord の個別 DM を電話/iBeacon の実機検証後、他の P1/P2 より先に縦断検証する優先順位を決める | 人間の判断 | 2026-09-26 決定。1 人の別支援者への任意通知・都道府県のみ・返信は参考保存・Social SDK 非依存を初回検証の既定とし、追加共有は別途決定 |
| P1-01b | TODO | Discord Developer Portal の Bot/Interactions と Cloud Run の Secret Manager 設定、招待・連絡先・通知配送の実 Firestore スキーマ/API 境界を先に確定する | P0-14a、P0-15、P1-01a | 本物のデータ構造・権限/期限/取り消し・Discord 署名/トークン/429/重複防止契約を `doc/plan.md` に凍結。秘密値はドキュメントやログに出さない |
| P1-01c | TODO | 発信者がアプリから招待を作成し、受信者が Discord 本人確認・通知/都道府県共有の明示同意を経て承認済み連絡先に表示され、解除できるフローを実装する | P1-01b、受信者の同意 | Firebase UID と Discord user ID の本人確認付きリンクが成立し、期限切れ/再使用/なりすましが拒否される。Discord 全友人一覧やサーバー参加だけを承認扱いにしない |
| P1-01d | TODO | 承認した本人に Bot テスト DM を送りボタン応答で到達を検証する | P1-01c | 実 Discord アカウントで受信/応答を確認。送信失敗・プライバシー設定・未着を成功扱いにせず、連絡先に状態を表示 |
| P1-01e | TODO | 実際の新規電話イベント発生時に選択済み受信者へ独立した Discord DM を試み、署名検証済み Interaction のボタン→モーダル返信を対象イベントの `updates` に一度だけ保存する | P1-01d、P0-15 | 実イベントと返信の照合・受信者認可・配送状態/重複排除を確認。送信失敗でも電話は継続し、GPS 座標・詳細住所は送らず、返信を無断で AI に注入しない |
| P1-01f | TODO | 電話+iBeacon+Discord の縦断フローを同意済み実機/実アカウントで再確認する | P1-01e | 1 回の長押しで電話 1 回/DM 1 回、受信者の返信が同じイベントに 1 件、再送で重複なし。DM 不達/Discord 障害でも電話成功を確認し証跡を残す |
| P1-02 | TODO | 【将来・提出スコープ外】LIFELiNK アプリ同士の友人リンクが Discord で代替できないか再評価し、必要な場合だけ `friend_links`・相互承認 API を設計/実装する | Discord の実測と将来の再判断 | 採用すると決めた場合に限り、本人確認を伴う相互リンクが成立。今回の Discord 検証・P2・Full UI を待たせない |
| P1-03 | TODO | 【将来・提出スコープ外】アプリ内友人リンクを採用する場合のみ `emergencySessions.participant_uids` に承認済み Firebase UID をスナップショットする | P1-02 を実装すると決めた場合 | 後から友人になった uid に過去イベントを公開せず、Discord ID を UID と混同しない |
| P1-04 | TODO | 【将来・提出スコープ外】アプリ内友人のコメントを `timeline` に追加する。Discord モーダル返信の保存は P1-01e と P2-01/P2-02 で別途実施 | P1-03 と P2-02 | アプリ内友人がコメントできる（Discord 返信はこのタスクを待たない） |
| P1-05 | TODO | 【将来・提出スコープ外】第三者返信を Realtime の通話中 AI に注入するか同意を確認し、採用時のみ実装する | Discord/アプリ内の返信保存と P2-05、追加同意 | 第三者情報として出典を区別し、無断の音声読み上げをしない |
| P1-06 | TODO | 【将来・提出スコープ外】LIFELiNK 同士の友人用ライブ共有 UI を必要になった場合のみ実装する。Discord DM 受信・返信の完成とは独立 | P1-02〜P1-04 を採用すると決めた場合 | 本物の `facts`/`timeline` に接続し、未実装時はダミーを表示せず「準備中」と表示 |
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

`doc/plan.md` 8a 章の設計に対応する実装タスク。**2026-09-26 優先順位更新: 電話+iBeacon 検証 → Discord 個別連絡の実アカウント縦断検証 → P2 最小パス → Full UI と delegations を並行**。Discord の初回実測には既存の `emergency_events`/`updates` を用い、P2 が出来上がるまで待たない。既存テストデータは移行せず、P2 着手後の新規イベントから `emergencySessions` を使う。

| ID | 状態 | タスク | 依存 | 完了条件 |
| --- | --- | --- | --- | --- |
| P2-01 | TODO | `situationStore.ts`: `facts` の append・`state/current` の materialization・`sequence` 採番・認可を実装する（ルート直下の `emergencySessions/{session_id}` 配下。`participant_uids` のスナップショット生成はこのタスク自体で実装し、P1-02 を待たない）。Discord 返信の `friend_reply` 移行先も確認する | P1-01f | Android/Discord の fact が一度だけ保存され、`state/current` の `version` が単調増加する |
| P2-02 | TODO | `timelineStore.ts`: `timeline` への transcript/UI 履歴書き込みを実装する（8a 章の `delivery` 状態遷移を含む） | P2-01 | 割り込み時に `conversation.item.truncate` と連動して `interrupted` が記録される |
| P2-03 | TODO | `realtimeTools.ts`: `get_current_situation`/`get_session_history` の同期 tool と routing 規則を実装する | P2-01、P2-02 | 「今どこ」「さっき何と言ったか」に根拠 `fact_id` 付きで即答できる |
| P2-04 | TODO | `delegationStore.ts` + `responsesDelegate.ts`: `delegate_investigation` の非同期委譲（`background: true`、poll、`call_id` 冪等化）を実装する | P2-03 | 保留発話が一回だけ発話され、Responses 完了後に同じ通話へ結果が音声で返る |
| P2-05 | TODO | `realtimeBridge.ts`: 既存 `voice.ts` の Media Stream bridge を tool event 処理・OOB 保留・結果注入・truncation 込みで発展させる | P2-01〜P2-04 | 通話終了後の delegation 結果は音声注入されず履歴のみに保存される |
| P2-06 | TODO | 認可・rate limit・ログ非記録の横断実装（8a 章「認可・安全」）と障害時のフォールバック文言を実装する | P2-01〜P2-05 | stale/unknown/timeout/failure を捏造せず明示し、重複 event/tool call/delegation で二重発話・二重発信しない |

## 次のアクション

Google ログイン、World ID、同意済み番号への AI 双方向電話・通話中メモは実機確認済み。残る最優先の検証は Beacon0 待機広告をトリガーから除いた修正後の iBeacon 長押し→実通話と失敗系（P0-14a/P0-15）。Discord 個別連絡に用いる Bot・受信者の本人同意・テスト DM はまだ未実施。

1. P0-14a: ドライランで待機/短押し 0・長押し 1 を確認し、ドライラン OFF で iBeacon 長押し 1 回→電話 1 回を再確認する。
2. P0-15: 電話/BLE の失敗系を確認し、実機検証のゲートを閉じる。
3. **次の主線** P1-01b〜P1-01f: データ契約→招待・受信同意→Bot テスト DM→電話と同時 DM→返信の実データ保存を実 Discord アカウントで検証する。
4. 成功後に P2 最小パスと Full UI へ進む。Google 同士のアプリ内友人リンクは提出スコープ外の将来選択肢とし、Discord で必要性を再評価する。AI への返信注入、GATT、録音も別途判断する。