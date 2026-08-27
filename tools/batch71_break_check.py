#!/usr/bin/env python3
"""Batch 71(通常モードの切断 = 候補 H)の壊し検証(裁定116)。

実装をわざと壊し、狙った番人が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った番人が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(番人が足りない)
  EMPTY    … その番人が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★★★<b>照合先は verify だけである。</b>これは手抜きではなく、
  <b>先に「そこまで届くか」を確かめた結果</b>である(70 の教訓)。
  71 は <b>Java を1行も変えていない</b> —— 守る対象(接続の判定・send のガード・
  オーバーレイ・帯・送れなかったときに畳まないこと)は<b>すべて battle.js の中</b>にあり、
  ハーネスの STOMP スタブは {@code client.onWebSocketClose()} を直接呼べるので、
  実物と同じ入口から落とせる。
  ★<b>逆に JUnit には照合先がほとんど無い。</b>MockMvc が測れるのは
  「テンプレートに箱が在るか」だけであり、それは<b>宣言</b>の話である ——
  箱が在ることと切断中に操作が止まることは別である(設計判断46)。
  ★だから BattlePageTest の 71 の項目は<b>軸にしていない</b>(壊しても
  「宣言が消えた」しか言えず、番人の強さの証明にならない)。

★★改変は「軸」ごとに1件ずつ当てる(57 の教訓)。軸は次の18である。

  (1)  send のガードそのもの            (2)  判定が socketDown を見ること
  (3)  拒否の合図(クラスが付くこと)    (4)  拒否の合図が実際に効いていること
  (5)  オーバーレイが出ること            (6)  オーバーレイが最前面であること
  (7)  番人がオーバーレイではないこと    (8)  覗き見中に帯が出ること
  (9)  帯の置き場所(ヘッダ行の中)      (10) 席選択ゲートとの排他
  (11) デッキゲートは抑止しないこと      (12) 確定待ち(プレイ)が消えないこと
  (13) 確定待ち(マナチャージ)が消えないこと (14) 対象選択の巻き戻し
  (15) 初回と再接続の区別                (16) 再接続を黙って済ませないこと
  (17) 音がガードの後ろにあること        (18) サーバ側のエラーも切断として扱うこと

★★★<b>軸12・13 は最初1本だった。</b>マナチャージの出口だけを壊したところ NG が出た ——
  番人(71-9)が通っていたのは<b>プレイの出口</b>だったからである。
  「壊しても落ちない」の新しい形ではなく、<b>番人が壊した枝を通っていない</b>という
  65 の形である。→ 71-9b を足して出口を2つとも測り、軸も2本に分けた。
  ★★<b>出口が4つある関数は、出口ごとに当てること。</b>

★★★<b>想定内の NG は1件も置いていない。</b>
  70 はケース2 に裁定298 の実例(期待値を実装から読む試験)をわざと置いていたが、
  71 の番人は<b>どれも実装から期待値を読んでいない</b> ——
  測っているのは「送ったか」「覆っているか」「矩形が重なるか」「animation が動くか」
  という<b>盤面から読める事実</b>だけである。

★★★<b>壊しどころが無い項目</b>(意図的に含めていないもの・裁定196 の正直な扱い):

  - <b>部屋消失(手動モードの connectionFatal)との排他</b> ……
    通常モードには {@code showRoomLostFatal} にあたるものが無い(66 が作らなかった)。
    <b>旗を立てる人が1人も居ないので、旗そのものを作っていない</b> ——
    作れば「書いてあるのに効いていない器」(70 が見つけた .mana-chip)になる。
    ★<b>無いものは壊せない。</b>作る日が来たら、そのときに排他の軸も足すこと。

  - <b>マリガンと割り込みの選択が消えないこと</b> ……
    実装は確定待ち・対象選択と<b>同じ形</b>(send の返り値を見て畳む)であり、
    軸12・軸13 が壊れれば同じ書き方の誤りとして現れる。
    ★これは<b>代用</b>である(68 の教訓)—— 4つのうち2つしか名指しで測っていない。
    書き方を変えた日には、残り2つが黙って落ちうる。

  - <b>ドラッグの途中で落ちたときの挙動</b> ……
    ドロップは {@code send()} を通るのでガードは効くが、
    「掴んでいる最中に相手が落ちたことに人が気づけるか」は機械には測れない。
    ★実機確認の依頼である。

  - <b>タッチ操作</b> …… 70 から変わらず HTML5 のドラッグはタッチで動かない。

使い方: python3 tools/batch71_break_check.py [ケース番号...]
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CSS = "src/main/resources/static/css/battle.css"
BATTLE_JS = "src/main/resources/static/js/battle.js"

# 壊しても落ちないことが分かっているもの(理由つき)。★71 は1件も無い。
EXPECTED_NG = {}

# (説明, ファイル, 置換前, 置換後, 照合先の名前の一部)
CASES = [
    # ===============================================================
    # 軸1: ★★★send のガードそのもの —— これが番人である
    # ===============================================================
    ("send のガードを外す(70 までの姿。死んだソケットへ無言で publish)", BATTLE_JS,
     "    if (!isConnected()) {\n"
     "        // ★宣言(オーバーレイか帯)は既に出ている。ここで足すのは",
     "    if (false) {\n"
     "        // ★宣言(オーバーレイか帯)は既に出ている。ここで足すのは",
     "切断中は send() が publish しない"),

    # ===============================================================
    # 軸2: ★★判定が socketDown を見ること(33 の 1-6)
    #   ★ライブラリ内部のフラグが倒れている保証はこちらに無い。
    #     自分で観測した事実を and で重ねていなければ、切断が<b>音を立てずに</b>見逃される
    # ===============================================================
    ("接続の判定を client.connected だけにする(自分で観測した事実を捨てる)", BATTLE_JS,
     "    return client.connected === true && !socketDown;",
     "    return client.connected === true;",
     "切断中は send() が publish しない"),

    # ===============================================================
    # 軸3: ★拒否の合図(クラスが付くこと)—— 無言で捨てない
    # ===============================================================
    ("拒否の合図を出さない(押したのに何も起きない、に戻す)", BATTLE_JS,
     "        flashDenied(document.getElementById('auto-conn-bar'));\n"
     "        flashDenied(document.getElementById('connection-status'));",
     "        // わざと黙る",
     "切断中の操作を無言で捨てない"),

    # ===============================================================
    # 軸4: ★★★拒否の合図が<b>実際に効いている</b>こと(70 の教訓・空文)
    #   ★クラスは付くのに規則が当たらない、という形を作る。
    #     クラスの数を数えるだけの検証なら、ここは緑のまま素通りする
    # ===============================================================
    ("明滅の規則を通常モードに当たらない形へ戻す(クラスは付くが光らない)", CSS,
     ".manual-denied,\n.auto-denied { animation: manual-deny-blink 0.35s ease-in-out 2; }",
     ".manual-denied { animation: manual-deny-blink 0.35s ease-in-out 2; }",
     "拒否の明滅は実際に効いている"),

    # ===============================================================
    # 軸5: ★オーバーレイが出ること(宣言)
    # ===============================================================
    ("切断オーバーレイを出さない(宣言が消える)", BATTLE_JS,
     "    document.getElementById('auto-offline')\n"
     "        .classList.toggle('d-none', !offline || offlinePeeking);",
     "    document.getElementById('auto-offline').classList.add('d-none');",
     "切断中はオーバーレイが手札を物理的に覆う"),

    # ===============================================================
    # 軸6: ★★オーバーレイが最前面であること(実測で守る)
    #   ★通常モードの最前面は #auto-zoom の 1500 である。その下へ潜らせる
    # ===============================================================
    ("オーバーレイの重ね順を盤面の最前面より下げる", CSS,
     "    position: fixed; inset: 0; z-index: 1970; background: rgba(0, 0, 0, 0.72);",
     "    position: fixed; inset: 0; z-index: 1200; background: rgba(0, 0, 0, 0.72);",
     "オーバーレイが通常モードの最前面である"),

    # ===============================================================
    # 軸7: ★★★番人がオーバーレイであってはならない(33 の 1-2 の中心)
    #   ★「覆っているか」でガードすると、覗き見の導線を足した瞬間に穴が開く。
    #     71-1 は緑のまま通り、<b>71-4 だけが落ちる</b> —— それがこの軸の意味である
    # ===============================================================
    ("ガードの根拠をオーバーレイの表示に差し替える(見えなくすることを安全装置にする)", BATTLE_JS,
     "    if (!isConnected()) {\n"
     "        // ★宣言(オーバーレイか帯)は既に出ている。ここで足すのは\n",
     "    if (!document.getElementById('auto-offline').classList.contains('d-none')) {\n"
     "        // ★宣言(オーバーレイか帯)は既に出ている。ここで足すのは\n",
     "盤面を覗いても send() のガードは効く"),

    # ===============================================================
    # 軸8: ★覗き見中に帯が状態を出し続けること
    # ===============================================================
    ("覗いている間の帯を出さない(畳んだら状態が読めなくなる)", BATTLE_JS,
     "        setConnBar(offlinePeeking\n"
     "            ? '切断中 — 操作は相手に届きません(再接続中)'\n"
     "            : null, 'offline');",
     "        setConnBar(null, 'offline');",
     "覗いている間は接続の帯が状態を出し続ける"),

    # ===============================================================
    # 軸9: ★★★帯の置き場所(ヘッダ行の中)—— 実測で選んだ位置を守る
    #   ★手動モードと同じ「画面中央の固定ピル」へ戻すと、
    #     実測どおり [進行: 手動] に重なる
    # ===============================================================
    ("帯を手動モードと同じ画面中央の固定ピルへ戻す(実測で余白が0だった置き方)", CSS,
     ".auto-conn-bar { flex: 0 0 auto; }",
     ".auto-conn-bar { position: fixed; left: 50%; top: 2px;"
     " transform: translateX(-50%); z-index: 1975; }",
     "接続の帯はヘッダ行の中に収まり"),

    # ===============================================================
    # 軸10: ★席選択ゲートとの排他(33 の 1-4)
    # ===============================================================
    ("席選択ゲートの排他をやめる(「入れませんでした」と「再接続を待って」を重ねる)", BATTLE_JS,
     "    const offline = !isConnected() && !isGateVisible();",
     "    const offline = !isConnected();",
     "席選択ゲートと切断の案内は重ねない"),

    # ===============================================================
    # 軸11: ★★★デッキゲートは抑止しないこと(マスター確認・手動モードと違うところ)
    #   ★手動モードをそのまま写すと<b>こちらまで抑止してしまう</b> ——
    #     写し間違いを名指しで捕まえる軸である
    # ===============================================================
    ("デッキゲートも抑止する(手動モードをそのまま写した姿)", BATTLE_JS,
     "    return !gateEl('seat-gate').classList.contains('d-none');",
     "    return !gateEl('seat-gate').classList.contains('d-none')\n"
     "        || !gateEl('deck-gate').classList.contains('d-none');",
     "デッキ読み込みゲートのときは切断の案内を出す"),

    # ===============================================================
    # 軸12: ★★★確定待ちが消えないこと(70 が増やした実害の中心)
    #   ★69 の姿(先に畳んでから送る)へ戻す
    #   ★★<b>出口ごとに1件ずつ当てる。</b>confirmManaPayment には出口が4つあり、
    #     最初は CHARGE の出口だけを壊して<b>NG を出した</b> ——
    #     番人(71-9)が通っていたのは PLAY の出口だったからである。
    #     ★これは「壊しても落ちない」の12個目ではなく、
    #       <b>番人が壊した枝を通っていない</b>という 65 の形である。
    #     → 71-9b を足して CHARGE の出口も測るようにし、軸を2本に分けた。
    # ===============================================================
    ("確定待ち(プレイ)を送る前に畳む(69 の姿。切断中に押すと選んだマナが消える)", BATTLE_JS,
     "    if (!beginSelection(pay.action, pay.handIndex, pay.specs, extra)) "
     "return restoreManaPayment(pay);",
     "    beginSelection(pay.action, pay.handIndex, pay.specs, extra);",
     "送れなかった確定待ちは消えない"),

    ("確定待ち(マナチャージ)を送る前に畳む(裁定323 の出口)", BATTLE_JS,
     "        if (!send('charge-mana', { handIndex: pay.handIndex })) return restoreManaPayment(pay);",
     "        send('charge-mana', { handIndex: pay.handIndex });",
     "マナチャージの確定待ちも、送れなければ消えない"),

    # ===============================================================
    # 軸13: ★★対象選択の巻き戻し(死に止まりを作らない)
    # ===============================================================
    ("対象選択を送れなくても畳む(選び終えたのに何も起きず、選択も消える)", BATTLE_JS,
     "        if (!send(action, buildActionPayload(handIndex, collected, extra))) {\n"
     "            pending.collected.pop();",
     "        if (false) {\n"
     "            pending.collected.pop();",
     "送れなかった対象選択は畳まれず"),

    # ===============================================================
    # 軸14: ★初回の接続と再接続を区別すること(嘘の宣言を出さない)
    # ===============================================================
    ("初回の接続でも「再接続しました」と言う(嘘の宣言)", BATTLE_JS,
     "    const reconnected = connectionEstablishedOnce;",
     "    const reconnected = true;",
     "初回の接続では「再接続しました」と言わない"),

    # ===============================================================
    # 軸15: ★再接続を黙って済ませないこと
    # ===============================================================
    ("再接続を黙って済ませる(裏で直ったことを告げない)", BATTLE_JS,
     "    setConnBar(reconnected ? '再接続しました。盤面を同期しています' : null,\n"
     "        'ok', CONN_BAR_MS);",
     "    setConnBar(null, 'ok', CONN_BAR_MS);",
     "再接続を黙って済ませない"),

    # ===============================================================
    # 軸16: ★★音がガードの<b>後ろ</b >にあること
    #   ★手前に置くと、送っていない操作で手応えだけが返る
    # ===============================================================
    ("音の取り付け点をガードの手前へ戻す(届いていないのに手応えだけ返る)", BATTLE_JS,
     "function send(action, payload) {\n"
     "    if (!isConnected()) {",
     "function send(action, payload) {\n"
     "    sfxForAction(action);\n"
     "    if (!isConnected()) {",
     "切断中の操作では音も鳴らない"),

    # ===============================================================
    # 軸17: ★★サーバ側のエラーも切断として扱うこと
    #   ★70 まで通常モードは onStompError を1つも持っていなかった
    # ===============================================================
    ("onStompError を 70 までの姿(何もしない)へ戻す", BATTLE_JS,
     "client.onStompError = (frame) => {\n"
     "    socketDown = true;",
     "client.onStompError = (frame) => {\n"
     "    socketDown = false;",
     "サーバ側のエラーも切断として扱い"),
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
    # ★★★<b>殺されても書き戻す</b>(70 の教訓)。70 の着手時、上限時間でスクリプトごと
    #   落とされ、<b>壊した1件がそのまま残った</b>(次の走行はそれを「開始時の姿」と
    #   思い込むので、誰も赤くしない)。finally は SIGTERM では走らないため、
    #   合図を捕まえて例外に変える —— そうすれば finally が走る。
    import signal

    def _raise(signum, frame):
        raise KeyboardInterrupt("signal %d" % signum)

    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, _raise)

    # ★★★開始時の姿を控えておく(62 の教訓)。壊したまま終わったら、
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
