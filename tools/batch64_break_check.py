#!/usr/bin/env python3
"""Batch 64(割り込み選択の一般化)の壊し検証(裁定116)。

実装をわざと壊し、狙った試験が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った試験が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(試験が足りない)
  EMPTY    … その試験が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★★<b>照合先が2つある</b>(63 と同じ)。64 の番人は JUnit と verify の両方に居る ——
  器と挙動は Java、はい/いいえの描かれ方と送信の中身はブラウザだからである。
  ケースごとに kind("junit" / "verify")で振り分ける。

★★"surefire:test" 単体で回してはいけない(裁定208)。必ず "test" を回すこと。
★★改変は「軸」ごとに1件ずつ当てる(57 の教訓)。全16ケース・軸は次の12 である。
  (1) はい/いいえの器          (2) 待ち行列に積む
  (3) 待ち行列から取り出す      (4) 位置ズレの照合(控えと比較の両側)
  (5) 山札が空なら問わない      (6) 英知の水晶の再誘発を止める
  (7) 自動決定へ戻さない        (8) 「このターン破壊された」の多重度
  (9) 再開に要る値(payload)   (10) 選択待ちの間は盤面を動かさない
  (11) 画面(はい/いいえ・残り件数)(12) 不滅のネクロマンサーの作り直し

★★<b>64 で出た新しい失敗の形(裁定304)</b>: 「条件を落とす」改変が EMPTY になった。
  {@code if (cond) { ... return; }} を素の {@code { ... return; }} に置き換えたら、
  その先が<b>到達不能</b>になって Java がコンパイルを拒み、試験が1件も走らなかった。
  「壊したのに落ちない」ではなく「壊しすぎて測れない」である ——
  ★<b>常に真になる条件へ書き換える</b>のが正しい壊し方である(ケース8)。
  裁定196(b)「改変が当たっているか」の3つ目の顔である
  (1つ目=当たっていない・2つ目=試験が実装から値を読む・3つ目=コンパイルが通らない)。

★壊しどころが無い項目(意図的に含めていないもの):
  - {@code AutoChoice} の復活 …… 63 の「退役したファイル」と同じ性質である。
    クラスを書き戻すのは改変ではなく<b>新設</b>であり、番人
    (Batch64InterruptChoiceTest.AutoChoiceは退役してもう存在しない)は
    存在そのものを測っているので、他の軸と同じ土俵に乗らない。
  - 待ち行列の上限(MAX_PENDING_CHOICES)の値を変えること …… ★63 の裁定298 と同じ形である。
    試験のほうも {@code PlayerState.MAX_PENDING_CHOICES} を読んで「上限+1件」を作りに行くため、
    <b>定数を書き換える改変は当たらない</b>。壊すなら見張りそのもの(ケース3で当てている)。

使い方: python3 tools/batch64_break_check.py [ケース番号...]
"""
import glob
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MVN = ["mvn", "-o", "-B", "-q", "-Dmaven.repo.local=/root/m2work/repository",
       "test", "-DfailIfNoTests=false"]

CHOICE = "src/main/java/com/example/qte/effect/PendingChoice.java"
PLAYER = "src/main/java/com/example/qte/game/PlayerState.java"
ACTIONS = "src/main/java/com/example/qte/game/GameActions.java"
SERVICE = "src/main/java/com/example/qte/game/GameService.java"
REGISTRY = "src/main/java/com/example/qte/effect/CardEffectRegistry.java"
VIEWS = "src/main/java/com/example/qte/game/view/GameViewBuilder.java"
BATTLE_JS = "src/main/resources/static/js/battle.js"

T64 = "com.example.qte.Batch64InterruptChoiceTest"
T59 = "com.example.qte.Batch59ReworkTest"
TEVO = "com.example.qte.EvolutionEffectTest"

