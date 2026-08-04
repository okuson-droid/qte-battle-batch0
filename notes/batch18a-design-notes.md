# Batch 18a 設計解説 — 手動モードの操作・進化スタックの分解・Undo

## ★★★ 設計書16 訂正(このバッチの納品後、同チャットで反映)

**設計書 4-5(ドロップ先)・4-5-1・4-5-2 の3が定めていた「場を離れても数値は保持する」は撤回する。**
正しくは次のとおりである。**マスターに設計書16本体の該当箇所を直してもらうこと。**

> **ミニオンゾーン(FIELD)の直下から離れる瞬間、attack / hp は印刷値に戻る。**
> 「離れる」とは、移動先が FIELD 以外になる場合と、進化の素材になる場合の両方を指す。
> 席をまたいで FIELD → FIELD へ移す(相手席のミニオンゾーンへ置く)場合は、
> 場に居続けている扱いなので戻さない。
> ウェポンについては未確定(2-3-1参照。次バッチまでに裁定を確認すること)。

修正箇所は本文2-3-1にまとめてある。テストは29件(旧26件 + 新規3件、うち1件は旧仕様のテストを
新仕様に書き換え)まで拡張し、`javac` + リフレクション実行で全件通過を確認済みである。

---

対象: QTE Battle 手動モード(フェイズ1 = 一人回し)の3本目。
前提: `batch16-manual-mode-design-v2_2.md`(手動モードの唯一の正) + `notes/batch17b-design-notes.md`。
範囲: 設計書 5-3(操作13項目) / 4-5(進化スタックの積みと分解) / 5-6(Undo・Redo)。

本バッチは**既存ファイルを1行も変更していない**。8ファイルすべてが新規である。

---

## ⚡ 結論チートシート

- **操作は18本の宛先になった。** 13項目のうち「ゾーン間移動」が最も広く、
  装備・解除・マリガン・素材の抜き出しまで**すべて `move` 1本で済んでいる**(2-2)。
- **★★設計書16 訂正: FIELD を離れると印刷値へ戻る。** 当初は「移動では数値に触らない」で
  納品したが、同チャットでマスターから訂正が入った。素材になるときも、場から他ゾーンへ
  移るときも、独立したミニオンとしての履歴は切れて印刷値に戻る(2-3)。
  席をまたぐ FIELD → FIELD だけは場に居続ける扱いで戻さない。ウェポンは今回含めていない(2-3-1)。
- **★進化は枠数を N → 1 に減らす。** 素材をゾーンから外し、平坦化して1枚の下に押し込み、
  最も左の素材が居た枠へ置き直す(2-4)。素材が進化スタックだった場合はその中身を先に持ち上げる。
- **★履歴への push は1箇所しかない。** `ManualOperationService.apply()` が握る。
  個々の操作は「状態を変えてログ本文を返す」だけの関数であり、`push` も `addLog` も書かない(2-1)。
- **★失敗した操作は盤面もログも履歴も変えない。** 操作前のスナップショットを先に取り、
  例外が出たら丸ごと差し戻す。17b の `execute` が書いていた約束をここで実際に守る(2-1)。
- **★Undo はログを巻き戻さない。** 状態だけを戻し「操作を1つ取り消した」を追記する。
  ログのみの操作(メモ・宣言)と Undo/Redo 自身は**履歴に積まない**(2-5)。
- **★`ManualWsController` に足さず、新しい `@Controller` を作った。**
  既存ファイル無変更の制約を literal に守るためである。代償として `execute` 相当が二重になっている。
  **19a で1本にまとめること**(6章)。
- **★この環境で型検査と実行検証ができた。** Lombok と Spring の jar は取れないが、
  既存クラスの公開APIを写したスタブを書いて `javac` を通し、テスト29件を実際に走らせた(3-2)。
  訂正後の再検証も同じ手順で行った。
- **★Batch 15b と 17b はいずれもリポジトリに反映されている**(0章)。15b の未反映は解消した。

---

## 0. ★★★ 着手前の報告

### 0-1. Batch 17b は完全に反映されている

指示された4点を、記述ではなく実データで照合した。

| 確認項目 | 結果 |
|---|---|
| `ManualCardInstance` の `materials` / `stackSize()` | **あり**(80行目に `private final List<ManualCardInstance> materials`、<br>110行目に `stackSize()`、115行目に `materialCount()`) |
| `ManualSeat.zone(ManualZone)` が `EnumMap` を引く形か | **そうなっている**(28行目 `EnumMap<>(ManualZone.class)`、<br>50行目 `zone()` が `zones.get(target)` を返す) |
| `ManualHistory` の `MAX_DEPTH = 200` と `undo` / `redo` | **あり**(29行目 `MAX_DEPTH = 200`、54行目 `undo`、67行目 `redo`) |
| `src/test/resources/decks/sample-deck.zip` | **あり**(2578 バイト。`.gitattributes` も同梱) |

manual パッケージの Java は 29ファイルすべて揃っている。

### 0-2. ★Batch 15b も今回は反映されている(3回続いた未反映は解消)

| 確認項目 | 期待(15b完了後) | 今回の main |
|---|---|---|
| イグニッション・バースト(0064) | 自傷2 | **`damageLeader(ctx.room(), ctx.owner(), 2, "QTE-0064")`**<br>(`CardEffectRegistry.java:581`) |
| `ResumePoint` | `AQUA_SEARCH_DISCARD` あり | **8種目として存在する** |
| `notes/batch15b-design-notes.md` | あり | **存在する** |
| 引き継ぎ書 | `qte-handoff-v12.md` | **`qte-handoff-v12.md`** |

