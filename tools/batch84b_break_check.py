#!/usr/bin/env python3
"""Batch 84b(通常モードの解決を段に分ける・クライアント側)の壊し検証(裁定116)。

★★★<b>回すのは `tools/break_check_runner.py` である</b>(Batch 82・設計判断64)。

```bash
python3 tools/break_check_runner.py --batch 84b
```

---

## ★★★このバッチが入れたもの

84a はサーバに段を作らせただけであり、<b>画面はまだ最終状態を一息で描いていた</b>。
84b が入れたのは<b>間</b>である(設計書 4章)。

| 何を | どこに |
|---|---|
| キューと再生器 | `battle.js` の 2.5 節。★<b>複数のメッセージにまたがって FIFO</b> |
| 段の長さ | ★★<b>`fxRegister` を通った ms をそのまま拾う</b> —— <b>表を2つ作らない</b>(設計書 4-2) |
| 調整の取り付け点 | `stepTune()` の1箇所。★84c はここだけを触ればよい |
| 入力ガード | `send()` の1箇所(裁定365)。★<b>覆いは被せない</b>(71 の判断) |
| 累計時間の上限 | `STEP_BUDGET_MS`(裁定364)。★<b>仕組みは 84b、値は 84c</b> |
| 畳んだことの表示 | `#auto-step-bar`(裁定368)。★サーバが畳んだぶん(裁定370)も同じ場所に出す |

★★★<b>飛ばす出口は作っていない</b>(裁定364)——
  <b>出口を1つ置いた瞬間、演出は「飛ばされる前提のもの」になる</b>。

★★<b>1段の中身は 80 のままである</b> ——
  `fxCaptureDelivery` → `render` → `fxSpawn` の順序も役割も1行も変えていない。
  変わったのは<b>いつ呼ばれるか</b>だけである。

★<b>静的ファイルを触ったので版数を上げた</b>: `battle.js` v=42 → **43** /
  `battle.css` v=57 → **58**(★<b>5枚のテンプレート全部</b> —— verify 36 が揃いを見張る)。

---

## ★★★軸と番人の対応(82 の教訓: 1対1であることを数える)

| 軸 | 壊すもの | 落ちる番人 |
|---|---|---|
| 1 | 段をキューへ積まない(最終状態だけ描く) | 段が届いても一息で最終状態にならない |
| 2 | 盤面を沈めない | 再生中は盤面が沈み |
| 3 | 入力ガードを外す | 再生中は盤面の操作が送信されない |
| 4 | 退室も断る | 再生中でも退室・ready は通る |
| 5 | 飛ばす出口を1つ足す | 飛ばす出口が存在しない |
| 6 | 累計時間の上限を外す | 上限を超えた連鎖は畳まれ |
| 7 | 畳んだ数を数えない | 上限で畳んだことは画面に出る |
| 8 | サーバが畳んだ段数を読まない | サーバが畳んだ段数も同じ場所に出る |
| 9 | 次の配信でキューを上書きする | 再生中に届いた次の配信は、後ろに継ぎ足される |
| 10 | 部屋消失でキューを捨てない | 部屋が消えたら、再生しかけの段は捨てられる |
| 11 | 演出を切っていても段を再生する | 演出を切っている人には段を再生せず |

---

## ★★壊しどころが無いもの(裁定196 の正直な扱い)

1. **退化の経路**(`steps` が空なら最終状態だけを描く)。
   ★<b>いまの形は「空なら回らない」であって、壊す対象の分岐が存在しない</b> ——
   <b>壊すには「空のときに落ちる」実装をわざわざ書くことになる</b>。
   ★★番人(84b-11)は置いてある。
2. **段の長さの出どころ**(`fxRegister` を通った ms)。
   ★<b>これは表ではなく<b>通り道</b>である</b> —— 壊すと演出そのものの長さが変わるので、
   <b>80 の番人(演出の時間は手動モードより長い・verify 80-13)が先に落ちる</b>。
   ★★<b>それでよい</b>: 段の長さの正が演出の長さであることは、
   <b>その番人が落ちること自体が示している</b>。
3. **サーバ側の段の作り方。**★84b は Java を1文字も触っていない(84a の担当)。
"""

BATTLE_JS = "src/main/resources/static/js/battle.js"

# ★壊しても落ちないことが分かっているもの(理由つき)。★★84b には1件も無い。
EXPECTED_NG = {}

