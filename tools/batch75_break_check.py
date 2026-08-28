#!/usr/bin/env python3
"""Batch 75(部屋消失の検出 + 無人部屋の掃除)の壊し検証(裁定116)。

実装をわざと壊し、狙った番人が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った番人が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(番人が足りない)
  EMPTY    … その番人が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★★★<b>照合先は2つある</b>(設計判断45・72・74 と同じ「両方あるバッチ」)。
  - <b>サーバの状態</b>(接続の記録・猶予の起点・掃除・ROOM_LOST の送出)
    …… {@code Batch75RoomLifecycleTest}。
    verify のハーネスは Java を起こさないので<b>あちらには1件も届かない</b>
    (70 の教訓「回る場所を選ぶ前に、そこまで届くかを確かめる」)。
  - <b>受け取った側の畳み方</b>(部屋消失の画面・切断の案内との排他・伝播)
    …… verify。JUnit からは1件も見えない。

★★<b>出口ごとに当てている</b>(71 の教訓)。
  - {@code GameWsController.ready} は「席に着いた人」と「観戦者」の2つ … 軸1・2
  - 接続が減る経路は「切断」と「退室」の2つ ……………………………… 軸5・6
  - {@code sweep} は「消す」「猶予を待つ」の2つ ………………………… 軸8・9

★★★<b>「壊した」と「効いた」は別である</b>(74 で2軸が空振りした)。
  75 は<b>照合先が壊した場所を通ることを1軸ずつ確かめてから</b>並べてある ——
  とくに軸1・2 は、番人が {@code room.markConnected} を直接叩いていた頃には
  <b>どちらを壊しても緑のままだった</b>(72-16 と同じ形)。
  ★その穴に気づいたので、番人のほうを<b>本物の入口を通る形</b>に直した。

使い方: python3 tools/batch75_break_check.py [ケース番号...]
★★<b>長いので3つに分けて回し、前後で `git diff --stat` を突き合わせること</b>(70 の教訓)。
  例: `python3 tools/batch75_break_check.py 1 2 3 4 5 6`
★★★<b>verify を使う軸(14〜18)は末尾に置いてある</b>(72 の教訓: 遷移を起こしうる項目は末尾)。
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
M2 = "/root/m2work/repository"

ROOM = "src/main/java/com/example/qte/room/GameRoom.java"
WS = "src/main/java/com/example/qte/web/GameWsController.java"
SWEEPER = "src/main/java/com/example/qte/web/GameCleanupScheduler.java"
LISTENER = "src/main/java/com/example/qte/web/GameDisconnectListener.java"
BATTLE_JS = "src/main/resources/static/js/battle.js"
BATTLE_HTML = "src/main/resources/templates/battle.html"

LIFECYCLE_TEST = "Batch75RoomLifecycleTest"
PAGE_TEST = "BattlePageTest"
ROUTING_TEST = "WsErrorRoutingTest"

# 壊しても落ちないことが分かっているもの(理由つき)。★75 は1件も無い。
EXPECTED_NG = {}

# ★★★壊しどころが無いもの(裁定196 の正直な扱い)——
#   軸に入れていない理由を書き残す。
#
#   1) 猶予の長さ(DESERTED_ROOM_TTL = 5分)
#      <b>番人が定数を実装から読んでいる</b>({@code 猶予より長く} が
#      {@code GameCleanupScheduler.DESERTED_ROOM_TTL} から作られる)ので、
#      値を変えても番人が一緒に動く —— 裁定298 の形そのものであり、壊せない。
#      ★<b>それでよい。</b>ここで守りたいのは「猶予がある」ことであって
#      「5分である」ことではない。<b>猶予を無視する</b>壊し方が軸9 に在る。
#
#   2) 手動モードの部屋消失(本文の文字列で判定している側)
#      75 は<b>手動モードを1文字も触っていない</b>。
#      通常モードを型で運ぶようにしたが、あちらを揃えていない ——
#      直すのは「手動モードを再開するとき」であり、別の工事である(片肺・71 の教訓)。
#      ★通常モードが<b>本文で判定していないこと</b>は軸15 が見張る。
#
#   3) 「相手が切断中」をビューに出すこと
#      <b>作っていない。</b>{@code RoomView} は接続を1つも持たない。
#      作らなかったものは壊せない(72 のフォーカストラップと同じ扱い)。
#
#   4) 掃除が消したことの配信
#      <b>作っていない。</b>誰も繋がっていないから消しているので、宛先が存在しない
#      (裁定342)。器の無いものは壊せない。

# (説明, ファイル, 置換前, 置換後, 種別, クラス, 照合先の名前の一部)
CASES = [
    # ===============================================================
    # I. 接続の記録 —— 本物の入口(軸1〜4)
    # ===============================================================

    # 軸1: ★★★ready の「席に着いた人」の出口
    ("ready が席に着いた人の接続を記録しなくなる", WS,
     "            room.markConnected(request.playerId(), sessionId);\n"
     "            slot.setReady(true);",
     "            slot.setReady(true);",
     "junit", LIFECYCLE_TEST, "readyで接続が記録される_席"),

    # 軸2: ★★★ready の「観戦者」の出口(71 の教訓: 出口ごとに当てる)
    ("ready が観戦者の接続を記録しなくなる", WS,
     "                room.markConnected(request.playerId(), sessionId);\n"
     "                return;",
     "                return;",
     "junit", LIFECYCLE_TEST, "readyで接続が記録される_観戦者"),

    # 軸3: ★記録が検証より前に走る(部屋に居ない人のセッションが載る)
    ("ready が検証より前に接続を記録する(居ない人でも載る)", WS,
     "            var slot = room.findSlot(request.playerId()).orElse(null);",
     "            room.markConnected(request.playerId(), sessionId);\n"
     "            var slot = room.findSlot(request.playerId()).orElse(null);",
     "junit", LIFECYCLE_TEST, "部屋に居ない人は記録しない"),

    # 軸4: ★markConnected が猶予の起点を消さない
    ("markConnected が猶予の起点を消さない(起点が作成時刻のまま動かない)", ROOM,
     "        sessions.put(occupantId, sessionId);\n"
     "        emptySince = null;",
     "        sessions.put(occupantId, sessionId);",
     "junit", LIFECYCLE_TEST, "猶予は無人になった時刻から数える"),

    # ===============================================================
    # II. 接続が減る経路 —— 出口は2つ(軸5〜7)
    # ===============================================================

    # 軸5: ★切断の側
    ("切断しても接続の記録から外れない", ROOM,
     "        sessions.remove(occupantId);\n"
     "        noteSessionsChanged();\n"
     "        return occupantId;",
     "        return occupantId;",
     "junit", LIFECYCLE_TEST, "切断はセッションidで引く"),

    # 軸6: ★退室の側(もう1つの出口。72 が作った経路である)
    ("退室しても接続の記録から外れない", ROOM,
     "        sessions.remove(occupantId);\n"
     "        noteSessionsChanged();\n"
     "    }",
     "    }",
     "junit", LIFECYCLE_TEST, "退室でも記録が外れる"),

    # 軸7: ★★★猶予の起点が立たない(判定を1本に閉じた側を壊す)
    ("接続者が0になっても猶予の起点が立たない", ROOM,
     "        if (sessions.isEmpty() && emptySince == null) {\n"
     "            emptySince = Instant.now();\n"
     "        }",
     "        if (false) {\n"
     "            emptySince = Instant.now();\n"
     "        }",
     "junit", LIFECYCLE_TEST, "全員切断で猶予が始まる"),

    # ===============================================================
    # III. 掃除 —— 出口は2つ(軸8〜10)
    # ===============================================================

    # 軸8: ★★★消す側
    ("掃除が部屋を消さない(74 までの姿へ戻す)", SWEEPER,
     "            roomManager.removeRoom(roomId);",
     "            if (false) roomManager.removeRoom(roomId);",
     "junit", LIFECYCLE_TEST, "無人の部屋は消える"),

    # 軸9: ★★★猶予を待つ側(もう1つの出口)
    ("掃除が猶予を無視して即座に消す", SWEEPER,
     "                if (room.desertedFor(now).compareTo(DESERTED_ROOM_TTL) >= 0) {",
     "                if (room.desertedFor(now).compareTo(Duration.ZERO) >= 0) {",
     "junit", LIFECYCLE_TEST, "繋がっていれば消えない"),

    # 軸10: ★★★対戦中を除外する(裁定343 を破る)
    ("掃除が対戦中の部屋を残す(裁定343 を破る)", SWEEPER,
     "                if (room.desertedFor(now).compareTo(DESERTED_ROOM_TTL) >= 0) {",
     "                if (room.getGameState() == null\n"
     "                        && room.desertedFor(now).compareTo(DESERTED_ROOM_TTL) >= 0) {",
     "junit", LIFECYCLE_TEST, "対戦中でも消える"),

    # ===============================================================
    # IV. 切断イベントの結線と ROOM_LOST の送出(軸11〜13)
    # ===============================================================

    # 軸11: ★切断イベントが部屋に届かない
    ("切断イベントが部屋へ届かない", LISTENER,
     "                occupantId = room.markDisconnected(sessionId);",
     "                occupantId = null;",
     "junit", LIFECYCLE_TEST, "切断イベントが結線されている"),

    # 軸12: ★★★部屋が無いときに ERROR へ戻す(裁定344 を破る)
    ("部屋が無いときに ERROR を返す(74 までの姿へ戻す)", WS,
     "            broadcaster.sendRoomLost(roomId, playerId);",
     "            broadcaster.sendError(roomId, playerId, \"部屋が見つかりません: \" + roomId);",
     "junit", LIFECYCLE_TEST, "部屋が無ければROOM_LOSTを返す"),

    # 軸13: ★同じ改変を、境目の番人でも観測する(72b が置いた表の側)
    #   ★★<b>照合先を変えて同じ場所を壊す。</b>「そこまで届くか」を2つの番人で確かめる ——
    #     74 の軸18 は照合先が壊した場所を通っておらず空振りした。
    ("部屋が無いときに ERROR を返す(境目の番人の側から見る)", WS,
     "            broadcaster.sendRoomLost(roomId, playerId);",
     "            broadcaster.sendError(roomId, playerId, \"部屋が見つかりません: \" + roomId);",
     "junit", ROUTING_TEST, "開ける本文はここを通らない"),

    # ===============================================================
    # V. 受け取った側(verify)★遷移を起こしうるので末尾(軸14〜18)
    #
    # ★★★<b>verify の側でも順序を直した。</b>「型で判定する」の項目(75-0)は
    #   <b>他の項目に依存しない</b>ので章の先頭へ移した ——
    #   後ろに置いていたときは、軸15 が 75-1 を倒した結果
    #   <b>その先の項目が例外で死に、答えが EMPTY になった</b>(72 の教訓)。
    # ===============================================================

    # 軸14: ★★★ROOM_LOST を受け取らない
    ("battle.js が ROOM_LOST を無視する", BATTLE_JS,
     "    if (message.type === 'ROOM_LOST') {\n"
     "        showRoomLostFatal();\n"
     "        return;\n"
     "    }\n",
     "",
     "verify", None, "無言で遷移せず理由を画面に出す"),

    # 軸15: ★★★型ではなく本文の文字列で判定する(手動モードの形へ寄せる)
    ("部屋消失を ERROR の本文で判定する(手動モードの形にする)", BATTLE_JS,
     "    if (message.type === 'ROOM_LOST') {",
     "    if (message.message === '部屋が見つかりません: TESTRM') {",
     "verify", None, "ERROR の本文では畳まない"),

    # 軸16: ★★★切断の案内との排他を外す
    ("部屋消失でも切断の案内を出す(2つの案内が並ぶ)", BATTLE_JS,
     "    const offline = !isConnected() && !connectionFatal && !isGateVisible();",
     "    const offline = !isConnected() && !isGateVisible();",
     "verify", None, "部屋消失のあいだは切断の案内を出さない"),

    # 軸17: ★★★[ロビーへ戻る] の伝播を止めない
    ("ロビーへ戻るボタンの伝播を止めない(席選択に拾われる)", BATTLE_JS,
     "        e.stopPropagation();\n"
     "        goToLobby();",
     "        goToLobby();",
     "verify", None, "委譲リスナーに拾われない"),

    # 軸18: ★在席の記録を捨てない
    ("部屋が消えても在席の記録を捨てない", BATTLE_JS,
     "    client.deactivate();\n"
     "    forgetOccupant();",
     "    client.deactivate();",
     "verify", None, "在席の記録を捨てる"),

    # 軸19: ★★静的ファイルの版数を戻す(7-5)
    ("battle.js の版数を 36 へ戻す", BATTLE_HTML,
     "battle.js(v=37)", "battle.js(v=36)",
     "junit", PAGE_TEST, "JSの版数が75で上がっている"),

    # 軸20: ★★★<b>据え置きにも番人を置く</b>(74 の教訓)——
    #   触っていない battle.css を上げると赤くなること
    ("触っていない battle.css の版数を上げる(据え置きの番人)", BATTLE_HTML,
     "battle.css(v=53)", "battle.css(v=54)",
     "junit", PAGE_TEST, "CSSの版数は75でも据え置きである"),
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


# ---- JUnit(サーバの状態を読む番人) ----

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
