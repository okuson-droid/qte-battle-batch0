#!/usr/bin/env python3
"""Batch 80(通常モードの演出 = 候補 B)の壊し検証(裁定116)。

実装をわざと壊し、狙った番人が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った番人が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(番人が足りない)
  EMPTY    … その番人が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★★★<b>このバッチが変えたのは4本である</b> ——
  {@code battle.js} / {@code battle.css} / テンプレート5枚の版数 / {@code verify/verify.js}。
  <b>Java(本体)・カード定義・効果・manual-battle.js は1文字も触っていない</b>。
  ★JUnit は<b>版数と時間</b>だけを見張るので、軸も3本だけである
  (設計判断45: 番人は「回る場所」で選ぶ)。

★★★<b>出口ごと・入口ごとに当てる</b>(71・75〜79 の教訓):
  - <b>結び方</b>は3段あるので3軸(名前で結ぶ / 匿名で結ぶ / 結ばない)。
  - <b>アンカー</b>は「全部やめる」と「1つだけ落とす」で2軸。
  - <b>演出の入口</b>は「呼ばない」と「呼びすぎる」で2軸 ——
    出さないのと出しすぎるのは<b>別の壊れ方</b>である。
  - <b>見た目のゲート</b>は「効かない」と「効きすぎる(音を道連れにする)」で2軸。

★★★<b>独立した項目を先頭へ、遷移を起こしうる項目を末尾へ</b>(72・75 の教訓)。
  ★<b>JUnit の3軸を先頭に置いてある</b> —— 1回 30秒で終わり、他と一切干渉しない。
  ★★<b>{@code fxSpawn} を呼ぶ場所を壊す2軸(17・18)は末尾</b> ——
    <b>あの2つは他の項目の画面にもゴーストを撒く</b>。

★★★<b>NG が出たら、まず実装ではなく番人を疑うこと</b>(75 の教訓)。
  ★<b>このバッチでは、置いた番人が先に実装の穴を1つ見つけた</b> ——
    軸6 がその穴の軸である(下の注を読むこと)。

★★★<b>結果: 19軸すべて OK。</b>ただし<b>3軸は最初そうではなかった</b> ——
  どれも「壊し方」または「番人の照合先」のほうが悪かった:
  - <b>軸9 は EMPTY だった</b>。ガードを外すだけだと実装が投げ、
    <b>検証スクリプトごと死ぬ</b>(72・75 の教訓)。短いほうだけ回す形へ直した。
  - <b>軸18 は NG だった</b>。守りが<b>二重に</b>あり(取り付け点 + 使い切り)、
    片方を壊しても振る舞いは変わらなかった ——
    <b>番人の照合先を「振る舞い」から「構造」へ移した</b>。
  - <b>軸6 は実装の穴を見つけた</b>(壊し検証ではなく、置いた番人が先に赤くなった)。

使い方: python3 tools/batch80_break_check.py [ケース番号...]
★★<b>verify の軸は1軸あたり1回転ぶん掛かる。分けて回し、
  前後で `git diff --stat` を突き合わせること</b>(70 で殺されて壊したまま残った・77 でも殺された)。
  例: `1 2 3` / `4 5 6 7 8 9` / `10 11 12 13` / `14 15 16` / `17 18 19`
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
M2 = "/root/m2work/repository"

JS = "src/main/resources/static/js/battle.js"
CSS = "src/main/resources/static/css/battle.css"
HTML = "src/main/resources/templates/battle.html"

# ★壊しても落ちないことが分かっているもの(理由つき)。
#   ★★<b>「落ちない」ことも書き残す</b> —— 次の人が「番人が足りない」と読まないために。
#   ★★★<b>Batch 80 には1件も無い。</b>19軸すべてが落ちるはずである ——
#     79 が2件持っていたのは「予防で直した箇所」であり、80 に予防の修正は1つも無い。
EXPECTED_NG = {}

# ★★★<b>壊しどころが無いもの</b>(裁定196 の正直な扱い)——
#   軸に入れていない理由を書き残す。
#
#   1) <b>儀式(配り・マリガン・ダイス・シャッフル)。</b>
#      80 は作っていない(裁定357)。★<b>材料がサーバに無い</b>ので、壊す先が存在しない。
#
#   2) <b>一時公開ゾーンの演出。</b>
#      アンカーが無い(母集団C の C9)—— <b>battle.js は revealedCards を1度も読んでいない</b>。
#      ★軸11 の隣の番人(「一時公開ゾーンにはアンカーが無い」)が、
#        <b>塞いだ日に赤くなる</b>形で見張っている —— それは壊し検証ではなく<b>予約</b>である。
#
#   3) <b>決着とマリガンの見た目。</b>
#      {@code fxBuild} が {@code null} を返す(裁定357)。
#      ★決着は右列の中段に面が出る(72 の renderResult)ので、帯を重ねると二重になる。
#      <b>「作っていないもの」は壊せない。</b>
#
#   4) <b>Java(本体)・カード定義・効果・manual-battle.js。</b>
#      80 は1文字も触っていない。
#      ★<b>ただし軸3・19 は手動モードの<b>値を読む</b></b> ——
#        壊すのは通常モード側であり、手動モードは読み取り専用の相手である。
#
#   5) <b>{@code fxRegister} の二重保険(終了イベント + タイマー)。</b>
#      片方を外しても<b>ゴーストは最終的に消える</b>ので、静止画を撮る検証では捕まらない。
#      ★<b>捕まえるには時間を測る番人が要り、それは「たまに落ちる番人」になる</b>
#        (74・75・79 の教訓)。<b>置かないほうが正しい。</b>

# (説明, ファイル, 置換前, 置換後, 走らせ方, JUnitのクラス, 照合先の名前の一部)
CASES = [
    # ===============================================================
    # I. 版数と時間(軸1〜3)★JUnit。1回 30秒で終わり、他と干渉しない
    # ===============================================================

    # 軸1: ★版数を据え置いてしまう(JS)
    #   ★<b>上げないと、既に開いている人だけが演出の無い画面で遊び続ける</b>。
    ("battle.js の版数を 80 で上げ忘れる", HTML,
     "/js/battle.js(v=41)", "/js/battle.js(v=40)",
     "junit", "BattlePageTest", "通常モードの盤面のJSの版数が80で上がっている"),

    # 軸2: ★版数を1枚だけ上げ忘れる(CSS)★<b>5枚のうち1枚である</b>(7-5)
    #   ★<b>1枚だけ古いと、そのページから来た人だけゴーストが左上に貼りつく</b>。
    ("battle.css の版数を battle.html だけ上げ忘れる", HTML,
     "/css/battle.css(v=56)", "/css/battle.css(v=55)",
     "junit", "Batch70PlayingCardTest", "battleCssを読む5枚のテンプレートは同じ版数である"),

    # 軸3: ★★★時間を手動モードと同じにする(裁定358)
    #   ★<b>「揃っていないことが要求である」珍しい番人を当てる。</b>
    #     設計判断54(変えないと決めたことにも番人を置く)の裏返しである。
    ("演出の時間を手動モードと同じ 220ms にする", JS,
     "const FX_MOVE_MS = 340;", "const FX_MOVE_MS = 220;",
     "junit", "BattlePageTest", "通常モードの演出の時間は手動モードより長い"),

    # ===============================================================
    # II. 結び方の3段(軸4〜6)★★出口ごとに当てる
    #     ★★★<b>3段は別々の規則である</b> —— まとめて1軸にしない
    # ===============================================================

    # 軸4: ★★名前で結ぶ段を殺す(裁定355 の第1段)
    #   ★これを殺すと、スペル使用とミニオン破壊が同じ配信で起きたときに
    #     <b>どちらがどちらか決まらなくなる</b>(出口2・入口2 は匿名では解けない)。
    ("カード名で結ぶ段を殺す", JS,
     "    for (const sameSeat of [true, false]) {",
     "    for (const sameSeat of []) {",
     "verify", None, "名前で結ぶので取り違えない"),

    # 軸5: ★★匿名で結ぶ段を殺す(裁定355 の第2段)
    #   ★<b>ドローはここでしか結ばれない</b> —— 山札は名前を持たないからである。
    ("匿名で結ぶ段を殺す", JS,
     "    if (out.exits.length === 1 && out.entries.length === 1) {",
     "    if (out.exits.length === 1 && out.entries.length === 99) {",
     "verify", None, "ドローは山札から同じ席の手札へ飛ぶ"),

    # 軸6: ★★★「中身が届いているか」の見分けを null 判定へ戻す
    #   ★★★<b>これは実際に踏んだ穴の軸である。</b>最初の版は {@code !!list} だけを見ており、
    #     <b>相手のドローを1件も採れなかった</b> —— フィクスチャは相手席にも
    #     {@code hand: []} を入れているためである。
    #   ★直し方は<b>手動モードの語彙に寄せること</b>だった:
    #     <b>枚数 &gt; 届いた配列の長さ なら「窓」である</b>({@code fxWindowedZones})。
    #   ★★<b>壊し検証より先に、置いた番人が教えた</b>(75 の教訓の、置いた当日の実例)。
    ("中身が届いているかを配列の有無だけで見分ける", JS,
     "    const full = (list, count) => !!list && list.length === (count || 0);",
     "    const full = (list, count) => !!list && count >= 0;",
     "verify", None, "相手のドローも同じ規則で結ばれる"),

    # ===============================================================
    # III. 結ばなかったものの扱い(軸7〜9)★★裁定356 の3つの顔
    # ===============================================================

    # 軸7: ★★窓のゾーンでも消滅を出してしまう(裁定356 の例外を殺す)
    #   ★<b>山札は中身が1枚も届かない</b> —— そこで消滅を描いても
    #     裏面が1枚点滅するだけで何も語らない。
    ("窓のゾーンでも消滅を出す", JS,
     "        if (exit.blind) continue;",
     "        if (exit.blind && false) continue;",
     "verify", None, "山札だけが減った配信では、演出を1つも出さない"),

    # 軸8: ★★★一意に決まらなくても無理やり結ぶ
    #   ★<b>これが「嘘を描く」壊れ方である。</b>行き先が2つあるのに片方へ結ぶと、
    #     <b>起きていない移動</b>を見せることになる。
    ("行き先が2つあっても先頭へ結んでしまう", JS,
     "    if (out.exits.length === 1 && out.entries.length === 1) {\n"
     "        moves.push(fxJoin(out.exits[0], out.entries[0]));",
     "    if (out.exits.length === 1 && out.entries.length >= 1) {\n"
     "        moves.push(fxJoin(out.exits[0], out.entries[0]));",
     "verify", None, "移動にせず消滅として語る"),

    # 軸9: ★★マナの枚数ガードを外す(位置で追うものの前提を壊す)
    #   ★<b>枚数が変われば位置がずれる</b> —— 裏返っていないマナを「裏返った」と描く。
    #
    # ★★★<b>最初の壊し方は「ガードに && false を足す」だったが、EMPTY を返した。</b>
    #   ガードを外しただけだと <b>{@code b[i]} が undefined になって実装が投げ</b>、
    #   {@code evaluate} の失敗が<b>検証スクリプトごと殺す</b> ——
    #   以降が1件も走らないので「どの番人が落ちたか」が読めなくなる
    #   (72・75 の教訓・<b>死ぬ検証は番人ではなく無音である</b>)。
    # ★★<b>NG(や EMPTY)が出たら、まず実装ではなく番人を疑う</b>(75 の教訓)——
    #   ここで疑うべきは<b>壊し方</b>のほうであった。
    # ★<b>短いほうだけを回して壊す</b>形に直すと、投げずに<b>誤った flip を1件出す</b> ——
    #   これがこの軸で見たかった壊れ方である。
    ("マナの枚数が変わっても位置で裏返りを採る", JS,
     "    if (b.length !== a.length) return;\n"
     "    for (let i = 0; i < a.length; i++) {",
     "    for (let i = 0; i < Math.min(a.length, b.length); i++) {",
     "verify", None, "マナの枚数が変わった配信では裏返りを採らない"),

    # ===============================================================
    # IV. 器(アンカー)と見た目(軸10〜13)★★出口ごとに当てる
    # ===============================================================

    # 軸10: ★★アンカーを全部登録しない(器そのものを壊す)
    ("パイルのアンカーを登録しない", JS,
     "        registerAutoAnchor(el, seat, zone);",
     "        void el;",
     "verify", None, "9ゾーン × 2席 = 18本そろっている"),

    # 軸11: ★★★アンカーを<b>1つだけ</b>落とす(出口ごと・71 の教訓)
    #   ★<b>全部やめるのと1つ落とすのは別の壊れ方である。</b>
    #     数を数える番人は前者しか捕まえないことがある —— <b>鍵まで見ているか</b>を確かめる。
    ("自席のウェポンのアンカーだけ登録しない", JS,
     "    registerAutoAnchor(\n"
     "        document.querySelector('#my-leader .auto-leader-weapon'), 'you', 'WEAPON');",
     "    void 0;",
     "verify", None, "9ゾーン × 2席 = 18本そろっている"),

    # 軸12: ★★層が操作を吸ってしまう(演出が守りに化ける)
    #   ★<b>覆いは守りではない</b>(71 の判断)—— 演出が操作を妨げたら、それは不具合である。
    ("fx層が操作を吸うようにする", CSS,
     "    pointer-events: none;\n    overflow: hidden;\n}\n\n"
     "/* ---- 飛ぶ・消える・現れるゴースト ---- */",
     "    pointer-events: auto;\n    overflow: hidden;\n}\n\n"
     "/* ---- 飛ぶ・消える・現れるゴースト ---- */",
     "verify", None, "層は操作を妨げない"),

    # 軸13: ★★LPのラベルを読めない色にする(32a からの規約: fx層の文字も網に入れる)
    #   ★<b>fx層は body 直下に在る</b>ので、盤面のセレクタでは判定の網に静かに漏れる。
    ("LPのラベルを黒地で読めない色にする", CSS,
     ".auto-fx-lp-down { color: #ff9b9b; }",
     ".auto-fx-lp-down { color: #2a2020; }",
     "verify", None, "黒地の上で 4.5:1 を超える"),

    # ===============================================================
    # V. ゲートと上限(軸14〜16)★★「効かない」と「効きすぎる」を分けて当てる
    # ===============================================================

    # 軸14: ★★上限のガードを外す(裁定8 の通常モード版)
    ("上限を超えた配信でも演出を出す", JS,
     "    if (effects.length === 0 || effects.length > FX_LIMIT) return;",
     "    if (effects.length === 0) return;",
     "verify", None, "上限を超えた配信は演出を1つも出さない"),

    # 軸15: ★★見た目のゲートが効かない(prefers-reduced-motion を見ない)
    ("prefers-reduced-motion を見ない", JS,
     "    if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {",
     "    if (window.matchMedia && false) {",
     "verify", None, "演出を切っている人には出ない"),

    # 軸16: ★★★ゲートが効きすぎる —— <b>音を道連れにする</b>
    #   ★★<b>これは 37 が手動モードで踏んだ形である。</b>見た目のゲートで差分の計算を
    #     飛ばすと、<b>演出を切っている人から音まで消える</b>。
    #   ★<b>軸15 の逆向きである</b> —— 同じゲートに2つの壊れ方がある(出口ごと)。
    ("見た目を切ると差分そのものを採らなくなる", JS,
     "    return fxAllowed() || sfxReady();",
     "    return fxAllowed();",
     "verify", None, "音は道連れにしない"),

    # ===============================================================
    # VI. 演出の入口(軸17〜18)★★★<b>末尾に置く</b> ——
    #     この2つは<b>他の項目の画面にもゴーストを撒く</b>(72・75 の教訓)
    # ===============================================================

    # 軸17: ★★演出を呼ばない(入口を塞ぐ)
    ("配信のあとに fxSpawn を呼ばない", JS,
     "    fxSpawn();",
     "    void 0;",
     "verify", None, "配信でゴーストが飛ぶ"),

    # 軸18: ★★★演出を呼びすぎる(描き直しのたびに出す)
    #   ★<b>62 が音で確かめたのと同じ形である</b>(裁定287)——
    #     {@code render(latestView)} は<b>画面の操作のたびにも走る</b>(15箇所)。
    #   ★★<b>出さないのと出しすぎるのは別の壊れ方である</b> —— 軸17 では捕まらない。
    #
    # ★★★<b>この軸は最初 NG を返した。</b>{@code render()} へ {@code fxSpawn()} を足しても、
    #   <b>振る舞いを見る番人は緑のままだった</b> —— {@code pendingFx} を1回で使い切るので、
    #   2度目以降は何も出ないからである。<b>守りが二重にあった</b>。
    # ★★<b>守りが二重なのは良いことだが、番人が片方しか見ていないのは別の話である</b>。
    #   そこで<b>取り付け点が1箇所であること</b>を構造として測る番人を足した ——
    #   <b>これが裁定287 の言っている性質そのものである</b>。
    # ★<b>照合先を「振る舞い」から「構造」へ移した</b>のがこの軸の結論である
    #   (70 の教訓「クラスの数を数える検証では見つからない」の、ちょうど裏返し)。
    ("描き直しのたびに演出を出す", JS,
     "    renderPendingChoice(view);\n    renderPlayingCard(view);",
     "    renderPendingChoice(view);\n    renderPlayingCard(view);\n    fxSpawn();",
     "verify", None, "演出を起こす口は1つだけである"),

    # ===============================================================
    # VII. 足した音(軸19)★62-3 の番人が<b>足した瞬間から見張っているか</b>
    # ===============================================================

    # 軸19: ★★★新しく足した flip の音を手動モードとずらす
    #   ★<b>80 は通常モードに flip の音を1つ足した</b>(裁定357 でマナの裏返りを採るため)。
    #   ★★<b>手動モードと同じファイル・同じ gain で書いた</b>ので、
    #     62-3 の番人(両モードに共通する音は同じである)が<b>足した瞬間から見張る</b> ——
    #     <b>それが本当かを確かめるのがこの軸である</b>(77 の教訓: 添えておいたは読まれているではない)。
    ("足した flip の音を手動モードとずらす", JS,
     "    flip: { files: ['card-shove-1.mp3'], gain: 0.45 },",
     "    flip: { files: ['card-shove-1.mp3'], gain: 0.90 },",
     "verify", None, "両モードに共通する音は同じファイル・同じ gain である"),
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
    # ★★書き戻せたことを読み返して確かめる(62 の教訓)——
    #   「やった」と「戻した」は別の主張である。
    if read(path) != text:
        raise RuntimeError("書き戻しに失敗した: %s" % path)


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


# ---- JUnit(版数と時間の番人) ----

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
