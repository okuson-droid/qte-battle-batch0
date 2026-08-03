package com.example.qte.manual;

/**
 * 手動モードのカード定義1件。試合中に変化しない不変データ。
 *
 * ★text と keywords を持たない(設計書 3-1)。効果は拡大画像で人間が読む。
 * 新カード66枚にはテキストデータが存在せず、既存168枚ぶんも Ver.0.4 以降の変更で
 * 信用できない。半分だけ値があるフィールドは「値がある方だけ使ってしまう」不具合を生む。
 *
 * @param id           {@code QTE-M-<文明>-<CSV行番号>}。画像IDは内容ハッシュであり
 *                     カードを作り直すと変わるため、識別子には使わない(設計書 3-2)
 * @param type         リーダー・ミニオン・進化ミニオン・スペル・ウェポン
 * @param cost         全種別が持つ。リーダーは 0
 * @param attack       ミニオン・進化ミニオン・ウェポンのみ。他は null
 * @param hp           ミニオン・進化ミニオンのみ。他は null
 * @param imageId      表面画像の SHA256。{@code /cards/<imageId>.png} で配信される
 * @param ledgerCardId 台帳({@code qte-cards.json})の対応カードID。新カード66枚は null
 */
public record ManualCardMaster(
        String id,
        String name,
        ManualCardType type,
        ManualCivilization civilization,
        Integer cost,
        Integer attack,
        Integer hp,
        String imageId,
        String ledgerCardId) {

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
