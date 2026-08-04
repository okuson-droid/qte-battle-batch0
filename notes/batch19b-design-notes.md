# Batch 19b 設計解説 — 盤面レイアウトの再設計(UI改善)

対象: QTE Battle 手動モード(フェイズ1 = 一人回し)。フェーズ1完成(19a)後の発注者レビューで
挙がった指摘への対応。
前提: `batch16-manual-mode-design-v2_4.md`(唯一の正)+ `batch19b-ui-design.md`(v2.1・唯一の正)。
範囲: 設計書 v2.1 の 1章〜3章全体(0章の指摘5件への対応)。Java・サーバ側の変更は無い。

---

## ⚡ 結論チートシート

- **3列(左110+中700+右350)を2列(中840+右224)へ再編した。** 左のゾーンバー列を廃止し、
  中身は禁忌・山札・公開・墓地・消滅の5ゾーンともリーダー行へ統合した(2-1)。
- **ミニオン枠は7→6に訂正した。** `renderStackRow` の呼び出し2箇所(旧323・352行)の
  第4引数を書き換えただけであり、`renderStackRow` 自体の実装は変えていない。
- **`renderZoneBar` を廃止し、`createCardPile` に置き換えた。** 山札・禁忌は枚数のみ
  (非公開ゾーンの秘匿)、墓地・消滅・公開は従来どおり一番上のカード名を出す。
- **マナ行を新設した。** 表/裏の2ストリップを横並びにし、ストリップ全体をドロップ対象にした。
  マナのミニタイルは 22×30px → 64×88px に拡大し、カード名を表示する。
  収まらない枚数は実測幅から重なり量を計算して圧縮する(`applyManaOverlap`)。
- **帯からドラッグできないバグを修正した。** 原因は `.manual-band-backdrop`
  (全画面・z-index 1040)が dragover/drop を遮断していたことで、18cの design-notes が
  「機能する」と書いていた操作が実際には一度も動いていなかった。
- **拡大表示を「常に表面 + 裏向きバッジ」に変更した。** 裏面画像を出す従来方式は
  確認の用をなしていなかった。
- **★通常モードと共有しているCSSクラス(`.leader-card` / `.minion-row` / `.mana-chip` /
  `.mana-row`)は1行も変更していない。** サイズ変更が要る箇所は手動モード側の修飾クラス
  (`.manual-leader-tile` 等)で上書きし、通常モード(`battle.html`)への影響をゼロにした。
- **名称の誤記「クイン・タブーエレメンタル」を修正した。** 設計書 2-8 が挙げた5箇所に加え、
  全文検索で新たに見つかった `pom.xml`・`tools/convert_manual_cards.py` の2箇所、および
  ビルドに使われないリポジトリルート直下の `qte-cards.json`(重複コピー)も合わせて直した
  (0章参照)。
- 変更ファイル: `manual-battle.html` / `manual-battle.js`(v=3→v=4)/ `battle.css`(v=12→v=13)+
  名称修正7ファイル。**コードの削除は無い。** ただし引き継ぎ書は `qte-handoff-v16.md` を
  `qte-handoff-v17.md` へ差し替えるため、旧ファイルの削除を手元で依頼する(1章参照)。

---

## 0. 着手前の報告

### 0-1. Batch 19a の反映確認

プロジェクトナレッジには `qte-handoff-v15.md`(19a着手前の版)しか無く、`qte-handoff-v16.md`
が見当たらなかった。GitHub リポジトリの方には `qte-handoff-v16.md` が存在したため、
そちらの「19aの確認項目」7点を実データと照合し、全項目クリアを確認した上で本バッチへ進んだ。
プロジェクトナレッジ側への `qte-handoff-v16.md` のアップロードが漏れていると見られる。

### 0-2. 名称修正の対象ファイルの食い違い

チャット開始時の指示では対象4ファイル(`manual-lobby.html` を含まない)だったが、
`batch19b-ui-design.md` の 2-8 は5ファイル目として `manual-lobby.html` を明記しており、
実際に同ファイルにも誤記が存在した。**設計書を優先し、5ファイルとも修正した。**

### 0-3. 設計書に無い誤記2箇所

リポジトリ全体を `クイン・タブーエレメンタル` で全文検索したところ、設計書のリストに無い
2箇所が追加で見つかった。