**15c の着手条件は満たされた。**

なお `check_records.py` の既知の偽陽性のうち `CardEffectRegistry` の行番号が
**706 → 748 に動いている**。15b の追加分ぶんであり、内容は同じ(`LeaderAbilitySpec` の
オーバーロード呼び出し)である。引き継ぎ書の記述を更新した。

---

## 1. 作ったもの

```
src/main/java/com/example/qte/manual/
├── ManualCardRef.java              カード1枚の所在(record)。ゾーン直下 / 素材 / リーダーを1つの型で表す
├── ManualBoardIndex.java           instanceId から所在を引き、元の場所から外す static ユーティリティ
├── ManualLabels.java               既定9種の札と正規化(設計書 5-4)
├── ManualDeclaration.java          勝敗の宣言4種(設計書 5-3 の12)
├── ManualOpRequest.java            操作リクエスト13種(record の入れ子)
└── ManualOperationService.java     ★操作の適用。1回の操作の型もここが握る

src/main/java/com/example/qte/manual/web/
└── ManualOpsWsController.java      ★@MessageMapping 18本 + 共通ディスパッチャ

src/test/java/com/example/qte/
└── ManualOperationTest.java        26件
```

既存ファイルの変更は**ゼロ**である。`ManualGameService` も `ManualWsController` も
`ManualCardInstance` も1行も触っていない。

### 1-1. 宛先の一覧(設計書 2-4 の `/app/manual/{roomId}/{action}`)

| 宛先 | ペイロード | 設計書 | 履歴 |
|---|---|---|---|
| `move` | `Move` | 5-3 の1 | 積む |
| `evolve` | `Evolve` | 4-5-1 | 積む |
| `lp` | `Lp` | 5-3 の2 | 積む |
| `stat` | `Stat` | 5-3 の3・4 | 積む |
| `stat-reset` | `Target` | 5-3 の3・4 | 積む |
| `label-add` / `label-remove` | `Label` | 5-3 の5 | 積む |
| `tap` | `Flag` | 5-3 の6 | 積む |
| `flip` | `Flag` | 5-3 の7 | 積む |
| `used` | `Flag` | 5-3 の8 | 積む |
| `turn` | `Turn` | 5-3 の10 | 積む |
| `phase` | `Phase` | 5-3 の10 | 積む |
| `draw` | `Draw` | 4-4 | 積む |
| `shuffle` | `Seat` | 5-3 の11 | 積む |
| `declare` | `Declare` | 5-3 の12 | **積まない** |
| `note` | `Note` | 5-3 の13 | **積まない** |
| `undo` / `redo` | `ManualWsController.OccupantRequest` | 5-6 | **積まない** |

**設計書 5-3 の13項目はすべて埋まっている。**
「ウェポンの装備 / 解除」(8)は `move`、「進化スタックの分解」(9の後半)も `move` である(2-2)。

---

## 2. 設計判断

### 2-1. ★ 1回の操作の型を1箇所に閉じた — push を13箇所に書かない

個々の操作メソッドは、次の形しか持たない。

```java
public String move(ManualGameState state, ManualOpRequest.Move request) { ... }
```

**状態を変更して、ログ本文を返す。** 履歴への `push` もログの `addLog` も書かない。
それを行うのは `ManualOperationService.apply()` の1箇所だけである。

```java
public void apply(ManualRoom room, Function<ManualGameState, String> mutation) {
    ManualGameState state = room.getGameState();
    ManualGameState snapshot = state.copy();
    String logText;
    try {
        logText = mutation.apply(state);
    } catch (RuntimeException e) {
        room.setGameState(snapshot);   // ★失敗したら操作前へ戻す
        throw e;
    }
    room.getHistory().push(snapshot);
    if (logText != null && !logText.isBlank()) {
        room.addLog(logText);
    }
}
```

理由は 17b が `ManualHistory.push()` の中で複製することにしたのと同じである(17b 2-4)。
呼び出し側の責務にすると、13項目のうち1箇所でも書き忘れた瞬間に
**「その操作だけ Undo で飛ばされる」**という不具合になり、
しかも Undo を実行するまで症状が出ない。器の側で閉じるほうが圧倒的に安い。

#### 失敗したら操作前へ戻すこと

17b の `ManualWsController.execute` は、失敗時のコメントに
「状態は変更されていないので、操作者にだけ理由を返す」と書いてある。
17b の時点では操作が `ready` / `resync` しか無かったので自明に真だったが、
18a の操作は複数枚を扱うため、途中で例外が出れば盤面は動きかけで止まりうる。
**約束を守るのは実装の側**であり、スナップショットを先に取って差し戻す形にした。

代償として、1回の操作につき `copy()` が2回走る(スナップショット1回 + `push` の中で1回)。
1状態は数KBであり、操作は人間の手の速さでしか来ないため実測上の意味は無い。

#### ★成功したときは `ManualGameState` のオブジェクトを差し替えない

「毎回新しい状態を作って差し替える」形も検討したが採らなかった。
そうすると `room.getGameState()` が操作のたびに別のオブジェクトになり、
**操作をまたいで `ManualSeat` の参照を持っている呼び出し側の変更が黙って消える。**
18b / 19a が最も踏みやすい罠である。差し替えるのは失敗時と Undo/Redo のときだけとした。

### 2-2. ★ 操作13項目のうち5項目が `move` 1本に収まった

