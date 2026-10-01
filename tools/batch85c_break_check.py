#!/usr/bin/env python3
"""Batch 85c(操作の作り直し・b系)の壊し検証(裁定116)。

★★★<b>回すのは `tools/break_check_runner.py` である</b>(Batch 82・設計判断64)——
  このファイルは `CASES` と `EXPECTED_NG` の表だけを持つ。

```bash
python3 tools/break_check_runner.py --batch 85c
```

★<b>壊すのは複製であり、本体は1バイトも書き換わらない</b>。

---

## ★★★このバッチが入れたもの(裁定381〜385)

| 何を | どこに |
|---|---|
| ポインタの身振り(ドラッグ・矢印・長押し) | `battle.js` の 2-8b 節。★<b>マウス・タッチ・ペンを1本の口で受ける</b> |
| HTML5 DnD の退役 | `attachDrag` は `draggable` を常に偽にし、`auto-grab` の印と `onpointerdown` だけを付ける |
| 落とし先の判定 | `dropZoneAt`(指の座標から引く)。★器ごとの DnD の手当て(`registerDropZone`)は退役した |
| 矢印の攻撃 | `aimStart` / `aimMove` / `aimEnd`。★送るのはクリック2回と同じ関数(裁定381) |
| 長押しの拡大 | タッチだけ・500ms(裁定384)。★出すのは右クリックと同じ拡大 |
| 指の扱い | `.auto-grab` / `.auto-aim-source` とその中身に `touch-action: none`(裁定385) |

★<b>版数を上げた</b>: `battle.js` v=44 → **45** / `battle.css` v=59 → **60**(5枚とも)。
★`battle-fx.js`(v=2)・`manual-battle.js`(v=34)・`SFX_VERSION`(1)は据え置き。

---

## ★★★軸と番人の対応(82 の教訓: 1対1であることを数える)

| 軸 | 壊すもの | 落ちる番人 |
|---|---|---|
| 1 | 掴めるカードに印(auto-grab)を付けない | 掴めるカードと矢印の元にだけ |
| 2 | draggable を真に戻す(HTML5 の DnD が身振りを奪う) | 掴めるカードと矢印の元にだけ |
| 3 | 身振りのあとのクリックを捨てない | 長押し(500ms)で拡大し |
| 4 | クリックの抑えを持ち越す | ドラッグのあとのクリックの抑えは |
| 5 | 矢印を離しても送らない | 対象の上で離すと、確認なしで攻撃が飛ぶ |
| 6 | リーダーから矢印を引けない | 掴めるカードと矢印の元にだけ |
| 7 | 動かしているあいだもホバーの拡大を出す | カードを動かしているあいだは拡大を出さず |
| 8 | 長押しを 100ms にする | 長押し(500ms)で拡大し |
| 9 | カードの中身に touch-action を当てない | 指で手札を掴んで場へ落とすと |
| 10 | ★落とし先を引けない(70 の軸17 の当て直し) | ドラッグで落とすと確認なしでプレイされ |
| 11 | ★演出モジュールを battle.js の後に読む(84c の軸8 の当て直し) | battle.js より先に読まれる |

★★<b>軸10・11 は過去の表の当て直しである</b> —— 85c は `registerDropZone` を退役させ、`battle.js` の版数を上げたので、
70 の軸17 と 84c の軸8 は SETUP-NG になった(過去の表は書き換えない・82 の決まり)。
★<b>触ったファイルを持つ過去の表は、着手後に静的に数えた</b>(各表の「置換前」の文字列がちょうど1回当たるか)——
SETUP-NG は verify を回さなくても分かる(84c の落とし穴の続き)。

---

## ★★壊しどころが無いもの(裁定196 の正直な扱い)

1. **送信の口のガード(裁定365)**。★85c は触っていない。84b の軸3 が守っている。
2. ★★<b>`draggable` を真に戻しても、身振りは奪われない</b>(軸2 が落とすのは 85c-1 だけである)——
   掴めるカードの上では `dragstart` を止める手当てを別に置いたので、<b>守りが二重になっている</b>(深い守りであって穴ではない)。
   ★逆に<b>その手当てだけを外しても落ちない</b>: ハーネスのカードには画像が無く、ブラウザ自身のドラッグが始まる要素が無いからである。
   ★実ページのカードには画像があるので、<b>実機でしか測れない</b>(実機確認に回した)。
3. **身振りの閾値(6px)**。★<b>壊しても落ちない</b> —— 閾値を 0 にしても、実マウスのクリックは動かない押下なのでクリックのまま。
   ★<b>閾値が守っているのは「押したまま少しぶれた指をクリックにする」ことであり、実機でしか分からない</b>(実機確認に回した)。
"""

