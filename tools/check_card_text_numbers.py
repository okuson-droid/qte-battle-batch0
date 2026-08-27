#!/usr/bin/env python3
"""カード本文の数値が、そのカードの実装にも現れているかを見る(★Batch 67)。

なぜ要るのか
------------
`tools/report_effects.py` の「未実装0枚」は**登録が在るか**しか見ない(裁定303)。
本文が Ver1.1 で「コスト4以下」に変わったのに実装が `<= 3` のままでも、
あのツールは何も言わない —— 登録は在るからである。

このツールが見るのは1つだけである。

    **本文に書かれた数値が、そのカードの実装のどこにも現れていない。**

これは「本文どおりか」の証明ではない。数の一致は本文どおりであることの
必要条件にすぎず、《聖剣 エクスカリバー》のように本文が数を持たない差
(「体力を全て回復」→ 2回復)はここでは捕まらない。
**それでも、Batch 67 が見つけた7枚のうち2枚はこれ1本で捕まった。**

本文と実装が「意味として」一致しているかを守るのは
`src/test/resources/text-impl-review.json`(突き合わせの台帳)のほうである。
2つは役割が違う —— 台帳は人が見たことを覚え、こちらは機械が数を数える。

判定
----
    OK : 説明の付いていない不足が0件
    NG : 説明の付いていない不足がある(そのカードの本文と実装を突き合わせること)

★不足に正当な理由があるときは KNOWN_GAPS に**理由つきで**足す。
  理由を書かずに黙らせるのがいちばん悪い(裁定196)。
"""

import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CARDS = os.path.join(ROOT, "src/main/resources/cards/manual-cards.json")
SRC = os.path.join(ROOT, "src/main/java")

ID_PATTERN = re.compile(r"QTE-M-[A-Z]+-\d+")
# 数値リテラル。小数点の後ろ・識別子の一部・COST_4_OR_LESS のような名前の中は拾わない
NUMBER = re.compile(r"(?<![A-Za-z_\d.])(\d+)(?![A-Za-z_\d])")
CONST_DECL = re.compile(
    r'\b([A-Z][A-Z0-9_]{2,})\s*=\s*"(QTE-M-[A-Z]+-\d+)"')
# 数値の定数(MEIMA_SWORD_USES_PER_TURN = 5 など)。名前で書かれた数も「実装が持つ数」である
NUMBER_CONST_DECL = re.compile(r"\b([A-Z][A-Z0-9_]{2,})\s*=\s*(\d+)\s*[;,)]")
# Filter の名前に埋まっている数値(COST_4_OR_LESS など)は「実装に現れた数」として数える
FILTER_NUMBER = re.compile(r"(?:COST|HP)_(\d+)_OR_LESS")
# ★★Batch 68: 名前を付けた効果のラムダ(2つの誘発が同じ効果を共有する形)。
#   `Consumer<EffectContext> galeFox = ctx -> { ... };` のように書くと、
#   register の文にはラムダの<b>名前</b>しか残らず、中の数が見えなくなる。
#   ★これは NUMBER_CONST_DECL と同じ問題である ——
#   「同じ規則を2箇所に書かない」(裁定130)に従った実装ほど、数を数える番人から不利になる。
#   そうならないよう、名前が現れた文にはラムダの本文を展開して渡す。
LAMBDA_DECL = re.compile(r"Consumer<EffectContext>\s+(\w+)\s*=\s*ctx\s*->\s*\{")

# ---------------------------------------------------------------
# 数えない数
# ---------------------------------------------------------------
# ★0 と 1 は、本文にあって実装に無くても報告しない。
#
# 「自分の墓地のカード<b>1</b>枚につき Attack+1」のような数え上げは、
# 実装では {@code attack += trash.size()} と書かれ、**1 という数が現れない** ——
# 1 は「1つあたり」を言うための語であって、実装が持つべき値ではないからである。
# 同じ理由で「ミニオン<b>1</b>体を選ぶ」「カードを<b>1</b>枚引く」も、
# 書き方によって現れたり現れなかったりする。
#
# ★<b>これで番人が弱くなるわけではない。</b>「本文が2体に増えたのに実装が1体のまま」
# (《生贄を求める邪鬼》)も「3以下が4以下に増えた」(《大地震》)も、
# <b>増えた側の数</b>が不足として現れるので、どちらもこの除外を素通りしない。
# 除外して静かになったぶん、残った不足を人が本当に読むようになる。
IGNORED_NUMBERS = {0, 1}

