# Batch 20a 設計解説 — 山札からの直接移動・裏向きの正規化・数値の増減UI

対象: `ManualOperationService.java`（唯一のJava変更）、`manual-battle.js`（v=5→v=6）、
`battle.css`（v=13→v=14）、`manual-battle.html`（js v=6・css v=14、`#lp-modal`追加）。
前提ドキュメントは `notes/batch20a-design.md`（本バッチの唯一の正、確認事項16件確定済み）。

---

## ⚡ 結論チートシート

- **山札のカード型パイルをドラッグの起点にした。** ドラッグして他ゾーンへ落とすと、
  山札の一番上の1枚（`zones.DECK` の index 0）が移る。空の山札は `draggable=false` で
  そもそもドラッグを開始しない。
- **裏向きの正規化を `ManualOperationService.move` に追加した。** FIELD/WEAPON/TRASH/
  LOST/REVEAL/HAND へ移すと表向きへ揃う。TABOO/DECK/MANAは対象外。クライアントが
  `faceDown` を明示していればそちらが勝つ。進化スタックの素材にも同じ規則を適用した。
- **全面表示のボタンを4個→10個に増やした。** 段組みは決め打ちせず、1つの
  flex-wrap 入れ物に並べた順で自然に折り返す形にした。
- **LPの `prompt()` を廃止し、ATK/HPと同じ `statInput` を流用したモーダルにした。**
  加えて `-5/-1/+1/+5` ボタンを設け、押しても閉じない。再配信されたビューでモーダル
  表示中の数値を差し替える経路も足した。
- **実マウス検証で設計書の想定外れを2点発見し、その場で訂正した。** いずれも
  「合成DragEventでは絶対に見えない」種類の不具合であり、以下2点は本ノートの中心的な
  記録である。
  1. 「上へ/下へ」枠・シャッフルボタンを**ドラッグの起点から除外する**手段として、
     設計書は「子要素に `draggable=false` を明示する」としていたが、これは効かない。
     `document.elementFromPoint` を使った訂正版に置き換えた（3-1）。
  2. 上記の調査中、**設計書の対象外の既存不具合**（山札パイルへのドロップが2重送信
     される）を発見し、マスター了承のうえ同時に修正した（3-2）。

---

## 1. 変更したファイル

```
src/main/java/com/example/qte/manual/ManualOperationService.java
    move() にゾーン別の表裏正規化を追加（FACE_UP_ON_ARRIVAL・normalizeFaceDown・
    applyFaceDownRule）。進化スタックの素材にも同じ規則を適用。

src/main/resources/static/js/manual-battle.js  （v=5 → v=6）
    createCardPile: DECK分岐にドラッグ起点化・空デッキでのdraggable=false
    createDeckRow: ボタンを4個→10個に拡張、sendDeckMoveにfaceDown引数を追加
    createLeaderTile: LPクリックでprompt()の代わりにopenLpModal()を呼ぶ
    openLpModal / refreshLpModal: 新設（9-2章）
    registerDropTarget: drop ハンドラに e.stopPropagation() を追加（既存不具合の修正）

src/main/resources/static/css/battle.css  （v=13 → v=14）
    .manual-deck-row-buttons に flex-wrap を追加
    #lp-modal-minus5/-1/+1/+5 の最小幅を指定

src/main/resources/templates/manual-battle.html
    battle.css(v=14)・manual-battle.js(v=6) へ更新
    #lp-modal を新設
    操作説明モーダルの「山札」行にドラッグの説明を追記
```

---

## 2. 設計判断

### 2-1. 山札ドラッグの実データはビューがそのまま持っている

フェイズ1（一人回し）はゾーンフィルタが掛からず、DECKゾーンも他ゾーンと同じ
`ManualCardView`（instanceId・imageId等すべて含む）が配信される。したがって
`createCardPile('A', 'DECK', pile)` に渡ってくる `pile[0]` は既に実データであり、
クライアント側で別途カード情報を持たせる必要はなかった。既存の `onDragStart(e, card,
seatId, zone)` をそのまま呼べる。

### 2-2. 表裏正規化はサーバの `move` 1箇所に集約した（設計書D1のとおり）

