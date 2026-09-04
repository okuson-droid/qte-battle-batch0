# 納品前の機械チェック

引き継ぎ書3章の項目のうち、機械化できるものをここに置く。
**このディレクトリはリポジトリに含めること。** Batch 12a 着手時、前チャットのサンドボックスに
あった `check_undeclared.py` が失われて作り直しになったため、以後はリポジトリで管理する。

## 使い方

リポジトリのルートで実行する。

```bash
python3 tools/check_structure.py src/main/java                       # ★最優先。メソッド構造の破壊
python3 tools/check_all.py .                                         # 項目 1・3・5・6
python3 tools/check_records.py src/main/java                         # 項目 4
python3 tools/check_undeclared.py src/main/resources/static/js/*.js  # 項目 8
node --check src/main/resources/static/js/battle.js                  # 項目 7
python3 tools/check_legacy_ids.py                                    # ★Batch 60
python3 tools/check_leader_abilities.py
python3 tools/report_effects.py --summary                            # 未実装 0 枚が正常値
python3 tools/check_card_text_numbers.py                             # ★Batch 67
python3 tools/mark_text_reviewed.py --check                          # ★Batch 67
python3 tools/break_check_runner.py --self-test                       # ★★★Batch 82
```

★★★**最後の1行は「壊し検証を回す道具」自身の番人である**(★Batch 82)。
ランナーは Python の道具であり、**JUnit にも verify にも照合先が無い** ——
★<b>だから番人はここにしか置けないし、ここに書かなければ誰も回さない</b>(70 の教訓)。

### ★★Batch 67: 「未実装0枚」の隣に置いた2本

`report_effects.py` の「未実装0枚」は**登録が在るか**しか見ていない(裁定303)。
本文が Ver1.1 で差し替わったのに実装が Ver0.4 のまま残っていても、あのツールは何も言わない
—— 64 で1枚、67 でさらに7枚が見つかった。そこで2本足した。

| ファイル | 見るもの |
|---|---|
| `check_card_text_numbers.py` | **本文の数値がそのカードの実装に現れているか。**★コメントの数字は数えない(旧本文が書き写されていることがあり、いちばん信用してはいけない場所である)。★0 と 1 は数えない(「1枚につき」は実装に現れない)。説明の付いた不足は `KNOWN_GAPS` に理由つきで持つ |
| `mark_text_reviewed.py` | **本文と実装の突き合わせ台帳**(`src/test/resources/text-impl-review.json`)の更新と点検。`--check` は台帳と本文の食い違いを一覧する。番人は JUnit の `CardTextReviewTest` である |

★**台帳を更新するときは `--card` と `--note` の両方が要る。**
理由を書かずに緑にできない形にしてある —— 台帳は緑にするための書類ではなく、点検の記録である(裁定196)。

★★**Batch 68: `check_card_text_numbers.py` に「名前を付けた効果のラムダを展開する」を足した。**
2つの誘発が同じ効果を共有する形(`Consumer<EffectContext> galeFox = ctx -> {...};` を
`register` に2回渡す)にすると、`register` の文には**ラムダの名前しか残らない**ため、
中の数が数えられなくなっていた。
★これは数値の定数(`NUMBER_CONST_DECL`)と同じ問題である ——
**「同じ規則を2箇所に書かない」(裁定130)に従った実装ほど、数を数える番人から不利になる。**
番人が実装の書き方を狭めてはいけないので、ツールの側を広げた。

## 各スクリプトが見るもの

| ファイル | 対応する項目 |
|---|---|
| `check_structure.py` | **メソッド構造の破壊 / 同一クラス内メソッドの未解決**(Batch 12a の事故対応) |
| `check_all.py` | 1 package宣言とディレクトリの一致 / 3 カードIDの実在 / 5 メソッド参照の解決 / 6 デッキプリセットの枚数と同名制限 |
| `check_records.py` | 4 recordのコンストラクタ引数の数 |
| `check_undeclared.py` | 8 JSの未宣言変数(Batch 11a の事故の再発防止) |
| `check_legacy_ids.py` | ★Batch 60。Ver0.4 形式のカードID(QTE-0001 等)が本番のコードに書かれていないこと / 由来のIDが重複せず169枚に付いていること |
| `check_leader_abilities.py` | リーダーの【起動：n】がテキストと一致すること |
| `report_effects.py` | 効果の実装状況(`--summary` で枚数だけ)。**未実装0枚が正常値である**。★ただし「本文どおり」は意味しない(裁定303) |
| `check_card_text_numbers.py` | ★Batch 67。本文の数値と実装の数値の照合 |
| `mark_text_reviewed.py` | ★Batch 67。本文と実装の突き合わせ台帳の更新・点検 |
| `batchNN_break_check.py` | そのバッチの「壊し検証」。実装をわざと壊して試験が落ちることを確かめる。★★★**Batch 82 以降は `CASES` の表だけを持ち、回すのは `break_check_runner.py` である** |
| ★`break_check_runner.py` | ★★★**Batch 82。壊し検証を「複製の上で・2並列で」回す共通ランナー。**<br>★`--batch NN` で `batchNN_break_check.py` の `CASES` を読み込んで回す(★**過去のスクリプトは1文字も書き換えていない**)。<br>★★<b>本体は1バイトも書き換えない</b> —— 70 の「殺されて壊したまま残る」事故が**構造的に起きない**。<br>★★★<b>`--self-test` がこのランナー自身の番人である(15本)</b> |

