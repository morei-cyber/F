# FOCUS ROOM Native Android 2.0

FOCUS ROOMをWebView/HTMLなしのAndroidネイティブアプリとして作り直したソースです。Kotlin + Android SDKのViewだけで構成しています。サーバー接続やWebViewは使いません。

## 実装した内容

- ネイティブ画面、専用アプリアイコン
- 集中/休憩タイマー（初期値25分/5分、設定変更可能）
- 集中終了後に休憩を自動開始し、休憩終了後に次の集中へ自動切替
- タイマーの終了時刻を端末内に保存し、アプリ復帰時に残り時間を再計算
- タスクの追加/完了/削除、集中目標、気が散った回数
- オフライン生成の雨風ノイズ/ホワイト/ピンク/ブラウンノイズ
- 終了音、バイブレーション、終了時通知
- 今日の集中分数と完了回数
- Device Owner登録済み端末でAndroid Lock Task Modeを使用
- 緊急解除PIN（FOCUS ROOMロゴ長押し）
- Android Enterprise QR provisioning用のGET_PROVISIONING_MODE / ADMIN_POLICY_COMPLIANCEハンドラー

## 強ロックについて（重要）

通常インストールだけでは「画面固定」にしかなりません。本格ロックは、このアプリがAndroidの **Device Owner（端末所有者）** として登録され、アプリがLock Task Modeの許可リストに入った場合だけ有効です。Device Owner時はLock Task機能を最小化し、Home/Recents/通知領域などを制限します。

### 端末の初期化について

既にセットアップ済みの個人用Galaxyに、後から通常の設定画面だけでDevice Ownerを追加することはできません。一般的なDevice Owner登録は、初期化後の初期セットアップ中にQRコード等で行います。**初期化するとGalaxy内の写真・アプリ・ファイル・設定等が消える可能性があります。バックアップが完了し、初期化に同意できるまでは絶対に実施しないでください。**

AndroidのDevice Owner QR登録では、登録対象の署名済みAPK、ダウンロードURL、署名チェックサム等が必要です。このリポジトリだけではまだQR登録コード/チェックサムを生成していません。まずAPKをビルドし、署名鍵を固定し、HTTPSで配布できる状態にする必要があります。

### ロック中の終了方法

- タイマーが集中を完了すると自動でLock Task Modeを解除して休憩へ切り替えます。
- 緊急解除は画面上部のFOCUS ROOMロゴを長押しし、設定した4～8桁PINを入力します。
- PINを忘れると、Device Ownerの状態によっては端末初期化でしか復旧できない場合があります。必ず安全な場所に控えてください。

### 休憩終了時の制約

休憩中は他アプリを使えるようにLock Task Modeを解除します。休憩終了時にFOCUS ROOMが前面にあれば、自動で集中画面/Lock Task Modeへ戻ります。休憩中に別アプリを前面で使っている場合、Androidはアプリが勝手に他アプリの上へ割り込むことを原則制限しています。その場合は終了通知を表示し、FOCUS ROOMを開いた瞬間に集中モードへ切り替えてロックします。**別アプリを使っている最中に100%強制的に前面へ奪い返す仕様はAndroidの通常アプリでは保証できません。**

## GitHub ActionsでAPKを作る

1. このフォルダーの中身を新しいGitHubリポジトリのルートへアップロードします。
2. GitHubの **Actions** タブを開きます。
3. **Build FOCUS ROOM Android APK** を選び、**Run workflow** を実行します。
4. 完了した実行を開き、Artifactsの `FOCUS-ROOM-debug-apk` をダウンロードします。
5. ZIPを展開し、`app-debug.apk` をGalaxyへ転送してインストールします。

このActions出力はテスト用debug署名APKです。**Device Ownerとして登録する本番利用に使う前に、継続利用する署名鍵を固定する必要があります。** Actionsの実行ごとに別のdebug署名鍵が作られる場合、後の更新APKを上書きインストールできなくなります。Device Owner登録や初期化は、署名鍵と配布APKが確定するまで行わないでください。

## ビルド設定

- applicationId: `jp.focusroom.nativeapp`
- minSdk: 26 (Android 8.0)
- targetSdk / compileSdk: 35
- JDK: 17
- Gradle: 8.11.1
- Android Gradle Plugin: 8.9.2
- Kotlin: 2.1.10

## テスト時の注意

- Device Owner未登録のGalaxyではアプリがその状態を明示し、タイマーのみ/解除可能な画面固定での試用を選べます。
- Android 13以降は通知許可が必要です。
- Android 12以降は正確なタイマー通知のために「アラームとリマインダー」権限を許可すると安定します。未許可時は省電力状態で通知が遅れることがあります。
- Samsungの省電力設定でアプリのバックグラウンド動作が制限されると、休憩終了通知が遅れる場合があります。

## 旧HTML版からのデータについて

このネイティブ版は別パッケージID (`jp.focusroom.nativeapp`) の新しいアプリです。旧HTML/PWAのブラウザ保存領域（localStorage）や統計データは、自動では引き継がれません。この初期ネイティブ版には旧HTML JSONバックアップのインポート機能もまだ含めていないため、旧アプリの記録を残したい場合は旧版を削除せず保管してください。
