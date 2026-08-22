#!/usr/bin/env python3
"""Batch 63(デッキファイル形式の一本化)の壊し検証(裁定116)。

実装をわざと壊し、狙った試験が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った試験が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(試験が足りない)
  EMPTY    … その試験が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★★<b>照合先が2つある。</b>63 の番人は JUnit と verify の両方に居る ——
  読み取りと入口は Java、画面の要約と欄名の突き合わせはブラウザだからである。
  ケースごとに kind("junit" / "verify")で振り分ける。

★★"surefire:test" 単体で回してはいけない(裁定208)。必ず "test" を回すこと。
★★改変は「軸」ごとに1件ずつ当てる(57 の教訓)。今回の軸は次の9つである。
  (1) 形式の門(format / version)   (2) 欄の名前(63 の本体)
  (3) 欄名の突き合わせ(verify)     (4) 読み取りの寛容さ(手動モードと揃える)
  (5) 読み取りは裁かない(まとめない) (6) 外から来るデータの上限
  (7) 本物の入口(ロビー)           (8) 画面の要約
  (9) 退役(リンク)

★壊しどころが無い項目(意図的に含めていないもの):
  - 退役したファイル(deck-builder.html / deck-builder.js / CardApiController)の復活 ……
    <b>ファイルを戻すのは改変ではなく新設である</b>。番人(verify 63-4 と
    LobbyPageTest の 404)は存在そのものを測っているので、壊す形が
    「消したものを書き戻す」しかない。他の軸と同じ土俵に乗らないため外した。
  - decks/*.json 12本すべてを旧形式に戻すこと …… 1本で落ちるものを12本で測っても
    分かることは増えない(ケース12 が1本で当てている)。

使い方: python3 tools/batch63_break_check.py [ケース番号...]
"""
import glob
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MVN = ["mvn", "-o", "-B", "-q", "-Dmaven.repo.local=/root/m2work/repository",
       "test", "-DfailIfNoTests=false"]

READER = "src/main/java/com/example/qte/deck/DeckFileReader.java"
VALIDATOR = "src/main/java/com/example/qte/deck/DeckValidator.java"
LOBBY_JAVA = "src/main/java/com/example/qte/web/LobbyController.java"
LOBBY_HTML = "src/main/resources/templates/lobby.html"
SAMPLE_DECK = "decks/batch54-dark-check-deck.json"

TREADER = "com.example.qte.DeckFileReaderTest"
TLOBBY = "com.example.qte.LobbyPageTest"

