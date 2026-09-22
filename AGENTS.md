# RecipeManager 作業ガイド

このファイルは、Codexなどの開発エージェントが新しいセッションで本プロジェクトを安全に扱うための入口である。このリポジトリ全体に適用する。

## 作業開始時

作業へ着手する前に、次の順で関連文書を確認する。

1. `document/DEVELOPMENT_RULES.md`
2. `document/REQUIREMENTS.md`
3. `document/OVERVIEW_DESIGN.md`
4. `document/DETAILED_DESIGN.md`
5. `CHANGELOG.md`
6. 依頼内容に応じて`document/TEST_REPORT.md`、`document/RELEASE_GUIDE.md`、`document/DEVELOPMENT_SETUP.md`

既存文書と依頼内容が矛盾する場合や、判断によって仕様が変わる場合は、実装前にユーザーへ確認する。

## 開発手順

- ドキュメント駆動で進め、要件・設計へ影響する変更では、ソースコードより先に該当文書を更新する。
- 各工程または成果物の完成時に作業を止め、ユーザーレビューを依頼する。
- ユーザーの承認または明示的な次工程への指示があるまで、先の工程へ進まない。
- 指示された範囲外の機能追加や大規模な整理を行わない。
- 既存データ、Import、バックアップとの互換性を維持する。
- DB構造を変更する場合はRoom Migrationを用意し、破壊的再作成を行わない。

## Git・リリース

- ユーザーから明示的な指示がない限り、コミット、タグ作成、Push、Pull Request、GitHub Release作成を行わない。
- Git操作前に対象差分を確認し、ローカル設定、キャッシュ、APK、署名鍵、認証情報、利用者データが含まれていないことを確認する。
- Release署名鍵、`keystore.properties`、パスワードをリポジトリへ登録しない。
- リリース作業は`document/RELEASE_GUIDE.md`に従う。

## 検証

変更範囲に応じて、最低限次を実行する。

```powershell
cd src
.\gradlew.bat testDebugUnitTest lintDebug copyDebugApkToDst
```

Android端末またはエミュレーターが利用できる場合は、次も実行する。

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

- テストを省略した場合は理由と未確認範囲を報告する。
- テスト結果やAPKが変わった場合は`document/TEST_REPORT.md`を更新する。
- バージョンごとの変更は`CHANGELOG.md`へ記録する。

## 主な配置先

- 文書: `document/`
- Androidソース: `src/`
- ローカル生成物: `dst/`
- Import・バックアップSchema: `document/schemas/`
- Importサンプル: `document/samples/`
