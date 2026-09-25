# Android BLE 常時監視 MAX サブプラン

最終更新: 2026-09-25

状態: 独立調査・統合待ち

対象: Android 8.0（API 26）以上を下限候補、Android 12〜16 と Samsung 実機を重点検証

## 0. この文書の位置づけ

この文書は、他チームの実装を変更せず、画面 OFF・ロック中・バックグラウンドで物理ボタンを受信できる時間を最大化するための独立設計である。`doc/plan.md` と `doc/tasks.md` への統合、Android 実装、firmware 変更は別担当者が行う。

目標は「常時受信を保証する」ことではない。Android/OEM、Bluetooth controller、無線環境、Peripheral、ユーザー操作によって配送は途切れる。目標は次の 3 点である。

1. GATT Notify を主経路として、ユーザー開始後の 2 時間セッション中に接続を最大限維持する。
2. GATT が切れている時間を、厳密 Filter の PendingIntent BLE scan と Companion Device presence で可能な範囲だけ補う。
3. 実機ログから `READY` 時間率、押下受信率、再接続時間を測り、「動いているはず」ではなく観測値で可否を決める。

## 1. 調査結論

### 1.1 採用する MAX 構成

```text
ユーザーが画面上で監視開始
  -> 前提・Safety gate確認
  -> connectedDevice FGSを即時開始
  -> MonitoringServiceが唯一のGATT ownerになる
  -> connect -> discover -> CCCD write -> READY
  -> filtered PendingIntent scanを予備登録
  -> 任意でCompanion Device association/presenceを補助登録
  -> 2時間、明示停止、前提喪失のいずれかで完全停止・disarm
```

採用判断は次のとおり。

| 手段 | 採用 | 役割 | 保証しないこと |
| --- | --- | --- | --- |
| `connectedDevice` FGS | 必須 | プロセス優先度、GATT owner、常駐通知 | プロセス不死、Notify配送、OEM挙動 |
| 接続済み GATT Notify | 主経路 | 最低遅延、ACK可能 | process death後の維持 |
| filtered PendingIntent scan | 必須の予備 | process不在時にも一致広告で起動を試みる | Doze/force-stop中の即時配送 |
| Companion Device presence | 条件付き推奨 | presence時のsystem binding、再接続契機、背景FGS開始の補助 | GATT接続・CCCD購読の代行 |
| `PARTIAL_WAKE_LOCK` | 実験的に採用 | 通常時のCPU sleep抑制 | Doze回避。Doze中はwake lockが無視され得る |
| battery optimization除外 | 配布条件を確認して採用 | Doze/App Standby制限の緩和、背景FGS開始例外 | OEM killの完全回避 |
| `location` FGS type | 位置を継続取得する時だけ | 背景位置取得 | BLE接続維持そのもの |
| `START_STICKY`/boot自動復旧 | 初版不採用 | 将来の監視復旧候補 | arm復元、安全な自動発信 |

### 1.2 特に重要な訂正

- Android公式は長時間のPeripheral Notify監視について、`CompanionDeviceService` を多くのアプリで最適、`connectedDevice` FGSも選択肢としている。Companion APIは検証対象に入れる価値が高い。
- `BluetoothGatt` はprocess-boundであり、プロセスがkillされると接続が閉じる。FGS、wake lock、PendingIntent scanのどれも既存GATTをprocess death越しに保存しない。
- `SCAN_MODE_LOW_LATENCY` はforeground中だけの利用が推奨され、アプリがforegroundでなければ `LOW_POWER` が強制される。MAXで指定はするが、実効duty cycleは端末依存として測る。
- `PARTIAL_WAKE_LOCK` はDozeを解除しない。Android公式はDoze中にwake lockを無視すると記載している。保持中表示だけで監視健全と判定しない。
- `POST_NOTIFICATIONS` はAndroid 13以降でもFGS開始のOS上の必須条件ではない。ただしLIFELiNKでは状態をユーザーが確認できない運用を避けるため、製品ポリシーとして拒否時は開始しない。
- BLE用途だけならAndroid 12以降は `BLUETOOTH_SCAN` の `neverForLocation` 宣言で位置権限を避けられる場合がある。しかし一部BLE beaconが結果から除外され得るうえ、本アプリは発報時に位置を扱う。Beacon受信と位置取得を実機で分けて検証する。

