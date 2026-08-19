package com.example.qte.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.example.qte.effect.PendingChoice;
import com.example.qte.effect.PersistentAura;
import com.example.qte.master.CardMaster;
import com.example.qte.master.Civilization;

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

    /** ミニオンゾーンの既定の上限(総合ルール2-2)。リーダーの常在能力で上書きされうる */
    public static final int DEFAULT_MINION_ZONE_LIMIT = 6;

    /**
     * ミニオンゾーンの上限として現行カードプールで到達しうる最大値(大地の巨頭)。
     * 「実際の上限」ではなく「上限の上限」であり、選択仕様(TargetSpec)のように
     * 静的に組み立てる必要がある箇所で、取りこぼしのない天井として使う。
     */
    public static final int MAX_MINION_ZONE_LIMIT = 8;

    /** 大地の巨頭。常在能力でミニオンゾーンの上限を8体に引き上げる(Ver.0.4) */
    private static final String EARTH_COLOSSUS_LEADER_ID = "QTE-M-EARTH-1";

    /**
     * このクラスが挙動を実装しているカード(★Batch 47)。
     * 趣旨と番人は {@link com.example.qte.effect.RuleGuards#IMPLEMENTED_CARDS} の説明を参照。
     */
    public static final java.util.Set<String> IMPLEMENTED_CARDS =
            java.util.Set.of(EARTH_COLOSSUS_LEADER_ID);

    private final String playerId;
    private final String displayName;
    private final CardMaster leader;

    /** 使用デッキ名(表示用)。プリセットなら「おまかせ」 */
    @Setter
    private String deckName = "おまかせ";

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

    /** ミニオンゾーン(上限はリーダーによって変わる。{@link #getMinionZoneLimit()}) */
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
     * このターンに自分が使用したカードの「枚数」(風文明が参照する)。
     * リーダー・ミニオンの起動能力の発動もカードの使用として数える
     * (発注者確認済みの横断ルール。qte-project-reference 2-9)。
     *
     * 加算は効果の解決が終わった後に行う。したがってこの値を参照する効果からは、
     * 常に「自分より前に使ったカードの枚数」が見える(裁定1: 使用カウンタは自身を含まない)。
     * 真偽値の playedCardThisTurn とは意味が異なるため統合していない。
     */
    @Setter
    private int cardsUsedThisTurn = 0;

    /** うちスペルを唱えた回数(詠唱の疾風騎士)。cardsUsedThisTurn の部分集合 */
    @Setter
    private int spellsCastThisTurn = 0;

    /**
     * 現在のターン中にマナゾーンへカードが置かれた回数(土文明の豊穣の地霊主が参照する)。
     * マナチャージ(総合ルール6章-3)・カード効果によるマナ加速のいずれによる配置も含む。
     * 配置経路は {@link GameActions#placeCardInManaFaceUp} の1箇所に集約されており、
     * そこから {@link #recordManaPlacement(int)} を通じて数える。
     * <p>
     * このカウンタは「自分のターン開始時にリセット」ではなく、配置のたびにターン番号を
     * 照合して数え直す({@link #manaPlacedCountTurn} を参照)。これは相手のターン中に
     * カード効果でマナ配置が起きた場合でも、そのターンの回数として正しく数えるためである。
     */
    private int cardsPutToManaThisTurn = 0;

    /**
     * {@link #cardsPutToManaThisTurn} が記録しているターン番号。
     * {@code GameState.getTurnNumber()} と食い違えば、別のターンの配置とみなして
     * カウンタを 0 から数え直す。初期値 -1 はどのターンとも一致しない番兵。
     */
    private int manaPlacedCountTurn = -1;

    /**
     * 現在のターンにおけるマナ配置を1回記録し、そのターンでの累計回数を返す。
     * 記録中のターンと {@code currentTurn} が異なれば、まずカウンタを 0 に戻してから
     * 数え直す。豊穣の地霊主(L012)の「2回目のマナ配置」の判定に用いる。
     */
    public int recordManaPlacement(int currentTurn) {
        if (manaPlacedCountTurn != currentTurn) {
            manaPlacedCountTurn = currentTurn;
            cardsPutToManaThisTurn = 0;
        }
        cardsPutToManaThisTurn++;
        return cardsPutToManaThisTurn;
    }

    /**
     * このターンの間、装備中ウェポンの攻撃力に加算される値(暴風の双剣)。
     * ウェポンは MinionInstance を持たないため StatModifier を積む先がなく、
     * プレイヤー単位の一時値として保持する。ターン終了時とウェポンが場を離れたときに0に戻す。
     */
    @Setter
    private int weaponAttackBonusThisTurn = 0;

    /**
     * 現在装備中のウェポンが、このターンに攻撃したか(Ver.0.4の総則変更)。
     * 「ウェポンは攻撃したらそのターンの終わりに破壊される(禁忌の場合消滅する)」を実装するための
     * 唯一の状態である。ウェポンは MinionInstance を持たず個体状態を置く先が無いため、
     * 攻撃回数(leaderAttacksUsedThisTurn)と同じくプレイヤー単位で持つ。
     *
     * 「攻撃した」の意味はリーダーの攻撃に限る(ミニオンの攻撃では立たない。発注者確認済み)。
     * ウェポンが場を離れた時点で false に戻る({@link GameActions#onWeaponLeftPlay})。
     * これにより「攻撃した後に別のウェポンへ付け替えた場合、新しいウェポンは壊れない」が成立する。
     */
    @Setter
    private boolean weaponAttackedThisTurn = false;

    /**
     * このターンに自分のリーダーがダメージを受けた「回数」(量ではない)。
     * 火文明の特殊召喚条件が参照する(極炎竜ヴォルカニクス4回・背水の炎壁3回)。
     */
    @Setter
    private int leaderDamagedCountThisTurn = 0;

    /**
     * このターンに回復した「回数」。
     * Ver.0.4 で鳳凰神ヴォルカニクスレヴォが累計量による判定へ移ったため、
     * 現在この値を参照するカードは存在しない。回数を条件にするカードは他文明にも現れうるので、
     * 被ダメージ回数(leaderDamagedCountThisTurn)と対になる計数として残している。
     */
    @Setter
    private int healedCountThisTurn = 0;

    /**
     * このターンにリーダーが回復した「累計量」を、回復させたカードの文明ごとに集計したもの
     * (鳳凰神ヴォルカニクスレヴォ: 火文明のカードで累計5以上)。
     *
     * 回数(healedCountThisTurn)と別に持つ理由は、量と回数が別の条件だからである
     * (1回で5回復と、1回復を5回は、どちらか一方しか満たさない条件がありうる)。
     * 文明ごとに分けて持つのは、「火文明のカードで」のような発生源の限定を、
     * 参照する側が加算後に絞り込めないためである。
     *
     * 発生源が不明な回復(発生源カードIDを渡していない呼び出し)はどの文明にも計上されない。
     */
    private final java.util.Map<Civilization, Integer> healedAmountByCivilizationThisTurn =
            new java.util.EnumMap<>(Civilization.class);

    /**
     * リーダーの回復を1件記録する。実際にLPが増えた分だけを計上する
     * (LP上限20で頭打ちになった分は「回復した」に数えない)。
     *
     * @param amount              実際に回復した量。0以下なら何も記録しない
     * @param sourceCivilization  回復させたカードの文明。不明ならnull(どの文明にも計上しない)
     */
    public void recordHealedAmount(int amount, Civilization sourceCivilization) {
        if (amount <= 0 || sourceCivilization == null) {
            return;
        }
        healedAmountByCivilizationThisTurn.merge(sourceCivilization, amount, Integer::sum);
    }

    /** このターン、指定した文明のカードの効果でリーダーが回復した累計量 */
    public int getHealedAmountThisTurn(Civilization civilization) {
        return healedAmountByCivilizationThisTurn.getOrDefault(civilization, 0);
    }

    /**
     * 【剛火の将】の起動能力で得た割引の残り回数。
     * 「次に手札から使用する火文明ミニオン」1体にのみ適用され、消費される。
     */
    @Setter
    private int pendingFireMinionDiscount = 0;

    /**
     * このターン中に自分のミニオンが破壊されたか。
     * 【這い寄る生霊】の特殊召喚条件が参照する。
     * 破壊の「瞬間」に割り込むのではなく、破壊が起きた事実をターン内フラグとして残し、
     * メインフェイズ中ならいつでも特殊召喚できる形にしている(黄泉還る水龍と同じ方式)。
     */
    @Setter
    private boolean ownMinionDestroyedThisTurn = false;

    /**
     * このターン中に破壊され、墓地にある自分のミニオンのカードID。
     * 【冥界神ハデス】の「このターン破壊された味方ミニオン」の蘇生対象。
     * 還元・消滅で墓地に行かなかったものは含めない(蘇生できないため)。
     */
    private final List<String> minionsDestroyedThisTurn = new ArrayList<>();

    /**
     * 【死者蘇生】の使用宣言時に生贄として破壊した自分のミニオンの数。
     * コストの評価はStatCalculatorが行うため、選択結果をここに置いて参照させる
     * (剛火の将の割引 pendingFireMinionDiscount と同じ方式)。
     */
    @Setter
    private int pendingSacrificeCount = 0;

    /**
     * 使用中のスペルの行き先の置換(a5)。nullなら通常どおり墓地(または【還元】でマナ)へ。
     * 効果が書き込み、GameService.playSpell が読んで消費する。
     */
    @Setter
    private SpellDisposition pendingSpellDisposition;

    /**
     * このターンに引いた枚数。【断罪の大天使】が「3枚目以降のドロー」を数えるために使う。
     * ターン開始時の通常ドローも1枚目として含む(発注者確認済み)。
     */
    @Setter
    private int drawnCountThisTurn = 0;

    /**
     * このターン番号の間はスペルを唱えられない(【断罪の聖導者】)。0なら制限なし。
     * 凍結(cannotAttackOnTurn)と同じく、効果を受けた時点で「次のターン番号」を記録する方式。
     */
    @Setter
    private int spellSealedOnTurn = 0;

    /**
     * ターン終了で自動的には消えない持続効果。
     * 「このターン中」の効果(thisTurnAuras)とは寿命の管理方法が異なるため別に持つ。
     */
    private final List<PersistentAura> persistentAuras = new ArrayList<>();

    /**
     * 一時的な公開領域。山札の上から表向きにしたカードが、行き先が決まるまでの間だけここに置かれる
     * (降臨の伝道師)。手札・場・マナ・墓地のどのゾーンにも属さない一時的な置き場である。
     *
     * Batch 12a で「公開されているカードの置き場(このフィールド)」と
     * 「プレイヤーへの問い合わせ(pendingChoice)」を分離した。
     * 旧 pendingReveal は両方の役割を1つのリストで兼ねていたため、
     * 公開を伴わない選択(手札から捨てる・墓地から回収する等)に流用できなかった。
     */
    private final List<String> revealedZone = new ArrayList<>();

    /**
     * 効果の解決を中断してプレイヤーに問い合わせている選択(a9)。nullなら中断していない。
     * 1プレイヤーにつき同時に1つだけ存在しうる。
     * これが非nullの間、そのプレイヤーは選択の解決以外の操作を行えない。
     */
    @Setter
    private PendingChoice pendingChoice;

    /** リーダー起動能力は1ターンに1回(現行の全リーダーカードの記載による) */
    @Setter
    private boolean leaderAbilityUsedThisTurn = false;

    /**
     * このターンにリーダーが攻撃した回数。上限は装備ウェポンによって変わる
     * (通常1回・疾風のレイピアなら2回)ため、真偽値ではなく回数で持つ(設計判断7)。
     * 上限の評価は StatCalculator.maxLeaderAttacks が行う。
     */
    @Setter
    private int leaderAttacksUsedThisTurn = 0;

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

    /**
     * このプレイヤーのミニオンゾーンの上限。相手の上限とは独立している(Ver.0.4)。
     *
     * 値を初期化時に確定させて保持するのではなく、参照のたびにリーダーから算出する。
     * 大地の巨頭の新テキストは起動能力ではなく常在能力であり、常在能力は
     * 「その瞬間に評価される」のが本プロジェクトの一貫した扱いだからである
     * (動的ステータスを毎回再計算する設計判断4と同じ理由)。保持方式だと、
     * 将来ミニオンやウェポンが上限を動かすカードが出たときに、
     * 加算・減算の取り消し漏れという形でしか壊れない。
     *
     * リーダーIDの直書きは、リーダーの常在能力に対する既存の扱いに揃えたものである
     * (StatCalculator の詠唱の風詠士、CardEffectRegistry.fireManaPlaced の豊穣の地霊主、
     * GameService の黄泉の召喚主と同じ形)。
     */
    public int getMinionZoneLimit() {
        if (EARTH_COLOSSUS_LEADER_ID.equals(leader.id())) {
            return MAX_MINION_ZONE_LIMIT;
        }
        return DEFAULT_MINION_ZONE_LIMIT;
    }

    public boolean isMinionZoneFull() {
        return minionZone.size() >= getMinionZoneLimit();
    }

    /** ターン開始処理(アンタップフェイズ相当)で呼ぶ: 全アンタップ+ターン内カウンタのリセット */
    public void startTurnReset() {
        manaZone.forEach(ManaCard::untap);
        // 総合ルール6章-2「自分の場・マナゾーンの全タップ状態カードをアンタップに戻す」。
        // 場のミニオンのタップ状態は Batch 12a(静空の風使い)で導入された
        minionZone.forEach(m -> {
            m.resetAttacksUsed();
            m.untap();
        });
        manaChargedThisTurn = false;
        cannotUseCardsThisTurn = false;
        playedCardThisTurn = false;
        cardsUsedThisTurn = 0;
        spellsCastThisTurn = 0;
        // cardsPutToManaThisTurn は startTurnReset では戻さない。
        // 配置のたびにターン番号を照合して数え直す(recordManaPlacement を参照)。
        leaderDamagedCountThisTurn = 0;
        healedCountThisTurn = 0;
        healedAmountByCivilizationThisTurn.clear();
        // 通常はターン終了時の破壊処理(GameService.finishEndTurnCleanup)で既に落ちている。
        // ここで戻すのは、ターン内フラグは自ターン開始時に必ず初期状態へ揃えるという
        // このメソッドの約束を、ウェポンだけ例外にしないためである
        weaponAttackedThisTurn = false;
        pendingFireMinionDiscount = 0;
        leaderAbilityUsedThisTurn = false;
        leaderAttacksUsedThisTurn = 0;
        ownMinionDestroyedThisTurn = false;
        drawnCountThisTurn = 0;
        pendingSacrificeCount = 0;
        minionsDestroyedThisTurn.clear();
    }

    /** マナゾーンにある裏向きのカードの枚数(闇文明の参照元) */
    public int getFaceDownManaCount() {
        return (int) manaZone.stream().filter(m -> !m.isFaceUp()).count();
    }
}
