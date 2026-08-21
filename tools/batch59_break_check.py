#!/usr/bin/env python3
"""Batch 59(作り直し④ = 裁定が付いた16枚)の壊し検証(裁定116)。

実装をわざと壊し、狙った試験が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った試験が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(試験が足りない)
  EMPTY    … その試験が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★照合は target/surefire-reports/*.xml に対して行う(55〜58 と同じ形)。
★★"surefire:test" 単体で回してはいけない(裁定208)。必ず "test" を回すこと。

★★<b>改変は「軸」ごとに1件ずつ当てる</b>(57 の教訓・58 でも遵守)。
  2つの誤りを1つの改変で同時に入れると、盤面によっては打ち消し合って落ちない。
  したがって《神風の大号令》のように3つの軸(破壊する・数だけ強化する・いるだけでよい)を
  持つカードには3件を別々に当てている。

★壊しどころが無い項目(意図的に含めていないもの):
  - 《フレア・ポーン》(裁定268)…… 登録が<b>無いこと</b>を測る試験であり、
    壊すには登録を足すことになる。それは実装の改変ではなく別のカードを作る行為である
    (Batch 58 の《剛火の将》の起動能力と同じ理由)。
  - 《ゾンストライカー》の構築特例の廃止(裁定267)…… 特例の宣言は
    {@code manual-cards.json} が持ち、Ver1.1 の定義にはその項目がそもそも無い。
    コード側に壊す分岐が存在しない(番人は本文を見る試験のほうが持つ)。
  - 《ガイル・フォックス》の「永続的に持つ」(裁定262)…… 恒久の付与は
    {@code grantKeyword} そのものであり、期限を持つ仕組みを経由していない。
    「期限を足す」改変は実装の破壊ではなく別の仕組みの新設になる。
  - 《痛撃の炎術師》の【知識】1ドロー(裁定261(a))…… キーワードはテキストから作られる
    (裁定158)。コード側に壊す分岐が存在しない。
  - 《悪夢》の封じの<b>範囲</b>(自分だけか両者か。裁定265(a))…… 本物の入口からは
    観測できない。【召喚時】が起きるのは召喚した瞬間だけで、召喚できるのはターンプレイヤーだけ、
    そして印({@code thisTurnAuras})はターン終了時に消える —— つまり
    「相手が印を持ったまま自分が召喚する」盤面が構造的に存在しない。
    《英霊・コレキ》の「相手のターン中は止めない」(53 設計解説 6-2)と同じ立場のものである。
    ★<b>観測できる持続の軸(このターンの間)のほうをケース9で測っている。</b>

使い方: python3 tools/batch59_break_check.py [ケース番号...]
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

T59 = "com.example.qte.Batch59ReworkTest"
T58 = "com.example.qte.Batch58ReworkTest"

# (説明, ファイル, 置換前, 置換後, テストクラス, 落ちるべきテストメソッド)
CASES = [
    # ===============================================================
    # 260. 突風の祝福 —— 単体のままであること
    # ===============================================================
    ("突風の祝福が全体化している(選ぶ数が1になっていない)", REG,
     '        targetSpecs.put("QTE-M-WIND-27", TargetSpec.of(new Requirement(Kind.MINION, Side.SELF, 1, false, false,',
     '        targetSpecs.put("QTE-M-WIND-27", TargetSpec.of(new Requirement(Kind.MINION, Side.SELF, 2, false, false,',
     T59, "突風の祝福は対象を1体だけ要求する"),

    # ===============================================================
    # 261. 痛撃の炎術師 —— 誘発が ON_ENTER であること
    # ===============================================================
    ("痛撃の炎術師の誘発が【召喚時】のまま(ON_ENTER になっていない)", REG,
     '        register("QTE-M-FIRE-18", TriggerType.ON_ENTER, ctx -> {',
     '        register("QTE-M-FIRE-18", TriggerType.ON_SUMMON, ctx -> {',
     T59, "痛撃の炎術師は効果で場に出しても発動する"),

    # ===============================================================
    # 262. ガイル・フォックス —— 2つの経路の閾値(軸ごとに1件)
    # ===============================================================
    ("ガイル・フォックスの召喚経路の閾値がずれている(自身を数えていない)", REG,
     "            if (ctx.owner().getCardsUsedThisTurn() >= 2) {\n"
     "                grantGaleFoxStealth(ctx);",
     "            if (ctx.owner().getCardsUsedThisTurn() >= 3) {\n"
     "                grantGaleFoxStealth(ctx);",
     T59, "ガイルフォックスは3枚目として召喚すると潜伏を得る"),

    ("ガイル・フォックスの登場経路の閾値がずれている(自身を余分に数えている)", REG,
     "            if (ctx.owner().getCardsUsedThisTurn() >= 3) {\n"
     "                grantGaleFoxStealth(ctx);",
     "            if (ctx.owner().getCardsUsedThisTurn() >= 2) {\n"
     "                grantGaleFoxStealth(ctx);",
     T59, "ガイルフォックスは効果で場に出た場合に使用2枚では潜伏を得ない"),

    # ===============================================================
    # 263. 創世神 ガイア —— マナ最大値 = 今の枚数
    # ===============================================================
    ("創世神ガイアの特殊召喚条件が10枚になっていない", REG,
     "                (state, player, handIndex) -> player.getManaZone().size() >= 10,",
     "                (state, player, handIndex) -> player.getManaZone().size() >= 9,",
     T59, "創世神ガイアはマナが9枚では特殊召喚できない"),

    # ===============================================================
    # 264. 禁忌の冥魔剣 —— 回数と、毎ターンのリセット(軸ごとに1件)
    # ===============================================================
    ("禁忌の冥魔剣の上限が5回になっていない", REG,
     "    private static final int MEIMA_SWORD_USES_PER_TURN = 5;",
     "    private static final int MEIMA_SWORD_USES_PER_TURN = 6;",
     T59, "禁忌の冥魔剣はターンに5回までしか発動しない"),

    ("禁忌の冥魔剣の回数がターンごとにリセットされない(ターン番号を刻んでいない)", REG,
     '            if (!ctx.owner().tryUseTurnLimited("QTE-M-DARK-14", turn, MEIMA_SWORD_USES_PER_TURN)) {',
     '            if (!ctx.owner().tryUseTurnLimited("QTE-M-DARK-14", 0, MEIMA_SWORD_USES_PER_TURN)) {',
     T59, "禁忌の冥魔剣はターンが変われば再び5回発動できる"),

    # ===============================================================
    # 265. 悪夢 —— 封じの有無・範囲・【知識】との区別(軸ごとに1件)
    # ===============================================================
    ("悪夢が【召喚時】封じの印を張っていない", REG,
     "            ctx.owner().getThisTurnAuras().add(NIGHTMARE_SUMMON_LOCK);",
     "            ctx.owner().getThisTurnAuras().add(NIGHTMARE_SUMMON_LOCK + \"#DISABLED\");",
     T59, "悪夢を使ったターンは自分の召喚時が発動しない"),

    ("悪夢の封じがターンをまたいで残る(「このターンの間」になっていない)", SERVICE,
     "            p.getThisTurnAuras().clear();",
     "            p.getThisTurnAuras().removeIf(aura -> false);",
     T59, "悪夢の召喚時封じは次のターンには残らない"),

    ("悪夢の封じが登場時(ON_ENTER)まで止めている(【知識】まで巻き込む)", REG,
     "        if (trigger == TriggerType.ON_SUMMON\n"
     "                && ctx.owner().getThisTurnAuras().contains(NIGHTMARE_SUMMON_LOCK)) {",
     "        if (trigger == TriggerType.ON_ENTER\n"
     "                && ctx.owner().getThisTurnAuras().contains(NIGHTMARE_SUMMON_LOCK)) {",
     T59, "悪夢は知識のドローを止めない"),

    # ===============================================================
    # 266. ボーン・コレクター —— 誘発が経路を問わないこと
    # ===============================================================
    ("ボーン・コレクターが戦闘破壊でしか引かない(「戦闘で」が残っている)", REG,
     '        register("QTE-M-DARK-6", TriggerType.ON_DESTROYED,',
     '        register("QTE-M-DARK-6", TriggerType.ON_DESTROYED_BY_COMBAT,',
     T59, "ボーンコレクターは効果で破壊されても1枚引く"),

    # ===============================================================
    # 267. ゾンストライカー —— 破壊時のセルフミル
    # ===============================================================
    ("ゾンストライカーの破壊時セルフミルが効いていない", REG,
     '        register("QTE-M-DARK-16", TriggerType.ON_DESTROYED,\n'
     "                ctx -> ctx.actions().mill(ctx.room(), ctx.owner(), 1));",
     '        register("QTE-M-DARK-16", TriggerType.ON_DESTROYED,\n'
     "                ctx -> ctx.actions().mill(ctx.room(), ctx.owner(), 0));",
     T59, "ゾンストライカーは破壊されると山札の上から1枚墓地に置く"),

    # ===============================================================
    # 269. 神風の大号令 —— 破壊する・数だけ強化する・いるだけでよい(軸ごとに1件)
    # ===============================================================
    ("神風の大号令が選んだミニオンを破壊していない", REG,
     "                ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion());\n"
     "                destroyed++;",
     "                destroyed++;",
     T59, "神風の大号令は2体破壊して残りを2上げる"),

    ("神風の大号令の強化量が破壊した数になっていない(常に1)", REG,
     "                m.addModifier(new StatModifier(StatModifier.Stat.ATTACK, StatModifier.Operation.ADD, destroyed,\n"
     '                        StatModifier.Duration.THIS_TURN, "QTE-M-WIND-12"));',
     "                m.addModifier(new StatModifier(StatModifier.Stat.ATTACK, StatModifier.Operation.ADD, 1,\n"
     '                        StatModifier.Duration.THIS_TURN, "QTE-M-WIND-12"));',
     T59, "神風の大号令は2体破壊して残りを2上げる"),

    ("神風の大号令が2体ちょうどを要求している(いるだけ破壊にならない)", REG,
     '                Requirement.upTo(Kind.MINION, Side.SELF, 2, "破壊する自分のミニオンを選んでください(最大2体)")));',
     '                new Requirement(Kind.MINION, Side.SELF, 2, false, false, List.of(),\n'
     '                        "破壊する自分のミニオンを2体選んでください")));',
     T59, "神風の大号令は自分のミニオンが1体でも使用できる"),

    # ===============================================================
    # 270. 英知の水晶 —— 向き・停止性(軸ごとに1件)
    # ===============================================================
    ("英知の水晶が自分のドローに反応している(向きが逆)", ACTIONS,
     "        PlayerState watcher = state.opponentOf(drawer.getPlayerId());",
     "        PlayerState watcher = drawer;",
     T59, "英知の水晶は自分が引いても反応しない"),

    ("英知の水晶の再入ガードが無い(誘発によるドローが再び誘発する)", ACTIONS,
     "        if (drawn <= 0 || firingOpponentDrawWatchers || state.getStatus() != GameStatus.PLAYING) {",
     "        if (drawn <= 0 || state.getStatus() != GameStatus.PLAYING) {",
     T59, "英知の水晶は両者が場に出していても無限に往復しない"),

    # ===============================================================
    # 271. 創世神 ゾディアックアイリス —— 読むのは現在の体力
    # ===============================================================
    ("ゾディアックアイリスが最大体力を読んでいる(現在の体力ではない)", REG,
     "            int amount = ctx.source().getCurrentHp();",
     "            int amount = ctx.source().getMaxHp();",
     T59, "ゾディアックアイリスの回復量はダメージを受けた分だけ減る"),

    # ===============================================================
    # 272. 大天使ミカエル —— 戦闘だけを止める(軸ごとに1件)
    # ===============================================================
    ("ミカエルが戦闘ダメージを受けてしまう(置換が効いていない)", GUARDS,
     "    public boolean preventsCombatDamage(MinionInstance minion) {\n"
     "        return MICHAEL.equals(minion.getMaster().id());",
     "    public boolean preventsCombatDamage(MinionInstance minion) {\n"
     "        return false && MICHAEL.equals(minion.getMaster().id());",
     T59, "ミカエルは戦闘でダメージを受けない"),

    ("ミカエルが効果ダメージまで無効にしている(「戦闘時」の限定が消えている)", ACTIONS,
     "    public void damageMinion(GameRoom room, PlayerState owner, MinionInstance minion, int amount) {\n"
     "        applyDamageToMinion(room, owner, minion, amount);",
     "    public void damageMinion(GameRoom room, PlayerState owner, MinionInstance minion, int amount) {\n"
     "        if (guards.preventsCombatDamage(minion)) {\n"
     "            return;\n"
     "        }\n"
     "        applyDamageToMinion(room, owner, minion, amount);",
     T59, "ミカエルは効果ダメージは受ける"),

    # ===============================================================
    # 273. マナを貪る怨霊 —— 枚数・ドロー量・文明(軸ごとに1件)
    # ===============================================================
    ("マナを貪る怨霊が置く枚数が2枚になっていない", REG,
     "                    .filter(id -> cards.findById(id).civilization() == Civilization.DARK)\n"
     "                    .limit(2)",
     "                    .filter(id -> cards.findById(id).civilization() == Civilization.DARK)\n"
     "                    .limit(1)",
     T59, "マナを貪る怨霊は墓地の闇2枚をマナに置いて2枚引く"),

    ("マナを貪る怨霊の引く枚数が「置いた枚数」になっていない(常に1枚)", REG,
     "            ctx.actions().drawCards(ctx.room(), ctx.owner(), placed);",
     "            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);",
     T59, "マナを貪る怨霊は墓地の闇2枚をマナに置いて2枚引く"),

    ("マナを貪る怨霊が闇文明に絞っていない", REG,
     "                    .filter(id -> cards.findById(id).civilization() == Civilization.DARK)\n"
     "                    .limit(2)",
     "                    .limit(2)",
     T59, "マナを貪る怨霊は墓地の闇が1枚なら1枚だけ引く"),

    # ===============================================================
    # 274. 地響きの槌 —— 巻き込む範囲・マナ加速の数(軸ごとに1件)
    # ===============================================================
    ("地響きの槌が相手のミニオンしか巻き込まない(「相手の」が残っている)", SERVICE,
     "        for (PlayerState side : List.of(player, opponent)) {\n"
     "            for (MinionInstance minion : List.copyOf(side.getMinionZone())) {\n"
     "                if (!side.getMinionZone().contains(minion)) {",
     "        for (PlayerState side : List.of(opponent)) {\n"
     "            for (MinionInstance minion : List.copyOf(side.getMinionZone())) {\n"
     "                if (!side.getMinionZone().contains(minion)) {",
     T59, "地響きの槌は自分のミニオンも巻き込んで破壊数だけマナを増やす"),

    ("地響きの槌のマナ加速が破壊した数になっていない(常に1枚)", SERVICE,
     "        for (int i = 0; i < destroyed; i++) {\n"
     "            if (!actions.placeTopOfDeckInManaFaceDown(room, player)) {",
     "        for (int i = 0; i < 1; i++) {\n"
     "            if (!actions.placeTopOfDeckInManaFaceDown(room, player)) {",
     T59, "地響きの槌は自分のミニオンも巻き込んで破壊数だけマナを増やす"),

    # ===============================================================
    # 275. 黄泉の召喚主 —— ガードが恒久のルールであること
    # ===============================================================
    ("黄泉の召喚主のガードが外れている(対象を読む【召喚時】で 500 に落ちる)", SERVICE,
     "        if (!effects.targetSpecOf(master.id()).requirements().isEmpty()) {",
     "        if (false && !effects.targetSpecOf(master.id()).requirements().isEmpty()) {",
     T59, "黄泉の召喚主は召喚時に対象を選ぶミニオンを墓地から召喚できない"),

    # ===============================================================
    # 276. 地脈の覚醒 —— 2枚目は【還元】もしない(★58 の試験を書き換えた分)
    # ===============================================================
    ("地脈の覚醒の2枚目が【還元】してしまう(裁定276 の上書きが効いていない)", REG,
     "                ctx.owner().setPendingSpellDisposition(SpellDisposition.TO_TRASH);",
     "                ctx.owner().setPendingSpellDisposition(null);",
     T58, "地脈の覚醒は同じターンに2枚目を使っても効果は発動しない"),

    ("地脈の覚醒の上限が1回になっていない", REG,
     "    private static final int LEYLINE_AWAKENING_USES_PER_TURN = 1;",
     "    private static final int LEYLINE_AWAKENING_USES_PER_TURN = 2;",
     T58, "地脈の覚醒は同じターンに2枚目を使っても効果は発動しない"),
]

# 壊しても落ちないことに理由があるもの(裁定196 の4値のうち NG を明示的に許すのはここだけ)。
EXPECTED_NG = {}


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
