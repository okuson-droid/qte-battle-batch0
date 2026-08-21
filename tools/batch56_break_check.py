#!/usr/bin/env python3
"""Batch 56 全体(作り直し② 区分3b・4 の40枚)の壊し検証(裁定116)。

実装をわざと壊し、狙った試験が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った試験が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(試験が足りない)
  EMPTY    … その試験が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★照合は target/surefire-reports/*.xml に対して行う(55 と同じ形)。
★★"surefire:test" 単体で回してはいけない(裁定208)。必ず "test" を回すこと。

★対象は Batch 56 の40枚である(火水風光23枚 = 56前半 + 闇土17枚 = 56後半/Batch 57 の
  チャットで実施)。裁定260〜267 待ちの8枚は実装していないため含まない。

★配分の方針。区分3b・4 は「既存の分岐を1〜3箇所直す」形が中心であり、
  <b>直した分岐そのもの</b>に当てるのが素直である。したがって
  「実装変更なし」と結論した10枚(流転の智者・回帰の風穴・風護の杖・唱導の聖騎士・
  戒律のガーディアン・降臨の伝道師・断罪の大天使・詠唱の宝珠・大地の精霊グラン・
  苗木植えの精霊・天変地異のタイタン・アースクエイク・ジャイアント・豊穣の地霊主・
  ガイア・ハンマー・死者蘇生)には壊しどころが無い —— これらは
  <b>変えていないことの確認</b>であり、壊し検証ではなく試験の存在そのものが番人である。
  代表として《ガイア・ハンマー》(ON_EQUIP の発火口)だけを1件含めてある。

使い方: python3 tools/batch56_break_check.py [ケース番号...]
"""
import glob
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MVN = ["mvn", "-o", "-B", "-q", "-Dmaven.repo.local=/root/m2work/repository",
       "test", "-DfailIfNoTests=false"]

SRC = "src/main/java/com/example/qte/"
REG = SRC + "effect/CardEffectRegistry.java"
STATS = SRC + "effect/StatCalculator.java"
GUARDS = SRC + "effect/RuleGuards.java"
SERVICE = SRC + "game/GameService.java"

T56 = "com.example.qte.Batch56ReworkTest"

