# レシピ管理アプリ 詳細設計書

## 1. 文書情報

- 文書名: レシピ管理アプリ 詳細設計書
- 対象工程: 詳細設計
- 前提文書: `document/REQUIREMENTS.md`、`document/OVERVIEW_DESIGN.md`
- 関連成果物:
  - `document/schemas/recipe-import.schema.json`
  - `document/schemas/backup.schema.json`
  - `document/samples/recipe-import.sample.json`
  - `document/prompts/RECIPE_IMPORT_PROMPT.md`
- ステータス: 承認済み

## 2. 共通仕様

### 2.1 日時・ID・文字列

- IDはUUID v4文字列とする。
- 日付は`YYYY-MM-DD`、日時はUTCのISO 8601形式とする。
- 週の開始日は月曜日とする。
- 画面表示時だけ端末のローカル時刻へ変換する。
- 料理名、材料名、メモは前後空白を除去して保存する。
- 検索用文字列はUnicode正規化、英字小文字化、全角・半角空白除去を行う。
- ユーザー入力およびImport文字列はHTMLとして解釈しない。

### 2.2 列挙値

| 項目 | 値 |
| --- | --- |
| レシピカテゴリ | `one_dish`、`main`、`side`、`soup` |
| 手間レベル | `easy`、`normal`、`hard` |
| レシピ状態 | `active`、`deleted` |
| 献立上の役割 | `one_dish`、`main`、`side`、`soup` |
| Import判定 | `add`、`restore`、`skip`、`error` |

### 2.3 共通UI

- 主要操作のタップ領域は44px以上を目安とする。
- 保存中は多重操作を防止し、完了または失敗を通知する。
- 入力エラーは対象項目の直下に表示し、先頭エラーへ移動できるようにする。
- 削除、復元、全件置換は確認ダイアログを表示する。
- 警告は保存を妨げないものと、処理を停止するエラーを視覚的に区別する。

### 2.4 Kotlin実装基盤

- UIはJetpack ComposeとMaterial 3で実装する。
- 画面状態はViewModelとStateFlowで管理し、端末回転などの構成変更後も必要な状態を復元する。
- 非同期処理はKotlin Coroutinesを使用し、DB・ファイル処理をメインスレッドで実行しない。
- 画面はUseCaseまたはRepositoryを介してデータへアクセスし、Room DAOを直接呼び出さない。
- JSONの直列化・逆直列化はkotlinx.serializationを使用し、Importと復元ではJSON Schema検証も実施する。
- JSON Schema検証ライブラリはDraft 2020-12、ローカル`$ref`、`uuid`、`date`、`date-time`、`uri`のformat検証に対応するものとし、ライブラリ固有APIは検証用Adapter内へ閉じ込める。
- ファイルの選択と保存はAndroidのStorage Access Frameworkを使用し、広範なストレージ権限を要求しない。

## 3. 画面詳細

### 3.1 画面一覧・遷移

| ID | 画面 | 主な遷移先 |
| --- | --- | --- |
| SCR-001 | ホーム | 献立作成、献立詳細、バックアップ |
| SCR-010 | レシピ一覧 | レシピ詳細、レシピ編集、Import |
| SCR-011 | レシピ詳細 | レシピ編集、作り方URL、削除・復元 |
| SCR-012 | レシピ編集 | レシピ詳細、一覧 |
| SCR-013 | レシピImport | Import結果、レシピ一覧 |
| SCR-020 | 献立作成 | 献立詳細、レシピ選択 |
| SCR-021 | 献立詳細 | 調理実績、買い物リスト |
| SCR-022 | 献立履歴 | 履歴編集 |
| SCR-030 | 買い物リスト | 献立詳細 |
| SCR-040 | ご飯不要日 | 献立作成 |
| SCR-090 | データ管理 | 復元確認、設定 |
| SCR-091 | 設定 | データ管理 |

### 3.2 SCR-001 ホーム

- 今週の月曜日から金曜日をカード表示する。
- 各日に献立、祝日、ご飯不要、未設定のいずれかを表示する。
- 今週の献立が未作成の場合は「献立を作る」を主要操作とする。
- 最終バックアップから設定期間が経過した場合はバックアップ通知を表示する。
- 削除済みレシピが未来の献立に含まれる場合は対象日と料理名を警告表示する。

