#!/usr/bin/env python3
"""Batch 66(通常モードのロビーを手動モードの形へ揃える)の壊し検証(裁定116)。

実装をわざと壊し、狙った試験が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った試験が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(試験が足りない)
  EMPTY    … その試験が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★★<b>66 の照合先は JUnit と verify の両方である。</b>このバッチは Java(受付・部屋・
  ビュー)と静的ファイル(ロビー・盤面・battle.js)の両方を触っている。
  「照合先はそのバッチが触った層に居る」という決め方は 63・64・65 から変えていない。

★★改変は「軸」ごとに1件ずつ当てる(57 の教訓)。全8ケース・軸は次の7である。
  (1) 試合の開始条件にデッキを数える    (2) 観戦者の視界(isSelf を渡さない)
  (3) 受付の門(席の重複・観戦の可否)   (4) 鍵つき部屋の部屋IDを一覧に載せない
  (5) 一覧の [入る] は遷移しない        (6) 席選択のゲートは localStorage で決まる
  (7) 見え方(ロビーの暗色 / デッキを丸ごと送る)

★★<b>裁定304 の罠</b>: Java で「条件を落とす」改変をすると、その先が到達不能になって
  <b>コンパイルが通らず</b> EMPTY になる(64 で踏んだ)。JavaScript には同じ壁が無く、
  黙って別物が動く(65 で書いた)。どちらも避けるため、66 の改変は
  <b>文を消して後続を宙に浮かせる形を1つも使っていない</b> ——
  条件を {@code false &&} で包むか、式ごと置き換えるかのどちらかである。

★★★<b>壊しどころが無い項目</b>(意図的に含めていないもの・裁定196 の正直な扱い):

  - <b>席の並びが席順であること</b>(Batch66LobbyTest「席の並びは到着順ではなく席順である」)
    …… この性質を守っているのは<b>コードではなくデータ構造</b>である。
    {@code seats} は {@code EnumMap<SeatId, PlayerSlot>} であり、反復順は必ず宣言順
    (A → B)になる。さらに {@code getSlots()} は {@code SeatId.values()} を回している。
    ★<b>1箇所の改変では崩せない</b> —— 崩すにはフィールドの型と反復の両方を同時に
    変えることになり、それは「軸ごとに1件」という決め(57 の教訓)を破る。
    ★これは 65 で見つけた4つ目の形(その分岐に入る盤面が作れない)の隣にある
    <b>5つ目の形</b>である ——「盤面は作れるが、<b>壊す場所が1つに絞れない</b>」。
    番人は残してある。将来 {@code seats} を LinkedHashMap に替えた人のための門である。

  - <b>観戦者が操作を送れないこと</b> …… 観戦者は playerId を持たないため、
    画面を書き換えて送っても {@code GameState.playerOf} が知らない id を弾く。
    ★これを壊すには「観戦者に playerId を配る」ことになり、
    それは改変ではなく<b>設計の変更</b>である。番人は
    Batch66LobbyTest の {@code myTurn / chooseOrder / mulligan} が false であることに置いた。

使い方: python3 tools/batch66_break_check.py [ケース番号...]
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

GAME_ROOM = "src/main/java/com/example/qte/room/GameRoom.java"
VIEW_BUILDER = "src/main/java/com/example/qte/game/view/GameViewBuilder.java"
LOBBY_CTRL = "src/main/java/com/example/qte/web/LobbyController.java"
LOBBY_HTML = "src/main/resources/templates/lobby.html"
BATTLE_JS = "src/main/resources/static/js/battle.js"

M2 = os.environ.get("QTE_M2_REPO", "/root/m2work/repository")

# (説明, ファイル, 置換前, 置換後, kind, 照合先クラス(junit のみ), 照合先の名前の一部)
CASES = [
    # ===============================================================
    # 軸1: ★★試合の開始条件にデッキが載っていることを数える
    #   65 までは「2人揃って購読が済んだ」だけで始まった —— デッキはロビーで
    #   受け取り済み、という前提があったからである。66 でその前提が消えた
    # ===============================================================
    ("開始条件からデッキを外す(65 の前提のまま残す)", GAME_ROOM,
     "        return slots.size() == 2\n"
     "                && slots.stream().allMatch(PlayerSlot::isReady)\n"
     "                && slots.stream().allMatch(PlayerSlot::isDeckLoaded);",
     "        return slots.size() == 2\n"
     "                && slots.stream().allMatch(PlayerSlot::isReady);",
     "junit", "Batch66LobbyTest", "デッキが片方だけのときは試合が始まらない"),

    # ===============================================================
    # 軸2: ★★★観戦者の視界。isSelf を渡した瞬間に手札・裏向きマナ・禁忌が漏れる
    # ===============================================================
    ("観戦者に席Aを「自分」として組む(手札が漏れる)", VIEW_BUILDER,
     "                buildPlayerView(state, seatA, false, false),",
     "                buildPlayerView(state, seatA, true, false),",
     "junit", "Batch66LobbyTest", "観戦者には両席の手札の中身が届かない"),

    # ===============================================================
    # 軸3: ★受付の門。画面のボタンを無効にするのは操作補助にすぎず、断るのはサーバである
    # ===============================================================
    ("埋まっている席への着席を許す", GAME_ROOM,
     "        if (seats.containsKey(seat)) {",
     "        if (false && seats.containsKey(seat)) {",
     "junit", "LobbyPageTest", "埋まっている席には座れない"),

    ("観戦を許可していない部屋でも観戦させる", GAME_ROOM,
     "        if (!options.spectatorAllowed()) {",
     "        if (false && !options.spectatorAllowed()) {",
     "junit", "Batch66LobbyTest", "観戦を許可していない部屋では観戦者を作れない"),

    # ===============================================================
    # 軸4: ★★鍵つき部屋の部屋IDを一覧に載せない(IDが鍵を兼ねている)
    # ===============================================================
    ("鍵つき部屋の部屋IDも一覧に載せる", LOBBY_CTRL,
     "            summaries.add(toSummary(room,\n"
     "                    room.getOptions().requireRoomId() ? null : room.getRoomId()));",
     "            summaries.add(toSummary(room, room.getRoomId()));",
     "junit", "LobbyPageTest", "鍵つき部屋の部屋IDは一覧に載らない"),

    # ===============================================================
    # 軸5: ★一覧の [入る] は遷移しない(マスター指示)。下の欄へ差し込むだけである
    # ===============================================================
    ("一覧の [入る] で盤面へ直接飛ばす", LOBBY_HTML,
     "                btn.addEventListener('click', () => fillJoinRoomId(room.roomId));",
     "                btn.addEventListener('click', () => goToRoom(room.roomId));",
     "verify", None, "「入る」は遷移せず入室欄へ部屋IDを差し込む"),

    # ===============================================================
    # 軸6: ★★席選択のゲートは localStorage で決まる。無ければ必ず開く
    # ===============================================================
    ("在席が無くても在席があることにする(ゲートを開かない)", BATTLE_JS,
     "    const saved = loadSavedOccupant();",
     "    const saved = { playerId: 'P1' };",
     "verify", None, "在席が無いと席選択のゲートが開く"),

    # ===============================================================
    # 軸7: ★見え方。ロビーの暗色と、デッキファイルを丸ごと送ること
    # ===============================================================
    # ★★★この改変が 66 で1つ見つけた —— 最初に当てたときは<b>落ちなかった</b>。
    #   原因は番人の弱さではなく、<b>壊した行がそもそも効いていなかった</b>ことである
    #   (body{} が class="bg-dark" に詳細度で負けていた)。詳しくは lobby.html の注記。
    #   ★<b>壊し検証は、番人だけでなく実装の死んだ行も見つける。</b>
    ("通常モードのロビーを白背景に戻す(65 までの姿)", LOBBY_HTML,
     "        body.bg-dark { background-color: #212529; color: #f8f9fa; }",
     "        body.bg-dark { background-color: #ffffff; color: #212529; }",
     "verify", None, "2つのロビーの地色と文字色が一致する"),

    ("デッキファイルの中身を送らない(空の JSON を送る)", BATTLE_JS,
     "                  body: reader.result });",
     "                  body: '{}' });",
     "verify", None, "盤面はデッキファイルを丸ごとサーバへ送る"),
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


def env():
    e = dict(os.environ)
    e.setdefault("NODE_PATH", "/home/claude/.npm-global/lib/node_modules")
    e.setdefault("PLAYWRIGHT_BROWSERS_PATH", "/opt/pw-browsers")
    return e


# ---- verify(静的ファイル側) ----

def run_verify():
    """ハーネスを作り直してから verify を回し、出力行を返す"""
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


# ---- JUnit(Java 側) ----

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

    ★<b>「ビルドが失敗した」を OK と数えない。</b>改変でコンパイルが通らなければ
    報告書そのものが生まれず、EMPTY になる(裁定304 の形をここで見分ける)。
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
