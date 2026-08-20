#!/usr/bin/env python3
"""Batch 55 の壊し検証(裁定116)。

実装をわざと壊し、狙った試験が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った試験が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(試験が足りない)
  EMPTY    … その試験が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★照合は target/surefire-reports/*.xml に対して行う(54 と同じ形)。
★★"surefire:test" 単体で回してはいけない(裁定208)。必ず "test" を回すこと。

★配分の方針(55 の運用ルール)。55 が新しく作ったのは
  <b>4件の食い違いの修正</b>(コスト0→1×3・剛火の将の死んだ登録削除)と
  <b>区分3aのうち構造が変わった2枚</b>(アクア・サーチの複数選択・冥府への道の2体要求)、
  <b>区分1・2の証明</b>(威圧・貫通が実戦闘で効くこと)である。そこに厚く当てる。
  単純な定数の書き換え(命を削る烈火の3ダメージ等)は数値を読めば正しさが分かるため、
  代表として1枚だけを壊し検証に含める。

★リーダー起動能力のコスト照合(tools/check_leader_abilities.py)は JUnit ではなく
  独立した Python 番人なので、この壊し検証の枠組み(JUnit XML を見る)には乗らない。
  最後の check_leader_ability_guard() で別立てに検証する。

使い方: python3 tools/batch55_break_check.py [ケース番号...]
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
SERVICE = SRC + "game/GameService.java"

T55 = "com.example.qte.Batch55TriageTest"
EI = "com.example.qte.EffectImplementationTest"

# (説明, ファイル, 置換前, 置換後, テストクラス, 落ちるべきテストメソッド)
CASES = [
    # ---------------------------------------------------------------
    # 工事1: アクア・サーチの複数選択(区分3a: 捨て 1→2枚)
    # ---------------------------------------------------------------
    ("アクア・サーチの捨て枚数が2枚に増えていない(1枚のまま)", REG,
     "            int discardCount = Math.min(2, handPositions.size());",
     "            int discardCount = Math.min(1, handPositions.size());",
     T55, "アクアサーチは2枚引いて2枚捨てる"),

    # ---------------------------------------------------------------
    # 工事2: 冥府への道の2体要求(区分3a: 破壊 1体→2体)
    # ---------------------------------------------------------------
    ("冥府への道の使用条件が1体のままになっている", REG,
     "                (state, player) -> state.opponentOf(player.getPlayerId()).getMinionZone().size() >= 2);",
     "                (state, player) -> !state.opponentOf(player.getPlayerId()).getMinionZone().isEmpty());",
     T55, "冥府への道は相手のミニオンが1体だと使用できない"),

    ("冥府への道の要求数が2体に増えていない(1体のまま)", REG,
     "                new Requirement(Kind.MINION, Side.OPPONENT, 2, false, false, List.of(),\n"
     "                        \"破壊する相手のミニオンを2体選んでください\")));",
     "                new Requirement(Kind.MINION, Side.OPPONENT, 1, false, false, List.of(),\n"
     "                        \"破壊する相手のミニオンを2体選んでください\")));",
     T55, "冥府への道は相手のミニオンを2体破壊する"),

    # ---------------------------------------------------------------
    # 工事3: 区分3a の数値差し替え(代表1枚。残りは単純な定数なのでコードで自明)
    # ---------------------------------------------------------------
    ("命を削る烈火の全体ダメージが3に増えていない(2のまま)", REG,
     "                    m -> ctx.actions().damageMinion(ctx.room(), ctx.opponent(), m, 3));",
     "                    m -> ctx.actions().damageMinion(ctx.room(), ctx.opponent(), m, 2));",
     T55, "命を削る烈火は相手の場全体に3ダメージ"),

    # ---------------------------------------------------------------
    # 工事4: 区分1・2の証明(威圧・貫通が実戦闘で効くこと)
    # ---------------------------------------------------------------
    ("【威圧】判定が働かない(ディープシー・シャークを攻撃対象にできてしまう)", SERVICE,
     "        if (target != null && target.hasKeyword(Keyword.INTIMIDATE)) {\n"
     "            throw new IllegalStateException(\"【威圧】持ちは攻撃対象にできません\");\n"
     "        }\n"
     "        boolean opponentHasGuard = opponent.getMinionZone().stream()\n"
     "                .anyMatch(m -> m.hasKeyword(Keyword.GUARD));\n"
     "        boolean targetIsGuard = target != null && target.hasKeyword(Keyword.GUARD);\n"
     "        if (opponentHasGuard && !targetIsGuard && !attacker.hasKeyword(Keyword.PIERCE)) {",
     "        if (false) {\n"
     "            throw new IllegalStateException(\"【威圧】持ちは攻撃対象にできません\");\n"
     "        }\n"
     "        boolean opponentHasGuard = opponent.getMinionZone().stream()\n"
     "                .anyMatch(m -> m.hasKeyword(Keyword.GUARD));\n"
     "        boolean targetIsGuard = target != null && target.hasKeyword(Keyword.GUARD);\n"
     "        if (opponentHasGuard && !targetIsGuard && !attacker.hasKeyword(Keyword.PIERCE)) {",
     T55, "威圧持ちのディープシーシャークは攻撃対象にできない"),

    ("【貫通】判定が働かない(急流の狙撃手が守護を無視できない)", SERVICE,
     "        if (opponentHasGuard && !targetIsGuard && !attacker.hasKeyword(Keyword.PIERCE)) {\n"
     "            throw new IllegalStateException(\"相手の【守護】持ちを先に攻撃する必要があります\");\n"
     "        }\n"
     "    }\n"
     "\n"
     "    // ---------------------------------------------------------------\n"
     "    // 内部ヘルパー",
     "        if (opponentHasGuard && !targetIsGuard) {\n"
     "            throw new IllegalStateException(\"相手の【守護】持ちを先に攻撃する必要があります\");\n"
     "        }\n"
     "    }\n"
     "\n"
     "    // ---------------------------------------------------------------\n"
     "    // 内部ヘルパー",
     T55, "貫通持ちの急流の狙撃手は相手の守護を無視してリーダーを攻撃できる"),

    # ---------------------------------------------------------------
    # 工事5: 剛火の将の死んだ登録を消したこと(2章の食い違い4件目)
    # ---------------------------------------------------------------
    ("剛火の将の死んだ起動能力登録が復活している", REG,
     "        // ★常在効果(HP+2)の新規実装と、pendingFireMinionDiscount 関連の死んだコード\n"
     "        // (PlayerState / GameService / StatCalculator に残る)の掃除は Batch 57(区分5)の範囲。\n"
     "    }",
     "        // ★常在効果(HP+2)の新規実装と、pendingFireMinionDiscount 関連の死んだコード\n"
     "        // (PlayerState / GameService / StatCalculator に残る)の掃除は Batch 57(区分5)の範囲。\n"
     "        leaderAbilities.put(\"QTE-M-FIRE-1\", LeaderAbilitySpec.of(0, TargetSpec.of(),\n"
     "                ctx -> ctx.actions().damageLeader(ctx.room(), ctx.owner(), 2, \"QTE-M-FIRE-1\"),\n"
     "                \"壊し検証用の復元\"));\n"
     "    }",
     EI, "効果未実装のカードは剛火の将だけである"),
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


def check_leader_ability_guard():
    """★check_leader_abilities.py 自体が食い違いを検出できるかの壊し検証(JUnitとは別枠)。

    蒼海の賢者(QTE-M-WATER-1)のコストを一時的に0へ戻し、
    番人スクリプトが NG(exit 1)を返すことを確認する。
    """
    path = REG
    before = "leaderAbilities.put(\"QTE-M-WATER-1\", LeaderAbilitySpec.of(1,"
    after = "leaderAbilities.put(\"QTE-M-WATER-1\", LeaderAbilitySpec.of(0,"
    original = read(path)
    hits = original.count(before)
    if hits != 1:
        print("check_leader_abilities.py 壊し検証: SETUP-NG(一致 %d 箇所)" % hits)
        return "SETUP-NG"
    write(path, original.replace(before, after))
    try:
        result = subprocess.run(
            ["python3", "tools/check_leader_abilities.py"],
            cwd=ROOT, capture_output=True)
        answer = "OK" if result.returncode != 0 else "NG"
    finally:
        write(path, original)
    print("check_leader_abilities.py 壊し検証: %s(蒼海の賢者のコストを0へ戻すと検出するか)" % answer)
    return answer


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

    guard_answer = check_leader_ability_guard()
    results.append((len(CASES) + 1, "check_leader_abilities.py 自体の検出力", guard_answer, "exit code"))

    counts = {}
    for _, _, answer, _ in results:
        counts[answer] = counts.get(answer, 0) + 1
    print("\n" + " / ".join("%s %d" % (k, counts[k]) for k in sorted(counts)))
    return 0 if counts.get("OK", 0) == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
