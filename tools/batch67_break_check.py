#!/usr/bin/env python3
"""Batch 67(本文と実装の総点検)の壊し検証(裁定116)。

実装をわざと壊し、狙った番人が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った番人が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(番人が足りない)
  EMPTY    … その番人が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★★<b>67 の照合先は3層である。</b>JUnit(直した5枚と台帳)・verify(Filter の一致)に加えて、
  <b>tools のスクリプトそのもの</b>が3つ目の照合先になる ——
  このバッチの成果物には {@code check_card_text_numbers.py} と
  {@code mark_text_reviewed.py --check} という<b>機械の番人が2本</b>あり、
  それらが仕事をしているかも壊して確かめなければ意味がない。
  「照合先はそのバッチが触った層に居る」という決め方は 63〜66 から変えていない。

★★改変は「軸」ごとに1件ずつ当てる(57 の教訓)。軸は次の10である。
  (1) 《大地震》のコスト上限        (2) 《聖剣 エクスカリバー》の回復量
  (3) 《生贄を求める邪鬼》の破壊    (4) 《禁忌の墓地利用》の絞り込み
  (5) 「ミニオンでない」の定義      (6) 《ツイン・ストライク》の文明の絞り込み
  (7) 光文明の踏み倒しの種別        (8) 突き合わせ台帳のハッシュ
  (9) Filter の Java と JS の一致  (10) 本文の数値と実装の照合

★<b>軸1 と 軸8 は、同じ改変を2つの番人に当てている。</b>ケース数は増えるが軸は1つである ——
  57 の教訓が戒めているのは「1つの改変で2つの誤りを同時に戻すこと」であって、
  「1つの改変で2つの番人を試すこと」ではない。むしろ
  <b>JUnit とツールのどちらが仕事をしていないか</b>を見分けられる形になる。

★★<b>裁定304 の罠</b>: Java で「条件を落とす」改変をすると、その先が到達不能になって
  <b>コンパイルが通らず</b> EMPTY になる(64 で踏んだ)。JavaScript には同じ壁が無く、
  黙って別物が動く(65)。どちらも避けるため、67 の改変は
  <b>文を消して後続を宙に浮かせる形を1つも使っていない</b> ——
  条件を {@code false &&} で包むか、式ごと置き換えるかのどちらかである。

★★★<b>壊しどころが無い項目</b>(意図的に含めていないもの・裁定196 の正直な扱い):

  - <b>{@code mark_text_reviewed.py} が --note を必須にしていること</b> ……
    これは<b>ツールの引数検証</b>であり、落ちる先の試験が無い。
    「理由を書かずに台帳を緑にできない」を守っているのは argparse の1行であって、
    番人ではない。★<b>ここは正直に書き残しておく</b> ——
    将来この必須を外した人を止めるものは、この文章しか無い。

  - <b>台帳の {@code reviewedIn} が「いつ突き合わせたか」を正しく言っていること</b> ……
    初回登録の値は当時の記録からの<b>推定</b>であり(当時の本文のハッシュは残っていない)、
    正しさを測る相手がそもそも存在しない。台帳が保証するのは未来だけである。

  - <b>本文の数値の照合が 0 と 1 を数えないこと</b> …… 除外を外すと誤検出が増えて
    ツールは NG を返すので「落ちる」が、それは<b>番人が仕事をした</b>のではなく
    <b>騒がしくなった</b>だけである。壊し検証の答えとして意味を持たない。

使い方: python3 tools/batch67_break_check.py [ケース番号...]
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

REGISTRY = "src/main/java/com/example/qte/effect/CardEffectRegistry.java"
GAME_SERVICE = "src/main/java/com/example/qte/game/GameService.java"
CARD_TYPE = "src/main/java/com/example/qte/master/CardType.java"
BATTLE_JS = "src/main/resources/static/js/battle.js"
LEDGER = "src/test/resources/text-impl-review.json"

M2 = os.environ.get("QTE_M2_REPO", "/root/m2work/repository")

# (説明, ファイル, 置換前, 置換後, kind, 照合先クラス(junit のみ), 照合先の名前の一部)
CASES = [
    # ===============================================================
    # 軸1: ★《大地震》のコスト上限。Ver1.1 は 4以下、66 までの実装は 3以下だった
    #   ★同じ改変を JUnit とツールの両方に当てる(この軸だけ2ケースある)
    # ===============================================================
    # ★★★<b>この改変は判定とログの両方を戻している。</b>最初は判定の1行だけを
    #   3 に戻したが、下の「数値の照合ツール」のケースが <b>NG</b> になった ——
    #   ログに「コスト4以下」が残っていたので、ツールは 4 を「実装が持っている」と
    #   数えてしまったのである。66 までの実際の姿は<b>判定もログも 3</b> であり、
    #   そちらが再現すべき姿である(実装者が本文を読まずに書けば、両方が同じ古い数になる)。
    #   ★<b>ここから分かる番人の限界</b>: 判定だけを間違えてログを正しく書いた実装は、
    #   このツールでは捕まらない。数を数える番人が守れるのはそこまでである
    #   —— 意味まで守るのは突き合わせ台帳(軸8)の役目である。
    ("大地震のコスト上限を 3 に戻す(Ver0.4 の姿)", REGISTRY,
     "                    if (c != null && c <= 4) {\n"
     "                        ctx.actions().destroyMinion(ctx.room(), side, m);\n"
     "                    }\n"
     "                }\n"
     "            }\n"
     "            ctx.room().addLog(\"【大地震】: お互いのコスト4以下のミニオンをすべて破壊しました\");",
     "                    if (c != null && c <= 3) {\n"
     "                        ctx.actions().destroyMinion(ctx.room(), side, m);\n"
     "                    }\n"
     "                }\n"
     "            }\n"
     "            ctx.room().addLog(\"【大地震】: お互いのコスト3以下のミニオンをすべて破壊しました\");",
     "junit", "Batch67TextImplTest", "大地震はコスト4のミニオンを破壊する"),

    ("大地震のコスト上限を 3 に戻す(→ 数値の照合ツール)", REGISTRY,
     "                    if (c != null && c <= 4) {\n"
     "                        ctx.actions().destroyMinion(ctx.room(), side, m);\n"
     "                    }\n"
     "                }\n"
     "            }\n"
     "            ctx.room().addLog(\"【大地震】: お互いのコスト4以下のミニオンをすべて破壊しました\");",
     "                    if (c != null && c <= 3) {\n"
     "                        ctx.actions().destroyMinion(ctx.room(), side, m);\n"
     "                    }\n"
     "                }\n"
     "            }\n"
     "            ctx.room().addLog(\"【大地震】: お互いのコスト3以下のミニオンをすべて破壊しました\");",
     "tool", None, "check_card_text_numbers"),

    # ===============================================================
    # 軸2: ★《聖剣 エクスカリバー》の回復量。Ver1.1 は「全て回復」、66 までは 2回復
    # ===============================================================
    ("エクスカリバーの回復量を 2 に戻す(Ver0.4 の姿)", GAME_SERVICE,
     "                        .forEach(m -> m.heal(m.getMaxHp()));",
     "                        .forEach(m -> m.heal(2));",
     "junit", "Batch67TextImplTest", "エクスカリバーは守護ミニオンの体力を全快させる"),

    # ===============================================================
    # 軸3: ★《生贄を求める邪鬼》。66 までは相手への破壊が丸ごと無かった
    # ===============================================================
    ("邪鬼の相手への破壊を行わない(Ver0.4 の姿)", REGISTRY,
     "            foe.forEach(t -> ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion()));",
     "            foe.forEach(t -> ctx.room().addLog(t.minion().getMaster().name()));",
     "junit", "Batch67TextImplTest", "生贄を求める邪鬼は自分2体と相手1体を破壊する"),

    ("邪鬼が自分から選べる数を 1 に戻す(Ver0.4 の姿)", REGISTRY,
     "                Requirement.upTo(Kind.MINION, Side.SELF, 2,\n"
     "                        \"破壊する自分のミニオンを2体まで選んでください\"),",
     "                Requirement.upTo(Kind.MINION, Side.SELF, 1,\n"
     "                        \"破壊する自分のミニオンを2体まで選んでください\"),",
     "junit", "Batch67TextImplTest", "生贄を求める邪鬼は自分と相手で別の要求を持つ"),

    # ===============================================================
    # 軸4: ★《禁忌の墓地利用》の絞り込み。Ver1.1 は「ミニオンでないカード」
    # ===============================================================
    ("墓地利用の絞り込みをスペル限定に戻す(Ver0.4 の姿)", REGISTRY,
     "                        Filter.NON_MINION_CARD)));",
     "                        Filter.SPELL_CARD)));",
     "junit", "Batch67TextImplTest", "禁忌の墓地利用は墓地のウェポンをマナに置ける"),

    # ===============================================================
    # 軸5: ★「ミニオンでない」の定義。進化ミニオンもミニオンである(総合ルール2-1)
    # ===============================================================
    ("isMinion が進化ミニオンを数えないようにする", CARD_TYPE,
     "        return this == MINION || this == EVOLUTION;",
     "        return this == MINION;",
     "junit", "Batch67TextImplTest", "禁忌の墓地利用は墓地の進化ミニオンも選べない"),

    # ===============================================================
    # 軸6: ★《ツイン・ストライク》の文明の絞り込み。登録側と判定側の両方を試す
    # ===============================================================
    ("ツイン・ストライクから文明の絞り込みを外す(Ver0.4 の姿)", REGISTRY,
     "                List.of(Filter.WIND_CIVILIZATION),",
     "                List.of(),",
     "junit", "Batch67TextImplTest", "ツインストライクは風文明でないミニオンを選べない"),

    ("文明フィルタの判定を風から水にすり替える", GAME_SERVICE,
     "                    if (master.civilization() != com.example.qte.master.Civilization.WIND) {",
     "                    if (master.civilization() != com.example.qte.master.Civilization.WATER) {",
     "junit", "Batch67TextImplTest", "ツインストライクは風文明のミニオンに2回攻撃を与える"),

    # ===============================================================
    # 軸7: ★★光文明の踏み倒しが種別を見ていること。
    #   本文は「ミニオン」だが、66 までは進化ミニオンも通り、素材なしで場に出ていた
    # ===============================================================
    ("聖なる降誕の儀式から種別の絞り込みを外す(66 までの姿)", REGISTRY,
     "                Filter.GUARD, Filter.COST_7_OR_LESS, Filter.MINION_CARD)));",
     "                Filter.GUARD, Filter.COST_7_OR_LESS)));",
     "junit", "Batch67TextImplTest", "聖なる降誕の儀式は手札の進化ミニオンを選べない"),

    # ===============================================================
    # 軸8: ★★突き合わせ台帳。本文が変わったのに記録が古いことを見つけられるか
    #   ★台帳のほうを1件だけ書き換える(実装ではなく記録を壊す)。
    #     JUnit とツールの両方に当てる(この軸も2ケースある)
    # ===============================================================
    ("台帳の《大地震》のハッシュを別の値にする", LEDGER,
     '"QTE-M-EARTH-11": {\n      "textHash": "d7491b6596b3eaa5"',
     '"QTE-M-EARTH-11": {\n      "textHash": "0000000000000000"',
     "junit", "CardTextReviewTest", "突き合わせたときから本文が変わっていない"),

    ("台帳の《大地震》のハッシュを別の値にする(→ 台帳のツール)", LEDGER,
     '"QTE-M-EARTH-11": {\n      "textHash": "d7491b6596b3eaa5"',
     '"QTE-M-EARTH-11": {\n      "textHash": "0000000000000000"',
     "tool", None, "mark_text_reviewed"),

    # ===============================================================
    # 軸9: ★★Filter の Java と JS の一致。67 で範囲を広げた番人が効いているか
    # ===============================================================
    ("battle.js の matchesFilters から NON_MINION_CARD を消す", BATTLE_JS,
     "            case 'NON_MINION_CARD':\n"
     "                // ★Batch 67(禁忌の墓地利用)。進化ミニオンもミニオンである(裁定 2-1)\n"
     "                ok = !!card && card.type !== 'MINION' && card.type !== 'EVOLUTION'; break;\n",
     "",
     "verify", None, "matchesFilters の両方に居る"),

    ("battle.js の手札ハイライトから GUARD を消す", BATTLE_JS,
     "                    case 'GUARD': return card.keywords.includes('守護');\n",
     "",
     "verify", None, "手札の絞り込みに使うフィルタは手札のハイライトにも居る"),
]

# ★壊しても落ちないことが分かっている項目があればここに理由を書く(裁定196)
EXPECTED_NG = {}


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as fh:
        return fh.read()


def write(path, text):
    with open(os.path.join(ROOT, path), "w", encoding="utf-8") as fh:
        fh.write(text)
    # ★★★62 の教訓: 書き戻せたことを、書き戻した本人が確かめる。
    if read(path) != text:
        raise RuntimeError("★★書き戻せていない: %s" % path)


def env():
    e = dict(os.environ)
    e.setdefault("NODE_PATH", "/home/claude/.npm-global/lib/node_modules")
    e.setdefault("PLAYWRIGHT_BROWSERS_PATH", "/opt/pw-browsers")
    return e


# ---- verify(静的ファイル側) ----

def run_verify():
    build = subprocess.run([sys.executable, "verify/build_harness.py"],
                           cwd=ROOT, capture_output=True, text=True, env=env())
    if build.returncode != 0:
        return None
    done = subprocess.run(["node", "verify/verify.js"],
                          cwd=ROOT, capture_output=True, text=True, env=env())
    return done.stdout


def verify_verdict(out, fragment):
    if out is None:
        return "EMPTY"
    hits = [line for line in out.splitlines()
            if fragment in line and (line.startswith("PASS") or line.startswith("FAIL"))]
    if not hits:
        return "EMPTY"
    return "OK" if any(line.startswith("FAIL") for line in hits) else "NG"


# ---- tools(このバッチが置いた機械の番人) ----

TOOLS = {
    "check_card_text_numbers": ["tools/check_card_text_numbers.py"],
    "mark_text_reviewed": ["tools/mark_text_reviewed.py", "--check"],
}


def tool_verdict(name):
    """★ツールが<b>0 以外</b>を返したら OK(番人が仕事をした)。

    ★<b>「落ちた」を例外で判断しない。</b>スクリプトが書き間違いで死んでも
    終了コードは 0 以外になる —— それを OK と数えると、
    <b>壊れた番人を「仕事をしている」と読む</b>ことになる。
    出力に判定の言葉(NG:)が出ていることまで確かめる。
    """
    argv = TOOLS.get(name)
    if argv is None:
        return "EMPTY"
    done = subprocess.run([sys.executable] + argv,
                          cwd=ROOT, capture_output=True, text=True, env=env())
    if done.returncode == 0:
        return "NG"
    return "OK" if "NG:" in done.stdout else "EMPTY"


# ---- JUnit(Java 側) ----

def run_junit(test_class):
    """1クラスだけ回す。★surefire:test ではなく test を使う(裁定208: あちらはコンパイルしない)"""
    report = os.path.join(ROOT, "target/surefire-reports",
                          "TEST-com.example.qte.%s.xml" % test_class)
    if os.path.exists(report):
        os.remove(report)
    subprocess.run(["mvn", "-o", "-B", "-q",
                    "-Dmaven.repo.local=%s" % M2,
                    "-Dtest=%s" % test_class, "-DfailIfNoTests=false", "test"],
                   cwd=ROOT, capture_output=True, text=True, env=env())
    return report if os.path.exists(report) else None


def junit_verdict(report, fragment):
    """★試験1件ずつの結果を XML から読む。

    ★<b>「ビルドが失敗した」を OK と数えない。</b>改変でコンパイルが通らなければ
    報告書そのものが生まれず、EMPTY になる(裁定304 の形をここで見分ける)。
    """
    if report is None:
        return "EMPTY"
    root = ET.parse(report).getroot()
    hits = [tc for tc in root.iter("testcase") if fragment in (tc.get("name") or "")]
    if not hits:
        return "EMPTY"
    broke = any(tc.find("failure") is not None or tc.find("error") is not None
                for tc in hits)
    return "OK" if broke else "NG"


def main():
    picked = [int(a) for a in sys.argv[1:]] or list(range(1, len(CASES) + 1))
    # ★★★開始時の姿を控えておく(62 の教訓)。壊したまま終わったら、
    #   OK が何件出ていようとこのスクリプトは失敗である。
    targets = sorted({case[1] for case in CASES})
    baseline = {path: read(path) for path in targets}
    results = []
    for number, (label, path, before, after, kind, cls, target) in enumerate(CASES, 1):
        if number not in picked:
            continue
        original = read(path)
        hits = original.count(before)
        if hits != 1:
            results.append((number, label, "SETUP-NG"))
            print("%2d SETUP-NG %s (一致 %d 箇所)" % (number, label, hits))
            continue
        write(path, original.replace(before, after))
        try:
            if kind == "junit":
                answer = junit_verdict(run_junit(cls), target)
            elif kind == "tool":
                answer = tool_verdict(target)
            else:
                answer = verify_verdict(run_verify(), target)
        finally:
            write(path, original)
        if answer == "NG" and label in EXPECTED_NG:
            answer = "NG(想定内)"
        results.append((number, label, answer))
        print("%2d %-10s %s  →  %s" % (number, answer, label, target))
        if answer == "NG(想定内)":
            print("      理由: %s" % EXPECTED_NG[label])

    counts = {}
    for _, _, answer in results:
        counts[answer] = counts.get(answer, 0) + 1
    print("\n" + " / ".join("%s %d" % (k, counts[k]) for k in sorted(counts)))

    dirty = [path for path in targets if read(path) != baseline[path]]
    if dirty:
        print("\n★★★実装が壊れたまま残っている(手で戻すこと):")
        for path in dirty:
            print("    " + path)
        return 1
    print("★実装は開始時の姿に戻っている。")

    # ★最後にハーネスを正しい状態へ戻す(壊した状態のまま残さない)
    run_verify()
    good = counts.get("OK", 0) + counts.get("NG(想定内)", 0)
    return 0 if good == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
