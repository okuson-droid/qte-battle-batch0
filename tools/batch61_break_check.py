#!/usr/bin/env python3
"""Batch 61(カード一覧の統一)の壊し検証(裁定116)。

実装をわざと壊し、狙った試験が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った試験が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(試験が足りない)
  EMPTY    … その試験が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★照合は target/surefire-reports/*.xml に対して行う(55〜60 と同じ形)。
★★"surefire:test" 単体で回してはいけない(裁定208)。必ず "test" を回すこと。
★走らせる前に対象クラスの XML を消す(Batch 60 で見つけた穴。コンパイルが通らないと
  前回の結果が残り、「壊したのに落ちなかった」= NG と誤読する)。

★壊しどころが JUnit の外にある項目(意図的に含めていないもの):
  - 本文の改行を生かす(white-space: pre-wrap)…… CSS の効き目であり、
    測れるのは computed style である。番人は verify 側にある2件 ——
    「盤面のカード本文は改行を生かす」と「デッキメーカーのカード本文は改行を生かす」。
    ★<b>2件あるのは、宣言が2箇所にあるからである</b>(battle.css の .mcard-text と
    デッキメーカーの .t-text)。片方だけ直された日を捕まえるには両方に番人が要る。
  - カードフェイスの見た目そのもの …… 正は battle.css であり、
    61 は1行も足していない(3つ目のコピーを作らないことがこのバッチの主旨である)。

使い方: python3 tools/batch61_break_check.py [ケース番号...]
"""
import glob
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MVN = ["mvn", "-o", "-B", "-q", "-Dmaven.repo.local=/root/m2work/repository",
       "test", "-DfailIfNoTests=false"]

FRAGMENT = "src/main/resources/templates/fragments/card-face.html"
CARDS = "src/main/resources/cards/manual-cards.json"
MANUAL_REPO = "src/main/java/com/example/qte/manual/ManualCardRepository.java"

TPAGE = "com.example.qte.CardListPageTest"
TKW = "com.example.qte.CardTextKeywordsTest"

# (説明, ファイル, 置換前, 置換後, テストクラス, 落ちるべきテストメソッド)
CASES = [
    # ===============================================================
    # カード一覧の描画(Thymeleaf のフラグメント)
    # ===============================================================
    ("フラグメントで th:each と th:replace を同じタグに書いている", FRAGMENT,
     '    <th:block th:each="card : ${cards}">\n'
     '        <div th:replace="~{fragments/card-face :: cell(${card})}"></div>\n'
     '    </th:block>',
     '    <div th:each="card : ${cards}"\n'
     '         th:replace="~{fragments/card-face :: cell(${card})}"></div>',
     TPAGE, "通常モードのカード一覧はカードフェイスで235枚を出す"),

    ("カードフェイスに文明色の値を直書きしている(裁定60 違反)", FRAGMENT,
     "             th:style=\"'--mc: var(--civ-' + ${#strings.toLowerCase(card.civilization.name())} + ')'\">",
     "             th:style=\"'--mc: #2f6fb5'\">",
     TPAGE, "カード一覧は文明色を値ではなく変数名で持つ"),

    ("進化ミニオンの印が付いていない", FRAGMENT,
     "         th:classappend=\"${card.type.name() == 'EVOLUTION'} ? 'is-evolution' : ''\">",
     "         th:classappend=\"''\">",
     TPAGE, "進化ミニオンには印が付く"),

    # ===============================================================
    # 手動モードのカード定義が本文を運ばない(60 までの姿に戻す)
    # ===============================================================
    ("手動モードのカード定義が本文を落としている(60 までの姿)", MANUAL_REPO,
     '                    cost, attack, hp, text == null ? "" : text, imageId, ledgerCardId);',
     '                    cost, attack, hp, "", imageId, ledgerCardId);',
     TPAGE, "手動モードのカード一覧にも本文が出る"),

    # ===============================================================
    # 本文の表記の統一(61 で76枚を揃えた)
    # ===============================================================
    ("本文の丸括弧が半角に戻っている(《禁忌の冥魔剣》)", CARDS,
     "（このカードの効果はターンに5回までしか発動しない）",
     "(このカードの効果はターンに5回までしか発動しない)",
     TKW, "本文の丸括弧は全角に揃っている"),

    ("本文の数字が全角に戻っている(《蒼海の賢者》)", CARDS,
     "【起動：1】自分の手札を1枚デッキの一番下に戻す。",
     "【起動：１】自分の手札を1枚デッキの一番下に戻す。",
     TKW, "本文の数字は半角に揃っている"),

    ("行末の句点が落ちている(《タイダルウェーブ》)", CARDS,
     "相手の場にいるコスト4以下のミニオンすべてを持ち主の手札に戻す。",
     "相手の場にいるコスト4以下のミニオンすべてを持ち主の手札に戻す",
     TKW, "本文の各行は句点か閉じ記号で終わっている"),

    ("【】の直後に空白が戻っている(《苗木植えの精霊》)", CARDS,
     "【召喚時】自分の手札を1枚表向きでマナに置く。",
     "【召喚時】 自分の手札を1枚表向きでマナに置く。",
     TKW, "本文に余分な空白が無い"),
]

EXPECTED_NG = {}


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as fh:
        return fh.read()


def write(path, text):
    with open(os.path.join(ROOT, path), "w", encoding="utf-8") as fh:
        fh.write(text)


def run_class(test_class):
    # ★走らせる前に、そのクラスの前回の結果を必ず消す(Batch 60 の教訓)。
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
