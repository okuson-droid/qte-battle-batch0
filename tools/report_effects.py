#!/usr/bin/env python3
"""
Ver1.1 全235枚の「効果の実装状況」を数える(Batch 46a)。

【このスクリプトが要る理由】
Ver1.1 対応(P1〜P5)は 163枚の効果を作る長い作業である。途中で
「あと何枚残っているか」が分からなくなると、終わりが見えないまま進むことになる。
数字をいつでも出せるようにしておくのが目的である。

★数え方の正はコードであり、この表ではない。読んでいるのは
  - src/main/resources/cards/manual-cards.json  (カードの正)
  - src/main/java/com/example/qte/effect/CardEffectRegistry.java (効果の登録の正)
  - src/main/java/**.java (登録以外の場所に書かれた挙動)
であり、途中に人が書いた一覧を挟まない。

【「実装済み」の数え方】
2段階で見る。厳しい方から順に、

  登録あり  … CardEffectRegistry の9つの表のどれかにカードIDが登録されている
  参照あり  … 登録は無いが、どこかの Java にカードIDが書かれている
              (RuleGuards・StatCalculator・GameService のように、表ではなく
               ルール側に直接書かれた挙動。これも実装ではある)
  未実装    … カードIDがコードのどこにも現れない

★Batch 46b で Java のカードIDが台帳ID(QTE-0001 等)から Ver1.1 のID(QTE-M-<文明>-<番号>)へ
  機械変換された。したがって照合の鍵は ledgerCardId ではなく card['id'] である。

【「効果テキストあり」の数え方】
テキストから【…】をすべて取り除いて、句読点と空白しか残らないカードは
「キーワードだけのカード」であり、効果の登録は要らない(データが正しければ動く)。
★ここで見ているのは「文が残るかどうか」だけである。どの【】がそのカード自身の
  キーワードかを判定する規則は Java 側(CardTextKeywords)にあり、こちらには書かない
  —— 同じ規則を2つの言語に置くと、必ず片方だけが直される日が来る(裁定130)。

【使い方】
  python3 tools/report_effects.py                       # Markdown を標準出力へ
  python3 tools/report_effects.py --out notes/xxx.md    # ファイルへ書く
  python3 tools/report_effects.py --summary             # 集計だけ(一覧を出さない)
"""
import json
import re
import sys
import unicodedata
from collections import Counter, OrderedDict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CARDS = ROOT / 'src/main/resources/cards'
JAVA = ROOT / 'src/main/java'
REGISTRY = JAVA / 'com/example/qte/effect/CardEffectRegistry.java'

# ★46b: Java に書かれるのは Ver1.1 のカードID である(台帳IDは1つも残っていない)
CARD_ID = re.compile(r'QTE-M-[A-Z]+-\d+')
BRACKET = re.compile(r'【[^】]*】')
NOISE = re.compile(r'[\s。、,\.]+')

# CardEffectRegistry が持つ「カードID → 効果」の表。ここに載っていない表が現れたら止める。
REGISTRY_MAPS = [
    'spellEffects', 'triggers', 'targetSpecs', 'specialSummons', 'leaderAbilities',
    'minionAbilities', 'enhancedCosts', 'ownMinionDestroyedWatchers', 'playConditions',
]

CIV_ORDER = ['FIRE', 'WATER', 'WIND', 'LIGHT', 'DARK', 'EARTH', 'NONE']
TYPE_ORDER = ['LEADER', 'MINION', 'EVOLUTION', 'SPELL', 'WEAPON']


def load_cards():
    with open(CARDS / 'manual-cards.json', encoding='utf-8') as f:
        return json.load(f)['cards']


def load_ledger():
    with open(CARDS / 'qte-cards.json', encoding='utf-8') as f:
        return {c['id']: c for c in json.load(f)['cards']}


def normalized(text):
    """本文の比較用。全角半角と句読点・空白の違いだけを消す。

    ★意味の違いまでは判定できない。ここで数えられるのは「字面が違う」までであり、
      それが遊びに効く変更かどうかは人が読むしかない。
    """
    return re.sub(r'[\s。、,\.]+', '', unicodedata.normalize('NFKC', text or ''))


def registered_ids():
    """CardEffectRegistry に登録されているカードIDの集合。

    ★左辺(どの表に入れたか)で判定する。ソース中に現れる ID を素朴に拾うと、
      healLeader(..., "QTE-0002") のような「効果の出どころ」の注記まで
      登録として数えてしまう。
    """
    src = REGISTRY.read_text(encoding='utf-8')

    # 未知の表への put を見逃さない(表が増えたのに数え漏れる、を防ぐ)
    unknown = set()
    for name in re.findall(r'(\w+)\.put\("QTE-', src):
        if name not in REGISTRY_MAPS:
            unknown.add(name)
    if unknown:
        raise SystemExit('NG: 未知の登録先がある(REGISTRY_MAPS に足すこと): %s'
                         % ', '.join(sorted(unknown)))

    ids = set()
    for name in REGISTRY_MAPS:
        ids |= set(re.findall(re.escape(name) + r'\.put\("(QTE-[\w-]+)"', src))
    # triggers への登録は register(...) ヘルパを通る
    ids |= set(re.findall(r'\bregister\("(QTE-[\w-]+)",\s*TriggerType\.', src))
    return ids


