# Batch 17b 設計解説 — 手動モードの状態モデル・部屋・配信・デッキ取り込み

対象: QTE Battle 手動モード(フェイズ1 = 一人回し)の2本目。
前提: `batch16-manual-mode-design-v2_2.md`(手動モードの唯一の正) + `notes/batch17a-design-notes.md`。
範囲: 設計書 2-3 / 2-4 / 4-5 / 5章 / 6章 / 7章。

本バッチは**既存ファイルを1行も変更していない**。29ファイルすべてが新規である。

---

## ⚡ 結論チートシート

- **状態モデルの芯は3つ。**
  `ManualCardInstance`(数値は attack / hp の現在値2つだけ + 平坦な進化スタック)、
  `ManualSeat`(全ゾーンを `EnumMap` 1本で持ち `zone(ManualZone)` で引く)、
  `ManualGameState`(`copy()` を持ち、**ログを含まない**)。
- **★進化スタックは平坦である。** 最上段のインスタンスが `materials`(素材の並び)を持ち、
  入れ子にしない。`+n` バッジの n が `materials.size()` そのものになる(2-2)。
- **★ゾーンは Map で一様に持つ。** 18a の操作13項目の大半が
  「ゾーンAの i 番目をゾーンBの j 番目へ」であり、この一様性がそのまま実装の短さになる(2-1)。
- 配信は**在室者ごとの個別宛先** `/topic/manual/{roomId}/view/{occupantId}`。
  `WebSocketConfig` は1行も触っていない。
- **★他人の occupantId をビューに載せない。** 宛先の一部であり、知ることが受信の権利になる(2-6)。
- デッキ取り込みの**突合キーは表面画像IDのみ**。実サンプル49枚すべてが解決した。
  main.xml の先頭リーダーは名前が誤って保存されているが、画像IDで正しく解決する(3-3)。
- **★`ManualHistory.push()` は中で複製する。** 設計書 5-6 の `push(state.copy())` から
  意図的に外した(2-4)。
- **★デッキ zip を multipart で受けない。** `@RequestBody byte[]` で素のバイト列として受ける。
  multipart の 1MB 上限を上げるとアプリ全体の設定に触ることになるためである(2-7)。
- **★Batch 15b はリポジトリに反映されていない(3回連続)**(0章)。17b とは重ならないため作業は進めた。

---

## 0. ★★★ 着手前の報告

### 0-1. Batch 17a は完全に反映されている

指示された4点を、記述ではなく実データで照合した。

| 確認項目 | 結果 |
|---|---|
| `src/main/resources/cards/manual-cards.json` | 存在。`meta.total` = 235、`cards` 配列も 235 件 |
| `src/main/java/com/example/qte/manual/` | `ManualCardMaster` / `ManualCardRepository` / `ManualCardType` /<br>`ManualCivilization` + `web/ManualCardsController` の5ファイルが存在 |
| `ManualCardMasterLoadTest` の `countType` | **44行目に `private long countType(ManualCardType type)` の宣言あり**<br>(初回納品でビルドを落とした箇所は修正済み) |
| `tools/convert_manual_cards.py` と `tools/csv/` | スクリプトあり。CSV 6本 + `.gitattributes` あり |

### 0-2. Batch 15b は今回も入っていない(3回連続)

| 確認項目 | 期待(15b完了後) | 今回の main |
|---|---|---|
| イグニッション・バースト(0064) | 自傷2 | `damageLeader(ctx.room(), ctx.owner(), 1, …)`<br>(`CardEffectRegistry.java:539`) |
| `ResumePoint` | `AQUA_SEARCH_DISCARD` あり | 7種のみ。リポジトリ全体を grep しても不在 |
| `notes/` | `batch15b-design-notes.md` あり | 存在しない |

17b は 15b と1ファイルも重ならないため作業は進めた。**15c 着手前に push すること。**

---

## 1. 作ったもの

