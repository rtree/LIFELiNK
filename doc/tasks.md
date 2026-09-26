# LIFELiNK Tasks

最終更新: 2026-09-26

> 新しいセッションは `reference/handover.md` を先に読むこと。本ファイルは「次に何をやるか」の正本。

状態: `TODO` / `IN PROGRESS` / `BLOCKED` / `DONE`

## P0: MVP 主線（電話・iBeacon の実通話は完了、Discord 個別連絡は次の未完了区間）

P0-15 は電話/iBeacon の **happy path** の通過点であり、製品全体の MVP 完了を意味しない。2026-09-26 の決定で Discord 個別連絡を同じ主線の P0-16〜P0-20 に追加した。アプリ内 Google 友人リンクは将来機能。失敗系の網羅・長時間ロック試験は人間判断により P3 に分離したが、Discord の不達が電話を妨げない確認は P0-20 に残す。

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
| P0-14a | DONE | ＋Beaconのボタン1/2・短押し/長押しと広告UUID/Major/Minorの対応をドライランで実測し、発信トリガーにする識別値を確定する | P0-14 | 2026-09-26: 実測で Beacon0=待機、Beacon1/2=ボタン1/2、Major bit14=長押しと確定（`doc/plan.md` 6b 章）。旧トリガーが待機広告だった不具合を修正し、長押し bit のみで発信候補にする実装を Samsung 実機へ導入済み。残り: ドライランで短押し→候補なし・長押し→候補1回を実機確認し、その後ドライランOFFで実通話1回を再確認。追記: 利用者ごとのスロット登録（未リンク時はトリガー無効）、PendingIntent LOW_LATENCY 化、反応速度の分解計測ログ（選択/一括コピー・logcat `LIFELiNK.BeaconLog`）、表示を iBeacon/XIAO のみに絞る変更を実機へ導入済み。**既存リンクは旧形式のため再リンクが必要**。完了（2026-09-26 11:10）: ドライランで短押し 0・長押し 1 を画面 ON/ロック中とも確認、常駐 FGS でロック中も受信、ドライラン OFF で長押し 1 回→`POST /v1/emergency-events` 1 回→Twilio `completed` 46 秒の実通話（押下から着信約 7 秒） |
| P0-15 | DONE | 電話・修正後の iBeacon 連動を含む MVP の縦断フローを実端末で確認する（失敗系は 2026-09-26 の人間判断で P3-01 へ分離） | P0-10〜P0-14a | 2026-09-26 11:10: 常駐見守り中の長押し 1 回→イベント 1 件→Twilio `completed` 46 秒の実通話。短押し/待機で発信 0。Discord 着手のゲートを開放 |
| P0-16 | DONE | Discord 個別連絡の実 Firestore スキーマ/API 契約を先に凍結し、Portal Redirect URL/Installation、Cloud Run の Bot/OAuth Secret・非秘密 ID/公開鍵、署名検証/PING 対応を準備する | P0-15、P0.5-11 | 2026-09-26: `doc/plan.md`「P0-16 凍結スキーマ・API」に確定。Discord 用 2 Secret のみ実行 SA に付与し Cloud Run rev `00023-c54` へ割当。Bot token の有効性を `/users/@me` で確認（値は非表示）。Interactions Endpoint URL を API で登録し Discord の署名付き PING 検証が通過。不正署名は 401。Firestore rules をデプロイ。**残: Portal の OAuth2 Redirect URL 登録（人間）** |
| P0-17 | DONE | 発信者がアプリから招待を作成し、相手が Discord 本人確認と緊急 DM/都道府県共有に明示同意して、承認済み連絡先の一覧と解除を使えるようにする | P0-16、受信者本人の同意 | 実装・デプロイ済み（`24bf108`、Android に招待作成/共有・一覧・解除）。実アカウントでの招待→同意→登録は未検証。実アカウントの Discord ID が Firebase UID 配下の本人発行招待と一度だけ結び付き、期限切れ/再利用/なりすましを拒否。Discord 全友人一覧には依存しない |
| P0-18 | DONE | 承認した相手へ Bot テスト DM を送り、ボタン応答で到達確認する | P0-17 | 実装済み（`ack:` ボタン→`last_test_dm.acknowledged_at`）。実 Discord アカウントの受信と応答を確認。送信失敗・未着は成功と表示しない |
| P0-19 | DONE | 実緊急電話イベント作成と並行して同意済み相手へ DM を試み、署名検証済みボタン→モーダル返信を同じイベントの参考情報へ一度だけ保存する | P0-18 | 実装済み（`discord_notifications` を create で重複防止、返信は `updates/discord_{interaction_id}`）。実 Firestore `emergency_events/{id}/updates` に権限を照合した返信を記録し、DM の配送状態/再送重複を管理。GPS 座標・詳細住所・録音を送らず、第三者返信を無断で AI に注入しない |
| P0-20 | DONE | 電話+iBeacon+Discord を同意済み実端末・実アカウントで縦断確認する | P0-19 | 2026-09-26: 実 Discord アカウントで招待→同意→登録→テスト DM（Bot と同じサーバーが必要、無いと `50278`）→緊急発信で電話と緊急 DM、通話書き起こしの DM 中継、友人の複数回返信が同じイベントに保存され通話中 AI が伝えることを人間が確認（「かなりいい」、細かい改善点は次段）。Cloud Run rev `00025-qb8` |
| P0-21 | DONE | **MVP 0.1 の保全（主線の終点）**: 動いている状態を再現可能な形で固定する（コード・インフラ設定・外部サービス設定・ Firestore 実データ） | P0-20 | 2026-09-26: `reference/mvp0.1.md`（commit・Cloud Run image digest・環境変数と Secret 名/version・Discord/Twilio/World ID/Beacon 設定・Firestore スキーマ・再現手順・チェックリスト・既知制約）を追加し Git タグ `mvp-0.1`（`5e67c3e`）を push。Firestore 全体を `gs://ethglobaltokyo2026lifelink-firestore-backups/mvp-0.1-2026-09-26`（asia-northeast1、公開アクセス防止、104 ドキュメント / 59.6 KB、操作 `SUCCESSFUL`）へ export。**ここで Discord 込みの MVP 主線は完了** |

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
| P0.5-11 | DONE | 人間が Discord Portal アプリを作成し、Bot token と OAuth Client Secret を GCP Secret Manager に直接登録する（任意の先行準備） | P0.5-10、Discord アカウント | 2026-09-26: Application ID/Public Key はユーザーが控え済み。対象 project に `key-discord-bot-token` と `key-discord-oauth-client-secret` が存在し version 1 `enabled` をメタデータのみ確認。値と API 有効性は未確認。Redirect URLs/Installation/Interactions の Portal 詳細設定、Cloud Run 割当/DM テストは P0-16〜P0-18 |