### 3.3 SCR-010 レシピ一覧

- 初期表示は`status = active`のみとする。
- 検索条件は料理名、材料名、カテゴリ、調理時間上限、手間、状態とする。
- 一覧項目は料理名、カテゴリ、調理時間、手間、最終調理日、栄養目安とする。
- 状態に「削除済み」を指定した場合だけ論理削除済みレシピを表示する。
- 並び順は料理名、更新日、最終調理日から選択する。

### 3.4 SCR-011 レシピ詳細

- 料理名、材料、カテゴリ、時間、手間、栄養目安、最終調理日、URL、メモを表示する。
- URLは確認可能な外部リンクとして新しいブラウザ画面で開く。
- 利用中レシピには編集・論理削除、削除済みレシピには復元を表示する。
- 未来の献立で使用中のレシピを削除する場合、対象日を表示して変更を促す。
- 論理削除後も既存の献立と履歴は変更しない。

### 3.5 SCR-012 レシピ編集

| 項目 | 入力規則 |
| --- | --- |
| 料理名 | 必須、1～100文字 |
| カテゴリ | 必須、単一選択 |
| 材料 | 1件以上。材料名必須、分量は0より大きい数値または未設定、単位は20文字以内 |
| 調理時間 | 任意、1～1440分 |
| 手間 | 必須、初期値`normal` |
| 最終調理日 | 履歴がない新規レシピだけ任意入力、未来日は不可 |
| URL | 任意、HTTPまたはHTTPS、2048文字以内 |
| メモ | 任意、2000文字以内 |

- 材料行は追加、削除、並べ替えができる。
- 保存前に料理名とURLを用いて重複候補を警告する。
- 食材変更時に栄養目安を再計算する。

### 3.6 SCR-013 レシピImport

1. JSONファイルを選択する。
2. JSON構文と、`schemaVersion`、`fileType`、`recipes`の配列・件数を含むファイル外枠を検証する。外枠エラーの場合はImport全体を中止する。
3. 各`recipes`要素を`recipe-import.schema.json`の`$defs/recipe`で個別検証し、項目ごとのエラーを表示する。
4. 正常なレシピを正規化して一覧表示し、エラーのあるレシピは登録対象外として同じ画面に残す。
5. 重複候補ごとに追加、復元、スキップを選択する。
6. エラーのない選択項目を一括登録する。
7. 成功、スキップ、エラー件数を表示する。

- 復元は削除済みレシピの状態だけを`active`へ変更する。
- 追加時は共有端末がIDと作成日時を付与する。
- 1件のエラーで他の正常データを失わない。
- 登録処理全体で予期しないエラーが発生した場合はトランザクションを取り消す。

### 3.7 SCR-020 献立作成

- 対象週を月曜日単位で選択する。
- 祝日とご飯不要日は対象外表示とする。
- 「自動提案」で対象日全体を提案する。
- 各日または各料理を再提案・選択変更できる。
- 警告一覧に、緩和条件、重複、手間、栄養、食材ロスを表示する。
- 保存時に変更後の条件を再評価する。

### 3.8 SCR-021 献立詳細・調理実績

- 日付、構成、料理、材料、URLを表示する。
- 「作った」を実行すると、その日の料理を履歴へ登録する。
- 実際に作らなかった料理は登録対象から外せる。
- 登録後、対象レシピの最終調理日を履歴から再計算する。
- 削除済みレシピも既存献立上では表示し、変更を促す警告を付ける。

### 3.9 SCR-022 献立履歴

- 日付範囲または週で絞り込む。
- 調理日、料理名スナップショット、現在のレシピ状態を表示する。
- 修正・削除後、影響する全レシピの最終調理日を再計算する。

### 3.10 SCR-030 買い物リスト

- 対象週の保存済み献立から生成する。
- 同じ正規化材料名かつ換算可能な単位を合算する。
- 合算不能な項目は別行にする。
- 利用料理、購入不要、購入済みを表示・変更する。
- 献立更新日時が生成元より新しい場合は「再生成が必要」と表示する。

