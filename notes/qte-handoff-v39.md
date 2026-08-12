# QTE 対戦アプリ — 引き継ぎ書

最終更新: 2026-08-12(**Batch 33(切断UXの強化 + 共有導線の整備)を実装・納品**。
商業水準レビュー(`notes/commercial-quality-review-2026-08-11.md`)の優先順位1・2を消化した。
`send()` に接続ガードを入れ(**28 の「無言をやめる」の宿題がここで終わる**)、
切断中の盤面ロック(オーバーレイ)と再接続の通知を足した。
部屋リンク/IDのコピーボタン・favicon・OGP・meta・カスタムエラーページを整備した。
**Java 変更ゼロ**(`application.properties` 2行と `templates/error.html` のみ)。
css v=32 / js v=24。verify **319/319**。
設計解説は `notes/batch33-design-notes.md`)。

**★★`mvn test` は 32b 時点でマスターの手元で実行済み・問題なし(2026-08-11 報告受領)。
30・31・32設計・32a・32b・32c・33 は Java 変更ゼロである。
★ただし 33 は `application.properties` を触っているため、起動確認は必要である。**

**★新しいチャットを始めるときは 5章「チャット開始テンプレート」を使うこと。**

このファイルは、新しいチャットで作業を再開するための最小限の情報をまとめたものである。
ゲームルール・設計判断の詳細は `qte-project-reference.md`、カードの詳細は後述の
「カードデータの正」を参照。

| 系統 | 前提ドキュメント |
|---|---|
| **今後の優先順位(商業水準レビュー)** | **`notes/commercial-quality-review-2026-08-11.md`** |
| **Batch 33(切断UX・共有導線・完了)** | **`notes/batch33-design-notes.md`** |
| Batch 32系(盤面演出・完了) | `notes/batch32-effects-design.md`(32系の唯一の正) |
| Batch 32c(フェイスの質感・完了) | `notes/batch32c-design-notes.md` |
| Batch 32b(状態系・節目系・完了) | `notes/batch32b-design-notes.md` |
| Batch 32a(演出基盤・完了) | `notes/batch32a-design-notes.md` |
| Batch 31(ハーネス黒背景 hotfix・完了) | `notes/batch31-dark-theme-hotfix-notes.md` |
| Batch 30(UI指摘9点・カードテキスト修正・完了) | `notes/batch30-ui-polish-design-notes.md` |
| Batch 29(配信・描画の軽量化・完了) | `notes/batch29-payload-render-design-notes.md` |
| Batch 28(接続の安定化・完了) | `notes/batch28-connection-design-notes.md` |
| Batch 27(進化スタックの解体・不具合修正・完了) | `notes/batch27-evolution-stack-design-notes.md` |
| Batch 26(盤面フェイスの仕上げ・完了) | `notes/batch26-board-polish-design.md` |
| Batch 25(対戦盤面のカードフェイス化・完了) | `notes/batch25-design-notes.md` |
| Batch 24(デッキメーカー・完了) | `notes/batch24-design-notes.md` + `notes/deckmaker-v2-design-notes.md` |
| 手動モード 追加分(22・23・完了) | `notes/batch22-ui-refinement-design.md` + 22/23 の design-notes |
| 手動モード フェーズ2(21a〜21c・完了) | `notes/batch21-versus-ui-design.md` + `batch16-manual-mode-design-v2_4.md` |
| Ver0.4 対応(15c/15d/15e・**要再計画**) | `notes/ver0.4-transcription-notes.md`(★下の「カードデータの正」を先に読むこと) |

---

## 0. 最初にやること

1. `qte-project-reference.md` を読む。★1章の実装状況は2026-07-21のまま古い。
2. 本ファイルと、作業対象の design-notes を読む。3章「既知の落とし穴」は必読。
3. ソースコード取得: `git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git`
   (codeload の zip URL は 403 で落ちることがある)
4. **「反映済み」を信じず、直近バッチの変更箇所を実際に読んで照合する。**

## ★カードデータの正(2026-08-06 マスター裁定、30で3件反映済み)

