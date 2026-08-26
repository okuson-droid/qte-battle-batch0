#!/usr/bin/env python3
"""
納品前の機械チェック(引き継ぎ書3章)のうち、項目 1・3・5・6 をまとめて実行する。

  1. package 宣言とディレクトリの一致
  3. コード中のカードIDがカードマスタに実在するか
  5. メソッド参照の解決(actions. effects. stats. guards. の呼び出し先が存在するか)
  6. デッキプリセットが退役していること(★Batch 66)
"""
import json
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else '.')
JAVA = ROOT / 'src/main/java'
# ★Batch 46b: カードマスタの正が台帳 qte-cards.json から manual-cards.json へ移った
CARDS = ROOT / 'src/main/resources/cards/manual-cards.json'

failures = []


def strip_java_noise(src):
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
        else:
            out.append(src[i])
            i += 1
    return ''.join(out)


# ---------------------------------------------------------------
# 1. package 宣言とディレクトリの一致
# ---------------------------------------------------------------
print('=== 1. package 宣言とディレクトリの一致 ===')
bad = 0
for f in sorted(JAVA.rglob('*.java')):
    src = f.read_text(encoding='utf-8')
    m = re.search(r'^\s*package\s+([\w.]+)\s*;', src, re.M)
    if not m:
        print(f'  ★ package 宣言なし: {f}')
        bad += 1
        continue
    expected = str(f.parent.relative_to(JAVA)).replace('/', '.')
    if m.group(1) != expected:
        print(f'  ★ 不一致: {f}  宣言={m.group(1)} 実際={expected}')
        bad += 1
print(f'  → 不一致 {bad} 件')
if bad:
    failures.append('package宣言')

# ---------------------------------------------------------------
# 3. コード中のカードIDが台帳に実在するか
# ---------------------------------------------------------------
print()
print('=== 3. コード中のカードIDがカードマスタに実在するか ===')
master = json.loads(CARDS.read_text(encoding='utf-8'))
known = {c['id'] for c in master['cards']}
print(f'  カードマスタの枚数: {len(known)}')
bad = 0
seen_ids = set()
# ★Batch 46b: 走査の範囲を src/main/java に絞った。
# テストは「存在しないカードID」をわざと書く(退役した台帳IDで引けないこと・
# 未知IDが弾かれることの確認)。テストまで見ると、そうした否定の試験を書くたびに
# 例外の一覧が要る = 2つ目の正ができる。★本番は実在するIDしか名指ししてはならず、
# テストの偽IDは実行時に findById が落として教えてくれる。守るべき線はそこである。
for f in sorted(JAVA.rglob('*.java')):
    src = f.read_text(encoding='utf-8')
    for m in re.finditer(r'"(QTE-[\w-]+)"', src):
        cid = m.group(1)
        seen_ids.add(cid)
        if cid not in known:
            lineno = src.count('\n', 0, m.start()) + 1
            print(f'  ★ カードマスタに無いカードID: {cid}  {f.name}:{lineno}')
            bad += 1
print(f'  コード中で参照されているカードID: {len(seen_ids)} 種')
print(f'  → カードマスタに無いID {bad} 件')
if bad:
    failures.append('カードID')

# ---------------------------------------------------------------
# 5. メソッド参照の解決
# ---------------------------------------------------------------
print()
print('=== 5. メソッド参照の解決(actions./effects./stats./guards.) ===')
OWNERS = {
    'actions': 'game/GameActions.java',
    'effects': 'effect/CardEffectRegistry.java',
    'stats': 'effect/StatCalculator.java',
    'guards': 'effect/RuleGuards.java',
}
methods = {}
for var, rel in OWNERS.items():
    src = strip_java_noise((JAVA / 'com/example/qte' / rel).read_text(encoding='utf-8'))
    names = set(re.findall(r'\b(?:public|private|protected)\s+[\w<>\[\], .]+?\s+(\w+)\s*\(', src))
    methods[var] = names
    print(f'  {var} ({Path(rel).name}): メソッド {len(names)} 個')

bad = 0
for f in sorted(JAVA.rglob('*.java')):
    src = strip_java_noise(f.read_text(encoding='utf-8'))
    for var, names in methods.items():
        # ctx.actions().foo(  と  actions.foo(  の両方を拾う
        for pat in (rf'\b{var}\(\)\.(\w+)\s*\(', rf'(?<![\w.]){var}\.(\w+)\s*\('):
            for m in re.finditer(pat, src):
                name = m.group(1)
                if name not in names:
                    lineno = src.count('\n', 0, m.start()) + 1
                    print(f'  ★ 未解決: {var}.{name}()  {f.name}:{lineno}')
                    bad += 1
print(f'  → 未解決 {bad} 件')
if bad:
    failures.append('メソッド参照')

# ---------------------------------------------------------------
# 6. ★★Batch 66: デッキプリセットの検査を退役させた
#
#   65 まで、この節は DeckFactory の静的マップ(6文明ぶんのスターター40枚 +
#   禁忌2本)を読み、合計枚数と同名制限を数えていた。
#
#   ★66 でプリセットデッキそのものが退役した。通常モードは<b>デッキファイル必須</b>になり、
#     配るデッキが1本も無くなったためである(マスター指示)。
#   ★数える相手が消えた検査は、残しても「プリセットを1件も検出できませんでした」と
#     言い続けるだけである。使い手を失った器はそのバッチで撤去する(裁定178)。
#
#   ★<b>失われた保証</b>: 「40枚・同名4枚以内」をコードのデータに対して数える場所は
#     これで無くなった。ただし同じ規則は DeckValidator が<b>実際に持ち込まれる
#     デッキファイル全部</b>に当てており(そちらが本番の門である)、
#     DeckValidatorTest / Batch66LobbyTest が本物の入口から測っている。
# ---------------------------------------------------------------
print()
print('=== 6. デッキプリセット(★Batch 66 で退役) ===')
deck_src = (JAVA / 'com/example/qte/game/DeckFactory.java').read_text(encoding='utf-8')
leftovers = re.findall(r'(\w+)\.put\(\s*"(QTE-[\w-]+)"\s*,\s*(\d+)\s*\)', deck_src)
if leftovers:
    # ★退役の消し残りを見張る番人に作り替えた。「無いこと」を測る側に回っている
    print(f'  ★ DeckFactory にプリセットの記述が {len(leftovers)} 行残っている')
    failures.append('デッキプリセットの消し残り')
else:
    print('  OK: プリセットは退役済み(デッキはファイルから読む)')

print()
print('=' * 50)
if failures:
    print('要確認の項目:', ', '.join(failures))
    sys.exit(1)
print('項目 1・3・5・6 はすべてパスしました')