注記: P0-13（通話中メモ・位置更新）を実装する際は、`updates` ドキュメントのフィールド名を `doc/plan.md` 6a 章の拡張スキーマ（`type`、`author_type`、`author_uid` などを含む）に合わせること。P1 での friend_comment / transcript 追加時にフィールド追加のみで済ませるため。

## P1: MVP 0.1 の次（提出までの主線、上から実行順）

MVP 主線（P0-01〜P0-21）は完了し `mvp-0.1` として保全済み。ここからは **P1-16 → P1-17 → P2-07 → P1-18 → P1-19** の順に進める（`doc/plan.md` 1a 章の実行順序決定、残り時間目安 10 時間）。英語化は専用フェーズを設けず、P1-16 以降で触る画面から順に英語へ寄せる。

**欠番（2026-09-26 整理）**: P1-02〜P1-06 と P1-08〜P1-15 は優先度最低のストレッチゴールとして **PX セクションへ移動**（PX-01〜PX-13）。P1-07（Android 周辺音声）は内容が重複するため **P2-07 へ統合**。これらの P1 番号は再利用せず欠番のままにする。

| ID | 状態 | タスク | 依存 | 完了条件 |
| --- | --- | --- | --- | --- |
| P1-16 | DONE | Android に緊急イベントのチャット風ライブ表示画面を実装する。`emergency_events/{id}/updates` を時系列購読し、`note`/`location`/`transcript_contact`/`transcript_ai`/`friend_comment`/`system` を種類ごとに整形して表示する（Discord チャンネル風または WhatsApp 風、uimock のテイストに近い方を採用） | P0-21（MVP 0.1 保全済み） | 2026-09-26 15:08 完了。実通話で「電話の相手の発言→AI の発言→Discord 友人の返信（`Passed to the AI on the call` 付き）→それを受けた AI の発言」が届いた順にアプリ内へ並ぶことを実機で確認（ロック解除後に目視）。実装: `firebase-firestore` 依存追加、`EmergencyFeed.kt`=表示モデルと mapper（データ源非依存、P2 で `timeline` へ差し替え可能）、`EmergencyFeedSource.kt`=Firestore listener、`EmergencyFeedSection.kt`=WhatsApp 風 UI。方針は `doc/plan.md` 6a 章。ダミーデータなし |
| P1-17 | DONE | 英語化と B2C 向け UI 整形を段階的に進める（専用フェーズは設けず、P1-16 以降で触る画面から順に文言・配色・導線を整える） | P1-16 | 2026-09-26 15:30 完了。検証用 1 スクロール画面を **Home / Members / Settings の 3 タブ**へ再構成し、モック由来の `LifeLinkTheme`（ネイビー＋緊急レッド＋pill ボタン）を適用。Android のユーザー可視文字列は技適番号を除き全て英語（MainActivity 他 4 ファイルの常駐通知・診断ログを含む）。未実装のプロフィールはダミーを置かず "coming soon" 表示。実機で 3 タブを目視確認。方針と意図は `doc/plan.md` 4a 章「Android 画面構成と言語方針」 |
| P1-18 | TODO | World ID の再認証・認証解除の設定画面と API を実装する（`backend/src/worldid.ts` に追加） | P2-07 完了後（`doc/plan.md` 1a 章の実行順序） | 認証切れ・失効後に設定画面から再認証でき、認証解除操作で `human_verified` claim が外れて緊急発信が再びブロックされることを実機で確認する |
| P1-19 | TODO | IDKit の `credential_types` に Passport/Selfie Check を追加する | P1-18 | Proof of Human に加えて Passport または Selfie Check でも認証が成立し、`human_verified` claim が同様に付与される |
| P1-20 | DONE | SOS ボタンの発火条件（3 回タップ / 2 秒長押し・アーミング中の色変化）、位置情報の定期送信、精度・電池残量・揺れの取得と AI 発話/Discord DM への反映、AI と Discord の完全英語化 | P1-17 | 2026-09-26 16:0x: 実機で 1 タップ→「Keep going」と暗色化、3 タップ/長押しで発信。`POST /v1/locations` が前面中 60 秒ごと・発信中 10 秒ごとに届く。`accuracy_m`/`battery_*`/`motion_*` が Firestore に保存され、AI が「accurate to about N meters」「battery N percent」「being shaken hard」と話し、同じ情報が Discord DM にも載る。契約は `doc/plan.md` 6 章「デバイス状態の契約」。**揺れ検知は自動発信しない**（誤報を避けるため状況情報のみ） |
| P1-01 | DONE | 通話録音/書き起こし共有の可否を決める | P0-20、同意/保持期間の判断 | 2026-09-26 決定・実装済み: 音声ファイルは録音・保存しない。通話の書き起こしのみ、同じイベントで DM 済みの同意済み Discord 受信者へ逐次中継する（`doc/plan.md` 4a 章「通話内容のリアルタイム共有」、実装 `25d9352`）。電話の相手への共有告知はデモのため入れず、製品化時に再検討する |
| P1-06a | TODO | `users/{uid}` へ `nickname`/`area` フィールドを追加し、プロフィール設定画面（モック 1-5）を実装する | P1-16 | ニックネームとエリアを保存・再取得でき、エリアを住所表示や連絡先の文脈情報に利用できる |
| P1-06b | TODO | 端末ローカルの 2 段階音声アナウンス（送信時 stage1・接続時 stage2、JP/EN/両方、Silent SOS トグル）を実装する | P0-09、P0-13（`in-progress`/`answered` を判定する Twilio status） | 送信直後に stage1 が即時発話され、Twilio status が `answered`/`in-progress` を報告した時だけ stage2 が発話される。Silent SOS 有効時は両方無音になる |

