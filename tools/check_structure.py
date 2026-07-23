#!/usr/bin/env python3
"""
Java のメソッド構造が壊れていないかを検証する。

【このスクリプトが生まれた理由】
Batch 12a で、str_replace の old_str に次の3行をまとめて含めてしまい、
new_str 側で後ろ2行を書き忘れる事故が起きた。

    return new ValidatedTargets(...);
    }                                      <- validateTargets を閉じる
    private void requireCount(...) {       <- 次のメソッドの宣言

結果、`}` が1つと `{` が1つ同時に消えたため、
**ファイル全体の括弧の総数は釣り合ったまま**メソッド構造だけが壊れた。
既存の「括弧の均衡チェック」は総数しか見ないため、この事故を検出できない。

【検出方法】
Java の整形規約では、クラス直下のメンバは必ずインデント4で始まり、
そのメンバを閉じる `}` も必ずインデント4に来る。
したがって「インデント4の `}` の数」と「クラス直下のメンバ宣言の数」がずれていれば構造が壊れている。

より直接的に、次の2つを見る。
  A) インデント8以上の位置にあるメソッド宣言(本来ありえない = 他のメソッドに飲み込まれている)
  B) 各メソッドの中身の行数が異常に多いもの(飲み込みの結果として肥大する)
"""
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else '.')

# メソッド宣言らしき行(アノテーション・制御構文・ラムダは除く)
METHOD_RE = re.compile(
    r'^(?P<indent> *)(?:public|private|protected)\s+'
    r'(?:static\s+|final\s+|abstract\s+|synchronized\s+)*'
    r'[\w<>\[\], .?]+\s+(?P<name>\w+)\s*\('
)


def strip_noise(src):
    """文字列・コメントを空白に潰す(行構造は保つ)。"""
    out, i, n = [], 0, len(src)
    while i < n:
        two = src[i:i + 2]
        if two == '//':
            j = src.find('\n', i)
            j = n if j < 0 else j
            out.append(' ' * (j - i))
            i = j
        elif two == '/*':
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append(re.sub(r'[^\n]', ' ', src[i:j]))
            i = j
        elif src[i] in '"\'':
            q = src[i]
            j = i + 1
            while j < n and src[j] != q:
                if src[j] == '\\':
                    j += 1
                j += 1
            out.append(' ' * (min(j + 1, n) - i))
            i = min(j + 1, n)
        else:
            out.append(src[i])
            i += 1
    return ''.join(out)


problems = 0
for f in sorted(ROOT.rglob('*.java')):
    src = strip_noise(f.read_text(encoding='utf-8'))
    lines = src.split('\n')

    # --- A) ★本命: メソッド宣言のインデントとブレース深度の一致 ---
    # Java の整形規約では、ブレース深度 d の位置にある宣言のインデントは 4*d である。
    # 閉じ括弧が1つ消えると、それ以降のメソッドは「深度は増えたのにインデントは4のまま」に
    # なるため、この不一致で構造の破壊を捕まえられる。
    # (総数だけを見る括弧の均衡チェックでは、開き括弧と閉じ括弧が同時に1つずつ消えた場合に
    #  釣り合ってしまい検出できない。Batch 12a の事故がまさにこれだった)
    depth = 0
    for no, line in enumerate(lines, 1):
        m = METHOD_RE.match(line)
        if m:
            indent = len(m.group('indent'))
            expected = 4 * depth
            if indent != expected:
                print(f'★ {f.name}:{no} {m.group("name")}() のインデント {indent} が '
                      f'ブレース深度 {depth}(期待 {expected})と一致しません')
                print('   閉じ括弧の消失、またはメソッドの飲み込みが疑われる')
                problems += 1
        depth += line.count('{') - line.count('}')

    if depth != 0:
        print(f'★ {f.name}: ファイル末尾でブレース深度が {depth}(0であるべき)')
        problems += 1

    # --- B) ★本命: 同一クラス内のメソッド呼び出しが解決できるか ---
    # Batch 12a の事故(メソッド宣言行の消失)は、開き括弧と閉じ括弧が同時に1つずつ消えたため
    # 括弧の総数もブレース深度も釣り合ってしまい、A の検査を素通りした。
    # 実際には「消えたメソッドを呼んでいる箇所」が残るので、そこを突く。
    # これが javac の "cannot find symbol: method xxx" に最も近い検査である。
    declared = set(re.findall(r'(?:public|private|protected|static)\s+'
                              r'[\w<>\[\], .?]+\s+(\w+)\s*\(', src))
    # レコードのコンポーネントは暗黙のアクセサになる
    for m in re.finditer(r'record\s+\w+\s*\(([^)]*)\)', src, re.S):
        for part in m.group(1).split(','):
            names = re.findall(r'(\w+)\s*$', part.strip())
            declared.update(names)
    # ローカル変数・仮引数に入った関数型は bare call されないので考慮不要。
    # 制御構文とキーワードは除外する
    NOT_METHODS = {
        'if', 'for', 'while', 'switch', 'catch', 'return', 'new', 'super', 'this',
        'synchronized', 'do', 'else', 'try', 'assert', 'throw', 'case', 'yield', 'record',
        # 列挙型が暗黙に持つメソッド
        'values', 'valueOf', 'ordinal', 'name',
        # 関数型インタフェースの呼び出し(フィールドやレコード成分に入った関数を呼ぶ形)
        'test', 'apply', 'accept', 'get', 'run', 'call', 'compare',
        # メソッド参照(this::foo)経由で渡されたものの呼び出し
        'toMaster',
    }
    for m in re.finditer(r'(?<![\w.])(\w+)\s*\(', src):
        name = m.group(1)
        if name in NOT_METHODS or name in declared:
            continue
        if name[0].isupper():
            continue  # コンストラクタ・型名
        # 直前が . でない bare call は、同一クラスのメソッドか静的インポートのはず
        head = src[:m.start()].rstrip()
        if head.endswith('.') or head.endswith('::'):
            continue
        # 宣言行そのもの(戻り値の型が前にある)は除外済み。残ったものを報告する
        lineno = src.count('\n', 0, m.start()) + 1
        print(f'★ {f.name}:{lineno} 未解決のメソッド呼び出し {name}()')
        print('   このクラスに宣言が見つからない(宣言行が消えた可能性)')
        problems += 1

    # --- C) メソッド本体の長さ(飲み込みの副次的な兆候) ---
    starts = [(no, m.group('name')) for no, line in enumerate(lines, 1)
              if (m := METHOD_RE.match(line)) and len(m.group('indent')) == 4]
    closes = [no for no, line in enumerate(lines, 1) if line.rstrip() == '    }']
    for no, name in starts:
        end = next((c for c in closes if c > no), None)
        if end is None:
            print(f'★ {f.name}:{no} メソッド {name}() を閉じる "    }}" が見つかりません')
            problems += 1
            continue
        if end - no > 300:
            print(f'△ {f.name}:{no} メソッド {name}() が {end - no} 行あります(要確認)')
            problems += 1


if problems == 0:
    print('メソッド構造の異常は検出されませんでした')
sys.exit(1 if problems else 0)