# (説明, ファイル, 置換前, 置換後, テストクラス, 落ちるべきテストメソッド)
CASES = [
    # ===============================================================
    # 56 前半 — 火文明
    # ===============================================================
    ("武具昇華の炎の対象が両者のウェポンに広がっていない(自分限定のまま)", REG,
     "                Requirement.upTo(Kind.WEAPON, Side.ANY, 1, \"破壊するウェポンを選んでください(いなければ確定)\")));\n"
     "        spellEffects.put(\"QTE-M-FIRE-24\", ctx -> {",
     "                Requirement.upTo(Kind.WEAPON, Side.SELF, 1, \"破壊するウェポンを選んでください(いなければ確定)\")));\n"
     "        spellEffects.put(\"QTE-M-FIRE-24\", ctx -> {",
     T56, "武具昇華の炎は相手のウェポンも破壊対象にできる"),

    ("鳳凰神ヴォルカニクスレヴォの特殊召喚コストが1になっていない(0のまま)", REG,
     "                (state, player, handIndex) -> player.getHealedAmountThisTurn(Civilization.FIRE) >= 5,\n"
     "                1, TargetSpec.of(), ctx -> {",
     "                (state, player, handIndex) -> player.getHealedAmountThisTurn(Civilization.FIRE) >= 5,\n"
     "                0, TargetSpec.of(), ctx -> {",
     T56, "鳳凰神ヴォルカニクスレヴォは1コスト分のマナが無ければ特殊召喚できない"),

    ("覚醒の炎童の【召喚時】1回復が登録されていない", REG,
     "        register(\"QTE-M-FIRE-20\", TriggerType.ON_SUMMON,\n"
     "                ctx -> ctx.actions().healLeader(ctx.room(), ctx.owner(), 1, \"QTE-M-FIRE-20\"));",
     "        register(\"QTE-M-FIRE-20\", TriggerType.ON_SUMMON, ctx -> {\n"
     "        });",
     T56, "覚醒の炎童は通常召喚でも召喚時に1回復し知識で1枚引く"),

    # ===============================================================
    # 56 前半 — 水文明
    # ===============================================================
    ("双流の幻術師の参照が【知識】ミニオンに絞られていない(全ミニオンのまま)", STATS,
     "                    .filter(m -> m.hasKeyword(Keyword.KNOWLEDGE))\n"
     "                    .count();\n"
     "            cost -= (int) knowledgeMinionsOnBoard;",
     "                    .count();\n"
     "            cost -= (int) knowledgeMinionsOnBoard;",
     T56, "双流の幻術師は知識を持たないミニオンだけならコストが下がらない"),

    ("智将ポセイドン・コアの特殊召喚条件が9に緩和されていない(12のまま)", REG,
     "                        .mapToInt(MinionInstance::getCurrentHp).sum() >= 9,",
     "                        .mapToInt(MinionInstance::getCurrentHp).sum() >= 12,",
     T56, "ポセイドンコアは合計体力9以上で特殊召喚でき召喚時に知識2体につき1枚引く"),

    ("静寂の瞑想のドローが2枚に減っていない(3枚のまま)", REG,
     "        spellEffects.put(\"QTE-M-WATER-26\", // 静寂の瞑想: 2枚引く\n"
     "                ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 2));",
     "        spellEffects.put(\"QTE-M-WATER-26\", // 静寂の瞑想: 2枚引く\n"
     "                ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 3));",
     T56, "静寂の瞑想は2枚引きその後もカードを使用できる"),

    # ===============================================================
    # 56 前半 — 風文明
    # ===============================================================
    # ★体力の向き(<=)と体数(==3)は<b>別の軸</b>である。1つの改変で両方を戻すと
    #   「体力1のミニオン4体」の盤面では両方の誤りが打ち消し合って条件が偽のままになり、
    #   壊したのに落ちない(初回の実行で実際に NG になった)。軸ごとに1件ずつ当てる。
    ("嵐の守り手の体数が「ちょうど3体」になっていない(3体以上のまま)", REG,
     "                        .filter(m -> m.getCurrentHp() <= 3).count() == 3,",
     "                        .filter(m -> m.getCurrentHp() <= 3).count() >= 3,",
     T56, "嵐の守り手は体力3以下のミニオンが4体だと特殊召喚できない"),

    ("嵐の守り手の体力の向きが反転していない(体力3以上のまま)", REG,
     "                        .filter(m -> m.getCurrentHp() <= 3).count() == 3,",
     "                        .filter(m -> m.getCurrentHp() >= 3).count() == 3,",
     T56, "嵐の守り手は体力3以下のミニオンがちょうど3体なら1コストで特殊召喚できる"),

    ("風神ヴァーユの参照が風文明のカード数になっていない(墓地の【守護】数のまま)", REG,
     "                        .filter(id -> cards.findById(id).civilization() == Civilization.WIND).count() >= 6,",
     "                        .filter(id -> cards.findById(id).keywords().contains(Keyword.GUARD)).count() >= 4,",
     T56, "風神ヴァーユは守護持ちが多いだけでは特殊召喚できない"),

    ("詠唱の風詠士の対象からミニオン・スペル限定が外れていない", STATS,
     "        if (WIND_CHANTER_LEADER.equals(owner.getLeader().id())\n"
     "                && owner.getCardsUsedThisTurn() == 2) {",
     "        if (WIND_CHANTER_LEADER.equals(owner.getLeader().id())\n"
     "                && owner.getCardsUsedThisTurn() == 2\n"
     "                && (asType == CardType.MINION || asType == CardType.SPELL)) {",
     T56, "詠唱の風詠士は3枚目に使うウェポンのコストも下がる"),

    ("選択の追い風の捨て札候補から【守護】限定が外れていない", REG,
     "            for (int i = 0; i < hand.size(); i++) {\n"
     "                handPositions.add(String.valueOf(i));\n"
     "            }\n"
     "            if (handPositions.isEmpty()) {\n"
     "                return;\n"
     "            }\n"
     "            ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.upTo(\n"
     "                    PendingChoice.Kind.HAND, handPositions, 1, ResumePoint.TAILWIND_DISCARD,",
     "            for (int i = 0; i < hand.size(); i++) {\n"
     "                if (cards.findById(hand.get(i)).keywords().contains(Keyword.GUARD)) {\n"
     "                    handPositions.add(String.valueOf(i));\n"
     "                }\n"
     "            }\n"
     "            if (handPositions.isEmpty()) {\n"
     "                return;\n"
     "            }\n"
     "            ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.upTo(\n"
     "                    PendingChoice.Kind.HAND, handPositions, 1, ResumePoint.TAILWIND_DISCARD,",
     T56, "選択の追い風は守護を持たないカードも捨てて追加ドローできる"),

    # ===============================================================
    # 56 前半 — 光文明
    # ===============================================================
    ("聖域の案内人が自身を除外していない(自分の【守護】でも条件を満たしてしまう)", REG,
     "                    .filter(m -> m != ctx.source())\n"
     "                    .anyMatch(m -> m.hasKeyword(Keyword.GUARD));",
     "                    .anyMatch(m -> m.hasKeyword(Keyword.GUARD));",
     T56, "聖域の案内人は自身の守護だけでは追加の知識は発動しない"),

    ("天界の守護神ゾディアックの【召喚時】ウェポン破壊が働かない", REG,
     "        register(\"QTE-M-LIGHT-8\", TriggerType.ON_SUMMON, ctx -> {\n"
     "            boolean destroyed = false;",
     "        register(\"QTE-M-LIGHT-8\", TriggerType.ON_SUMMON, ctx -> {\n"
     "            if (true) {\n"
     "                return;\n"
     "            }\n"
     "            boolean destroyed = false;",
     T56, "ゾディアックは召喚時に相手のウェポンを破壊する"),

    ("ホーリー・シグナルが最低体力側を破壊していない(最高攻撃力の1体だけ)", REG,
     "            MinionInstance lowestHp = AutoChoice.lowestCurrentHp(beforeOpp);",
     "            MinionInstance lowestHp = null;",
     T56, "ホーリーシグナルは攻撃力最大と体力最小が別のミニオンなら両方破壊する"),

    ("聖光の武装解除の破壊成功時1ドローが無い", REG,
     "            if (destroyed) {\n"
     "                ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);",
     "            if (false) {\n"
     "                ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);",
     T56, "聖光の武装解除はウェポンを破壊できたときだけ1枚引く"),

    # ===============================================================
    # 56 後半 — 闇文明
    # ===============================================================
    ("執念の暗殺者の監視が「自分の」限定に戻っている", REG,
     "        watchAnyMinionDestroyed(\"QTE-M-DARK-20\", (ctx, destroyedCardId) -> {",
     "        watchOwnMinionDestroyed(\"QTE-M-DARK-20\", (ctx, destroyedCardId) -> {",
     T56, "執念の暗殺者は相手のミニオンが破壊されても引く"),

    ("執念の暗殺者のドローが破壊された側へ飛んでいる(swapSidesの向きが逆)", REG,
     "        EffectContext otherSide = ctx.swapSides();",
     "        EffectContext otherSide = ctx;",
     T56, "執念の暗殺者は相手のミニオンが破壊されても引く"),

    ("墓場の怨念集合体のコスト軽減が無い(Attack加算だけのまま)", STATS,
     "        if (GRAVE_WRAITH_MASS.equals(card.id())) {\n"
     "            cost -= nonSpellCountInTrash(owner);\n"
     "        }",
     "        if (GRAVE_WRAITH_MASS.equals(card.id())) {\n"
     "            cost -= 0;\n"
     "        }",
     T56, "墓場の怨念集合体は墓地のスペル以外の数だけコストも下がる"),

    ("群がる死霊王の軽減量が2になっていない(1枚につき-1のまま)", STATS,
     "            cost -= countInTrash(owner, ZOMB_STRIKER) * 2;",
     "            cost -= countInTrash(owner, ZOMB_STRIKER);",
     T56, "群がる死霊王はゾンストライカー1枚につきコストが2下がる"),

    ("冥府の禁皇の参照ゾーンが墓地になっていない(裏向きマナのまま)", REG,
     "                (state, player) -> !player.getTrash().isEmpty(),\n"
     "                \"墓地のカード1枚を手札に戻し、山札の上から2枚を墓地に置く\"));",
     "                (state, player) -> true,\n"
     "                \"墓地のカード1枚を手札に戻し、山札の上から2枚を墓地に置く\"));",
     T56, "冥府の禁皇は墓地が空なら使用できない"),

    ("冥府の禁皇の後半がセルフミルになっていない(2ドローのまま)", REG,
     "                    if (returned) {\n"
     "                        ctx.actions().mill(ctx.room(), ctx.owner(), 2);\n"
     "                    }",
     "                    if (returned) {\n"
     "                        ctx.actions().drawCards(ctx.room(), ctx.owner(), 2);\n"
     "                    }",
     T56, "冥府の禁皇は墓地のカードを手札に戻し山札の上から2枚を墓地に置く"),

    ("獄門の裁定者のリーダー攻撃禁止が無い", GUARDS,
     "        if (targetIsLeader && WARDEN_JUDGE.equals(attacker.getMaster().id())) {\n"
     "            return \"【獄門の裁定者】はリーダーを攻撃できません\";\n"
     "        }",
     "        if (false) {\n"
     "            return \"【獄門の裁定者】はリーダーを攻撃できません\";\n"
     "        }",
     T56, "獄門の裁定者はリーダーを攻撃できない"),

    ("禁忌の代償の後半が蘇生になっていない(何も出さない)", REG,
     "            ctx.targets().get(0).trashCardIds()\n"
     "                    .forEach(id -> ctx.actions().reviveFromGrave(ctx.room(), ctx.owner(), id));",
     "            ctx.targets().get(0).trashCardIds().forEach(id -> {\n"
     "            });",
     T56, "禁忌の代償は裏向きマナを砕いて墓地のコスト4以下を場に出す"),

    ("絶望の連鎖の3体以上ドローの閾値が違う(4体以上になっている)", REG,
     "            if (ctx.state().getMinionsDestroyedThisTurn() >= 3) {",
     "            if (ctx.state().getMinionsDestroyedThisTurn() >= 4) {",
     T56, "絶望の連鎖はこのターン3体以上破壊されていたら1枚引く"),

    ("絶望の連鎖の「そうしたら」が効いていない(自分の破壊と無関係に相手を壊す)", REG,
     "            if (destroyedOwn) {\n"
     "                ctx.targets().get(1).minions()",
     "            if (true) {\n"
     "                ctx.targets().get(1).minions()",
     T56, "絶望の連鎖は破壊が2体だけならドローしない"),

    ("黄泉の召喚主の墓地召喚ガードが無い(対象を選ぶ召喚時でNPEになる)", SERVICE,
     "        if (!effects.targetSpecOf(master.id()).requirements().isEmpty()) {\n"
     "            throw new IllegalStateException(\n"
     "                    \"【%s】は召喚時に対象を選ぶため、墓地からは召喚できません\".formatted(master.name()));\n"
     "        }",
     "        if (false) {\n"
     "            throw new IllegalStateException(\n"
     "                    \"【%s】は召喚時に対象を選ぶため、墓地からは召喚できません\".formatted(master.name()));\n"
     "        }",
     T56, "黄泉の召喚主は対象を選ぶ召喚時を持つミニオンを墓地から召喚できない"),

    # ===============================================================
    # 56 後半 — 土文明
    # ===============================================================
    ("安らぎのガーディアンの回復が自分のターンに限定されていない", REG,
     "            if (ctx.state().turnPlayer() != ctx.owner()) {\n"
     "                return;\n"
     "            }\n"
     "            ctx.actions().healLeader(ctx.room(), ctx.owner(), 4, \"QTE-M-EARTH-20\");",
     "            ctx.actions().healLeader(ctx.room(), ctx.owner(), 4, \"QTE-M-EARTH-20\");",
     T56, "安らぎのガーディアンは自分のターンエンドにだけ4回復する"),

    ("大地の恵みのマナ10枚以上ドローが無い", REG,
     "            if (ctx.owner().getManaZone().size() >= 10) {\n"
     "                ctx.room().addLog(\"【大地の恵み】: マナが10枚以上のため1枚ドロー\");",
     "            if (false) {\n"
     "                ctx.room().addLog(\"【大地の恵み】: マナが10枚以上のため1枚ドロー\");",
     T56, "大地の恵みはマナが10枚以上になったら1枚引く"),

    ("ガイア・ハンマーの装備時マナ加速が働かない(実装変更なし組の代表)", REG,
     "        register(\"QTE-M-EARTH-14\", TriggerType.ON_EQUIP,\n"
     "                ctx -> ctx.actions().placeTopOfDeckInManaFaceUp(ctx.room(), ctx.owner()));",
     "        register(\"QTE-M-EARTH-14\", TriggerType.ON_EQUIP, ctx -> {\n"
     "        });",
     T56, "ガイアハンマーは装備時に山札の上を表向きでマナに置く"),
]


