# 総合テスト報告書

## 1. 実施概要

- 実施日: 2026-09-22
- 対象: RecipeManager v1.00 確定版 Debug APK
- 判定: 合格
- 実行コマンド: `gradlew testDebugUnitTest lintDebug copyDebugApkToDst connectedDebugAndroidTest`

## 2. 実施環境

- PC: MAG-MINI8
- 最低対応環境: Android 8.0（API 26）エミュレーター、2 GB RAM
- 追加確認環境: Android 16 エミュレーター
- ネットワーク: オンラインおよびオフライン

## 3. 結果

| 区分 | 件数・確認内容 | 結果 |
| --- | --- | --- |
| JVM単体テスト | 18件 | 全件成功 |
| Android端末テスト | 15件 | 全件成功 |
| Android 8.0互換性 | API 26で全端末テストを実行 | 成功 |
| Android 16互換性 | 全端末テストを実行 | 成功 |
| 静的解析 | Android Lint（Debug） | 成功 |
| APK生成 | `dst/recipe-manager-debug.apk` | 成功 |

端末テストでは、データベース、標準マスター・祝日、レシピ登録・編集・論理削除・復元、Import、バックアップ・復元、主要画面への遷移を確認した。

## 4. 境界・異常系

- レシピImportは100件を受理し、上限を超える101件を拒否することを確認した。
- Import途中の予期しない失敗時に、レシピと利用者追加マスターがロールバックされることを確認した。
- バックアップは往復変換、未対応の新しい形式版、壊れたID参照、復元トランザクションを確認した。
- 献立はご飯不要日、1品・3品構成、不完全な構成、2週間以内の重複回避、手間の偏り、栄養不足警告を確認した。

## 5. 性能確認

- 条件: 500件のレシピから平日5日分を自動提案
- 基準: 5秒以内
- 測定方法: 1回のウォームアップ後に3回測定し、最遅値で判定
- JVMテスト実測: 0.224秒、0.400秒、0.507秒（最遅0.507秒）
- 判定: 合格

実測値は開発PC上のJVMテスト結果であり、実機性能を保証する値ではない。ただし2GB RAMのAndroid 8.0環境を含む端末テストでも、タイムアウトや異常終了は発生していない。

## 6. オフライン・更新確認

- Android 16エミュレーターでWi-Fiとモバイル通信を無効化し、アプリが起動して操作可能な状態になることを確認した。
- Android Manifestにインターネット権限がないことを確認した。
- 既存データを保持した状態でAPKを上書きインストールし、更新前後でデータベースのSHA-256が一致することを確認した。

## 7. 成果物

- APK: `dst/recipe-manager-debug.apk`
- サイズ: 14,514,775 bytes
- SHA-256: `6AFC12B90F329F986CA67FAB860CDC9850785118D0F442C51877AEA43CDA8083`

## 8. 補足

- 食材ロス評価は、販売単位を管理する項目が現行データ形式にないため、標準的な購入量を仮定した概算である。
- 本成果物はDebug APKである。日常利用へ移す前に、実際に使用するAndroid端末で主要操作とバックアップ復元を確認する。
- GitHub ActionsのCI定義は追加済みだが、GitHubへPushするまでは実環境で未実行である。

## 9. v2.00 Schema成果物の検証（2026-10-03）

本章はSchema成果物の検証結果。第1～8章のv1.00実績とは区別し、v2.00のアプリ全体・移行・性能の合格を意味しない。

- 環境: Windows、JDK 24.0.2、現行依存キャッシュのJackson 2.18.4・networknt 2.0.4、Draft 2020-12、format assertions有効。
- 独立検証: `powershell -NoProfile -ExecutionPolicy Bypass -File document/tools/Check-SchemaContracts.ps1`。58項目成功。メタSchema、ローカル参照、1.0保存版のバイト一致、2.0合成サンプルとJSON往復、既存Importサンプル、版・ID・日付・UTC日時・分量・結果・延期・別名・下書きの異常系を確認。
- 旧Schema SHA-256（backup.schema.jsonとbackup-1.0.schema.json共通）: `3A885EEC3B76FEAD9EDA6C358E0D2A1D9A86A088BA33E7D8D20C4B07C98561C0`。
- 回帰確認: `gradlew.bat testDebugUnitTest lintDebug copyDebugApkToDst`を実行。初回はKSPのディレクトリ生成失敗。GRADLE_USER_HOMEを既存キャッシュ、ANDROID_USER_HOMEをdst/android-home、JAVA_TOOL_OPTIONSのuser.homeを実ユーザーディレクトリへ指定して再実行した。
- 再実行は29件中1件失敗。`JsonInputTest.malformedUtf8Utf16AndEmptyInputAreRejected`（77行）でIOExceptionを期待した入力が受理された。この実行時には、Schema作業開始後に共有ワークスペースへ追加されたJSON入力処理・テスト等のソース差分が含まれている。本Schema工程ではそのソースを変更していない。
- `lintDebug copyDebugApkToDst`の個別実行も、並行して追加されたBackupFormatAdapterの`BackupGraphValidator`未解決参照によりcompileDebugKotlinで停止。Lint完了・APK生成を合格扱いしない。本検証で新APKは生成されていない。
- 端末確認: `adb devices`に接続端末なし。connectedDebugAndroidTestは未実施。未確認範囲はAndroid実行、Migration、旧バックアップ変換・復元Adapter、2.0のアプリ経由往復、UI・性能。

Schemaだけでは配列間の参照の実在・一意性・献立成立性を保証しない。参照切れでも構造上は受理されるケースを独立検証へ含め、Adapter必須検査をschemas/README.mdに列挙した。ソース工程の完了後に回帰・端末検証を改めて行う。

## 10. v2.00コーディング第1成果物の検証（2026-10-03）

対象は共通JSON読込・バックアップSchema検証・旧形式変換Adapterと、現行Import／復元の読込への接続。第9章の途中失敗を修正した後の検証結果を本章に記録する。v2.00全体の完了・リリース判定ではない。アプリ表示バージョンは1.00、稼働DBは版1、画面経由バックアップ形式は1.0を維持している。

| 区分 | 内容 | 結果 |
| --- | --- | --- |
| JVM単体テスト | 51件（既存18件、JsonInputTest 11件、BackupFormatAdapterTest 22件） | 全件成功 |
| Android Lint | lintDebug | 成功 |
| Debug APK生成 | copyDebugApkToDst | 成功 |
| 差分検査 | git diff --check | 成功 |
| Android実行 | adb devicesで接続端末なし | connectedDebugAndroidTest未実施 |

