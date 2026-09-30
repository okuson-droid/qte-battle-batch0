package com.example.qte.game;

import java.util.List;

/**
 * 解決の途中で起きた「出来事」の生の記録(★Batch 85a・裁定371〜376)。
 *
 * <h2>なぜ差分ではなく出来事なのか</h2>
 *
 * 84b までの演出は<b>前後2枚のビューの差分</b>から作られていた。
 * 差分には<b>「誰が誰を」が無い</b> —— 攻撃は「タップが付いた」「HP が減った」という
 * 結果としてしか映らない(`notes/batch85-demo-fx-design.md` 1-1)。
 * ★そこでサーバが<b>起きたことそのもの</b>を記録する(設計書 0章の回答)。
 *
 * <h2>★★この記録は「生」である —— 閲覧者に直接送ってはいけない</h2>
 *
 * {@link #ownerId} と {@link Ref#playerId} は<b>プレイヤーID</b>であり、
 * プレイヤーIDは<b>配信の宛先</b>そのものである(`/topic/room/{id}/player/{id}`)。
 * 相手のIDを渡すと、相手の宛先を購読して<b>相手の手札を読める</b>ようになる(設計判断42)。
 * ★★<b>閲覧者向けへの変換は {@code GameViewBuilder#buildEvents} の1箇所だけが行う</b>
 * (設計判断9: 絞る場所は1箇所)。そこで席は YOU / OPPONENT に、
 * {@code DRAW} の面は本人のぶんだけに切り落とされる。
 *
 * <h2>★いつ記録するか</h2>
 *
 * <b>その出来事を語るログ行を積む「直前」</b>に記録する。
 * {@code GameRoom#addLog} が段を切るたびに、それまでに溜まった出来事がその段へ渡る ——
 * すなわち<b>「ログ行 X の前に起きた出来事は段 X に属する」</b>(設計書 3-3)。
 * ★ログを書かない出来事(通常のドロー)は、<b>そのあとの最初の段</b>に属する。
 * その段のビューには、その出来事の結果が必ず載っている。
 *
 * @param kind    出来事の種類
 * @param ownerId 出来事の主のプレイヤーID(攻撃した側・受けた側・引いた側・勝った側など)。★生の値
 * @param src     発生元。無ければ null
 * @param dst     宛先。無ければ null
 * @param amount  量(ダメージ・回復・ターン番号)。無ければ null
 * @param after   適用後の値(残り HP / LP)。無ければ null
 * @param cardIds 出来事に関わるカードのID。★{@code DRAW} だけは本人にしか見せない
 */
public record GameEvent(Kind kind, String ownerId, Ref src, Ref dst, Integer amount, Integer after,
        List<String> cardIds) {

    /** 出来事の種類(★名前はそのまま閲覧者へ届く) */
    public enum Kind {
        /** 攻撃の宣言。src = 攻撃した側、dst = 攻撃された側(裁定371) */
        ATTACK,
        /** ダメージ。dst = 受けた側、amount = 量、after = 残り HP / LP */
        DAMAGE,
        /** 回復。dst = 回復した側、amount = 実際に増えた量、after = 回復後の HP / LP */
        HEAL,
        /** 破壊(行き先が墓地・還元・消滅のどれでも)。dst = 破壊されたミニオン */
        DESTROY,
        /** ★場のミニオンが<b>破壊されずに</b>消滅ゾーンへ行った(裁定372)。dst = そのミニオン */
        BANISH,
        /** 場に出た(召喚でも効果による「出す」でも)。dst = 出たミニオン、cardIds = 公開面 */
        SUMMON,
        /** スペル(【賢魂】・禁忌を含む)の使用。cardIds = 公開面 */
        CAST,
        /** 1枚引いた。★cardIds は本人にしか届かない */
        DRAW,
        /** ターンの開始。amount = ターン番号 */
        TURN,
        /** 決着。ownerId = 勝者 */
        GAME_OVER
    }

    /**
     * 盤面の上の「誰か」の生の参照。
     *
     * @param playerId   持ち主のプレイヤーID(★生の値)
     * @param instanceId 場のミニオンなら instanceId、リーダーなら null
     */
    public record Ref(String playerId, String instanceId) {

        /** リーダー */
        public static Ref leader(PlayerState player) {
            return new Ref(player.getPlayerId(), null);
        }

        /** 場のミニオン */
        public static Ref minion(PlayerState owner, MinionInstance minion) {
            return new Ref(owner.getPlayerId(), minion.getInstanceId());
        }

        /** リーダーを指しているか */
        public boolean isLeader() {
            return instanceId == null;
        }
    }

    public GameEvent {
        cardIds = cardIds == null ? List.of() : List.copyOf(cardIds);
    }

    // ---- 組み立て口(★引数の意味を種類ごとに固定しておく) ----

    public static GameEvent attack(PlayerState attacker, Ref src, Ref dst) {
        return new GameEvent(Kind.ATTACK, attacker.getPlayerId(), src, dst, null, null, List.of());
    }

    public static GameEvent damage(Ref dst, int amount, int after) {
        return new GameEvent(Kind.DAMAGE, dst.playerId(), null, dst, amount, after, List.of());
    }

    public static GameEvent heal(Ref dst, int amount, int after) {
        return new GameEvent(Kind.HEAL, dst.playerId(), null, dst, amount, after, List.of());
    }

    public static GameEvent destroy(PlayerState owner, MinionInstance minion) {
        return new GameEvent(Kind.DESTROY, owner.getPlayerId(), null, Ref.minion(owner, minion),
                null, null, List.of(minion.getMaster().id()));
    }

    public static GameEvent banish(PlayerState owner, MinionInstance minion) {
        return new GameEvent(Kind.BANISH, owner.getPlayerId(), null, Ref.minion(owner, minion),
                null, null, List.of(minion.getMaster().id()));
    }

    public static GameEvent summon(PlayerState owner, MinionInstance minion) {
        return new GameEvent(Kind.SUMMON, owner.getPlayerId(), null, Ref.minion(owner, minion),
                null, null, List.of(minion.getMaster().id()));
    }

    public static GameEvent cast(PlayerState player, String cardId) {
        return new GameEvent(Kind.CAST, player.getPlayerId(), null, null, null, null, List.of(cardId));
    }

    public static GameEvent draw(PlayerState player, String cardId) {
        return new GameEvent(Kind.DRAW, player.getPlayerId(), null, null, 1, null, List.of(cardId));
    }

    public static GameEvent turn(PlayerState turnPlayer, int turnNumber) {
        return new GameEvent(Kind.TURN, turnPlayer.getPlayerId(), null, null, turnNumber, null, List.of());
    }

    public static GameEvent gameOver(PlayerState winner) {
        return new GameEvent(Kind.GAME_OVER, winner.getPlayerId(), null, Ref.leader(winner),
                null, null, List.of());
    }
}
