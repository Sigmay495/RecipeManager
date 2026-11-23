# RecipeManager リリース手順書

## 1. 前提

- 日常利用向けには、固定した署名鍵で署名したRelease APKを使用する。
- 署名鍵、`keystore.properties`、パスワードはGitへ登録しない。
- 同じアプリを上書き更新するには、同じアプリIDと署名鍵を継続して使用する。
- リリース作業とGitHubへの公開は、ユーザーの明示的な指示を受けて実施する。

## 2. 初回だけ行う準備

1. Javaの`keytool`でRelease用の署名鍵を作成する。
2. 署名鍵をリポジトリ外の安全な場所へ保存する。
3. 署名鍵と復旧手順を暗号化した別媒体へバックアップする。
4. `keystore.properties.example`を`keystore.properties`としてコピーし、実際の保存場所、エイリアス、パスワードを設定する。
5. `keystore.properties`と署名鍵がGitの追跡対象外であることを確認する。

署名鍵またはパスワードを失うと、同じアプリとして上書き更新できなくなるため注意する。

## 3. リリース前確認

1. 要件定義書・設計書・README・CHANGELOGを更新する。
2. `versionName`と`versionCode`を更新する。
3. 翌年までの日本の祝日が内蔵マスターへ収録されていることを確認する。
4. JVM単体テスト、Android端末テスト、Lintを実行する。
5. バックアップ・復元、オフライン起動、既存データを保持した上書き更新を確認する。
6. MIT Licenseの著作権表示と、`THIRD_PARTY_NOTICES.md`に記載した依存ライブラリのライセンス・NOTICEを確認する。
7. テスト結果を`document/TEST_REPORT.md`へ記録する。

### 3.1 署名に応じた端末移行の確認

移行試験は、合成データを登録した検証端末で行う。旧版でアプリ外のフォルダーにバックアップを保存し、ファイルを読み出せることを確認する。

| 経路 | 操作と確認 |
| --- | --- |
| 同じ署名のRelease間の更新 | 旧版と新版のアプリID・公開証明書のSHA-256を照合し、新版の`versionCode`が旧版より大きいことを確認する。旧版が端末に入った状態で新版を上書きし、起動後に既存レシピ・献立・履歴などを確認する。 |
| 署名が異なるDebug版からRelease版への移行 | 旧版のバックアップを確保してから旧アプリをアンインストールし、Release版を導入する。「その他」→「データ管理」→「復元ファイルを選択」で旧バックアップを開く。内容を確認して全件置換し、移行したデータを確認する。 |

公開証明書の照合には `apksigner verify --print-certs` を使用する。v2.00の復元は形式1.0と2.0に対応する。検証専用鍵による結果と、配布に使う正式署名での結果はテスト報告書に個別に記録する。

## 4. Release APKの生成

リポジトリ直下に有効な`keystore.properties`を用意してから実行する。

```powershell
cd src
.\gradlew.bat testDebugUnitTest lintDebug assembleRelease copyReleaseApkToDst
```

署名済みAPKは`dst/recipe-manager-release.apk`へ出力される。生成後は署名状態、ファイルサイズ、SHA-256を確認する。

## 5. GitHubでの公開

1. 変更内容をレビューしてコミットする。
2. `main`をPushする。
3. バージョンと同名の注釈付きGitタグを作成してPushする。
4. ユーザーの指示を受けてGitHub Releaseを作成する。
5. 署名済みRelease APKと変更内容をGitHub Releaseへ登録する。
6. READMEのダウンロード案内が実際の公開先と一致していることを確認する。
