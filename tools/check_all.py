#!/usr/bin/env python3
"""
納品前の機械チェック(引き継ぎ書3章)のうち、項目 1・3・5・6 をまとめて実行する。

  1. package 宣言とディレクトリの一致
  3. コード中のカードIDが台帳に実在するか
  5. メソッド参照の解決(actions. effects. stats. guards. の呼び出し先が存在するか)
  6. デッキプリセットの合計枚数と同名制限
"""
import json
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else '.')
JAVA = ROOT / 'src/main/java'
CARDS = ROOT / 'src/main/resources/cards/qte-cards.json'

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
print('=== 3. コード中のカードIDが台帳に実在するか ===')
ledger = json.loads(CARDS.read_text(encoding='utf-8'))
known = {c['id'] for c in ledger['cards']}
print(f'  台帳のカード数: {len(known)}')
bad = 0
seen_ids = set()
for f in sorted(ROOT.rglob('*.java')):
    src = f.read_text(encoding='utf-8')
    for m in re.finditer(r'"(QTE-[\w]+)"', src):
        cid = m.group(1)
        seen_ids.add(cid)
        if cid not in known:
            lineno = src.count('\n', 0, m.start()) + 1
            print(f'  ★ 台帳に無いカードID: {cid}  {f.name}:{lineno}')
            bad += 1
print(f'  コード中で参照されているカードID: {len(seen_ids)} 種')
print(f'  → 台帳に無いID {bad} 件')
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
# 6. デッキプリセットの合計枚数と同名制限
# ---------------------------------------------------------------
print()
print('=== 6. デッキプリセットの合計枚数と同名制限 ===')
deck_src = (JAVA / 'com/example/qte/game/DeckFactory.java').read_text(encoding='utf-8')
UNLIMITED = {'QTE-0012'}  # ゾンストライカー(テキストで4枚制限を上書き)
bad = 0
# DeckFactory はプリセットを静的マップで持つ。XXX.put("QTE-xxxx", n) を定数名ごとに集計する
presets = {}
for m in re.finditer(r'(\w+)\.put\(\s*"(QTE-[\w]+)"\s*,\s*(\d+)\s*\)', deck_src):
    presets.setdefault(m.group(1), Counter())[m.group(2)] += int(m.group(3))
if not presets:
    print('  ★ プリセットを1件も検出できませんでした(DeckFactoryの書式が変わった可能性)')
    bad += 1
for name, counts in sorted(presets.items()):
    total = sum(counts.values())
    is_taboo = 'TABOO' in name.upper()
    expected = 8 if is_taboo else 40
    limit = 1 if is_taboo else 4
    status = 'OK' if total == expected else '★'
    if total != expected:
        bad += 1
    print(f'  {status} {name}: 合計 {total} 枚 (期待 {expected})')
    for cid, cnt in counts.items():
        if cnt > limit and cid not in UNLIMITED:
            print(f'      ★ 同名制限違反: {cid} が {cnt} 枚 (上限 {limit})')
            bad += 1
print(f'  → 問題 {bad} 件')
if bad:
    failures.append('デッキプリセット')

print()
print('=' * 50)
if failures:
    print('要確認の項目:', ', '.join(failures))
    sys.exit(1)
print('項目 1・3・5・6 はすべてパスしました')
