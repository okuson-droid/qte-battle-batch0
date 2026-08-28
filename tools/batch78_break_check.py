#!/usr/bin/env python3
"""Batch 78(通常モードの確認の1本化)の壊し検証(裁定116)。

実装をわざと壊し、狙った番人が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った番人が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(番人が足りない)
  EMPTY    … その番人が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★★★<b>照合先は2つある</b>が、77 と同じく<b>重心は verify に寄っている</b> ——
  78 が変えたのは {@code battle.js} / {@code battle.html} / {@code battle.css} だけである。

  - <b>画面の振る舞い</b>(3つの出口・初期フォーカス・Tab の折り返し・Esc)
    …… {@code verify}。<b>フォーカスは実測でしか測れない</b>(設計判断45)。
  - <b>器が在るか・版数</b> …… {@code BattlePageTest}。

★★<b>規則が n 入口ぶんあるなら、番人も n 入口ぶん要る</b>(77 の教訓)——
  宣言が出る入口は4つある(手札クリックの賢魂 / 特殊召喚 / 強化、禁忌クリックの賢魂)
  \\+ ドラッグ。★軸ごとに<b>別の入口</b>を当ててある(軸5〜9)。

★★★<b>独立した項目を先頭へ、遷移を起こしうる項目を末尾へ</b>(72・75 の教訓)。
  JUnit の軸(1〜4)を先に、verify を回す軸(5〜16)を末尾に置いてある。

★★★<b>NG が出たら、まず実装ではなく番人を疑うこと</b>(75 の教訓)——
  78 の verify を書いたときも、最初の赤は<b>すべて番人側</b>だった:
  {@code data-initial-focus} を内側の div に書いていた(実装側の1件)ほかは、
  「前の項目が禁忌の帯を開いたまま渡していた」「[投了] は d-none で焦点を取れない」
  「宣言モーダルを閉じずに次へ渡していた」の3件である。

使い方: python3 tools/batch78_break_check.py [ケース番号...]
★★<b>長いので3つに分けて回し、前後で `git diff --stat` を突き合わせること</b>(70 の教訓)。
  例: `1..4` / `5..10` / `11..16`
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
M2 = "/root/m2work/repository"

BATTLE_JS = "src/main/resources/static/js/battle.js"
BATTLE_HTML = "src/main/resources/templates/battle.html"
BATTLE_CSS = "src/main/resources/static/css/battle.css"

PAGE_TEST = "BattlePageTest"

# 壊しても落ちないことが分かっているもの(理由つき)。★78 は1件も無い。
EXPECTED_NG = {}

# ★★★<b>壊しどころが無いもの</b>(裁定196 の正直な扱い)——
#   軸に入れていない理由を書き残す。
#
#   1) <b>Java を1行も変えていない。</b>78 は画面だけのバッチである(71・77 と同じ)。
#      サーバから見ると、飛んでくる本文は 77 と1バイトも変わっていない ——
#      ★<b>宣言は「どの宛先へ送るか」を決めるだけ</b>であり、送る形は変えていない。
#
#   2) <b>手動モードの層(36)には触っていない。</b>78 が写したのは<b>形</b>であって、
#      あちらのコードを共有したのではない(裁定111・289 と同じ複製である)。
#      ★<b>片方だけ直さないこと</b> —— 落とし穴として書き残した。
#
#   3) <b>「通常プレイする」を選んだあとに強化を問い直さないこと。</b>
#      77 までの {@code else if} と同じ振る舞いを保っただけであり、
#      <b>78 が作った性質ではない</b>。変えるなら裁定が要る(母集団の外である)。
#
#   4) <b>{@code stillThere} が「動いていない」側で何もしないこと。</b>
#      一致する場合に素通りするのが正しい振る舞いなので、
#      <b>壊すと「常に止まる」になり、他のほぼ全部の項目が落ちる</b> ——
#      軸を1つに絞れない(66 の教訓「壊す場所が1つに絞れない」)。
#      ★<b>動いた側</b>は軸16 が当てている。

# (説明, ファイル, 置換前, 置換後, 種別, クラス, 照合先の名前の一部)
CASES = [
    # ===============================================================
    # I. 器と版数(軸1〜4・JUnit)★独立しているので先頭
    # ===============================================================

    # 軸1: ★★★宣言モーダルの器そのものを消す
    ("宣言モーダルが盤面に無い", BATTLE_HTML,
     '<div id="auto-declare" class="info-modal auto-declare d-none"',
     '<div id="auto-declare-REMOVED" class="info-modal auto-declare d-none"',
     "junit", PAGE_TEST, "通常モードの盤面に宣言モーダルがある"),

    # 軸2: ★★初期フォーカスの名指しを外す(裁定52)
    #   ★外すと層は<b>先頭の [A の姿で使う] に焦点を当てる</b> —— 送る側である
    ("宣言モーダルの初期フォーカスの名指しを外す", BATTLE_HTML,
     '<div id="auto-declare" class="info-modal auto-declare d-none"\n'
     '     data-initial-focus="#auto-declare-close">',
     '<div id="auto-declare" class="info-modal auto-declare d-none">',
     "junit", PAGE_TEST, "通常モードの盤面に宣言モーダルがある"),

    # 軸3: ★★確認モーダルの名指しを外す(72 の性質を 78 が層へ移した側)
    ("確認モーダルの初期フォーカスの名指しを外す", BATTLE_HTML,
     '<div id="auto-confirm" class="info-modal auto-confirm d-none"\n'
     '     data-initial-focus="#auto-confirm-close">',
     '<div id="auto-confirm" class="info-modal auto-confirm d-none">',
     "junit", PAGE_TEST, "通常モードの確認モーダルは初期フォーカスをキャンセルに名指ししている"),

    # 軸4: ★★★据え置きの番人を逆から当てる —— CSS を触ったのに上げない
    ("battle.css の版数を 54 のまま据え置く", BATTLE_HTML,
     "battle.css(v=55)", "battle.css(v=54)",
     "junit", PAGE_TEST, "通常モードの盤面のCSSの版数が78で上がっている"),

    # ===============================================================
    # II. 宣言の3つの出口(軸5〜9・verify)
    #     ★★入口ごと・出口ごとに当てる(71・75・76・77 の教訓)
    # ===============================================================

    # 軸5: ★★★77 の姿へ戻す —— 手札クリックの特殊召喚を素の confirm() に戻す
    #   ★<b>これが「[キャンセル] が通常プレイを意味する」姿そのものである。</b>
    #   ★★★<b>手札とドラッグで文字列が同じである</b>(意図して同じ形に書いてある)ので、
    #     コールバックの行き先({@code startHandPlay} / {@code startDropPlay})まで含めて当てる ——
    #     <b>入口ごとに当てるという軸の立て方そのものが、ここで一度 SETUP-NG を出した</b>。
    ("手札クリックの特殊召喚が宣言モーダルを出さない(77 の姿へ戻す)", BATTLE_JS,
     "    if (card.canSpecialSummon && latestView.phase === 'MAIN') {\n"
     "        askDeclare(card.specialSummonText,\n"
     "            '特殊召喚する',\n"
     "            () => startHandPlay(index, card, 'special-summon', card.specialTargets, false),",
     "    if (false && card.canSpecialSummon && latestView.phase === 'MAIN') {\n"
     "        askDeclare(card.specialSummonText,\n"
     "            '特殊召喚する',\n"
     "            () => startHandPlay(index, card, 'special-summon', card.specialTargets, false),",
     "verify", None, "特殊召喚は宣言モーダルで問い、両方に動詞が載る"),

    # 軸6: ★★A と B が同じ宛先へ飛ぶ(「両方の分岐に意味がある」を殺す)
    #   ★<b>器が出ているだけでは足りない</b>ことを測る側である(裁定181)
    ("宣言の B が A と同じ宛先へ飛ぶ(分岐が1つに潰れる)", BATTLE_JS,
     "            '通常プレイする',\n"
     "            () => startHandPlay(index, card, 'play-card', card.targets, false));\n"
     "        return;\n"
     "    }\n"
     "    if (card.enhancedCost > 0) {",
     "            '通常プレイする',\n"
     "            () => startHandPlay(index, card, 'special-summon', card.specialTargets, false));\n"
     "        return;\n"
     "    }\n"
     "    if (card.enhancedCost > 0) {",
     "verify", None, "宣言の A と B は別の宛先へ飛ぶ"),

    # 軸7: ★★★[やめる] が「B を選んだ」に落ちる(77 までの振る舞いへ戻す)
    #   ★<b>78 の中心である。</b>ここが緑のままなら、裁定353 は守られていない
    ("[やめる]が B を選んだことになる(77 までの振る舞いへ戻す)", BATTLE_JS,
     "function closeAutoDeclare() {\n"
     "    autoDeclarePending = null;",
     "function closeAutoDeclare() {\n"
     "    if (autoDeclarePending && autoDeclarePending.onB) autoDeclarePending.onB();\n"
     "    autoDeclarePending = null;",
     "verify", None, "宣言の[やめる]は、どちらの姿でも使わずに何も送らない"),

    # 軸8: ★★ドラッグの入口から宣言を落とす(軸5 と<b>同じ規則・別の入口</b>)
    #   ★★★<b>77 の教訓の実演である</b>: 規則が2入口ぶんあるので、軸も2つ要る
    ("ドラッグの入口だけ宣言モーダルを出さない", BATTLE_JS,
     "    if (card.canSpecialSummon && latestView.phase === 'MAIN') {\n"
     "        askDeclare(card.specialSummonText,\n"
     "            '特殊召喚する',\n"
     "            () => startDropPlay(d, card, 'special-summon', card.specialTargets,",
     "    if (false && card.canSpecialSummon && latestView.phase === 'MAIN') {\n"
     "        askDeclare(card.specialSummonText,\n"
     "            '特殊召喚する',\n"
     "            () => startDropPlay(d, card, 'special-summon', card.specialTargets,",
     "verify", None, "ドラッグの入口でも宣言モーダルが出て"),

    # 軸9: ★★強化使用の宣言を落とす(3つ目の枝。コストに効く側である)
    ("強化使用が宣言モーダルを出さない", BATTLE_JS,
     "    if (card.enhancedCost > 0) {\n"
     "        // 追加コストによる強化使用(a5: 回帰の風穴・風弾の跳弾)。",
     "    if (false && card.enhancedCost > 0) {\n"
     "        // 追加コストによる強化使用(a5: 回帰の風穴・風弾の跳弾)。",
     "verify", None, "強化使用も宣言モーダルで問い"),

    # ===============================================================
    # III. 文言と入口ごとの言い分け(軸10・11・verify)
    # ===============================================================

    # 軸10: ★★禁忌の側のボタンを手札の言い方にする
    #   ★禁忌はコスト軽減を受けない —— <b>入口で言い方が違うことに意味がある</b>
    ("禁忌の賢魂のボタンが実効コストで書かれる", BATTLE_JS,
     "            `スペルとして使う(マナ${card.soulCost}枚)`,",
     "            `スペルとして使う(${soulCostLabel(card)})`,",
     "verify", None, "禁忌の【賢魂】の宣言は、退けるマナの枚数で書く"),

    # 軸11: ★★★素の confirm() へ戻す(裁定53 の番人・7箇所ぶん)
    #   ★照合先は 78-5 である —— <b>張り込んで呼ばれないことを見ている</b>
    ("禁忌クリックが素の confirm() を呼ぶ", BATTLE_JS,
     "    if (card.soulCost != null) {\n"
     "        askDeclare(soulPrompt(card),\n"
     "            `スペルとして使う(マナ${card.soulCost}枚)`,",
     "    if (card.soulCost != null && confirm(soulPrompt(card))) {\n"
     "        startTabooPlay(index, card, 'play-taboo-soul', card.soulCost, card.soulTargets);\n"
     "        return;\n"
     "    }\n"
     "    if (false) {\n"
     "        askDeclare(soulPrompt(card),\n"
     "            `スペルとして使う(マナ${card.soulCost}枚)`,",
     "verify", None, "宣言の入口でも素の confirm() を1度も呼ばない"),

    # ===============================================================
    # IV. 層とフォーカス(軸12〜15・verify)
    #     ★★<b>裁定50 は2つのことを言っている</b>: 閉じ込めることと、戻すこと
    # ===============================================================

    # 軸12: ★★★focusin の網を外す(裏の盤面へ抜けられるようにする)
    ("裏へ移ったフォーカスを引き戻さない(focusin の網を外す)", BATTLE_JS,
     "    if (!top || !top.trap || top.el.contains(e.target)) return;\n"
     "    applyInitialFocus(top.el);",
     "    if (!top || !top.trap || top.el.contains(e.target)) return;",
     "verify", None, "裏の盤面へフォーカスを移しても、宣言モーダルへ引き戻す"),

    # 軸13: ★★Tab の折り返しを外す(軸12 とは<b>別の網</b>である)
    #   ★36 が「Tab の折り返しだけでは足りない」と書いた裏返しで、
    #     <b>網が2枚あるなら、軸も2つ要る</b>
    ("Tab が層の外へ抜ける(折り返しを外す)", BATTLE_JS,
     "    if (e.key !== 'Tab' || !top.trap) return;",
     "    if (e.key !== 'Tab' || !top.trap || true) return;",
     "verify", None, "Tab は宣言モーダルの中で折り返す"),

    # 軸14: ★★★閉じたあとの戻り先を捨てる(裁定50 の<b>残り半分</b>)
    #   ★77 までの askConfirm はここを持っていなかった
    ("閉じてもフォーカスが元へ戻らない", BATTLE_JS,
     "    if (index !== modalStack.length) return;\n"
     "    if (layer.trap) restoreFocus(layer.restore);",
     "    if (index !== modalStack.length) return;",
     "verify", None, "閉じたらフォーカスが開く前の場所へ戻る"),

    # 軸15: ★★情報モーダルの Esc を落とす(77 まで効かなかった側)
    ("情報モーダルに Esc が効かない(77 の姿へ戻す)", BATTLE_JS,
     "    syncModalLayer('info-modal', true, { escape: hideModal });",
     "    syncModalLayer('info-modal', true);",
     "verify", None, "情報モーダルは Esc で閉じる"),

    # ===============================================================
    # V. 非同期になったことの手当て(軸16・verify)
    # ===============================================================

    # 軸16: ★★★<b>問うているあいだに盤面が動いた側</b>を素通りさせる。
    #   ★{@code stillThere} を常に真にすると、答えが返ったときに
    #     <b>違うカードを使ってしまう</b>。
    #   ★★★<b>この軸は一度 NG を返した。</b>照合先を 53 の項目に向けていたが、
    #     <b>verify に「盤面が動く」場面が1つも無かった</b> ——
    #     壊しても差が出ない(「その分岐に入る盤面が構造的に作れない」)。
    #   ★<b>疑うべきは番人だった</b>(75 の教訓)。78-9b を新設して照合先をそちらへ向けてある。
    ("宣言のあいだに盤面が動いても引き直さない", BATTLE_JS,
     "function stillThere(zone, index, cardId) {\n"
     "    const list = latestView && latestView.you ? latestView.you[zone] : null;\n"
     "    return !!(list && list[index] && list[index].cardId === cardId);",
     "function stillThere(zone, index, cardId) {\n"
     "    const list = latestView && latestView.you ? latestView.you[zone] : null;\n"
     "    if (list) return true;\n"
     "    return !!(list && list[index] && list[index].cardId === cardId);",
     "verify", None, "宣言のあいだに手札が入れ替わったら、答えても何も送らない"),
]


def env():
    e = dict(os.environ)
    e.setdefault("NODE_PATH", "/home/claude/.npm-global/lib/node_modules")
    e.setdefault("PLAYWRIGHT_BROWSERS_PATH", "/opt/pw-browsers")
    return e


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as handle:
        return handle.read()


def write(path, text):
    with open(os.path.join(ROOT, path), "w", encoding="utf-8") as handle:
        handle.write(text)


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


# ---- JUnit(器と版数の番人) ----

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
    """★<b>「ビルドが失敗した」を OK と数えない。</b>報告書が生まれなければ EMPTY である(裁定304)。"""
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
    import signal

    def _raise(signum, frame):
        raise KeyboardInterrupt("signal %d" % signum)

    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, _raise)

    # ★★★開始時の姿を控えておく(70 の教訓)。壊したまま終わったら、
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
        except KeyboardInterrupt:
            write(path, original)
            print("\n★中断された。%s は書き戻した。" % path)
            raise
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
    if any(CASES[n - 1][4] == "verify" for n in picked):
        run_verify()
    good = counts.get("OK", 0) + counts.get("NG(想定内)", 0)
    return 0 if good == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