- **テキスト・数値とも DeckMaker Verβ(`QTE-Vol1-Vol2-Vol3-Ver-1.1.json`)が正。**
- 手動モードは `manual-cards.json` に反映済み(数値44枚更新・text/keywords追加)。
  **30 で追加修正**: 悪夢(QTE-M-DARK-27)コスト1→13、苗木植えの精霊
  (QTE-M-EARTH-16)コスト1→2、ゾンストライカー(QTE-M-DARK-16)の
  `unlimitedCopies` 削除(通常どおり4枚まで)。★これで `unlimitedCopies` を
  持つカードは全カード中 **0枚**になった。
- **台帳 `qte-cards.json` と通常モードの効果実装はVer0.4基準のまま乖離している。**
  Ver1.1への追随は転記ワークフローの再実行として要計画(15c系はこの前提で再スコープ)。
  ★台帳側のゾンストライカーのテキストに「このカードは4枚以上入れられる。」が
  まだ残っている。再計画に含めること。
- 進化ミニオン18枚・Vol2/Vol3のカードは台帳に存在しない(手動モードのみ)。

## 24〜33 の確認項目(履歴・照合済み)

いずれも当時のfresh cloneで全項目確認済み(32c は v39 のチャットで再照合済み)。
再照合は不要。要点のみ残す。

- **24**: `manual-cards.json` が全カードに `text` を持ち `keywords` は存在しないこと
  (hotfix2で廃止・UIへの再導入禁止)。デッキのJSON標準化(`importAuto`)。
  `unlimitedCopies` の構築は `ManualCardRepository.toMaster()` の1箇所のみ。
- **25**: `manual-battle.js` から画像URL組み立てが消え `cardFace` 系に統一。
  25c で全公開部屋の反転統一・盤面ミニオンのフェイス化(`.manual-tile-face`)。
  カラーパレットは `.mcard-frame` + `--mc` に統一(旧CIV_COLORS廃止)。
- **26**: パイル最上段の描画・ドラッグを `pileTopCard(zoneName, pile)` に統一。
  タップ表現は減光+バッジ(★30でマナのみ回転へ再変更)。
  差分チップ `.manual-stat-chip` 廃止。自席リーダーのみ
  `createLeaderTile(seat, { face: true })`。verify 243/243。
- **27**: `ManualOperationService` に `unstack` / `willUnstack`(判定は
  「移動先が FIELD か」)。`move` の挿入位置はカーソル方式。verify 245/245。
- **28**: STOMPハートビート有効化(スケジューラは非Bean)、`sendBufferSizeLimit`
  1MB拡大、部屋消失時の無言 `location.reload()` 廃止。verify 248/248。
- **29**: `ManualViewBuilder` に `LOG_TAIL`(=60)・`deliverable`・`buildZoneCards`。
  `ManualGameView` に `logTotal`、`ManualLobbyController` に
  `zoneContents`(`GET /manual/api/rooms/{roomId}/zone`)。
  `manual-battle.js` に `measurePhase` / `pendingMeasure` / `fetchDeckContents` /
  `zoneFetchSeq` / `LOG_DOM_MAX`。verify 257/257。
- **30**: `manual-cards.json` 3件(上の「カードデータの正」)。マナのタップは
  `rotate(90deg)`・場のタイルは減光のまま(意図した非対称)。`applyManaOverlap` が
  回転外接(`MANA_TILE_HEIGHT`)で幅確保。verify 273/273。
- **31**: ハーネスに実ページの黒背景
  (`body { ... background: #212529; color: #f8f9fa; }`)。
  `verify.js` に「検出器が生きている確認」。css v=28 / js v=21。verify 274/274。
- **32a**: `manual-battle.js` 8-2章に fx層一式(`applyView` / `diffViews` /
  `fxIndex` / `fxWindowedZones` / `fxSpawn` / `fxEnabled` / `prevView`)。
  js v=22 / css v=29。verify 288/288。**Java 変更ゼロ。**
- **32b**: `diffViews` に `tapChanged` / `flipped` / `stackGrew` / `turnAdvanced`。
  `fxBuildTap` / `fxTapStateClass` / `fxBuildFlip` / `fxBuildSink` / `fxBuildTurn`。
  js v=23 / css v=30。verify 300/300。**Java 変更ゼロ。**
