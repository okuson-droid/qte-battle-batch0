package com.example.qte.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.example.qte.effect.TargetSpec.Filter;
import com.example.qte.effect.TargetSpec.Kind;
import com.example.qte.effect.TargetSpec.Requirement;
import com.example.qte.effect.TargetSpec.Side;
import com.example.qte.game.GameState;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.SpellDisposition;
import com.example.qte.game.TurnPhase;
import com.example.qte.game.StatModifier;
import com.example.qte.game.PlayerState;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardType;
import com.example.qte.master.Civilization;
import com.example.qte.master.Keyword;

/**
 * カード効果の台帳。「カードID → 効果」の対応をここに一元的に登録する。
 *
 * エフェクトシステムの中核となる考え方:
 * ルール本体(GameService)は「いつ効果が発動しうるか」(タイミング)だけを知り、
 * 「何が起きるか」(効果の中身)はこの台帳から引く。カードが増えても
 * GameServiceは1行も変わらず、この台帳に登録が増えるだけ、という構造を守る。
 *
 * Batch 3で追加: 対象指定の仕様(targetSpecs)と特殊召喚の仕様(specialSummons)。
 * どちらも「カードごとに違う部分」なので、効果と同じくここに集約する。
 */
@Component
public class CardEffectRegistry {

    /** スペルの解決時効果(カードID → 処理) */
    private final Map<String, Consumer<EffectContext>> spellEffects = new HashMap<>();

    /** ミニオンのトリガー効果(カードID → タイミング → 処理) */
    private final Map<String, Map<TriggerType, Consumer<EffectContext>>> triggers = new HashMap<>();

    /** プレイ時に対象指定を要求するカード(カードID → 要求仕様) */
    private final Map<String, TargetSpec> targetSpecs = new HashMap<>();

    /** 【特殊召喚】の仕様(カードID → 条件・代替コスト) */
    private final Map<String, SpecialSummonSpec> specialSummons = new HashMap<>();

    /** リーダー起動能力(リーダーカードID → 仕様) */
    private final Map<String, LeaderAbilitySpec> leaderAbilities = new HashMap<>();

    /** ミニオンの起動能力(カードID → 仕様。a6。静空の風使いが初出) */
    private final Map<String, MinionAbilitySpec> minionAbilities = new HashMap<>();

    /** 追加コストによる強化使用(カードID → 仕様。a5。回帰の風穴・風弾の跳弾) */
    private final Map<String, EnhancedCostSpec> enhancedCosts = new HashMap<>();

    /**
     * 「自分の他のミニオンが破壊された」ことを場から監視する効果
     * (カードID → 処理。第2引数は破壊されたミニオンのカードID)。
     *
     * 破壊されたミニオン自身のトリガー(ON_DESTROYED)とは向きが逆で、
     * 場に残っている側が他者の破壊に反応する。執念の暗殺者・不滅のネクロマンサーが該当する。
     */
    private final Map<String, BiConsumer<EffectContext, String>> ownMinionDestroyedWatchers = new HashMap<>();

    /**
     * 使用条件(カードID → 判定)。「代償を払えないなら使用できない」カードのための仕組み。
     *
     * 対象指定(TargetSpec)では表現できない条件をここに置く。判定は
     * コストの支払いより前に行われるため、条件を満たさないカードは状態を一切変えずに弾かれる。
     */
    private final Map<String, BiPredicate<GameState, PlayerState>> playConditions = new HashMap<>();

    // ---------------------------------------------------------------
    // ★Batch 47: 表に登録せず、このクラスのコードに直接書かれているカード。
    // 表に載らないので {@link #isRegistered(String)} では拾えない。
    // ---------------------------------------------------------------

    private static final String ABYSS_DRAGON = "QTE-M-WATER-20";    // 黄泉還る水龍(墓地から自動で戻る)
    private static final String HARVEST_LEADER = "QTE-M-EARTH-15";  // 豊穣の地霊主(マナ設置2回目でドロー)
    private static final String FLAME_FANATIC = "QTE-M-FIRE-4";     // 火炎の狂信者(自傷でAttack+2)
    private static final String FLAME_MIRROR = "QTE-M-FIRE-28";     // 反転の炎鏡(自傷を水増しする)
    private static final String STOK_LEADER = "QTE-M-WIND-29";      // 妖ノ長・ストク(★Batch 48)

    /**
     * このクラスのコードに直接書かれている(=表に載っていない)カード(★Batch 47)。
     * 趣旨と番人は {@link RuleGuards#IMPLEMENTED_CARDS} の説明を参照。
     */
    public static final java.util.Set<String> IMPLEMENTED_CARDS =
            java.util.Set.of(ABYSS_DRAGON, HARVEST_LEADER, FLAME_FANATIC, FLAME_MIRROR,
                    STOK_LEADER);

    /** キーワード判定(知識カードの枚数条件など)にマスタ参照が必要 */
    private final CardMasterRepository cards;

    public CardEffectRegistry(CardMasterRepository cards) {
        this.cards = cards;
        registerSpells();
        registerMinionTriggers();
        registerTargetedCards();
        registerSpecialSummons();
        registerLeaderAbilities();
        registerFireCards();
        registerDarkCards();
        registerLightCards();
        registerWindCards();
        registerEarthCards();
    }

    // ---------------------------------------------------------------
    // 登録: 対象指定なしのスペル(Batch 2)
    // ---------------------------------------------------------------

