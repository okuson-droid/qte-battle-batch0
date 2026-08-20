#!/usr/bin/env python3
"""Batch 53 の壊し検証(裁定116)。

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

★配分の方針(v59 の運用ルール)。53 が新しく作ったのは
  <b>効果から進化を出す経路</b>・<b>墓地からの特殊召喚</b>・<b>登場の数え上げ</b>の3つなので、
  そこに厚く当てる。既存の形に乗っただけのカード(ラカブの引いて捨てる等)は1通りずつでよい。

★verify(JavaScript)側の 53-1・53-2 はこのスクリプトの対象外である。
  壊し方と結果は 53 設計解説の8章に書いてある。

使い方: python3 tools/batch53_break_check.py [ケース番号...]
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

EF = "com.example.qte.EvolutionEffectTest"
EV = "com.example.qte.EvolutionEngineTest"
EI = "com.example.qte.EffectImplementationTest"

# (説明, ファイル, 置換前, 置換後, テストクラス, 落ちるべきテストメソッド)
CASES = [
    # ---------------------------------------------------------------
    # 工事1: 束を作る処理を1箇所にまとめた(GameActions.attachEvolutionMaterials)
    # ★52 は summonToField の中に直接書いていた。53 が効果側にも同じ束を作る必要から移した。
    #   まとめたぶん、<b>召喚側が今も生きているか</b>を確かめないと意味がない。
    # ---------------------------------------------------------------
    ("共通化した attachEvolutionMaterials が素材を場から外さない(召喚側が壊れる)", ACTIONS,
     "            owner.getMinionZone().remove(material);",
     "            // 壊し検証: 素材を場に残す",
     EV, "進化召喚は素材を場から取り除いて自分と入れ替わる"),

    ("共通化した attachEvolutionMaterials が引き継ぎを写さない(効果側が壊れる)", ACTIONS,
     "            minion.inheritGrantsFrom(material);",
     "            // 壊し検証: 引き継がない",
     EF, "スケアロックで出した進化も付与された効果を引き継ぐ"),

    ("効果で出す進化に素材を渡さない(束が空になる)", REG,
     "                .putIntoFieldByEffect(ctx.room(), ctx.owner(), cardId, materials);",
     "                .putIntoFieldByEffect(ctx.room(), ctx.owner(), cardId);",
     EF, "スケアロックは出した1体目を素材にして進化を出す"),

    # ---------------------------------------------------------------
    # 工事2: 登場の数え上げと《英霊・コレキ》の制限
    # ---------------------------------------------------------------
    ("場に出たことを数えない(コレキが何体でも通す)", ACTIONS,
     "        owner.countMinionEntry(room.getGameState().getTurnNumber());",
     "        // 壊し検証: 登場を数えない",
     EF, "コレキがあると相手は自身のターンに1体しかミニオンを出せない"),

    ("コレキが「自身のターン中」の限定を無視する", GUARDS,
     "        if (!owner.getPlayerId().equals(state.getTurnPlayerId())) {\n"
     "            return null; // 「自身のターン中」に限る\n"
     "        }",
     "        // 壊し検証: 手番を問わず縛る",
     EF, "コレキは相手の手番でないあいだの登場を止めない"),

    ("コレキが自分の場に居ても自分を縛る(「相手は」を無視する)", GUARDS,
     "        if (!hasOnField(state.opponentOf(owner.getPlayerId()), KOREKI)) {",
     "        if (!hasOnField(state.opponentOf(owner.getPlayerId()), KOREKI)\n"
     "                && !hasOnField(owner, KOREKI)) {",
     EF, "コレキは自分の展開を縛らない"),

    ("「場に出られるか」がコレキを見ない(効果で3体とも出てしまう)", ACTIONS,
     "        return state != null && guards.minionEntryDenial(state, owner) != null;",
     "        return false;",
     EF, "コレキの制限下では3体出す効果でも1体しか出ない"),

    ("通常召喚の入口が「場に出られるか」を確かめない", SERVICE,
     "        requireCanEnterField(state, player);\n"
     "        // 検証(状態を変えない)→ 支払い → 手札除去 → 場に出す → 効果、の順を守る。",
     "        // 検証(状態を変えない)→ 支払い → 手札除去 → 場に出す → 効果、の順を守る。",
     EF, "コレキがあると相手は自身のターンに1体しかミニオンを出せない"),

    # ---------------------------------------------------------------
    # 工事3: 墓地からの【特殊召喚】(《サモナーポップ・エンラ》)
    # ---------------------------------------------------------------
    ("墓地から出せると宣言していないカードも通す", SERVICE,
     "        if (spec == null || !spec.fromGrave()) {",
     "        if (spec == null) {",
     EF, "墓地から特殊召喚できないカードはこの入口を通れない"),

    ("エンラの fromGrave を落とす(手札からしか出せなくなる)", REG,
     "                \"自分の墓地にミニオンが6体以上います: コスト1で進化召喚します\",\n"
     "                true));",
     "                \"自分の墓地にミニオンが6体以上います: コスト1で進化召喚します\",\n"
     "                false));",
     EF, "エンラは墓地から特殊召喚できる"),

    ("墓地の枚数に自分自身(進化)を数えない(マスター裁定 違反)", REG,
     "                    return type == CardType.MINION || type == CardType.EVOLUTION;",
     "                    return type == CardType.MINION;",
     EF, "エンラは墓地から特殊召喚できる"),

    ("墓地からの特殊召喚では素材を要求しない(裁定226 違反)", SERVICE,
     "        List<MinionInstance> materials = evolution\n"
     "                ? resolveMaterials(player, master, materialIds) : List.of();\n"
     "        // 墓地のカード自身は手札に無いため、対象検証の自己除外インデックスは -1 である",
     "        List<MinionInstance> materials = List.of();\n"
     "        // 墓地のカード自身は手札に無いため、対象検証の自己除外インデックスは -1 である",
     EF, "エンラは墓地から特殊召喚できる"),

    ("エンラの候補にコスト4以上のミニオンも入れる", REG,
     "                    .filter(m -> m.getMaster().cost() != null && m.getMaster().cost() <= 3)",
     "                    .filter(m -> m.getMaster().cost() != null)",
     EF, "エンラは登場時に相手のコスト3以下のミニオンを1体破壊する"),

    ("エンラの候補が【潜伏】持ちを外さない", REG,
     "                    .filter(m -> !m.hasKeyword(Keyword.STEALTH))\n"
     "                    .filter(m -> m.getMaster().cost() != null && m.getMaster().cost() <= 3)",
     "                    .filter(m -> m.getMaster().cost() != null && m.getMaster().cost() <= 3)",
     EF, "エンラは相手の潜伏持ちを候補にしない"),

    # ---------------------------------------------------------------
    # 工事4: 候補の絞り込みをサーバに閉じたこと(裁定234)
    # ---------------------------------------------------------------
    ("灰ノ霊呼者が本文の【破壊時】を確かめない(どのミニオンでも出せる)", REG,
     "                if (m.type() == CardType.MINION && m.text() != null\n"
     "                        && m.text().contains(ON_DESTROYED_MARK)) {",
     "                if (m.type() == CardType.MINION) {",
     EF, "灰ノ霊呼者は破壊時を持たないミニオンを候補にしない"),

    ("灰ノ霊呼者が出したカードを手札から取り除かない(手札が増える)", REG,
     "                for (String cardId : takeHandCardsAt(ctx.owner(), chosen)) {\n"
     "                    if (ctx.actions().isFieldEntryBlocked(ctx.room(), ctx.owner())) {",
     "                for (String cardId : ctx.owner().getHand().stream()\n"
     "                        .limit(chosen.size()).toList()) {\n"
     "                    if (ctx.actions().isFieldEntryBlocked(ctx.room(), ctx.owner())) {",
     EF, "灰ノ霊呼者は召喚時に破壊時持ちを手札から2体出す"),

    ("灰ノ霊呼者が出したミニオンの【召喚時】も焚く", ACTIONS,
     "        fireEntryTriggers(room, owner, minion, contextOf(room, owner, minion));",
     "        effects.fire(TriggerType.ON_SUMMON, minion, contextOf(room, owner, minion));\n"
     "        fireEntryTriggers(room, owner, minion, contextOf(room, owner, minion));",
     EF, "灰ノ霊呼者が出したミニオンの召喚時は発動しない"),

    ("スケアロックが素材を確保できない進化も候補にする(マスター裁定 違反)", REG,
     "            if (!evolutionMaterialsAvailable(ctx.owner(), m.id())) {\n"
     "                continue;\n"
     "            }",
     "            // 壊し検証: 素材の有無を見ない",
     EF, "スケアロックは素材を確保できない進化を候補にしない"),

    ("スケアロックが1体目を出す前に進化の候補を作る(順序が逆)", REG,
     "            requestScarelockEvolution(ctx);\n"
     "        });",
     "        });",
     EF, "スケアロックは出した1体目を素材にして進化を出す"),

    ("スケアロックが光文明でない進化も候補にする", REG,
     "            if (m.type() != CardType.EVOLUTION || m.civilization() != Civilization.LIGHT) {",
     "            if (m.type() != CardType.EVOLUTION) {",
     EF, "スケアロックは光文明でない進化を候補にしない"),

    # ---------------------------------------------------------------
    # カード(既存の形に乗ったものは1通りずつ)
    # ---------------------------------------------------------------
    ("ラカブが引く前に捨てさせる(順序が逆)", REG,
     "            ctx.actions().drawCards(ctx.room(), ctx.owner(), 3);\n"
     "            requestDiscard(ctx, 1, 1, ResumePoint.RAKABU_DISCARD,",
     "            requestDiscard(ctx, 1, 1, ResumePoint.RAKABU_DISCARD,",
     EF, "ラカブは召喚時に3枚引いてから1枚捨てる"),

    ("ゾクシムの前半を【召喚時】として扱わない(マスター裁定 違反)", REG,
     "        register(\"QTE-M-WATER-32\", TriggerType.ON_SUMMON,\n"
     "                ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 2));",
     "        // 壊し検証: 召喚時に引かない",
     EF, "ゾクシムは召喚時に2枚引く"),

    ("手札が空でも捨てる問い合わせを出す(答えられない選択が残る)", REG,
     "        if (ctx.owner().getHand().isEmpty() || max <= 0) {\n"
     "            return;\n"
     "        }",
     "        if (false) {\n"
     "            return;\n"
     "        }",
     EF, "ゾクシムは手札が1枚しかなければ1枚だけ捨てる"),

    ("ノアが墓地からの経路(reviveFromGrave)を通らない(常在が効かなくなる)", REG,
     "                if (ctx.actions().reviveFromGrave(ctx.room(), ctx.owner(), cardId) != null) {\n"
     "                    summoned++;\n"
     "                }",
     "                ctx.owner().getTrash().remove(cardId);\n"
     "                if (ctx.actions().putIntoFieldByEffect(ctx.room(), ctx.owner(), cardId) != null) {\n"
     "                    summoned++;\n"
     "                }",
     EF, "ノアの召喚時で墓地から出たミニオンは突進を得る"),

    ("ノアの常在が「ノアが場に居るか」を見ない(誰の蘇生にも突進が付く)", REG,
     "        if (ctx.owner().getMinionZone().stream().anyMatch(m -> NOA.equals(m.getMaster().id()))\n"
     "                && !entered.hasKeyword(Keyword.RUSH)) {",
     "        if (!entered.hasKeyword(Keyword.RUSH)) {",
     EF, "ノアが場に居なければ墓地から出たミニオンは突進を得ない"),

    ("ニュウキロの増加量を+1固定にする(テングスンと同じ読みにする)", STATS,
     "            cost += (int) nyukiro * across.getHand().size();",
     "            cost += (int) nyukiro;",
     EF, "ニュウキロは相手のスペルのコストを自分の手札の数だけ重くする"),

    ("ニュウキロが自分の場を数える(「相手の」を取り違える)", STATS,
     "            long nyukiro = across.getMinionZone().stream()",
     "            long nyukiro = owner.getMinionZone().stream()",
     EF, "ニュウキロは自分のスペルのコストを変えない"),

    # ---------------------------------------------------------------
    # 印(EffectImplementation)
    # ---------------------------------------------------------------
    ("コレキの宣言(RuleGuards)を外す(ルール側にしか実装が無いので印が付く)", GUARDS,
     "            ORDER_ENFORCER, TEMPLE_KNIGHT, HAKUREI, KOKUREI, MOANIRU, SUPPORT_TANUKI,\n"
     "            KOREKI);",
     "            ORDER_ENFORCER, TEMPLE_KNIGHT, HAKUREI, KOKUREI, MOANIRU, SUPPORT_TANUKI);",
     EI, "Batch53で実装した8枚には印が付かない"),

    ("ニュウキロの宣言(StatCalculator)を外す(同上・置き場所が違うほうも測る)", STATS,
     "            EARTH_BERSERKER, SHEER_AYAKASHI, GIGAMOUSE_BITE, TENGSUN, NYUKIRO,",
     "            EARTH_BERSERKER, SHEER_AYAKASHI, GIGAMOUSE_BITE, TENGSUN,",
     EI, "Batch53で実装した8枚には印が付かない"),

    ("賢魂待ちの1枚を実装済みに見せる(送ったカードから印が消える)", REG,
     "        registerEvolutionEffectCards();",
     "        registerEvolutionEffectCards();\n"
     "        spellEffects.put(\"QTE-M-EARTH-36\", ctx -> {\n"
     "        });",
     EI, "Batch52と53が賢魂待ちとしてP4へ送った7枚には今も印が付く"),
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
