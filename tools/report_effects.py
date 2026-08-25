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

【「実装済み」の数え方】★Batch 47 で数え方を直した
2段階で見る。厳しい方から順に、

  登録あり  … CardEffectRegistry の9つの表のどれかにカードIDが登録されている
  宣言あり  … 表ではなくルール側の判定点に直接書かれている(RuleGuards・StatCalculator・
              GameService・GameActions・PlayerState・CardEffectRegistry の
              IMPLEMENTED_CARDS が自分で名乗ったもの。裁定164)
  未実装    … どちらでもない

★46b までは「どこかの Java にIDが書かれていれば実装済み」と数えていた。これは間違いで、
  DeckFactory(プリセットデッキのIDの羅列)まで実装として数えていた。そのせいで
  《百獣の王 ベヒーモス》のように<b>プリセットに入っているだけの未実装カード</b>が
  実装済みに化けていた。46b の裁定175(移行は既存の不整合を掘り出す)の続きである。

★★★このスクリプトが測らないもの(★Batch 64 で実害が出た)
  数えているのは「登録が<b>在るか</b>」であって「登録が<b>本文どおりか</b>」ではない。
  したがって、Ver1.1 でテキストが丸ごと差し替わったカードの実装が
  Ver0.4 のまま残っていても、このスクリプトは「未実装0枚」と言い続ける。
  実際に《不滅のネクロマンサー》(QTE-M-DARK-5)がそれだった ——
  P5(Batch 55〜59)の作り直しから抜け落ち、Ver1.1 に無い効果が 63 まで動いていた。
  ★<b>「未実装0枚」は「本文どおり」を意味しない。</b>本文との照合は人が読むしかない
  (Ver0.4 由来169枚のうち、作り直しの対象外とされた分は誰も突き合わせていない)。

★宣言の足し忘れは下の check_declarations() が検出して止まる。
  「ルール側のファイルにIDが書いてあるのに、どの IMPLEMENTED_CARDS にも載っていない」
  「IMPLEMENTED_CARDS に載っているのに、そのファイルのどこにも使われていない」の両方を見る。

★Batch 46b で Java のカードIDが台帳ID(QTE-0001 等)から Ver1.1 のID(QTE-M-<文明>-<番号>)へ
  機械変換された。したがって照合の鍵は ledgerCardId ではなく card['id'] である。

【「効果テキストあり」の数え方】
テキストから【…】と、その直後の注釈の丸括弧を取り除いて、句読点と空白しか残らないカードは
「キーワードだけのカード」であり、効果の登録は要らない(データが正しければ動く)。

★★この規則の正は Java の CardTextKeywords.hasEffectSentence である(Batch 47)。
  盤面に出る「効果未実装」の印はそちらが決めており、ここにあるのは<b>写し</b>である。
  同じ規則が2つの言語にあるのは裁定163 が戒めた形だが、
  (a) 印(=遊びに影響する判定)の正は Java ただ1つであり、
  (b) こちらが左右するのは進捗の数字だけである、
  という理由で許容している。数字がずれたら、正である Java 側に合わせること。
  なお【特殊召喚】の括弧は注釈ではなく発動条件そのものなので落とさない(Java と同じ)。

【使い方】
  python3 tools/report_effects.py                       # Markdown を標準出力へ
  python3 tools/report_effects.py --out notes/xxx.md    # ファイルへ書く
  python3 tools/report_effects.py --summary             # 集計だけ(一覧を出さない)