### 3.11 SCR-040 ご飯不要日

- 日付は平日だけ選択可能とする。
- 理由・メモは任意、200文字以内とする。
- 献立設定済みの日を不要日にする場合、献立解除の確認を表示する。

### 3.12 SCR-090 データ管理

- アプリデータの概算使用量、最終バックアップ日時、データ消去・アンインストール時の注意を表示する。
- 「バックアップ」で全データJSONを生成する。
- 「復元」でファイル選択、検証、全件置換確認を行う。
- 復元前に現在データのバックアップを促す。

### 3.13 SCR-091 設定

- バックアップ通知間隔は7日、14日、30日、60日から選択でき、初期値は30日とする。
- 最終バックアップ日時、祝日データ版、DBスキーマ版、バックアップ形式版、アプリ版は参照専用とする。
- バックアップ通知間隔の変更は即時保存し、次回のホーム表示から反映する。
- 署名鍵、署名パスワードなどの開発・配布用秘密情報はアプリ内に保存または表示しない。

## 4. Roomデータベース詳細

DBファイル名は`recipe-manager.db`とする。Room Entityの論理名と索引は以下とする。

| テーブル | 主キー | 索引 |
| --- | --- | --- |
| recipes | `id` | `normalizedName`、`category`、`effortLevel`、`status`、`lastCookedAt`、`updatedAt` |
| recipeIngredients | `id` | `recipeId`、`normalizedName`、`foodMasterId`、`[recipeId+displayOrder]` |
| weeklyMenus | `id` | 一意`weekStart`、`updatedAt` |
| menuDays | `id` | `weeklyMenuId`、一意`date` |
| menuItems | `id` | `menuDayId`、`recipeId`、`role` |
| cookingHistories | `id` | 一意`cookedDate`、`updatedAt` |
| historyItems | `id` | `historyId`、`recipeId`、`role` |
| shoppingLists | `id` | 一意`weekStart`、`sourceMenuUpdatedAt` |
| shoppingItems | `id` | `shoppingListId`、`normalizedName`、`checked`、`excluded` |
| foodMasters | `id` | 一意`normalizedName`、`userEditable` |
| foodMasterAliases | `[foodMasterId+normalizedAlias]` | 一意`normalizedAlias`、`foodMasterId` |
| foodMasterGroups | `[foodMasterId+foodGroup]` | `foodGroup`、`foodMasterId` |
| foodMasterSeasons | `[foodMasterId+month]` | `month`、`foodMasterId` |
| holidays | `date` | `year` |
| settings | `key` | なし |
| metadata | `key` | なし |

### 4.1 Recipe

```kotlin
@Entity(tableName = "recipes")
data class RecipeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val normalizedName: String,
    val category: RecipeCategory,
    val cookingTimeMinutes: Int?,
    val effortLevel: EffortLevel,
    val sourceUrl: String?,
    val memo: String,
    val lastCookedAt: LocalDate?,
    val initialLastCookedAt: LocalDate?,
    val nutritionGroups: Set<FoodGroup>,
    val status: RecipeStatus,
    val deletedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
```

### 4.2 RecipeIngredient

```kotlin
@Entity(tableName = "recipeIngredients")
data class RecipeIngredientEntity(
    @PrimaryKey val id: String,
    val recipeId: String,
    val displayOrder: Int,
    val name: String,
    val normalizedName: String,
    val amount: BigDecimal?,
    val unit: String,
    val note: String,
    val foodMasterId: String?,
)
```

### 4.3 Menu・History

```kotlin
@Entity(tableName = "menuItems")
data class MenuItemEntity(
    @PrimaryKey val id: String,
    val menuDayId: String,
    val recipeId: String,
    val recipeNameSnapshot: String,
    val role: MenuRole,
)

@Entity(tableName = "historyItems")
data class HistoryItemEntity(
    @PrimaryKey val id: String,
    val historyId: String,
    val recipeId: String,
    val recipeNameSnapshot: String,
    val role: MenuRole,
)
```

