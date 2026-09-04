#!/usr/bin/env python3
"""壊し検証の共通ランナー(Batch 82・候補 P)。

★★★<b>壊すのは本体ではなく複製である。</b>
  81 までの `batchNN_break_check.py` は<b>本体を直接書き換えて、finally で書き戻す</b>形だった。
  ★<b>これは殺されると壊したまま残る</b> —— 70 で実際に起き、77・79 でも殺された。
  ★★<b>複製の上で壊せば、その事故は構造的に起きない</b>(本体を1バイトも書かないからである)。

★★★<b>2並列で回す。</b>並列度は 2 で固定である(下記の実測)。
  ★<b>3以上にしない</b> —— <b>番人が嘘をつく</b>。

---

## ★★★実測(Batch 82・この箱は `nproc` = 2)

| 測ったもの | 実測 |
|---|---|
| リポジトリの複製(43MB・`target` 込み) | **0.17秒/本** |
| `verify` 1回転(直列) | **82秒** |
| `verify` 2並列(複製2本) | **94.5秒/2本** —— ★**1.74倍速**・両方 793/793 |
| `verify` 4並列(複製4本) | 138秒/4本 —— ★★★**3本が赤**(下記) |
| JUnit 1クラス(テンプレートだけ) | 10秒 |
| JUnit 1クラス(Java 再コンパイル込み) | 22秒 |
| JUnit 2並列(複製2本) | **44.6秒/2本** —— ★★**1.0倍速(速くならない)** |
| ★**`verify` と JUnit の混走** | **87.7秒** —— ★★**verify は 793/793 のまま** |

★★★<b>JUnit は並列にしても1秒も速くならない</b> —— <b>CPU で詰まっているからである</b>。
  ★<b>それでも複製の上で回す</b> —— <b>速さのためではなく、本体を汚さないためである</b>。
  ★★<b>だから「verify だけ複製へ」ではなく「全軸を複製へ」にした</b>(規則が1つで済む)。

★★★<b>4並列で赤くなるのは「アニメーションを時間で捕まえる」2項目だけである</b>:

  - 「★★タップ表現の非対称: 場のタイルは filter(減光)だけで回らない」
  - 「★自席マナのタップは transform(回転)の遷移が実際に走る」

  ★<b>つまり並列度の天井を決めているのは、固定待ちの番人そのものである</b>
    (81 の追補・候補 U の一族)。<b>あれを事実待ちへ移すたびに、天井は上がる。</b>

★★<b>混走(verify + mvn)でも verify は全緑だった</b> ——
  <b>だから軸を種別ごとに固めず、来た順に2つの待ち行列へ流してよい</b>。

---

## 使い方

```bash
python3 tools/break_check_runner.py --batch 82            # 全軸
python3 tools/break_check_runner.py --batch 82 1 2 3      # 軸を選ぶ
python3 tools/break_check_runner.py --batch 81            # ★過去のバッチもそのまま回せる
python3 tools/break_check_runner.py --self-test           # ★★★このランナー自身の番人
```

★★★<b>`--batch NN` は `tools/batchNN_break_check.py` を<b>読み込むだけ</b>である</b> ——
  あちらの `CASES` と `EXPECTED_NG` をそのまま使う。<b>過去のスクリプトは1文字も書き換えていない。</b>

★★★<b>`--self-test` がこのランナーの番人である</b>(設計判断45: 番人は「回る場所」で選ぶ)。
  ★<b>ランナーは Python の道具であり、JUnit にも verify にも照合先が無い</b> ——
    だから番人は<b>ここにしか置けない</b>。
  ★★<b>納品前の機械チェックに1行足してある</b>(`tools/README.md`)——
    <b>置いただけで誰も回さない番人は、番人ではない</b>(70 の教訓)。

---

## 答えは4値である(裁定196・81 から据え置き)

  OK       … 狙った番人が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(番人が足りない)
  EMPTY    … その番人が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)
"""
import argparse
import importlib.util
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
M2 = "/root/m2work/repository"

# ★★★並列度は2で固定である(82 の実測。この箱は nproc = 2)。
#   ★<b>4にすると「アニメーションを時間で捕まえる」番人が3本赤くなる</b> ——
#     <b>速さのために番人に嘘をつかせるのは、検証を捨てることである</b>。
#   ★★<b>コアの数が違う箱では測り直すこと</b>(81 の追補)。
WORKERS = 2


