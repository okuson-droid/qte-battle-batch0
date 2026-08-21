#!/usr/bin/env python3
"""Batch 62(音響のファイル化)の壊し検証(裁定116)。

実装をわざと壊し、狙った試験が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った試験が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(試験が足りない)
  EMPTY    … その試験が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★★<b>照合先が 55〜61 と違う。</b>今回の番人はほぼ全部 verify(Node)側にある ——
  音は JUnit では測れないためである(37 以来の形)。したがって、このスクリプトは
  surefire の XML ではなく <b>verify.js の出力行</b>を読む。
  ★verify は1回あたり数十秒かかるので、ケース数ぶんの時間がかかる。

★★改変は「軸」ごとに1件ずつ当てる(57 の教訓)。今回の軸は次の8つである。
  (1) 読み込み失敗の扱い(裁定283) (2) 合成の撤去(裁定283)
  (3) 版数(裁定284)             (4) 出所の記録(裁定285)
  (5) 表の複製のずれ(裁定289)    (6) 散らし(裁定286)
  (7) 通常モードの取り付け点(裁定287) (8) 1操作2音にしない(裁定70)

★★壊しても落ちなかったときは、「試験が足りない」の前に
  「改変が当たっているか」「その盤面が構造的に作れるか」を疑うこと(59・60 の教訓)。

使い方: python3 tools/batch62_break_check.py [ケース番号...]
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

MANUAL_JS = "src/main/resources/static/js/manual-battle.js"
AUTO_JS = "src/main/resources/static/js/battle.js"
CREDITS = "src/main/resources/static/sounds/CREDITS.md"

# (説明, ファイル, 置換前, 置換後, 落ちるべき検証の名前の一部)
CASES = [
    # ===============================================================
    # 軸1: 読み込み失敗の扱い(裁定283 = a)
    # ===============================================================
    ("読み込めていない音でも鳴らそうとする(失敗を無視する)", MANUAL_JS,
     "    const buffer = sfxPickBuffer(name);\n"
     "    // ★★読み込めなかった音は<b>鳴らない</b>(裁定283)。合成へは戻らない\n"
     "    if (!buffer) return false;",
     "    const buffer = sfxPickBuffer(name) || sfxCtx.createBuffer(1, 128, 22050);",
     "読み込めなかった音は鳴らない"),

    # ===============================================================
    # 軸2: 合成の撤去(裁定283)。★保険が復活する経路を塞いでいるか
    # ===============================================================
    ("合成のコードが戻ってきている(保険の復活)", MANUAL_JS,
     "function sfxUrl(file) {",
     "function sfxTone(at, seconds, wave, from, to, gain) {\n"
     "    const osc = sfxCtx.createOscillator();\n"
     "    osc.connect(sfxMaster);\n"
     "    osc.start(at);\n"
     "    osc.stop(at + seconds);\n"
     "}\n\n"
     "function sfxUrl(file) {",
     "合成のコードは撤去されている"),

    # ===============================================================
    # 軸3: 版数(裁定284)
    # ===============================================================
    ("音の版数に JS の版数と同じ数字を使い回している", MANUAL_JS,
     "const SFX_VERSION = 1;",
     "const SFX_VERSION = 34;",
     "音声ファイルの版数は独立した1つの定数である"),

    ("音声ファイルに版数を付けていない(キャッシュ任せ)", MANUAL_JS,
     "    return `${SFX_BASE}${file}?v=${SFX_VERSION}`;",
     "    return `${SFX_BASE}${file}`;",
     "音声ファイルの版数は独立した1つの定数である"),

    # ===============================================================
    # 軸4: 出所の記録(裁定285)。★CC0 以外が紛れ込む経路
    # ===============================================================
    ("出所不明の音が1つ紛れている(CREDITS.md から1行消えた)", CREDITS,
     "| `card-shuffle.mp3` | `shuffle` | `card-shuffle.ogg` | Casino Audio (1.1) |"
     " https://kenney.nl/assets/casino-audio |\n",
     "",
     "static/sounds のファイルは全部 CREDITS.md に載っている"),

    ("SFX_SPECS が実在しないファイルを名指している", MANUAL_JS,
     "    draw: { files: ['card-slide-1.mp3'], gain: 0.55 },",
     "    draw: { files: ['card-slide-9.mp3'], gain: 0.55 },",
     "SFX_SPECS が名指すファイルは全部 static/sounds に在る"),

    # ===============================================================
    # 軸5: 表の複製のずれ(裁定289)。★61 の教訓(実装が2つある)の音版
    # ===============================================================
    ("両モードの表がずれた(通常モードだけ音量を変えた)", AUTO_JS,
     "        files: ['card-place-1.mp3', 'card-place-2.mp3', 'card-place-3.mp3',\n"
     "            'card-place-4.mp3'],\n"
     "        gain: 0.80,",
     "        files: ['card-place-1.mp3', 'card-place-2.mp3', 'card-place-3.mp3',\n"
     "            'card-place-4.mp3'],\n"
     "        gain: 0.40,",
     "両モードに共通する音は同じファイル・同じ gain である"),

    ("珍しさの順序が両モードでずれた", AUTO_JS,
     "const SFX_PRIORITY = ['decisive', 'shuffle', 'lpDown', 'lpUp', 'draw', 'tap', 'place'];",
     "const SFX_PRIORITY = ['decisive', 'shuffle', 'draw', 'lpDown', 'lpUp', 'tap', 'place'];",
     "珍しさの順序は両モードで同じである"),

    # ===============================================================
    # 軸6: 散らし(裁定286)
    # ===============================================================
    ("珍しい音まで散らしている(同一であることが情報である音)", MANUAL_JS,
     "    dice: { files: ['dice-throw-1.mp3'], gain: 0.55 },",
     "    dice: { files: ['dice-throw-1.mp3', 'card-fan-1.mp3'], gain: 0.55 },",
     "散らすのは tap と place だけである"),

    ("散らした音が2連続で同じものを鳴らす", MANUAL_JS,
     "    let i = Math.floor(Math.random() * list.length);\n"
     "    if (i === sfxLastIndex[name]) i = (i + 1) % list.length;",
     "    let i = 0;",
     "散らした音は2連続で同じものを鳴らさない"),

    # ===============================================================
    # 軸7: 通常モードの取り付け点(裁定287)
    # ===============================================================
    ("通常モードが描き直しでも音を鳴らす(取り付け点が render に落ちた)", AUTO_JS,
     "function render(view) {\n    if (!view) return;",
     "function render(view) {\n    if (!view) return;\n    sfxPlay('tap');",
     "描き直しただけでは鳴らない"),

    ("通常モードが差分の上限を見ていない(裁定8 が効いていない)", AUTO_JS,
     "const SFX_DIFF_LIMIT = 8;",
     "const SFX_DIFF_LIMIT = 999;",
     "裁定8 の通常モード版"),

    # ===============================================================
    # 軸8: 1つの操作で2音にしない(裁定70)
    # ===============================================================
    ("差分が語る操作まで send で鳴らしている(1操作2音)", AUTO_JS,
     "function sfxForAction(action) {\n"
     "    if (action === 'attack' || action === 'leader-attack') {",
     "function sfxForAction(action) {\n"
     "    if (action === 'play-card' || action === 'charge-mana') {\n"
     "        sfxPlay('place');\n"
     "    } else if (action === 'attack' || action === 'leader-attack') {",
     "差分が語る操作は send では鳴らさない"),

    ("攻撃で音が鳴らない(差分に現れない出来事が語られない)", AUTO_JS,
     "    if (action === 'attack' || action === 'leader-attack') {\n"
     "        sfxPlay('attack');",
     "    if (action === 'attack' || action === 'leader-attack') {\n"
     "        return;",
     "攻撃の宣言で音が鳴る"),
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
    #   ★62 で<b>実際に踏んだ</b> —— ケース11 の改変(render で音を鳴らす)が復元されず、
    #   後続のケースがその汚染された内容を「元の姿」として保存し、
    #   スクリプトが終わった後も残った。**14ケース全部が OK と出たまま**である
    #   (汚染された状態でも、狙った番人はどちらにせよ落ちるため)。
    #   ★60 が「結果ファイルが今回のものか」を測るようにしたのと同じ形の穴である。
    if read(path) != text:
        raise RuntimeError("★★書き戻せていない: %s" % path)


def run_verify():
    """ハーネスを作り直してから verify を回し、出力行を返す。

    ★ハーネスを毎回作り直すのは、テンプレートを壊すケースを足したときに
      「ハーネスだけ古い」という取りこぼしを作らないためである。
    """
    env = dict(os.environ)
    env.setdefault("NODE_PATH", "/home/claude/.npm-global/lib/node_modules")
    env.setdefault("PLAYWRIGHT_BROWSERS_PATH", "/opt/pw-browsers")
    build = subprocess.run([sys.executable, "verify/build_harness.py"],
                           cwd=ROOT, capture_output=True, text=True, env=env)
    if build.returncode != 0:
        return None
    done = subprocess.run(["node", "verify/verify.js"],
                          cwd=ROOT, capture_output=True, text=True, env=env)
    return done.stdout


def verdict(out, fragment):
    """狙った検証が落ちたか。★名前の一部で照合する(名前は長いため)"""
    if out is None:
        return "EMPTY"
    hits = [line for line in out.splitlines()
            if fragment in line and (line.startswith("PASS") or line.startswith("FAIL"))]
    if not hits:
        return "EMPTY"
    return "OK" if any(line.startswith("FAIL") for line in hits) else "NG"


def main():
    picked = [int(a) for a in sys.argv[1:]] or list(range(1, len(CASES) + 1))
    # ★★★開始時の姿を控えておく。最後に1ファイルずつ突き合わせる(62 の教訓)。
    #   ★<b>「全部 OK」と「実装が元に戻っている」は別のことである。</b>
    #   壊したまま終わると、次に走らせる検証やコミットが汚染された実装を見る。
    targets = sorted({path for _, path, _, _, _ in CASES})
    baseline = {path: read(path) for path in targets}
    results = []
    for number, (label, path, before, after, fragment) in enumerate(CASES, 1):
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
            answer = verdict(run_verify(), fragment)
        finally:
            write(path, original)
        if answer == "NG" and label in EXPECTED_NG:
            answer = "NG(想定内)"
        results.append((number, label, answer))
        print("%2d %-10s %s  →  %s" % (number, answer, label, fragment))
        if answer == "NG(想定内)":
            print("      理由: %s" % EXPECTED_NG[label])

    counts = {}
    for _, _, answer in results:
        counts[answer] = counts.get(answer, 0) + 1
    print("\n" + " / ".join("%s %d" % (k, counts[k]) for k in sorted(counts)))

    # ★★★実装が開始時の姿に戻っているかを確かめる(62 の教訓)。
    #   ★これは結果の良し悪しとは別の話である。壊れたまま終わったら、
    #   OK が何件出ていようとこのスクリプトは失敗である。
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
