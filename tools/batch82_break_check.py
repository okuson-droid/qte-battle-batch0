#!/usr/bin/env python3
"""Batch 82(壊し検証を複製の上で2並列に = 候補 P)の壊し検証(裁定116)。

★★★<b>このバッチが作ったのは「壊し検証を回す道具」そのものである</b>
  (`tools/break_check_runner.py`)。★<b>したがって壊す相手もその道具である</b>。

★★★<b>このスクリプトは、そのランナーを使わない。</b>
  <b>壊す相手を、壊す道具として使うことはできない</b> ——
  軸1でランナーの並列度を壊した状態のまま、そのランナーに軸2を回させるのは
  <b>壊れた番人に自分の壊れ具合を尋ねること</b>である。
  ★<b>だからここでは自前で複製を1本作り、複製の中のランナーを壊して
    `--self-test` を回す</b>(複製は実測 0.17秒/本)。
  ★★<b>本体は1バイトも触らない</b> —— このスクリプトは本体に `open(..., "w")` を1度もしない。

---

## ★★★軸と番人は1対1である(81 の教訓 3-6)

`break_check_runner.py --self-test` が持つ番人は **15本**であり、
<b>15軸それぞれが、ちょうど1本ずつを落とす</b>。

| 軸 | 壊すもの | 落ちる番人 |
|---|---|---|
| 1 | 並列度を 4 にする | S6 同時に走るワーカーは2を超えない |
| 2 | ワーカーを1本しか起こさない | S13 起こした数だけ別々の複製で走る |
| 3 | ワーカーの1本だけが本体を指す | S1 本体は一度も書き換えられない |
| 4 | 書き込みのガードを緩める | S7 複製の外へは書けない |
| 5 | 作業場を本体の木の中に作る | S8 作業場は本体の木の外にある |
| 6 | 片付けの書き戻しをしない | S3 各軸のあと複製は元へ戻る |
| 7 | 壊しを当てずに書き戻す | S2 壊しは複製に載る |
| 8 | SETUP-NG の判定を外す | S4 一致が1箇所でなければ SETUP-NG |
| 9 | verify の EMPTY を NG と答える | S5 verify の EMPTY |
| 10 | JUnit の EMPTY を NG と答える | S5b JUnit の EMPTY |
| 11 | JUnit を本体で走らせる | S9 JUnit は複製の中で走る |
| 12 | JUnit の報告書を本体から読む | S10 報告書は複製から読む |
| 13 | verify のハーネス生成を本体で走らせる | S11 ハーネス生成は複製の中 |
| 14 | verify を本体で走らせる | S12 verify の実行は複製の中 |
| 15 | 割り込みの罠を外す | S14 罠が仕掛けてあり例外に変える |

★★★<b>1対1であることは実測した</b> ——
  <b>15軸のどれも、落とす番人はちょうど1本である</b>(設計解説 6-2)。
  ★<b>「1本ずつ当てたつもり」ではなく、当てて数えた</b>。

★★★<b>軸3 が「1本だけ」なのには理由がある。</b>
  <b>2本とも本体を指すように壊すと、S1 と S13(別々の複製で走る)が同時に落ちる</b> ——
  ★<b>1つの改変が2つの番人に当たったら、それは軸の切り方が悪い</b>(81 の教訓 3-6)。
  ★★<b>しかもこの形は、このプロジェクトが何度も踏んできた「片肺」そのものである</b>
    (71・76・77・80・81)—— <b>片方の入口だけが直っていない</b>。

★★★<b>軸7(壊しを当てない)は S2 にしか当たらない。</b>
  ★<b>複製は一度も壊れないので S3 は緑のままである</b> ——
    <b>「壊せていない」と「片付けていない」は別の壊れ方である</b>。

★★★<b>EMPTY の判定は入口が2つある</b>(verify と JUnit)。
  ★<b>だから軸も番人も2本ずつある</b>(77・79・80・81 の教訓:
  規則が n 入口ぶんあるなら、番人も n 入口ぶん要る)。

---

## ★★★壊しどころが無いもの(裁定196 の正直な扱い)

1. **本体そのものの差分照合(`dirty_root`)。**
   ★これは<b>番人 S1 の道具</b>であって、番人ではない ——
   <b>S1 は更新時刻まで見ているので、`dirty_root` を殺しても S1 は落ちる</b>。
   ★★<b>「守りが二重にある」形である</b>(80 の12番目の形)。<b>軸には立てない。</b>

2. **`cp -a` そのもの。**
   ★複製に失敗すれば `subprocess.run(..., check=True)` が例外を投げ、
   <b>self-test はそもそも動かない</b>。★★<b>落ちる番人ではなく、落ちる床である。</b>

3. **JUnit と verify の中身。**
   ★82 は Java も JS も CSS もカード定義も<b>1文字も触っていない</b> ——
   <b>触っていないものは壊せない</b>。★★<b>だから版数も上げていない</b>(7-5)。

4. **`--batch NN` の読み込み。**
   ★過去の `batchNN_break_check.py` を<b>読み込むだけ</b>で、1文字も書き換えていない。
   ★★<b>読み込みが壊れれば `CASES` が取れず、その場で止まる</b>(これも床である)。

使い方: python3 tools/batch82_break_check.py [ケース番号...]
★★<b>1軸あたり約1秒である</b>(self-test は本物の verify も mvn も回さない)——
  <b>分けて回す必要が無い</b>。それでも前後で `git status --porcelain` を突き合わせること。
"""
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RUNNER = "tools/break_check_runner.py"

