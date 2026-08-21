package com.example.qte.manual;

/**
 * 手動モードのカード定義1件。試合中に変化しない不変データ。
 *
 * <h2>★Batch 61: text を持つようになった(keywords は今も持たない)</h2>
 *
 * 設計書 3-1 は「text と keywords を持たない」と決めていた。理由はこうだった ——
 * <b>新カード66枚にはテキストデータが存在せず、既存168枚ぶんも Ver.0.4 以降の変更で
 * 信用できない。半分だけ値があるフィールドは「値がある方だけ使ってしまう」不具合を生む。</b>
 *
 * <p>★<b>その理由はもう成り立たない。</b>Ver1.1 移行(Batch 46b)で
 * {@code manual-cards.json} は<b>全235枚に本文を持つ</b>ようになり、しかもそれが
 * 全モード共通の正である(7章)。「半分だけ値がある」状態ではない。
 * カード一覧(/manual/cards)が本文を1文字も出せなかったのは、この古い前提の名残である。
 *
 * <p><b>keywords は今も持たない。</b>こちらの理由は生きている ——
 * キーワードは<b>テキストから作る</b>のであって、データが持つものではない(裁定158)。
 * 持たせた瞬間に「テキスト」と「フィールド」という2つの正ができる。
 *
 * @param id           {@code QTE-M-<文明>-<CSV行番号>}。画像IDは内容ハッシュであり
 *                     カードを作り直すと変わるため、識別子には使わない(設計書 3-2)
 * @param type         リーダー・ミニオン・進化ミニオン・スペル・ウェポン
 * @param cost         全種別が持つ。リーダーは 0
 * @param attack       ミニオン・進化ミニオン・ウェポンのみ。他は null
 * @param hp           ミニオン・進化ミニオンのみ。他は null
 * @param text         カード本文(★Batch 61)。効果の文が無いカードは空文字である
 * @param imageId      表面画像の SHA256。{@code /cards/<imageId>.png} で配信される
 * @param ledgerCardId 退役した Ver0.4 台帳の対応カードID。新カード66枚は null。
 *                     ★台帳({@code qte-cards.json})そのものは Batch 60 で削除した。
 *                     この値は「Ver0.4 のどのカードの後身か」という<b>由来の記録</b>として残る
 */
public record ManualCardMaster(
        String id,
        String name,
        ManualCardType type,
        ManualCivilization civilization,
        Integer cost,
        Integer attack,
        Integer hp,
        String text,
        String imageId,
        String ledgerCardId) {

    /*
     * ★Batch 60: unlimitedCopies(同名無制限の宣言)を削除した。
     *
     * 「このカードは4枚以上入れられる」というテキストは Ver1.1 の235枚に1枚も無い ——
     * 最後の持ち主だった《ゾンストライカー》の構築特例は裁定267 で廃止され、
     * manual-cards.json にはこの項目自体が存在しない。つまり<b>常に false</b> であり、
     * 読む側の分岐は「必ず通らない道」だった。通らない道は、次に読む人に
     * 「そういう仕組みがある」と誤解させるだけである
     * ({@code DeckValidator} が 46b に UNLIMITED_COPIES の例外表を捨てたのと同じ理由)。
     * 必要になったら、そのカードが来たときに作り直すほうが安全である。
     */

    /** 場に出る(タイル表示になる)種別か。スペルは場に残らない。 */
    public boolean isPermanent() {
        return type == ManualCardType.MINION
                || type == ManualCardType.EVOLUTION
                || type == ManualCardType.WEAPON;
    }

    /** 台帳に対応するカードがあるか(= 通常モードに実装済みの候補であるか)。 */
    public boolean isLinkedToLedger() {
        return ledgerCardId != null;
    }
}
