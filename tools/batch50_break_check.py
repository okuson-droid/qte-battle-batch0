#!/usr/bin/env python3
"""Batch 50 の壊し検証(裁定116)。

実装をわざと壊し、狙った試験が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った試験が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(試験が足りない)
  EMPTY    … その試験が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★照合は target/surefire-reports/*.xml に対して行う。
  Surefire は日本語のメソッド名を -Dtest=Class#method で選べず、
  コンソール出力は日本語を ? に潰すため、クラス単位で回して XML を読む。

使い方: python3 tools/batch50_break_check.py [ケース番号...]
"""
import glob
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# ★"surefire:test" 単体ではコンパイルが走らず、改変が反映されないまま古い .class を試験して
# しまう(1回目の実行で18件が NG になった。裁定196 の (b)「改変が当たっていない」の実例)。
# 必ず "test" ライフサイクル(= compile → test-compile → test)を回すこと。
MVN = ["mvn", "-o", "-B", "-q", "-Dmaven.repo.local=/root/m2work/repository",
       "test", "-DfailIfNoTests=false"]

SRC = "src/main/java/com/example/qte/"
REG = SRC + "effect/CardEffectRegistry.java"
GUARDS = SRC + "effect/RuleGuards.java"
STATS = SRC + "effect/StatCalculator.java"
ACTIONS = SRC + "game/GameActions.java"
SERVICE = SRC + "game/GameService.java"
PSTATE = SRC + "game/PlayerState.java"

DL = "com.example.qte.DarkLightVer11EffectTest"

# (説明, ファイル, 置換前, 置換後, テストクラス, 落ちるべきテストメソッド)
CASES = [
    # ---- 新しい仕組み1: 「場以外から墓地へ」の入口 ----
    ("putIntoTrashFromElsewhere が発火口を呼ばない", ACTIONS,
     "        effects.fireCardPutIntoTrashFromElsewhere(contextOf(room, owner, null), cardId);",
     "        // 壊し検証: 発火しない",
     DL, "キーパーは手札から捨てられると場に戻る"),

    ("ミルが入口を通らず直接墓地に置く", ACTIONS,
     "            putIntoTrashFromElsewhere(room, player, cardId); // 山札から = 場以外から(★Batch 50)\n"
     "            moved++;",
     "            player.getTrash().add(cardId);\n"
     "            moved++;",
     DL, "キーパーは山札から墓地に置かれても場に戻る"),

    ("場を離れた墓地送りも入口を通す(場以外の区別を壊す)", ACTIONS,
     "        owner.getTrash().add(card.id());\n        return true;",
     "        putIntoTrashFromElsewhere(room, owner, card.id());\n        return true;",
     DL, "キーパーは場で破壊されたときは戻らない"),

    ("発火口がカードIDを見ない(何が捨てられても戻る)", REG,
     "        if (!COMEBACK_KEEPER.equals(putCardId)) {\n            return;\n        }",
     "        if (false) {\n            return;\n        }",
     DL, "キーパーは他のカードが捨てられても戻らない"),

    # ---- 新しい仕組み2: 「墓地から場へ」の発火口 ----
    ("reviveFromGrave が墓守の発火口を呼ばない", ACTIONS,
     "            effects.fireMinionEnteredFromGrave(contextOf(room, owner, revived));",
     "            // 壊し検証: 発火しない",
     DL, "墓守は墓地から出たミニオンの攻撃力を1上げる"),

    ("墓守の発火口がリーダーを見ない(誰でも+1)", REG,
     "        if (entered == null || !GRAVE_DANCER_LEADER.equals(ctx.owner().getLeader().id())) {",
     "        if (entered == null) {",
     DL, "墓守でないリーダーでは蘇生しても攻撃力は上がらない"),

    ("墓守の修正を PERMANENT にする", REG,
     "                1, StatModifier.Duration.THIS_TURN, GRAVE_DANCER_LEADER));",
     "                1, StatModifier.Duration.PERMANENT, GRAVE_DANCER_LEADER));",
     DL, "墓守の加算はそのターンで切れる"),

    # ---- 新しい仕組み3: 登場の置換 ----
    ("モアニールの置換が場に居るかを見ない", GUARDS,
     "        if (!hasOnField(state.opponentOf(owner.getPlayerId()), MOANIRU)) {\n            return false;\n        }",
     "        if (false) {\n            return false;\n        }",
     DL, "モアニールが居なければ重いミニオンも普通に場に出る"),

    ("召喚の経路には置換を掛けない", SERVICE,
     "        if (guards.isEntryToDeckBottom(state, player, master)) {",
     "        if (false && guards.isEntryToDeckBottom(state, player, master)) {",
     DL, "モアニールは特殊召喚で出るミニオンも山札の下に送る"),

    # ---- 新しい仕組み4: リーダーへのダメージの肩代わり ----
    ("効果ダメージの肩代わりを外す", ACTIONS,
     "        if (tryReplaceLeaderDamageWithGuardian(room, player)) {\n            return;\n        }",
     "        // 壊し検証: 肩代わりしない",
     DL, "モアニールは効果ダメージを肩代わりして自身が破壊される"),

    ("ミニオンの攻撃では肩代わりしない", SERVICE,
     "            if (!actions.tryInterceptLeaderAttackWithShield(room, opponent)\n"
     "                    && !actions.tryReplaceLeaderDamageWithGuardian(room, opponent)) {\n"
     "                int damage = stats.effectiveAttack(state, player, attacker);",
     "            if (!actions.tryInterceptLeaderAttackWithShield(room, opponent)) {\n"
     "                int damage = stats.effectiveAttack(state, player, attacker);",
     DL, "モアニールは戦闘ダメージも肩代わりする"),

    # ---- 新しい仕組み5: 場全体の攻撃回数 ----
    ("バンユーの制限を「2回まで」にする", GUARDS,
     "                && owner.getMinionAttacksUsedThisTurn() >= 1) {",
     "                && owner.getMinionAttacksUsedThisTurn() >= 2) {",
     DL, "バンユーは相手の場全体で攻撃を1回までに制限する"),

    ("制限そのものをターン開始でリセットする", PSTATE,
     "        minionAttacksUsedThisTurn = 0;",
     "        minionAttacksUsedThisTurn = 0;\n        minionAttackLimitedOnTurn = 0;",
     DL, "バンユーは相手の場全体で攻撃を1回までに制限する"),

    # ---- 新しい仕組み6: アントマルエルの相乗り ----
    ("アントマルエルの手札上限を無くす", REG,
     "    private static final int ANTOMARUEL_HAND_LIMIT = 6;",
     "    private static final int ANTOMARUEL_HAND_LIMIT = 99;",
     DL, "アントマルエルは手札が7枚以上なら引かない"),

    # ---- カード単位(既存の形に乗ったもの。1通りずつ) ----
    ("サモンズライトがコスト2まで蘇生する", REG,
     "                        return m.type() == CardType.MINION && m.cost() != null && m.cost() == 1;",
     "                        return m.type() == CardType.MINION && m.cost() != null && m.cost() <= 2;",
     DL, "サモンズライトはコスト2のミニオンを出さない"),

    ("ネオンステージが自身も手札の枚数に数える", REG,
     "                (state, player, handIndex) -> !player.getMinionZone().isEmpty()\n"
     "                        && player.getHand().size() - 1 >= 2,",
     "                (state, player, handIndex) -> !player.getMinionZone().isEmpty()\n"
     "                        && player.getHand().size() >= 2,",
     DL, "ネオンステージは自身を除いて手札が2枚なければ特殊召喚できない"),

    ("ドリーミーの常在の加算を外す", STATS,
     "        if (DREAMY.equals(cardId)) {\n            attack += state.getMinionsDestroyedThisTurn();\n        }",
     "        if (false) {\n            attack += state.getMinionsDestroyedThisTurn();\n        }",
     DL, "ドリーミーは破壊した数だけ攻撃力が上がる"),

    ("テングスンが自分の場を見る", STATS,
     "            cost += (int) state.opponentOf(owner.getPlayerId()).getMinionZone().stream()",
     "            cost += (int) owner.getMinionZone().stream()",
     DL, "テングスンは自分の場に居ても自分のスペルを重くしない"),

    ("ネフラが残りを墓地に置く", REG,
     "            ctx.actions().returnToBottomOfDeck(ctx.owner(), rest);",
     "            rest.forEach(id -> ctx.actions().putIntoTrashFromElsewhere(ctx.room(), ctx.owner(), id));",
     DL, "ネフラは守護とスペルを手札に加え残りを山札の下に置く"),
]


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as fh:
        return fh.read()


