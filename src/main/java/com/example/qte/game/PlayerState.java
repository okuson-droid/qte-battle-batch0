package com.example.qte.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.example.qte.master.CardMaster;

import lombok.Getter;
import lombok.Setter;

/**
 * プレイヤー1人分の全状態。ゾーン(総合ルール第2章)はすべてここに集約する。
 * このクラスはSpringのビーンではない(ゲームごと・プレイヤーごとに生成される可変データ)。
 * 手札・山札などカードの実体を持たないゾーンはカードID(String)のリストで表現し、
 * 場に出て個体状態を持つミニオンだけ MinionInstance に昇格する。
 */
@Getter
public class PlayerState {

    public static final int INITIAL_LP = 20;
    public static final int MAX_MANA = 15;
    public static final int MAX_MINIONS = 6;

    private final String playerId;
    private final String displayName;
    private final CardMaster leader;

    /** 体力。0以下で即座に敗北(総合ルール2-1) */
    @Setter
    private int lp = INITIAL_LP;

    /** 山札。上から引くためDequeで表現 */
    private final Deque<String> deck = new ArrayDeque<>();

    private final List<String> hand = new ArrayList<>();

    /**
     * 禁忌デッキ(総合ルール1-3・第3章)。リーダーと異なる文明のカード8枚、同名1枚まで。
     * 山札のように順序を持たず、常に全カードから選んで使用できる(所有者のみ閲覧可能)。
     */
    private final List<String> tabooDeck = new ArrayList<>();

    /** マナゾーン(上限15枚) */
    private final List<ManaCard> manaZone = new ArrayList<>();

    /** ミニオンゾーン(上限6体) */
    private final List<MinionInstance> minionZone = new ArrayList<>();

    /** リーダーに装備中のウェポン。1枚まで(付け替え時は旧ウェポンが墓地へ) */
    @Setter
    private CardMaster equippedWeapon;

    /** 装備中ウェポンが禁忌由来か。trueなら外れたとき消滅ゾーンへ行く */
    @Setter
    private boolean equippedWeaponFromTaboo = false;

    /** 墓地(Trash) */
    private final List<String> trash = new ArrayList<>();

    /** 消滅(Lost)ゾーン。禁忌由来のカードとピュア・エレメントの一時マナが行き着く先 */
    private final List<String> lostZone = new ArrayList<>();

    /** マリガン(ゲーム開始前の手札交換)を済ませたか。1回のみ(総合ルール5章-3) */
    @Setter
    private boolean mulliganDone = false;

    /** マナチャージは1ターンに1回まで(総合ルール6章-3) */
    @Setter
    private boolean manaChargedThisTurn = false;

    /** 静寂の瞑想: このターンカードを使用できない(設計判断14: プレイヤー単位のターン内フラグ) */
    @Setter
    private boolean cannotUseCardsThisTurn = false;

    /** このターンに既にカードをプレイしたか。海皇の「メインフェーズ開始時」の近似判定に使う */
    @Setter
    private boolean playedCardThisTurn = false;

    /**
     * このターンに自分のリーダーがダメージを受けた「回数」(量ではない)。
     * 火文明の特殊召喚条件が参照する(極炎竜ヴォルカニクス4回・背水の炎壁3回)。
     */
    @Setter
    private int leaderDamagedCountThisTurn = 0;

    /** このターンに回復した「回数」。鳳凰神ヴォルカニクスレヴォ(5回)が参照する */
    @Setter
    private int healedCountThisTurn = 0;

    /**
     * 【剛火の将】の起動能力で得た割引の残り回数。
     * 「次に手札から使用する火文明ミニオン」1体にのみ適用され、消費される。
     */
    @Setter
    private int pendingFireMinionDiscount = 0;

    /** リーダー起動能力は1ターンに1回(現行の全リーダーカードの記載による) */
    @Setter
    private boolean leaderAbilityUsedThisTurn = false;

    /** リーダーの攻撃は1ターンに1回(暗黙ルール: ミニオンと同様) */
    @Setter
    private boolean leaderAttackedThisTurn = false;

    /** このターン番号の間リーダーは攻撃できない(氷結の杖の凍結)。0なら制限なし */
    @Setter
    private int leaderCannotAttackOnTurn = 0;

    /**
     * このターン中だけ有効な全体効果(オーラ)の発生源カードID。
     * 例: 溢れ出る英知。効果値は固定せず、StatCalculatorが評価のたびに算出する。
     * ターン終了時にクリアされる。
     */
    private final List<String> thisTurnAuras = new ArrayList<>();

    public PlayerState(String playerId, String displayName, CardMaster leader) {
        this.playerId = playerId;
        this.displayName = displayName;
        this.leader = leader;
    }

    /** 現在使用可能なMP(アンタップ状態のマナの枚数。裏向きでも支払いに使える) */
    public int getAvailableMp() {
        return (int) manaZone.stream().filter(m -> !m.isTapped()).count();
    }

    public boolean isMinionZoneFull() {
        return minionZone.size() >= MAX_MINIONS;
    }

    /** ターン開始処理(アンタップフェイズ相当)で呼ぶ: 全アンタップ+ターン内カウンタのリセット */
    public void startTurnReset() {
        manaZone.forEach(ManaCard::untap);
        minionZone.forEach(MinionInstance::resetAttacksUsed);
        manaChargedThisTurn = false;
        cannotUseCardsThisTurn = false;
        playedCardThisTurn = false;
        leaderDamagedCountThisTurn = 0;
        healedCountThisTurn = 0;
        pendingFireMinionDiscount = 0;
        leaderAbilityUsedThisTurn = false;
        leaderAttackedThisTurn = false;
    }
}