## P2: 周辺音の取り込み（現行データ基盤＋キャリア会議の段階実験）

**2026-09-26 追加判断**: Galaxy＋SoftBank の手動3者通話・ロック後のマイク到達をユーザーが確認。
次は **キャリア IMS Conference＋Twilio AI 電話レッグ**を P2-12〜16 で段階評価する（Twilio Conference ではない）。
既存 Cloud Run / Firestore / Discord を共用し、既存 SOS は温存、Beacon の旧／実験選択は既定を旧にする案。
詳細・証跡・復旧手順は `reference/ambient-verification.md` 0 章。**今回は文書化のみ、Twilio参加・自動制御は未実装／未検証**。
P2-09〜11 は独立録音の代替案として保留し、この電話実験の前提にはしない。

**2026-09-26 方針転換（人間判断）**: 8a 章の `emergencySessions`/`facts`/`timeline`/`delegations` への移行（旧 P2-01〜06）は
**ハッカソンでは実装しない**と決め、PX-14〜PX-19 へ退避した。理由は 3 つ:

1. やりたいこと（周辺音の取り込み）に新コレクションが要らない。既存の `emergency_events/{id}/updates` に
   独立録音を採る場合は `type: "ambient"` の追加案を検討できる。キャリア会議では既存 transcript の意味とラベルを検討する（契約は先に plan で凍結）。
