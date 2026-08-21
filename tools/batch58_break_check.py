#!/usr/bin/env python3
"""Batch 58(作り直し③ 区分5のうち裁定を要さない8枚)の壊し検証(裁定116)。

実装をわざと壊し、狙った試験が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った試験が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(試験が足りない)
  EMPTY    … その試験が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★照合は target/surefire-reports/*.xml に対して行う(55〜57 と同じ形)。
★★"surefire:test" 単体で回してはいけない(裁定208)。必ず "test" を回すこと。

★★<b>改変は「軸」ごとに1件ずつ当てる</b>(57 の教訓)。
  2つの誤りを1つの改変で同時に入れると、盤面によっては打ち消し合って落ちない。
  したがって《風弾の跳弾》のように3つ変わったカードには3件を別々に当てている。

★対象は区分5 の15枚のうち<b>8枚</b>である。裁定268〜274 待ちの7枚は実装していない。

★壊しどころが無い項目(意図的に含めていないもの):
  - 《剛火の将》の「起動能力を持たない」…… 登録が<b>無いこと</b>を測る試験であり、
    壊すには登録を足すことになる。それは実装の改変ではなく別のカードを作る行為である
    (番人は tools/check_leader_abilities.py が別途持っている)。
  - 《背水の烈火使い》の【守護】・《知恵の双翼》の【知識】【守護】…… キーワードは
    テキストから作られる(裁定158)。コード側に壊す分岐が存在しない。
  - 《剛火の将》の常在が<b>相手の場のミニオンにも書き込まれること</b>…… 加算量は
    場に出るすべてのミニオンへ無条件に写している。側で分ける分岐が構造として無いため、
    壊せる箇所が無い。壊せるのは「どちらのリーダーを数えるか」(ケース2)のほうである。

使い方: python3 tools/batch58_break_check.py [ケース番号...]
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
ACTIONS = SRC + "game/GameActions.java"
MINION = SRC + "game/MinionInstance.java"
PLAYER = SRC + "game/PlayerState.java"

T58 = "com.example.qte.Batch58ReworkTest"
TIMPL = "com.example.qte.EffectImplementationTest"

# (説明, ファイル, 置換前, 置換後, テストクラス, 落ちるべきテストメソッド)
CASES = [
    # ===============================================================
    # 火文明 — 剛火の将(常在のHP+2)
    # ===============================================================
    ("剛火の将の常在の加算量が2になっていない(0のまま)", STATS,
     "                bonus += 2;",
     "                bonus += 0;",
     T58, "剛火の将は速攻を持つミニオンのHPを2上げる"),

    ("剛火の将を数えるのが自分のリーダーだけになっている(両者を数えていない)", STATS,
     "        for (PlayerState side : new PlayerState[] { state.getPlayer1(), state.getPlayer2() }) {",
     "        for (PlayerState side : new PlayerState[] { state.getPlayer1() }) {",
     T58, "両者が剛火の将なら常在は累積する"),

    ("加算の条件が【速攻】ではなく【突進】になっている(HASTE と RUSH の取り違え)", MINION,
     "        if (rushHpBonus > 0 && hasKeyword(Keyword.HASTE)) {",
     "        if (rushHpBonus > 0 && hasKeyword(Keyword.RUSH)) {",
     T58, "剛火の将は速攻を持つミニオンのHPを2上げる"),

    ("場に出るミニオンへ常在の加算量が写されていない(召喚の経路だけが抜ける)", ACTIONS,
     "        minion.setRushHpBonus(stats.rushHpBonus(state));",
     "        minion.setRushHpBonus(0);",
     T58, "召喚時に速攻を得たミニオンにも剛火の将の常在が乗る"),

    ("剛火の将が StatCalculator の IMPLEMENTED_CARDS を名乗っていない", STATS,
     "            GALE_RAPIER, DREAMY, RENTA, MERINA, FIRE_GENERAL);",
     "            GALE_RAPIER, DREAMY, RENTA, MERINA);",
     TIMPL, "効果未実装のカードは1枚も無い"),

    # ===============================================================
    # 火文明 — 背水の烈火使い
    # ===============================================================
    ("背水の烈火使いの【召喚時】手札全捨てが残っている(Ver0.4 のまま)", REG,
     "        // ★Batch 58(区分5): 背水の烈火使い(QTE-M-FIRE-7)。",
     "        register(\"QTE-M-FIRE-7\", TriggerType.ON_SUMMON, ctx -> {\n"
     "            List<String> discarded = List.copyOf(ctx.owner().getHand());\n"
     "            ctx.owner().getHand().clear();\n"
     "            discarded.forEach(id -> ctx.actions()"
     ".putIntoTrashFromElsewhere(ctx.room(), ctx.owner(), id));\n"
     "        });\n"
     "        // ★Batch 58(区分5): 背水の烈火使い(QTE-M-FIRE-7)。",
     T58, "背水の烈火使いは召喚しても手札を捨てない"),

    # ===============================================================
    # 水文明 — 英知の継承者
    # ===============================================================
    ("英知の継承者のドローが4枚になっていない(3枚のまま)", REG,
     "            ctx.actions().drawCards(ctx.room(), ctx.owner(), 4);\n"
     "            int count = Math.min(3, ctx.owner().getHand().size());",
     "            ctx.actions().drawCards(ctx.room(), ctx.owner(), 3);\n"
     "            int count = Math.min(3, ctx.owner().getHand().size());",
     T58, "英知の継承者は4枚引いてから3枚捨てる"),

    ("英知の継承者の捨てる枚数が3枚になっていない(1枚のまま)", REG,
     "            int count = Math.min(3, ctx.owner().getHand().size());\n"
     "            requestDiscard(ctx, count, count, ResumePoint.WISDOM_HEIR_DISCARD,",
     "            int count = Math.min(1, ctx.owner().getHand().size());\n"
     "            requestDiscard(ctx, count, count, ResumePoint.WISDOM_HEIR_DISCARD,",
     T58, "英知の継承者は4枚引いてから3枚捨てる"),

    # ===============================================================
    # 水文明 — 知恵の双翼(★実装変更なしの代表として1件)
    # ===============================================================
    ("知恵の双翼の特殊召喚条件が2体になっていない(1体で通る)", REG,
     "                        .filter(m -> m.hasKeyword(Keyword.KNOWLEDGE)).count() >= 2,",
     "                        .filter(m -> m.hasKeyword(Keyword.KNOWLEDGE)).count() >= 1,",
     T58, "知恵の双翼は知識ミニオンが1体では特殊召喚できない"),

    # ===============================================================
    # 風文明 — ストーム・カイザー
    # ===============================================================
    ("ストーム・カイザーの条件が5枚に上がっていない(4枚のまま)", REG,
     "                (state, player, handIndex) -> player.getCardsUsedThisTurn() >= 5,\n"
     "                1,",
     "                (state, player, handIndex) -> player.getCardsUsedThisTurn() >= 4,\n"
     "                1,",
     T58, "ストームカイザーは4枚使用では特殊召喚できない"),

    ("ストーム・カイザーの代替コストが1になっていない(0のまま)", REG,
     "                (state, player, handIndex) -> player.getCardsUsedThisTurn() >= 5,\n"
     "                1,",
     "                (state, player, handIndex) -> player.getCardsUsedThisTurn() >= 5,\n"
     "                0,",
     T58, "ストームカイザーはマナが0なら特殊召喚できない"),

    # ===============================================================
    # 風文明 — 風弾の跳弾(3つの軸に3件)
    # ===============================================================
    ("風弾の跳弾が破壊ではなくバウンスのまま(Ver0.4 の挙動)", REG,
     "                ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion());\n"
     "                destroyed |= !t.owner().getMinionZone().contains(t.minion());",
     "                ctx.actions().bounceToHand(ctx.room(), t.owner(), t.minion());\n"
     "                destroyed |= !t.owner().getMinionZone().contains(t.minion());",
     T58, "風弾の跳弾は自分のミニオンを破壊して相手に3ダメージ与える"),

    ("風弾の跳弾のダメージが3になっていない(2のまま)", REG,
     "                    t -> ctx.actions().damageMinion(ctx.room(), t.owner(), t.minion(), 3));\n"
     "        });",
     "                    t -> ctx.actions().damageMinion(ctx.room(), t.owner(), t.minion(), 2));\n"
     "        });",
     T58, "風弾の跳弾は自分のミニオンを破壊して相手に3ダメージ与える"),

    ("風弾の跳弾の追加コストが+2に下がっていない(+3のまま)", REG,
     "        enhancedCosts.put(\"QTE-M-WIND-24\", new EnhancedCostSpec(2,",
     "        enhancedCosts.put(\"QTE-M-WIND-24\", new EnhancedCostSpec(3,",
     T58, "風弾の跳弾は強化使用で合計3マナになり手札に戻る"),

    # ===============================================================
    # 闇文明 — カース・ボーン
    # ===============================================================
    ("カース・ボーンのセルフミルが破壊したミニオンのコスト分になっていない(固定2枚)", REG,
     "        int millCount = printedCost == null ? 0 : printedCost;",
     "        int millCount = 2;",
     T58, "カースボーンは選んだ自分のミニオンを破壊しそのコスト分セルフミルする"),

    ("カース・ボーンの候補から自分自身が外れている", REG,
     "            List<MinionInstance> candidates = ctx.owner().getMinionZone();",
     "            List<MinionInstance> candidates = ctx.owner().getMinionZone().stream()\n"
     "                    .filter(m -> m != ctx.source()).toList();",
     T58, "カースボーンは他にミニオンが居なければ自分を破壊しマナへ還元される"),

    # ===============================================================
    # 土文明 — 地脈の覚醒
    # ===============================================================
    ("地脈の覚醒のターン1回制限が効いていない(毎回発動する)", REG,
     "            if (!ctx.owner().tryUseLeylineAwakening(turn)) {",
     "            if (false) {",
     T58, "地脈の覚醒は同じターンに2枚目を使っても効果は発動しない"),

    ("地脈の覚醒のターン1回制限がターンをまたいで解けない(1試合に1回になっている)", PLAYER,
     "        if (leylineAwakeningTurn == currentTurn) {",
     "        if (leylineAwakeningTurn >= 0) {",
     T58, "地脈の覚醒はターンが変われば再び発動できる"),

    ("地脈の覚醒が選んだマナではなく先頭のマナを手札に加えている", REG,
     "            case LEYLINE_AWAKENING_TO_HAND -> ctx.actions().returnManaToHandAt(\n"
     "                    ctx.room(), ctx.owner(), Integer.parseInt(chosen.get(0)));",
     "            case LEYLINE_AWAKENING_TO_HAND -> ctx.actions().returnManaToHandAt(\n"
     "                    ctx.room(), ctx.owner(), 0);",
     T58, "地脈の覚醒はマナから1枚を手札に加える"),
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
