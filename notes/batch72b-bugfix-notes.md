# Batch 72b 設計解説 —— 賢魂がスペル枠で使えない不具合

## 0. この文書の位置づけ

Batch 72 の納品後に受けた不具合報告

> スペルのエリアにドラッグしても賢魂が使えない。落とせるけど何も起きない。

の調査記録と修正の解説である。新機能ではないので「Batch 73」ではなく **72b** とした。
実装が触れたファイルは `GameWsController.java` 1つだけであり、
残りはすべて番人(試験)と記録である。

---

## 1. 症状と、マスターから受けた4つの訂正

| 段階 | クロエの見立て | マスターの訂正 |
|---|---|---|
| 1 | 効果が地味で「起きていないように見える」のではないか | 「変わらない。私がやった場合は墓地も増えないよ」 |
| 2 | Render のスリープで部屋が消えているのではないか | 「これは関係ない」 |
| 3 | マナチャージフェイズで落としているのではないか | 「メインフェイズで使えなかった。チャージフェイズは関係ない」 |
| 4 | (再掲) | 「あなたが測った環境と、実際の環境に異なる部分が必ずあるはず」 |

★**4 が転回点である。** それまでの調査はすべて
`verify/build_harness.py` が作るハーネス —— **Java を起動しない検証環境** —— の上で行っていた。
そこでは何度やっても再現しなかった。

---

## 2. 実物の上で再現させる

`mvn spring-boot:run` はオフラインではプラグインが無くて動かないので、
クラスパスを直接組んで起こした。

```
java -cp "target/classes:$(find /root/m2work/repository -name '*.jar' \
        | grep -v sources | tr '\n' ':')" com.example.qte.QteBattleApplication
```

そのうえで Playwright に **本物の受付API → 本物のデッキ読み込み → 本物のドラッグ** を
やらせ、実装の関数(`send` / `playByDrop` / `beginSelection` / `isConnected`)を
包んで足跡を採った。

```
playByDrop     zone=SPELL  card=スタンディングテント soulCost=2 soulTargets=0
beginSelection action=play-soul hi=0 extra={"manaIndexes":[]}
isConnected    ret=true
send           action=play-soul
               payload={"handIndex":0,"targets":[],"manaIndexes":[]}  ret=true
drop(泡)       defaultPrevented=true
前: trash 0 / deck 34 / hand 4
後: trash 0 / deck 34 / hand 4   ★ログが1行も増えない
```

**クライアントは最後まで正しく走り、送信も成功していた。**
同じ瞬間のサーバ側のログ:

```
ERROR ... Unhandled exception from message handler method
org.springframework.messaging.converter.MessageConversionException:
  Could not read JSON: Cannot map `null` into type `boolean`
  (through reference chain: GameWsController$PlayCardRequest["enhanced"])
  ... lookupDestination=/room/B67SFP/play-soul
```

---

## 3. 原因

```java
public record PlayCardRequest(String playerId, int handIndex,
        List<TargetChoice> targets, boolean enhanced, ...) {}
```

`enhanced` が **原始型の `boolean`** である。
一方 `play-soul` はこれを送らない —— そしてそれは正しい。
【賢魂】に強化使用は無く、`playSoul` の javadoc 自身が

> 素材も強化コストも伴わないので `materialIds` / `enhanced` は読まない。

と書いている。**型だけが「必ず在る」と要求していた。**

Spring Boot 4 が使う Jackson 3 は、record の原始型の部品が本文に無いと
`MismatchedInputException` を投げる(Jackson 2 は既定値 `false` を入れていた)。
そして **変換はハンドラに入る前に起きる** ので:

- `execute` を通らない → `broadcaster.sendError` も通らない
- 例外は `WebSocketAnnotationMethodMessageHandler` が
  「Unhandled exception」としてログに書いて終わる

→ **サーバのログにだけ残り、押した人の画面には何も返らない。**
「落とせるけど何も起きない」「墓地も増えない」は、この形の必然である。

★同じ理由で、**クリックから賢魂を使う道も壊れていた**
(`beginPlayFromHand(index, card, 'play-soul', card.soulTargets, {}, false)` の
`extra` が `{}` なので、こちらも `enhanced` を積まない)。
マスターがドラッグだけを報告したのは、ドラッグが 70 で作った新しい入口だからである。

---

## 4. 直し方

### 4-1. 原因 —— 箱型で受け、防御アクセサで畳む

```java
public record PlayCardRequest(String playerId, int handIndex,
        List<TargetChoice> targets, Boolean enhanced, List<String> materialIds,
        List<Integer> manaIndexes) {

    public Boolean enhanced() {
        return enhanced != null && enhanced;
    }
    ...
}
```

★**この形はこの record が既に持っていた。** `materialIds()` も `manaIndexes()` も
「送られてこないことがある」から null を畳んでいる。
`enhanced` だけが防御を持たなかった —— 新しい筋を持ち込んでいない(裁定130)。

