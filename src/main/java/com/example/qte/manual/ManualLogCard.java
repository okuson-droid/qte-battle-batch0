package com.example.qte.manual;

/**
 * ログが指すカード1枚(Batch 21 設計書 5-2)。
 *
 * <h2>★所在を1枚ごとに持つ</h2>
 * マスク規則(5-2)は「移動の from か to のどちらかが、その閲覧者にとって公開ゾーンなら
 * 名前を出す」である。複数枚をまとめて動かす操作では、1回の操作の中に
 * 公開ゾーン由来のカードと非公開ゾーン由来のカードが混ざりうる
 * (手札の1枚と場の1枚を同時に墓地へ送る等)。
 * 所在をイベント単位で1つしか持たないと、そのときに全部隠すか全部見せるかしか選べず、
 * 隠せば情報が失われ、見せれば手札の名前が漏れる。1枚ごとに持てばどちらも起きない。
 *
 * <h2>名前をここで確定させる</h2>
 * カード定義は cardId から引けるが、ログのレンダリングは配信のたびに走る。
 * 名前は不変であり、記録時に1度引いておけば以後の参照が要らない。
 * ★突合できていないカード(設計書 7-3)も名前だけは持つため、この形で必ず埋まる。
 *
 * @param instanceId 個体ID。将来ログから盤面を指し示す用途に備えて残す
 * @param name       記録時に解決したカード名
 * @param seatId     所在の席。★共有ゾーンでは null
 * @param zone       所在のゾーン。★リーダーでは null
 */
public record ManualLogCard(String instanceId, String name, ManualSeatId seatId, ManualZone zone) {

    /** 中身が見えない閲覧者へ出す代替表記(5-2 の「カード1枚」)。 */
    public static final String MASKED = "カード";

    public ManualLogPlace place() {
        return new ManualLogPlace(seatId, zone);
    }
}
