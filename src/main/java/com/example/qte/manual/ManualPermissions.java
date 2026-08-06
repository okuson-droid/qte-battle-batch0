package com.example.qte.manual;

import java.util.List;

/**
 * 対戦部屋の操作権限(Batch 21 設計書 6章)。
 *
 * <h2>★これは「判断」ではない(設計書16 5-1 との関係)</h2>
 * 手動モードはゲームの裁定を実装しない。コストも召喚時効果も攻撃可否も勝敗も見ない。
 * 権限層が見るのは<b>誰がどのカードに触れるか</b>だけであり、
 * 「情報保護と盤面同一性の保証」に属する。相手の手札を勝手に動かせてしまう対戦は、
 * ルール以前に対戦として成立しない。
 * ウェポンの付け替え(20b)のような「アプリが行き先を決める」例外はここで増やさない。
 *
 * <h2>★理由を返し、例外は投げない(設計判断34 の型)</h2>
 * 各メソッドは「拒否する理由」を返し、許可なら null を返す。
 * 例外化は {@link #require} が行う。理由を値で返す形にしておくと、
 * <b>ビューが同じ判定を呼べる</b>(ボタンの活性・Undo の可否)。
 * 表示と検証がズレないのは、両者が同じ関数を通っているからである。
 *
 * <h2>拒否は黙って棄却し、操作者にだけ通知する(6-1・6-4)</h2>
 * {@link #require} が投げる例外は {@code ManualWsController.dispatch} が捕まえ、
 * 盤面を配信せずに操作者へのみ理由を返す。他の在室者には何も起きない。
 * 「相手が違反操作を試みた」ことまで見せる必要は無い。
 */
public final class ManualPermissions {

    private ManualPermissions() {
    }

    /** 理由が付いていれば例外にする。呼び出し側は結果を捨ててよい。 */
    public static void require(String denyReason) {
        if (denyReason != null) {
            throw new IllegalArgumentException(denyReason);
        }
    }

    /**
     * そもそも盤面を操作できるか(6-1 の「観戦者の全操作 — 不可」)。
     * ★全公開部屋では観戦者も操作できる。全公開部屋の在室者は席の有無に意味が無いためである。
     */
    public static String denyOperate(ManualActor actor) {
        if (!actor.isRestricted()) {
            return null;
        }
        if (actor.isSpectator()) {
            return "観戦者は盤面を操作できません";
        }
        return null;
    }

    /**
     * そのカードを<b>今の場所から動かす・書き換える</b>権限があるか(6-1・6-2)。
     *
     * <ul>
     *   <li>自席のカード — 可</li>
     *   <li>共有ゾーン(PLAY / REVEAL)— 入れた席({@code placedBySeat})のみ可(6-2)</li>
     *   <li>相手のカード — 不可(「相手に代行してもらう」の一般化)</li>
     * </ul>
     *
     * ★共有ゾーンでは {@link ManualCardRef#seatId()} が null である(ハンドオフ3章)。
     * 席で判定する分岐に入る前に、共有ゾーンを先に片付けている。
     */
    public static String denyControl(ManualActor actor, ManualCardRef ref) {
        String base = denyOperate(actor);
        if (base != null) {
            return base;
        }
        if (!actor.isRestricted()) {
            return null;
        }
        if (ref.isShared()) {
            ManualSeatId owner = ref.card().getPlacedBySeat();
            if (owner != null && owner != actor.seat()) {
                return "共有ゾーンのそのカードは、置いた席%s のプレイヤーしか動かせません".formatted(owner);
            }
            return null;
        }
        // ★リーダー(zone == null)も席に属する。同じ規則で判定してよい。
        if (ref.seatId() != null && ref.seatId() != actor.seat()) {
            return "相手のカードは操作できません(相手に操作してもらってください)";
        }
        return null;
    }

