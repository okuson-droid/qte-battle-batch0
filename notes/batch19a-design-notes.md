# Batch 19a 設計解説 — 入口の作り替え・リセット・ログ書き出し・切断復帰・仕上げ

対象: QTE Battle 手動モード(フェイズ1 = 一人回し)の6本目、最終バッチ。
前提: `batch16-manual-mode-design-v2_4.md`(唯一の正)+ `notes/batch18c-design-notes.md`。
範囲: 設計書 6-2(入口)・6-3(名前と席・切断復帰)・7-1(リセット)・5-5(ログ)。

本バッチをもって、フェーズ1(一人回し)は完成する。

---

## ⚡ 結論チートシート

- **既存ファイルの変更が前提のバッチである。** `LobbyController`(通常モード)に加えて、
  `ManualLobbyController` / `ManualWsController` / `ManualSeat` / `ManualOccupant` /
  `ManualRoom` / `ManualRoomManager` / `ManualGameService` / `manual-battle.html` /
  `manual-battle.js` / `lobby.html` に手を入れた。18a〜18c が守っていた「既存ファイル無変更」の
  制約は 19a には掛かっていない(設計書 8章のバッチ計画どおり)。
- **`ManualOpsWsController` を `ManualWsController` に統合した。** 18a の積み残し
  (`qte-handoff-v15.md` に明記)を解消した。`@MessageMapping` の宛先はすべて維持している。
- **`ManualBattleController`(暫定入口)を削除した。** 役目は `ManualLobbyController` の
  `battle()` メソッドに移した。`/manual/deck-check`(17bの目視確認画面)も削除した。
- **occupantId はもうサーバがページ生成時に発行しない。** クライアントの localStorage が
  保持し、無ければ `POST /manual/api/rooms/{roomId}/occupants` で新規入室する。
  これにより「同じ人がタブを開き直しても同じ在室者として戻る」が初めて成立する。
- **「リセットして引き直す」は zip の再アップロード無しで動く。** 席が直近読み込んだ
  `ManualDeckImport` を `ManualSeat.lastImport` に保持し、そこから再シャッフル・再配布する。
- **切断復帰はプロジェクト初のイベントリスナー + スケジューラ。** `SessionDisconnectEvent`
  を検知する `ManualDisconnectListener` と、5分の猶予切れを監視する
  `ManualCleanupScheduler`(1分間隔)を新設した。通常モードにこの種の仕組みは無い。
- **ログの書き出しは WebSocket ではなく HTTP GET。** `GET /manual/api/rooms/{roomId}/log` が
  `Content-Disposition: attachment` 付きでテキストファイルを返す。ブラウザの標準ダウンロードに
  任せる形であり、クライアント側で Blob を組み立てる必要が無い。
- **静的ファイルのバージョンは js のみ v=2 → v=3。** `battle.css` は無変更のため v=12 のまま。

---

## 0. 着手前の確認(マスターとのやり取り)

前チャット冒頭でまとめて6点確認した。回答はすべて「提案通りでよい」。

| # | 質問 | 回答 |
|---|---|---|
| 1 | リセットの裏側。`ManualSeat` に `lastImport` を追加し、`copy()` には複製せず参照共有で持たせる | 採用 |
| 2 | 切断復帰。`SessionDisconnectEvent` リスナー + `@Scheduled` の1分間隔スイープ | 採用 |
| 3 | 暫定入口(`ManualBattleController`)と目視確認画面(`/manual/deck-check`)の扱い | 両方削除 |
| 4 | 新ロビー。既存 `lobby.html` は無変更で `/auto` へ、`manual-lobby.html` を新設して `/` へ | 採用 |
| 5 | ログ書き出し。`GET /manual/api/rooms/{roomId}/log` + `Content-Disposition: attachment` | 採用 |
| 6 | 自動記録ログの追加分(入退室・切断・復帰・リセット)を本バッチでまとめて足す | 採用 |

---

## 1. 変更したファイル