```
src/main/java/com/example/qte/room/
└── RoomIds.java                      部屋ID生成の static ユーティリティ(新規・共用予定)

src/main/java/com/example/qte/manual/
├── ManualZone.java                   DECK/HAND/MANA/FIELD/WEAPON/TRASH/LOST/TABOO/REVEAL
├── ManualSeatId.java                 A / B
├── ManualPhase.java                  7フェイズ(表示のみ。強制しない)
├── ManualOccupantRole.java           PLAYER / SPECTATOR
├── ManualCardInstance.java           ★ゾーン上のカード1枚。進化スタックを持つ
├── ManualSeat.java                   ★席1つ分。全ゾーンを EnumMap で一様に持つ
├── ManualGameState.java              1試合の全状態。copy() を持つ。ログは含まない
├── ManualHistory.java                Undo/Redo のスナップショットスタック(深さ200)
├── ManualLogEntry.java               ログ1行(seq / 時刻 / 本文)
├── ManualOccupant.java               在室者。occupantId は配信先の一部
├── ManualRoom.java                   部屋。盤面 + ログ + 履歴 + 在室者
├── ManualRoomManager.java            部屋の台帳
├── ManualDeckImport.java             取り込み結果(リーダー / メイン / 禁忌 / 警告)
├── ManualDeckImporter.java           ★ユドナリウム card-stack XML(zip)の読み込み
└── ManualGameService.java            状態変更。17b は開始処理のみ

src/main/java/com/example/qte/manual/view/
├── ManualCardView.java               印刷値と現在値を両方載せる
├── ManualSeatView.java               ゾーンは Map で送る
├── ManualOccupantView.java           ★occupantId を載せない
├── ManualLogView.java                時刻は整形済み文字列
├── ManualGameView.java               在室者1人に送る全体
└── ManualViewBuilder.java            組み立て

src/main/java/com/example/qte/manual/web/
├── ManualBroadcaster.java            ★個別宛先への配信
├── ManualWsController.java           /app/manual/{roomId}/{action}
└── ManualLobbyController.java        部屋作成・入室・デッキ取り込み(HTTP)

src/main/resources/templates/
└── manual-deck-check.html            取り込みの目視確認画面(作り込まない)

src/test/java/com/example/qte/
└── ManualDeckImportTest.java

src/test/resources/decks/
├── sample-deck.zip                   実サンプル(main.xml 41枚 / kinki.xml 8枚)
└── .gitattributes                    *.zip -text
```

---

## 2. 設計判断

### 2-1. ★ ゾーンをフィールドに展開せず Map 1本で持った

`ManualSeat` は `EnumMap<ManualZone, List<ManualCardInstance>>` を持ち、
`zone(ManualZone)` で引く。`deck` / `hand` / `mana` … と9個のフィールドに分けていない。

理由は Batch 18a の形にある。設計書 5-3 の操作13項目のうち、実装量の大半を占めるのは
「ゾーン間移動(挿入位置・表裏・複数枚)」である。移動元と移動先を同じ型で受け取れれば、
実装は次の2行に収まる。

```java
ManualCardInstance card = seat.zone(from).remove(fromIndex);
seat.zone(to).add(toIndex, card);
```

ゾーンごとに専用フィールドを持つと、9×9の組み合わせを switch で書き分けることになる。
ゾーンが1つ増えるたびに、増える手数が線形ではなく二乗で効く形は採らない。

リーダーだけはこの Map に入れない。1枚しか存在せず、他のゾーンへ移動しないためである。
「0枚以上のカードが出入りする入れ物」という定義から外れるものを混ぜると、
`zone()` の返り値に対する不変条件が弱くなる。

WEAPON は仕様上1枚だが、他と同じくリストで持つ。
「装備済みの枠には落とせない」は画面側の規約(設計書 4-5)であり、
状態モデルが枚数を強制すると、人間が一時的に2枚置いて考えることすらできなくなる。

### 2-2. ★ 進化スタックを平坦にした

`ManualCardInstance` が `List<ManualCardInstance> materials` を持ち、
**入れ子にしない**。進化ミニオンの上にさらに進化を重ねた場合も、
下の進化ミニオンは素材リストの1要素として平らに並ぶ。