    /** 複数枚をまとめて操作するとき、1枚でも権限が無ければ全体を拒否する。 */
    public static String denyControlAll(ManualActor actor, List<ManualCardRef> refs) {
        for (ManualCardRef ref : refs) {
            String reason = denyControl(actor, ref);
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    /**
     * 相手のゾーンへ<b>落とす</b>のは許す(6-1)。
     *
     * ★何も拒否しないメソッドを残しているのは、
     * 「ここは意図的に許可している」ことをコードに書き残すためである。
     * 相手の場へミニオンを出す・相手の墓地へ送るといった代行操作は対戦で普通に起きる。
     * FIELD → FIELD の数値保持(20b)もこの経路で生きる。
     */
    public static String denyDropTo(ManualActor actor, ManualSeatId toSeat, ManualZone toZone) {
        return denyOperate(actor);
    }

    /**
     * 席に紐づくグローバル操作(ドロー・シャッフル・デッキ読込・勝敗宣言・LP)。
     *
     * ★LP をここに含めたのは設計書 6-3 の表の一般化である。表は
     * 「相手カードの数値変更は不可」と定めており、LP も相手の持ち物である。
     * ダメージを与えた側ではなく受けた側が減らす、という運用になる。
     */
    public static String denySeatAction(ManualActor actor, ManualSeatId seat) {
        String base = denyOperate(actor);
        if (base != null) {
            return base;
        }
        if (!actor.isRestricted()) {
            return null;
        }
        if (seat != null && seat != actor.seat()) {
            return "席%s の操作はできません(自分の席のぶんだけです)".formatted(seat);
        }
        return null;
    }

    /**
     * Undo(6-3)。★対戦部屋では<b>直前の操作をした席</b>だけが、1段だけ戻せる。
     *
     * 深さ1は {@link ManualHistory} が部屋の種類から決めている。ここで見るのは
     * 「戻そうとしている1手が自分の操作か」だけである。相手の操作を勝手に取り消せると、
     * 盤面が同じであることの保証が相手の同意なしに崩れる。
     */
    public static String denyUndo(ManualActor actor, ManualRoom room) {
        String base = denyOperate(actor);
        if (base != null) {
            return base;
        }
        if (!room.getHistory().canUndo()) {
            return "取り消せる操作がありません";
        }
        if (!actor.isRestricted()) {
            return null;
        }
        ManualSeatId last = room.getHistory().lastActorSeat();
        if (last != null && last != actor.seat()) {
            return "直前の操作は席%s のものです。取り消せるのは操作した本人だけです".formatted(last);
        }
        return null;
    }

    // ================= ★Batch 23: 開始シーケンス(23 設計書 7章) =================

    /**
     * 開始シーケンス中の盤面操作を止める(★23 設計書 7-1)。
     *
     * <h3>★★止めるのはサーバである(設計判断27)</h3>
     * クライアントのモーダル・オーバーレイは<b>操作補助</b>にすぎず、検証ではない。
     * 21b 1-5 の「表示と検証が同じ事実を見ている状態を保つ」がそのまま当てはまる。
     * 理由を返す形にしてあるので、ビューが同じ判定を呼んでボタンを無効化できる(設計判断34)。
     *
     * <h3>★呼び出し点は1つである</h3>
     * 盤面を変える操作はすべて {@link ManualOperationService#apply} を通る
     * (move / evolve / lp / stat / label / tap / flip / used / turn / phase / draw / shuffle)。
     * したがって棄却もそこ1箇所で足りる。操作を1つ足すたびに書き足す形にすると、
     * 必ずどれかが漏れる。Undo / Redo だけは {@code apply} を通らないため、
     * {@link ManualOperationService#undo} / {@link ManualOperationService#redo} が個別に呼ぶ。
     *
     * <h3>★止めないもの(7-2)</h3>
     * <b>リセット</b>(逃げ道。詰まったら抜けられること)・自由メモ・勝敗宣言・退室・
     * 席の移動・観戦の視点切替・ログ書出。
     * ★リセットを止めないのが最重要である。<b>止まったまま抜けられない画面を作らない。</b>
     */
    public static String denyDuringStart(ManualRoom room) {
        ManualStartPhase phase = room.getStartPhase();
        if (phase == null || !phase.isLocking()) {
            return null;
        }
        return "ゲーム開始の手続き中です(%s)。終わるまで盤面は操作できません(リセットは可能です)"
                .formatted(phase.getDisplayName());
    }

    /**
     * 開始方法(3択)を選べるか(★23 設計書 2-4・P8)。
     *
     * <ul>
     *   <li>対戦部屋の観戦者 — 不可({@link #denyOperate} が弾く)</li>
     *   <li>対戦部屋のプレイヤー — <b>作成者の席</b>のみ。
     *       ★作成者席が空席(誰も座っていない)なら、どちらの席のプレイヤーでも可。
     *       「押せる人が居ない画面」を作らないためのフォールバックである</li>
     *   <li>全公開部屋 — 誰でも可。1人が両席を操作する運用であり、席の有無に意味が無い(21 6-1)</li>
     * </ul>
     */
    public static String denyStartControl(ManualActor actor, ManualRoom room) {
        String base = denyOperate(actor);
        if (base != null) {
            return base;
        }
        if (!actor.isRestricted()) {
            return null;
        }
        ManualSeatId creator = room.getCreatorSeat();
        if (creator == null || room.occupantOfSeat(creator).isEmpty()) {
            // ★フォールバック: 作成者が居ない部屋を永久に開始できなくしない(2-4)
            return null;
        }
        if (actor.seat() != creator) {
            return "ゲームの開始方法を選べるのは、部屋を作った席%s のプレイヤーだけです".formatted(creator);
        }
        return null;
    }

    /**
     * 先攻 / 後攻を選べるか(★23 設計書 3-3)。★<b>ダイスで勝った席だけ</b>が押せる。
     *
     * ★{@link #denyStartControl} と分けてあるのは、押せる人が違うからである。
     * 開始方法は作成者、先攻の選択はダイスの勝者であり、
     * 同じ判定にまとめると「どちらの権利で押しているのか」が読めなくなる。
     */
    public static String denyOrderChoice(ManualActor actor, ManualRoom room) {
        String base = denyOperate(actor);
        if (base != null) {
            return base;
        }
        ManualSeatId chooser = room.getOrderChooserSeat();
        if (chooser == null) {
            return "先攻・後攻を選ぶ場面ではありません";
        }
        if (!actor.isRestricted()) {
            return null; // 全公開部屋は1人が両席を操作する(6-1)
        }
        if (actor.seat() != chooser) {
            return "先攻・後攻を選べるのは、ダイスで勝った席%s のプレイヤーだけです".formatted(chooser);
        }
        return null;
    }

    /**
     * Redo(6-3・D6)。★対戦部屋では提供しない。
     * 取り消しの取り消しまで相手に見せると、盤面がどこへ向かっているのか分からなくなる。
     * やり直したければ手で操作し直す。
     */
    public static String denyRedo(ManualActor actor, ManualRoom room) {
        String base = denyOperate(actor);
        if (base != null) {
            return base;
        }
        if (actor.isRestricted()) {
            return "対戦部屋ではやり直し(Redo)を使えません。手で操作し直してください";
        }
        return null;
    }
}