```
src/main/java/com/example/qte/QteBattleApplication.java          ★@EnableScheduling を追加
src/main/java/com/example/qte/web/LobbyController.java           ★/ と /auto を入れ替え
src/main/resources/templates/lobby.html                          手動モードへのリンクを1行追加

src/main/java/com/example/qte/manual/ManualSeat.java             ★lastImport を追加
src/main/java/com/example/qte/manual/ManualGameService.java      ★applyImport に共通化、resetRoom を新設
src/main/java/com/example/qte/manual/ManualOccupant.java         ★sessionId を追加
src/main/java/com/example/qte/manual/ManualRoom.java              findOccupantBySession を追加
src/main/java/com/example/qte/manual/ManualRoomManager.java       allRooms を追加

src/main/java/com/example/qte/manual/web/ManualWsController.java  ★ManualOpsWsControllerを統合。leave/reset を追加
src/main/java/com/example/qte/manual/web/ManualOpsWsController.java  ★削除(統合済み)
src/main/java/com/example/qte/manual/web/ManualBattleController.java ★削除(ManualLobbyControllerへ統合)
src/main/java/com/example/qte/manual/web/ManualLobbyController.java  ★battle() 追加、ログ書き出しAPI追加、deck-check削除
src/main/java/com/example/qte/manual/web/ManualDisconnectListener.java  ★新設
src/main/java/com/example/qte/manual/web/ManualCleanupScheduler.java    ★新設

src/main/resources/templates/manual-battle.html                  ★リセット/ログ/退室ボタン、在室者リスト枠を追加
src/main/resources/templates/manual-lobby.html                   ★新設
src/main/resources/templates/manual-deck-check.html              ★削除

src/main/resources/static/js/manual-battle.js                    ★「0) 在室」を新設、リセット/退室/在室者リスト対応
tools/check_undeclared.py                                        DEFAULT_LABELS を既知グローバルへ追加(誤検出解消)
```

**★削除した3ファイルは zip に含まれない。** 手元で該当パスを削除すること。

```
src/main/java/com/example/qte/manual/web/ManualBattleController.java
src/main/java/com/example/qte/manual/web/ManualOpsWsController.java
src/main/resources/templates/manual-deck-check.html
```

---

## 2. 設計判断

### 2-1. `LobbyController` の変更範囲を最小に抑えた

設計書 6-2 は「`/` を手動モードの新ロビーにする」と定める。素直に実装すると
`LobbyController`(通常モードのファイル)が `ManualRoomManager` 等の手動モード依存を
持ち込みそうになるが、それは避けた。

`LobbyController` に足したのは次の2メソッドだけである。

```java
@GetMapping("/auto")
public String lobby(Model model) { ... }   // 既存の中身そのまま

@GetMapping("/")
public String manualLobby() {
    return "manual-lobby";                  // ビュー名を返すだけ
}
```

`manual-lobby.html` 自体は `ManualLobbyController` が既に持っている JSON API
(`POST /manual/api/rooms`、`POST /manual/api/rooms/{roomId}/occupants`)を JS から叩く。
サーバ側の新しい依存はゼロで、`LobbyController` は「ビュー名を返すだけの薄い入口」のまま
に留められた。

### 2-2. occupantId をサーバ発行からクライアント保持へ

18b が採用した「ページ生成時に occupantId を発行してテンプレートへ埋め込む」方式
(`ManualBattleController` の暫定入口)は、**同じ人がタブを開き直すだけで新しい在室者になる**
という欠点を最初から抱えていた。切断復帰(設計書 6-3)を作るには、この方式自体を
置き換える必要があった。

19a からの流れ:

1. `manual-lobby.html` で「作る」または「入る」を選ぶと、JSON API が occupantId を発行する。
2. クライアントはその occupantId を `localStorage['qte-manual-occupant-{roomId}']` へ保存し、
   `/manual/battle/{roomId}` へ遷移する(occupantId は URL に載せない)。
3. `manual-battle.js` は STOMP 接続を始める前に `resolveOccupant()` を呼ぶ。
   localStorage に保存済みならそれを使い、無ければその場で入室APIを呼んで新規発行する。
4. `ready` メッセージで、サーバは受け取った occupantId が本当にその部屋の在室者かを
   `room.requireOccupant()` で検証する。存在しなければ `ERROR` を返す。