設計書 5-3 を素直に読むと13個の操作を実装することになるが、実際には
**移動という1つの操作の言い換え**であるものが多い。

| 設計書の項目 | 実装 |
|---|---|
| 1. ゾーン間移動 | `move` |
| 8. ウェポンの装備 / 解除 | `move`(WEAPON ゾーンへ / から) |
| 9後半. 進化スタックの分解 | `move`(素材の instanceId を指定する) |
| 5-2. マリガン | `move`(手札 → 山札) |
| 4-5-2 の4. 素材を場に戻す | `move`(素材 → FIELD) |

これが成り立つのは 17b がゾーンを `EnumMap` 1本にしたおかげである(17b 2-1)。
移動元と移動先が同じ型なので、9×9の組み合わせを書き分ける必要が無い。

素材まで `move` で扱えるのは、`ManualBoardIndex` が
**「ゾーン直下」「進化スタックの素材」「リーダー」の3種類の所在を1つの型で返す**からである。
外し方の分岐は `ManualBoardIndex.detach()` の1箇所にしかない。

- **最上段(束そのもの)を指定すると、素材が付いてくる。** 設計書 4-5-2 の1
  「ドラッグは束全体を動かす」がそのまま実装になる(素材は最上段が抱えているため)。
- **素材を指定すると、その1枚だけが抜ける。** 同 2 の「帯から1枚ずつ抜く」である。
- **リーダーは外せない。** ゾーンに属さず、`ManualSeat` が専用スロットで持っているためである
  (17b 2-1)。タップ・数値・札はリーダーにも効く(外す処理を通らない)。

#### 複数枚の並び順

`move` と `tap` / `flip` / `used` は複数枚を受け取る。
**クライアントが選択した順ではなく、盤面上の並び順(席 → ゾーン → 位置)に並べ替えてから適用する。**
設計書 4-5-1 が進化の素材について「選択した順ではなくミニオンゾーンの左からの並び順で積む。
順序に意味は無いが、再現性のために規則を固定しておく」と定めているのと同じ理由である。
同じ盤面から同じ選択をすれば必ず同じ結果になる。

### 2-3. ★★ 設計書16 訂正 — FIELD を離れる(素材になる)と印刷値に戻る

当初、`move` は数値に一切触らない実装で納品した。根拠は設計書の2箇所を両立させるためだった。

> 4-5(ドロップ先): ミニオンゾーンの空き枠 → 場に出す。**ATK/HP は印刷値で初期化**(空欄なら空欄のまま)

> 4-5-2 の3: 帯から1枚抜いても**タイルの数値は変えない**。一体扱いの数値は人間の管理下にあり、
> アプリが推測して書き換えない

**この前提が、納品後の同チャットでマスターから訂正された。**
受けたダメージや強化は「そのミニオンが場に居続けている間」だけの状態であり、
進化の素材になったときも、場から他のゾーンへ移動したときも、その履歴は切れて消えるべきである。
これは設計書4-5-2の3が明示的に否定していた挙動であり、**設計判断そのものの訂正**として扱う。
**マスターに設計書16本体を直してもらう必要がある**(このバッチのファイルには反映できない。
プロジェクトナレッジの設計書は読み取り専用のためである)。

#### 新しい規則

> **ミニオンゾーン(FIELD)の直下から離れる瞬間、印刷値に戻る。**
> 「離れる」は次の2つを指す。
> 1. `move` で移動先が FIELD 以外になる場合
> 2. `evolve` で素材になる場合(独立した FIELD の枠を失うという意味で、これも場を離れる一形態)
>
> 席をまたいで FIELD → FIELD へ移す(相手席のミニオンゾーンへ「相手にこのミニオンがいる想定」で
> 置き直す。設計書 4-1)場合は、場に居続けている扱いなので戻さない。

#### 実装

`move` は移動する1枚ごとに、移動元が FIELD かつ移動先が FIELD でないときだけ印刷値へ戻す。

```java
if (ref.zone() == ManualZone.FIELD && request.toZone() != ManualZone.FIELD) {
    resetToPrinted(card);
}
```

`evolve` は素材にする瞬間に印刷値へ戻す。平坦化で持ち上がってくる孫世代の素材
(既に別の進化ミニオンの素材だったカード)にも同じ処理を掛けるが、
その時点で既に印刷値になっているため、二重適用しても無害である。

```java
for (ManualCardRef ref : materials) {
    ManualCardInstance material = ref.card();
    for (ManualCardInstance nested : material.getMaterials()) {
        resetToPrinted(nested);
        stacked.add(nested);
    }
    material.getMaterials().clear();
    resetToPrinted(material);   // ★ここが訂正の核
    stacked.add(material);
}
```

`resetToPrinted` は突合できていないカード(`isResolved()` が false)には何もしない。
印刷値そのものが分からないためであり、名前だけの灰色タイルに数値を勝手に生やすことはしない。

#### `stat-reset` は残す

「盤面上に留まったまま、明示的に印刷値へ戻したい」という操作は今回の規則と別物であり、
`/stat-reset` はそのまま残した。内部実装を `resetToPrinted` へ委譲するよう整理しただけである。

#### 2-3-1. ★未確定: ウェポンをこの規則に含めるか

**含めていない。** マスターの指示は「場から他のゾーンに移動したとき」であり、
文字どおりには FIELD だけを指す。ウェポンも ATK を持ち(設計書 5-3 の8)、
装備解除で他ゾーンへ移すことがあるため、対称に扱うべきという見方もできるが、
今回はスコープを広げず FIELD のみに絞った。**次バッチまでに裁定を確認すること。**
含める場合の修正は `move` の条件式に `ref.zone() == ManualZone.WEAPON` を足すだけで、
影響範囲は小さい。

