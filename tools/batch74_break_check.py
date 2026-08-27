#!/usr/bin/env python3
"""Batch 74(73 の判断待ち13件 + 進化ミニオンの一族13枚)の壊し検証(裁定116)。

実装をわざと壊し、狙った番人が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った番人が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(番人が足りない)
  EMPTY    … その番人が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★★★<b>照合先は2つある</b>(設計判断45・72 と同じ「両方あるバッチ」)。
  74 は <b>Java も JavaScript も変えている</b> ——
  {@code MINION_CARD} の絞り込みは <b>Java に1つ・battle.js に2つ</b>写しがあり、
  <b>73 まで3つとも揃って {@code == MINION} で間違っていた</b>。
  ★<b>揃っていることは正しいことではない</b>ので、
  「両方に居るか」を測る 49-1 とは別に、<b>どう判定するか</b>を verify で測っている。

★★<b>出口ごとに当てている</b>(71 の教訓)。
  - {@code onWeaponLeftPlay} は「破壊」「付け替え」「山札へ戻す」の3経路 … 軸14〜16
  - {@code effectPutSequence} は「進化に当たったら止める」「素材が足りない」
    「出せた」の3つの出口 …………………………………………………………… 軸19・20・21
  - {@code requestManaSummon} は「自動決定」と「割り込みの解決」の2つ … 軸22・23

使い方: python3 tools/batch74_break_check.py [ケース番号...]
★★<b>長いので3つに分けて回し、前後で `git diff --stat` を突き合わせること</b>(70 の教訓)。
  例: `python3 tools/batch74_break_check.py 1 2 3 4 5 6 7 8`
★★★<b>verify を使う軸は最後に置いてある</b>(72 の教訓: 遷移を起こしうる項目は末尾)。
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
M2 = "/root/m2work/repository"

GUARDS = "src/main/java/com/example/qte/effect/RuleGuards.java"
REGISTRY = "src/main/java/com/example/qte/effect/CardEffectRegistry.java"
ACTIONS = "src/main/java/com/example/qte/game/GameActions.java"
SERVICE = "src/main/java/com/example/qte/game/GameService.java"
CANDIDATES = "src/main/java/com/example/qte/effect/TargetCandidates.java"
BATTLE_JS = "src/main/resources/static/js/battle.js"

RULING_TEST = "Batch74RulingTest"
EVOLUTION_TEST = "Batch74EvolutionPutTest"
FIRE_EARTH_TEST = "FireEarthVer11EffectTest"

# 壊しても落ちないことが分かっているもの(理由つき)。★74 は1件も無い。
EXPECTED_NG = {}

# ★★★壊しどころが無いもの(裁定196 の正直な扱い)——
#   軸に入れていない理由を書き残す。
#
#   1) B-9《傷痕の闘帝》の「そうしたら」
#      <b>裁定338 は「据え置き」である。</b>実装は無条件でドローしており、
#      壊すとは「成否を見るようにする」ことになる ——
#      <b>本文どおりに直すことが「壊す」になる</b>という珍しい形なので、軸にしていない。
#      ★番人({@code 傷痕の闘帝は軽減で0でもドローする})は置いてある。
#
#   2) B-12《聖光の守護聖》のリーダー破壊防止
#      <b>本物の入口が1つも無い</b>(235枚にリーダーを破壊するカードが無い)。
#      壊すと {@code RuleGuards} を直接叩く番人だけが落ちるので、軸として置いた(軸13)——
#      ★ただし<b>それは「本物の入口を通っている」ことの証明にはならない</b>。
#
#   3) B-8《豊穣の地霊主》/ B-10《背水の炎壁》/ B-11《悪夢》の「側」
#      どれも<b>据え置き</b>であり、壊すとは「両者を見るようにする」ことである。
#      ★{@code PlayerState} のフィールドを読んでいるだけなので、
#      「両者を見る」実装に書き換えるには器の新設が要る —— 1行の改変では表せない。
#      ★番人は置いてあるので、<b>逆向き(両者を見る実装にした人)には赤が出る</b>。
#
#   4) 手札からの経路(Batch 68 が作った側)
#      74 は<b>名前を変えただけ</b>である({@code PutFromHandState} →
#      {@code EffectPutState})。壊しどころは 68 の壊し検証が既に持っている。

# (説明, ファイル, 置換前, 置換後, 種別, クラス, 照合先の名前の一部)
CASES = [
    # ===============================================================
    # I. B の裁定328〜336(軸1〜16)
    # ===============================================================

    # 軸1: ★★★ゾディアックの「相手のリーダーは攻撃できない」の範囲(裁定328)
    ("ゾディアックがミニオンからリーダーへの攻撃も止める(73 までの姿へ戻す)", GUARDS,
     "        // ---- 光文明(★Batch 50): 場全体で合計1回しか攻撃できない(英術・バンユー) ----",
     "        if (targetIsLeader && hasOnField(state.opponentOf(owner.getPlayerId()), ZODIAC)) {\n"
     "            return \"【天界の守護神 ゾディアック】がいるためリーダーを攻撃できません\";\n"
     "        }\n"
     "        // ---- 光文明(★Batch 50): 場全体で合計1回しか攻撃できない(英術・バンユー) ----",
     "junit", RULING_TEST, "ゾディアックはミニオンからリーダーへの攻撃を止めない"),

    # 軸2: ★<b>残した側</b>を消す(出口ごとに当てる。71 の教訓)
    ("ゾディアックがリーダーの攻撃を止めなくなる(残した側を消す)", GUARDS,
     "        if (hasOnField(state.opponentOf(owner.getPlayerId()), ZODIAC)) {\n"
     "            return \"【天界の守護神 ゾディアック】がいるためリーダーは攻撃できません\";\n"
     "        }\n",
     "",
     "junit", RULING_TEST, "ゾディアックはリーダーの攻撃を止め続ける"),

    # 軸3: ★★蒼海の賢者の手札要求(裁定329)
    ("蒼海の賢者の手札1枚を必須へ戻す(手札0枚だと起動できない)", REGISTRY,
     "                TargetSpec.of(new TargetSpec.Requirement(TargetSpec.Kind.HAND, TargetSpec.Side.SELF,\n"
     "                        1, true, false, List.of(), \"山札の一番下に戻すカードを選んでください\")),",
     "                TargetSpec.of(new TargetSpec.Requirement(TargetSpec.Kind.HAND, TargetSpec.Side.SELF,\n"
     "                        1, false, false, List.of(), \"山札の一番下に戻すカードを選んでください\")),",
     "junit", RULING_TEST, "蒼海の賢者は手札0枚でも回復する"),

    # 軸4: ★分那愚利の対象を任意へ戻す(裁定330)
    ("分那愚利の対象を任意へ戻す(相手が居ても0体で答えられる)", REGISTRY,
     "                new Requirement(Kind.MINION, Side.OPPONENT, 1, false, false, List.of(),\n"
     "                        \"1ダメージを与える相手のミニオンを1体選んでください\")));",
     "                new Requirement(Kind.MINION, Side.OPPONENT, 1, true, false, List.of(),\n"
     "                        \"1ダメージを与える相手のミニオンを1体選んでください\")));",
     "junit", FIRE_EARTH_TEST, "分那愚利は相手の場に2体居ると必ず1体を選ばされる"),

    # 軸5: ★風弾の跳弾の相手側を必須へ戻す(裁定331)
    ("風弾の跳弾の相手側を必須へ戻す(相手の場が空だと使えない)", REGISTRY,
     "                new Requirement(Kind.MINION, Side.OPPONENT, 1, true, false, List.of(), \"3ダメージを与える相手のミニオンを選んでください\")));",
     "                new Requirement(Kind.MINION, Side.OPPONENT, 1, false, false, List.of(), \"3ダメージを与える相手のミニオンを選んでください\")));",
     "junit", RULING_TEST, "風弾の跳弾は相手の場が空でも使える"),

    # 軸6: ★★★静空の風使いのアンタップ先を自動決定へ戻す(裁定333)
    ("静空の風使いが先頭のマナを自動でアンタップする(73 までの姿)", REGISTRY,
     "        if (tappedPositions.size() == 1) {\n"
     "            ctx.actions().untapManaAt(ctx.room(), ctx.owner(), Integer.parseInt(tappedPositions.get(0)));\n"
     "            return;\n"
     "        }",
     "        ctx.actions().untapManaAt(ctx.room(), ctx.owner(), Integer.parseInt(tappedPositions.get(0)));\n"
     "        if (true) {\n"
     "            return;\n"
     "        }",
     "junit", RULING_TEST, "静空の風使いはアンタップするマナを選ばせる"),

    # 軸7: ★<b>もう一方の出口</b>(候補が1枚のときは問わない)を壊す
    ("静空の風使いが候補1枚でも問い合わせる(選ぶ余地が無いのに止まる)", REGISTRY,
     "        if (tappedPositions.size() == 1) {",
     "        if (false) {",
     "junit", RULING_TEST, "静空の風使いはタップ済みが1枚なら問わない"),

    # 軸8: ★★★回帰の風穴の2回目を数えなくする(裁定334)
    ("回帰の風穴の2回目を使用として数えない(73 までの姿)", REGISTRY,
     "            ctx.owner().setPendingExtraSpellCasts(ctx.owner().getPendingExtraSpellCasts() + 1);",
     "",
     "junit", RULING_TEST, "回帰の風穴の強化使用は2回数える"),

    # 軸9: ★<b>受け口の側</b>を壊す(効果が書いても、消費されなければ数は増えない)
    ("追加の詠唱を数える側(afterCardUsed)を止める", SERVICE,
     "        for (int i = 0; i < extra; i++) {\n"
     "            player.setCardsUsedThisTurn(player.getCardsUsedThisTurn() + 1);",
     "        for (int i = 0; i < 0; i++) {\n"
     "            player.setCardsUsedThisTurn(player.getCardsUsedThisTurn() + 1);",
     "junit", RULING_TEST, "回帰の風穴の強化使用は2回数える"),

    # 軸10: ★<b>数えすぎない</b>ほうの出口(通常使用では増えない)
    ("通常使用でも追加で数える(強化していないのに2回になる)", REGISTRY,
     "            if (!ctx.enhanced()) {\n"
     "                return;\n"
     "            }\n"
     "            ctx.owner().setPendingSpellDisposition(SpellDisposition.TO_DECK_BOTTOM);",
     "            ctx.owner().setPendingSpellDisposition(SpellDisposition.TO_DECK_BOTTOM);",
     "junit", RULING_TEST, "回帰の風穴の通常使用は1回だけ数える"),

    # 軸11: ★★神風の大号令の期限(裁定332)
    ("神風の大号令の強化を THIS_TURN へ戻す(ターンが終わると消える)", REGISTRY,
     "                m.addModifier(new StatModifier(StatModifier.Stat.ATTACK, StatModifier.Operation.ADD, destroyed,\n"
     "                        StatModifier.Duration.PERMANENT, \"QTE-M-WIND-12\"));",
     "                m.addModifier(new StatModifier(StatModifier.Stat.ATTACK, StatModifier.Operation.ADD, destroyed,\n"
     "                        StatModifier.Duration.THIS_TURN, \"QTE-M-WIND-12\"));",
     "junit", RULING_TEST, "神風の大号令の強化は永続である"),

    # 軸12: ★★据え置きの側を壊す —— 豊穣の地霊主が両者のマナを数えるようにする(裁定337)
    #   ★★★<b>1度目の壊し方は空振りした</b>(74 の実測)——
    #     「相手のリーダーも見る」だけを足しても、<b>引く先が置いた側のままだった</b>ので
    #     地霊主の持ち主の手札は1枚も増えず、番人は緑のままだった。
    #     <b>「壊した」と「効いた」は別である</b>(11 の形の新しい顔)。
    #     側を本当に外すには<b>誘発する側と引く側の両方</b>を書き換える必要がある。
    ("豊穣の地霊主が相手のマナ配置でも引く(誘発の側と引く側の両方を外す)", REGISTRY,
     "        PlayerState owner = ctx.owner();\n"
     "        if (HARVEST_LEADER.equals(owner.getLeader().id())\n"
     "                && owner.getCardsPutToManaThisTurn() == 2) {",
     "        PlayerState owner = HARVEST_LEADER.equals(ctx.owner().getLeader().id())\n"
     "                ? ctx.owner() : ctx.opponent();\n"
     "        if (HARVEST_LEADER.equals(owner.getLeader().id())\n"
     "                && ctx.owner().getCardsPutToManaThisTurn() == 2) {",
     "junit", RULING_TEST, "豊穣の地霊主は相手のマナでは引かない"),

    # 軸13: ★★★聖光の守護聖のリーダー破壊防止(裁定335)
    #   ★<b>本物の入口が無い</b>ので、番人は RuleGuards を直接叩いている
    ("聖光の守護聖がリーダーの破壊を防がない(本文の「自分のリーダーと」を無視する)", GUARDS,
     "        return hasPersistentAura(owner, HOLY_PROTECTOR_AURA) && causedByOpponent(state, owner);",
     "        return false;",
     "junit", RULING_TEST, "聖光の守護聖はリーダーの破壊も防ぐ"),

    # 軸14〜16: ★★★詠唱の宝珠の誘発条件(裁定336)。<b>出口ごとに当てる</b>
    ("詠唱の宝珠が「場を離れたとき」で発動する(73 までの姿)", ACTIONS,
     "        if (CHANT_ORB.equals(weapon.id()) && byDestruction && !fromTaboo) {",
     "        if (CHANT_ORB.equals(weapon.id())) {",
     "junit", RULING_TEST, "詠唱の宝珠は山札へ戻っても発動しない"),

    ("禁忌由来でも発動する(消滅は破壊ではないことを忘れる)", ACTIONS,
     "        if (CHANT_ORB.equals(weapon.id()) && byDestruction && !fromTaboo) {",
     "        if (CHANT_ORB.equals(weapon.id()) && byDestruction) {",
     "junit", RULING_TEST, "詠唱の宝珠は禁忌由来なら発動しない"),

    ("付け替えを破壊として扱わない(裁定336 の但し書きを落とす)", SERVICE,
     "            actions.onWeaponLeftPlay(room, player, old, true, player.isEquippedWeaponFromTaboo());",
     "            actions.onWeaponLeftPlay(room, player, old, false, player.isEquippedWeaponFromTaboo());",
     "junit", RULING_TEST, "詠唱の宝珠は付け替えでも発動する"),

    # ===============================================================
    # II. A の裁定341 —— 進化ミニオンの一族13枚(軸17〜25)
    # ===============================================================

    # 軸17: ★★★絞り込みの正(1箇所)を 73 の姿へ戻す
    ("MINION_CARD の絞り込みを == MINION へ戻す(進化が候補から落ちる)", CANDIDATES,
     "                case MINION_CARD -> master.type().isMinion() ? null\n"
     "                        : \"ミニオンカードを選んでください\";",
     "                case MINION_CARD -> master.type() == CardType.MINION ? null\n"
     "                        : \"ミニオンカードを選んでください\";",
     "junit", EVOLUTION_TEST, "ギガマウスは進化も出せる"),

    # 軸18: ★<b>広げすぎていない</b>ほうの出口(スペルは今も弾かれる)
    #   ★★★<b>1度目の照合先は空振りした</b>(74 の実測)——
    #     「マナのスペルは場に出せない」はマナの経路の<b>ラムダ</b>が弾いており、
    #     {@code TargetSpec.Filter} を1つも通らない。
    #     <b>壊した場所と番人が見ている場所が違っていた</b>(70 の「照合先がそこまで届いていない」)。
    #     Filter を通る否定側は<b>宣言時に墓地から選ぶ《禁忌の代償》</b>である。
    ("MINION_CARD が種別を1つも見なくなる(スペルまで通す)", CANDIDATES,
     "                case MINION_CARD -> master.type().isMinion() ? null\n"
     "                        : \"ミニオンカードを選んでください\";",
     "                case MINION_CARD -> null;",
     "junit", EVOLUTION_TEST, "禁忌の代償は墓地のスペルを選べない"),

    # 軸19: ★★★<b>素材を問う段</b>を消す(裁定226 が壊れる)
    ("進化に当たっても素材を問わずに出す(素材ゼロで場に立つ)", REGISTRY,
     "                if (requestEvolutionMaterialForPut(ctx, next)) {\n"
     "                    return; // 問い合わせを出した。続きは resolveChoice が引き継ぐ\n"
     "                }",
     "",
     "junit", EVOLUTION_TEST, "ギガマウスは進化も出せる"),

    # 軸20: ★<b>素材が足りないときに出さない</b>ほうの出口
    ("素材が足りなくても出してしまう(裁定226 に反する)", REGISTRY,
     "        if (materials.size() < spec.minMaterials()) {\n"
     "            ctx.room().addLog(\"【%s】: 【%s】の進化素材が足りないため、場に出せませんでした\"\n"
     "                    .formatted(sourceName, cards.findById(evolutionCardId).name()));\n"
     "            return false;\n"
     "        }",
     "",
     "junit", EVOLUTION_TEST, "喧嘩上等は素材が無ければ進化を出せない"),

    # 軸21: ★<b>出せなかったカードを戻す</b>出口(宣言時に抜かれた手札が消える)
    ("出せなかった手札を戻さない(カードがどのゾーンにも居なくなる)", REGISTRY,
     "            case HAND_SELECTED -> ctx.owner().getHand().add(cardId);",
     "            case HAND_SELECTED -> { /* 戻さない */ }",
     "junit", EVOLUTION_TEST, "ギガマウスは素材が無ければ進化を出せない"),

    # 軸22: ★★マナの経路の<b>自動決定</b>の側
    ("マナの自動決定が共通の列を通らない(進化でも素材を問わずに出す)", REGISTRY,
     "            effectPutSequence(ctx, EffectPutState.of(EffectPutSource.MANA, sourceCardId,\n"
     "                    List.of(positions.get(0)), false));",
     "            ctx.actions().putManaCardIntoField(ctx.room(), ctx.owner(),\n"
     "                    Integer.parseInt(positions.get(0)));",
     "junit", EVOLUTION_TEST, "喧嘩上等は進化も出せる"),

    # 軸23: ★★マナの入口が進化を弾く(GameActions の側)
    ("putManaCardIntoField が進化を弾く(73 までの姿)", ACTIONS,
     "        if (!master.type().isMinion()) {\n"
     "            room.addLog(\"【%s】はミニオンではないため、マナから場に出せません\".formatted(master.name()));",
     "        if (master.type() != com.example.qte.master.CardType.MINION) {\n"
     "            room.addLog(\"【%s】はミニオンではないため、マナから場に出せません\".formatted(master.name()));",
     "junit", EVOLUTION_TEST, "喧嘩上等は進化も出せる"),

    # 軸24: ★★墓地の入口が素材を受け取らない
    ("reviveFromGrave が素材を無視する(進化が素材ゼロで蘇る)", ACTIONS,
     "        MinionInstance revived = evolution\n"
     "                ? putIntoFieldByEffect(room, owner, cardId, materials, false, FieldEntryOrigin.OTHER)\n"
     "                : putIntoFieldByEffect(room, owner, cardId);",
     "        MinionInstance revived = putIntoFieldByEffect(room, owner, cardId);",
     "junit", EVOLUTION_TEST, "死者蘇生は進化も蘇生できる"),

    # 軸25: ★★★墓地からの「召喚」は素材を宣言のときに選ぶ
    ("墓地からの召喚が進化を弾く(73 までの姿)", SERVICE,
     "        if (!master.type().isMinion()) {\n"
     "            throw new IllegalStateException(\"墓地から召喚できるのはミニオンのみです\");",
     "        if (master.type() != CardType.MINION) {\n"
     "            throw new IllegalStateException(\"墓地から召喚できるのはミニオンのみです\");",
     "junit", EVOLUTION_TEST, "黄泉の召喚主は進化を召喚できる"),

    # 軸26: ★<b>スペルは今も弾かれる</b>ほうの出口
    ("墓地からの召喚が種別を1つも見なくなる(スペルまで召喚できる)", SERVICE,
     "        if (!master.type().isMinion()) {\n"
     "            throw new IllegalStateException(\"墓地から召喚できるのはミニオンのみです\");\n"
     "        }",
     "",
     "junit", EVOLUTION_TEST, "墓地のスペルは召喚できない"),

    # 軸27: ★★★山札の経路(《降臨の伝道師》)。73 が「暫定」と書いた場所
    ("降臨の伝道師が進化を候補から落とす(73 の暫定へ戻す)", REGISTRY,
     "                if (revealedCard.type().isMinion()\n"
     "                        && revealedCard.hasKeyword(Keyword.GUARD)\n"
     "                        && (revealedCard.type() != CardType.EVOLUTION\n"
     "                                || evolutionMaterialsAvailable(ctx.owner(), revealedCard.id()))) {",
     "                if (revealedCard.type() == CardType.MINION\n"
     "                        && revealedCard.hasKeyword(Keyword.GUARD)) {",
     "junit", EVOLUTION_TEST, "降臨の伝道師は素材があれば進化も出す"),

    # 軸28: ★<b>素材が確保できない進化は候補にしない</b>ほうの出口
    ("降臨の伝道師が素材の有無を見ずに進化を候補にする", REGISTRY,
     "                        && (revealedCard.type() != CardType.EVOLUTION\n"
     "                                || evolutionMaterialsAvailable(ctx.owner(), revealedCard.id()))) {",
     "                        ) {",
     "junit", EVOLUTION_TEST, "降臨の伝道師は素材が無ければ進化を候補にしない"),

    # 軸29: ★★★出したミニオンへの3ダメージ(割り込みを跨いで続く後半)
    ("降臨の伝道師の3ダメージを消す(割り込みの後の続きが落ちる)", REGISTRY,
     "            ctx.actions().damageMinion(ctx.room(), ctx.owner(), last, 3);",
     "",
     "junit", EVOLUTION_TEST, "降臨の伝道師は素材があれば進化も出す"),

    # 軸30: ★★★payload の2つの欄を1つに畳む(74 の実装中に実際に踏んだ形)
    #   ★マナだけ locator が「位置」なので、カードIDと同じ欄に入れると
    #     {@code evolutions.get("15")} を引いて<b>マナの経路でだけ</b>壊れる
    ("進化カードIDと locator を同じ欄に畳む(マナの経路でだけ壊れる)", REGISTRY,
     "                EffectPutState next = new EffectPutState(state.source(), state.sourceCardId(),\n"
     "                        master.id(), locator, queue, summoned, state.grantRush());",
     "                EffectPutState next = new EffectPutState(state.source(), state.sourceCardId(),\n"
     "                        locator, locator, queue, summoned, state.grantRush());",
     "junit", EVOLUTION_TEST, "喧嘩上等は進化も出せる"),

    # ===============================================================
    # III. ★★★verify(実測)—— 遷移を起こしうるので<b>末尾に置く</b>(72 の教訓)
    # ===============================================================

    # 軸31: ★★★battle.js の写し(その1)。matchesFilters
    ("battle.js の matchesFilters が進化を落とす(Java とずれる)", BATTLE_JS,
     "                ok = !!card && (card.type === 'MINION' || card.type === 'EVOLUTION'); break;",
     "                ok = !!card && card.type === 'MINION'; break;",
     "verify", None, "MINION_CARD は進化ミニオンも通す"),

    # 軸32: ★★★battle.js の写し(その2)。手札のハイライト
    #   ★<b>写しは2箇所ある。</b>片方だけ直すと「押せるのに灰色」または「灰色なのに押せる」になる
    ("battle.js の手札のハイライトが進化を落とす(写しの片方だけ直った状態)", BATTLE_JS,
     "                    case 'MINION_CARD': return card.type === 'MINION' || card.type === 'EVOLUTION';",
     "                    case 'MINION_CARD': return card.type === 'MINION';",
     "verify", None, "手札のハイライトも進化ミニオンを通す"),

    # 軸33: ★★墓地からの召喚の一覧が進化を落とす
    ("墓地からの召喚の一覧が進化を落とす(73 までの姿)", BATTLE_JS,
     "        if (mode === 'summon' && card.type !== 'MINION' && card.type !== 'EVOLUTION') return;",
     "        if (mode === 'summon' && card.type !== 'MINION') return;",
     "verify", None, "墓地からの召喚の一覧に進化ミニオンが並ぶ"),

    # 軸34: ★★★並ぶだけで素材を選ばせない(必ずサーバに弾かれるボタンになる)
    ("墓地の進化を選んでも素材の選択へ入らない(素材を送らずに投げる)", BATTLE_JS,
     "    if (card.type === 'EVOLUTION') {\n"
     "        beginEvolutionSelection('summon-from-grave', null, card.targets, { trashIndex }, card);\n"
     "        return;\n"
     "    }\n",
     "",
     "verify", None, "墓地の進化を選ぶと、素材の選択へ入る"),
]


def env():
    e = dict(os.environ)
    e.setdefault("NODE_PATH", "/home/claude/.npm-global/lib/node_modules")
    e.setdefault("PLAYWRIGHT_BROWSERS_PATH", "/opt/pw-browsers")
    return e


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as handle:
        return handle.read()


def write(path, text):
    with open(os.path.join(ROOT, path), "w", encoding="utf-8") as handle:
        handle.write(text)


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
    """★<b>「ビルドが失敗した」を OK と数えない。</b>報告書が生まれなければ EMPTY である(裁定304)。"""
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
    import signal

    def _raise(signum, frame):
        raise KeyboardInterrupt("signal %d" % signum)

    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, _raise)

    # ★★★開始時の姿を控えておく(70 の教訓)。壊したまま終わったら、
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
    if any(CASES[n - 1][4] == "verify" for n in picked):
        run_verify()
    good = counts.get("OK", 0) + counts.get("NG(想定内)", 0)
    return 0 if good == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
