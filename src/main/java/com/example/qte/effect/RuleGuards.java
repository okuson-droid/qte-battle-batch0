package com.example.qte.effect;

import org.springframework.stereotype.Component;

import com.example.qte.game.DestructionCause;
import com.example.qte.game.GameState;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.master.Keyword;

/**
 * 「できる／できない」「起きる／起きない」を盤面全体から判定する層。
 *
 * <b>なぜこのクラスが必要になったか。</b>
 * 闇文明までのカードは「操作を追加する」ものが中心で、{@code GameActions} に
 * メソッドを足せば実装できた。光文明は逆に「既存の処理を止める・置き換える」
 * カードの集合であり、追加すべきなのは操作ではなく<b>判定点</b>である。
 *
 * <pre>
 *   平和の結界     : Attack3以上のミニオンは攻撃できない
 *   煌めきの盾     : このカードは攻撃できない
 *   創世神         : このミニオンはリーダーを攻撃できない
 *   ゾディアック   : 相手のリーダーは攻撃できない
 *   大天使ミカエル : 戦闘では破壊されない
 *   聖光の守護聖   : 相手の効果では破壊されない
 *   正義の御盾     : リーダーへのダメージを-1
 *   断罪の大天使   : 相手の3枚目以降のドローを墓地送りに置換
 *   断罪の聖導者   : 相手はスペルを唱えられない
 *   秩序の執行官   : 相手は特殊召喚を行えない
 *   戒律の聖堂騎士 : 相手はサブフェイズを行えない
 * </pre>
 *
 * 条件はばらばらだが、判定される場所は「攻撃宣言」「破壊」「ダメージ」「ドロー」
 * 「使用」「フェイズ進行」の6箇所に集約できる。それぞれの入口をこのクラスに集め、
 * カードごとの条件をここに差し込む形にした。
 *
 * <b>戻り値の設計。</b> 攻撃可否は理由の文字列(不可なら理由・可ならnull)を返す。
 * 例外を投げるのはルール本体(GameService)の仕事であり、判定層は判定だけを行う。
 * こうしておくと、同じ判定をビュー生成側から呼んでボタンを無効化することもできる。
 */
@Component
public class RuleGuards {

    // 攻撃を禁止するカード
    private static final String PEACE_BARRIER = "QTE-0095";   // 平和の結界(Attack3以上は攻撃不可)
    private static final String GLEAM_SHIELD = "QTE-0101";    // 煌めきの盾(自身は攻撃不可)
    private static final String GENESIS_IRIS = "QTE-0107";    // 創世神(自身はリーダーを攻撃不可)
    private static final String ZODIAC = "QTE-0104";          // ゾディアック(相手リーダーは攻撃不可)
    // 破壊・ダメージ・ドローを置換するカード
    private static final String MICHAEL = "QTE-0004";         // 大天使ミカエル(戦闘では破壊されない)
    private static final String HOLY_PROTECTOR_AURA = "QTE-L008"; // 聖光の守護聖(相手の効果で破壊されない)
    private static final String JUSTICE_SHIELD = "QTE-0103";  // 正義の御盾(リーダーへのダメージ-1)
    private static final String JUDGEMENT_ANGEL = "QTE-0096"; // 断罪の大天使(3枚目以降のドローを置換)
    // 行動そのものを禁止するカード
    private static final String ORDER_ENFORCER = "QTE-0111";  // 秩序の執行官(相手は特殊召喚不可)
    private static final String TEMPLE_KNIGHT = "QTE-0098";   // 戒律の聖堂騎士(相手はサブフェイズ不可)

    /** 断罪の大天使が置換を始めるドロー枚数(このターンのN枚目以降) */
    private static final int DRAW_REPLACE_FROM = 3;

    private final StatCalculator stats;

    public RuleGuards(StatCalculator stats) {
        this.stats = stats;
    }

    // ---------------------------------------------------------------
    // 攻撃宣言
    // ---------------------------------------------------------------

    /**
     * ミニオンが攻撃できない理由を返す。攻撃できるならnull。
     *
     * 従来 GameService.validateAttack に直書きされていた判定(攻撃回数・凍結・召喚酔い)も
     * ここへ移した。判定の置き場所が2つに分かれていると、カードが増えるたびに
     * 「どちらに書くか」を毎回考えることになるためである。
     *
     * @param targetIsLeader 攻撃対象がリーダーならtrue
     */
    public String minionAttackDenial(GameState state, PlayerState owner, MinionInstance attacker,
            boolean targetIsLeader) {
        if (attacker.getAttacksUsedThisTurn() >= 1) {
            return "このミニオンはこのターン既に攻撃しています";
        }
        if (attacker.getCannotAttackOnTurn() == state.getTurnNumber()) {
            return "このミニオンは凍結していて攻撃できません";
        }
        if (attacker.getEnteredTurn() == state.getTurnNumber()) {
            boolean allowed = attacker.hasKeyword(Keyword.HASTE)
                    || (attacker.hasKeyword(Keyword.RUSH) && !targetIsLeader);
            if (!allowed) {
                return targetIsLeader
                        ? "出たターンにリーダーへ攻撃できるのは【速攻】持ちのみです"
                        : "出たターンのミニオンは攻撃できません(【速攻】【突進】を除く)";
            }
        }
        // ---- 光文明: カードによる攻撃の禁止 ----
        if (GLEAM_SHIELD.equals(attacker.getMaster().id())) {
            return "【煌めきの盾】は攻撃できません";
        }
        if (targetIsLeader && GENESIS_IRIS.equals(attacker.getMaster().id())) {
            return "【創世神 ゾディアックアイリス】はリーダーを攻撃できません";
        }
        // 平和の結界は敵味方を問わず、Attack3以上の全てのミニオンを止める(自身も含む)
        if (isOnAnyField(state, PEACE_BARRIER)
                && stats.effectiveAttack(state, owner, attacker) >= 3) {
            return "【平和の結界】によりAttack3以上のミニオンは攻撃できません";
        }
        if (targetIsLeader && hasOnField(state.opponentOf(owner.getPlayerId()), ZODIAC)) {
            return "【天界の守護神 ゾディアック】がいるためリーダーを攻撃できません";
        }
        return null;
    }

