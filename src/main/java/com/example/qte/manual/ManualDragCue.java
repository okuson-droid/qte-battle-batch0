package com.example.qte.manual;

/**
 * ドラッグ軌跡の矢印(Batch 21 設計書 7章)。★<b>揮発メッセージである。</b>
 *
 * <h2>★画素座標を中継しない(7-1)</h2>
 * ウィンドウ幅・カードの伸縮(20c)・上下反転(3-1/3-2)で座標系は閲覧者ごとに違う。
 * 座標を送ると、送った側の画面では正しく、受け取った側では見当違いの位置を指す。
 * 代わりに<b>論理アンカー</b>(ドラッグ元のゾーン+カード / ホバー中のドロップ先ゾーン)を送り、
 * 受信側が自分のレイアウト上の該当要素の中心同士を結ぶ。
 * 全ゾーンが全閲覧者のレイアウトに何らかのアンカーを持つため必ず描ける
 * (相手上段の再構成(4章)がこの性質を保証する)。
 *
 * <h2>★ログ・Undo・履歴に一切残さない(7-2)</h2>
 * ドラッグ中の動きは「起きたこと」ではない。途中で手を離せば何も起きていない。
 * 記録すると、ログが「実際には起きなかった操作」で埋まる。
 * 配信は既存の個別宛先を使うが、{@link ManualRoom#addLog} も
 * {@link ManualHistory#push} も通らない経路にする。
 *
 * <h2>★視点フィルタはサーバの責務(7-3)</h2>
 * 起点のカード識別子は「その閲覧者に見えるカードのときだけ」載せる。
 * 見えないとき(手札の1枚など)はゾーンだけを送り、矢印の根は手札の領域になる。
 * クライアントに「受け取ったけど描かない」を任せると、DevTools で中身が読める。
 */
public final class ManualDragCue {

    private ManualDragCue() {
    }

    /**
     * クライアントから受け取る要求。
     *
     * ★{@code fromZone} をクライアントに宣言させず、{@code cardId} からサーバが引き直す。
     * 外部から来るデータはすべて検証する(設計判断27)。宣言を信じると、
     * 「実際は手札にあるカードを、場にあると偽って矢印の根にする」ことができてしまい、
     * 受信側に「相手の手札の何番目を掴んだか」以上の情報が漏れる余地が生まれる。
     *
     * @param cardId 掴んでいるカードの instanceId。ドラッグ終了時は null でよい
     * @param toZone ホバー中のドロップ先ゾーン。まだどこにも重なっていなければ null
     * @param toSeat ドロップ先の席。共有ゾーンでは無視される
     * @param active false ならこの人の矢印を消す(dragend / drop。7-2)。
     *               ★★Batch 73: <b>箱型にして、null は false に畳む。</b>
     *               原始型のままだと、送られてこないこと自体が変換の失敗になり
     *               <b>メッセージごと捨てられる</b>(72b と同じ地雷である)。
     *               <p>★通常モードの {@code handIndex} は<b>畳まずに断った</b>が、
     *               ここは畳む —— 畳んだ先が「矢印を消す」= <b>何も起きない</b>だからである。
     *               <p>★★しかも <b>この宛先はエラーを返さない</b>
     *               ({@code ManualWsController.dragCue} は {@code dispatch} を通らず、
     *               盤面に無いカードも黙って捨てる。7-2)——
     *               断っても届かないのだから、断る意味が無い。
     */
    public record Request(
            String occupantId,
            String cardId,
            ManualSeatId toSeat,
            ManualZone toZone,
            Boolean active) {

        /** ★Batch 73: 送られてこなければ「矢印を消す」に落とす(安全側)。 */
        public Boolean active() {
            return active != null && active;
        }
    }

    /**
     * 各閲覧者へ配るメッセージ。★閲覧者ごとに {@code cardId} の有無が変わる(7-3)。
     *
     * @param actorSeat 掴んでいる人の席。観戦者は矢印を出さない(操作できないため)
     * @param actorName 誰が動かしているか。人数が増えたときに矢印を見分けるために使う
     * @param cardId    ★見える閲覧者にだけ入る。見えないなら null で、根はゾーンになる
     * @param active    false なら消去指示
     */
    public record View(
            ManualSeatId actorSeat,
            String actorName,
            ManualLogPlace from,
            String cardId,
            ManualLogPlace to,
            boolean active) {
    }
}
