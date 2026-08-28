#!/usr/bin/env python3
"""Batch 76(「自動で選ぶ」をやめる + 使用条件を運ぶ + 裏向きマナを読む)の壊し検証(裁定116)。

実装をわざと壊し、狙った番人が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った番人が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(番人が足りない)
  EMPTY    … その番人が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★★★<b>照合先は2つある</b>(設計判断45・72・74・75 と同じ「両方あるバッチ」)。
  - <b>サーバの状態</b>(問い合わせが立つか / 立たないか・答えた結果・順序・使用条件)
    …… {@code Batch76ChoiceTest}(+ 版数は {@code BattlePageTest})。
    verify のハーネスは Java を起こさないので<b>あちらには1件も届かない</b>。
  - <b>受け取った側の描き方</b>(条件未達の印・掴めないこと・裏向きマナの名前とホバー)
    …… verify。JUnit からは1件も見えない。

★★<b>出口ごとに当てている</b>(71・75 の教訓)。
  - 《マナを貪る怨霊》は「問う / 問わない」の2つ ………………………… 軸1・2
  - 《禁忌の代償》は「問う / 問わない」と「破壊 / 出す」の順 ……… 軸5〜8
  - 《光霊・モアニール》は「2体で問う / 1体で問わない / 居ない」…… 軸9〜12
  - {@code dropZonesFor} は手札・禁忌・賢魂の3経路 …………………… 軸22〜24
  - マナタイルは「名前」と「ホバー」で、どちらも自席と相手席の2つ … 軸25〜29

★★★<b>独立した項目を先頭へ、遷移を起こしうる項目を末尾へ</b>(72・75 の教訓)。
  verify を回す軸(20〜30)は末尾に置いてある。

使い方: python3 tools/batch76_break_check.py [ケース番号...]
★★<b>長いので4つに分けて回し、前後で `git diff --stat` を突き合わせること</b>(70 の教訓)。
  例: `1..8` / `9..19` / `20..25` / `26..32`
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
M2 = "/root/m2work/repository"

REGISTRY = "src/main/java/com/example/qte/effect/CardEffectRegistry.java"
GUARDS = "src/main/java/com/example/qte/effect/RuleGuards.java"
ACTIONS = "src/main/java/com/example/qte/game/GameActions.java"
SERVICE = "src/main/java/com/example/qte/game/GameService.java"
VIEWS = "src/main/java/com/example/qte/game/view/GameViewBuilder.java"
BATTLE_JS = "src/main/resources/static/js/battle.js"
BATTLE_CSS = "src/main/resources/static/css/battle.css"
BATTLE_HTML = "src/main/resources/templates/battle.html"

CHOICE_TEST = "Batch76ChoiceTest"
PAGE_TEST = "BattlePageTest"

# 壊しても落ちないことが分かっているもの(理由つき)。★76 は1件も無い。
EXPECTED_NG = {}

# ★★★<b>壊しどころが無いもの</b>(裁定196 の正直な扱い)——
#   軸に入れていない理由を書き残す。
#
#   1) 撤去した器4つ(turnManaFaceDown / turnManaFaceUp / returnFaceUpManaToHand /
#      destroyFaceDownMana)と、消した関数1つ(battle.js の showManaList)。
#      <b>消したものは壊せない。</b>「呼び手が居ないこと」を測る番人も置いていない ——
#      呼び手が生えたらコンパイルが通るだけで、それは誰かが使い道を見つけたということである。
#      ★代わりに「消した名前」を設計解説と裁定196 の記録に書き残した。
#
#   2) 賢魂として使う道に使用条件が掛からないこと(サーバ側)。
#      <b>使用条件を持つ9枚に、賢魂を持つカードが1枚も無い。</b>
#      本物の入口が存在しないので JUnit では観測できない(74 の《聖光の守護聖》と同じ形)。
#      ★<b>クライアント側は観測できる</b>ので、軸24 がそちらから見張っている。
#
#   3) 《ホーリー・シグナル》の「同じ1体が両方の条件を満たしたら1回だけ壊す」。
#      重複除去を外しても、2回目の {@code destroyChosenMinion} は
#      <b>instanceId で引き直して居ないので何もしない</b> —— 盤面に差が出ない。
#
#   4) 猶予・掃除・部屋消失(Batch 75 のもの)。76 は1文字も触っていない。

# (説明, ファイル, 置換前, 置換後, 種別, クラス, 照合先の名前の一部)
CASES = [
    # ===============================================================
    # I. 《マナを貪る怨霊》—— 裁定346(軸1〜4)
    # ===============================================================

    # 軸1: ★★★問わずに「古い順」で置く(59 の姿へ戻す)
    ("怨霊が問わずに墓地の古い順で2枚置く(59 の姿へ戻す)", REGISTRY,
     "            ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.of(\n"
     "                    PendingChoice.Kind.TRASH, positions, count, count,\n"
     "                    ResumePoint.MANA_WRAITH_TRASH_TO_MANA,",
     "            resolveManaWraithPut(ctx, positions.subList(0, count));\n"
     "            if (false) ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.of(\n"
     "                    PendingChoice.Kind.TRASH, positions, count, count,\n"
     "                    ResumePoint.MANA_WRAITH_TRASH_TO_MANA,",
     "junit", CHOICE_TEST, "怨霊は墓地の闇が3枚以上なら問い合わせる"),

    # 軸2: ★選ぶ余地が無いのに問う(もう1つの出口)
    ("怨霊が候補2枚でも問い合わせる(選ぶ余地が無いのに問う)", REGISTRY,
     "            if (positions.size() <= count) {",
     "            if (false) {",
     "junit", CHOICE_TEST, "怨霊は墓地の闇が2枚なら問わない"),

    # 軸3: ★候補の文明の絞り込みを外す
    ("怨霊の候補に闇文明以外も混ざる", REGISTRY,
     "            if (cards.findById(trash.get(i)).civilization() == civilization) {",
     "            if (true) {",
     "junit", CHOICE_TEST, "怨霊は墓地の闇が2枚なら問わない"),

    # 軸4: ★引く枚数が「置いた枚数」でなくなる(裁定273(a))
    ("怨霊が置いた枚数と関係なく1枚しか引かない", REGISTRY,
     '        ctx.room().addLog("【マナを貪る怨霊】: 墓地から%d枚を裏向きでマナに置きました".formatted(placed));\n'
     "        ctx.actions().drawCards(ctx.room(), ctx.owner(), placed);",
     '        ctx.room().addLog("【マナを貪る怨霊】: 墓地から%d枚を裏向きでマナに置きました".formatted(placed));\n'
     "        ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);",
     "junit", CHOICE_TEST, "怨霊は選んだ2枚を置いて2枚引く"),

    # ===============================================================
    # II. 《禁忌の代償》—— 裁定347(軸5〜8)
    # ===============================================================

    # 軸5: ★★★問わずに「末尾から」壊す(75 の姿へ戻す)
    ("代償が問わずに末尾の裏向きマナを壊す(75 の姿へ戻す)", REGISTRY,
     "            ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.one(\n"
     "                    PendingChoice.Kind.MANA, positions,\n"
     "                    ResumePoint.TABOO_PRICE_MANA_DESTROY,",
     "            resolveTabooPriceDestroy(ctx,\n"
     "                    Integer.parseInt(positions.get(positions.size() - 1)), putTargets);\n"
     "            if (false) ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.one(\n"
     "                    PendingChoice.Kind.MANA, positions,\n"
     "                    ResumePoint.TABOO_PRICE_MANA_DESTROY,",
     "junit", CHOICE_TEST, "代償は裏向きマナが2枚以上なら問い合わせる"),

    # 軸6: ★候補に表向きのマナが混ざる
    ("代償の候補に表向きのマナも混ざる", REGISTRY,
     "            if (!owner.getManaZone().get(i).isFaceUp()) {",
     "            if (true) {",
     "junit", CHOICE_TEST, "代償は裏向きマナが2枚以上なら問い合わせる"),

    # 軸7: ★★★本文の「その後」が逆転する(問い合わせは後回しである)
    ("代償が答えを待たずに先に蘇生する(破壊とその後が逆転する)", REGISTRY,
     '                    .withPayload(String.join(",", putTargets)));\n'
     "        });",
     '                    .withPayload(String.join(",", putTargets)));\n'
     '            effectPutSequence(ctx, EffectPutState.of(EffectPutSource.TRASH, "QTE-M-DARK-10",\n'
     "                    putTargets, false));\n"
     "        });",
     "junit", CHOICE_TEST, "代償は答える前に蘇生しない"),

    # 軸8: ★破壊せずに蘇生だけする
    ("代償が裏向きマナを壊さない", REGISTRY,
     "            ctx.actions().destroyManaAt(ctx.room(), ctx.owner(), manaIndex);",
     "            if (false) ctx.actions().destroyManaAt(ctx.room(), ctx.owner(), manaIndex);",
     "junit", CHOICE_TEST, "代償は選んだ裏向きマナを壊してから蘇生する"),

    # ===============================================================
    # III. 《光霊・モアニール》—— 裁定348(軸9〜12)
    # ===============================================================

    # 軸9: ★★★2体でも問わずに先頭を壊す(75 の姿へ戻す)
    ("モアニールが2体でも問わずに先頭を壊す(75 の姿へ戻す)", ACTIONS,
     "        if (interceptors.size() == 1) {\n"
     "            destroyMinion(room, target, interceptors.get(0));\n"
     "            return true;\n"
     "        }",
     "        if (interceptors.size() >= 1) {\n"
     "            destroyMinion(room, target, interceptors.get(0));\n"
     "            return true;\n"
     "        }",
     "junit", CHOICE_TEST, "モアニールは2体並んでいたら問い合わせる"),

    # 軸10: ★1体しか居なくても問う(もう1つの出口)
    ("モアニールが1体しか居なくても問い合わせる", ACTIONS,
     "        if (interceptors.size() == 1) {\n"
     "            destroyMinion(room, target, interceptors.get(0));\n"
     "            return true;\n"
     "        }",
     "        if (false) {\n"
     "            destroyMinion(room, target, interceptors.get(0));\n"
     "            return true;\n"
     "        }",
     "junit", CHOICE_TEST, "モアニールは1体だけなら問わない"),

    # 軸11: ★★★居ないときのフォールバックを外す(肩代わりがただになる)
    ("選んだモアニールが居ないと何も壊れない(肩代わりがただになる)", ACTIONS,
     "                .orElseGet(() -> candidates.isEmpty() ? null : candidates.get(0));",
     "                .orElse(null);",
     "junit", CHOICE_TEST, "モアニールは選んだ個体が居なければ残りを壊す"),

    # 軸12: ★判定層が候補を1体しか返さない(findFirst の姿へ戻す)
    ("肩代わりの候補が1体しか返らない(判定層を 75 の姿へ戻す)", GUARDS,
     "        return target.getMinionZone().stream()\n"
     "                .filter(m -> MOANIRU.equals(m.getMaster().id()))\n"
     "                .toList();",
     "        return target.getMinionZone().stream()\n"
     "                .filter(m -> MOANIRU.equals(m.getMaster().id()))\n"
     "                .limit(1).toList();",
     "junit", CHOICE_TEST, "モアニールは2体並んでいたら問い合わせる"),

    # ===============================================================
    # IV. 《ホーリー・シグナル》—— 裁定349(軸13〜15)
    # ===============================================================

    # 軸13: ★★★同値でも問わずに先頭を壊す(56 の姿へ戻す)
    ("シグナルが同値でも問わずに先頭を壊す(56 の姿へ戻す)", REGISTRY,
     "            if (lowest.size() <= 1) {",
     "            if (true) {",
     "junit", CHOICE_TEST, "シグナルは最低体力が同値なら問い合わせる"),

    # 軸14: ★最高攻撃力側を運ぶ payload を落とす
    ("シグナルが最高攻撃力側を再開先へ運ばない", REGISTRY,
     "                    .withPayload(highest));",
     '                    .withPayload(""));',
     "junit", CHOICE_TEST, "シグナルは選んだ1体と最高攻撃力を壊す"),

    # 軸15: ★同値の全員ではなく1体しか返さない
    ("最低体力の候補が1体しか返らない(min の1件だけを返す)", REGISTRY,
     "                .map(min -> candidates.stream()\n"
     "                        .filter(m -> m.getCurrentHp() == min.getCurrentHp())\n"
     "                        .toList())",
     "                .map(List::of)",
     "junit", CHOICE_TEST, "シグナルは最低体力が同値なら問い合わせる"),

    # ===============================================================
    # V. 禁忌デッキの使用条件と、ビューが運ぶ真偽値(軸16〜19)
    # ===============================================================

    # 軸16: ★★★禁忌経路が使用条件を見ない(75 の姿へ戻す)
    ("禁忌デッキから使うと使用条件を見ない(75 の姿へ戻す)", SERVICE,
     "        effects.requirePlayable(master.id(), state, player);\n\n"
     "        // ★進化は素材を必ず1体消費するので、場が満杯でも枠は空く",
     "        // ★進化は素材を必ず1体消費するので、場が満杯でも枠は空く",
     "junit", CHOICE_TEST, "禁忌の静寂の瞑想は1枚目でなければ使えない"),

    # 軸17: ★検証そのものが素通りする(裁定130 の1本を空にする)
    ("使用条件の検証が素通りする", REGISTRY,
     "        if (!playConditionMet(cardId, state, player)) {",
     "        if (false) {",
     "junit", CHOICE_TEST, "禁忌の静寂の瞑想は1枚目でなければ使えない"),

    # 軸18: ★★ビューが常に「使える」と送る
    ("ビューが使用条件を常に true で送る", VIEWS,
     "                effects.playConditionMet(master.id(), state, player));",
     "                true);",
     "junit", CHOICE_TEST, "条件を満たさない手札はビューに印が付く"),

    # 軸19: ★★<b>据え置きにも番人を置く</b>(74 の教訓)——
    #   本文に「選び」を足して実装を正当化する、という直し方をしていないこと
    ("《マナを貪る怨霊》の本文に「選び」を足す(裁定346 は本文を変えない)",
     "src/main/resources/cards/manual-cards.json",
     "自分の墓地にある闇文明のカードを2枚裏向きでマナに置く。",
     "自分の墓地にある闇文明のカードを2枚選び、裏向きでマナに置く。",
     "junit", CHOICE_TEST, "怨霊の本文は変えていない"),

    # ===============================================================
    # VI. 受け取った側(verify)★遷移を起こしうるので末尾(軸20〜30)
    # ===============================================================

    # 軸20: ★★★条件未達でも光る
    ("条件を満たしていない手札が光る", BATTLE_JS,
     "&& card.type !== 'LEADER' && conditionMet",
     "&& card.type !== 'LEADER'",
     "verify", None, "使用条件を満たしていない手札は光らない"),

    # 軸21: ★★★理由が読める印が出ない
    ("条件未達の印を出さない", BATTLE_JS,
     "    // ★★★Batch 76(裁定350): 使用条件を満たしていないことを盤面に出す\n"
     "    addConditionBadge(badges, card);\n",
     "",
     "verify", None, "使用条件を満たしていない手札には印が出る"),

    # 軸22: ★★★手札を掴めてしまう(落とせないのに掴める)
    ("条件を満たしていない手札を掴める", BATTLE_JS,
     "            && ((conditionMet && p.affordable) || card.canSpecialSummon)) {",
     "            && (p.affordable || card.canSpecialSummon)) {",
     "verify", None, "条件を満たしていない手札は掴めない"),

    # 軸23: ★★★禁忌の側だけ規則が抜ける(片肺・71 の教訓の形)
    ("禁忌デッキだけ使用条件が掛からない", BATTLE_JS,
     "        if (conditionMet && card.cost <= payable) zones.push(dropZoneOfType(card.type));",
     "        if (card.cost <= payable) zones.push(dropZoneOfType(card.type));",
     "verify", None, "禁忌デッキにも同じ規則が掛かる"),

    # 軸24: ★★★掛かる場所より広く取る(賢魂の道まで塞ぐ。72 の教訓・幅)
    ("賢魂として使う道にも使用条件を掛ける(塞ぎすぎ)", BATTLE_JS,
     "    if (p.soulAffordable && (view.phase === 'MAIN' || view.phase === 'SUB')) zones.push('SPELL');",
     "    if (conditionMet && p.soulAffordable\n"
     "        && (view.phase === 'MAIN' || view.phase === 'SUB')) zones.push('SPELL');",
     "verify", None, "賢魂として使う道は使用条件を通らない"),

    # 軸25: ★★★裏向きマナに名前を重ねない
    ("裏向きマナに名前を重ねない", BATTLE_JS,
     "            const backName = document.createElement('div');\n"
     "            backName.className = 'mana-tile-name mana-tile-back-name';\n"
     "            backName.textContent = mana.name;\n"
     "            tile.appendChild(backName);\n",
     "",
     "verify", None, "持ち主の裏向きマナには、裏面の上に名前が出る"),

    # 軸26: ★同じ改変を、44 の番人の側から観測する
    #   ★★<b>照合先を変えて同じ場所を壊す</b>(75 の軸13 と同じ形)——
    #     44 の項目は 76 で「名前を出さない」から「名前を出す」へ裏返した。
    ("裏向きマナに名前を重ねない(44 の番人の側から見る)", BATTLE_JS,
     "            const backName = document.createElement('div');\n"
     "            backName.className = 'mana-tile-name mana-tile-back-name';\n"
     "            backName.textContent = mana.name;\n"
     "            tile.appendChild(backName);\n",
     "",
     "verify", None, "裏向きも持ち主には名前が出る"),

    # 軸27: ★★★相手の裏向きマナにも名前の器を作る(見せてはいけないものを見せる)
    ("相手の裏向きマナにも名前の器を作る", BATTLE_JS,
     "        if (mana.name) {",
     "        if (true) {",
     "verify", None, "相手の裏向きマナには名前が出ない"),

    # 軸28: ★★★マナにホバープレビューを付けない(69 の「途中」の姿へ戻す)
    ("マナタイルにホバープレビューを付けない", BATTLE_JS,
     "    if (mana.cardId) attachHover(tile, () => faceDataFromMana(mana));",
     "",
     "verify", None, "マナタイルにホバープレビューが付く"),

    # 軸29: ★★★中身が届いていないマナにもホバーを付ける(空の面が出る)
    ("相手の裏向きマナにもホバーを付ける", BATTLE_JS,
     "    if (mana.cardId) attachHover(tile, () => faceDataFromMana(mana));",
     "    attachHover(tile, () => faceDataFromMana(mana));",
     "verify", None, "マナタイルにホバープレビューが付く"),

    # 軸30: ★★名前が裏面画像の下に沈む(重なりの順は CSS が持つ)
    ("裏向きマナの名前から重なりの順を外す", BATTLE_CSS,
     "    position: relative; z-index: 1; width: 100%;",
     "    width: 100%;",
     "verify", None, "持ち主の裏向きマナには、裏面の上に名前が出る"),

    # ===============================================================
    # VII. 静的ファイルの版数(7-5)。★末尾に置く(軸31〜32)
    # ===============================================================

    # 軸31: ★★JS の版数を 37 へ戻す
    ("battle.js の版数を 37 へ戻す", BATTLE_HTML,
     "battle.js(v=38)", "battle.js(v=37)",
     "junit", PAGE_TEST, "JSの版数が76で上がっている"),

    # 軸32: ★★CSS の版数を 53 へ戻す(2バッチぶりに触ったので上げている)
    ("battle.css の版数を 53 へ戻す", BATTLE_HTML,
     "battle.css(v=54)", "battle.css(v=53)",
     "junit", PAGE_TEST, "CSSの版数が76で上がっている"),
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