- **32c**: `battle.css` の `.mcard-*` が多層 background。共通定義 `--mc-gloss`(形)
  + `--mc-sheen`(強さ)。`verify/faceshot.js` を新設。検証項目63。
  css v=31 / js は v=23 のまま。verify 301/301。**Java・JavaScript とも変更ゼロ。**
- **33**: `manual-battle.js` の「1-2) 切断中のロック」章に
  `isConnected` / `isGateVisible` / `setConnBar` / `updateOfflineLock`、
  「1-3) 部屋の共有」章に `roomShareUrl` / `copyText` / `copyTextFallback` /
  `bindCopyButton`。`send()` は第3引数 `options`(`quiet`)を取り**真偽を返す**。
  `manual-battle.html` に `#manual-offline` / `#manual-conn-bar` /
  `#btn-copy-link` / `#btn-copy-id` / `#seat-gate-copy`。
  `templates/error.html`(新規)・`static/favicon.svg` ほか3点(新規)・
  `tools/make_brand_assets.js`(新規)。全9テンプレートに meta。
  **css v=32 / js v=24。verify 319/319。Java 変更ゼロ。**

## ★裁定記録

**2026-08-06 確定:**

1. **リーダーのcostは0を維持する**(Verβの1はデータ入力上の既定値と扱う)。
2. **進化ミニオンはメインデッキ40枚に算入する**(デッキメーカー実装のとおり)。
3. **[ゲームを始める] は対戦部屋にも表示する**。
4. **進化スタックは FIELD を離れたら常に解体する。** 席をまたぐ FIELD → FIELD
   だけは束のまま。根拠は設計書16 4-5-2。

**2026-08-10・08-11 確定(Batch 32 系。詳細は各 design-notes):**

5. 演出は「移動系・LPポップ・状態系・節目系」+カードフェイス質感を採用。
6. 盤面DOMの差分更新は演出の前提にしない。
7. **★「窓」のゾーンでは出現・消滅を演出しない。**
8. **差分が8件を超えたら演出を丸ごと出さない。**
9. **Undo の逆向き演出は抑制しない。**
10. **★状態系は「居場所が変わっていないカード」だけに出す。**
11. **★ターンの帯は上限(8件)の特例にしない。要らない特例を作らない。**
12. **★★★フェイスの質感に transform と filter を使わない。**(機械判定・項目63)
13. **★擬似要素も使わない。**
14. **★光沢の「形」は `--mc-gloss` 1箇所、変えるのは強さ(`--mc-sheen`)だけ。**
15. **★盤面タイル(`.mcard-frame`)も質感の対象に含める。**

**2026-08-11 確定(商業水準レビュー・前提の更新):**

16. **Discord 通話併用が前提である。** 合図・宣言・雑談・ターン管理は音声が担う。
    → **作らないもの**: チャット・エモート・ターンタイマー・BGM・ロビー自動更新・
    フルリザルト画面・再戦フロー・コーチマーク。
    → **重要度が上がるもの**: 切断/desync の UX・共有導線(リンク+OGP)・部屋の永続化。

**2026-08-12 確定:**

17. **ターン帯を退役させる。**(レビュー改訂2)
    手動モードの `turnNumber` は何の判定にも使われない純粋な記帳であり、
    押し忘れでずれた帯は「嘘をつく演出」になる。
    **退役の範囲は `turnAdvanced` の検出(`diffViews`)と `fxBuildTurn` の呼び出し経路**
    であり、**帯の描画機構(`.manual-fx-turn` 系CSS・z-index 1040 の座席)は
    勝敗の帯へ転用する。**該当する verify 項目も同じバッチで差し替える。
    ★**まだ実施していない**(レビュー優先順位3のバッチで行う)。

**2026-08-12 確定(Batch 33 実装時。詳細は `notes/batch33-design-notes.md`):**

18. **★★★切断中の操作を止める番人は `send()` であって、オーバーレイではない。**
    オーバーレイは宣言であり、畳んでも安全性は落ちない構造にしてある。
    「見えなくすること」を安全装置にすると、覗き見の導線や z-index の並びが
    変わった瞬間に穴が開く。**機械判定あり**(「盤面を覗いても `send()` の
    ガードは効く」)。