## 2. 所有権とコンポーネント境界

Android実装が作られたら、次の責務分離を維持する。

```text
Activity / Compose UI
  - 権限説明、association、監視開始、arm、状態表示
  - BluetoothGattを保持しない

MonitoringService (connectedDevice FGS)
  - MonitoringSessionの唯一のowner
  - GattController、BeaconRegistration、WakeLock、期限timerを所有
  - 常駐通知を更新

CompanionPresenceService (任意)
  - presence callbackだけを受ける
  - GATTを所有しない
  - 実行中MonitoringServiceへ再接続契機を渡す
  - セッションが無ければ勝手にarmしない

BeaconReceiver
  - PendingIntent scan結果をparse・検証
  - GATT/電話APIを直接呼ばない
  - 共通TriggerIngressへ候補イベントを渡す

TriggerIngress -> EventGate -> SafetyGate
  - GATT/Beaconの共通化、重複排除、鮮度、arm、cooldown
  - 通過後だけ位置取得とbackend要求へ進む
```

`MonitoringService` と `CompanionPresenceService` の両方が `connectGatt()` を呼ぶ構成は禁止する。常に一つのowner、一つのactive `BluetoothGatt`、直列化されたGATT operation queueとする。

## 3. セッション状態モデル

### 3.1 監視状態

```text
STOPPED
  -> STARTING
  -> CONNECTING
  -> CONNECTED
  -> DISCOVERING
  -> SUBSCRIBING
  -> READY

READY/CONNECTING/... -> DEGRADED(reason)
DEGRADED -> RECONNECT_WAIT -> CONNECTING
any -> STOPPING -> STOPPED
```

`READY` の条件はすべてANDとする。

- FGSが有効で、常駐通知を表示済み
- 監視セッションが期限内
- Bluetooth ON、必要権限あり
- 対象deviceとのGATT接続が `STATE_CONNECTED`
- service discovery成功、期待するservice/characteristic UUIDが一意に存在
- `setCharacteristicNotification(..., true)` 成功
- CCCDへ `ENABLE_NOTIFICATION_VALUE` をwriteし、成功callback受信
- Peripheral epochを現在セッションの値として確立
- GATT operation queueに破損・timeoutがない

接続callbackだけで `READY` にしない。CCCD再購読前に届いたcallback、旧Gatt instanceから届いたcallback、旧epochのNotifyは発信候補にしない。

### 3.2 世代管理

各接続試行に単調増加の `connectionGeneration` を付ける。callbackはGatt instanceとgenerationの両方が現行値に一致するときだけ処理する。stop/reconnect時は先にgenerationを更新し、旧callbackを無効化してから `disconnect()` / `close()` する。

各監視開始にランダムな `monitoringSessionId` を発行する。これは電話のarmとは別であり、process death/reboot後にarmを復元しない。

## 4. GATT接続と再接続

### 4.1 初回接続

1. UI表示中にFGSを開始し、5秒以内に `startForeground()` する。
2. 保存済みassociation/device identityを検証する。
3. 既知の `BluetoothDevice` へ `connectGatt()` する。最初の実機比較では `autoConnect=true` と `false + 手動backoff` をA/B測定する。
4. 接続後はGATT operationを必ず一つずつ実行する。
5. service discovery、MTU（必要な場合のみ）、Notify設定、CCCD writeの順で進める。
6. `READY` 到達時刻を記録し、通知を即時更新する。

Android公式上、`autoConnect=true` はPeripheralが利用可能になったとき自動接続し、Peripheral起因または圏外切断後も再接続を試みる。まずこれを基準実装とし、同じGattに対して独自reconnect loopを重ねない。

