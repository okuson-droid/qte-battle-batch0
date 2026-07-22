# 納品前の機械チェック

引き継ぎ書3章の8項目のうち、機械化できるものをここに置く。
**このディレクトリはリポジトリに含めること。** Batch 12a 着手時、前チャットのサンドボックスに
あった `check_undeclared.py` が失われて作り直しになったため、以後はリポジトリで管理する。

## 使い方

リポジトリのルートで実行する。

```bash
python3 tools/check_all.py .                      # 項目 1・3・5・6
python3 tools/check_records.py src/main/java      # 項目 4
python3 tools/check_undeclared.py src/main/resources/static/js/*.js   # 項目 8
node --check src/main/resources/static/js/battle.js                   # 項目 7
```

## 各スクリプトが見るもの

| ファイル | 対応する項目 |
|---|---|
| `check_all.py` | 1 package宣言とディレクトリの一致 / 3 カードIDの実在 / 5 メソッド参照の解決 / 6 デッキプリセットの枚数と同名制限 |
| `check_records.py` | 4 recordのコンストラクタ引数の数 |
| `check_undeclared.py` | 8 JSの未宣言変数(Batch 11a の事故の再発防止) |

## 既知の誤検出

`check_records.py` は `<` `>` をジェネリクスの括弧として数えるため、
引数の中に単独の比較演算子(`a > b`)があると引数の数がずれる。
またオーバーロードされたコンストラクタの呼び出しも不一致として出る。
不一致が出たら必ず該当行を目視し、そのどちらかであることを確認すること。

Batch 12a 時点で不一致として出るのは次の3件で、いずれも問題ない。

- `GameActions.java` / `GameService.java` の `new EffectContext(...)` 7引数
  → 強化使用フラグを持たない文脈のための追加コンストラクタ
- `CardEffectRegistry.java` の `new LeaderAbilitySpec(...)`
  → 引数内の `getFaceDownManaCount() > 0` による誤検出