# ★壊しても落ちないことが分かっているもの(理由つき)。
#   ★★<b>Batch 82 には1件も無い。</b>15軸すべてが落ちるはずである。
EXPECTED_NG = {}

# (説明, 置換前, 置換後, 落ちるはずの番人の名前の一部)
CASES = [
    # ===============================================================
    # I. 並列そのもの(軸1・2)
    # ===============================================================

    # 軸1: ★★★並列度を上げてしまう
    #   ★<b>4にすると「アニメーションを時間で捕まえる」番人が3本赤くなる</b>(82 の実測)——
    #     <b>速さのために番人に嘘をつかせるのは、検証を捨てることである</b>。
    ("並列度を 4 にする",
     "WORKERS = 2",
     "WORKERS = 4",
     "S6 同時に走るワーカーは2を超えない"),

    # 軸2: ★ワーカーを1本しか起こさない
    #   ★<b>「2並列にした」と書いてあっても、走っているのが1本なら 1.0倍速である</b> ——
    #     <b>設定ではなく、実際に何本走ったかを数えること</b>。
    ("ワーカーを1本しか起こさない",
     "                   for work_root in roots]",
     "                   for work_root in roots[:1]]",
     "S13 ワーカーは起こした数だけ別々の複製で走る"),

    # ===============================================================
    # II. 本体を汚さないこと(軸3〜5)★★★このバッチの中心である
    # ===============================================================

    # 軸3: ★★★ワーカーの1本だけが本体を指す(=<b>片肺</b>)
    #   ★<b>2本とも本体にすると S13 も道連れになる</b>ので、1本だけ壊す(冒頭の表を参照)。
    #   ★★<b>壊して書き戻すので、中身を見るだけの番人では素通りする</b> ——
    #     <b>S1 が更新時刻まで見ているから落ちる</b>。
    ("ワーカーの1本だけが本体を指す(片肺)",
     "                   for work_root in roots]",
     "                   for work_root in [root] + roots[1:]]",
     "S1 本体の対象ファイルは一度も書き換えられない"),

    # 軸4: ★書き込みのガードを緩める
    #   ★<b>「複製の上で壊している」は、書く直前に1回測って初めて事実になる</b>
    #     (81 の教訓 9-1: 「絞っている」と書いたコメントは証拠ではない)。
    ("書き込みのガードを緩める",
     "    if not inside(work_root, target):\n"
     '        raise RuntimeError("複製の外へ書こうとした: %s" % target)',
     "    if False:\n"
     '        raise RuntimeError("複製の外へ書こうとした: %s" % target)',
     "S7 複製の外へは書けない"),

    # 軸5: ★作業場を本体の木の中に作る
    #   ★<b>木の中に作ると `git status` が作業場を数え始める</b> ——
    #     <b>本体が汚れていないことを確かめる目そのものが曇る</b>。
    ("作業場を本体の木の中に作る",
     '    return tempfile.mkdtemp(prefix="qte-break-")',
     '    return tempfile.mkdtemp(prefix="qte-break-", dir=ROOT)',
     "S8 作業場は本体の木の外にある"),

    # ===============================================================
    # III. 壊しと片付け(軸6・7)
    #      ★★<b>「壊せていない」と「片付けていない」は別の壊れ方である</b>
    # ===============================================================

    # 軸6: ★★片付けの書き戻しをしない(78 の教訓)
    #   ★<b>複製は消えるので実害は出ないが、同じ複製を使い回す次の軸が
    #     SETUP-NG になる</b> —— <b>黙って軸が1本死ぬ</b>。
    ("片付けの書き戻しをしない",
     "        gauge.leave()\n"
     "        write_into(work_root, path, original)  # ★片付けは自分でする(番人 S3・78 の教訓)",
     "        gauge.leave()",
     "S3 各軸のあと複製は元へ戻る"),

    # 軸7: ★壊しを当てずに書き戻す
    #   ★<b>これがいちばん静かな壊れ方である</b> —— <b>全軸が NG になり、
    #     「番人が足りない」と読み違える</b>(75・80 の教訓: NG が出たらまず番人を疑う、
    #     の<b>さらに手前</b>にある形)。
    ("壊しを当てずに書き戻す",
     "    write_into(work_root, path, original.replace(before, after))",
     "    write_into(work_root, path, original)",
     "S2 壊しは複製に載る"),

    # ===============================================================
    # IV. 答えの4値(軸8〜10)★<b>EMPTY は入口が2つある</b>
    # ===============================================================

    # 軸8: ★SETUP-NG の判定を外す
    #   ★<b>当たっていない改変を「当たったふり」で通すと、NG が出ない代わりに
    #     何も測っていない緑が出る</b>。
    ("SETUP-NG の判定を外す",
     "    if hits != 1:",
     "    if False:",
     "S4 一致が1箇所でなければ SETUP-NG"),

    # 軸9: ★verify の EMPTY を NG と答える
    ("verify の EMPTY を NG と答える",
     '    if not hits:\n'
     '        return "EMPTY"\n'
     '    return "OK" if any(line.startswith("FAIL") for line in hits) else "NG"',
     '    if not hits:\n'
     '        return "NG"\n'
     '    return "OK" if any(line.startswith("FAIL") for line in hits) else "NG"',
     "S5 verify の照合先が1件も走らなければ EMPTY"),

    # 軸10: ★JUnit の EMPTY を NG と答える(★<b>入口が2つあるので番人も2本ある</b>)
    ("JUnit の EMPTY を NG と答える",
     '    if not hits:\n'
     '        return "EMPTY"\n'
     '    broke = any(tc.find("failure") is not None or tc.find("error") is not None',
     '    if not hits:\n'
     '        return "NG"\n'
     '    broke = any(tc.find("failure") is not None or tc.find("error") is not None',
     "S5b JUnit の照合先が1件も走らなければ EMPTY"),

    # ===============================================================
    # V. 走らせる場所(軸11〜14)
    #    ★★★<b>出口ごとに当てる</b>(71・75〜81 の教訓)——
    #      JUnit の実行 / JUnit の報告書 / verify のハーネス / verify の実行
    # ===============================================================

    # 軸11: ★JUnit を本体で走らせる
    ("JUnit を本体で走らせる",
     "                   cwd=work_root,  # ★JUnit は複製の中で走る(番人 S9)",
     "                   cwd=ROOT,  # ★JUnit は複製の中で走る(番人 S9)",
     "S9 JUnit は複製の中で走る"),

    # 軸12: ★★JUnit の報告書を本体から読む
    #   ★<b>いちばん静かな嘘である</b> —— <b>複製で壊した結果ではなく、本体の前回の緑を読む</b>。
    #     <b>全軸が NG になり、しかもそれらしく見える</b>。
    ("JUnit の報告書を本体から読む",
     '    return os.path.join(work_root, "target/surefire-reports",',
     '    return os.path.join(ROOT, "target/surefire-reports",',
     "S10 JUnit の報告書は複製から読む"),

    # 軸13: ★verify のハーネス生成を本体で走らせる
    #   ★<b>ハーネスは本体の `verify/harness*.html` を書き換える</b> ——
    #     <b>本体を汚す形でありながら、`git` は追っていない</b>(生成物である)。
    ("verify のハーネス生成を本体で走らせる",
     "                           cwd=work_root,  # ★ハーネスは複製の中で作る(番人 S11)",
     "                           cwd=ROOT,  # ★ハーネスは複製の中で作る(番人 S11)",
     "S11 verify のハーネス生成は複製の中で走る"),

    # 軸14: ★verify を本体で走らせる
    #   ★<b>壊したのは複製なのに、測るのは本体</b> —— <b>全軸が NG になる</b>。
    ("verify を本体で走らせる",
     "                          cwd=work_root,  # ★verify は複製の中で走る(番人 S12)",
     "                          cwd=ROOT,  # ★verify は複製の中で走る(番人 S12)",
     "S12 verify の実行は複製の中で走る"),

    # ===============================================================
    # VI. 殺されたときの片付け(軸15)
    #     ★★★<b>82 は自分でこれを踏んだ</b> —— 10分で殺され、
    #       <b>43MB×2 の複製が `/tmp` に残った</b>(設計解説 4章)。
    # ===============================================================

    # 軸15: ★★★割り込みの罠を外す
    #   ★<b>本体はもう汚れない</b>(壊すのは複製だから)——
    #     <b>だから 70 の事故は起きない</b>。★★<b>だが作業場は残る</b>。
    #   ★★★<b>「直したあとに何が新しく起きるか」を1つ考える</b>(78 の教訓)——
    #     <b>複製にしたことで、新しく「作業場の後始末」が生まれた</b>。
    ("割り込みの罠を外す",
     "    for sig in (signal.SIGTERM, signal.SIGINT):\n"
     "        signal.signal(sig, _raise)",
     "    for sig in ():\n"
     "        signal.signal(sig, _raise)",
     "S14 割り込みの罠が仕掛けてあり、例外に変える"),
]


