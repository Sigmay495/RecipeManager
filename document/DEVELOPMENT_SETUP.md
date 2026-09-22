# RecipeManager 開発環境構築手順

## 1. 対象環境

本手順はWindowsとPowerShellを基準とする。Android Studioを使用する場合も、プロジェクトとして開くフォルダは`src`とする。

## 2. 必要なソフトウェア

- Git
- JDK 17
- Android Studio、またはAndroid SDK Command-line Tools
- Android SDK Platform 37.0
- Android SDK Build Tools 36.0.0
- Android SDK Platform-Tools
- 端末テストを行う場合はAndroid EmulatorとAndroid 8.0（API 26）以降のSystem Image

Gradle本体を別途インストールする必要はない。リポジトリに含まれるGradle Wrapperを使用する。

## 3. リポジトリの取得

```powershell
git clone https://github.com/Sigmay495/RecipeManager.git
cd RecipeManager
```

## 4. Android SDKの指定

`src/local.properties`を作成し、Android SDKの絶対パスを記載する。このファイルは端末固有のためGitへ登録しない。

```properties
sdk.dir=C\:\\Users\\ユーザー名\\AppData\\Local\\Android\\Sdk
```

Android Studioを使う場合は、最初に`src`を開いた際に自動生成されることがある。

## 5. 初回確認

PowerShellで次を実行する。

```powershell
cd src
.\gradlew.bat --version
.\gradlew.bat testDebugUnitTest lintDebug copyDebugApkToDst
```

成功すると、開発・動作確認用APKが`dst/recipe-manager-debug.apk`へ生成される。

## 6. Android端末テスト

Android StudioのDevice ManagerなどでAndroid 8.0（API 26）以降のエミュレーターを作成し、起動する。最低対応環境の確認ではAPI 26、メモリ2GB以上を使用する。

接続確認後、次を実行する。

```powershell
cd src
.\gradlew.bat connectedDebugAndroidTest
```

実機を使用する場合は、開発者向けオプションとUSBデバッグを有効にする。個人データを含む実利用端末では、テストによるデータ変更に注意する。

## 7. よくある問題

### Android SDKが見つからない

- `src/local.properties`の`sdk.dir`を確認する。
- Windowsパスの区切りとドライブ記号が正しくエスケープされていることを確認する。

### Android設定ディレクトリへのアクセスが拒否される

必要に応じて、書き込み可能なAndroid設定ディレクトリを指定してからGradleを実行する。

```powershell
$env:ANDROID_USER_HOME = "$env:USERPROFILE\.android"
```

### 端末テストで「No connected devices」と表示される

- エミュレーターまたは実機が起動していることを確認する。
- `adb devices`で端末が`device`状態になっていることを確認する。

### 依存関係の取得に失敗する

- 初回ビルド時はインターネット接続が必要である。
- プロキシ、ファイアウォール、Gradleのキャッシュ状態を確認する。

## 8. Releaseビルド

日常利用・配布用のRelease APKには固定署名鍵が必要である。署名鍵を準備するまではDebug APKだけを使用し、具体的な手順は`document/RELEASE_GUIDE.md`を参照する。
