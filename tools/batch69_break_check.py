#!/usr/bin/env python3
"""Batch 69(通常モードの盤面の続き)の壊し検証(裁定116)。

実装をわざと壊し、狙った番人が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った番人が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(番人が足りない)
  EMPTY    … その番人が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★★<b>69 の照合先は2層である。</b>verify(実測)と JUnit(ファイルを読む番人)である。
  ★<b>Java の挙動は1行も変えていない</b>ので、効果や盤面の JUnit は照合先に入らない。
  ★<b>2層に分かれている理由は「回る場所が違う」ことである</b> ——
  verify は Playwright が要り、マスターの手元(Eclipse)では回らない。
  だから「壊れたら致命的で、しかもファイルを読むだけで測れる」2つだけを
  {@code Batch69BoardTest} に置いてある(軸6・軸10 がその2つを名指しで測る)。

★★改変は「軸」ごとに1件ずつ当てる(57 の教訓)。軸は次の11である。
  (1) 自陣と敵陣の地色            (2) 色の系統が帯の線と一致すること
  (3) ホバーの取り付け(場・手札)  (4) 対象を選んでいる最中は出さないこと
  (5) 出ているプレビューを消すこと  (6) 進行表が TurnPhase と一致すること
  (7) 進行表の現在地の印           (8) 進行表が右列の空白を埋めること
  (9) 0枚のバッジを黙らせること     (10) バッジの書き込みが1本を通ること
  (11) 右列の中段が溢れても巻けること

★★<b>裁定304 の罠</b>(Java で条件を落とすと到達不能でコンパイルが通らない)は
  69 には無い —— 触ったのは CSS と JS だけである。
  ただし<b>JS では別の形で同じ罠が起きる</b>: 文を消して構文を壊すと
  ページ全体が死に、狙った検査どころか全部が落ちる(それは OK ではなく EMPTY である)。
  69 の改変は<b>独立した1文の削除</b>か<b>値・式の置き換え</b>だけにしてある。

★★★<b>壊しどころが無い項目</b>(意図的に含めていないもの・裁定196 の正直な扱い):

  - <b>接続バー</b> …… 69 では<b>作っていない</b>(マスター確認で候補 H へ譲った)。
    無いものは壊せない。理由は notes/batch69-design-notes.md の 5章に書いてある。

  - <b>ホバープレビューの出る位置</b> …… {@code .auto-hover} は right/top の固定位置である。
    「候補を隠さないこと」は<b>位置ではなく、出さないことで</b>担保している(軸4)。
    位置を変える改変は「隠れる/隠れない」の境目が盤面の中身に依存するため、
    壊し検証の答えが盤面次第で変わる —— <b>測れないものを測るふりをしない</b>。

  - <b>右列の中段が溢れたときに「気づけるか」</b> …… 巻けることは測れる(軸11)が、
    「巻ける状態に人が気づくか」は機械には測れない。実機確認の依頼として残してある。

使い方: python3 tools/batch69_break_check.py [ケース番号...]
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CSS = "src/main/resources/static/css/battle.css"
BATTLE_JS = "src/main/resources/static/js/battle.js"

M2 = os.environ.get("QTE_M2_REPO", "/root/m2work/repository")

# 壊しても落ちないことが分かっているもの(理由つき)。今のところ無い。
EXPECTED_NG = {}

# (説明, ファイル, 置換前, 置換後, kind, 照合先クラス(junit のみ), 照合先の名前の一部)
CASES = [
    # ===============================================================
    # 軸1: ★自陣と敵陣の地色(65 が挙げた穴の本体)
    # ===============================================================
    ("自分の場の地色を相手と同じにする(66 までの姿に近づける)", CSS,
     ".auto-field-self     { background: rgba(13, 110, 253, 0.11); border-left-color: #0d6efd; }",
     ".auto-field-self     { background: rgba(220, 53, 69, 0.09); border-left-color: #0d6efd; }",
     "verify", None, "相手の場と自分の場は地色も左端の色も異なる"),

    # ===============================================================
    # 軸2: ★色の系統が帯の線と一致すること(裁定130: 値の正は .opponent-side / .my-side)
    #   ★地色は動かさない —— 動かすと軸1 と見分けが付かなくなる
    # ===============================================================
    ("相手の場の左端だけ別の色にする(帯の線と系統がずれる)", CSS,
     ".auto-field-opponent { background: rgba(220, 53, 69, 0.09); border-left-color: #dc3545; }",
     ".auto-field-opponent { background: rgba(220, 53, 69, 0.09); border-left-color: #a1465e; }",
     "verify", None, "場の左端の色は帯の線と同じ系統である"),

    # ===============================================================
    # 軸3: ★ホバーの取り付け(44 の器を呼ぶこと)。場と手札で1件ずつ
    # ===============================================================
    ("場のミニオンのホバーを取り付けない(44 の止まった姿へ戻す)", BATTLE_JS,
     "    attachHover(el, minionFace);\n    return el;",
     "    return el;",
     "verify", None, "場のミニオン(両席)と手札にホバープレビューが出る"),

    ("手札のホバーを取り付けない(44 の止まった姿へ戻す)", BATTLE_JS,
     "    attachHover(el, handFace);\n    return el;",
     "    return el;",
     "verify", None, "場のミニオン(両席)と手札にホバープレビューが出る"),

    # ===============================================================
    # 軸4: ★対象を選んでいる最中は出さないこと
    #   ★2件目は<b>69 の実装で実際に踏んだ穴</b>である ——
    #     最初の実装は宣言時の pending しか見ておらず、
    #     68 が【召喚時】の対象を移した先である<b>割り込みを見落としていた</b>
    # ===============================================================
    ("選んでいる最中でもプレビューを出す(ガードを外す)", BATTLE_JS,
     "    return !!pending || !!evolution || !!tabooPay || hasPendingChoice()\n"
     "        || !!(latestView && latestView.mulligan);",
     "    return false;",
     "verify", None, "対象を選んでいる最中はホバープレビューを出さない"),

    ("割り込みだけ見落とす(69 の実装で実際に踏んだ穴)", BATTLE_JS,
     "    return !!pending || !!evolution || !!tabooPay || hasPendingChoice()\n"
     "        || !!(latestView && latestView.mulligan);",
     "    return !!pending || !!evolution || !!tabooPay\n"
     "        || !!(latestView && latestView.mulligan);",
     "verify", None, "対象を選んでいる最中はホバープレビューを出さない"),

    # ===============================================================
    # 軸5: ★もう出ているプレビューを消すこと(出す側のガードだけでは足りない)
    # ===============================================================
    ("問い合わせが来ても出ているプレビューを消さない", BATTLE_JS,
     "    if (hoverBlocked()) hideHover();\n    renderHeader(view);",
     "    renderHeader(view);",
     "verify", None, "出ているプレビューは、問い合わせが来た時点で消える"),

    # ===============================================================
    # 軸6: ★進行表が TurnPhase と一致すること(★照合先が2層あることの証拠)
    # ===============================================================
    ("進行表からサブフェイズを落とす(→ verify 層)", BATTLE_JS,
     "    { phase: 'SUB', label: 'サブ' },\n",
     "",
     "verify", None, "フェイズの進行表は TurnPhase.java と同じ並び・同じ表示名である"),

    ("進行表からサブフェイズを落とす(→ JUnit 層・Eclipse で回る番人)", BATTLE_JS,
     "    { phase: 'SUB', label: 'サブ' },\n",
     "",
     "junit", "Batch69BoardTest", "フェイズの進行表はTurnPhaseと同じ並びと表示名である"),

    # ===============================================================
    # 軸7: ★今のフェイズだけに印が付くこと
    # ===============================================================
    ("今のフェイズに印を付けない(全部これから扱い)", BATTLE_JS,
     "            + (i === now ? 'auto-phase-now' : (now >= 0 && i < now ? 'auto-phase-done' : 'auto-phase-todo'));",
     "            + (now >= 0 && i < now ? 'auto-phase-done' : 'auto-phase-todo');",
     "verify", None, "進行表は7つあり、今のフェイズだけに印が付く"),

    # ===============================================================
    # 軸8: ★進行表が右列の空白を実際に埋めること(65 が挙げた穴の答え)
    # ===============================================================
    ("進行表を自然な高さにする(空白が下に戻る)", CSS,
     ".auto-phase-track {\n    flex: 1 1 0; min-height: 0; overflow: hidden;",
     ".auto-phase-track {\n    flex: 0 0 auto; min-height: 0; overflow: hidden;",
     "verify", None, "進行表が右列の空白を埋めている"),

    # ===============================================================
    # 軸9: ★0枚のバッジを黙らせること
    # ===============================================================
    ("0枚でもバッジを光らせたままにする(65 までの姿)", BATTLE_JS,
     "    el.classList.toggle('auto-pile-count-zero', Number(count) === 0);",
     "    el.classList.toggle('auto-pile-count-zero', false);",
     "verify", None, "0枚のパイルのバッジは黙り"),

    # ===============================================================
    # 軸10: ★バッジの書き込みが1本を通ること(★照合先が2層あることの証拠)
    #   ★44 は書き込みを8箇所に散らしていた。1箇所だけ直書きへ戻すと、
    #     <b>そのパイルだけ</b> 0枚でも金色のままになる
    # ===============================================================
    ("山札のバッジだけ直書きへ戻す(→ verify 層)", BATTLE_JS,
     "    setPileCount('my-deck-count', you.deckCount);",
     "    document.getElementById('my-deck-count').textContent = you.deckCount;",
     "verify", None, "0枚のパイルのバッジは黙り"),

    ("山札のバッジだけ直書きへ戻す(→ JUnit 層・Eclipse で回る番人)", BATTLE_JS,
     "    setPileCount('my-deck-count', you.deckCount);",
     "    document.getElementById('my-deck-count').textContent = you.deckCount;",
     "junit", "Batch69BoardTest", "パイルの枚数バッジの書き込みは一本を通る"),

    # ===============================================================
    # 軸11: ★右列の中段が溢れても巻けること(69 が見つけた既存の穴)
    # ===============================================================
    ("中段の巻きを止める(問い合わせが画面の外へ消える)", CSS,
     "    overflow-y: auto; scrollbar-width: thin;\n}",
     "    overflow-y: visible; scrollbar-width: thin;\n}",
     "verify", None, "右列の中段は、問い合わせが長くて溢れても巻ける"),
]


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as handle:
        return handle.read()


def write(path, text):
    with open(os.path.join(ROOT, path), "w", encoding="utf-8") as handle:
        handle.write(text)
    # ★★★62 の教訓: 書き戻せたことを、書き戻した本人が確かめる。
    if read(path) != text:
        raise RuntimeError("★★書き戻せていない: %s" % path)


def env():
    e = dict(os.environ)
    e.setdefault("NODE_PATH", "/home/claude/.npm-global/lib/node_modules")
    e.setdefault("PLAYWRIGHT_BROWSERS_PATH", "/opt/pw-browsers")
    return e


# ---- verify(実測) ----

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


# ---- JUnit(ファイルを読む番人) ----

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

    ★<b>「ビルドが失敗した」を OK と数えない。</b>報告書そのものが生まれなければ EMPTY である。
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
