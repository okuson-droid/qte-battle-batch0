package com.example.qte.manual;

/**
 * 操作を要求した人(Batch 21 設計書 6章)。
 *
 * <h2>なぜ {@link ManualOccupant} をそのまま渡さないのか</h2>
 * 権限の判定に要るのは「どの席の人か」と「制限の効く部屋か」の2つであり、
 * 後者は在室者ではなく<b>部屋</b>が持っている。両方を1つの引数にまとめておくと、
 * 操作メソッドの引数が1つで済み、テストでも部屋を作らずに actor を組み立てられる。
 *
 * <h2>★actor はログにも要る</h2>
 * 権限を切っても(全公開部屋でも)actor は必要である。ログの
 * {@link ManualLogEvent#actorSeat()} は「誰が押したか」であり、
 * 対象の席とは別物だからである。相手の場へ自分のカードを落とす操作(6-1 で許可)では、
 * 対象の席は相手だが、押したのは自分である。
 */
public record ManualActor(ManualRoomType roomType, ManualOccupant occupant) {

    public ManualActor {
        roomType = roomType == null ? ManualRoomType.OPEN : roomType;
    }

    /**
     * 権限を一切見ない actor。全公開部屋の既定であり、テストでも使う。
     * ★対戦部屋の経路でこれを作らないこと。作れば権限層が丸ごと素通りする。
     */
    public static ManualActor unrestricted() {
        return new ManualActor(ManualRoomType.OPEN, null);
    }

    public static ManualActor of(ManualRoom room, ManualOccupant occupant) {
        return new ManualActor(room.getOptions().type(), occupant);
    }

    /** 座っている席。★観戦者・在室者不明なら null */
    public ManualSeatId seat() {
        return occupant == null ? null : occupant.getSeatId();
    }

    /** 観戦者(席に着いていない)か。 */
    public boolean isSpectator() {
        return seat() == null;
    }

    /** 権限判定を働かせる部屋か(対戦部屋のみ。6章の見出し)。 */
    public boolean isRestricted() {
        return roomType.isRestricted();
    }

    public String displayName() {
        return occupant == null ? "システム" : occupant.getDisplayName();
    }
}