5. クライアントはその `ERROR`(メッセージが「この部屋に入室していません」)を見たら
   localStorage を捨てて `location.reload()` する。次のロードでは在室者が見つからないので
   自動的に新規入室へ回る。

★この5番の経路が、猶予切れで席を空けられた後の「取り残されたタブ」を正常な状態へ
戻す唯一の手段である。ページ側にエラー画面や再入室ボタンは作っていない
(自動でやり直すほうが一人回しの用途には合う)。

### 2-3. `ManualWsController` と `ManualOpsWsController` の統合

18a の javadoc に「既存ファイルの変更が許される Batch 19a で1本にまとめること」と
明記されていたとおりに実施した。`dispatch` の型は次の1つに揃えた。

```java
private void dispatch(String roomId, String occupantId, Consumer<ManualRoom> body) {
    ManualRoom room = roomManager.findRoom(roomId).orElse(null);
    if (room == null) { broadcaster.sendError(...); return; }
    try {
        synchronized (room.getLock()) {
            room.requireOccupant(occupantId);   // ★全操作で共通の在室チェック
            body.accept(room);
        }
        broadcaster.broadcast(room);
    } catch (IllegalStateException | IllegalArgumentException e) {
        broadcaster.sendError(roomId, occupantId, e.getMessage());
    }
}
```

`mutate`(盤面を変える・履歴に積む)、`direct`(盤面に触らない・履歴を動かす)、
`execute`(在室・退室・リセットなど部屋そのものを扱う)の3つの薄いラッパーが、
すべてこの `dispatch` を最終的に呼ぶ形に揃えた。`@MessageMapping` の宛先・引数の型は
18a・18c から一切変えていないため、クライアント側の呼び出し(`send('move', ...)` 等)は
無修正で動く。

### 2-4. 「リセットして引き直す」— `lastImport` の設計

設計書 7-1 は「リセット1クリックの価値が最も高い」と明言している。zip の再アップロードを
求めるのは体験として重い。

`ManualSeat` に `lastImport`(`ManualDeckImport`)を持たせ、`loadDeck` を呼ぶたびに
更新することにした。`ManualGameService` は「クリア → 配布 → シャッフル → 初期手札 → LP20」を
`applyImport(seat, imported)` という私有メソッドへ切り出し、`loadDeck`(ログ文言が違う)と
`resetRoom`(A席・B席の両方を対象に回す)の両方から呼ぶ。

```java
public void resetRoom(ManualRoom room) {
    boolean any = false;
    for (ManualSeatId seatId : ManualSeatId.values()) {
        ManualSeat seat = state.seat(seatId);
        if (seat.getLastImport() == null) continue;   // B席(空席)はスキップ
        applyImport(seat, seat.getLastImport());
        any = true;
    }
    if (!any) throw new IllegalStateException("まだデッキを読み込んでいないため、リセットできません");
    room.getHistory().clear();
    room.addLog("リセットして引き直した");
}
```

★`lastImport` は `ManualGameState#copy()`(Undo用のスナップショット)には複製せず、
`ManualSeat#copy()` の中で参照をそのまま渡す。Undo 履歴に積む対象ではなく、
「今このデッキが読み込まれている」という部屋の設定に近い情報だからである。
Undo で盤面を巻き戻しても、リセットボタンは効き続ける。

### 2-5. 切断復帰 — 新設した2つの部品

プロジェクト全体でこの種の仕組み(WebSocketセッションの生死を扱うもの)はこれが初めてであり、
通常モードにも参考になる先例が無かった。設計はゼロから行った。

**`ManualOccupant.sessionId`**: `ready` を受けた時点の STOMP セッションIDを記録する。
`SessionDisconnectEvent` はアプリ層の occupantId を持たないため、この対応表が無いと
「誰が切れたか」を特定できない。

**`ManualDisconnectListener`**(`@EventListener`): 切断イベントを受け、sessionId から
在室者を逆引きして `connected=false` / `disconnectedAt=now` にする。全部屋を走査するが、
一人回しの規模(部屋数は多くても数十)では無視できるコストと判断した。見つかった時点で
`break` する(sessionId は接続1本につき1つしか無いため)。

