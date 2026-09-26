# APK 配布サイト（2026-09-27）

## 公開先と構成

- 共有 URL: https://lifelink-56179519-0e8c-4306-abad-ef9019fcec0e-4qp2xid4rq-an.a.run.app
- Service: `lifelink-56179519-0e8c-4306-abad-ef9019fcec0e`
- Region: `asia-northeast1`、project: `ethglobaltokyo2026lifelink`
- 初回 revision: `lifelink-56179519-0e8c-4306-abad-ef9019fcec0e-00001-jh9`
- Runtime SA: `lifelink-web@ethglobaltokyo2026lifelink.iam.gserviceaccount.com`（専用、新規作成、プロジェクト role 付与なし、Secret 割当なし）
- Nginx / port 8080、CPU 1、256 MiB、min=0 / max=1（サービス・revision の両方）、concurrency 80、timeout 60 秒、認証不要。
- `web/` のユーザー提供デザインを維持。HTML/CSS/JS だけをイメージの公開ディレクトリへ COPY。Firestore・backend・World ID には接続しない。
- APK 本体は GitHub Releases から配布。2 個の Download APK リンクは `HowToUse.md` の URL と一致。
- ガイドへのリンク、Android 要件とキャリア会議要件、デモ注意書きを追加。警察自動通報・精密座標共有・Discord 音声配信等の未実装表現は現行デモの説明へ修正。画面内イベントは説明用アニメーションであり、実発報ではないと表示。

## URL の制約

共有するのはプロジェクト ID も番号も含まないハッシュ形式 URL。Cloud Run は別途プロジェクト番号入りの deterministic URL も割り当てるため、**番号入りエンドポイントの不存在や、プロジェクト自体の秘匿を保証するものではない**。番号入り URL を配布リンクには使わない。

ハッシュ形式 URL はデプロイ後安定するが文字列を組み立てて推測せず、サービスの `status.url` / `run.googleapis.com/urls` から取得する。
公式: https://docs.cloud.google.com/run/docs/triggering/https-request#service_url

## 更新手順

リポジトリルートから実行する。**UUID を作り直さず同じサービスを更新**すること。

```sh
node --check web/script.js
git diff --check
gcloud run deploy lifelink-56179519-0e8c-4306-abad-ef9019fcec0e \
  --source=web --region=asia-northeast1 --project=ethglobaltokyo2026lifelink \
  --service-account=lifelink-web@ethglobaltokyo2026lifelink.iam.gserviceaccount.com \
  --allow-unauthenticated --ingress=all --port=8080 \
  --cpu=1 --memory=256Mi --min=0 --max=1 --min-instances=0 --max-instances=1 \
  --concurrency=80 --timeout=60 --quiet
```

`web/.gcloudignore` と `.dockerignore` は許可リスト方式。公開アセットを追加するときは両ファイルと Dockerfile の COPY 対象を合わせて更新する。リポジトリルートや APK、認証情報を COPY しない。イメージタグは `nginx:stable-alpine` のため、再ビルド時のパッチ更新後も HTTP 検証する。

## 検証済み

- HTTPS `/`、`/style.css`、`/script.js`: 200、公開内容とローカルのバイト一致。
- `/Dockerfile`、`/nginx.conf`、存在しないページ: 404。`/.env`、`/.git/config`: 403。
- APK 配布 URL: リダイレクト先 200、`application/vnd.android.package-archive`、21,255,148 bytes（HEAD 確認。APK の再インストールは本作業では行っていない）。
- ブラウザ幅 1440 / 390 / 360 で横スクロールなし。Download APK 2 個の href がガイドと一致。モバイルメニュー開閉、SOS プレビュー（発信なし）を確認。
- `node --check`、エディタ diagnostics、`git diff --check`: 問題なし。
- 既存 backend: revision `lifelink-backend-00035-bsv`、maxScale=1、`/health` は `{"status":"ok"}`。本作業では更新していない。

## 次の更新時

### 2026-09-27 動画追加

- ユーザー指定動画 `1OhpYx8LXekzEg_mBHvBp2384rsDZiPpV` を `#how`（Five steps）の末尾へ Google Drive `/preview` iframe で埋め込み。
- 16:9 レスポンシブ表示・遅延読み込み・全画面対応・直接視聴リンク付き。動画の再ホストや共有権限変更は行っていない。
- 同じサービスの revision `00002-2sj` にデプロイ。ブラウザ幅1440/390で横はみ出しなし、Driveプレビューと再生ボタン表示を確認。内側のプレイヤー iframe が自動クリックを遮ったため、動画の実再生・音声までは未確認。
- 閲覧可否は Drive 側の共有設定にも依存する。動画を差し替えるときは iframe と直接リンクの両方を更新する。

- APK のファイル名変更時は `HowToUse.md` と配布サイトの両方を更新し、リダイレクト最終応答を再確認する。
- min=0 のため初回アクセスにはコールドスタートがあり得る。常時起動や独自ドメイン・CDN は今回の範囲外。