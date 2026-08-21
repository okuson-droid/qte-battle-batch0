# 効果音の出典

このフォルダの音声ファイルの出典・ライセンス・取得日の記録である(Batch 62・裁定285)。

★**CC0 は表記を要求しない。これは表記ではなく記録である。**
後から「これはどこの音だったか」「本当に CC0 だったか」を確かめられない状態にしないために置く。

★**このフォルダに置くファイルは、すべて CC0(パブリックドメイン相当)でなければならない。**
理由は `okuson-droid/qte-battle-batch0` が**公開リポジトリ**だからである。
`static/` に置いたファイルは GitHub 上で誰でも単体でダウンロードできる状態になるので、
**「アプリに組み込んでよい」だけでは足りず、「再配布してよい」素材でなければならない。**
効果音ラボのような「再配布禁止」の素材は、商用可・クレジット不要であっても**ここには置けない**。

★**機械判定がある。**`verify/verify.js` が「`static/sounds/` にあるファイルが全部この表に載っているか」を
測っている。出所不明の音が1つ紛れ込む経路を塞ぐためである。

---

## 変換について

原本はすべて **Ogg Vorbis(`.ogg`)** である。**`.mp3` へ変換して同梱している。**

理由は **iOS Safari が 17.3 以前で Ogg Vorbis を再生できない**ためである(18.4 未満は部分対応)。
CC0 は改変を無条件に許すので、変換して差し支えない。

```bash
ffmpeg -i "<原本>.ogg" -codec:a libmp3lame -q:a 4 "<出力>.mp3"
```

★**ファイル名は原本のまま**にしてある(拡張子だけが変わる)。この表と実物の対応を素直に保つためである。

---

## 一覧

出所はすべて **Kenney**(https://kenney.nl)。ライセンスはすべて **CC0 1.0**
(https://creativecommons.org/publicdomain/zero/1.0/)。取得日はすべて **2026-08-21**。

| ファイル | 用途(音の名前) | 原本 | パック | パックのURL |
|---|---|---|---|---|
| `card-slide-1.mp3` | `draw` | `card-slide-1.ogg` | Casino Audio (1.1) | https://kenney.nl/assets/casino-audio |
| `card-place-1.mp3` | `place` | `card-place-1.ogg` | Casino Audio (1.1) | https://kenney.nl/assets/casino-audio |
| `card-place-2.mp3` | `place` | `card-place-2.ogg` | Casino Audio (1.1) | https://kenney.nl/assets/casino-audio |
| `card-place-3.mp3` | `place` | `card-place-3.ogg` | Casino Audio (1.1) | https://kenney.nl/assets/casino-audio |
| `card-place-4.mp3` | `place` | `card-place-4.ogg` | Casino Audio (1.1) | https://kenney.nl/assets/casino-audio |
| `card-shove-1.mp3` | `flip` | `card-shove-1.ogg` | Casino Audio (1.1) | https://kenney.nl/assets/casino-audio |
| `card-fan-1.mp3` | `deal` | `card-fan-1.ogg` | Casino Audio (1.1) | https://kenney.nl/assets/casino-audio |
| `card-shuffle.mp3` | `shuffle` | `card-shuffle.ogg` | Casino Audio (1.1) | https://kenney.nl/assets/casino-audio |
| `dice-throw-1.mp3` | `dice` | `dice-throw-1.ogg` | Casino Audio (1.1) | https://kenney.nl/assets/casino-audio |
| `impactMetal_medium_000.mp3` | `attack`(通常モードのみ) | `impactMetal_medium_000.ogg` | Impact Sounds | https://kenney.nl/assets/impact-sounds |
| `select_001.mp3` | `tap` | `select_001.ogg` | Interface Sounds (1.0) | https://kenney.nl/assets/interface-sounds |
| `select_002.mp3` | `tap` | `select_002.ogg` | Interface Sounds (1.0) | https://kenney.nl/assets/interface-sounds |
| `select_007.mp3` | `tap` | `select_007.ogg` | Interface Sounds (1.0) | https://kenney.nl/assets/interface-sounds |
| `select_008.mp3` | `tap` | `select_008.ogg` | Interface Sounds (1.0) | https://kenney.nl/assets/interface-sounds |
| `minimize_001.mp3` | `lpDown` | `minimize_001.ogg` | Interface Sounds (1.0) | https://kenney.nl/assets/interface-sounds |
| `maximize_001.mp3` | `lpUp` | `maximize_001.ogg` | Interface Sounds (1.0) | https://kenney.nl/assets/interface-sounds |
| `confirmation_001.mp3` | `commit` | `confirmation_001.ogg` | Interface Sounds (1.0) | https://kenney.nl/assets/interface-sounds |
| `jingles_PIZZI01.mp3` | `decisive` | `jingles_PIZZI01.ogg` | Music Jingles | https://kenney.nl/assets/music-jingles |

★各パックに同梱されていたライセンス表記(原文):

> License: (Creative Commons Zero, CC0)
> http://creativecommons.org/publicdomain/zero/1.0/
> You may use these assets in personal and commercial projects.
> Credit (Kenney or www.kenney.nl) would be nice but is not mandatory.

---

## 音を差し替えるときの手順

1. 新しい素材が **CC0 であることを確かめる**(パック同梱のライセンスファイルを読む)。
2. `ffmpeg` で `.mp3` へ変換し、このフォルダへ置く。
3. **この表に1行足す。**★足さないと機械判定が落ちる。
4. `manual-battle.js` / `battle.js` の `SFX_SPECS` のファイル名を直す。
   ★**両方である。**表は2箇所にある(裁定289 により UI ごと複製しているため)。
   ★番人が「手動モードの表の全行が通常モードの表にも同じ内容で載っているか」を測っている。
5. ★**`SFX_VERSION` を1つ上げる**(裁定284)。★`manual-battle.js` の `?v=` とは別の数字である。
6. ★**実機で聞く。**機械検証が緑でも良い音とは限らない。
