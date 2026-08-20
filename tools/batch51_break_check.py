#!/usr/bin/env python3
"""Batch 51 の壊し検証(裁定116)。

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

★配分の方針(v57 の運用ルール)。51 は<b>エンジンの工事が3つ</b>あるバッチなので、
  そちらに厚く当てる —— マナ⇄場の行き来・攻撃の保留・相手ターンの選択。
  既存の形に乗っただけのカードは1通りずつでよい。

使い方: python3 tools/batch51_break_check.py [ケース番号...]
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
ACTIONS = SRC + "game/GameActions.java"
SERVICE = SRC + "game/GameService.java"

FE = "com.example.qte.FireEarthVer11EffectTest"
EI = "com.example.qte.EffectImplementationTest"

# (説明, ファイル, 置換前, 置換後, テストクラス, 落ちるべきテストメソッド)
CASES = [
    # ---------------------------------------------------------------
    # 工事1: マナと場の行き来(GameActions の新設2本)
    # ---------------------------------------------------------------
    ("マナから場に出すとき、場が満杯かを見ない(カードが宙に浮く)", ACTIONS,
     "        if (owner.isMinionZoneFull()) {\n"
     "            room.addLog(\"場がいっぱいのため、マナから場に出せませんでした\");\n"
     "            return null;\n"
     "        }",
     "        // 壊し検証: 満杯を見ない",
     FE, "場が満杯ならマナから場に出せずマナも減らない"),

    ("マナから場に出す経路が ON_ENTER を通らない(直接 minionZone へ)", ACTIONS,
     "        MinionInstance minion = putIntoFieldByEffect(room, owner, mana.getCardId());",
     "        MinionInstance minion = new MinionInstance(master, room.getGameState().getTurnNumber());\n"
     "        owner.getMinionZone().add(minion);",
     FE, "マナから場に出したミニオンの登場時効果が発動する"),

    ("場のミニオンをマナに置くとき、マナ上限で場から消してしまう", ACTIONS,
     "        if (!minion.isFromTaboo() && owner.getManaZone().size() >= PlayerState.MAX_MANA) {",
     "        if (false) {",
     FE, "マナが上限なら場のミニオンをマナに置けず場からも消えない"),

    ("場からマナへ置くとき表向きにする(裏向きの指定を無視)", ACTIONS,
     "        ManaCard mana = new ManaCard(minion.getMaster().id(), false);\n"
     "        mana.turnFaceDown();",
     "        ManaCard mana = new ManaCard(minion.getMaster().id(), false);",
     FE, "喧嘩上等は相手のミニオンを相手のマナに裏向きで置く"),

    # ---------------------------------------------------------------
    # 工事2: 攻撃時の割り込みは戦闘を保留する(裁定213)
    # ---------------------------------------------------------------
    ("攻撃時に割り込みが出ても戦闘を保留しない", SERVICE,
     "        if (player.getPendingChoice() != null) {\n"
     "            state.setPendingAttack(new PendingAttack(playerId, attackerInstanceId,\n"
     "                    targetInstanceId, targetIsLeader));\n"
     "            return;\n"
     "        }",
     "        // 壊し検証: 保留しない",
     FE, "素手喧嘩はマナに置くと戦闘が起きずマナからミニオンを出す"),

    # ★{@code resolveCombat} の「攻撃者が場を離れていたら戦闘しない」と
    #   {@code resumePendingAttack} の「連鎖した割り込みを待つ」の2つは、
    #   <b>現行のカードプールでは到達しない防御的な分岐</b>のため壊し検証に入れていない
    #   (素手喧嘩の経路では、instanceId で引き直す側が先に「場に居ない」と判定するため、
    #   壊しても結果が変わらない)。設計解説の「答えていないこと」に明記してある。
    ("選択が解決しても保留した戦闘を再開しない", SERVICE,
     "        resumePendingAttack(room, state);\n",
     "        // 壊し検証: 再開しない\n",
     FE, "素手喧嘩はマナに置かなければ普通に戦闘する"),

    # ---------------------------------------------------------------
    # 工事3: 相手のターン中にも本人が選ぶ(裁定214)
    # ---------------------------------------------------------------
    ("相手の選択待ちのあいだ、手番の側を止めない", SERVICE,
     "        if (state.opponentOf(playerId).getPendingChoice() != null) {\n"
     "            throw new IllegalStateException(\"相手が選択中です。解決を待ってください\");\n"
     "        }",
     "        // 壊し検証: 相手の選択待ちを見ない",
     FE, "勝鼓美は相手のターンに破壊されても本人が選べる"),

    ("自分の選択待ちのあいだも操作を通す", SERVICE,
     "        if (state.playerOf(playerId).getPendingChoice() != null) {\n"
     "            throw new IllegalStateException(\"先に選択を解決してください\");\n"
     "        }",
     "        // 壊し検証: 自分の選択待ちを見ない",
     FE, "選択待ちのあいだは他の操作ができない"),

    # ---------------------------------------------------------------
    # 裁定の細目(211: 向きの限定 / 210: 明記の無い向き)
    # ---------------------------------------------------------------
    ("勝鼓美が表向きのマナしか候補にしない(限定を足してしまう)", REG,
     "                        return master.type() == CardType.MINION && master.cost() <= 3;",
     "                        return mana.isFaceUp() && master.type() == CardType.MINION\n"
     "                                && master.cost() <= 3;",
     FE, "勝鼓美は裏向きのマナからも場に出せる"),

    ("セカイヲスベシモノが裏向きのマナも候補にする(限定を落とす)", REG,
     "                    mana -> mana.isFaceUp()\n"
     "                            && cards.findById(mana.getCardId()).type() == CardType.MINION,",
     "                    mana -> cards.findById(mana.getCardId()).type() == CardType.MINION,",
     FE, "セカイヲスベシモノは裏向きのマナからは出さない"),

    ("翔山が裏向きでマナに置く(裁定210 を取り違える)", REG,
     "                    if (!ctx.actions().placeCardInManaFaceUp(ctx.room(), ctx.owner(), cardId)) {",
     "                    if (!ctx.actions().putTrashCardIntoManaFaceDown(ctx.room(), ctx.owner(), cardId)) {",
     FE, "翔山は墓地のカードを表向きでマナに置く"),

    ("勝鼓美のコスト上限を外す", REG,
     "                        return master.type() == CardType.MINION && master.cost() <= 3;",
     "                        return master.type() == CardType.MINION;",
     FE, "勝鼓美はコスト4以上のミニオンをマナから出さない"),

    ("素手喧嘩の Attack 上限を外す", REG,
     "                            return mana.isFaceUp() && master.type() == CardType.MINION\n"
     "                                    && master.attack() != null && master.attack() <= 6;",
     "                            return mana.isFaceUp() && master.type() == CardType.MINION;",
     FE, "素手喧嘩はAttack7以上のミニオンをマナから出さない"),

    # ---------------------------------------------------------------
    # 既存の形に乗ったカード(1通りずつ)
    # ---------------------------------------------------------------
    ("支援盾機狸の攻撃禁止を外す", GUARDS,
     "        if (SUPPORT_TANUKI.equals(attacker.getMaster().id())) {\n"
     "            return \"【支援盾機狸】は攻撃できません\";\n"
     "        }",
     "        // 壊し検証: 攻撃を止めない",
     FE, "支援盾機狸は攻撃できない"),

    ("乱戦鉄機狼のLP判定を反転する", REG,
     "            if (ctx.owner().getLp() <= IRON_WOLF_LP_THRESHOLD) {",
     "            if (ctx.owner().getLp() > IRON_WOLF_LP_THRESHOLD) {",
     FE, "乱戦鉄機狼はLPが10以下なら代わりに相手を削る"),

    ("砲台鉄機虎の特殊召喚条件を常に真にする", REG,
     "                (state, player, handIndex) -> hasEvolutionOnAnyField(state),",
     "                (state, player, handIndex) -> true,",
     FE, "砲台鉄機虎は進化ミニオンが場に居なければ特殊召喚できない"),

    ("ラスト・アタックが進化でなくても全体2ダメージを撒く", REG,
     "            if (!wasEvolution) {\n                return;\n            }",
     "            if (false) {\n                return;\n            }",
     FE, "ラストアタックは進化でなければ相手全体には広がらない"),

    ("リペア・チューナーのディスカードを必須にする", REG,
     "                Requirement.upTo(Kind.HAND, Side.SELF, 1, \"捨てるカードを1枚選んでください\")));",
     "                new Requirement(Kind.HAND, Side.SELF, 1, false, false, List.of(),\n"
     "                        \"捨てるカードを1枚選んでください\")));",
     FE, "リペアチューナーは捨てる手札が無くても2枚引ける"),

    ("アイアン・リターンが戻した枚数ちょうどしか引かない", REG,
     "            ctx.actions().drawCards(ctx.room(), ctx.owner(), hand.size() + 1);",
     "            ctx.actions().drawCards(ctx.room(), ctx.owner(), hand.size());",
     FE, "アイアンリターンは自身を除いた枚数プラス1枚引く"),

    ("ドレイン・ブラストが破壊できなくても回復する", REG,
     "            if (destroyed > 0) {\n"
     "                ctx.actions().healLeader(ctx.room(), ctx.owner(), destroyed, \"QTE-M-FIRE-39\");\n"
     "            }",
     "            ctx.actions().healLeader(ctx.room(), ctx.owner(), 1, \"QTE-M-FIRE-39\");",
     FE, "ドレインブラストは破壊できなければ回復しない"),

    ("ベヒーモスが同値のときも相手を削る", REG,
     "            if (ctx.owner().getLp() == ctx.opponent().getLp()) {",
     "            if (false) {",
     FE, "ベヒーモスは体力が同じなら誰も削らない"),

    ("分那愚利の対象を必須にする(相手の場が空だと召喚できなくなる)", REG,
     "        targetSpecs.put(\"QTE-M-EARTH-33\", TargetSpec.of(\n"
     "                new Requirement(Kind.MINION, Side.OPPONENT, 1, true, false, List.of(),",
     "        targetSpecs.put(\"QTE-M-EARTH-33\", TargetSpec.of(\n"
     "                new Requirement(Kind.MINION, Side.OPPONENT, 1, false, false, List.of(),",
     FE, "分那愚利は相手の場が空でも召喚できる"),

    ("仏恥義理が表向きでマナに置く", REG,
     "                ctx.actions().putHandCardIntoManaFaceDown(ctx.room(), ctx.owner(), idx);\n"
     "            }\n"
     "            // 勝鼓美",
     "                ctx.actions().placeHandCardIntoManaFaceUp(ctx.room(), ctx.owner(), idx);\n"
     "            }\n"
     "            // 勝鼓美",
     FE, "仏恥義理は1枚引いてから手札1枚を裏向きでマナに置く"),

    ("喧嘩上等が破壊でミニオンを取り除く(マナに置かない)", REG,
     "            ctx.targets().get(0).minions().forEach(t ->\n"
     "                    ctx.actions().putFieldMinionIntoManaFaceDown(ctx.room(), t.owner(), t.minion()));",
     "            ctx.targets().get(0).minions().forEach(t ->\n"
     "                    ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion()));",
     FE, "喧嘩上等はマナに置くだけなので破壊時は発動しない"),

    # ---------------------------------------------------------------
    # 印(EffectImplementation)
    # ---------------------------------------------------------------
    ("実装したのに印を残す(勝鼓美の登録を外す)", REG,
     "        register(\"QTE-M-EARTH-34\", TriggerType.ON_DESTROYED, ctx -> {",
     "        register(\"QTE-M-EARTH-34-DISABLED\", TriggerType.ON_DESTROYED, ctx -> {",
     EI, "土文明のVer11カード8枚には印が付かない"),

    ("送ったはずのカードを実装済みに見せる(勝阿外を空登録する)", REG,
     "    private void registerEarthVer11Cards() {",
     "    private void registerEarthVer11Cards() {\n"
     "        spellEffects.put(\"QTE-M-EARTH-36\", ctx -> {\n        });",
     EI, "Batch51が後続に送った2枚には今も印が付く"),
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
    print()
    for name in ("OK", "NG", "EMPTY", "SETUP-NG"):
        print("%s: %d" % (name, sum(1 for r in results if r[2] == name)))


if __name__ == "__main__":
    main()