根拠は設計書 4-5-1 の `+n` バッジの定義である。

> n = スタック内の最上段以外の枚数とする。進化ミニオンも数に含める。
> 3体を素材にすれば `+3`、その上にさらに進化を重ねれば `+4` である。

3体を素材にした状態(n=3)にもう1枚重ねて n=4 になるということは、
**数え方が階層を無視している**ということである。入れ子にすると n の計算が再帰になり、
4-5-2 の「束の中身を帯として開き、1枚ずつ任意のゾーンへドラッグする」も
階層を持つことになる。帯は1列である。データも1列にしておく。

並び順は設計書 4-5-1 のとおり「ミニオンゾーンの左からの並び順」で積む。
先頭が最下段、末尾が最上段のすぐ下である。順序に意味は無いが、再現性のために固定する。

数値は各インスタンスが自分のぶんを持つ。表示されるのは最上段の数値であり、
素材は重ねる前の数値を保ったまま下に眠る。
帯から素材を抜いてミニオンゾーンの空き枠へ落とせば、その数値のまま場に戻る。
4-5-2 の「帯から1枚抜いてもタイルの数値は変えない」も、
数値が最上段のインスタンスに属している以上、自動的に満たされる。

**最上段だけを抜く操作は定義していない。** 設計書が定めているのは
「束全体を動かす」と「帯から最上段以外を抜く」の2つだけである。
必要になったら 18a で足す。無い操作を先回りで作らない。

### 2-3. ★ ミニオンの数値は現在値の1軸だけ / 印刷値は配信のたびに引き直す

設計書 4-3(レビューA反映)のとおり、maxHp / damage の2軸案は採らない。
状態が持つのは attack / hp の現在値だけである。

一方、画面は増減を白チップで示す必要があり、比較対象として印刷値が要る。
これを状態に複製せず、**配信のたびに `ManualCardRepository` から引き直す**。
`ManualCardView` が `printedAttack` / `printedHp` と `attack` / `hp` を並べて持つのはそのためである。

状態にカード定義を複製しておくと、(1) スナップショットが太って Undo の前提(丸ごとコピーが最も安い)が崩れ、
(2) カード定義を直したときに履歴の中だけ古い値が残る。
**状態が持つのは cardId だけ**にしておけば、どちらも起きない。

### 2-4. ★ `ManualHistory.push()` は中で複製する — 設計書の記述から意図的に外した

設計書 5-6 は `history.push(state.copy())` と書いているが、実装では
`push(ManualGameState)` が内部で `copy()` を呼ぶ。呼び出し側で複製する必要は無い。

呼び出し側の責務にすると、18a で足す操作13項目のうち1箇所でも `copy()` を書き忘れた瞬間、
**「履歴に積んだはずの状態が、その後の操作で一緒に書き換わる」**という不具合になる。
これは Undo を実行するまで症状が出ず、出たときにも「Undo が効かない」という
原因から遠い形で現れる。器の側で閉じるほうが圧倒的に安い。

`undo(current)` / `redo(current)` も同様に、渡された現在状態を中で複製して反対側のスタックへ積む。
Redo は同じ履歴の仕組みの上で提供し、新しい操作を積んだ時点で無効になる
(枝分かれした未来は保持しない)。

深さは 200 で打ち切り、古いものから捨てる(レビューJ反映)。
両端に触れる必要があるため `ArrayDeque` を使っている。

### 2-5. ★ ログと履歴は `ManualGameState` の外に置いた

- **ログは `ManualRoom` が持つ**(設計書 5-5・レビューE反映)。
  ログが状態の中にあると、Undo のたびにログまで巻き戻り、
  「何をして、それを取り消した」という記録そのものが消える。
  アプリが効果を解決しない以上、何が起きたのかを記録できるのは人間だけであり、
  **ログはこのモードの成果物**である。追記専用にしなければならない。
- **履歴も `ManualRoom` が持つ。** スナップショットの中にスナップショットのスタックがあると、
  コピーのたびに履歴ごと複製されて指数的に膨らむ。

