#!/usr/bin/env python3
"""Batch 52 の壊し検証(裁定116)。

実装をわざと壊し、狙った試験が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った試験が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(試験が足りない)
  EMPTY    … その試験が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★照合は target/surefire-reports/*.xml に対して行う。
  Surefire は日本語のメソッド名を -Dtest=Class#method で選べず、
  コンソール出力は日本語を ? に潰すため、クラス単位で回して XML を読む。

★★"surefire:test" 単体で回してはいけない(裁定208)。コンパイルが走らないため、
  改変が反映されないまま古い .class を試験することになる。必ず "test" を回すこと。

★配分の方針(v58 の運用ルール)。52 の本体は<b>カードではなく進化エンジン</b>なので、
  そちらに厚く当てる —— 素材を下に置く / 引き継ぎ / 場を離れるときの同伴 / 召喚酔い。
  既存の形に乗っただけのカード(連太の2回攻撃・裏雷怒乗込のドロー等)は1通りずつでよい。

使い方: python3 tools/batch52_break_check.py [ケース番号...]
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
GUARDS = SRC + "effect/RuleGuards.java"
STATS = SRC + "effect/StatCalculator.java"
ACTIONS = SRC + "game/GameActions.java"
SERVICE = SRC + "game/GameService.java"
MINION = SRC + "game/MinionInstance.java"

EV = "com.example.qte.EvolutionEngineTest"
EI = "com.example.qte.EffectImplementationTest"
DV = "com.example.qte.DeckValidatorTest"

# (説明, ファイル, 置換前, 置換後, テストクラス, 落ちるべきテストメソッド)
CASES = [
    # ---------------------------------------------------------------
    # 工事1: 素材を場から外して下に置く(裁定154)
    # ---------------------------------------------------------------
    ("素材を場から取り除かない(進化と素材が並んで残る)", SERVICE,
     "            player.getMinionZone().remove(material);",
     "            // 壊し検証: 素材を場に残す",
     EV, "進化召喚は素材を場から取り除いて自分と入れ替わる"),

    ("素材を束に入れない(場からは消えるが下に置かれない)", SERVICE,
     "            minion.putUnder(new StackedCard(material.getMaster().id(), material.isFromTaboo()));",
     "            // 壊し検証: 下に置かない",
     EV, "素材は進化ミニオンの下に置かれる"),

    ("素材を破壊して取り除く(場を離れる扱いにする)", SERVICE,
     "            player.getMinionZone().remove(material);\n"
     "            // 素材が進化ミニオンなら、その下の束もそのまま引き継ぐ(マスター裁定 A3)。",
     "            actions.destroyMinion(room, player, material);\n"
     "            // 素材が進化ミニオンなら、その下の束もそのまま引き継ぐ(マスター裁定 A3)。",
     EV, "素材は墓地にも消滅ゾーンにも行かない"),

    ("進化召喚が召喚の共通処理を通らない(直接 minionZone へ)", SERVICE,
     "        summonToField(room, state, player, master, resolved, false, materials);\n"
     "    }",
     "        materials.forEach(m -> player.getMinionZone().remove(m));\n"
     "        player.getMinionZone().add(new MinionInstance(master, state.getTurnNumber()));\n"
     "    }",
     EV, "進化召喚でも登場時効果が発動する"),

    ("素材2体のうち1体しか下に入れない", SERVICE,
     "        for (MinionInstance material : materials) {",
     "        for (MinionInstance material : materials.subList(0, Math.min(1, materials.size()))) {",
     EV, "素材2体は2体とも束に入る"),

    ("進化を素材にしたとき、その下にあった束を捨てる(マスター裁定 A3 違反)", SERVICE,
     "            for (StackedCard stacked : material.getUnder()) {\n"
     "                minion.putUnder(stacked);\n"
     "            }",
     "            // 壊し検証: 古い束を引き継がない",
     EV, "進化を素材にすると束がそのまま引き継がれる"),

    # ---------------------------------------------------------------
    # 工事2: 引き継ぎ(裁定157(2)(3)・マスター裁定 B1〜B5)
    # ---------------------------------------------------------------
    ("素材に付与されていた効果を引き継がない", SERVICE,
     "            minion.inheritGrantsFrom(material);",
     "            // 壊し検証: 引き継がない",
     EV, "素材に付与されていた攻撃力の修正を引き継ぐ"),

    ("引き継ぎで受けているダメージも写す(マスター裁定 B3 違反)", MINION,
     "        modifiers.addAll(material.modifiers);",
     "        modifiers.addAll(material.modifiers);\n"
     "        this.damage = material.damage;",
     EV, "素材が受けているダメージは引き継がない"),

    ("引き継ぎでタップ状態も写す(マスター裁定 B2 違反)", MINION,
     "        grantedKeywords.addAll(material.grantedKeywords);",
     "        grantedKeywords.addAll(material.grantedKeywords);\n"
     "        this.tapped = material.tapped;",
     EV, "素材のタップ状態は引き継がない"),

    ("引き継ぎでこのターン限りの付与を落とす(マスター裁定 B4 違反)", MINION,
     "        grantedKeywordsThisTurn.addAll(material.grantedKeywordsThisTurn);",
     "        // 壊し検証: このターン限りの付与は引き継がない",
     EV, "このターンの間の付与も引き継ぐ"),

    # ---------------------------------------------------------------
    # 工事3: 場を離れるときの同伴(裁定154・マスター裁定 C1〜C3)
    # ---------------------------------------------------------------
    ("破壊されたとき束を運ばない(束が消える)", ACTIONS,
     "        dispatchUnderCards(room, owner, minion, UnderDestination.TRASH);",
     "        // 壊し検証: 束を運ばない",
     EV, "破壊されると束のカードも墓地へ行く"),

    ("束のカードの【破壊時】も発火させる(マスター裁定 C1 違反)", ACTIONS,
     "                case TRASH -> sendToTrashOrRestore(room, owner, card, false);",
     "                case TRASH -> {\n"
     "                    sendToTrashOrRestore(room, owner, card, false);\n"
     "                    MinionInstance ghost = new MinionInstance(card, 0);\n"
     "                    effects.fire(TriggerType.ON_DESTROYED, ghost, contextOf(room, owner, ghost));\n"
     "                }",
     EV, "束のカードの破壊時は発動しない"),

    ("手札に戻すとき束を運ばない", ACTIONS,
     "        dispatchUnderCards(room, owner, minion, UnderDestination.HAND);",
     "        // 壊し検証: 束を運ばない",
     EV, "手札に戻されると束のカードも手札へ戻る"),

    ("マナに置くとき束を運ばない", ACTIONS,
     "        dispatchUnderCards(room, owner, minion, UnderDestination.MANA_FACE_DOWN);",
     "        // 壊し検証: 束を運ばない",
     EV, "マナに置かれると束のカードも裏向きでマナへ行く"),

    ("マナ上限の判定に束を数えない(本体1枚ぶんだけ見る)", ACTIONS,
     "        int needed = (minion.isFromTaboo() ? 0 : 1)\n"
     "                + (int) minion.getUnder().stream().filter(s -> !s.fromTaboo()).count();",
     "        int needed = 1;",
     EV, "マナ上限で束ごと置けないなら場から動かさない"),

    # ★{@code underCardsForDeck}(サイクロン・リフレッシュ)にも同じ形の行があるため、
    #   置換前の文字列には dispatchUnderCards 側だけに現れる continue まで含める。
    #   1回目の実行はここが2箇所に一致して SETUP-NG になった(裁定196 の (b))。
    ("束のカードの出自を本体に揃える(マスター裁定 C3 違反)", ACTIONS,
     "            if (stacked.fromTaboo()) {\n"
     "                owner.getLostZone().add(card.id());\n"
     "                room.addLog(\"【%s】の下にあった【%s】は禁忌カードのため消滅しました\"\n"
     "                        .formatted(minion.getMaster().name(), card.name()));\n"
     "                continue;\n"
     "            }",
     "            if (minion.isFromTaboo()) {\n"
     "                owner.getLostZone().add(card.id());\n"
     "                continue;\n"
     "            }",
     EV, "禁忌由来の進化が破壊されると本体は消滅し素材は墓地へ行く"),

    # ---------------------------------------------------------------
    # 工事4: 召喚酔いの免除(裁定157(1))
    # ---------------------------------------------------------------
    ("進化の召喚酔い免除を外す", GUARDS,
     "            boolean allowed = attacker.isEvolution()\n"
     "                    || attacker.hasKeyword(Keyword.HASTE)",
     "            boolean allowed = attacker.hasKeyword(Keyword.HASTE)",
     EV, "進化ミニオンは出したターンにリーダーを攻撃できる"),

    ("召喚酔いの判定そのものを外す(誰でも出したターンに殴れる)", GUARDS,
     "        if (attacker.getEnteredTurn() == state.getTurnNumber()) {",
     "        if (false) {",
     EV, "普通のミニオンは出したターンに攻撃できない"),

    # ---------------------------------------------------------------
    # 素材の検証(サーバは届いた値を信用しない)
    # ---------------------------------------------------------------
    ("素材条件の述語を確かめない(クライアントの申告を信じる)", SERVICE,
     "            if (!spec.material().test(material)) {",
     "            if (false) {",
     EV, "条件を満たさないミニオンは素材にできない"),

    ("素材の数を確かめない", SERVICE,
     "        if (ids.size() < spec.minMaterials() || ids.size() > spec.maxMaterials()) {",
     "        if (false) {",
     EV, "素材を2体要求するカードは1体では出せない"),

    ("特殊召喚では素材を要求しない(マスター裁定 D1 違反)", SERVICE,
     "        List<MinionInstance> materials = evolution\n"
     "                ? resolveMaterials(player, master, materialIds) : List.of();",
     "        List<MinionInstance> materials = List.of();",
     EV, "走太は特殊召喚でも素材を要求する"),

    # ---------------------------------------------------------------
    # カード(既存の形に乗ったものは1通りずつ)
    # ---------------------------------------------------------------
    ("闘太の「下1枚につき+2」を場に出るときに写さない", SERVICE,
     "            minion.setStatPerUnderCard(spec == null ? 0 : spec.statPerUnderCard());",
     "            minion.setStatPerUnderCard(0);",
     EV, "闘太は下にあるカード1枚につきAttackとHPが2ずつ増える"),

    ("連太の2回攻撃を外す", STATS,
     "        if (RENTA.equals(minion.getMaster().id())) {\n            max += 1;\n        }",
     "        // 壊し検証: 2回攻撃しない",
     EV, "連太は1ターンに2回攻撃できる"),

    ("メリィナのコストの下限を一般の0に落とす", STATS,
     "            cost = Math.max(MERINA_MIN_COST, cost - owner.getMinionZone().size());",
     "            cost = cost - owner.getMinionZone().size();",
     EV, "メリィナのコストは3より下がらない"),

    ("茶爺が相手の進化ミニオンも選べる", REG,
     "                        Requirement.filtered(Kind.MINION, Side.SELF, 1, false,\n"
     "                                \"カードを下に入れる進化ミニオンを選んでください\",",
     "                        Requirement.filtered(Kind.MINION, Side.ANY, 1, false,\n"
     "                                \"カードを下に入れる進化ミニオンを選んでください\",",
     EV, "茶爺は相手の進化ミニオンの下には入れられない"),

    # ---------------------------------------------------------------
    # 印(EffectImplementation)とデッキ構築
    # ---------------------------------------------------------------
    ("進化の素材条件を「効果の登録」として数える(未実装の進化から印が消える)", REG,
     "                || playConditions.containsKey(cardId);",
     "                || playConditions.containsKey(cardId)\n"
     "                || evolutions.containsKey(cardId);",
     EI, "Batch52がBatch53へ送った8枚には今も印が付く"),

    ("素材条件しか効果文が無いカードの宣言を外す(シラーカに印が付く)", REG,
     "                    SHIRAKA, TOUTA);",
     "                    TOUTA);",
     EI, "Batch52で実装した8枚には印が付かない"),

    ("進化ミニオンをデッキ構築で弾いたままにする", "src/main/java/com/example/qte/deck/DeckValidator.java",
     "        if (card.type() == CardType.LEADER) {",
     "        if (card.type() == CardType.EVOLUTION || card.type() == CardType.LEADER) {",
     DV, "進化ミニオンはメインデッキに入れられる"),
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
    counts = {}
    for _, _, answer, _ in results:
        counts[answer] = counts.get(answer, 0) + 1
    print("\n" + " / ".join("%s %d" % (k, counts[k]) for k in sorted(counts)))
    return 0 if counts.get("OK", 0) == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
