# Third-Party Notices

RecipeManagerは、以下の主要なオープンソースソフトウェアを利用している。各ソフトウェアの著作権とライセンスは、それぞれの権利者に帰属する。

## APKに含まれる主なライブラリ

| ソフトウェア | 用途 | ライセンス |
| --- | --- | --- |
| AndroidX / Jetpack / Jetpack Compose | Android UI、Lifecycle、Navigation、Roomなど | Apache License 2.0 |
| Kotlin Standard Library | Kotlinランタイム | Apache License 2.0 |
| Kotlin Coroutines / Serialization | 非同期処理、状態保存 | Apache License 2.0 |
| networknt JSON Schema Validator | Import・バックアップのSchema検証 | Apache License 2.0 |
| Jackson | JSON Schema Validatorの依存ライブラリ | Apache License 2.0 |
| SLF4J | JSON Schema Validatorの依存ライブラリ | MIT License |
| itu（com.ethlo.time） | JSON Schema Validatorの日時検証依存 | Apache License 2.0 |
| JSpecify / JetBrains Annotations | 型注釈 | Apache License 2.0 |
| Guava ListenableFuture | AndroidXの依存ライブラリ | Apache License 2.0 |

networknt JSON Schema Validatorには、同プロジェクトのNOTICEに記載されたApache Commons Validator由来の構成要素が含まれる。

## ビルド・テストで使用する主なソフトウェア

| ソフトウェア | 用途 | ライセンス |
| --- | --- | --- |
| Gradle / Gradle Wrapper | ビルド | Apache License 2.0 |
| Android Gradle Plugin | Androidビルド | Apache License 2.0 |
| Kotlin Gradle Plugin / KSP | Kotlinコンパイル、コード生成 | Apache License 2.0 |
| JUnit 4 | JVM単体テスト | Eclipse Public License 1.0 |
| AndroidX Test / Espresso / Compose UI Test | Android端末テスト | Apache License 2.0 |

## ライセンス本文

- Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0
- MIT License: https://opensource.org/license/mit
- Eclipse Public License 1.0: https://www.eclipse.org/legal/epl-v10.html

実際の配布前には、Release APKの解決済み依存関係を再確認し、追加・変更された依存ライブラリのNOTICEおよびライセンス条件を本ファイルへ反映する。