    /** リーダー(ウェポン)が攻撃できない理由を返す。攻撃できるならnull */
    public String leaderAttackDenial(GameState state, PlayerState owner) {
        if (hasOnField(state.opponentOf(owner.getPlayerId()), ZODIAC)) {
            return "【天界の守護神 ゾディアック】がいるためリーダーは攻撃できません";
        }
        return null;
    }

    // ---------------------------------------------------------------
    // 破壊の置換
    // ---------------------------------------------------------------

    /**
     * この破壊が無効化されるか。
     *
     * <b>「相手の効果による破壊」の判定について。</b>
     * 破壊処理は「誰の効果が原因か」を引数として持っていない。全ての破壊呼び出しに
     * 実行者を足すとカード28枚分の記述が変わるため、代わりに次の推定を用いる。
     *
     * <pre>
     *   効果による破壊で、破壊されるミニオンの持ち主がターンプレイヤーでない
     *     → その破壊はターンプレイヤー(=持ち主から見た相手)の効果によるもの
     * </pre>
     *
     * 現在のカードプールでは、自分のミニオンを破壊する効果(絶望の連鎖・生贄を求める邪鬼・
     * 死者蘇生・這い寄る生霊の自壊)は全て自分のターンに発動するため、この推定は正しく働く。
     * 相手のターン中に自分のミニオンを自ら破壊するカードが登場した場合は、
     * 破壊処理に実行者を渡す方式へ切り替える必要がある。
     */
    public boolean isDestructionPrevented(GameState state, PlayerState owner, MinionInstance minion,
            DestructionCause cause) {
        // 大天使ミカエル: 戦闘では破壊されない(ダメージは受けるためHPは0のまま場に残る)
        if (cause == DestructionCause.COMBAT && MICHAEL.equals(minion.getMaster().id())) {
            return true;
        }
        // 聖光の守護聖: 相手のカードや能力の効果による破壊を防ぐ(戦闘破壊は防げない)
        if (cause == DestructionCause.EFFECT && hasPersistentAura(owner, HOLY_PROTECTOR_AURA)) {
            boolean causedByOpponent = !owner.getPlayerId().equals(state.getTurnPlayerId());
            return causedByOpponent;
        }
        return false;
    }

    // ---------------------------------------------------------------
    // ダメージ・ドローの置換
    // ---------------------------------------------------------------

    /** リーダーが実際に受けるダメージ量(正義の御盾による軽減後。下限0) */
    public int reduceLeaderDamage(GameState state, PlayerState target, int amount) {
        if (target.getEquippedWeapon() != null
                && JUSTICE_SHIELD.equals(target.getEquippedWeapon().id())) {
            return Math.max(0, amount - 1);
        }
        return amount;
    }

    /**
     * このドローが「引く代わりに墓地へ置く」に置換されるか(断罪の大天使)。
     * 数えるのはターンごとであり、ターン開始時の通常ドローも1枚目として含む。
     *
     * @param drawnSoFar このドローの直前までに、このターンで引いた枚数
     */
    public boolean isDrawReplaced(GameState state, PlayerState drawer, int drawnSoFar) {
        return hasOnField(state.opponentOf(drawer.getPlayerId()), JUDGEMENT_ANGEL)
                && drawnSoFar + 1 >= DRAW_REPLACE_FROM;
    }

    // ---------------------------------------------------------------
    // 行動の禁止
    // ---------------------------------------------------------------

    /** スペルを唱えられない理由(断罪の聖導者)。唱えられるならnull */
    public String spellDenial(GameState state, PlayerState player) {
        if (player.getSpellSealedOnTurn() == state.getTurnNumber()) {
            return "【断罪の聖導者】の効果でこのターンはスペルを唱えられません";
        }
        return null;
    }

    /** 特殊召喚を行えない理由(秩序の執行官)。行えるならnull */
    public String specialSummonDenial(GameState state, PlayerState player) {
        if (hasOnField(state.opponentOf(player.getPlayerId()), ORDER_ENFORCER)) {
            return "【秩序の執行官】の効果で【特殊召喚】を行えません";
        }
        return null;
    }

    /** サブフェイズを行えるか(戒律の聖堂騎士)。行えない場合はスキップする */
    public boolean canEnterSubPhase(GameState state, PlayerState player) {
        return !hasOnField(state.opponentOf(player.getPlayerId()), TEMPLE_KNIGHT);
    }

    // ---------------------------------------------------------------
    // 補助
    // ---------------------------------------------------------------

    /** 指定プレイヤーの場に、そのカードIDのミニオンがいるか */
    public boolean hasOnField(PlayerState player, String cardId) {
        return player.getMinionZone().stream()
                .anyMatch(m -> cardId.equals(m.getMaster().id()));
    }

    /** どちらかの場に、そのカードIDのミニオンがいるか(敵味方を問わない効果用) */
    private boolean isOnAnyField(GameState state, String cardId) {
        return hasOnField(state.getPlayer1(), cardId) || hasOnField(state.getPlayer2(), cardId);
    }

    /** 持続効果を持っているか */
    public boolean hasPersistentAura(PlayerState player, String cardId) {
        return player.getPersistentAuras().stream()
                .anyMatch(a -> a.cardId().equals(cardId));
    }
}