### 4.2 bounded reconnect

Bluetooth stack errorや端末差で `autoConnect` が回復しない場合に限り、Gattをcloseして新規接続を作る。

- backoff候補: 1、2、4、8、16、30、30秒（jitter ±20%）
- 連続7回または5分でactive retryを停止し `DEGRADED(RECONNECT_EXHAUSTED)`
- 停止後もセッション期限まではCompanion presence、filtered scan、Bluetooth ON、ユーザーの通知操作を再試行契機にできる
- 同時接続試行は禁止
- Bluetooth OFF中は回数を消費せず待機し、ON後に新generationで再開
- 権限取消、association消失、service/characteristic不一致はretryせず安全停止
- GATT status 133等を個別の「成功扱い」にせず、status、newState、試行番号、経過時間を記録

`autoConnect=true` と手動backoffのどちらを本採用するかは、対象Samsung実機での「距離離脱10分後の復帰時間」と「Bluetooth OFF/ON復帰時間」で決める。

### 4.3 Peripheral側の要件

- XIAOは接続中も必要に応じてconnectable advertisingを再開できること
- stable public/static random address、またはbond済みResolvable Private Addressを使うこと
- Companion presenceを使う場合、OSが解決できないrotating random MACを避けること
- Notifyは `epoch + eventId + action`、ACKは `epoch + eventId`
- CCCD有効前はNotifyを送らない
- ACK timeoutで同一eventを再送する場合、Android側で必ず同一eventとして重複排除できること
- 再接続時に未ACKの旧押下を自動送信するかは初版では「送らない」。緊急用途でstore-and-forwardを採る場合は、イベント発生時刻と明確な鮮度上限をprotocolへ追加する

## 5. Beacon予備経路

### 5.1 登録

API 26以上では `BluetoothLeScanner.startScan(filters, settings, pendingIntent)` を使う。公式に、processが常時存在しない場合にscan結果でprocessを開始する用途が示されている。

- Filter: Apple manufacturer IDだけでなく、専用UUID、major、minorまで可能な限りcontroller側で絞る
- callback: `CALLBACK_TYPE_ALL_MATCHES`
- report delay: `0`
- requested mode: MAXセッション中は `SCAN_MODE_LOW_LATENCY`
- 同一のPendingIntent identityを登録・停止で再利用し、`FLAG_CANCEL_CURRENT` を使わない
- scan API戻り値、`EXTRA_ERROR_CODE`、登録時刻を記録

ただし、background時は `LOW_POWER` が強制され得る。`LOW_LATENCY` 指定を受信保証として表示しない。API 34の `ALL_MATCHES_AUTO_BATCH` は画面OFF時に最低10分batchとなるため、本用途では使わない。

### 5.2 イベント同一性

標準的な固定UUID/major/minorだけでは、GATTの `epoch/eventId` と厳密に同一イベントだと証明できない。次の優先順で統合する。

1. firmwareが広告payloadに同じepoch/eventIdの短縮表現またはmanufacturer dataを載せられるなら、完全一致で重複排除する。
2. 変更できないiBeaconなら、device identity + action + 受信時刻による短いdedup windowを使い、`dedupReason=temporal_fallback` を記録する。
3. GATT `READY` 中にBeaconも届いた場合、同一性を確認できるときだけGATTを優先する。不明なイベントを無条件に捨てない一方、Safety gateのarm一回制約で二重発信を防ぐ。

Beacon受信を理由にGATTを切断しない。Beacon受信からAndroid 12以降に新規FGSを起動できるとは限らないため、実行中セッションへの入力か、短いWorkerでの記録に限定する。Companion権限または他の正当な例外がある場合だけ背景FGS起動を試す。

## 6. Companion Device採用案

### 6.1 推奨条件

次を満たすならassociationとpresence observationを追加する。

- XIAOをユーザーが一台ずつ明示関連付けできる
- stable addressまたはbond済みRPAを使える
- 対象端末が `FEATURE_COMPANION_DEVICE_SETUP` を持つ
- API 31以上で `CompanionDeviceService` を利用できる

