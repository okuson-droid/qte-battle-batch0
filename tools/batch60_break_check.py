#!/usr/bin/env python3
"""Batch 60(P6 仕上げ)の壊し検証(裁定116)。

実装をわざと壊し、狙った試験が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った試験が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(試験が足りない)
  EMPTY    … その試験が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★照合は target/surefire-reports/*.xml に対して行う(55〜59 と同じ形)。
★★"surefire:test" 単体で回してはいけない(裁定208)。必ず "test" を回すこと。

★★改変は「軸」ごとに1件ずつ当てる(57 の教訓・58・59 でも遵守)。

★壊しどころが無い項目(意図的に含めていないもの):
  - 裁定277(《神風の大号令》は少なく選んでもよい)…… 「許す」側の裁定であり、
    実装は upTo のまま0行である。upTo を false にする改変はケース8で当てているが、
    それが測っているのは「固定要求にすると1体しか居ない側が使えなくなる」ことのほうである。
  - 裁定279(《英知の水晶》の再入ガード)…… Batch 59 の実装のままであり、
    番人も 59 側にある(tools/batch59_break_check.py が同じ軸を持っている)。
  - unlimitedCopies の撤去 …… 消したのは「必ず偽になる分岐」である。
    壊すには分岐を復活させることになり、それは実装の破壊ではなく別の仕組みの新設である
    (Batch 59 の《ゾンストライカー》の構築特例と同じ理由)。
  - qte-cards.json の削除そのもの …… ファイルが在るか無いかは試験で測れない。
    測れるのは「番人が今も生きているか」であり、それをケース7で当てている。
  - 《海淵獣ゾクシム》の素材(水文明ではないミニオン)…… 素材の述語を書き換えると
    「素材が居ない」ではなく「別のカードになる」。プリセット側を削るケース9 のほうが、
    測りたいこと(進化を入れたら素材も要る)に当たっている。

使い方: python3 tools/batch60_break_check.py [ケース番号...]
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
ACTIONS = SRC + "game/GameActions.java"
SERVICE = SRC + "game/GameService.java"
DECKS = SRC + "game/DeckFactory.java"
IMPORTER = SRC + "manual/ManualDeckImporter.java"
KEYWORDS = SRC + "master/CardTextKeywords.java"

T60 = "com.example.qte.Batch60Test"
TJSON = "com.example.qte.ManualDeckJsonImportTest"
TKW = "com.example.qte.CardTextKeywordsTest"

# (説明, ファイル, 置換前, 置換後, テストクラス, 落ちるべきテストメソッド)
CASES = [
    # ===============================================================
    # 278(c). 墓地からの召喚に対象選択の導線を新設した
    # ===============================================================
    ("墓地からの召喚が対象を場に運んでいない(summonToField に null を渡している)", SERVICE,
     '        room.addLog("%sが墓地から【%s】を召喚".formatted(player.getDisplayName(), master.name()));\n'
     "        MinionInstance summoned = summonToField(room, state, player, master, resolved, false);",
     '        room.addLog("%sが墓地から【%s】を召喚".formatted(player.getDisplayName(), master.name()));\n'
     "        MinionInstance summoned = summonToField(room, state, player, master, null, false);",
     T60, "黄泉の召喚主は召喚時に対象を選ぶミニオンを墓地から召喚できる"),

    ("墓地からの召喚の順序が逆(検証より先に支払って墓地から取り除いている)", SERVICE,
     "        ValidatedTargets validated = validateTargets(state, player, -1, spec, choices);\n"
     "        payCost(player, stats.effectiveCost(state, player, master));\n"
     "        ResolvedTargets resolved = removePlayedAndTargets(player, -1, validated);\n"
     "        player.getTrash().remove(trashIndex);",
     "        payCost(player, stats.effectiveCost(state, player, master));\n"
     "        player.getTrash().remove(trashIndex);\n"
     "        ValidatedTargets validated = validateTargets(state, player, -1, spec, choices);\n"
     "        ResolvedTargets resolved = removePlayedAndTargets(player, -1, validated);",
     T60, "黄泉の召喚主は対象の検証で弾かれたとき盤面を1つも変えない"),

    ("墓地から出すカード自身を対象に選べてしまう(自己除外の門が効いていない)", SERVICE,
     "            if (choices.get(i).trashIndexes().contains(trashIndex)) {",
     "            if (choices.get(i).trashIndexes().contains(-1)) {",
     T60, "墓地から出すカード自身は対象に選べない"),

    # ===============================================================
    # 裏向きマナと fireManaPlaced の非対称(51 設計解説 6-2 の積み残し)
    # ===============================================================
    ("裏向きの配置が「置かれた」を知らせていない(59 までの非対称のまま)", ACTIONS,
     "        owner.getManaZone().add(mana);\n"
     "        manaPlaced(room, owner);\n"
     "    }",
     "        owner.getManaZone().add(mana);\n"
     "    }",
     T60, "豊穣の地霊主は還元による裏向きの配置も2回目として数える"),

    ("ピュア・エレメントの一時マナが「置かれた」を知らせていない", SERVICE,
     "        actions.manaPlaced(room, player);",
     "        // actions.manaPlaced(room, player);",
     T60, "豊穣の地霊主はピュアエレメントの一時マナも1回として数える"),

    ("豊穣の地霊主が1回目から引いてしまう(2回目という条件が緩い)", REG,
     "                && owner.getCardsPutToManaThisTurn() == 2) {",
     "                && owner.getCardsPutToManaThisTurn() >= 1) {",
     T60, "豊穣の地霊主は表向き1回だけでは引かない"),

    # ===============================================================
    # 台帳の削除 —— 番人(キーワード抽出の169枚照合)が今も生きているか
    # ===============================================================
    ("キーワード抽出の「条件付きの付与」の判定が緩い(なら を見ていない)", KEYWORDS,
     'Pattern.compile("(なら|たら|とき|時|場合)[\\\\s、,]*$")',
     'Pattern.compile("(たら|とき|時|場合)[\\\\s、,]*$")',
     TKW, "物差しと対応づく169枚のうち食い違うのは既知の9枚だけである"),

    # ===============================================================
    # 裁定277 の裏返し —— 固定要求にすると使えなくなる側
    # ===============================================================
    ("神風の大号令の対象要求が固定2体になっている(少なく選べない)", REG,
     '                Requirement.upTo(Kind.MINION, Side.SELF, 2, "破壊する自分のミニオンを選んでください(最大2体)")',
     '                Requirement.of(Kind.MINION, Side.SELF, 2, false, "破壊する自分のミニオンを選んでください(最大2体)")',
     T60, "神風の大号令は2体いても1体だけ選べる"),

    # ===============================================================
    # プリセットデッキの Ver1.1 化
    # ===============================================================
    ("プリセットから Ver1.1 の新カードが1枚抜けている(枚数は40のまま)", DECKS,
     '        WIND_STARTER.put("QTE-M-WIND-38", 1); // 暴レ狂ウ・オニ 5/2/2 召喚時:味方を全破壊し数だけ相手全体へ+リーダー1\n'
     '        WIND_STARTER.put("QTE-M-WIND-39", 1); // 天翔ケル霊鬼・シュテン 8/4/2 速攻・特殊召喚(8体破壊で1)',
     '        WIND_STARTER.put("QTE-M-WIND-38", 2); // 暴レ狂ウ・オニ(★壊し検証: 39 を落として枚数だけ合わせた)',
     T60, "プリセットは6文明とも新カード10種をすべて積んでいる"),

    # ===============================================================
    # unlimitedCopies の撤去に伴う、同名上限の検証(手動モードの取り込み)
    # ===============================================================
    ("手動モードの同名上限の警告が1枚ぶん甘い", IMPORTER,
     "            if (count.getValue() > limit) {",
     "            if (count.getValue() > limit + 1) {",
     TJSON, "規定枚数違反と文明違反は警告に留まり読み込みは成立する"),
]

EXPECTED_NG = {}


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as fh:
        return fh.read()


def write(path, text):
    with open(os.path.join(ROOT, path), "w", encoding="utf-8") as fh:
        fh.write(text)


def run_class(test_class):
    # ★Batch 60: 走らせる前に、そのクラスの前回の結果を必ず消す。
    #   消さないとコンパイルが通らなかったときに前回の XML がそのまま残り、
    #   「壊したのに落ちなかった」= NG に見える。実際には改変が当たってすらいない。
    #   59 までの版はここが抜けていて、Java の書き間違いを NG と誤読する余地があった。
    path = os.path.join(ROOT, "target/surefire-reports/TEST-%s.xml" % test_class)
    if os.path.exists(path):
        os.remove(path)
    subprocess.run(MVN + ["-Dtest=" + test_class.split(".")[-1]],
                   cwd=ROOT, capture_output=True)
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