def self_test_in(work_root):
    """複製の中の `--self-test` を回して、行ごとの結果を返す。"""
    done = subprocess.run([sys.executable, RUNNER, "--self-test"],
                          cwd=work_root, capture_output=True, text=True)
    return done.stdout


def verdict(out, fragment):
    hits = [line for line in out.splitlines()
            if fragment in line and (line.startswith("PASS") or line.startswith("FAIL"))]
    if not hits:
        return "EMPTY"
    return "OK" if any(line.startswith("FAIL") for line in hits) else "NG"


def main():
    picked = [int(a) for a in sys.argv[1:]] or list(range(1, len(CASES) + 1))
    results = []
    for number, (label, before, after, target) in enumerate(CASES, 1):
        if number not in picked:
            continue
        base = tempfile.mkdtemp(prefix="qte-b82-")
        try:
            work_root = os.path.join(base, "w")
            subprocess.run(["cp", "-a", ROOT, work_root], check=True)
            path = os.path.join(work_root, RUNNER)
            with open(path, encoding="utf-8") as handle:
                original = handle.read()
            hits = original.count(before)
            if hits != 1:
                results.append((number, label, "SETUP-NG"))
                print("%2d SETUP-NG  %s (一致 %d 箇所)" % (number, label, hits))
                continue
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(original.replace(before, after))
            answer = verdict(self_test_in(work_root), target)
        finally:
            shutil.rmtree(base, ignore_errors=True)
        if answer == "NG" and label in EXPECTED_NG:
            answer = "NG(想定内)"
        results.append((number, label, answer))
        print("%2d %-10s %s  →  %s" % (number, answer, label, target))

    counts = {}
    for _, _, answer in results:
        counts[answer] = counts.get(answer, 0) + 1
    print("\n" + " / ".join("%s %d" % (key, counts[key]) for key in sorted(counts)))

    # ★★★<b>本体は1バイトも触っていないはずである。</b>
    #   <b>このスクリプトは本体を対象に `open(..., "w")` を1度も呼んでいない</b> ——
    #   <b>それでも確かめる</b>(70 の教訓: 「やった」と「そうなっている」は別の主張である)。
    dirty = subprocess.run(["git", "status", "--porcelain"],
                           cwd=ROOT, capture_output=True, text=True).stdout
    changed = [line for line in dirty.splitlines() if RUNNER in line and not line.startswith("??")]
    if changed:
        print("\n★★★ランナーが壊れたまま残っている(あってはならない):")
        for line in changed:
            print("    " + line)
        return 1
    print("★本体のランナーは一度も書き換えていない。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