BATTLE_JS = "src/main/resources/static/js/battle.js"
BATTLE_CSS = "src/main/resources/static/css/battle.css"
BATTLE_HTML = "src/main/resources/templates/battle.html"

# ★壊しても落ちないことが分かっているもの(理由つき)。★★85c には1件も無い。
EXPECTED_NG = {}

CASES = [
    ("掴めるカードに印を付けない", BATTLE_JS,
     "    el.classList.add('auto-grab');\n    el.onpointerdown = (e) => gestureBegin(e, 'drag', el, { from, index, card, zones });",
     "    el.onpointerdown = (e) => gestureBegin(e, 'drag', el, { from, index, card, zones });",
     "verify", None, "掴めるカードと矢印の元にだけ"),
    ("draggable を真に戻す", BATTLE_JS,
     "    el.draggable = false;\n    const zones = dropZonesFor(card, from, latestView);",
     "    el.draggable = true;\n    const zones = dropZonesFor(card, from, latestView);",
     "verify", None, "掴めるカードと矢印の元にだけ"),
    ("身振りのあとのクリックを捨てない", BATTLE_JS,
     "    if (!suppressClickOnce) return;\n    suppressClickOnce = false;",
     "    return;\n    suppressClickOnce = false;",
     "verify", None, "長押し(500ms)で拡大し"),
    ("クリックの抑えを持ち越す", BATTLE_JS,
     "    setTimeout(() => { suppressClickOnce = false; },\n        pointerType === 'mouse' ? 0 : TOUCH_CLICK_WINDOW_MS);",
     "",
     "verify", None, "ドラッグのあとのクリックの抑えは"),
    ("矢印を離しても送らない", BATTLE_JS,
     "    const target = aimTargetAt(x, y);\n    aimClear(false);",
     "    const target = null;\n    aimClear(false);",
     "verify", None, "対象の上で離すと、確認なしで攻撃が飛ぶ"),
    ("リーダーから矢印を引けない", BATTLE_JS,
     "    myLeaderEl.classList.toggle('auto-aim-source', leaderReady);",
     "    myLeaderEl.classList.toggle('auto-aim-source', false);",
     "verify", None, "掴めるカードと矢印の元にだけ"),
    ("動かしているあいだもホバーの拡大を出す", BATTLE_JS,
     "        || !!(latestView && latestView.mulligan) || !!dragging || !!aiming;",
     "        || !!(latestView && latestView.mulligan);",
     "verify", None, "カードを動かしているあいだは拡大を出さず"),
    ("長押しを 100ms にする", BATTLE_JS,
     "const LONG_PRESS_MS = 500;", "const LONG_PRESS_MS = 100;",
     "verify", None, "長押し(500ms)で拡大し"),
    ("カードの中身に touch-action を当てない", BATTLE_CSS,
     ".auto-grab, .auto-aim-source,\n.auto-grab *, .auto-aim-source * {",
     ".auto-grab, .auto-aim-source {",
     "verify", None, "指で手札を掴んで場へ落とすと"),
    # 軸10: ★70 の軸17 の当て直し(85c が registerDropZone を退役させたので、あちらは SETUP-NG になった)
    ("落とし先を引けない", BATTLE_JS,
     "function dropZoneAt(x, y) {\n    if (!dragging) return null;",
     "function dropZoneAt(x, y) {\n    return null;",
     "verify", None, "ドラッグで落とすと確認なしでプレイされ"),
    # 軸11: ★84c の軸8 の当て直し(85c が battle.js の版数を v=45 に上げた)
    ("演出モジュールを battle.js の後に読む", BATTLE_HTML,
     '<script th:src="@{/js/battle-fx.js(v=2)}"></script>\n'
     '<script th:src="@{/js/battle.js(v=45)}"></script>',
     '<script th:src="@{/js/battle.js(v=45)}"></script>\n'
     '<script th:src="@{/js/battle-fx.js(v=2)}"></script>',
     "verify", None, "battle.js より先に読まれる"),
]
