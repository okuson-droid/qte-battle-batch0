#!/usr/bin/env python3
"""Batch 72b(不具合修正: 賢魂がスペル枠で使えない)の壊し検証(裁定116)。

実装をわざと壊し、狙った番人が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った番人が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(番人が足りない)
  EMPTY    … その番人が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★★★<b>照合先は JUnit だけである。</b>今回の不具合は
  「クライアントが送った本文を、サーバが開く」ところに居た ——
  機械検証(verify)のハーネスは Java を起こさないので、<b>そこまで届かない</b>
  (70・71 の教訓「番人を置く場所を選ぶ前に、そこまで届くかを確かめる」)。
  ★verify に軸を置かないのは手抜きではなく、<b>この不具合を見つけられなかった理由そのもの</b>である。

★★<b>2つの番人は役割が違う。</b>
  - {@code WsRequestPayloadTest} …… 本文が<b>開けるか</b>(原因の側)
  - {@code WsErrorRoutingTest}   …… 開けなかったとき<b>本人に返るか</b>(無言の側)
  ★軸1・2 は前者、軸3〜6 は後者を狙う。

★★★<b>出口ごとに当てている</b>(71 の教訓)。
  {@code onUnreadableMessage} には出口が2つある:
    - 送り主が分からず<b>返さずに終わる</b>出口 …… 軸4(部屋)・軸5(送り主)
    - <b>返して終わる</b>出口 …………………………… 軸6

使い方: python3 tools/batch72b_break_check.py [ケース番号...]
★短いので殺される心配は小さいが、前後で git diff を見る習慣は変えない(70 の教訓)。
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
M2 = "/root/m2work/repository"

WS = "src/main/java/com/example/qte/web/GameWsController.java"

PAYLOAD_TEST = "WsRequestPayloadTest"
ROUTING_TEST = "WsErrorRoutingTest"

# 壊しても落ちないことが分かっているもの(理由つき)。★72b は1件も無い。
EXPECTED_NG = {}

# (説明, ファイル, 置換前, 置換後, クラス, 照合先の名前の一部)
CASES = [
    # ===============================================================
    # 軸1: ★★★クライアントが送らない項目を原始型で受けないこと
    #   ★これが不具合の本体である。Jackson 3 は原始型の欠落を
    #     「変換できない」として扱い、メッセージごと捨てる ——
    #     ハンドラには入らないので execute も sendError も通らない
    #   ★型と防御アクセサは<b>一組で1つの筋</b>である(片方だけ戻すと
    #     コンパイルが通らず、報告書が生まれない = EMPTY になる)
    # ===============================================================
    ("enhanced を原始型 boolean で受ける(送られてこないと変換ごと失敗する)", WS,
     "            List<TargetChoice> targets, Boolean enhanced, List<String> materialIds,\n"
     "            List<Integer> manaIndexes) {\n"
     "\n"
     "        public Boolean enhanced() {\n"
     "            return enhanced != null && enhanced;\n"
     "        }\n"
     "\n",
     "            List<TargetChoice> targets, boolean enhanced, List<String> materialIds,\n"
     "            List<Integer> manaIndexes) {\n"
     "\n",
     PAYLOAD_TEST, "賢魂のドラッグの本文が開ける"),

    # ===============================================================
    # 軸2: ★★送られてこない enhanced を false に畳むこと
    #   ★箱型にしただけでは足りない —— null のまま読ませると、
    #     呼び出し側の {@code request.enhanced()} が<b>開封時ではなく実行時に</b>倒れる
    # ===============================================================
    ("防御アクセサを外す(enhanced が null のまま渡る)", WS,
     "        public Boolean enhanced() {\n"
     "            return enhanced != null && enhanced;\n"
     "        }\n"
     "\n",
     "",
     PAYLOAD_TEST, "賢魂のドラッグの本文が開ける"),

    # ===============================================================
    # 軸3: ★★★開けなかったメッセージを受ける宣言があること
    #   ★アノテーションを外すと Spring は「受け皿が無い」と判断し、
    #     ログに書いて<b>そこで終わる</b> —— 不具合当時の姿に戻る
    # ===============================================================
    ("変換の失敗を受ける宣言を外す(元の無言に戻る)", WS,
     "    @MessageExceptionHandler(MessageConversionException.class)\n"
     "    public void onUnreadableMessage(",
     "    public void onUnreadableMessage(",
     ROUTING_TEST, "開けない本文は本人の宛先へ返る"),

    # ===============================================================
    # 軸4: ★★宛先から部屋を取り出せること(返さずに終わる出口・その1)
    # ===============================================================
    ("宛先から部屋を取り出さない(いつも返さずに終わる)", WS,
     "        Matcher m = ROOM_IN_DESTINATION.matcher(destination);\n"
     "        return m.find() ? m.group(1) : null;",
     "        return null;",
     ROUTING_TEST, "開けない本文は本人の宛先へ返る"),

    # ===============================================================
    # 軸5: ★★★開けなかった本文から送り主を拾えること(返さずに終わる出口・その2)
    #   ★ここが今回いちばん頼りない場所である —— <b>解釈できないと分かっている本文</b>から
    #     1項目だけを拾っている。拾えなくなったら黙ることを、番人が知っていなければならない
    # ===============================================================
    ("開けなかった本文から送り主を拾わない(いつも返さずに終わる)", WS,
     "        Matcher m = PLAYER_ID_IN_BODY.matcher(new String(payload, StandardCharsets.UTF_8));\n"
     "        return m.find() ? m.group(1) : null;",
     "        return null;",
     ROUTING_TEST, "開けない本文は本人の宛先へ返る"),

    # ===============================================================
    # 軸6: ★★★分かっているのに返さないこと(返して終わる出口)
    #   ★軸3〜5 は「届かない」形の壊し方であり、これは「届いたのに黙る」形である。
    #     設計判断51 が禁じているのは後者そのものである
    # ===============================================================
    ("送り主が分かっているのに返さない(届いたのに黙る)", WS,
     "        broadcaster.sendError(roomId, playerId,\n"
     "                \"操作を受け取れませんでした(送信の形が不正です)。画面を再読み込みしてください\");",
     "        return;",
     ROUTING_TEST, "開けない本文は本人の宛先へ返る"),
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

    targets = sorted({case[1] for case in CASES})
    baseline = {path: read(path) for path in targets}
    results = []
    for number, (label, path, before, after, cls, target) in enumerate(CASES, 1):
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
            answer = junit_verdict(run_junit(cls), target)
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
    good = counts.get("OK", 0) + counts.get("NG(想定内)", 0)
    return 0 if good == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
