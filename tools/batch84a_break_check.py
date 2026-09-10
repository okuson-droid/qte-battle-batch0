#!/usr/bin/env python3
"""Batch 84a(通常モードの解決を段に分ける・サーバ側)の壊し検証(裁定116)。

★★★<b>回すのは `tools/break_check_runner.py` である</b>(Batch 82・設計判断64)——
  このファイルは `CASES` の表だけを持つ。

```bash
python3 tools/break_check_runner.py --batch 84a
```

★<b>壊すのは複製であり、本体は1バイトも書き換わらない</b>。

---

## ★★★このバッチが入れたもの

解決の途中を<b>段</b>として控え、1メッセージに列として載せる(方式B・裁定362)。

| 何を | どこに |
|---|---|
| 段の境目 | `GameRoom.addLog` の**1箇所**。★<b>呼び出し243件は1行も変えていない</b>(裁定68) |
| 段の控え | `StepRecorder`。★閲覧者ごとにビューを作る(設計判断9 のフィルタを1本のまま通す) |
| 段の配信 | `GameBroadcaster.WsMessage.steps` / `foldedSteps` |
| 差し込みと片付け | `GameWsController.execute` の `try` / `finally` |
| ★上限 | **8段**(裁定370)。`FX_LIMIT` と同じ根拠であって、値を揃えたのではない |

★★★<b>配信の口は増えていない</b> —— `broadcast` の呼び出しは
  `GameWsController:359` の1箇所のままであり、増えたのは<b>1メッセージに載る段の数</b>である
  (裁定360 が退けた「配信の口を2つにする」ではない)。

---

## ★★★実測(`notes/batch84a-design-notes.md`)

カード169枚を本物の入口から1枚ずつ使って測った。

| | 実測 |
|---|---|
| 1操作あたりの段数 | **平均 2.33段 / 86.4%が2段以下 / 最大 24段** |
| 1ビューの JSON | **約20KB**(★Batch 29 の 18〜27KB と一致した) |
| ★上限が無いときの最悪 | ★★★**511KB / 1閲覧者**(従来の25倍) |
| ★8段で切ったとき | 169枚中**160枚(94.7%)は1段も畳まれない** |
| `GameViewBuilder.build` | 中央値 74µs。24段 × 3人で **5.4ms**(ロックの中) |
| ★3-5 の1(盤面が変わらない段を省く) | ★★**394段のうち19段(4.8%)しか無い** —— <b>測って外した</b> |

---

## ★★★軸と番人の対応(82 の教訓: 1対1であることを数える)

| 軸 | 壊すもの | 落ちる番人 |
|---|---|---|
| 1 | `addLog` から記録係の呼び出しを消す | `stepCountEqualsLogGrowth` |
| 2 | 記録係を差し込まない | `foldsBeyondLimit` |
| 3 | `finally` を消す(記録係を外さない) | `recorderIsAlwaysRemoved` |
| 4 | 上限を外す | `foldsBeyondLimit` |
| 5 | 畳みを「捨て」に変える(数えない) | `foldsBeyondLimit` |
| 6 | 段の並びを逆にする | `stepOrderEqualsLogOrder` |
| 7 | 通し番号を0から振る | `seqIsDense` |
| 8 | 段のビューを閲覧者で分けない | `stepViewsAreFilteredPerViewer` |
| 9 | 観戦者を宛先から外す | `spectatorGetsTheSameSteps` |

## ★★★NG は「番人が足りない」ことを教えた(83 の教訓の実例)

★<b>1回目の壊し検証で、軸6 と軸8 が NG だった。</b>どちらも実装ではなく<b>番人の側</b>が原因である。

| 軸 | 何が起きていたか | どう直したか |
|---|---|---|
| 6 | 番人が {@code GameRoom} の記録係を<b>試験が自分で差し込んで</b>測っていた ——<br>★<b>`StepRecorder` の並びを崩しても1本も落ちない</b>(測っている層が違う) | ★<b>配信された `steps` の並びで測る形へ移した</b>。<br>★★記録係の層の番人は `recorderSeesEveryLogLine` として残した |
| 8 | 番人が「手札が隠れているか」だけを見ていた ——<br>★★<b>全員に同じ視点を配っても、隠れているものは隠れたままである</b> | ★<b>「自分の側」が席ごとに入れ替わっていることを測る形にした</b> |

★★★<b>どちらも「番人を置く場所を選ぶ前に、そこまで届くかを確かめる」(70〜82)の形である</b> ——
  <b>届いていない場所に置いた番人は、緑のまま何も守らない</b>。

---

★★<b>軸2・4・5 は同じ番人に当たる。</b>これは1対1ではない ——
  <b>ただし壊しているものは違う</b>(段が無い / 畳まない / 数えない)。
  ★<b>「同じ番人が3通りの壊れ方を捕まえている」のであって、
    「番人が余っている」のではない</b>。
  ★★<b>軸2 は段そのものを消すので、他の番人も一緒に落ちる</b> ——
  <b>照合先は `foldsBeyondLimit` の1本だけを見る</b>。

---

## ★★壊しどころが無いもの(裁定196 の正直な扱い)

1. **クライアント側の再生器**(キュー・段の長さ・入力ガード・累計時間の上限)。
   ★<b>84a は1文字も書いていない</b> —— 84b の担当である。
   ★★<b>だから `battle.js` の版数も据え置きである</b>(73・77・79・82・83 と同じ判断)。
2. **退化の経路**(`steps` が空でも従来どおり描かれる)。
   ★<b>壊すには「空のときに落ちる」実装をわざわざ書くことになる</b> ——
   <b>いまの形は「空なら何もしない」であって、壊す対象の分岐が存在しない</b>。
   ★★番人(`viewStillArrivesWithoutSteps`)は置いてある ——
   <b>84b が読む側を書いたときに、そこが壊れうる場所になる</b>。
3. **手動モード。**★`ManualBroadcaster` も `ManualRoom.addLog` も別物であり、
   <b>構造的に届かない</b>(母集団Aで数え直した)。
"""