★`boolean` ではなく `Boolean` を返すのは、record のアクセサは
部品と同じ戻り値型でなければならないためである。呼び出し側は自動開封で受ける。

### 4-2. 無言 —— 開けなかった手紙を捨てない(設計判断51)

原因を直しても、**この種類の事故が次に起きたときにまた無言になる**。
`@MessageExceptionHandler` を置いて、変換に失敗したメッセージを受け、
送った本人へ理由を返す。

```java
@MessageExceptionHandler(MessageConversionException.class)
public void onUnreadableMessage(MessageConversionException e, Message<byte[]> message) {
    String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
    log.error("受け取れないメッセージ: destination={} {}", destination, e.getMessage());
    String roomId = roomIdOf(destination);
    String playerId = playerIdOf(message.getPayload());
    if (roomId == null || playerId == null) {
        return;
    }
    broadcaster.sendError(roomId, playerId,
            "操作を受け取れませんでした(送信の形が不正です)。画面を再読み込みしてください");
}
```

★**本文をもう一度解釈することはしない。** 解釈できないと分かっている物である。
`playerId` という1項目だけを正規表現で拾い、拾えなければ **サーバのログだけが残る**
(黙るのは「誰に返せばよいか分からないとき」に限る)。
`roomId` は宛先ヘッダから取る —— 本文を読まずに済むほうを選んだ。

★これは **番人であって直し方ではない。** 原因は 4-1 で直してある。

---

## 5. 番人

### 5-1. `WsRequestPayloadTest` —— 本文が開けるか(原因の側)

`battle.js` の `send(...)` が実際に組み立てる本文を、
**アプリと同じ既定の変換器** `JacksonJsonMessageConverter` に通す。
宛先22すべてを覆い、**入口ごとに1行**ある(賢魂はクリックとドラッグの2行)。全29件。

★**表は増えなければならない。** 宛先や入口を足したら1行足す。
足し忘れに気づけるよう件数を1件だけ書き留めてある(件数そのものに意味は無い)。

### 5-2. `WsErrorRoutingTest` —— 開けなかったとき本人に返るか(無言の側)

★**宣言の確認では足りない。** `onUnreadableMessage` を直接呼ぶ試験は
「呼べば返す」しか言わず、今回の本体である **そこへ届かないこと** を見張れない。
→ Spring が実際に使う `SimpAnnotationMethodMessageHandler` に壊れた本文を流し込み、
ブローカー行きの通路に ERROR が1通出ることを見る。

★2件目 `開ける本文はここを通らない` は、
**開ける本文が同じ受け皿に落ちていない**ことを確かめる —— これが無いと
「常にこの文言を返す」実装でも1件目が緑になってしまう(裁定181)。

### 5-3. 壊し検証 `tools/batch72b_break_check.py`

6軸すべて OK。

| 軸 | 壊し方 | 落ちた番人 |
|---|---|---|
| 1 | `enhanced` を原始型に戻す(防御アクセサごと) | 賢魂のドラッグの本文が開ける |
| 2 | 防御アクセサだけ外す | 同上 |
| 3 | `@MessageExceptionHandler` を外す | 開けない本文は本人の宛先へ返る |
| 4 | 宛先から部屋を取り出さない(返さない出口) | 同上 |
| 5 | 本文から送り主を拾わない(返さない出口) | 同上 |
| 6 | 分かっているのに返さない(返す出口) | 同上 |

★軸1で型と防御アクセサを**一緒に**戻しているのは、片方だけだとコンパイルが通らず
試験が1件も走らない(EMPTY になる)ためである — 裁定304 の形。
★`onUnreadableMessage` は出口が2つあり、**出口ごとに軸を当ててある**(71 の教訓)。

★**verify に軸が1つも無い。** これは手抜きではなく、
**この不具合を見つけられなかった理由そのもの**である ——
ハーネスは Java を起こさないので、この経路には届かない(70・71 の教訓)。

---

## 6. 残した宿題

- **まだ原始型で受けている項目がある。** いまはどの入口も送っているので落ちないが、
  同じ形の地雷である: `ChooseOrderRequest.goFirst` / `HandActionRequest.handIndex` /
  `PlayCardRequest.handIndex` / `GraveSummonRequest.trashIndex` /
  `TabooRequest.tabooIndex` / 手動モードの `ManualDragCue.Request.active`。
  ★全部を箱型にするなら、`handIndex` が null のときに
  **`IllegalArgumentException` で断る**関門が要る(素の自動開封は
  `NullPointerException` になり、`execute` が捕まえないので**また無言に戻る**)。
  → **設計判断としてマスターに諮る案件**であり、72b では触っていない。

- **手動モードは同じ検査を持っていない。** `ManualOpRequest` 系の本文を
  `WsRequestPayloadTest` と同じ形で通す表がまだ無い。

---

## 7. 数字

| | 前 | 後 |
|---|---|---|
| JUnit | 805 | **814**(+9) |
| verify | 706 | 706(変更なし) |
| 壊し検証 | 72: 40軸 OK | **72b: 6軸 OK** |