# ---------------------------------------------------------------
# 説明の付いた不足(★理由を必ず書くこと)
# ---------------------------------------------------------------
# ★Batch 67 の実測では、残った不足はすべて<b>5つの型</b>のどれかだった。
#   どれも実装のほうが正しく、本文との食い違いではない。
#   型の名前を理由の先頭に書いてある —— 新しい不足が出たとき、
#   「どの型でもない」なら本当に疑うべき、と分かるようにするためである。
KNOWN_GAPS = {
    # 型1: 【賢魂：n】の n はコードに書かない(裁定248)。
    #      出どころは CardTextKeywords.soulCost ただ1つであり、
    #      SoulSpellSpec は効果と対象要求しか持たない。両方に書けば必ずずれる日が来る。
    "QTE-M-EARTH-30": "型1(賢魂のコスト)。「【賢魂：3】」の 3 は CardTextKeywords.soulCost が読む。",
    "QTE-M-EARTH-36": "型1(賢魂のコスト)。「【賢魂：2】」の 2 は CardTextKeywords.soulCost が読む。",
    "QTE-M-LIGHT-32": "型1(賢魂のコスト)。「【賢魂：3】」の 3 は CardTextKeywords.soulCost が読む。",

    # 型2: 「1ターンに2回攻撃できる」は、既定の1回に1回足す形で表す。
    #      StatCalculator の `max += 1` に 2 という数は現れない。
    "QTE-M-EARTH-19": "型2(2回攻撃)。StatCalculator が `max += 1` と書くので 2 は現れない。",
    "QTE-M-FIRE-31": "型2(2回攻撃)。《連撃の巨岩》と同じ。",
    "QTE-M-WIND-5": "型2(2回攻撃)。《連撃の巨岩》と同じ。",

    # 型3: 割り込み(PendingChoice)を挟むカードは、数が<b>解決側</b>のメソッドにある。
    #      解決側はカードIDを持たない(ResumePoint で分岐する)ので切り出しが届かない。
    "QTE-M-EARTH-28": "型3(割り込みの解決側)。2ダメージは GameService.resolveQuakeHammerAttack にある。",
    # ★Batch 74: QTE-M-EARTH-35 は表から外した。requestManaSummon の第2引数を
    #   カード名からカードIDへ変えた結果、Attack6以下の 6 と同じ文にIDが現れるようになり、
    #   切り出しが届くようになったためである(裁定178: 誰も守っていない例外を残さない)。
    "QTE-M-LIGHT-22": "型3(割り込みの解決側)。3ダメージは finishEffectPut にある(★Batch 74 で resolveMissionaryChoice から移した)。",
    "QTE-M-WIND-36": "型3(割り込みの解決側)。2枚ドローは GATHERING_AYAKASHI_SACRIFICE の解決にある。",

    # 型4: 数が別クラスの定数として宣言されており、そのカードIDと同じ文に現れない。
    "QTE-M-EARTH-1": "型4(別クラスの定数)。6 は PlayerState.DEFAULT_MINION_ZONE_LIMIT である。",

    # 型5: 「n枚目」は、自身を含まない使用カウンタでは n-1 と一致する(裁定1)。
    "QTE-M-WIND-15": "型5(数え方の基準)。「3枚目」は cardsUsedThisTurn == 2 の瞬間である(裁定1)。",
}


def strip_java(src):
    """コメントを空白へ潰す(長さは保つ)。文字列リテラルは残す ―― IDを探すため。"""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            j = i + 1
            while j < n and src[j] != '"':
                if src[j] == "\\":
                    j += 1
                j += 1
            i = j + 1
        elif c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                if src[j] == "\\":
                    j += 1
                j += 1
            i = j + 1
        elif c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        elif c == "/" and i + 1 < n and src[i + 1] == "*":
            j = src.find("*/", i)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        else:
            i += 1
    return "".join(out)


def mask_strings(src):
    """文字列・文字リテラルを空白へ潰す(数値リテラルだけを見たいときに使う)。"""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c in '"\'':
            q = c
            j = i + 1
            while j < n and src[j] != q:
                if src[j] == "\\":
                    j += 1
                j += 1
            for k in range(i, min(j + 1, n)):
                if out[k] != "\n":
                    out[k] = " "
            i = j + 1
        else:
            i += 1
    return "".join(out)


def statements(src):
    """文の区切り(offset の対)。深度2以上(=メソッドの中)で始まり、
    括弧の外の ';' か、深度が戻る '}' で終わる単位を1つの文とする。"""
    masked = mask_strings(src)
    res, depth, paren, start = [], 0, 0, None
    for i, ch in enumerate(masked):
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if start is not None and depth <= 2 and paren == 0:
                res.append((start, i + 1))
                start = None
        elif ch == "(":
            paren += 1
        elif ch == ")":
            paren -= 1
        elif ch == ";" and paren == 0 and start is not None:
            res.append((start, i + 1))
            start = None
        if start is None and not ch.isspace() and depth >= 2:
            start = i
    return res


