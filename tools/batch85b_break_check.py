#!/usr/bin/env python3
"""Batch 85b(デモの演出を移植する・b系)の壊し検証(裁定116)。

★★★<b>回すのは `tools/break_check_runner.py` である</b>(Batch 82・設計判断64)——
  このファイルは `CASES` と `EXPECTED_NG` の表だけを持つ。

```bash
python3 tools/break_check_runner.py --batch 85b
```

★<b>壊すのは複製であり、本体は1バイトも書き換わらない</b>。

---

## ★★★このバッチが入れたもの

85a がサーバに足した<b>出来事</b>を、画面の演出にした(設計書 4章・裁定371〜377)。

| 何を | どこに |
|---|---|
| 演出モジュール(デモの fx/ 9本を1本の IIFE に束ねた) | ★新設 `static/js/battle-fx.js`(`window.QteFx`) |
| 出来事 → 演出の対応表(デモの director.js に相当) | `battle.js` の 2-10 節 |
| 出来事が語る対象の差分演出の抑止(鍵は `dst`) | `battle.js` の `fxSuppress` |
| 演出層のスタイル | `battle.css` の末尾の節 |
| ★84b の出口の修正(累計時間を操作をまたいで持ち越していた) | `battle.js` の `stepPump` |

★<b>版数を上げた</b>: `battle.js` v=43 → **44** / `battle.css` v=58 → **59**(5枚とも)/
  ★新設 `battle-fx.js` は **v=1**。★`manual-battle.js`(v=34)と `SFX_VERSION`(1)は据え置き。

---

## ★★★軸と番人の対応(82 の教訓: 1対1であることを数える)

| 軸 | 壊すもの | 落ちる番人 |
|---|---|---|
| 1 | 段の出来事をキューへ積まない | 攻撃は攻撃者の複製が的へ突進し |
| 2 | 攻撃のあいだ本物を隠さない | 攻撃は攻撃者の複製が的へ突進し |
| 3 | 攻撃が終わっても本物を戻さない | 攻撃が終わると本物が戻り |
| 4 | リーダーへの突進を盤面の縁で止める | リーダー欄まで突進する |
| 5 | リーダーの LP 演出を抑止しない | 差分の LP 演出は出ない |
| 6 | 回復 0 でも数字を出す | 回復は緑の数字で出る |
| 7 | 小さいダメージでも揺らす | 大きいダメージで画面を揺らし |
| 8 | 破壊したミニオンの差分を抑止しない | 破壊されたミニオンは旧位置で砕け散り |
| 9 | 消滅を破壊と同じ演出にする | 消滅は専用の演出で光に溶け |
| 10 | 召喚の着地を飛行のあとへずらさない | 召喚は差分の飛行のあとに着地し |
| 11 | 召喚したミニオンの出現を抑止しない | 召喚は差分の飛行のあとに着地し |
| 12 | 相手の呪文の面を見せない | 呪文は面で詠唱し |
| 13 | 詠唱位置を次の操作へ持ち越す | 次の操作には持ち越さない |
| 14 | ドローの出現を抑止しない | 自分のドローは面で、相手のドローは裏面で |
| 15 | 1枚ずつずらさない | 開始の配り |
| 16 | 観戦者にも「あなた」で語る | ターンと決着は帯テロップで語り |
| 17 | ★累計時間を操作をまたいで持ち越す(84b の穴を戻す) | 操作をまたいで再生時間の累計を持ち越さない |
| 18 | 演出モジュールが自分で演出の可否を判定する | 自分で演出の可否を判定しない |
| 19 | 演出モジュールを battle.js の後に読む | battle.js より先に読まれる |

---

## ★★壊しどころが無いもの(裁定196 の正直な扱い)

1. **段の長さの出どころ**(`fxRegister` を通った ms)。★84b の表(2-1)と同じ理由である ——
   <b>壊すと演出そのものの長さが変わるので、85b-4(段の長さは演出の長さに追随する)が落ちる</b>。
   ★★<b>それでよい</b>: 段の長さの正が演出の長さであることは、その番人が落ちること自体が示している。
2. **演出を切っている人に出さない**(85b-20)。★<b>門が2つある</b>(段を積まない `stepAllowed` と、
   差分を採った直後の `fxAllowed`)。★どちらか1つを外しても、もう1つが止めるので落ちない ——
   <b>これは深い守りであって穴ではない</b>(84b の軸11 が1つ目の門を測っている)。
3. **相手のドローの面**。★<b>面を運ぶかどうかはサーバが決める</b>(`GameViewBuilder.buildEvents`・85a)。
   クライアントは届いた ID を描くだけで、描かない判断を持たない。★85a の番人が守っている。
"""