2. 旧 P2 の本体は `voice.ts` の作り替えで、そこは最も壊れやすく、提出前に再度不安定にする理由がない。
3. 凍結したスキーマと rules/indexes はデプロイ済みで、放置しても害がない。設計済み・実装は将来、で良い。

`doc/plan.md` 8a 章は**設計文書としてそのまま残す**（削除しない）。

| ID | 状態 | タスク | 依存 | 完了条件 |
| --- | --- | --- | --- | --- |
| P2-07 | DONE | 利用者周辺の声・音を AI と友人へ届ける方式を評価する（音声は永続化しない） | 同意・法務判断 | 2026-09-26: キャリア3者会議（SOSV2-ambientMode）で実現し既定化（P2-12〜16）。独立録音案 P2-09〜11 は代替として保留。詳細は `reference/ambient-verification.md` |
| P2-08 | DONE | マイク権限とマニフェスト宣言を「事前設定」として用意する（緊急時に権限ダイアログを出さないため） | P1-17 | `RECORD_AUDIO` / `FOREGROUND_SERVICE_MICROPHONE` を宣言し、Settings から事前に許可を取れる。許可状態が画面に出る |
| P2-09 | TODO | `microphone` 種別の FGS を実装し、**画面 OFF・ロック中に実際に非無音の PCM が取れるかを実機で測る** | P2-08 | `reference/ambient-verification.md` 5 章のチェックリストを埋める。`AudioRecord.registerAudioRecordingCallback()` で `isClientSilenced()` を常に記録し、「録れている」と「送れている」を分けて計る |
| P2-10 | TODO | 15 秒チャンクを backend へ POST し、`gpt-4o-mini-transcribe` の結果だけを `updates.type: "ambient"` へ保存する取り込みパス | P2-09 | 無音チャンク（RMS 閾値以下）は送らない。音声バイトは Firestore ・ログ・一時ファイルのどこにも残さない。**AI への注入は間引く**（意味が変わったときだけ・最短間隔あり）。さもないと通話が実況中継になる |
| P2-11 | TODO | 非発話音（叫び声・ガラス・アラーム）を MediaPipe + YAMNet で端末内分類する | P2-10、時間が余った場合 | 追加コスト $0 で「Screaming」「Glass」等を同じ `ambient` へ書く。音声は端末から出ない |
| P2-12 | DONE | キャリア会議実験の基準点・使用番号・inbound 契約を確定する（E0） | 手動3者通話は確認済み、番号用途と参加者の同意 | 番号 `PNbe25648b5f32bd261cb3ac9039855fd3`（+1629280xxxx）をユーザー承認で転用し旧 Voice URL を削除。契約は `doc/plan.md`「キャリア会議実験の凍結契約」 |
| P2-13 | DONE | 既存 backend に実験イベント準備と AI 番号の着信 webhook を隔離追加する（E1） | P2-12 | rev `00030-j5p` 以降（現行 `00033-77z`、env `TWILIO_AI_INBOUND_NUMBER`）。Voice URL=`/v1/twilio/inbound`、StatusCallback=`/v1/twilio/inbound/status`。署名なし 403、実機でコード照合・join 成功 |
| P2-14 | DONE | 標準 Samsung dialer の手動操作で AI 単独→連絡先追加→会議を確認する（E2/E3） | P2-13、同意済み実番号 | 2026-09-26 19:15 3 者通話成立（`reference/ambient-verification.md` 0.5）。rev `00031-dfn` 後の再試験で「保留中に AI が黙る・話者を推測と言う・画面 OFF でも会話継続」をユーザー確認 |
| P2-15 | DONE | 最低限の実ダイヤラー機能（約2画面＋α）と画面の実験 SOS を実装・実機確認する（E4） | P2-14 | 2026-09-26 20:40 実機確認（ユーザー）: role 取得、ダイヤル画面からの通常発信、画面の「Start conference SOS」で AI→join 確認→連絡先→**自動統合**（IMS は元の 2 本を新しい会議通話に置き換える。応答直後は conferenceable が空なので 1 秒ごとに再試行）、AI の振る舞い及第点。発信元端末は**マイク ON・受話口・通話音量最小・通話画面を点けない**（「ほとんど聞こえない」を確認）、終了後に音量を復元。backend rev `00033-77z`（AI に通話シーケンスと Discord 閲覧を指示）、commit `3b6fdc8`。未確認: 60 秒の呼び出し打ち切り、LIFELiNK 経由の通常着信。戻し方: Settings で電話アプリを Samsung に戻す（`cmd role get-role-holders android.app.role.DIALER`）。今後の改善: 統合タイミングを backend 経由で AI に伝えると話者推定が改善する |
| P2-16 | DONE | Settings の Beacon SOS route 選択を追加しロック起点を確認する（E5） | P2-15 | 2026-09-26 21:00 実機確認（ユーザー）: Settings「Button SOS mode」で `SOSV1-nope` / `SOSV2-ambientMode`（**既定 V2**、人間決定）。**ロック中の Beacon 押下 → Discord 緊急 DM → 連絡先に電話 → 3 者通話**まで成立。電話アプリ未設定・`CALL_PHONE` 無しなら V1 にフォールバック。60 秒広告／75 秒途切れ規則と Safety gate は変更なし。commit `8284897` |
| P2-17 | TODO | SOSV2 で AI が参加できないときも連絡先には必ず電話する | P2-16 | 現状は AI の join を 45 秒待って失敗すると連絡先に**発信しない**（Twilio/backend 障害で誰にもつながらない）。join 失敗・AI 通話が切れた場合は連絡先へ直接発信し、状態を画面とログに出す。二重発信しないこと |
| P2-18 | TODO | SOSV2 の未確認経路を実機で確認する | P2-16 | (1) 連絡先が 60 秒出ないと発信をやめ AI の保留が解除される、(2) 普段の着信を LIFELiNK の画面で応答／拒否できる、(3) 画面 OFF・ロック中の着信表示、(4) 電話アプリを Samsung に戻すと Beacon が V1 にフォールバックする |
| P2-19 | TODO | 統合のタイミングを AI に伝え、話者の推測を助ける | P2-16 | 統合成功時に Android→backend へ `note` 相当の更新（例: "The contact has just joined"）を送り、既存の注入キューで AI に伝える。新規スキーマは作らない |
| P2-20 | TODO | Discord 招待の同意文に「通話の書き起こしが届く」「SOSV2 では本人周辺の音声も通話に乗る」を明記する | なし | README の既知の同意ギャップを解消。既存の同意済み連絡先の扱い（再同意の要否）も決める |

