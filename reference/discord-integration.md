# Discord 個別緊急連絡 — 調査と事前準備（2026-09-26）

## 結論と実装順序

電話と修正後 iBeacon 長押しの実機 happy path（P0-14a/P0-15）は 2026-09-26 に完了。現在の **MVP 主線 P0-16〜P0-20** として、1 人の同意済み Discord ユーザーで「招待→本人確認/位置共有同意→Bot テスト DM→実電話イベントの DM→返信をイベントへ保存」を検証する。アプリ同士の Google/Firebase 友人リンクは将来の任意機能。Discord は電話の代わりではない。

今回の小規模な開発では、通常の Discord Developer Portal アプリ/Bot 作成・Bot token・`identify` OAuth2・HTTP Interactions へのアクセスについて、Discord 側の KYC、事前審査、有料 API キー申請は公式の通常手順には記載されていない。ただし Discord 全友人一覧の `relationships.read` は Social SDK への申請が必要であり、今回使わない。大量展開での審査・ポリシー適合性は別問題。[公式 Bot 入門](https://docs.discord.com/developers/quick-start/getting-started) / [OAuth scopes](https://docs.discord.com/developers/topics/oauth2#shared-resources-oauth2-scopes) / [特権 Intent 審査](https://docs.discord.com/developers/gateway/getting-started-with-privileged-intent-review)

## 人間が Discord 側で今できる準備（DM はまだ送らない）

**準備状況（2026-09-26、ユーザー報告と GCP メタデータ確認）**: Developer Portal アプリを作成し、Application ID と Public Key はユーザーが控え済み。`key-discord-bot-token` と `key-discord-oauth-client-secret` は対象 GCP project に存在し、各 version 1 が `enabled`。**秘密値の中身や Discord API での有効性は確認していない**。OAuth Redirect URLs、Installation Contexts、Interactions Endpoint URL その他 Portal の細かいオプションは未設定。Cloud Run への Secret 割り当て、実行サービスアカウントの Secret 参照権限、OAuth コールバック/署名検証 API、DM テストも未実施。

1. [Developer Portal](https://discord.com/developers/applications) に自分の Discord アカウントでログインし、**New Application / Create App** で LIFELiNK 用アプリを 1 つ作る。新規アプリには Bot user が既定で付く。**General Information** の `Application ID`（OAuth `client_id`、非秘密）と `Public Key`（Interactions 署名検証用、非秘密）を控える。Bot とユーザーの「友達申請」はできない。
2. **Bot → Token → Reset Token** で Bot token を発行する。表示は原則一度だけ。**OAuth2 → Client Secret** は招待を受けた相手の `identify` authorization-code 認可で server-side token exchange を行う場合に使う。こちらも秘密。OAuth token と Bot token は別物で、いわゆる単一の「Discord API キー」はない。**Bot token と Client Secret はこのチャット、Git、スクリーンショット、端末の履歴に貼らず、GCP Secret Manager の追加バージョン画面へ直接入力する**。一度漏れた token は Developer Portal 側で再発行し、Secret のバージョンを差し替える。[公式 Bot 入門](https://docs.discord.com/developers/quick-start/getting-started#fetching-your-credentials)
3. **OAuth2 → Redirects / Redirect URLs** に、後で実装する HTTPS callback の正確な URL を登録する。候補は既存 Cloud Run ドメインの `/v1/discord/oauth/callback`（実装前なのでまだアクセス不能）。保存時の URL と認可時の `redirect_uri` は完全一致させる。相手本人の Discord ID は `identify` scope で `/users/@me` から取得し、OAuth の `state` とサーバー側の招待情報を照合する。`email`、`connections`、`relationships.read`、`dm_channels.read` はこの最小フローに不要。ユーザーの OAuth access/refresh token は本人確認後に不要なら保持しない。[OAuth2 authorization code](https://docs.discord.com/developers/topics/oauth2#authorization-code-grant) / [state](https://docs.discord.com/developers/topics/oauth2#state-and-security)
4. **Installation** で `User Install` を有効化し、User Install の default scope は必要時に `applications.commands`。これは受信者が Bot とやり取りする導線の候補。テスト用の共有サーバーでの到達性も試す場合だけ `Guild Install` と `bot` scope を有効化し、権限は最小にする。**User Install や OAuth `identify` だけで Bot の任意相手への自発的 DM が保証されるわけではない**。Bot からの DM API 呼び出しは別で、実アカウントのテスト DM とボタン応答で到達を確認する。必要なら相手に先に Bot を開いてもらい、共通テストサーバー利用も検討するが、DM を確約しない。[Installation contexts](https://docs.discord.com/developers/resources/application#installation-context) / [Bot 入門](https://docs.discord.com/developers/quick-start/getting-started#installing-your-app) / [Create DM](https://docs.discord.com/developers/resources/user#create-dm)
5. **General Information → Interactions Endpoint URL** は **今は空欄でよい**。Cloud Run 側に `X-Signature-Ed25519` と `X-Signature-Timestamp` の署名検証（raw body に対して行う）、不正署名への 401、PING `type: 1` への PONG `type: 1` を実装・デプロイしてから設定する。URL 候補は同じドメインの `/v1/discord/interactions`（未実装）。先に URL を登録しても Discord の自動 PING 検証に失敗し、セキュリティ検証失敗時には URL が解除され得る。ボタン→モーダル返信はこの HTTP endpoint で扱い、Gateway 常時接続や `MESSAGE_CONTENT` 特権 Intent は不要。[Interactions 設定](https://docs.discord.com/developers/interactions/overview#configuring-an-interactions-endpoint-url) / [応答期限](https://docs.discord.com/developers/interactions/receiving-and-responding#interaction-callback)

### GCP Secret Manager へ先に保存する場合

[GCP Secret Manager コンソール](https://console.cloud.google.com/security/secret-manager)で**対象 project が `ethglobaltokyo2026lifelink` であることを確認**し、次の秘密を **Add secret / Add new version 画面から直接貼り付ける**。登録しただけでは Cloud Run は参照できない。後で実装時に実行サービスアカウントへの **各 Secret に限定した** `roles/secretmanager.secretAccessor` を付け、Cloud Run の環境変数へ割り当てる。IAM や Cloud Run の設定前に秘密値をチャットへ渡す必要はない。[Cloud Run の Secret 連携](https://cloud.google.com/run/docs/configuring/services/secrets)

| GCP Secret 名（推奨） | 内容 | 将来の Cloud Run 環境変数 |
| --- | --- | --- |
| `key-discord-bot-token` | Bot → Token の値 | `DISCORD_BOT_TOKEN` |
| `key-discord-oauth-client-secret` | OAuth2 の Client Secret（`identify` code grant を採用する場合） | `DISCORD_CLIENT_SECRET` |

`Application ID`（`DISCORD_APPLICATION_ID`）、`Public Key`（`DISCORD_PUBLIC_KEY`）は非秘密。Git に置く場合も ID だけにし、秘密値は書かない。**空の Secret コンテナだけでは Cloud Run に渡す version がない**ため、Portal で token を確保してから version 1 を追加する。漏洩や Bot token の Reset 後は新バージョンを登録して Cloud Run の参照先を確認する。Git 管理のローカル `.env`、`echo token`、シェル引数、チケット/スクリーンショットへの貼付を避ける。

## 実装後の期待動作・制約

- 発信者は Firebase 認証済みで期限付き・一回限りの招待を発行。受信者自身が `identify` を許可し、**別途**緊急 DM と都道府県レベルの位置共有に明示同意。Discord 全友人一覧をインポートしたり Bot がユーザーの友人になるわけではない。
- Cloud Run が Bot token で `POST /users/@me/channels` に `recipient_id` を渡し、取得した channel ID へ message を送る。これはユーザーの OAuth token を使って代わりに送る DM ではない。相手の設定・ブロック・共通サーバー・レート制限により送信失敗あり（例 `50007`, `50278`, `40003`, HTTP 429）。送信 HTTP 成功と既読・プッシュ通知・救助の成立は別物。電話は主経路、DM 不達時も止めない。[Create DM](https://docs.discord.com/developers/resources/user#create-dm) / [エラーコード](https://docs.discord.com/developers/topics/opcodes-and-status-codes#json-json-error-codes)
- Discord 返信は署名検証済みのボタン/モーダル Interaction の `user.id` とイベントの許可済み受信者を照合し、`interaction.id` で冪等化して Firestore に保存。最初は参考情報に留め、通話の AI には自動注入しない。HTTP Interactions の初回応答は 3 秒以内、follow-up 用 token の有効期限は 15 分。自由文の DM 全件を受けるには別途 Gateway の常時接続が必要。[Interaction 応答](https://docs.discord.com/developers/interactions/receiving-and-responding)
- デモの Discord DM には GPS 座標・地図の精密リンク・番地レベルの住所・音声ファイル・通話書き起こしを含めない。情報最小化・受信者の取り消し導線・配信ステータス・再送重複防止を実装時に確認。

## 未実施のもの

この文書は公開公式資料と現行設計の整理、および上記の **Secret メタデータ確認**の記録。Secret の内容・Discord API 接続・DM 到達・受信同意は未検証。P0-14a/P0-15 は通過済みで、P0-16 から実装と schema を `doc/plan.md` に先に確定し、既存の `emergency_events`/`updates` を用いた実データの縦断試験に進む。