    private void registerSpells() {
        // アクア・サーチ: カードを2枚引く。その後手札のカードを1枚捨てる。
        // Ver.0.4 で 1ドロー から「2ドロー+1枚捨て」に変わった。捨てる対象は引いた後の手札から
        // 選ぶため、使用宣言時に選び終える TargetSpec では表現できない。解決を中断して
        // 問い合わせる a9(割り込み選択)を使う。捨てるのは必須なので one(min=1)である
        spellEffects.put("QTE-M-WATER-25", ctx -> {
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 2);
            if (ctx.owner().getHand().isEmpty()) {
                // 山札が尽きている等で手札が空なら捨てようがない(敗北判定はdrawCards側が持つ)
                return;
            }
            List<String> handPositions = new ArrayList<>();
            for (int i = 0; i < ctx.owner().getHand().size(); i++) {
                handPositions.add(String.valueOf(i));
            }
            ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.one(
                    PendingChoice.Kind.HAND, handPositions, ResumePoint.AQUA_SEARCH_DISCARD,
                    "【アクア・サーチ】: 捨てる手札を1枚選んでください"));
        });

        spellEffects.put("QTE-M-WATER-9", // スプラッシュ・ドロー: カードを2枚引く
                ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 2));

        spellEffects.put("QTE-M-WATER-10", // 恵みの雨: リーダーを4回復。1枚引く
                ctx -> {
                    ctx.actions().healLeader(ctx.room(), ctx.owner(), 4, "QTE-M-WATER-10");
                    ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
                });

        spellEffects.put("QTE-M-WATER-27", // 流転の書: 1枚引く(【還元】の処理はGameActions側で共通)
                ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 1));

        // 静寂の瞑想: このカードはメインフェーズの最初にしか使えない(Ver.0.4で追加)。
        // 「最初」は海皇ポセイドン(0038)の【特殊召喚】条件と同じ近似で表す。すなわち
        // 「メインフェイズであり、かつこのターンまだ1枚もカードを使っていない」である。
        // playedCardThisTurn は解決後に立つため、このカード自身の使用では条件が壊れない
        playConditions.put("QTE-M-WATER-26",
                (state, player) -> state.getPhase() == TurnPhase.MAIN && !player.isPlayedCardThisTurn());
        spellEffects.put("QTE-M-WATER-26", // 静寂の瞑想: 3枚引く。このターンカードを使用できない
                ctx -> {
                    ctx.actions().drawCards(ctx.room(), ctx.owner(), 3);
                    ctx.owner().setCannotUseCardsThisTurn(true);
                    ctx.room().addLog("%sはこのターンカードを使用できません"
                            .formatted(ctx.owner().getDisplayName()));
                });

        // 溢れ出る英知: 2枚引く。ターン中、手札枚数分だけ自分の「水文明」ミニオンの攻撃+1。
        // Ver.0.4 でドローが 3 → 2 に減り、バフ対象が自分の全ミニオンから水文明に限定された。
        // 限定の判定は評価側(StatCalculator.effectiveAttack)にあり、ここはオーラを立てるだけである
        spellEffects.put("QTE-M-WATER-12",
                ctx -> {
                    ctx.actions().drawCards(ctx.room(), ctx.owner(), 2);
                    ctx.owner().getThisTurnAuras().add("QTE-M-WATER-12");
                    ctx.room().addLog("このターン中、%sの水文明ミニオンは手札の枚数分攻撃力が上がります"
                            .formatted(ctx.owner().getDisplayName()));
                });

        spellEffects.put("QTE-M-WATER-11", // タイダルウェーブ: 相手のコスト4以下のミニオンを全て手札に戻す
                ctx -> {
                    List<MinionInstance> targets = ctx.opponent().getMinionZone().stream()
                            .filter(m -> m.getMaster().cost() <= 4)
                            .toList();
                    targets.forEach(m -> ctx.actions().bounceToHand(ctx.room(), ctx.opponent(), m));
                });
    }

    // ---------------------------------------------------------------
    // 登録: 対象指定なしのミニオントリガー(Batch 2)
    // ---------------------------------------------------------------

    private void registerMinionTriggers() {
        register("QTE-M-WATER-7", TriggerType.ON_SUMMON, // 水鏡の幻術師: カードを2枚引く
                ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 2));

        register("QTE-M-WATER-3", TriggerType.ON_SUMMON, // 潮流の魔導士: 手札5枚以上ならリーダーを3回復
                ctx -> {
                    if (ctx.owner().getHand().size() >= 5) {
                        ctx.actions().healLeader(ctx.room(), ctx.owner(), 3, "QTE-M-WATER-3");
                    }
                });

        register("QTE-M-WATER-18", TriggerType.ON_ATTACK, // 波濤の突撃兵: 攻撃時1枚引く
                ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 1));
    }

    // ---------------------------------------------------------------
    // 登録: 対象指定を要するカード(Batch 3)
    // ---------------------------------------------------------------

    private void registerTargetedCards() {

        // 手札を喰らう大蟹: 【召喚時】自分の手札を1枚捨てる。そうしたらミニオン1体を持ち主の手札に戻す。
        // Ver.0.4 で2点変わった。
        //  1. バウンス対象が「相手のミニオン」から側の限定なしになった(記法規約により両者参照)
        //  2. 「そうしたら」が入り、手札を捨てられた場合にのみバウンスが発動するようになった
        // 「そうしたら」型は再起の炎陣(0052)・血の対価(0065)と同じ形で表現する。すなわち
        // 手札の要求を optional にし、選択が空なら後続を実行しない。捨てる手札が無い場面でも
        // 召喚そのものは通す必要があるため、必須指定のままにはできない
        targetSpecs.put("QTE-M-WATER-4", TargetSpec.of(
                new Requirement(Kind.HAND, Side.SELF, 1, true, false, List.of(), "捨てるカードを選んでください"),
                new Requirement(Kind.MINION, Side.ANY, 1, true, false, List.of(), "手札に戻すミニオンを選んでください")));
        register("QTE-M-WATER-4", TriggerType.ON_SUMMON, ctx -> {
            var discarded = ctx.targets().get(0);
            if (discarded.isEmpty()) {
                ctx.room().addLog("捨てる手札が無かったため【手札を喰らう大蟹】の効果は発動しませんでした");
                return; // 「そうしたら」: 捨てが成立しなければバウンスもしない
            }
            // 選択済み手札は除去済みで届くため、行き先(墓地)を決めるだけでよい
            discarded.handCardIds().forEach(id -> ctx.owner().getTrash().add(id));
            ctx.room().addLog("%sが手札を1枚捨てました".formatted(ctx.owner().getDisplayName()));
            ctx.targets().get(1).minions().forEach(
                    t -> ctx.actions().bounceToHand(ctx.room(), t.owner(), t.minion()));
        });

        // 英知の継承者: 【召喚時】【知識】を持つカードを1枚手札から捨てても良い。そうしたら【知識】を行う
        targetSpecs.put("QTE-M-WATER-19", TargetSpec.of(
                new Requirement(Kind.HAND, Side.SELF, 1, true, false, List.of(Filter.KNOWLEDGE),
                        "捨てる【知識】カードを選んでください(任意)")));
        register("QTE-M-WATER-19", TriggerType.ON_SUMMON, ctx -> {
            var selection = ctx.targets().get(0);
            if (selection.isEmpty()) {
                return; // 「〜してもよい」なので捨てなくてもよい
            }
            selection.handCardIds().forEach(id -> ctx.owner().getTrash().add(id));
            // 「【知識】を行う」= 知識のキーワードアクション(1ドロー)を実行する
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
            ctx.room().addLog("【知識】%sが1枚ドロー".formatted(ctx.owner().getDisplayName()));
        });

        // 双流の幻術師: 場に居るミニオンの数Cost-1。【召喚時】ミニオンを3体選び持ち主の手札に戻す
        // (数え方・対象とも両者の場を参照する: 発注者確認済み)。
        // Ver.0.4 でコスト参照が「知識の数」から「ミニオンの数」に、バウンスが2体から3体になった。
        // コスト側の変更は StatCalculator.effectiveCost にある
        targetSpecs.put("QTE-M-WATER-21", TargetSpec.of(
                new Requirement(Kind.MINION, Side.ANY, 3, false, false, List.of(), "手札に戻すミニオンを3体選んでください")));
        register("QTE-M-WATER-21", TriggerType.ON_SUMMON, ctx -> ctx.targets().get(0).minions().forEach(
                t -> ctx.actions().bounceToHand(ctx.room(), t.owner(), t.minion())));
    }

    // ---------------------------------------------------------------
    // 登録: 【特殊召喚】(Batch 3)
    // ---------------------------------------------------------------

    private void registerSpecialSummons() {

        // 深海神 プレサージュ: 自分の知識を持つカードを手札から5枚山札の下に置いて0コストで出せる
        specialSummons.put("QTE-M-WATER-24", SpecialSummonSpec.of(
                (state, player, handIndex) -> countKnowledgeInHandExcluding(player, handIndex) >= 5,
                TargetSpec.of(new Requirement(Kind.HAND, Side.SELF, 5, false, false, List.of(Filter.KNOWLEDGE),
                        "山札の下に置く【知識】カードを5枚選んでください")),
                ctx -> {
                    ctx.targets().get(0).handCardIds().forEach(id -> ctx.owner().getDeck().addLast(id));
                    ctx.room().addLog("%sが手札5枚を山札の下に置きました".formatted(ctx.owner().getDisplayName()));
                },
                "手札の【知識】カード5枚を山札の下に置き、0コストで召喚します"));

        // 知恵の双翼: 自分の【知識】を持つミニオンを2体手札に戻して0コストで出せる
        specialSummons.put("QTE-M-WATER-22", SpecialSummonSpec.of(
                (state, player, handIndex) -> player.getMinionZone().stream()
                        .filter(m -> m.hasKeyword(Keyword.KNOWLEDGE)).count() >= 2,
                TargetSpec.of(new Requirement(Kind.MINION, Side.SELF, 2, false, false, List.of(Filter.KNOWLEDGE),
                        "手札に戻す自分の【知識】ミニオンを2体選んでください")),
                ctx -> ctx.targets().get(0).minions().forEach(
                        t -> ctx.actions().bounceToHand(ctx.room(), t.owner(), t.minion())),
                "自分の【知識】ミニオン2体を手札に戻し、0コストで召喚します"));

        // 智将 ポセイドン・コア: 自分の【知識】ミニオンの合計体力が12以上なら0コストで出せる
        specialSummons.put("QTE-M-WATER-23", SpecialSummonSpec.of(
                (state, player, handIndex) -> player.getMinionZone().stream()
                        .filter(m -> m.hasKeyword(Keyword.KNOWLEDGE))
                        .mapToInt(MinionInstance::getCurrentHp).sum() >= 12,
                TargetSpec.of(),
                ctx -> {
                },
                "【知識】ミニオンの合計体力12以上: 代替コストなしで0コスト召喚します"));
        // ポセイドン・コアの【召喚時】: 自分のミニオンは【突進】を得る
        // (召喚時点で場にいるミニオンにのみ永続付与: 発注者確認済み。自身も場にいるため含まれる)
        register("QTE-M-WATER-23", TriggerType.ON_SUMMON, ctx -> {
            ctx.owner().getMinionZone().forEach(m -> m.grantKeyword(Keyword.RUSH));
            ctx.room().addLog("%sのミニオンは【突進】を得ました".formatted(ctx.owner().getDisplayName()));
        });

        // 海皇 ポセイドン: メインフェーズ開始時、手札7枚以上なら手札3枚を捨ててコストなしで出せる
        // 「開始時」の厳密な実装は「このターンまだカードをプレイしていない」で近似する(設計解説4章)
        specialSummons.put("QTE-M-WATER-8", SpecialSummonSpec.of(
                (state, player, handIndex) -> player.getHand().size() >= 7 && !player.isPlayedCardThisTurn(),
                TargetSpec.of(new Requirement(Kind.HAND, Side.SELF, 3, false, false, List.of(),
                        "捨てるカードを3枚選んでください")),
                ctx -> {
                    ctx.targets().get(0).handCardIds().forEach(id -> ctx.owner().getTrash().add(id));
                    ctx.room().addLog("%sが手札3枚を捨てました".formatted(ctx.owner().getDisplayName()));
                },
                "手札3枚を捨て、コストを支払わずに召喚します(メインフェーズ開始時のみ)"));
    }

    // ---------------------------------------------------------------
    // 登録: リーダー起動能力(Batch 4)
    // ---------------------------------------------------------------

    private void registerLeaderAbilities() {
        // 蒼海の賢者: 自分の手札を1枚デッキの一番下に戻す。自分のリーダーの体力を2回復
        leaderAbilities.put("QTE-M-WATER-1", LeaderAbilitySpec.of(0,
                TargetSpec.of(new TargetSpec.Requirement(TargetSpec.Kind.HAND, TargetSpec.Side.SELF,
                        1, false, false, List.of(), "山札の一番下に戻すカードを選んでください")),
                ctx -> {
                    ctx.targets().get(0).handCardIds().forEach(id -> ctx.owner().getDeck().addLast(id));
                    ctx.actions().healLeader(ctx.room(), ctx.owner(), 2, "QTE-M-WATER-1");
                },
                "手札1枚を山札の下に戻し、リーダーを2回復"));

        // 流転の智者: コスト2支払っても良い。そうしたら、マナを1枚手札に戻して2ドロー
        leaderAbilities.put("QTE-M-WATER-15", LeaderAbilitySpec.of(2,
                TargetSpec.of(new TargetSpec.Requirement(TargetSpec.Kind.MANA, TargetSpec.Side.SELF,
                        1, false, false, List.of(), "手札に戻すマナを選んでください")),
                ctx -> {
                    ctx.targets().get(0).mana().forEach(mana -> {
                        ctx.owner().getManaZone().remove(mana);
                        ctx.owner().getHand().add(mana.getCardId());
                        ctx.room().addLog("%sがマナを1枚手札に戻しました"
                                .formatted(ctx.owner().getDisplayName()));
                    });
                    // マナがマナゾーンを離れた → ゾーン横断トリガーの発火(黄泉還る水龍)
                    ctx.actions().manaLeft(ctx.room(), ctx.owner());
                    ctx.actions().drawCards(ctx.room(), ctx.owner(), 2);
                },
                "コスト2: マナ1枚を手札に戻して2ドロー"));
    }

    public LeaderAbilitySpec leaderAbilityOf(String leaderCardId) {
        return leaderAbilities.get(leaderCardId);
    }

    /** ミニオンの起動能力の仕様(持たないカードはnull) */
    public MinionAbilitySpec minionAbilityOf(String cardId) {
        return minionAbilities.get(cardId);
    }

    /** 追加コストによる強化使用の仕様(持たないカードはnull) */
    public EnhancedCostSpec enhancedCostOf(String cardId) {
        return enhancedCosts.get(cardId);
    }

    /**
     * 「自分がカードを1枚使用し終えた」イベントの発火(a1)。
     *
     * GameService がカードの使用を数え終えた直後に呼ぶ。発火時点で場にいるミニオンと、
     * 装備中のウェポンだけが反応する(後から出てきたミニオンに遡って効果が及ばないようにするため、
     * カウンタの差分ではなくイベントで配る形にしている)。
     *
     * ウェポンは MinionInstance を持たないため source は null のまま発火する。
     */
    public void fireCardUsed(EffectContext ctx) {
        PlayerState owner = ctx.owner();
        // 効果の中で場が変化しても走査が壊れないように、発火時点の場を写してから回す
        for (MinionInstance minion : List.copyOf(owner.getMinionZone())) {
            Consumer<EffectContext> effect = triggers
                    .getOrDefault(minion.getMaster().id(), Map.of())
                    .get(TriggerType.ON_CARD_USED);
            if (effect != null) {
                effect.accept(ctx.withSource(minion));
            }
        }
        if (owner.getEquippedWeapon() != null) {
            Consumer<EffectContext> effect = triggers
                    .getOrDefault(owner.getEquippedWeapon().id(), Map.of())
                    .get(TriggerType.ON_CARD_USED);
            if (effect != null) {
                effect.accept(ctx);
            }
        }
    }

    /**
     * 「自分のマナがマナゾーンを離れた」イベントの処理。
     * 黄泉還る水龍: このカードが墓地にあれば場に「出す」。
     * 召喚ではないためON_ENTERのみが発動する(GameActions.putIntoFieldByEffect側で保証)。
     * 場に限定されないゾーン横断トリガー(設計判断15)の初の実装例。
     */
    public void fireManaLeft(EffectContext ctx) {
        while (ctx.owner().getTrash().contains(ABYSS_DRAGON) && !ctx.owner().isMinionZoneFull()) {
            ctx.owner().getTrash().remove(ABYSS_DRAGON);
            ctx.actions().putIntoFieldByEffect(ctx.room(), ctx.owner(), ABYSS_DRAGON);
        }
    }

    /**
     * 「自分のミニオンが破壊された」イベントの処理。
     * 場に残っている自分のミニオンのうち、監視効果を登録しているものだけが反応する。
     *
     * 反復中に効果が場を変える(蘇生で増える・連鎖破壊で減る)ため、
     * リストのコピーを走査し、実行直前に「まだ場にいるか」を確認する。
     *
     * @param destroyedCardId 破壊されたミニオンのカードID(既に場を離れているため実体ではなくIDで渡す)
     */
    public void fireOwnMinionDestroyed(EffectContext ctx, String destroyedCardId) {
        // 妖ノ長・ストク(★Batch 48): 【常在】ターンに1回、自分のミニオンが破壊されたら2回復。
        // リーダーの常在能力をカードIDの直書きで判定するのは fireManaPlaced(豊穣の地霊主)と同じ形である。
        //
        // ★「ターンに1回」は毎ターンリセットされる(裁定156(3)) —— 自分のターンで1回、
        // 相手のターンで1回である。相手ターンの戦闘破壊でも回復するため、
        // ターン内フラグではなくターン番号の刻印で判定する(PlayerState.tryConsumeDestroyHeal)。
        // このメソッドは「破壊されたミニオンの持ち主」を owner として呼ばれるので、
        // 相手ターン中の発火もそのまま拾える
        if (STOK_LEADER.equals(ctx.owner().getLeader().id())
                && ctx.owner().tryConsumeDestroyHeal(ctx.state().getTurnNumber())) {
            ctx.room().addLog("【妖ノ長・ストク】: 自分のミニオンが破壊されたため2回復");
            ctx.actions().healLeader(ctx.room(), ctx.owner(), 2, STOK_LEADER);
        }
        for (MinionInstance watcher : List.copyOf(ctx.owner().getMinionZone())) {
            BiConsumer<EffectContext, String> effect =
                    ownMinionDestroyedWatchers.get(watcher.getMaster().id());
            if (effect == null || !ctx.owner().getMinionZone().contains(watcher)) {
                continue;
            }
            effect.accept(ctx, destroyedCardId);
        }
    }

    /**
     * 「自分のマナゾーンにカードが置かれた」イベントの処理(土文明)。
     * GameActions.placeCardInManaFaceUp が配置1回ごとに呼ぶ(マナチャージ・カード効果を問わない)。
     *
     * 豊穣の地霊主(L012): マナにカードが置かれたとき、そのターン中それが2回目なら1ドロー。
     * カウンタは配置イベントの発火前に加算済みのため、2回目の配置ではちょうど2を読む。
     * fireManaLeft と同じく、リーダーの常在能力をカードIDの直書きで判定する。
     */
    public void fireManaPlaced(EffectContext ctx) {
        PlayerState owner = ctx.owner();
        if (HARVEST_LEADER.equals(owner.getLeader().id())
                && owner.getCardsPutToManaThisTurn() == 2) {
            ctx.room().addLog("【豊穣の地霊主】: このターン2回目のマナ配置により1ドロー");
            ctx.actions().drawCards(ctx.room(), owner, 1);
        }
    }

    /**
     * ウェポンの装備時効果(ON_EQUIP)の発火。GameService.equipWeapon が装備直後に呼ぶ。
     * ガイア・ハンマー(装備時に山札の上から1枚を表向きでマナに置く)が使う。
     * 【知識】の装備時ドローは GameService.equipWeapon が別途処理しているためここでは扱わない。
     * ウェポンは MinionInstance を持たないため source は null のまま渡される。
     */
    public void fireEquip(String weaponId, EffectContext ctx) {
        Consumer<EffectContext> effect = triggers
                .getOrDefault(weaponId, Map.of())
                .get(TriggerType.ON_EQUIP);
        if (effect != null) {
            effect.accept(ctx);
        }
    }

    /**
     * 「自分のミニオンが攻撃した/場に出た」イベントに対する、装備中ウェポンの反応(Ver.0.4)。
     * 魔剣レーヴァテイン(ON_ALLY_MINION_ATTACK)・禁忌の冥魔剣(ON_ALLY_MINION_ENTER)が使う。
     *
     * ウェポンの既存の発火口は「装備時(ON_EQUIP)」と「リーダー攻撃時(GameService.leaderAttack の
     * switch)」の2つだけで、味方ミニオンのイベントを拾う経路が無かった。ここを
     * leaderAttack と同じくカードIDのswitchで書かずトリガー型として起こしたのは、
     * 発火の判断(誰が反応するか)と効果の中身(何が起きるか)を分けるという台帳の原則を、
     * ウェポンにも通すためである。効果の中身は各文明の登録メソッドに置ける。
     *
     * @param ctx イベントの持ち主(ctx.owner())は、攻撃した/場に出たミニオンの持ち主である。
     *            ctx.source() にはそのミニオンが入る(現行2枚は参照しないが、
     *            「出たミニオンのコスト分」のような効果に備えて渡している)
     */
    public void fireAllyMinionEvent(TriggerType trigger, EffectContext ctx) {
        CardMaster weapon = ctx.owner().getEquippedWeapon();
        if (weapon == null) {
            return;
        }
        Consumer<EffectContext> effect = triggers
                .getOrDefault(weapon.id(), Map.of())
                .get(trigger);
        if (effect != null) {
            effect.accept(ctx);
        }
    }

    private int countKnowledgeInHandExcluding(PlayerState player, int excludeIndex) {
        int count = 0;
        for (int i = 0; i < player.getHand().size(); i++) {
            if (i == excludeIndex) {
                continue; // プレイしようとしているこのカード自身は数えない
            }
            if (cards.findById(player.getHand().get(i)).hasKeyword(Keyword.KNOWLEDGE)) {
                count++;
            }
        }
        return count;
    }

    // ---------------------------------------------------------------
    // 登録: 火文明(Batch 8で全面実装)
    // ---------------------------------------------------------------

    private void registerFireCards() {

        // ---- 【召喚時】 ----

        // 血誓のバーサーカー: 自分のリーダーに1ダメージ。
        // 体力が10以上なら追加で2ダメージ(判定は1ダメージを与えた後: 発注者確認済み)
        register("QTE-M-FIRE-16", TriggerType.ON_SUMMON, ctx -> {
            ctx.actions().damageLeader(ctx.room(), ctx.owner(), 1, "QTE-M-FIRE-16");
            if (ctx.owner().getLp() >= 10) {
                ctx.actions().damageLeader(ctx.room(), ctx.owner(), 2, "QTE-M-FIRE-16");
            }
        });

        // ブラッドレイジの突撃兵: 自分のリーダーに2ダメージ
        register("QTE-M-FIRE-3", TriggerType.ON_SUMMON,
                ctx -> ctx.actions().damageLeader(ctx.room(), ctx.owner(), 2, "QTE-M-FIRE-3"));

        // 赫灼の重戦士: 自分のリーダーの体力が10以下ならこれは【速攻】を得る
        register("QTE-M-FIRE-5", TriggerType.ON_SUMMON, ctx -> {
            if (ctx.owner().getLp() <= 10 && ctx.source() != null) {
                ctx.source().grantKeyword(Keyword.HASTE);
                ctx.room().addLog("【赫灼の重戦士】は【速攻】を得た");
            }
        });

        // 痛撃の炎術師: 自分のリーダーの体力が10以上なら自分のリーダーに1ダメージ
        register("QTE-M-FIRE-18", TriggerType.ON_SUMMON, ctx -> {
            if (ctx.owner().getLp() >= 10) {
                ctx.actions().damageLeader(ctx.room(), ctx.owner(), 1, "QTE-M-FIRE-18");
            }
        });

        // 相打ちの咎人: 以下を2回行う。自分のリーダーに1ダメージ、相手のリーダーに1ダメージ
        register("QTE-M-FIRE-19", TriggerType.ON_SUMMON, ctx -> {
            for (int i = 0; i < 2; i++) {
                ctx.actions().damageLeader(ctx.room(), ctx.owner(), 1, "QTE-M-FIRE-19");
                ctx.actions().damageLeader(ctx.room(), ctx.opponent(), 1, "QTE-M-FIRE-19");
            }
        });

        // 背水の烈火使い: 手札をすべて捨てる
        register("QTE-M-FIRE-7", TriggerType.ON_SUMMON, ctx -> {
            int count = ctx.owner().getHand().size();
            ctx.owner().getTrash().addAll(ctx.owner().getHand());
            ctx.owner().getHand().clear();
            ctx.room().addLog("%sは手札%d枚をすべて捨てた".formatted(ctx.owner().getDisplayName(), count));
        });

        // 背水の炎壁: 【召喚時】2回復(特殊召喚で出した場合の追加1回復は下のspecで別途)。
        // Ver.0.4 で【召喚時】の回復量が 1 → 2 に増えた。特殊召喚の追加分(1)は据え置きのため、
        // 特殊召喚で出した場合の合計は 3 になる(【特殊召喚】も召喚でありON_SUMMONを通る)
        register("QTE-M-FIRE-21", TriggerType.ON_SUMMON,
                ctx -> ctx.actions().healLeader(ctx.room(), ctx.owner(), 2, "QTE-M-FIRE-21"));

        // 逆境の猛火者: 体力10以下なら手札からコスト4以下のミニオンを1体場に出す。
        // 条件を満たさないときのために選択は任意(optional)としている
        targetSpecs.put("QTE-M-FIRE-17", TargetSpec.of(Requirement.filtered(
                Kind.HAND, Side.SELF, 1, true, "場に出すコスト4以下のミニオンを選んでください(体力10以下のときのみ有効)",
                Filter.MINION_CARD, Filter.COST_4_OR_LESS)));
        register("QTE-M-FIRE-17", TriggerType.ON_SUMMON, ctx -> {
            var selection = ctx.targets().get(0);
            if (selection.isEmpty()) {
                return;
            }
            if (ctx.owner().getLp() > 10) {
                // 条件を満たさない場合、選ばれたカードは手札から失われたままにはしない
                selection.handCardIds().forEach(id -> ctx.owner().getHand().add(id));
                ctx.room().addLog("体力が10を超えているため効果は発動しなかった");
                return;
            }
            // 「出す」であり召喚ではないため【召喚時】は発動しない(ON_ENTERのみ)
            selection.handCardIds().forEach(id ->
                    ctx.actions().putIntoFieldByEffect(ctx.room(), ctx.owner(), id));
        });

        // ---- スペル ----

        // 武具昇華の炎: 自分のウェポンを1枚破壊する。そうしたら自分のリーダーを2回復
        spellEffects.put("QTE-M-FIRE-24", ctx -> {
            if (ctx.actions().destroyOwnWeapon(ctx.room(), ctx.owner())) {
                ctx.actions().healLeader(ctx.room(), ctx.owner(), 2, "QTE-M-FIRE-24");
            } else {
                ctx.room().addLog("破壊するウェポンがなかった");
            }
        });

        // マグマ・ストレート: ミニオン1体に3ダメージ(対象は限定なし=両者の場)
        targetSpecs.put("QTE-M-FIRE-10", TargetSpec.of(Requirement.of(
                Kind.MINION, Side.ANY, 1, false, "3ダメージを与えるミニオンを選んでください")));
        spellEffects.put("QTE-M-FIRE-10", ctx -> ctx.targets().get(0).minions().forEach(
                t -> ctx.actions().damageMinion(ctx.room(), t.owner(), t.minion(), 3)));

        // イグニッション・バースト: 自分のリーダーに2ダメージ。カードを2枚引く
        // (Ver.0.4 で自傷が1から2に増えた。自傷は火文明の解放条件を進めるため、
        //  ヴォルカニクス(4回)・背水の炎壁(3回)の回数カウンタには影響しない量の変更である)
        spellEffects.put("QTE-M-FIRE-9", ctx -> {
            ctx.actions().damageLeader(ctx.room(), ctx.owner(), 2, "QTE-M-FIRE-9");
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 2);
        });

        // 再起の炎陣: 1枚捨てる。そうしたら1枚引く。【還元】
        targetSpecs.put("QTE-M-FIRE-26", TargetSpec.of(Requirement.of(
                Kind.HAND, Side.SELF, 1, true, "捨てるカードを選んでください")));
        spellEffects.put("QTE-M-FIRE-26", ctx -> {
            var selection = ctx.targets().get(0);
            if (selection.isEmpty()) {
                return;
            }
            selection.handCardIds().forEach(id -> ctx.owner().getTrash().add(id));
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
        });

        // 血の対価: 手札を1枚捨てる。そうしたら3回復
        targetSpecs.put("QTE-M-FIRE-25", TargetSpec.of(Requirement.of(
                Kind.HAND, Side.SELF, 1, true, "捨てるカードを選んでください")));
        spellEffects.put("QTE-M-FIRE-25", ctx -> {
            var selection = ctx.targets().get(0);
            if (selection.isEmpty()) {
                return;
            }
            selection.handCardIds().forEach(id -> ctx.owner().getTrash().add(id));
            ctx.actions().healLeader(ctx.room(), ctx.owner(), 3, "QTE-M-FIRE-25");
        });

        // 捨て身の猛進: このターン中、自分のミニオンすべての攻撃力+1、および【突進】付与
        spellEffects.put("QTE-M-FIRE-12", ctx -> {
            ctx.owner().getMinionZone().forEach(m -> {
                m.addModifier(new StatModifier(StatModifier.Stat.ATTACK, StatModifier.Operation.ADD,
                        1, StatModifier.Duration.THIS_TURN, "QTE-M-FIRE-12"));
                m.grantKeywordThisTurn(Keyword.RUSH);
            });
            ctx.room().addLog("%sのミニオンは攻撃力+1と【突進】を得た(このターン中)"
                    .formatted(ctx.owner().getDisplayName()));
        });

        // フレイム・スナイプ: 相手の【守護】を持つHP5以下のミニオンを1体選び破壊
        targetSpecs.put("QTE-M-FIRE-23", TargetSpec.of(Requirement.filtered(
                Kind.MINION, Side.OPPONENT, 1, false, "破壊する相手の【守護】ミニオン(HP5以下)を選んでください",
                Filter.GUARD, Filter.HP_5_OR_LESS)));
        spellEffects.put("QTE-M-FIRE-23", ctx -> ctx.targets().get(0).minions().forEach(
                t -> ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion())));

        // 命を削る烈火: 自分のリーダーに3ダメージ。相手の場のミニオンすべてに2ダメージ
        spellEffects.put("QTE-M-FIRE-11", ctx -> {
            ctx.actions().damageLeader(ctx.room(), ctx.owner(), 3, "QTE-M-FIRE-11");
            List.copyOf(ctx.opponent().getMinionZone()).forEach(
                    m -> ctx.actions().damageMinion(ctx.room(), ctx.opponent(), m, 2));
        });

        // 命喰いの火種: 自分のリーダーに3ダメージ。その後カードを2枚引く。【還元】
        spellEffects.put("QTE-M-FIRE-27", ctx -> {
            ctx.actions().damageLeader(ctx.room(), ctx.owner(), 3, "QTE-M-FIRE-27");
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 2);
        });

        // ---- ウェポン ----

        // 魔剣 レーヴァテイン(QTE-M-FIRE-14): 自分のミニオンが攻撃した時、自分のリーダーに1ダメージ。
        // Ver.0.4 で発火元が「自分のリーダーの攻撃」から「自分のミニオンの攻撃」へ移り、
        // ダメージも3から1になった。攻撃宣言ごとに1ダメージであり、
        // 連撃の巨岩のように2回攻撃するミニオンでは2回発動する(発注者確認済み)。
        // 火文明の自傷テーマの加速装置として働く(被ダメージ回数のカウンタも同時に増える)
        register("QTE-M-FIRE-14", TriggerType.ON_ALLY_MINION_ATTACK,
                ctx -> ctx.actions().damageLeader(ctx.room(), ctx.owner(), 1, "QTE-M-FIRE-14"));

        // 真珠の三叉槍(QTE-M-WATER-13)などの「リーダーが攻撃した時」のウェポンは、
        // 従来どおり GameService.leaderAttack の switch で解決する

        // ---- 【特殊召喚】 ----

        // 極炎竜 ヴォルカニクス: ターン中に自分のリーダーが4回以上ダメージを受けている時、コスト1で出せる
        specialSummons.put("QTE-M-FIRE-8", new SpecialSummonSpec(
                (state, player, handIndex) -> player.getLeaderDamagedCountThisTurn() >= 4,
                1, TargetSpec.of(), ctx -> {
                }, ctx -> {
                },
                "このターン4回以上ダメージを受けている: コスト1で召喚します"));

        // 背水の炎壁: ターン中3回以上ダメージを受けていた場合0コストで出せる。これで出したとき1回復
        specialSummons.put("QTE-M-FIRE-21", new SpecialSummonSpec(
                (state, player, handIndex) -> player.getLeaderDamagedCountThisTurn() >= 3,
                0, TargetSpec.of(), ctx -> {
                },
                ctx -> ctx.actions().healLeader(ctx.room(), ctx.owner(), 1, "QTE-M-FIRE-21"),
                "このターン3回以上ダメージを受けている: 0コストで召喚し、追加で1回復します"));

        // 鳳凰神 ヴォルカニクスレヴォ: このターン、火文明のカードで累計5以上回復したとき0コストで出せる。
        // Ver.0.4 で判定基準が「回復した回数」から「累計回復量」に変わり、
        // さらに発生源が火文明のカードに限定された(発注者確認済み)。
        // 火文明は自傷でLPを削る文明であり、回復の上限20に頭打ちされにくいため、
        // 「実際に回復した量」を数える方式(GameActions.healLeader)と噛み合う
        specialSummons.put("QTE-M-FIRE-22", SpecialSummonSpec.of(
                (state, player, handIndex) -> player.getHealedAmountThisTurn(Civilization.FIRE) >= 5,
                TargetSpec.of(), ctx -> {
                },
                "このターン火文明のカードで累計5以上回復している: 0コストで召喚します"));

        // 覚醒の炎童: 自分のリーダーの体力が10以下のときコスト0にする
        specialSummons.put("QTE-M-FIRE-20", SpecialSummonSpec.of(
                (state, player, handIndex) -> player.getLp() <= 10,
                TargetSpec.of(), ctx -> {
                },
                "体力10以下: 0コストで召喚します"));

        // ---- リーダー起動能力 ----

        // 傷痕の闘帝: 自分のリーダーに1ダメージ。そうしたら1枚ドローする
        leaderAbilities.put("QTE-M-FIRE-15", LeaderAbilitySpec.of(0, TargetSpec.of(), ctx -> {
            ctx.actions().damageLeader(ctx.room(), ctx.owner(), 1, "QTE-M-FIRE-15");
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
        }, "自分のリーダーに1ダメージ、1枚ドロー"));

        // 剛火の将: 自分のライフを2減らす(ダメージ扱い: 発注者確認済み)。
        // このターン中、次に手札から使用する火文明ミニオンのコストを-1する(0にはならない)
        leaderAbilities.put("QTE-M-FIRE-1", LeaderAbilitySpec.of(0, TargetSpec.of(), ctx -> {
            ctx.actions().damageLeader(ctx.room(), ctx.owner(), 2, "QTE-M-FIRE-1");
            ctx.owner().setPendingFireMinionDiscount(1);
            ctx.room().addLog("次に使う火文明ミニオンのコストが1下がる(このターン中)");
        }, "ライフを2減らし、次の火文明ミニオンのコストを-1"));
    }

    /**
     * 自分のリーダーがダメージを受けたときのトリガー(ON_LEADER_DAMAGED)。
     * ミニオンだけでなく装備ウェポンも発動源になりうるため、
     * ミニオン単位のfire()とは別の入口として用意している。
     *
     * @param sourceCardId ダメージの発生源カードID(戦闘ダメージ等ならnull)
     */
    public void fireLeaderDamaged(EffectContext ctx, String sourceCardId) {
        // 火炎の狂信者: 自分のリーダーがダメージを受けるたび、自身の攻撃力+2(永続・累積)
        ctx.owner().getMinionZone().stream()
                .filter(m -> FLAME_FANATIC.equals(m.getMaster().id()))
                .forEach(m -> m.addModifier(new StatModifier(StatModifier.Stat.ATTACK,
                        StatModifier.Operation.ADD, 2, StatModifier.Duration.PERMANENT, FLAME_FANATIC)));

        // 反転の炎鏡: 自分のターン中、このカード以外の「効果で」ダメージを受けたとき
        // 自分のリーダーに1ダメージ、その後1回復。
        // 自己誘発を除外しないと無限ループになるため、発生源が炎鏡自身なら発動しない
        var weapon = ctx.owner().getEquippedWeapon();
        boolean mirrorEquipped = weapon != null && FLAME_MIRROR.equals(weapon.id());
        boolean ownTurn = ctx.owner().getPlayerId().equals(ctx.state().getTurnPlayerId());
        boolean byOtherCard = sourceCardId != null && !FLAME_MIRROR.equals(sourceCardId);
        if (mirrorEquipped && ownTurn && byOtherCard) {
            ctx.room().addLog("【反転の炎鏡】が反応した");
            ctx.actions().damageLeader(ctx.room(), ctx.owner(), 1, FLAME_MIRROR);
            ctx.actions().healLeader(ctx.room(), ctx.owner(), 1, FLAME_MIRROR);
        }
    }


    // ---------------------------------------------------------------
    // 登録: 闇文明(Batch 10bで全面実装)
    //
    // 闇のテーマは「墓地」と「裏向きマナ」を資源として使い潰すこと。
    // 効果の解決中に生じる選択は AutoChoice が自動で決める(Batch 10bの暫定方針)。
    // ---------------------------------------------------------------

    private void registerDarkCards() {

        // ---- リーダー ----

        // 冥府の禁皇: 裏向きのマナ1枚を手札に戻し、2枚引く。
        // 裏向きマナが無ければ使用できないため、状態を変える前に条件で弾く
        leaderAbilities.put("QTE-M-DARK-1", new LeaderAbilitySpec(0, TargetSpec.of(),
                ctx -> {
                    if (ctx.actions().returnFaceDownManaToHand(ctx.room(), ctx.owner())) {
                        ctx.actions().drawCards(ctx.room(), ctx.owner(), 2);
                    }
                },
                (state, player) -> player.getFaceDownManaCount() > 0,
                "裏向きのマナ1枚を手札に戻し、カードを2枚引く"));

        // 黄泉の召喚主(QTE-M-DARK-15)は起動能力ではなく常在能力(サブフェイズの墓地召喚)。
        // ルールそのものを書き換えるため GameService.summonFromGrave が担当する

        // ---- ミニオン ----

        // 執念の暗殺者: 【召喚時】ミニオン1体に3ダメージ。自分のミニオンが破壊されるたび1枚引いてもよい
        targetSpecs.put("QTE-M-DARK-20", TargetSpec.of(
                new Requirement(Kind.MINION, Side.ANY, 1, true, false, List.of(),
                        "3ダメージを与えるミニオンを選んでください(自分のミニオンも選べます)")));
        register("QTE-M-DARK-20", TriggerType.ON_SUMMON, ctx -> ctx.targets().get(0).minions()
                .forEach(t -> ctx.actions().damageMinion(ctx.room(), t.owner(), t.minion(), 3)));
        watchOwnMinionDestroyed("QTE-M-DARK-20", (ctx, destroyedCardId) -> {
            // 「引いてもよい」= 山札が空でなければ引く(AutoChoice)
            if (AutoChoice.shouldDrawOptional(ctx.owner())) {
                ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
                ctx.room().addLog("【執念の暗殺者】が1枚ドロー");
            }
        });

        // ゾンストライカー: 【召喚時】墓地の「ゾンストライカー」を全て出す(ゾーン上限まで)。
        // 効果による「出す」なので【召喚時】は再発動しない(無限ループにならない)
        register("QTE-M-DARK-16", TriggerType.ON_SUMMON, ctx -> {
            while (ctx.owner().getTrash().contains("QTE-M-DARK-16") && !ctx.owner().isMinionZoneFull()) {
                ctx.owner().getTrash().remove("QTE-M-DARK-16");
                ctx.actions().putIntoFieldByEffect(ctx.room(), ctx.owner(), "QTE-M-DARK-16");
            }
        });

        // 腐敗の投擲者: 【召喚時】相手のミニオン1体に1ダメージ
        targetSpecs.put("QTE-M-DARK-17", TargetSpec.of(
                new Requirement(Kind.MINION, Side.OPPONENT, 1, true, false, List.of(),
                        "1ダメージを与える相手のミニオンを選んでください")));
        register("QTE-M-DARK-17", TriggerType.ON_SUMMON, ctx -> ctx.targets().get(0).minions()
                .forEach(t -> ctx.actions().damageMinion(ctx.room(), t.owner(), t.minion(), 1)));

        // 墓場の怨念集合体: 【召喚時】墓地のスペルを1枚手札に加える(攻撃力の加算はStatCalculator)
        targetSpecs.put("QTE-M-DARK-22", TargetSpec.of(
                new Requirement(Kind.TRASH, Side.SELF, 1, true, false, List.of(Filter.SPELL_CARD),
                        "手札に加えるスペルを墓地から選んでください")));
        register("QTE-M-DARK-22", TriggerType.ON_SUMMON, ctx -> ctx.targets().get(0).trashCardIds()
                .forEach(id -> ctx.actions().returnFromTrashToHand(ctx.room(), ctx.owner(), id)));

        // 不滅のネクロマンサー: 自分の他のミニオンが破壊されるたび、裏向きマナ1枚を破壊して
        // そのミニオンを蘇生し【突進】を付与してもよい。
        // 「してもよい」の判断はAutoChoice。マナを無駄にしないよう、蘇生できる見込みを先に確かめる
        watchOwnMinionDestroyed("QTE-M-DARK-5", (ctx, destroyedCardId) -> {
            if (!AutoChoice.shouldRevivePayingMana(ctx.owner())
                    || !ctx.owner().getTrash().contains(destroyedCardId)
                    || ctx.actions().isCheatIntoFieldBlocked(destroyedCardId)) {
                return;
            }
            if (ctx.actions().destroyFaceDownMana(ctx.room(), ctx.owner(), 1) == 0) {
                return;
            }
            if (ctx.actions().reviveFromGrave(ctx.room(), ctx.owner(), destroyedCardId)) {
                List<MinionInstance> zone = ctx.owner().getMinionZone();
                zone.get(zone.size() - 1).grantKeyword(Keyword.RUSH);
                ctx.room().addLog("【不滅のネクロマンサー】が【%s】を蘇生し【突進】を付与"
                        .formatted(cards.findById(destroyedCardId).name()));
            }
        });

        // ボーン・コレクター: このミニオンが戦闘で破壊された時1枚引く(効果破壊では引かない)
        register("QTE-M-DARK-6", TriggerType.ON_DESTROYED_BY_COMBAT,
                ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 1));

        // カース・ボーン: 【召喚時】表向きマナ1枚を裏向きにする。できなければ自身を破壊する
        register("QTE-M-DARK-2", TriggerType.ON_SUMMON, ctx -> {
            if (ctx.actions().turnManaFaceDown(ctx.room(), ctx.owner(), 1) == 0) {
                ctx.room().addLog("表向きのマナが無いため【カース・ボーン】は破壊されます");
                ctx.actions().destroyMinion(ctx.room(), ctx.owner(), ctx.source());
            }
        });

        // 冥界神ハデス: 【召喚時】ハデス以外の全ミニオンを破壊し、その後
        // 裏向きマナの枚数だけ、このターン破壊された味方ミニオンを墓地から出す。
        // 破壊が先・蘇生が後という順序のため、自分が今破壊したミニオンも蘇生候補に入る
        register("QTE-M-DARK-8", TriggerType.ON_SUMMON, ctx -> {
            for (PlayerState side : List.of(ctx.owner(), ctx.opponent())) {
                for (MinionInstance minion : List.copyOf(side.getMinionZone())) {
                    if (!"QTE-M-DARK-8".equals(minion.getMaster().id())) {
                        ctx.actions().destroyMinion(ctx.room(), side, minion);
                    }
                }
            }
            int reviveLimit = ctx.owner().getFaceDownManaCount();
            ctx.room().addLog("【冥界神ハデス】: 裏向きマナ%d枚分まで蘇生します".formatted(reviveLimit));
            int revived = 0;
            // どの体を蘇生するかはAutoChoice(コストの高い順)が決める
            for (String cardId : AutoChoice.reviveOrder(cards, ctx.owner().getMinionsDestroyedThisTurn())) {
                if (revived >= reviveLimit) {
                    break;
                }
                if (ctx.actions().reviveFromGrave(ctx.room(), ctx.owner(), cardId)) {
                    revived++;
                }
            }
        });

        // 裏切りの魔女: 【召喚時】裏向きマナが2枚以上なら、相手のコスト3以下のミニオン1体を破壊
        targetSpecs.put("QTE-M-DARK-4", TargetSpec.of(
                new Requirement(Kind.MINION, Side.OPPONENT, 1, true, false, List.of(Filter.COST_3_OR_LESS),
                        "破壊する相手のコスト3以下のミニオンを選んでください(裏向きマナ2枚以上が必要)")));
        register("QTE-M-DARK-4", TriggerType.ON_SUMMON, ctx -> {
            if (ctx.owner().getFaceDownManaCount() < 2) {
                ctx.room().addLog("裏向きのマナが2枚未満のため【裏切りの魔女】の効果は発動しません");
                return;
            }
            ctx.targets().get(0).minions()
                    .forEach(t -> ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion()));
        });

        // 獄門の裁定者: 【守護】このミニオンがダメージを受けた時、相手のリーダーに2ダメージ
        register("QTE-M-DARK-23", TriggerType.ON_MINION_DAMAGED,
                ctx -> ctx.actions().damageLeader(ctx.room(), ctx.opponent(), 2, "QTE-M-DARK-23"));

        // 這い寄る生霊: 【特殊召喚】自分のターン中に自分のミニオンが破壊されていればコスト0で使用できる。
        // 特殊召喚で出た場合のみ、そのターンの終わりに破壊される
        specialSummons.put("QTE-M-DARK-7", new SpecialSummonSpec(
                (state, player, handIndex) -> player.isOwnMinionDestroyedThisTurn(),
                0,
                TargetSpec.of(),
                ctx -> {
                },
                ctx -> {
                    if (ctx.source() != null) {
                        ctx.source().setDestroyAtEndOfTurn(true);
                    }
                },
                "自分のミニオンが破壊されているため、コスト0で特殊召喚できます(このターンの終わりに破壊されます)"));

        // 生贄を求める邪鬼: 【召喚時】自分の他のミニオン1体を破壊しなければ、このミニオンを破壊する。
        // 選ばない(自壊を選ぶ)こともできる(発注者確認済み)
        targetSpecs.put("QTE-M-DARK-3", TargetSpec.of(
                new Requirement(Kind.MINION, Side.SELF, 1, true, false, List.of(),
                        "生贄にする自分のミニオンを選んでください(選ばない場合このミニオンが破壊されます)")));
        register("QTE-M-DARK-3", TriggerType.ON_SUMMON, ctx -> {
            List<ResolvedTargets.TargetedMinion> sacrifice = ctx.targets().get(0).minions();
            if (sacrifice.isEmpty()) {
                ctx.room().addLog("生贄を選ばなかったため【生贄を求める邪鬼】は破壊されます");
                ctx.actions().destroyMinion(ctx.room(), ctx.owner(), ctx.source());
                return;
            }
            sacrifice.forEach(t -> ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion()));
        });

        // ---- スペル ----

        // マナを貪る怨霊: 表向きのマナ2枚を裏向きにする。3枚引く
        playConditions.put("QTE-M-DARK-11",
                (state, player) -> player.getManaZone().stream().anyMatch(ManaCard::isFaceUp));
        spellEffects.put("QTE-M-DARK-11", ctx -> {
            ctx.actions().turnManaFaceDown(ctx.room(), ctx.owner(), 2);
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 3);
        });

        // 墓穴の呪い: 山札の上から3枚を墓地に置く。墓地の枚数以下のHPを持つミニオンを全て破壊。
        // 枚数の判定は3枚を置いた後に行う(発注者確認済み)。自分のミニオンも巻き込む
        spellEffects.put("QTE-M-DARK-24", ctx -> {
            ctx.actions().mill(ctx.room(), ctx.owner(), 3);
            int threshold = ctx.owner().getTrash().size();
            ctx.room().addLog("【墓穴の呪い】: HP%d以下のミニオンを全て破壊します".formatted(threshold));
            for (PlayerState side : List.of(ctx.owner(), ctx.opponent())) {
                for (MinionInstance minion : List.copyOf(side.getMinionZone())) {
                    if (minion.getCurrentHp() <= threshold) {
                        ctx.actions().destroyMinion(ctx.room(), side, minion);
                    }
                }
            }
        });

        // 冥府への道: 相手のミニオンを1体選び破壊する
        playConditions.put("QTE-M-DARK-26",
                (state, player) -> !state.opponentOf(player.getPlayerId()).getMinionZone().isEmpty());
        targetSpecs.put("QTE-M-DARK-26", TargetSpec.of(
                new Requirement(Kind.MINION, Side.OPPONENT, 1, false, false, List.of(),
                        "破壊する相手のミニオンを選んでください")));
        spellEffects.put("QTE-M-DARK-26", ctx -> ctx.targets().get(0).minions()
                .forEach(t -> ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion())));

        // 悪夢: コスト軽減はStatCalculatorが行う。本体効果はサブフェイズに使ったときのみ。
        // 「このターン、ミニオンの召喚コストを-4」はターン中オーラとして表現する
        spellEffects.put("QTE-M-DARK-27", ctx -> {
            if (ctx.state().getPhase() != TurnPhase.SUB) {
                ctx.room().addLog("【悪夢】はサブフェイズ以外で使用されたため、効果は発動しませんでした");
                return;
            }
            ctx.owner().getThisTurnAuras().add("QTE-M-DARK-27");
            ctx.room().addLog("このターン、%sのミニオンの召喚コストが4下がります"
                    .formatted(ctx.owner().getDisplayName()));
        });

        // 禁忌の代償: 裏向きマナ1枚を破壊する。相手のミニオン1体を破壊する
        playConditions.put("QTE-M-DARK-10", (state, player) -> player.getFaceDownManaCount() > 0
                && !state.opponentOf(player.getPlayerId()).getMinionZone().isEmpty());
        targetSpecs.put("QTE-M-DARK-10", TargetSpec.of(
                new Requirement(Kind.MINION, Side.OPPONENT, 1, false, false, List.of(),
                        "破壊する相手のミニオンを選んでください")));
        spellEffects.put("QTE-M-DARK-10", ctx -> {
            ctx.actions().destroyFaceDownMana(ctx.room(), ctx.owner(), 1);
            ctx.targets().get(0).minions()
                    .forEach(t -> ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion()));
        });

        // 死者蘇生: 好きな数の自分のミニオンを破壊してもよい(その数だけコスト-1)。
        // 墓地からミニオン1体を【突進】付きで蘇生する。
        // 生贄はコスト計算に影響するためGameServiceが支払い前に数を読む
        playConditions.put("QTE-M-DARK-12", (state, player) -> !player.getMinionZone().isEmpty()
                || player.getTrash().stream().anyMatch(id -> cards.findById(id).type() == CardType.MINION));
        targetSpecs.put("QTE-M-DARK-12", TargetSpec.of(
                // 上限は「今の自分のゾーン上限」ではなく到達しうる天井(8体)にする。
                // TargetSpec は起動時に静的に組み立てるためプレイヤーごとの値を持てず、
                // 6のままだと大地の巨頭のプレイヤーが8体並べたときに生贄を選び切れなくなる
                // (禁忌デッキ経由で土のリーダーが闇のスペルを使う組み合わせが存在する)
                Requirement.upTo(Kind.MINION, Side.SELF, PlayerState.MAX_MINION_ZONE_LIMIT,
                        "生贄にする自分のミニオンを選んでください(1体につきコスト-1)")));
        spellEffects.put("QTE-M-DARK-12", ctx -> {
            ctx.targets().get(0).minions()
                    .forEach(t -> ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion()));
            List<String> candidates = ctx.owner().getTrash().stream()
                    .filter(id -> cards.findById(id).type() == CardType.MINION)
                    .toList();
            // 蘇生する1体はAutoChoice(コストの高い順)が決める
            for (String cardId : AutoChoice.reviveOrder(cards, candidates)) {
                if (ctx.actions().reviveFromGrave(ctx.room(), ctx.owner(), cardId)) {
                    List<MinionInstance> zone = ctx.owner().getMinionZone();
                    zone.get(zone.size() - 1).grantKeyword(Keyword.RUSH);
                    ctx.room().addLog("【死者蘇生】が【%s】を蘇生し【突進】を付与"
                            .formatted(cards.findById(cardId).name()));
                    break;
                }
            }
        });

        // 絶望の連鎖: 自分のミニオン1体を破壊する。相手のミニオン1体を破壊する
        playConditions.put("QTE-M-DARK-9", (state, player) -> !player.getMinionZone().isEmpty());
        targetSpecs.put("QTE-M-DARK-9", TargetSpec.of(
                new Requirement(Kind.MINION, Side.SELF, 1, false, false, List.of(),
                        "破壊する自分のミニオンを選んでください"),
                new Requirement(Kind.MINION, Side.OPPONENT, 1, true, false, List.of(),
                        "破壊する相手のミニオンを選んでください")));
        spellEffects.put("QTE-M-DARK-9", ctx -> {
            ctx.targets().get(0).minions()
                    .forEach(t -> ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion()));
            ctx.targets().get(1).minions()
                    .forEach(t -> ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion()));
        });

        // 禁忌の墓地利用: 墓地のスペルを2枚選び、マナゾーンに裏向きで置く。
        // 墓地に1枚しかなければ1枚だけ置く(発注者確認済み)ため upTo で表現する
        playConditions.put("QTE-M-DARK-25", (state, player) -> player.getManaZone().size() < PlayerState.MAX_MANA
                && player.getTrash().stream().anyMatch(id -> cards.findById(id).type() == CardType.SPELL));
        targetSpecs.put("QTE-M-DARK-25", TargetSpec.of(
                Requirement.upTo(Kind.TRASH, Side.SELF, 2,
                        "マナに置くスペルを墓地から2枚まで選んでください", Filter.SPELL_CARD)));
        spellEffects.put("QTE-M-DARK-25", ctx -> ctx.targets().get(0).trashCardIds()
                .forEach(id -> ctx.actions().putTrashCardIntoManaFaceDown(ctx.room(), ctx.owner(), id)));

        // ---- ウェポン ----

        // 禁忌の冥魔剣(QTE-M-DARK-14): 自分のミニオンが場に出たとき、相手のリーダーに1ダメージ。
        // Ver.0.4 で旧効果(リーダー攻撃時に裏向きマナを表に戻して1ダメージ)を全面置換した。
        // 「場に出たとき」であって【召喚時】ではないため、蘇生や効果による「出す」でも発動する
        register("QTE-M-DARK-14", TriggerType.ON_ALLY_MINION_ENTER,
                ctx -> ctx.actions().damageLeader(ctx.room(), ctx.opponent(), 1, "QTE-M-DARK-14"));

        // 死神の大鎌(QTE-M-DARK-13)・死霊の収鎌(QTE-M-DARK-28)は「リーダーが攻撃した時」の効果であり、
        // GameService.leaderAttack内で解決する
    }

    // ---------------------------------------------------------------
    // 登録: 光文明(Batch 11bで全面実装)
    //
    // 光のテーマは「相手のリソースの流れを止める」こと。攻撃・破壊・ダメージ・ドロー・使用・
    // フェイズ進行の判定点(RuleGuards)はBatch 11aで用意済みのため、ここで書くのは
    // 主に効果の「発火側」である(11a側の受け皿は各カードのnotesと引き継ぎ書を参照)。
    // ---------------------------------------------------------------

    private void registerLightCards() {

        // ---- リーダー ----

        // 断罪の聖導者: コスト4を支払う。次の相手のターン、相手はスペルを唱えられません。1枚引く。
        // 禁忌デッキ由来のスペルも封じられる(GameService.playCard/playTabooCardの両方でspellDenialを見る)
        leaderAbilities.put("QTE-M-LIGHT-15", LeaderAbilitySpec.of(4, TargetSpec.of(), ctx -> {
            ctx.opponent().setSpellSealedOnTurn(ctx.state().getTurnNumber() + 1);
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
            ctx.room().addLog("次の%sのターン、スペルを唱えられません".formatted(ctx.opponent().getDisplayName()));
        }, "コスト4を支払う: 次の相手のターン、相手はスペルを唱えられません。カードを1枚引く"));

        // 聖光の守護聖: コスト2を支払う。次の相手のターン終了時まで、自分は相手の効果による
        // 破壊を受けない(戦闘破壊・自分自身の効果による破壊は防げない)。自分のターンをまたぐ持続効果
        leaderAbilities.put("QTE-M-LIGHT-1", LeaderAbilitySpec.of(2, TargetSpec.of(), ctx -> {
            int expireTurn = ctx.state().getTurnNumber() + 1;
            ctx.owner().getPersistentAuras().add(PersistentAura.untilEndOfTurn("QTE-M-LIGHT-1", expireTurn));
            ctx.room().addLog("次の相手のターン終了時まで、%sは相手の効果で破壊されなくなりました"
                    .formatted(ctx.owner().getDisplayName()));
        }, "コスト2を支払う: 次の相手のターン終了時まで、自分は相手の効果による破壊を受けません"));

        // ---- ミニオン ----

        // 聖域の案内人: 【知識】自分の場に【守護】を持つミニオンがいるなら、もう一度【知識】を行う。
        // 1回目のドローはfire()が自動処理する(自身がKNOWLEDGEを持つため)。ここでは2回目だけを扱う。
        // 守護の有無は「登場時」(ON_ENTER)に判定し、召喚か効果で出したかを問わない(発注者確認済み)
        register("QTE-M-LIGHT-3", TriggerType.ON_ENTER, ctx -> {
            boolean hasGuard = ctx.owner().getMinionZone().stream().anyMatch(m -> m.hasKeyword(Keyword.GUARD));
            if (hasGuard) {
                ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
                ctx.room().addLog("【聖域の案内人】: 【守護】がいるためもう一度【知識】");
            }
        });

        // 光の召喚士: 【召喚時】の表記が無いためON_ENTER型として扱う(発注者確認済み)。
        // 自分の手札からコスト3以下のミニオンを1体、コストを支払わず場に出す。
        // 効果で「出す」のでON_SUMMONは発動しない
        targetSpecs.put("QTE-M-LIGHT-21", TargetSpec.of(Requirement.filtered(
                Kind.HAND, Side.SELF, 1, true, "場に出すコスト3以下のミニオンを選んでください(いなければ確定)",
                Filter.MINION_CARD, Filter.COST_3_OR_LESS)));
        register("QTE-M-LIGHT-21", TriggerType.ON_ENTER, ctx -> {
            var selection = ctx.targets().get(0);
            if (selection.isEmpty()) {
                return;
            }
            selection.handCardIds().forEach(id -> ctx.actions().putIntoFieldByEffect(ctx.room(), ctx.owner(), id));
        });

        // 降臨の伝道師: 【召喚時】山札の上から4枚を公開。【守護】ミニオンを1体場に出し
        // (0体なら不発・1体なら自動決定・2体以上ならプレイヤーが選ぶ)、残りは山札の下へ。
        // 出したミニオンはその後3ダメージを受ける
        register("QTE-M-LIGHT-22", TriggerType.ON_SUMMON, ctx -> {
            List<String> revealed = ctx.actions().revealFromTopOfDeck(ctx.room(), ctx.owner(), 4);
            List<Integer> guardIndexes = new ArrayList<>();
            for (int i = 0; i < revealed.size(); i++) {
                if (cards.findById(revealed.get(i)).hasKeyword(Keyword.GUARD)) {
                    guardIndexes.add(i);
                }
            }
            if (guardIndexes.isEmpty()) {
                ctx.actions().returnToBottomOfDeck(ctx.owner(), revealed);
                ctx.room().addLog("公開した4枚に【守護】ミニオンがいなかったため、山札の下に置かれました");
                return;
            }
            if (guardIndexes.size() == 1) {
                resolveMissionaryChoice(ctx, revealed, guardIndexes.get(0));
                return;
            }
            // 【守護】が複数: プレイヤーの選択を待つ(a9の割り込み選択に載せている)。
            // 公開されたカードの置き場(revealedZone)と、問い合わせ(pendingChoice)は別物である。
            // 選べるのは【守護】を持つものだけなので、候補には守護の位置だけを入れる
            ctx.owner().getRevealedZone().addAll(revealed);
            ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.one(
                    PendingChoice.Kind.REVEALED,
                    guardIndexes.stream().map(String::valueOf).toList(),
                    ResumePoint.MISSIONARY_SUMMON,
                    "【降臨の伝道師】: 公開した%d枚から場に出す【守護】ミニオンを選んでください"
                            .formatted(revealed.size())));
        });

        // ---- スペル ----

        // 運命のリセット: 両者が手札を全てデッキに戻しシャッフルし、同じ枚数を引き直す
        spellEffects.put("QTE-M-LIGHT-27", ctx -> {
            int ownerCount = ctx.owner().getHand().size();
            int opponentCount = ctx.opponent().getHand().size();
            reshuffleHandIntoDeck(ctx.owner());
            reshuffleHandIntoDeck(ctx.opponent());
            ctx.room().addLog("%sと%sが手札をシャッフルして山札に戻しました"
                    .formatted(ctx.owner().getDisplayName(), ctx.opponent().getDisplayName()));
            ctx.actions().drawCards(ctx.room(), ctx.owner(), ownerCount);
            ctx.actions().drawCards(ctx.room(), ctx.opponent(), opponentCount);
        });

        // ホーリー・シグナル: 相手の場で最も攻撃力の高いミニオン1体を破壊。
        // 対象はプレイヤーが選ばず盤面から自動決定する除去で、タイのときだけ実質選択になる。
        // 【潜伏】持ちであっても破壊できる(発注者確認済み。IGNORES_STEALTHで潜伏の対象化禁止を上書き)
        playConditions.put("QTE-M-LIGHT-10",
                (state, player) -> !state.opponentOf(player.getPlayerId()).getMinionZone().isEmpty());
        targetSpecs.put("QTE-M-LIGHT-10", TargetSpec.of(new Requirement(
                Kind.MINION, Side.OPPONENT, 1, false, false,
                List.of(Filter.HIGHEST_ATTACK_OPPONENT, Filter.IGNORES_STEALTH),
                "相手の場で最も攻撃力の高いミニオンを選んでください")));
        spellEffects.put("QTE-M-LIGHT-10", ctx -> ctx.targets().get(0).minions()
                .forEach(t -> ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion())));

        // 聖光の武装解除: ウェポンを1枚破壊する。【還元】。自分のウェポンも選べ、
        // 誰も装備していなければ空撃ちになる(発注者確認済み)
        targetSpecs.put("QTE-M-LIGHT-26", TargetSpec.of(
                Requirement.upTo(Kind.WEAPON, Side.ANY, 1, "破壊するウェポンを選んでください(いなければ確定)")));
        spellEffects.put("QTE-M-LIGHT-26", ctx -> ctx.targets().get(0).weapons()
                .forEach(owner -> ctx.actions().destroyOwnWeapon(ctx.room(), owner)));

        // 神の福音: 手札から光文明の【守護】ミニオンを最大2体、コストを支払わず場に出す。
        // 出した数だけ引く。ゾーンの空きが足りなければ出せた数だけ出し、その数だけ引く(発注者確認済み)
        targetSpecs.put("QTE-M-LIGHT-12", TargetSpec.of(Requirement.upTo(Kind.HAND, Side.SELF, 2,
                "コストを支払わず場に出す光文明の【守護】ミニオンを2体まで選んでください",
                Filter.LIGHT_CIVILIZATION, Filter.GUARD)));
        spellEffects.put("QTE-M-LIGHT-12", ctx -> {
            int summoned = 0;
            for (String id : ctx.targets().get(0).handCardIds()) {
                if (ctx.owner().isMinionZoneFull()) {
                    ctx.owner().getHand().add(id); // 出せなかった分は手札に戻す
                    continue;
                }
                ctx.actions().putIntoFieldByEffect(ctx.room(), ctx.owner(), id);
                summoned++;
            }
            if (summoned > 0) {
                ctx.actions().drawCards(ctx.room(), ctx.owner(), summoned);
            }
        });

        // 聖なる降誕の儀式: 手札のコスト7以下の【守護】ミニオン1体を、コストを支払わず場に出す
        playConditions.put("QTE-M-LIGHT-11", (state, player) -> player.getHand().stream().anyMatch(id -> {
            var m = cards.findById(id);
            return m.hasKeyword(Keyword.GUARD) && m.cost() != null && m.cost() <= 7;
        }));
        targetSpecs.put("QTE-M-LIGHT-11", TargetSpec.of(Requirement.filtered(
                Kind.HAND, Side.SELF, 1, false, "コストを支払わず場に出すコスト7以下の【守護】ミニオンを選んでください",
                Filter.GUARD, Filter.COST_7_OR_LESS)));
        spellEffects.put("QTE-M-LIGHT-11", ctx -> ctx.targets().get(0).handCardIds()
                .forEach(id -> ctx.actions().putIntoFieldByEffect(ctx.room(), ctx.owner(), id)));

        // 光の戒め: 相手のミニオン1体を次のターン攻撃できなくする(氷結の杖と同じ仕組み)。1枚引く
        playConditions.put("QTE-M-LIGHT-9",
                (state, player) -> !state.opponentOf(player.getPlayerId()).getMinionZone().isEmpty());
        targetSpecs.put("QTE-M-LIGHT-9", TargetSpec.of(Requirement.of(
                Kind.MINION, Side.OPPONENT, 1, false, "凍結させる相手のミニオンを選んでください")));
        spellEffects.put("QTE-M-LIGHT-9", ctx -> {
            ctx.targets().get(0).minions().forEach(t -> {
                int nextTurn = ctx.state().getTurnNumber() + 1;
                t.minion().setCannotAttackOnTurn(nextTurn);
                ctx.room().addLog("【%s】は凍結しました(次のターン攻撃不可)"
                        .formatted(t.minion().getMaster().name()));
            });
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
        });

        // ---- ウェポン ----
        // 聖剣エクスカリバー(QTE-M-LIGHT-14)は「リーダーが攻撃した時」の効果であり、
        // 既存の6件と同じくGameService.leaderAttack内のswitchで解決する(11bでは移設しない)
    }

    // ---------------------------------------------------------------
    // 登録: 風文明(Batch 12b。土台のa1〜a9はBatch 12aで実装済み)
    // ---------------------------------------------------------------

    private void registerWindCards() {

        // ---- 既存機構のみ(キーワードだけで完結するカードは登録不要) ----
        // ウィンド・ペティ(0010)・疾風の先陣(0016)・スカイ・スワロー(0129)は
        // 印刷キーワードのみで完結するため、このメソッドには登場しない。
        // 結集する風の精(0124)・疾風のレイピア(0130)・サイクロン・フェンサー(0133)・
        // 詠唱の風詠士(L010)はStatCalculatorの動的評価のみで完結するため、
        // ここにも登場しない(効果テーブルへの登録が不要な理由は各設計解説を参照)。
        // 風護の杖(0123)は攻撃時効果をGameService.leaderAttackに置いている(switchには足さない)ため、
        // ここでの登録は resolveChoice の GUARD_STAFF_TARGET 分岐のみである。

        // ---- リーダー ----

        // 疾風の導き手: コスト1を支払う。このターン中に自分が使用したカードの枚数が3枚以上なら、
        // 自分のミニオン1体の攻撃力を+2(永続。「このターン中」の指定はカウント条件のみに掛かる)
        leaderAbilities.put("QTE-M-WIND-1", new LeaderAbilitySpec(1,
                TargetSpec.of(new Requirement(Kind.MINION, Side.SELF, 1, false, false, List.of(),
                        "攻撃力を+2するミニオンを選んでください")),
                ctx -> ctx.targets().get(0).minions().forEach(t -> t.minion().addModifier(
                        new StatModifier(StatModifier.Stat.ATTACK, StatModifier.Operation.ADD, 2,
                                StatModifier.Duration.PERMANENT, "QTE-M-WIND-1"))),
                (state, player) -> player.getCardsUsedThisTurn() >= 3,
                "コスト1を支払う: このターン3枚以上カードを使用していれば、自分のミニオン1体の攻撃力を+2"));

        // ---- ミニオン ----

        // ガイル・フォックス: 【召喚時】このターン中にカードを2枚以上使用しているなら【潜伏】。
        // 使用カウンタは解決中に読むため自身を含まない(裁定1)
        register("QTE-M-WIND-6", TriggerType.ON_SUMMON, ctx -> {
            if (ctx.owner().getCardsUsedThisTurn() >= 2 && ctx.source() != null) {
                ctx.source().grantKeyword(Keyword.STEALTH);
                ctx.room().addLog("【ガイル・フォックス】は【潜伏】を得ました");
            }
        });

        // 風神ヴァーユ: 【特殊召喚】自分の墓地に【守護】を持つカードが4枚以上のとき、
        // このカードを手札から1コストで出せる(代替コストなし。条件のみ)
        specialSummons.put("QTE-M-WIND-21", new SpecialSummonSpec(
                (state, player, handIndex) -> player.getTrash().stream()
                        .filter(id -> cards.findById(id).hasKeyword(Keyword.GUARD)).count() >= 4,
                1,
                TargetSpec.of(),
                ctx -> {
                },
                ctx -> {
                },
                "自分の墓地に【守護】を持つカードが4枚以上: コスト1で召喚します"));

        // 嵐の守り手: 【特殊召喚】自分の場に体力3以上のミニオンが3体以上のとき、
        // このカードを手札から0コストで出せる
        specialSummons.put("QTE-M-WIND-19", SpecialSummonSpec.of(
                (state, player, handIndex) -> player.getMinionZone().stream()
                        .filter(m -> m.getCurrentHp() >= 3).count() >= 3,
                TargetSpec.of(),
                ctx -> {
                },
                "自分の場に体力3以上のミニオンが3体以上: コスト0で召喚します"));

        // ストーム・カイザー: 【特殊召喚】このターン中に自分がカードを4枚以上使用している時、
        // コストを支払わずに場に出せる
        specialSummons.put("QTE-M-WIND-8", SpecialSummonSpec.of(
                (state, player, handIndex) -> player.getCardsUsedThisTurn() >= 4,
                TargetSpec.of(),
                ctx -> {
                },
                "このターン中に自分がカードを4枚以上使用: コストを支払わず召喚します"));

        // 嵐の呼び手: 【召喚時】このターン中にカードを3枚以上使用しているなら、
        // 相手のミニオン1体を持ち主の手札に戻す(a1のカウンタのみで完結。対象は自動選択)
        register("QTE-M-WIND-4", TriggerType.ON_SUMMON, ctx -> {
            if (ctx.owner().getCardsUsedThisTurn() < 3) {
                return;
            }
            List<MinionInstance> opponentMinions = ctx.opponent().getMinionZone();
            if (opponentMinions.isEmpty()) {
                return;
            }
            MinionInstance target = AutoChoice.highestPrintedAttack(opponentMinions);
            ctx.actions().bounceToHand(ctx.room(), ctx.opponent(), target);
            ctx.room().addLog("【嵐の呼び手】: 【%s】を持ち主の手札に戻しました".formatted(target.getMaster().name()));
        });

        // 詠唱の疾風騎士: ターンエンド時、このターン5回以上スペルを撃っていたら
        // 墓地にあるスペルを2枚まで手札に戻す(候補が2枚以下なら選ぶ余地がないため自動で全て回収し、
        // 3枚以上ならa9でプレイヤーに選ばせる)。コスト軽減はStatCalculator.effectiveCostが担う
        register("QTE-M-WIND-18", TriggerType.ON_TURN_END, ctx -> {
            if (ctx.owner().getSpellsCastThisTurn() < 5) {
                return;
            }
            List<String> trash = ctx.owner().getTrash();
            List<Integer> spellPositions = new ArrayList<>();
            for (int i = 0; i < trash.size(); i++) {
                if (cards.findById(trash.get(i)).type() == CardType.SPELL) {
                    spellPositions.add(i);
                }
            }
            if (spellPositions.isEmpty()) {
                return;
            }
            if (spellPositions.size() <= 2) {
                List<String> recovered = spellPositions.stream().map(trash::get).toList();
                for (int i = spellPositions.size() - 1; i >= 0; i--) {
                    trash.remove((int) spellPositions.get(i));
                }
                recovered.forEach(id -> {
                    ctx.owner().getHand().add(id);
                    ctx.room().addLog("【詠唱の疾風騎士】: 【%s】を墓地から手札に戻しました"
                            .formatted(cards.findById(id).name()));
                });
                return;
            }
            ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.upTo(
                    PendingChoice.Kind.TRASH,
                    spellPositions.stream().map(String::valueOf).toList(),
                    2, ResumePoint.GALE_KNIGHT_RECOVER,
                    "【詠唱の疾風騎士】: 墓地から手札に戻すスペルを2枚まで選んでください"));
        });

        // 突風のまとめ役: 自分が他のカードを使用するたび、ターン終了時まで自分のミニオンすべての
        // 攻撃力を+1(a1のON_CARD_USEDイベントで配る。期限管理は既存の修正スタックが担う)。
        // ※既知の限界: このカード自身の召喚イベントも「使用」としてこのハンドラを1回だけ通るため、
        // 召喚直後の自分自身にも+1が乗る(カード文言の「他の」を厳密に外すには、fireCardUsedが
        // 参照する場のスナップショットを「この使用が始まる前」に取る改修が要る。b系の範囲を超えるため
        // 本バッチでは着手せず、design-notesに明記して発注者に確認する)
        register("QTE-M-WIND-7", TriggerType.ON_CARD_USED, ctx -> {
            for (MinionInstance m : ctx.owner().getMinionZone()) {
                m.addModifier(new StatModifier(StatModifier.Stat.ATTACK, StatModifier.Operation.ADD, 1,
                        StatModifier.Duration.THIS_TURN, "QTE-M-WIND-7"));
            }
            ctx.room().addLog("【突風のまとめ役】: 自分のミニオンすべての攻撃力が+1されました(このターン中)");
        });

        // 静空の風使い: このカードをタップすることで自分のマナを1アンタップ状態にする
        minionAbilities.put("QTE-M-WIND-17", MinionAbilitySpec.of(0, TargetSpec.of(),
                ctx -> ctx.actions().untapMana(ctx.room(), ctx.owner(), 1),
                "タップして、自分のマナを1枚アンタップします"));

        // ---- スペル ----

        // 神風の大号令: このターン中に自分が使用したカードの枚数と同じだけ、
        // 自分のミニオンすべての攻撃力を+1(このターン限定)。解決時点でのスナップショットであり、
        // 自身は含まない(裁定1)。0の場合(このターン最初のカードだった場合)は何も起きない
        spellEffects.put("QTE-M-WIND-12", ctx -> {
            int amount = ctx.owner().getCardsUsedThisTurn();
            if (amount <= 0) {
                ctx.room().addLog("【神風の大号令】: このターンまだ他のカードを使用していないため、効果はありません");
                return;
            }
            for (MinionInstance m : ctx.owner().getMinionZone()) {
                m.addModifier(new StatModifier(StatModifier.Stat.ATTACK, StatModifier.Operation.ADD, amount,
                        StatModifier.Duration.THIS_TURN, "QTE-M-WIND-12"));
            }
            ctx.room().addLog("【神風の大号令】: 自分のミニオンすべての攻撃力が+%dされました".formatted(amount));
        });

        // 回帰の風穴: ミニオンを1体手札に戻す(対象は自分・相手の両方: 記法規約どおり)。
        // コスト+1してもよい。そうした場合もう一度墓地から唱え(2体目のバウンス対象を選ばせる)、
        // その後山札の一番下に置く(a5の強化使用 + a9の割り込み)
        targetSpecs.put("QTE-M-WIND-26", TargetSpec.of(new Requirement(Kind.MINION, Side.ANY, 1, false, false,
                List.of(), "手札に戻すミニオンを選んでください")));
        enhancedCosts.put("QTE-M-WIND-26", new EnhancedCostSpec(1,
                "コストを+1して、このカードをもう一度墓地から唱え、その後山札の一番下に置きますか？"));
        spellEffects.put("QTE-M-WIND-26", ctx -> {
            ctx.targets().get(0).minions().forEach(
                    t -> ctx.actions().bounceToHand(ctx.room(), t.owner(), t.minion()));
            if (!ctx.enhanced()) {
                return;
            }
            ctx.owner().setPendingSpellDisposition(SpellDisposition.TO_DECK_BOTTOM);
            resolveWindholeSecondTargets(ctx);
        });

        // ツイン・ストライク: このターン中、自分のミニオン1体に「1ターンに2回攻撃できる」を付与
        targetSpecs.put("QTE-M-WIND-11", TargetSpec.of(new Requirement(Kind.MINION, Side.SELF, 1, false, false,
                List.of(), "「1ターンに2回攻撃できる」を付与するミニオンを選んでください")));
        spellEffects.put("QTE-M-WIND-11", ctx -> ctx.targets().get(0).minions().forEach(
                t -> t.minion().addModifier(new StatModifier(StatModifier.Stat.EXTRA_ATTACKS,
                        StatModifier.Operation.ADD, 1, StatModifier.Duration.THIS_TURN, "QTE-M-WIND-11"))));

        // 風弾の跳弾: 自分のミニオンを1体手札に戻す。そうしたら相手のミニオン1体に2ダメージ。
        // コストを+3してもよい。そうした場合、墓地に置く代わりに手札に戻す(使い回せる)
        targetSpecs.put("QTE-M-WIND-24", TargetSpec.of(
                new Requirement(Kind.MINION, Side.SELF, 1, false, false, List.of(), "手札に戻す自分のミニオンを選んでください"),
                new Requirement(Kind.MINION, Side.OPPONENT, 1, false, false, List.of(), "2ダメージを与える相手のミニオンを選んでください")));
        enhancedCosts.put("QTE-M-WIND-24", new EnhancedCostSpec(3,
                "コストを+3して、このカードを墓地に置く代わりに手札に戻しますか？"));
        spellEffects.put("QTE-M-WIND-24", ctx -> {
            ctx.targets().get(0).minions().forEach(
                    t -> ctx.actions().bounceToHand(ctx.room(), t.owner(), t.minion()));
            ctx.targets().get(1).minions().forEach(
                    t -> ctx.actions().damageMinion(ctx.room(), t.owner(), t.minion(), 2));
            if (ctx.enhanced()) {
                ctx.owner().setPendingSpellDisposition(SpellDisposition.TO_HAND);
            }
        });

        // 突風の祝福: 自分のミニオン1体の体力を+2する。【還元】(還元の処理は共通)
        targetSpecs.put("QTE-M-WIND-27", TargetSpec.of(new Requirement(Kind.MINION, Side.SELF, 1, false, false,
                List.of(), "体力を+2するミニオンを選んでください")));
        spellEffects.put("QTE-M-WIND-27", ctx -> ctx.targets().get(0).minions().forEach(
                t -> t.minion().addModifier(new StatModifier(StatModifier.Stat.HP,
                        StatModifier.Operation.ADD, 2, StatModifier.Duration.PERMANENT, "QTE-M-WIND-27"))));

        // そよ風の加護: 自分のミニオン1体の体力を+1し、【守護】を付与
        targetSpecs.put("QTE-M-WIND-10", TargetSpec.of(new Requirement(Kind.MINION, Side.SELF, 1, false, false,
                List.of(), "体力を+1し守護を与えるミニオンを選んでください")));
        spellEffects.put("QTE-M-WIND-10", ctx -> ctx.targets().get(0).minions().forEach(t -> {
            t.minion().addModifier(new StatModifier(StatModifier.Stat.HP,
                    StatModifier.Operation.ADD, 1, StatModifier.Duration.PERMANENT, "QTE-M-WIND-10"));
            t.minion().grantKeyword(Keyword.GUARD);
        }));

        // 選択の追い風: カードを1枚引く。その後守護を持つカードを1枚捨てても良い。
        // そうしたら追加でカードを1枚引く(候補が無ければ問い合わせ自体を出さない)
        spellEffects.put("QTE-M-WIND-25", ctx -> {
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
            List<String> guardPositions = new ArrayList<>();
            List<String> hand = ctx.owner().getHand();
            for (int i = 0; i < hand.size(); i++) {
                if (cards.findById(hand.get(i)).hasKeyword(Keyword.GUARD)) {
                    guardPositions.add(String.valueOf(i));
                }
            }
            if (guardPositions.isEmpty()) {
                return;
            }
            ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.upTo(
                    PendingChoice.Kind.HAND, guardPositions, 1, ResumePoint.TAILWIND_DISCARD,
                    "【選択の追い風】: 守護を持つカードを1枚捨てて、もう1枚引きますか？(任意)"));
        });

        // 風のマナ変換: 自分の表向きのマナを1枚手札に戻す。その後自分の手札から1枚を裏向きでマナに置く
        spellEffects.put("QTE-M-WIND-23", ctx -> {
            boolean returned = ctx.actions().returnFaceUpManaToHand(ctx.room(), ctx.owner());
            if (!returned) {
                ctx.room().addLog("【風のマナ変換】: 表向きのマナが無いため、何も起こりませんでした");
                return;
            }
            if (ctx.owner().getHand().isEmpty()) {
                return;
            }
            List<String> handPositions = new ArrayList<>();
            for (int i = 0; i < ctx.owner().getHand().size(); i++) {
                handPositions.add(String.valueOf(i));
            }
            ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.one(
                    PendingChoice.Kind.HAND, handPositions, ResumePoint.MANA_CONVERT_PUT,
                    "【風のマナ変換】: 裏向きでマナに置く手札を選んでください"));
        });

        // サイクロン・リフレッシュ: 場か手札のカードを合計2枚デッキに戻してシャッフルする。
        // その後カードを2枚引く(a7: Kindを増やさず合計指定で解く。TargetSpecの本家サンプル)
        targetSpecs.put("QTE-M-WIND-22", TargetSpec.combined(2,
                Requirement.upTo(Kind.HAND, Side.SELF, 2, "デッキに戻す手札を選んでください(合計2枚まで)"),
                Requirement.upTo(Kind.MINION, Side.SELF, 2, "デッキに戻すミニオンを選んでください(合計2枚まで)")));
        spellEffects.put("QTE-M-WIND-22", ctx -> {
            List<String> toDeck = new ArrayList<>(ctx.targets().get(0).handCardIds());
            for (ResolvedTargets.TargetedMinion t : ctx.targets().get(1).minions()) {
                t.owner().getMinionZone().remove(t.minion());
                if (t.minion().isFromTaboo()) {
                    t.owner().getLostZone().add(t.minion().getMaster().id());
                    ctx.room().addLog("【%s】は禁忌カードのため消滅しました".formatted(t.minion().getMaster().name()));
                } else {
                    toDeck.add(t.minion().getMaster().id());
                }
            }
            if (!toDeck.isEmpty()) {
                ctx.actions().returnToDeckAndShuffle(ctx.room(), ctx.owner(), toDeck);
            }
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 2);
        });

        // 追い風: 自分のミニオン1体の攻撃力を+1。カードを1枚引く
        targetSpecs.put("QTE-M-WIND-9", TargetSpec.of(new Requirement(Kind.MINION, Side.SELF, 1, false, false,
                List.of(), "攻撃力を+1するミニオンを選んでください")));
        spellEffects.put("QTE-M-WIND-9", ctx -> {
            ctx.targets().get(0).minions().forEach(t -> t.minion().addModifier(
                    new StatModifier(StatModifier.Stat.ATTACK, StatModifier.Operation.ADD, 1,
                            StatModifier.Duration.PERMANENT, "QTE-M-WIND-9")));
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
        });

        // ---- ウェポン ----

        // 暴風の双剣: 自分がカードを使用するたび、このターンの間、このウェポンの攻撃力を+1
        // (a1のON_CARD_USEDイベント。0134と同じ既知の限界を持つ: 装備直後の自分自身の使用でも1回乗る)
        register("QTE-M-WIND-13", TriggerType.ON_CARD_USED, ctx ->
                ctx.owner().setWeaponAttackBonusThisTurn(ctx.owner().getWeaponAttackBonusThisTurn() + 1));

        registerWindVer11Cards();
    }

    // ---------------------------------------------------------------
    // ★Batch 48: Ver1.1 で追加された風文明のカード(P2 の1本目)。
    //
    // 風文明の新しいテーマは「自分のミニオンを能動的に失うこと」である ——
    // ハク霊・コク霊は毎ターン自壊して相方を呼び、2種のオニは自分の場を薙ぎ払って
    // その数を火力に変え、シュテンはその結果として積み上がった破壊数で降りてくる。
    // 既存の風(カードの使用回数を数える)とは別系統の資源であり、
    // 数える対象も「使用したカードの枚数」ではなく「破壊されたミニオンの数」になる。
    //
    // ★リーダーのストク(QTE-M-WIND-29)はここに登録を持たない。
    //   常在の破壊誘発は fireOwnMinionDestroyed に直接書いてある(豊穣の地霊主と同じ形)。
    // ★透キ通ル・アヤカシのコスト0化は StatCalculator、
    //   ハク霊・コク霊の「攻撃できない」は RuleGuards にある。
    //   1枚のカードの実装が複数のクラスに分かれるのは想定内である(裁定180)。
    // ---------------------------------------------------------------
    private void registerWindVer11Cards() {

        // ---- 透キ通ル・アヤカシ(QTE-M-WIND-33) ----
        // 「自分の場にコスト2以上のミニオンが場に居るときこのカードのコストを0にする。
        //   ターンの終わりこのカードは破壊される【突進】」
        //
        // 自壊の予約には既存の destroyAtEndOfTurn を使う。這い寄る生霊は
        // 「特殊召喚で出したときだけ」立てるが、こちらはカードの性質そのものなので無条件である。
        // ON_SUMMON ではなく ON_ENTER なのは、蘇生や効果で場に出た場合も同じく
        // ターンの終わりに消えるべきだからである(テキストが召喚を条件にしていない)
        register("QTE-M-WIND-33", TriggerType.ON_ENTER, ctx -> {
            if (ctx.source() == null) {
                return;
            }
            ctx.source().setDestroyAtEndOfTurn(true);
            ctx.room().addLog("【透キ通ル・アヤカシ】はターンの終わりに破壊されます");
        });

        // ---- ハク霊(QTE-M-WIND-34) / コク霊(QTE-M-WIND-35) ----
        // 「【常在】ターンのはじめにこれを破壊する。これは攻撃できない
        //   【破壊時】(効果)、墓地から『相方』を出す」
        //
        // ★「ターンのはじめ」は自分のターンのはじめだけである(マスター裁定183)。
        //   発火側の非対称は TriggerType.ON_TURN_START に書いた。
        // ★墓地から出すのは「召喚」ではなく効果による「出す」なので ON_ENTER のみが発動する
        //   (reviveFromGrave → putIntoFieldByEffect)。相方が墓地に無い・場が満杯なら不発である。
        // ★2枚が同時に場に居ても無限には増えない。ターン開始の反復は場のコピーを回すため、
        //   その開始時に出てきたミニオンはその開始時には処理されない(GameService.beginTurn)。
        register("QTE-M-WIND-34", TriggerType.ON_TURN_START, ctx ->
                selfDestructAtTurnStart(ctx, "ハク霊"));
        register("QTE-M-WIND-34", TriggerType.ON_DESTROYED, ctx -> {
            ctx.actions().healLeader(ctx.room(), ctx.owner(), 1, "QTE-M-WIND-34");
            reviveCounterpart(ctx, "QTE-M-WIND-35", "ハク霊", "コク霊");
        });

        register("QTE-M-WIND-35", TriggerType.ON_TURN_START, ctx ->
                selfDestructAtTurnStart(ctx, "コク霊"));
        register("QTE-M-WIND-35", TriggerType.ON_DESTROYED, ctx -> {
            ctx.actions().damageLeader(ctx.room(), ctx.opponent(), 1, "QTE-M-WIND-35");
            reviveCounterpart(ctx, "QTE-M-WIND-34", "コク霊", "ハク霊");
        });

        // ---- 喚ビ集ウ・アヤカシ(QTE-M-WIND-36) ----
        // 「【召喚時】自分の他のミニオンを1体破壊する。そうしたらカードを2枚引く。」
        //
        // 破壊は必須(「〜してもよい」ではない)だが、候補が1体も居ないときは何も起きない。
        // 使用宣言時の対象指定にすると候補が無いだけで召喚そのものが弾かれてしまうため、
        // 解決中の問い合わせにしている(ResumePoint.GATHERING_AYAKASHI_SACRIFICE の説明)。
        // 「そうしたら」なので、破壊が起きなければドローも起きない
        register("QTE-M-WIND-36", TriggerType.ON_SUMMON, ctx -> {
            List<String> others = otherOwnMinionIds(ctx);
            if (others.isEmpty()) {
                ctx.room().addLog("【喚ビ集ウ・アヤカシ】: 他の自分のミニオンが居ないため、何も起こりませんでした");
                return;
            }
            ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.one(
                    PendingChoice.Kind.MINION, others, ResumePoint.GATHERING_AYAKASHI_SACRIFICE,
                    "【喚ビ集ウ・アヤカシ】: 破壊する自分の他のミニオンを1体選んでください"));
        });

        // ---- 魂喰ラウ・オニ(QTE-M-WIND-37) ----
        // 「【召喚時】自分の他のミニオンを全て破壊する。こうして破壊した数相手のリーダーに1ダメージ与える。」
        register("QTE-M-WIND-37", TriggerType.ON_SUMMON, ctx -> {
            int destroyed = destroyOtherOwnMinions(ctx, "魂喰ラウ・オニ");
            if (destroyed <= 0) {
                return;
            }
            ctx.room().addLog("【魂喰ラウ・オニ】: %d体を糧に相手のリーダーへ%dダメージ".formatted(destroyed, destroyed));
            ctx.actions().damageLeader(ctx.room(), ctx.opponent(), destroyed, "QTE-M-WIND-37");
        });

        // ---- 暴レ狂ウ・オニ(QTE-M-WIND-38) ----
        // 「【召喚時】自分の他のミニオンを全て破壊する。こうして破壊した数相手のミニオンに1ダメージ。
        //   その後相手のリーダーに1ダメージ。」
        //
        // ★「破壊した数」は相手ミニオン<b>全員が受けるダメージ量</b>である(マスター裁定184)。
        //   体数ではない。魂喰ラウ・オニがリーダーへ縦に伸ばすのに対し、こちらは横に広げる。
        // ★リーダーへの1ダメージは「その後」であって破壊数を条件にしていないため、
        //   1体も破壊しなかった場合でも与える
        register("QTE-M-WIND-38", TriggerType.ON_SUMMON, ctx -> {
            int destroyed = destroyOtherOwnMinions(ctx, "暴レ狂ウ・オニ");
            if (destroyed > 0) {
                ctx.room().addLog("【暴レ狂ウ・オニ】: 相手のミニオンすべてに%dダメージ".formatted(destroyed));
                for (MinionInstance target : List.copyOf(ctx.opponent().getMinionZone())) {
                    ctx.actions().damageMinion(ctx.room(), ctx.opponent(), target, destroyed);
                }
            }
            ctx.actions().damageLeader(ctx.room(), ctx.opponent(), 1, "QTE-M-WIND-38");
        });

        // ---- 天翔ケル霊鬼・シュテン(QTE-M-WIND-39) ----
        // 「【特殊召喚】(このターンミニオンが8体以上破壊されていれば自分の手札からコスト1として出せる。)【速攻】」
        //
        // ★数えるのは両者の合計であり、破壊された後の行き先も問わない(マスター裁定185)。
        //   「自分の」と書いていない条件は両者を見る(裁定156(2))。
        //   カウンタは GameState が持つ(プレイヤー単位ではないため)
        specialSummons.put("QTE-M-WIND-39", new SpecialSummonSpec(
                (state, player, handIndex) -> state.getMinionsDestroyedThisTurn() >= 8,
                1,
                TargetSpec.of(),
                ctx -> {
                },
                ctx -> {
                },
                "このターン、ミニオンが8体以上破壊されている: コスト1で召喚します"));
    }

    /** ハク霊・コク霊のターン開始時の自壊。破壊時トリガーを正しく通すため破壊処理を経由する */
    private void selfDestructAtTurnStart(EffectContext ctx, String label) {
        if (ctx.source() == null) {
            return;
        }
        ctx.room().addLog("【%s】はターンのはじめに破壊されます".formatted(label));
        ctx.actions().destroyMinion(ctx.room(), ctx.owner(), ctx.source());
    }

    /** ハク霊・コク霊の【破壊時】に相方を墓地から場へ出す。墓地に無い・場が満杯なら不発 */
    private void reviveCounterpart(EffectContext ctx, String counterpartId,
            String selfLabel, String counterpartLabel) {
        if (ctx.actions().reviveFromGrave(ctx.room(), ctx.owner(), counterpartId)) {
            ctx.room().addLog("【%s】: 墓地から【%s】が場に出ました".formatted(selfLabel, counterpartLabel));
        } else {
            ctx.room().addLog("【%s】: 墓地に【%s】が無いか場が満杯のため、出せませんでした"
                    .formatted(selfLabel, counterpartLabel));
        }
    }

    /** 発生源以外の自分の場のミニオンの instanceId(喚ビ集ウ・アヤカシの候補) */
    private List<String> otherOwnMinionIds(EffectContext ctx) {
        List<String> ids = new ArrayList<>();
        for (MinionInstance m : ctx.owner().getMinionZone()) {
            if (m != ctx.source()) {
                ids.add(m.getInstanceId());
            }
        }
        return ids;
    }

    /**
     * 自分の他のミニオンを全て破壊し、<b>実際に破壊できた数</b>を返す(2種のオニ)。
     *
     * 大天使ミカエル・聖光の守護聖のような破壊の置換が働いた場合、そのミニオンは
     * 場に残る。数えるのは「破壊した数」なので、場に残ったものは数えない ——
     * したがって破壊の前後で場に居るかどうかを見る(destroyMinion は成否を返さない)。
     */
    private int destroyOtherOwnMinions(EffectContext ctx, String label) {
        int destroyed = 0;
        for (MinionInstance m : List.copyOf(ctx.owner().getMinionZone())) {
            if (m == ctx.source()) {
                continue;
            }
            ctx.actions().destroyMinion(ctx.room(), ctx.owner(), m);
            if (!ctx.owner().getMinionZone().contains(m)) {
                destroyed++;
            }
        }
        ctx.room().addLog("【%s】: 自分の他のミニオンを%d体破壊しました".formatted(label, destroyed));
        return destroyed;
    }

    // ---------------------------------------------------------------
    // 登録: 土文明(Batch 13b)
    //
    // 土のテーマは「マナ加速」。山札や手札のカードを表向きでマナに置く効果が多く、
    // その配置は Batch 13a で集約した GameActions.placeTopOfDeckInManaFaceUp /
    // placeCardInManaFaceUp を通す(マナ上限判定・配置回数の計数・豊穣の地霊主の発火を
    // 一元化するため。manaZone に直接 add してはならない)。
    //
    // 13a で土台を実装済みのため、ここに登録が不要なカードがある:
    //   - 豊穣の地霊主(L012): 常在トリガーは fireManaPlaced に実装済み
    //   - 大地の守護盾(0146): 肩代わりは attack/leaderAttack の tryIntercept... に実装済み
    //   - 不動の岩石竜(0141)・百獣の王ベヒーモス(0147)・ゴーレム・ウォール(0154):
    //     キーワードのみのバニラ。効果登録は不要(キーワードは台帳から付与される)
    // ---------------------------------------------------------------
    private void registerEarthCards() {

        // ---- リーダー起動能力 ----

        // 大地の巨頭(L011)は Ver.0.4 で起動能力(コスト4で手札1枚をマナに置く)を失い、
        // 「自分のミニオンは8体まで場に出せる」という常在効果だけを持つリーダーになった。
        // 常在効果の実体は PlayerState.getMinionZoneLimit() にあり、ここには登録を持たない。
        // leaderAbilities に残しておくと、存在しないはずの能力ボタンが盤面に出てしまう
        // (GameViewBuilder.buildLeaderAbility は spec が null かどうかだけを見ている)。

        // ---- ミニオン: 召喚時(ON_SUMMON) ----

        // 大地の精霊グラン(0137): 【召喚時】山札の上から1枚を表向きでマナに置く
        register("QTE-M-EARTH-3", TriggerType.ON_SUMMON,
                ctx -> ctx.actions().placeTopOfDeckInManaFaceUp(ctx.room(), ctx.owner()));

        // 苗木植えの精霊(0156): 【召喚時】手札を1枚表向きでマナに置く(手札は選択)
        targetSpecs.put("QTE-M-EARTH-16", TargetSpec.of(Requirement.of(
                Kind.HAND, Side.SELF, 1, false, "マナに置く手札を選んでください")));
        register("QTE-M-EARTH-16", TriggerType.ON_SUMMON, ctx ->
                ctx.targets().get(0).handCardIds().forEach(
                        id -> ctx.actions().placeCardInManaFaceUp(ctx.room(), ctx.owner(), id)));

        // 創世神ガイア(0138): 【召喚時】このミニオン以外の、お互いの場のミニオンをすべて破壊。
        // 【特殊召喚】自分のマナ最大値(マナゾーンの枚数)10以上でコスト0(代替コストなし)。
        specialSummons.put("QTE-M-EARTH-8", SpecialSummonSpec.of(
                (state, player, handIndex) -> player.getManaZone().size() >= 10,
                TargetSpec.of(),
                ctx -> {
                },
                "マナ10枚以上: 代替コストなしで0コスト召喚します"));
        register("QTE-M-EARTH-8", TriggerType.ON_SUMMON, ctx -> {
            MinionInstance self = ctx.source();
            for (PlayerState side : List.of(ctx.owner(), ctx.opponent())) {
                for (MinionInstance m : List.copyOf(side.getMinionZone())) {
                    if (m != self) {
                        ctx.actions().destroyMinion(ctx.room(), side, m);
                    }
                }
            }
            ctx.room().addLog("【創世神ガイア】: このミニオン以外の全ミニオンを破壊しました");
        });

        // 天変地異のタイタン(0145): 【召喚時】相手の場すべてに7ダメージ。カードを2枚引く
        register("QTE-M-EARTH-21", TriggerType.ON_SUMMON, ctx -> {
            List.copyOf(ctx.opponent().getMinionZone()).forEach(
                    m -> ctx.actions().damageMinion(ctx.room(), ctx.opponent(), m, 7));
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 2);
        });

        // アースクエイクジャイアント(0153): 【召喚時】相手の場の【守護】ミニオンをすべて破壊
        register("QTE-M-EARTH-4", TriggerType.ON_SUMMON, ctx ->
                List.copyOf(ctx.opponent().getMinionZone()).stream()
                        .filter(m -> m.hasKeyword(Keyword.GUARD))
                        .forEach(m -> ctx.actions().destroyMinion(ctx.room(), ctx.opponent(), m)));

        // 安らぎのガーディアン(0152): 【召喚時】リーダーを2回復。【ターンエンド時】リーダーを4回復
        register("QTE-M-EARTH-20", TriggerType.ON_SUMMON,
                ctx -> ctx.actions().healLeader(ctx.room(), ctx.owner(), 2, "QTE-M-EARTH-20"));
        register("QTE-M-EARTH-20", TriggerType.ON_TURN_END,
                ctx -> ctx.actions().healLeader(ctx.room(), ctx.owner(), 4, "QTE-M-EARTH-20"));

        // ---- ミニオン: その他トリガー ----

        // 連撃の巨岩(0017): 2回攻撃(maxAttacks=StatCalculator)。【ターンエンド時】このカードを手札に戻す
        register("QTE-M-EARTH-19", TriggerType.ON_TURN_END,
                ctx -> ctx.actions().bounceToHand(ctx.room(), ctx.owner(), ctx.source()));

        // 疾風怒濤のベヒーモス(0144): 【ターンエンド時】このカードを手札に戻す(速攻のみ、攻撃回数の上書きなし)
        register("QTE-M-EARTH-24", TriggerType.ON_TURN_END,
                ctx -> ctx.actions().bounceToHand(ctx.room(), ctx.owner(), ctx.source()));

        // タイタン・ウォリアー(0140): 戦闘で相手ミニオンを破壊した時、相手リーダーに4ダメージ
        // (ON_COMBAT_KILL は Batch 13a で新設。破壊された側ではなく撃破した側に発火する)
        register("QTE-M-EARTH-6", TriggerType.ON_COMBAT_KILL,
                ctx -> ctx.actions().damageLeader(ctx.room(), ctx.opponent(), 4, "QTE-M-EARTH-6"));

        // 不動の絶対神ガイア(0150): リーダーを攻撃できない(RuleGuards=13a)。攻撃時、相手リーダーに4ダメージ
        register("QTE-M-EARTH-23", TriggerType.ON_ATTACK,
                ctx -> ctx.actions().damageLeader(ctx.room(), ctx.opponent(), 4, "QTE-M-EARTH-23"));

        // 地砕きの突撃兵(0155): 攻撃時、自分のマナから1枚選び手札に戻す。破壊された時、山札の上から1枚を表向きでマナに置く。
        // 攻撃時の「1枚選び」は割り込み選択(a9)で本人に選ばせる(Batch 13c で自動選択から移行)。
        register("QTE-M-EARTH-17", TriggerType.ON_ATTACK, this::requestEarthbreakerManaReturn);
        register("QTE-M-EARTH-17", TriggerType.ON_DESTROYED,
                ctx -> ctx.actions().placeTopOfDeckInManaFaceUp(ctx.room(), ctx.owner()));

        // ---- ウェポン: 装備時(ON_EQUIP=13a) ----

        // ガイア・ハンマー(0142): 装備時、山札の上から1枚を表向きでマナに置く
        register("QTE-M-EARTH-14", TriggerType.ON_EQUIP,
                ctx -> ctx.actions().placeTopOfDeckInManaFaceUp(ctx.room(), ctx.owner()));
        // 地響きの槌(0009)の攻撃時効果(相手ミニオン全体2ダメージ)は GameService.leaderAttack の
        // ウェポン攻撃時 switch に直書きする(既存のウェポン攻撃時効果と同じ扱い)。

        // ---- スペル ----

        // 落石の罠(0139): 相手のミニオン1体に5ダメージ
        targetSpecs.put("QTE-M-EARTH-10", TargetSpec.of(Requirement.of(
                Kind.MINION, Side.OPPONENT, 1, false, "5ダメージを与える相手のミニオンを選んでください")));
        spellEffects.put("QTE-M-EARTH-10", ctx -> ctx.targets().get(0).minions().forEach(
                t -> ctx.actions().damageMinion(ctx.room(), t.owner(), t.minion(), 5)));

        // 大地震(0148): お互いのコスト3以下のミニオンをすべて破壊(印刷コストで判定)
        spellEffects.put("QTE-M-EARTH-11", ctx -> {
            for (PlayerState side : List.of(ctx.owner(), ctx.opponent())) {
                for (MinionInstance m : List.copyOf(side.getMinionZone())) {
                    Integer c = m.getMaster().cost();
                    if (c != null && c <= 3) {
                        ctx.actions().destroyMinion(ctx.room(), side, m);
                    }
                }
            }
            ctx.room().addLog("【大地震】: お互いのコスト3以下のミニオンをすべて破壊しました");
        });

        // 大地の開眼(0149): カードを1枚引く。自分のマナが7枚以上ならさらに1枚引く
        spellEffects.put("QTE-M-EARTH-25", ctx -> {
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
            if (ctx.owner().getManaZone().size() >= 7) {
                ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
            }
        });

        // 豊穣の祈り(0157): 山札の上から1枚を表向きでマナに置く。その後カードを2枚引く
        spellEffects.put("QTE-M-EARTH-12", ctx -> {
            ctx.actions().placeTopOfDeckInManaFaceUp(ctx.room(), ctx.owner());
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 2);
        });

        // 大地の恵み(0158): 山札の上から1枚を表向きでマナに置く
        spellEffects.put("QTE-M-EARTH-9",
                ctx -> ctx.actions().placeTopOfDeckInManaFaceUp(ctx.room(), ctx.owner()));

        // ガイア・リソース(0151): 山札の上から1枚をマナに置く。【還元】(還元の処理は GameActions 側で共通)
        spellEffects.put("QTE-M-EARTH-26",
                ctx -> ctx.actions().placeTopOfDeckInManaFaceUp(ctx.room(), ctx.owner()));

        // 地脈の覚醒(0015): マナ7枚以上でコスト2(effectiveCost=StatCalculator)。【還元】。
        // 解決時の固有処理はなく、還元によるマナ加速が本体(GameActions 側で共通処理)。
        // isSpellImplemented を true にするため、空の効果を登録しておく。
        spellEffects.put("QTE-M-EARTH-27", ctx -> {
            // 固有の解決処理なし(マナ加速は【還元】が担う)
        });
    }

    /**
     * 地砕きの突撃兵(QTE-M-EARTH-17)の攻撃時効果: 自分のマナから1枚選び手札に戻す。
     *
     * 候補が0枚なら不発、1枚なら自動決定、2枚以上なら本人に選ばせる(降臨の伝道師・風護の杖と同じ流儀)。
     * 候補はマナゾーン内の位置(0起点)で表す。表向き・裏向きを問わず候補に含めるのは、
     * カードテキストが向きを限定していないためである(流転の智者の Kind.MANA と同じ扱い)。
     *
     * <b>候補の位置がずれない根拠。</b> 選択待ちの間、そのプレイヤーは他の操作を行えない
     * (GameService.requireTurnPlayer が塞ぐ)。この攻撃の続きで起きうるマナゾーンの変化は、
     * 自身が戦闘破壊されたときの ON_DESTROYED による末尾への追加だけであり、
     * 既存の位置は動かない。
     */
    private void requestEarthbreakerManaReturn(EffectContext ctx) {
        int size = ctx.owner().getManaZone().size();
        if (size == 0) {
            ctx.room().addLog("【地砕きの突撃兵】: マナが無いため、手札に戻せませんでした");
            return;
        }
        if (size == 1) {
            ctx.actions().returnManaToHandAt(ctx.room(), ctx.owner(), 0);
            return;
        }
        List<String> positions = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            positions.add(String.valueOf(i));
        }
        ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.one(
                PendingChoice.Kind.MANA, positions, ResumePoint.EARTHBREAKER_MANA_RETURN,
                "【地砕きの突撃兵】: 手札に戻すマナを1枚選んでください"));
    }

    /**
     * 回帰の風穴の強化使用(a5)における2体目のバウンス対象を解決する。
     * 候補は自分・相手両方の場のミニオン(記法規約どおり)。0体なら不発、1体なら自動決定、
     * 2体以上ならプレイヤーの選択を待つ(a9。resolveChoiceのWINDHOLE_SECOND分岐が
     * 選択結果を受けて bounceWindholeSecondById を呼び、続きを行う)。
     */
    private void resolveWindholeSecondTargets(EffectContext ctx) {
        List<MinionInstance> board = new ArrayList<>();
        board.addAll(ctx.owner().getMinionZone());
        board.addAll(ctx.opponent().getMinionZone());
        if (board.isEmpty()) {
            return;
        }
        if (board.size() == 1) {
            bounceWindholeSecond(ctx, board.get(0));
            return;
        }
        ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.one(
                PendingChoice.Kind.MINION,
                board.stream().map(MinionInstance::getInstanceId).toList(),
                ResumePoint.WINDHOLE_SECOND,
                "【回帰の風穴】(2回目): 手札に戻すミニオンを選んでください"));
    }

    /** instanceIdから持ち主を特定してバウンスする(回帰の風穴2回目・resolveChoice双方から使う) */
    private void bounceWindholeSecond(EffectContext ctx, MinionInstance minion) {
        PlayerState owner = ctx.owner().getMinionZone().contains(minion) ? ctx.owner() : ctx.opponent();
        ctx.actions().bounceToHand(ctx.room(), owner, minion);
        ctx.room().addLog("【回帰の風穴】(2回目): 【%s】を手札に戻しました".formatted(minion.getMaster().name()));
    }

    private void bounceWindholeSecondById(EffectContext ctx, String instanceId) {
        for (PlayerState p : List.of(ctx.owner(), ctx.opponent())) {
            MinionInstance minion = p.getMinionZone().stream()
                    .filter(m -> m.getInstanceId().equals(instanceId)).findFirst().orElse(null);
            if (minion != null) {
                bounceWindholeSecond(ctx, minion);
                return;
            }
        }
    }

    /** 運命のリセット: 手札をすべて山札に戻してシャッフルする(枚数はドローで別途戻す) */
    private void reshuffleHandIntoDeck(PlayerState player) {
        List<String> pool = new ArrayList<>(player.getDeck());
        pool.addAll(player.getHand());
        player.getHand().clear();
        java.util.Collections.shuffle(pool);
        player.getDeck().clear();
        player.getDeck().addAll(pool);
    }

    /**
     * 降臨の伝道師の解決を1箇所にまとめる。0体/1体の自動解決(registerLightCards内)と、
     * 2体以上のときのプレイヤー選択(resolveChoiceのMISSIONARY_SUMMON分岐)の両方から呼ばれる。
     */
    private void resolveMissionaryChoice(EffectContext ctx, List<String> revealed, int chosenIndex) {
        String chosenId = revealed.get(chosenIndex);
        List<String> rest = new ArrayList<>(revealed);
        rest.remove(chosenIndex);
        if (ctx.owner().isMinionZoneFull()) {
            ctx.actions().returnToBottomOfDeck(ctx.owner(), revealed);
            ctx.room().addLog("ミニオンゾーンが満杯のため、公開した4枚はすべて山札の下に置かれました");
            return;
        }
        ctx.actions().returnToBottomOfDeck(ctx.owner(), rest);
        ctx.actions().putIntoFieldByEffect(ctx.room(), ctx.owner(), chosenId);
        List<MinionInstance> zone = ctx.owner().getMinionZone();
        MinionInstance summoned = zone.get(zone.size() - 1);
        ctx.room().addLog("【降臨の伝道師】が【%s】を場に出しました".formatted(cards.findById(chosenId).name()));
        ctx.actions().damageMinion(ctx.room(), ctx.owner(), summoned, 3);
    }

    /**
     * 中断していた効果を、プレイヤーの選択結果で再開する(a9)。
     *
     * GameService.resolveChoice から呼ばれる。GameService 側は
     * 「誰が・いくつ・正しい候補から選んだか」までを検証済みであり、
     * ここは「その結果で何が起きるか」だけを担当する(GameServiceとRegistryの役割分担どおり)。
     *
     * 継続をラムダで保持せず列挙体+switchにした理由は {@link ResumePoint} を参照。
     *
     * @param choice  解決対象の選択(GameServiceが状態から取り除いた後の写し)
     * @param chosen  選ばれた候補の識別子。choice.candidates() の部分集合
     */
    public void resolveChoice(EffectContext ctx, PendingChoice choice, List<String> chosen) {
        switch (choice.resumeAt()) {
            case MISSIONARY_SUMMON -> {
                // 公開領域の中身を取り出して確定させる(選択待ちの間だけ置かれていたもの)
                List<String> revealed = new ArrayList<>(ctx.owner().getRevealedZone());
                ctx.owner().getRevealedZone().clear();
                resolveMissionaryChoice(ctx, revealed, Integer.parseInt(chosen.get(0)));
            }
            // 選択の追い風(QTE-M-WIND-25): 「〜してもよい」の任意ディスカード。
            // 選ばなかった(chosenが空)場合は何もしない
            case TAILWIND_DISCARD -> {
                if (!chosen.isEmpty()) {
                    int idx = Integer.parseInt(chosen.get(0));
                    String cardId = ctx.owner().getHand().remove(idx);
                    ctx.owner().getTrash().add(cardId);
                    ctx.room().addLog("【選択の追い風】: 【%s】を捨てました".formatted(cards.findById(cardId).name()));
                    ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
                }
            }
            // 風のマナ変換(QTE-M-WIND-23): 裏向きでマナに置く手札1枚を確定させる
            case MANA_CONVERT_PUT -> {
                int idx = Integer.parseInt(chosen.get(0));
                ctx.actions().putHandCardIntoManaFaceDown(ctx.room(), ctx.owner(), idx);
            }
            // 回帰の風穴(QTE-M-WIND-26)の強化使用: 2回目のバウンス対象を確定させる
            case WINDHOLE_SECOND -> bounceWindholeSecondById(ctx, chosen.get(0));
            // 風護の杖(QTE-M-WIND-28): 攻撃時に体力+1・守護を与えるミニオンを確定させる
            case GUARD_STAFF_TARGET -> {
                MinionInstance minion = ctx.owner().getMinionZone().stream()
                        .filter(m -> m.getInstanceId().equals(chosen.get(0)))
                        .findFirst().orElse(null);
                if (minion != null) {
                    minion.addModifier(new StatModifier(StatModifier.Stat.HP, StatModifier.Operation.ADD, 1,
                            StatModifier.Duration.PERMANENT, "QTE-M-WIND-28"));
                    minion.grantKeyword(Keyword.GUARD);
                    ctx.room().addLog("【風護の杖】: 【%s】の体力が+1され、【守護】を得ました"
                            .formatted(minion.getMaster().name()));
                }
            }
            // 詠唱の疾風騎士(QTE-M-WIND-18): ターンエンド時に墓地から回収するスペル(最大2枚)を確定させる。
            // 複数選択なので位置がずれないよう降順に取り除く
            case GALE_KNIGHT_RECOVER -> {
                List<Integer> positions = new ArrayList<>();
                chosen.forEach(s -> positions.add(Integer.parseInt(s)));
                positions.sort(java.util.Comparator.reverseOrder());
                for (int pos : positions) {
                    String cardId = ctx.owner().getTrash().remove(pos);
                    ctx.owner().getHand().add(cardId);
                    ctx.room().addLog("【詠唱の疾風騎士】: 【%s】を墓地から手札に戻しました"
                            .formatted(cards.findById(cardId).name()));
                }
            }
            // 地砕きの突撃兵(QTE-M-EARTH-17): 攻撃時に手札へ戻すマナを確定させる。
            // 候補はマナゾーン内の位置。返却とゾーン横断トリガーの発火は GameActions が担う
            case EARTHBREAKER_MANA_RETURN -> {
                int idx = Integer.parseInt(chosen.get(0));
                ctx.actions().returnManaToHandAt(ctx.room(), ctx.owner(), idx);
            }
            // アクア・サーチ(QTE-M-WATER-25): 2枚引いた後に捨てる手札を確定させる。
            // TAILWIND_DISCARD と違い必須(min=1)のため、chosen が空になることはない
            case AQUA_SEARCH_DISCARD -> {
                int idx = Integer.parseInt(chosen.get(0));
                String cardId = ctx.owner().getHand().remove(idx);
                ctx.owner().getTrash().add(cardId);
                ctx.room().addLog("【アクア・サーチ】: 【%s】を捨てました"
                        .formatted(cards.findById(cardId).name()));
            }
            // 喚ビ集ウ・アヤカシ(QTE-M-WIND-36): 【召喚時】に破壊する自分のミニオンを確定させる。★Batch 48。
            // 「そうしたらカードを2枚引く」なので、破壊が実際に起きたときだけ引く。
            // 選択中に盤面が変わって候補が場から消えている場合(破壊の置換で残った場合も含む)は
            // 何も起きない ―― 候補は instanceId で保持しているため、照合はここで行う
            case GATHERING_AYAKASHI_SACRIFICE -> {
                MinionInstance victim = ctx.owner().getMinionZone().stream()
                        .filter(m -> m.getInstanceId().equals(chosen.get(0)))
                        .findFirst().orElse(null);
                if (victim == null) {
                    ctx.room().addLog("【喚ビ集ウ・アヤカシ】: 選んだミニオンが場に居ないため、何も起こりませんでした");
                    break;
                }
                ctx.actions().destroyMinion(ctx.room(), ctx.owner(), victim);
                if (ctx.owner().getMinionZone().contains(victim)) {
                    ctx.room().addLog("【喚ビ集ウ・アヤカシ】: 破壊されなかったためドローしません");
                    break;
                }
                ctx.actions().drawCards(ctx.room(), ctx.owner(), 2);
            }
        }
    }

    // ---------------------------------------------------------------
    // 照会・発火
    // ---------------------------------------------------------------

    /**
     * 使用条件の検証。満たしていなければ例外を投げる(状態は変更しない)。
     * GameServiceがコストの支払いより前に呼ぶ。
     */
    public void requirePlayable(String cardId, GameState state, PlayerState player) {
        BiPredicate<GameState, PlayerState> condition = playConditions.get(cardId);
        if (condition != null && !condition.test(state, player)) {
            throw new IllegalStateException("このカードを使用する条件を満たしていません");
        }
    }

    /** 「自分のミニオンが破壊された」監視効果の登録 */
    private void watchOwnMinionDestroyed(String cardId, BiConsumer<EffectContext, String> effect) {
        ownMinionDestroyedWatchers.put(cardId, effect);
    }

    private void register(String cardId, TriggerType trigger, Consumer<EffectContext> effect) {
        triggers.computeIfAbsent(cardId, k -> new EnumMap<>(TriggerType.class)).put(trigger, effect);
    }

    /** プレイ時の対象指定仕様。要求しないカードは空のTargetSpecを返す */
    public TargetSpec targetSpecOf(String cardId) {
        return targetSpecs.getOrDefault(cardId, TargetSpec.of());
    }

    public SpecialSummonSpec specialSummonOf(String cardId) {
        return specialSummons.get(cardId);
    }

    /** スペルの解決。効果が未登録のスペルは実装漏れとして拒否する(黙って何も起きないのが最悪) */
    public void resolveSpell(String cardId, EffectContext ctx) {
        Consumer<EffectContext> effect = spellEffects.get(cardId);
        if (effect == null) {
            throw new IllegalStateException("このスペルの効果は未実装です(Batch 4で対応)");
        }
        effect.accept(ctx);
    }

    /** スペルの解決時効果が登録済みか */
    public boolean isSpellImplemented(String cardId) {
        return spellEffects.containsKey(cardId);
    }

    /**
     * このカードIDが、9つの表のどれかに登録されているか(★Batch 47)。
     *
     * <b>実行時の表そのものを見ている</b>ことに意味がある。登録は
     * {@code registerXxx()} が実行された結果であり、ソースを走査して数える
     * {@code tools/report_effects.py} と違って、書き方(直接 put か、
     * {@code register(...)} や {@code watchOwnMinionDestroyed(...)} のような
     * ヘルパ経由か)に左右されない(裁定170: 測るのはエンジンが実際に持っている値)。
     */
    public boolean isRegistered(String cardId) {
        return spellEffects.containsKey(cardId)
                || triggers.containsKey(cardId)
                || targetSpecs.containsKey(cardId)
                || specialSummons.containsKey(cardId)
                || leaderAbilities.containsKey(cardId)
                || minionAbilities.containsKey(cardId)
                || enhancedCosts.containsKey(cardId)
                || ownMinionDestroyedWatchers.containsKey(cardId)
                || playConditions.containsKey(cardId);
    }

    /**
     * ミニオンのトリガー発火。
     * ON_ENTERのタイミングでは、キーワード【知識】(登場時1ドロー)も共通処理として発動する。
     */
    public void fire(TriggerType trigger, MinionInstance minion, EffectContext ctx) {
        if (trigger == TriggerType.ON_ENTER && minion.hasKeyword(Keyword.KNOWLEDGE)) {
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
            ctx.room().addLog("【知識】%sが1枚ドロー".formatted(ctx.owner().getDisplayName()));
        }
        Consumer<EffectContext> effect = triggers
                .getOrDefault(minion.getMaster().id(), Map.of())
                .get(trigger);
        if (effect != null) {
            effect.accept(ctx);
        }
    }
}