- `RecipeCategory`、`EffortLevel`、`RecipeStatus`、`MenuRole`、`FoodGroup`はKotlinの`enum class`として定義する。
- `LocalDate`、`Instant`、`BigDecimal`、`Set<FoodGroup>`は明示的なRoom TypeConverterを用意し、日時と数値の保存形式を固定する。
- RecipeIngredient、MenuItem、HistoryItemおよびFoodMaster子テーブルには外部キーを設定する。通常の親削除は行わず、復元処理を除いて意図しない連鎖削除を許可しない。
- FoodMasterの別名、食品群、旬の月は、それぞれ`foodMasterAliases`、`foodMasterGroups`、`foodMasterSeasons`へ1件ずつ保存する。
- FoodMaster保存前に、標準名と別名の正規化値を両テーブル横断で検証する。同じ正規化値を異なるFoodMasterへ割り当てることは許可せず、競合する食材名を利用者へ表示する。
- バックアップJSONでは互換性と可読性のため、これらの子テーブルをFoodMasterの`aliases`、`foodGroups`、`seasonMonths`配列へまとめてExportし、復元時に子テーブルへ展開する。
- RecipeIngredientのRoom列名はSQLとの衝突を避けて`displayOrder`とし、Import・バックアップJSONの`order`との間で相互変換する。

- 参照対象のレシピが論理削除されてもMenuItemとHistoryItemは残す。
- 最終調理日は`max(cookingHistories.cookedDate)`をRecipe ID単位で求める。
- 履歴が0件の場合だけ`initialLastCookedAt`を使用する。

### 4.4 トランザクション境界

| 処理 | 同一トランザクション対象 |
| --- | --- |
| レシピ保存 | recipes、recipeIngredients |
| レシピ論理削除・復元 | recipes |
| 献立保存 | weeklyMenus、menuDays、menuItems |
| 調理実績保存 | cookingHistories、historyItems、recipes |
| 履歴修正・削除 | cookingHistories、historyItems、recipes |
| 買い物リスト生成 | shoppingLists、shoppingItems |
| 新規レシピImport | recipes、recipeIngredients、foodMasters、foodMasterAliases、foodMasterGroups、foodMasterSeasons |
| 全データ復元 | metadataと内蔵マスターを除く全ユーザーデータテーブルおよび設定 |

### 4.5 献立データの不変条件

MenuDayは、必ず次のいずれか1つの状態とする。

1. ご飯不要: `noMeal = true`かつMenuItemは0件
2. 一品もの: `noMeal = false`かつ`one_dish`が1件、その他の役割は0件
3. 3品構成: `noMeal = false`かつ`main`、`side`、`soup`が各1件、`one_dish`は0件

- 同じMenuDayに同じ役割を複数登録しない。
- 献立保存前、新規提案後、手動変更後、バックアップ復元前に不変条件を検証する。
- 不変条件を満たさない献立は保存・復元しない。
- ご飯不要へ変更する場合は、既存MenuItemを削除する確認を行ってから同一トランザクションで更新する。
- 料理変更中の一時的な未完成状態は画面内だけに保持し、Roomへ保存しない。

## 5. 献立提案アルゴリズム

### 5.1 前処理

1. 対象日から土日、祝日、ご飯不要日を除外する。
2. `status = active`のレシピをカテゴリ別に取得する。
3. 最終調理日、食品群、旬、材料正規化結果を付加する。
4. 最終調理日から14日未満のレシピを初期候補から除外する。

### 5.2 日別候補

- 一品もの候補は`one_dish`を1件とする。
- 3品候補は`main`、`side`、`soup`を各1件とする。
- 各カテゴリで事前評価上位20件を候補とし、組み合わせ爆発を防ぐ。
- 同一日に同じRecipe IDを重複採用しない。
- 初期探索では、同じ対象週の複数日に同じRecipe IDを採用しない。
- レシピ不足時に限り、14日以内の重複回避と同じ緩和段階で週内重複を許可し、警告する。

事前評価上位20件は、探索中の部分的な週間献立を基に、次の順で辞書式に選ぶ。

1. 14日以内の調理および選択済み週内Recipe IDとの重複がない
2. 追加後の日別負担差が小さい
3. その時点で不足している食品群を補う
4. 既に選択された主要食材を再利用する
5. 旬食材を多く含む
6. 過去の採用回数が少ない

