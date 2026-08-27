#!/usr/bin/env python3
"""Batch 70(手札からの操作を2つの入口にする + ホバーの取りこぼし)の壊し検証(裁定116)。

実装をわざと壊し、狙った番人が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った番人が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(番人が足りない)
  EMPTY    … その番人が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★★<b>70 の照合先は2層である。</b>verify(実測)と JUnit(Eclipse で回る番人)である。
  ★69 と違い、<b>70 は Java の挙動を変えている</b>(払い方の規則・問い合わせが運ぶ値)ので、
    JUnit 側の照合先が多い。★<b>回る場所で選ぶ</b>(設計判断45)——
    払い方は盤面の状態で測れるので JUnit、色と位置と実マウスは verify である。

★★★<b>verify はフィクスチャで描く。</b>ハーネスは Java のサーバを起こさず、
  {@code verify/fixture.js} が組んだビューを {@code render()} に直接渡す ——
  したがって <b>GameViewBuilder や GameActions を壊しても verify には届かない</b>。
  ★だから「ビューが順序を載せること」「問い合わせが出どころを運ぶこと」は
  <b>JUnit 側にしか照合先が無い</b>(軸5・軸6)。
  ★★<b>これは 70 で実際に踏んだ</b> —— 最初は2層のつもりで verify 側も書いたが、
    壊しても落ちなかった。<b>「2層に見えて1層」だったのである。</b>

★★改変は「軸」ごとに1件ずつ当てる(57 の教訓)。軸は次の18である。

  (1)  通常の支払い順(裁定315・316)   (2)  禁忌の支払い順(裁定317)
  (3)  人が選んだ支払いを尊重すること  (4)  通らない指定を弾くこと
  (5)  ビューが支払いの順を載せること  (6)  問い合わせが出どころを運ぶこと
  (7)  【召喚時】も同じ口を通ること    (8)  効果を呼ぶ口が1つであること
  (9)  ホバーの取りこぼし(禁忌・パイル) (10) モーダルの中では左へ逃がすこと
  (11) スペル枠の常設(裁定320)        (12) 落とし先が種別で決まること(裁定318)
  (13) 払う予定のマナの強調(裁定315)  (14) ドラッグで実際にプレイされること(裁定321)
  (15) 裁定317 の警告                  (16) 進化は素材の上だけ(裁定322)
  (17) マナチャージの2入口(裁定323)  (18) プレイ中の面を描くこと(指摘2)

★★★<b>裁定298 の実例を1件わざと置いてある</b>(ケース2)。
  「自動の支払いは ManaPayment の順の先頭から行われる」という試験は、
  <b>期待する順序を実装から読んでいる</b>ので、順序を入れ替えても落ちない ——
  それは番人の失敗ではなく、<b>その試験が測っているのは「順と払いが一致すること」だから</b>である。
  順序<b>そのもの</b>を守っているのは別の試験(「表向きを温存する」)であり、
  そちらが軸1 の照合先になっている。
  ★<b>2つを混ぜて1つの試験にすると、どちらが壊れたのか分からなくなる。</b>

★★★<b>壊しどころが無い項目</b>(意図的に含めていないもの・裁定196 の正直な扱い):

  - <b>接続バー</b> …… 69 に続き 70 でも作っていない(候補 H)。無いものは壊せない。

  - <b>「マナが1通りでも自動確定しない」(裁定319)</b> ……
    自動確定の分岐は<b>そもそも書いていない</b>ので、消す対象が無い。
    代わりに軸17 の2件目が「確定を押すまで飛ばない」を名指しで測っている。

  - <b>ドラッグ中に render() を呼ばないこと</b> ……
    呼ぶとブラウザがドラッグを中断するので、壊すと verify が<b>複数落ちる</b>。
    「狙った1件が落ちる」という形にならないため、軸にしていない。
    ★代わりに、そうしてはいけない理由を battle.js の節の冒頭に書いてある。

  - <b>「巻ける状態に人が気づくか」(69 から継続)</b> …… 機械には測れない。

使い方: python3 tools/batch70_break_check.py [ケース番号...]
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

CSS = "src/main/resources/static/css/battle.css"
BATTLE_JS = "src/main/resources/static/js/battle.js"
MANA_PAYMENT = "src/main/java/com/example/qte/game/ManaPayment.java"
GAME_SERVICE = "src/main/java/com/example/qte/game/GameService.java"
GAME_ACTIONS = "src/main/java/com/example/qte/game/GameActions.java"
VIEW_BUILDER = "src/main/java/com/example/qte/game/view/GameViewBuilder.java"
REGISTRY = "src/main/java/com/example/qte/effect/CardEffectRegistry.java"
CARDS_HTML = "src/main/resources/templates/cards.html"

M2 = os.environ.get("QTE_M2_REPO", "/root/m2work/repository")

# 壊しても落ちないことが分かっているもの(理由つき)。
EXPECTED_NG = {
    "通常の支払い順を入れ替える(→ 順と払いの一致だけを見る試験。裁定298)":
        "この試験は<b>期待する順序を ManaPayment から読んでいる</b>ので、"
        "規則を変えると期待値も一緒に動く(裁定298)。"
        "順序そのものを守っているのは軸1 の「表向きを温存する」である —— "
        "★2つを1つの試験に混ぜると、どちらが壊れたのか分からなくなる。",
}

# (説明, ファイル, 置換前, 置換後, kind, 照合先クラス(junit のみ), 照合先の名前の一部)
CASES = [
    # ===============================================================
    # 軸1: ★通常の支払い順(裁定315・316)。一時マナ → 裏向き → 表向き
    # ===============================================================
    ("通常の支払い順を 69 の姿(先頭から)へ戻す", MANA_PAYMENT,
     "        return mana.isFaceUp() ? 2 : 1;   // 裁定315: 表向きは禁忌の弾として温存する",
     "        return 1;   // 表裏を見ない(69 までの姿)",
     "junit", "Batch70ManaPaymentTest", "自動の支払いは表向きを温存する"),

    # ★裁定298 の実例(わざと NG になる。上の EXPECTED_NG を参照)
    ("通常の支払い順を入れ替える(→ 順と払いの一致だけを見る試験。裁定298)", MANA_PAYMENT,
     "        return mana.isFaceUp() ? 2 : 1;   // 裁定315: 表向きは禁忌の弾として温存する",
     "        return mana.isFaceUp() ? 1 : 2;   // わざと逆",
     "junit", "Batch70ManaPaymentTest", "自動の支払いはManaPaymentの順の先頭から行われる"),

    # ===============================================================
    # 軸2: ★禁忌の支払い順(裁定317)。表向き → 裏向き
    #   ★逆にすると、表向きが在るのに<b>裏向きが墓地へ行く</b>
    # ===============================================================
    ("禁忌の支払い順を逆にする(裏向きから払う)", MANA_PAYMENT,
     "        return mana.isFaceUp() ? 0 : 1;   // 裁定317: 裏向きは墓地送りになるので後回し",
     "        return mana.isFaceUp() ? 1 : 0;   // わざと逆",
     "junit", "Batch70ManaPaymentTest", "禁忌は指定を省くと表向きから自動で払われる"),

    # ===============================================================
    # 軸3: ★人が選んだ支払いを尊重すること(裁定319)
    # ===============================================================
    ("指定を無視して自動で払う(裁定319 のクリック経路が死ぬ)", GAME_SERVICE,
     "        List<Integer> indexes = chosen.isEmpty()\n"
     "                ? ManaPayment.normalOrder(player).subList(0, cost)\n"
     "                : validateManaSelection(player, cost, chosen);",
     "        List<Integer> indexes = ManaPayment.normalOrder(player).subList(0, cost);",
     "junit", "Batch70ManaPaymentTest", "指定されたマナがそのとおりに払われる"),

    # ===============================================================
    # 軸4: ★通らない指定を弾くこと(設計判断27: 届いた値をそのまま信じない)
    # ===============================================================
    ("タップ済みのマナの指定を通す(盤面と支払いが静かにずれる)", GAME_SERVICE,
     "            if (player.getManaZone().get(index).isTapped()) {\n"
     "                throw new IllegalArgumentException(\"タップ済みのマナは支払いに使えません\");\n"
     "            }\n",
     "",
     "junit", "Batch70ManaPaymentTest", "通らないマナの指定は弾かれる"),

    # ===============================================================
    # 軸5: ★ビューが支払いの順を載せること(★照合先が2層あることの証拠)
    #   ★載らなければクライアントは何も光らせられず、規則を写したくなる
    # ===============================================================
    ("ビューに支払いの順を載せない(→ JUnit 層)", VIEW_BUILDER,
     "                isSelf ? ManaPayment.normalOrder(player) : List.of(),",
     "                List.of(),",
     "junit", "Batch70ManaPaymentTest", "ビューは自分にだけ支払いの順を載せる"),

    # ===============================================================
    # 軸6: ★問い合わせが「どのカードから出たか」を運ぶこと(指摘2)
    # ===============================================================
    ("問い合わせに出どころを写さない(→ JUnit 層)", GAME_ACTIONS,
     "        PendingChoice stamped = choice.withSourceCardId(room.getGameState() == null\n"
     "                ? null : room.getGameState().getResolvingCardId());",
     "        PendingChoice stamped = choice;",
     "junit", "Batch70PlayingCardTest", "スペルの問い合わせは出どころのカードIDを運ぶ"),

    # ===============================================================
    # 軸7: ★【召喚時】の対象の問い合わせも、解決中の内側で立つこと
    #   ★68 が足した needsTargetChoice は<b>効果を呼ぶ手前</b>で戻る。
    #     runEffect の外に出すと、15枚の【召喚時】だけ黙って表示が出なくなる
    # ===============================================================
    ("【召喚時】の対象の問い合わせを runEffect の外へ出す(69 の教訓・途中)", REGISTRY,
     "        runSummonEffect(minion, trigger, ctx, effect);",
     "        if (needsTargetChoice(ctx, minion, trigger)) {\n"
     "            return;\n"
     "        }\n"
     "        runEffect(minion.getMaster().id(), ctx, effect);",
     "junit", "Batch70PlayingCardTest", "ミニオンの召喚時の問い合わせも出どころのカードIDを運ぶ"),

    # ===============================================================
    # 軸8: ★効果を呼ぶ口が1つであること(直呼びを足すと、そのカードだけ黙る)
    # ===============================================================
    ("効果の直呼びを1つ足す(runEffect を素通りする経路を作る)", GAME_SERVICE,
     "        effects.runEffect(minion.getMaster().id(),\n"
     "                contextOf(room, state, player, minion, resolved), spec.effect());",
     "        spec.effect().accept(contextOf(room, state, player, minion, resolved));",
     "junit", "Batch70PlayingCardTest", "効果を呼ぶ口はrunEffectだけである"),

    # ===============================================================
    # 軸9: ★ホバーの取りこぼし(指摘1)。禁忌の帯とパイルで1件ずつ
    # ===============================================================
    ("禁忌の帯のホバーを取り付けない(69 の取りこぼしへ戻す)", BATTLE_JS,
     "    attachHover(el, tabooFace);\n",
     "",
     "verify", None, "禁忌の帯とパイルの一番上にもホバープレビューが出る"),

    ("パイルの一番上のホバーを取り付けない", BATTLE_JS,
     "        attachHover(el, topFace);\n",
     "",
     "verify", None, "禁忌の帯とパイルの一番上にもホバープレビューが出る"),

    # ===============================================================
    # 軸10: ★モーダルの中では左へ逃がすこと(実測でここだけが重なる)
    # ===============================================================
    ("モーダルが開いていても位置を動かさない(面がカード一覧を覆う)", BATTLE_JS,
     "            hover.classList.toggle('auto-hover-left', modalOpen());",
     "            hover.classList.toggle('auto-hover-left', false);",
     "verify", None, "ゾーン一覧でもホバーが出て、モーダル本体と重ならない"),

    # ===============================================================
    # 軸11: ★スペル枠を常設すること(裁定320)
    # ===============================================================
    # ★<b>幅を0にする改変は使えない。</b>flex アイテムの min-width は auto なので、
    #   中の文字が枠を押し広げてしまう(実際に試して NG になった)。器そのものを消す。
    # ★★<b>宣言の順にも注意する。</b>ブロックの頭に display: none を足しても、
    #   後ろの display: flex に負ける(これも実際に試して NG になった)。
    #   ★<b>同じ性質の値を2回書けば、勝つのは「意図」ではなく「後に書いたほう」である</b>(65 の筋)。
    ("スペル枠を出さない(「決まった場所」が無くなる)", CSS,
     "    display: flex; align-items: center; justify-content: center; text-align: center;\n"
     "    border: 2px dashed #4a4460; border-radius: 8px;",
     "    display: none; align-items: center; justify-content: center; text-align: center;\n"
     "    border: 2px dashed #4a4460; border-radius: 8px;",
     "verify", None, "スペルの枠は自分のミニオン行の右に常設され"),

    # ===============================================================
    # 軸12: ★落とし先は種別で決まること(裁定318)
    # ===============================================================
    ("ウェポンの落とし先を盤面にする(裁定318 の対応表を壊す)", BATTLE_JS,
     "    if (type === 'WEAPON') return 'LEADER';",
     "    if (type === 'WEAPON') return 'FIELD';",
     "verify", None, "掴むと種別に合った落とし先だけが光る"),

    # ===============================================================
    # 軸13: ★払う予定のマナを強調すること(裁定315・316)
    # ===============================================================
    ("ドラッグ中に何も光らせない", BATTLE_JS,
     "    return order.slice(0, draggingCost());",
     "    return [];",
     "verify", None, "ドラッグ中に光るマナは、サーバの支払い順の先頭 n 件である"),

    # ★★<b>67 の教訓「写し」そのものの形。</b>サーバの順を読まずに
    #   「マナゾーンの先頭から」を書き写すと、<b>光る場所だけが黙って嘘になる</b>
    ("サーバの順を読まず、クライアントが自分で先頭から数える(写し)", BATTLE_JS,
     "    return order.slice(0, draggingCost());",
     "    return Array.from({ length: draggingCost() }, (unused, i) => i);",
     "verify", None, "ドラッグ中に光るマナは、サーバの支払い順の先頭 n 件である"),

    # ===============================================================
    # 軸14: ★ドラッグで実際にプレイされること(裁定321: 確認を挟まない)
    # ===============================================================
    ("ドロップ先を1つも登録しない(掴めるが落とせない)", BATTLE_JS,
     "DROP_ZONES.forEach(({ zone, id }) => registerDropZone(zone, id));\n",
     "",
     "verify", None, "ドラッグで落とすと確認なしでプレイされ"),

    # ===============================================================
    # 軸15: ★裁定317 の警告(裁定321 の唯一の例外)
    # ===============================================================
    ("裏向きが墓地送りになる禁忌でも止めない(取り返しがつかない支払いを黙って通す)", BATTLE_JS,
     "        if (tabooPayBurns(cost)) {",
     "        if (false) {",
     "verify", None, "裏向きマナが墓地送りになる禁忌のドラッグは、確認で止まる"),

    # ===============================================================
    # 軸16: ★進化は素材の上にしか落とせないこと(裁定318・322)
    # ===============================================================
    ("素材でない場所に落とした進化も通す(裁定318 の但し書きを落とす)", BATTLE_JS,
     "        if (!droppedOnInstanceId\n"
     "                || !(card.evolutionMaterialIds || []).includes(droppedOnInstanceId)) {",
     "        if (false) {",
     "verify", None, "進化は素材の上に落としたときだけ通り"),

    # ===============================================================
    # 軸17: ★マナチャージの2入口(裁定323)。落とし先とクリックの確認で1件ずつ
    # ===============================================================
    ("マナゾーンをドロップ先から外す(光る条件は在るが行き先が無い姿へ戻す)", BATTLE_JS,
     "    { zone: 'MANA', id: 'my-mana-row' },     // マナチャージ(裁定323)\n",
     "",
     "verify", None, "マナチャージフェイズは落とし先がマナゾーンに切り替わる"),

    ("クリックのマナチャージを 66 の姿(即送信)へ戻す", BATTLE_JS,
     "        beginManaPayment({ kind: 'CHARGE', cost: 0, card: latestView.you.hand[index],\n"
     "            handIndex: index });",
     "        send('charge-mana', { handIndex: index });",
     "verify", None, "クリックのマナチャージは確認を挟む"),

    # ===============================================================
    # 軸18: ★「今プレイしているカード」を出すこと(指摘2)
    #   ★<b>サーバ側(出どころを運ぶこと)は軸6 が JUnit で測る。</b>
    #     verify はフィクスチャで描くので、Java のビューを壊しても届かない(下の注記)
    # ===============================================================
    ("プレイ中の面を描かない(問い合わせ中に何が起きているか分からなくなる)", BATTLE_JS,
     "    renderPlayingCard(view);\n",
     "",
     "verify", None, "問い合わせ中は「プレイ中のカード」が出て、盤面のカードを覆わない"),

    # ===============================================================
    # 番外: ★静的ファイルの版数(7-5)。5枚のうち1枚だけ上げ忘れる
    #   ★69 まで、これを測る番人は1つも無かった
    # ===============================================================
    ("cards.html だけ版数を上げ忘れる(そのページだけ古い CSS を受け取り続ける)", CARDS_HTML,
     "@{/css/battle.css(v=51)}",
     "@{/css/battle.css(v=50)}",
     "junit", "Batch70PlayingCardTest", "battleCssを読む5枚のテンプレートは同じ版数である"),
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


# ---- JUnit(盤面とファイルを読む番人) ----

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

    ★<b>「ビルドが失敗した」を OK と数えない。</b>報告書そのものが生まれなければ EMPTY である
    (裁定304: Java では条件を落とすとコンパイルが通らないことがある)。
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
    # ★★★<b>殺されても書き戻す。</b>70 の着手時、10分の上限でこのスクリプトごと
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
    run_verify()
    good = counts.get("OK", 0) + counts.get("NG(想定内)", 0)
    return 0 if good == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