def write(path, text):
    with open(os.path.join(ROOT, path), "w", encoding="utf-8") as fh:
        fh.write(text)


def run_class(test_class):
    subprocess.run(MVN + ["-Dtest=" + test_class.split(".")[-1]],
                   cwd=ROOT, capture_output=True)
    path = os.path.join(ROOT, "target/surefire-reports/TEST-%s.xml" % test_class)
    if not os.path.exists(path):
        return None
    return ET.parse(path).getroot()


def verdict(root, method):
    if root is None:
        return "EMPTY"
    for case in root.iter("testcase"):
        if case.get("name") == method:
            failed = any(child.tag in ("failure", "error") for child in case)
            return "OK" if failed else "NG"
    return "EMPTY"


def main():
    picked = [int(a) for a in sys.argv[1:]] or list(range(1, len(CASES) + 1))
    # 事前に既存レポートを消す(古い結果を読まないため)
    for stale in glob.glob(os.path.join(ROOT, "target/surefire-reports/TEST-*.xml")):
        os.remove(stale)
    results = []
    for number, (label, path, before, after, test_class, method) in enumerate(CASES, 1):
        if number not in picked:
            continue
        original = read(path)
        hits = original.count(before)
        if hits != 1:
            results.append((number, label, "SETUP-NG", "置換前の文字列が %d 箇所に一致" % hits))
            print("%2d SETUP-NG %s (一致 %d 箇所)" % (number, label, hits))
            continue
        write(path, original.replace(before, after))
        try:
            answer = verdict(run_class(test_class), method)
        finally:
            write(path, original)
        results.append((number, label, answer, method))
        print("%2d %-8s %s  →  %s" % (number, answer, label, method))
    print()
    for name in ("OK", "NG", "EMPTY", "SETUP-NG"):
        print("%s: %d" % (name, sum(1 for r in results if r[2] == name)))


if __name__ == "__main__":
    main()