# (説明, ファイル, 置換前, 置換後, kind, 照合先)
#   kind "junit"  … 照合先は (テストクラス, テストメソッド)
#   kind "verify" … 照合先は 検証の名前の一部
CASES = [
    # ===============================================================
    # 軸1: はい/いいえの器(「いいえ」を選べる形になっているか)
    # ===============================================================
    ("「いいえ」を選べない形にする(min=1)", CHOICE,
     "        return new PendingChoice(Kind.CONFIRM, List.of(CONFIRM_YES), 0, 1,",
     "        return new PendingChoice(Kind.CONFIRM, List.of(CONFIRM_YES), 1, 1,",
     "junit", (T64, "はいいいえの問い合わせは候補1件の選択として表される")),

    # ===============================================================
    # 軸2: ★待ち行列に積む(63 の「1人1件」へ戻す)
    # ===============================================================
    ("2件目の問い合わせを黙って捨てる(63 の1人1件へ戻す)", PLAYER,
     "        pendingChoices.addLast(choice);",
     "        if (pendingChoices.isEmpty()) {\n"
     "            pendingChoices.addLast(choice);\n"
     "        }",
     "junit", (T64, "破壊のたびに問い合わせが1件ずつ積まれる")),

    # ===============================================================
    # 軸3: ★待ち行列から取り出す(先頭1件だけのはずが全部消える)
    # ===============================================================
    ("解決のたびに待ち行列を空にしてしまう", PLAYER,
     "    public PendingChoice pollPendingChoice() {\n"
     "        return pendingChoices.pollFirst();",
     "    public PendingChoice pollPendingChoice() {\n"
     "        PendingChoice head = pendingChoices.pollFirst();\n"
     "        pendingChoices.clear();\n"
     "        return head;",
     "junit", (T64, "待ち行列は起きた順に1件ずつ解決される")),

    # ===============================================================
    # 軸4: ★★位置ズレの照合。控える側と比べる側の両方に当てる
    # ===============================================================
    ("解決の直前に位置を照合しない(比べる側を殺す)", SERVICE,
     "        if (hasDriftedCandidate(player, choice, chosen)) {",
     "        if (false) {",
     "junit", (T64, "待っている間に墓地が動いたら選択は何も起こさない")),

    ("問い合わせを作った瞬間の中身を控えない(控える側を殺す)", ACTIONS,
     "        if (!choice.pointsAtZonePositions()) {\n"
     "            return choice;\n"
     "        }",
     "        if (true) {\n"
     "            return choice;\n"
     "        }",
     "junit", (T64, "待っている間に墓地が動いたら選択は何も起こさない")),

    # ===============================================================
    # 軸5: ★裁定302(引けば敗北する選択肢を並べない)
    # ===============================================================
    ("山札が空でも「引きますか?」と問う(裁定302 を無視する)", REGISTRY,
     '        watchAnyMinionDestroyed("QTE-M-DARK-20", (ctx, destroyedCardId) -> {\n'
     "            if (ctx.owner().getDeck().isEmpty()) {\n"
     "                return;\n"
     "            }",
     '        watchAnyMinionDestroyed("QTE-M-DARK-20", (ctx, destroyedCardId) -> {',
     "junit", (T64, "山札が空なら引くかどうかを問い合わせない")),

    # ===============================================================
    # 軸6: ★★裁定279(誘発によるドローは数えない)を 64 の形で止めているか
    # ===============================================================
    ("英知の水晶のドローが「相手が引いたとき」を焚く(往復が復活する)", REGISTRY,
     "                    ctx.actions().drawCardsWithoutOpponentWatchers(ctx.room(), ctx.owner(), 1);",
     "                    ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);",
     "junit", (T59, "英知の水晶は両者が場に出していても無限に往復しない")),

    # ===============================================================
    # 軸7: ★自動決定へ戻さない(裁定192・299)
    # ===============================================================
    # ★条件を落とすのではなく「常に真」にする —— 素の {} で囲うと
    #   その先が到達不能になり、Java がコンパイルを拒む(EMPTY になって軸が測れない)。
    ("蘇生の対象を問い合わせず、いつも自動で決める", REGISTRY,
     "        if (positions.size() <= limit) {",
     "        if (positions.size() <= limit || positions.size() > limit) {",
     "junit", (T64, "冥界神ハデスは蘇生する体を本人に選ばせる")),

    ("候補が1体でも問い合わせる(選ぶ余地の無いときに問う)", REGISTRY,
     "            if (opponentMinions.size() == 1) {\n"
     "                bounceStormCallerTarget(ctx, opponentMinions.get(0).getInstanceId());\n"
     "                return;\n"
     "            }",
     "            if (false) {\n"
     "                bounceStormCallerTarget(ctx, opponentMinions.get(0).getInstanceId());\n"
     "                return;\n"
     "            }",
     "junit", (T64, "嵐の呼び手は候補が1体なら問い合わせない")),

    # ===============================================================
    # 軸8: ★「このターン破壊された味方」の多重度
    # ===============================================================
    ("以前から墓地に居るミニオンまで蘇生の候補にする", REGISTRY,
     "        return trashPositionsMatching(owner, remaining::remove);",
     "        return trashPositionsMatching(owner, id -> !remaining.isEmpty() || true);",
     "junit", (T64, "冥界神ハデスは以前から墓地に居るミニオンを候補にしない")),

    # ===============================================================
    # 軸9: ★再開に要る値(payload)。使い手は《英術・スケアロック》である
    # ===============================================================
    ("進化の文脈(どの進化カードか)を運ばない", REGISTRY,
     "                        .withPayload(cardId));",
     "                        );",
     "junit", (TEVO, "スケアロックは出した1体目を素材にして進化を出す")),

    # ===============================================================
    # 軸10: ★裁定214 の対。残件があるうちは誰も盤面を動かさない
    # ===============================================================
    ("選択待ちが残っていても手番の操作を通してしまう", SERVICE,
     "        if (state.playerOf(playerId).getPendingChoice() != null) {\n"
     '            throw new IllegalStateException("先に選択を解決してください");\n'
     "        }",
     "        if (false) {\n"
     '            throw new IllegalStateException("先に選択を解決してください");\n'
     "        }",
     "junit", (T64, "選択待ちが残っている間は手番の側も盤面を動かせない")),

    # ===============================================================
    # 軸11: ★画面(実物の battle.js を押して測っている)
    # ===============================================================
    ("はい/いいえを候補ボタンとして描いてしまう", BATTLE_JS,
     "    if (choice.kind === 'CONFIRM') {",
     "    if (false) {",
     "verify", "はい/いいえは2つのボタンとして描かれる(64)"),

    ("待っている残り件数を案内に出さない", BATTLE_JS,
     "    promptEl.textContent = choice.queued > 1",
     "    promptEl.textContent = false",
     "verify", "待っている問い合わせが複数あるなら残り件数を添える(64)"),

    ("ビューが待ち行列の件数を運ばない(いつも1件と言う)", VIEWS,
     "                choice.min(), choice.max(), choice.prompt(), player.getPendingChoiceCount());",
     "                choice.min(), choice.max(), choice.prompt(), 1);",
     "junit", (T64, "破壊のたびに問い合わせが1件ずつ積まれる")),

    # ===============================================================
    # 軸12: ★★不滅のネクロマンサーの作り直し(Ver1.1 の本文へ)
    # ===============================================================
    ("不滅のネクロマンサーのドローが自分に飛ぶ(「相手は」を読み落とす)", REGISTRY,
     '        register("QTE-M-DARK-5", TriggerType.ON_ENTER, ctx -> {\n'
     "            ctx.actions().drawCards(ctx.room(), ctx.opponent(), 1);",
     '        register("QTE-M-DARK-5", TriggerType.ON_ENTER, ctx -> {\n'
     "            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);",
     "junit", (T64, "不滅のネクロマンサーは出たとき相手が1枚引く")),
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
