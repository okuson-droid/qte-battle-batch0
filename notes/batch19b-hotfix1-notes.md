# Batch 19b hotfix1 — 帯からのドラッグ不具合の再調査と修正

対象: `manual-battle.html`(1ファイルのみ)。19b本体の設計判断・チートシートは
`batch19b-design-notes.md` を参照。本ノートは、19b納品後にマスターから受けた
「帯からドラッグできない」報告への対応記録である。

---

## ⚡ 結論

- **19bで実装した帯ドラッグのバグ修正(設計書2-5・`manual-drag-active`)自体は正しく動作していた。**
  実機を用いた検証(後述)で、FIELD・マナ・他のパイルへの帯ドラッグはすべて成功することを
  確認済みである。
- **本当の不具合は別の場所にあった。** `manual-battle.html` の手札(`#hand-row`)の
  マークアップを19bで書き換えた際、誤って外側の入れ物に `renderHand` が内部で生成する
  カード行と同じ `class="hand-row"` と `data-seat`/`data-zone` を重ねて付けてしまっていた。
  結果として実際にドロップ登録されている内側の行が、JSが再生成するラベルの分だけ
  ずれて二重構造になり、帯からのドラッグに限らず**手札へのドロップ全般**が
  意図した場所で受け取れなくなっていた。
- 修正は `manual-battle.html` の該当1箇所のみ。`manual-battle.js` は無変更(18c以前から
  ある `renderHand` の実装は元々正しく、触る必要が無かった)。

---

## 1. 調査の経緯

「反映済みという記述を信じない」の原則に立ち返り、まず前提を1つずつ機械的に確認した。

1. **19bのコードが本当にリポジトリへ反映されているか** → GitHubから再取得し、
   `manual-battle.js(v=4)` と `manual-drag-active` の存在を確認。反映されていた。
2. **`manual-drag-active` のCSS機構そのものが壊れていないか** → この環境に用意されていた
   Playwright(Chromiumヘッドレスブラウザ)を使い、実ファイル(`battle.css` /
   `manual-battle.js`)を読み込んだテストページを作成し、次を直接検証した。
   - `manual-drag-active` クラスの有無で `document.elementFromPoint()` の結果が
     `.manual-band-backdrop` → 実際の下敷き要素へ正しく切り替わること。
   - 帯を実際にクリックで開き(`openZoneBand` 経由)、帯のカードから合成 `DragEvent`
     (`dragstart` → `dragover` → `drop` → `dragend`)で実際にドラッグを再現し、
     ドロップ後に `send('move', ...)` に相当する STOMP publish が発生するかを確認。
3. **ドロップ先ごとに結果を比較した。**

| ドロップ先 | 結果(修正前) |
|---|---|
| Aミニオン行の空き枠(FIELD) | ★成功。`toZone:"FIELD"` で move が飛んだ |
| マナ(表)ストリップ | ★成功。`toZone:"MANA", faceDown:false` で move が飛んだ |
| 消滅(LOST)パイル | ★成功。`toZone:"LOST"` で move が飛んだ |
| **手札(HAND)** | **★失敗。moveが一切飛ばなかった** |

FIELD・マナ・パイルは成功するのに手札だけ失敗する、という結果から、`manual-drag-active`
自体の欠陥ではなく **手札固有の実装ミス** であると判断した。

---

## 2. 原因

`renderHand`(`manual-battle.js`、18c以前から無変更)は、呼ばれるたびに次を行う。

```js
function renderHand(view) {
    const el = document.getElementById('hand-row');
    el.innerHTML = '';
    const label = document.createElement('div');
    label.className = 'small text-muted';
    label.textContent = '手札';
    el.appendChild(label);

    const row = document.createElement('div');
    row.className = 'hand-row';
    row.dataset.seat = 'A';
    row.dataset.zone = 'HAND';
    ...
    registerDropTarget(row, 'A', 'HAND');   // ← ドロップ登録はこの内側のrowに対して行われる
    el.appendChild(row);
}
```

つまり `#hand-row` は本来「中身を毎回作り直すだけの空の入れ物」であることが前提であり、
実際にドロップ登録される要素は関数が**内部で新しく作る** `row`(クラス名がたまたま
同じ `hand-row`)である。