緩和段階では、解除された条件を事前評価からも除外する。

### 5.3 調理負担

| 手間 | 基礎点 |
| --- | ---: |
| easy | 1 |
| normal | 2 |
| hard | 3 |

日別負担は、料理の手間基礎点合計に、合計調理時間60分ごとに0.5点を加算する。調理時間による加算は最大2点とする。週内の日別負担の最大差と`hard`を含む日の連続を評価し、差と連続が小さい候補を優先する。

### 5.4 栄養評価

食品群は次を初期分類とする。

- `staple`: 米、パン、麺など
- `protein`: 肉、魚、卵、大豆など
- `vegetable`: 野菜、いも類など
- `dairy`: 牛乳、乳製品
- `fruit`: 果物
- `other`: 調味料、分類外

各日と週全体で`staple`、`protein`、`vegetable`の不足を主要な偏りとして警告する。3品構成では、ご飯を標準の主食として`staple`を満たすものとする。`dairy`と`fruit`は参考表示とし、初期版の献立成立条件にはしない。

栄養ペナルティは、対象日ごとに不足した食品群を次の点数で加算する。小さいほど良い。

| 不足食品群 | ペナルティ |
| --- | ---: |
| staple | 1 |
| protein | 2 |
| vegetable | 2 |

さらに、週の対象日の半数以上で同じ食品群が不足する場合は、その食品群ごとに5点を加算する。

### 5.5 食材ロス評価

- `g`と`kg`、`ml`と`l`を基準単位へ換算する。
- 個、本、枚などはFoodMasterに換算値がある場合だけ重量・容量へ換算する。
- 同じ正規化材料名の再利用数が多い候補を優先する。
- 販売単位がある場合、想定残量は`(販売単位 - (週使用量 mod 販売単位)) mod 販売単位`とする。
- 不明な分量・単位は残量計算から除外するが、共通食材としては評価する。

食材ロスペナルティは、次の合計とする。小さいほど良い。

- 販売単位を計算できる各食材について、`想定残量 / 販売単位 × 100`を四捨五入した値
- 週内で1料理にしか使用されない主要食材1種類につき10点

調味料と分量不明の食材は残量比の計算から除外する。主要食材かどうかはFoodMasterで管理する。

### 5.6 旬評価

- 対象日の月が材料の`seasonMonths`に含まれる数を集計する。
- 旬情報がない材料は加点・減点しない。
- 旬スコアは、週の献立に含まれる重複を除いた旬食材数とする。多いほど良い。

### 5.7 探索と優先順位

- 献立構成は必須条件とし、成立候補だけを比較対象とする。
- 候補週間献立は、次の比較タプルを左から辞書式に比較し、小さい候補を優先する。

```text
(
  手間ペナルティ,
  14日以内重複件数,
  栄養ペナルティ,
  食材ロスペナルティ,
  -旬スコア,
  過去採用回数,
  乱数値
)
```

- 手間ペナルティは`日別負担の最大値と最小値の差 × 100 + hardを含む日が隣接するペア数 × 1000`とする。
- 14日以内重複件数は、対象週で採用した該当レシピ数とする。
- 過去採用回数は、履歴に残る候補レシピの採用回数合計とする。
- 辞書式比較により、下位条件が上位条件の差を逆転させないようにする。
- 日別候補を評価し、上位候補を保持するビーム探索を使用する。
- ビーム幅の初期値は50とする。
- 乱数シードを提案日時と対象週から生成し、同一操作内では結果を再現可能にする。

### 5.8 条件緩和

候補が成立しない場合、次の順で緩和して再探索する。

1. 旬の優先を解除
2. 食材ロスの優先を解除
3. 栄養目標を警告扱いへ変更
4. 2週間以内および同一週内の重複回避を警告扱いへ変更

献立構成、削除済み除外は緩和しない。各段階を提案結果へ記録する。

## 6. 材料正規化・マスター

