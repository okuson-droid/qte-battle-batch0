#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
手動モード用カードデータの変換(Batch 17a)。

6文明の CSV(Shift_JIS・ヘッダ行なし・9列)から
src/main/resources/cards/manual-cards.json を生成する。

★これはビルド時変換ではなく、1回きりのオフライン変換である。
  生成物 manual-cards.json をリポジトリにコミットし、実行時は JSON だけを読む。
  実行時に Shift_JIS を読むと Docker イメージのロケール差で壊れうるためである。
  カードが増えたら tools/csv/ の CSV を差し替えて本スクリプトを再実行する。

使い方(リポジトリのルートで実行する):

    python3 tools/convert_manual_cards.py

CSV の列(設計書 batch16-manual-mode-design-v2_2.md 1-1)

    1  種別:【カード名】
    2  コスト
    3  常に 0(用途不明。読まない)
    4  表面画像ID(64桁)
    5  裏面画像ID(全行同一)
    6  Attack
    7  HP
    8  台帳ID(QTE-XXXX。新カードは空欄)
    9  備考

出力される JSON

    {
      "meta":   { ... 生成日・枚数内訳・裏面画像ID ... },
      "cards":  [ { id, name, type, civilization, cost, attack, hp,
                    imageId, ledgerCardId }, ... ]
    }