読込テストは5MiB／50MiBちょうど・最初の超過バイトでの停止・サイズ不明ストリーム・短い読込・0バイト読込・途中IOException、UTF-8日本語・BOM・高精度小数、空入力・UTF-16／不正UTF-8・重複キー・末尾JSON、Schema外部参照拒否を確認した。変換テストは旧ID・履歴参照・別名・チェック・数量精度の保全、食品群欠落警告、廃止／完全削除の参照保護、旧不完全献立拒否、重複・参照切れ・別名競合、7日範囲・固定カテゴリ、結果／延期の双方向整合、数量不明の往復、下書きの未完成入力・確定参照索引・既存競合保持・未確定材料候補、masterVersion別ID変換を確認した。変換はメモリ上だけで、元データを変更しない。

環境はWindows 11、JDK 24.0.2、Gradle 9.7.1、既存Android SDK（Platform 37、Build Tools 36.0.0）。通常出力先のファイルロックを避けるため、ローカル生成物dst/coding-build.init.gradleでsrc/app/build/codingへ出力した。GradleキャッシュのAAPT2はWindowsのApplication Controlにより実行を拒否されたため、実行可能な既存SDKのAAPT2を明示指定した。実行コマンドはsrcから以下のとおり。

```powershell
$env:GRADLE_USER_HOME = 'C:/Users/8itav/.gradle'
$env:ANDROID_USER_HOME = 'C:/Users/8itav/.vscode/RecipeManager/dst/android-home'
$env:JAVA_TOOL_OPTIONS = '-Duser.home=C:/Users/8itav'
.\gradlew.bat --init-script ../dst/coding-build.init.gradle '-Pandroid.aapt2FromMavenOverride=C:/Users/8itav/.vscode/RecipeManager/.android-sdk/build-tools/36.0.0/aapt2.exe' testDebugUnitTest lintDebug copyDebugApkToDst
```

- APK: dst/recipe-manager-debug.apk（開発・確認用）
- サイズ: 14,194,656 bytes
- SHA-256: `436FE10C0DB804CA51DFCBD13E4BD5508D3ACF86B272733A385ABDE9FB69972F`
- 未確認範囲: Android端末上の表示・読込・取消、Room Migration、変換AdapterのDB接続と復元書込、2.0のアプリ経由往復、後続の材料・献立・下書き・買い物機能、アクセシビリティ・性能・Release移行。端末テストは接続端末がないため省略し、後続機能は未実装のため検証していない。

第1成果物完成時点でユーザーレビューを依頼し、承認後にDB・Migration等の次成果物へ進む。

## 12. 承認済みロゴ反映の検証（2026-10-04）

対象はランチャーアイコンと起動時画面のリソース。承認済み`icon-proposal-realistic.png`の輪郭をVectorDrawableへ転記し、0.66倍の等比縮小で安全領域へ配置した。起動背景はライト／ダークで切り替える。DB・Import・バックアップ形式・アプリバージョンは変更していない。

- 第10章と同じ環境・コマンドで`testDebugUnitTest lintDebug copyDebugApkToDst`を実行。最終調整後の再実行もBUILD SUCCESSFUL。
- 単体テスト51件すべて成功。Lintはエラーなし、警告7件。Androidリソースのコンパイル・APK生成成功。
- 初回はサンドボックスから既存Gradleキャッシュのロックファイルへアクセスできず停止。承認された実行環境で再実行し成功。
- `adb devices`は接続端末なし。connectedDebugAndroidTestは省略。端末でのランチャーマスク、縮小時の識別性、Android 8～11／12以降の起動表示、ライト／ダーク背景、色のちらつきは未確認であり、CR-01の端末受入完了を意味しない。
- 最新APK: `dst/recipe-manager-debug.apk`（開発・確認用）、14,671,122 bytes。
- SHA-256: `AC22490EAE0133A128935EE56A4FFBEAA7EF5032D76E01DA1DF2FCC9D2322A9C`。
- 第7・10章のAPK情報は当時の履歴。第11章の別署名起動用APKは今回更新していない。

作業ガイドに従い、本成果物の完成時点でレビューを依頼し、後続成果物は承認後に進める。

## 11. Debug APKの起動確認（2026-10-03）

ユーザーの「起動して」に従い、既存medium_phoneエミュレーター（Android 16、emulator-5554）を起動した。生成APKとインストール済みアプリのDebug署名が異なったため、既存APKの公開証明書と一致する既存Debug鍵で別の起動用APKを署名し、`adb install -r`で上書きした。アンインストール・データ消去は行っていない。

- 起動用APK: dst/recipe-manager-launch-debug.apk（14,214,957 bytes）
- SHA-256: `DA8E3360486572EF5A0F9795ABCB55093A81A158DD2C54944F02C98EFAEE2EC8`
- `am start -W -n jp.local.recipemanager/.MainActivity`: Status ok、LaunchState COLD。
- dumpsysでMainActivityがtopResumedActivityであること、エミュレーターの画面ウィンドウが存在することを確認。
- 本確認は起動のみ。connectedDebugAndroidTest、各機能・画面操作、データ内容の保全比較は未実施。第10章の元の生成APKは変更していない。

## 13. DB版2基盤・Migration成果物の検証（2026-11-22）

対象は`data/local/v2`の21テーブル、版2 exportSchema、移行前検証、Migration 1→2。既存のアプリDB・AppContainerは版1のままで、専用の一時DBを版1 Roomで作り、版2 Roomで開く実際の更新経路を検証した。版2Repository・画面・復元書込の接続完了を意味しない。

| 区分 | 結果 |
| --- | --- |
| JVM単体テスト | 51件すべて成功 |
| Android端末テスト | API26で26件すべて成功（既存15件、Migration1To2Test 11件） |
| Android Lint | エラーなし、警告9件。警告対象は既存の依存関係・アイコン構成・画面コード |
| Debug APK | copyDebugApkToDst成功、承認済みロゴを維持 |
| 差分形式 | git diff --check成功 |

移行テストは空DB、既存ID・旧履歴ID・材料順・精密なTEXT分量・単位・スナップショット・履歴・食品群全行・旬・設定・metadata・祝日・購入チェックの保全、削除済みレシピの参照有無、実績だけによる最終調理日の再計算、旧手入力列の除去、土日追加、別名の移動とUUID／移行日時、数量不明情報を検証した。重複実績・材料なしレシピ・不要日と料理の矛盾・不完全構成・別名競合・月曜以外の週開始・週外日付を拒否し、元の版1スキーマと全テーブル内容が保持されることを確認した。移行SQL完了直後に例外を注入する試験でも全件ロールバックした。外部キーが有効な接続の移行と参照先の改名も別途検証した。

環境は第10章のWindows／JDK／Gradle／SDKを継続。既存`recipe_api26` AVD（Android 8.0.0、emulator-5556）を`-read-only -no-snapshot -no-window -no-audio -no-boot-anim`で起動し、利用者の保存データへの変更を残さない構成で実施した。移行テストはUUID付きの専用DBのみ作成・削除する。

実行コマンドは第10章の環境変数・init script・AAPT2指定を使用し、以下を別々に実行した。