| ファイル | 内容 | 判断 |
|---|---|---|
| `pom.xml` | `<description>` タグの値 | Javaコードでもビルド設定でもない説明文であり、修正してもビルドに影響しないため修正した |
| `tools/convert_manual_cards.py` | `manual-cards.json` を生成するテンプレート内の固定文字列 | オフライン変換スクリプト(3-3)であり実行時に動くコードではない。放置すると将来の再変換時に誤記が再生成されるため修正した |

### 0-4. リポジトリルート直下の `qte-cards.json`

`src/main/resources/cards/qte-cards.json` と内容が同一の重複コピーがリポジトリルートに
存在する(`Dockerfile` は `src/` のみを COPY するため、ビルド・実行には使われない参照用と
見られる)。設計書のリストには無いが、内容が同一である以上片方だけ直すと矛盾するため
合わせて修正した。

---

## 1. 変更したファイル

```
src/main/resources/templates/manual-battle.html   ★2列構成・ヘルプボタン/モーダル追加。js(v=4)/css(v=13)
src/main/resources/static/js/manual-battle.js      ★大部分を書き換え(2章参照)
src/main/resources/static/css/battle.css           ★v=13。新クラス追加、既存クラスは大半を維持

src/main/resources/templates/lobby.html            名称修正のみ
src/main/resources/templates/manual-lobby.html     名称修正のみ
src/main/resources/templates/cards.html            名称修正のみ
src/main/resources/cards/qte-cards.json             名称修正のみ("game"フィールド)
src/main/resources/cards/manual-cards.json          名称修正のみ("game"フィールド)
qte-cards.json(リポジトリルート)                    名称修正のみ(0-4)
pom.xml                                             名称修正のみ(0-3)
tools/convert_manual_cards.py                       名称修正のみ(0-3)

notes/batch19b-design-notes.md                      ★新設(本ファイル)
qte-handoff-v17.md                                  ★新設(qte-handoff-v16.md を差し替え)
```

**削除したファイルは zip に含まれない。** 手元で次のパスを削除すること。

```
qte-handoff-v16.md
```

コード側の削除は無い。Java ファイルは1行も触っていない。

---

## 2. 設計判断

### 2-1. レイアウトの再編 — 6つの描画関数への分割

旧 `renderZoneBar` / `renderOpponent` / `renderSelfField` / `renderLeaderAndMana` の4関数を、
新レイアウトの行構成に合わせて次の6関数へ再編した。

```
renderOpponentTop(view)      B席リーダー + 小型ゾーンバー(飾り)
renderOpponentMinions(view)  Bミニオン行(6枠)
renderSelfMinions(view)      Aミニオン行(6枠)
renderLeaderRow(view)        ウェポン・リーダー・禁忌・山札・公開・墓地・消滅
renderManaRow(view)          マナ行(表/裏ストリップ)
renderHand(view)             手札(無変更)
```

`renderStackRow`(タイル列を描く共通関数)自体は変更していない。呼び出し側の第4引数
(`minSlots`)を場のミニオン行だけ 7→6 に直した(2-9)。ウェポン行の呼び出し(`minSlots=1`)は
無変更である。

コンテナIDも旧構成(`zone-bar-self` / `seat-opponent` / `seat-self-field` /
`seat-self-leader-mana`)から新構成(`seat-opponent-top` / `seat-opponent-minions` /
`seat-self-minions` / `seat-self-leader-row` / `seat-self-mana-row`)へ全面的に差し替えている。

### 2-2. カード型パイル — `createCardPile`

旧 `renderZoneBar` はゾーンごとに横幅110pxの薄い箱(高さ約50px)を縦に5つ並べていたが、
新設計はリーダー行に横並びで90×120pxの箱を置く(設計書2-2)。実装は「箱を1つ作って返す」
関数 `createCardPile(seatId, zoneName, pile)` に切り出し、`renderLeaderRow` がゾーン名の配列
(`['TABOO', 'DECK', 'REVEAL', 'TRASH', 'LOST']`)をこの順で呼ぶだけにした。

**非公開ゾーンの判定は `PRIVATE_PILE_ZONES = new Set(['DECK', 'TABOO'])` の1箇所に集約した。**
このセットに入っているゾーンだけ一番上のカード名を隠す。ゾーンが増えても分岐を増やさず
このセットに追加するだけでよい。