def install_signal_traps():
    """★★★殺されても作業場を残さない(番人 S14)。

    ★<b>本体はもう汚れない</b> —— 壊すのは複製だからである。
      ★★<b>だが `finally` は、既定の SIGTERM では走らない</b> ——
        <b>82 は自分でそれを踏んだ</b>(10分で殺され、43MB×2 の複製が `/tmp` に残った)。
    ★★★<b>罠を仕掛けて例外に変えると、`finally` が走って作業場が消える</b>。
      ★<b>「片付けは自分でしてから次へ渡す」の、いちばん外側の顔である</b>(78・79・81 の教訓)。
    """
    def _raise(signum, frame):
        raise KeyboardInterrupt("signal %d" % signum)

    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, _raise)
    return _raise


def env():
    e = dict(os.environ)
    e.setdefault("NODE_PATH", "/home/claude/.npm-global/lib/node_modules")
    e.setdefault("PLAYWRIGHT_BROWSERS_PATH", "/opt/pw-browsers")
    return e


# ---- 作業場と複製 ----

def make_workspace():
    """★★作業場は<b>本体の木の外</b>に作る(番人 S8)。

    ★<b>木の中に作ると、`git status` と `git diff --stat` が作業場を数え始める</b> ——
      <b>本体が汚れていないことを確かめる目そのものが曇る</b>(70 の教訓の親戚)。
    """
    return tempfile.mkdtemp(prefix="qte-break-")


def clone_into(base, src, name):
    """★複製は `cp -a` で作る(実測 0.17秒/本・43MB)。

    ★★<b>ハードリンクにしてはいけない</b> —— 複製の上で開いて書くと<b>本体の中身が変わる</b>。
    """
    dest = os.path.join(base, name)
    subprocess.run(["cp", "-a", src, dest], check=True)
    return dest


def inside(root, path):
    r = os.path.realpath(root)
    p = os.path.realpath(path)
    return p == r or p.startswith(r + os.sep)


# ---- 読み書き(★宛先のガードつき) ----

def read_at(work_root, path):
    with open(os.path.join(work_root, path), encoding="utf-8") as handle:
        return handle.read()


def write_into(work_root, path, text):
    """★★★複製の外へは書かせない(番人 S7)。

    ★<b>「複製の上で壊す」は、書く直前に1回測って初めて事実になる</b> ——
      <b>「そうしているはず」と書いたコメントは証拠ではない</b>(81 の教訓 9-1)。
    """
    target = os.path.join(work_root, path)
    if not inside(work_root, target):
        raise RuntimeError("複製の外へ書こうとした: %s" % target)
    with open(target, "w", encoding="utf-8") as handle:
        handle.write(text)
    # ★★書き戻せたことを読み返して確かめる(62 の教訓)——
    #   「やった」と「戻した」は別の主張である。
    if read_at(work_root, path) != text:
        raise RuntimeError("書き戻しに失敗した: %s" % target)


# ---- verify(実測) ----

def run_verify(work_root):
    build = subprocess.run([sys.executable, "verify/build_harness.py"],
                           cwd=work_root,  # ★ハーネスは複製の中で作る(番人 S11)
                           capture_output=True, text=True, env=env())
    if build.returncode != 0:
        return None
    done = subprocess.run(["node", "verify/verify.js"],
                          cwd=work_root,  # ★verify は複製の中で走る(番人 S12)
                          capture_output=True, text=True, env=env())
    return done.stdout


def verify_verdict(out, fragment):
    if out is None:
        return "EMPTY"
    hits = [line for line in out.splitlines()
            if fragment in line and (line.startswith("PASS") or line.startswith("FAIL"))]
    if not hits:
        return "EMPTY"
    return "OK" if any(line.startswith("FAIL") for line in hits) else "NG"


# ---- JUnit ----

def junit_report(work_root, test_class):
    """★報告書は<b>複製から</b>読む(番人 S10)。

    ★★<b>本体の報告書を読むと、複製で壊した結果ではなく前回の緑を読む</b> ——
      <b>いちばん静かな嘘である</b>。
    """
    return os.path.join(work_root, "target/surefire-reports",
                        "TEST-com.example.qte.%s.xml" % test_class)


