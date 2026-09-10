# 開発の環境と道具

## 技術スタック(QTE 対戦アプリ)

| 層 | 中身 |
|---|---|
| サーバ | Spring Boot、STOMP over WebSocket、SimpleBroker |
| ゲーム状態 | サーバのメモリ上に保持。DB へ永続化しない |
| クライアント | 素の JavaScript + Bootstrap、Thymeleaf |
| 手元の環境 | Eclipse、Maven (m2e)、Lombok |
| 配置先 | Render (https://qte-battle-batch0.onrender.com/) |
| リポジトリ | https://github.com/okuson-droid/qte-battle-batch0 |

部屋はサーバの寿命を超えない。状態を持たないので、再起動すれば試合も消える。

## 試験と道具

| 道具 | 何をするか |
|---|---|
| `mvn test` | JUnit を回す。964件が現在の全緑 |
| `node verify/verify.js` | 画面の検証を回す。797件が現在の全緑。並列度は2 |
| `tools/break_check_runner.py` | 壊し検証を複製の上で2並列に回す共通ランナー |
| `tools/batchNN_break_check.py` | そのバッチの軸の表。`CASES` と `EXPECTED_NG` だけを持つ |
| `tools/report_effects.py --summary` | 効果の未実装枚数を出す。0枚が正常値 |
| `tools/mark_text_reviewed.py --check` | 本文と実装の突き合わせの鮮度を見る |
| `tools/check_records.py` | レコードの整合を見る。既知の誤検出が3件ある |

## 毎バッチの手順

1. `qte-project-reference.md` を読む
2. `notes/qte-pitfalls.md` の該当節を読む
3. 引き継ぎ書と直近の設計解説・依頼文を読む
4. `git clone --depth 1` でソースを取る
5. `mvn test` の全緑を出発点として確かめる
6. 作業する。母集団は着手前に固定して控える
7. 壊し検証をランナーに回させる
8. 成果物をそろえて出す

## モデルの使い分け

| 作業 | 難度 | モデル | 拡張思考 |
|---|---|---|---|
| 転記・台帳更新・ルール確認 | 低 | Sonnet | 不要 |
| b系(カード登録・デッキ・UI) | 中 | Sonnet | 不要 |
| a系(基盤設計・判定層・処理経路) | 高 | Opus | 必要 |
| 全文明の整合チェック | 最高 | Fable(要所のみ) | 必要 |
