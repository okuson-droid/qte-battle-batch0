#!/usr/bin/env python3
"""Batch 73(総点検の続き + WS の受け口の箱型化)の壊し検証(裁定116)。

実装をわざと壊し、狙った番人が落ちることを確かめる。
★答えは4値である(裁定196):

  OK       … 狙った番人が落ちた(番人は仕事をしている)
  NG       … 壊したのに落ちなかった(番人が足りない)
  EMPTY    … その番人が1件も走っていない(名前の書き間違い等)
  SETUP-NG … 改変が当たっていない(置換文字列が0箇所または2箇所以上に一致)

★★★<b>照合先は JUnit だけである。</b>
  73 は <b>JavaScript を1行も変えていない</b> —— テンプレートも CSS も触っていないので、
  機械検証(verify)に足す軸が1つも無い。
  ★71 の<b>逆</b>である(あちらは Java を1行も変えず、verify にしか照合先が無かった)。
  ★★「番人を置く場所を選ぶ前に、そこまで届くかを確かめる」(70 の教訓)の結果である。

★★<b>3種類の番人を狙っている。</b>
  - {@code WsRequestPayloadTest} / {@code ManualWsRequestPayloadTest}
      …… クライアントの本文が<b>開けるか</b>(変換の層)
  - {@code Batch73PayloadGuardTest}
      …… 送られてこない項目が<b>実際の入口で断られ、理由が返るか</b>
  - {@code Batch73TextImplTest}
      …… <b>本文どおりに動くか</b>(総点検で見つかった8枚)

★★★<b>出口ごとに当てている</b>(71 の教訓)。
  - {@code manaConvertReturnThenPut} は出口が3つ …… 軸18・19・20
  - 《墓穴の呪い》の解決も出口が3つ …………………… 軸16・17(+ 候補0は下の書き残し)

使い方: python3 tools/batch73_break_check.py [ケース番号...]
★★<b>長いので3つに分けて回し、前後で `git diff --stat` を突き合わせること</b>(70 の教訓)。
  例: `python3 tools/batch73_break_check.py 1 2 3 4 5 6 7`
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
M2 = "/root/m2work/repository"

WS = "src/main/java/com/example/qte/web/GameWsController.java"
DRAG = "src/main/java/com/example/qte/manual/ManualDragCue.java"
KEYWORDS = "src/main/java/com/example/qte/master/CardTextKeywords.java"
REGISTRY = "src/main/java/com/example/qte/effect/CardEffectRegistry.java"
STATS = "src/main/java/com/example/qte/effect/StatCalculator.java"
ACTIONS = "src/main/java/com/example/qte/game/GameActions.java"

PAYLOAD_TEST = "WsRequestPayloadTest"
MANUAL_PAYLOAD_TEST = "ManualWsRequestPayloadTest"
GUARD_TEST = "Batch73PayloadGuardTest"
TEXT_TEST = "Batch73TextImplTest"

# 壊しても落ちないことが分かっているもの(理由つき)。★73 は1件も無い。
EXPECTED_NG = {}

# ★★★壊しどころが無いもの(裁定196 の正直な扱い)——
#   軸に入れていない理由を書き残す。
#
#   1) 《墓穴の呪い》の「候補が0体」の出口
#      壊しても<b>何も起きないまま何も起きない</b>ので、どの番人も色が変わらない。
#      ログの文言だけが違う経路であり、文言を測る番人は置いていない。
#
#   2) {@code required} の「値が在れば通す」出口
#      通さなくすると<b>すべての操作が断られる</b>ので、
#      ほぼ全部の JUnit が赤くなる —— 番人の強さの証明にならない。
#      ★代わりに {@code Batch73PayloadGuardTest#送られてくれば関門は通る} を置いてある
#      (「常に断る」実装を排除する側。裁定181)。
#
#   3) 進化ミニオンの一族(13枚)
#      <b>73 は直していない</b>(裁定依頼 A)。作っていないものは壊せない。

# (説明, ファイル, 置換前, 置換後, クラス, 照合先の名前の一部)
CASES = [
    # ===============================================================
    # I. WS の受け口 —— 箱型にすること・畳まずに断ること(軸1〜9)
    # ===============================================================

    # 軸1: ★★★クライアントが送らない可能性のある項目を原始型で受けないこと
    #   ★型と関門は<b>一組で1つの筋</b>である(片方だけ戻すとコンパイルが通らず EMPTY になる。裁定304)
    ("goFirst を原始型 boolean で受ける(送られてこないと変換ごと失敗する)", WS,
     "    public record ChooseOrderRequest(String playerId, Boolean goFirst) {\n"
     "\n"
     "        public Boolean goFirst() {\n"
     "            return required(goFirst, \"goFirst\");\n"
     "        }\n"
     "    }",
     "    public record ChooseOrderRequest(String playerId, boolean goFirst) {\n"
     "    }",
     PAYLOAD_TEST, "必須項目が欠けても変換は通る"),

    # 軸2: ★★箱型にしただけでは足りない —— 欠けたまま読ませないこと
    ("goFirst の関門を外す(null をそのまま返す)", WS,
     "            return required(goFirst, \"goFirst\");",
     "            return goFirst;",
     PAYLOAD_TEST, "必須項目が欠けたら断る"),

    # 軸3〜6: ★入口ごとに当てる(4つの record が別々に関門を持っている)
    ("charge-mana の handIndex の関門を外す", WS,
     "    public record HandActionRequest(String playerId, Integer handIndex) {\n"
     "\n"
     "        public Integer handIndex() {\n"
     "            return required(handIndex, \"handIndex\");\n"
     "        }\n"
     "    }",
     "    public record HandActionRequest(String playerId, Integer handIndex) {\n"
     "\n"
     "        public Integer handIndex() {\n"
     "            return handIndex;\n"
     "        }\n"
     "    }",
     GUARD_TEST, "マナチャージの手札の位置が無いと断る"),

    ("play-card の handIndex の関門を外す", WS,
     "        public Integer handIndex() {\n"
     "            return required(handIndex, \"handIndex\");\n"
     "        }\n"
     "\n"
     "        public Boolean enhanced() {",
     "        public Integer handIndex() {\n"
     "            return handIndex;\n"
     "        }\n"
     "\n"
     "        public Boolean enhanced() {",
     GUARD_TEST, "プレイの手札の位置が無いと断る"),

    ("summon-from-grave の trashIndex の関門を外す", WS,
     "            return required(trashIndex, \"trashIndex\");",
     "            return trashIndex;",
     GUARD_TEST, "墓地の位置が無いと断る"),

    ("play-taboo の tabooIndex の関門を外す", WS,
     "            return required(tabooIndex, \"tabooIndex\");",
     "            return tabooIndex;",
     GUARD_TEST, "禁忌の位置が無いと断る"),

    # 軸7: ★★★<b>例外の型が肝である。</b>execute が捕まえない型で投げると、
    #   関門は在るのに<b>また無言に戻る</b>(72b の宿題そのもの)
    ("関門が execute の捕まえない例外を投げる(無言に戻る)", WS,
     "            throw new IllegalArgumentException(\n"
     "                    \"操作に必要な項目が送られていません: \" + field);",
     "            throw new NullPointerException(\n"
     "                    \"操作に必要な項目が送られていません: \" + field);",
     GUARD_TEST, "マナチャージの手札の位置が無いと断る"),

    # 軸8・9: ★手動モードの受け口も同じ地雷を持っていた
    #
    # ★★★<b>軸8 の照合先は「表」ではない。</b>最初は
    #   {@code ManualWsRequestPayloadTest#すべての送信が開ける} を狙ったが <b>NG</b> だった ——
    #   {@code manual-battle.js} は dragcue の3つの入口すべてで {@code active} を<b>送っている</b>ので、
    #   原始型に戻しても表の本文は全部開ける。
    #   ★これは 72b の宿題が書いたとおりの状態である(「いまはどの入口も送っているので落ちない」)。
    #   → 届く番人は「<b>送られてこなかったとき</b>の1件」だけである。
    #   ★★<b>「まだ落ちていない地雷」は、踏まれる形でしか測れない。</b>
    ("矢印の active を原始型 boolean で受ける", DRAG,
     "            Boolean active) {\n"
     "\n"
     "        /** ★Batch 73: 送られてこなければ「矢印を消す」に落とす(安全側)。 */\n"
     "        public Boolean active() {\n"
     "            return active != null && active;\n"
     "        }\n"
     "    }",
     "            boolean active) {\n"
     "    }",
     MANUAL_PAYLOAD_TEST, "矢印の旗は畳む"),

    ("矢印の active を畳まない(null のまま渡る)", DRAG,
     "            return active != null && active;",
     "            return active;",
     MANUAL_PAYLOAD_TEST, "矢印の旗は畳む"),

    # ===============================================================
    # II. 総点検で見つかった8枚(軸10〜20)
    # ===============================================================

    # 軸10: ★「【X】の◯◯」は参照である
    ("キーワードの参照除外から「の」を外す(アルキンティスが【知識】を持つ)", KEYWORDS,
     "\"^(を持|を与|を付|付与|を行|を得|を失|の)\"",
     "\"^(を持|を与|を付|付与|を行|を得|を失)\"",
     TEXT_TEST, "アルキンティスは知識を持たない"),

    # 軸11: ★ターンエンドの誘発は自分のターンだけ
    ("詠唱の疾風騎士の自ターン判定を消す(相手のターンエンドでも回収する)", REGISTRY,
     "            if (ctx.state().turnPlayer() != ctx.owner()) {\n"
     "                return;\n"
     "            }\n"
     "            if (ctx.owner().getSpellsCastThisTurn() < 5) {",
     "            if (ctx.owner().getSpellsCastThisTurn() < 5) {",
     TEXT_TEST, "疾風騎士は相手のターンエンドでは回収しない"),

    # 軸12: ★「墓地にあるカード」は種別を絞らない
    ("悪夢の数え方をスペル以外に戻す", STATS,
     "        if (NIGHTMARE.equals(card.id())) {\n"
     "            cost -= owner.getTrash().size();\n"
     "        }",
     "        if (NIGHTMARE.equals(card.id())) {\n"
     "            cost -= nonSpellCountInTrash(owner);\n"
     "        }",
     TEXT_TEST, "悪夢は墓地のスペルも数える"),

    # 軸13: ★Ver1.1 で消えた自壊
    ("這い寄る生霊に自壊を戻す", REGISTRY,
     "                TargetSpec.of(),\n"
     "                ctx -> {\n"
     "                },\n"
     "                ctx -> {\n"
     "                },\n"
     "                \"自分のミニオンが破壊されているため、コスト0で特殊召喚できます\"));",
     "                TargetSpec.of(),\n"
     "                ctx -> {\n"
     "                },\n"
     "                ctx -> {\n"
     "                    if (ctx.source() != null) {\n"
     "                        ctx.source().setDestroyAtEndOfTurn(true);\n"
     "                    }\n"
     "                },\n"
     "                \"自分のミニオンが破壊されているため、コスト0で特殊召喚できます\"));",
     TEXT_TEST, "這い寄る生霊は自壊しない"),

    # 軸14: ★「【守護】ミニオン」の種別(67 が塞いだ穴の3枚目)
    ("降臨の伝道師の種別の絞り込みを消す(進化が素材ゼロで出る)", REGISTRY,
     "                if (revealedCard.type() == CardType.MINION\n"
     "                        && revealedCard.hasKeyword(Keyword.GUARD)) {",
     "                if (revealedCard.hasKeyword(Keyword.GUARD)) {",
     TEXT_TEST, "降臨の伝道師は進化を出さない"),

    # 軸15: ★「次の自分のターン」の計算(自分の手番なら2つ先)
    ("詠唱の宝珠の期限を常に1つ先にする(次は相手のターンになる)", ACTIONS,
     "        return state.turnPlayer() == owner ? now + 2 : now + 1;",
     "        return now + 1;",
     TEXT_TEST, "詠唱の宝珠の期限はターン番号で決まる"),

    # 軸16: ★★「2枚選び」を全体破壊に戻す(72 までの姿)
    ("墓穴の呪いを全体破壊に戻す", REGISTRY,
     "            if (candidates.size() <= 2) {\n"
     "                ctx.room().addLog(\"【墓穴の呪い】: 体力%d以下のミニオンを破壊します\".formatted(threshold));\n"
     "                candidates.forEach(id -> destroyChosenMinion(ctx, id));\n"
     "                return;\n"
     "            }",
     "            ctx.room().addLog(\"【墓穴の呪い】: 体力%d以下のミニオンを破壊します\".formatted(threshold));\n"
     "            candidates.forEach(id -> destroyChosenMinion(ctx, id));\n"
     "            if (true) {\n"
     "                return;\n"
     "            }",
     TEXT_TEST, "墓穴の呪いは2体までしか壊さない"),

    # 軸17: ★同じ関数の<b>別の出口</b>(候補が2体以下のとき問わない側)
    ("墓穴の呪いが候補2体以下でも問い合わせる", REGISTRY,
     "            if (candidates.size() <= 2) {\n"
     "                ctx.room().addLog(\"【墓穴の呪い】: 体力%d以下のミニオンを破壊します\".formatted(threshold));",
     "            if (candidates.size() <= 1) {\n"
     "                ctx.room().addLog(\"【墓穴の呪い】: 体力%d以下のミニオンを破壊します\".formatted(threshold));",
     TEXT_TEST, "墓穴の呪いは候補が2体以下なら問わない"),

    # 軸18: ★戻すマナを本人が選ぶこと(裁定299)
    ("風のマナ変換が戻すマナを自動で決める(末尾を取る)", REGISTRY,
     "            ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.one(\n"
     "                    PendingChoice.Kind.MANA, faceUpPositions, ResumePoint.MANA_CONVERT_RETURN,\n"
     "                    \"【風のマナ変換】: 手札に戻す表向きのマナを選んでください\"));",
     "            manaConvertReturnThenPut(ctx,\n"
     "                    Integer.parseInt(faceUpPositions.get(faceUpPositions.size() - 1)));",
     TEXT_TEST, "風のマナ変換は戻すマナを選べる"),

    # 軸19: ★同じ関数の<b>別の出口</b>(表向きが1枚のとき問わない側)
    ("風のマナ変換が表向き1枚でも問い合わせる", REGISTRY,
     "            if (faceUpPositions.size() == 1) {\n"
     "                manaConvertReturnThenPut(ctx, Integer.parseInt(faceUpPositions.get(0)));\n"
     "                return;\n"
     "            }",
     "",
     TEXT_TEST, "風のマナ変換は表向きが1枚なら問わない"),

    # 軸20: ★★<b>後半が走ること</b>(戻したあと手札から置く段)——
    #   1箇所にまとめた理由そのものを測る(66 の教訓「候補が1枚のときだけ後半が走らない」)
    ("風のマナ変換の後半(裏向きで置く問い合わせ)を消す", REGISTRY,
     "        ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.one(\n"
     "                PendingChoice.Kind.HAND, handPositions, ResumePoint.MANA_CONVERT_PUT,\n"
     "                \"【風のマナ変換】: 裏向きでマナに置く手札を選んでください\"));",
     "",
     TEXT_TEST, "風のマナ変換は表向きが1枚なら問わない"),
]


def env():
    copied = dict(os.environ)
    copied["MAVEN_OPTS"] = copied.get("MAVEN_OPTS", "")
    return copied


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as handle:
        return handle.read()


def write(path, text):
    with open(os.path.join(ROOT, path), "w", encoding="utf-8") as handle:
        handle.write(text)


def run_junit(test_class):
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

    targets = sorted({case[1] for case in CASES})
    baseline = {path: read(path) for path in targets}
    results = []
    for number, (label, path, before, after, cls, target) in enumerate(CASES, 1):
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
            answer = junit_verdict(run_junit(cls), target)
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
    good = counts.get("OK", 0) + counts.get("NG(想定内)", 0)
    return 0 if good == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
