#!/usr/bin/env python3
"""Batch 84c(段の長さの調整・b系)の壊し検証(裁定116)。

★★★<b>回すのは `tools/break_check_runner.py` である</b>(Batch 82・設計判断64)——
  このファイルは `CASES` と `EXPECTED_NG` の表だけを持つ。

```bash
python3 tools/break_check_runner.py --batch 84c
```

★<b>壊すのは複製であり、本体は1バイトも書き換わらない</b>。

---

## ★★★このバッチが決めたもの(裁定378〜380)

| 値 | 85b | ★84c |
|---|---|---|
| 数字の拍(`QteFx.MS.NUMBER`) | 800 | ★**500**(★数字そのものは余韻として 800ms 浮く) |
| 破壊の拍(`QteFx.MS.DEATH`) | 940 | ★**700** |
| 消滅の拍(`QteFx.MS.BANISH`) | 880 | ★**700** |
| 詠唱の拍(`QteFx.MS.CAST`) | 1200 | ★**900** |
| 総再生時間の上限(`STEP_BUDGET_MS`) | 4000 | ★**4000 で確定**(裁定379) |
| 画面を揺らすダメージ(`FX_SHAKE_DAMAGE`) | 5 | ★**5 で確定**(裁定380) |

★<b>版数を上げた</b>: `battle-fx.js` v=1 → **2**。★`battle.js`(v=44)・`battle.css`(v=59)は据え置き(1文字も触っていない)。

---

## ★★★軸と番人の対応(82 の教訓: 1対1であることを数える)

| 軸 | 壊すもの | 落ちる番人 |
|---|---|---|
| 1 | 数字の拍を 800 に戻す | 拍は数字500・破壊700 |
| 2 | 破壊の拍を 940 に戻す | 拍は数字500・破壊700 |
| 3 | 詠唱の拍を 1200 に戻す | 拍は数字500・破壊700 |
| 4 | 消滅の拍を 880 に戻す | 拍は数字500・破壊700 |
| 5 | 数字を拍と一緒に消す(余韻にしない) | 数字は拍が終わっても浮き続け |
| 6 | 上限を 3秒に下げる | 拍は数字500・破壊700 |
| 7 | 揺れの閾値を 3 に下げる | 拍は数字500・破壊700 |
| 8 | ★演出モジュールを battle.js の後に読む(85b の軸19 の当て直し) | battle.js より先に読まれる |
| 9 | ★★段をキューへ積まない(84b の軸1 の当て直し) | 段が届いても一息で最終状態にならない |

★★<b>軸1〜4・6・7 は同じ番人(84c-1)に当たる</b> —— 値の番人は1本にまとめた。
★<b>どの値が変わったかは、その番人の証拠(JSON)に全部載る</b>ので、1本でも読み分けられる。
★★<b>軸8 は 85b の軸19 の当て直しである</b> —— 版数を v=1 → v=2 に上げたので、85b の表の文字列はもう当たらない
(SETUP-NG)。★<b>過去の表は書き換えない</b>(82 の決まり・85a の軸36〜38 と同じ扱い)。
★★★<b>軸9 は 84b の軸1 の当て直しである</b> —— 85b が `stepQueue.push` の行に `events` を足したので、
84b の表の文字列は<b>85b の時点で既に SETUP-NG だった</b>。★<b>85b は 84b の表を回し直していなかったので気づかなかった</b>
(84c で 84b の表を回し直して見つけた)。
★★軸1・6 は<b>84c-3(長い操作が最後まで再生される)も落とす</b>はずである —— 値を戻すと《命を削る烈火》の最後の段が畳まれる。

---

## ★★壊しどころが無いもの(裁定196 の正直な扱い)

1. **段の長さが拍に追随すること**。★85b の軸表の1と同じ理由である(85b-4 が守っている)。
"""

BATTLE_JS = "src/main/resources/static/js/battle.js"
BATTLE_FX = "src/main/resources/static/js/battle-fx.js"

# ★壊しても落ちないことが分かっているもの(理由つき)。★★84c には1件も無い。
EXPECTED_NG = {}

VALUES = "拍は数字500・破壊700・消滅700・詠唱900"

# (説明, ファイル, 置換前, 置換後, 走らせ方, JUnitのクラス, 照合先の名前の一部)
CASES = [
    ("数字の拍を 800 に戻す", BATTLE_FX, "        NUMBER: 500,", "        NUMBER: 800,",
     "verify", None, VALUES),
    ("破壊の拍を 940 に戻す", BATTLE_FX, "        DEATH: 700,", "        DEATH: 940,",
     "verify", None, VALUES),
    ("詠唱の拍を 1200 に戻す", BATTLE_FX, "        CAST: 900,", "        CAST: 1200,",
     "verify", None, VALUES),
    ("消滅の拍を 880 に戻す", BATTLE_FX, "        BANISH: 700,", "        BANISH: 880,",
     "verify", None, VALUES),
    # 軸5: ★★数字を拍と一緒に消す。<b>拍が終わる前に数字が消え、読めなくなる</b>
    ("数字を拍と一緒に消す", BATTLE_FX,
     "        ], { duration: NUMBER_LIFE_MS, easing: 'ease-out', fill: 'forwards' }).finished",
     "        ], { duration: MS.NUMBER - 100, easing: 'ease-out', fill: 'forwards' }).finished",
     "verify", None, "数字は拍が終わっても浮き続け"),
    ("上限を 3秒に下げる", BATTLE_JS, "const STEP_BUDGET_MS = 4000;", "const STEP_BUDGET_MS = 3000;",
     "verify", None, VALUES),
    ("揺れの閾値を 3 に下げる", BATTLE_JS, "const FX_SHAKE_DAMAGE = 5;", "const FX_SHAKE_DAMAGE = 3;",
     "verify", None, VALUES),
    # 軸8: ★85b の軸19 の当て直し(版数が変わって、あちらの文字列は SETUP-NG になった)
    ("演出モジュールを battle.js の後に読む", "src/main/resources/templates/battle.html",
     '<script th:src="@{/js/battle-fx.js(v=2)}"></script>\n'
     '<script th:src="@{/js/battle.js(v=44)}"></script>',
     '<script th:src="@{/js/battle.js(v=44)}"></script>\n'
     '<script th:src="@{/js/battle-fx.js(v=2)}"></script>',
     "verify", None, "battle.js より先に読まれる"),
    # 軸9: ★★84b の軸1 の当て直し(85b が行を変えたので、あちらの文字列は SETUP-NG になった)
    ("段をキューへ積まない", BATTLE_JS,
     "    for (const step of steps) {\n"
     "        stepQueue.push({ view: step.view, logLine: step.logLine, final: false, events: step.events || [] });\n"
     "    }",
     "",
     "verify", None, "段が届いても一息で最終状態にならない"),
]