ROOM = "src/main/java/com/example/qte/room/GameRoom.java"
RECORDER = "src/main/java/com/example/qte/web/StepRecorder.java"
BROADCASTER = "src/main/java/com/example/qte/web/GameBroadcaster.java"
CONTROLLER = "src/main/java/com/example/qte/web/GameWsController.java"
TEST = "Batch84aStepTest"

# ★壊しても落ちないことが分かっているもの(理由つき)。★★84a には1件も無い。
EXPECTED_NG = {}

# (説明, ファイル, 置換前, 置換後, 走らせ方, JUnitのクラス, 照合先の名前の一部)
CASES = [
    # 軸1: ★★★段の境目そのものを消す。
    #   <b>「段 = ログ行」が崩れる、いちばん静かな形である</b> ——
    #   ログは今までどおり積まれるので、<b>ログを読む番人は1本も落ちない</b>。
    ("addLog から記録係の呼び出しを消す", ROOM,
     "        if (stepRecorder != null) {\n            stepRecorder.accept(message);\n        }\n",
     "",
     "junit", TEST, "stepCountEqualsLogGrowth"),

    # 軸2: 記録係を差し込まない(設計書 6章 項目1)
    ("記録係を差し込まない", CONTROLLER,
     "                room.setStepRecorder(recorder);\n",
     "",
     "junit", TEST, "foldsBeyondLimit"),

    # 軸3: ★★★片付けを消す(項目10)。
    #   <b>居残ると、次の操作の段に前の操作が混ざる</b>。
    ("記録係を外す finally を消す", CONTROLLER,
     "                try {\n                    action.apply(room);\n"
     "                } finally {\n                    room.setStepRecorder(null);\n                }\n",
     "                action.apply(room);\n",
     "junit", TEST, "recorderIsAlwaysRemoved"),

    # 軸4: ★★上限を外す(裁定370)。★実測では 511KB を送ることになる
    ("段の上限を外す", RECORDER,
     "    public static final int LIMIT = 8;",
     "    public static final int LIMIT = 1000;",
     "junit", TEST, "foldsBeyondLimit"),

    # 軸5: ★★★畳みを「捨て」に変える(裁定368)。
    #   <b>畳んだことが画面に出せなくなる</b> —— <b>黙って消えるのが、いちばん悪い形である</b>。
    ("畳んだ数を数えない", RECORDER,
     "            folded++;\n            return;",
     "            return;",
     "junit", TEST, "foldsBeyondLimit"),

    # 軸6: 段の並びを逆にする(項目2)
    ("段の並びを逆にする", RECORDER,
     "        frames.add(new StepFrame(logLine, byViewer));",
     "        frames.add(0, new StepFrame(logLine, byViewer));",
     "junit", TEST, "stepOrderEqualsLogOrder"),

    # 軸7: 通し番号を0から振る(飛び番・ずれの検出)
    ("通し番号を0から振る", RECORDER,
     "            steps.add(new GameStep(i + 1, frames.get(i).logLine(), view));",
     "            steps.add(new GameStep(i, frames.get(i).logLine(), view));",
     "junit", TEST, "seqIsDense"),

    # 軸8: ★★★段のビューを閲覧者で分けない(設計判断9)。
    #   <b>相手の手札が段に載って届く</b> —— 81 が裁定359 で塞いだのと同じ形の穴である。
    ("段のビューを閲覧者で分けない", RECORDER,
     "            byViewer.put(viewerId, viewBuilder.build(room, viewerId));",
     "            byViewer.put(viewerId, viewBuilder.build(room, viewerIds.get(0)));",
     "junit", TEST, "stepViewsAreFilteredPerViewer"),

    # 軸9: 観戦者を宛先から外す(裁定366・席で分岐しない)
    ("観戦者を宛先から外す", BROADCASTER,
     "        for (Spectator spectator : room.getSpectators()) {\n"
     "            ids.add(spectator.spectatorId());\n        }\n",
     "",
     "junit", TEST, "spectatorGetsTheSameSteps"),
]
