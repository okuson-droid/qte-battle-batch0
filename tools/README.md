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
```

## 各スクリプトが見るもの

| ファイル | 対応する項目 |
|---|---|
| `check_structure.py` | **メソッド構造の破壊 / 同一クラス内メソッドの未解決**(Batch 12a の事故対応) |
| `check_all.py` | 1 package宣言とディレクトリの一致 / 3 カードIDの実在 / 5 メソッド参照の解決 / 6 デッキプリセットの枚数と同名制限 |
| `check_records.py` | 4 recordのコンストラクタ引数の数 |
| `check_undeclared.py` | 8 JSの未宣言変数(Batch 11a の事故の再発防止) |

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