盤面ドラッグ・帯からのドラッグ・全面表示のボタン・複数選択の一括移動という4経路
すべてが `move` を通るため、正規化をここに置けば経路ごとの実装漏れが構造的に起きない。
`request.faceDown()` が明示されていれば優先し、明示が無いときだけゾーンに応じて
表向きへ揃える。進化スタックの素材（`getMaterials()`）は平坦なリストなので、
再帰を書かずに1段のループで正規化できる。

### 2-3. LPモーダルは新規UIをほとんど作っていない

`statInput` をそのまま流用しているため、ATK/HPと全く同じスピナー挙動を追加コード
ゼロで獲得できた。新設したのは `-5/-1/+1/+5` ボタンと、モーダルを開いたまま
再配信された数値を差し替える `refreshLpModal` のみである。

---

## 3. 実マウス検証で見つかった2点（設計書の想定外れ・既存不具合）

### 3-1. 「ドラッグ起点からの除外」は `draggable=false` では実現できない

設計書 A4 は「上へ/下へ枠・シャッフルボタンに `draggable=false` を明示すれば、
掴んでも山札ドラッグが始まらない」としていたが、これは誤りだった。

HTML5のドラッグ&ドロップは、mousedown位置から**祖先方向へ**`draggable=true`を
探して「実際にドラッグされる要素（source node）」を決める。子要素に
`draggable=false` を明示しても、それは「その子要素自身をドラッグ起点にしない」
という意味でしかなく、祖先探索を止める壁にはならない。祖先（山札パイル本体）が
`draggable=true` である以上、子要素を掴んでも結局祖先がドラッグされる。

実マウス（`page.mouse.down/move/up`）で検証したところ、`draggable=false` を
明示した状態でも「上へ」枠からTRASHへカードが移動してしまうことを確認した。

次に、`dragstart` イベントの `e.target` で子要素かどうかを判定しようとしたが、
これも成立しなかった。`dragstart` の `target` は「実際にドラッグ対象になった要素
（= 祖先である山札パイル本体）」であり、実際に指を置いた子要素ではないためである。

最終的に、`dragstart` 発火時点の座標に対して `document.elementFromPoint(e.clientX,
e.clientY)` を呼び、実際にポインタの真下にある要素を取得して判定する方式に
訂正した。これなら実際の掴んだ場所が正しく分かる。

```js
box.addEventListener('dragstart', (e) => {
    const origin = document.elementFromPoint(e.clientX, e.clientY);
    if (origin && origin.closest('.zone-drop-mini, button')) {
        e.preventDefault();
        return;
    }
    onDragStart(e, pile[0], seatId, 'DECK');
});
```

`.zone-drop-mini`（上へ/下へ）・`button`（シャッフル）のいずれかの内側から
始まった場合は `preventDefault()` でドラッグそのものを打ち消す。子要素への
`draggable=false` の明示は効果が無いため削除した。

この訂正は実マウス操作でしか発見できない。合成 `DragEvent` はブラウザ本来の
ドラッグ確立処理（祖先探索・source node決定）を一切通らないため、
`draggable=false` が効いているように誤って見え続けた可能性が高い。

### 3-2.（設計対象外・マスター了承のうえ同時修正）山札パイルへのドロップの2重送信

3-1の検証中に、**設計書の対象外である既存の不具合**を発見した。「上へ/下へ」枠は
山札パイル本体（`box`）の**内側**にあり、`box` 自身も `registerDropTarget(box,
seatId, zoneName)` で独立したドロップ対象として登録されている。したがって
「上へ」枠へ何かをドロップすると、`drop` イベントが「上へ」枠自身のハンドラで
処理された後、**祖先である `box` まで伝播して `box` のハンドラも重ねて発火する。**

結果として `move` が2回送信される。1回目は狙いどおり `toIndex: 0`、2回目は
`box` 側の既定値（`toIndex: null` = 末尾）であり、カードは最終的に一番上ではなく
末尾へ行ってしまう。この構造（パイル本体と内側の子要素が両方ドロップ対象として
登録されている）は本バッチより前から存在しており、山札パイル特有の入れ子構造である。
他のゾーン（TRASH/LOST/TABOO/REVEAL等のパイル、FIELDのミニオン枠等）にはこの
入れ子は無く、影響は山札パイルに閉じている。

修正は `registerDropTarget` の `drop` ハンドラの先頭に `e.stopPropagation()` を
1行追加するのみ。マスターに状況を説明し、同一バッチでの修正について了承を得たうえで
適用した。

