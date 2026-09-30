#!/usr/bin/env python3
"""Batch 85a(通常モードの出来事をサーバに足す・a系)の壊し検証(裁定116)。

★★★<b>回すのは `tools/break_check_runner.py` である</b>(Batch 82・設計判断64)——
  このファイルは `CASES` と `EXPECTED_NG` の表だけを持つ。

```bash
python3 tools/break_check_runner.py --batch 85a
```

★<b>壊すのは複製であり、本体は1バイトも書き換わらない</b>。

---

## ★★★このバッチが入れたもの

解決の途中で起きた<b>出来事</b>(`GameEvent`)をサーバが記録し、段と一緒に配る(裁定371〜376)。

| 何を | どこに |
|---|---|
| 出来事の形 | `game/GameEvent`(生・プレイヤーIDを持つ)/ `game/view/EventView`(閲覧者向け) |
| 溜め | `GameRoom.recordEvent` / `drainEvents`。★記録係が居るときだけ溜める |
| 段との結び付け | `StepRecorder.accept` が段を切るたびに汲み出す。★畳む段の出来事は落とす(裁定374) |
| 最終状態に付く出来事 | `StepRecorder.closeTail`(`GameWsController.execute` の `finally`) |
| ★視点ごとの切り落とし | `GameViewBuilder.buildEvents` の**1箇所**(設計判断9) |
| 取り付け点 | ★**20箇所**(`GameActions` 9 / `GameService` 11)。★`CardEffectRegistry` は1行も変えていない |

★★★<b>呼び出し元と `addLog` の呼び出し(243件)は1行も変えていない</b>(裁定68)。

---

## ★★★軸と番人の対応

軸は「取り付け点ごと」「溜めの着脱ごと」「視点の切り落としごと」に1本ずつ立てた
(81 の教訓: 軸を入口ごとに立てて、当てる先が無ければそれは足りない番人である)。
★落ちた番人の<b>本数</b>は `notes/batch85a-design-notes.md` 4章に数えてある(82 の教訓)。

★★★**軸 36〜38 は 84a の軸 3・6・7 の引き継ぎである。**
  85a が `finally` の中身・`StepFrame`・`GameStep` の形を変えたので、
  <b>84a の表の置換前の文字列はもう本体に無い</b>(SETUP-NG になる)。
  ★<b>過去の表は書き換えない</b>(82 の決まり)—— <b>同じ性質を、85a の表で新しい文字列に当て直した</b>。

---

## ★★壊しどころが無いもの(裁定196 の正直な扱い)

1. **《サイクロン・リフレッシュ》の禁忌由来ミニオンの消滅(BANISH)。**
   ★<b>取り付けていない</b> —— 消滅の処理が `CardEffectRegistry` の中にあり、
   85a の約束(`CardEffectRegistry` を1行も変えない)の外だからである。
   ★<b>取りこぼしとして設計解説 0章に書き残した</b>。壊すものが無い。
2. **クライアント。**★<b>85a は `battle.js` を1文字も触っていない</b>(画面は変えない)。
   ★★<b>だから版数も4系統すべて据え置きである</b>。
3. **手動モード。**★`manual/` 配下は `GameActions` を通らず、`ManualWsMessage` は出来事の欄を持たない
   (番人 `manualMessageHasNoEvents`)。<b>構造的に届かない</b>。
"""

ROOM = "src/main/java/com/example/qte/room/GameRoom.java"
RECORDER = "src/main/java/com/example/qte/web/StepRecorder.java"
BUILDER = "src/main/java/com/example/qte/game/view/GameViewBuilder.java"
CONTROLLER = "src/main/java/com/example/qte/web/GameWsController.java"
ACTIONS = "src/main/java/com/example/qte/game/GameActions.java"
SERVICE = "src/main/java/com/example/qte/game/GameService.java"
TEST = "Batch85aEventTest"
TEST84A = "Batch84aStepTest"

# ★壊しても落ちないことが分かっているもの(理由つき)。★★85a には1件も無い。
EXPECTED_NG = {}