def referenced_ids():
    """Java のどこかに書かれているカードIDの集合(登録も含む)。"""
    ids = set()
    for path in sorted(JAVA.rglob('*.java')):
        ids |= set(CARD_ID.findall(path.read_text(encoding='utf-8')))
    return ids


def has_sentence(text):
    """【…】を取り除いて文が残るか(= 効果の登録が要りそうか)。"""
    if not text:
        return False
    return bool(NOISE.sub('', BRACKET.sub('', text)))


def classify(card, registered, referenced):
    card_id = card['id']
    if card_id in registered:
        return '登録あり'
    if card_id in referenced:
        return '参照あり'
    return '未実装'


def main():
    args = sys.argv[1:]
    cards = load_cards()
    registered = registered_ids()
    referenced = referenced_ids()

    rows = []
    for card in cards:
        rows.append({
            'id': card['id'],
            'name': card['name'],
            'type': card['type'],
            'civ': card['civilization'],
            'new': card.get('ledgerCardId') is None,
            'sentence': has_sentence(card.get('text')),
            'state': classify(card, registered, referenced),
        })

    out = []
    w = out.append
    w('# Ver1.1 効果の実装状況')
    w('')
    w('`tools/report_effects.py` が生成。数え方はスクリプトの冒頭を参照。')
    w('')
    w('## 全体')
    w('')
    total = len(rows)
    need = [r for r in rows if r['sentence']]
    kw_only = [r for r in rows if not r['sentence']]
    done = [r for r in need if r['state'] == '登録あり']
    partial = [r for r in need if r['state'] == '参照あり']
    todo = [r for r in need if r['state'] == '未実装']
    w('| 区分 | 枚数 |')
    w('|---|---|')
    w('| Ver1.1 全カード | %d |' % total)
    w('| うちキーワードのみ(効果の登録は不要) | %d |' % len(kw_only))
    w('| うち効果の文がある(登録が要る) | %d |' % len(need))
    w('| ├ 登録あり | %d |' % len(done))
    w('| ├ 参照あり(表ではなくルール側に実装) | %d |' % len(partial))
    w('| └ **未実装** | **%d** |' % len(todo))
    w('')
    w('新カード(台帳に無い) %d 枚のうち、効果の文があるのは %d 枚。'
      % (len([r for r in rows if r['new']]),
         len([r for r in rows if r['new'] and r['sentence']])))
    w('')

    w('## 文明別(効果の文があるカードのみ)')
    w('')
    w('| 文明 | 要実装 | 登録あり | 参照あり | 未実装 |')
    w('|---|---|---|---|---|')
    for civ in CIV_ORDER:
        sub = [r for r in need if r['civ'] == civ]
        if not sub:
            continue
        c = Counter(r['state'] for r in sub)
        w('| %s | %d | %d | %d | %d |'
          % (civ, len(sub), c['登録あり'], c['参照あり'], c['未実装']))
    w('')

    w('## 種別別(効果の文があるカードのみ)')
    w('')
    w('| 種別 | 要実装 | 登録あり | 参照あり | 未実装 |')
    w('|---|---|---|---|---|')
    for typ in TYPE_ORDER:
        sub = [r for r in need if r['type'] == typ]
        if not sub:
            continue
        c = Counter(r['state'] for r in sub)
        w('| %s | %d | %d | %d | %d |'
          % (typ, len(sub), c['登録あり'], c['参照あり'], c['未実装']))
    w('')

    ledger = load_ledger()
    changed = [c for c in cards
               if c.get('ledgerCardId')
               and normalized(c['text']) != normalized(ledger[c['ledgerCardId']]['text'])]
    changed_impl = [c for c in changed if c['id'] in registered]
    w('## 本文が台帳と異なるカード(作り直しの候補)')
    w('')
    w('| 区分 | 枚数 |')
    w('|---|---|')
    w('| 台帳と対応づくカード | %d |' % len([c for c in cards if c.get('ledgerCardId')]))
    w('| うち本文が異なる(全角半角・句読点の違いを除く) | %d |' % len(changed))
    w('| うち効果が登録済み = **作り直しの対象** | %d |' % len(changed_impl))
    w('')
    w('★これは字面の比較である。表記を整えただけのものも含まれるため、'
      '遊びに効く変更が何枚かは人が読んで決める'
      '(2026-08-16 時点の読み合わせでは実質変更86・うち実装済み69。'
      '`notes/ver11-migration-plan.md` 0-2)。')
    w('')

    if '--summary' not in args:
        w('## 未実装の一覧(効果の文があるのに、コードのどこにも現れないカード)')
        w('')
        by_civ = OrderedDict((civ, []) for civ in CIV_ORDER)
        for r in todo:
            by_civ[r['civ']].append(r)
        for civ, sub in by_civ.items():
            if not sub:
                continue
            w('### %s (%d枚)' % (civ, len(sub)))
            w('')
            w('| ID | 名前 | 種別 | 新カード |')
            w('|---|---|---|---|')
            for r in sub:
                w('| %s | %s | %s | %s |'
                  % (r['id'], r['name'], r['type'], '★' if r['new'] else ''))
            w('')

    text = '\n'.join(out) + '\n'
    if '--out' in args:
        path = ROOT / args[args.index('--out') + 1]
        path.write_text(text, encoding='utf-8')
        print('書き出した: %s' % path, file=sys.stderr)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == '__main__':
    sys.exit(main())