BATTLE_JS = "src/main/resources/static/js/battle.js"
BATTLE_FX = "src/main/resources/static/js/battle-fx.js"
BATTLE_HTML = "src/main/resources/templates/battle.html"

# ★壊しても落ちないことが分かっているもの(理由つき)。★★85b には1件も無い。
EXPECTED_NG = {}

# (説明, ファイル, 置換前, 置換後, 走らせ方, JUnitのクラス, 照合先の名前の一部)
CASES = [
    # 軸1: ★★★段の出来事をキューへ積まない。<b>85b の中心命題が丸ごと消える形である</b>
    ("段の出来事をキューへ積まない", BATTLE_JS,
     "final: false, events: step.events || [] });",
     "final: false, events: [] });",
     "verify", None, "攻撃は攻撃者の複製が的へ突進し"),

    # 軸2: ★攻撃のあいだ本物を隠さない。<b>同じ1体が2枚に見える</b>
    ("攻撃のあいだ本物を隠さない", BATTLE_JS,
     "    ghost.dataset.fxTarget = e.dst;\n    att.classList.add('auto-fx-hold');",
     "    ghost.dataset.fxTarget = e.dst;",
     "verify", None, "攻撃は攻撃者の複製が的へ突進し"),

    # 軸3: ★★攻撃が終わっても本物を戻さない。<b>攻撃したミニオンが盤面から消えたままになる</b>
    ("攻撃が終わっても本物を戻さない", BATTLE_JS,
     "    const entry = fxStoryEntry(layer, 'attack', window.QteFx.MS.ATTACK, null,\n"
     "        () => att.classList.remove('auto-fx-hold'));",
     "    const entry = fxStoryEntry(layer, 'attack', window.QteFx.MS.ATTACK, null, null);",
     "verify", None, "攻撃が終わると本物が戻り"),

    # 軸4: ★★★リーダーへの突進を盤面の縁で止める(裁定371 の (b) の形)
    ("リーダーへの突進を盤面の縁で止める", BATTLE_JS,
     "    const tgt = fxRefElement(dst);\n    const rect = fxRectOf(att);",
     "    const tgt = dst && dst.leader ? autoAnchorElement(fxPlace(dst.seat, 'FIELD')) : fxRefElement(dst);\n"
     "    const rect = fxRectOf(att);",
     "verify", None, "リーダー欄まで突進する"),

    # 軸5: ★★リーダーの LP 演出を抑止しない(裁定376)。<b>数字が2つ出る</b>
    ("リーダーの LP 演出を抑止しない", BATTLE_JS,
     "        if (fx.kind === 'lp' && leaderSeats.has(fx.seat)) return false;\n",
     "",
     "verify", None, "差分の LP 演出は出ない"),

    # 軸6: ★回復 0 でも数字を出す。<b>+0 は何も語らない</b>
    ("回復 0 でも数字を出す", BATTLE_JS,
     "    const amount = e.amount || 0;\n    if (amount <= 0) return null;",
     "    const amount = e.amount || 0;\n    if (amount < 0) return null;",
     "verify", None, "回復は緑の数字で出る"),

    # 軸7: ★★小さいダメージでも揺らす(裁定375 の「大きい」を外す)
    ("小さいダメージでも揺らす", BATTLE_JS,
     "        if (!heal && amount >= FX_SHAKE_DAMAGE) {",
     "        if (!heal && amount >= 1) {",
     "verify", None, "大きいダメージで画面を揺らし"),

    # 軸8: ★★★破壊したミニオンの差分を抑止しない(設計書 4-2)。<b>砕けたものが墓地へも飛ぶ</b>
    ("破壊したミニオンの差分を抑止しない", BATTLE_JS,
     "        if (fx.fromId && gone.has(fx.fromId)) return false;\n",
     "",
     "verify", None, "破壊されたミニオンは旧位置で砕け散り"),

    # 軸9: ★★消滅を破壊と同じ演出にする(裁定372 の (a) の形)
    ("消滅を破壊と同じ演出にする", BATTLE_JS,
     "        ? window.QteFx.playBanish(stage, ghost, { hue: hue })",
     "        ? window.QteFx.playDeath(stage, entry.holder, ghost, { hue: hue })",
     "verify", None, "消滅は専用の演出で光に溶け"),

    # 軸10: ★召喚の着地を飛行のあとへずらさない(85a 2-5)。<b>飛んでいる最中に着地が始まる</b>
    ("召喚の着地を飛行のあとへずらさない", BATTLE_JS,
     "    const delay = flies ? FX_MOVE_MS : 0;",
     "    const delay = 0;",
     "verify", None, "召喚は差分の飛行のあとに着地し"),

    # 軸11: ★★召喚したミニオンの出現を抑止しない。<b>着地とフェードインが重なる</b>
    ("召喚したミニオンの出現を抑止しない", BATTLE_JS,
     "        if (fx.kind === 'appear' && fx.toId && summoned.has(fx.toId)) return false;\n",
     "",
     "verify", None, "召喚は差分の飛行のあとに着地し"),

    # 軸12: ★★★相手の呪文の面を見せない(裁定373 を破る)。<b>相手の手は何もかも初見である</b>
    ("相手の呪文の面を見せない", BATTLE_JS,
     "    const face = cardFace(fxFaceOfCard(cardId), 'full');",
     "    const face = cardFace(fxFaceOfCard(side === 'opponent' ? null : cardId), 'full');",
     "verify", None, "呪文は面で詠唱し"),

    # 軸13: ★★詠唱位置を次の操作へ持ち越す。<b>次の操作のダメージが前の呪文の弾になる</b>
    ("詠唱位置を次の操作へ持ち越す", BATTLE_JS,
     "    if (item.final) fxCastFrom = null;",
     "",
     "verify", None, "次の操作には持ち越さない"),

    # 軸14: ★★ドローの出現を抑止しない。<b>飛んできた1枚の横で、同じ1枚がもう一度現れる</b>
    ("ドローの出現を抑止しない", BATTLE_JS,
     "        if (fx.kind === 'appear' && fx.to.zone === 'HAND' && drawQuota[fx.to.seat] > 0) {",
     "        if (false) {",
     "verify", None, "自分のドローは面で、相手のドローは裏面で"),

    # 軸15: ★1枚ずつずらさない(裁定377)。<b>配りの9枚が1枚に重なって飛ぶ</b>
    ("1枚ずつずらさない", BATTLE_JS,
     "const FX_DRAW_STAGGER_MS = 110;",
     "const FX_DRAW_STAGGER_MS = 0;",
     "verify", None, "開始の配り"),

    # 軸16: ★観戦者にも「あなた」で語る。<b>観戦者には「あなた」が居ない</b>
    ("観戦者にも「あなた」で語る", BATTLE_JS,
     "    const spectator = !!(view && view.room && !view.room.viewerSeat);",
     "    const spectator = false;",
     "verify", None, "ターンと決着は帯テロップで語り"),

    # 軸17: ★★★累計時間を操作をまたいで持ち越す(84b の穴を戻す)
    ("累計時間を操作をまたいで持ち越す", BATTLE_JS,
     "        //   ★★85b で段が長くなって表に出た(1操作 = 詠唱 1.2秒 + 着弾 1.3秒 × n)。\n"
     "        stepSpent = 0;\n",
     "        //   ★★85b で段が長くなって表に出た(1操作 = 詠唱 1.2秒 + 着弾 1.3秒 × n)。\n",
     "verify", None, "操作をまたいで再生時間の累計を持ち越さない"),

    # 軸18: ★★演出モジュールが自分で演出の可否を判定する(裁定375・正を2つにする)
    ("演出モジュールが自分で演出の可否を判定する", BATTLE_FX,
     "    const MS = Object.freeze({",
     "    const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;\n"
     "    const MS = Object.freeze({",
     "verify", None, "自分で演出の可否を判定しない"),

    # 軸19: ★演出モジュールを battle.js の後に読む(設計書 4-1)
    ("演出モジュールを battle.js の後に読む", BATTLE_HTML,
     '<script th:src="@{/js/battle-fx.js(v=1)}"></script>\n'
     '<script th:src="@{/js/battle.js(v=44)}"></script>',
     '<script th:src="@{/js/battle.js(v=44)}"></script>\n'
     '<script th:src="@{/js/battle-fx.js(v=1)}"></script>',
     "verify", None, "battle.js より先に読まれる"),
]