### 2-4. ★ 進化 — 枠数が N → 1 に減る唯一の操作

```java
// 1. 素材の並び順を固定する(左から)
materials.sort(ManualBoardIndex.BOARD_ORDER);
// 2. 置き場所は「最も左の素材が居た枠」
int slot = field.indexOf(materials.get(0).card());
// 3. ★平坦化する: 素材が進化スタックなら、その中身を先に持ち上げてから素材自身を積む
for (ManualCardRef ref : materials) {
    stacked.addAll(material.getMaterials());
    material.getMaterials().clear();
    stacked.add(material);
}
// 4. 素材をゾーンから外す(ここで N 枠が空く)
// 5. 進化ミニオンを元の場所(ふつうは手札)から外し、素材を抱えて slot へ置く(1枠使う)
```

**通常のカードプレイに無い性質**であり、実装上ここだけが特殊である。
「1枚出したのにゾーンが縮む」ため、画面(18b)の枠計算もこの挙動に合わせる必要がある。

#### 平坦化の根拠(17b 2-2 の再確認)

設計書 4-5-1 の `+n` バッジは
「3体を素材にすれば `+3`、その上にさらに進化を重ねれば `+4`」と定めている。
n が 3 → 4 になるということは、**数え方が階層を無視している**ということである。
入れ子にすると n の計算が再帰になり、4-5-2 の帯(1列)とも合わない。

したがって、素材にした進化ミニオンは自分の素材を手放し、
その素材たちと同じ列に平らに並ぶ。テストで固定してある。

#### 素材の条件

**素材は「同じ席のミニオンゾーンの直下にあるカード」に限った。**
この操作の定義そのものが「ミニオン枠が N → 1 に減る」ことであり、
素材がどこにあってもよいとすると `move` との区別が消える。
墓地のカードを下に敷きたいような場合は、先に `move` で場へ出してから重ねる。

**素材にできるカードの種類・枚数・文明は一切判定しない**(設計書 4-5-1 の4)。
素材0体も許す(不敗鉄人闘太を 0/0 で出す形は仕様上ありうる)。

### 2-5. ★ 履歴に積むもの / 積まないもの

| 操作 | 積む | 理由 |
|---|---|---|
| 盤面を変える操作(14本) | ○ | |
| 自由メモ・勝敗の宣言 | **×** | 盤面に触らないため。積むと Undo が「見た目に何も起きない1手」を消費し、<br>人間が数えている取り消し回数と食い違う |
| Undo / Redo | **×** | 積んだ瞬間に Undo が自分自身を取り消せてしまう |

そして **Undo はログを巻き戻さない。**

```java
public String undo(ManualRoom room) {
    ManualGameState restored = room.getHistory().undo(room.getGameState())
            .orElseThrow(() -> new IllegalStateException("取り消せる操作がありません"));
    room.setGameState(restored);
    return "操作を1つ取り消した(Undo。残り %d手)".formatted(room.getHistory().undoDepth());
}
```

ログは `ManualRoom` が持ち、`ManualGameState` の外にある(17b 2-5)。
アプリが効果を解決しない以上、**何が起きたのかを記録できるのは人間だけ**であり、
ログはこのモードの成果物である(設計書 5-5)。追記専用でなければならない。
テスト `Undoでログは巻き戻らない()` で固定した。

### 2-6. ★ `ManualWsController` に足さず、新しい `@Controller` を作った

指示は「既存ファイルは1行も変更しない」と
「`ManualGameService` にメソッドを足し、`ManualWsController` の `execute` 経由で呼ぶ」の両方を含む。
17b の次バッチ予告も後者を書いている。**この2つは字面上ぶつかる。**

制約のほうを literal に守った。

| 本来足す先 | 実際に作ったもの |
|---|---|
| `ManualGameService` にメソッド追加 | `ManualOperationService` を新設(`ManualGameService` を注入して使う) |
| `ManualWsController` に `@MessageMapping` 追加 | `ManualOpsWsController` を新設 |

Spring では `@MessageMapping` が複数の `@Controller` に分かれていてよく、
`ready` / `resync` と本バッチの18本は宛先が1つも重ならない。
`WebSocketConfig` も無変更である。

**代償は、部屋を引いてロックを取り配信するまでの型が二重になったこと**である
(`ManualWsController.execute` は `private` のため再利用できない)。
既存ファイルの変更が許される **Batch 19a で1本にまとめること**を積み残しに入れた。

### 2-7. ★ null は「変えない」— リクエストはすべてラッパー型

```java
public record Move(String occupantId, List<String> cardIds, ManualSeatId toSeat,
        ManualZone toZone, Integer toIndex, Boolean faceDown) { }
```

`faceDown` が null なら表裏を変えず、`true` / `false` なら明示的に設定する。
素の `boolean` にすると「未指定」と「false 指定」が区別できない。
`tap` / `flip` / `used` の `value` は null のとき**1枚ずつ現在値を反転する**(トグル)。
設計書 4-4 の「左クリック = タップ ⇔ アンタップ」がこれである。

`toIndex` が null なら末尾、範囲外なら丸める。
**丸めるのは裁定ではない。**「山札の一番下へ」を大きな数で表せると画面が楽になる。

### 2-8. アプリが弾くのは「要求が成り立たない場合」だけである

設計書 5-1 の「判断を要するものは全部切る」を守るため、**弾く条件を明文化しておく。**

