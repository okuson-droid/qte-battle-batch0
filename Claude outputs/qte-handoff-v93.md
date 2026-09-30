# QTE 対戦アプリ — 引き継ぎ書 v93

最終更新: 2026-09-30(★**Batch 85a 完了時点**)。
★v92 の後に 84a・84b が完了しているが、どちらも引き継ぎ書を書いていない ——
<b>84a・84b の正は `notes/batch84a-design-notes.md` / `notes/batch84b-design-notes.md` である</b>。

| 項目 | 現在値 |
|---|---|
| JUnit | ★**1007件 全緑**(84a で 964 → 977、★85a で **+30**) |
| verify | **811件 全緑**(84b で 797 → 811。★85a は据え置き) |
| 効果の未実装 | **0枚**(据え置き) |
| 本文と実装の突き合わせ | **235 / 235枚(据え置き)** —— カード本文は1文字も触っていない |
| 版数 | ★**4系統すべて据え置き** —— `battle.js` v=43 / `battle.css` v=58 / `manual-battle.js` v=34 / `SFX_VERSION`=1 |
| 裁定 | ★★★**台帳は 1〜376・欠番ゼロ。新しい裁定は 377 から** |
| ★85a で変えたもの | ★**Java(通常モード)とノートだけ**。静的ファイル・テンプレート・カード定義・手動モードは0件 |
| ★判断待ち | ★**1件も残していない**(85b の着手時に1問伺う・下の 2章) |
| push の状態 | ★84b は `5af5cef`(メッセージは「プッシュ」)、85 の設計書は `f463a10` に載っている。★**85a は未 push** |

---

## 0. 最初にやること

1. `qte-project-reference.md` を読む。★★<b>最終更新は Batch 83 のままである</b>(84a・84b・85a は更新していない)——
   <b>現在値は本書の冒頭の表が正である</b>。
2. ★★★`notes/batch85-demo-fx-design.md` を読む。**85 の正である。**
3. ★★`notes/batch85a-design-notes.md` を読む(本バッチの設計解説)。
4. `notes/qte-pitfalls.md` の「★★★出来事の取り付け点は『状態が変わる文』で数える(Batch 85a)」ほか該当節。
5. `git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git`
   ★**85a が載っているか**は次で確かめる: `src/main/java/com/example/qte/game/GameEvent.java` が在る /
   `tools/batch85a_break_check.py` が在る / `mvn test` が **1007件**。
   ★★<b>コミットメッセージでは測らないこと</b>(84b は「プッシュ」という名前で載っていた)。
6. `m2repo.zip` を接続フォルダから取り込み(★zip の中は `repository\\...` なので `/root/m2work` へ展開する)、
   `mvn -o -B "-Dmaven.repo.local=/root/m2work/repository" test`。

---

## 1. 本バッチ(85a)がやったこと

★詳細は `notes/batch85a-design-notes.md`。ここには要点だけを書く。

- ★★★**サーバが「出来事」(`GameEvent`)を記録し、段と一緒に配るようにした。**
  `GameStep.events`(その段に属する出来事)/ `WsMessage.events`(最終状態に付く出来事)。
  ★種類は10: ATTACK / DAMAGE / HEAL / DESTROY / ★BANISH / SUMMON / CAST / DRAW / TURN / GAME_OVER。
- ★★**視点ごとの切り落としは `GameViewBuilder.buildEvents` の1箇所だけ**(設計判断9)。
  ★席は YOU / OPPONENT、リーダーは `leader:YOU` / `leader:OPPONENT`(★設計書の `leader:A` から変えた・2-1)。
  ★★<b>プレイヤーIDは1文字も届かない</b>(宛先そのものだから。設計判断42)。
  ★DRAW の面は本人にだけ届く。
- ★★★**取り付け点は20箇所**(`GameActions` 9 / `GameService` 11)。
  ★<b>母集団を「状態が変わる文」で数え直したら、設計書の表から3つ漏れていた</b> ——
  ★★★<b>戦闘によるリーダーへのダメージが `damageLeader` を通らない</b>(攻撃の一撃に数字が出ないところだった)。
- ★★**呼び出し元・`addLog` の呼び出し(243件)・`CardEffectRegistry` は1行も変えていない。**
- ★裁定371〜376 を採番して台帳へ写した(Q1〜Q6)。★<b>Q7・Q8 は工事の選択なので番号を振っていない</b>(Batch 82 の追記)。
- ★壊し検証 **38軸すべて OK**(1回目で NG 0)。★<b>23軸が1対1</b>。
  ★<b>84a の軸3・6・7 は形が変わって当たらなくなったので、85a の軸36〜38 で当て直した</b>。

---

## 2. 次の作業(85b)

### 85b(b系)—— デモの演出を移植する

★正は設計書 4章。移植元は `reference/card-battle/`(★`static/` から読み込まないこと。配信物ではない)。

★★**着手して最初に、マスターへ1問伺うこと:**

> ★★★**開始の配り(先攻4枚・後攻5枚)とマリガンのドローも DRAW を出している。**
> ★裁定357 は「開始の配り・マリガンは演出しない」と決めたが、<b>その理由は「材料が無い」だった</b>。
> ★<b>材料はもう在る</b>(85a の DRAW)。語るか・語らないかは画面の振る舞いなので、実装で決めない。
> (75 の教訓: 「旗を立てる人が居ない」は、居るようになった日に結論が変わる)

★その他の申し送り(設計解説 6章):

