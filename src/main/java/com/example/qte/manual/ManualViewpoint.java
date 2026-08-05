package com.example.qte.manual;

/**
 * 「この閲覧者に、このゾーンの中身が見えるか」を答える唯一の判定(Batch 21 設計書 3-3)。
 *
 * <h2>★情報保護の判定はこのクラスの1メソッドに集約する</h2>
 * 盤面ビュー({@link com.example.qte.manual.view.ManualViewBuilder})・ログのマスク
 * ({@link ManualLogRenderer})・ドラッグ矢印の起点フィルタ(7-3)の3経路が、
 * すべて {@link #canSeeZone} を通る。3箇所に同じ条件を書き写すと、いずれ一方だけが
 * 更新されて「盤面には出ないがログには出る」という漏れ方をする。
 * 20b の {@code sharedZones} と {@code copy()} で学んだのと同じ形の事故である。
 *
 * <h2>★これは「判断」ではない(設計書16 5-1 との関係)</h2>
 * 手動モードはゲームの裁定を行わないが、<b>情報保護は裁定ではない</b>。
 * 「相手の手札が見えない」はルールの適用ではなく、対戦が成立するための前提である。
 * 同じ理由で操作権限({@link ManualPermissions})も 5-1 の原則の外に置く。
 *
 * <h2>視点は3種類しかない</h2>
 * <ol>
 *   <li>全公開部屋 — 誰でも全部見える(現行の挙動そのまま)</li>
 *   <li>対戦部屋のプレイヤー — 自席の非公開ゾーンだけが見える</li>
 *   <li>対戦部屋の観戦者 — 全見え / 公開のみ の2択(3-2)</li>
 * </ol>
 * 値オブジェクトにしてあるので、テストでは部屋も在室者も作らずに視点だけを組み立てられる。
 *
 * @param roomType      部屋の種類。{@link ManualRoomType#OPEN} なら以降の条件は一切効かない
 * @param viewerSeat    閲覧者の席。null なら観戦者(または席に着いていない在室者)
 * @param spectatorView 観戦者の視点。★プレイヤー({@code viewerSeat != null})には効かない
 */
public record ManualViewpoint(
        ManualRoomType roomType,
        ManualSeatId viewerSeat,
        ManualSpectatorView spectatorView) {

    public ManualViewpoint {
        roomType = roomType == null ? ManualRoomType.OPEN : roomType;
        spectatorView = spectatorView == null ? ManualSpectatorView.PUBLIC_ONLY : spectatorView;
    }

    /**
     * 全部見える視点。全公開部屋・テスト・サーバ内部の処理で使う。
     * ★ログのダウンロードでこれを既定にしてはならない(5-4 の「完全ログの裏口」になる)。
     */
    public static ManualViewpoint full() {
        return new ManualViewpoint(ManualRoomType.OPEN, null, ManualSpectatorView.ALL);
    }

    /** 部屋と在室者から視点を組み立てる。在室者が null(配信先が特定できない)なら最も狭い視点にする。 */
    public static ManualViewpoint of(ManualRoom room, ManualOccupant viewer) {
        ManualRoomType type = room.getOptions().type();
        if (viewer == null) {
            return new ManualViewpoint(type, null, ManualSpectatorView.PUBLIC_ONLY);
        }
        return new ManualViewpoint(type, viewer.getSeatId(), viewer.getSpectatorView());
    }

    /** 観戦者(席に着いていない在室者)の視点か。 */
    public boolean isSpectator() {
        return viewerSeat == null;
    }

    /**
     * ゾーンの中身が見えるか(3-3 の表)。
     *
     * <h3>判定の順序に意味がある</h3>
     * 「全公開部屋」と「全見え観戦」を先に返すことで、以降の条件は
     * <b>対戦部屋で情報を絞る場合だけ</b>を考えればよくなる。
     *
     * @param ownerSeat ゾーンを持つ席。★共有ゾーンでは null になりうる
     *                  (ハンドオフ3章の「seatId == null 問題」)。無条件に参照しないこと
     * @param zone      ゾーン。★null はリーダーを表す。リーダーは常に公開である
     *                  (総合ルール 2-5 の「リーダー公開」)
     */
    public boolean canSeeZone(ManualSeatId ownerSeat, ManualZone zone) {
        if (!roomType.isRestricted()) {
            return true;
        }
        if (isSpectator() && spectatorView == ManualSpectatorView.ALL) {
            return true;
        }
        if (zone == null) {
            return true; // リーダーは両者が公開している(2-5)
        }
        if (zone.isShared() || zone.isContentsPublic()) {
            return true;
        }
        // ★ここから先は非公開ゾーンである。自席のものだけが見える。
        //   ownerSeat が null(共有ゾーン)ならここには来ないが、
        //   将来ゾーンが増えたときに null で素通りしないよう明示的に書いておく。
        return ownerSeat != null && ownerSeat == viewerSeat;
    }

    /**
     * ★マナの特例はここに置かない。
     *
     * 「表向きのマナは相手にも見える」(3-3)を {@link #canSeeZone} が MANA だけ true を
     * 返す形で実現すると、同じメソッドを使うログのマスク側まで MANA を公開扱いにしてしまい、
     * 裏向きのマナに対する操作でカード名が漏れる。特例は
     * {@link com.example.qte.manual.view.ManualViewBuilder} が
     * 「表向きカードの配列 + 裏向きの枚数」を組み立てる形で1箇所だけが知っていればよい。
     */
}