**弾く(画面側の取りこぼしであり、ゲームの裁定ではない)**

- 盤面に無い instanceId を指した
- 同じカードを2回指した
- リーダーをゾーンへ動かそうとした
- 直接指定と増減を同時に載せた / どちらも載せなかった
- 空欄の数値に増減を掛けようとした
- 進化の素材が同じ席のミニオンゾーンに無い
- 札が空文字 / 24文字超 / 1枚に20個超 / 同じ札の二重付け
- メモが空 / 500文字超、1回のドローが 1〜60 の外

**弾かない(判断であるため)**

- LP が20を超える・0未満になる
- ミニオンゾーンが7枠を超える / ウェポンが2枚になる
- コストを払っていない / MP が足りない
- 山札が尽きた(引ける枚数だけ引いてログに残す。**敗北にしない**)
- 攻撃できない状態のミニオンで攻撃した(そもそも攻撃という操作が無い)
- フェイズと操作が噛み合っていない
- 進化の素材の種類・枚数・文明

### 2-9. MP を直接増減する操作は作っていない

設計書 5-3 のとおり、MP はマナゾーンのアンタップ枚数から算出される派生値であり
(`ManualSeat.availableMp()`)、直接書き換えを許すと
「同じ盤面を見ていることの保証」という手動モード唯一の役割が壊れる。
MP を動かしたければマナをタップ / アンタップする。

### 2-10. 札は既定9種を検証に使わない

`ManualLabels.DEFAULTS` は**画面のワンタッチボタンに並べる候補**でしかなく、
サーバは札がこの一覧に含まれるかを検査しない(設計書 5-4)。
カードテキストには `【賢魂：3】` `【破壊時】` のように既存9種に無い記法が既に現れており、
既定の一覧を検証に使った瞬間に、カードが増えるたびにサーバの改修が要る形になる。

長さ(24文字)と個数(20個)だけは制限した。これは裁定ではなく入力の衛生である。
札は状態モデルに入り、スナップショットとして最大200件複製される。
長文の受け皿は自由メモ(5-5)のほうである。

### 2-11. ログにはカード名を出す(フェイズ2で見直しが要る)

ログの読みやすさのため、`ManualOperationService` は `ManualCardRepository` を注入して
`cardId` から名前を引く。**状態モデルには一切持ち込んでいない**ため、
スナップショット方式(設計書 5-6)の前提は壊れていない。
カード定義が引けなくても例外にしない(ログ本文が組み立てられないことを理由に
操作そのものを差し戻すのは筋が違う)。

**★フェイズ1は全公開なので問題無いが、フェイズ2の対戦モードでは
「相手が裏向きで置いたマナの名前がログに出る」ことになる**(設計書 11-3)。
ログのフィルタはフェイズ2の設計項目として積み残しに入れた。

---

## 3. 検証結果

### 3-1. 機械チェック

| スクリプト | 結果 |
|---|---|
| `check_structure.py src/main/java` | **異常0件** |
| `check_all.py .` | 項目 1・3・5・6 すべてパス(手動モードのIDをリテラルで書いていない) |
| `check_records.py src/main/java` | 既存の★3件のみ。**新しい★は無し**(行番号は 15b の反映で移動している) |
| `check_undeclared.py`(既存JS) | 0件(JS は無変更) |

`check_records.py` の `TARGETS` は固定リストで手動モードの record を見ないため、
**同じロジックの検算を 18a の新規 record 14個について別途行った。**

```
record ManualCardRef: コンポーネント 6 個 / new 呼び出し 3 件 → 不一致 0
record Move: 6 個 / 10 件、Evolve: 5 個 / 7 件、Seat: 2 個 / 0 件、
Draw: 3 個 / 4 件、Lp: 4 個 / 2 件、Stat: 6 個 / 4 件、Target: 2 個 / 1 件、
Label: 3 個 / 4 件、Flag: 3 個 / 5 件、Turn: 3 個 / 2 件、Phase: 3 個 / 2 件、
Declare: 4 個 / 1 件、Note: 2 個 / 1 件
→ 新規 record の不一致: 0
```

`Seat` の `new` 呼び出しが0件なのは、`shuffle` の受け口としてしか使わず
Jackson が組み立てるためである(異常ではない)。

`src/test` は `check_structure.py` の対象外であるため、
B 検査(素呼び出しの宣言存在確認)を `src/test` 向けに調整したものを別途走らせた。
静的インポート(`assertThat` / `assertThatThrownBy`)と `@Test` の日本語メソッド名を
宣言として扱う。**未解決0件。**
`ManualOperationTest` が宣言しているヘルパは `card` / `seatA` / `put` / `reload` の4つで、すべて宣言済みである。

### 3-2. ★ この環境で型検査と実行検証ができた

引き継ぎ書は「この環境では Maven ビルドができないため、型エラーは発注者の手元でしか出ない」と
書いているが、今回は**別の手段で通した。**

1. `apt` から JDK 21 を入れた(`javac` が使えるようになった)。
2. Lombok と Spring の jar は Maven Central が到達不可のため取れない。
   そこで**既存クラスの公開APIを Lombok 展開後の形で写したスタブ**と、
   Spring / JUnit / AssertJ の最小スタブを手で書いた。
3. **新規7ファイル(main)と テスト1ファイルが `javac` を通った。**
4. さらに AssertJ スタブに実際の検証を実装し、`ManualGameService` の
   `drawCards` / `shuffleDeck` を本物と同じ実装にしたうえで、
   **テスト29件をリフレクションで実行して全件通過を確認した。**
   (設計書16 訂正を反映した再検証を含む。旧仕様のテスト1件を新仕様に書き換え、新規3件を追加した)