# (説明, ファイル, 置換前, 置換後, 走らせ方, JUnitのクラス, 照合先の名前の一部)
CASES = [
    # ---- 溜めと段(設計書 3-3) ----

    # 軸1: ★★★溜めそのものを消す。<b>画面は何も変わらないので、いちばん静かな壊れ方である</b>
    ("出来事を溜めない", ROOM,
     "        pendingEvents.add(event);\n",
     "",
     "junit", TEST, "minionAttackCarriesWhoAndWhom"),

    # 軸2: ★★段を切るときに汲み出さない —— 出来事が全部「最終状態」へずれる
    ("段を切るときに汲み出さない", RECORDER,
     "        List<GameEvent> events = drain();\n        if (frames.size() >= LIMIT) {",
     "        List<GameEvent> events = List.of();\n        if (frames.size() >= LIMIT) {",
     "junit", TEST, "destroyBelongsToItsOwnLogLine"),

    # 軸3: ★★★畳む段では汲み出さない(裁定374)——
    #   <b>畳んだ段の出来事が、最終状態へ「ずれて」載る</b>(捨てるのではなく語り直してしまう)
    ("畳む段では汲み出さない", RECORDER,
     "        List<GameEvent> events = drain();\n"
     "        if (frames.size() >= LIMIT) {\n"
     "            // ★★★畳んだ段の出来事は、段と一緒に落とす(裁定374)。\n"
     "            //   最終状態には結果が載っているので、嘘にはならない\n"
     "            folded++;\n"
     "            return;\n"
     "        }\n",
     "        if (frames.size() >= LIMIT) {\n"
     "            folded++;\n"
     "            return;\n"
     "        }\n"
     "        List<GameEvent> events = drain();\n",
     "junit", TEST, "foldedStepsDropTheirEvents"),

    # 軸4: ★★最終状態に付く出来事を締めない(closeTail を呼ばない)
    ("closeTail を呼ばない", CONTROLLER,
     "                    recorder.closeTail();\n",
     "",
     "junit", TEST, "eventsAfterTheLastLogLineGoToTheFinalView"),

    # 軸5: 記録係を差し替えても溜めを空にしない(前の操作の出来事が混ざる)
    ("記録係の差し替えで溜めを空にしない", ROOM,
     "        this.stepRecorder = stepRecorder;\n        pendingEvents.clear();\n",
     "        this.stepRecorder = stepRecorder;\n",
     "junit", TEST, "swappingRecorderClearsTheBuffer"),

    # 軸6: 記録係が居なくても溜める(減らない溜めができる)
    ("記録係が居なくても溜める", ROOM,
     "        if (stepRecorder == null) {\n            return;\n        }\n        pendingEvents.add(event);\n",
     "        pendingEvents.add(event);\n",
     "junit", TEST, "nothingIsBufferedWithoutRecorder"),

    # 軸7: 差し込んだ時点で居なかった閲覧者にも最終状態の出来事を渡す(途中から始まる物語)
    ("知らない閲覧者にも最終状態の出来事を渡す", RECORDER,
     "        return tailByViewer.getOrDefault(viewerId, List.of());",
     "        return tailByViewer.getOrDefault(viewerId,\n"
     "                tailByViewer.values().stream().findFirst().orElse(List.of()));",
     "junit", TEST, "unknownViewerGetsNoTailEvents"),

    # ---- 視点(設計判断9・42・裁定373) ----

    # 軸8: ★★★DRAW の面を切り落とさない —— <b>相手の手札の中身が届く</b>
    ("DRAW の面を本人以外にも渡す", BUILDER,
     "            List<String> cardsForViewer = e.kind() == GameEvent.Kind.DRAW && !ownedByViewer\n",
     "            List<String> cardsForViewer = false\n",
     "junit", TEST, "drawFaceOnlyForOwner"),

    # 軸9: ★★★リーダーの参照にプレイヤーIDを書く —— <b>相手の宛先が漏れる</b>(設計判断42)
    ("リーダーの参照にプレイヤーIDを書く", BUILDER,
     "        return ref.isLeader() ? \"leader:\" + sideOf(ref.playerId(), youId) : ref.instanceId();",
     "        return ref.isLeader() ? \"leader:\" + ref.playerId() : ref.instanceId();",
     "junit", TEST, "noPlayerIdIsDelivered"),

    # 軸10: 観戦者の向きを決めない(観戦者から見ると誰も YOU にならない)
    ("観戦者の向きを A 席に合わせない", BUILDER,
     "        String youId = state.hasPlayer(viewerId) ? viewerId : state.getPlayer1().getPlayerId();",
     "        String youId = viewerId;",
     "junit", TEST, "spectatorSeesSeatAAsYou"),

    # 軸11: ★★段の出来事を閲覧者で分けない(全員に最初の閲覧者の向きで配る)
    ("段の出来事を閲覧者で分けない", RECORDER,
     "            eventsByViewer.put(viewerId, viewBuilder.buildEvents(room, viewerId, events));",
     "            eventsByViewer.put(viewerId, viewBuilder.buildEvents(room, viewerIds.get(0), events));",
     "junit", TEST, "attackOnLeaderIsNamedRelatively"),

    # ---- 取り付け点(入口ごと・76/77 の教訓) ----

    # 軸12: ATTACK(ミニオンの攻撃)
    ("ミニオンの攻撃で ATTACK を記録しない", SERVICE,
     "        room.recordEvent(GameEvent.attack(player, GameEvent.Ref.minion(player, attacker),\n",
     "        if (false) room.recordEvent(GameEvent.attack(player, GameEvent.Ref.minion(player, attacker),\n",
     "junit", TEST, "minionAttackCarriesWhoAndWhom"),

    # 軸13: ATTACK(リーダーの攻撃)
    ("リーダーの攻撃で ATTACK を記録しない", SERVICE,
     "        room.recordEvent(GameEvent.attack(player, GameEvent.Ref.leader(player),\n",
     "        if (false) room.recordEvent(GameEvent.attack(player, GameEvent.Ref.leader(player),\n",
     "junit", TEST, "leaderAttackUsesLeaderRef"),

    # 軸14: ★★★戦闘によるリーダーへのダメージ(attack の経路)——設計書 3-2 が数えていなかった地点
    ("ミニオンの攻撃によるリーダーへのダメージを記録しない", SERVICE,
     "                // ★★★Batch 85a(裁定376): leaderAttack と同じく、damageLeader を通らない地点である\n"
     "                room.recordEvent(",
     "                if (false) room.recordEvent(",
     "junit", TEST, "combatDamageToLeaderIsRecorded"),

    # 軸15: ★★戦闘によるリーダーへのダメージ(leaderAttack の経路)
    ("リーダーの攻撃によるリーダーへのダメージを記録しない", SERVICE,
     "                //   (リファレンス 6章の2)。★設計書 3-2 の表が数えていなかった地点である\n"
     "                room.recordEvent(",
     "                if (false) room.recordEvent(",
     "junit", TEST, "leaderAttackUsesLeaderRef"),

    # 軸16: DAMAGE(damageLeader)
    ("damageLeader で DAMAGE を記録しない", ACTIONS,
     "        room.recordEvent(GameEvent.damage(GameEvent.Ref.leader(player), amount, player.getLp()));",
     "",
     "junit", TEST, "effectDamageCarriesAmountAndRemainder"),

    # 軸17: DAMAGE(ミニオン・ダメージの適用の唯一の地点)
    ("ミニオンへのダメージを記録しない", ACTIONS,
     "        room.recordEvent(GameEvent.damage(GameEvent.Ref.minion(owner, minion), amount,\n"
     "                minion.getCurrentHp()));\n",
     "",
     "junit", TEST, "effectDamageCarriesAmountAndRemainder"),

    # 軸18: HEAL の量を「要求した量」にする(画面の数字が LP の動きと食い違う)
    ("回復の量を要求した量で運ぶ", ACTIONS,
     "        room.recordEvent(GameEvent.heal(GameEvent.Ref.leader(player), healed, player.getLp()));",
     "        room.recordEvent(GameEvent.heal(GameEvent.Ref.leader(player), amount, player.getLp()));",
     "junit", TEST, "healLeaderCarriesActualGain"),

    # 軸19: HEAL(ミニオン・エクスカリバー)
    ("ミニオンの回復を記録しない", SERVICE,
     "                            if (m.getCurrentHp() > before) {\n",
     "                            if (false) {\n",
     "junit", TEST, "minionHealIsRecorded"),

    # 軸20: DESTROY(すべての破壊の合流点)
    ("破壊を記録しない", ACTIONS,
     "        room.recordEvent(GameEvent.destroy(owner, minion));\n",
     "",
     "junit", TEST, "destroyBelongsToItsOwnLogLine"),

    # 軸21: ★★破壊された禁忌由来のミニオンに BANISH を重ねる(1体に2つの退場を語る・裁定372)
    ("破壊された禁忌ミニオンに BANISH も重ねる", ACTIONS,
     "        room.recordEvent(GameEvent.destroy(owner, minion));\n",
     "        room.recordEvent(GameEvent.destroy(owner, minion));\n"
     "        if (minion.isFromTaboo()) { room.recordEvent(GameEvent.banish(owner, minion)); }\n",
     "junit", TEST, "destroyedTabooMinionIsOnlyDestroy"),

    # 軸22: BANISH(手札へ戻されて消滅)
    ("手札へ戻されて消滅しても BANISH を記録しない", ACTIONS,
     "        dispatchUnderCards(room, owner, minion, UnderDestination.HAND);\n"
     "        if (minion.isFromTaboo()) {\n"
     "            owner.getLostZone().add(minion.getMaster().id());\n"
     "            room.recordEvent(",
     "        dispatchUnderCards(room, owner, minion, UnderDestination.HAND);\n"
     "        if (minion.isFromTaboo()) {\n"
     "            owner.getLostZone().add(minion.getMaster().id());\n"
     "            if (false) room.recordEvent(",
     "junit", TEST, "bounceOfTabooMinionIsBanish"),

    # 軸23: BANISH(マナへ置かれそうになって消滅)
    ("マナへ置かれそうになって消滅しても BANISH を記録しない", ACTIONS,
     "        dispatchUnderCards(room, owner, minion, UnderDestination.MANA_FACE_DOWN);\n"
     "        if (minion.isFromTaboo()) {\n"
     "            owner.getLostZone().add(minion.getMaster().id());\n"
     "            room.recordEvent(",
     "        dispatchUnderCards(room, owner, minion, UnderDestination.MANA_FACE_DOWN);\n"
     "        if (minion.isFromTaboo()) {\n"
     "            owner.getLostZone().add(minion.getMaster().id());\n"
     "            if (false) room.recordEvent(",
     "junit", TEST, "manaPutOfTabooMinionIsBanish"),

    # 軸24: SUMMON(召喚)
    ("召喚で SUMMON を記録しない", SERVICE,
     "        room.recordEvent(GameEvent.summon(player, minion));\n",
     "",
     "junit", TEST, "summonPointsAtTheNewMinion"),

    # 軸25: SUMMON(効果による「出す」)
    ("効果で場に出ても SUMMON を記録しない", ACTIONS,
     "        room.recordEvent(GameEvent.summon(owner, minion));\n",
     "",
     "junit", TEST, "effectEntryIsSummonToo"),

    # 軸26: CAST(手札のスペル)
    ("スペルの使用で CAST を記録しない", SERVICE,
     "        room.recordEvent(GameEvent.cast(player, master.id())); // ★Batch 85a: 使った瞬間に公開情報\n",
     "",
     "junit", TEST, "castIsPublic"),

    # 軸27: CAST(賢魂)
    ("賢魂の使用で CAST を記録しない", SERVICE,
     "        room.recordEvent(GameEvent.cast(player, master.id())); // ★Batch 85a: 賢魂はスペルの使用である(裁定247)\n",
     "",
     "junit", TEST, "soulCastIsCast"),

    # 軸28: CAST(禁忌のスペル)
    ("禁忌のスペルで CAST を記録しない", SERVICE,
     "        if (master.type() == CardType.SPELL) {\n            room.recordEvent(GameEvent.cast(player, master.id()));\n        }\n",
     "",
     "junit", TEST, "tabooSpellIsCast"),

    # 軸29: CAST(ピュア・エレメント)
    ("ピュア・エレメントで CAST を記録しない", SERVICE,
     "        room.recordEvent(GameEvent.cast(player, PURE_ELEMENT_ID)); // ★Batch 85a: これもスペルの使用である\n",
     "",
     "junit", TEST, "pureElementIsCast"),

    # 軸30: DRAW
    ("ドローを記録しない", ACTIONS,
     "            room.recordEvent(GameEvent.draw(player, cardId));\n",
     "",
     "junit", TEST, "drawFaceOnlyForOwner"),

    # 軸31: TURN
    ("ターンの開始を記録しない", SERVICE,
     "        room.recordEvent(GameEvent.turn(player, state.getTurnNumber())); // ★Batch 85a\n",
     "",
     "junit", TEST, "eventsAfterTheLastLogLineGoToTheFinalView"),

    # 軸32: GAME_OVER
    ("決着を記録しない", ACTIONS,
     "        room.recordEvent(GameEvent.gameOver(winner)); // ★Batch 85a: 決着の口は1本(裁定130)\n",
     "",
     "junit", TEST, "lethalAttackEndsWithGameOver"),

    # ---- 記録する「時点」(ログ行の前か後か) ----

    # 軸33: ★★★ATTACK をログ行の「後」に記録する —— <b>次の段へずれる</b>
    #   (「ログ行 X の前に起きた出来事は段 X に属する」の、いちばん起きやすい破り方)
    ("ATTACK をログ行の後に記録する", SERVICE,
     "        room.recordEvent(GameEvent.attack(player, GameEvent.Ref.minion(player, attacker),\n"
     "                targetIsLeader ? GameEvent.Ref.leader(opponent) : GameEvent.Ref.minion(opponent, target)));\n"
     "        room.addLog(\"【%s】が攻撃を宣言\".formatted(attacker.getMaster().name()));\n",
     "        room.addLog(\"【%s】が攻撃を宣言\".formatted(attacker.getMaster().name()));\n"
     "        room.recordEvent(GameEvent.attack(player, GameEvent.Ref.minion(player, attacker),\n"
     "                targetIsLeader ? GameEvent.Ref.leader(opponent) : GameEvent.Ref.minion(opponent, target)));\n",
     "junit", TEST, "minionAttackCarriesWhoAndWhom"),

    # 軸34: ★DESTROY をログ行の後に記録する(破壊の段のビューと、出来事の段がずれる)
    ("DESTROY をログ行の後に記録する", ACTIONS,
     "        room.recordEvent(GameEvent.destroy(owner, minion));\n"
     "        room.addLog(\"【%s】が破壊されました\".formatted(minion.getMaster().name()));\n",
     "        room.addLog(\"【%s】が破壊されました\".formatted(minion.getMaster().name()));\n"
     "        room.recordEvent(GameEvent.destroy(owner, minion));\n",
     "junit", TEST, "destroyBelongsToItsOwnLogLine"),

    # 軸35: SUMMON を場に出す前(実体を作る前)に記録する形は作れないので、
    #   ★代わりに「別のミニオンを指す」形で壊す —— dst が場に居ないものを指す
    ("SUMMON が別のミニオンを指す", SERVICE,
     "        room.recordEvent(GameEvent.summon(player, minion));\n",
     "        room.recordEvent(GameEvent.summon(player, actions.newFieldMinion(state, master, fromTaboo)));\n",
     "junit", TEST, "summonPointsAtTheNewMinion"),

    # ---- ★★★84a の軸の引き継ぎ(85a が形を変えたので、84a の表の文字列は本体にもう無い) ----

    # 軸36(84a 軸3): 記録係を外す finally を消す
    ("[84a-3] 記録係を外す finally を消す", CONTROLLER,
     "                try {\n                    action.apply(room);\n"
     "                } finally {\n"
     "                    // ★★Batch 85a: 最後のログ行の後に起きた出来事を締める(裁定374)。\n"
     "                    //   ★外すと部屋が出来事の溜めを空にするので、<b>外す前</b>でなければならない\n"
     "                    recorder.closeTail();\n"
     "                    room.setStepRecorder(null);\n                }\n",
     "                action.apply(room);\n",
     "junit", TEST84A, "recorderIsAlwaysRemoved"),

    # 軸37(84a 軸6): 段の並びを逆にする
    ("[84a-6] 段の並びを逆にする", RECORDER,
     "        frames.add(new StepFrame(logLine, byViewer, eventsByViewer));",
     "        frames.add(0, new StepFrame(logLine, byViewer, eventsByViewer));",
     "junit", TEST84A, "stepOrderEqualsLogOrder"),

    # 軸38(84a 軸7): 通し番号を0から振る
    ("[84a-7] 通し番号を0から振る", RECORDER,
     "            steps.add(new GameStep(i + 1, frames.get(i).logLine(), view,",
     "            steps.add(new GameStep(i, frames.get(i).logLine(), view,",
     "junit", TEST84A, "seqIsDense"),
]