# (説明, ファイル, 置換前, 置換後, kind, 照合先)
#   kind "junit"  … 照合先は (テストクラス, テストメソッド)
#   kind "verify" … 照合先は 検証の名前の一部
CASES = [
    # ===============================================================
    # 軸1: 形式の門(format / version)
    # ===============================================================
    ("形式名がデッキメーカーの書く名前と違う", READER,
     'public static final String FORMAT = "taboo-elemental-deck";',
     'public static final String FORMAT = "qte-deck";',
     "junit", (TREADER, "デッキメーカーが書く形をそのまま読める")),

    ("名前で書かれた古い版(v1)を通してしまう", READER,
     "public static final int MIN_VERSION = 2;",
     "public static final int MIN_VERSION = 1;",
     "junit", (TREADER, "名前で書かれた古い版のデッキファイルは拒否する")),

    # ===============================================================
    # 軸2: 欄の名前(★63 が直したものそのもの)
    # ===============================================================
    ("枚数の欄を旧形式の名前(count)で読む", READER,
     'return element.isObject() ? element.path("qty").asInt(1) : 1;',
     'return element.isObject() ? element.path("count").asInt(1) : 1;',
     "junit", (TREADER, "同じデッキファイルを両モードが同じ中身として読む")),

    ("デッキ名の欄を旧形式の名前(name)で読む", READER,
     'textOrNull(root.path("deckName")),',
     'textOrNull(root.path("name")),',
     "junit", (TREADER, "デッキメーカーが書く形をそのまま読める")),

    # ===============================================================
    # 軸3: 欄名の突き合わせ(verify)。★「実物に無い欄」を見に行ったら気づけるか
    # ===============================================================
    ("実物に無い欄を読みに行く(デッキメーカーが書かない欄が増えた)", READER,
     'int version = root.path("version").asInt(0);',
     'int version = root.path("version").asInt(0);\n'
     '        root.path("formatVersion").asInt(0);',
     "verify", "通常モードの読み取りが見る欄は、デッキメーカーの書き出しに全部在る"),

    # ===============================================================
    # 軸4: 読み取りの寛容さ(★手動モードと揃えてある)
    # ===============================================================
    ("リーダーの別名(leaderId)を読まなくなった", READER,
     '                : textOrNull(root.path("leaderId"));',
     "                : null;",
     "junit", (TREADER, "リーダーはleaderId文字列でも読める")),

    # ===============================================================
    # 軸5: ★読み取りは裁かない(まとめない)
    # ===============================================================
    ("読み取りが同じカードIDの行をまとめてしまう(親切がすぎる)", READER,
     "            requireRoom(result.size());\n"
     "            result.add(new DeckDefinition.Entry(requireCardId(element), readQty(element)));",
     "            requireRoom(result.size());\n"
     "            String id = requireCardId(element);\n"
     "            int at = -1;\n"
     "            for (int i = 0; i < result.size(); i++) {\n"
     "                if (result.get(i).cardId().equals(id)) {\n"
     "                    at = i;\n"
     "                }\n"
     "            }\n"
     "            if (at >= 0) {\n"
     "                result.set(at, new DeckDefinition.Entry(id,\n"
     "                        result.get(at).count() + readQty(element)));\n"
     "                continue;\n"
     "            }\n"
     "            result.add(new DeckDefinition.Entry(id, readQty(element)));",
     "junit", (TREADER, "同じカードIDの行はまとめずに検証へ渡す")),

    ("検証層が行の重複を見なくなった(まとめない意味が消える)", VALIDATOR,
     '            if (!seen.add(entry.cardId())) {\n'
     '                throw new IllegalArgumentException("メインデッキに同じカードの行が重複しています: " + card.name());\n'
     '            }',
     "            seen.add(entry.cardId());",
     "junit", (TREADER, "同じカードIDの行はまとめずに検証へ渡す")),

    # ===============================================================
    # 軸6: 外から来るデータの上限(設計判断27)
    # ===============================================================
    # ★★ここは<b>定数の値を変える改変が当たらない</b>。試験は上限の値を
    #   実装から読んで作った行数で測っているので(裁定110)、200 を 100000 にしても
    #   試験のほうも 100001 行を作りに行く。<b>測っているのは「上限があること」であって
    #   「上限が200であること」ではない</b> —— だから見張りを外すほうを壊す。
    ("行数の見張りが仕事をしていない(上限が無い)", READER,
     "        if (size >= MAX_ENTRIES) {\n"
     "            throw new IllegalArgumentException(\n"
     "                    \"デッキファイルの行数が多すぎます(%d行まで)\".formatted(MAX_ENTRIES));\n"
     "        }",
     "        return;",
     "junit", (TREADER, "行数が多すぎるファイルは拒否する")),

    # ===============================================================
    # 軸7: 本物の入口(ロビー)
    # ===============================================================
    ("ロビーがデッキファイルを読まずに捨てる", LOBBY_JAVA,
     "        return deckFileReader.read(deckJson);",
     "        return null;",
     "junit", (TLOBBY, "デッキメーカーで組んだデッキで部屋を作って盤面まで行ける")),

    ("読めなかった理由を握りつぶす(62 までの姿)", LOBBY_JAVA,
     "        return deckFileReader.read(deckJson);",
     "        try {\n"
     "            return deckFileReader.read(deckJson);\n"
     "        } catch (RuntimeException e) {\n"
     "            throw new IllegalArgumentException(\"デッキファイルの形式が正しくありません\");\n"
     "        }",
     "junit", (TLOBBY, "旧形式のデッキを渡すと直し方が画面に出る")),

    # ===============================================================
    # 軸8: 画面の要約(★実物の書き出しに当てている)
    # ===============================================================
    ("ロビーの要約が旧形式の欄(count)を数える(62 までの姿)", LOBBY_HTML,
     "        const main = (deck.main || []).reduce((sum, e) => sum + (e.qty || 1), 0);",
     "        const main = (deck.main || []).reduce((sum, e) => sum + e.count, 0);",
     "verify", "ロビーの要約はデッキメーカーの書き出しを 40/8 と数える"),

    ("ロビーが形式を見ずに数える(旧形式を0枚と表示する)", LOBBY_HTML,
     "        if (!deck || deck.format !== 'taboo-elemental-deck') {\n"
     "            return null;\n"
     "        }",
     "        if (!deck) {\n"
     "            return null;\n"
     "        }",
     "verify", "旧形式のファイルを黙って0枚と数えない"),

    # ===============================================================
    # 軸9: 退役(リンク)
    # ===============================================================
    ("ロビーが退役した画面へ案内し続ける", LOBBY_HTML,
     '<a th:href="@{/deck-maker}">デッキメーカー</a> /',
     '<a th:href="@{/deck-builder}">デッキビルダー</a> /',
     "junit", (TLOBBY, "ロビーはデッキメーカーへ案内する")),

    # ===============================================================
    # 軸10(番外): 変換した確認用デッキ
    # ===============================================================
    ("確認用デッキの1本が旧形式のまま残っている", SAMPLE_DECK,
     '  "format": "taboo-elemental-deck",\n  "version": 2,',
     '  "formatVersion": 1,',
     "junit", (TREADER, "同じデッキファイルを両モードが同じ中身として読む")),
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


# ---- JUnit 側 ----

def run_class(test_class):
    # ★走らせる前に前回の結果を消す(60 の教訓)。消さないとコンパイルが通らなかったとき、
    #   前回の XML が残って「壊したのに落ちなかった」= NG に見える。
    path = os.path.join(ROOT, "target/surefire-reports/TEST-%s.xml" % test_class)
    if os.path.exists(path):
        os.remove(path)
    subprocess.run(MVN + ["-Dtest=" + test_class.split(".")[-1]],
                   cwd=ROOT, capture_output=True)
    if not os.path.exists(path):
        return None
    return ET.parse(path).getroot()


def junit_verdict(root, method):
    if root is None:
        return "EMPTY"
    for case in root.iter("testcase"):
        if case.get("name") == method:
            failed = any(child.tag in ("failure", "error") for child in case)
            return "OK" if failed else "NG"
    return "EMPTY"


# ---- verify 側 ----

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
    for stale in glob.glob(os.path.join(ROOT, "target/surefire-reports/TEST-*.xml")):
        os.remove(stale)
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
            if kind == "junit":
                answer = junit_verdict(run_class(target[0]), target[1])
                shown = target[1]
            else:
                answer = verify_verdict(run_verify(), target)
                shown = target
        finally:
            write(path, original)
        if answer == "NG" and label in EXPECTED_NG:
            answer = "NG(想定内)"
        results.append((number, label, answer))
        print("%2d %-10s %s  →  %s" % (number, answer, label, shown))
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
    if any(case[4] == "verify" for i, case in enumerate(CASES, 1) if i in picked):
        run_verify()
    good = counts.get("OK", 0) + counts.get("NG(想定内)", 0)
    return 0 if good == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