- 文字正規化後、FoodMasterの標準名・別名と完全一致する場合に同一食材とする。
- 自動で一致しない材料は、新しい利用者編集可能FoodMaster候補として確認する。
- 初期版では曖昧一致による自動統合を行わない。
- 単位は`g`、`kg`、`ml`、`l`、`個`、`本`、`枚`、`袋`、`パック`、`適量`、その他文字列を許容する。
- 内蔵マスターと利用者編集分を分離し、バックアップには利用者編集分を含める。
- FoodMasterに`isMainIngredient`を保持し、肉、魚、野菜など献立評価対象の材料は`true`、調味料は`false`とする。
- 内蔵FoodMasterのIDは一度公開した後は変更・再利用しない。
- 内蔵食材の名称変更ではIDを維持する。
- 内蔵食材を統合・廃止する場合は、旧IDから新IDへの変換表をアプリに保持する。
- 復元時はバックアップの`masterVersion`を確認し、変換表を適用してから参照整合性を検証する。
- 変換先が存在しないFoodMaster IDは参照切れとして復元を中止する。

## 7. Import詳細

### 7.1 新規レシピJSON

- ファイル名の推奨形式は`recipe-import_YYYY-MM-DD.json`とする。
- MIME typeは`application/json`、文字コードはUTF-8とする。
- 1ファイル最大100件、ファイルサイズ上限5MBとする。
- Schema Version 1.0を初期版とする。
- JSON Schemaは`document/schemas/recipe-import.schema.json`を正とする。

### 7.2 正規化

- 未指定の手間は`normal`とする。
- 空文字のURL、メモ、単位、注記は`null`または空文字へ統一する。
- 材料名を正規化し、FoodMasterへ関連付ける。
- 栄養目安はImport値を信用せずアプリで計算する。
- `warnings`はImport確認画面で利用者へ表示するが、レシピデータとしては保存しない。

Schema検証は二段階で行う。JSON構文とファイル外枠が不正な場合は全体を中止する。外枠が正常な場合は各レシピを`$defs/recipe`で個別検証し、不正なレシピだけを登録対象外とする。

### 7.3 重複候補

次のいずれかを満たす場合に重複候補とする。

1. 正規化料理名と正規化URLが一致する。
2. URLが両方に存在し、正規化URLが一致する。
3. 正規化料理名が一致し、主要材料名の50%以上が一致する。

自動確定は行わず、利用者が追加・復元・スキップを選択する。

## 8. バックアップ・復元詳細

### 8.1 バックアップ内容

- Roomの全ユーザーデータと設定を含める。`metadata`テーブルの端末側運用情報は含めない。
- 内蔵マスター本体は含めず、そのバージョンをメタデータへ記録する。
- 利用者編集可能マスターは含める。
- Room内でKey-Value管理する設定は、バックアップJSONでは型付きの`settings`オブジェクトへ変換する。未定義キーや不正な値はSchema検証で拒否する。
- バックアップ形式版、作成日時、作成元アプリ版、マスター版はファイル直下だけに記録し、二重管理しない。
- JSON生成に成功し、AndroidのStorage Access Frameworkを通じて利用者が指定した保存先への書き込みを完了した時点で最終バックアップ日時を更新する。
- Schemaは`document/schemas/backup.schema.json`を正とする。
- バックアップファイル名は`recipe-manager-backup_YYYY-MM-DD_HHmmss.json`、文字コードはUTF-8、MIME typeは`application/json`とする。

### 8.2 復元

復元ファイルの上限は50MBとし、上限を超えるファイルはJSON全体をメモリへ展開する前に拒否する。

1. JSON構文、ファイルサイズ、形式版を確認する。
2. 形式版に対応する旧版Schemaで入力データを検証する。
3. 対応する旧版は段階的な変換関数で現在形式へ変換する。
4. 変換後のデータを現在版Schemaで再検証する。
5. Recipe ID、FoodMaster IDなどの参照関係を検証する。
6. 現在データのバックアップを促す。
7. `metadata`と内蔵マスターを除く全ユーザーデータテーブルおよび設定をRoomの単一トランザクションで置換する。
8. 最終調理日を再計算する。
9. `settings.lastBackupAt`はバックアップ内の値ではなく、ファイル直下の`createdAt`で更新する。
10. `metadata`テーブルは置換せず、現在のDBスキーマ版、バックアップ形式版、アプリ版を維持する。
11. 件数と結果を表示する。

