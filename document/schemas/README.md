# JSON Schema成果物（v2.00）

詳細設計第13章に基づくSchema成果物。開発版v2.00はバックアップ2.0を出力し、1.0を変換して復元できる。ソース工程の第1成果物で旧形式変換と2.0検証Adapter、第2成果物で版2 DB・Room Migration、第3成果物で材料・レシピRepository、第4成果物で献立・実績・延期・提案、第5成果物でImport・下書き、第6成果物で買い物、第7成果物でバックアップ2.0保存処理、第8成果物で画面経由読み書き・稼働DB接続を実装した。Import形式は1.0を維持する。

2026-10-03の「コーディングへ」でソース工程開始指示を受領した。最初の成果物では共通JSON読込と互換検証・変換Adapterを実装する。AdapterはDBへ書き込まず、検証済み2.0候補と警告を返す。内蔵材料は呼出元から渡された実在ID・標準名・別名で解決する。旧形式の必要日＋料理0件、不完全構成、不要日＋料理は変換前に拒否する。旧買い物項目は材料IDを推測せず、元の正規化名・単位を集約キーの根拠とする。集約キーは材料識別種別・識別値・単位のJSON配列文字列とし、文字列連結の衝突を避ける。旧形式変換で同一キーが重複する場合はチェックを独断で合成せず拒否する。

共通JSON読込は累積バイト数を制限し、Importは5MiB、復元は50MiBを上限とする。最初の超過バイトで読込を中止する。UTF-8以外、JSON重複キー、空入力、末尾の別JSONを拒否する。Schemaはローカル参照だけを許可する。失敗・キャンセルでは確認待ちの古い復元候補を破棄し、読込失敗と構文不正を区別して表示する。

## ファイルと検証の順序

- `recipe-import.schema.json`: 新規レシピImport 1.0。既存サンプル・プロンプトとともに維持する。
- `backup.schema.json`: 版1 Codecの互換回帰用バックアップ1.0。版2画面は`backup-1.0.schema.json`と`backup-2.0.schema.json`をAdapter経由で使用する。
- `backup-1.0.schema.json`: 上記とバイト単位で同一の互換用保存版。旧形式受理後の参照・構成・重複検証と変換はAdapterで行う。
- `backup-2.0.schema.json`: 新バックアップ2.0。参照は同一ファイルのローカル`$ref`のみ。
- `../samples/backup-2.0.sample.json`: 別名、7日献立、結果・延期、買い物の不明量、3種類の下書きを含む合成データ。利用者の実データは含まない。

形式判定後に対応するSchemaで構造検証し、2.0では参照・不変条件検証へ進む。1.0では旧互換検証→変換→2.0構造検証→参照・不変条件検証とする。検証完了と利用者確認までDBへ書き込まない。

全オブジェクトは`additionalProperties=false`。任意の参照は必須項目としてnullを許容する。確定分量は正の10進文字列（指数なし、BigDecimal.toPlainString相当）またはnull。買い物のamount=nullはhasUnknownAmount=trueとする。混在では既知小計と不明フラグをともに保持する。

通常IDはUUID、履歴IDとhistoryItems.historyIdだけUUIDまたは`history-YYYY-MM-DD`を許容する。材料参照はUUIDまたは内蔵ID`builtin-…`を許容し、内蔵IDの実在性はAdapterで検査する。新規IDの生成はUUID v4。UTC日時はdate-time形式かつ末尾Zとする。

食品群は現行FoodGroupの全12分類を保持する（grain/meat/seafood/egg/dairy/soy/vegetable/mushroom/seaweed/fruit/fat/seasoning）。旧1.0の6分類の互換変換は既存対応表を用い、保存されていない詳細分類を推測で追加しない。

キャッシュのlastCookedAt・nutritionGroupsは2.0に出力せず、復元時に履歴・材料から再計算する。利用者材料の別名はuserFoodAliasesへ分離する。買い物の利用料理はrecipeUsagesのrecipeId（旧生成元を特定できない場合null）とrecipeNameSnapshotで保持する。旧recipeNamesを特定のRecipe IDへ推測で対応付けない。sourceFingerprintが不明ならnullとし、needsRegeneration=trueを要求する。

## 下書きの型付きpayload（payloadVersion=1）

draftはid、kind、targetKey、payloadVersion、payload、baseRevisions、updatedAtを持つ。kindに応じてpayloadとtargetKeyを検証する。参照索引draftReferencesは出力せずpayloadから再構築する。

- recipe: targetKeyは編集対象UUIDまたは`new`。payloadはform、foodCandidates、conflicts。formはname/category/cookingTimeMinutes/effortLevel/sourceUrl/memo/proposalPriorityとingredients。入力中の数値・URLは文字列として未完成・不正入力も保持し、確定時にレシピ検証を行う。材料行はlocalKey/name/amount/unit/note/foodMasterId/foodCandidateKeyを持ち、確定材料と新規候補を区別する。
- import: targetKeyは`singleton`。payloadはsourceJson、entries、foodCandidates、conflicts。sourceJsonは元入力を文字列として保持する。各entryはlocalKey、修正後form、decision（add/skip）、validationState（unchecked/valid/error）、errors、duplicateRecipeIdsを保持する。古い検証状態を信頼せず確定時に再検証する。
- menu: targetKeyは週開始日。payloadはdays（7日）、warnings、seed（64bit乱数シードの10進文字列）、conflicts。各日はdate/noMeal/requirementOrigin/dayLocked/items。各料理はlocalKey/recipeId/role/locked。未完成構成は下書きだけで許容し、採用時に成立構成を検査する。

foodCandidatesはlocalKey/name/aliases/foodGroups/baseUnit/seasonMonths/isMainIngredientを持つ。未完成名は空文字列を許容する。確定マスタへ先行登録しない。

baseRevisionsはentityType（recipe/food/menu/menuDay）、entityId、revisionの配列。新規対象には基準行を作らない。conflictsはentityType/entityId/reasonの配列で、統合前からの競合理由を保持する。統合済みの参照へ移しても古い基準revision・既存競合を一律更新しない。

## Adapterで必須の検証（Schemaだけでは保証しない）

ID・正規化名・別名・週開始日・日付・役割・集約キー・下書きキーの一意性、全参照の実在、旧履歴ID内の暦日の実在、曜日・7日範囲、確定献立の成立構成、固定とカテゴリの整合、同日Recipe実績の重複、結果と実績の整合、延期元／先と状態の整合を検査する。

下書きの候補キー・参照・基準情報・対象キー・日付の整合、既存競合の保持、買い物の不明量と購入状態、材料識別子＋単位とaggregationKey、プリセットのmasterVersionとID解決も検査する。Schemaの成功を復元可能の判定に代用しない。累積読込バイト上限と外部参照・ネットワーク解決禁止は読込Adapterの責務。

## Schema検証の再実行

`../tools/Check-SchemaContracts.ps1`で、アプリと同じJackson・networknt（format assertions有効）を用いた独立検証を実行する。Gradle依存キャッシュがある環境で、リポジトリ直下から以下を実行する。

```powershell
.\document\tools\Check-SchemaContracts.ps1
```

PowerShellの実行ポリシーでローカルスクリプトが拒否される環境では、システム設定を変更せず、この検証プロセスだけを`powershell -NoProfile -ExecutionPolicy Bypass -File document/tools/Check-SchemaContracts.ps1`で起動する。

これはSchema構造・正常サンプル・異常入力の検証であり、Migration・変換・Repository・復元書込・画面の試験ではない。ソース成果物の検証はTEST_REPORT第10・13・14章に記録する。
