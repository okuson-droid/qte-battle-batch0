#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
【起動：n】とコードのmpCostの照合(Batch 55)。

【この番人が要る理由】
着手前の機械照合で、《蒼海の賢者》《傷痕の闘帝》《冥府の禁皇》の3枚は
テキストが【起動：1】なのにコードが0マナで撃たせていた。旧本文が「起動能力
(1ターンに1回):」としかコストを書いておらず、実装は0と決め打ちしていたためである
(rework-triage.md 2章)。この種の食い違いは字面の類似度では検出できない。

規則がテキストと CardEffectRegistry の2箇所にある以上、片方だけが直される日は
必ず来る(裁定163)。この番人はそれを機械で守らせる。

【測るもの】(rework-triage.md 2-2)
  1. 【起動：n】の n と leaderAbilities の mpCost が一致していること。
  2. テキストに【起動】が無いのに leaderAbilities に登録があるもの(= 死んだ登録)。

【使い方】
  python3 tools/check_leader_abilities.py
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MASTER = ROOT / 'src/main/resources/cards/manual-cards.json'
REGISTRY = ROOT / 'src/main/java/com/example/qte/effect/CardEffectRegistry.java'

# ★《流転の智者》(QTE-M-WATER-15)だけ「【 起動：2】」と【の直後に半角スペースが入っている
# (カードデータの表記ゆれ。データは書き換えず読む側で吸収する。裁定257 と同じ判断)。
SOUL_OR_ACTIVATE = re.compile(r'【\s*起動[：:]\s*([0-9０-９]+)】')

# leaderAbilities.put("QTE-...", LeaderAbilitySpec.of(N, ...  または
# leaderAbilities.put("QTE-...", new LeaderAbilitySpec(N, ...
REGISTRATION = re.compile(
    r'leaderAbilities\.put\("(QTE-[\w-]+)",\s*'
    r'(?:LeaderAbilitySpec\.of|new LeaderAbilitySpec)\(\s*(\d+)'
)


def to_half_width(digits):
    out = []
    for c in digits:
        if '０' <= c <= '９':
            out.append(chr(ord(c) - ord('０') + ord('0')))
        else:
            out.append(c)
    return ''.join(out)


def main():
    cards = json.load(open(MASTER, encoding='utf-8'))['cards']
    text_by_id = {c['id']: c['text'] for c in cards}

    src = REGISTRY.read_text(encoding='utf-8')
    registrations = {m.group(1): int(m.group(2)) for m in REGISTRATION.finditer(src)}

    problems = []

    for card_id, mp_cost in registrations.items():
        text = text_by_id.get(card_id)
        if text is None:
            problems.append(f'{card_id}: leaderAbilities に登録があるが manual-cards.json に無い')
            continue
        m = SOUL_OR_ACTIVATE.search(text)
        if m is None:
            problems.append(
                f'{card_id}: テキストに【起動：n】が無いのに leaderAbilities に登録が残っている'
                f'(死んだ登録) -- text={text!r}'
            )
            continue
        text_n = int(to_half_width(m.group(1)))
        if text_n != mp_cost:
            problems.append(
                f'{card_id}: テキストの【起動：{text_n}】に対し、コードは mpCost={mp_cost}'
            )

    # 逆方向: テキストに【起動：n】があるのに leaderAbilities に登録が無いもの
    for card_id, text in text_by_id.items():
        if SOUL_OR_ACTIVATE.search(text) and card_id not in registrations:
            problems.append(f'{card_id}: テキストに【起動：n】があるのに leaderAbilities に登録が無い')

    if problems:
        print('NG: 【起動：n】とコードの食い違いが見つかった')
        for p in problems:
            print(' -', p)
        sys.exit(1)

    print(f'OK: leaderAbilities {len(registrations)}件、すべてテキストの【起動：n】と一致する')


if __name__ == '__main__':
    main()