### ★Batch 60 で消したもの

| ファイル | なぜ消したか |
|---|---|
| `build_id_map.py` | Ver0.4 台帳と Ver1.1 の対応表を作るツール。台帳(`qte-cards.json`)を 60 で削除したので作れないし、46b の機械変換はとうに終わっている。台帳を見ずに確かめられる分だけ `check_legacy_ids.py` に移した |
| `rework_triage.py` | 作り直し(P5)121枚の進捗を数えるツール。Batch 59 で121枚すべてを消化して**完了した**ので、数え直す相手が居ない。当時の内訳は `notes/rework-triage.md` に記録として残る |

`convert_manual_cards.py`(CSV → manual-cards.json)は**退役した**。実行すると理由を出して止まる ——
読んでいた台帳が無く、そのうえ今の `manual-cards.json` は全235枚に本文を持っているので、
再実行すると本文が丸ごと消えるためである。カードを増やすときは、あのファイルに書かれた
CSV の列の意味と検査項目を読んだうえで、本文を保つ変換を新しく書くこと。

---

## ★ check_structure.py が最優先である理由

### Batch 12a で起きた事故

`str_replace` の `old_str` に次の3行をまとめて含め、`new_str` 側で後ろ2行を書き忘れた。

```java
    return new ValidatedTargets(...);
    }                                      // ← validateTargets を閉じる
    private void requireCount(...) {       // ← 次のメソッドの宣言
```

結果、`requireCount` の本体が `validateTargets` の中に取り残された。

### なぜ既存のチェックが全部素通りしたのか

**`}` が1つと `{` が1つ、同時に消えた。**

| チェック | 結果 | 理由 |
|---|---|---|
| 括弧の総数の均衡 | 素通り | 234 対 234 で釣り合ってしまう |
| ブレース深度の追跡 | 素通り | 深度も末尾で0に戻る |
| `node --check` 相当 | — | Java には適用できない |

さらに悪いことに、**壊れた結果は括弧構造としては正当な Java** である
(`return` の後ろに到達不能コードが続くだけ)。したがって構造の検査では原理的に捕まらない。

### 何を見れば捕まるのか

**消えたメソッドを呼んでいる箇所が残る。** これが唯一かつ確実な信号である。
`check_structure.py` の項目 B は、同一クラス内の bare call
(`.` を前置しない `foo(` 形式の呼び出し)が、そのクラスに宣言されているかを照合する。
javac の `cannot find symbol: method xxx` に最も近い検査になっている。

事故を再現して検証済みで、javac が報告した5箇所とまったく同じ行を検出する。

### 教訓

**`str_replace` の `old_str` に「次のメソッドの宣言行」を含めない。**
含めざるをえない場合は、`new_str` に必ず同じ行を書き戻したうえで
`check_structure.py` を回すこと。

---

## 既知の誤検出

### check_records.py

`<` `>` をジェネリクスの括弧として数えるため、引数の中に単独の比較演算子(`a > b`)があると
引数の数がずれる。またオーバーロードされたコンストラクタの呼び出しも不一致として出る。
**不一致が出たら必ず該当行を目視すること。**

Batch 12a 時点で不一致として出るのは次の3件で、いずれも問題ない。

- `GameActions.java` / `GameService.java` の `new EffectContext(...)` 7引数
  → 強化使用フラグを持たない文脈のための追加コンストラクタ
- `CardEffectRegistry.java` の `new LeaderAbilitySpec(...)`
  → 引数内の `getFaceDownManaCount() > 0` による誤検出

### check_structure.py

次の呼び出しは同一クラスに宣言がなくても正当なため、`NOT_METHODS` で除外している。
新しい関数型インタフェースを使い始めたら、その呼び出しメソッド名をここに足すこと。

- 列挙型の暗黙メソッド: `values` `valueOf` `ordinal` `name`
- 関数型インタフェースの呼び出し: `test` `apply` `accept` `get` `run` `call` `compare`
- メソッド参照経由で渡されたもの: `toMaster`
