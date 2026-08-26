#!/usr/bin/env python3
"""本文と実装の突き合わせ台帳(text-impl-review.json)を更新する道具(★Batch 67)。

なぜ要るのか
------------
`tools/report_effects.py` の「未実装0枚」は、**登録が在るか**しか見ていない
(裁定303)。「登録が本文どおりか」は測っていないので、Ver1.1 で本文が
差し替わったのに実装が Ver0.4 のまま残っていても、あのツールは何も言わない。
実際 Batch 64 で《不滅のネクロマンサー》が、Batch 67 でさらに7枚が見つかった。

本文と実装が一致しているかは、機械には測れない。**測れるのは「人が突き合わせたか」**
である。この台帳は、突き合わせた時点の本文のハッシュを覚えておき、
**本文が変わったのに突き合わせ直されていないカード**を機械に見つけさせる。

    CardTextReviewTest が番人である。台帳が本文とずれたら赤くなる。

使い方
------
    # 1枚を突き合わせ済みとして記録する(理由は必須)
    python3 tools/mark_text_reviewed.py --card QTE-M-EARTH-11 --batch 67 \
        --note "コスト3以下 → 4以下に直した"

    # 台帳と本文の食い違いを一覧する(書き換えない)
    python3 tools/mark_text_reviewed.py --check

    # 突き合わせが古いカードを一覧する(次に見るべき母集団)
    python3 tools/mark_text_reviewed.py --stale batch67

    # 台帳を作り直す(初回のみ。既に載っているカードは触らない)
    python3 tools/mark_text_reviewed.py --bootstrap

★--card は複数指定できるが、**--note は1回の実行に1つ**である。
  「まとめて更新」を楽にしないための形である —— 別々の理由で直した2枚は、
  2回に分けて記録すること。台帳は緑にするための書類ではなく、点検の記録である。
"""

import argparse
import hashlib
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CARDS = os.path.join(ROOT, "src/main/resources/cards/manual-cards.json")
LEDGER = os.path.join(ROOT, "src/test/resources/text-impl-review.json")

BOOTSTRAP_NOTE = (
    "★Batch 67 の初回登録。ここに書かれた reviewedIn は"
    "「そのカードの本文と実装を最後に突き合わせたバッチ」であり、"
    "batch67 以外は当時の記録からの推定である(当時の本文のハッシュは残っていない)。"
    "この台帳が保証するのは未来だけ —— 以後、本文が変われば必ず赤くなる。"
)


def text_hash(text):
    """本文のハッシュ。改行も空白もそのまま含める(表記の揺れも差である)。"""
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def load_cards():
    with open(CARDS, encoding="utf-8") as f:
        return {c["id"]: c for c in json.load(f)["cards"]}


def load_ledger():
    if not os.path.exists(LEDGER):
        return {"_note": BOOTSTRAP_NOTE, "reviews": {}}
    with open(LEDGER, encoding="utf-8") as f:
        return json.load(f)


def save_ledger(ledger):
    ledger["reviews"] = dict(sorted(ledger["reviews"].items()))
    with open(LEDGER, "w", encoding="utf-8") as f:
        json.dump(ledger, f, ensure_ascii=False, indent=2)
        f.write("\n")


def do_check(cards, ledger):
    """台帳と本文の食い違いを一覧する。JUnit と同じ判定を人の目にも見せる。"""
    reviews = ledger["reviews"]
    missing = [i for i in cards if i not in reviews]
    unknown = [i for i in reviews if i not in cards]
    changed = [
        i for i in cards
        if i in reviews and reviews[i]["textHash"] != text_hash(cards[i]["text"])
    ]
    print("カード %d枚 / 台帳 %d件" % (len(cards), len(reviews)))
    print("  台帳に無いカード      : %d件 %s" % (len(missing), sorted(missing)))
    print("  カードマスタに無いID  : %d件 %s" % (len(unknown), sorted(unknown)))
    print("  本文が変わったカード  : %d件 %s" % (len(changed), sorted(changed)))
    if missing or unknown or changed:
        print("\nNG: 台帳が本文と一致していません。"
              "本文と実装を突き合わせ直してから --card で記録すること。")
        return 1
    print("\nOK: 全%d枚が突き合わせ済みで、本文も当時のままです。" % len(cards))
    return 0


