# LIFELiNK Tasks

最終更新: 2026-09-25

状態: `TODO` / `IN PROGRESS` / `BLOCKED` / `DONE`

## P0: MVP 主線

| ID | 状態 | タスク | 依存 | 完了条件 |
| --- | --- | --- | --- | --- |
| P0-01 | BLOCKED | Android package name、GCP project、region、ログイン方式を決定する | 人間の意思決定 | 決定を `doc/plan.md` に反映済み |
| P0-02 | BLOCKED | GCP/Firebase project と billing を利用可能にする | P0-01、人間の課金設定 | Agent が対象 project を CLI で参照可能 |
| P0-03 | TODO | Firestore、Cloud Run、Secret Manager、service account、必要 API を構成する | P0-02 | 最小権限の実行環境と空の backend がデプロイ済み |
| P0-04 | TODO | Firebase Android app、Authentication、`google-services.json` を構成する | P0-02 | 実端末からログイン成功 |
| P0-05 | TODO | Android の位置取得・保存 UI と backend API を実装する | P0-03、P0-04 | 実端末の座標・精度・時刻・住所を再取得可能 |
| P0-06 | TODO | 緊急連絡先登録と `contact_id` 解決を実装する | P0-03、P0-04 | E.164 番号を登録し、所有者検証付きで解決可能 |
| P0-07 | BLOCKED | Twilio account、発信番号、テスト受電番号を準備する | 人間の契約・同意 | Agent が Secret Manager 経由でテスト発信可能 |
| P0-08 | BLOCKED | OpenAI API key を Secret Manager へ登録する | P0-03、人間のアカウント | Cloud Run のみが Secret を参照可能 |
| P0-09 | TODO | `EmergencyTrigger` と Android Safety gate を実装する | P0-04 | 画面ボタンが一意な `emergency_event_id` を生成 |
| P0-10 | TODO | backend の冪等イベント作成と Twilio 発信を実装する | P0-06、P0-07、P0-09 | 連打・HTTP 再送でも実着信が一回だけ |
| P0-11 | TODO | Twilio Media Streams と OpenAI Realtime bridge を実装する | P0-08、P0-10 | 実通話で双方向会話が成立 |
| P0-12 | TODO | 鮮度付き初回発話を実装する | P0-05、P0-11 | 住所、座標、精度、鮮度を順番どおり発話 |
| P0-13 | TODO | Android の通話中メモ・位置更新を AI へ注入する | P0-11、P0-12 | 終話せず追加情報を音声で伝達可能 |
| P0-14 | TODO | 既存 BLE イベントを Safety gate へ接続する | P0-09、利用 BLE 仕様 | BLE と画面ボタンが同じ発信経路を利用 |
| P0-15 | TODO | MVP の失敗系と縦断フローを実端末で確認する | P0-10〜P0-14 | 権限拒否・通信断・外部 API 障害で二重発信せず、実通話証跡あり |

## P1: 実通話成立後

| ID | 状態 | タスク | 依存 | 完了条件 |
| --- | --- | --- | --- | --- |
| P1-01 | TODO | World ID / IDKit の app、RP、action、proof 検証を実装する | P0-15、Secret 保存先 | 人間性証明済みユーザーだけが発信可能 |
| P1-02 | TODO | 友人登録・共有を実装する | P0-15 | 友人がイベントと更新を閲覧可能 |
| P1-03 | TODO | Discord 風の緊急通話履歴を実装する | P1-02 | 自分と共有されたイベントを時系列表示 |
| P1-04 | TODO | Android 周辺音声の扱いを設計・実装する | P0-15、同意・法務判断 | 明示同意と状態表示のもとで音声を通話へ追加可能 |
| P1-05 | TODO | GATT ボタンとロック中対応を実装する | P0-14、端末・Play 制約検証 | 対象端末のロック中に実イベントが Safety gate へ到達 |

## 次のアクション

1. P0-01 の未決事項を人間と確定する。
2. 決定後、P0-02〜P0-04 を並列で進める。
3. 外部アカウント待ちと並行して Android と backend の骨格を作る。