山札(`DECK`)だけクリック・ドロップの挙動が他4ゾーンと異なる(1枚ドロー・シャッフル・
上へ/下へ)点は18c以前と変えていない。`createCardPile` 内で `zoneName === 'DECK'` の分岐に
その処理をそのまま移設した。

**箱の高さは `height` ではなく `min-height: 120px` にした。** 設計書2-2の図は、山札の枠だけ
シャッフルボタン・上へ/下へドロップぶん下に長く描かれている。`height` 固定だと山札パイルの
中身がはみ出す(または隠れる)ため、他の4パイルより山札だけ縦に伸びることを許した。
これに伴い、リーダー行コンテナの `align-items` は当初案の `flex-end`(下揃え)から
`flex-start`(上揃え)に変更した。山札だけ下に伸びても、ラベル・枚数の行が他のパイルと
同じ高さで揃う。

### 2-3. マナ行 — ストリップと重ね表示

`renderManaRow` は `seat.zones.MANA` を `faceDown` で2グループに分け、`createManaStrip` を
表・裏それぞれに呼ぶ。ストリップの `<div class="mana-strip-track">` を丸ごと
`registerDropTarget` に渡している(表裏の区別は第5引数 `faceDown`)。これは18c以前の
「表向きへ」「裏向きへ」という小さな専用ボックスをやめ、ストリップ全体をドロップ対象に
広げたことに当たる(設計書2-3の指摘5「マナのドロップエリアが小さい」への対応)。

**重ね表示は実測幅で計算する。** 設計時点でストリップの正確なpx幅を決め打ちすると、
ブラウザのズーム・フォント差・将来のCSS調整で簡単に狂う。そこで `applyManaOverlap(wrap)` は
`renderManaRow` の最後、要素がすでにライブDOMへ挿入された後に呼び、
`track.clientWidth`(実測値)を使って次を計算する。

```
1枚あたりの幅 = 64px(固定)
最小露出 = 28px → 最大重なり = 64 - 28 = 36px
自然な合計幅 が トラック実測幅 を超えた分だけ、(枚数-1)で割って重なり量を求める
重なり量は 36px を上限にクランプする(全部重なって数が読めなくなるのを防ぐ)
```

これにより、コンテナ幅を将来変えても(例えば右列をさらに縮めた場合)コード修正なしで
追従する。

### 2-4. 拡大表示 — 常に表面 + 裏向きバッジ

旧 `renderZoom(card, backImageId)` は `card.faceDown` なら `backImageId`(裏面画像)を出す
実装だった。設計書2-4の指摘どおり、これでは持ち主が自分の裏向きカードの中身を
確認する手段が無かった(裏面画像はどのカードも同じ絵柄のため)。

新実装は `card.imageId`(表面)を常に表示し、`card.faceDown` のときだけ
`.manual-facedown-badge`(「裏向き」の黒い帯)を `#zoom-panel` の下部に重ねる。
`backImageId` パラメータは不要になったため、`renderZoom` の引数から削除し、
呼び出し側(`setZoom` と `renderAll`)も1引数に揃えた。

★フェーズ2(対戦モード)ではサーバが相手の非公開カードの `imageId` 自体をビューに
含めない設計(設計書11-3)であるため、この変更が新たな情報漏えい経路になることは無い。

### 2-5. 帯からドラッグできないバグの修正

**原因**: `.manual-band-backdrop`(`position: fixed; inset: 0; z-index: 1040`)が画面全体を
覆っており、帯を開いている間は盤面のどの要素も `dragover`/`drop` を受け取れなかった。
帯の中のカード(`createBandItem`)自体は `draggable` と `dragstart` を正しく実装していたが、
**着地点そのものが存在しなかった。** 18cの design-notes 3章はこの操作を検証済みと記載していたが、
実際には一度も機能していなかったことになる(0章参照。既知の落とし穴に追記する)。

**修正**: ドラッグ中だけバックドロップと帯自身の `pointer-events` を切る。

```css
body.manual-drag-active .manual-band-backdrop,
body.manual-drag-active .manual-band {
    pointer-events: none;
}
```

`manual-drag-active` クラスの付け外しは、全カード共通のドラッグ開始点である
`onDragStart(e, card, seatId, zone)` の1箇所に実装した。この関数は場のタイル・手札・
マナのミニタイル・帯のカードのすべてが呼ぶ共通関数であるため、呼び出し元ごとに
処理を増やす必要が無い。