```powershell
.\gradlew.bat --init-script ../dst/coding-build.init.gradle '-Pandroid.aapt2FromMavenOverride=C:/Users/8itav/.vscode/RecipeManager/.android-sdk/build-tools/36.0.0/aapt2.exe' testDebugUnitTest lintDebug copyDebugApkToDst
$env:ANDROID_SERIAL = 'emulator-5556'
.\gradlew.bat --init-script ../dst/coding-build.init.gradle '-Pandroid.aapt2FromMavenOverride=C:/Users/8itav/.vscode/RecipeManager/.android-sdk/build-tools/36.0.0/aapt2.exe' connectedDebugAndroidTest
```

途中の検証では、テストDBのクローズAPIのコンパイル不一致と、ローカル出力先dst配下のDebug鍵をcopyタスクの出力と見なすGradle依存判定で停止した。クローズ処理を修正し、APK生成と端末テストを別コマンドへ分けて解消した。初回の端末検証ではRoomがonUpgrade時はforeign_keys=0で、onOpen後に有効化する挙動により正常移行が停止した。API26のSQLiteの改名動作に合わせてDDL参照先を分岐し、最終の全26件は成功した。移行処理自体は外部キー設定を切り替えていない。

- 最新APK: `dst/recipe-manager-debug.apk`（開発・確認用）、14,671,181 bytes。
- SHA-256: `3F8F29573C0A8D2B4E1F28B353C25ECAE0588D4E218C2618ACB8BF188D9E1492`。
- 版1 exportSchemaのファイルは変更なし。版2 Schemaは`src/app/schemas/jp.local.recipemanager.data.local.v2.RecipeManagerDatabaseV2/2.json`。
- 未確認範囲: 他Android版でのMigration、利用者の実DB移行、版2Repository・復元接続とバックアップ2.0のDB往復、後続機能、ロゴのランチャーマスク／ライト・ダーク起動表示の目視、アクセシビリティ・性能・Release署名移行。ロゴは今回のAPKにも含むが、端末テスト成功をロゴ表示の受入完了とは扱わない。

本成果物のレビュー後、2026-11-22の「OK」により承認を受領した。次の成果物は材料・レシピ保存処理。承認記録のみの更新で、実装・APK・検証結果の変更はない。

## 14. 材料・レシピ保存処理の検証（2026-11-22）

対象は版2のFoodRepositoryV2／RecipeRepositoryV2、対応DAOと、材料統合・削除保護で共有するDraftFoodMergeSupport。現行画面・AppContainer・版1 DB・バックアップ1.0は変更していない。版2の保存・復元が揃うまでは独立したDB基盤へ実装を蓄積する。

| 区分 | 結果 |
| --- | --- |
| JVM単体テスト | 51件すべて成功 |
| Android端末テスト | API26で43件すべて成功（既存15件、Migration 11件、材料・レシピ17件） |
| Android Lint | エラーなし、既存箇所の警告9件 |
| Debug APK生成 | copyDebugApkToDst成功、承認済みロゴを維持 |
| 差分形式 | git diff --check成功 |

新規の17件は以下を確認した。

- 材料の全食品群・旬・正規化・完全一致別名、標準名／別名の横断競合拒否、内蔵属性の保護と内蔵材料への利用者別名追加。
- 材料・レシピの古いrevisionによる保存拒否。変更なし再保存でrevision・更新日時・材料行IDを維持。
- 基本単位の変更で既存分量・単位を換算しないこと、精密小数をTEXTで保持すること。
- レシピと新規材料の同時保存。先行する材料作成後に後続材料の参照不正で失敗しても、材料を含めて全体をロールバック。分量・材料名100文字・注記200文字の入力検査。
- 確認のない削除、材料・履歴・下書き参照中の削除、固定役割と異なるカテゴリ変更の拒否。廃止・カテゴリ・手間・時間・材料別名による検索。
- 材料統合で同一単位の精密な既知小計を合算し、不明フラグを論理和として保存。異単位を合算せず、利用料理・購入チェックの規則を保持。購入不要の状態が混在する行を再確認用の結果として返す。
- 競合のない下書きはpayload・参照索引・統合で変わった基準だけを更新。入力途中の空の別名候補を保持。既存競合の理由・古い基準revisionを保持。
- 統合後半で不正な下書きの未知項目を検出した場合、材料・別名ID・レシピ・買い物の数量／チェック・更新必要状態・下書き索引をすべて元に戻す。

環境と実行方法は第13章を継続し、read-only・no-snapshot・no-windowで既存API26 AVDを起動した。テストは版2のメモリDBを使用する。`testDebugUnitTest lintDebug copyDebugApkToDst`、続いて`connectedDebugAndroidTest`を別コマンドで実行し、最終はいずれもBUILD SUCCESSFUL。途中で買い物利用料理集合のKotlin型推論によるコンパイルエラーを修正した。レビューで下書き候補の空別名許容と、入力上限・別名照合のSchema整合を修正し、最終43件で再確認した。

- 最新APK: `dst/recipe-manager-debug.apk`（開発・確認用）、14,671,181 bytes。
- SHA-256: `365C332DFE9D7809672766B57A6186690278BD31174F86F380D9FB6A5BA5B219`。
- APKのDEXにFoodRepositoryV2・Migration1To2が含まれることを確認。
- 版2 exportSchemaは変更なし（SHA-256: `9E71C3376626B6F2339067C359289169A7D90FCC686776A37C4D1BE1DD1BEA74`）。
- 未確認範囲: 版2 Repositoryの画面接続、下書き自動保存UI、版2マスタ初期ロード、7日献立・固定・実績・提案・Import・買い物再生成・バックアップ2.0復元の接続、他Android版・実DB、ロゴの目視、アクセシビリティ・性能・Release移行。現行画面から新機能を利用できることは意味しない。

作業ガイドに従い第3成果物のレビューを依頼し、次の献立・固定・実績・提案へは承認後に進める。

その後、同日の「次へ」を第3成果物の承認・第4成果物への開始指示として受領した。

## 15. 献立・固定・実績・延期・提案の検証（2026-11-22）

対象は版2のMenuRepositoryV2、HistoryRepositoryV2、ProposalEngineV2、MenuProposalRepositoryV2と対応DAO。中断時の実装を「再開して」により継続した。現行AppContainer・画面・稼働DB・バックアップ形式は変更していない。

| 区分 | 結果 |
| --- | --- |
| JVM単体テスト | 67件すべて成功（既存51件、提案エンジン16件） |
| Android端末テスト | API26で63件すべて成功（既存43件、実績・延期8件、献立・提案採用12件） |
| Android Lint | エラーなし、既存箇所の警告9件 |
| Debug APK生成 | copyDebugApkToDst成功、承認済みロゴを維持 |
| 差分形式 | git diff --check成功 |

