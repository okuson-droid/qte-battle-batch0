#!/usr/bin/env python3
"""Batch 68(【召喚時】の対象は場に出てから・手札からの召喚時)の壊し検証(裁定116)。

実装をわざと壊し、狙った番人が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った番人が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(番人が足りない)
  EMPTY    … その番人が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★★<b>68 の照合先は2層である。</b>JUnit(サーバ側の挙動と構造)と
  verify(割り込みの描画)である。
  ★<b>67 にあった「tools が3つ目の照合先になる」形は、68 には無い</b> ——
  このバッチは機械の番人を1本も新設していないからである(裁定196: 無いものは無いと書く)。
  照合先は「そのバッチが触った層に居る」という決め方は 63〜67 から変えていない。

★★改変は「軸」ごとに1件ずつ当てる(57 の教訓)。軸は次の11である。
  (1) 宣言時の対象をミニオンから外したこと   (2) 場に出てから対象を問うこと
  (3) 誘発の種別で絞ること                   (4) 手札から出したら【召喚時】(裁定311)
  (5) スタンディングテントの例外              (6) 素材を確保できる進化だけを候補に(裁定308)
  (7) 進化を候補に戻したこと(裁定308)        (8) ガイル・フォックスの数え方
  (9) 選ぶ余地が無いときだけ自動で決めること   (10) 裁定306・307・309 の小さい3件
  (11) 割り込みの描画が Kind を数えないこと(verify 層)

★★<b>裁定304 の罠</b>: Java で「条件を落とす」改変をすると、その先が到達不能になって
  <b>コンパイルが通らず</b> EMPTY になる(64 で踏んだ)。
  68 の改変も<b>文を消して後続を宙に浮かせる形を1つも使っていない</b> ——
  条件を反転させるか、式ごと・値ごと置き換えるかのどちらかである。

★★★<b>壊しどころが無い項目</b>(意図的に含めていないもの・裁定196 の正直な扱い):

  - <b>撤去した門({@code requireTrashSourceNotTargeted})</b> ……
    「消したコード」は壊せない。代わりに<b>同じ性質を候補の側から測る試験</b>
    ({@code Batch60Test#墓地から召喚したカード自身は召喚時の候補に現れない})を置いてある。
    その試験が仕事をしているかは軸2(場に出てから問うこと)を壊すと分かる ——
    対象を宣言時に戻せば、出どころは再び墓地に居るからである。

  - <b>{@code PutFromHandState} の payload の畳み方</b> ……
    区切り文字を変えても、encode と decode の両方が同じ場所にあるので<b>整合したまま動く</b>。
    壊せるのは「片方だけ変える」形だが、それは<b>実装者が絶対にしない壊し方</b>であり、
    壊し検証が想定すべき誤りではない(裁定116 は「ありうる誤り」を試すためのものである)。

  - <b>裁定312(裁定245 と 311 の衝突)の扱い</b> ……
    まだマスターの回答が無い。今の実装は 311 を優先しており、
    それが正しいかを測る番人は<b>作れない</b>(正解が未定である)。
    {@code notes/batch68-ruling-requests.md} に書き残してある。

使い方: python3 tools/batch68_break_check.py [ケース番号...]
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

REGISTRY = "src/main/java/com/example/qte/effect/CardEffectRegistry.java"
ACTIONS = "src/main/java/com/example/qte/game/GameActions.java"
VIEW_BUILDER = "src/main/java/com/example/qte/game/view/GameViewBuilder.java"
BATTLE_JS = "src/main/resources/static/js/battle.js"

M2 = os.environ.get("QTE_M2_REPO", "/root/m2work/repository")

# (説明, ファイル, 置換前, 置換後, kind, 照合先クラス(junit のみ), 照合先の名前の一部)
CASES = [
    # ===============================================================
    # 軸1: ★宣言時の対象をミニオンから外したこと(裁定282 の入口)
    # ===============================================================
    ("ミニオンにも宣言時の対象を返す(66 までの姿)", REGISTRY,
     "        if (cards.findById(cardId).type().isMinion()) {\n"
     "            return TargetSpec.of();\n"
     "        }\n"
     "        return targetSpecOf(cardId);",
     "        return targetSpecOf(cardId);",
     "junit", "Batch68SummonTargetTest", "ミニオンは宣言時の対象要求を1件も持たない"),

    # ===============================================================
    # 軸2: ★場に出てから対象を問うこと(裁定282 の本体)
    #   ★これを壊すと、対象が never 選ばれないまま効果が走る ——
    #     15枚が一斉に落ちるので、狙いを1件に絞って測る
    # ===============================================================
    ("場に出てからの問い合わせをやめる(対象が選ばれない)", REGISTRY,
     "        if (needsTargetChoice(ctx, minion, trigger)) {\n"
     "            return;\n"
     "        }\n"
     "        effect.accept(ctx);",
     "        if (false && needsTargetChoice(ctx, minion, trigger)) {\n"
     "            return;\n"
     "        }\n"
     "        effect.accept(ctx);",
     "junit", "Batch68SummonTargetTest", "召喚時の対象には自分自身も含まれる"),

    # ★同じ改変を、撤去した門の代わりに置いた試験にも当てる(軸は1つ)
    ("場に出てからの問い合わせをやめる(→ 撤去した門の代わりの試験)", REGISTRY,
     "        if (needsTargetChoice(ctx, minion, trigger)) {\n"
     "            return;\n"
     "        }\n"
     "        effect.accept(ctx);",
     "        if (false && needsTargetChoice(ctx, minion, trigger)) {\n"
     "            return;\n"
     "        }\n"
     "        effect.accept(ctx);",
     "junit", "Batch60Test", "墓地から召喚したカード自身は召喚時の候補に現れない"),

    # ===============================================================
    # 軸3: ★誘発の種別で絞ること(68 の実装で実際に踏んだ穴)
    # ===============================================================
    ("誘発の種別を見ずに対象を問う(【破壊時】まで巻き込む)", REGISTRY,
     "        if (trigger != TriggerType.ON_SUMMON && trigger != TriggerType.ON_ENTER) {\n"
     "            return false;\n"
     "        }\n"
     "        boolean alreadyChosen",
     "        boolean alreadyChosen",
     "junit", "Batch68SummonTargetTest", "破壊時の誘発は召喚時の対象を問わない"),

    # ===============================================================
    # 軸4: ★手札から出したら【召喚時】も発動する(裁定311)
    # ===============================================================
    ("手札からの登場で【召喚時】を焚かない(67 までの姿)", ACTIONS,
     "        if (origin == FieldEntryOrigin.HAND) {\n"
     "            // ★★Batch 68(裁定311): 手札から出たなら【召喚時】も発動する\n"
     "            room.addLog(\"【%s】が効果で手札から場に出ました\".formatted(master.name()));\n"
     "            effects.fire(TriggerType.ON_SUMMON, minion, ctx);",
     "        if (origin == FieldEntryOrigin.HAND) {\n"
     "            // ★★Batch 68(裁定311): 手札から出たなら【召喚時】も発動する\n"
     "            room.addLog(\"【%s】が効果で手札から場に出ました\".formatted(master.name()));",
     "junit", "Batch68SummonFromHandTest", "効果で手札から出したミニオンは召喚時を発動する"),

    # ★「そうでない側」も壊す —— 墓地からも焚いてしまう改変
    ("墓地から出しても【召喚時】を焚く(裁定311 を広げすぎる)", ACTIONS,
     "        if (origin == FieldEntryOrigin.HAND) {",
     "        if (origin == FieldEntryOrigin.HAND || origin == FieldEntryOrigin.OTHER) {",
     "junit", "Batch68SummonFromHandTest", "効果で墓地から出したミニオンは召喚時を発動しない"),

    # ===============================================================
    # 軸5: ★スタンディングテントの例外(総合ルール2-7)
    # ===============================================================
    ("スタンディングテントの賢魂に HAND を渡す(本文の打ち消しを消す)", REGISTRY,
     "            MinionInstance placed = ctx.actions().putIntoFieldByEffect(\n"
     "                    ctx.room(), ctx.owner(), \"QTE-M-DARK-38\", List.of(), ctx.fromTaboo());",
     "            MinionInstance placed = ctx.actions().putIntoFieldByEffect(\n"
     "                    ctx.room(), ctx.owner(), \"QTE-M-DARK-38\", List.of(), ctx.fromTaboo(),\n"
     "                    FieldEntryOrigin.HAND);",
     "junit", "Batch68SummonFromHandTest", "スタンディングテントの賢魂で出た自身は召喚時を発動しない"),

    # ===============================================================
    # 軸6: ★素材を確保できる進化だけを候補に入れる(裁定308(b) の但し書き)
    # ===============================================================
    ("素材の確保を見ずに進化を候補へ入れる", REGISTRY,
     "            if (m.type() == CardType.EVOLUTION && !evolutionMaterialsAvailable(owner, m.id())) {\n"
     "                continue;\n"
     "            }",
     "            if (false) {\n"
     "                continue;\n"
     "            }",
     "junit", "Batch68SummonFromHandTest", "素材を確保できない進化は候補に入らない"),

    # ===============================================================
    # 軸7: ★進化を候補に戻したこと(裁定308(b) の本体)
    # ===============================================================
    ("進化ミニオンを候補から外す(67 までの姿)", REGISTRY,
     "            if (!m.type().isMinion() || !filter.test(m)) {\n"
     "                continue;\n"
     "            }",
     "            if (m.type() != CardType.MINION || !filter.test(m)) {\n"
     "                continue;\n"
     "            }",
     "junit", "Batch67TextImplTest", "聖なる降誕の儀式は素材を確保できる進化ミニオンを出せる"),

    # ===============================================================
    # 軸8: ★ガイル・フォックスの数え方(裁定311 で壊れた「1違う閾値」)
    # ===============================================================
    ("ガイル・フォックスが自分の使用を数えない(59 の ON_ENTER 側の姿)", REGISTRY,
     "        boolean summoned = ctx.source() != null && !ctx.source().isEnteredByEffect();\n"
     "        return summoned ? used + 1 : used;",
     "        return used;",
     "junit", "Batch59ReworkTest", "ガイルフォックスは3枚目として召喚すると潜伏を得る"),

    # ★「そうでない側」も壊す —— 常に自分を数える(効果で出た場合が1枚甘くなる)
    ("ガイル・フォックスが常に自分を数える(裁定311 で壊れた形)", REGISTRY,
     "        boolean summoned = ctx.source() != null && !ctx.source().isEnteredByEffect();\n"
     "        return summoned ? used + 1 : used;",
     "        return used + 1;",
     "junit", "Batch59ReworkTest", "ガイルフォックスは効果で場に出た場合に使用2枚では潜伏を得ない"),

    # ===============================================================
    # 軸9: ★選ぶ余地が無いときだけ自動で決めること(12b・51 からの流儀)
    # ===============================================================
    ("任意の要求でも候補1件なら自動で決める(選ばない自由を奪う)", REGISTRY,
     "        if (min == max && candidates.size() == min) {",
     "        if (candidates.size() == 1) {",
     "junit", "Batch68SummonTargetTest", "任意の要求は候補が1件でも問い合わせる"),

    # ===============================================================
    # 軸10: ★68 に相乗りした小さい3件(裁定306・307・309)
    # ===============================================================
    ("ポセイドンからフェイズの検査を外す(裁定306 を戻す)", REGISTRY,
     "                (state, player, handIndex) -> state.getPhase() == TurnPhase.MAIN\n"
     "                        && player.getHand().size() >= 7 && !player.isPlayedCardThisTurn(),",
     "                (state, player, handIndex) -> player.getHand().size() >= 7\n"
     "                        && !player.isPlayedCardThisTurn(),",
     "junit", "Batch68SummonTargetTest", "ポセイドンはメインフェイズ以外では特殊召喚できない"),

    ("ゾクシムのドローを【召喚時】に戻す(裁定307 を戻す)", REGISTRY,
     "        register(\"QTE-M-WATER-32\", TriggerType.ON_ENTER,\n"
     "                ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 2));",
     "        register(\"QTE-M-WATER-32\", TriggerType.ON_SUMMON,\n"
     "                ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 2));",
     "junit", "Batch68SummonTargetTest", "ゾクシムのドローは登場時である"),

    ("サイクロン・リフレッシュのウェポン要求を両者に広げる(裁定309 の側を崩す)", REGISTRY,
     "        Requirement.upTo(Kind.WEAPON, Side.SELF, 1, \"デッキに戻すウェポンを選んでください(合計2枚まで)\")",
     "        Requirement.upTo(Kind.WEAPON, Side.ANY, 1, \"デッキに戻すウェポンを選んでください(合計2枚まで)\")",
     "junit", "Batch68SummonTargetTest", "サイクロンリフレッシュの要求は手札ミニオンウェポンの順である"),

    # ===============================================================
    # 軸11: ★割り込みの描画が Kind を数えないこと(verify 層)
    # ===============================================================
    ("割り込みの描画に Kind ごとの分岐を足す(知らない種類が空欄になる)", BATTLE_JS,
     "    const multi = choice.max > 1 || choice.min === 0;\n"
     "    choice.candidates.forEach(cand => {",
     "    const multi = choice.max > 1 || choice.min === 0;\n"
     "    const KNOWN = ['HAND', 'TRASH', 'REVEALED', 'MANA', 'MINION'];\n"
     "    if (!KNOWN.includes(choice.kind)) return;\n"
     "    choice.candidates.forEach(cand => {",
     "verify", None, "割り込みの描画は Kind を数えていない"),

    # ★同じ改変で、68 が足した WEAPON も描けなくなる(軸は1つ・番人は2つ)
    ("割り込みの描画に Kind ごとの分岐を足す(→ ウェポンの割り込み)", BATTLE_JS,
     "    const multi = choice.max > 1 || choice.min === 0;\n"
     "    choice.candidates.forEach(cand => {",
     "    const multi = choice.max > 1 || choice.min === 0;\n"
     "    const KNOWN = ['HAND', 'TRASH', 'REVEALED', 'MANA', 'MINION'];\n"
     "    if (!KNOWN.includes(choice.kind)) return;\n"
     "    choice.candidates.forEach(cand => {",
     "verify", None, "割り込みのウェポン選択が描ける"),

    # ★ウェポンのラベルから側を落とす(押し間違えを招く。JUnit 層)
    ("ウェポンの候補ラベルから側を落とす", VIEW_BUILDER,
     "                    label = weapon == null ? id\n"
     "                            : \"%s(%s)\".formatted(weapon.name(), side == player ? \"自分\" : \"相手\");",
     "                    label = weapon == null ? id : weapon.name();",
     "junit", "Batch68SummonTargetTest", "ウェポンの割り込み候補にはどちらの側かが添えられる"),
]

# ★想定内の NG(壊れているのに落ちない、と分かっていて残しているもの)。
#   68 では1件も無い。
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