**`ManualCleanupScheduler`**(`@Scheduled(fixedRate = 60_000)`): 1分ごとに全部屋を確認し、
「切断中かつ5分経過」の在室者を `room.leave()` で退室させる。明示的な退室
(`ManualWsController#leave`)は即座に在室者リストから消えるため、このスケジューラの対象には
そもそも入らない。

`QteBattleApplication` に `@EnableScheduling` を足したのはこのスケジューラのためであり、
影響は手動モードの2クラスに閉じる。

### 2-6. ログの書き出しを HTTP にした理由

WebSocket 経由でバイト列を送り返し、クライアント側で `Blob` を組み立てて
`URL.createObjectURL` で落とす方式も検討したが、単なる `<a href="...">` にブラウザの
標準ダウンロードを任せるほうが圧倒的に単純である。`ManualLobbyController` に
`GET /manual/api/rooms/{roomId}/log` を足し、`Content-Disposition: attachment` を
付けたテキストレスポンスを返す形にした。`manual-battle.html` 側は
`<a th:href="@{/manual/api/rooms/{roomId}/log(roomId=${roomId})}">` の1行で足りている。

ファイル名は `qte-manual-log-{部屋コード}-{書き出し日時}.txt`。ログ本文の各行は
`[yyyy-MM-dd HH:mm:ss] 本文` の形式で、UI 上の表示(時刻のみ)より日付を含めて出力する
(長時間に渡る記録を後から見返す前提のため)。

### 2-7. 自動記録ログの追加分

設計書 5-5 が定める自動記録対象のうち、19a 以前は「入室」しかログに残っていなかった。
本バッチで次を追加した。

| 事象 | 発生源 | 文言 |
|---|---|---|
| 退室(明示的) | `ManualWsController#leave` | `{名前} が退室した` |
| 切断 | `ManualDisconnectListener` | `{名前} が切断した(5分以内に戻らなければ席を空ける)` |
| 退室(猶予切れ) | `ManualCleanupScheduler` | `{名前} が退室した(切断から5分経過)` |
| リセット | `ManualGameService#resetRoom` | `リセットして引き直した` |

「復帰」は専用のログ文言を出していない。`ready` の既存ロジック
(`if (!occupant.isConnected()) room.addLog("...が入室した")`)が、切断から戻ってきた場合も
同じ「入室した」ログを出す形にすでになっていたため、そのまま踏襲した
(初回入室と再接続を文言で区別する必要は設計書に無い)。

---

## 3. 積み残し

基盤の新設が必要だと判明した項目は無かった。今回スコープから外した、または
意図的に簡略化した点は次の3つである。

| 項目 | 扱い |
|---|---|
| 同名の在室者が複数いる場合の在室者リストの見分け | フェーズ1では自分自身に「(自分)」を付けるのみ。名前の重複対策はフェーズ2の11-2で扱う想定 |
| 部屋そのものの寿命管理(誰もいなくなった部屋の削除) | `ManualRoomManager#removeRoom` は用意されているが、呼び出し元が無い。長時間運用ではメモリに残り続けるが、一人回しの検証用途では実害が小さいため見送った |
| ログ書き出しファイルの文字コード | UTF-8 固定。Windows のメモ帳で開く場合の BOM 付与は見送った(現代の主要エディタはBOM無しUTF-8を問題なく開ける) |

フェーズ1(一人回し)はこのバッチで完成する。次はフェーズ2着手前のUI詰めセッション
(設計書11章)、または Ver.0.4 対応(15c以降)のどちらかに進む。

---

## 4. この設計が守っていること

| 制約 | どう守っているか |
|---|---|
| 既存168枚の効果を壊さない | `CardEffectRegistry` / `GameService` 等は1行も触っていない |
| Ver.0.4 対応を壊さない | ファイルが重ならない。台帳も無変更 |
| 通常モードと共存 | `LobbyController` の変更は「/」と「/auto」の付け替えのみ。`WebSocketConfig` 無変更 |
| `@MessageMapping` の宛先・ペイロード型を維持 | `ManualWsController` への統合はクラスの merge のみで、宛先は18a・18cから無変更 |
| 一人で回せる | B席(空席)は `lastImport` が無いためリセット対象から自然に外れる |