def run_junit(work_root, test_class):
    """1クラスだけ回す。★surefire:test ではなく test を使う(裁定208: あちらはコンパイルしない)"""
    report = junit_report(work_root, test_class)
    if os.path.exists(report):
        os.remove(report)
    subprocess.run(["mvn", "-o", "-B", "-q",
                    "-Dmaven.repo.local=%s" % M2,
                    "-Dtest=%s" % test_class, "-DfailIfNoTests=false", "test"],
                   cwd=work_root,  # ★JUnit は複製の中で走る(番人 S9)
                   capture_output=True, text=True, env=env())
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


# ---- 並列度の実測(番人 S6) ----

class _Gauge:
    """★同時に走ったワーカーの最大数を数える。

    ★★<b>「2並列にした」ではなく「2を超えていない」を測る</b> ——
      <b>設定を読むのではなく、実際に何本走ったかを数える</b>(設計判断41 の形)。
    """

    def __init__(self):
        self.live = 0
        self.peak = 0
        self._lock = threading.Lock()

    def enter(self):
        with self._lock:
            self.live += 1
            self.peak = max(self.peak, self.live)

    def leave(self):
        with self._lock:
            self.live -= 1


# ---- 1軸を当てる ----

def apply_case(work_root, number, case, gauge, stub=None):
    label, path, before, after, kind, cls, target = case
    original = read_at(work_root, path)
    hits = original.count(before)
    if hits != 1:
        # ★★改変が当たっていないことを、当たったふりで通さない(SETUP-NG・番人 S4)
        return (number, label, "SETUP-NG", target)
    write_into(work_root, path, original.replace(before, after))
    gauge.enter()
    try:
        if stub is not None:
            answer = stub(work_root, kind, cls, target)
        elif kind == "junit":
            answer = junit_verdict(run_junit(work_root, cls), target)
        else:
            answer = verify_verdict(run_verify(work_root), target)
    finally:
        gauge.leave()
        write_into(work_root, path, original)  # ★片付けは自分でする(番人 S3・78 の教訓)
    return (number, label, answer, target)


# ---- 本体 ----

def run(cases, expected_ng=None, picked=None, root=None, workers=WORKERS,
        stub=None, quiet=False):
    """壊し検証を複製の上で回す。

    戻り値は報告の辞書である ——
    `results` / `peak`(同時に走った最大数)/ `dirty_root`(本体で変わったファイル)/
    `dirty_copies`(複製で戻らなかったファイル)/ `workspace_outside`(作業場が本体の外か)。
    """
    root = root if root is not None else ROOT
    expected_ng = expected_ng or {}
    picked = picked or list(range(1, len(cases) + 1))
    targets = sorted({case[1] for case in cases})

    # ★★★開始時の姿を控えておく(70 の教訓)。壊したまま終わったら、
    #   OK が何件出ていようとこの回は失敗である。
    baseline = {path: read_at(root, path) for path in targets}

    queue = [(number, cases[number - 1]) for number in picked]
    gauge = _Gauge()
    results = []
    lock = threading.Lock()
    cursor = iter(queue)

    base = make_workspace()
    try:
        roots = [clone_into(base, root, "w%d" % (index + 1)) for index in range(workers)]
        # ★★複製は本体の「いまの姿」から作る —— 使い回さない(番人 S13)
        errors = []

        def worker(work_root):
            while True:
                with lock:
                    item = next(cursor, None)
                if item is None:
                    return
                number, case = item
                try:
                    answer = apply_case(work_root, number, case, gauge, stub)
                except Exception as exc:  # noqa: BLE001 —— 1軸の事故で残りを道連れにしない
                    answer = (number, case[0], "ERROR: %s" % exc, case[6])
                    with lock:
                        errors.append(exc)
                with lock:
                    results.append(answer)
                    if not quiet:
                        print("%2d %-10s %s  →  %s" % (answer[0], answer[2], answer[1], answer[3]),
                              flush=True)

        threads = [threading.Thread(target=worker, args=(work_root,), daemon=True)
                   for work_root in roots]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()

        dirty_copies = sorted({
            "%s:%s" % (os.path.basename(work_root), path)
            for work_root in roots for path in targets
            if read_at(work_root, path) != baseline[path]
        })
    finally:
        shutil.rmtree(base, ignore_errors=True)

    results.sort(key=lambda row: row[0])
    fixed = []
    for number, label, answer, target in results:
        if answer == "NG" and label in expected_ng:
            answer = "NG(想定内)"
        fixed.append((number, label, answer, target))

    dirty_root = [path for path in targets if read_at(root, path) != baseline[path]]
    return {
        "results": fixed,
        "peak": gauge.peak,
        "workers": workers,
        "dirty_root": dirty_root,
        "dirty_copies": dirty_copies,
        # ★作業場は<b>リポジトリの木</b>の外でなければならない ——
        #   偽の木で回しているときも、測る相手は本物の ROOT である。
        "workspace_outside": not inside(ROOT, base),
        "errors": errors,
    }