19. **★部屋消失と切断の案内を同時に出さない。** 重ね順ではなく
    `updateOfflineLock()` の1行(`connectionFatal` / `isGateVisible()`)で排他にする。
20. **★「再接続しました」は初回の接続では出さない。** 宣言は事実に一致していなければ
    無いほうがましである(裁定11・ターン帯と同じ理屈)。
21. **★エラーページは外部CDNに依存しない。** CDN が落ちているときにも出るページである。
    **機械判定あり。**
22. **★`og:image` は絶対URLで書く。** 相対URLの解決はクローラ依存であり、
    「貼ってみるまで分からない」ものを主動線に置かない。
23. **★Thymeleaf フラグメント化は、実サーバで確認できない間はやらない。**
    フラグメントの解決失敗は全ページを落とす。24 hotfix で踏んだ経路である。

---

## 1. 次の作業の候補(商業水準レビューの優先順位順)

**★レビュー優先順位 1・2 は Batch 33 で消化した。**

1. **★優先順位3: 勝敗の帯 + ログ強調(軽量な終了演出)+ ターン帯の退役**
   (規模 小〜中。裁定17 とセット。`declare` が何も描画せず「ゲームが止まる」現状を潰す)
2. 優先順位4: 初回ヘルプ自動表示 + 動的生成要素への `title` 付与(規模 小)
3. 優先順位5: ロビーのダークテーマ統一(規模 小。白→黒のフラッシュを消す)
4. 優先順位6: Esc 対応 + モーダルのフォーカス管理 + `confirm()` の置換(規模 中)
5. 優先順位7: ゲーム開始の一括演出(配り・ダイス・マリガン)(規模 中)
6. 優先順位8: SFX 6〜8種 + 設定パネル(BGMなし・初期音量控えめ)(規模 中)
7. 優先順位9: デッキメーカーのマナカーブ + 検証一覧 + autosave(規模 中)
8. **優先順位10: 部屋の永続化**(規模 大。通話前提でセッションが長時間化するため
   タッチ対応より先に着手する価値がある、とレビューは提言している)
9. 優先順位11: Pointer Events 移行 + 盤面スケーリング(タブレット対応)(規模 大)
10. テキスト66枚(台帳未転記分)の目視校正(マスター作業)
11. 台帳のVer1.1追随の再計画(通常モード側。ゾンストライカーの誤テキスト含む)
12. `qte-project-reference.md` の更新(1章の実装状況・カードデータの正・裁定記録の反映)

### 完了済みバッチ

17a〜20c(手動モード フェーズ1)/ 21a〜21c(フェーズ2)/ 22(UI8点)/
23(ゲーム開始シーケンス)/ 24(デッキメーカー組み込み・JSONデッキ標準化)/
25(対戦盤面のカードフェイス化)/ 26(盤面フェイスの仕上げ)/
27(進化スタックの解体・不具合修正)/ 28(接続の安定化)/
29(配信・描画の軽量化)/ 30(UI指摘9点・カードテキスト修正3件)/
31(ハーネス黒背景 hotfix)/ 32設計 / 32a / 32b / 32c(= 32系 完了)/
**33(切断UXの強化 + 共有導線の整備)**

---

## 2. コンテキスト効率・発注者とのやりとり

grep 優先でファイルを渡り歩き、全体読み込みは避ける。
判断に迷う点は**まとめて質問する。**1つずつ聞かない。
呼び方は「クロエ」、発注者は「マスター」。会話は日本語カジュアル体、
ドキュメントは通常文体(である調)。

---

## 3. 既知の落とし穴

### サンドボックスの制約

- **★★Maven Central へ到達できない(403)。`mvn test` は納品後にマスターの手元で。**
  `javac -proc:none` による構文走査まではできる(型解決は不可)。
- **★★実サーバを起動できない。したがって Thymeleaf の解決は確認できない。**
  ★テンプレートを変えたら**マスターに実サーバでの確認を必ず依頼すること**。
  ★フラグメント化・インライン式など「解決に失敗すると全ページが落ちる」変更は
  この制約下では行わない(裁定23)。