def do_stale(cards, ledger, keep):
    reviews = ledger["reviews"]
    rows = [(reviews[i]["reviewedIn"], i, cards[i]["name"])
            for i in sorted(cards) if i in reviews and reviews[i]["reviewedIn"] != keep]
    print("reviewedIn が %s でないカード: %d枚" % (keep, len(rows)))
    for r in sorted(rows):
        print("  %-24s %-20s %s" % (r[0], r[1], r[2]))
    return 0


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--card", action="append", default=[], help="記録するカードID(複数可)")
    p.add_argument("--batch", help="突き合わせたバッチ番号(例: 67)")
    p.add_argument("--note", help="そのとき何を確かめたか(必須)")
    p.add_argument("--check", action="store_true", help="台帳と本文の食い違いを一覧する")
    p.add_argument("--stale", help="reviewedIn がこの値でないカードを一覧する")
    p.add_argument("--bootstrap", action="store_true", help="台帳の初回登録")
    args = p.parse_args()

    cards = load_cards()
    ledger = load_ledger()

    if args.check:
        return do_check(cards, ledger)
    if args.stale:
        return do_stale(cards, ledger, args.stale)
    if args.bootstrap:
        return do_bootstrap(cards, ledger)

    if not args.card or not args.batch or not args.note:
        p.error("--card / --batch / --note のすべてが要ります"
                "(理由を書かずに台帳を緑にしないための形です)")
    for cid in args.card:
        if cid not in cards:
            print("NG: カードマスタに %s がありません" % cid)
            return 1
        ledger["reviews"][cid] = {
            "textHash": text_hash(cards[cid]["text"]),
            "reviewedIn": "batch%s" % args.batch,
            "note": args.note,
        }
        print("記録: %s (%s)" % (cid, cards[cid]["name"]))
    save_ledger(ledger)
    return 0


def do_bootstrap(cards, ledger):
    """初回登録。★既に台帳に載っているカードは触らない(上書きしない)。"""
    triage = classify_by_triage(cards)
    added = 0
    for cid, c in cards.items():
        if cid in ledger["reviews"]:
            continue
        ledger["reviews"][cid] = {
            "textHash": text_hash(c["text"]),
            "reviewedIn": triage[cid],
            "note": "初回登録(★Batch 67)",
        }
        added += 1
    ledger["_note"] = BOOTSTRAP_NOTE
    save_ledger(ledger)
    print("初回登録: %d件を足しました(台帳は計 %d件)" % (added, len(ledger["reviews"])))
    return 0


def classify_by_triage(cards):
    """reviewedIn の初期値を、そのカードを最後に触ったフェーズから決める。

    ★推定である。notes/rework-triage.md の区分1〜5(121枚)は P5(55〜59)、
    残る Ver0.4 由来48枚は Batch 67(このバッチで1枚ずつ読んだ)、
    ledgerCardId を持たない新カード66枚は P2・P3(47〜53)である。
    """
    import re
    path = os.path.join(ROOT, "notes/rework-triage.md")
    reworked = set()
    if os.path.exists(path):
        by_name = {}
        for c in cards.values():
            by_name.setdefault(c["name"], c["id"])
        txt = open(path, encoding="utf-8").read()
        sec = None
        buf = {}
        for ln in txt.split("\n"):
            m = re.match(r"^### 区分(\S+) — ", ln)
            if m:
                sec = m.group(1)
                buf[sec] = []
                continue
            if ln.startswith("## ") or ln.startswith("# "):
                sec = None
            if sec is not None:
                buf[sec].append(ln)
        for ls in buf.values():
            joined = re.sub(r"\n\s{2,}(?=\S)", " ", "\n".join(ls))
            for m in re.finditer(r"^\s*-\s+\*\*[火水風光闇土]\*\*:\s*(.+)$", joined, re.M):
                for part in m.group(1).split("/"):
                    name = re.sub(r"★.*", "", part).strip().rstrip("。").strip()
                    if name in by_name:
                        reworked.add(by_name[name])
            for m in re.finditer(r"^\|\s*([^|]+?)\((?:[火水風光闇土])(?:・L)?\)\s*\|", joined, re.M):
                name = m.group(1).strip()
                if name in by_name:
                    reworked.add(by_name[name])
    out = {}
    for cid, c in cards.items():
        if cid in reworked:
            out[cid] = "batch55-59(P5 の作り直し)"
        elif c.get("ledgerCardId"):
            out[cid] = "batch67"
        else:
            out[cid] = "batch47-53(P2・P3 の新規実装)"
    return out


if __name__ == "__main__":
    try:
        sys.exit(main())
    except BrokenPipeError:
        # ★`| head` で切られたときに醜い traceback を出さない(道具としての作法)
        os.dup2(os.open(os.devnull, os.O_WRONLY), sys.stdout.fileno())
        sys.exit(0)
