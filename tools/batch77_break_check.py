#!/usr/bin/env python3
"""Batch 77(禁忌デッキからの進化召喚)の壊し検証(裁定116)。

実装をわざと壊し、狙った番人が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った番人が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(番人が足りない)
  EMPTY    … その番人が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★★★<b>照合先は2つある</b>(設計判断45・72・74・75・76 と同じ「両方あるバッチ」)——
  ただし 77 は<b>重心が verify に大きく寄っている</b>。

  - <b>クライアントの振る舞い</b>(素材を問う段が出るか・materialIds が飛ぶか)
    …… {@code verify}。<b>77 が直したのは battle.js だけ</b>なので、ここが本体である。
  - <b>クライアントが読む材料が届いているか</b>(禁忌の面の evolutionMaterialIds)と
    <b>サーバが素材を要求し続けていること</b> …… {@code Batch77TabooEvolutionTest}。
    ★verify のハーネスは Java を起こさないので、あちらには1件も届かない。
  - <b>版数</b> …… {@code BattlePageTest}。

★★<b>出口ごとに当てている</b>(71・75・76 の教訓)。禁忌から場へ出る道は4つある ——

  - クリック(マナ確定 → 素材 → 送信) ……………………………… 軸6・7・8
  - ドラッグ・表向きから払える(即 素材 → 送信) ……………… 軸9・10
  - ドラッグ・裏向きが焼ける(マナ確定 → 素材 → 送信) ……… 軸11
  - 素材でない場所に落とした(止まる) ………………………………… 軸12
  - ★賢魂の道(素材を取ってはいけない側) ……………………… 軸13・14

★★★<b>独立した項目を先頭へ、遷移を起こしうる項目を末尾へ</b>(72・75 の教訓)。
  JUnit の軸(1〜5)を先に、verify を回す軸(6〜14)を末尾に置いてある。

★★★<b>NG が出たら、まず実装ではなく番人を疑うこと</b>(75 の教訓)——
  77 の JUnit を書いたときも、最初の3回の赤はすべて番人側(試験の盤面)の間違いだった。

使い方: python3 tools/batch77_break_check.py [ケース番号...]
★★<b>長いので2つに分けて回し、前後で `git diff --stat` を突き合わせること</b>(70 の教訓)。
  例: `1..5` / `6..14`
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
M2 = "/root/m2work/repository"

SERVICE = "src/main/java/com/example/qte/game/GameService.java"
VIEWS = "src/main/java/com/example/qte/game/view/GameViewBuilder.java"
BATTLE_JS = "src/main/resources/static/js/battle.js"
BATTLE_HTML = "src/main/resources/templates/battle.html"

TABOO_TEST = "Batch77TabooEvolutionTest"
PAGE_TEST = "BattlePageTest"

# 壊しても落ちないことが分かっているもの(理由つき)。★77 は1件も無い。
EXPECTED_NG = {}

# ★★★<b>壊しどころが無いもの</b>(裁定196 の正直な扱い)——
#   軸に入れていない理由を書き残す。
#
#   1) <b>サーバ側は1文字も変えていない。</b>
#      {@code playTabooCard} が {@code resolveMaterials} を呼ぶ形は Batch 52 からのもので、
#      77 が足したものではない。★<b>それでも軸4・5 で見張っている</b> ——
#      あれは「直したこと」ではなく<b>クライアントを直す理由が消えていないこと</b>を
#      測る番人だからである(サーバが素材を要求しなくなったら、77 の分岐は不要になる)。
#
#   2) <b>手札からの進化(裁定322)は 70 のものであり、77 は触っていない。</b>
#      70-8 の項目がそのまま見張っている。★77 の軸9 と<b>並べて読むためだけ</b>に
#      同じ盤面・同じ所作で書いてあり、壊しどころを重ねてはいない。
#
#   3) <b>{@code battle.css} は1文字も触っていない。</b>
#      素材を選ぶ段の見た目({@code attack-target} / {@code exhausted})は
#      Batch 52 のものをそのまま使う —— 入口が増えても、素材を選ぶ画面は1つである。
#      ★据え置きであることは軸3 が「上げたら落ちる」側から見張る(74 の教訓・据え置き)。
#
#   4) <b>{@code confirmManaPayment} の {@code kind === 'CHARGE'} の道。</b>
#      マナチャージに進化は無い(手札から1枚をマナに置くだけである)。
#      70-9 の項目が引き続き見張っており、77 は1文字も触っていない。

# (説明, ファイル, 置換前, 置換後, 種別, クラス, 照合先の名前の一部)
CASES = [
    # ===============================================================
    # I. 材料が届いているか / サーバが要求し続けているか(軸1〜5・JUnit)
    #    ★独立している(ブラウザを起こさない)ので先頭に置く
    # ===============================================================

    # 軸1: ★★★禁忌の面に素材の候補を添えるのをやめる(52 のコメントごと裏切る)
    #   ★これが 77 の不具合の「もう一段深い版」である ——
    #     届いていなければ、クライアントを直しても選ばせようがない。
    ("禁忌の面に進化素材の候補を添えない(手札のときだけ添える)", VIEWS,
     "        EvolutionSpec evolution = effects.evolutionOf(master.id());",
     "        EvolutionSpec evolution = handIndex < 0 ? null : effects.evolutionOf(master.id());",
     "junit", TABOO_TEST, "禁忌デッキの面にも進化素材の候補が載る"),

    # 軸2: ★候補の絞り込みを外す(自分の場のミニオンを全部候補にする)
    #   ★★「載っている」だけを測る番人なら、これで緑のままになる(裁定181 の必要性)
    ("禁忌の面の候補が素材条件で絞られなくなる", VIEWS,
     "    private List<String> evolutionMaterialIds(PlayerState player, EvolutionSpec evolution) {",
     "    private List<String> evolutionMaterialIds(PlayerState player, EvolutionSpec evolution) {\n"
     "        if (evolution != null) {\n"
     "            return player.getMinionZone().stream()\n"
     "                    .map(com.example.qte.game.MinionInstance::getInstanceId).toList();\n"
     "        }",
     "junit", TABOO_TEST, "禁忌デッキの面の候補は素材条件で絞られている"),

    # 軸3: ★★版数を据え置く(77 は battle.js を触っている)
    ("battle.js の版数を 38 のまま据え置く", BATTLE_HTML,
     "battle.js(v=39)", "battle.js(v=38)",
     "junit", PAGE_TEST, "通常モードの盤面のJSの版数が77で上がっている"),

    # 軸4: ★★★据え置きの番人を逆から当てる(74 の教訓・据え置き)
    #   ★<b>触っていない CSS の版数を上げる</b>。上げるのも据え置くのも
    #     「触ったかどうか」だけで決まる(7-5)ので、これは規則違反である。
    ("触っていない battle.css の版数を上げる(据え置きの番人を当てる)", BATTLE_HTML,
     "battle.css(v=54)", "battle.css(v=55)",
     "junit", PAGE_TEST, "通常モードの盤面のCSSの版数は77で据え置きである"),

    # 軸5: ★★サーバが素材を要求しなくなる(クライアントを直す理由が消える側)
    ("サーバが素材の枚数を検査しなくなる", SERVICE,
     "        if (ids.size() < spec.minMaterials() || ids.size() > spec.maxMaterials()) {",
     "        if (false) {",
     "junit", TABOO_TEST, "素材を送らない禁忌の進化召喚はサーバが断る"),

    # ===============================================================
    # II. クリックの入口(軸6〜8・verify)
    # ===============================================================

    # 軸6: ★★★76 の姿へ戻す —— confirmManaPayment が TABOO を先に return する
    #   ★<b>これがマスターの踏んだ不具合そのものである。</b>
    ("確定のあと TABOO を先に返して素材を問わない(76 の姿へ戻す)", BATTLE_JS,
     "    if (pay.evolutionFlow) {\n"
     "        // ★進化素材の選択は<b>送信を伴わない</b>ので、ここは必ず先へ進む",
     "    if (pay.evolutionFlow && !isTaboo) {\n"
     "        // ★進化素材の選択は<b>送信を伴わない</b>ので、ここは必ず先へ進む",
     "verify", None, "禁忌をクリックして進化を使うと"),

    # 軸7: ★入口の側で旗を立てるのをやめる(軸6 と同じ出口・別の場所)
    #   ★★<b>2箇所そろって初めて効く</b>ので、片方ずつ当てる(71・75 の教訓)
    ("禁忌のクリックが evolutionFlow を立てない", BATTLE_JS,
     "        evolutionFlow: action === 'play-taboo' && card.type === 'EVOLUTION' });",
     "        evolutionFlow: false });",
     "verify", None, "禁忌をクリックして進化を使うと"),

    # 軸8: ★★禁忌なのに handIndex を本文に載せる(送る本文の形を壊す)
    #   ★<b>素材を足したせいで禁忌の本文が壊れていないこと</b>を測る側である(72b の教訓・あいだ)
    ("禁忌の本文に handIndex が混ざる", BATTLE_JS,
     "    const handIndex = isTaboo ? null : pay.handIndex;",
     "    const handIndex = pay.handIndex === undefined ? 0 : pay.handIndex;",
     "verify", None, "禁忌の進化は materialIds を連れて play-taboo へ飛ぶ"),

    # ===============================================================
    # III. ドラッグの入口(軸9〜12・verify)
    # ===============================================================

    # 軸9: ★★★ドラッグの道から進化の枝を消す(76 の姿へ戻す)
    ("禁忌のドラッグが進化を素通りする(76 の姿へ戻す)", BATTLE_JS,
     "        const asEvolution = !asSoul && card.type === 'EVOLUTION';",
     "        const asEvolution = false;",
     "verify", None, "禁忌のドラッグも、落とした先が1体目の素材になる"),

    # 軸10: ★落とし先を1体目の素材にしない(裁定352 の中身だけを壊す)
    #   ★★軸9 とは<b>別の出口</b>である —— あちらは枝ごと、こちらは種を蒔く1行だけ
    ("禁忌のドラッグが落とし先を素材にしない(問い合わせのまま止まる)", BATTLE_JS,
     "            beginEvolutionSelection(action, null, specs,\n"
     "                { tabooIndex: d.index, manaIndexes: [] }, card);\n"
     "            if (evolution) pickEvolutionMaterial(droppedOnInstanceId);",
     "            beginEvolutionSelection(action, null, specs,\n"
     "                { tabooIndex: d.index, manaIndexes: [] }, card);",
     "verify", None, "禁忌のドラッグも、落とした先が1体目の素材になる"),

    # 軸11: ★★★焼ける道で落とし先を運ばない(確定待ちの向こうで消える)
    #   ★<b>3つ目の出口である。</b>確定待ちを1段はさむのはこの道だけであり、
    #     軸9・10 を直しても、ここだけ別に壊れうる。
    ("焼ける禁忌の進化が確定待ちの向こうで落とし先を忘れる", BATTLE_JS,
     "                materialSeed: asEvolution ? droppedOnInstanceId : null,",
     "                materialSeed: null,",
     "verify", None, "焼ける禁忌の進化は確認で止まり"),

    # 軸12: ★素材でない場所への落としを弾かなくなる(そうでない側・裁定181)
    ("素材でないミニオンの上に落としても通ってしまう", BATTLE_JS,
     "        if (asEvolution && (!droppedOnInstanceId\n"
     "                || !(card.evolutionMaterialIds || []).includes(droppedOnInstanceId))) {",
     "        if (false) {",
     "verify", None, "禁忌の進化を素材でない場所に落としたら"),

    # ===============================================================
    # IV. 賢魂の道は素材を取らない(軸13・14・verify)
    #     ★★掛ける場所を、掛かる場所より広く取らない(72 の教訓・幅)
    # ===============================================================

    # 軸13: ★★★ドラッグの賢魂まで進化にする
    ("進化かつ賢魂の禁忌をスペル枠へ落としても素材を問う(ドラッグ)", BATTLE_JS,
     "        const asEvolution = !asSoul && card.type === 'EVOLUTION';",
     "        const asEvolution = card.type === 'EVOLUTION';",
     "verify", None, "進化かつ賢魂の禁忌をスペル枠へ落としても"),

    # 軸14: ★★クリックの賢魂まで進化にする(軸13 と同じ規則・別の入口)
    #   ★★★<b>この軸が番人を1つ増やさせた。</b>
    #     最初は照合先を 54 の項目({@code 禁忌の【賢魂】は n 枚のマナを退けて…})に
    #     向けていたが、<b>NG が返った</b> —— あの項目の盤面の禁忌は
    #     《グレイヴガールズファン》(MINION)であり、<b>進化ではないので改変が効かない</b>
    #     (「壊しても落ちない」11の形のうち「その分岐に入る盤面が構造的に作れない」)。
    #   ★<b>NG が出たら、まず実装ではなく番人を疑う</b>(75 の教訓)——
    #     疑ったら、足りていなかったのは<b>クリックの入口を見張る項目</b>だった。
    #     77-6 を新設して照合先をそちらへ向けてある。
    ("進化かつ賢魂の禁忌をクリックで賢魂として使っても素材を問う", BATTLE_JS,
     "        evolutionFlow: action === 'play-taboo' && card.type === 'EVOLUTION' });",
     "        evolutionFlow: card.type === 'EVOLUTION' });",
     "verify", None, "クリックで賢魂として使っても、素材は問わない"),
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


# ---- JUnit(サーバの状態と版数を読む番人) ----

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
