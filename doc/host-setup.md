# ホスト環境セットアップ手順

最終更新: 2026-09-25

## なぜホストで作業するか

Coding Agent はゲスト macOS(Apple Virtualization Framework 上の VM)で動いている。次の 2 つはゲスト内では実用にならないため、ホスト macOS 側で行う。

- **Android Emulator**: ゲスト VM 内でさらに仮想化支援(HVF 相当)をネストするのは現実的でない。ホストは `kern.hv_support: 1` を確認済みで、ホスト上なら実機並みに動く。
- **XIAO nRF52840 への書き込み**: USB シリアル/UF2 デバイスのパススルーは VM 経由では不安定になりやすい。書き込みはホストの USB ポートへ直接行う。

## ホストへの接続

- ゲストとホストは同じ bridge (`10.211.55.0/24`)上にいる。ホストのアドレスは `10.211.55.2`、ユーザーは `araki`。
- 接続はパスワードなし SSH（鍵ベース、`~/.ssh/authorized_keys` 設定済み）。

```bash
ssh araki@10.211.55.2
```

- このアドレスは VM のネットワーク再構成で変わり得る。変わった場合はゲスト側で次を実行し、`en0` の `inet` に対応するホスト側 bridge アドレス（`bridge100`/`bridge101` の `.2` など）を探す。

```bash
ifconfig en0 | grep inet
netstat -rn -f inet | grep default
```

## 2026-09-25 時点のホスト在庫確認結果

`ssh araki@10.211.55.2` から確認済み。

| 項目 | 状態 |
| --- | --- |
| OS | macOS 26.5.2 (arm64) |
| Xcode Command Line Tools | 導入済み (`/Library/Developer/CommandLineTools`) |
| Homebrew | 導入済み (`/opt/homebrew/bin/brew`, 7.0.6)。ただし非ログイン SSH シェルでは `~/.zprofile` の `brew shellenv` が読み込まれず `PATH` に乗らないため、SSH 経由のコマンドは `zsh -lc '...'` でログインシェルとして実行するか、`/opt/homebrew/bin/brew` のフルパスを使う |
| Java | OpenJDK 17.0.20.1 (Temurin) が `/usr/bin/java` |
| Android SDK | `~/Library/Android/sdk` に導入済み（cmdline-tools、platform-tools、emulator、system-images/android-36） |
| Android AVD | `beacon_api36`（Pixel 6、Android 16 "Baklava"、`google_apis/arm64-v8a`）が作成済み |
| Hypervisor support | `kern.hv_support: 1`（Emulator のハードウェアアクセラレーションが有効にできる） |
| Android Studio.app | 未導入（cmdline-tools + emulator コマンドで足りる範囲は GUI 不要） |
| arduino-cli / PlatformIO | **未導入**（XIAO 書き込みに必要） |
| git | 導入済み (2.50.1) |
| GitHub 認証（host→GitHub） | 未設定（`git@github.com` の SSH は host key 未登録で失敗する。読み取りは HTTPS clone で代替する） |
| リポジトリの clone | **未実施** |

## 手順

### 1. 環境変数（Android SDK / Java）

すでに SDK は存在するため、シェルの起動ファイル（`~/.zprofile` など）に以下を追加する。

```bash
ssh araki@10.211.55.2 'cat >> ~/.zprofile <<'"'"'EOF'"'"'
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
EOF'
```

### 2. リポジトリを host へ clone する（読み取り用途）

host は GitHub への SSH 鍵を持っていないため、HTTPS で clone する（public repo であることを前提。将来 private 化する場合は host 用の deploy key か `gh auth login` を別途用意する）。

```bash
ssh araki@10.211.55.2 'mkdir -p ~/operations && cd ~/operations && git clone https://github.com/rtree/LIFELiNK.git'
```

以後、ゲスト側で commit & push した内容を host へ反映するには、host 側で `git pull` するだけでよい。**host からリモートへの push はしない**（push は今まで通りゲスト側で行い、作業証跡の一本化を保つ）。

### 3. Android Emulator の動作確認

```bash
ssh araki@10.211.55.2 'nohup "$HOME/Library/Android/sdk/emulator/emulator" -avd beacon_api36 -no-window -no-audio -no-boot-anim > /tmp/emulator.log 2>&1 & sleep 20 && "$HOME/Library/Android/sdk/platform-tools/adb" devices'
```

`List of devices attached` に `emulator-5554 device` が出れば起動成功。以後の Android 実装が進んだら `adb install` / `adb logcat` も同じ SSH 経由で行える。GUI で見たい場合は host に直接ログイン（画面共有 or 物理アクセス）して `-no-window` を外す。

### 4. XIAO nRF52840 書き込みツールチェーン（arduino-cli）

host には Homebrew が入っているため、`arduino-cli` は brew で導入する。

```bash
ssh araki@10.211.55.2 'zsh -lc "
brew install arduino-cli
arduino-cli config init
arduino-cli config add board_manager.additional_urls https://files.seeedstudio.com/arduino/package_seeeduino_boards_index.json
arduino-cli core update-index
arduino-cli core search nrf52
"'
```

`arduino-cli core search nrf52` の結果に出てくる正式な core ID（Seeed の nRF52 ボードパッケージ。バージョンにより名称が変わる可能性があるため、検索結果で確認してから次のコマンドの `<CORE_ID>` を置き換える）を使ってインストールする。

```bash
ssh araki@10.211.55.2 'zsh -lc "arduino-cli core install <CORE_ID>"'
```

XIAO nRF52840 の USB 接続自体は host に物理接続する（VM へのパススルーはしない）。

```bash
ssh araki@10.211.55.2 'zsh -lc "arduino-cli board listall | grep -i xiao"'
ssh araki@10.211.55.2 'zsh -lc "arduino-cli board list"'
```

### 5. firmware ディレクトリの用意（実装は Phase 7 まで着手しない）

`doc/plan.md` 6b 章の通り、GATT 実装自体は主線と Beacon 完動後（Phase 7）まで着手しない。ここではツールチェーンの動作確認（付属 Blink サンプルの書き込み）だけを host で行い、実際の `firmware/xiao_gatt_button/` のコードは Phase 7 で作成する。

## 完了条件

- [x] ゲストから `ssh araki@10.211.55.2` がパスワードなしで通る。
- [x] host 上で `adb devices` に `beacon_api36` の emulator（`emulator-5554 device`）が表示される。
- [x] host 上で `arduino-cli core search nrf52` が Seeed の nRF52 core を返す。`Seeeduino:nrf52` をインストール済み。
- [x] host に repo が clone されており、`git pull` で最新化できる。

すべて 2026-09-25 に完了済み。

## 既知の制約・未決事項

- host の GitHub 認証は未設定。host からの push が必要になった場合は、host 専用の deploy key か PAT を発行し、Secret として扱う（チャットやリポジトリに書かない）。
- host の IP (`10.211.55.2`) は VM 環境依存。別ホストや別ネットワーク構成に切り替わった場合はこの文書の接続情報を更新する。