`ManualRoom.log` は古い行を捨てない(既存の `GameRoom` は60行で切り捨てている)。
これは設計書 5-5 の明示的な要求である。書き出し(Batch 19a)まで全部残す。

### 2-6. ★ 他人の occupantId をビューに載せない

配信先は `/topic/manual/{roomId}/view/{occupantId}` である。
SimpleBroker は購読を認可しないため、**宛先を知っていることがそのまま受信の権利**になる。
したがって在室者リストに他人の occupantId を載せると、
フェイズ2で「観戦を許可していない部屋の観戦」や「相手席の視点の盗み見」が
仕様上の穴ではなく配信データの中身として成立してしまう。

`ManualOccupantView` は名前・役割・接続状態・`self` フラグだけを持つ。
在室者リスト(設計書 11-2)に必要なのはこれで足りる。

なおこの宛先形式をフェイズ1のうちに入れておく理由は設計書 2-4 のとおりである。
フェイズ1では在室者が1人なので実質1本しか流れないが、
**宛先は配管であり、後から変えると 17b〜18c を掘り返すことになる。**

### 2-7. ★ デッキ zip を multipart で受けない

`ManualLobbyController.importDeck` は `@RequestBody byte[]` で zip の生バイト列を受ける。
`MultipartFile` を使っていない。

`MultipartFile` にすると Spring Boot 既定の 1MB 上限に当たる。
ユドナリウムの保存 zip は画像を同梱することがあり(設計書 7-2・レビューL)、
49枚ぶんの画像が入れば容易に 1MB を超える。
上限を上げるには `application.properties` を書き換えるか
`MultipartConfigElement` を差し替えることになり、どちらも
**通常モードを含むアプリ全体の設定に触る**。
本バッチの「既存ファイルを1行も変更しない」という制約以前に、
手動モードの都合で通常モードの受け口の性質を変えるのは設計書 2-1 の方針に反する。

zip の中身は1つのバイト列でしかない。素で受ければ設定を1行も足さずに済む。
クライアントは `fetch(url, {method:'POST', body: file})` で送るだけである。

### 2-8. ★ 部屋ID生成を `RoomIds` に切り出したが、通常モードはまだ使っていない

設計書 2-1 は「部屋ID生成ロジックを `static` ユーティリティに切り出して共用」と定めている。
`com.example.qte.room.RoomIds` を新設し、`ManualRoomManager` がこれを使う。

ただし **`GameRoomManager` は同じロジックを private メソッドとして持ったままである。**
切り出しを完了させるには既存ファイルの変更が必要であり、本バッチの制約に反するためである。
差し替えは既存ファイルの変更が許されるバッチ(19a)で行う。

現状は2つの実装が並立するが、文字集合(`ABCDEFGHJKLMNPQRSTUVWXYZ23456789`)と長さ(6)は
同一であることを目視で確認しており、両者のIDが偶然一致しても
**引く先が別の Map なので実害は無い**(宛先の前置詞も `/app/room` と `/app/manual` で分かれている)。

### 2-9. ★ 突合キーは表面画像IDのみ — 名前は一切使わない

`ManualDeckImporter` は `ManualCardRepository.findByImageId()` だけでカードを引く。
理由は3つある。

1. 表記ゆれが実在する(全角スペース・中黒・`【` の欠落)。
2. **名前が誤って保存されているカードが実在する。**
   実サンプルの `main.xml` の先頭は `リーダー：【傷痕の闘帝】` だが、
   これは水文明の `流転の智者`(QTE-L003)である(設計書 1-3)。
   名前で突合していたら、火文明のリーダーに化けるか突合に失敗する。
3. 画像IDは内容に対応する不透明なキーであり、突合が成立すれば確実に同じカードである。

この性質はテスト `名前が誤っていても画像IDで正しいカードに解決する()` で固定してある。

### 2-10. XML は階層をたどらない / 受け取った XML を信用しない

`<card>` 要素の配下を**全走査**し、`name="front"` と `name="name"` を拾う(設計書 1-8)。
「card の下の image の下の front」のように道順を書くと、
ユドナリウム側の構造が変わった瞬間に**無言で0枚になる**。