提案エンジンの試験は各カテゴリ20件の5日／7日、500レシピでの成立性・入力順を変えた同一シード再現、実績の0／13／14日と別週予定の前後13／14日、キャッシュだけでは見えない実績、高優先度より先の除外条件、廃止固定と新規廃止除外、経過済み・結果記録済み・固定日・未設定保護、固定重複の警告、全日不要、理由別失敗、採用前の改変検証、負担・栄養・共通主要食材・週内重複なし旬評価を確認した。全候補を使う割当てによる成立性判定と、幅50の評価候補比較を分離している。

端末試験は7日の初期値、祝日と土日、明示必要・未設定、構成・カテゴリ・廃止・固定・revisionの検査、変更なし保存のID維持、不要化の即時取消と破棄・再利用禁止、関連する実績追加による取消停止、結果の関連解除後も履歴を保持することを確認した。実績は同日同Recipeの重複、部分調理・変更・未調理、後日登録・古い実績・旧履歴ID、訂正・削除の確認と古い更新日時の拒否、実際の日付MAXの再計算を確認した。結果記録だけで買い物が変わらないことも検証した。

延期は未確定時に既存の延期先を変更しないこと、確定・取消・固定／結果／古い状態の拒否、確定済み延期先に実績がある場合は結果の訂正を先に求めること、確認後の参照整理で実績を削除しないことを検証した。提案プレビューがDBを書き込まないこと、採用時の生成元変更・日付境界の拒否、固定／結果の保持、採用途中のSQL失敗で先行する日・週までロールバックすることを確認した。

環境・コマンドは第13章と同じ。既存API26 AVDをread-only・no-snapshot・no-windowで起動し、テスト用DBで検証した。`testDebugUnitTest lintDebug copyDebugApkToDst`の後に`connectedDebugAndroidTest`を別実行し、最終はいずれもBUILD SUCCESSFUL。レビューで旬加点の週内重複排除と評価ビーム幅を設計に合わせ、最終67件・63件で再確認した。

- 最新APK: `dst/recipe-manager-debug.apk`（開発・確認用）、14,671,181 bytes。
- SHA-256: `9B2458AD2E352F59EDC420864F87622C38453C1795C3EB607B2FD333B268F4A8`。
- 版2 exportSchemaは変更なし（SHA-256: `9E71C3376626B6F2339067C359289169A7D90FCC686776A37C4D1BE1DD1BEA74`）。
- 未確認範囲: 版2の画面・稼働DB接続、Import・下書きの再起動保存、買い物再生成、バックアップ2.0復元、他Android版・利用者の実DB、ロゴの目視、アクセシビリティ、Release移行。500レシピのJVM成立性確認は、API26・材料1,000件・履歴5年・3回測定の総合性能合格には代用していない。

作業ガイドに従い、第4成果物でレビューを依頼する。次のImport・下書き保存処理は承認後に進める。

その後、同日の「レビューOK」を第4成果物の承認として受領した。

## 16. Import・下書き保存処理の検証（2026-11-22）

対象は版2のDraftDaoV2・DraftRepositoryV2・DraftCommitRepositoryV2と、材料統合時の下書き更新日時。3種類の保存・再開・破棄と、レシピ／Import／献立への確定処理を専用DBで検証した。現行AppContainer・画面・版1 DB・Import Schema 1.0・バックアップ形式は変更していない。

| 区分 | 結果 |
| --- | --- |
| JVM単体テスト | 既存67件すべて成功 |
| Android端末テスト | API26で80件すべて成功（既存63件、下書き・Import17件） |
| Android Lint | エラーなし、既存箇所の警告9件 |
| Debug APK生成 | copyDebugApkToDst成功、承認済みロゴを維持 |
| 差分形式 | git diff --check成功 |

新規17件は、3種類の未完成入力をファイルDBへ保存してclose／再open後も保持すること、無変更保存で日時を維持すること、古い更新日時・確認のない置換／破棄・未知項目を拒否すること、参照先の別編集を競合として保持すること、材料統合後の参照／基準情報と更新日時の前進、レシピと新規材料の原子的確定を確認した。

Importは確認中に材料を登録しないこと、既存Importの置換確認、構文・容量の拒否時に保存済み下書きを失わないこと、入力修正後の再検証、精密分量と共有材料候補の保持、一部エラーの修正／スキップ、廃止候補の識別と別ID・普通での新規追加、確認後に増えた重複の再検出、同一ファイル内重複の明示確認、途中のSQL失敗で先行レシピ・材料も戻して下書きを保持することを確認した。100件が成功し、101件では既存下書きを変更せず拒否した。

献立下書きは、途中の不完全構成で先行日の保存も戻ること、全日成功時だけ下書き・索引を除去すること、保留中に経過済みとなった日の変更を拒否することを検証した。レシピrevisionを変えずに実績だけが追加された場合も警告を再評価し、新しい重複警告を未確認のまま採用できないことを確認した。

環境・コマンドは第13章を継続。API26 AVDをread-only・no-snapshot・no-windowで起動し、専用DBで実施した。永続性の試験では`draft-test-UUID.db`だけを作成・削除する。`testDebugUnitTest lintDebug copyDebugApkToDst`の後に`connectedDebugAndroidTest`を別実行し、最終はいずれもBUILD SUCCESSFUL。重複判定は材料属性を一括読取し、同じ候補について繰り返しDBへ照会しない構成にした。100件の機能試験は、規定データ・環境での3回測定による総合性能合格を意味しない。

- 最新APK: `dst/recipe-manager-debug.apk`（開発・確認用）、14,671,181 bytes。
- SHA-256: `424DE66A95991FCDA85FF05539651C7C5DD7055356D2BEC44E0533E5DA84BE14`。
- 版2 exportSchemaは変更なし（SHA-256: `9E71C3376626B6F2339067C359289169A7D90FCC686776A37C4D1BE1DD1BEA74`）。
- 未確認範囲: 画面での遅延自動保存・プロセス再生成・再開導線、稼働DB接続、買い物再生成、バックアップ2.0往復、他Android版・利用者の実DB、ロゴの目視、アクセシビリティ・総合性能・Release移行。DBの再open試験を画面やプロセス再生成の確認へ代用していない。

作業ガイドに従い第5成果物のレビューを依頼する。次の買い物リスト再生成処理は承認後に進める。

その後、同日の「OK」を第5成果物の承認として受領した。

## 17. 買い物リスト再生成の検証（2026-11-22）

対象は版2のShoppingDaoV2・ShoppingRepositoryV2。保存済み献立からの集計・更新必要判定・状態更新を専用DBで検証した。現行AppContainer・画面・版1 DB・バックアップ形式は変更していない。

| 区分 | 結果 |
| --- | --- |
| JVM単体テスト | 既存67件すべて成功 |
| Android端末テスト | API26で92件すべて成功（既存80件、買い物12件） |
| Android Lint | エラーなし、既存箇所の警告9件 |
| Debug APK生成 | copyDebugApkToDst成功、承認済みロゴを維持 |
| 差分形式 | git diff --check成功 |