API 31〜35ではaddress版 `startObservingDevicePresence()`、API 36以上では `ObservingDevicePresenceRequest` を使う。presence中はsystemがserviceをbindしてprocess優先度を上げるため、FGSだけより回復契機を増やせる。

必要候補:

- `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`
- 原則 `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`
- 本当にprocess常駐が不可欠で審査・電池影響を許容する場合のみ `REQUEST_COMPANION_RUN_IN_BACKGROUND`
- serviceに `BIND_COMPANION_DEVICE_SERVICE`

### 6.2 明確な限界

Companion associationはpairing/GATT connectionではない。Presence APIはBLE addressのscanまたはBluetooth接続状態を観測し、GATT service discovery、CCCD、Notify、ACKを実行しない。`CompanionPresenceService` は再接続を依頼するだけで、監視セッションもarmも生成しない。

## 7. FGS、位置、権限

### 7.1 Manifest候補

- `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`（Android 12以上）
- `BLUETOOTH`、`BLUETOOTH_ADMIN` に `maxSdkVersion=30`（API 30以下対応時）
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_CONNECTED_DEVICE`（target 34以上）
- `POST_NOTIFICATIONS`（Android 13以上、製品上必須扱い）
- `WAKE_LOCK`
- `ACCESS_COARSE_LOCATION` / `ACCESS_FINE_LOCATION`
- `ACCESS_BACKGROUND_LOCATION`（背景で位置を取得する製品要件が確定した場合のみ）
- `FOREGROUND_SERVICE_LOCATION`（location typeを実際に使う場合のみ）
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（Play policy適合性を確認して採用）
- Companion関連permission（6章の採用時のみ）

service typeは常に最小化する。GATT維持だけなら `connectedDevice`。監視中に継続位置を取得する場合だけ `connectedDevice|location` とし、Android 14以上のwhile-in-use制約に備えてUI表示中に開始する。背景からlocation typeを作るには原則 `ACCESS_BACKGROUND_LOCATION` も必要になる。

### 7.2 開始前チェック

- Bluetooth adapterと対象機能が利用可能
- runtime Bluetooth権限
- 通知権限（LIFELiNK独自要件）
- 選択したservice typeのpermissionとruntime prerequisites
- 位置取得を有効にする場合は位置サービスと位置権限
- association/device identity
- battery optimization除外（MAXモードの必須条件にするか製品判断）
- Safety gateのdry-run初期値、arm状態、登録先の安全性

設定画面から戻っただけでは開始・armしない。ユーザーが再度開始操作を行う。

## 8. WakeLockと期限

- `PARTIAL_WAKE_LOCK` はnon-reference-counted、session固有tag、2時間timeout付き
- FGS開始・通知表示後に取得し、`finally` 相当の共通stop pathで解放
- `isHeld` を状態表示・ログに使うが、受信可能判定には使わない
- Doze突入前後でNotify/Beacon受信を実測する
- 期限はwake lock timeoutだけに依存せず、`elapsedRealtime()` によるsession deadlineで判定する
- process death後に永続化されたdeadlineを見つけても自動armしない。初版は監視も手動再開

2時間到達、通知の停止action、FGS Task Managerからの停止、権限取消、association消失、永続化失敗では同じ停止手順を使う。

```text
generation無効化 -> disarm -> scan停止 -> GATT disconnect/close
-> presence停止（製品方針による） -> WakeLock解放
-> session状態保存 -> foreground停止 -> stopSelf
```

## 9. Safety gateとの分離

監視継続と発信許可を完全に別state machineにする。

- 監視開始でarmしない
- armは明示操作から15分、一回限り
- 発信試行後60秒cooldown
- reboot/process death/service停止で必ずdisarm
- dry-runが初期値
- emergency number、MMI、未登録番号を拒否
- ACK、backend HTTP成功、Twilio受付、実着信、AI接続を別状態として記録
- 認証切れは監視を直ちに切る理由にしなくてよいが、発信は拒否してUI/通知に安全な状態だけ表示

## 10. 常駐通知

表示する情報:

- `接続中`、`準備完了`、`再接続中`、`Bluetooth OFF`、`要確認`、残り時間
- 停止action
- 必要ならアプリを開くaction

表示しない情報:

- 電話番号、連絡先名、BLE address/UUID、eventId、位置、認証状態の詳細、失敗payload

`READY`喪失・復帰は即時更新する。通知を出せない状態ではLIFELiNKのMAX監視を開始しない。

## 11. 復旧ポリシー

| 状況 | 初版の挙動 |
| --- | --- |
| Activity終了 | FGS/GATTを継続 |
| task swipe | 端末差を測定。`onTaskRemoved()`で勝手に再armしない |
| Bluetooth OFF | `DEGRADED`、retry停止、ON後に再接続 |
| 一時圏外/GATT error | bounded reconnect |
| process kill | GATT喪失。PendingIntent/CDM callbackが届いてもarm復元なし |
| force-stop | 復旧不可。PendingIntent、receiver、serviceに依存しない |
| reboot | 初版は監視・armとも手動再開 |
| app update | `MY_PACKAGE_REPLACED`からの自動監視は実機・ポリシー確認後 |
| FGS Task Managerの停止 | 完全停止・disarm、ユーザー再開待ち |

boot復旧を将来採る場合も、associationと「監視を復旧してよい」という別同意だけを保存し、armは保存しない。Android 12以降のbackground FGS開始制限とAndroid 14以降のlocation while-in-use制限を満たす端末条件を先に確認する。

## 12. 実機試験計画

### 12.1 対象matrix

最低限:

- Samsung Galaxyの実対象機種 / One UI版
- PixelまたはAOSP寄り端末
- Android 12、14、16のうち利用可能な実機
- 充電中/非充電、battery optimization ON/OFF、Samsung Never sleeping有/無
- Companion presence有/無
- `autoConnect=true` / 手動backoff

### 12.2 シナリオ

1. 画面ONで `CONNECTED -> SUBSCRIBED -> READY`、Notify一回、ACK一回。
2. 同一eventId、過去eventId、epoch変更、旧generation callbackを拒否。
3. 画面OFF/ロック後1、10、30、60、120分で各10回押下。
4. `adb shell dumpsys battery unplug` 後、`adb shell dumpsys deviceidle force-idle` でDozeを強制し、GATTとBeaconを別々に測定。
5. `adb shell am set-inactive com.rtree.LIFELiNK true` でApp Standby相当を確認。
6. 距離離脱1/10/30分後の復帰、Bluetooth OFF/ON、Peripheral reboot。
7. Activity終了、task swipe、low-memory、FGS Task Manager停止、force-stop。
8. 通知・Bluetooth・位置権限取消、battery optimization除外解除、Samsung sleeping設定変更。
9. 2時間期限と停止actionでGATT/scan/wake lock/armが全停止。
10. ネットワーク断、Firebase token失効、backend timeoutでも二重発信しない。

試験後は必ず次で端末状態を戻す。

```bash
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset
adb shell am set-inactive com.rtree.LIFELiNK false
```

### 12.3 記録と合格指標

全ログは `elapsedRealtime` とwall clockを併記し、秘密情報を含めない。

- session/connection generation、状態遷移、reason、GATT status
- screen/lock/charging/Doze/Bluetooth/location/WakeLock/battery optimization
- Notify受信・ACK完了・Beacon受信・dedup判定の時刻と匿名化event key
- reconnect開始から `READY` までの時間
- backend要求、Twilio受付、実着信、AI接続の個別結果

初期合格案:

- 2時間セッションの `READY` 時間率 99%以上（通常据置条件）
- `READY` 中のGATT押下受信 100/100、重複発信0
- 30秒以内の再READY 95%以上（短時間圏外/Bluetooth復帰）
- Beacon単独は保証値を置かず、状態別のp50/p95配送遅延と欠落率を報告
- force-stop後0受信を期待結果として明記

安全用途なので、少数回成功だけで「常時」と表示しない。端末型番・OS・設定ごとに結果を公開できる形式で残す。

## 13. Samsung運用

対象端末ではユーザー案内と開始時health checkに次を含める。

- Settings > Battery and device care > Battery > Background usage limits
- Deep sleeping apps / Sleeping appsから除外
- Never sleeping appsへ追加
- アプリ個別BatteryをUnrestricted（名称・場所はOne UI版で変わる）
- Power saving、Adaptive battery、unused app permission resetの影響確認

Samsung公式はDeep sleeping appsを「開いた時だけ動き、backgroundでは動かない」、Never sleeping appsを「backgroundでsleepしない」と説明している。ただし設定名・実効性はOS updateで変わる。設定済みを保証根拠にせず、update後も再試験する。

## 14. 統合時の作業分割

他チームとの競合を避けるため、以下の単位で担当を分ける。

1. `ble-core`: protocol parser、generation、state reducer、dedup。Android API非依存のunit test付き。
2. `gatt-android`: GattControllerとoperation queue。Service/UI/backendを触らない。
3. `monitoring-service`: FGS、通知、WakeLock、期限、stop path。
4. `beacon-android`: ScanFilter、PendingIntent、Receiver。TriggerIngressまで。
5. `companion-android`: association/presenceの実験feature flag。
6. `firmware`: address方針、advertising、Notify/ACK、event identity。
7. `device-test`: adb harness、ログ収集、matrix結果。

統合前に決める必要がある項目:

- minSdkと対象Samsung機種/One UI版
- firmwareのMAC address方式とbonding可否
- Beacon広告にGATTと共通event identityを載せられるか
- `location` FGSを監視開始時から使うか、発報後だけ取得するか
- Companion Device権限とbattery optimization除外のPlay policy方針
- 2時間後に完全停止するか、監視だけ手動延長UIを出すか

## 15. 参照した公開情報

一次資料を判断根拠とし、端末依存情報は補助資料として区別する。

- Android: BLE background communication
  https://developer.android.com/develop/connectivity/bluetooth/ble/background
- Android: Foreground service types
  https://developer.android.com/develop/background-work/services/fgs/service-types
- Android: Restrictions on starting FGS from background
  https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Android API: `BluetoothLeScanner`
  https://developer.android.com/reference/android/bluetooth/le/BluetoothLeScanner
- Android API: `ScanSettings`
  https://developer.android.com/reference/android/bluetooth/le/ScanSettings
- Android API: `CompanionDeviceManager`
  https://developer.android.com/reference/android/companion/CompanionDeviceManager
- Android API: `CompanionDeviceService`
  https://developer.android.com/reference/android/companion/CompanionDeviceService
- Android: Doze and App Standby
  https://developer.android.com/training/monitoring-device-state/doze-standby
- Android: Request special access / battery optimization
  https://developer.android.com/training/monitoring-device-state/doze-standby#support_for_other_use_cases
- Samsung公式: Sleeping apps on Galaxy
  https://www.samsung.com/us/support/answer/ANS00088422/
- OEM挙動の補助資料（一次資料ではない）: Don't kill my app - Samsung
  https://dontkillmyapp.com/samsung

## 16. 最終判断

実装候補は「FGSかCompanionかBeaconか」の一択ではなく、役割を重ねる。

```text
GATT Notify = 通常時の主配送
connectedDevice FGS = 明示セッションと可視性、GATT owner
Companion presence = process優先度と再接続契機の補助
PendingIntent Beacon = GATT断の予備観測
Safety gate = どの経路でも発信を一回に制限
実機測定 = 利用可能性を判定する唯一の根拠
```

それでもforce-stop、権限取消、Bluetooth OFF、OEM kill、無線断、Peripheral障害は越えられない。UIでは `READY` を「現在、購読まで確認済み」とだけ表現し、未来の押下配送を保証する文言を使わない。