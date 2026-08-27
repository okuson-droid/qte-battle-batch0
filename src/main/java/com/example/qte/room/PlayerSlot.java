package com.example.qte.room;

import com.example.qte.deck.DeckDefinition;

import lombok.Getter;
import lombok.Setter;

/**
 * 席に着いたプレイヤー1人分の情報(試合開始前の受付データ)。
 *
 * <h2>★Batch 66 で変わったこと</h2>
 * <ol>
 * <li><b>席({@link SeatId})を持つ。</b>65 までは到着順のリストであり、
 *     「席Aが空いている」を一覧に出せなかった。</li>
 * <li><b>デッキは後から入る。</b>65 まではロビーのフォームで受け取って
 *     コンストラクタに渡していた。66 からは盤面に入ってから読み込むので
 *     ({@code POST /auto/api/rooms/{roomId}/deck})、着席の時点では null である。</li>
 * <li><b>リーダーはデッキファイルが決める。</b>65 まではプルダウンで選べたが、
 *     それは「デッキを読まないときのプリセット(おまかせ)」のためにあった。
 *     プリセットは 66 で退役したので、リーダーの出どころは
 *     デッキファイル1つだけになった(設計判断28)。</li>
 * </ol>
 */
@Getter
public class PlayerSlot {

    private final String playerId;

    private final String displayName;

    /** この人が座っている席。着席と同時に決まり、以後変わらない */
    private final SeatId seat;

    /**
     * 読み込んだデッキファイル。<b>読み込むまでは null であり、
     * null のあいだは試合が始まらない</b>({@link GameRoom#bothReady()})。
     * 検証は読み込みの受付時に完了している。
     */
    private DeckDefinition deck;

    /** デッキ名(表示用)。デッキを読み込むまでは null */
    private String deckName;

    /** リーダーカードのID。デッキファイルの {@code leaderCardId} をそのまま持つ */
    private String leaderCardId;

    /** WebSocket接続・購読が完了して対戦準備ができたか */
    @Setter
    private boolean ready = false;

    public PlayerSlot(String playerId, String displayName, SeatId seat) {
        this.playerId = playerId;
        this.displayName = displayName;
        this.seat = seat;
    }

    /**
     * デッキを載せ替える。★試合が始まったあとに呼んではいけない
     * (呼び出し側の {@code LobbyController} が盤面の有無を見て弾く)。
     *
     * @param deck     検証済みのデッキ
     * @param deckName 表示用のデッキ名。空なら「読み込んだデッキ」に寄せる
     */
    public void loadDeck(DeckDefinition deck, String deckName) {
        this.deck = deck;
        this.deckName = deckName == null || deckName.isBlank() ? "読み込んだデッキ" : deckName;
        this.leaderCardId = deck.leaderCardId();
    }

    /** デッキが載っているか。★試合の開始条件の半分である */
    public boolean isDeckLoaded() {
        return deck != null;
    }

    /**
     * デッキを外す(★Batch 72・再戦)。{@code GameRoom.resetForRematch} だけが呼ぶ。
     *
     * <p>★<b>3つとも外す。</b>{@code leaderCardId} を残すと、
     * デッキが無いのにリーダーのIDだけが残る ——
     * 受付の画面は {@code isDeckLoaded()} を見るので表示は正しいままだが、
     * <b>「載っていないのに一部だけ載っている」という状態</b>が盤面の外に生き残る。
     * ★{@link #loadDeck} が3つとも書くので、外すほうも3つである(対にしておく)。
     */
    public void clearDeck() {
        this.deck = null;
        this.deckName = null;
        this.leaderCardId = null;
    }
}