```
通過 29 / 29
```

**この検証は完全ではない。**確認できていないのは次である。

- Lombok が実際に生成するアクセサ(スタブは既存ソースから手で写したもの)
- Spring の DI と `@MessageMapping` の登録(宛先の重複は目視で確認済み)
- Jackson によるリクエスト record の組み立て
- `ManualCardRepository` の実データ235枚に対する挙動

型エラーと操作ロジックの誤りは、この手順でほぼ潰せたと考えてよい。

### 3-3. テスト29件の内訳

| 区分 | 件数 | 主な確認内容 |
|---|---|---|
| ゾーン間移動 | 5 | 裏向き指定 / 盤面の並び順で挿入 / 挿入位置の丸め / リーダー拒否 / 重複拒否 |
| 進化スタック | 8 | 枠 3 → 1 / 平坦(`+3` の上に重ねて `+4`) / 束ごと移動 / 素材なし / 素材の場所の検査 /<br>**★素材になる瞬間に印刷値へ戻る / FIELD を離れると印刷値へ戻る /<br>FIELD → FIELD(相手席)は戻らない / 帯から抜いた素材は印刷値のまま FIELD へ戻せる** |
| 数値・札・フラグ | 6 | LP の上限下限なし / 直接指定と増減 / 空欄の増減拒否 /<br>印刷値へ戻す / 自由入力の札 / トグルと明示指定 / リーダーのタップ |
| ターン・フェイズ・ドロー | 2 | 前後移動と下限1 / **山札が尽きても敗北にしない** |
| Undo / Redo | 5 | 盤面が戻る・進む / **ログが巻き戻らない** / 空のとき失敗 /<br>ログのみの操作は積まない / 1操作1段 |
| 失敗時 | 1 | **盤面もログも履歴も変わらない** |

---

## 4. ✅ 検証手順

### 4-1. 通常モードが従来どおり動くこと

既存ファイルを1行も変更していないため壊れようがないが、
新設したビーンの読み込みに失敗するとアプリ全体が起動しない。

- `http://localhost:8080/` でロビーが出る
- `http://localhost:8080/cards` で従来のカード一覧(169枚)が出る
- 対戦を1試合開始できる

### 4-2. 手動モードの既存機能が維持されていること

- `http://localhost:8080/manual/cards` が **235枚**を表示する
  (LEADER 18 / MINION 119 / EVOLUTION 18 / SPELL 61 / WEAPON 19)
- `http://localhost:8080/manual/deck-check` に zip を投入し、
  **「警告なし。49枚すべてがカード定義に解決した。」の緑の帯が出る**

### 4-3. テスト

Eclipse で `ManualOperationTest` を右クリック → Run As → JUnit Test。**26件すべて緑になること。**

`ManualDeckImportTest`(17b)も引き続き緑であること。

**★`CardMasterLoadTest.台帳の全カードが読み込まれる()` は赤いままである**
(`hasSize(72)` に対して台帳は169枚)。Batch 3 頃の値のまま更新されていない既知の問題で、
18a も既存ファイル変更禁止のため触っていない。`Dockerfile` は `-DskipTests` である。

### 4-4. ★ 操作の疎通(画面が無いため手で叩く)

盤面の画面は Batch 18b で作るため、18a の時点では WebSocket を直接叩くしかない。
**ブラウザの開発者ツールのコンソールから**次を実行する。

```js
// 1) 部屋を作る
const r = await (await fetch('/manual/api/rooms', {method:'POST'})).json();
console.log(r);   // { roomId, occupantId, displayName }

// 2) 購読して ready を送る(SockJS と Stomp は battle.html が読み込んでいるものを使う)
const sock = new SockJS('/ws');
const stomp = Stomp.over(sock);
stomp.connect({}, () => {
  stomp.subscribe(`/topic/manual/${r.roomId}/view/${r.occupantId}`, m => {
    const msg = JSON.parse(m.body);
    console.log(msg.type, msg.view ? msg.view.log.at(-1) : msg.message);
    window.view = msg.view;
  });
  stomp.send(`/app/manual/${r.roomId}/ready`, {}, JSON.stringify({occupantId: r.occupantId}));
});

// 3) デッキを読み込む(zip をファイル選択で拾って送る)
await fetch(`/manual/api/rooms/${r.roomId}/deck?seat=A&occupantId=${r.occupantId}`,
            {method:'POST', body: file});
```

ここまで来れば `window.view` に盤面が入る。以下を順に確認する。

