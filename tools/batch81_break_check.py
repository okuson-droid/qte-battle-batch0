#!/usr/bin/env python3
"""Batch 81(一時公開ゾーンを画面に出す = 候補 W)の壊し検証(裁定116)。

実装をわざと壊し、狙った番人が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った番人が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(番人が足りない)
  EMPTY    … その番人が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★★★<b>このバッチは 80 と違い、サーバ(Java)にも触っている</b> ——
  {@code PlayerState} / {@code GameActions} / {@code GameViewBuilder} /
  {@code PlayerView} / {@code CardEffectRegistry}、そして
  {@code battle.js} / {@code battle.css} / テンプレート5枚の版数 / {@code verify/verify.js}。
  ★<b>manual-battle.js とカード定義(manual-cards.json)は1文字も触っていない</b>。

★★★<b>出口ごと・入口ごと・席ごとに当てる</b>(71・75〜80 の教訓):
  - <b>公開範囲</b>は「配信のフィルタ」と「入口2つ(公開・非公開)」で3軸 ——
    <b>フィルタを外す</b>のと<b>旗を逆に立てる</b>のは別の壊れ方である。
  - <b>ログ</b>は入口が<b>3つ</b>ある({@code revealFromTopOfDeck} の呼び出し)。
    ★<b>3つ目(《光霊・ネフラ》)の番人は、この壊し検証が要求して初めて生まれた</b> ——
    軸を入口ごとに立てたら、当てる先が無かった(77 の教訓の実演)。
  - <b>描画</b>は「呼ばない」「片方の席しか見ない」「見出しを書き分けない」で3軸。
  - <b>アンカー</b>は「登録しない」と「外さない」で2軸 ——
    ★<b>外さない側は 81 が新しく作った危険である</b>(78 の教訓:
    直したあとに何が新しく起きるかを1つは考えること)。
  - <b>層</b>は「操作を吸う」「fx より上に出る」「プレイ中のカードと重なる」で3軸。

★★★<b>独立した項目を先頭へ、遷移を起こしうる項目を末尾へ</b>(72・75 の教訓)。
  ★<b>JUnit の9軸を先頭に置いてある</b> —— 1軸あたり 30〜60秒で終わり、他と干渉しない。
  ★★<b>音の遅らせを壊す軸(21)は末尾</b> —— あれは verify の 62 の節を
    まるごと遅くするので、他の軸と混ぜると読み違えやすい。

★★★<b>NG が出たら、まず実装ではなく番人を疑うこと</b>(75・80 の教訓)。
  ★★<b>EMPTY も同じである</b> —— 壊し方が悪いと検証スクリプトごと死ぬ(80 の軸9)。

★★★<b>結果: 21軸すべて OK。</b>ただし<b>2軸は最初そうではなかった</b>(設計解説 6-1):
  - <b>軸9 は当てる先が無かった</b>({@code revealFromTopOfDeck} の3つ目の入口)。
    → <b>番人を1本足した</b>(《光霊・ネフラ》のログ)。
  - <b>軸16 は 81 の作業中に実装の穴として先に出た</b> ——
    アンカーを外す段が無く、<b>表から切り離された DOM が残っていた</b>。
    → 番人(80 の {@code live})が赤くなる前に、<b>設計の側で気づいて外す段を作った</b>。

使い方: python3 tools/batch81_break_check.py [ケース番号...]
★★<b>verify の軸は1軸あたり1回転ぶん掛かる。分けて回し、
  前後で `git diff --stat` を突き合わせること</b>(70 で殺されて壊したまま残った・77・79 でも殺された)。
  例: `1 2 3 4 5` / `6 7 8 9` / `10 11 12 13` / `14 15 16 17` / `18 19 20 21`
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
VIEWS = "src/main/java/com/example/qte/game/view/GameViewBuilder.java"
ACTIONS = "src/main/java/com/example/qte/game/GameActions.java"
EFFECTS = "src/main/java/com/example/qte/effect/CardEffectRegistry.java"
VERIFY = "verify/verify.js"

# ★壊しても落ちないことが分かっているもの(理由つき)。
#   ★★<b>「落ちない」ことも書き残す</b> —— 次の人が「番人が足りない」と読まないために。
#   ★★★<b>Batch 81 には1件も無い。</b>21軸すべてが落ちるはずである。
EXPECTED_NG = {}

# ★★★<b>壊しどころが無いもの</b>(裁定196 の正直な扱い)——
#   軸に入れていない理由を書き残す。
#
#   1) <b>解決の途中で配信する口。</b>
#      81 は作っていない(設計解説 0-4・c2)—— 通常モードは
#      {@code action.apply} → {@code broadcast} の1往復しか持たない。
#      <b>作っていないものは壊せない。</b>
#
#   2) <b>「公開しました」の確認を挟む段。</b>
#      裁定113 に触れるので作らなかった(0-7・c1)。同上。
#
#   3) <b>一時公開ゾーンの「窓」。</b>
#      このゾーンは<b>枚数の欄をビューに持たない</b>ので、
#      届いた列の長さがそのまま枚数である —— <b>「一部だけ届く」が構造的に起きない</b>。
#      ★軸11 は<b>その前提を壊す</b>形で当てているが、
#        <b>本物の配信でこの形が来ることは無い</b>。
#
#   4) <b>{@code manual-battle.js}・カード定義。</b>
#      81 は1文字も触っていない。★手動モードに一時公開ゾーンは存在しない。
#
#   5) <b>《愚乱怒土地》の「マナが上限なら山札の上へ戻す」。</b>
#      81 は取り出す口を1本にしただけで、行き先の規則には触れていない
#      (番人は Batch 54 から {@code SoulSpellTest} に在る)。

# (説明, ファイル, 置換前, 置換後, 走らせ方, JUnitのクラス, 照合先の名前の一部)
CASES = [
    # ===============================================================
    # I. 版数(軸1〜2)★JUnit。1回で終わり、他と一切干渉しない
    # ===============================================================

    # 軸1: ★版数を据え置いてしまう(JS)
    #   ★<b>上げないと、既に開いている人の画面には束が1枚も出ない</b> ——
    #     サーバは公開の別つきで送るのに、読む側が 41 のままその欄を見ない。
    #     ★★<b>80 までと同じ症状(何も出ない)になるので、直したことに誰も気づけない</b>。
    ("battle.js の版数を 81 で上げ忘れる", HTML,
     "/js/battle.js(v=42)", "/js/battle.js(v=41)",
     "junit", "BattlePageTest", "通常モードの盤面のJSの版数が81で上がっている"),

    # 軸2: ★版数を1枚だけ上げ忘れる(CSS)★<b>5枚のうち1枚である</b>(7-5)
    #   ★<b>1枚だけ古いと、そのページから来た人だけ束が左上に貼りつく</b>
    #     (位置も寸法も CSS の側が決めている)。
    ("battle.css の版数を battle.html だけ上げ忘れる", HTML,
     "/css/battle.css(v=57)", "/css/battle.css(v=56)",
     "junit", "Batch70PlayingCardTest", "battleCssを読む5枚のテンプレートは同じ版数である"),

    # ===============================================================
    # II. 公開範囲(軸3〜6)★★★<b>81 の中心である</b>
    #     ★<b>フィルタを外す</b>のと<b>旗を逆に立てる</b>のは別の壊れ方である
    # ===============================================================

    # 軸3: ★★★配信のフィルタを 80 の状態へ戻す(isSelf を通さない)
    #   ★<b>これが 80 まで実際に在った姿である</b> ——
    #     《愚乱怒土地》の「相手に見せず」見た2枚が、相手にも観戦者にも届いていた。
    #   ★★<b>既存の954件は1件も赤くならなかった</b>(誰も測っていなかった)——
    #     67・70・80 と同じ一族である。
    ("配信が公開範囲を絞らない(80 の姿へ戻す)", VIEWS,
     "        if (!isSelf && !player.isRevealedPublic()) {\n            return List.of();\n        }\n",
     "",
     "junit", "Batch81RevealedZoneTest", "見た束は本人にしか届かない"),

    # 軸4: ★★《愚乱怒土地》の入口で旗を<b>逆に</b>立てる(非公開 → 公開)
    #   ★<b>フィルタが生きていても、旗が間違っていれば漏れる</b> ——
    #     入口ごとに当てないと、この形は見つからない(76・77 の教訓)。
    ("《愚乱怒土地》の見た2枚を公開扱いにする", EFFECTS,
     "            ctx.actions().placeInRevealedZone(ctx.owner(), revealed, false);",
     "            ctx.actions().placeInRevealedZone(ctx.owner(), revealed, true);",
     "junit", "Batch81RevealedZoneTest", "見た束は本人にしか届かない"),

    # 軸5: ★★《降臨の伝道師》の入口で旗を<b>逆に</b>立てる(公開 → 非公開)
    #   ★★<b>逆向きも当てる</b>(裁定181: そうでない側も測る)——
    #     「漏れない」だけを測る番人は、<b>全部を隠す実装</b>でも緑になる。
    ("《降臨の伝道師》の公開4枚を非公開扱いにする", EFFECTS,
     "            ctx.actions().placeInRevealedZone(ctx.owner(), revealed, true);",
     "            ctx.actions().placeInRevealedZone(ctx.owner(), revealed, false);",
     "junit", "Batch81RevealedZoneTest", "公開した束は相手にも観戦者にも届く"),

    # 軸6: ★★取り出す口が旗を降ろさない
    #   ★<b>束は空なのに「公開中」の旗が立ったまま残る</b> ——
    #     次に非公開の束を置いた入口が旗を立て直すまで、<b>前の公開が効き続ける</b>。
    ("束を取り出しても公開の旗を降ろさない", ACTIONS,
     "        player.getRevealedZone().clear();\n        player.setRevealedPublic(false);\n        return taken;",
     "        player.getRevealedZone().clear();\n        return taken;",
     "junit", "Batch81RevealedZoneTest", "束を取り出すと公開の旗も降りる"),

    # ===============================================================
    # III. ログ(軸7〜9)★★★<b>入口は3つある</b>(裁定360)
    #     ★配信を跨げない場面を埋めているのはここだけである(設計解説 0-4)
    # ===============================================================

    # 軸7: ★★公開のログから名前を落とす
    ("公開のログにカード名を並べない", ACTIONS,
     '        StringBuilder sb = new StringBuilder(": ");',
     '        StringBuilder sb = new StringBuilder("");\n        if (true) return "";',
     "junit", "Batch81RevealedZoneTest", "公開のログにはカード名が並ぶ"),

    # 軸8: ★★★非公開でも名前を並べてしまう(ログからの漏れ)
    #   ★<b>ビューを絞ってもログが漏らせば同じことである</b> ——
    #     ログは相手も観戦者も読む。<b>公開範囲の出口は2つある</b>。
    ("「見た」ときのログにも名前を並べる", ACTIONS,
     '            room.addLog("%sが山札の上から%d枚を見ました".formatted(player.getDisplayName(), revealed.size()));',
     '            room.addLog("%sが山札の上から%d枚を見ました%s".formatted(player.getDisplayName(), revealed.size(), revealedNameList(revealed)));',
     "junit", "Batch81RevealedZoneTest", "見たときのログには名前を並べない"),

    # 軸9: ★★★3つ目の入口(《光霊・ネフラ》)を非公開扱いにする
    #   ★★<b>この軸が番人を1本増やさせた。</b>軸を入口ごとに立てたら、
    #     3つ目に当てる先が無かった —— <b>規則が n 入口ぶんあるなら番人も n 入口ぶん要る</b>
    #     (77・79・80 の教訓の、81 での実演)。
    ("《光霊・ネフラ》の「表向きにする」を非公開扱いにする", EFFECTS,
     "            List<String> revealed = ctx.actions().revealFromTopOfDeck(ctx.room(), ctx.owner(), 3, true);",
     "            List<String> revealed = ctx.actions().revealFromTopOfDeck(ctx.room(), ctx.owner(), 3, false);",
     "junit", "Batch81RevealedZoneTest", "光霊ネフラの表向きも公開でありログに名前が並ぶ"),

    # ===============================================================
    # IV. 差分の語彙(軸10〜11)★verify
    # ===============================================================

    # 軸10: ★一時公開ゾーンを差分から外す
    #   ★<b>描かれてはいるが、動きが1つも語られない</b>状態になる。
    ("一時公開ゾーンの出入りを差分から採らない", JS,
     "    fxZoneDelta(out, seat, 'REVEALED', before.revealedCards, fxRevealedCount(before),\n"
     "        after.revealedCards, fxRevealedCount(after), faceDataFromRevealed);",
     "",
     "verify", None, "1枚だけ公開したときは「山札 → 一時公開」の移動になる"),

    # 軸11: ★★一時公開ゾーンを「窓」にしてしまう(枚数 > 配列の長さ)
    #   ★<b>窓では出現も消滅も出さない</b>(裁定356)ので、公開の束が黙る。
    #   ★★<b>本物の配信でこの形は来ない</b> —— このゾーンは枚数の欄を持たないからである。
    #     それでも当てるのは、<b>「窓の見分け」がこのゾーンにも掛かっている</b>ことを示すためである。
    ("一時公開ゾーンを「窓」扱いにする", JS,
     "    return (side && side.revealedCards) ? side.revealedCards.length : 0;",
     "    return (side && side.revealedCards) ? side.revealedCards.length + 1 : 0;",
     "verify", None, "4枚まとめて公開したときは結ばず、一時公開に4件の「出現」を出す"),

    # ===============================================================
    # V. 描画(軸12〜14)★★出口ごと・席ごとに当てる
    # ===============================================================

    # 軸12: ★★描画そのものを呼ばない(80 までの姿へ戻す)
    ("renderRevealed を render から呼ばない", JS,
     "    renderRevealed(view);\n",
     "",
     "verify", None, "公開中の束は cardFace で描かれる"),

    # 軸13: ★★★相手席を見ない(席ごとに当てる・77・79・80 の教訓)
    #   ★<b>自分の束だけ描く実装でも、自席の番人は緑のままである</b>。
    ("相手席の束を描かない", JS,
     "    for (const seat of ['you', 'opponent']) {\n        const side = view[seat];",
     "    for (const seat of ['you']) {\n        const side = view[seat];",
     "verify", None, "両席ぶんの束を並べ、アンカーも席ごとに置く"),

    # 軸14: ★★見出しの書き分けをやめる
    #   ★<b>《愚乱怒土地》の「相手に見せず」が守られていることを、人が確かめられなくなる</b>。
    ("見出しが公開と非公開を書き分けない", JS,
     "    return g.open ? '一時公開(相手にも見えている)' : '一時公開(あなただけが見ている)';",
     "    return '一時公開';",
     "verify", None, "見出しは「公開」「あなただけ」「相手が公開中」を書き分ける"),

    # ===============================================================
    # VI. アンカー(軸15〜17)★★<b>登録・解除・印</b>で3軸
    # ===============================================================

    # 軸15: ★アンカーを登録しない(演出の着地点が無くなる)
    ("一時公開ゾーンのアンカーを登録しない", JS,
     "        registerAutoAnchor(group, g.seat, 'REVEALED');",
     "",
     "verify", None, "束が出ている間はアンカーが登録され"),

    # 軸16: ★★★アンカーを外さない(★81 が新しく作った危険・78 の教訓)
    #   ★<b>表から切り離された DOM がアンカーに残る</b> ——
    #     80 の {@code live}(すべてのアンカーが画面上の要素であること)が偽になる。
    #   ★★<b>これは 81 の作業中に、番人が赤くなる前に設計の側で見つけた</b> ——
    #     「描く」と「登録する」を対にしたなら、「捨てる」と「外す」も対にする。
    ("束が消えてもアンカーを外さない", JS,
     "    for (const seat of ['you', 'opponent']) clearAutoAnchor(seat, 'REVEALED');",
     "",
     "verify", None, "束が消えた配信でアンカーが外れ、死んだ参照が残らない"),

    # 軸17: ★【守護】の印を付けない
    #   ★<b>《降臨の伝道師》で「どれが選べるのか」が面の側から読めなくなる</b>。
    ("【守護】の印を面に付けない", JS,
     "            face.classList.toggle('auto-revealed-guard', !!card.guard);",
     "",
     "verify", None, "【守護】の面には印が付く"),

    # ===============================================================
    # VII. 層(軸18〜20)★★★<b>操作・演出・隣の面</b>の3方向に当てる
    # ===============================================================

    # 軸18: ★★帯が操作を吸う(71 の判断: 覆いは守りではない)
    ("一時公開の帯が操作を吸う", CSS,
     "    pointer-events: none; text-align: right;",
     "    pointer-events: auto; text-align: right;",
     "verify", None, "帯は操作を吸わない"),

    # 軸19: ★★帯が fx層より上に出る
    #   ★<b>飛んできたゴーストの最後の数フレームが帯の裏へ隠れる</b> ——
    #     ここは<b>演出の着地点</b>なので、上に出てはいけない。
    ("一時公開の帯を fx層より上に出す", CSS,
     "    position: fixed; right: 300px; top: 412px; z-index: 1010;",
     "    position: fixed; right: 300px; top: 412px; z-index: 1030;",
     "verify", None, "帯は演出の層(fx)より下にある"),

    # 軸20: ★★★帯をプレイ中のカードに重ねる(実測の番人・設計判断41)
    #   ★<b>412px は .auto-zoom-card の 232px から生えた、2箇所に書いた定数である</b> ——
    #     だから値ではなく<b>「重なっていないこと」</b>を測っている。
    ("一時公開の帯をプレイ中のカードに重ねる", CSS,
     "    position: fixed; right: 300px; top: 412px; z-index: 1010;",
     "    position: fixed; right: 300px; top: 244px; z-index: 1010;",
     "verify", None, "一時公開の帯はプレイ中のカードと重ならず"),

    # ===============================================================
    # VIII. 音の待ち(軸21)★★★<b>81 が直した番人そのもの</b>
    #     ★遅らせが効かなくなった日に、待ちが「ただ通る」のを防ぐ(裁定186)
    # ===============================================================

    # 軸21: ★★★わざとの遅れを外す(設計判断61)
    #   ★<b>遅らせが無い待ちは、次の人に固定時間へ戻されても
    #     「赤」ではなく「揺れ」にしかならない</b>(79 の教訓)。
    ("音のファイルのわざとの遅れを外す", VERIFY,
     "  SOUND_RESPONSE.delayMs = 600;\n  await bsnd.goto(",
     "  SOUND_RESPONSE.delayMs = 0;\n  await bsnd.goto(",
     "verify", None, "遅らせが効いている"),
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


# ---- JUnit ----

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
    return 0


if __name__ == "__main__":
    sys.exit(main())