新規12件で、必要日だけの精密な小計・利用料理参照、未設定日の警告、g／kgの非換算、不明のみ／既知小計との混在、同量・減少・増加・単位変更・統合でのチェック引継ぎを検証した。購入不要は同一材料を追跡して維持し、複数元の状態混在時は解除と再確認警告を行う。変更のない未チェックg行に、別のチェック済みkg行を理由とする解除警告を出さないことも確認した。

結果記録・訂正・未確定延期では更新必要や数量が変わらず、確定済み延期先は保存献立として集計されることを確認した。材料統合後に数量不明を保持して解除済みチェックを復活させないこと、完全一致別名での材料解決・マスタ変更の検出、消失材料の除去、全日不要での空リストを確認した。献立未保存・古い表示からの状態更新を拒否し、項目削除と親リスト更新の後にSQL失敗を発生させても、既存リスト・全項目・チェック状態が戻ることを確認した。

環境・コマンドは第13章を継続。API26 AVDをread-only・no-snapshot・no-windowで起動し、inMemoryの専用DBで実施した。`testDebugUnitTest lintDebug copyDebugApkToDst`の後に`connectedDebugAndroidTest`を別実行し、最終の結果XMLは単体67件・端末92件とも失敗・エラー0件。必須ビルドとLint・APK生成も成功した。結果記録のみの変更を区別するため、fingerprintに週／日revisionを含めず、実際の集計元と解決先マスタから判定する。

- 最新APK: `dst/recipe-manager-debug.apk`（開発・確認用）、14,671,181 bytes。
- SHA-256: `59E4A9BE516CD98417B7E02AB7A20BF72EB31814D6D1B5EC2014EAAA00B9CF35`。
- 版2 exportSchemaは変更なし（SHA-256: `9E71C3376626B6F2339067C359289169A7D90FCC686776A37C4D1BE1DD1BEA74`）。
- 未確認範囲: 版2画面での再生成・警告表示・チェック操作、稼働DB接続、バックアップ2.0往復、他Android版・利用者の実DB、ロゴの目視、アクセシビリティ・規定データでの3回測定による総合性能・Release移行。保存処理の試験を画面での操作確認へ代用していない。

作業ガイドに従い第6成果物のレビューを依頼する。次のバックアップ2.0出力・復元処理は承認後に進める。

その後、同日の「OK」を第6成果物の承認として受領した。

## 18. バックアップ2.0出力・復元処理の検証（2026-11-22）

対象は版2のBackupDaoV2・BackupCodecV2・BackupRepositoryV2と、材料順序の整数範囲検証。専用DBで全件出力・旧／新形式復元を検証した。現行画面・AppContainer・版1 DB・1.0バックアップの接続は変更していない。

| 区分 | 結果 |
| --- | --- |
| JVM単体テスト | 既存67件すべて成功 |
| Android端末テスト | API26で106件すべて成功（既存92件、バックアップ14件） |
| Android Lint | エラーなし、既存箇所の警告9件 |
| Debug APK生成 | copyDebugApkToDst成功、承認済みロゴを維持 |
| 差分形式 | git diff --check成功 |

新規14件で、全データの往復、3種類の下書き・未完成入力・古い基準情報／既存競合、利用者別名ID・複数食品群・旬、固定・結果・確定延期・旧履歴IDの参照を検証した。買い物の既知小計／数量不明・異単位・購入済み／購入不要・生成元情報を再集計せず保持し、履歴由来の最終調理日と材料由来の栄養だけを再計算する。復元後の専用ファイルDBをclose／再openして下書きと混在数量の保持を確認した。

出力対象を一貫したトランザクションで取得し、同時書込が途中で停止している間に読取を開始しても料理と材料の変更が混在しないことを確認した。出力先を開く時点でDBを変更しても出力済みスナップショットは維持され、ファイル処理中にDBトランザクションを保持しない。write・flush・closeの各失敗、出力先取消、書込後のコルーチン取消で最終日時を維持し、close成功後だけ更新する。保存データの検証失敗では出力先自体を開かない。

読込／close失敗・不正JSON・非対応版・参照切れ・重複ID・不正な不明数量・材料順序の整数桁あふれを、既存データを変更せず拒否した。確認なしの復元、確認中にチェック状態が変更された復元を停止し、入力ByteArrayの後変更でも検証済み候補が変わらないことを確認した。全件置換終盤の下書き挿入でSQL失敗を発生させ、先行した削除・材料／料理／献立／履歴／買い物の保存と設定も完全に戻ることを確認した。空バックアップで全利用者データを除去し、導入済みプリセット・内蔵別名・祝日・metadataは維持する。

実際のv1 BackupCodec出力からの変換・復元で、既存ID、旧履歴ID、精密分量、利用者別名、チェックを保持し、旧手入力日を捨てて履歴から再計算することを確認した。分類欠落は警告して推測補完しない。旧プリセット版の変換表なしを拒否し、変換表ありでは確定材料・買い物・下書きpayload／基準情報・再構築索引の内蔵IDを現在のプリセットへ解決した。

環境・コマンドは第13章を継続。API26 AVDをread-only・no-snapshot・no-windowで起動し、専用inMemory DBおよび`backup-test-UUID.db`だけで検証した。`testDebugUnitTest lintDebug copyDebugApkToDst`の後に`connectedDebugAndroidTest`を別実行し、最終はいずれもBUILD SUCCESSFUL。50MiB累積読込制限・厳密UTF-8・重複キー・末尾JSON拒否は既存共通読込処理を利用し、その回帰テストも実行した。

- 最新APK: `dst/recipe-manager-debug.apk`（開発・確認用）、14,765,916 bytes。
- SHA-256: `0EE31EC56992AE14967A8DD068BA6BCD23B1DFE54D90E7172EC961DDCF50D2F3`。
- 版2 exportSchemaは変更なし（SHA-256: `9E71C3376626B6F2339067C359289169A7D90FCC686776A37C4D1BE1DD1BEA74`）。
- 未確認範囲: 版2画面・稼働DBへの接続、SAF画面と実際の各保存プロバイダーでの保存／取消／エラー表示、画面・プロセス再生成、他Android版・利用者の実DB、ロゴの目視、アクセシビリティ・規定データでの3回測定による総合性能・Release移行。OutputStreamの模擬失敗とDB再openを画面操作・実プロバイダーの確認へ代用していない。既存DBに独立した日時列がない材料・買い物のSchema用日時は詳細設計13.9の投影規則に従う。

作業ガイドに従い第7成果物のレビューを依頼する。次の全画面UI対応と稼働DBへの接続は承認後に進める。

その後、同日の「Ok」を第7成果物の承認として受領した。

## 19. 全画面対応の実装前レビュー見本の確認（2026-11-22）

詳細設計13.2が画面実装前の配色レビューを要求するため、13.2.2へ具体値と画面構成を追加した。対象は文書とオフラインHTML見本・PNG生成ツールであり、Android実装・稼働DB・APKは変更していない。