変換不能、新しい非対応版、参照切れ、容量超過の場合は書き込み前に中止する。

## 9. Androidアプリ・更新詳細

- アプリIDおよびKotlin名前空間は`jp.local.recipemanager`、表示名は「レシピ管理」とし、公開後は変更しない。
- アプリ名と各Android密度向けのランチャーアイコンを用意する。
- 内蔵マスターはAPKのassetsまたはresourcesへ同梱する。
- リリースAPKは同じ署名鍵で署名し、上書きインストールできるようにする。
- 署名鍵とパスワードはリポジトリ外で管理し、Git管理対象や配布物へ含めない。
- 署名鍵は暗号化した別媒体へバックアップし、復旧手順を利用者だけが参照できる場所へ記録する。
- DB移行を伴う更新では、更新前バックアップを案内する。
- RoomのMigration失敗時に破壊的再作成を行わず、旧データを保持してエラーを案内する。
- アプリIDまたは署名鍵を変更する場合は、旧アプリでバックアップし、新アプリで復元する手順を案内する。

## 10. エラーコード

| コード | 内容 | 処理 |
| --- | --- | --- |
| VAL-001 | 必須入力不足 | 対象項目を表示 |
| IMP-001 | JSON構文不正 | Import中止 |
| IMP-002 | Schema不適合 | 問題箇所を表示 |
| IMP-003 | 件数・容量超過 | Import中止 |
| IMP-004 | 重複候補 | 利用者選択待ち |
| BAK-001 | バックアップ生成失敗 | 最終日時を更新しない |
| RST-001 | 非対応形式 | 復元中止 |
| RST-002 | 参照切れ | 復元中止 |
| RST-003 | 形式変換失敗 | 復元中止 |
| DB-001 | DB保存失敗 | トランザクション取消 |
| DB-002 | DB移行失敗 | 旧データを維持し案内 |
| APP-001 | APKまたは署名不整合で更新不能 | 正しい署名のAPKを案内 |

## 11. テスト観点

### 11.1 必須シナリオ

- レシピ登録・編集・論理削除・削除済み検索・復元
- 削除済みレシピを含む未来献立と履歴の表示
- 新規JSONの正常Import、部分エラー、重複、削除済み復元
- 平日、祝日、ご飯不要日を考慮した献立提案
- 条件不足時の段階的緩和と警告
- 通常時の週内重複禁止と、候補不足時の警告付き緩和
- 手動変更後の再評価
- 調理実績・履歴変更後の最終調理日再計算
- 買い物リストの合算・合算不能・再生成警告
- 全データバックアップ・復元・旧版変換・破損拒否
- オフライン起動、APK上書き更新、DB移行失敗時の保護

### 11.2 境界値

- レシピ名1文字・100文字・101文字
- 材料1件・大量材料、分量未設定、小数
- 調理時間1分・1440分・範囲外
- Import 100件・101件、5MB以内・超過
- レシピ合計500件、履歴5年分
- 2週間境界の13日・14日
- 年末年始、うるう年、祝日とご飯不要日の重複

### 11.3 性能測定環境

- 献立提案アルゴリズム: 開発PC上のJVM、レシピ500件。ウォームアップ後に3回測定し、最も遅い値が5秒以内であることを確認する。
- 端末互換性: Android EmulatorのAndroid 8.0（API 26）、メモリ2GB以上で主要操作がタイムアウトせず完了することを確認する。
- 実端末で日常利用を開始する前に、主要画面、献立提案、バックアップ・復元の操作感を確認する。
- 測定時にPCまたは端末名、OS版、メモリ、データ件数、測定回数を記録する。

## 12. 実装順序

コーディング工程では、次の順で実装する。

1. Androidプロジェクト基盤、Compose、画面遷移
2. Kotlinモデル、Room、内蔵マスター
3. レシピCRUD・論理削除・検索
4. 新規レシピJSON Import
5. 献立・ご飯不要日・履歴
6. 献立提案、栄養、旬、食材ロス、警告
7. 買い物リスト
8. バックアップ・復元・DB移行
9. 総合テスト、性能、APK更新確認

各段階の実装範囲は、コーディング開始前にユーザーの指示へ従う。
