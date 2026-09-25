# LIFELiNK Tasks

最終更新: 2026-09-25

状態: `TODO` / `IN PROGRESS` / `BLOCKED` / `DONE`

## P0: MVP 主線

| ID | 状態 | タスク | 依存 | 完了条件 |
| --- | --- | --- | --- | --- |
| P0-01 | DONE | Android package name、GCP project、region、ログイン方式を決定する | 人間の意思決定 | 決定を `doc/plan.md` に反映済み |
| P0-02 | DONE | GCP/Firebase project と billing を利用可能にする | P0-01、人間の課金設定 | Agent が対象 project を CLI で参照可能 |
| P0-03 | DONE | Firestore、Cloud Run、Secret Manager、service account、必要 API を構成する | P0-02 | 最小権限の実行環境と空の backend がデプロイ済み |
| P0-04 | BLOCKED | Firebase Android app、Authentication、`google-services.json` を構成する | P0-02、人間によるGoogle provider有効化 | 実端末からログイン成功 |
| P0-05 | TODO | Android の位置取得・保存 UI と backend API を実装する | P0-03、P0-04 | 実端末の座標・精度・時刻・住所を再取得可能 |
| P0-06 | TODO | 緊急連絡先登録と `contact_id` 解決を実装する | P0-03、P0-04 | E.164 番号を登録し、所有者検証付きで解決可能 |
| P0-07 | BLOCKED | Twilio account、発信番号、テスト受電番号を準備する | 人間の契約・同意 | Agent が Secret Manager 経由でテスト発信可能 |
| P0-08 | IN PROGRESS | OpenAI API key を Secret Manager から Cloud Run へ割り当てる | P0-03 | Cloud Run のみが Secret を参照可能 |
| P0-09 | TODO | `EmergencyTrigger` と Android Safety gate を実装する | P0-04 | 画面ボタンが一意な `emergency_event_id` を生成 |
| P0-10 | TODO | backend の冪等イベント作成と Twilio 発信を実装する | P0-06、P0-07、P0-09 | 連打・HTTP 再送でも実着信が一回だけ |
| P0-11 | TODO | Twilio Media Streams と OpenAI Realtime bridge を実装する | P0-08、P0-10 | 実通話で双方向会話が成立 |
| P0-12 | TODO | 鮮度付き初回発話を実装する | P0-05、P0-11 | 住所、座標、精度、鮮度を順番どおり発話 |
| P0-13 | TODO | Android の通話中メモ・位置更新を AI へ注入する | P0-11、P0-12 | 終話せず追加情報を音声で伝達可能 |
| P0-14 | TODO | BLE Beacon 経路（専用 UUID/Major/Minor 広告、`BeaconReceiver`/Filter/PendingIntent、重複排除）を Safety gate へ接続する | P0-09、Beacon 機器 | 長押し一回が Android で一回の有効イベントになり、Beacon と画面ボタンが同じ発信経路を利用する |
| P0-15 | TODO | MVP の失敗系と縦断フローを実端末で確認する | P0-10〜P0-14 | 権限拒否・通信断・外部 API 障害で二重発信せず、実通話証跡あり |

## P0.5: 先回り設計（主線をブロックしない）

| ID | 状態 | タスク | 依存 | 完了条件 |
| --- | --- | --- | --- | --- |
| P0.5-01 | DONE | 友人共有・Discord 風フィード・OpenAI Realtime 注入を前提にしたデータモデル（`friend_links`、`emergency_events.participant_uids`、`updates` の拡張スキーマ）と UI 画面遷移を確定する | なし | 決定を `doc/plan.md` の 4 章・6a 章・5 章・9 章に反映済み。P0-13 は拡張スキーマの `note`/`location` サブセットのみ実装すればよく、P1 で作り直しが不要 |

注記: P0-13（通話中メモ・位置更新）を実装する際は、`updates` ドキュメントのフィールド名を `doc/plan.md` 6a 章の拡張スキーマ（`type`、`author_type`、`author_uid` などを含む）に合わせること。P1 での friend_comment / transcript 追加時にフィールド追加のみで済ませるため。

## P1: 実通話成立後