唯一の例外が card-stack 自身の名前(「水_メイン」など)で、ここだけ直下の子をたどる。
`<card>` という目印が無いため、全走査すると1枚目のカード名を拾ってしまうからである。
取れなければ黙って null にする(表示に使うだけの値である)。

パースでは DTD と外部実体を無効化している。
これは利用者を疑う話ではなく、**他人から受け取ったデッキファイルが普通に流通する道具**だからである。

### 2-11. zip のエントリ名を2つの文字コードで試す

`ZipInputStream` は UTF-8 として不正なバイト列のエントリ名に出会うと例外で止まる。
Windows のエクスプローラで作った zip(日本語ファイル名が CP932)は、
画像が1枚でも同梱されていればこれで落ちる。
1回目に失敗したときだけ `windows-31j` で読み直す。
デッキXML自体の文字コードは XML 宣言に従うため、この件と無関係である。

### 2-12. 検証は行うが違反は警告に留める

設計書 7-4 のとおり、40枚 / 禁忌8枚 / 同名4枚 / 禁忌同名1枚 / リーダーと異なる文明を検証するが、
**すべて警告であり、違反していても開始できる。**
`DeckValidator` には触らず、**実装済み文明・実装済みスペルの判定は一切行わない。**

画像IDが `manual-cards.json` に無いカードも捨てず、
名前だけの灰色タイルとして山札に入れる(設計書 7-3)。
手動モードは効果を判定しないため、名前さえあれば遊べる。厳しく弾くほうが検証の邪魔になる。

リーダーが先頭にいなければ、種別がリーダーの行を探して代用し、警告に出す。
ファイル名が `main.xml` / `kinki.xml` でなければ、
名前の手掛かり(`main` / `メイン` / `kinki` / `禁忌`)で判別し、
それでも決まらなければ枚数の多い方をメインとして警告を出す。

### 2-13. Batch 17b の WebSocket 操作は ready と resync だけである

設計書 5-3 の操作13項目は Batch 18a の範囲である。
17b が置いたのは、それらが同じ形で並ぶための配管である。

```java
private void execute(String roomId, String occupantId, ManualRoomAction action) {
    // 1) 部屋を特定 2) synchronized(room.getLock()) 3) 状態変更
    // 4) 成功なら全在室者へ配信 / 失敗なら操作者にだけエラー
}
```

18a は `@MessageMapping` と `ManualGameService` のメソッドを足すだけでよい。

**occupantId を WebSocket で発行しない理由**は、配信先が
`/topic/manual/{roomId}/view/{occupantId}` である以上、
occupantId を知る前のクライアントには受信できる宛先が存在しないためである。
入室は HTTP で行い、受け取った occupantId で購読してから `ready` を送る。
通常モードで playerId を `LobbyController` が発行しているのと同じ構造である。

### 2-14. `ManualGameService` は自分でロックを取らない

呼び出し側が `synchronized (room.getLock())` の中で呼ぶ前提で書いてある。
「1つの操作」がどこからどこまでかを知っているのは入口(コントローラ)であり、
業務層が自前でロックを取ると、2つの操作をまとめて1つに見せたいときに
ロックの入れ子か取りこぼしのどちらかが起きる。既存の `GameService` と同じ方針である。

---

## 3. 検証結果

### 3-1. 実サンプルのデッキ

| 項目 | 結果 |
|---|---|
| `main.xml` | 41枚(先頭がリーダー) |
| `kinki.xml` | 8枚 |
| **カード定義に解決した枚数** | **49 / 49(未解決0)** |
| リーダー | 流転の智者(水文明)。**XMLの名前は「傷痕の闘帝」** |
| メイン40枚の文明 | 全て水。同名は最大4枚 |
| 禁忌8枚 | 土2 / 風2 / 光2 / 火1 / 闇1。同名は1枚まで |
| 警告 | **0件** |

### 3-2. 機械チェック

