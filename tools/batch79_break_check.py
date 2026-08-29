#!/usr/bin/env python3
"""Batch 79(verify の「時々落ちる2項目」を直す = 候補 U)の壊し検証(裁定116)。

実装をわざと壊し、狙った番人が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った番人が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(番人が足りない)
  EMPTY    … その番人が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★★★<b>このバッチが変えたのは {@code verify/verify.js} だけである</b> ——
  Java も JavaScript も CSS もカード定義も1文字も触っていない。
  ★したがって<b>照合先はすべて verify であり、JUnit の軸は1つも無い</b>
  (設計判断45: 番人は「回る場所」で選ぶ)。

★★<b>壊す先は2種類ある。</b>
  - <b>verify 側</b>(軸1〜4・8・9)…… 待ち方を 78 までの姿(時間・待たない)へ戻す。
  - <b>{@code battle.js} 側</b>(軸5〜7)…… <b>番人が実装の旗を本当に読んでいるか</b>を確かめる。
    ★★79 は battle.js を1文字も変えていないが、
    <b>新しい番人がその旗を読んでいること</b>は壊してしか確かめられない
    (77 の教訓「添えておいたは、読まれているではない」)。

★★★<b>規則が n 入口ぶんあるなら、番人も n 入口ぶん要る</b>(77 の教訓)——
  デッキファイルを非同期に読む入口は<b>2つ</b>ある
  (デッキメーカーの {@code #in-deck} / 盤面の {@code #deck-gate-file})。
  ★軸1〜4 が前者、軸5〜7 が後者を当てている。
  ★★旗は<b>立てる側と下ろす側</b>で1つずつ当てる(出口ごと・71 の教訓)。

★★★<b>独立した項目を先頭へ、遷移を起こしうる項目を末尾へ</b>(72・75 の教訓)。
  ここは全軸が verify の1回転なので遷移の順序問題は無いが、
  <b>他の項目を巻き込む改変(battle.js 側)を後ろへ</b>置いてある。

★★★<b>NG が出たら、まず実装ではなく番人を疑うこと</b>(75 の教訓)。

使い方: python3 tools/batch79_break_check.py [ケース番号...]
★★<b>1軸あたり verify を1回転させるので長い。分けて回し、
  前後で `git diff --stat` を突き合わせること</b>(70 の教訓・77 でも殺された)。
  例: `1..3` / `4..6` / `7..9`
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

VERIFY = "verify/verify.js"
BATTLE_JS = "src/main/resources/static/js/battle.js"

# ★壊しても落ちないことが分かっているもの(理由つき)。
#   ★★<b>「落ちない」ことも書き残す</b> —— 次の人が「番人が足りない」と読まないために。
EXPECTED_NG = {
    "コピーの待ちを 78 の姿(固定 120ms)へ戻す":
        "手動モードのコピーは {@code navigator.clipboard} を await するが、"
        "ハーネスでは即座に解決するので<b>固定待ちでも間に合ってしまう</b>。"
        "★<b>79 がここを直したのは予防である</b>(V5 とまったく同じ形なので、"
        "遅い環境では同じ落ち方をする)—— <b>今は壊せない</b>。",
    "入室APIの待ちを 78 の姿(固定 80ms)へ戻す":
        "{@code fetch(...)} の<b>呼び出しそのものは同期である</b> ——"
        "{@code await fetch(...)} は fetch を呼んでから待つので、"
        "{@code window.__fetched} への記録は click の中で<b>同期的に</b>済んでいる。"
        "★<b>待たなくても落ちない</b>。79 がここを直したのも予防である。",
}

# ★★★<b>壊しどころが無いもの</b>(裁定196 の正直な扱い)——
#   軸に入れていない理由を書き残す。
#
#   1) <b>{@code settled} が時間切れで false を返すこと。</b>
#      {@code catch} を外すと {@code waitForFunction} の例外がそのまま上がり、
#      <b>検証スクリプトごと死ぬ</b> —— 以降が1件も走らず EMPTY になる。
#      ★<b>死ぬ検証は、番人ではなく無音である</b>(72・75 の教訓)。
#      軸にすると「どの番人が落ちたか」が読めないので入れていない。
#
#   2) <b>V1(名前未入力)と V3(入り直す)の固定待ち。</b>
#      V1 は<b>否定</b>を測っており(起きないことは事実で待てない)、
#      V3 は<b>ハンドラが同期</b>である —— どちらも直す理由が無い(設計解説 0-3)。
#
#   3) <b>実装(Java / battle.js / CSS / カード定義)。</b>
#      79 は1文字も変えていない。★軸5〜7 が battle.js を壊すのは
#      <b>新しい番人がその旗を読んでいるか</b>を確かめるためであり、
#      79 が作った性質を測っているのではない。
#
#   4) <b>手動モードのデッキ読込({@code #deck-file-a} / {@code -b})。</b>
#      verify が1度も起こしていない(母集団A6)。
#      ★<b>起こすようになった日に、V5 と同じ穴が開く</b> —— 設計解説 0-3 に書き残した。

# (説明, ファイル, 置換前, 置換後, 照合先の名前の一部)
CASES = [
    # ===============================================================
    # I. デッキメーカーの入口(軸1〜4)★このバッチの発端そのもの
    # ===============================================================

    # 軸1: ★★★待ちの器を「待たない」に壊す(候補 U の 78 までの姿)
    #   ★<b>これが「時々落ちる」の正体である。</b>78 までは待ちが1ミリ秒も無かった。
    ("待ちの器 settled が待たずに true を返す", VERIFY,
     "async function settled(page, fn, arg, timeout = 5000) {\n"
     "  try {\n"
     "    await page.waitForFunction(fn, arg, { timeout, polling: 25 });",
     "async function settled(page, fn, arg, timeout = 5000) {\n"
     "  try {\n"
     "    if (page) return true;\n"
     "    await page.waitForFunction(fn, arg, { timeout, polling: 25 });",
     "組みかけは黙って捨てられない"),

    # 軸2: ★★入口の側で待ちを外す(器ではなく、使っている場所を壊す)
    #   ★<b>器ごと壊す軸1 と、使う場所を壊すこの軸は別である</b> ——
    #     器が正しくても、呼ばれていなければ意味が無い(77 の教訓)。
    ("デッキメーカーの読み込みで待ちを使わない", VERIFY,
     "  const loadSettled = await settled(deckPage,\n"
     "    () => document.getElementById('toast').textContent !== '');",
     "  const loadSettled = true;",
     "条件を満たしたデッキは検証が OK になる"),

    # 軸3: ★★★<b>わざと遅らせているのを外す</b>(裁定186 の番人を当てる)
    #   ★遅らせが黙って効かなくなると、上の2項目は<b>ただ通る</b> ——
    #     「たまに落ちる」へ静かに戻る。それを見張るのがこの1件である。
    ("デッキメーカーの読み込みを遅らせない", VERIFY,
     "      return new Promise((r) => { setTimeout(() => r(orig.call(this)), 350); });",
     "      return new Promise((r) => { r(orig.call(this)); });",
     "遅い読み込みは、返ってきた直後にはまだ終わっていない"),

    # 軸4: ★★片付けを外す(78 の教訓・3回つまずいた側)
    #   ★遅らせたまま次へ渡すと、以降でファイルを読む項目が全部この節のせいで落ちる。
    ("遅らせた読み込みを元に戻さない", VERIFY,
     "    File.prototype.text = window.__origFileText;\n"
     "    delete window.__origFileText;",
     "    void window.__origFileText;",
     "遅らせた読み込みは自分で元に戻す"),

    # ===============================================================
    # II. 盤面のデッキゲートの入口(軸5〜7)
    #     ★★<b>n 入口ぶんの番人</b>(77 の教訓)。旗は出口ごとに当てる
    # ===============================================================

    # 軸5: ★verify 側 —— 旗を読まずに、78 の姿(固定 200ms)へ戻す
    ("盤面のデッキゲートで旗を読まない", VERIFY,
     "    const deadline = Date.now() + 5000;\n"
     "    while (input.dataset.busy && Date.now() < deadline) {\n"
     "      await new Promise((r) => { setTimeout(r, 20); });\n"
     "    }\n"
     "    window.fetch = original;\n"
     "    return { immediate, settled: !input.dataset.busy,",
     "    await new Promise((r) => { setTimeout(r, 200); });\n"
     "    window.fetch = original;\n"
     "    return { immediate, settled: !input.dataset.busy,",
     "盤面のデッキゲートは、旗が下りてから測る"),

    # 軸6: ★★★実装側 —— <b>旗を立てるのをやめる</b>
    #   ★79 は battle.js を1文字も変えていないが、<b>新しい番人がその旗を
    #     本当に読んでいるか</b>は壊してしか確かめられない(77 の教訓)。
    ("battle.js が読み込み中の旗を立てない", BATTLE_JS,
     "    input.dataset.busy = '1';",
     "    void input;",
     "遅い読み込みの最中は旗を立てている"),

    # 軸7: ★★実装側 —— <b>旗を下ろすのをやめる</b>(出口の側・71 の教訓)
    #   ★下りないので待ちが時間切れになる —— <b>時間切れは赤である</b>(無音ではない)。
    ("battle.js が読み込み後の旗を下ろさない", BATTLE_JS,
     "            delete input.dataset.busy;",
     "            void input;",
     "盤面のデッキゲートは、旗が下りてから測る"),

    # ===============================================================
    # III. 予防で直した2箇所(軸8〜9)★どちらも NG になることが分かっている
    #      ★★<b>それでも軸に置く</b> —— 「今は壊せない」ことを毎回確かめるため。
    #        壊せるようになった日(= 環境が遅くなった日)に OK へ変わる
    # ===============================================================

    ("コピーの待ちを 78 の姿(固定 120ms)へ戻す", VERIFY,
     "  const copySettled = await settled(page,\n"
     "    () => document.getElementById('manual-toast').textContent !== '');",
     "  const copySettled = true;\n  await page.waitForTimeout(120);",
     "コピーは必ず結果を告げる"),

    ("入室APIの待ちを 78 の姿(固定 80ms)へ戻す", VERIFY,
     "    const deadline = Date.now() + 5000;\n"
     "    while (window.__fetched.length === 0 && Date.now() < deadline) {\n"
     "      await new Promise((r) => setTimeout(r, 20));\n"
     "    }",
     "    await new Promise((r) => setTimeout(r, 80));",
     "席を選ぶと seat つきで入室APIを呼ぶ"),
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
    # ★★書き戻せたことを読み返して確かめる(62 の教訓)——
    #   「やった」と「戻した」は別の主張である。
    if read(path) != text:
        raise RuntimeError("書き戻しに失敗した: %s" % path)


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
    for number, (label, path, before, after, target) in enumerate(CASES, 1):
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
    run_verify()
    good = counts.get("OK", 0) + counts.get("NG(想定内)", 0)
    return 0 if good == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
