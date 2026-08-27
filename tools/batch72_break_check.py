#!/usr/bin/env python3
"""Batch 72(試合の出入り = 席・退室・投了・再戦)の壊し検証(裁定116)。

実装をわざと壊し、狙った番人が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った番人が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(番人が足りない)
  EMPTY    … その番人が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★★★<b>照合先は2つに割れている。</b>71 は verify だけだったが、
  72 は <b>Java と JavaScript の両方</b>を変えている。
  ★<b>回る場所を選ぶ前に、そこまで届くかを確かめた</b>(70 の教訓):
    - 席の移動・退室・投了・再戦の<b>サーバ側の状態</b> …… JUnit にしか照合先が無い
      (verify のハーネスは Java を起こさない。GameRoom を壊しても<b>あちらには届かない</b>)
    - ボタンの出し分け・重なり・確認モーダル・遷移 …… verify にしか照合先が無い
      (MockMvc が測れるのは「テンプレートに箱が在るか」= <b>宣言</b>だけである)
  ★だから {@code BattlePageTest} の 72 の項目は<b>軸にしていない</b> ——
    壊しても「宣言が消えた」しか言えず、番人の強さの証明にならない(設計判断46)。

★★改変は「軸」ごとに1件ずつ当てる(57 の教訓)。
★★★<b>出口が複数ある関数は、出口ごとに当てる</b>(71 の教訓)——
  {@code GameActions.finish} には後始末が2かたまりあり(問い合わせの待ち行列 /
  保留していた戦闘とターンの受け渡し)、軸11・12 に分けてある。
  {@code renderRoomControls} も「席」と「退室」で軸を分けた。

★★★<b>想定内の NG は1件も置いていない。</b>72 の番人はどれも実装から期待値を読まない ——
  測っているのは「id がいくつの立場に居るか」「断られたか」「送ったか」「矩形が重なるか」
  「遷移したか」という<b>読める事実</b>だけである(裁定298 の回避)。

★★★<b>壊しどころが無い項目</b>(意図的に含めていないもの・裁定196 の正直な扱い):

  - <b>ヘッダのボタンがヘッダ行に収まること</b> ……
    ボタンは自前の CSS を1つも持たない(Bootstrap の {@code .btn-sm} だけである)。
    <b>壊す対象の宣言が存在しない</b> —— 71 の帯は {@code .auto-conn-bar} を持っていたので
    位置指定を壊せたが、こちらは「ヘッダの子として並べた」以外に何も書いていない。
    ★verify 72-1 は毎回矩形で測っているので、将来 CSS を足した日には軸にできる。

  - <b>無人になった部屋の掃除</b> ……
    通常モードには {@code ManualCleanupScheduler} にあたるものが無い(66 の積み残し)。
    72 は退室を作ったが掃除は作っていない —— <b>無いものは壊せない。</b>
    ★これは「まずは作らない」ではなく、<b>別の工事である</b>という事実である。

  - <b>再戦の申し込みが切断中に消えないこと</b> ……
    申し込みの旗は<b>サーバが持っている</b>ので、そもそもローカルに畳むものが無い。
    ★71 の設計判断49 が守っているのは<b>ローカルにしか無い状態</b>であり、
    ここには当てはまらない。

  - <b>退室したあとの画面</b> ……
    {@code location.href} で本物のロビーへ出るところまでは verify 72-16 が測るが、
    そこから先(ロビーが正しく描かれるか)は 66 の担当である。

  - <b>確認モーダルのフォーカストラップ</b> ……
    通常モードには {@code modalStack} も焦点の閉じ込めも<b>元から無い</b>
    (#info-modal も #sound-modal も持っていない)。72 は初期フォーカス(裁定52)だけを
    守っており、Tab の折り返し(裁定50)は<b>作っていない</b> ——
    ★作っていないものは壊せない。**片肺として書き残す**(設計解説 9-2)。

使い方: python3 tools/batch72_break_check.py [ケース番号...]
★★<b>長い。前後で git diff を見ること</b>(70 の教訓: 殺されると壊したまま残る)。
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
M2 = "/root/m2work/repository"

CSS = "src/main/resources/static/css/battle.css"
BATTLE_JS = "src/main/resources/static/js/battle.js"
GAME_ROOM = "src/main/java/com/example/qte/room/GameRoom.java"
GAME_SERVICE = "src/main/java/com/example/qte/game/GameService.java"
GAME_ACTIONS = "src/main/java/com/example/qte/game/GameActions.java"
VIEW_BUILDER = "src/main/java/com/example/qte/game/view/GameViewBuilder.java"

SEAT_TEST = "Batch72SeatTest"

# 壊しても落ちないことが分かっているもの(理由つき)。★72 は1件も無い。
EXPECTED_NG = {}

# (説明, ファイル, 置換前, 置換後, 種別, クラス or "", 照合先の名前の一部)
CASES = [
    # ===============================================================
    # 軸1: ★★★席を立ったら席から除くこと
    #   ★除き忘れると、同じ id が席と観戦者の両方に居る ——
    #     GameBroadcaster が<b>同じ宛先へ2回配信する</b>。画面からは決して見えない
    # ===============================================================
    ("席を立っても席から除かない(同じ id が2つの立場に居る)", GAME_ROOM,
     "        seats.remove(slot.getSeat());\n"
     "        Spectator spectator = new Spectator(slot.getPlayerId(), slot.getDisplayName());",
     "        Spectator spectator = new Spectator(slot.getPlayerId(), slot.getDisplayName());",
     "junit", SEAT_TEST, "同じidが席と観戦者の両方に現れることはない"),

    # ===============================================================
    # 軸2: ★★★席を移っても id を変えないこと
    #   ★id は配信の宛先であり localStorage の値でもある。変えると
    #     購読を張り直すまでビューが1通も届かず、書き換えに失敗した端末は二度と戻れない
    # ===============================================================
    ("席を立つときに新しい id を発行する(宛先と localStorage が食い違う)", GAME_ROOM,
     "        Spectator spectator = new Spectator(slot.getPlayerId(), slot.getDisplayName());",
     "        Spectator spectator = new Spectator(UUID.randomUUID().toString(), slot.getDisplayName());",
     "junit", SEAT_TEST, "席を立つと観戦者になる_idは変わらない"),

    # ===============================================================
    # 軸3: ★★席に着いたら観戦者から除くこと(軸1 の逆向き)
    # ===============================================================
    ("席に着いても観戦者から除かない(軸1 の逆向き)", GAME_ROOM,
     "        spectators.remove(spectator);\n"
     "        PlayerSlot slot = new PlayerSlot(spectator.spectatorId(), spectator.displayName(), seat);",
     "        PlayerSlot slot = new PlayerSlot(spectator.spectatorId(), spectator.displayName(), seat);",
     "junit", SEAT_TEST, "同じidが席と観戦者の両方に現れることはない"),

    # ===============================================================
    # 軸4: ★★★対戦が始まったら席を立てないこと
    #   ★66 が「席を立てない」と書いた理由そのものである ——
    #     席は GameState の2人と1対1であり、動かすと盤面の持ち主が消える
    # ===============================================================
    ("対戦中でも席を立てるようにする(盤面の持ち主が消える)", GAME_ROOM,
     "        if (gameState != null) {\n"
     "            throw new IllegalArgumentException(\"対戦が始まったあとは席を立てません\");",
     "        if (false) {\n"
     "            throw new IllegalArgumentException(\"対戦が始まったあとは席を立てません\");",
     "junit", SEAT_TEST, "対戦が始まったら席を立てないし着けない"),

    # ===============================================================
    # 軸5: ★★★盤面が在る間は席に着けないこと(軸4 の逆向きの入口)
    #
    #   ★★★<b>この軸は最初 NG だった。</b>2つの誤りが重なっていた ——
    #     (1) 改変が当たっていなかった(ガードを外さず、無意味な行を足していただけ。形1)
    #     (2) 直したあとも、狙った判定に<b>番人が届かなかった</b> ——
    #         両席が埋まった盤面では「席が埋まっている」の判定が先に弾くので、
    #         盤面の有無を見る判定を消しても<b>誰も赤くしない</b>(65 の形)。
    #   ★<b>「決着後に退室して空いた席」という盤面を作って初めて、この判定に当たる。</b>
    #     ★★その盤面は 72 が作れるようにしたものである(決着後の退室)——
    #       <b>新しく作れるようにした状態には、新しく番人が要る。</b>
    # ===============================================================
    ("盤面が在っても席に着けるようにする(席と盤面の持ち主が食い違う)", GAME_ROOM,
     "        if (gameState != null) {\n"
     "            throw new IllegalArgumentException(\"この部屋は既に対戦が始まっています\");\n"
     "        }\n"
     "        Spectator spectator = findSpectator(spectatorId).orElseThrow(",
     "        if (false) {\n"
     "            throw new IllegalArgumentException(\"この部屋は既に対戦が始まっています\");\n"
     "        }\n"
     "        Spectator spectator = findSpectator(spectatorId).orElseThrow(",
     "junit", SEAT_TEST, "決着後に空いた席にはまだ座れない"),

    # ===============================================================
    # 軸6: ★★★観戦できない部屋では席を立てないこと(マスター確認)
    #   ★手動モードは退室に読み替えるが、こちらは断る ——
    #     1つのボタンが部屋の設定しだいで別のことをするのは、
    #     「押すつもりが無かった」を作る形である(設計判断47 の筋)
    # ===============================================================
    ("観戦できない部屋でも席を立てるようにする(降りる先が無いのに降りる)", GAME_ROOM,
     "        if (!options.spectatorAllowed()) {\n"
     "            throw new IllegalArgumentException(\n"
     "                    \"この部屋は観戦できないため、席を立てません(退室してください)\");",
     "        if (false) {\n"
     "            throw new IllegalArgumentException(\n"
     "                    \"この部屋は観戦できないため、席を立てません(退室してください)\");",
     "junit", SEAT_TEST, "観戦できない部屋では席を立てない"),

    # ===============================================================
    # 軸7: ★★観戦にも名前が要ること(72 で変えた)
    #   ★66 の姿(空欄なら「観戦者」)に戻す。名前を持たないまま席に着けると、
    #     相手には<b>「観戦者」という名前の対戦相手</b>が現れる
    # ===============================================================
    ("観戦の名前を 66 の姿(空欄なら「観戦者」)へ戻す", GAME_ROOM,
     "        String name = displayName == null ? \"\" : displayName.trim();\n"
     "        if (name.isEmpty()) {\n"
     "            throw new IllegalArgumentException(\"名前を入力してください\");\n"
     "        }\n"
     "        Spectator spectator = new Spectator(UUID.randomUUID().toString(), name);",
     "        String name = displayName == null || displayName.isBlank()\n"
     "                ? \"観戦者\" : displayName.trim();\n"
     "        Spectator spectator = new Spectator(UUID.randomUUID().toString(), name);",
     "junit", SEAT_TEST, "観戦にも名前が要る"),

    # ===============================================================
    # 軸8: ★★★対戦中の着席者は退室できないこと
    #   ★黙って抜けられると、相手には「相手が動かなくなった」としか見えない ——
    #     71 が潰した「気づきにくい事故」と同じ形である
    # ===============================================================
    ("対戦中でも着席者が退室できるようにする(相手には理由が分からない)", GAME_ROOM,
     "        if (slot != null && gameState != null\n"
     "                && gameState.getStatus() != GameStatus.FINISHED) {",
     "        if (false) {",
     "junit", SEAT_TEST, "対戦中の着席者は退室できない_観戦者はできる"),

    # ===============================================================
    # 軸9: ★★退室したら再戦の申し込みも倒れること
    #   ★倒さないと、<b>誰も答えられない問い</b>が残り続ける
    # ===============================================================
    ("退室しても再戦の申し込みを倒さない(答える人が居ない問いが残る)", GAME_ROOM,
     "        if (occupantId.equals(rematchOfferedBy)) {\n"
     "            rematchOfferedBy = null;\n"
     "        }",
     "        // わざと旗を残す",
     "junit", SEAT_TEST, "退室すると再戦の申し込みも倒れる"),

    # ===============================================================
    # 軸10: ★★★投了は手番を要求しないこと(マスター確認)
    #   ★★<b>詰まったときの逃げ道は、詰まりの原因になっている規則に左右されてはいけない。</b>
    #     requireTurnPlayer は「相手が選択中」でも弾くので、
    #     ここに置くと<b>割り込みで固まったときに投了もできない</b>
    # ===============================================================
    ("投了に手番の検証を足す(手動モードの「リセットは止めない」を破る)", GAME_SERVICE,
     "        PlayerState me = state.playerOf(playerId);\n"
     "        room.addLog(\"%s が投了しました\".formatted(me.getDisplayName()));",
     "        requireTurnPlayer(state, playerId);\n"
     "        PlayerState me = state.playerOf(playerId);\n"
     "        room.addLog(\"%s が投了しました\".formatted(me.getDisplayName()));",
     "junit", SEAT_TEST, "投了は相手のターン中でも押せる"),

    # ===============================================================
    # 軸11: ★★★決着したら問い合わせの待ち行列をたたむこと
    #   ★72 が投了を「いつでも押せる」ものにしたので、
    #     <b>割り込み待ちのまま決着する盤面が初めて作れるようになった</b>
    # ===============================================================
    ("決着しても問い合わせの待ちを残す(決着後に問いと [確定] が出続ける)", GAME_ACTIONS,
     "        state.getPlayer1().clearPendingChoices();\n"
     "        state.getPlayer2().clearPendingChoices();",
     "        // わざと待ちを残す",
     "junit", SEAT_TEST, "投了は割り込み待ちでも押せて_待ちはたたまれる"),

    # ===============================================================
    # 軸12: ★★★同じ関数の<b>もう一方の後始末</b>(出口ごとに当てる・71 の教訓)
    # ===============================================================
    ("決着しても保留していた戦闘とターンの受け渡しを残す", GAME_ACTIONS,
     "        state.setPendingAttack(null);\n"
     "        state.setTurnHandoffPending(false);\n"
     "        state.setPendingNextPlayerId(null);\n"
     "        state.setResolvingCardId(null);",
     "        // わざと保留を残す",
     "junit", SEAT_TEST, "決着で保留していた戦闘とターンの受け渡しもたたまれる"),

    # ===============================================================
    # 軸13: ★★投了したら<b>相手</b>が勝つこと
    # ===============================================================
    ("投了した本人を勝たせる", GAME_SERVICE,
     "        actions.finish(room, state.opponentOf(playerId));",
     "        actions.finish(room, state.playerOf(playerId));",
     "junit", SEAT_TEST, "投了すると相手の勝ちになる"),

    # ===============================================================
    # 軸14: ★★観戦者は投了できないこと
    #   ★観戦者は playerId を持たないが、<b>画面を書き換えれば送れる</b> ——
    #     断るのはサーバである(設計判断27)
    # ===============================================================
    ("観戦者かどうかを見ずに投了を受け付ける", GAME_SERVICE,
     "        if (!state.hasPlayer(playerId)) {\n"
     "            throw new IllegalArgumentException(\"この対戦のプレイヤーではありません\");",
     "        if (false) {\n"
     "            throw new IllegalArgumentException(\"この対戦のプレイヤーではありません\");",
     "junit", SEAT_TEST, "観戦者は投了できない"),

    # ===============================================================
    # 軸15: ★★★再戦に応じたらデッキが外れること(マスター指定)
    #   ★外さないと bothReady がその場で真になり、
    #     <b>[応じる] を押した瞬間に次の試合が始まる</b>
    # ===============================================================
    ("再戦でデッキを外さない(押した瞬間に次の試合が始まる)", GAME_ROOM,
     "        for (PlayerSlot slot : getSlots()) {\n"
     "            slot.clearDeck();\n"
     "        }",
     "        // わざとデッキを残す",
     "junit", SEAT_TEST, "再戦は申し込みと承諾の2段である"),

    # ===============================================================
    # 軸16: ★★再戦で ready を倒さないこと
    #   ★ready は「WebSocket の購読が済んでいるか」という事実であり、
    #     再戦で購読が切れるわけではない。倒すと<b>意味を取り違えた旗</b>になる
    # ===============================================================
    ("再戦で ready まで倒す(購読の旗を再戦の旗と取り違える)", GAME_ROOM,
     "        for (PlayerSlot slot : getSlots()) {\n"
     "            slot.clearDeck();\n"
     "        }",
     "        for (PlayerSlot slot : getSlots()) {\n"
     "            slot.clearDeck();\n"
     "            slot.setReady(false);\n"
     "        }",
     "junit", SEAT_TEST, "再戦は申し込みと承諾の2段である"),

    # ===============================================================
    # 軸17: ★★再戦でログを消すこと
    # ===============================================================
    ("再戦でログを消さない(前の試合の行が新しい試合に混ざる)", GAME_ROOM,
     "        log.clear();",
     "        // わざとログを残す",
     "junit", SEAT_TEST, "再戦に応じるとログも消える"),

    # ===============================================================
    # 軸18: ★★★自分の申し込みには自分で答えられないこと
    #   ★答えられると、2段(申し込み → 承諾)にした意味が消える
    # ===============================================================
    ("自分の申し込みに自分で答えられるようにする(2段の意味が消える)", GAME_SERVICE,
     "        if (offeredBy.equals(playerId)) {\n"
     "            throw new IllegalStateException(\"自分の申し込みには答えられません\");",
     "        if (false) {\n"
     "            throw new IllegalStateException(\"自分の申し込みには答えられません\");",
     "junit", SEAT_TEST, "自分の申し込みには自分で答えられない"),

    # ===============================================================
    # 軸19: ★★断るのは旗を倒すだけであること
    #   ★★<b>断ったのに盤面が消える</b>は、いちばん取り返しがつかない誤りである
    # ===============================================================
    ("再戦を断ったのに盤面を捨てる", GAME_SERVICE,
     "            case DECLINE -> {\n"
     "                requireOfferFromOpponent(room, playerId);\n"
     "                room.setRematchOfferedBy(null);",
     "            case DECLINE -> {\n"
     "                requireOfferFromOpponent(room, playerId);\n"
     "                room.resetForRematch();",
     "junit", SEAT_TEST, "再戦を断ると旗だけが倒れて盤面は残る"),

    # ===============================================================
    # 軸20: ★★申し込みは「席」で配ること(viewer 目線に加工しない)
    #   ★加工すると、観戦者ぶんの意味をもう1つ決めることになる(設計判断9)
    # ===============================================================
    ("再戦の申し込みをビューに載せない", VIEW_BUILDER,
     "                offerer == null ? null : offerer.getSeat().name(),",
     "                null,",
     "junit", SEAT_TEST, "再戦の申し込みは席で配られる_観戦者にも同じ値が届く"),

    # ===============================================================
    # 軸21: ★★★盤面が載ったら status は WAITING でないこと
    #   ★★<b>battle.js の renderRoomControls がこの一致に乗っている。</b>
    #     WAITING を「盤面がまだ無い」と読んで席のボタンを出し分けているので、
    #     WAITING の盤面が配信されると<b>対戦中に席を動かせる</b>
    # ===============================================================
    ("試合を生成しても status を WAITING のままにする", GAME_SERVICE,
     "        state.setStatus(GameStatus.SETUP);\n"
     "        room.setGameState(state);",
     "        room.setGameState(state);",
     "junit", SEAT_TEST, "盤面が在るあいだビューのstatusはWAITINGにならない"),

    # ===============================================================
    # ここから JavaScript(照合先は verify)
    # ===============================================================

    # ===============================================================
    # 軸22: ★★★盤面が在る間は席のボタンを出さないこと(画面側の出し分け)
    # ===============================================================
    ("対戦中でも [席を立つ] を出す(操作補助が嘘をつく)", BATTLE_JS,
     "    if (!board && seated && room.spectatorAllowed) {",
     "    if (seated && room.spectatorAllowed) {",
     "verify", "", "対戦中の着席者は席を立てず"),

    # ===============================================================
    # 軸23: ★★同じ関数の<b>もう一方の出し分け</b>(出口ごとに当てる・71 の教訓)
    # ===============================================================
    ("対戦中の着席者にも [退室] を出す", BATTLE_JS,
     "    leaveBtn.classList.toggle('d-none', seated && board && !finished);",
     "    leaveBtn.classList.toggle('d-none', false);",
     "verify", "", "対戦中の着席者は席を立てず"),

    # ===============================================================
    # 軸24: ★★★投了は確認を通すこと(裁定53)
    # ===============================================================
    ("投了から確認を外す(1回のクリックで対戦が終わる)", BATTLE_JS,
     "    askConfirm('投了する。この対戦は相手の勝ちになり、やり直せない。',\n"
     "        '投了する', () => send('concede', {}));",
     "    send('concede', {});",
     "verify", "", "投了は確認を通さずには飛ばない"),

    # ===============================================================
    # 軸25: ★★初期フォーカスは [キャンセル] であること(裁定52)
    #   ★破壊的操作の [実行] に初期フォーカスを載せると、Enter で通ってしまう
    # ===============================================================
    ("確認の初期フォーカスを [実行] に載せる(Enter で通る)", BATTLE_JS,
     "    document.getElementById('auto-confirm-close').focus();",
     "    document.getElementById('auto-confirm-ok').focus();",
     "verify", "", "確認の初期フォーカスは [キャンセル] である"),

    # ===============================================================
    # 軸26: ★★取り消した確認は何も実行しないこと
    # ===============================================================
    ("[キャンセル] でも実行してしまう", BATTLE_JS,
     "document.getElementById('auto-confirm-close').addEventListener('click', closeAutoConfirm);",
     "document.getElementById('auto-confirm-close').addEventListener('click', () => {\n"
     "    const r = autoConfirmPending; closeAutoConfirm(); if (r) r();\n"
     "});",
     "verify", "", "取り消した確認は何も送らない"),

    # ===============================================================
    # 軸27: ★★★確認モーダルは切断オーバーレイより<b>下</b>であること(裁定56)
    #   ★上に出すと「実行しても何も起きない問い」を最前面に置くことになる
    #   ★★この規則は両モードで共有している(71 の「規則は1つ、名前は2つ」)ので、
    #     壊すと手動モードの重ね順も動く。<b>照合先は 72 の項目名だけを見る。</b>
    # ===============================================================
    ("確認モーダルを切断オーバーレイより上に出す", CSS,
     ".info-modal.manual-confirm,\n.info-modal.auto-confirm { z-index: 1965; }",
     ".info-modal.manual-confirm,\n.info-modal.auto-confirm { z-index: 1990; }",
     "verify", "", "切断オーバーレイは確認モーダルより手前に出る"),

    # ===============================================================
    # 軸28: ★★Esc で確認が閉じ、下の層へ落ちないこと(裁定49)
    # ===============================================================
    ("Esc が確認モーダルを飛ばして下の層へ落ちる", BATTLE_JS,
     "    if (isAutoConfirmOpen()) {\n"
     "        e.preventDefault();\n"
     "        closeAutoConfirm();\n"
     "        return;\n"
     "    }",
     "    if (false) {\n"
     "        e.preventDefault();\n"
     "        closeAutoConfirm();\n"
     "        return;\n"
     "    }",
     "verify", "", "Esc は確認モーダルだけを閉じ"),

    # ===============================================================
    # 軸29: ★★★決着の面は盤面を覆わないこと
    #   ★★<b>オーバーレイにしなかった判断そのものである。</b>
    #     終わった盤面はまだ読まれている(手動モードの裁定44)
    # ===============================================================
    ("決着の面をオーバーレイにする(終わった盤面が読めなくなる)", BATTLE_JS,
     "    el.classList.toggle('d-none', !on);\n"
     "    if (!on) return;\n"
     "\n"
     "    const room = view.room;",
     "    el.classList.toggle('d-none', !on);\n"
     "    if (on) el.style.cssText = 'position:fixed;inset:0;z-index:1980;';\n"
     "    if (!on) return;\n"
     "\n"
     "    const room = view.room;",
     "verify", "", "決着しても盤面は読める"),

    # ===============================================================
    # 軸30: ★★★自分の申し込みには答える導線を出さないこと(画面側)
    #   ★サーバも断るが(軸18)、<b>画面に出ていれば人は押す</b>
    # ===============================================================
    ("自分の申し込みにも [応じる] を出す", BATTLE_JS,
     "    if (offerSeat === room.viewerSeat) {\n"
     "        note.textContent = '再戦を申し込みました。相手の返事を待っています。';\n"
     "        return;\n"
     "    }",
     "    if (false) {\n"
     "        note.textContent = '再戦を申し込みました。相手の返事を待っています。';\n"
     "        return;\n"
     "    }",
     "verify", "", "自分の申し込みには自分で答えられない"),

    # ===============================================================
    # 軸31: ★★[応じる] は確認を通すこと(相手の盤面まで消える)
    # ===============================================================
    ("[応じる] から確認を外す(相手の盤面が1クリックで消える)", BATTLE_JS,
     "    askConfirm('再戦に応じる。この対戦の盤面とログは消え、両者がデッキを読み込み直す。',\n"
     "        '再戦する', () => send('rematch', { action: 'ACCEPT' }));",
     "    send('rematch', { action: 'ACCEPT' });",
     "verify", "", "[応じる] は確認を通すまで飛ばない"),

    # ===============================================================
    # 軸32: ★★★席替えのゲートは入室後なので、切断の案内を出すこと
    #   ★★71 の判断(入室前は出さない / 入室後は出す)の延長である。
    #     判定を「要素が出ているか」だけにすると、ここで<b>黙って案内が消える</b>
    # ===============================================================
    ("isGateVisible からモードの判定を落とす(席替え中に切断の案内が消える)", BATTLE_JS,
     "    return seatGateMode === 'JOIN' && !gateEl('seat-gate').classList.contains('d-none');",
     "    return !gateEl('seat-gate').classList.contains('d-none');",
     "verify", "", "席替えのゲートは入室後なので、切断の案内を出す"),

    # ===============================================================
    # 軸33: ★★★送れなかったらゲートを開き直すこと(設計判断49 の新しい使い手)
    # ===============================================================
    ("席替えで送れなくてもゲートを畳んだままにする(押したのに何も起きない)", BATTLE_JS,
     "        if (!send('seat', { seat })) {\n"
     "            openSeatChangeGate();\n"
     "            showGateError('サーバとの接続が切れています。復帰してからもう一度お試しください。');\n"
     "        }",
     "        send('seat', { seat });",
     "verify", "", "切断中に席を選んでもゲートは畳まれず"),

    # ===============================================================
    # 軸34: ★★★席替えは WebSocket を通ること(受付 API を叩かない)
    #   ★入室前と同じ経路を叩くと、<b>同じ人の席が2つ生える</b>
    # ===============================================================
    ("席替えでも入室前と同じ受付 API を叩く(席が2つ生える)", BATTLE_JS,
     "    if (seatGateMode === 'CHANGE') {\n"
     "        closeSeatChangeGate();",
     "    if (false) {\n"
     "        closeSeatChangeGate();",
     "verify", "", "席替えは WebSocket の seat で飛び"),

    # ===============================================================
    # 軸35: ★★★退室は LEFT を受けてから動くこと
    # ===============================================================
    ("LEFT を受け取らない(受理されても盤面ページに留まる)", BATTLE_JS,
     "    if (message.type === 'LEFT') {\n"
     "        forgetOccupant();\n"
     "        location.href = '/auto';\n"
     "        return;\n"
     "    }",
     "    if (false) {\n"
     "        forgetOccupant();\n"
     "        location.href = '/auto';\n"
     "        return;\n"
     "    }",
     "verify", "", "受理された退室では記録を消してロビーへ戻る"),

    # ===============================================================
    # 軸36: ★★★断られた退室では遷移しないこと(手動モードの形を写した場合)
    #   ★★これが<b>写せなかった</b>ところである。手動モードは送って即遷移するが、
    #     あちらの退室は失敗しない。こちらで同じことをすると、
    #     断られた端末が<b>席を持ったまま戻れなくなる</b>
    # ===============================================================
    ("手動モードの形を写す(送った時点で記録を消して遷移する)", BATTLE_JS,
     "function leaveRoom() {\n"
     "    askConfirm('この部屋から退室する。席は空き、盤面はこの端末から見えなくなる。',\n"
     "        '退室する', () => send('leave', {}));\n"
     "}",
     "function leaveRoom() {\n"
     "    askConfirm('この部屋から退室する。席は空き、盤面はこの端末から見えなくなる。',\n"
     "        '退室する', () => { send('leave', {}); forgetOccupant(); location.href = '/auto'; });\n"
     "}",
     "verify", "", "退室は送っただけでは動かない"),

    # ===============================================================
    # 軸37: ★★★試合の出入りも send() のガードを通ること
    #   ★★<b>ガードの外に道を作らない。</b>71 の番人は send() であり、
    #     publish を直呼びする経路を1つでも足すと、そこだけ切断中に飛ぶ ——
    #     しかも例外もログも出ないので、人には「届いたつもり」しか残らない
    # ===============================================================
    ("投了が send() を迂回して publish を直呼びする(ガードの外に道を作る)", BATTLE_JS,
     "    askConfirm('投了する。この対戦は相手の勝ちになり、やり直せない。',\n"
     "        '投了する', () => send('concede', {}));",
     "    askConfirm('投了する。この対戦は相手の勝ちになり、やり直せない。',\n"
     "        '投了する', () => client.publish({\n"
     "            destination: `/app/room/${ROOM_ID}/concede`,\n"
     "            body: JSON.stringify({ playerId: PLAYER_ID }),\n"
     "        }));",
     "verify", "", "切断中は投了も退室も飛ばない"),
]


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as handle:
        return handle.read()


def write(path, text):
    with open(os.path.join(ROOT, path), "w", encoding="utf-8") as handle:
        handle.write(text)
    # ★★★62 の教訓: 書き戻せたことを、書き戻した本人が確かめる。
    if read(path) != text:
        raise RuntimeError("★★書き戻せていない: %s" % path)


def env():
    e = dict(os.environ)
    e.setdefault("NODE_PATH", "/home/claude/.npm-global/lib/node_modules")
    e.setdefault("PLAYWRIGHT_BROWSERS_PATH", "/opt/pw-browsers")
    return e


# ---- verify(実測) ----

def run_verify():
    build = subprocess.run([sys.executable, "verify/build_harness.py"],
                           cwd=ROOT, capture_output=True, text=True, env=env())
    if build.returncode != 0:
        return None
    done = subprocess.run(["node", "verify/verify.js"],
                          cwd=ROOT, capture_output=True, text=True, env=env())
    return done.stdout


def verify_verdict(out, fragment):
    if out is None:
        return "EMPTY"
    hits = [line for line in out.splitlines()
            if fragment in line and (line.startswith("PASS") or line.startswith("FAIL"))]
    if not hits:
        return "EMPTY"
    return "OK" if any(line.startswith("FAIL") for line in hits) else "NG"


# ---- JUnit(サーバの状態を読む番人) ----

def run_junit(test_class):
    """1クラスだけ回す。★surefire:test ではなく test を使う(裁定208: あちらはコンパイルしない)"""
    report = os.path.join(ROOT, "target/surefire-reports",
                          "TEST-com.example.qte.%s.xml" % test_class)
    if os.path.exists(report):
        os.remove(report)
    subprocess.run(["mvn", "-o", "-B", "-q",
                    "-Dmaven.repo.local=%s" % M2,
                    "-Dtest=%s" % test_class, "-DfailIfNoTests=false", "test"],
                   cwd=ROOT, capture_output=True, text=True, env=env())
    return report if os.path.exists(report) else None


def junit_verdict(report, fragment):
    """★試験1件ずつの結果を XML から読む。

    ★<b>「ビルドが失敗した」を OK と数えない。</b>報告書そのものが生まれなければ EMPTY である
    (裁定304: Java では条件を落とすとコンパイルが通らないことがある)。
    """
    if report is None:
        return "EMPTY"
    root = ET.parse(report).getroot()
    hits = [tc for tc in root.iter("testcase") if fragment in (tc.get("name") or "")]
    if not hits:
        return "EMPTY"
    broke = any(tc.find("failure") is not None or tc.find("error") is not None
                for tc in hits)
    return "OK" if broke else "NG"


def main():
    picked = [int(a) for a in sys.argv[1:]] or list(range(1, len(CASES) + 1))
    # ★★★<b>殺されても書き戻す</b>(70 の教訓)。70 の着手時、上限時間でスクリプトごと
    #   落とされ、<b>壊した1件がそのまま残った</b>(次の走行はそれを「開始時の姿」と
    #   思い込むので、誰も赤くしない)。finally は SIGTERM では走らないため、
    #   合図を捕まえて例外に変える —— そうすれば finally が走る。
    import signal

    def _raise(signum, frame):
        raise KeyboardInterrupt("signal %d" % signum)

    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, _raise)

    # ★★★開始時の姿を控えておく(62 の教訓)。壊したまま終わったら、
    #   OK が何件出ていようとこのスクリプトは失敗である。
    targets = sorted({case[1] for case in CASES})
    baseline = {path: read(path) for path in targets}
    results = []
    for number, (label, path, before, after, kind, cls, target) in enumerate(CASES, 1):
        if number not in picked:
            continue
        original = read(path)
        hits = original.count(before)
        if hits != 1:
            results.append((number, label, "SETUP-NG"))
            print("%2d SETUP-NG %s (一致 %d 箇所)" % (number, label, hits))
            continue
        write(path, original.replace(before, after))
        try:
            if kind == "junit":
                answer = junit_verdict(run_junit(cls), target)
            else:
                answer = verify_verdict(run_verify(), target)
        except KeyboardInterrupt:
            write(path, original)
            print("\n★中断された。%s は書き戻した。" % path)
            raise
        finally:
            write(path, original)
        if answer == "NG" and label in EXPECTED_NG:
            answer = "NG(想定内)"
        results.append((number, label, answer))
        print("%2d %-10s %s  →  %s" % (number, answer, label, target))
        if answer == "NG(想定内)":
            print("      理由: %s" % EXPECTED_NG[label])

    counts = {}
    for _, _, answer in results:
        counts[answer] = counts.get(answer, 0) + 1
    print("\n" + " / ".join("%s %d" % (k, counts[k]) for k in sorted(counts)))

    dirty = [path for path in targets if read(path) != baseline[path]]
    if dirty:
        print("\n★★★実装が壊れたまま残っている(手で戻すこと):")
        for path in dirty:
            print("    " + path)
        return 1
    print("★実装は開始時の姿に戻っている。")

    # ★最後にハーネスを正しい状態へ戻す(壊した状態のまま残さない)
    run_verify()
    good = counts.get("OK", 0) + counts.get("NG(想定内)", 0)
    return 0 if good == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