```js
el.addEventListener('drop', (e) => {
    e.preventDefault();
    e.stopPropagation(); // ★追加。入れ子のドロップ対象への伝播を止める
    el.classList.remove('manual-drop-hover');
    ...
```

---

## 4. 検証手順（実マウス操作。合成DragEventは使用していない）

Playwrightで実ファイル（`battle.css`・`manual-battle.js`）を読み込む検証用HTMLを
作り、`StompJs` をスタブして送信内容（`client.publish` の引数）を捕捉した。
`page.mouse.down/move/up` による多段階の座標移動でネイティブのドラッグを発生させ、
以下を確認済み。

- 山札ドラッグ→墓地: `cardIds`・`toZone`・`faceDown`（null）が正しい
- 山札が空のとき: `draggable=false` になり、ドラッグ自体が発生しない
- 「上へ」枠・シャッフルボタンを起点にした場合: ドラッグが打ち消され、何も送信されない
  （3-1の訂正後）
- シャッフルボタンの通常クリック: `shuffle` が正しく送信される（3-1の訂正がクリックを
  壊していないことの確認）
- マナ表/裏ストリップへのドラッグ: `faceDown` がそれぞれ `false`/`true` で送信される
- 全面表示の10ボタン: ラベル・並び順が設計書どおり、`toZone`/`faceDown` も正しい
- LPモーダル: `prompt()` が呼ばれない、連続クリックで閉じない、数値入力欄からの直接
  指定も送信される、モーダルを開いたまま再配信された値に表示が追従する
- 「上へ」枠へのドロップ（手札から）: 修正後は `move` が1件のみ送信される（3-2の修正確認）

---

## 5. 理解確認Q&A

**Q1. なぜ表裏の正規化を `move()` 1箇所に集約したのか。クライアント側で
各ドロップ経路に処理を足す方式ではだめなのか。**
A. だめではないが壊れやすい。盤面ドラッグ・帯・全面表示ボタン・一括移動の
4経路があり、クライアント側に分散させると新しい経路が増えるたびに正規化を
書き足す必要があり、1箇所書き忘れれば静かに見た目だけ崩れる（動作はする分、
気づきにくい）。`move()` は全経路が最終的に通る唯一の関門なので、ここに置けば
経路の増減に関わらず保証が続く。

**Q2. `draggable=false` を子要素に付けても効かなかった理由を、自分の言葉で
説明できるか。**
A. HTML5のドラッグは「mousedown位置から**祖先方向へ**`draggable=true`を探して
実際にドラッグする要素を決める」仕組みである。子要素の `draggable=false` は
「その子要素自身は掴んでも始まらない」という意味に過ぎず、祖先が
`draggable=true` であれば探索はそこで見つかってしまい、結局祖先がドラッグされる。
「除外」を実現するには、祖先側の `dragstart` で「実際に掴んだ場所」を調べて
`preventDefault()` するしかない。

**Q3. 3-2の不具合はなぜ本バッチより前から気づかれなかったのか。**
A. 「上へ/下へ」枠へのドロップという操作自体、これまで実マウスで検証された
機会が無かったため。合成DragEventでの検証では、そもそも `drop` イベントの伝播
（バブリング）の挙動が実ブラウザと一致する保証が無く、この種の「1回の操作で
ハンドラが2回発火する」不具合は見え方が正常時と紛らわしい（1回目の正しい移動が
先に成立するため、パッと見は動いているように見える）。

---

## 6. 次バッチへの申し送り

Batch 20a はこれで完了。設計書5章のとおり、次はフェーズ2（ソロ対戦・対戦・観戦）の
UI詰めセッション、またはVer.0.4対応（15c以降）のどちらかへ進む。どちらを先に
進めてもよく、2系統はファイルが重ならない。

フェーズ2で手札の `faceDown` に「相手へ公開」の意味を与える場合、本バッチ2-3の
HAND正規化（C2）を見直す必要がある点は設計書の注記どおり据え置き。

**新たな申し送り事項（本バッチで判明）**: `registerDropTarget` で登録した要素が
入れ子になる新しいUIを作る場合、`drop` の `stopPropagation()` により内側の
ハンドラだけが働く前提になった。逆に「内側と外側の両方に同じ操作をさせたい」
UIを将来作る場合は、この前提を踏まえて設計すること。