- **★★codeload の zip URL は 403 で落ちることがある。git clone は通る。**
- **★Playwright は `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers` で通る。**

### 切断・接続(Batch 33 で追加)

- **★★★操作を止めているのは `send()` のガードである(裁定18)。**
  オーバーレイ(`#manual-offline`)は宣言にすぎない。
  「オーバーレイが出ているから安全」と考えて `send()` のガードを外さないこと。
  **検証項目「盤面を覗いても `send()` のガードは効く」が番人である。**
- **★★`send()` は真偽を返すようになった。** 送れなかったときに
  クライアント側で何かを確定させている箇所があれば、戻り値を見ること。
- **★★新しい `send()` に第3引数 `{ quiet: true }` を足すのは揮発メッセージだけ。**
  現在は `dragcue` の3箇所のみ。ビューやログに影響するものを quiet にすると、
  28 で潰した「無言」が別の形で戻る。
- **★`isConnected()` は `client.connected` と `socketDown` の and である。**
  ライブラリ内部のフラグが倒れる順序をこちらは保証できないため、
  自分で観測した事実を重ねてある。片方だけ見るように「簡潔化」しないこと。
- **★切断の案内と部屋消失の案内は排他である(裁定19)。**
  判定は `updateOfflineLock()` の1行。z-index では解決しない。
- **★z-index の並びが増えた: fx層 1030 < 軌跡 1035 < 帯 1040 < 全面表示 1050 <
  ポップオーバー 1055 < トースト 1058 < 席選択ゲート 1060 < 開始の帯 1900 <
  開始モーダル 1950/1960 < 切断オーバーレイ 1970 < 接続の帯 1975。**
- **★接続の帯(`.manual-conn-bar`)は中央のピルである。**全幅にするとヘッダを
  丸ごと覆い、切断中でも使える導線(リンクのコピー)まで隠れる。

### メタ情報・共有導線(Batch 33 で追加)

- **★アイコンと OGP 画像は `tools/make_brand_assets.js` が生成する。**
  PNG を手で差し替えないこと。色の正は `favicon.svg` とこのスクリプトである。
  実行: `PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node tools/make_brand_assets.js`
- **★`description` / `theme-color` / アイコンは全9テンプレートに入っている。**
  テンプレートを増やしたら足すこと(**機械判定あり**)。
- **★`og:image` は絶対URLで直書きしてある**(`https://qte-battle-batch0.onrender.com/...`)。
  公開URLが変わったら手で直す必要がある。設定化は 33 設計解説5章 Q1 で保留中。
- **★部屋URLの組み立ては `roomShareUrl()` の1箇所。**
  `/manual/battle/{roomId}` はサーバの `ManualLobbyController#battle` と対である。
- **★ゲートのコピーボタンは `#seat-gate-buttons` の外側に置くこと。**
  中に置くと席選択の委譲リスナーに拾われる(28 の [入り直す] で踏んだ罠)。
- **★エラーページ(`templates/error.html`)に CDN を書かないこと(裁定21・機械判定あり)。**
  Whitelabel の置き換えはこのファイルの存在だけで成立する
  (`ErrorTemplateMissingCondition`)。`ErrorController` を書き足さないこと。

### カードフェイスの質感(Batch 32c)

- **★★★フェイス(`.mcard-*`)に transform / filter を足さないこと。**
  **検証項目63 が番人**であり、破ると 26 と 32b の既存項目も一緒に落ちる。
- **★フェイスに擬似要素を足さないこと。**
- **★★光沢の形は `--mc-gloss` の1箇所、強さは各所の `--mc-sheen` だけ。**
- **★縁のハイライトは background の層で描く(box-shadow の inset を使わない)。**
- **★フェイスはコントラストの機械判定の対象外である。そのぶん目視が必須。**
  (`verify/faceshot.js`)

### 演出・fx層(Batch 32a・32b)

