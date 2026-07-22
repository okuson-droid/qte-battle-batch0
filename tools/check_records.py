#!/usr/bin/env python3
"""
record のコンポーネント数と new 呼び出しの引数の数が一致するかを検証する。

Batch 12a では TargetSpec / CardView / MinionView / PlayerView / EffectContext に
コンポーネントを追加したため、既存の呼び出しに漏れがあると必ずコンパイルエラーになる。
javac が使えない環境のため、ここで機械的に洗う。

引数の数え方は「トップレベルのカンマの数 + 1」。
入れ子の括弧・ジェネリクス・ラムダ・文字列の中のカンマは数えない。
"""
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else '.')


def strip_java_noise(src):
    """文字列・文字リテラル・コメントを空白に潰す(位置は保つ)。"""
    out = []
    i, n = 0, len(src)
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


def find_record_components(src, name):
    """record 宣言のコンポーネント数を返す。見つからなければ None。"""
    m = re.search(r'\brecord\s+' + re.escape(name) + r'\s*\(', src)
    if not m:
        return None
    start = m.end()
    depth, i, n = 1, start, len(src)
    while i < n and depth:
        if src[i] in '(<':
            depth += 1
        elif src[i] in ')>':
            depth -= 1
        i += 1
    body = src[start:i - 1]
    return count_top_level_args(body)


def count_top_level_args(body):
    if body.strip() == '':
        return 0
    # ラムダ矢印や比較演算子に含まれる < > を、ジェネリクスの括弧と誤認しないよう先に潰す。
    # これを忘れると `->` の > で深度が下がり、以降のカンマを数え損ねる
    for op in ('->', '=>', '<=', '>=', '<<', '>>', '&&', '||'):
        body = body.replace(op, '  ')
    depth = 0
    count = 1
    for ch in body:
        if ch in '([{<':
            depth += 1
        elif ch in ')]}>':
            depth -= 1
        elif ch == ',' and depth == 0:
            count += 1
    return count


def find_new_calls(src, name):
    """new Name(...) の引数の数を (lineno, argc) で返す。"""
    results = []
    for m in re.finditer(r'\bnew\s+(?:[\w.]+\.)?' + re.escape(name) + r'\s*\(', src):
        start = m.end()
        depth, i, n = 1, start, len(src)
        while i < n and depth:
            if src[i] in '([{':
                depth += 1
            elif src[i] in ')]}':
                depth -= 1
            i += 1
        body = src[start:i - 1]
        lineno = src.count('\n', 0, m.start()) + 1
        results.append((lineno, count_top_level_args(body)))
    return results


TARGETS = [
    ('TargetSpec', None),
    ('CardView', None),
    ('MinionView', None),
    ('PlayerView', None),
    ('EffectContext', None),
    ('PendingChoice', None),
    ('StatModifier', None),
    ('MinionAbilitySpec', None),
    ('LeaderAbilitySpec', None),
    ('EnhancedCostSpec', None),
]

files = sorted(ROOT.rglob('*.java'))
sources = {f: strip_java_noise(f.read_text(encoding='utf-8')) for f in files}

bad = 0
for name, _ in TARGETS:
    components = None
    for f, src in sources.items():
        c = find_record_components(src, name)
        if c is not None:
            components = c
            decl_file = f
            break
    if components is None:
        print(f'[skip] record {name} の宣言が見つかりません')
        continue
    print(f'record {name}: コンポーネント {components} 個 ({decl_file.name})')
    for f, src in sources.items():
        for lineno, argc in find_new_calls(src, name):
            # 追加コンストラクタ(オーバーロード)を許容するため、
            # 一致しないものは警告として出し、人が確認する
            mark = 'OK ' if argc == components else '★不一致'
            if argc != components:
                bad += 1
                print(f'  {mark} {f.name}:{lineno} 引数 {argc} 個')
print()
print('不一致(要確認):', bad, '件')
print()
print('注: 次の2つは不一致として出るが誤りではない。')
print('  1) オーバーロードされたコンストラクタの呼び出し')
print('     (EffectContext の7引数版は、強化使用フラグを持たない文脈のための追加コンストラクタ)')
print('  2) 引数の中に単独の比較演算子 ( a > b ) がある呼び出し。')
print('     このスクリプトは < > をジェネリクスの括弧として数えるため深度がずれる。')
print('     不一致が出たら必ず該当行を目視し、上記のどちらかであることを確認すること。')