- ★★出来事が語る対象は差分演出を抑止する。★<b>鍵は `dst`</b>。
- ★★SUMMON は出どころを持たない(2-5)。<b>差分の飛行 → SUMMON の着地</b>で組み合わせる。
- ★《サイクロン・リフレッシュ》の禁忌由来ミニオンだけ BANISH が出ない(0-2)。差分の消滅のまま残る。
- ★`DAMAGE.after` は 0 未満になりうる(ビューの `currentHp` と同じ値)。
- ★ドローは1枚につき1件である(2-4)。「2枚引いた」は同じ段の DRAW を数える。
- ★<b>段の長さは `fxRegister` を通った ms から決まる</b>(84b 2-1)—— デモの演出も `fxRegister` を通すこと。
- ★<b>`filter` は使えない</b>(84b 7章)。演出層は `position: fixed`、祖先に `transform` / `filter` を付けない。

### 85c(b系)—— 操作の作り直し / 84c(b系)—— 段の長さの調整(★85b の後)

設計書 5章 / 84b 6章のとおり。

---

## 3. 発注者とのやりとり

- 呼び方は「クロエ」、マスターへの呼びかけは「マスター」。会話は日本語カジュアル体。ドキュメントは である調。
- 質問はバッチ化する。大きい工事は着手前に実寸を測って出す。間違いは結論から率直に指摘する。

---

## 4. マスターにお願いすること

1. ★★★**85a の変更を push する。**zip を展開してリポジトリへ上書きし、push する。
   ★<b>このセッションからは GitHub へ push できない</b>。
   ★★<b>`GameViewBuilder.java` は接続フォルダから8階層下にあり、こちらから直接書き込めない</b> ——
   だから今回は直接書き込みではなく、zip を「Claude outputs」フォルダへ置いた。
2. Eclipse で JUnit **1007件**の全緑を確かめる(refresh → Run As → JUnit Test)。
3. ★85a は画面を変えていないので、実機確認の新しい項目は無い(積み残しは v92 の4章のまま)。

---

## 5. デリバリー形式(変更なし)

変更ファイルのみの zip ＋ `notes/batchNN-design-notes.md` ＋ `claude/qte-handoff-vNN.md` ＋ 次チャット用プロンプト。
★消したファイルは zip に含めず、別途リストで挙げる(★85a は1つも消していない)。

---

## 6. チャット開始テンプレート(85b)

```
QTE Battle の開発を継続する。Batch 85b(デモの演出を移植する・b系)を行う。

読む順:
1. プロジェクトナレッジ内の `qte-project-reference.md`(★最終更新は 83 のまま。現在値は v93 の冒頭の表)
2. ★★★プロジェクトナレッジ内の `notes/batch85-demo-fx-design.md`(85 の正。4章が 85b)
3. ★★プロジェクトナレッジ内の `notes/batch85a-design-notes.md`(出来事の形。2章と6章)
4. プロジェクトナレッジ内の `claude/qte-handoff-v93.md`
5. `notes/batch84b-design-notes.md`(再生器・fxRegister・filter の罠)と `notes/qte-pitfalls.md` の該当節

環境:
6. git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git
   ★85a が載っているか: game/GameEvent.java と tools/batch85a_break_check.py が在ること。
     無ければ未 push。マスターに知らせて止まる。
   ★battle.js が v=43、battle.css が v=58 であること(85a は据え置き)。
   ★reference/card-battle/ が在ること(移植元)。
7. m2repo.zip を取り込んで mvn -o -B "-Dmaven.repo.local=/root/m2work/repository" test。
   ★1007件全緑が出発点である。verify は 811件。

作業:
8. ★★★着手して最初に、マスターへ1問伺う(v93 の 2章):
   開始の配り・マリガンのドローも DRAW を出している。裁定357 の前提(材料が無い)が消えた。語るか?
   ★回答は その場で採番し(377 から)、その場で notes/qte-rulings.md へ写す。
9. ★母集団は設計書 4章と 85a の出来事10種を出発点に、自分で数え直して固定する。
10. reference/card-battle/ の fx を static/js/battle-fx.js(IIFE・window.QteFx)へ束ね、
    battle.js は出来事 → 演出の対応表だけを持つ。演出の長さは fxRegister を通す。
    ★出来事が語る対象は差分演出を抑止する(鍵は dst)。★fxAllowed() に一本化する。
    ★手動モードは触らない。★静的ファイルを触ったら版数を上げる(battle.css は5枚とも)。
11. 壊し検証は tools/batch85b_break_check.py に CASES を書き、break_check_runner.py に回させる。
    ★1対1であることを数える。

呼び方は「クロエ」、こちらの呼び方は「マスター」で。会話は日本語カジュアル体、
ドキュメントは通常文体(である調)で。
```

---

## 7. 積み残し

### ★本バッチが新しく見つけたもの

- ★★★**戦闘によるリーダーへのダメージは `damageLeader` を通らない**(既知の据え置きだが、出来事の母集団としては設計書が数え落としていた)。85a は2箇所とも取り付けた。
- ★**《サイクロン・リフレッシュ》の禁忌由来ミニオンの消滅が `CardEffectRegistry` の中にある**(BANISH の取りこぼし)。
- ★**84a の壊し検証の軸3・6・7 が SETUP-NG になった**(85a の軸36〜38 が引き継いだ)。
- ★**裁定357 の前提が消えた**(85b の着手時に伺う)。
- ★`TASKS.md` は 84b を完了に移していなかった(本バッチで直した)。`CLAUDE.md` の「84 は着手前」も古い。

### 継続中の積み残し(v92 から)

- 実機確認 30項目 + 音20種(v92 の4章)
- 公開範囲の裁定(候補 V の前提)
- 固定待ち 239箇所 / 25.2秒・4並列で赤くなる番人 1本(候補 P2)
- `GameRoom` の肥大(★85a で +約50行)/ `resumeChoice` の整理
- カード名の表記ゆれ(【常在】：のコロンが3枚)