- **★★演出のイベント検出は「ビューの差分」である。ログを材料にしないこと。**
- **★★「窓」のゾーン(counts > 届いた配列の長さ)では出現・消滅を出さない。**
- **★★配信の反映は `applyView` 1本である。** 差分は再描画の**前**に採る。
- **★★fx層は `position: fixed` で `#manual-root` の外にある。**
  ★**Batch 33 の切断オーバーレイと接続の帯は `#manual-root` の中にある**が、
  どちらも `verify.js` のコントラスト対象へ明示的に登録済みである。
  **新しい文字を作ったら必ず `targets` に足すこと。**
- **★★★遷移のクラスは「最終状態」と一緒に当てる。旧状態と一緒に当てない。**
- **★★`fxSpawn` の確定は `document.documentElement.offsetHeight` である。**
- **★★実要素へ当てる演出は `onStop` で必ずクラスを剥がす。**
- **★動かすのは transform と opacity(と 32b の filter)だけ。**
- **★実要素に当てる演出は `animation` で書く。**
- **★★★タップ表現の非対称は `fxTapStateClass` が要素のクラスから引く。**
- **★演出の時間・上限は定数1箇所**(`FX_*` / `FX_LIMIT` / `FX_BULK_LIMIT`)。
- **★`fxEnabled` は検証の「わざと壊す」入口である。消さないこと。**
- **★ターンの合図(`turnNumber` 増加)は退役が決まっている(裁定17)。**
  帯の描画機構は勝敗の帯へ転用する。

### 性能・接続(Batch 28・29)

- **★★1配信は27KBで頭打ち(Batch 29 で解消)。**
  (a) **★★山札は「最上段の1枚」しか届かない。** 枚数は `counts` から。
  (b) **★★ログは末尾60行しか届かない。** イベント種別は届かない。
  (c) **★★描画関数の中で幅を実測しないこと。**(`pendingMeasure` → `measurePhase`)
  (d) **★ログの差分追記は seq の連続性が前提。**
  (e) 非同期で取りに行くものは通し番号で古い応答を捨てる(`zoneFetchSeq`)。
- **★★`TaskScheduler` 型のBeanを増やさないこと。**
- **★★無言の `location.reload()` を書かないこと。**
- **★★部屋はメモリ上にしか無い。** 接続の寿命がゲームの寿命である。
- **★静的ファイルの版数は `manual-battle.js` / `battle.js` / `battle.css` の
  3系統ある。** 現在 **js v=24 / battle.js v=14 / css v=32**。
  ★`battle.html` の `battle.css` 参照が **v=10 のまま**(共有CSSは v=32)。
  通常モードを再訪するバッチでキャッシュずれに注意。

### カードデータ・デッキ(Batch 24)

- **★★`manual-cards.json` が手動モードとデッキメーカーの共通の正である。**
- **★★デッキの標準形式は JSON。** 判別はサーバが先頭バイト(`PK`)で行う。
  **拡張子で分岐を書かないこと。**
- **★同名無制限はコードに書かない。**(`unlimitedCopies`)
- **★★Thymeleafテンプレートのインライン式に注意(24 hotfixで踏んだ)。**
  角括弧の開きを2つ連ねる書き方はスクリプトごと破壊される。
  **★テンプレートを変えたら実サーバを通して確認すること(マスターに依頼する)。**
- **★リーダーのcostは0のまま(Verβは1)。**

### 進化スタック(Batch 27)

- **★★進化スタックは FIELD にしか存在しない(不変条件)。**
- **★★1つの ref から複数枚が入りうる。**
- **★★「状態にはあるが画面のどこからも触れない」は消失と同じ扱いである。**

### 手動モードの原則(要点)

- **★★手動モードで「判断」を実装しない — 例外はウェポン付け替え(20b)と
  ゲーム開始前処理(23)だけ。** 演出も同じ原則に服する(32)。
- **★★共有ゾーン(PLAY / REVEAL)は席に属さない。`seatId` の無条件参照を書かない。**
- **★★スナップショットに項目を足したら `copy()` を直す。**
- **★★公開範囲・押せるか、の判定を2箇所に書かない。**
- **★★操作の型は `ManualOperationService.apply` が握る。** リセットは絶対に止めない。
- **★MPを直接増減する操作を作らない。カードIDをJavaにリテラルで書かない。**

### UI の落とし穴(要点)