## P3（欠番）

2026-09-26 の判断で **P3-01〜P3-04 は PX-20〜PX-23 へ移した**。番号は再利用しない。
当面の堅牢化は、事前に組んだ試験項目を消し込むのではなく、**実端末で縦断フローを何度も回し、壊れたものをその場で直す**やり方で進める。
そこで見つかった問題はこの文書に新しいタスクとして追加し、原因と切り分け手順は `reference/handover.md` 5 章へ書き出すこと。

## PX: ストレッチゴール（優先度は最低・提出スコープ外）

提出までの主線（P1・P2）が片付き、それでも時間が余った場合にだけ着手する。**着手しないまま提出することを前提に計画する**。2026-09-26 に旧 P1-02〜P1-06（アプリ同士の友人リンク）と旧 P1-08〜P1-15（GATT）をここへ移し、同日さらに旧 P2-01〜P2-06（状況ストア）と旧 P3-01〜P3-04（異常系）も移した。旧番号は欠番のまま再利用しない。

| ID | 旧 ID | 状態 | タスク | 依存 | 完了条件 |
| --- | --- | --- | --- | --- | --- |
| PX-14 | P2-01 | TODO | `situationStore.ts`: `facts` の append・`state/current` の materialization・`sequence` 採番・認可（ルート直下の `emergencySessions/{session_id}` 配下） | 提出後に継続する場合のみ | fact が一度だけ保存され、`state/current` の `version` が単調増加する |
| PX-15 | P2-02 | TODO | `timelineStore.ts`: `timeline` への transcript/UI 履歴書き込み（8a 章の `delivery` 状態遷移を含む） | PX-14 | 割り込み時に `conversation.item.truncate` と連動して `interrupted` が記録される |
| PX-16 | P2-03 | TODO | `realtimeTools.ts`: `get_current_situation`/`get_session_history` の同期 tool と routing 規則 | PX-14、PX-15 | 「今どこ」「さっき何と言ったか」に根拠 `fact_id` 付きで即答できる |
| PX-17 | P2-04 | TODO | `delegationStore.ts` + `responsesDelegate.ts`: `delegate_investigation` の非同期委譲（`background: true`、poll、`call_id` 冪等化） | PX-16 | 保留発話が一回だけ発話され、Responses 完了後に同じ通話へ結果が音声で返る |
| PX-18 | P2-05 | TODO | `realtimeBridge.ts`: 既存 `voice.ts` の Media Stream bridge を tool event 処理・OOB 保留・結果注入・truncation 込みで発展させる | PX-14〜PX-17 | 通話終了後の delegation 結果は音声注入されず履歴のみに保存される |
| PX-19 | P2-06 | TODO | 認可ヺrate limit・ログ非記録の横断実装と障害時のフォールバック文言 | PX-14〜PX-18 | stale/unknown/timeout/failure を捿造せず明示し、重複 event/tool call/delegation で二重発話・二重発信しない |
| PX-20 | P3-01 | TODO | 実端末で失敗系を網羅的に確認する（権限拒否、通信断、Twilio/OpenAI/World ID 障害、認証切れ、通話中の再押下、片方向音声） | 時間が余った場合のみ | いずれでも二重発信せず、Android に状態と次の操作が表示される証跡がある。切り分けは `reference/handover.md` 5 章 |
| PX-21 | P3-02 | TODO | Beacon 見守りの長時間ロック（15 分〜2 時間）・APK 更新/再起動後の復帰・通知権限拒否時の挙動を測る | 時間が余った場合のみ | heartbeat 欠落・受信遅延・見落としの観測値を `doc/plan.md` 6b 章へ記録 |
| PX-22 | P3-03 | TODO | 画面 OFF 直後の受信空白（38 秒の実測あり）を定量化し、送信時間 10 秒を維持するか判断する | 時間が余った場合のみ | 空白の発生率・長さ・条件と対策の効果が `doc/plan.md` 6b 章に記録される |
| PX-23 | P3-04 | TODO | ボタンの死活表示（最終受信時刻、未受信警告、電池低下警告、初期設定の確認項目） | PX-22（警告しきい値の根拠） | IDLE 化・電池抜き・範囲外で警告が出て、復帰で消えることを実機確認 |
| PX-01 | P1-02 | TODO | LIFELiNK アプリ同士の友人リンクが Discord で代替できないか再評価し、必要な場合だけ `friend_links`・相互承認 API を設計/実装する | 将来の再判断 | 採用すると決めた場合に限り、本人確認を伴う相互リンクが成立。P1・P2 を待たせない |
| PX-02 | P1-03 | TODO | アプリ内友人リンクを採用する場合のみ `emergencySessions.participant_uids` に承認済み Firebase UID をスナップショットする | PX-01 を実装すると決めた場合 | 後から友人になった uid に過去イベントを公開せず、Discord ID を UID と混同しない |
| PX-03 | P1-04 | TODO | アプリ内友人のコメントを `timeline` に追加する（Discord モーダル返信の保存は P0-19 で実装済み） | PX-02 と P2-02 | アプリ内友人がコメントできる |
| PX-04 | P1-05 | TODO | アプリ内友人の返信を Realtime の通話中 AI に注入する（Discord 返信の AI 注入は P0-19 で実装済み） | PX-03、P2-05、追加同意 | 第三者情報として出典を区別し、無断の音声読み上げをしない |
| PX-05 | P1-06 | TODO | LIFELiNK 同士の友人用ライブ共有 UI を実装する（自分のアプリ内チャット UI は P1-16 で別途実装する） | PX-01〜PX-03 を採用すると決めた場合 | 本物の `facts`/`timeline` に接続し、未実装時はダミーを表示せず「準備中」と表示 |
| PX-06 | P1-08 | TODO | GATT 統合前の決定事項（`doc/plan.md` 13 章）を人間と確定する（minSdk/対象 Samsung 機種、firmware MAC 方式、Beacon 広告への event identity 同居可否、location FGS 採用有無、Companion Device/battery optimization の Play policy 方針、2 時間後の停止方針） | P1・P2・P3 完了後に時間が余った場合のみ | 決定を `doc/plan.md` 6c 章・13 章に反映済み |
| PX-07 | P1-09 | TODO | `ble-core`: GATT/Beacon 共通の protocol parser、`connectionGeneration`、状態 reducer、重複排除を実装する（Android API 非依存、unit test 付き） | PX-06 | epoch/eventId/generation の妥当性判定が unit test で再現できる |
| PX-08 | P1-10 | TODO | `gatt-android`: `GattController` と直列化された GATT operation queue を実装する（Service/UI/backend に依存しない） | PX-07 | service discovery→CCCD write→`READY` が一つの owner・一つの `BluetoothGatt` で完結する |
| PX-09 | P1-11 | TODO | `monitoring-service`: `connectedDevice` FGS、常駐通知、`PARTIAL_WAKE_LOCK`、2 時間の session deadline、共通停止 path を実装する | PX-08 | 6c 章の停止手順（generation 無効化→disarm→scan 停止→GATT close→WakeLock 解放→stopSelf）を満たす |
| PX-10 | P1-12 | TODO | `companion-android`: Companion Device association/presence を feature flag 付きで実装する（採用条件を満たす場合のみ有効化） | PX-09 | presence 復帰時に `MonitoringService` へ再接続契機が渡り、Companion 側は GATT/arm を生成しない |
| PX-11 | P1-13 | TODO | `firmware`: `firmware/xiao_gatt_button/` に address 方針、advertising 再開、Notify/ACK、event identity を実装する | PX-06、host のツールチェーン（`doc/host-setup.md`） | 6b/6c 章のプロトコル（17/16 byte, big-endian, epoch/eventId 単調増加）を満たす |
| PX-12 | P1-14 | TODO | `device-test`: adb ハーネスとログ収集で 6c 章の実機試験 matrix・シナリオ・合格指標を実施する | PX-07〜PX-11 | `READY` 時間率・押下受信率・再接続時間が観測値として記録され、初期合格案を満たすか判定できる |
| PX-13 | P1-15 | TODO | GATT 経路を `trigger_source: gatt` として Safety gate へ接続し、Beacon へのフォールバックを実装する | PX-07〜PX-12 | `CONNECTED -> SUBSCRIBED -> READY` を維持し、長押し一回が Notify 一回・受信一回・ACK 一回になる。GATT 不通時は Beacon 経路にフォールバックする |