| 確認 | 結果 |
| --- | --- |
| `node document/tools/Check-UiReview.cjs` | 14画面の生成、テーマ／文字倍率操作、見本の必要／不要切替を確認 |
| `powershell -NoProfile -ExecutionPolicy Bypass -File document/tools/New-UiReviewPreview.ps1` | PNG生成成功。文字色18組すべて4.5:1以上、最小5.65:1 |
| `dst/ui-review/ui-overview.png`目視 | 日本語・3画面の配色・主要カード・5項目ナビゲーションを確認 |
| 差分形式 | git diff --check成功 |

HTMLの見本はホーム・献立・レシピ・材料・その他・買い物・レシピ編集・材料詳細・提案確認・調理結果・調理履歴・Import・データ管理・設定の14画面を切り替えられる。操作は合成データの表示だけに作用し、端末データの読み書き・ファイル選択・バックアップは行わない。HTMLの文字200%切替はレイアウト案の確認用であり、Androidの200%・TalkBack検証の代用ではない。PNGは同じ配色による静的な構成見本であり、実装画面のスクリーンショットではない。

今回の変更は文書・レビュー見本だけのためGradle・Android端末テスト・APK生成は再実行していない。Androidの直近の検証結果は第18章の単体67件・端末106件、APKは同章のSHA-256のまま。次工程でAndroidソースを変更した後に必須タスクと画面テストを実行する。

詳細設計13.2.2の配色・フォント・構成案をレビュー待ちとし、承認後に全画面UIと稼働DB接続へ進める。

その後の「OK」により、配色・画面構成を承認済みとして実装へ進んだ。

## 20. 版2画面・稼働DB接続の検証（2026-11-22開始）

配色・画面見本への「OK」を承認として受領し、AppViewModelV2・RecipeManagerV2App・DatabaseRuntimeV2で版2の保存処理へ接続した。MainActivityの起動経路から版1 ViewModel／画面と版1の自動初期化を外し、同じDB名にMigration 1→2を登録した。版1コードは互換回帰用として残る。APKのversionName=2.00・versionCode=200とし、公開やRelease生成は行っていない。

| 区分 | 結果 |
| --- | --- |
| JVM単体テスト | 67件すべて成功 |
| Android端末テスト | API26で115件すべて成功（既存106件、起動／ViewModel7件、画面2件を追加） |
| Android Lint | エラーなし、既存箇所の警告9件 |
| Debug APK生成 | copyDebugApkToDst成功。版2.00／200、承認済みロゴを維持 |
| 差分形式 | git diff --check成功 |
| 実画面の目視 | API26でホームのライト・ダーク・実際のfont_scale=2.0を確認 |

起動の新規試験で、内蔵材料・食品群・旬・祝日の初期化と再実行時の無変更、旧販売単位を基本単位へ流用しないこと、別名競合で全件ロールバックすることを確認した。専用の版1ファイルDBへ料理・材料行を保存してから、本番と同じDatabaseRuntimeV2のbuilderで開き、版2への移行と既存ID・メモ・数量の保持を確認した。利用者の実DBは変更していない。

ViewModelの新規試験では、材料を選択した下書きへ必要な基準情報が追加され、画面遷移前に保存され、再開後の確定で下書きだけが除去されることを確認した。連続入力15回と非同期保存を重ね、最後の入力と更新日時トークンが一致することを確認した。検証中に見つかったImport読込結果と編集の競合は、再検証後の状態を一度だけ公開する方法で修正した。入力更新も原子的に行い、保存完了で更新されたトークンを古い入力イベントから巻き戻さない。

ファイルURIによるViewModel経由のバックアップ出力・読込・確認後の復元、復元後の画面状態と確認候補の破棄、読込取消を確認した。ImportもファイルURIから読み込み、料理名の修正・再検証・ファイル選択取消・保存済み下書きの保持と再開・新規登録までを確認した。実際のSAF選択画面やGoogle Drive等のプロバイダーの検証には代用していない。

画面の新規試験で、材料登録→名称／別名検索→レシピでの材料選択→精密分量入力→保存→編集下書き保持→再開を実際のCompose操作で確認した。既存のCRUD試験は承認済みの参照保護・確認付き完全削除へ合わせ、削除取消で保持され、確認後に検索から消えることを確認した。主要画面のオフライン遷移も継続成功。文字200%ではその他・設定・長い選択ダイアログ・材料導線への到達を確認した。全画面の200%合格とはしていない。

実画面は `dst/ui-verification/light-home.png`、`dark-home.png`、`dark-home-200.png`へ記録した。文字200%の目視で下部の装飾アイコンまで拡大して文字の領域を圧迫する点を修正し、図形を24dp固定で描画し、拡大時の下端余白を確保して文字末尾まで表示する構成にした。端末の表示設定は撮影後に元へ戻した。TalkBackは検証用AVDに未導入（パッケージ照会で該当なし）のため、読み上げ操作は未確認。

環境とGradleの指定は第13章を継続。read-only・no-snapshot・no-windowのAPI26 AVD、専用DB・アプリ内cacheの試験JSONを使用した。最終のXMLで115件・失敗0・エラー0・スキップ0を確認した。手動目視用の導入と端末テストの再導入が競合した回は試験結果へ数えず、最終結果XMLを証拠とする。起動移行・下書き・ファイル処理とUIの検証範囲を分けて記録する。

- 最新APK: `dst/recipe-manager-debug.apk`（開発・確認用）、15,022,406 bytes。
- SHA-256: `5E0E3C1A2F30D14886ECB9A5A1693D7DD46EB7ED797FD7FB5E0E76247E040832`。
- 版2 exportSchemaは変更なし（SHA-256: `9E71C3376626B6F2339067C359289169A7D90FCC686776A37C4D1BE1DD1BEA74`）。
- 未確認範囲: 全画面・全ダイアログのTalkBack／200%、全編集状態での端末回転・プロセス終了後の再開、実SAFプロバイダーと保存障害、他Android版、利用者実DB、規定の4GB・Recipe500／材料1000／履歴5年での性能3回測定、署名／Release移行。今回のAPI26画面試験や起動時間を、それらの合格へ代用しない。

作業ガイドに従い第8成果物のレビューを依頼する。承認後に、残る総合検証・アクセシビリティ・性能／Release移行確認へ進める。

## 21. v2.00 総合検証（2026-11-23）

第8成果物のレビューに対する「OK」を承認として受領した。ここでは承認後の受入検証を記録する。

### 21.1 規定データでの画面性能

ホストはWindows 11 26H2（build 26300）、Intel Core i5-14400、RAM 16GB。Android Emulator 37.1.11.0の`recipe_api26`でAndroid 8.0.0（API 26）、x86-64、4仮想CPU、設定RAM 4096MiB（ゲスト認識 4,042,036kB）を使用した。同時稼働する他のAVDを止めた状態で、Debug版の表示までを端末上の経過時間で測った。