def brace_body(src, open_index):
    """`{` の位置から対応する `}` までを返す(★Batch 68)。
    見つからなければ空文字を返す —— 番人が落ちるより、拾えないほうがまだよい。"""
    depth = 0
    for i in range(open_index, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[open_index:i + 1]
    return ""


def collect():
    """カードID -> そのカードに関係する実装の断片(原文)。

    IDのリテラルが直接現れる文に加えて、**そのIDを指す定数**が現れる文も集める
    (ルール側の判定点はカードIDを定数に置く決まりだからである。裁定130・163)。
    """
    files = []
    for dp, _, fs in os.walk(SRC):
        for f in fs:
            if f.endswith(".java"):
                p = os.path.join(dp, f)
                src = strip_java(open(p, encoding="utf-8").read())
                files.append((p, src, statements(src)))

    consts = {}   # 定数名 -> カードID
    numbers = {}  # 定数名 -> 数値
    lambdas = {}  # ラムダ名 -> その本文(★Batch 68)
    for _, src, _ in files:
        for name, cid in CONST_DECL.findall(src):
            consts[name] = cid
        for name, value in NUMBER_CONST_DECL.findall(src):
            numbers[name] = value
        for m in LAMBDA_DECL.finditer(src):
            lambdas[m.group(1)] = brace_body(src, m.end() - 1)

    out = {}
    for _, src, sts in files:
        for a, b in sts:
            seg = src[a:b]
            ids = set(ID_PATTERN.findall(seg))
            for name, cid in consts.items():
                if re.search(r"\b%s\b" % name, seg):
                    ids.add(cid)
            if not ids:
                continue
            # ★名前で書かれた数を、その場に展開してから渡す。
            # 「1箇所に置く」ために定数にしたものが、数を数えるツールから
            # 見えなくなるのでは本末転倒である(裁定163 に従った実装ほど不利になる)。
            expanded = seg
            for name, value in numbers.items():
                if re.search(r"\b%s\b" % name, expanded):
                    expanded += " /*%s*/ %s" % (name, value)
            # ★★Batch 68: 名前を付けた効果のラムダも同じように展開する。
            #   2つの誘発が同じ効果を共有する形(《ガイル・フォックス》)では、
            #   register の文にラムダの名前しか残らない
            for name, body in lambdas.items():
                if re.search(r"\b%s\b" % name, expanded):
                    expanded += " /*%s*/ %s" % (name, body)
            for cid in ids:
                out.setdefault(cid, []).append(expanded)
    return out


def main():
    with open(CARDS, encoding="utf-8") as f:
        cards = {c["id"]: c for c in json.load(f)["cards"]}
    blocks = collect()

    gaps, unimplemented = [], 0
    for cid, card in sorted(cards.items()):
        text = card.get("text") or ""
        if not text:
            continue
        if cid not in blocks:
            unimplemented += 1
            continue
        wanted = set(int(x) for x in re.findall(r"\d+", text))
        # ★カードID(QTE-M-EARTH-11 の 11)は数値ではないので落とす。
        # ★<b>文字列リテラルの中の数字は数える。</b>プロンプトやログに書かれた数も
        #   実装の一部であり、「実装がその数を持っている」証拠になる ——
        #   《大地震》は本文が 4 に変わったのにログが「コスト3以下」と言い続けていた。
        # ★<b>コメントの中の数字は数えない</b>(strip_java が先に落としている)。
        #   コメントには Ver0.4 の本文が書き写されていることがあり、
        #   <b>いちばん信用してはいけない場所</b>だからである(67 が見つけた5枚がまさにそれ)。
        code = "\n".join(ID_PATTERN.sub(" ", seg) for seg in blocks[cid])
        found = set(int(x) for x in NUMBER.findall(code))
        found |= set(int(x) for x in FILTER_NUMBER.findall(code))
        missing = sorted(wanted - found - IGNORED_NUMBERS)
        if missing:
            gaps.append((cid, card["name"], sorted(wanted), missing))

    print("=== カード本文の数値と実装の照合(★Batch 67) ===")
    print("  照合したカード     : %d枚" % (len(blocks) & 0xFFFFFF))
    print("  実装の断片が無い枚数: %d枚(キーワードだけのカードなど)" % unimplemented)
    print()

    unexplained = [g for g in gaps if g[0] not in KNOWN_GAPS]
    explained = [g for g in gaps if g[0] in KNOWN_GAPS]

    if explained:
        print("--- 説明の付いている不足(%d件) ---" % len(explained))
        for cid, name, wanted, missing in explained:
            print("  %-20s %-16s 本文%s 実装に無い%s" % (cid, name, wanted, missing))
            print("      理由: %s" % KNOWN_GAPS[cid])
        print()

    if unexplained:
        print("--- ★説明の付いていない不足(%d件) ---" % len(unexplained))
        for cid, name, wanted, missing in unexplained:
            print("  %-20s %-16s 本文%s 実装に無い%s" % (cid, name, wanted, missing))
            print("      → 本文と実装を突き合わせること。"
                  "正当な差なら KNOWN_GAPS に理由つきで足すこと。")
        print("\nNG: 説明の付いていない不足が %d件あります。" % len(unexplained))
        return 1

    stale = [cid for cid in KNOWN_GAPS if cid not in {g[0] for g in gaps}]
    if stale:
        print("NG: KNOWN_GAPS に、もう不足していないカードが残っています: %s" % stale)
        print("    直ったのなら表から外すこと(誰も守っていない例外を残さない。裁定178)。")
        return 1

    print("OK: 本文の数値はすべて実装に現れています"
          "(説明の付いた %d件を除く)。" % len(explained))
    return 0


if __name__ == "__main__":
    sys.exit(main())
