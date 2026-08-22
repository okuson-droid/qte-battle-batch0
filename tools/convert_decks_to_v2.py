#!/usr/bin/env python3
"""旧デッキファイル(formatVersion: 1)を taboo-elemental-deck v2 へ変換する(★Batch 63)。

Batch 63 でデッキファイルの形式を taboo-elemental-deck(version 2)に一本化した。
リポジトリの `decks/*.json`(効果確認用デッキ)は旧形式で書かれていたため、
このスクリプトで移行する。手元に旧形式のデッキが残っている場合も、同じスクリプトで直せる。

    python3 tools/convert_decks_to_v2.py decks/*.json

★カードIDは変換しない。46b で両モードとも manual-cards.json のIDに統一済みであり、
  変わるのは「欄の名前」だけである(leaderCardId → leader.cardId、count → qty、
  name → deckName、禁忌はID文字列 → {cardId, name})。
★name 欄は人が読むための飾りであり、突合には使わない(突合キーはカードIDのみ)。
  それでも入れるのは、デッキメーカーが書く形式に合わせるためと、
  ファイルを開いた人が中身を読めるようにするためである。
"""
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CARDS = ROOT / "src/main/resources/cards/manual-cards.json"

FORMAT = "taboo-elemental-deck"
VERSION = 2


def load_names():
    data = json.loads(CARDS.read_text(encoding="utf-8"))
    return {c["id"]: c["name"] for c in data["cards"]}


def convert(old, names):
    """旧形式(formatVersion 1)の dict を v2 の dict にする"""
    if old.get("format") == FORMAT:
        return None  # 変換済み
    if "leaderCardId" not in old:
        raise ValueError("旧形式ではない(leaderCardId が無い)")
    leader_id = old["leaderCardId"]
    return {
        "format": FORMAT,
        "version": VERSION,
        "deckName": old.get("name") or "",
        "leader": {"cardId": leader_id, "name": names.get(leader_id, leader_id)},
        "main": [
            {"cardId": e["cardId"], "name": names.get(e["cardId"], e["cardId"]),
             "qty": e["count"]}
            for e in old.get("main", [])
        ],
        "taboo": [
            {"cardId": cid, "name": names.get(cid, cid)}
            for cid in old.get("taboo", [])
        ],
    }


def main(argv):
    targets = [pathlib.Path(a) for a in argv[1:]]
    if not targets:
        targets = sorted((ROOT / "decks").glob("*.json"))
    names = load_names()
    converted = skipped = 0
    for path in targets:
        old = json.loads(path.read_text(encoding="utf-8"))
        new = convert(old, names)
        if new is None:
            print(f"skip    {path}(すでに v2)")
            skipped += 1
            continue
        path.write_text(json.dumps(new, ensure_ascii=False, indent=2) + "\n",
                        encoding="utf-8")
        total = sum(e["qty"] for e in new["main"])
        print(f"convert {path}(メイン{total}枚 / 禁忌{len(new['taboo'])}枚)")
        converted += 1
    print(f"\n{converted} 件を変換、{skipped} 件は変換不要")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
