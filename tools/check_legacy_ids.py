#!/usr/bin/env python3
"""
Ver0.4 の遺物が残っていないことを確かめる(★Batch 60。tools/build_id_map.py の後身)。

【この検査が要る理由】
Batch 46b で、通常モードのカードマスタを台帳 qte-cards.json から manual-cards.json へ
差し替え、Java 側にベタ書きされていた台帳ID169種を QTE-M-<文明>-<番号> へ機械変換した。
その対応表を作っていたのが build_id_map.py である。

★Batch 60 で台帳ファイルそのものを削除したので、対応表はもう作れないし、作る相手も居ない。
  代わりに残すのは、台帳が無くても確かめられる2つだけである。

  1. Ver0.4 の形式のカードID(QTE-0001 / QTE-L001 / QTE-X001)が
     本番のコード(src/main/java と static/js)のどこにも書かれていない
  2. manual-cards.json の ledgerCardId が重複せず、ちょうど169枚に付いている
     (= 46b の機械変換が成り立っていた前提が、今も崩れていない)

★1 は「変換し忘れ」ではなく「新しく書き足された」ことを捕まえる番人である。
  次に誰かが古いドキュメントを見て QTE-0002 と書いたら、ここで止まる。

【使い方】
  python3 tools/check_legacy_ids.py
  python3 tools/check_legacy_ids.py --check   # 同じ(呼び出し側の書き味をそろえるため)
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CARDS = ROOT / 'src/main/resources/cards'

# Ver0.4 の形式: QTE-0001(通常) / QTE-L001(リーダー) / QTE-X001(新カード枠)
LEGACY_ID = re.compile(r'QTE-(?:\d{4}|L\d{3}|X\d{3})')

# 探す先は本番のコードだけである。
#   - notes/ は歴史の記録であり、過去の文書に古いIDが出るのは正しい
#   - src/test/ には「古いIDでは引けないこと」を測る試験がある
#     (CardMasterLoadTest: findById("QTE-0027") は例外になる)。
#     これは問題そのものではなく問題を捕まえる番人なので、ここで数えると逆になる
#   - templates/ の QTE-0000 は ledgerCardId を出す欄の見本文字列である
#     (th:text で実データに差し替わる)。由来のIDを画面に出すのは正しい用途である
SCAN_DIRS = [
    (ROOT / 'src/main/java', '*.java'),
    (ROOT / 'src/main/resources/static/js', '*.js'),
]

EXPECTED_LINKED = 169
EXPECTED_TOTAL = 235


def scan_sources():
    """ソースに現れた Ver0.4 形式のID → 見つかったファイルの一覧"""
    found = {}
    for base, pattern in SCAN_DIRS:
        if not base.is_dir():
            continue
        for path in sorted(base.rglob(pattern)):
            text = path.read_text(encoding='utf-8')
            for legacy in set(LEGACY_ID.findall(text)):
                found.setdefault(legacy, []).append(str(path.relative_to(ROOT)))
    return found


def check_cards():
    with open(CARDS / 'manual-cards.json', encoding='utf-8') as f:
        cards = json.load(f)['cards']
    errors = []
    seen = {}
    duplicated = []
    for card in cards:
        origin = card.get('ledgerCardId')
        if origin is None:
            continue
        if origin in seen:
            duplicated.append('%s: %s と %s' % (origin, seen[origin], card['id']))
        seen[origin] = card['id']
    if duplicated:
        errors.append('由来のIDが重複している %d 件: %s'
                      % (len(duplicated), ' / '.join(duplicated)))
    if len(cards) != EXPECTED_TOTAL:
        errors.append('カードが %d 枚である(期待 %d 枚)' % (len(cards), EXPECTED_TOTAL))
    if len(seen) != EXPECTED_LINKED:
        errors.append('由来のIDを持つカードが %d 枚である(期待 %d 枚)'
                      % (len(seen), EXPECTED_LINKED))
    return len(cards), len(seen), errors


def main():
    total, linked, errors = check_cards()
    found = scan_sources()

    print('# Ver1.1 %d 枚 / うち Ver0.4 由来 %d 枚 / 新カード %d 枚'
          % (total, linked, total - linked), file=sys.stderr)
    print('# ソースに残る Ver0.4 形式のID: %d 種' % len(found), file=sys.stderr)

    if found:
        for legacy in sorted(found):
            errors.append('Ver0.4 形式のID %s が %s に書かれている'
                          % (legacy, ', '.join(found[legacy])))

    if errors:
        print('', file=sys.stderr)
        for e in errors:
            print('NG: %s' % e, file=sys.stderr)
        return 1
    print('OK: Ver0.4 の遺物は残っていない', file=sys.stderr)
    return 0


if __name__ == '__main__':
    sys.exit(main())
