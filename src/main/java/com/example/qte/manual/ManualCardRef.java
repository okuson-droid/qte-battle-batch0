package com.example.qte.manual;

/**
 * 盤面のどこに1枚のカードが居るかを表す。{@link ManualBoardIndex} が作る。
 *
 * <h2>なぜ instanceId だけで操作せず、所在を持ち回るのか</h2>
 * 設計書 5-3 の操作13項目の大半は「そのカードを今の場所から外して、別の場所へ入れる」である。
 * 「今の場所」は3種類あり、外し方がそれぞれ違う。
 *
 * <ol>
 *   <li>ゾーンの直下 — {@code seat.zone(z).remove(card)}</li>
 *   <li>進化スタックの素材 — {@code stackTop.getMaterials().remove(card)}(設計書 4-5-2)</li>
 *   <li>リーダー — ゾーンに属さないため、そもそも外せない</li>
 * </ol>
 *
 * 呼び出し側で毎回この3分岐を書くと、いずれ「素材を外したつもりがゾーンを走査していた」
 * という取りこぼしが出る。所在を1つの型にして、外す処理を
 * {@link ManualBoardIndex#detach(ManualGameState, ManualCardRef)} の1箇所に閉じる。
 *
 * <h2>★状態を持たない</h2>
 * これは検索結果であり、状態モデルの一部ではない。{@link ManualGameState} には入らないため、
 * スナップショット方式(設計書 5-6)には影響しない。
 * 逆に、操作を1つ適用した後のこの値は無効になる(位置がずれる)。使い捨てること。
 *
 * @param seatId 席
 * @param zone ゾーン。★リーダーのときだけ null
 * @param index ゾーン内の位置。素材のときは「その素材を抱えている最上段」の位置。リーダーは -1
 * @param materialIndex 素材リスト内の位置。素材でなければ -1
 * @param card 見つかったカードそのもの
 * @param stackTop 素材のとき、それを抱えている最上段のカード。素材でなければ null
 */
public record ManualCardRef(
        ManualSeatId seatId,
        ManualZone zone,
        int index,
        int materialIndex,
        ManualCardInstance card,
        ManualCardInstance stackTop) {

    /** リーダーか。リーダーはゾーンに属さない({@link ManualZone} の javadoc)。 */
    public boolean isLeader() {
        return zone == null;
    }

    /** 進化スタックの素材か(設計書 4-5-1)。 */
    public boolean isMaterial() {
        return stackTop != null;
    }

    /** ゾーンの直下に置かれているか。束ごと動かす対象はこれである(設計書 4-5-2 の1)。 */
    public boolean isTopLevel() {
        return zone != null && stackTop == null;
    }
}
