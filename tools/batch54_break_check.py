#!/usr/bin/env python3
"""Batch 54 の壊し検証(裁定116)。

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

★配分の方針(v60 の運用ルール)。54 が新しく作ったのは
  <b>1枚のカードが2つの姿を持つ</b>という構造そのものなので、そこに厚く当てる ——
  テキストの割れ方・コスト・スペル扱い・使用後の行き先・2つの入口。
  既存の形に乗っただけのカードの効果(タイガラムの2ドロー等)は1通りずつでよい。

★verify(JavaScript)側の 54-1〜54-3 はこのスクリプトの対象外である。
  壊し方と結果は 54 設計解説の8章に書いてある。

使い方: python3 tools/batch54_break_check.py [ケース番号...]
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
KEYWORDS = SRC + "master/CardTextKeywords.java"

SS = "com.example.qte.SoulSpellTest"
EI = "com.example.qte.EffectImplementationTest"
CK = "com.example.qte.CardTextKeywordsTest"

# (説明, ファイル, 置換前, 置換後, テストクラス, 落ちるべきテストメソッド)
CASES = [
    # ---------------------------------------------------------------
    # 工事1: テキストが【賢魂：n】で2つに割れること(裁定152・マスター裁定 B1)
    # ★これが 54 の土台である。割れていなければ、n もキーワードも姿を区別できない。
    # ---------------------------------------------------------------
    ("抽出層が境目を無視して本文全体を読む(白ノ霊知者の還元が本体に付く)", KEYWORDS,
     "        return keywordsIn(minionFace(text));",
     "        return keywordsIn(text);",
     SS, "白ノ霊知者はミニオンとしては還元を持たない"),

    ("賢魂の姿のキーワードを読まない(還元が効かず墓地へ行く)", KEYWORDS,
     "        String face = soulFace(text);\n"
     "        return face == null ? Set.of() : keywordsIn(face);",
     "        return Set.of();",
     SS, "還元を持つ賢魂は使用後に裏向きでマナへ置かれる"),

    ("賢魂のコストが全角数字を読まない(グレイヴガールズファンだけ賢魂を失う)", KEYWORDS,
     "\"【賢魂[：:]\\\\s*([0-9０-９]+)】\"",
     "\"【賢魂[：:]\\\\s*([0-9]+)】\"",
     SS, "賢魂のコストは全角でも半角でも読める"),

    # ---------------------------------------------------------------
    # 工事2: 賢魂の使用は「スペルの使用」であること(裁定152・マスター裁定 A2)
    # ---------------------------------------------------------------
    ("賢魂のコストに印刷コストを使う(n を無視する)", SERVICE,
     "        payCost(player, stats.effectiveSoulCost(state, player, master, soulCostOf(master)));",
     "        payCost(player, stats.effectiveCost(state, player, master));",
     SS, "賢魂として使うときのコストはnである"),

    ("賢魂のコスト計算がスペルとして扱われない(軽減が乗らない)", STATS,
     "        return effectiveCost(state, owner, card, soulCost, CardType.SPELL);",
     "        return effectiveCost(state, owner, card, soulCost, card.type());",
     SS, "賢魂はスペルのコスト軽減を受ける"),

    ("賢魂をスペルの使用として数えない(spellsCastThisTurn が進まない)", SERVICE,
     "        // 賢魂はスペルの使用である —— spellsCastThisTurn も進む(マスター裁定 A2(1))\n"
     "        afterCardUsed(room, state, player, true);",
     "        afterCardUsed(room, state, player, false);",
     SS, "賢魂の使用はスペルの使用として数える"),

    ("賢魂がスペル封じを見ない(勝阿外の下でも使えてしまう)", SERVICE,
     "        String spellDenial = guards.spellDenial(state, player);\n"
     "        if (spellDenial != null) {\n"
     "            throw new IllegalStateException(spellDenial);\n"
     "        }\n"
     "        return soul;",
     "        return soul;",
     SS, "勝阿外が場に居ると相手はスペルも賢魂も使えない"),

    ("賢魂がメインフェイズ限定になる(サブで使えない)", SERVICE,
     "        if (state.getPhase() != TurnPhase.MAIN && state.getPhase() != TurnPhase.SUB) {\n"
     "            throw new IllegalStateException(\"スペルはメイン/サブフェイズでのみ使用できます\");\n"
     "        }\n"
     "        PlayerState player = state.playerOf(playerId);\n"
     "        if (player.isCannotUseCardsThisTurn()) {\n"
     "            throw new IllegalStateException(\"このターンはカードを使用できません\");\n"
     "        }\n"
     "        CardMaster master = cards.findById(peekHand(player, handIndex));\n"
     "        SoulSpellSpec soul = requireSoul(state, player, master);",
     "        requirePhase(state, TurnPhase.MAIN);\n"
     "        PlayerState player = state.playerOf(playerId);\n"
     "        CardMaster master = cards.findById(peekHand(player, handIndex));\n"
     "        SoulSpellSpec soul = requireSoul(state, player, master);",
     SS, "賢魂はサブフェイズでも使えるがミニオンとしての召喚はできない"),

    # ★1回目は NG だった —— 試験が「【賢魂】」という語だけを見ていたため、
    #   この検査を消しても「【賢魂】の効果は未実装です」のほうが拾ってしまっていた。
    #   試験を文言まで測る形に直して OK になった(裁定196 の (a)「試験が足りない」)。
    ("賢魂を持たないカードもこの入口を通れる", SERVICE,
     "        if (!CardTextKeywords.hasSoul(master.text())) {\n"
     "            throw new IllegalStateException(\"このカードは【賢魂】を持ちません\");\n"
     "        }\n"
     "        SoulSpellSpec soul = effects.soulSpellOf(master.id());",
     "        SoulSpellSpec soul = effects.soulSpellOf(master.id());",
     SS, "賢魂を持たないカードは賢魂として使えない"),

    ("賢魂の対象要求にミニオンとしての対象要求を使う", SERVICE,
     "        ValidatedTargets validated = validateTargets(state, player, handIndex, soul.targets(), choices);",
     "        ValidatedTargets validated = validateTargets(state, player, handIndex,\n"
     "                effects.targetSpecOf(master.id()), choices);",
     SS, "白ノ霊知者の賢魂は自分のミニオンの攻撃力を1上げる"),

    # ---------------------------------------------------------------
    # 工事3: 使用後の行き先(マスター裁定 A1・A6・B6-2)
    # ---------------------------------------------------------------
    ("賢魂の【還元】を判定するとき、ミニオンの姿のキーワードを見る", SERVICE,
     "            boolean restoration = CardTextKeywords.soulKeywords(master.text())\n"
     "                    .contains(Keyword.RESTORATION);",
     "            boolean restoration = master.hasKeyword(Keyword.RESTORATION);",
     SS, "還元を持つ賢魂は使用後に裏向きでマナへ置かれる"),

    ("禁忌の賢魂が消滅せず墓地へ行く(総合ルール3-6 違反)", SERVICE,
     "            actions.disposeUsedCard(room, player, master, true, false);\n"
     "            return;",
     "            actions.disposeUsedCard(room, player, master, false, false);\n"
     "            return;",
     SS, "禁忌デッキの賢魂はn枚のマナを退けて使える"),

    ("禁忌の賢魂が印刷コスト分のマナを要求する(マスター裁定 A6 違反)", SERVICE,
     "            validateTabooCost(player, soulCostOf(master), manaIndexes);",
     "            validateTabooCost(player, master.cost(), manaIndexes);",
     SS, "禁忌デッキの賢魂はn枚のマナを退けて使える"),

    # ---------------------------------------------------------------
    # 工事4: 姿が分かれていること(ミニオンとして召喚したら賢魂は発動しない)
    # ---------------------------------------------------------------
    ("賢魂の効果を【召喚時】にも登録する(召喚しても発動してしまう)", REG,
     "        register(\"QTE-M-DARK-38\", TriggerType.ON_SUMMON,",
     "        register(\"QTE-M-DARK-37\", TriggerType.ON_SUMMON, ctx -> {\n"
     "            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);\n"
     "            ctx.actions().mill(ctx.room(), ctx.owner(), 1);\n"
     "        });\n"
     "        register(\"QTE-M-DARK-38\", TriggerType.ON_SUMMON,",
     SS, "ミニオンとして召喚しても賢魂の効果は発動しない"),

    # ---------------------------------------------------------------
    # 工事5: 7枚のカード
    # ---------------------------------------------------------------
    ("グレイヴガールズファンが山札の上を墓地に置かない", REG,
     "            // 「山札の上から1枚目を墓地に置く」= ミル。場を経由しない墓地送りである(裁定207)\n"
     "            ctx.actions().mill(ctx.room(), ctx.owner(), 1);",
     "            // 壊し検証: ミルしない",
     SS, "グレイヴガールズファンの賢魂は1枚引いて1枚墓地に置く"),

    ("スタンディングテントが行き先を書かない(場に出たのに墓地にも入る)", REG,
     "            ctx.owner().setPendingSpellDisposition(SpellDisposition.KEPT_BY_EFFECT);\n"
     "            if (placed != null) {",
     "            if (placed != null) {",
     SS, "スタンディングテントの賢魂は自身を場に出し召喚時は発動しない"),

    ("スタンディングテントが2ダメージを与えない", REG,
     "                ctx.actions().damageMinion(ctx.room(), ctx.owner(), placed, 2);",
     "                // 壊し検証: ダメージを与えない",
     SS, "スタンディングテントの賢魂は自身を場に出し召喚時は発動しない"),

    ("スタンディングテントが「場に出られるか」を先に見ない(満杯でカードが宙に浮く)", REG,
     "            if (ctx.actions().isFieldEntryBlocked(ctx.room(), ctx.owner())) {\n"
     "                ctx.room().addLog(\"【スタンディングテント】: 場に出せないため、墓地に置かれます\");\n"
     "                return;\n"
     "            }",
     "            // 壊し検証: 先に見ない",
     SS, "スタンディングテントの賢魂は場が満杯なら墓地へ行く"),

    ("タイガラムの【召喚時】が手札からミニオンを出さない", REG,
     "                ctx.actions().putIntoFieldByEffect(ctx.room(), ctx.owner(), id);\n"
     "            }\n"
     "        });\n"
     "        soulSpells.put(\"QTE-M-LIGHT-32\",",
     "            }\n"
     "        });\n"
     "        soulSpells.put(\"QTE-M-LIGHT-32\",",
     SS, "タイガラムの召喚時は手札から守護ミニオンを場に出す"),

    ("タイガラムの賢魂が引く枚数を間違える", REG,
     "                SoulSpellSpec.of(ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 2)));",
     "                SoulSpellSpec.of(ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 1)));",
     SS, "タイガラムの賢魂は2枚引く"),

    ("黒ノ霊導者が「そうしたら」を見ない(破壊できなくてもダメージを与える)", REG,
     "                    if (sacrifices.isEmpty()) {\n"
     "                        ctx.room().addLog(\"【黒ノ霊導者】: 破壊する自分のミニオンが居ないため、何も起こりませんでした\");\n"
     "                        return;\n"
     "                    }\n"
     "                    ResolvedTargets.TargetedMinion sacrifice = sacrifices.get(0);\n"
     "                    ctx.actions().destroyMinion(ctx.room(), sacrifice.owner(), sacrifice.minion());\n"
     "                    if (sacrifice.owner().getMinionZone().contains(sacrifice.minion())) {\n"
     "                        ctx.room().addLog(\"【黒ノ霊導者】: 破壊されなかったため、3ダメージは与えられません\");\n"
     "                        return;\n"
     "                    }",
     "                    for (ResolvedTargets.TargetedMinion s : sacrifices) {\n"
     "                        ctx.actions().destroyMinion(ctx.room(), s.owner(), s.minion());\n"
     "                    }",
     SS, "黒ノ霊導者の賢魂は自分のミニオンが居なくても使えるが何も起こらない"),

    ("白ノ霊知者の【召喚時】が自分の場しか候補にしない(裁定156(2) 違反)", REG,
     "            for (PlayerState side : List.of(ctx.owner(), ctx.opponent())) {\n"
     "                side.getMinionZone().forEach(m -> candidates.add(m.getInstanceId()));\n"
     "            }",
     "            ctx.owner().getMinionZone().forEach(m -> candidates.add(m.getInstanceId()));",
     SS, "白ノ霊知者の召喚時は2枚引いてから割り込みで1体破壊する"),

    ("愚乱怒土地が選ばなかった1枚を手札に加えない", REG,
     "            if (i != toMana) {\n"
     "                ctx.owner().getHand().add(cardId);",
     "            if (i != toMana) {\n"
     "                if (false) ctx.owner().getHand().add(cardId);",
     SS, "愚乱怒土地の賢魂は2枚見て1枚をマナに1枚を手札に加える"),

    ("愚乱怒土地が表向きでマナに置く(本文の「裏向きで」を無視する)", REG,
     "            if (!ctx.actions().placeCardInManaFaceDown(ctx.room(), ctx.owner(), cardId)) {",
     "            if (!ctx.actions().placeCardInManaFaceUp(ctx.room(), ctx.owner(), cardId)) {",
     SS, "愚乱怒土地の賢魂は2枚見て1枚をマナに1枚を手札に加える"),

    ("勝阿外のドロー判定をマナに置く前に行う(マスター裁定 B8-4 違反)", REG,
     "            ctx.actions().placeTopOfDeckInManaFaceDown(ctx.room(), ctx.owner());\n"
     "            if (ctx.owner().getManaZone().size() <= KATSUAGE_DRAW_MANA_LIMIT) {\n"
     "                ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);\n"
     "            }",
     "            boolean draws = ctx.owner().getManaZone().size() <= KATSUAGE_DRAW_MANA_LIMIT;\n"
     "            ctx.actions().placeTopOfDeckInManaFaceDown(ctx.room(), ctx.owner());\n"
     "            if (draws) {\n"
     "                ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);\n"
     "            }",
     SS, "勝阿外の賢魂はマナが4枚以上になるとドローしない"),

    ("勝阿外の【常在】がスペルを封じない", GUARDS,
     "        if (hasOnField(state.opponentOf(player.getPlayerId()), KATSUAGE)) {",
     "        if (false) {",
     SS, "勝阿外が場に居ると相手はスペルも賢魂も使えない"),

    ("勝阿外の【常在】が自分のスペルも封じる(「相手は」を無視する)", GUARDS,
     "        if (hasOnField(state.opponentOf(player.getPlayerId()), KATSUAGE)) {",
     "        if (hasOnField(state.opponentOf(player.getPlayerId()), KATSUAGE)\n"
     "                || hasOnField(player, KATSUAGE)) {",
     SS, "自分の場の勝阿外は自分のスペルを止めない"),

    ("勝阿外の攻撃力が相手の手札を数えない", STATS,
     "            attack += state.opponentOf(owner.getPlayerId()).getHand().size();",
     "            attack += 0;",
     SS, "勝阿外の攻撃力は相手の手札の枚数だけ上がる"),

    # ---------------------------------------------------------------
    # 工事6: 印(効果未実装)が 0 になったこと
    # ---------------------------------------------------------------
    ("isRegistered が soulSpells を見ない(賢魂7枚に印が戻る)", REG,
     "                || soulSpells.containsKey(cardId);",
     "                ;",
     EI, "効果未実装のカードは1枚も無い"),
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