各回で専用ファイルDBを同じ初期状態へ戻した。初期状態はレシピ500件（4カテゴリ各125件）、各レシピに材料3件、内蔵を含む材料マスタ1,000件、過去5年の毎日1件の調理履歴、翌週に必要日7日。検索は素材名で1件へ絞り、提案は7日すべてに料理があることを確認した。Importは100件の事前読込・内容確認を計測外とし、確定の押下から追加結果までを測り、全回で登録後600件を確認した。

| 画面操作 | 1回目 | 2回目 | 3回目 | 最遅 | 合格値 | 結果 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| レシピ一覧の表示 | 416ms | 182ms | 166ms | 416ms | 2,000ms | 合格 |
| 材料名でレシピを検索 | 351ms | 285ms | 232ms | 351ms | 2,000ms | 合格 |
| 7日間の提案プレビュー | 2,835ms | 2,667ms | 2,222ms | 2,835ms | 5,000ms | 合格 |
| 確認済み100件のImport確定 | 2,872ms | 2,499ms | 2,561ms | 2,872ms | 10,000ms | 合格 |

初回検証で提案に8,347msかかった。提案の表示時に下書きの警告を計算する目的で探索を繰り返していたため、最新データから候補を再評価する処理へ変更した。1回の探索中に直近重複判定と候補の評価順も再利用する。レシピの検索結果件数は検索欄の直下へ配置し、一覧・検索計測では表示された件数を終了条件にした。

表は最終Debug APKを全回帰試験と同じ回で測定した結果。性能試験だけを再現する場合は`connectedDebugAndroidTest`へ `-Pandroid.testInstrumentationRunnerArguments.class=jp.local.recipemanager.app.V2PerformanceUiTest` と `-Pandroid.testInstrumentationRunnerArguments.recipeManagerPerformance=true` を指定する。最終の測定ログは `dst/acceptance-evidence/performance-final-api26.log`。性能修正直後の単独実行ログとXMLは同じディレクトリの `performance-api26.log` と `performance-api26.xml`。

### 21.2 実ストレージとAPK上書き更新

- API 26のAndroidファイル選択画面で、Downloads内のJSONがMIME型指定により選択不可になっていた。対象に `application/octet-stream` を追加すると選択可能になり、不適合なプリセット版・集約キーのバックアップは検証で拒否された。現行のプリセット版・集約キーを持つ合成データを読み込み、復元前のプレビューと全件置換の確認を経て復元できた。
- API 26で同じDownloadsからImport 1.0のJSONを選び、確認付きの下書き置き換えと登録前の表示を確認した。
- 同じAPI 26で、復元後のデータをAndroidのファイル作成画面からDownloadsへ保存した。保存完了後にファイルから形式2.0、レシピ3件、3種類の下書き、買い物の6.25個と購入済み、25.500gと不明あり、数量不明と購入不要を読み取った。`dst/acceptance-evidence/backup-api26.json` は合成データの出力。
- API 36（Android 16）の新規検証用AVDで、旧v1.00 APKにサンプルレシピをImportし、Downloadsへ旧形式1.0のバックアップを作った。旧APKと同じ開発用証明書で署名したv2.00をアンインストールなしで上書きし、レシピ1件、材料3行の数量（300g、0.25個、0.5本）、メモを詳細画面で確認した。更新時に端末内データベースが使用された。検証に使用したアプリデータは合成データ。
- 同じAPI 36のv2.00で機内モードとWi-Fi無効化後のコールドスタートを確認し、v1.00の実出力ファイルをDownloadsから選択した。プレビューと全件置換確認を経て復元が成功し、料理をレシピ一覧で確認した。

### 21.3 端末表示・アクセシビリティ

データ管理などの画面で別項目を選択済みと通知していた下部ナビゲーションを修正した。現在の画面に対応する1項目を選択状態にし、履歴・Import・下書き・データ管理・設定は「その他」に対応させた。文字200%の画面テストに「設定」での選択状態を追加した。

