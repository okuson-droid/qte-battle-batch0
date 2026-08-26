#!/usr/bin/env python3
"""Batch 65(通常モードのマナ行の重なり)の壊し検証(裁定116)。

実装をわざと壊し、狙った試験が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った試験が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(試験が足りない)
  EMPTY    … その試験が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★★<b>65 の照合先は verify だけである。</b>63・64 は JUnit と verify の2つに散っていたが、
  このバッチが触ったのは battle.js と battle.css の2ファイルであり、Java は1行も変えていない。
  「照合先はそのバッチが触った層に居る」という決め方は変えていない。

★★改変は「軸」ごとに1件ずつ当てる(57 の教訓)。全7ケース・軸は次の6である。
  (1) 回転したタイルの外接を勘定に入れる   (2) 必要なぶんだけ重ねる
  (3) 回転のぶんの下駄(左右の margin)     (4) 相手のマナ行にも同じ規則を当てる
  (5) 重なりの前後(右のタイルが上)        (6) margin を書く場所は1つ(CSS 側・JS 側の2件)

★★<b>裁定304 の罠を踏まないように書いてある。</b>「条件を落とす」改変で
  その先が到達不能になると、JS では例外にならないかわりに<b>黙って別物が動く</b> ——
  Java の「コンパイルが通らない」に相当する見えにくさである。
  だからケース2は式ごと 0 に置き換え、ケース4は<b>常に偽になる条件</b>で包んでいる。
  文を消して後続を宙に浮かせる形は使っていない。

★★★<b>壊しどころが無い項目</b>(意図的に含めていないもの・裁定196 の正直な扱い):

  - <b>露出の下限</b>(AUTO_MANA_MIN_EXPOSURE / AUTO_MANA_HARD_EXPOSURE)……
    1280×800 では 15枚すべてタップ済でも必要な重なりが 18px 程度にしかならず、
    上限(52px)に<b>一度も触れない</b>。定数をどう変えても盤面が動かないので、
    改変が当たったかどうかを測れない。★これは 63 の裁定298(試験が実装から値を読む)とは
    別の形である ——「試験が読んでいる」のではなく「<b>その分岐に入る盤面が作れない</b>」。
    59・60 の教訓(その盤面が構造的に作れるかを疑う)の3度目の適用である。
    ★下限が効くのは幅の狭い窓(実測: 960px 幅で露出 39px)であり、
    verify のビューポートは 43 から 1280×800 固定である(あちらを動かすと 43-1 が意味を失う)。

  - <b>枚数の端(0枚・1枚)の守り</b>…… {@code tiles.length <= 1} を落としても、
    候補が空のときの {@code Math.min()} は Infinity を返し、
    {@code Math.min(0, Infinity)} は 0 のままである。壊しても値が変わらない。
    ★番人(65-5)は残してある —— <b>将来こちらの式を触った人</b>のための門である。

使い方: python3 tools/batch65_break_check.py [ケース番号...]
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

BATTLE_JS = "src/main/resources/static/js/battle.js"
BATTLE_CSS = "src/main/resources/static/css/battle.css"

# (説明, ファイル, 置換前, 置換後, kind, 照合先の名前の一部)
CASES = [
    # ===============================================================
    # 軸1: ★★回転したタイルの外接を勘定に入れる
    #   タップ済のタイルは要素の幅こそ 64px のままだが、90度回した見た目の外接は
    #   縦の 80px になる。45 はこれを勘定に入れていなかった
    # ===============================================================
    ("回転したタイルも幅 64px として数える(45 の見立てに戻す)", BATTLE_JS,
     "    const footprint = (i) => (tapped[i] ? AUTO_MANA_TILE_HEIGHT : AUTO_MANA_TILE_WIDTH);",
     "    const footprint = (i) => AUTO_MANA_TILE_WIDTH;",
     "verify", "マナ15枚がすべてタップ済でも行から右へはみ出さない"),

    # ===============================================================
    # 軸2: ★★必要なぶんだけ重ねる(重なりの量を計算する)
    # ===============================================================
    ("必要な重なりを計算せず、まったく重ねない", BATTLE_JS,
     "    let perTileOverlap = Math.min(needed, overlapCapAt(AUTO_MANA_MIN_EXPOSURE));\n"
     "    if (needed > perTileOverlap) {\n"
     "        perTileOverlap = Math.min(needed, overlapCapAt(AUTO_MANA_HARD_EXPOSURE));\n"
     "    }",
     "    let perTileOverlap = 0;\n"
     "    if (needed > perTileOverlap) {\n"
     "        perTileOverlap = 0;\n"
     "    }",
     "verify", "マナ15枚がすべてタップ済でも行から右へはみ出さない"),

    # ===============================================================
    # 軸3: ★★★回転のぶんの下駄。45 の CSS にも在ったが詳細度で負けて効いていなかった
    # ===============================================================
    ("回転のぶんの下駄を付けない(タップ済が左隣へ食い込む)", BATTLE_JS,
     "        const base = tapped[i] ? pad : 0;",
     "        const base = 0;",
     "verify", "タップ済と非タップが混ざっても重なりは均等である"),

    # ===============================================================
    # 軸4: ★相手のマナ行にも同じ規則を当てる(片側だけ直す形に戻す)
    # ===============================================================
    ("相手のマナ行には重なりの計算を当てない", BATTLE_JS,
     "    applyAutoManaOverlap(oppManaRow);",
     "    if (false) applyAutoManaOverlap(oppManaRow);",
     "verify", "相手のマナ行も同じ規則で重なる"),

    # ===============================================================
    # 軸5: ★★重なりの前後。名前は左寄せなので、右のタイルが上でなければ先頭が隠れる
    # ===============================================================
    ("重なりの前後を逆にする(左のタイルが上に来る)", BATTLE_JS,
     "        tile.style.zIndex = String(i + 1);",
     "        tile.style.zIndex = String(tiles.length - i);",
     "verify", "重なった部分では右のタイルが上にある"),

    # ===============================================================
    # 軸6: ★★margin を書く場所は1つである。CSS 側と JS 側の両方に当てる
    #   45 はここが2箇所に散っており、詳細度でどちらが勝つかが挙動を決めていた
    # ===============================================================
    ("CSS にマナ行の margin を書き戻す(2箇所目を作る)", BATTLE_CSS,
     ".auto-mana-row .mana-tile.mana-temporary { box-shadow: inset 0 0 0 2px #6edff6; }",
     ".auto-mana-row .mana-tile + .mana-tile { margin-left: 4px; }\n"
     ".auto-mana-row .mana-tile.mana-temporary { box-shadow: inset 0 0 0 2px #6edff6; }",
     "verify", "マナ行の margin を書くのは battle.js だけである"),

    ("退役した .auto-mana-overlap を JS に書き戻す", BATTLE_JS,
     "    applyAutoManaOverlap(manaRow);",
     "    manaRow.classList.toggle('auto-mana-overlap', false);\n"
     "    applyAutoManaOverlap(manaRow);",
     "verify", "マナ行の margin を書くのは battle.js だけである"),
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


def run_verify():
    """ハーネスを作り直してから verify を回し、出力行を返す"""
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
    # ★★★開始時の姿を控えておく(62 の教訓)。壊したまま終わったら、
    #   OK が何件出ていようとこのスクリプトは失敗である。
    targets = sorted({case[1] for case in CASES})
    baseline = {path: read(path) for path in targets}
    results = []
    for number, (label, path, before, after, kind, target) in enumerate(CASES, 1):
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