text と keywords は持たせない(設計書 3-1)。効果は拡大画像で人間が読む。
"""

import argparse
import csv
import datetime
import json
import re
import sys
import unicodedata
from collections import Counter
from pathlib import Path

# ---------------------------------------------------------------- 定数

# 出力順。実装順(水→火→闇→光→風→土)に合わせる。
CIVILIZATIONS = [
    ("水", "WATER"),
    ("火", "FIRE"),
    ("闇", "DARK"),
    ("光", "LIGHT"),
    ("風", "WIND"),
    ("土", "EARTH"),
]

# 種別の判定。「進化ミニオン」を「ミニオン」より先に見ること。
TYPE_RULES = [
    ("進化ミニオン", "EVOLUTION"),
    ("リーダー", "LEADER"),
    ("ミニオン", "MINION"),
    ("スペル", "SPELL"),
    ("ウェポン", "WEAPON"),
]

# 種別ごとに数値を持つか。(cost, attack, hp)
# True = 必須、False = null でなければならない。
TYPE_NUMERICS = {
    "LEADER":    (True, False, False),
    "MINION":    (True, True, True),
    "EVOLUTION": (True, True, True),
    "SPELL":     (True, False, False),
    "WEAPON":    (True, True, False),
}

# 設計書 1-2 の期待値。
EXPECTED_ROWS_PER_CIV = 39
EXPECTED_TYPE_COUNTS = {
    "LEADER": 18, "MINION": 119, "SPELL": 60, "WEAPON": 19, "EVOLUTION": 18,
}

# ピュア・エレメント(設計書 1-7)。CSV に含まれないため、ここで1枚だけ手で足す。
# 画像IDが正しいかは 17a の確認画面 /manual/cards で目視する。
PURE_ELEMENT = {
    "id": "QTE-M-NONE-01",
    "name": "ピュア・エレメント",
    "type": "SPELL",
    "civilization": "NONE",
    "cost": 0,
    "attack": None,
    "hp": None,
    "imageId": "d380cd40d87843737e64e7b513323dbd61e0897c3379a059e6a9367fe9304a02",
    "ledgerCardId": "QTE-X001",
}

ENCODINGS = ["cp932", "utf-8-sig", "utf-8"]


# ---------------------------------------------------------------- 補助

def normalize_name(name):
    """台帳との突合キー。NFKC → 空白除去 → 中黒除去。

    NFKC が全角スペース(U+3000)を半角に、半角中黒(U+FF65)を U+30FB に寄せるので、
    その後に一括で落とせる。名前は表記が揺れるので、これでも一致しないものは
    突合できなかったものとして必ず報告する(自動で近いものを当てにいかない)。
    """
    s = unicodedata.normalize("NFKC", name)
    s = re.sub(r"\s+", "", s)
    s = s.replace("・", "")
    return s


def parse_type_and_name(raw):
    """「種別:【カード名】」を (type, name) に分解する。

    土36行目のように「【」が欠けた行があるため、括弧は必須としない。
    区切りのコロンは全角・半角どちらも受ける。
    """
    text = unicodedata.normalize("NFKC", raw).strip()
    idx = text.find(":")
    if idx < 0:
        return None, None, "種別とカード名の区切り(コロン)が無い"

    type_text = text[:idx].strip()
    name = text[idx + 1:].strip()
    name = name.strip("【】[]").strip()

    card_type = None
    for label, value in TYPE_RULES:
        if label in type_text:
            card_type = value
            break
    if card_type is None:
        return None, None, "未知の種別: " + type_text
    if not name:
        return None, None, "カード名が空"
    return card_type, name, None


def parse_int(raw):
    """空欄は None。整数でなければ ('bad', 原文) を返す。"""
    if raw is None:
        return None, None
    text = unicodedata.normalize("NFKC", raw).strip()
    if text == "":
        return None, None
    try:
        return int(text), None
    except ValueError:
        return None, text


def read_csv_rows(path):
    """Shift_JIS を第一候補に、読めた文字コードで全行を返す。"""
    last_error = None
    for encoding in ENCODINGS:
        try:
            with path.open(encoding=encoding, newline="") as f:
                rows = list(csv.reader(f))
            return rows, encoding, None
        except UnicodeDecodeError as e:
            last_error = e
    return None, None, last_error


# ---------------------------------------------------------------- 本体

class Report:
    """報告を溜める箱。設計書 3-3 が要求する項目をすべてここに集める。"""

    def __init__(self):
        self.fatal = []          # 生成を止めるもの
        self.unmatched = []      # 台帳と突合できなかった
        self.ledger_conflict = []  # 名前突合の結果と CSV 8列目が食い違う
        self.type_conflict = []  # 種別と数値の有無が食い違う
        self.blank_numeric = []  # あるべき数値が空欄
        self.missing_image = []  # CSV にあるが画像が無い ★最重要
        self.surplus_image = []  # CSV に対応しない余剰画像
        self.duplicate_image = []
        self.duplicate_name = []
        self.notes = []

    def has_blocking(self):
        return bool(self.fatal)


def convert(root, out_path, csv_dir):
    report = Report()
    images_dir = root / "src/main/resources/static/cards"
    ledger_path = root / "src/main/resources/cards/qte-cards.json"

    # ---- 台帳を読む(正規化名 → カードID の索引を作る)
    ledger_by_norm = {}
    ledger_ids = set()
    if ledger_path.exists():
        ledger = json.loads(ledger_path.read_text(encoding="utf-8"))
        for card in ledger.get("cards", []):
            ledger_ids.add(card["id"])
            key = normalize_name(card["name"])
            ledger_by_norm.setdefault(key, []).append(card)
    else:
        report.fatal.append("台帳が見つからない: " + str(ledger_path))

    # ---- 画像の一覧
    image_files = set()
    if images_dir.is_dir():
        image_files = {p.stem for p in images_dir.glob("*.png")}
    else:
        report.fatal.append("画像ディレクトリが見つからない: " + str(images_dir))

    cards = []
    back_image_ids = set()
    seen_images = {}
    seen_names = {}

    for jp_name, civ in CIVILIZATIONS:
        path = csv_dir / (jp_name + ".csv")
        if not path.exists():
            report.fatal.append("CSV が見つからない: " + str(path))
            continue

        rows, encoding, error = read_csv_rows(path)
        if rows is None:
            report.fatal.append("CSV を読めない({}): {}".format(path.name, error))
            continue
        if encoding != "cp932":
            report.notes.append("{} は cp932 で読めず {} で読んだ".format(path.name, encoding))

        # 末尾の空行は落とす。行番号は落とす前の位置で数える必要が無いので、
        # 「意味のある行」の連番を ID に使う(設計書 3-2 の CSV 行番号)。
        while rows and not any(cell.strip() for cell in rows[-1]):
            rows.pop()

        if len(rows) != EXPECTED_ROWS_PER_CIV:
            report.fatal.append("{}: 行数が {} 行(期待 {} 行)".format(
                path.name, len(rows), EXPECTED_ROWS_PER_CIV))

        for line_no, row in enumerate(rows, start=1):
            where = "{} {}行目".format(path.name, line_no)

            if len(row) < 9:
                report.fatal.append("{}: 列数が {}(期待 9)".format(where, len(row)))
                continue
            if len(row) > 9:
                report.notes.append("{}: 列数が {} で 9 を超える。10列目以降は無視した".format(
                    where, len(row)))

            card_type, name, error = parse_type_and_name(row[0])
            if error:
                report.fatal.append("{}: {} / 原文={!r}".format(where, error, row[0]))
                continue

            cost, bad_cost = parse_int(row[1])
            image_id = row[3].strip()
            back_id = row[4].strip()
            attack, bad_attack = parse_int(row[5])
            hp, bad_hp = parse_int(row[6])
            csv_ledger_id = row[7].strip()

            for label, bad in (("cost", bad_cost), ("attack", bad_attack), ("hp", bad_hp)):
                if bad is not None:
                    report.fatal.append("{}: {} が整数でない({!r})".format(where, label, bad))

            if back_id:
                back_image_ids.add(back_id)

            # --- 種別ごとの数値の整合(設計書 3-1)
            need_cost, need_attack, need_hp = TYPE_NUMERICS[card_type]
            for label, value, required in (
                    ("cost", cost, need_cost),
                    ("attack", attack, need_attack),
                    ("hp", hp, need_hp)):
                if required and value is None:
                    report.blank_numeric.append("{} {}({}): {} が空欄".format(
                        where, name, card_type, label))
                if not required and value is not None:
                    report.type_conflict.append(
                        "{} {}({}): {} は空欄のはずだが {} が入っている。null に落とした".format(
                            where, name, card_type, label, value))
            if not need_attack:
                attack = None
            if not need_hp:
                hp = None

            # --- 画像
            if not image_id:
                report.fatal.append("{}: 表面画像IDが空欄".format(where))
            elif image_id not in image_files:
                report.missing_image.append("{} {} → {}.png が無い".format(
                    where, name, image_id))
            if image_id:
                if image_id in seen_images:
                    report.duplicate_image.append("{} {} は {} と同じ画像ID".format(
                        where, name, seen_images[image_id]))
                else:
                    seen_images[image_id] = where + " " + name

            # --- 名前の重複
            norm = normalize_name(name)
            if norm in seen_names:
                report.duplicate_name.append("{} {} は {} と同名".format(
                    where, name, seen_names[norm]))
            else:
                seen_names[norm] = where

            # --- 台帳との突合(★名前の正規化で行う。CSV 8列目は照合にのみ使う)
            matched = ledger_by_norm.get(norm, [])
            ledger_card_id = None
            if len(matched) == 1:
                ledger_card_id = matched[0]["id"]
            elif len(matched) > 1:
                same_type = [c for c in matched if c["type"] == _ledger_type(card_type)]
                if len(same_type) == 1:
                    ledger_card_id = same_type[0]["id"]
                else:
                    report.unmatched.append("{} {}: 台帳に同名が {} 件あり一意に決まらない".format(
                        where, name, len(matched)))

            if ledger_card_id is None and csv_ledger_id:
                # CSV には台帳IDがあるのに名前で突合できなかった = 表記ゆれ。
                if csv_ledger_id in ledger_ids:
                    report.unmatched.append(
                        "{} {}: 名前で突合できず。CSV 8列目 {} を採用した".format(
                            where, name, csv_ledger_id))
                    ledger_card_id = csv_ledger_id
                else:
                    report.unmatched.append(
                        "{} {}: 名前で突合できず、CSV 8列目 {} も台帳に無い".format(
                            where, name, csv_ledger_id))
            elif ledger_card_id is not None and csv_ledger_id and csv_ledger_id != ledger_card_id:
                report.ledger_conflict.append(
                    "{} {}: 名前突合={} だが CSV 8列目={}。名前突合を採用した".format(
                        where, name, ledger_card_id, csv_ledger_id))
            elif ledger_card_id is not None and not csv_ledger_id:
                report.ledger_conflict.append(
                    "{} {}: CSV 8列目は空欄だが名前で台帳 {} に一致した".format(
                        where, name, ledger_card_id))

            cards.append({
                "id": "QTE-M-{}-{}".format(civ, line_no),
                "name": name,
                "type": card_type,
                "civilization": civ,
                "cost": cost,
                "attack": attack,
                "hp": hp,
                "imageId": image_id,
                "ledgerCardId": ledger_card_id,
            })

    # ---- ピュア・エレメント(CSV に含まれないので手で足す)
    pure = dict(PURE_ELEMENT)
    if pure["imageId"] not in image_files:
        report.missing_image.append(
            "ピュア・エレメント → {}.png が無い".format(pure["imageId"]))
    cards.append(pure)

    # ---- 裏面
    back_image_id = None
    if len(back_image_ids) == 1:
        back_image_id = next(iter(back_image_ids))
        if back_image_id not in image_files:
            report.missing_image.append("裏面 → {}.png が無い".format(back_image_id))
    elif len(back_image_ids) > 1:
        report.fatal.append("裏面画像IDが {} 種類ある: {}".format(
            len(back_image_ids), sorted(back_image_ids)))

    # ---- 余剰画像(参考。削除は不要)
    used = {c["imageId"] for c in cards if c["imageId"]}
    if back_image_id:
        used.add(back_image_id)
    report.surplus_image = sorted(image_files - used)

    # ---- 枚数の検算
    type_counts = Counter(c["type"] for c in cards)
    civ_counts = Counter(c["civilization"] for c in cards)

    if report.has_blocking():
        return None, report, type_counts, civ_counts

    payload = {
        "meta": {
            "game": "クイン・タブーエレメント",
            "purpose": "手動モード(一人回し)用のカード定義。効果テキストとキーワードは持たない",
            "generatedBy": "tools/convert_manual_cards.py",
            "generatedAt": datetime.date.today().isoformat(),
            "total": len(cards),
            "counts": {k: type_counts.get(k, 0) for k in
                       ["LEADER", "MINION", "SPELL", "WEAPON", "EVOLUTION"]},
            "backImageId": back_image_id,
        },
        "cards": cards,
    }

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return payload, report, type_counts, civ_counts


def _ledger_type(manual_type):
    """手動モードの種別を台帳の種別に寄せる。台帳に EVOLUTION は無い。"""
    return "MINION" if manual_type == "EVOLUTION" else manual_type


# ---------------------------------------------------------------- 出力

def print_report(payload, report, type_counts, civ_counts, out_path):
    def section(title, items, limit=None):
        print("\n■ {} : {} 件".format(title, len(items)))
        shown = items if limit is None else items[:limit]
        for line in shown:
            print("    - " + line)
        if limit is not None and len(items) > limit:
            print("    ... 他 {} 件".format(len(items) - limit))

    print("=" * 72)
    print("手動モード カードデータ変換 レポート")
    print("=" * 72)

    if report.fatal:
        section("★致命的(生成を中止した)", report.fatal)
        print("\n生成は行わなかった。上記を直してから再実行すること。")
        return

    print("\n生成: {}".format(out_path))
    print("  総枚数        : {}".format(payload["meta"]["total"]))
    print("  裏面画像ID    : {}".format(payload["meta"]["backImageId"]))

    print("\n■ 種別内訳(期待値は設計書 1-2)")
    for key, expected in EXPECTED_TYPE_COUNTS.items():
        actual = type_counts.get(key, 0)
        # ピュア・エレメントは SPELL に1枚上乗せされる。
        adjusted = expected + (1 if key == "SPELL" else 0)
        mark = "OK " if actual == adjusted else "★NG"
        print("    {} {:<10} {:>4} (期待 {})".format(mark, key, actual, adjusted))

    print("\n■ 文明内訳")
    for _, civ in CIVILIZATIONS:
        print("       {:<6} {:>4}".format(civ, civ_counts.get(civ, 0)))
    print("       {:<6} {:>4}".format("NONE", civ_counts.get("NONE", 0)))

    section("★CSV にあるが画像が無いカード", report.missing_image)
    section("台帳と突合できなかった / 表記ゆれ", report.unmatched)
    section("台帳IDの食い違い(名前突合 vs CSV 8列目)", report.ledger_conflict)
    section("種別と数値の食い違い", report.type_conflict)
    section("数値が空欄", report.blank_numeric)
    section("表面画像IDの重複", report.duplicate_image)
    section("カード名の重複", report.duplicate_name)
    section("CSV に対応しない余剰画像(削除不要・配信されないだけ)", report.surplus_image)
    section("その他", report.notes)

    linked = sum(1 for c in payload["cards"] if c["ledgerCardId"])
    print("\n■ 台帳との対応")
    print("    ledgerCardId あり : {} 枚(期待 169 = 168 + ピュア・エレメント)".format(linked))
    print("    ledgerCardId なし : {} 枚(期待 66 = 新カード)".format(
        len(payload["cards"]) - linked))


def main():
    parser = argparse.ArgumentParser(description="CSV6本から manual-cards.json を生成する")
    parser.add_argument("root", nargs="?", default=".", help="リポジトリのルート")
    parser.add_argument("--csv-dir", default=None, help="CSV の置き場(既定 <root>/tools/csv)")
    parser.add_argument("--out", default=None,
                        help="出力先(既定 <root>/src/main/resources/cards/manual-cards.json)")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    csv_dir = Path(args.csv_dir) if args.csv_dir else root / "tools/csv"
    out_path = Path(args.out) if args.out else root / "src/main/resources/cards/manual-cards.json"

    payload, report, type_counts, civ_counts = convert(root, out_path, csv_dir)
    print_report(payload, report, type_counts, civ_counts, out_path)
    return 1 if report.has_blocking() else 0


if __name__ == "__main__":
    sys.exit(main())
