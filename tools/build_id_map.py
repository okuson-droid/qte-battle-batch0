#!/usr/bin/env python3
"""
台帳ID(QTE-0001 等) と Ver1.1 のカードID(QTE-M-<文明>-<番号>) の対応表を作る。

【このスクリプトが要る理由】
Ver1.1 移行(裁定D1)では、通常モードのカードマスタを台帳 qte-cards.json から
manual-cards.json へ差し替え、Java 側にベタ書きされた台帳IDを機械的に書き換える。
その書き換えの材料になるのが、この対応表である。

対応の出どころは manual-cards.json の ledgerCardId フィールド1つだけである
(対応表をここで組み立てて別ファイルに保存しない —— 正はカード定義ファイルである)。

【検証】
書き換えの前提が崩れていないことを、出力の前に必ず確かめる。1つでも崩れていれば
終了コード1で止まる。黙って一部だけ変換された状態がいちばん危ない。

  1. ledgerCardId は重複しない(2枚の Ver1.1 カードが同じ台帳カードを指さない)
  2. ledgerCardId は台帳に実在する
  3. 台帳の全カードが、ちょうど1枚の Ver1.1 カードから指されている
  4. Java にベタ書きされた台帳IDが、すべて変換先を持つ

【使い方】
  python3 tools/build_id_map.py            # 検証して対応表を TSV で標準出力へ
  python3 tools/build_id_map.py --check    # 検証だけ(出力しない)
  python3 tools/build_id_map.py --sed      # sed のスクリプト形式で出す(46b の機械変換用)
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CARDS = ROOT / 'src/main/resources/cards'
JAVA = ROOT / 'src/main/java'

LEDGER_ID = re.compile(r'QTE-(?:\d{4}|L\d{3}|X\d{3})')


def load(name):
    with open(CARDS / name, encoding='utf-8') as f:
        return json.load(f)['cards']


def java_sources():
    return sorted(JAVA.rglob('*.java'))


def build():
    ledger = {c['id']: c for c in load('qte-cards.json')}
    ver11 = load('manual-cards.json')

    errors = []
    mapping = {}
    for card in ver11:
        ledger_id = card.get('ledgerCardId')
        if ledger_id is None:
            continue
        if ledger_id in mapping:
            errors.append('台帳ID %s を %s と %s の2枚が指している'
                          % (ledger_id, mapping[ledger_id]['id'], card['id']))
            continue
        if ledger_id not in ledger:
            errors.append('%s の ledgerCardId が台帳に無い: %s' % (card['id'], ledger_id))
            continue
        mapping[ledger_id] = card

    unreferenced = sorted(set(ledger) - set(mapping))
    if unreferenced:
        errors.append('どの Ver1.1 カードからも指されていない台帳カード %d 件: %s'
                      % (len(unreferenced), ', '.join(unreferenced)))

    # Java にベタ書きされた台帳IDが、すべて変換先を持つか
    hardcoded = set()
    for path in java_sources():
        hardcoded |= set(LEDGER_ID.findall(path.read_text(encoding='utf-8')))
    missing = sorted(hardcoded - set(mapping))
    if missing:
        errors.append('Java にあるが変換先が無い台帳ID %d 件: %s'
                      % (len(missing), ', '.join(missing)))

    return ledger, ver11, mapping, hardcoded, errors


def main():
    args = set(sys.argv[1:])
    ledger, ver11, mapping, hardcoded, errors = build()

    print('# 台帳 %d 枚 / Ver1.1 %d 枚 / 対応 %d 組 / 新カード %d 枚'
          % (len(ledger), len(ver11), len(mapping),
             len(ver11) - len(mapping)), file=sys.stderr)
    print('# Java にベタ書きされた台帳ID: %d 種' % len(hardcoded), file=sys.stderr)

    if errors:
        print('', file=sys.stderr)
        for e in errors:
            print('NG: %s' % e, file=sys.stderr)
        return 1
    print('OK: 対応は全単射で、Java の全IDに変換先がある', file=sys.stderr)

    if '--check' in args:
        return 0

    # 出力は台帳IDの昇順。長いIDから置換しないと前方一致で壊れる、という事故は
    # 台帳IDが固定長(QTE-0000 / QTE-L000 / QTE-X000)なので起きない。
    for ledger_id in sorted(mapping):
        card = mapping[ledger_id]
        if '--sed' in args:
            print('s/%s/%s/g' % (ledger_id, card['id']))
        else:
            print('%s\t%s\t%s\t%s' % (ledger_id, card['id'], card['type'], card['name']))
    return 0


if __name__ == '__main__':
    sys.exit(main())