| スクリプト | 結果 |
|---|---|
| `check_structure.py src/main/java` | 異常0件 |
| `check_all.py .` | 項目 1・3・5・6 すべてパス(カードID 169種すべて台帳に実在) |
| `check_records.py src/main/java` | 既存の★3件のみ。**新しい★は無し** |
| `check_undeclared.py`(既存JS) | 0件 |
| `node --check`(既存JS + 新規テンプレートの inline JS) | OK |

### 3-3. ★ `check_records.py` が見ない新規 record を手動検算した

`TARGETS` が固定リストのため、手動モードの record は検査対象に入らない。
同じロジックの検算を別途行い、**16個の record すべてで宣言のコンポーネント数と
`new` の引数数が一致**していることを確認した。

ただし1件だけ★が出る。これは**名前の衝突による偽陽性**である。

```
record Entry: コンポーネント 2 個 (DeckDefinition.java)
  ★不一致 ManualDeckImporter.java:340 引数 3 個
```

`ManualDeckImport.Entry` は3コンポーネントであり、
`ManualDeckImporter.java:340` の `new ManualDeckImport.Entry(found.orElse(null), raw.name(), raw.imageId())`
も3引数である。スクリプトが同名の `DeckDefinition.Entry`(2コンポーネント)を先に見つけ、
そちらと突き合わせているだけである。該当行を目視して確認済みである。

### 3-4. ★ `src/test` の素呼び出しを検査した

17a が初回納品でビルドを落とした形(テストが宣言していないヘルパを呼ぶ)を防ぐため、
`check_structure.py` の B 検査を `src/test` 向けに調整したものを別途走らせた
(静的インポートと `@Test` の日本語メソッド名を除外する)。

`CardMasterLoadTest` / `ManualCardMasterLoadTest` / `ManualDeckImportTest` の3ファイルとも
**未解決の素呼び出し0件**である。`ManualDeckImportTest` が宣言しているヘルパは
`sampleDeckZip()` / `importSample()` / `catchImportFailure(byte[])` の3つで、すべて宣言済みである。

**この環境では Maven ビルドができないため、型エラーは発注者の手元でしか出ない。**

---

## 4. ✅ 検証手順

### 4-1. 通常モードが従来どおり動くこと

既存ファイルを1行も変更していないため壊れようがないが、
新設したビーンの読み込みに失敗するとアプリ全体が起動しない。

- `http://localhost:8080/` でロビーが出る
- `http://localhost:8080/cards` で従来のカード一覧(169枚)が出る
- 対戦を1試合開始できる

### 4-2. `/manual/cards` が引き続き235枚を表示すること

```
http://localhost:8080/manual/cards
```

上部の合計が 235、種別が LEADER 18 / MINION 119 / EVOLUTION 18 / SPELL 61 / WEAPON 19 であること。

### 4-3. ★ デッキ取り込みの目視確認

```
http://localhost:8080/manual/deck-check
```

1. デッキ zip(`main.xml` と `kinki.xml` を含むもの)を選ぶ。
2. **「警告なし。49枚すべてがカード定義に解決した。」の緑の帯が出ること。**
3. 表の値が次のとおりであること。
   - リーダー: **流転の智者**(XMLには「傷痕の闘帝」と書かれているが、画像IDで正しく解決する)
   - 枚数: リーダー込み 49枚 / メイン 40枚 / 禁忌 8枚
   - 未解決: **0枚**
4. 手札が4枚、禁忌が8枚、山札が36枚並び、**画像が1枚も割れていないこと。**
   灰色の背景のまま画像が出ないタイルがあれば、それが未解決カードである。
5. 読み込むたびに手札の中身が変わること(シャッフルが効いている)。

この画面は目視確認専用であり作り込んでいない。本物の盤面は Batch 18b で作る。

### 4-4. テスト

Eclipse で `ManualDeckImportTest` を右クリック → Run As → JUnit Test。