| 手順 | 送るもの | 期待 |
|---|---|---|
| ドロー | `/draw` `{occupantId, seat:'A', count:1}` | 手札が1枚増え、ログに「1枚 引いた」 |
| マナへ裏向き | `/move` `{occupantId, cardIds:[手札のinstanceId], toSeat:'A', toZone:'MANA', faceDown:true}` | マナが1枚増え、`mp` が1になる |
| タップ | `/tap` `{occupantId, cardIds:[マナのinstanceId]}` | `mp` が0に戻る(トグル) |
| 場に出す | `/move` `{..., toZone:'FIELD'}` | ミニオンゾーンに出る。**数値は印刷値のまま** |
| ★進化 | `/evolve` `{occupantId, seat:'A', evolutionCardId:X, materialCardIds:[A,B,C]}` | **ミニオン枠が 3 → 1 に減り**、`stackSize` が4、`materials` が3件。<br>**素材にした3体は、被弾していても印刷値へ戻っていること** |
| ★分解 | `/move` `{occupantId, cardIds:[materialsの中のinstanceId], toSeat:'A', toZone:'TRASH'}` | 素材が1件減る。抜けたカードは印刷値のまま墓地へ入る |
| ★場を離れる | `/move` `{..., toZone:'TRASH'}`(FIELD上のミニオンを墓地へ) | **attack / hp が印刷値に戻ること** |
| 札 | `/label-add` `{occupantId, cardId:X, label:'守護'}` | `labels` に入る |
| LP | `/lp` `{occupantId, seat:'A', delta:-25}` | **LP が -5 になる(弾かれない)** |
| メモ | `/note` `{occupantId, text:'ここで焼いた'}` | ログに「メモ: ここで焼いた」。**`canUndo` が変わらない** |
| ★Undo | `/undo` `{occupantId}` | 盤面が1手戻り、**ログは1行増える** |
| Redo | `/redo` `{occupantId}` | 盤面が1手進む |

**特に見るべきは3つである。**

1. **進化でミニオン枠が減ること**(通常のカードプレイに無い挙動)。
2. **★FIELD を離れる(素材になる・他ゾーンへ移る)と印刷値へ戻ること**(設計書16 訂正)。
   席をまたぐ FIELD → FIELD だけは戻らないことも合わせて確認する。
3. **Undo でログが巻き戻らないこと**(設計書 5-5。ログがこのモードの成果物である)。

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
<summary>Q1. 進化スタックから素材を1枚抜く操作は、どの宛先へ送るか</summary>

**`/move` である。** 専用の宛先は作っていない。

`ManualBoardIndex` が「ゾーン直下のカード」と「進化スタックの素材」を
同じ `ManualCardRef` として返し、`detach()` が外し方を分岐するためである。
クライアントは素材の `instanceId` を `cardIds` に載せて、移動先のゾーンを指定するだけでよい。

同じ理由で、最上段の `instanceId` を載せれば束ごと動く(設計書 4-5-2 の1)。
</details>

<details>
<summary>Q2. 場のミニオンが被弾したまま墓地へ送られ、後で場へ戻された。HP は印刷値か、被弾した値のままか</summary>

**印刷値に戻る(設計書16 訂正・2-3)。**

FIELD を離れる瞬間(墓地へ送られた瞬間)に印刷値へ戻る。墓地に居る間はもちろん、
その後また場へ戻しても、被弾した記憶は残っていない。
「そのミニオンが場に居続けている間だけの状態」という考え方であり、
場を一度離れた個体は履歴が切れる。

例外は席をまたぐ FIELD → FIELD の移動(相手席へ「相手にこのミニオンがいる想定」で置く)で、
これは場に居続ける扱いなので数値を保つ。
</details>

<details>
<summary>Q3. 3体を素材にした進化ミニオンの上に、もう1枚進化ミニオンを重ねた。materials は何件か</summary>

**4件である。入れ子にはならない。**

新しく乗せた進化ミニオンが最上段になり、下にあった進化ミニオンは自分の素材3体を手放して、
その3体と同じ列に平らに並ぶ(合計4件)。

根拠は設計書 4-5-1 の `+n` バッジで、「3体を素材にすれば +3、その上にさらに重ねれば +4」。
n が 3 → 4 になるのは、数え方が階層を無視しているということである。
</details>

<details>
<summary>Q4. 自由メモを書いた直後に Undo を押した。何が起きるか</summary>

**メモを書く前の盤面ではなく、その前の「盤面を変える操作」の前へ戻る。**

自由メモと勝敗の宣言は盤面に触らないため、履歴に積んでいない。
積むと Undo が「見た目に何も起きない1手」を消費し、
人間が数えている取り消し回数と食い違うためである。

そして**メモはログに残ったままである。** Undo はログを巻き戻さない(設計書 5-5)。
</details>

<details>
<summary>Q5. 操作の途中で例外が出た。盤面はどうなるか</summary>

**操作前の状態へ丸ごと戻り、ログにも履歴にも何も残らない。**

`ManualOperationService.apply()` が操作前のスナップショットを先に取っており、
例外が出たら `room.setGameState(snapshot)` で差し戻してから投げ直す。
17b の `ManualWsController.execute` が
「状態は変更されていないので、操作者にだけ理由を返す」と書いていた約束を、実装の側で守っている。

★このとき状態オブジェクトが差し替わるため、同じ `instanceId` でも別のオブジェクトになる。
失敗をまたいでカードの参照を持ち回らないこと(テストの `reload()` がその実演である)。
</details>

<details>
<summary>Q6. LP を -5 にする要求が来た。サーバは弾くか</summary>

**弾かない。** 設計書 5-3 の2 が「上限20は強制しない」と定めており、
5-1 は勝敗判定を切ると定めている。LP が0を下回っても何も起きない。
「決着した」と記録できるのは人間だけであり、そのための `/declare` がある。

同様に、山札が尽きても敗北にせず、ミニオンゾーンの枚数もウェポンの枚数も制限しない。
</details>

<details>
<summary>Q7. 「守護」以外の札、たとえば「賢魂：3」を付けられるか</summary>

**付けられる。** `ManualLabels.DEFAULTS` は画面のワンタッチボタンに並べる候補でしかなく、
サーバは札がこの一覧に含まれるかを検査しない(設計書 5-4)。