"""
import json
import re
import sys
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
# ★Batch 54: soulSpells(【賢魂：n】としての効果)を足した。
#   進化の素材条件(evolutions)と違い、これは「そのカードの効果そのもの」なので
#   Java 側の isRegistered() も数える(裁定233 との線引き)。
REGISTRY_MAPS = [
    'spellEffects', 'triggers', 'targetSpecs', 'specialSummons', 'leaderAbilities',
    'minionAbilities', 'enhancedCosts', 'ownMinionDestroyedWatchers', 'playConditions',
    'soulSpells',
]

CIV_ORDER = ['FIRE', 'WATER', 'WIND', 'LIGHT', 'DARK', 'EARTH', 'NONE']
TYPE_ORDER = ['LEADER', 'MINION', 'EVOLUTION', 'SPELL', 'WEAPON']


def load_cards():
    with open(CARDS / 'manual-cards.json', encoding='utf-8') as f:
        return json.load(f)['cards']


# ★Batch 60: normalized(本文の字面比較)は、使い手だった台帳との突き合わせごと削除した。


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
    # 表への登録がヘルパを通るもの。★ヘルパを増やしたらここにも足すこと
    # (足し忘れると「登録あり」を数え落とし、印が実装済みのカードに付いてしまう)
    # ★Batch 64: watchOwnMinionDestroyed は使い手を失って撤去された
    # (《不滅のネクロマンサー》を Ver1.1 の本文へ作り直したため)。
    for helper in ['register', 'watchAnyMinionDestroyed']:
        ids |= set(re.findall(r'\b%s\("(QTE-[\w-]+)"' % helper, src))
    return ids


def evolution_only_ids():
    """進化の素材条件としてだけ現れるカードID(★Batch 52)。

    ★<b>素材条件は「効果」ではなく「場に出す手段」である。</b>
      Batch 52 は進化18枚すべての素材条件を登録した(デッキ構築を解禁するため)が、
      効果まで実装したのはそのうち一部である。素材条件の登録を「実装あり」と数えると、
      効果が未実装の進化から<b>盤面の印が消えてしまう</b> ——
      だから Java の CardEffectRegistry.isRegistered() もこの表を見ない。

    ★その結果、CardEffectRegistry に現れるのに登録にも宣言にも無いIDが出る。
      下の check_declarations() の「足し忘れ」検査からは、この集合を外す。
      <b>外して安全なのは、印が付く側に倒れるからである</b> ——
      見落としがあっても「実装済みなのに印が付く」であって、その逆ではない。
    """
    src = REGISTRY.read_text(encoding='utf-8')
    return set(re.findall(r'\bregisterEvolution\("(QTE-[\w-]+)"', src))


# ルール側の判定点を持つクラス。ここに IMPLEMENTED_CARDS の宣言がある(裁定164)。
# ★このリストに載っていない .java にカードIDが現れたら、下の check_declarations() が止める。
RULE_SIDE_FILES = [
    'com/example/qte/effect/CardEffectRegistry.java',
    'com/example/qte/effect/RuleGuards.java',
    'com/example/qte/effect/StatCalculator.java',
    'com/example/qte/game/GameService.java',
    'com/example/qte/game/GameActions.java',
    'com/example/qte/game/PlayerState.java',
]

# プリセットデッキの中身。カードIDが並ぶが、これは「実装」ではなく「デッキの内容」である。
# ★実在するIDかの検査は tools/check_all.py 項目3 と DeckValidatorTest が行う。
DECK_CONTENT_FILES = ['com/example/qte/game/DeckFactory.java']

BLOCK_COMMENT = re.compile(r'/\*.*?\*/', re.S)
LINE_COMMENT = re.compile(r'//[^\n]*')
CONSTANT_DECL = re.compile(r'static\s+final\s+String\s+(\w+)\s*=\s*"(QTE-[\w-]+)"')
IMPLEMENTED_BLOCK = re.compile(r'IMPLEMENTED_CARDS\s*=[^;]*?Set\.of\((.*?)\)\s*;', re.S)


def without_comments(src):
    """コメントを取り除いたソース。

    ★注釈でカード名やIDに触れているだけの行を「実装」と数えないため。
      46b までの数え方はコメントも拾っており、実装の所在を正しく指していなかった。
    """
    return LINE_COMMENT.sub('', BLOCK_COMMENT.sub('', src))


def declared_ids():
    """ルール側の各クラスが IMPLEMENTED_CARDS で名乗ったカードIDの集合。

    宣言は定数名で書かれていることがあるので、同じファイルの定数宣言で解決する。
    ★ここで「別ファイルの一覧」を作らない —— 読んでいるのは実装のあるファイルそのものである。
    """
    ids = set()
    for rel in RULE_SIDE_FILES:
        ids |= declared_in(JAVA / rel)[0]
    return ids


def declared_in(path):
    """(宣言されたID, そのファイルの定数名→ID) を返す。"""
    src = path.read_text(encoding='utf-8')
    constants = dict((name, value) for name, value in CONSTANT_DECL.findall(src))
    block = IMPLEMENTED_BLOCK.search(without_comments(src))
    if not block:
        raise SystemExit('NG: %s に IMPLEMENTED_CARDS の宣言が見つからない' % path.name)
    ids = set()
    for token in re.split(r'[,\s]+', block.group(1).strip()):
        if not token:
            continue
        if token.startswith('"'):
            ids.add(token.strip('"'))
        elif token in constants:
            ids.add(constants[token])
        else:
            raise SystemExit('NG: %s の IMPLEMENTED_CARDS に解決できない要素がある: %s'
                             % (path.name, token))
    return ids, constants


def check_declarations(registered):
    """宣言の過不足を両方向から検める(★Batch 47 の番人)。

    1. ルール側のファイルにIDが書いてあるのに、登録にもどの宣言にも無い → 足し忘れ
    2. 宣言にあるのに、そのファイルの宣言以外の場所で1度も使われていない → 宣言が古い
    3. ルール側でもデッキ内容でもないファイルにカードIDが書かれている → 置き場所が想定外

    ★1 は<b>全クラスの宣言の和</b>と比べる。1枚のカードの実装が2つのクラスに分かれることが
      実際にある(風護の杖は GameService が発火し、CardEffectRegistry が選択を解決する)。
      「どのファイルで名乗るか」ではなく「どこかで名乗っているか」を問うのが正しい。
    ★2 はファイルごとに見る。宣言したクラスがその実装を捨てたことは、そのクラスでしか分からない。
    """
    problems = []
    known = set(RULE_SIDE_FILES) | set(DECK_CONTENT_FILES)
    everywhere = declared_ids()
    evolution_only = evolution_only_ids()  # ★Batch 52: 素材条件は効果の実装ではない
    for path in sorted(JAVA.rglob('*.java')):
        rel = path.relative_to(JAVA).as_posix()
        body = without_comments(path.read_text(encoding='utf-8'))
        found = set(CARD_ID.findall(body))
        if not found:
            continue
        if rel in DECK_CONTENT_FILES:
            continue
        if rel not in known:
            problems.append('%s にカードIDが書かれている(%d種)。ルール側なら RULE_SIDE_FILES に足し、'
                            'IMPLEMENTED_CARDS を宣言すること' % (rel, len(found)))
            continue
        declared, constants = declared_in(path)
        # 1. 足し忘れ
        for card_id in sorted(found - everywhere - registered - evolution_only):
            problems.append('%s: %s が登録にも、どのクラスの IMPLEMENTED_CARDS にも無い'
                            % (rel, card_id))
        # 2. 宣言が古い(宣言の行を取り除いても、なおそのIDが使われているか)
        outside = IMPLEMENTED_BLOCK.sub('', body)
        used = set(CARD_ID.findall(outside))
        for name, value in constants.items():
            if value in declared and re.search(r'\b%s\b' % name, outside):
                used.add(value)
        for card_id in sorted(declared - used):
            problems.append('%s: %s を IMPLEMENTED_CARDS が名乗っているが、'
                            'このファイルのどこにも使われていない' % (rel, card_id))
    if problems:
        raise SystemExit('NG: 効果の実装の宣言に食い違いがある\n  - ' + '\n  - '.join(problems))


# キーワード表記の直後に続く注釈の丸括弧(【威圧】(相手の攻撃対象にならない) など)。
# ★【特殊召喚】だけは括弧の中身が発動条件そのものなので落とさない(Java と同じ規則)。
NOTE_KEYWORDS = ['守護', '潜伏', '突進', '速攻', '威圧', '貫通', '知識', '還元']
TRAILING_NOTE = re.compile(r'【(?:%s)】\s*[（(][^）)]*[）)]' % '|'.join(NOTE_KEYWORDS))
NO_EFFECT = re.compile(r'効果\s*なし')


def has_sentence(text):
    """【…】と注釈の括弧を取り除いて、文が残るか(= 効果の登録が要りそうか)。

    ★正は Java の CardTextKeywords.hasEffectSentence である(冒頭の注記を参照)。
    """
    if not text:
        return False
    rest = NO_EFFECT.sub('', BRACKET.sub('', TRAILING_NOTE.sub('', text)))
    return bool(NOISE.sub('', rest))


def classify(card, registered, declared):
    card_id = card['id']
    if card_id in registered:
        return '登録あり'
    if card_id in declared:
        return '宣言あり'
    return '未実装'


def main():
    args = sys.argv[1:]
    cards = load_cards()
    registered = registered_ids()
    check_declarations(registered)
    declared = declared_ids()

    rows = []
    for card in cards:
        rows.append({
            'id': card['id'],
            'name': card['name'],
            'type': card['type'],
            'civ': card['civilization'],
            'new': card.get('ledgerCardId') is None,
            'sentence': has_sentence(card.get('text')),
            'state': classify(card, registered, declared),
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
    partial = [r for r in need if r['state'] == '宣言あり']
    todo = [r for r in need if r['state'] == '未実装']
    w('| 区分 | 枚数 |')
    w('|---|---|')
    w('| Ver1.1 全カード | %d |' % total)
    w('| うちキーワードのみ(効果の登録は不要) | %d |' % len(kw_only))
    w('| うち効果の文がある(登録が要る) | %d |' % len(need))
    w('| ├ 登録あり | %d |' % len(done))
    w('| ├ 宣言あり(表ではなくルール側に実装) | %d |' % len(partial))
    w('| └ **未実装** | **%d** |' % len(todo))
    w('')
    w('新カード(Ver0.4 に由来を持たない) %d 枚のうち、効果の文があるのは %d 枚。'
      % (len([r for r in rows if r['new']]),
         len([r for r in rows if r['new'] and r['sentence']])))
    w('')

    w('## 文明別(効果の文があるカードのみ)')
    w('')
    w('| 文明 | 要実装 | 登録あり | 宣言あり | 未実装 |')
    w('|---|---|---|---|---|')
    for civ in CIV_ORDER:
        sub = [r for r in need if r['civ'] == civ]
        if not sub:
            continue
        c = Counter(r['state'] for r in sub)
        w('| %s | %d | %d | %d | %d |'
          % (civ, len(sub), c['登録あり'], c['宣言あり'], c['未実装']))
    w('')

    w('## 種別別(効果の文があるカードのみ)')
    w('')
    w('| 種別 | 要実装 | 登録あり | 宣言あり | 未実装 |')
    w('|---|---|---|---|---|')
    for typ in TYPE_ORDER:
        sub = [r for r in need if r['type'] == typ]
        if not sub:
            continue
        c = Counter(r['state'] for r in sub)
        w('| %s | %d | %d | %d | %d |'
          % (typ, len(sub), c['登録あり'], c['宣言あり'], c['未実装']))
    w('')

    # ★Batch 60: 「本文が台帳と異なるカード(作り直しの候補)」の節は削除した。
    #   数えるのに Ver0.4 台帳(qte-cards.json)が要るが、区分5 が終わったので
    #   台帳ごと消してある。作り直し(P5)は Batch 59 で121枚すべてを消化して完了しており、
    #   何枚残っているかを毎回数え直す相手はもう居ない。
    #   当時の内訳は notes/rework-triage.md に記録として残っている。

    if '--summary' not in args:
        w('## 未実装の一覧(★盤面で「効果未実装」の印が付くカード)')
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