def load_batch(number):
    path = os.path.join(ROOT, "tools", "batch%s_break_check.py" % number)
    if not os.path.exists(path):
        raise SystemExit("そのバッチの壊し検証が無い: %s" % path)
    spec = importlib.util.spec_from_file_location("batch%s_break_check" % number, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# ---- ★★★このランナー自身の番人(--self-test) ----

def self_test():
    """★★★ランナーの不変量を、偽の木と偽の軸で測る。

    ★<b>本物の verify も mvn も回さない</b> —— 測りたいのは<b>ランナーの段取り</b>であって、
      あちらの中身ではないからである(設計判断45: 番人は「回る場所」で選ぶ)。
    ★★<b>ただし「JUnit / verify が複製の中で走るか」だけは本物の関数で測る</b> ——
      <b>そこはスタブでは通ってしまう</b>(77 の教訓: 用意した側は「使われている」と思い込む)。
    """
    checks = []

    def check(name, ok, detail=""):
        checks.append((name, bool(ok), detail))

    # ---- A. 行列の検査(偽の木・偽の軸) ----
    fake = tempfile.mkdtemp(prefix="qte-selftest-root-")
    try:
        # ★★★偽の軸は<b>1本ずつ別のファイル</b>を壊す ——
        #   <b>同じファイルを使い回すと、片付けを壊した軸が次の軸まで道連れにする</b>。
        #   ★<b>そうなると1つの改変が2つの番人に当たり、どちらが壊れたか読めない</b>
        #     (81 の教訓 3-6: 別の性質を1つの番人にまとめない、の裏返し)。
        for index in range(1, 7):
            with open(os.path.join(fake, "impl%d.txt" % index), "w", encoding="utf-8") as handle:
                handle.write("GOOD\n")

        seen = []

        def stub(work_root, kind, cls, target):
            # ★複製の中の姿を読む。壊れていれば「番人が落ちた」= OK を返す。
            body = read_at(work_root, cls)
            seen.append((target, body.strip(), work_root))  # ★list.append は不可分である
            # ★★わざと少し掛ける —— <b>速すぎると片方のワーカーが全部さらってしまい、
            #   「2本とも走ったか」を測れない</b>(遅らせを入れて初めて測れる・79 の教訓の形)。
            time.sleep(0.05)
            return "OK" if "BROKEN" in body else "NG"

        cases = [
            ("壊しが複製に載る(1)", "impl1.txt", "GOOD", "BROKEN", "stub", "impl1.txt", "番人A"),
            ("壊しが複製に載る(2)", "impl2.txt", "GOOD", "BROKEN", "stub", "impl2.txt", "番人B"),
            ("壊しが複製に載る(3)", "impl3.txt", "GOOD", "BROKEN", "stub", "impl3.txt", "番人C"),
            ("壊しが複製に載る(4)", "impl4.txt", "GOOD", "BROKEN", "stub", "impl4.txt", "番人D"),
            ("当たらない改変", "impl5.txt", "ここには無い文字列", "X", "stub", "impl5.txt", "番人E"),
            ("壊しが複製に載る(5)", "impl6.txt", "GOOD", "BROKEN", "stub", "impl6.txt", "番人F"),
        ]
        # ★★★<b>中身だけでなく更新時刻も控える</b> ——
        #   <b>「壊して戻した」は「触っていない」ではない</b>。
        #   ★<b>中身だけを見ていると、本体を壊してから書き戻す形が素通りする</b> ——
        #     <b>それこそが 70 の事故(殺されて壊したまま残る)の正体である</b>。
        stamp = {name: os.stat(os.path.join(fake, name)).st_mtime_ns
                 for name in os.listdir(fake)}

        report = run(cases, root=fake, stub=stub, quiet=True)
        answers = {row[0]: row[2] for row in report["results"]}
        used = {work_root for _, _, work_root in seen}
        after = {name: os.stat(os.path.join(fake, name)).st_mtime_ns
                 for name in os.listdir(fake)}

        check("S1 本体の対象ファイルは一度も書き換えられない",
              report["dirty_root"] == [] and after == stamp
              and all(read_at(fake, "impl%d.txt" % i) == "GOOD\n" for i in range(1, 7)),
              "dirty_root=%s 更新された=%s"
              % (report["dirty_root"], sorted(k for k in after if after[k] != stamp.get(k))))
        check("S2 壊しは複製に載る(壊した軸は OK になる)",
              all(answers.get(n) == "OK" for n in (1, 2, 3, 4, 6)),
              "answers=%s" % answers)
        check("S3 各軸のあと複製は元へ戻る",
              report["dirty_copies"] == [],
              "dirty_copies=%s" % report["dirty_copies"])
        check("S4 一致が1箇所でなければ SETUP-NG",
              answers.get(5) == "SETUP-NG", "answer5=%s" % answers.get(5))
        # ★★★上限の 2 は<b>ここに書く</b> —— WORKERS を上げた人が、ここで赤を見るためである。
        #   ★<b>設定を読み直す番人は、設定を変えた瞬間に一緒に動いてしまう</b>(設計判断41)。
        check("S6 同時に走るワーカーは2を超えない(82 の実測)",
              0 < report["peak"] <= 2,
              "peak=%d" % report["peak"])
        check("S8 作業場は本体の木の外にある",
              report["workspace_outside"], "")
        # ★<b>ワーカーの数そのものは読み直してよい</b> —— ここが測るのは
        #   「起こした数だけ<b>別々の複製で</b>実際に走ったか」であって、上限の話ではない(それは S6)。
        check("S13 ワーカーは起こした数だけ別々の複製で走る",
              len(used) == report["workers"] and report["peak"] >= 2,
              "used=%d workers=%d peak=%d" % (len(used), report["workers"], report["peak"]))
    finally:
        shutil.rmtree(fake, ignore_errors=True)

    # ---- B. 直接の検査(宛先そのもの) ----
    base = make_workspace()
    try:
        work_root = os.path.join(base, "w1")
        os.makedirs(os.path.join(work_root, "verify"))
        with open(os.path.join(work_root, "impl.txt"), "w", encoding="utf-8") as handle:
            handle.write("GOOD\n")
        escaped = False
        try:
            write_into(work_root, os.path.join("..", "escape.txt"), "x")
        except RuntimeError:
            escaped = True
        check("S7 複製の外へは書けない", escaped, "")

        check("S10 JUnit の報告書は複製から読む",
              inside(work_root, junit_report(work_root, "SomeTest")),
              junit_report(work_root, "SomeTest"))

        # ★★★EMPTY の判定は<b>入口が2つある</b>(verify と JUnit)——
        #   <b>規則が n 入口ぶんあるなら、番人も n 入口ぶん要る</b>(77・79・80・81 の教訓)。
        check("S5 verify の照合先が1件も走らなければ EMPTY",
              verify_verdict("PASS  よその項目\nFAIL  もっとよその項目\n", "在らぬ番人") == "EMPTY"
              and verify_verdict(None, "何でもよい") == "EMPTY",
              "verify_verdict=%s" % verify_verdict("PASS  よその項目\n", "在らぬ番人"))
        empty_xml = os.path.join(work_root, "empty.xml")
        with open(empty_xml, "w", encoding="utf-8") as handle:
            handle.write('<testsuite><testcase name="よその番人"/></testsuite>')
        # ★★★殺されたときの片付けは<b>罠が仕掛けてあるかどうか</b>で決まる ——
        #   <b>82 は実際に殺され、43MB×2 の複製を `/tmp` に残した</b>。
        trap = install_signal_traps()
        raised = False
        try:
            trap(signal.SIGTERM, None)
        except KeyboardInterrupt:
            raised = True
        check("S14 割り込みの罠が仕掛けてあり、例外に変える",
              raised and signal.getsignal(signal.SIGTERM) is trap
              and signal.getsignal(signal.SIGINT) is trap, "")
        check("S5b JUnit の照合先が1件も走らなければ EMPTY",
              junit_verdict(empty_xml, "在らぬ番人") == "EMPTY"
              and junit_verdict(None, "何でもよい") == "EMPTY",
              "junit_verdict=%s" % junit_verdict(empty_xml, "在らぬ番人"))

        # ★★<b>本物の run_junit / run_verify の宛先を測る</b>。
        #   ★<b>外へ出さないよう subprocess を差し替え、そのうえで自分で片付ける</b>(79・81 の教訓)。
        calls = []
        real_run = subprocess.run

        class _Done:
            returncode = 0
            stdout = ""

        def recorder(argv, **kwargs):
            calls.append((argv, kwargs.get("cwd")))
            return _Done()

        subprocess.run = recorder
        try:
            run_junit(work_root, "SomeTest")
            junit_cwds = [cwd for _, cwd in calls]
            calls.clear()
            run_verify(work_root)
            verify_cwds = [cwd for _, cwd in calls]
        finally:
            subprocess.run = real_run  # ★差し替えは自分で戻す(79 の教訓)

        check("S9 JUnit は複製の中で走る",
              junit_cwds and all(cwd == work_root for cwd in junit_cwds),
              "cwd=%s" % junit_cwds)
        check("S11 verify のハーネス生成は複製の中で走る",
              len(verify_cwds) >= 1 and verify_cwds[0] == work_root,
              "cwd=%s" % verify_cwds[:1])
        check("S12 verify の実行は複製の中で走る",
              len(verify_cwds) >= 2 and verify_cwds[1] == work_root,
              "cwd=%s" % verify_cwds[1:2])
    finally:
        shutil.rmtree(base, ignore_errors=True)

    for name, ok, detail in checks:
        print("%s  %s%s" % ("PASS" if ok else "FAIL", name,
                            ("  -- " + detail) if detail and not ok else ""))
    bad = [name for name, ok, _ in checks if not ok]
    print("\n%d/%d passed" % (len(checks) - len(bad), len(checks)))
    return 1 if bad else 0


def main():
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("--batch", help="tools/batchNN_break_check.py の NN")
    parser.add_argument("--self-test", action="store_true",
                        help="★このランナー自身の番人を回す")
    parser.add_argument("--workers", type=int, default=WORKERS,
                        help="並列度(既定 %d。★3以上にしないこと)" % WORKERS)
    parser.add_argument("numbers", nargs="*", type=int)
    args = parser.parse_args()

    install_signal_traps()  # ★殺されても作業場を残さない(番人 S14)
    if args.self_test:
        return self_test()
    if not args.batch:
        parser.error("--batch か --self-test のどちらかが要る")

    module = load_batch(args.batch)
    cases = module.CASES
    expected_ng = getattr(module, "EXPECTED_NG", {})
    picked = args.numbers or list(range(1, len(cases) + 1))

    report = run(cases, expected_ng=expected_ng, picked=picked, workers=args.workers)

    counts = {}
    for _, _, answer, _ in report["results"]:
        counts[answer] = counts.get(answer, 0) + 1
    print("\n" + " / ".join("%s %d" % (key, counts[key]) for key in sorted(counts)))
    print("★同時に走った最大数: %d(上限 %d)" % (report["peak"], report["workers"]))

    if report["dirty_root"]:
        print("\n★★★本体が汚れている(あってはならない):")
        for path in report["dirty_root"]:
            print("    " + path)
        return 1
    print("★本体は一度も書き換えていない。")
    if report["dirty_copies"]:
        print("★複製で戻らなかったもの(作業場は消したので実害は無いが、片付けの穴である):")
        for item in report["dirty_copies"]:
            print("    " + item)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
