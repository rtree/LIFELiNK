# ホスト環境セットアップ手順

最終更新: 2026-09-25

## なぜホストで作業するか

Coding Agent はゲスト macOS(Apple Virtualization Framework 上の VM)で動いている。次の 2 つはゲスト内では実用にならないため、ホスト macOS 側で行う。

- **Android Emulator**: ゲスト VM 内でさらに仮想化支援(HVF 相当)をネストするのは現実的でない。ホストは `kern.hv_support: 1` を確認済みで、ホスト上なら実機並みに動く。
- **XIAO nRF52840 への書き込み**: USB シリアル/UF2 デバイスのパススルーは VM 経由では不安定になりやすい。書き込みはホストの USB ポートへ直接行う。

## ホストへの接続

- ゲストとホストは同じ bridge (`10.211.55.0/24`)上にいる。ホストのアドレスは `10.211.55.2`、ユーザーは `araki`。
- 接続はパスワードなし SSH（鍵ベース、`~/.ssh/authorized_keys` 設定済み）。
- ゲストの `~/.ssh/config` には `beacon-host` alias が設定済み。LAN側のホストIPは `192.168.50.218` だが、VM bridge側の `10.211.55.2` を優先する。

```bash
ssh araki@10.211.55.2
# または
ssh beacon-host
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

Coding Agentが検証するときは、ユーザーが画面を確認できるようGUI表示を既定とし、`-no-window`を付けない。

```bash
ssh beacon-host 'ANDROID_HOME="$HOME/Library/Android/sdk"; nohup "$ANDROID_HOME/emulator/emulator" -avd beacon_api36 -no-audio -no-snapshot-save > /tmp/lifelink-emulator.log 2>&1 < /dev/null &'
ssh beacon-host 'ADB="$HOME/Library/Android/sdk/platform-tools/adb"; "$ADB" wait-for-device; until [[ "$("$ADB" shell getprop sys.boot_completed | tr -d "\r")" == "1" ]]; do :; done; "$ADB" devices -l'
```

`List of devices attached` に `emulator-5554 device` が出れば起動成功。以後の Android 実装が進んだら `adb install` / `adb logcat` も同じ SSH 経由で行える。ウィンドウはhostのログイン済みデスクトップへ表示される。

CIや画面不要の自動確認だけは、上記の起動コマンドへ`-no-window`を追加してheadless実行してよい。

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

## 2026-09-25 追加検証: android/ の host 上ビルド・起動確認

`git pull` で android/ プロジェクトが host に届いた後、host 上で実際にビルドし、emulator へインストールして起動を確認済み。

```bash
ssh araki@10.211.55.2 'zsh -lc "cd ~/operations/LIFELiNK/android && ./gradlew assembleDebug"'
# BUILD SUCCESSFUL（既存の Temurin 17 / Android SDK Build-Tools 35 自動導入で通った）

ssh araki@10.211.55.2 'zsh -lc "
emulator -avd beacon_api36 -no-window -no-audio -no-boot-anim &
adb install -r ~/operations/LIFELiNK/android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.rtree.LIFELiNK/.MainActivity
"'
# topResumedActivity=...com.rtree.LIFELiNK/.MainActivity state=RESUMED を確認
```

以後、Android 側の実装が進んだら同じ手順（`git pull` → `./gradlew assembleDebug` → `adb install` → `adb shell am start`）で host 上のエミュレータで動作確認できる。検証後は `adb emu kill` でエミュレータを止め、resource を空けておく。

## 2026-09-26 追加検証: ゲストbuildをhost emulatorで実行

ゲスト内emulatorはnested HVFが使えず起動しない。ゲストでbuildしたAPKをSSH転送し、Apple SiliconホストのAVDへ直接入れる。通常はユーザーが画面を見られるGUIモードで起動する。

```bash
ssh beacon-host 'ANDROID_HOME="$HOME/Library/Android/sdk"; nohup "$ANDROID_HOME/emulator/emulator" -avd beacon_api36 -no-audio -no-snapshot-save > /tmp/lifelink-emulator.log 2>&1 < /dev/null &'
ssh beacon-host 'ADB="$HOME/Library/Android/sdk/platform-tools/adb"; "$ADB" wait-for-device; until [[ "$("$ADB" shell getprop sys.boot_completed | tr -d "\r")" == "1" ]]; do :; done; "$ADB" devices -l'

scp android/app/build/outputs/apk/debug/app-debug.apk beacon-host:/tmp/lifelink-debug.apk
ssh beacon-host 'ADB="$HOME/Library/Android/sdk/platform-tools/adb"; "$ADB" install -r /tmp/lifelink-debug.apk; "$ADB" shell am start -W -n com.rtree.LIFELiNK/.MainActivity'
```

署名が異なる旧debug APKが残り `INSTALL_FAILED_UPDATE_INCOMPATIBLE` になった場合、テストAVD内だけ旧packageを削除してから再インストールする。

```bash
ssh beacon-host 'ADB="$HOME/Library/Android/sdk/platform-tools/adb"; "$ADB" uninstall com.rtree.LIFELiNK; "$ADB" install /tmp/lifelink-debug.apk'
```

実測結果:

- `emulator-5554`、`sdk_gphone64_arm64`、API 36で起動。
- LIFELiNK 0.1.0のMainActivityを85msで表示し、FATAL/ANRなし。
- Credential ManagerからGoogleログインに成功し、Firebase Auth UID発行と「ログイン済み」表示を確認。
- Cloud Run実行サービスアカウントへ`roles/firebaseauth.admin`を追加し、失効確認付きID token検証とcustom claims更新を可能にした。
- World ID backend start APIはHTTP 200。connector URIはChromeへ開き、World App未導入時にGoogle Playの`org.world.id`へ誘導された。
- Cloud Runは`lifelink-backend-00013-dq4`でReady。World ID proof完了はWorld Appを導入した物理端末で確認する。

## 既知の制約・未決事項

- host の GitHub 認証は未設定。host からの push が必要になった場合は、host 専用の deploy key か PAT を発行し、Secret として扱う（チャットやリポジトリに書かない）。
- host の IP (`10.211.55.2`) は VM 環境依存。別ホストや別ネットワーク構成に切り替わった場合はこの文書の接続情報を更新する。