**★`CardMasterLoadTest.台帳の全カードが読み込まれる()` は赤いままである**
(`hasSize(72)` に対して台帳は169枚)。Batch 3 頃の値のまま更新されていない既知の問題で、
17b も既存ファイル変更禁止のため触っていない。
`Dockerfile` は `-DskipTests` であるためデプロイには影響しない。

### 4-5. 機械チェックの再実行

```bash
python3 tools/check_structure.py src/main/java
python3 tools/check_all.py .
python3 tools/check_records.py src/main/java
python3 tools/check_undeclared.py src/main/resources/static/js/*.js
node --check src/main/resources/static/js/battle.js
```

---

## 5. ✅ 理解確認

<details>
<summary>Q1. なぜログを `ManualGameState` ではなく `ManualRoom` に置いたのか</summary>

Undo のためである。`ManualGameState` はスナップショットとして丸ごと複製され、履歴に積まれる。
ログがこの中にあると Undo のたびにログまで巻き戻り、
「何をして、それを取り消した」という記録そのものが消える。
アプリが効果を解決しない手動モードでは、**ログが唯一の成果物**であり、
追記専用でなければならない。
</details>

<details>
<summary>Q2. 進化ミニオンの上に進化ミニオンを重ねたとき、データはどうなるか</summary>

**平らに並ぶ。** 新しく乗せた進化ミニオンが最上段になり、
下にあった進化ミニオンは「その素材リストの1要素」として、
元の素材たちと同じ列に並ぶ。入れ子にはしない。

根拠は設計書 4-5-1 の `+n` バッジで、「3体を素材にすれば +3、
その上にさらに進化を重ねれば +4」と定めている。
n が 3 → 4 になるということは、数え方が階層を無視しているということである。
</details>

<details>
<summary>Q3. ミニオンの現在HPが5、印刷値が3のとき、状態モデルは何を持っているか</summary>

**5だけである。** 印刷値は状態が持たない。
画面が増減を白チップで示すために印刷値が要るが、それは配信のたびに
`ManualCardRepository` から `cardId` で引き直して `ManualCardView` に載せる。

状態にカード定義を複製すると、スナップショットが太って Undo の前提が崩れ、
カード定義を直したときに履歴の中だけ古い値が残る。
</details>

<details>
<summary>Q4. 18a で操作を実装するとき、`history.push(state.copy())` と書くべきか</summary>

**書かなくてよい。** `ManualHistory.push()` が中で複製する。
`history.push(room.getGameState())` で足りる(二重に copy() しても動作は正しいが無駄である)。

設計書 5-6 の記述から意図的に外している。呼び出し側の責務にすると、
1箇所でも書き忘れた瞬間に「履歴に積んだはずの状態が一緒に書き換わる」不具合になり、
Undo を実行するまで症状が出ない。
</details>

<details>
<summary>Q5. デッキXMLの1枚目の名前が「リーダー：【傷痕の闘帝】」だった。火文明のリーダーとして読むか</summary>

**読まない。** 突合キーは表面画像IDのみである。
このカードの画像IDは水文明の「流転の智者」(QTE-L003)を指しており、
名前のほうが誤って保存されている(設計書 1-3)。
名前は表記ゆれも誤りもあるため、突合に一切使わない。
</details>

<details>
<summary>Q6. デッキの中に `manual-cards.json` に無い画像IDのカードがあった。どうなるか</summary>

**捨てずに、名前だけの灰色タイルとして山札に入る**(設計書 7-3)。
`ManualCardInstance.cardId` が null になり、`fallbackName` に XML の名前が入る。
警告として一覧に出るが、読み込みは成功して開始できる。

手動モードは効果を判定しないため、名前さえあれば遊べる。
厳しく弾くほうが検証の邪魔になる。
</details>

<details>
<summary>Q7. 在室者リストに他の在室者の occupantId を載せてよいか</summary>

**駄目である。** occupantId は配信先
`/topic/manual/{roomId}/view/{occupantId}` の一部であり、
SimpleBroker は購読を認可しないため、**知っていることがそのまま受信の権利**になる。
`ManualOccupantView` が持つのは名前・役割・接続状態と、自分かどうかのフラグだけである。
</details>