- **静的ファイルを変えたらキャッシュバスティングを上げ、
  `verify/build_harness.py` の置換文字列も同時に直す。**
- **★カードフェイスは `.mcard-*`(battle.css 末尾・手動モード専用)。**
  裏面(`cardBackFace`)は imageId を持たない。
- **★★パイルの最上段は「山札だけ先頭・それ以外は末尾」。**(`pileTopCard`)
- **★★★タップ表現は場のタイル=減光+バッジ、マナ=回転で、意図的に異なる。**
- **★★回転する要素の幅は `applyManaOverlap` が確保している。**
- **★★高さを画面から逆算するときは「入れ物のパディング」まで引くこと。**
- **★盤面のラベルはコントラスト比 4.5:1 以上であることが機械判定される。**
  新しいラベル(fx層・**切断オーバーレイ・接続の帯を含む**)は
  `verify.js` の `targets` に足すこと。フェイスだけは対象外(目視)。
- **★ログのカード名リンクはサーバの `《名前》` 表記に依存している。**
- **★★レイアウト計算で `getBoundingClientRect().top` を素で使わない。**
- **★★差分チップ `.manual-stat-chip` は廃止済み。復活させないこと。**
- **★★リーダーのフェイス化は自席のみ。**
- **★★★描画の席と送信の席を混ぜない。**
- **★★クリック規約: 左=見る / 右=動かす。新しい `contextmenu` には必ず
  `e.preventDefault()`。**
- **★★カード上の専用ボタンは規約の外。** カード本体は `.manual-tile-name` を狙う。
- **★★対戦部屋では非公開ゾーンが `zones` にキーごと現れない。`zoneCount` を使う。**
- **★★flex: 可変幅の列に `min-width:0`。**
- **★★D&D検証は合成DragEventではなく実マウス(`page.mouse`)で。**
- **★`battle.css` は共有ファイル。** `.leader-card` / `.minion-row` / `.mana-chip` /
  `.mana-row` は通常モードも使う。
- **★検証ハーネス: `python3 verify/build_harness.py && node verify/verify.js`
  (319項目)。UIを足したら項目を足す。**

### その他

- occupantId は localStorage(`qte-manual-occupant-{roomId}`)。切断猶予5分は
  `ManualCleanupScheduler` と `ManualViewBuilder` の2箇所にある。片方だけ変えない。
- 無人部屋は5分で自動削除。ゲームの正式名称は「クイン・タブーエレメント」。
- `tools/check_structure.py` は `List.<String>of()` を誤検出(一時変数に受けて回避)。
- `tools/check_records.py` の既知の誤検出3件(GameActions:775 / GameService:1144 /
  CardEffectRegistry:748)。

### ★★検証環境と実環境の差(Batch 31 で痛い目を見た)

- **★★★盤面は黒背景である(`<body class="bg-dark text-light">`)。ロビーは `bg-light`。**
- **★★ハーネスは Bootstrap を落として代替を当てている。代替に漏れがあると
  「ハーネスでだけ違う画面」を検証することになる。**
- **★★ハーネスの STOMP スタブは実物の性質を持たねばならない(Batch 33)。**
  33 で `connected` を持たせた。これが無いと**32 までの全項目が一斉に落ちる**
  (すべての操作が「切断中」として捨てられるため)。
- **★★判定を入れたら、判定が落ちることを1回確かめること。**
- **★★「クラスが付いたか」を見る検証は、演出の検証にならない。**
- **★★`waitForFunction` が投げると検証スクリプトごと死ぬ。** try/catch で FAIL に。
- **★★機械で測れないものを機械で測ろうとしない。測れる決めごとのほうを測る。**
- **★★イベントは実際の入口から起こす(Batch 33)。** 切断は
  `client.onWebSocketClose()` を呼んで作る。フラグを直接倒すと、
  ハンドラの中身を消しても検証が通ってしまう。
- **★半透明の背景は下地で意味が変わる。**
- **★`color-mix()` の計算結果は `color(srgb r g b / a)` 形式(0〜1)で返る。**

## 4. デリバリー形式

zip(変更・新規ファイルのみ)+ `batchNN-design-notes.md` + ハンドオフ更新。
ドキュメントはチャットにファイルとして添付する。
パッケージング前に必ず実行:

```bash
python3 tools/check_structure.py src/main/java
python3 tools/check_all.py .
python3 tools/check_records.py src/main/java
python3 tools/check_undeclared.py src/main/resources/static/js/*.js
node --check src/main/resources/static/js/manual-battle.js
PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers \
  python3 verify/build_harness.py && node verify/verify.js
javac -proc:none -d /tmp/jc $(find src/main/java src/test/java -name '*.java') 2>&1 \
  | grep error: | grep -viE "cannot find symbol|package .* does not exist"
```

★フェイス(`.mcard-*`)に触れたバッチでは、加えて**目視**を必ず行う:

```bash
PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers OUT=/tmp/faces.png node verify/faceshot.js
```

★ブランド資産(アイコン・OGP画像)を変えたバッチでは:

```bash
PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node tools/make_brand_assets.js
```

---

## 5. チャット開始テンプレート

**★次に何をやるかはマスターの選択である。**1章の候補から選んでもらう。

```
QTE Battle の開発を継続する。Batch 34(＜ここに内容＞)を行う。

1. プロジェクトナレッジ内の `qte-project-reference.md` を読む。
2. プロジェクトナレッジ内の `claude/qte-handoff-v39.md` を読む。
   3章「既知の落とし穴」と「カードデータの正」は必読。
3. `notes/commercial-quality-review-2026-08-11.md` を読む(今後の優先順位の正)。
   関連する design-notes も読む。
4. git clone --depth 1 https://github.com/okuson-droid/qte-battle-batch0.git
5. 33 の反映を「24〜33 の確認項目」の 33 の行で照合し、
   verify 319/319 を確認する。反映されていなければ止めて報告する。

Batch 34 の範囲: ＜ここに書く＞

制約:
- サンドボックスから Maven Central へ到達できない。`mvn test` は納品後にこちらで
  走らせるので必ず依頼すること。機械チェック(tools/ と verify/)は実行すること。
- テンプレート(Thymeleaf)を変えたら、実サーバでの確認をこちらに依頼すること。
- 納品形式はハンドオフ4章のとおり。ドキュメントはチャットにファイルとして添付。
- 判断に迷う点はまとめて質問する。1バッチ1チャット。

呼び方は「クロエ」、こちらの呼び方は「マスター」で。会話は日本語カジュアル体、
ドキュメントは通常文体(である調)で。
```

---

## 6. 33 完了時点の積み残し

### マスターにお願いすること(Batch 33 分)

- **`mvn test`**(Java 変更は無いが、`application.properties` を触っている)。
- **実サーバでの目視4点**(33 設計解説5章):
  各ページが従来どおり開くこと / 不正URLでダークテーマのエラーページが出ること /
  Discord に貼ったリンクにカードが出ること / [リンクをコピー] が実際に効くこと。
- **切断の実機確認**(Network を Offline にする、またはサーバを一時停止)。
- **質問3件への回答**(33 設計解説5章 Q1〜Q3: og:image の設定化 /
  [盤面を確認する] の去就 / 次バッチの選択)。

### 継続中の積み残し

- **ターン帯の退役は未実施**(裁定17。優先順位3のバッチで勝敗の帯と同時に行う)。
- **部屋の永続化**(未実施。レビュー優先順位10)。
- **タッチ非対応・アクセシビリティ**(レビュー S-1 / A-1。優先順位11・6)。
- 盤面DOMの差分更新(低優先で据え置き)。
- shuffle・declaration の演出(Java変更とセットで再検討)。
- 演出の速さ・強さ、フェイスの質感の強さの実機確認(マスター)。
- テキスト66枚(台帳未転記分)の目視校正。
- 台帳・通常モードのVer1.1追随(要再計画)。
- `battle.html` の `battle.css` 参照が v=10 のまま。
- 入室前の席選択で「切断中」を表示できない(21bから継続)。
- 拡大画像パネル204px幅 / 相手上段マナ名8px(継続・据え置き)。
- `qte-project-reference.md` 1章の実装状況が古い。

**完了**: Batch 32 系 / **Batch 33(レビュー優先順位1・2)**。