## 次のアクション

**2026-09-26 時点で MVP 主線（P0-01〜P0-21）は完了し、MVP 0.1 として保全済み**（`reference/mvp0.1.md`、タグ `mvp-0.1`、Firestore export `gs://ethglobaltokyo2026lifelink-firestore-backups/mvp-0.1-2026-09-26`）。壊れたらここへ戻る。

ここからの実行順（`doc/plan.md` 1a 章の決定、残り時間目安 10 時間）:

1. ~~**P1-16**: アプリ内チャット風ライブ表示~~ **完了（2026-09-26 15:08、実通話で確認済み）**。
2. ~~**P1-17**: 英語化・B2C 向け UI 整形~~ **完了（2026-09-26 15:30、3 タブ化・テーマ適用・全文英語化）**。
3. ~~**P2-12 → P2-16**: キャリア3者会議（SOSV2-ambientMode）~~ **完了（2026-09-26 21:00、ロック中の Beacon から3者通話まで実機確認、既定 V2）**。
4. **次の候補**（上から推奨順）: P2-17（AI 不参加でも連絡先に電話）→ P1-18（World ID 認証の解除・再認証）→ P2-20（Discord 同意文）→ P2-18（未確認経路）→ P2-19（統合を AI に通知）。
5. 並行して、実端末で縦断フローを何度も回して堅牢化する。壊れたものをその場で直し、見つかった問題をタスクとして本ファイルへ追加する。
6. P1-19（Passport/Selfie）と PX（状況ストア、異常系の網羅、アプリ内友人リンク、GATT）は時間が余った場合のみ。

リファクタリング時は `reference/mvp0.1.md` の「既知の制約」、特に `maxScale=1` 前提に注意する。