カードテキストには既に `【賢魂：3】` `【破壊時】` という既存9種に無い記法が現れており、
既定の一覧を検証に使った瞬間に、カードが増えるたびにサーバの改修が要る形になる。
制限しているのは長さ(24文字)と1枚あたりの個数(20個)だけであり、これは入力の衛生である。
</details>

<details>
<summary>Q8. なぜ操作を `ManualGameService` ではなく新しいクラスに足したのか</summary>

**Batch 18a が「既存ファイルを1行も変更しない」制約の下にあるためである。**

17b が作った `ManualGameService` も、この時点では既存ファイルである。
`ManualOperationService` を新設し、`ManualGameService` を注入して
`drawCards` / `shuffleDeck` を使う形にした。`ManualWsController` も同様に、
宛先が重ならない別の `@Controller` を立てている。

代償として、部屋を引いてロックを取り配信するまでの型が二重になっている。
**既存ファイルの変更が許される Batch 19a で1本にまとめること。**
</details>

<details>
<summary>Q9. 3枚を Ctrl+クリックで選んで墓地へドラッグした。墓地に入る順番は何で決まるか</summary>

**選択した順ではなく、盤面上の並び順(席 → ゾーン → 位置)である。**

設計書 4-5-1 が進化の素材について「選択した順ではなくミニオンゾーンの左からの並び順で積む。
順序に意味は無いが、再現性のために規則を固定しておく」と定めており、
移動でも同じ規則を使っている。同じ盤面から同じ選択をすれば必ず同じ結果になる。
</details>

---

## 6. 積み残し

| 項目 | 内容 |
|---|---|
| **★★設計書16 本体の更新** | 4-5・4-5-1・4-5-2 の3が「場を離れても数値は保持する」のままになっている。<br>プロジェクトナレッジ側は読み取り専用のため、**マスターに直接直してもらう必要がある**(2-3) |
| **★ウェポンをこの規則に含めるか** | 今回は FIELD のみに絞った(2-3-1)。次バッチまでに裁定を確認する |
| **★`ManualWsController` との統合** | `execute` 相当が `ManualOpsWsController.dispatch` と二重になっている(2-6)。<br>既存ファイル変更が許される **19a** で1本にまとめる |
| **★ログの視点フィルタ** | フェイズ1は全公開なので問題無いが、フェイズ2の対戦モードでは<br>相手の裏向きマナの名前がログに出る(2-11)。フェイズ2の設計項目 |
| **★既定9種の札の配信口** | `ManualLabels.DEFAULTS` はサーバ定数として置いただけである。<br>画面へ渡す経路(テンプレートのモデル属性か GET エンドポイント)は **18b** で決める |
| 「リセットして引き直す」 | `ManualGameService.loadDeck` が履歴を空にする形で既にある。<br>ヘッダのボタンと入口は **19a** |
| `GameRoomManager` の `RoomIds` 化 | 17b から継続。**19a** |
| 既存テストが赤い | `CardMasterLoadTest.台帳の全カードが読み込まれる()` が `hasSize(72)`。台帳は169枚 |
| `check_records.py` の `TARGETS` | 手動モードの record が 16 + 14 = 30 個未登録。`tools/` の改修時にまとめて追加 |
| `check_structure.py` の `src/test` 対応 | 今回も調整版を別途走らせて代用した(3-1)。恒久対処は `tools/` の改修時 |
| 部屋の掃除 | `ManualRoomManager.removeRoom()` を誰も呼んでいない。切断猶予と合わせて **19a** |
| **★Ver.0.4 の 15c / 15d / 15e** | 15b が push されたため**着手条件は満たされた**(0-2) |

---

## 7. 次バッチ予告 — Batch 18b(b系・Sonnet 5)

**盤面の画面(タイル・手札・拡大画像・操作規約)。** 設計書 4-1 〜 4-4 が範囲である。
18a が作った18本の宛先を画面から呼ぶ。**既存ファイルの変更は `battle.css` のみ。**

| 作るもの | 要点 |
|---|---|
| レイアウト | 幅1200px・3列(左110px ゾーンバー / 中700px 盤面 / 右350px ログ + 拡大画像 330×462) |
| 場のタイル | 110×110px。名前 / ATK・HP / 札 / **`+n` バッジ**。★画像は使わない |
| 文明色 | 4-2 の実値7色。**文字色は黒のコントラスト比4.5未満なら白**という計算で決める(直書きしない) |
| 増減チップ | 白背景 + 増加 `#27500A` / 減少 `#791F1F`。比較対象は `printedAttack` / `printedHp` |
| 手札 | カード画像(幅90px基本・枚数で自動縮小)。URL の組み立ては1関数に閉じる |
| 操作規約 | 4-4 の表。**ダブルクリックを使わない** / 複数選択は `ctrlKey \|\| metaKey` /<br>山札だけ左右が逆(左=ドロー、右=全面表示) |
| ドラッグ | 4-5 のドロップ先。**埋まっているウェポン枠は赤く点滅させて受け付けない** |
| 進化 | Ctrl / Cmd で素材を選び、選択中のタイルへドラッグ → `/evolve` |

**18b で守ること**

- **★静的ファイルのキャッシュバスティング版を上げる**(`battle.css` は v10 → v11)。
- **★ミニオンゾーンは進化で枠数が N → 1 に減る。** 枠の再計算を配信ビューから毎回やり直すこと。
- 数値の編集は `/stat`、印刷値へ戻すのは `/stat-reset`。**画面が勝手に初期化しない。**
- `node --check` と `check_undeclared.py` を新規JSに必ず掛ける。