# (説明, ファイル, 置換前, 置換後, 走らせ方, JUnitのクラス, 照合先の名前の一部)
CASES = [
    # 軸1: ★★★間そのものを消す。<b>84 の中心命題が丸ごと消える形である</b>
    ("段をキューへ積まない", BATTLE_JS,
     "    for (const step of steps) {\n"
     "        stepQueue.push({ view: step.view, logLine: step.logLine, final: false });\n"
     "    }",
     "",
     "verify", None, "段が届いても一息で最終状態にならない"),

    # 軸2: 沈めない(裁定365)。★<b>再生中であることが伝わらなくなる</b>
    ("盤面を沈めない", BATTLE_JS,
     "    if (root) root.classList.toggle('auto-step-sink', stepPlaying);",
     "    if (root) root.classList.remove('auto-step-sink');",
     "verify", None, "再生中は盤面が沈み"),

    # 軸3: ★★★入力ガードを外す(裁定365・項目5)。
    #   <b>古い盤面を見ている人が、既に居ないミニオンを選ぶ</b>
    ("入力ガードを外す", BATTLE_JS,
     "    if (stepBlocks(action)) {",
     "    if (false) {",
     "verify", None, "再生中は盤面の操作が送信されない"),

    # 軸4: ★退室まで断る。★★<b>7秒間、人が画面から出られなくなる</b>
    ("退室も断る", BATTLE_JS,
     "const STEP_FREE_ACTIONS = new Set(['ready', 'leave']);",
     "const STEP_FREE_ACTIONS = new Set([]);",
     "verify", None, "再生中でも退室・ready は通る"),

    # 軸5: ★★★飛ばす出口を1つ足す(裁定364・項目7c)。
    #   <b>これは「壊す」というより「裁定を破る」軸である</b> ——
    #   出口を足した人が、その日に赤を見る。
    ("飛ばす出口を1つ足す", BATTLE_JS,
     "/** 1段ずつ取り出して描く。★<b>間を空けるのはここだけである</b> */\nfunction stepPump() {",
     "function stepSkipAll() {\n"
     "    stepQueue = stepQueue.filter((item) => item.final);\n"
     "}\n"
     "document.addEventListener('click', stepSkipAll);\n\n"
     "function stepPump() {",
     "verify", None, "飛ばす出口が存在しない"),

    # 軸6: ★★★累計時間の上限を外す(裁定364)。
    #   <b>飛ばす出口が無いので、上限を外すと操作不能時間の上限も外れる</b>
    ("累計時間の上限を外す", BATTLE_JS,
     "const STEP_BUDGET_MS = 4000;",
     "const STEP_BUDGET_MS = 1000000;",
     "verify", None, "上限を超えた連鎖は畳まれ"),

    # 軸7: ★★畳みを「捨て」に変える(裁定368)。★<b>黙って消えるのがいちばん悪い形である</b>
    ("畳んだ数を数えない", BATTLE_JS,
     "    stepQueue = kept;\n    stepFolded += folded;",
     "    stepQueue = kept;",
     "verify", None, "上限で畳んだことは画面に出る"),

    # 軸8: ★サーバが畳んだぶん(裁定370)を読まない
    ("サーバが畳んだ段数を読まない", BATTLE_JS,
     "    stepFolded += (message.foldedSteps || 0);",
     "",
     "verify", None, "サーバが畳んだ段数も同じ場所に出る"),

    # 軸9: ★★★次の配信でキューを上書きする(項目6)。
    #   <b>表示されない盤面変化が生まれる</b> —— 静かな嘘である
    ("次の配信でキューを上書きする", BATTLE_JS,
     "    const steps = stepAllowed() ? (message.steps || []) : [];",
     "    stepQueue = [];\n    const steps = stepAllowed() ? (message.steps || []) : [];",
     "verify", None, "再生中に届いた次の配信は、後ろに継ぎ足される"),

    # 軸10: 部屋消失でキューを捨てない(項目8b)。★<b>もう無い盤面を再生し続ける</b>
    ("部屋消失でキューを捨てない", BATTLE_JS,
     "        stepDropAll();\n        showRoomLostFatal();",
     "        showRoomLostFatal();",
     "verify", None, "部屋が消えたら、再生しかけの段は捨てられる"),

    # 軸11: ★★演出を切っていても段を再生する(4-4-3)。
    #   <b>何も動かないまま待たされるだけになる</b> —— いちばん理不尽な形である
    ("演出を切っていても段を再生する", BATTLE_JS,
     "function stepAllowed() {\n    return fxAllowed();\n}",
     "function stepAllowed() {\n    return true;\n}",
     "verify", None, "演出を切っている人には段を再生せず"),
]