```js
document.body.classList.add('manual-drag-active');
e.target.addEventListener('dragend', () => {
    document.body.classList.remove('manual-drag-active');
}, { once: true });
```

`{ once: true }` により、リスナーの登録・解除の対応漏れを気にしなくてよい。帯自身も
透過させているのは、帯の背後に隠れているB席行などへも落とせるようにするためであり、
設計書2-5の指示どおりである。ドロップ成功時はサーバの再配信で `refreshOverlay()` が
帯を描き直すため、追加の後始末は不要な点も設計書のとおりだった。

なお、山札の全面表示(`.manual-fullscreen`)は開いている間そもそも盤面ドラッグを想定しない
設計(設計書4-6「開いている間は盤面が見えない」)であり、`createDeckRow` は独自の
`dragstart` を持つ(`onDragStart` を経由しない)。今回の修正の対象外であり、意図的に触れていない。

### 2-6. 操作説明モーダル

ヘッダ右端に「?」ボタンを追加した。中身は静的HTML(`<table>`)で、設計書2-6の表をそのまま
書き起こしている。JSは既存の `stat-modal`/`label-modal` と同じ `d-none` 切り替えパターンを
踏襲し、ヘッダ操作群の初期化セクション(旧 `btn-leave` の直後)に2行追加しただけである。
新しい状態変数・モード管理は導入していない。

### 2-7. B席と右列

小型ゾーンバー(`.zone-pile-mini`)はクリックリスナーを持たない飾りのままとし
(18c 0-2 Q1・設計書2-7で維持を確認済み)、ドロップ受けの面積のみ 32×24px → 44×32px に
拡大した。`cursor` も `pointer` から `default` に変え、クリックできない見た目にした
(従来は飾りなのに `cursor: pointer` のままで紛らわしかった)。

右列は 350px → 224px、拡大画像は 330×462px → 204×286px。宣言ボタン(勝利/敗北/引分/投了)は
幅が縮んだ分、`flex-wrap` を足して折り返せるようにした(既存の `btn-group` のままだと
224px幅では4ボタンが収まらない)。

### 2-8. 名称の修正

0章に記載のとおり、設計書の5箇所に加えて `pom.xml`・`tools/convert_manual_cards.py`・
リポジトリルートの重複 `qte-cards.json` の3箇所、計8箇所を修正した。すべて `sed` による
単純な文字列置換(`クイン・タブーエレメンタル` → `クイン・タブーエレメント`)であり、
構造やロジックには一切触れていない。

「ピュア・エレメント」(QTE-X001)は元から正しい表記であり、対象に含めていない
(誤って一致しないことを目視確認済み)。

### 2-9. ミニオン枠数の訂正

`renderStackRow(fieldRow, seat, 'FIELD', 7)` だった呼び出しを、`renderOpponentMinions` と
`renderSelfMinions` の両方で `6` に直した。`renderStackRow` 自体のロジック(空き枠を
`minSlots` まで用意する)は変更していないため、7体目以降を置いた場合は従来どおり行が
折り返して表示される(大地の巨頭による8体運用もこの折り返しで表現できる。設計書2-9)。

通常モード(`PlayerState.DEFAULT_MINION_ZONE_LIMIT`)は元から6であり、Javaの変更は不要だった。

### 2-10. 通常モードとの共有クラスに触れていないこと

`battle.css` は `battle.html`(通常モード)と `manual-battle.html`(手動モード)の両方が
読み込む共有ファイルである。次のクラスは通常モード側でも使われているため、**定義そのものは
変更せず**、手動モード側の追加クラス・修飾クラスで上書きする方式に統一した。

| 共有クラス | 通常モードでの使用箇所 | 手動モードでの対応 |
|---|---|---|
| `.leader-card` | `#opp-leader` / `#my-leader`(battle.html) | `.manual-leader-tile` に `width: 90px; height: 120px` を追加で当てる |
| `.minion-row` | `#opp-minions` / `#my-minions`(battle.html) | 変更なし。子要素(`.manual-tile`)の高さが110→120pxになった分は `min-height` を超えて自然に広がるだけで、通常モード側の見た目には影響しない |
| `.mana-chip` / `.mana-row` | `#opp-mana-row` / `#my-mana-row`(battle.html) | 手動モードは新設の `.mana-tile` / `.mana-strip*` を使い、`.mana-chip`/`.mana-row` 自体は一切触っていない |
| `.zone-pile-mini` | 手動モードのみ | 直接変更(通常モードは未使用のため安全) |