<details>
<summary>Q8. デッキ zip を `MultipartFile` で受けなかったのはなぜか</summary>

Spring Boot 既定の multipart 上限が 1MB であり、
画像を同梱したユドナリウムの保存 zip は容易にこれを超えるためである。
上限を上げるには `application.properties` か `MultipartConfigElement` に触ることになり、
どちらも**通常モードを含むアプリ全体の設定**を変えてしまう。
zip は1つのバイト列でしかないので、`@RequestBody byte[]` で素のまま受ける。
</details>

<details>
<summary>Q9. `zone(ManualZone.WEAPON)` が2枚返ってきた。バグか</summary>

**バグではない。** 状態モデルはウェポンの枚数を強制しない。
「装備済みの枠には落とせない(赤く点滅させる)」は画面側の規約(設計書 4-5)である。
手動モードのサーバは判断を持たない。人間が一時的に2枚置いて考えることを妨げない。
</details>

---

## 6. 積み残し(17b の範囲外)

| 項目 | 内容 |
|---|---|
| **★Batch 15b の push** | 15c 着手前に必須(0-2)。**3回連続で未反映** |
| **`GameRoomManager` の `RoomIds` 化** | 切り出しは済んでいるが通常モードが未使用(2-8)。既存ファイル変更が許される 19a で |
| **既存テストが赤い** | `CardMasterLoadTest.台帳の全カードが読み込まれる()` が `hasSize(72)`。台帳は169枚 |
| `check_records.py` の `TARGETS` | 手動モードの record 16個が未登録(3-3)。`tools/` の改修時にまとめて追加 |
| `check_structure.py` の `src/test` 対応 | 今回は調整版を別途走らせて代用した(3-4)。恒久対処は `tools/` の改修時 |
| 部屋の掃除 | `ManualRoomManager.removeRoom()` はあるが、誰も呼んでいない。<br>切断猶予(設計書 6-3)と合わせて 19a で |
| 名前と occupantId の `localStorage` 保存 | 設計書 6-3。画面が無いため 18b / 19a |

---

## 7. 次バッチ予告 — Batch 18a(a系・Opus + 拡張思考)

**操作の実装(設計書 5-3 の13項目)・進化スタックの分解・Undo。**
17b が作った状態モデルの上に載る。既存ファイルの変更は無い。

| 作るもの | 要点 |
|---|---|
| ゾーン間移動 | 挿入位置(上/下)・表裏・複数枚。`seat.zone(from)` → `seat.zone(to)` |
| LP / ATK / HP の変更 | 現在値の直接書き換え。上限は強制しない |
| 札の付け外し | `ManualCardInstance.labels` への追加・削除。既定9種 + 自由入力 |
| タップ / 表裏 / ウェポン使用済み | 既にフィールドがある。操作を足すだけ |
| **進化スタックの積み(複数素材)/ 分解** | ★枠数が N → 1 に減る(設計書 4-5-1)。<br>通常のカードプレイに無い性質であり、実装時に注意を要する |
| ターン番号・フェイズ | `ManualPhase.forward()` / `backward()` は用意済み |
| 山札のシャッフル | `ManualGameService.shuffleDeck()` は用意済み |
| 勝敗の宣言 / 自由メモ | ログへの追記のみ。盤面には触らない |
| **Undo / Redo の適用** | `ManualHistory` は完成している。各操作の先頭で `push` を呼ぶだけ |

**18a で守ること**

- 各操作は `ManualGameService` に足し、`ManualWsController` の `execute` 経由で呼ぶ。
- 操作の適用前に `room.getHistory().push(room.getGameState())` を呼ぶ(copy() は不要)。
- ログは `room.addLog(...)` に追記する。**Undo でログを巻き戻さない。**
- **判断を要する処理を足さない**(設計書 5-1)。コスト支払い・戦闘の解決・攻撃可否・
  フェイズ強制・勝敗判定・デッキ切れはすべて切る。
- **手動モードのカードIDを Java の文字列リテラルに書かない**(`check_all.py` 項目3 が偽陽性を出す)。
