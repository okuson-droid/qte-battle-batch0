package com.example.qte.manual;

import java.util.List;

/**
 * クライアントから受け取る操作リクエストの型(設計書 5-3 の13項目)。
 *
 * <h2>なぜ操作ごとに型を分けるのか</h2>
 * 「種別 + 汎用の Map」で1本にまとめる形も採れるが、そうすると
 * どの操作にどの値が要るのかがサーバのコードを読むまで分からなくなる。
 * 手動モードのクライアント(Batch 18b / 18c)を書くときに最も要る情報は
 * <b>「この操作には何を載せればよいか」</b>であり、それは型として書いてあるべきである。
 * Jackson はレコードをそのまま組み立てられるため、追加の設定も要らない。
 *
 * <h2>★null は「変えない」を意味する</h2>
 * ラッパー型({@code Integer} / {@code Boolean})を使っているのはそのためである。
 * {@code faceDown} が null なら表裏はそのまま、{@code attack} が null なら Attack はそのまま。
 * 素の {@code int} / {@code boolean} にすると、未指定と 0 / false が区別できない。
 *
 * <h2>この型はパッケージ {@code manual} に置く</h2>
 * {@code manual.web} に置くと、業務層({@link ManualOperationService})が
 * 入口のパッケージを見に行くことになり、依存が逆流する。
 * 受け取る値の形は業務の語彙であり、転送の都合ではない。
 */
public final class ManualOpRequest {

    private ManualOpRequest() {
    }

    /**
     * ゾーン間移動(設計書 5-3 の1)。挿入位置・表裏・複数枚をすべてこれ1つで扱う。
     *
     * ★{@code cardIds} には進化スタックの素材も指定できる。
     * 最上段を指定すれば束ごと動き、素材を指定すればその1枚だけが抜ける(設計書 4-5-2)。
     *
     * @param toIndex 挿入位置。null なら末尾。範囲外は丸める
     * @param faceDown null なら表裏を変えない。true/false で明示的に設定する
     */
    public record Move(
            String occupantId,
            List<String> cardIds,
            ManualSeatId toSeat,
            ManualZone toZone,
            Integer toIndex,
            Boolean faceDown) {
    }

    /**
     * 進化スタックの積み(設計書 4-5-1)。
     *
     * ★{@code materialCardIds} は0個でもよい(素材なしで場に出す形)。
     * 複数指定すれば、その全部が1つの束にまとまり、ミニオン枠が N → 1 に減る。
     *
     * @param evolutionCardId 上に乗せるカード。ふつうは手札にある
     * @param materialCardIds 素材。同じ席のミニオンゾーンの直下にあること
     * @param toIndex 素材が0個のときの置き場所。素材があるときは最も左の素材の位置を使う
     */
    public record Evolve(
            String occupantId,
            ManualSeatId seat,
            String evolutionCardId,
            List<String> materialCardIds,
            Integer toIndex) {
    }

    /** 席だけを指定する操作(山札のシャッフル)。 */
    public record Seat(String occupantId, ManualSeatId seat) {
    }

    /** ドロー。{@code count} が null なら1枚。 */
    public record Draw(String occupantId, ManualSeatId seat, Integer count) {
    }

    /**
     * LP の変更(設計書 5-3 の2)。★上限20は強制しない。0未満も許す。
     * {@code value}(直接指定)と {@code delta}(増減)はどちらか一方だけを載せる。
     */
    public record Lp(String occupantId, ManualSeatId seat, Integer value, Integer delta) {
    }

    /**
     * ATK / HP の変更(設計書 5-3 の3・4)。軸ごとに直接指定か増減かを選ぶ。
     * 何も載せない要求は誤りとして弾く。
     */
    public record Stat(
            String occupantId,
            String cardId,
            Integer attack,
            Integer hp,
            Integer attackDelta,
            Integer hpDelta) {
    }

    /** カード1枚だけを指定する操作(数値を印刷値に戻す)。 */
    public record Target(String occupantId, String cardId) {
    }

    /** 札の付け外し(設計書 5-3 の5)。外すときに {@code label} が空なら全部外す。 */
    public record Label(String occupantId, String cardId, String label) {
    }

    /**
     * 真偽値1つを複数枚にまとめて設定する操作。
     * タップ(6)・表裏(7)・ウェポンの使用済み(8)で共用する。
     *
     * @param value null なら1枚ずつ現在値を反転する。true/false なら全部をその値にする
     */
    public record Flag(String occupantId, List<String> cardIds, Boolean value) {
    }

    /** ターン番号(設計書 5-3 の10)。{@code number} か {@code delta} のどちらか一方。 */
    public record Turn(String occupantId, Integer number, Integer delta) {
    }

    /**
     * フェイズ(設計書 5-3 の10)。{@code phase} で直接指定するか、
     * {@code step} で進める / 戻す({@code ManualPhase.forward()} / {@code backward()})。
     */
    public record Phase(String occupantId, ManualPhase phase, Integer step) {
    }

    /** 勝敗の宣言(設計書 5-3 の12)。★ログに1行足すだけで、盤面には触らない。 */
    public record Declare(
            String occupantId,
            ManualSeatId seat,
            ManualDeclaration declaration,
            String note) {
    }

    /** 自由メモ(設計書 5-3 の13 / 5-5)。★このモードの成果物を生む中核機能である。 */
    public record Note(String occupantId, String text) {
    }
}