| ID | 状態 | タスク | 依存 | 完了条件 |
| --- | --- | --- | --- | --- |
| P1-01 | TODO | World ID / IDKit の app、RP、action、proof 検証と発信認可を実装する | P0-15、Secret 保存先 | Googleログイン済みかつ人間性証明済みユーザーだけが発信可能 |
| P1-02 | TODO | `friend_links` コレクションと招待コード発行・承認 API（`/v1/friends/*`）を実装する | P0-15、P0.5-01 | 相互承認済みの友人一覧が取得でき、`pending`/`accepted`/`blocked` を切り替えられる |
| P1-03 | TODO | `emergency_events.participant_uids` のスナップショット生成と Firestore security rules を実装する | P1-02 | イベント作成時点の友人だけが該当イベントを読み取れ、後から友人になった uid はアクセスできないことを確認 |
| P1-04 | TODO | `updates` フィードへ `friend_comment`・`transcript_contact`・`transcript_ai` を書き込む処理を実装する（入力音声 transcription 有効化を含む） | P1-03、P0-11 | 通話中の両者の発話と友人コメントが同一フィードに時系列で保存される |
| P1-05 | TODO | 友人コメントを `conversation.item.create` + `response.create` で進行中の Realtime セッションへ注入する | P1-04 | 通話を切らずに友人コメントの内容が相手へ音声で伝わる |
| P1-06 | TODO | Discord 風 UI（友人登録画面、招待コード入力、通話履歴・ライブフィード画面、コメント入力欄）を実装する | P1-02〜P1-05 | 自分と共有されたイベントを時系列表示し、通話中にコメント投稿できる |
| P1-07 | TODO | Android 周辺音声の扱いを設計・実装する | P0-15、同意・法務判断 | 明示同意と状態表示のもとで音声を通話へ追加可能 |
| P1-08 | TODO | GATT 統合前の決定事項（`doc/plan.md` 13 章）を人間と確定する（minSdk/対象 Samsung 機種、firmware MAC 方式、Beacon 広告への event identity 同居可否、location FGS 採用有無、Companion Device/battery optimization の Play policy 方針、2 時間後の停止方針） | P0-15 完了（Beacon 完動） | 決定を `doc/plan.md` 6c 章・13 章に反映済み |
| P1-09 | TODO | `ble-core`: GATT/Beacon 共通の protocol parser、`connectionGeneration`、状態 reducer、重複排除を実装する（Android API 非依存、unit test 付き） | P1-08 | epoch/eventId/generation の妥当性判定が unit test で再現できる |
| P1-10 | TODO | `gatt-android`: `GattController` と直列化された GATT operation queue を実装する（Service/UI/backend に依存しない） | P1-09 | service discovery→CCCD write→`READY` が一つの owner・一つの `BluetoothGatt` で完結する |
| P1-11 | TODO | `monitoring-service`: `connectedDevice` FGS、常駐通知、`PARTIAL_WAKE_LOCK`、2 時間の session deadline、共通停止 path を実装する | P1-10 | 6c 章の停止手順（generation 無効化→disarm→scan 停止→GATT close→WakeLock 解放→stopSelf）を満たす |
| P1-12 | TODO | `companion-android`: Companion Device association/presence を feature flag 付きで実装する（採用条件を満たす場合のみ有効化） | P1-11 | presence 復帰時に `MonitoringService` へ再接続契機が渡り、Companion 側は GATT/arm を生成しない |
| P1-13 | TODO | `firmware`: `firmware/xiao_gatt_button/` に address 方針、advertising 再開、Notify/ACK、event identity を実装する | P1-08、host のツールチェーン（`doc/host-setup.md`） | 6b/6c 章のプロトコル（17/16 byte, big-endian, epoch/eventId 単調増加）を満たす |
| P1-14 | TODO | `device-test`: adb ハーネスとログ収集で 6c 章の実機試験 matrix・シナリオ・合格指標を実施する | P1-09〜P1-13 | `READY` 時間率・押下受信率・再接続時間が観測値として記録され、初期合格案を満たすか判定できる |
| P1-15 | TODO | GATT 経路を `trigger_source: gatt` として Safety gate へ接続し、Beacon へのフォールバックを実装する | P1-09〜P1-14 | `CONNECTED -> SUBSCRIBED -> READY` を維持し、長押し一回が Notify 一回・受信一回・ACK 一回になる。GATT 不通時は Beacon 経路にフォールバックする |

## 次のアクション

1. P0-01 の未決事項を人間と確定する。
2. 決定後、P0-02〜P0-04 を並列で進める。
3. 外部アカウント待ちと並行して Android と backend の骨格を作る。