API 36のTalkBack 16.0で、開発者向けの読み上げ文表示を使用した。日別献立画面のフォーカス枠、「戻る」のラベル、Buttonの役割表示、および主菜選択ダイアログへの遷移を端末上で確認した。音声の発音・全画面のフォーカス順は未確認。[Android Accessibility Help](https://support.google.com/accessibility/android/answer/6006589?hl=en)

API 26の端末設定で `font_scale=2.0` とし、ホーム、週間献立、日別献立、レシピ一覧／詳細／編集、材料一覧／編集、その他、買い物、調理履歴／追加、Import、データ管理、設定、下書き一覧、提案確認の17画面へ到達した。材料選択・料理選択・バックアップ通知間隔のダイアログを開き、Importの登録、バックアップの保存・復元、提案の採用までの主要なボタンが見えることを確認した。結果画像は `dst/ui-verification/acceptance-home-200.png`、`acceptance-food-picker-200.png`、`acceptance-import-confirm-200.png`、`acceptance-settings-200.png`。

API 26で新規レシピの名前を入力し、下書きの自動保存表示を確認した。端末を横向きにしても編集画面に入力が残り、縦向きへの復帰とアプリの強制停止後は下書き一覧から同じ入力を再開できた。写真は `dst/ui-verification/acceptance-draft-rotated-api26.png` と `acceptance-draft-reopened-api26.png`。

### 21.4 回帰試験と成果物

| 区分 | 結果 |
| --- | --- |
| JVM単体テスト | 67件成功、失敗・エラー・スキップ0件 |
| Android API 26 | 116件成功、失敗・エラー・スキップ0件。性能試験1件の中で各操作3回測定 |
| Android API 36 | 回帰115件成功。API 26専用性能試験は対象外 |
| Android Lint | エラー0件、既存警告9件 |
| Debug APK生成 | `copyDebugApkToDst` 成功。versionName=2.00、versionCode=200 |
| 差分形式 | `git diff --check` 成功 |

API 26は第13章のGradle設定に従い、`connectedDebugAndroidTest` の最後の結果XMLで確認した。手動確認用アプリが残っている回は導入競合でテストを開始できなかったため、検証用AVDのアプリを外して再実行した。コピー先に検証用AVDのロックファイルが存在した回は、ローカルinit scriptでコピータスクの状態追跡を外して実行した。API 36は新規AVDを使用し、最終Debug APKと試験APKを導入してAndroidJUnitRunnerで実行した。証跡は `dst/acceptance-evidence/regression-api26.xml` と `regression-api36.log`。

- 最新APK: `dst/recipe-manager-debug.apk`（開発・確認用）、15,249,523 bytes。SHA-256 `16B9E6E8008547C96B6AB081C8734F3E0067BE72FF3EE9587F70AF237F9AA53D`。
- 版2のRoom exportSchemaは変更なし。SHA-256 `9E71C3376626B6F2339067C359289169A7D90FCC686776A37C4D1BE1DD1BEA74`。
- 全画面・全ダイアログのTalkBackの読み上げ順とテーマ別の組合せ、全編集状態での回転とプロセス再生成は未確認。ヘッドレスのAPI 36検証では読み上げ文を画面で確認し、発音の聞き取りは行えなかった。
- クラウドプロバイダー、利用者の実機・実データは試験環境に用意されていない。正式署名のRelease間上書きとDebugからReleaseへの移行には、有効な `keystore.properties` が必要。

2026-11-23の「OK」により、ここまでの総合検証結果の承認を受領した。上記の未確認項目はリリース前の確認事項として扱う。

## 22. リリース前の追加検証（2026-11-23）

### 22.1 Releaseビルドの署名移行

API 36（Android 16）の新規検証用AVDを用意した。v1.00の確定ソース（`872cb6b`）と作業中のv2.00を `assembleRelease` で組み立てた。リポジトリ外で作成した検証専用鍵で両APKに署名し、公開証明書のSHA-256が一致することを確認した。両APKのアプリIDは `jp.local.recipemanager` で、`versionCode` は100から200に増加した。試験データは料理1件と材料3行の合成データ。

| 経路 | 端末上の結果 |
| --- | --- |
| 同じ検証用署名のRelease v1.00 → Release v2.00 | v1.00に料理を登録して上書きインストールに成功。上書き後の端末内DBから料理1件、分量300g・0.25個・0.5本、メモを確認した。 |
| 署名が異なるDebug v1.00 → Release v2.00 | Debug版からDownloadsへ形式1.0を保存した。異なる証明書の上書きはAndroidが `INSTALL_FAILED_UPDATE_INCOMPATIBLE` で拒否。旧アプリを検証端末から削除し、Release版を導入後、同じファイルを選択・確認して復元した。料理・分量・メモを確認した。 |
| 復元後のRelease v2.00 → 端末内Documents | ファイル作成画面で端末内のDocumentsフォルダーに形式2.0を出力した。元の形式1.0と、レシピID・メモ・材料3行の名称・数量・単位が一致した。 |

証跡は `dst/acceptance-evidence/prerelease-migration.log`、`prerelease-debug-v1-backup.json`、`prerelease-release-v2-backup.json`。正式署名での移行には有効な `keystore.properties` が必要で、両経路の結果は検証専用鍵でのもの。

### 22.2 アクセシビリティとファイル選択

別のAPI 36検証用AVDに合成データを復元し、アプリの全17画面と材料選択・料理選択・設定選択の3ダイアログでAndroidのアクセシビリティ情報を取得した。画面内に表示された有効な操作要素の延べ187件にラベルがあった。クラス、選択状態、チェック状態と画面上の位置は `dst/acceptance-evidence/prerelease-accessibility-tree.log` に記録した。

TalkBack 16.0を有効にし、エミュレーターのタッチスクリーン入力による右スワイプを各場面で行った。20場面で延べ156件のフォーカス状態を取得した。ホームでは見出し、週の日付、夕食の集計、献立ボタン、個別の日付と要否へ進む順序を確認した。日ごとの操作は先行する日付・要否の表示と合わせて確認した。入力値が空の欄はヒントや子要素のラベルも参照して再取得し、レシピ名や材料検索欄など9件のラベルを確認した。観測記録は `dst/acceptance-evidence/prerelease-talkback-focus.log` と `prerelease-talkback-fields.log`。全操作を通る探索順、テーマ・文字倍率との全組合せ、発音の聞き取りは未確認。 [Android Developers「アプリのユーザー補助をテストする」](https://developer.android.com/guide/topics/ui/accessibility/testing)

API 36のファイル選択画面に公開された保存先を確認した。Driveアプリはインストールされていたが、試験AVDの登録アカウントは0件で、Driveは保存先一覧に表示されなかった。Driveなどのクラウドプロバイダーで読込・保存・障害時の挙動を確認するには、接続済みの検証端末が必要。画面一覧は `dst/acceptance-evidence/prerelease-provider-roots-api36.log`。

### 22.3 配布前の資料確認

内蔵祝日マスターの日付35件が、内閣府の祝日・休日一覧（2026年18件、2027年17件）と一致した。 [内閣府「国民の祝日について」](https://www8.cao.go.jp/chosei/shukujitsu/gaiyou.html)

READMEとリリース手順書に署名別の移行手順を記載した。MIT Licenseの著作権者表記はSigmay495で、v1.00との差分にGradleの依存関係の変更はない。アプリソースと既存Debug APKのSHA-256は第21章の検証時点と同じ。

2026-11-23の「OK」により、追加検証結果の承認を受領した。正式署名での移行、接続済みクラウドプロバイダーでの保存・復元、TalkBackの実音声と全操作は、引き続きリリース前の確認事項とする。

## 23. ダークテーマ・文字200%でのアクセシビリティ追加検証（2026-11-23）

API 36の隔離AVDに第21章のDebug APKを導入し、ファイル選択画面から合成データを復元した。端末設定でダークテーマと文字倍率2.0を併用し、TalkBack 16.0を有効にした。タッチスクリーンの右スワイプで対象にフォーカスを移し、同じノードにAndroidのアクセシビリティ操作 `ACTION_CLICK` を送った。

| 操作 | 確認結果 |
| --- | --- |
| ホームの「献立を確認」 | 週の献立へ遷移し、「1週間の献立を自動提案」を確認した。 |
| 設定のバックアップ通知間隔 | 選択ダイアログを開き、「60日」を選択した。設定画面の表示が30日から60日に更新された。 |
| 保存中の下書きの「レシピ編集・入力途中」を再開 | レシピ編集画面へ遷移した。 |

フォーカス遷移と操作結果は `dst/acceptance-evidence/talkback-actions-api36.log`、画面は `dst/ui-verification/talkback-actions-*-dark200.png` に記録した。

エミュレーターのタッチ入力によるダブルタップでは、ホームから週の献立へ進む操作を確認できなかった。TalkBackの言語を日本語（ja-JP）に設定し、ホームの対象ボタンへフォーカスを移した際の端末出力をWAVで採取した。音量を補正して保存した約4秒の抜粋は `dst/acceptance-evidence/talkback-home-sample.wav`。音声認識で読み上げ文言を照合できなかったため、日本語の文言と発音の確認が残る。

アプリソースとAPKに変更はない。APKのSHA-256は第21章と一致する。この工程では上記の端末操作を追加で実施し、Gradleの単体テスト・Lint・APK生成と端末回帰の全件は第21章の結果を参照する。

正式署名用の `keystore.properties` とクラウドプロバイダーに接続済みの検証端末は今回も用意されていない。正式署名での移行、クラウドの保存・復元、実機と他の画面・設定でのTalkBack操作は引き続き確認が必要。

2026-11-23の「OK」により、第23章の検証結果の承認を受領した。同章の未確認項目は引き続きリリース前の確認事項とする。