`.manual-tile` / `.manual-pile` / `.manual-weapon-slot` などプレフィックスに `manual-` を
持つクラスは手動モード専用であり、そもそも共有の心配が無い。

---

## 3. 検証

この環境では Maven ビルドができないため、従来と同じ手段で確認した。

- **`tools/check_structure.py src/main/java`** → メソッド構造の異常は検出されず(Javaを
  一切触っていないため元より非対象)。
- **`tools/check_all.py .`** → 項目1・3・5・6すべて通過。
- **`tools/check_records.py src/main/java`** → 既知の3件(オーバーロード2件・`>` の
  誤カウント1件)のみで、いずれも `tools/README.md` に記載済みの偽陽性。新規の不一致は無い。
- **`tools/check_undeclared.py src/main/resources/static/js/*.js`** → 3ファイルすべて
  「未宣言の識別子は検出されませんでした」。
- **`node --check`** → `battle.js` / `deck-builder.js` / `manual-battle.js` すべて構文エラー無し。
- **全文検索による名称誤記の再確認**: `grep -rn クイン・タブーエレメンタル .` で0件を確認
  (修正漏れが無いことの最終確認)。
- **手読み**: `renderAll` から呼ばれる6関数すべてがコンテナID(`manual-battle.html` 側)と
  一致していること、`cardLocation` への登録がLEADER/MANA/FIELD/WEAPON/HANDの5ゾーンで
  旧実装と同じ組み合わせのまま保たれていること(帯・全面表示のcardLocation非対応ゾーンは
  従来どおり非対象)を確認した。

★この環境にはブラウザが無いため、実際のレンダリング(幅の折り返し・重なり計算の見た目)は
コード上の検証に留まる。特に `applyManaOverlap` の重なり量とヘルプモーダルの表組みの見た目は、
マスターの手元での目視確認を推奨する。

---

## 4. 既知の限界(要確認・未対応)

- **★マナの重ね表示は実測幅ベースだが、極端に小さい画面幅(ブラウザの拡大表示等)では
  検証していない。** コンテナ幅1200px・通常のズーム倍率を前提にしている。
- **リーダー行の折り返し(`flex-wrap`)は7ゾーン(ウェポン+リーダー+パイル5つ)が
  横に収まらない画面幅で発生する。** 設計書1章の検算どおり840px幅では発生しないはずだが、
  ウェポンが複数装備されて右に伸びた場合(設計書2-1)は折り返しうる。複数装備は現状の
  カードプールで頻度が低いと判断し、特別な対応はしていない。
- 18a〜19a由来の既知の限界(`CardMasterLoadTest` の `hasSize(72)`、`check_records.py` の
  `TARGETS` 未登録、`check_structure.py` の `src/test` 未対応、`check_undeclared.py` の
  `BUILTINS` 未登録2件、手動モードの部屋が自動的には消えない点)はすべて変わらず残っている。

---

## 5. 積み残し

基盤の新設が必要だと判明した項目は無かった。今回スコープから外した項目は次の1つのみである。

| 項目 | 理由 |
|---|---|
| 相手席(B席)の小型ゾーンバーを開く操作 | 設計書2-7で「クリック無効の飾りのまま」と明記されており、今回もスコープ外 |

手動モードのフェーズ1(一人回し)は19aで完成済みであり、本バッチはそのUI改善に当たる。
次はフェーズ2(ソロ対戦・対戦・観戦)のUI詰めセッション、またはVer.0.4対応のどちらかに進む
(6章参照)。

---

## 6. 次バッチ予告

本バッチはUI改善であり、手動モードの機能追加バッチ(17a〜19a)のような直線的な「次」は
無い。`qte-handoff-v17.md` の対応表のとおり、次は次の2つのどちらかである。

| 候補 | 内容 | 前提ドキュメント |
|---|---|---|
| フェーズ2 UI詰めセッション | 相手側ゾーンの見せ方・視点切替UI・在室者リスト画面・席選択画面の設計(設計書11章) | `batch16-manual-mode-design-v2_4.md` 11章 |
| Ver.0.4対応 15c | Ver.0.4差分(52枚+ルール変更1件)のバッチ計画実施 | `ver0.4-transcription-notes.md` + `batch15a-design-notes.md` + `batch15b-design-notes.md` |

どちらを先に進めるかはマスターとの相談事項である(6章参照)。