19bで `manual-battle.html` を書き換えた際、この前提を誤解し、外側の入れ物自体に

```html
<div id="hand-row" class="hand-row" data-seat="A" data-zone="HAND"></div>
```

と `renderHand` が内部で作るのと同じ属性一式を付けてしまっていた。`renderHand` 自体は
無変更のまま動くため、実際には次の二重構造ができていた。

```html
<div id="hand-row" class="hand-row" data-seat="A" data-zone="HAND">  <!-- 外側(誤り) -->
    <div class="small text-muted">手札</div>                          <!-- JSが生成 -->
    <div class="hand-row" data-seat="A" data-zone="HAND">             <!-- JSが生成。ここにのみドロップ登録 -->
        ...カード...
    </div>
</div>
```

ドロップ登録されているのは内側の `row` だけであり、外側の `#hand-row` にはドロップ処理が
一切紐づいていない。ユーザーがカードをドロップしようとした位置(外側の枠の見た目)と、
実際に登録されている内側の行の位置(ラベルぶんだけ下にずれている)がずれるため、
狙った場所でドロップが受け取られない状況になっていた。

---

## 3. 修正

`manual-battle.html` の手札ブロックを、18c以前と同じ「プレーンな入れ物」へ戻した。

```html
<!-- 修正前 -->
<div class="board-side p-2">
    <div class="small text-muted">手札</div>
    <div id="hand-row" class="hand-row" data-seat="A" data-zone="HAND"></div>
</div>

<!-- 修正後 -->
<div id="hand-row" class="board-side p-2"></div>
```

`renderHand` 側は一切変更していない。ラベル・カード行・ドロップ登録はすべて
`renderHand` が毎回生成する内容に一本化された。

---

## 4. 検証(修正後)

同じPlaywrightハーネスで、修正後のファイルに対して再度4パターンを実行し、
すべて成功することを確認した。

| ドロップ先 | 結果(修正後) |
|---|---|
| Aミニオン行の空き枠(FIELD) | 成功(変化なし) |
| マナ(表)ストリップ | 成功(変化なし) |
| 消滅(LOST)パイル | 成功(変化なし) |
| **手札(HAND)** | **★成功に変わった**。`toZone:"HAND"` で move が飛んだ |

機械チェックも再実行し、全て通過を確認した。

- `node --check src/main/resources/static/js/manual-battle.js` → 構文エラー無し
- `tools/check_undeclared.py` → 3ファイルとも未宣言の識別子無し
- `tools/check_structure.py` → 異常無し(Javaは今回も無変更)
- `<div>` の開閉タグ数の一致を確認(32 / 32)

---

## 5. この調査で得られた教訓

- **「帯からドラッグできない」という報告文言だけで原因を1つに決め打ちしないこと。**
  19bで実装した対象(帯の背後にあるバックドロップ)の修正自体は正しかったが、
  報告された症状は別の独立した不具合(手札固有の二重登録)によるものだった。
  症状が一致していても原因が違う場合があるため、疑わしい箇所を1つに絞らず
  実機検証で候補を1つずつ切り分けることが有効だった。
- **この環境にはPlaywright(Chromiumヘッドレス)が用意されており、実際のCSS/JSファイルを
  読み込んでの検証が可能である。** 今後、DOM構造やドラッグ&ドロップが絡む不具合報告を
  受けた際は、まずこの手段で実機検証してから修正することを標準の手順とする。
  (ブラウザ環境のネットワークはこのサンドボックス自体の制限を受けるため、
  外部CDN(Bootstrap等)は読み込めない点に注意。テスト用HTMLではCDN依存箇所を
  スタブするか、影響のない形に調整する必要がある。)
- **HTMLテンプレートを書き換える際は、対応するJS描画関数が「入れ物への追記」を
  前提にしているか「入れ物ごと使う」ことを前提にしているかを、関数の中身を読んで
  確認してから書くこと。** 今回はJS側(`renderHand`)を見落とし、命名の一致だけで
  同じ構造だと思い込んだことが原因だった。