# ★「壊しても落ちないことが分かっていて、それでよい」ケース(説明 → 理由)。
#
# 裁定196 の4値は「試験が足りない」を NG と呼ぶが、<b>そもそも本物の入口から
# 観測できない分岐</b>というものが存在する(ハンドオフの積み残しにある
# 《英霊・コレキ》の「相手のターン中は止めない」と同じ立場である)。
# 黙って外すと記録が消えるので、理由つきでここに残す。
EXPECTED_NG = {
    "絶望の連鎖の「そうしたら」が効いていない(自分の破壊と無関係に相手を壊す)":
        "自分側の破壊は必須の対象であり、使用条件(自分のミニオンが1体以上)も掛かっているため、"
        "現在のカードプールでは<b>破壊が成立しない盤面を作れない</b>。"
        "「破壊されない」効果(大天使ミカエルなど区分5)が入ったら観測できるようになる。"
        "本文が「そうしたら」と書いている以上、分岐は残す。",
}


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
        if answer == "NG" and label in EXPECTED_NG:
            answer = "NG(想定内)"
        results.append((number, label, answer, method))
        print("%2d %-10s %s  →  %s" % (number, answer, label, method))
        if answer == "NG(想定内)":
            print("      理由: %s" % EXPECTED_NG[label])

    counts = {}
    for _, _, answer, _ in results:
        counts[answer] = counts.get(answer, 0) + 1
    print("\n" + " / ".join("%s %d" % (k, counts[k]) for k in sorted(counts)))
    good = counts.get("OK", 0) + counts.get("NG(想定内)", 0)
    return 0 if good == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
