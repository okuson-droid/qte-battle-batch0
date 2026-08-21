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
     * 「<b>どちらの</b>ミニオンが破壊された」にも反応する監視効果(★Batch 57。カードID → 処理)。
     *
     * <p>{@link #ownMinionDestroyedWatchers} との違いは<b>数える範囲</b>だけである。
     * Ver1.1 の《執念の暗殺者》は本文から「自分の」が消えたため、裁定156(2)により
     * 両者のミニオンの破壊に反応する。
     *
     * <p>相手の場で起きた破壊に反応するとき、文脈は
     * {@link EffectContext#swapSides()} で持ち主側に向け直してから渡す。
     */
    private final Map<String, BiConsumer<EffectContext, String>> anyMinionDestroyedWatchers = new HashMap<>();

    /**
     * 使用条件(カードID → 判定)。「代償を払えないなら使用できない」カードのための仕組み。
     *
     * 対象指定(TargetSpec)では表現できない条件をここに置く。判定は
     * コストの支払いより前に行われるため、条件を満たさないカードは状態を一切変えずに弾かれる。
     */
    private final Map<String, BiPredicate<GameState, PlayerState>> playConditions = new HashMap<>();

    /**
     * 進化ミニオンの召喚素材の仕様(★Batch 52。裁定154)。
     *
     * ★<b>この表だけは {@link #isRegistered(String)} が見ない。</b>
     * 素材条件は「そのカードの効果」ではなく<b>場に出す手段</b>だからである。
     * 数えてしまうと、素材条件を書いただけで効果が未実装の進化から印が消える
     * (詳細は {@link EvolutionSpec} の javadoc)。
     */
    private final Map<String, EvolutionSpec> evolutions = new HashMap<>();

    /**
     * 【賢魂：n】としての使用の仕様(★Batch 54。裁定152)。
     *
     * ★<b>この表は {@link #isRegistered(String)} が見る。</b>
     * {@link #evolutions} と違い、賢魂は<b>そのカードの効果そのもの</b>だからである
     * (素材条件は「場に出す手段」であって効果ではない。裁定233)。
     * ★n はここに持たない —— テキストが正である({@link SoulSpellSpec} の説明を参照)。
     */
    private final Map<String, SoulSpellSpec> soulSpells = new HashMap<>();

    // ---------------------------------------------------------------
    // ★Batch 47: 表に登録せず、このクラスのコードに直接書かれているカード。
    // 表に載らないので {@link #isRegistered(String)} では拾えない。
    // ---------------------------------------------------------------

    private static final String ABYSS_DRAGON = "QTE-M-WATER-20";    // 黄泉還る水龍(墓地から自動で戻る)
    private static final String HARVEST_LEADER = "QTE-M-EARTH-15";  // 豊穣の地霊主(マナ設置2回目でドロー)
    private static final String FLAME_FANATIC = "QTE-M-FIRE-4";     // 火炎の狂信者(自傷でAttack+2)
    private static final String FLAME_MIRROR = "QTE-M-FIRE-28";     // 反転の炎鏡(自傷を水増しする)
    private static final String STOK_LEADER = "QTE-M-WIND-29";      // 妖ノ長・ストク(★Batch 48)
    private static final String LOLOIYO_LEADER = "QTE-M-WATER-29";  // ロロイヨ伯爵(★Batch 49)
    private static final String GRAVE_DANCER_LEADER = "QTE-M-DARK-29"; // 演舞の墓守(★Batch 50)
    private static final String COMEBACK_KEEPER = "QTE-M-DARK-35";  // カムバックキーパー(★Batch 50)
    private static final String ANTOMARUEL_LEADER = "QTE-M-LIGHT-29"; // 英皇アントマルエル(★Batch 50)

    /** 英皇アントマルエルがドローする手札の上限(「自分の手札が6枚以下だったら」) */
    private static final int ANTOMARUEL_HAND_LIMIT = 6;

    /** 1stL「NEMれぬ夜のドリーミー」が【速攻】を得る破壊数(「10体以上で」) */
    private static final int DREAMY_HASTE_THRESHOLD = 10;

    /** 乱戦鉄機狼(★Batch 51): この値以下なら自傷が相手への1ダメージに置き換わる */
    private static final int IRON_WOLF_LP_THRESHOLD = 10;

    /** 勝阿外の【賢魂：2】(★Batch 54): 「自分のマナが3枚以下のとき」1枚引く */
    private static final int KATSUAGE_DRAW_MANA_LIMIT = 3;

    /**
     * 海淵獣シラーカ(★Batch 52)。効果の文が<b>進化の素材条件だけ</b>のカードである。
     * 素材条件は {@link #evolutions} にあるが、あの表は {@link #isRegistered(String)} が
     * 数えないため、ここで名乗らないと実装済みなのに印が付く(裁定176)。
     */
    private static final String SHIRAKA = "QTE-M-WATER-30";

    /**
     * 不敗鉄人闘太(★Batch 52)。【常在】の中身が
     * 「下にあるミニオン1枚につき Attack と HP が +2」であり、
     * その値は {@link EvolutionSpec#statPerUnderCard()} が運んでいる。
     * 表への登録ではないので、シラーカと同じくここで名乗る。
     */
    private static final String TOUTA = "QTE-M-FIRE-30";

    /** 不敗鉄人闘太が下にあるカード1枚につき得る Attack・HP */
    private static final int TOUTA_STAT_PER_UNDER_CARD = 2;

    /**
     * リボーンライヴ・ノア(★Batch 53)。【常在】「自分のミニオンが墓地から場に出た時
     * そのミニオンは【突進】を得る」を {@link #fireMinionEnteredFromGrave} に直接書いている ——
     * 演舞の墓守と同じ発火口である。
     *
     * <p>★<b>それでも {@link #IMPLEMENTED_CARDS} には入れない。</b>
     * このカードは【召喚時】のほうで表(targetSpecs / triggers)に載っており、
     * {@link #isRegistered(String)} が既に真を返すからである。
     * あの集合は<b>「表に1行も載らないカード」</b>の一覧であって、
     * 「このクラスが面倒を見ているカード」の一覧ではない ——
     * 入れると、外しても何も落ちない宣言が1つ増える(壊し検証28番がそれを検出した)。
     */
    private static final String NOA = "QTE-M-DARK-30";

    /** 【破壊時】を持つカードを見分けるための本文中の印(★Batch 53。灰ノ霊呼者) */
    private static final String ON_DESTROYED_MARK = "【破壊時】";

    /**
     * このクラスのコードに直接書かれている(=表に載っていない)カード(★Batch 47)。
     * 趣旨と番人は {@link RuleGuards#IMPLEMENTED_CARDS} の説明を参照。
     */
    public static final java.util.Set<String> IMPLEMENTED_CARDS =
            java.util.Set.of(ABYSS_DRAGON, HARVEST_LEADER, FLAME_FANATIC, FLAME_MIRROR,
                    STOK_LEADER, LOLOIYO_LEADER,
                    GRAVE_DANCER_LEADER, COMEBACK_KEEPER, ANTOMARUEL_LEADER,
                    SHIRAKA, TOUTA);

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
        registerWaterVer11Cards();
        registerDarkVer11Cards();
        registerLightVer11Cards();
        registerFireVer11Cards();
        registerEarthVer11Cards();
        registerEvolutionMaterials();
        registerEvolutionCards();
        registerEvolutionEffectCards();
        registerSoulCards();
    }

    // ---------------------------------------------------------------
    // 登録: 対象指定なしのスペル(Batch 2)
    // ---------------------------------------------------------------

    private void registerSpells() {
        // アクア・サーチ: カードを2枚引く。その後手札のカードを2枚捨てる。
        // ★Batch 55(区分3a): 捨て 1→2枚(rework-triage.md)。捨てる対象は引いた後の手札から
        // 選ぶため、使用宣言時に選び終える TargetSpec では表現できない。解決を中断して
        // 問い合わせる a9(割り込み選択)を使う。捨てるのは必須なので min=max とし、
        // 手札が2枚に満たない場合(山札切れ等)は、あるだけ全部を捨てさせる
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
            int discardCount = Math.min(2, handPositions.size());
            ctx.actions().requestChoice(ctx.room(), ctx.owner(), new PendingChoice(
                    PendingChoice.Kind.HAND, handPositions, discardCount, discardCount,
                    ResumePoint.AQUA_SEARCH_DISCARD,
                    "【アクア・サーチ】: 捨てる手札を2枚選んでください"));
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
        // ★Batch 56(区分4): Ver1.1 でドロー 3→2、「このターンカードを使用できない」の
        // 制限が撤廃された(rework-triage.md 区分4)。「最初にしか使えない」制限は据え置き
        playConditions.put("QTE-M-WATER-26",
                (state, player) -> state.getPhase() == TurnPhase.MAIN && !player.isPlayedCardThisTurn());
        spellEffects.put("QTE-M-WATER-26", // 静寂の瞑想: 2枚引く
                ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 2));

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
            discarded.handCardIds().forEach(
                    id -> ctx.actions().putIntoTrashFromElsewhere(ctx.room(), ctx.owner(), id));
            ctx.room().addLog("%sが手札を1枚捨てました".formatted(ctx.owner().getDisplayName()));
            ctx.targets().get(1).minions().forEach(
                    t -> ctx.actions().bounceToHand(ctx.room(), t.owner(), t.minion()));
        });

        // ★Batch 58(区分5): 英知の継承者(QTE-M-WATER-19)。
        // 旧: 「【召喚時】【知識】を持つカードを1枚手札から捨てても良い。そうしたら【知識】を行う。」
        //     (任意の捨て → 1ドロー。差し引き手札は増減なし)
        // 新: 「【召喚時】カードを4枚引く。その後カードを3枚捨てる」
        //     (必須。手札は差し引き+1だが、山札を4枚掘って要らない3枚を選べる)
        // ★<b>使用宣言時の対象指定(TargetSpec)を消した。</b>捨てるのは<b>引いた後の</b>手札から
        //   であり、宣言時に選び終える TargetSpec では表現できない ——
        //   《海淵獣ラカブ》(3枚引いて1枚捨てる)と同じ割り込み(a9)に移す。
        // ★手札が3枚に満たなければ、あるだけ捨てる(裁定191・217 と同じ形)。
        //   4枚引いた直後なので実際には必ず3枚以上あるが、
        //   《断罪の大天使》のドロー置換で引けなかった場合に効いてくる。
        register("QTE-M-WATER-19", TriggerType.ON_SUMMON, ctx -> {
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 4);
            int count = Math.min(3, ctx.owner().getHand().size());
            requestDiscard(ctx, count, count, ResumePoint.WISDOM_HEIR_DISCARD,
                    "【英知の継承者】: 捨てる手札を%d枚選んでください".formatted(count));
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

        // 深海神 プレサージュ: 自分の知識を持つカードを手札から3枚山札の下に置いて0コストで出せる
        // ★Batch 55(区分3a): 山下送り 5→3枚(rework-triage.md)。【知識】はテキストから自動で付く
        specialSummons.put("QTE-M-WATER-24", SpecialSummonSpec.of(
                (state, player, handIndex) -> countKnowledgeInHandExcluding(player, handIndex) >= 3,
                TargetSpec.of(new Requirement(Kind.HAND, Side.SELF, 3, false, false, List.of(Filter.KNOWLEDGE),
                        "山札の下に置く【知識】カードを3枚選んでください")),
                ctx -> {
                    ctx.targets().get(0).handCardIds().forEach(id -> ctx.owner().getDeck().addLast(id));
                    ctx.room().addLog("%sが手札3枚を山札の下に置きました".formatted(ctx.owner().getDisplayName()));
                },
                "手札の【知識】カード3枚を山札の下に置き、0コストで召喚します"));

        // 知恵の双翼: 自分の【知識】を持つミニオンを2体手札に戻して0コストで出せる
        // ★Batch 58(区分5)実装変更なし: Ver1.1 で変わったのは<b>書き方だけ</b>である。
        //   旧「【特殊召喚】自分の【知識】を持つミニオンを2体手札に戻した手札から0コストとして出せる。」
        //     (旧台帳の注記にも「原文ママ」とあり、日本語として壊れていた)
        //   新「【知識】【守護】【特殊召喚】(自分の【知識】を持つミニオンを2体手札に戻したとき
        //       手札から0コストとして出せる。)」
        //   増えた【知識】【守護】は旧台帳の keywords にも入っていたものが本文に現れただけであり、
        //   キーワードはテキストから付く(裁定158)ので実装は要らない。
        //   条件・代替コスト・コストのいずれも動いていない。
        //   ★<b>区分5(ほぼ書き直し)に仕分けられていたが、実際は区分2(記法だけ)であった。</b>
        //     《死者蘇生》《ガイア・ハンマー》に続く3例目である(rework-triage.md 1-1)。
        specialSummons.put("QTE-M-WATER-22", SpecialSummonSpec.of(
                (state, player, handIndex) -> player.getMinionZone().stream()
                        .filter(m -> m.hasKeyword(Keyword.KNOWLEDGE)).count() >= 2,
                TargetSpec.of(new Requirement(Kind.MINION, Side.SELF, 2, false, false, List.of(Filter.KNOWLEDGE),
                        "手札に戻す自分の【知識】ミニオンを2体選んでください")),
                ctx -> ctx.targets().get(0).minions().forEach(
                        t -> ctx.actions().bounceToHand(ctx.room(), t.owner(), t.minion())),
                "自分の【知識】ミニオン2体を手札に戻し、0コストで召喚します"));

        // 智将 ポセイドン・コア: 自分の【知識】ミニオンの合計体力が9以上なら0コストで出せる
        // ★Batch 56(区分4): Ver1.1 で条件が12→9に緩和(rework-triage.md 区分4)
        specialSummons.put("QTE-M-WATER-23", SpecialSummonSpec.of(
                (state, player, handIndex) -> player.getMinionZone().stream()
                        .filter(m -> m.hasKeyword(Keyword.KNOWLEDGE))
                        .mapToInt(MinionInstance::getCurrentHp).sum() >= 9,
                TargetSpec.of(),
                ctx -> {
                },
                "【知識】ミニオンの合計体力9以上: 代替コストなしで0コスト召喚します"));
        // ポセイドン・コアの【召喚時】: 自分の【知識】ミニオン2体につきカードを1枚引く
        // ★Batch 56(区分4): 旧効果「自分のミニオンは【突進】を得る」が別物に置き換わった。
        // 端数切り捨て(2体で1枚・3体でも1枚・4体で2枚)。自身も【知識】を持つため場に出た
        // 時点で数に含まれる(旧効果の「自身も場にいるため含まれる」という扱いを踏襲)
        register("QTE-M-WATER-23", TriggerType.ON_SUMMON, ctx -> {
            long knowledgeMinions = ctx.owner().getMinionZone().stream()
                    .filter(m -> m.hasKeyword(Keyword.KNOWLEDGE)).count();
            int drawn = (int) (knowledgeMinions / 2);
            if (drawn > 0) {
                ctx.actions().drawCards(ctx.room(), ctx.owner(), drawn);
            }
        });

        // 海皇 ポセイドン: メインフェーズ開始時、手札7枚以上なら手札3枚を捨ててコストなしで出せる
        // 「開始時」の厳密な実装は「このターンまだカードをプレイしていない」で近似する(設計解説4章)
        specialSummons.put("QTE-M-WATER-8", SpecialSummonSpec.of(
                (state, player, handIndex) -> player.getHand().size() >= 7 && !player.isPlayedCardThisTurn(),
                TargetSpec.of(new Requirement(Kind.HAND, Side.SELF, 3, false, false, List.of(),
                        "捨てるカードを3枚選んでください")),
                ctx -> {
                    ctx.targets().get(0).handCardIds().forEach(
                            id -> ctx.actions().putIntoTrashFromElsewhere(ctx.room(), ctx.owner(), id));
                    ctx.room().addLog("%sが手札3枚を捨てました".formatted(ctx.owner().getDisplayName()));
                },
                "手札3枚を捨て、コストを支払わずに召喚します(メインフェーズ開始時のみ)"));
    }

    // ---------------------------------------------------------------
    // 登録: リーダー起動能力(Batch 4)
    // ---------------------------------------------------------------

    private void registerLeaderAbilities() {
        // 蒼海の賢者: 【起動：1】自分の手札を1枚デッキの一番下に戻す。自分のリーダーの体力を2回復
        // ★Batch 55: 旧本文はコストを書いておらず、実装は0マナと決め打ちしていた。
        // Ver1.1の【起動：1】で初めて値が定まった(rework-triage.md 2章の食い違い)。
        leaderAbilities.put("QTE-M-WATER-1", LeaderAbilitySpec.of(1,
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
        // ★Batch 50: 墓地から場へ出す経路を reviveFromGrave に集約した
        // (演舞の墓守の常在がそこに1本だけ掛かるようにするため)。
        // 出せなかった場合(場が満杯・登場が置換された)はループを抜ける
        while (ctx.owner().getTrash().contains(ABYSS_DRAGON)
                && !ctx.actions().isFieldEntryBlocked(ctx.room(), ctx.owner())) {
            if (ctx.actions().reviveFromGrave(ctx.room(), ctx.owner(), ABYSS_DRAGON) == null) {
                return;
            }
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
            if (effect == null) {
                effect = anyMinionDestroyedWatchers.get(watcher.getMaster().id());
            }
            if (effect == null || !ctx.owner().getMinionZone().contains(watcher)) {
                continue;
            }
            effect.accept(ctx, destroyedCardId);
        }
        // ★Batch 57: 「自分の」と書かれていない監視効果(執念の暗殺者)は、
        // 破壊されたミニオンの<b>持ち主でない側</b>の場でも発火する(裁定156(2))。
        // 文脈は swapSides で反応する側へ向け直す —— そうしないとドローが相手に飛ぶ。
        // 上のループと2本に分けているのは、「自分の」限定の監視効果(不滅のネクロマンサー・
        // 妖ノ長・ストク)をこちら側で発火させてはならないためである。
        EffectContext otherSide = ctx.swapSides();
        for (MinionInstance watcher : List.copyOf(otherSide.owner().getMinionZone())) {
            BiConsumer<EffectContext, String> effect =
                    anyMinionDestroyedWatchers.get(watcher.getMaster().id());
            if (effect == null || !otherSide.owner().getMinionZone().contains(watcher)) {
                continue;
            }
            effect.accept(otherSide, destroyedCardId);
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
     * 「場にミニオンが出た」イベントの処理(★Batch 49)。
     * 発火は {@link TriggerType#ON_ENTER} と同じ2箇所
     * ({@code GameService.summonToField} / {@code GameActions.putIntoFieldByEffect})であり、
     * 召喚か効果による「出す」かを問わない(<b>マスター裁定193</b>)。
     *
     * <h2>★なぜ TriggerType を足さなかったか</h2>
     *
     * `notes/ver11-migration-plan.md` 2-1 は、このイベントを「P2 で足すトリガーの2つ目」と
     * 見込んでいた。実際に使い手を読んだところ、<b>ロロイヨ伯爵(水)も英皇アントマルエル(光)も
     * リーダーの【常在】</b>であり、ミニオンやウェポンが反応するものは1枚も無かった。
     * {@link TriggerType} はカード(ミニオン・ウェポン)の表に載せるための分類であって、
     * リーダーの常在能力はこれまでも表に載せず、専用の発火口に直接書いてきた
     * ({@code fireManaPlaced} = 豊穣の地霊主 / {@code fireOwnMinionDestroyed} = 妖ノ長・ストク)。
     * ここで TriggerType を足すと、<b>誰も登録しない器</b>が1つ増える(裁定178 が戒めた形そのもの)。
     * 使い手が現れたときに足せばよい。
     *
     * <h2>★両者のリーダーを見る</h2>
     *
     * テキストが「自分の」と書いていない誘発は両者を見る(裁定156(2))。
     * したがって<b>相手のミニオンが場に出ても誘発する</b>。
     * {@code ctx.owner()} は「場に出たミニオンの持ち主」であって「反応する側」ではないため、
     * ドロー先は必ず watcher 側で指定すること。
     *
     * @param ctx ctx.source() に場に出たミニオンが入る
     */
    public void fireAnyMinionEntered(EffectContext ctx) {
        MinionInstance entered = ctx.source();
        if (entered == null) {
            return;
        }
        int turn = ctx.state().getTurnNumber();
        for (PlayerState watcher : List.of(ctx.state().getPlayer1(), ctx.state().getPlayer2())) {
            // 英皇アントマルエル(★Batch 50): 「【常在】場にミニオンが出た時
            // 自分の手札が6枚以下だったらカードを1枚引く」。
            // ★<b>「ターンに一回」と書かれていない</b>ので回数制限は無い。
            //   ロロイヨ伯爵(すぐ下)がターン刻印を要るのは、あちらのテキストに
            //   「ターンに一回」と書いてあるからであって、この発火口の性質ではない。
            // ★手札の枚数を見るのは<b>反応する側(watcher)</b>である。
            //   自分の召喚で誘発した場合、そのカードは既に手札を離れている
            //   (GameService は removePlayedAndTargets のあとで場に出す)ので、
            //   数えるのは「出したあとの手札」になる
            if (ANTOMARUEL_LEADER.equals(watcher.getLeader().id())
                    && watcher.getHand().size() <= ANTOMARUEL_HAND_LIMIT) {
                ctx.room().addLog("【英皇アントマルエル】: 場にミニオンが出たため1ドロー");
                ctx.actions().drawCards(ctx.room(), watcher, 1);
            }
            if (!LOLOIYO_LEADER.equals(watcher.getLeader().id())) {
                continue;
            }
            // ★【守護】と【潜伏】のカウントは独立である(裁定156(1))。
            // 両方を持つミニオン1体が場に出たら、そのターンに2枚引く。
            // 判定に hasKeyword を使うのは、効果で付与された【守護】(そよ風の加護など)も
            // 「【守護】のミニオン」だからである
            if (entered.hasKeyword(Keyword.GUARD) && watcher.tryConsumeGuardEntryDraw(turn)) {
                ctx.room().addLog("【ロロイヨ伯爵】: 【守護】のミニオンが場に出たため1ドロー");
                ctx.actions().drawCards(ctx.room(), watcher, 1);
            }
            if (entered.hasKeyword(Keyword.STEALTH) && watcher.tryConsumeStealthEntryDraw(turn)) {
                ctx.room().addLog("【ロロイヨ伯爵】: 【潜伏】のミニオンが場に出たため1ドロー");
                ctx.actions().drawCards(ctx.room(), watcher, 1);
            }
        }
    }

    /**
     * 「自分の墓地から場にミニオンが出た」イベントの処理(★Batch 50)。
     *
     * <blockquote>演舞の墓守(QTE-M-DARK-29・リーダー):
     * 【常在】自分の墓地から出たミニオンのAttackをそのターン+1</blockquote>
     *
     * <b>経路を問わない</b>(マスター裁定204)。効果による「出す」(蘇生)も、
     * 黄泉の召喚主の「墓地からの召喚」も、カムバックキーパーの自己復帰も、
     * すべてこの1行の対象である。したがって発火位置も2箇所ある ——
     * {@code GameActions.reviveFromGrave}(効果で出す側の唯一の入口)と
     * {@code GameService.summonFromGrave}(召喚する側)。
     *
     * <p><b>「自分の」なので相手のリーダーは見ない。</b> 蘇生は自分の墓地から自分の場へ行う
     * 操作しか存在しないため、墓地の持ち主 = 出たミニオンの持ち主 = {@code ctx.owner()} である。
     * ロロイヨ伯爵の発火口({@link #fireAnyMinionEntered})が両者を回すのと対称ではないが、
     * これはテキストの「自分の」の有無から来る意図した非対称である(裁定156(2))。
     *
     * <p>修正の期限は {@code THIS_TURN} である。「そのターン」と書いてあるとおりで、
     * 既存の期限管理({@code expireThisTurnModifiers})がそのまま担う ——
     * <b>常在だが保存しない形ではない</b>。アルキンティスのような「評価するたびに場を見る」常在と違い、
     * これは<b>登場という一度きりの出来事に反応して修正を配る</b>効果であり、
     * 墓守が場を離れても配り終えた修正は残る(突風のまとめ役と同じ性質)。
     *
     * @param ctx ctx.source() に墓地から出たミニオンが入る
     */
    public void fireMinionEnteredFromGrave(EffectContext ctx) {
        MinionInstance entered = ctx.source();
        if (entered == null) {
            return;
        }
        if (GRAVE_DANCER_LEADER.equals(ctx.owner().getLeader().id())) {
            entered.addModifier(new StatModifier(StatModifier.Stat.ATTACK, StatModifier.Operation.ADD,
                    1, StatModifier.Duration.THIS_TURN, GRAVE_DANCER_LEADER));
            ctx.room().addLog("【演舞の墓守】: 墓地から出た【%s】の攻撃力が+1(このターン)"
                    .formatted(entered.getMaster().name()));
        }
        // ★Batch 53: リボーンライヴ・ノア(QTE-M-DARK-30)
        // 「【常在】自分のミニオンが墓地から場に出た時そのミニオンは【突進】を得る」。
        // ★演舞の墓守が<b>リーダー</b>の常在であるのに対し、こちらは<b>場のミニオン</b>の常在である。
        //   同じ発火口に2つの読み手が並ぶのは、発火口が「出来事」を表しているからであって
        //   「リーダーの能力」を表しているからではない(裁定98: 名前は性質に付ける)。
        // ★<b>ノア自身の【召喚時】で出す3体にも乗る</b>(マスター裁定) ——
        //   【召喚時】はノアが場に出た後に発動するので、この判定の時点でノアは場に居る。
        // ★【突進】は「得る」なので恒久の付与である(ギガマウス・バイトと同じ。
        //   意味を持つのは出したターンだけだが、書かれていない期限を足さない)
        if (ctx.owner().getMinionZone().stream().anyMatch(m -> NOA.equals(m.getMaster().id()))
                && !entered.hasKeyword(Keyword.RUSH)) {
            entered.grantKeyword(Keyword.RUSH);
            ctx.room().addLog("【リボーンライヴ・ノア】: 墓地から出た【%s】が【突進】を得ました"
                    .formatted(entered.getMaster().name()));
        }
    }

    /**
     * 「カードが場以外から自分の墓地に置かれた」イベントの処理(★Batch 50)。
     *
     * <blockquote>カムバックキーパー(QTE-M-DARK-35):
     * 場以外から自分の墓地に置かれたときに墓地からこのカードを場に出す。【守護】</blockquote>
     *
     * <h2>★なぜ TriggerType を足さなかったか(裁定194 の2度目の適用)</h2>
     *
     * 計画書(`notes/ver11-migration-plan.md` 2-1)は、このイベントを
     * 「P2 で足す3つの TriggerType の3つ目」と見込んでいた。使い手を読んで、また外れた。
     *
     * <p>{@link #fire(TriggerType, MinionInstance, EffectContext)} は
     * <b>場に居るミニオンの表を引く</b>仕組みである。ところがカムバックキーパーが反応するのは
     * <b>自分が墓地に置かれた瞬間</b>であり、そのとき場には居ない。
     * トリガー型として登録しても、引く相手が居ないので永久に発火しない。
     * 器の形は「場に限定されないゾーン横断トリガー」(設計判断15)であり、
     * これは黄泉還る水龍の {@link #fireManaLeft(EffectContext)} と同じ形である。
     *
     * <h2>★反応するのはこのカード自身が置かれたときだけ</h2>
     *
     * テキストは「(何かが)場以外から自分の墓地に置かれたとき」とも読めるが、
     * <b>主語はこのカード自身である</b>(マスター裁定203)。したがって
     * 置かれたカードのIDを引数で受け取り、それが自分でなければ何もしない ——
     * 「墓地にあれば何かが捨てられるたびに戻ってくる」形にはしない。
     *
     * <h2>★「場以外から」の担保</h2>
     *
     * この発火口を呼ぶのは {@code GameActions.putIntoTrashFromElsewhere} 1つだけであり、
     * 場を離れる処理({@code leaveFieldByDestruction} → {@code sendToTrashOrRestore})は
     * <b>この入口を通らない</b>。したがって「場で破壊されたときには戻ってこない」が
     * 条件分岐ではなく<b>構造で</b>決まる。
     *
     * @param putCardId 墓地に置かれたカードのID
     */
    public void fireCardPutIntoTrashFromElsewhere(EffectContext ctx, String putCardId) {
        if (!COMEBACK_KEEPER.equals(putCardId)) {
            return;
        }
        if (ctx.actions().isFieldEntryBlocked(ctx.room(), ctx.owner())) {
            ctx.room().addLog("【カムバックキーパー】: 場に出られないため戻れませんでした");
            return;
        }
        if (ctx.actions().reviveFromGrave(ctx.room(), ctx.owner(), COMEBACK_KEEPER) != null) {
            ctx.room().addLog("【カムバックキーパー】: 墓地から場に戻りました");
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

        // 相打ちの咎人: 以下を2回行う。自分のリーダーに2ダメージ、相手のリーダーに2ダメージ
        // ★Batch 55(区分3a): 相互ダメージ 1→2(rework-triage.md)
        register("QTE-M-FIRE-19", TriggerType.ON_SUMMON, ctx -> {
            for (int i = 0; i < 2; i++) {
                ctx.actions().damageLeader(ctx.room(), ctx.owner(), 2, "QTE-M-FIRE-19");
                ctx.actions().damageLeader(ctx.room(), ctx.opponent(), 2, "QTE-M-FIRE-19");
            }
        });

        // ★Batch 58(区分5): 背水の烈火使い(QTE-M-FIRE-7)。
        // 旧: 「【召喚時】手札をすべて捨てる。」(【守護】持ちの4/3/5に重いデメリット)
        // 新: 「【守護】」のみ —— 誘発効果が丸ごと消え、素の【守護】ミニオンになった。
        // ★<b>登録を消すのが実装である。</b>【守護】はテキストから付く(裁定158)ので、
        //   ここに何も書かないことがそのまま新本文の姿になる。
        //   report_effects.py も「効果の文が無いカード」として数えるため未実装には計上されない。

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

        // 武具昇華の炎: ウェポンを1枚破壊する。そうしたら自分のリーダーを2回復
        // ★Batch 56(区分3b): 旧本文の「自分の」が消えた。裁定156(2)(「自分の」の
        // 省略は両者を見る)により相手のウェポンも対象にする(聖光の武装解除と同じ形)。
        targetSpecs.put("QTE-M-FIRE-24", TargetSpec.of(
                Requirement.upTo(Kind.WEAPON, Side.ANY, 1, "破壊するウェポンを選んでください(いなければ確定)")));
        spellEffects.put("QTE-M-FIRE-24", ctx -> {
            boolean destroyed = false;
            for (var owner : ctx.targets().get(0).weapons()) {
                destroyed |= ctx.actions().destroyOwnWeapon(ctx.room(), owner);
            }
            if (destroyed) {
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

        // 再起の炎陣: 1枚捨てる。そうしたら2枚引く。【還元】
        // ★Batch 55(区分3a): ドロー 1→2(rework-triage.md)。【還元】はテキストから自動で付く
        targetSpecs.put("QTE-M-FIRE-26", TargetSpec.of(Requirement.of(
                Kind.HAND, Side.SELF, 1, true, "捨てるカードを選んでください")));
        spellEffects.put("QTE-M-FIRE-26", ctx -> {
            var selection = ctx.targets().get(0);
            if (selection.isEmpty()) {
                return;
            }
            selection.handCardIds().forEach(
                    id -> ctx.actions().putIntoTrashFromElsewhere(ctx.room(), ctx.owner(), id));
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 2);
        });

        // 血の対価: 手札を1枚捨てる。そうしたら4回復
        // ★Batch 55(区分3a): 回復 3→4(rework-triage.md)
        targetSpecs.put("QTE-M-FIRE-25", TargetSpec.of(Requirement.of(
                Kind.HAND, Side.SELF, 1, true, "捨てるカードを選んでください")));
        spellEffects.put("QTE-M-FIRE-25", ctx -> {
            var selection = ctx.targets().get(0);
            if (selection.isEmpty()) {
                return;
            }
            selection.handCardIds().forEach(
                    id -> ctx.actions().putIntoTrashFromElsewhere(ctx.room(), ctx.owner(), id));
            ctx.actions().healLeader(ctx.room(), ctx.owner(), 4, "QTE-M-FIRE-25");
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

        // 命を削る烈火: 自分のリーダーに3ダメージ。相手の場のミニオンすべてに3ダメージ
        // ★Batch 55(区分3a): 全体ダメージ 2→3(rework-triage.md)
        spellEffects.put("QTE-M-FIRE-11", ctx -> {
            ctx.actions().damageLeader(ctx.room(), ctx.owner(), 3, "QTE-M-FIRE-11");
            List.copyOf(ctx.opponent().getMinionZone()).forEach(
                    m -> ctx.actions().damageMinion(ctx.room(), ctx.opponent(), m, 3));
        });

        // 命喰いの火種: 自分のリーダーに4ダメージ。その後カードを2枚引く。【還元】
        // ★Batch 55(区分3a): 自傷 3→4(rework-triage.md)。【還元】はテキストから自動で付く
        spellEffects.put("QTE-M-FIRE-27", ctx -> {
            ctx.actions().damageLeader(ctx.room(), ctx.owner(), 4, "QTE-M-FIRE-27");
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

        // 背水の炎壁: ターン中3回以上ダメージを受けていた場合1コストで出せる。これで出したとき1回復
        // ★Batch 55(区分3a): 特殊召喚コスト 0→1(rework-triage.md)。【守護】はテキストから自動で付く
        specialSummons.put("QTE-M-FIRE-21", new SpecialSummonSpec(
                (state, player, handIndex) -> player.getLeaderDamagedCountThisTurn() >= 3,
                1, TargetSpec.of(), ctx -> {
                },
                ctx -> ctx.actions().healLeader(ctx.room(), ctx.owner(), 1, "QTE-M-FIRE-21"),
                "このターン3回以上ダメージを受けている: 1コストで召喚し、追加で1回復します"));

        // 鳳凰神 ヴォルカニクスレヴォ: このターン、火文明のカードで累計5以上回復したとき1コストで出せる。
        // Ver.0.4 で判定基準が「回復した回数」から「累計回復量」に変わり、
        // さらに発生源が火文明のカードに限定された(発注者確認済み)。
        // 火文明は自傷でLPを削る文明であり、回復の上限20に頭打ちされにくいため、
        // 「実際に回復した量」を数える方式(GameActions.healLeader)と噛み合う
        // ★Batch 56(区分4): Ver1.1で代替コスト 0→1。【速攻】が明記されたが、これは
        // CardTextKeywords がテキストから自動で拾うのでコード側の変更は不要
        specialSummons.put("QTE-M-FIRE-22", new SpecialSummonSpec(
                (state, player, handIndex) -> player.getHealedAmountThisTurn(Civilization.FIRE) >= 5,
                1, TargetSpec.of(), ctx -> {
                }, ctx -> {
                },
                "このターン火文明のカードで累計5以上回復している: 1コストで召喚します"));

        // 覚醒の炎童: 自分のリーダーの体力が10以下のときコスト0にする。【召喚時】1回復
        // ★Batch 56(区分4): Ver1.1で「【召喚時】自分のリーダーの体力を1回復する」が追加。
        // 【知識】は本カード自身のキーワードとして別枠(fire()のON_ENTER自動処理が1ドローする)。
        // 【召喚時】は特殊召喚(この代替コスト)でも通常召喚でも発動する(ON_SUMMON。GameService参照)
        specialSummons.put("QTE-M-FIRE-20", SpecialSummonSpec.of(
                (state, player, handIndex) -> player.getLp() <= 10,
                TargetSpec.of(), ctx -> {
                },
                "体力10以下: 0コストで召喚します"));
        register("QTE-M-FIRE-20", TriggerType.ON_SUMMON,
                ctx -> ctx.actions().healLeader(ctx.room(), ctx.owner(), 1, "QTE-M-FIRE-20"));

        // ---- リーダー起動能力 ----

        // 傷痕の闘帝: 【起動：1】自分のリーダーに1ダメージ。そうしたら1枚ドローする
        // ★Batch 55: 旧本文は「起動能力(1ターンに1回):」としかコストを書いておらず、
        // 実装は0マナと決め打ちしていた。Ver1.1の【起動：1】で初めて値が定まった
        // (rework-triage.md 2章の食い違い)。
        leaderAbilities.put("QTE-M-FIRE-15", LeaderAbilitySpec.of(1, TargetSpec.of(), ctx -> {
            ctx.actions().damageLeader(ctx.room(), ctx.owner(), 1, "QTE-M-FIRE-15");
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
        }, "自分のリーダーに1ダメージ、1枚ドロー"));

        // ★Batch 55: 剛火の将(QTE-M-FIRE-1)の起動能力は Ver1.1 で本文から丸ごと消えた
        // (rework-triage.md 2章の食い違い)。新本文は「場にある【速攻】を持つカードのHP+2」
        // という常在効果だけを持つ。旧起動能力の登録をここに残すと、カードが持たない能力の
        // ボタンが盤面に押せてしまうため削除した。
        // ★Batch 58(区分5): 常在効果(HP+2)を実装し、割引の死んだコードを掃除した。
        // 常在の規則は StatCalculator.rushHpBonus、加算は MinionInstance.getMaxHp にある
        // (この表には載らない —— 誘発ではなく常在だからである)。
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
        // 自分のリーダーに2ダメージ、その後1回復。
        // ★Batch 55(区分3a): 自傷 1→2(rework-triage.md)。回復量は据え置き
        // 自己誘発を除外しないと無限ループになるため、発生源が炎鏡自身なら発動しない
        var weapon = ctx.owner().getEquippedWeapon();
        boolean mirrorEquipped = weapon != null && FLAME_MIRROR.equals(weapon.id());
        boolean ownTurn = ctx.owner().getPlayerId().equals(ctx.state().getTurnPlayerId());
        boolean byOtherCard = sourceCardId != null && !FLAME_MIRROR.equals(sourceCardId);
        if (mirrorEquipped && ownTurn && byOtherCard) {
            ctx.room().addLog("【反転の炎鏡】が反応した");
            ctx.actions().damageLeader(ctx.room(), ctx.owner(), 2, FLAME_MIRROR);
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

        // 冥府の禁皇: 【起動：1】自分の墓地のカードを1枚選び手札に戻す。
        //             そうした場合、山札の上から2枚を墓地に置く。
        // ★Batch 57(区分4): Ver1.1 で<b>参照ゾーンが「裏向きのマナ」から「墓地」へ変わり</b>、
        //   後半も「2枚引く」から「山札の上から2枚を墓地に置く」(セルフミル)へ変わった。
        //   資源を増やす能力から、墓地を回して墓地を肥やす能力へ性格が入れ替わっている。
        //   ★コストは 55 で 0→1 に直し済みで、ここでは触らない(同じカードを二度触る形)。
        // ★「1枚選び」なので対象指定を伴う起動能力である —— LeaderAbilitySpec は
        //   もともと TargetSpec を運べる(GameService.useLeaderAbility が validateTargets を通す)。
        //   これまで使い手が居なかっただけで、新しい仕組みは要らない。
        // ★墓地が空なら使用できない(状態を変える前に条件で弾く)。
        leaderAbilities.put("QTE-M-DARK-1", new LeaderAbilitySpec(1,
                TargetSpec.of(new Requirement(Kind.TRASH, Side.SELF, 1, false, false, List.of(),
                        "手札に戻すカードを墓地から選んでください")),
                ctx -> {
                    boolean returned = false;
                    for (String cardId : ctx.targets().get(0).trashCardIds()) {
                        returned |= ctx.actions().returnFromTrashToHand(ctx.room(), ctx.owner(), cardId);
                    }
                    // 「そうした場合」= 実際に手札へ戻せたときだけミルする
                    if (returned) {
                        ctx.actions().mill(ctx.room(), ctx.owner(), 2);
                    }
                },
                (state, player) -> !player.getTrash().isEmpty(),
                "墓地のカード1枚を手札に戻し、山札の上から2枚を墓地に置く"));

        // 黄泉の召喚主(QTE-M-DARK-15)は起動能力ではなく常在能力(サブフェイズの墓地召喚)。
        // ルールそのものを書き換えるため GameService.summonFromGrave が担当する

        // ---- ミニオン ----

        // 執念の暗殺者: 【召喚時】ミニオン1体に3ダメージ。【常在】ミニオンが破壊されるたび1枚引いてもよい
        // ★Batch 57(区分3b): Ver1.1 で監視の本文から「自分の」が消えた。
        // 裁定156(2)(「自分の」の省略は両者を見る)により、<b>相手のミニオンの破壊でも</b>
        // 1枚引けるようになった。watchOwnMinionDestroyed → watchAnyMinionDestroyed へ移す。
        // 【常在】の印が付いたが、これは「誘発ではなく置かれている間ずっと効く」ことの明示で
        // あり、監視という形そのものは変わらない(印刷上の整理)。
        targetSpecs.put("QTE-M-DARK-20", TargetSpec.of(
                new Requirement(Kind.MINION, Side.ANY, 1, true, false, List.of(),
                        "3ダメージを与えるミニオンを選んでください(自分のミニオンも選べます)")));
        register("QTE-M-DARK-20", TriggerType.ON_SUMMON, ctx -> ctx.targets().get(0).minions()
                .forEach(t -> ctx.actions().damageMinion(ctx.room(), t.owner(), t.minion(), 3)));
        watchAnyMinionDestroyed("QTE-M-DARK-20", (ctx, destroyedCardId) -> {
            // 「引いてもよい」= 山札が空でなければ引く(AutoChoice)
            if (AutoChoice.shouldDrawOptional(ctx.owner())) {
                ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
                ctx.room().addLog("【執念の暗殺者】が1枚ドロー");
            }
        });

        // ゾンストライカー: 【召喚時】墓地の「ゾンストライカー」を全て出す(ゾーン上限まで)。
        // 効果による「出す」なので【召喚時】は再発動しない(無限ループにならない)
        register("QTE-M-DARK-16", TriggerType.ON_SUMMON, ctx -> {
            // ★Batch 50: 墓地から場へ出す経路を reviveFromGrave に集約した(fireManaLeft と同じ理由)
            while (ctx.owner().getTrash().contains("QTE-M-DARK-16")
                    && !ctx.actions().isFieldEntryBlocked(ctx.room(), ctx.owner())) {
                if (ctx.actions().reviveFromGrave(ctx.room(), ctx.owner(), "QTE-M-DARK-16") == null) {
                    return;
                }
            }
        });

        // 腐敗の投擲者: 【召喚時】相手のミニオン1体に1ダメージ
        targetSpecs.put("QTE-M-DARK-17", TargetSpec.of(
                new Requirement(Kind.MINION, Side.OPPONENT, 1, true, false, List.of(),
                        "1ダメージを与える相手のミニオンを選んでください")));
        register("QTE-M-DARK-17", TriggerType.ON_SUMMON, ctx -> ctx.targets().get(0).minions()
                .forEach(t -> ctx.actions().damageMinion(ctx.room(), t.owner(), t.minion(), 1)));

        // 墓場の怨念集合体: 【守護】【召喚時】墓地のスペルを1枚手札に加える。
        // 墓地のスペル以外1枚につき Cost-1 / Attack+1(どちらも StatCalculator)。
        // ★Batch 57(区分3b): Ver1.1 で【守護】(テキストから自動抽出)と Cost-1 が付いた。
        //   ここ(ON_SUMMON)の実装は無変更である。
        targetSpecs.put("QTE-M-DARK-22", TargetSpec.of(
                new Requirement(Kind.TRASH, Side.SELF, 1, true, false, List.of(Filter.SPELL_CARD),
                        "手札に加えるスペルを墓地から選んでください")));
        register("QTE-M-DARK-22", TriggerType.ON_SUMMON, ctx -> ctx.targets().get(0).trashCardIds()
                .forEach(id -> ctx.actions().returnFromTrashToHand(ctx.room(), ctx.owner(), id)));

        // 不滅のネクロマンサー: 自分の他のミニオンが破壊されるたび、裏向きマナ1枚を破壊して
        // そのミニオンを蘇生し【突進】を付与してもよい。
        // 「してもよい」の判断はAutoChoice。マナを無駄にしないよう、蘇生できる見込みを先に確かめる
        watchOwnMinionDestroyed("QTE-M-DARK-5", (ctx, destroyedCardId) -> {
            // ★Batch 53: 「場が満杯か」ではなく「場に出られるか」を先に見る ——
            // 《英霊・コレキ》で出られないときに裏向きマナだけを失うのを避けるためである
            if (!AutoChoice.shouldRevivePayingMana(ctx.owner())
                    || ctx.actions().isFieldEntryBlocked(ctx.room(), ctx.owner())
                    || !ctx.owner().getTrash().contains(destroyedCardId)
                    || ctx.actions().isCheatIntoFieldBlocked(destroyedCardId)) {
                return;
            }
            if (ctx.actions().destroyFaceDownMana(ctx.room(), ctx.owner(), 1) == 0) {
                return;
            }
            // ★Batch 50: 「場の末尾を取る」形をやめ、蘇生したミニオンの実体を受け取る。
            // 末尾は ON_ENTER の中でさらに場が増えると別人になりうる(49 設計解説 2-3)
            MinionInstance revived = ctx.actions().reviveFromGrave(ctx.room(), ctx.owner(), destroyedCardId);
            if (revived != null) {
                revived.grantKeyword(Keyword.RUSH);
                ctx.room().addLog("【不滅のネクロマンサー】が【%s】を蘇生し【突進】を付与"
                        .formatted(cards.findById(destroyedCardId).name()));
            }
        });

        // ボーン・コレクター: このミニオンが戦闘で破壊された時1枚引く(効果破壊では引かない)
        register("QTE-M-DARK-6", TriggerType.ON_DESTROYED_BY_COMBAT,
                ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 1));

        // ★Batch 58(区分5): カース・ボーン(QTE-M-DARK-2)。
        // 旧: 「【召喚時】自分のマナゾーンの表向きのカード1枚を、裏向きにする。
        //      裏向きにできなかったとき破壊する。」(裏向きマナを能動的に作る1/2/1)
        // 新: 「【召喚時】自分のミニオンを1体破壊する。破壊したミニオンのコストと同じ数
        //      山札の上から墓地に置く。【還元】」(2/1/1)
        // ★<b>参照するゾーンが裏向きマナ → 自分の場に変わり、産物が裏向きマナ → 墓地に変わった。</b>
        //   闇文明の資源が「裏向きマナ」から「墓地」へ寄った Ver1.1 全体の流れと同じ向きである
        //   (《冥府の禁皇》《マナを貪る怨霊》も同じ方向に書き換わっている)。
        // ★破壊する1体は<b>本人が選ぶ</b>(裁定192)。候補には自分自身が含まれる ——
        //   解決の時点でカース・ボーンは場に居るので、他に何も居なければ自分を破壊する。
        // ★数えるのは<b>印刷コスト</b>である。場のミニオンには動的コストの概念が無い
        //   (コストが動くのは手札にある間だけ。StatCalculator の《透キ通ル・アヤカシ》の注)。
        // ★【還元】はテキストから付く(裁定158)。自分を破壊した場合、
        //   カース・ボーン自身は墓地ではなく裏向きでマナへ行く。
        register("QTE-M-DARK-2", TriggerType.ON_SUMMON, ctx -> {
            List<MinionInstance> candidates = ctx.owner().getMinionZone();
            if (candidates.isEmpty()) {
                return; // 構造上ここには来ない(自身が場に居る)が、入口の前提を書き残す
            }
            if (candidates.size() == 1) {
                resolveCurseBoneSacrifice(ctx, candidates.get(0));
                return;
            }
            ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.one(
                    PendingChoice.Kind.MINION,
                    candidates.stream().map(MinionInstance::getInstanceId).toList(),
                    ResumePoint.CURSE_BONE_SACRIFICE,
                    "【カース・ボーン】: 破壊する自分のミニオンを1体選んでください"));
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
                if (ctx.actions().reviveFromGrave(ctx.room(), ctx.owner(), cardId) != null) {
                    revived++;
                }
            }
        });

        // 裏切りの魔女: 【召喚時】裏向きマナが1枚以上なら、相手のコスト3以下のミニオン1体を破壊
        // ★55(区分3a): 裏向きマナ 2枚以上→1枚以上
        targetSpecs.put("QTE-M-DARK-4", TargetSpec.of(
                new Requirement(Kind.MINION, Side.OPPONENT, 1, true, false, List.of(Filter.COST_3_OR_LESS),
                        "破壊する相手のコスト3以下のミニオンを選んでください(裏向きマナ1枚以上が必要)")));
        register("QTE-M-DARK-4", TriggerType.ON_SUMMON, ctx -> {
            if (ctx.owner().getFaceDownManaCount() < 1) {
                ctx.room().addLog("裏向きのマナが無いため【裏切りの魔女】の効果は発動しません");
                return;
            }
            ctx.targets().get(0).minions()
                    .forEach(t -> ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion()));
        });

        // 獄門の裁定者: 【守護】このミニオンがダメージを受けた時、相手のリーダーに2ダメージ。
        // ★Batch 57(区分4): Ver1.1 で「このミニオンはリーダーを攻撃できない」が追加された。
        // 9/9/9(旧 9/5/7)という破格の体格を、リーダーを殴れない壁に閉じ込めるための制約である。
        // 攻撃の可否は RuleGuards.minionAttackDenial にある(不動の絶対神ガイア・
        // 創世神 ゾディアックアイリスと同じ形) —— ここには登録を持たない。
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

        // ★Batch 57: スペルとウェポンは registerDarkSpellsAndWeapons() へ分けた。
        // 闇文明の登録が1メソッドで300行を超え、tools/check_structure.py の
        // 「飲み込みの兆候」の閾値に触れたためである。区切りは元からあった
        // 「---- スペル ----」の見出しそのままで、中身は動かしていない。
        registerDarkSpellsAndWeapons();
    }

    /** 闇文明のスペルとウェポン(★Batch 57 で registerDarkCards から切り出した) */
    private void registerDarkSpellsAndWeapons() {

        // ---- スペル ----

        // マナを貪る怨霊: 表向きのマナ2枚を裏向きにする。3枚引く
        playConditions.put("QTE-M-DARK-11",
                (state, player) -> player.getManaZone().stream().anyMatch(ManaCard::isFaceUp));
        spellEffects.put("QTE-M-DARK-11", ctx -> {
            ctx.actions().turnManaFaceDown(ctx.room(), ctx.owner(), 2);
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 3);
        });

        // 墓穴の呪い: 山札の上から2枚を墓地に置く。墓地の枚数以下のHPを持つミニオンを全て破壊。
        // ★55(区分3a): セルフミル 3→2枚。判定は2枚を置いた後(発注者確認済み)。自分も巻き込む
        spellEffects.put("QTE-M-DARK-24", ctx -> {
            ctx.actions().mill(ctx.room(), ctx.owner(), 2);
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

        // 冥府への道: 相手のミニオンを2体選び破壊する
        // ★55(区分3a): 破壊 1体→2体。playConditionsも2体以上へ揃える(1体なら使用不可。
        // 既存の「N体選ぶ」系スペルと同じ、要求数=最小要求の形)
        playConditions.put("QTE-M-DARK-26",
                (state, player) -> state.opponentOf(player.getPlayerId()).getMinionZone().size() >= 2);
        targetSpecs.put("QTE-M-DARK-26", TargetSpec.of(
                new Requirement(Kind.MINION, Side.OPPONENT, 2, false, false, List.of(),
                        "破壊する相手のミニオンを2体選んでください")));
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

        // 禁忌の代償: 裏向きマナ1枚を破壊する。その後、自分の墓地からコスト4以下のミニオンを
        //             1体選び場に出す。
        // ★Batch 57(区分4): Ver1.1 で後半が「相手のミニオン1体を破壊」から
        //   <b>自分の墓地からの蘇生</b>へ丸ごと置き換わった(除去から展開へ)。
        // ★使用条件も対象も入れ替わる。「代償を払えなければ使用できない」の形は据え置きで、
        //   裏向きマナに加えて<b>蘇生先が墓地に居ること</b>も条件に加える ——
        //   対象指定が必須(optional=false)である以上、候補0では選び切れないためである
        //   (旧本文が「相手のミニオンが居ること」を条件にしていたのと同じ理屈)。
        // ★「場に出す」は召喚ではないため reviveFromGrave を通す(【召喚時】は発動しない)。
        playConditions.put("QTE-M-DARK-10", (state, player) -> player.getFaceDownManaCount() > 0
                && player.getTrash().stream().anyMatch(id -> {
                    CardMaster m = cards.findById(id);
                    return m.type() == CardType.MINION && m.cost() != null && m.cost() <= 4;
                }));
        targetSpecs.put("QTE-M-DARK-10", TargetSpec.of(
                new Requirement(Kind.TRASH, Side.SELF, 1, false, false,
                        List.of(Filter.MINION_CARD, Filter.COST_4_OR_LESS),
                        "場に出すコスト4以下のミニオンを墓地から選んでください")));
        spellEffects.put("QTE-M-DARK-10", ctx -> {
            ctx.actions().destroyFaceDownMana(ctx.room(), ctx.owner(), 1);
            ctx.targets().get(0).trashCardIds()
                    .forEach(id -> ctx.actions().reviveFromGrave(ctx.room(), ctx.owner(), id));
        });

        // 死者蘇生: 好きな数の自分のミニオンを破壊してもよい(その数だけコスト-1)。
        // 墓地からミニオン1体を【突進】付きで蘇生する。
        // ★Batch 57(区分3b)実装変更なし: Ver1.1 の差は「好きな数<b>の</b>」「破壊した数<b>だけ</b>」の
        //   送りがなと読点が落ちただけであり、条件も数量も同じである(区分の境目の誤りの実例。
        //   rework-triage.md 1-2「仕分けの境目には数枚の誤りが混じる」)。
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
                // ★Batch 50: 「場の末尾を取る」形をやめ、蘇生したミニオンの実体を受け取る
                MinionInstance revived = ctx.actions().reviveFromGrave(ctx.room(), ctx.owner(), cardId);
                if (revived != null) {
                    revived.grantKeyword(Keyword.RUSH);
                    ctx.room().addLog("【死者蘇生】が【%s】を蘇生し【突進】を付与"
                            .formatted(cards.findById(cardId).name()));
                    break;
                }
            }
        });

        // 絶望の連鎖: 自分のミニオン1体を破壊する。そうしたら相手のミニオン1体を破壊する。
        //             このターンミニオンが3体以上破壊されていたならカードを1枚引く。
        // ★Batch 57(区分4): Ver1.1 で2つ足された。
        //   (1) 「そうしたら」—— 相手側の破壊は<b>自分側の破壊が成立したときだけ</b>行う。
        //       自分の破壊は必須の対象なので普段は必ず成立するが、【守護】や置換で
        //       場を離れないことがありうる以上、条件は本文どおり書いておく。
        //   (2) 「このターンミニオンが3体以上破壊されていたなら1ドロー」——
        //       「自分の」と書いていないので<b>両者の合計</b>を数える(裁定156(2))。
        //       数える先は GameState のターン内カウンタである
        //       (裁定185・裁定205。NEMれぬ夜のドリーミーと同じ値を読む)。
        //       ★このスペル自身が破壊した2体も当然その中に入る —— 判定は解決の最後に行う。
        playConditions.put("QTE-M-DARK-9", (state, player) -> !player.getMinionZone().isEmpty());
        targetSpecs.put("QTE-M-DARK-9", TargetSpec.of(
                new Requirement(Kind.MINION, Side.SELF, 1, false, false, List.of(),
                        "破壊する自分のミニオンを選んでください"),
                new Requirement(Kind.MINION, Side.OPPONENT, 1, true, false, List.of(),
                        "破壊する相手のミニオンを選んでください")));
        spellEffects.put("QTE-M-DARK-9", ctx -> {
            boolean destroyedOwn = false;
            for (ResolvedTargets.TargetedMinion t : ctx.targets().get(0).minions()) {
                ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion());
                destroyedOwn |= !t.owner().getMinionZone().contains(t.minion());
            }
            if (destroyedOwn) {
                ctx.targets().get(1).minions()
                        .forEach(t -> ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion()));
            } else {
                ctx.room().addLog("【絶望の連鎖】: 自分のミニオンが破壊されなかったため相手のミニオンは破壊しません");
            }
            if (ctx.state().getMinionsDestroyedThisTurn() >= 3) {
                ctx.room().addLog("【絶望の連鎖】: このターン3体以上のミニオンが破壊されているため1枚ドロー");
                ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
            }
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

        // 聖域の案内人: 【知識】自分の場に「他の」【守護】を持つミニオンがいるなら、もう一度【知識】を行う。
        // 1回目のドローはfire()が自動処理する(自身がKNOWLEDGEを持つため)。ここでは2回目だけを扱う。
        // 守護の有無は「登場時」(ON_ENTER)に判定し、召喚か効果で出したかを問わない(発注者確認済み)
        // ★Batch 56(区分3b): Ver1.1 でこのカード自身に【守護】が付き、「他の」が明記された
        // (rework-triage.md 区分3b)。自身を除外しないと常に真になってしまうため、
        // ctx.source()(自身)を除いて数える
        register("QTE-M-LIGHT-3", TriggerType.ON_ENTER, ctx -> {
            boolean hasOtherGuard = ctx.owner().getMinionZone().stream()
                    .filter(m -> m != ctx.source())
                    .anyMatch(m -> m.hasKeyword(Keyword.GUARD));
            if (hasOtherGuard) {
                ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
                ctx.room().addLog("【聖域の案内人】: 他に【守護】がいるためもう一度【知識】");
            }
        });

        // 天界の守護神 ゾディアック: 【召喚時】相手のウェポンを1つ選び破壊する。【守護】。
        // このミニオンが場にいる限り、相手のリーダーは攻撃できない。
        // ★Batch 56(区分4): Ver1.1 で【召喚時】のウェポン破壊が追加された
        // (rework-triage.md 区分4)。「相手のリーダーは攻撃できない」の常在部分は
        // 既にRuleGuards.minionAttackDenial/leaderAttackDenialのZODIAC判定として
        // 実装済みであり、こちらは触らない。相手がウェポンを装備していなければ空撃ち
        targetSpecs.put("QTE-M-LIGHT-8", TargetSpec.of(
                Requirement.upTo(Kind.WEAPON, Side.OPPONENT, 1,
                        "破壊する相手のウェポンを選んでください(いなければ確定)")));
        register("QTE-M-LIGHT-8", TriggerType.ON_SUMMON, ctx -> {
            boolean destroyed = false;
            for (var owner : ctx.targets().get(0).weapons()) {
                destroyed |= ctx.actions().destroyOwnWeapon(ctx.room(), owner);
            }
            if (!destroyed) {
                ctx.room().addLog("破壊する相手のウェポンがなかった");
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

        // ホーリー・シグナル: 相手の場で最も攻撃力の高いミニオン1体と最も体力の低いミニオン1体を破壊。
        // ★Batch 56(区分4): Ver1.1 で「最も体力の低いミニオン1体」の同時破壊が追加された
        // (rework-triage.md 区分4)。
        // ★最低体力側はTargetSpec.Requirementにせず、AutoChoice.lowestCurrentHpで自動決定する
        // (AutoChoice.javaのJavadoc参照)。理由: GameService.validateTargetsは1枚のカードの
        // 検証中、Requirementをまたいでusedひとつの Set<String> usedMinionIds を使い回すため、
        // 2つのRequirementにすると「同じミニオンが両方の条件を満たす」ケース
        // (例: 相手の場が1体しかいない)で「同じミニオンを重複して選べません」の例外になり、
        // カードが使用不能になってしまう。最高攻撃力側だけはタイのとき実質選択の意味があるため
        // (同値が複数いれば選ばせる)、引き続きプレイヤーが選ぶRequirementのまま残した。
        // 【潜伏】持ちであっても破壊できる(発注者確認済み。IGNORES_STEALTHで潜伏の対象化禁止を上書き)
        playConditions.put("QTE-M-LIGHT-10",
                (state, player) -> !state.opponentOf(player.getPlayerId()).getMinionZone().isEmpty());
        targetSpecs.put("QTE-M-LIGHT-10", TargetSpec.of(
                new Requirement(Kind.MINION, Side.OPPONENT, 1, false, false,
                        List.of(Filter.HIGHEST_ATTACK_OPPONENT, Filter.IGNORES_STEALTH),
                        "相手の場で最も攻撃力の高いミニオンを選んでください")));
        spellEffects.put("QTE-M-LIGHT-10", ctx -> {
            // 両方の対象を、効果解決前(=まだ何も壊れていない)盤面で先に確定してから破壊する。
            // 片方の破壊で盤面が変わり、もう片方の判定が狂うのを防ぐため
            List<MinionInstance> beforeOpp = new ArrayList<>(ctx.opponent().getMinionZone());
            MinionInstance lowestHp = AutoChoice.lowestCurrentHp(beforeOpp);

            List<MinionInstance> toDestroy = new ArrayList<>();
            ctx.targets().get(0).minions().forEach(t -> toDestroy.add(t.minion()));
            // 同じミニオンが両方の条件を満たす場合は1回だけ破壊する(重複して足さない)
            if (lowestHp != null && toDestroy.stream()
                    .noneMatch(m -> m.getInstanceId().equals(lowestHp.getInstanceId()))) {
                toDestroy.add(lowestHp);
            }
            toDestroy.forEach(m -> ctx.actions().destroyMinion(ctx.room(), ctx.opponent(), m));
        });

        // 聖光の武装解除: ウェポンを1枚破壊する。そうしたらカードを1枚引く。【還元】。
        // 自分のウェポンも選べ、誰も装備していなければ空撃ちになる(発注者確認済み)
        // ★Batch 56(区分3b): Ver1.1 で「そうしたらカードを1枚引く」が追加された。
        // 武具昇華の炎(QTE-M-FIRE-24)と同じ「破壊できたら」の条件付き形
        targetSpecs.put("QTE-M-LIGHT-26", TargetSpec.of(
                Requirement.upTo(Kind.WEAPON, Side.ANY, 1, "破壊するウェポンを選んでください(いなければ確定)")));
        spellEffects.put("QTE-M-LIGHT-26", ctx -> {
            boolean destroyed = false;
            for (var owner : ctx.targets().get(0).weapons()) {
                destroyed |= ctx.actions().destroyOwnWeapon(ctx.room(), owner);
            }
            if (destroyed) {
                ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
            } else {
                ctx.room().addLog("破壊するウェポンがなかった");
            }
        });

        // 神の福音: 手札から光文明の【守護】ミニオンを最大2体、コストを支払わず場に出す。
        // 出した数だけ引く。ゾーンの空きが足りなければ出せた数だけ出し、その数だけ引く(発注者確認済み)
        targetSpecs.put("QTE-M-LIGHT-12", TargetSpec.of(Requirement.upTo(Kind.HAND, Side.SELF, 2,
                "コストを支払わず場に出す光文明の【守護】ミニオンを2体まで選んでください",
                Filter.LIGHT_CIVILIZATION, Filter.GUARD)));
        spellEffects.put("QTE-M-LIGHT-12", ctx -> {
            int summoned = 0;
            for (String id : ctx.targets().get(0).handCardIds()) {
                if (ctx.actions().isFieldEntryBlocked(ctx.room(), ctx.owner())) {
                    ctx.owner().getHand().add(id); // 出せなかった分は手札に戻す
                    continue;
                }
                // ★Batch 50: 「出した数だけ引く」ので、実際に場へ出たかを戻り値で見る
                // (光霊・モアニールに山札の下へ置き換えられた場合は出ていない)
                if (ctx.actions().putIntoFieldByEffect(ctx.room(), ctx.owner(), id) != null) {
                    summoned++;
                }
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

        // 風神ヴァーユ: 【特殊召喚】自分の墓地に風文明を持つカードが6枚以上のとき、
        // このカードを手札から1コストで出せる(代替コストなし。条件のみ)
        // ★Batch 56(区分4): Ver1.1 で条件が「【守護】持ちが4枚以上」から
        // 「風文明のカードが6枚以上」に変わった(rework-triage.md 区分4)
        specialSummons.put("QTE-M-WIND-21", new SpecialSummonSpec(
                (state, player, handIndex) -> player.getTrash().stream()
                        .filter(id -> cards.findById(id).civilization() == Civilization.WIND).count() >= 6,
                1,
                TargetSpec.of(),
                ctx -> {
                },
                ctx -> {
                },
                "自分の墓地に風文明のカードが6枚以上: コスト1で召喚します"));

        // 嵐の守り手: 【守護】【特殊召喚】自分の場に体力3以下のミニオンがちょうど3体のとき、
        // このカードを手札から1コストで出せる
        // ★Batch 56(区分3b): Ver1.1 でコスト 0→1、条件が「体力3以上が3体以上」から
        // 「体力3以下がちょうど3体」に反転した(rework-triage.md 区分3b)。【守護】が付いた
        specialSummons.put("QTE-M-WIND-19", new SpecialSummonSpec(
                (state, player, handIndex) -> player.getMinionZone().stream()
                        .filter(m -> m.getCurrentHp() <= 3).count() == 3,
                1,
                TargetSpec.of(),
                ctx -> {
                },
                ctx -> {
                },
                "自分の場に体力3以下のミニオンがちょうど3体: コスト1で召喚します"));

        // ★Batch 58(区分5): ストーム・カイザー(QTE-M-WIND-8)。
        // 旧: 「このターン中に自分がカードを4枚以上使用している時、コストを支払わずに場に出せる。」
        // 新: 「【速攻】/【特殊召喚】(このターン中に自分がカードを5枚以上使用している時、
        //       コストを1払って場に出せる)」
        // 変わったのは3点 —— 条件が 4枚 → <b>5枚</b>、代替コストが 0 → <b>1</b>、
        // そして【速攻】が付いた(印刷コストも 5 → 7 に上がっている)。
        // ★【速攻】はテキストから付く(裁定158)のでここには書かない。
        // ★使用枚数の数え上げは自身を含まない(裁定1)ため、「5枚以上」はそのまま >= 5 である。
        // ★代替コストが0でない先例は《極炎竜 ヴォルカニクス》(mpCost=1)であり、
        //   SpecialSummonSpec は最初からその形を持っている(新しい仕組みは要らない)。
        specialSummons.put("QTE-M-WIND-8", new SpecialSummonSpec(
                (state, player, handIndex) -> player.getCardsUsedThisTurn() >= 5,
                1,
                TargetSpec.of(),
                ctx -> {
                },
                ctx -> {
                },
                "このターン中に自分がカードを5枚以上使用: コスト1で召喚します"));

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

        // ★Batch 58: スペルとウェポンは registerWindSpellsAndWeapons() へ分けた。
        // 《風弾の跳弾》の書き換えでこのメソッドが 300 行を超え、check_structure.py が
        // △要確認を出したためである(Batch 57 の闇文明と同じ処置。中身は動かしていない)。
        registerWindSpellsAndWeapons();
    }

    private void registerWindSpellsAndWeapons() {

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

        // ★Batch 58(区分5): 風弾の跳弾(QTE-M-WIND-24)。
        // 旧: 「自分のミニオンを1体<b>手札に戻す</b>。そうしたら相手のミニオン1体に<b>2</b>ダメージ。
        //      このカードのコストを<b>+3</b>してもよい。そうした場合このカードを墓地に置く代わりに手札に戻す。」
        // 新: 「このカードのコストを<b>+2</b>してもよい。そうした場合このカードを墓地に置く代わり手札に戻す。
        //      自分のミニオンを1枚<b>破壊する</b>。そうしたら相手のミニオン1体に<b>3</b>ダメージ。」
        // 変わったのは3点 —— 自分側のコストが<b>バウンス → 破壊</b>(取り返しがつかなくなった)、
        // ダメージが 2 → 3、使い回しの追加コストが +3 → +2(安くなった)。
        // ★<b>本文の順序が入れ替わっているが、解決の順序は変わらない。</b>
        //   追加コストは使用宣言に付随する二者択一であり(EnhancedCostSpec の説明)、
        //   支払いは効果の解決より前に必ず終わっている。本文のどこに書かれていても同じである。
        // ★「そうしたら」は破壊が<b>実際に起きたか</b>を見る。《大天使 ミカエル》の
        //   「戦闘では破壊されない」は効果破壊を止めないが、《聖光の守護聖》の
        //   「相手の効果では破壊されない」は自分のミニオンには掛からない ——
        //   それでも条件として書いておくのは、本文が「そうしたら」と書いているからである
        //   (裁定217 の系。行える保証がないなら、行えたかを見る)。
        targetSpecs.put("QTE-M-WIND-24", TargetSpec.of(
                new Requirement(Kind.MINION, Side.SELF, 1, false, false, List.of(), "破壊する自分のミニオンを選んでください"),
                new Requirement(Kind.MINION, Side.OPPONENT, 1, false, false, List.of(), "3ダメージを与える相手のミニオンを選んでください")));
        enhancedCosts.put("QTE-M-WIND-24", new EnhancedCostSpec(2,
                "コストを+2して、このカードを墓地に置く代わりに手札に戻しますか？"));
        spellEffects.put("QTE-M-WIND-24", ctx -> {
            if (ctx.enhanced()) {
                ctx.owner().setPendingSpellDisposition(SpellDisposition.TO_HAND);
            }
            boolean destroyed = false;
            for (var t : ctx.targets().get(0).minions()) {
                ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion());
                destroyed |= !t.owner().getMinionZone().contains(t.minion());
            }
            if (!destroyed) {
                ctx.room().addLog("【風弾の跳弾】: 自分のミニオンを破壊できなかったため、ダメージは与えません");
                return;
            }
            ctx.targets().get(1).minions().forEach(
                    t -> ctx.actions().damageMinion(ctx.room(), t.owner(), t.minion(), 3));
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

        // 選択の追い風: カードを1枚引く。その後カードを1枚捨てても良い。
        // そうしたら追加でカードを1枚引く(候補が無ければ問い合わせ自体を出さない)
        // ★Batch 56(区分4): Ver1.1 で捨てるカードの「守護を持つ」限定が外れ、
        // 手札のどのカードでも良くなった(rework-triage.md 区分4)
        spellEffects.put("QTE-M-WIND-25", ctx -> {
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
            List<String> handPositions = new ArrayList<>();
            List<String> hand = ctx.owner().getHand();
            for (int i = 0; i < hand.size(); i++) {
                handPositions.add(String.valueOf(i));
            }
            if (handPositions.isEmpty()) {
                return;
            }
            ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.upTo(
                    PendingChoice.Kind.HAND, handPositions, 1, ResumePoint.TAILWIND_DISCARD,
                    "【選択の追い風】: カードを1枚捨てて、もう1枚引きますか？(任意)"));
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
                // ★Batch 52: 進化の下にあったカードも一緒に山札へ戻る(裁定154)
                toDeck.addAll(ctx.actions().underCardsForDeck(ctx.room(), t.owner(), t.minion()));
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
        if (ctx.actions().reviveFromGrave(ctx.room(), ctx.owner(), counterpartId) != null) {
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
    // ★Batch 49: Ver1.1 で追加された水文明のカード(P2 の2本目)。
    //
    // 水文明の新しいテーマは【潜伏】と【知識】を「数える」ことである ——
    // ロロイヨ伯爵は守護と潜伏の登場をドローに、潮獣ビシャカワは潜伏の数を回復に、
    // アルキンティスは知識の数をウェポンの攻撃力に変える。
    // 既存の水(手札の枚数を参照する: 知識の守護者・溢れ出る英知)と違い、
    // ここで数えるのは<b>盤面に並んだキーワードの数</b>である。
    //
    // ★ロロイヨ伯爵(QTE-M-WATER-29)はここに登録を持たない。
    //   常在の登場誘発は fireAnyMinionEntered に直接書いてある(豊穣の地霊主・ストクと同じ形)。
    // ★ギガマウス・バイトのコスト軽減とアルキンティスの攻撃力は StatCalculator にある。
    //   1枚のカードの実装が複数のクラスに分かれるのは想定内である(裁定180)。
    // ---------------------------------------------------------------
    private void registerWaterVer11Cards() {

        // ---- 海獣リューグー(QTE-M-WATER-35) ----
        // 「【知識】【突進】【召喚時】自分の場に【潜伏】を持つミニオンが居るならカードを1枚引く」
        //
        // 【知識】の1ドローは fire() が自動処理する(自身が KNOWLEDGE を持つため)。
        // ここで扱うのは【召喚時】の条件つき1ドローだけである(聖域の案内人と同じ分担)。
        // 自身は【潜伏】を持たないので、場を素朴に見て自分を数えてしまう心配はない
        register("QTE-M-WATER-35", TriggerType.ON_SUMMON, ctx -> {
            boolean hasStealth = ctx.owner().getMinionZone().stream()
                    .anyMatch(m -> m.hasKeyword(Keyword.STEALTH));
            if (hasStealth) {
                ctx.room().addLog("【海獣リューグー】: 自分の場に【潜伏】が居るため1ドロー");
                ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
            }
        });

        // ---- 潮獣ビシャカワ(QTE-M-WATER-36・スペル) ----
        // 「自分の【潜伏】の数自分のリーダーのHPを1回復を行う。」
        //
        // 数えるのは<b>自分の場</b>の【潜伏】ミニオンである(影潜む水刺客と同じ参照)。
        // 手札や墓地の潜伏は数えない —— 「自分の」に続く場所の指定が無いキーワードの数え上げは、
        // 既存カードがすべて場を指している(影潜む水刺客・双流の幻術師)。
        // 0体なら回復量0であり、スペルとしては空撃ちになる(使用は妨げない)
        spellEffects.put("QTE-M-WATER-36", ctx -> {
            int stealth = (int) ctx.owner().getMinionZone().stream()
                    .filter(m -> m.hasKeyword(Keyword.STEALTH))
                    .count();
            if (stealth <= 0) {
                ctx.room().addLog("【潮獣ビシャカワ】: 自分の場に【潜伏】が居ないため回復しません");
                return;
            }
            ctx.room().addLog("【潮獣ビシャカワ】: 【潜伏】%d体ぶん%d回復".formatted(stealth, stealth));
            ctx.actions().healLeader(ctx.room(), ctx.owner(), stealth, "QTE-M-WATER-36");
        });

        // ---- 潮獣コアンチ(QTE-M-WATER-37・スペル) ----
        // 「自分のリーダーのHPを2回復。カードを1枚引き、カードを1枚捨てる。」
        //
        // 捨てる対象は<b>引いた後の手札</b>から選ぶため、使用宣言時に選び終える TargetSpec では
        // 表現できない。アクア・サーチと同じ a9(割り込み選択)を使う。
        // 捨てるのは必須なので one(min=1)。山札切れ等で手札が空なら捨てようがない
        spellEffects.put("QTE-M-WATER-37", ctx -> {
            ctx.actions().healLeader(ctx.room(), ctx.owner(), 2, "QTE-M-WATER-37");
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
            if (ctx.owner().getHand().isEmpty()) {
                return;
            }
            List<String> handPositions = new ArrayList<>();
            for (int i = 0; i < ctx.owner().getHand().size(); i++) {
                handPositions.add(String.valueOf(i));
            }
            ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.one(
                    PendingChoice.Kind.HAND, handPositions, ResumePoint.TIDE_COANCHI_DISCARD,
                    "【潮獣コアンチ】: 捨てる手札を1枚選んでください"));
        });

        // ---- ギガマウス・バイト(QTE-M-WATER-38・スペル) ----
        // 「自分の手札の枚数このカードのコスト-1
        //   自分の手札から水文明のミニオンを3体場に出す。それは【突進】を得る。」
        //
        // ★コスト軽減(印刷15)は StatCalculator.effectiveCost にある。
        //   数える手札には<b>このカード自身を含む</b>(マスター裁定190)。
        // ★出す3体はプレイヤーが選ぶ(マスター裁定192)。手札の水ミニオンが3体未満なら
        //   居るだけ出す(マスター裁定191) —— Requirement.upTo が「あるだけ」を表す既存の形である。
        // ★効果による「出す」なので【召喚時】は発動せず、登場時(ON_ENTER)のみが発動する。
        // ★場の空きが足りず出せなかったぶんは手札に戻す(神の福音と同じ扱い)
        targetSpecs.put("QTE-M-WATER-38", TargetSpec.of(Requirement.upTo(Kind.HAND, Side.SELF, 3,
                "コストを支払わず場に出す水文明のミニオンを3体まで選んでください",
                Filter.WATER_CIVILIZATION, Filter.MINION_CARD)));
        spellEffects.put("QTE-M-WATER-38", ctx -> {
            int summoned = 0;
            for (String id : ctx.targets().get(0).handCardIds()) {
                // ★Batch 50: 「場が満杯か」を呼ぶ前に自分で見る形に揃えた(神の福音と同じ)。
                // putIntoFieldByEffect が null を返す理由が2つになったためである ——
                // 「場が満杯で出せなかった(カードは宙に浮いたまま)」と
                // 「光霊・モアニールが山札の下へ置き換えた(行き先は決まっている)」。
                // null だけを見て手札に戻すと、置換された場合にカードが2枚に増える
                if (ctx.actions().isFieldEntryBlocked(ctx.room(), ctx.owner())) {
                    ctx.owner().getHand().add(id); // 出せなかった分は手札に戻す
                    continue;
                }
                MinionInstance put = ctx.actions().putIntoFieldByEffect(ctx.room(), ctx.owner(), id);
                if (put == null) {
                    continue; // 登場が置換された(行き先は置換側が決めている)
                }
                // 【突進】は「得る」なので恒久の付与である(そのターン限定とは書かれていない)。
                // 実際に意味を持つのは出したターンだけだが、期限を勝手に足さない
                put.grantKeyword(Keyword.RUSH);
                summoned++;
            }
            ctx.room().addLog("【ギガマウス・バイト】: 水文明のミニオン%d体が【突進】を得て場に出ました"
                    .formatted(summoned));
        });
    }

    // ---------------------------------------------------------------
    // ★Batch 50: Ver1.1 で追加された闇文明のカード(P2 の3本目・前半)。
    //
    // 闇の新しいテーマは「墓地を出入り口として使うこと」である ——
    // 演舞の墓守は墓地から出てきたミニオンを強化し、カムバックキーパーは
    // 捨てられても自力で場へ戻り、サモンズライトは自分が壊れたときに次を呼ぶ。
    // 既存の闇(墓地と裏向きマナを<b>資源として使い潰す</b>)とは向きが逆で、
    // ここでの墓地は<b>通り道</b>である。
    //
    // ★演舞の墓守(QTE-M-DARK-29)とカムバックキーパー(QTE-M-DARK-35)は
    //   ここに登録を持たない。前者はリーダーの常在なので fireMinionEnteredFromGrave に、
    //   後者は「場に居ない間に反応する」ため fireCardPutIntoTrashFromElsewhere に直接書いてある。
    // ★1stL「NEMれぬ夜のドリーミー」の【常在】(破壊数ぶんのAttack)は StatCalculator にある。
    //   1枚のカードの実装が複数のクラスに分かれるのは想定内である(裁定180)。
    // ---------------------------------------------------------------
    private void registerDarkVer11Cards() {

        // ---- デビルズマイク(QTE-M-DARK-33) ----
        // 「攻撃時、相手のリーダーに1ダメージ」
        //
        // 攻撃対象を限定していないので、リーダーを殴っても他のミニオンを殴っても発動する
        // (不動の絶対神ガイアと同じ形)。1/1 のコスト1が毎ターン1点を足していく
        register("QTE-M-DARK-33", TriggerType.ON_ATTACK,
                ctx -> ctx.actions().damageLeader(ctx.room(), ctx.opponent(), 1, "QTE-M-DARK-33"));

        // ---- サモンズライト(QTE-M-DARK-34) ----
        // 「【召喚時】相手のミニオン1体に1ダメージ。
        //   【破壊時】自分の墓地からコスト1のミニオンを1体場に出す」
        //
        // ★【召喚時】の対象は optional である。必須にすると、相手の場が空のときに
        //   <b>召喚そのものが弾かれる</b>(腐敗の投擲者と同じ理由)。
        targetSpecs.put("QTE-M-DARK-34", TargetSpec.of(
                new Requirement(Kind.MINION, Side.OPPONENT, 1, true, false, List.of(),
                        "1ダメージを与える相手のミニオンを選んでください")));
        register("QTE-M-DARK-34", TriggerType.ON_SUMMON, ctx -> ctx.targets().get(0).minions()
                .forEach(t -> ctx.actions().damageMinion(ctx.room(), t.owner(), t.minion(), 1)));
        // ★【破壊時】の蘇生対象は<b>自動決定</b>である(AutoChoice)。裁定192
        //   (盤面に残るものを決める選択は本人にさせる)の例外にあたるが、構造上の理由がある ——
        //   【破壊時】は<b>相手のターン中にも起きる</b>のに対し、割り込み選択の解決
        //   (GameService.resolveChoice)は「ターンプレイヤーでなければ拒否する」。
        //   相手ターンに問い合わせを出すと、誰も解決できないまま盤面が固まる。
        // ★数えるのは印刷コストである(場に出る前のカードには動的コストが無い)。
        // ★自分自身(コスト2)は候補にならない。破壊処理は墓地へ置いてから
        //   ON_DESTROYED を発火するため、もしコスト1だったら自分を選びうる点に注意
        register("QTE-M-DARK-34", TriggerType.ON_DESTROYED, ctx -> {
            List<String> candidates = ctx.owner().getTrash().stream()
                    .filter(id -> {
                        CardMaster m = cards.findById(id);
                        return m.type() == CardType.MINION && m.cost() != null && m.cost() == 1;
                    })
                    .toList();
            for (String cardId : AutoChoice.reviveOrder(cards, candidates)) {
                if (ctx.actions().reviveFromGrave(ctx.room(), ctx.owner(), cardId) != null) {
                    ctx.room().addLog("【サモンズライト】: 墓地から【%s】を場に出しました"
                            .formatted(cards.findById(cardId).name()));
                    return;
                }
            }
            ctx.room().addLog("【サモンズライト】: 墓地にコスト1のミニオンが居ないため、何も起こりませんでした");
        });

        // ---- ダークネオンステージ(QTE-M-DARK-36) ----
        // 「【特殊召喚】(自分の場1枚、自分の手札を2枚捨てることでこのカードを0コストとして場に出す)」
        //
        // ★「自分の場1枚」は<b>自分の場のミニオン1体を破壊する</b>ことである(マスター裁定198)。
        //   闇文明の代替コストは一貫して「資源を失う」形であり(這い寄る生霊・冥界神ハデス・
        //   禁忌の代償)、場を減らさずに0コストで5マナ相当が出る読みは取らない。
        // ★手札は<b>このカード自身を除いて</b>2枚必要である。対象指定はプレイするカードを
        //   自動で除外する(GameService.validateTargets)ので、条件側でも1枚引いて数える。
        // ★生贄で場が1つ空くため、specialSummon の「場が満杯なら弾く」事前判定は
        //   MINION/SELF の要求があることで自動的に免除される(知恵の双翼と同じ)
        specialSummons.put("QTE-M-DARK-36", SpecialSummonSpec.of(
                (state, player, handIndex) -> !player.getMinionZone().isEmpty()
                        && player.getHand().size() - 1 >= 2,
                TargetSpec.of(
                        new Requirement(Kind.MINION, Side.SELF, 1, false, false, List.of(),
                                "破壊する自分のミニオンを1体選んでください"),
                        new Requirement(Kind.HAND, Side.SELF, 2, false, false, List.of(),
                                "捨てるカードを2枚選んでください")),
                ctx -> {
                    ctx.targets().get(0).minions().forEach(
                            t -> ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion()));
                    // 選択済みの手札は既に除去されて届くので、行き先(墓地)を決めるだけでよい。
                    // ★「場以外から墓地へ」の入口を通す(カムバックキーパーが反応する)
                    ctx.targets().get(1).handCardIds().forEach(
                            id -> ctx.actions().putIntoTrashFromElsewhere(ctx.room(), ctx.owner(), id));
                    ctx.room().addLog("【ダークネオンステージ】: 自分のミニオン1体と手札2枚を代償にしました");
                },
                "自分のミニオン1体を破壊し手札2枚を捨てて、0コストで召喚します"));

        // ---- 1stL「NEMれぬ夜のドリーミー」(QTE-M-DARK-39) ----
        // 「【召喚時】他のミニオンを全て破壊する。こうして破壊したミニオンが10体以上で
        //   自分のリーダーが闇文明ならこれは【速攻】を得る。
        //   【常在】このターン中破壊されたミニオン1体につきこのターンの間Attack+1」
        //
        // ★【召喚時】は<b>両者の場</b>の、自身以外すべてを破壊する(創世神ガイアと同じ)。
        //   「自分の」と書いていないので両者を見る(裁定156(2))。
        // ★「こうして破壊した」数は<b>実際に破壊できた数</b>である(裁定: 2種のオニと同じ)。
        //   大天使ミカエル・聖光の守護聖の置換で場に残ったミニオンは数えない。
        // ★【常在】のAttack加算は StatCalculator にある。こちらが数えるのは
        //   「この召喚で破壊した数」だけであり、別の量である
        register("QTE-M-DARK-39", TriggerType.ON_SUMMON, ctx -> {
            MinionInstance self = ctx.source();
            int destroyed = 0;
            for (PlayerState side : List.of(ctx.owner(), ctx.opponent())) {
                for (MinionInstance m : List.copyOf(side.getMinionZone())) {
                    if (m == self) {
                        continue;
                    }
                    ctx.actions().destroyMinion(ctx.room(), side, m);
                    if (!side.getMinionZone().contains(m)) {
                        destroyed++;
                    }
                }
            }
            ctx.room().addLog("【1stL「NEMれぬ夜のドリーミー」】: 他のミニオンを%d体破壊しました"
                    .formatted(destroyed));
            boolean darkLeader = ctx.owner().getLeader().civilization() == Civilization.DARK;
            if (destroyed >= DREAMY_HASTE_THRESHOLD && darkLeader && self != null) {
                self.grantKeyword(Keyword.HASTE);
                ctx.room().addLog("【1stL「NEMれぬ夜のドリーミー」】は【速攻】を得た");
            }
        });
    }

    // ---------------------------------------------------------------
    // ★Batch 50: Ver1.1 で追加された光文明のカード(P2 の3本目・後半)。
    //
    // 光の新しいテーマは既存と地続きで「相手の流れを止めること」だが、
    // 止める場所が増えた —— テングスンは<b>スペルのコスト</b>を、
    // モアニールは<b>ミニオンの登場</b>と<b>リーダーへのダメージ</b>を、
    // バンユーは<b>攻撃の回数</b>を止める。
    //
    // ★英皇アントマルエル(QTE-M-LIGHT-29)はここに登録を持たない。
    //   常在の登場誘発は fireAnyMinionEntered に直接書いてある(ロロイヨ伯爵と同じ形)。
    // ★光霊・テングスンのコスト+1は StatCalculator、光霊・モアニールの2つの置換は
    //   RuleGuards(判定)と GameActions / GameService(実行)、
    //   英術・バンユーの攻撃制限は RuleGuards にある(裁定180)。
    // ---------------------------------------------------------------
    private void registerLightVer11Cards() {

        // ---- 光霊・ネフラ(QTE-M-LIGHT-35) ----
        // 「【召喚時】自分の山札の上から3枚表向きにする。
        //   その中の【守護】を持っているもしくはスペルのカードを全て手札に加える。」
        //
        // ★<b>残りの行き先は本文に書かれていない。</b>山札の下に置く(マスター裁定199) ——
        //   降臨の伝道師(公開4枚 → 残りは山札の下)と同じ既存の形である。
        // ★条件は「【守護】を持つ」<b>または</b>「スペルである」。【守護】はミニオン以外にも
        //   付きうる(ウェポン)ため、種別ではなくキーワードで見る。
        // ★プレイヤーの選択は発生しない(全部加える)ので、割り込みは要らない。
        // ★山札が3枚に満たなければ、あるだけ公開する(revealFromTopOfDeck の既存の挙動)
        register("QTE-M-LIGHT-35", TriggerType.ON_SUMMON, ctx -> {
            List<String> revealed = ctx.actions().revealFromTopOfDeck(ctx.room(), ctx.owner(), 3);
            List<String> rest = new ArrayList<>();
            int taken = 0;
            for (String cardId : revealed) {
                CardMaster master = cards.findById(cardId);
                if (master.hasKeyword(Keyword.GUARD) || master.type() == CardType.SPELL) {
                    ctx.owner().getHand().add(cardId);
                    ctx.room().addLog("【光霊・ネフラ】: 【%s】を手札に加えました".formatted(master.name()));
                    taken++;
                } else {
                    rest.add(cardId);
                }
            }
            ctx.actions().returnToBottomOfDeck(ctx.owner(), rest);
            ctx.room().addLog("【光霊・ネフラ】: %d枚を手札に加え、残り%d枚を山札の下に置きました"
                    .formatted(taken, rest.size()));
        });

        // ---- 英術・グラーニス(QTE-M-LIGHT-37・スペル) ----
        // 「自分のリーダーの体力を2回復する。【還元】」
        //
        // 【還元】(墓地の代わりに裏向きでマナへ)の処理は GameActions 側で共通である
        // (流転の書・再起の炎陣と同じ)。ここに書くのは回復だけでよい
        spellEffects.put("QTE-M-LIGHT-37",
                ctx -> ctx.actions().healLeader(ctx.room(), ctx.owner(), 2, "QTE-M-LIGHT-37"));

        // ---- 英術・バンユー(QTE-M-LIGHT-38・スペル) ----
        // 「相手は次の相手のターン中スペルを唱えられない。
        //   相手のミニオンは次の相手のターン1度しか攻撃できない。」
        //
        // ★スペル封じは断罪の聖導者(QTE-M-LIGHT-15)とまったく同じ仕組みを使う
        //   —— 効果を受けた時点で「次のターン番号」を刻む。
        // ★「1度しか攻撃できない」は<b>相手の場全体で合計1回</b>である(マスター裁定200)。
        //   ミニオン1体につき1回ではない —— 判定は RuleGuards.minionAttackDenial にある。
        // ★リーダー(ウェポン)の攻撃は止めない。テキストが「相手のミニオンは」と
        //   書いているので、書かれていない対象を勝手に増やさない
        spellEffects.put("QTE-M-LIGHT-38", ctx -> {
            int nextTurn = ctx.state().getTurnNumber() + 1;
            ctx.opponent().setSpellSealedOnTurn(nextTurn);
            ctx.opponent().setMinionAttackLimitedOnTurn(nextTurn);
            ctx.room().addLog("次の%sのターン、スペルを唱えられず、ミニオンの攻撃は合計1回までになります"
                    .formatted(ctx.opponent().getDisplayName()));
        });
    }

    // ---------------------------------------------------------------
    // 登録: 火文明の Ver1.1(★Batch 51)
    //
    // 火の Ver1.1 は「鉄機」——【進化】を軸にした機械の群れである。
    // 進化エンジンそのものは P3 の担当だが、7枚のうち2枚は<b>進化を参照するだけ</b>で、
    // 参照は「そのカードの種別が EVOLUTION か」を見るだけで足りる(マスター裁定215)。
    // ★現行のカードプールでは進化ミニオンを場に出せないため、その分岐は今は必ず偽である。
    //   50 の演舞の墓守の2本目の発火位置と同じ性質であり、P3 で自動的に効き始める。
    // ★手札から進化ミニオンを場に出す《機神兵長茶爺》だけは、進化スタックそのものを
    //   要求するため P3 送りである。
    // ---------------------------------------------------------------
    private void registerFireVer11Cards() {

        // ---- 支援盾機狸(QTE-M-FIRE-33) ----
        // 「【守護】このミニオンは攻撃できない。【破壊時】自分のリーダーに1ダメージ」
        //
        // 【守護】はテキストから抽出される(裁定158)。「攻撃できない」は判定であり、
        // RuleGuards.minionAttackDenial にある(煌めきの盾・ハク霊・コク霊と同じ形)。
        // ここに登録するのは【破壊時】だけでよい。
        // ★0コストで出る【守護】の壁であり、自分のリーダーを削ることが代償になっている
        register("QTE-M-FIRE-33", TriggerType.ON_DESTROYED, ctx ->
                ctx.actions().damageLeader(ctx.room(), ctx.owner(), 1, "QTE-M-FIRE-33"));

        // ---- 乱戦鉄機狼(QTE-M-FIRE-34) ----
        // 「【速攻】【召喚時】自分のリーダーに1ダメージ。
        //   自分のリーダーの体力が10以下なら代わりに相手のリーダーに1ダメージ。」
        //
        // ★「代わりに」なので置換であり、<b>両方には当たらない</b>。
        // ★判定はダメージを与える前の自分のLPで行う(マスター裁定216)。
        //   これは置換効果の一般則である —— 置換は「起きようとしている事象」を見て決まる。
        // ★自傷は火文明の資源であり、火炎の狂信者・反転の炎鏡が反応する。
        //   発生源IDを渡しているのはそのためである(damageLeader の第4引数)
        register("QTE-M-FIRE-34", TriggerType.ON_SUMMON, ctx -> {
            if (ctx.owner().getLp() <= IRON_WOLF_LP_THRESHOLD) {
                ctx.room().addLog("【乱戦鉄機狼】: 自分のLPが%d以下のため、相手のリーダーに1ダメージ"
                        .formatted(IRON_WOLF_LP_THRESHOLD));
                ctx.actions().damageLeader(ctx.room(), ctx.opponent(), 1, "QTE-M-FIRE-34");
            } else {
                ctx.actions().damageLeader(ctx.room(), ctx.owner(), 1, "QTE-M-FIRE-34");
            }
        });

        // ---- 砲台鉄機虎(QTE-M-FIRE-35) ----
        // 「【特殊召喚】(場に進化ミニオンが1体以上いるとき0コストとして場に出せる)【突進】」
        //
        // ★「場に」に<b>「自分の」が付いていない</b>ので両者の場を見る(記法規約。裁定156(2))。
        // ★代替コストは無い(0コストで出るだけ)。創世神ガイアと同じ形である。
        // ★現行のカードプールでは進化ミニオンが場に出ないため、この条件は常に偽になる。
        //   P3 で進化が解禁された時点で、ここに手を入れずに効き始める
        specialSummons.put("QTE-M-FIRE-35", SpecialSummonSpec.of(
                (state, player, handIndex) -> hasEvolutionOnAnyField(state),
                TargetSpec.of(),
                ctx -> {
                },
                "場に進化ミニオンが居ます: 代替コストなしで0コスト召喚します"));

        // ---- ラスト・アタック(QTE-M-FIRE-36・スペル) ----
        // 「場の自分のミニオンを1枚選び破壊する。そうしたら相手のミニオンに3ダメージを与える。
        //   こうして破壊した自分のミニオンが進化ミニオンなら相手の全てのミニオンに追加で2ダメージ。」
        //
        // ★自分のミニオンの選択は<b>必須</b>である。喚ビ集ウ・アヤカシ(48)は【召喚時】だったので
        //   TargetSpec にすると召喚そのものが弾かれたが、こちらはスペルであり、
        //   「破壊できないなら撃てない」で正しい(テキストが破壊を条件にしている)。
        // ★相手のミニオンの選択は任意にしてある。相手の場が空でも自分のミニオンを破壊する
        //   意味はある(【破壊時】を能動的に起こす)ため、撃てなくしてはいけない。
        // ★「そうしたら」= 実際に破壊できたときだけ先へ進む。破壊の置換(大天使ミカエル等)で
        //   場に残った場合はダメージを与えない(48 の喚ビ集ウ・アヤカシと同じ判定)
        targetSpecs.put("QTE-M-FIRE-36", TargetSpec.of(
                new Requirement(Kind.MINION, Side.SELF, 1, false, false, List.of(),
                        "破壊する自分のミニオンを1体選んでください"),
                new Requirement(Kind.MINION, Side.OPPONENT, 1, true, false, List.of(),
                        "3ダメージを与える相手のミニオンを1体選んでください")));
        spellEffects.put("QTE-M-FIRE-36", ctx -> {
            ResolvedTargets.TargetedMinion sacrifice = ctx.targets().get(0).minions().get(0);
            boolean wasEvolution = sacrifice.minion().getMaster().type() == CardType.EVOLUTION;
            ctx.actions().destroyMinion(ctx.room(), sacrifice.owner(), sacrifice.minion());
            if (sacrifice.owner().getMinionZone().contains(sacrifice.minion())) {
                ctx.room().addLog("【ラスト・アタック】: 破壊されなかったため、ダメージは発生しません");
                return;
            }
            ctx.targets().get(1).minions().forEach(t ->
                    ctx.actions().damageMinion(ctx.room(), t.owner(), t.minion(), 3));
            if (!wasEvolution) {
                return;
            }
            ctx.room().addLog("【ラスト・アタック】: 破壊したのが進化ミニオンのため、相手全体に追加で2ダメージ");
            for (MinionInstance m : List.copyOf(ctx.opponent().getMinionZone())) {
                ctx.actions().damageMinion(ctx.room(), ctx.opponent(), m, 2);
            }
        });

        // ---- リペア・チューナー(QTE-M-FIRE-37・スペル) ----
        // 「手札を1枚捨てる。その後カードを2枚引く。」
        //
        // ★捨てる手札は「あるだけ」(upTo)にしてある(裁定191 の形)。必須にすると、
        //   このスペル1枚しか手札に無いときに<b>撃てなくなる</b> ——
        //   テキストは「捨てられなければ引けない」とは書いていない(マスター裁定217)。
        // ★選択済みの手札は検証の時点で手札から取り除かれて届く。
        //   行き先を決めるだけでよく、「場以外から墓地へ」の入口を通す(★Batch 50)
        targetSpecs.put("QTE-M-FIRE-37", TargetSpec.of(
                Requirement.upTo(Kind.HAND, Side.SELF, 1, "捨てるカードを1枚選んでください")));
        spellEffects.put("QTE-M-FIRE-37", ctx -> {
            ctx.targets().get(0).handCardIds().forEach(
                    id -> ctx.actions().putIntoTrashFromElsewhere(ctx.room(), ctx.owner(), id));
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 2);
        });

        // ---- アイアン・リターン(QTE-M-FIRE-38・スペル) ----
        // 「自分の手札を全て山札に戻してシャッフルする。こうして戻した枚数+1枚山札からカードを引く。」
        //
        // ★このカード自身は数に入らない。スペルの解決は
        //   検証 → コスト支払い → 手札からの除去(removePlayedAndTargets)→ 解決 の順で進むため、
        //   ここに来た時点でこのカードは既に手札を離れている。
        //   ギガマウス・バイトのコスト計算が自身を含む(裁定190)のと対になる事実であり、
        //   <b>数えるのが「コストの計算時点」か「解決時点」かで答えが変わる</b>
        spellEffects.put("QTE-M-FIRE-38", ctx -> {
            List<String> hand = new ArrayList<>(ctx.owner().getHand());
            ctx.owner().getHand().clear();
            ctx.actions().returnToDeckAndShuffle(ctx.room(), ctx.owner(), hand);
            ctx.actions().drawCards(ctx.room(), ctx.owner(), hand.size() + 1);
        });

        // ---- ドレイン・ブラスト(QTE-M-FIRE-39・スペル) ----
        // 「ミニオンを2体選び4ダメージ与える。この効果で破壊した枚数自分のリーダーを1回復行う。【還元】」
        //
        // ★「ミニオン」に「相手の」が付いていないので<b>自分のミニオンも選べる</b>(記法規約)。
        // ★「2体」は upTo である(裁定191)。場に2体居なければ、あるだけを選ぶ。
        //   固定にすると、盤面に1体しか居ないときに撃てなくなる。
        // ★回復量は<b>実際に破壊できた数</b>である(48 の「破壊した数」と同じ)。
        //   破壊の置換で場に残ったミニオンは数えない。
        // ★【還元】の処理は GameActions 側の共通処理(流転の書・英術グラーニスと同じ)
        targetSpecs.put("QTE-M-FIRE-39", TargetSpec.of(
                Requirement.upTo(Kind.MINION, Side.ANY, 2,
                        "4ダメージを与えるミニオンを2体まで選んでください")));
        spellEffects.put("QTE-M-FIRE-39", ctx -> {
            List<ResolvedTargets.TargetedMinion> chosen = ctx.targets().get(0).minions();
            int destroyed = 0;
            for (ResolvedTargets.TargetedMinion t : chosen) {
                ctx.actions().damageMinion(ctx.room(), t.owner(), t.minion(), 4);
                if (!t.owner().getMinionZone().contains(t.minion())) {
                    destroyed++;
                }
            }
            if (destroyed > 0) {
                ctx.actions().healLeader(ctx.room(), ctx.owner(), destroyed, "QTE-M-FIRE-39");
            }
        });
    }

    /**
     * 場に進化ミニオンが1体でも居るか(★Batch 51。砲台鉄機虎)。
     *
     * ★「場に」に「自分の」が付いていないため両者の場を見る(記法規約)。
     * ★進化エンジンは P3 の担当であり、現行のカードプールではここは必ず false を返す。
     *   それでも書いておくのは、P3 が解禁したときにこのカードへ戻ってこなくて済むようにするためである。
     */
    private boolean hasEvolutionOnAnyField(GameState state) {
        return List.of(state.getPlayer1(), state.getPlayer2()).stream()
                .flatMap(p -> p.getMinionZone().stream())
                .anyMatch(m -> m.getMaster().type() == CardType.EVOLUTION);
    }

    // ---------------------------------------------------------------
    // 登録: 土文明の Ver1.1(★Batch 51)
    //
    // 土の Ver1.1 は「マナと場を直接つなぐ」——13a が作ったマナ加速の資源を、
    // そのまま盤面に変える。そのための2方向の入口は GameActions に1本ずつ作った
    // (putManaCardIntoField / putFieldMinionIntoManaFaceDown)。
    // ★manaZone や minionZone に直接 add / remove を書かないこと。
    //
    // 「マナから場へ出すミニオンを選ぶ」は4枚に現れる。候補の絞り込みだけが違うので、
    // requestManaSummon に集約してある(裁定163: 同じ規則を2箇所に置かない)。
    // ---------------------------------------------------------------
    private void registerEarthVer11Cards() {

        // ---- 百獣の王 ベヒーモス(QTE-M-EARTH-7) ----
        // 「【召喚時】他のミニオン全てに7ダメージ。体力の多いリーダーに3ダメージ」
        //
        // ★Ver0.4 では効果を持たないバニラだったカードに、Ver1.1 で効果が付いた
        //   (13b の登録不要リストにあった3枚のうちの1枚である)。
        // ★「他のミニオン全て」= 自分自身を除く両者の場。創世神ガイアと同じ形。
        // ★「体力の多いリーダー」が<b>同値のときは何も起きない</b>(マスター裁定218)。
        //   「多いほう」が一意に定まらないためである
        register("QTE-M-EARTH-7", TriggerType.ON_SUMMON, ctx -> {
            MinionInstance self = ctx.source();
            for (PlayerState side : List.of(ctx.owner(), ctx.opponent())) {
                for (MinionInstance m : List.copyOf(side.getMinionZone())) {
                    if (m != self) {
                        ctx.actions().damageMinion(ctx.room(), side, m, 7);
                    }
                }
            }
            if (ctx.owner().getLp() == ctx.opponent().getLp()) {
                ctx.room().addLog("【百獣の王 ベヒーモス】: 体力が同じため、リーダーへのダメージは発生しません");
                return;
            }
            PlayerState higher = ctx.owner().getLp() > ctx.opponent().getLp()
                    ? ctx.owner() : ctx.opponent();
            ctx.actions().damageLeader(ctx.room(), higher, 3, "QTE-M-EARTH-7");
        });

        // ---- 地上覇総長・翔山(QTE-M-EARTH-29・リーダー) ----
        // 「【起動：2】自分の墓地からカード1枚選びマナに置く。」
        //
        // ★向きが書かれていない。<b>表向き</b>で置く(マスター裁定210)——
        //   《ガイア・リソース》が同じく向きを書いておらず、既存実装が表向きだからである。
        //   墓地→マナの既存2枚(マナを貪る怨霊・禁忌の墓地利用)はどちらも本文に
        //   「裏向きで」と明記しているので、明記の無いこれは表向きの側に入る。
        // ★表向きであることには意味がある —— 翔山で置いたカードは、
        //   《俺等地上覇夜露死苦》の「表向きのマナから場に出す」で盤面に変えられる。
        //   土の Ver1.1 は「墓地 → マナ → 場」の1本の線として組まれている。
        // ★配置は placeCardInManaFaceUp を通す(マナ上限の判定・配置回数の計数・
        //   豊穣の地霊主の発火が、そこに集約されている)
        leaderAbilities.put("QTE-M-EARTH-29", LeaderAbilitySpec.of(2,
                TargetSpec.of(new Requirement(Kind.TRASH, Side.SELF, 1, false, false, List.of(),
                        "マナに置く墓地のカードを1枚選んでください")),
                ctx -> {
                    String cardId = ctx.targets().get(0).trashCardIds().get(0);
                    if (!ctx.owner().getTrash().remove(cardId)) {
                        return;
                    }
                    if (!ctx.actions().placeCardInManaFaceUp(ctx.room(), ctx.owner(), cardId)) {
                        // マナ上限で置けなかった場合は墓地へ戻す(カードを消さない)
                        ctx.owner().getTrash().add(cardId);
                        return;
                    }
                    ctx.room().addLog("【地上覇総長・翔山】: 【%s】を墓地から表向きでマナに置きました"
                            .formatted(cards.findById(cardId).name()));
                },
                "墓地のカード1枚を表向きでマナに置きます"));

        // ---- 分那愚利(QTE-M-EARTH-33) ----
        // 「【突進】【召喚時】相手ミニオン1体に1ダメージ」
        //
        // 【突進】はテキストから抽出される。ここは【召喚時】の1ダメージだけでよい。
        // ★相手の場が空でも召喚できるよう、対象は任意にする(腐敗の投擲者と同じ形)
        targetSpecs.put("QTE-M-EARTH-33", TargetSpec.of(
                new Requirement(Kind.MINION, Side.OPPONENT, 1, true, false, List.of(),
                        "1ダメージを与える相手のミニオンを1体選んでください")));
        register("QTE-M-EARTH-33", TriggerType.ON_SUMMON, ctx ->
                ctx.targets().get(0).minions().forEach(t ->
                        ctx.actions().damageMinion(ctx.room(), t.owner(), t.minion(), 1)));

        // ---- 勝鼓美(QTE-M-EARTH-34) ----
        // 「【破壊時】山札の上からカードを1枚マナゾーンに置く。
        //   その後コスト3以下のミニオンを1体選びマナゾーンから場に出す。」
        //
        // ★マナに置く向きは書かれていない → 表向き(マスター裁定210。翔山と同じ根拠)。
        // ★場に出す候補は<b>裏向きのマナも含む</b>(マスター裁定211)——
        //   本文が「表向きの」と限定していないためである。限定しているのは
        //   《俺等地上覇夜露死苦》1枚だけで、そちらだけが表向きに絞られる。
        // ★★【破壊時】は<b>相手のターン中にも起きる</b>。50 まではそこで本人に選ばせられず
        //   自動決定にするしかなかったが(サモンズライト)、51 で
        //   GameService.resolveChoice の制限を外した(マスター裁定214)ので、本人が選ぶ
        register("QTE-M-EARTH-34", TriggerType.ON_DESTROYED, ctx -> {
            ctx.actions().placeTopOfDeckInManaFaceUp(ctx.room(), ctx.owner());
            requestManaSummon(ctx, "勝鼓美", ResumePoint.KACHIKOMI_MANA_SUMMON,
                    mana -> {
                        CardMaster master = cards.findById(mana.getCardId());
                        return master.type() == CardType.MINION && master.cost() <= 3;
                    },
                    "【勝鼓美】: マナから場に出すコスト3以下のミニオンを1体選んでください");
        });

        // ---- 素手喧嘩(QTE-M-EARTH-35) ----
        // 「【突進】攻撃時このカードをマナに裏向きで置いても良い。
        //   そうしたらマナにある表向きのAttackが6以下のミニオンを1体場に出す。」
        //
        // ★★<b>置いた場合、戦闘は起きない</b>(マスター裁定213)。攻撃者が場を離れるためである。
        //   そのため 51 では「攻撃時の割り込みが出たら戦闘の解決を保留する」構造を
        //   GameService に入れた(GameState.pendingAttack)。この登録自体は
        //   戦闘のことを何も知らない —— 場を離れたという事実だけが戦闘を止める。
        // ★「置いても良い」なので min=0 の任意選択である。候補は自分自身1体だけであり、
        //   Kind.MINION の候補に自分を1体だけ入れることで「はい/いいえ」を表している。
        //   真偽値を選ばせる新しい PendingChoice.Kind を足していないのは、
        //   クライアントの分岐を増やさずに済むためである(版数を上げずに済んでいる)。
        // ★2段目(マナから出すミニオン)は「マナにある<b>表向きの</b>」と限定されている
        register("QTE-M-EARTH-35", TriggerType.ON_ATTACK, ctx -> {
            MinionInstance self = ctx.source();
            if (self == null || !ctx.owner().getMinionZone().contains(self)) {
                return;
            }
            ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.upTo(
                    PendingChoice.Kind.MINION, List.of(self.getInstanceId()), 1,
                    ResumePoint.STEGORO_TO_MANA,
                    "【素手喧嘩】: このカードを裏向きでマナに置きますか?"
                            + "(置くと戦闘は行われず、マナから表向きのAttack6以下のミニオンを1体出します)"));
        });

        // ---- 勝阿外(QTE-M-EARTH-36)は P4(【賢魂】)の担当である ----
        // 【常在】(相手はスペルを唱えられない / 相手の手札の枚数だけAttack+1)だけなら
        // 51 で書けるが、【賢魂：2】と合わせて1枚のカードであり、
        // 常在だけを実装すると「効果未実装」の印が消えて<b>賢魂も実装済みに見えてしまう</b>
        // (裁定165: 印は部分実装を表せない)。マスター裁定により、丸ごと P4 へ送る。

        // ---- 仏恥義理(QTE-M-EARTH-37・スペル) ----
        // 「カードを1枚引く。その後自分の手札を1枚選びマナに裏向きで置く。」
        //
        // ★引いた後に選ぶので、使用宣言時の対象指定(TargetSpec)では表せない ——
        //   引いたカードが候補に入らないためである。割り込み(PendingChoice)を使う。
        //   《風のマナ変換》とまったく同じ形だが、再開先は分けてある(ResumePoint の説明)
        spellEffects.put("QTE-M-EARTH-37", ctx -> {
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
            int size = ctx.owner().getHand().size();
            if (size == 0) {
                ctx.room().addLog("【仏恥義理】: 手札が無いため、マナに置けませんでした");
                return;
            }
            List<String> positions = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                positions.add(String.valueOf(i));
            }
            ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.one(
                    PendingChoice.Kind.HAND, positions, ResumePoint.BUCCHIGIRI_MANA_PUT,
                    "【仏恥義理】: 裏向きでマナに置く手札を1枚選んでください"));
        });

        // ---- 喧嘩上等(QTE-M-EARTH-38・スペル) ----
        // 「相手のミニオンを1体マナに裏向きで置く。
        //   その後自分のマナからコスト6以下のミニオンを1体マナから場に出す」
        //
        // ★置き先は<b>相手のマナゾーン</b>である(マスター裁定212)。
        //   後半が「自分のマナから」と書き分けているので、前半の「マナ」は相手のものと読む。
        //   相手にマナを1枚与えることになるが、それがこの除去の調整になっている。
        // ★破壊ではないので【破壊時】は発動しない。禁忌由来のミニオンは消滅する
        //   (putFieldMinionIntoManaFaceDown が bounceToHand と同じ扱いをする)。
        // ★相手の場が空でも撃てるよう、対象は任意にしてある
        targetSpecs.put("QTE-M-EARTH-38", TargetSpec.of(
                new Requirement(Kind.MINION, Side.OPPONENT, 1, true, false, List.of(),
                        "マナに置く相手のミニオンを1体選んでください")));
        spellEffects.put("QTE-M-EARTH-38", ctx -> {
            ctx.targets().get(0).minions().forEach(t ->
                    ctx.actions().putFieldMinionIntoManaFaceDown(ctx.room(), t.owner(), t.minion()));
            requestManaSummon(ctx, "喧嘩上等", ResumePoint.KENKAJOTO_MANA_SUMMON,
                    mana -> {
                        CardMaster master = cards.findById(mana.getCardId());
                        return master.type() == CardType.MINION && master.cost() <= 6;
                    },
                    "【喧嘩上等】: マナから場に出すコスト6以下のミニオンを1体選んでください");
        });

        // ---- 俺等地上覇夜露死苦(QTE-M-EARTH-39・スペル) ----
        // 「相手のミニオンを全て破壊する。その後自分の表向きのマナからミニオンを1枚選び場に出す。」
        //
        // ★このカードだけが「<b>表向きの</b>マナから」と限定している。
        //   勝鼓美・喧嘩上等が限定していないのは書き落としではなく、
        //   限定の有無をそのまま実装に写す(マスター裁定211)。
        // ★出すミニオンにコストの制限は無い。マナに置いた大物をそのまま盤面に変えられる
        //   のが、コスト9に見合う部分である
        spellEffects.put("QTE-M-EARTH-39", ctx -> {
            for (MinionInstance m : List.copyOf(ctx.opponent().getMinionZone())) {
                ctx.actions().destroyMinion(ctx.room(), ctx.opponent(), m);
            }
            requestManaSummon(ctx, "俺等地上覇夜露死苦", ResumePoint.SEKAIWO_MANA_SUMMON,
                    mana -> mana.isFaceUp()
                            && cards.findById(mana.getCardId()).type() == CardType.MINION,
                    "【俺等地上覇夜露死苦】: 表向きのマナから場に出すミニオンを1枚選んでください");
        });
    }

    /**
     * 「自分のマナから場に出すミニオンを1体選ぶ」の共通処理(★Batch 51)。
     *
     * 土の Ver1.1 の4枚(勝鼓美・素手喧嘩・喧嘩上等・俺等地上覇夜露死苦)が同じ形を持ち、
     * 違うのは<b>候補の絞り込みと再開先だけ</b>である。候補が0体なら不発、1体なら自動決定、
     * 2体以上なら本人に選ばせる(降臨の伝道師・地砕きの突撃兵と同じ流儀)。
     *
     * <b>候補はマナゾーン内の位置(0起点)で表す。</b> 選択待ちの間に位置がずれないことは、
     * 「選択待ちのあいだは誰も盤面を動かせない」という規則が保証している
     * ({@code GameService.requireTurnPlayer} が両者を塞ぐ。★Batch 51 で相手側も塞いだ)。
     *
     * @param filter 候補にするマナの条件。カードの本文が限定している内容をそのまま書く
     */
    private void requestManaSummon(EffectContext ctx, String cardName, ResumePoint resumeAt,
            java.util.function.Predicate<ManaCard> filter, String prompt) {
        List<String> positions = new ArrayList<>();
        List<ManaCard> mana = ctx.owner().getManaZone();
        for (int i = 0; i < mana.size(); i++) {
            if (filter.test(mana.get(i))) {
                positions.add(String.valueOf(i));
            }
        }
        if (positions.isEmpty()) {
            ctx.room().addLog("【%s】: 条件に合うミニオンがマナに無いため、場には出せませんでした"
                    .formatted(cardName));
            return;
        }
        if (positions.size() == 1) {
            ctx.actions().putManaCardIntoField(ctx.room(), ctx.owner(),
                    Integer.parseInt(positions.get(0)));
            return;
        }
        ctx.actions().requestChoice(ctx.room(), ctx.owner(),
                PendingChoice.one(PendingChoice.Kind.MANA, positions, resumeAt, prompt));
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
        // ★Batch 57(区分3b/4)実装変更なし: Ver1.1 の差は【召喚時】の印が明記されたことと
        //   数値(5/3/3 → 4/4/3)だけであり、数値は manual-cards.json から読まれる。
        //   旧本文は印を持たなかったが実装は当初から ON_SUMMON である(55 で確認済み)。
        register("QTE-M-EARTH-3", TriggerType.ON_SUMMON,
                ctx -> ctx.actions().placeTopOfDeckInManaFaceUp(ctx.room(), ctx.owner()));

        // 苗木植えの精霊(0156): 【召喚時】手札を1枚表向きでマナに置く(手札は選択)
        // ★Batch 57(区分3b)実装変更なし: 差は【召喚時】の明記と HP 1→2 だけである
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
        // ★Batch 57(区分3b)実装変更なし: 差は【召喚時】の明記だけである
        register("QTE-M-EARTH-21", TriggerType.ON_SUMMON, ctx -> {
            List.copyOf(ctx.opponent().getMinionZone()).forEach(
                    m -> ctx.actions().damageMinion(ctx.room(), ctx.opponent(), m, 7));
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 2);
        });

        // アースクエイクジャイアント(0153): 【召喚時】相手の場の【守護】ミニオンをすべて破壊
        // ★Batch 57(区分3b)実装変更なし: 差は【召喚時】の明記だけである
        register("QTE-M-EARTH-4", TriggerType.ON_SUMMON, ctx ->
                List.copyOf(ctx.opponent().getMinionZone()).stream()
                        .filter(m -> m.hasKeyword(Keyword.GUARD))
                        .forEach(m -> ctx.actions().destroyMinion(ctx.room(), ctx.opponent(), m)));

        // 安らぎのガーディアン(0152): 【守護】【召喚時】リーダーを2回復。
        //                              <b>自分の</b>ターンエンド時にリーダーを4回復
        // ★Batch 57(区分3b): Ver1.1 で2つ変わった。
        //   (1) 【守護】が付いた —— テキストから CardTextKeywords が自動で拾うのでコード変更は不要。
        //   (2) 回復の誘発が「ターンエンド時」から「<b>自分の</b>ターンエンド時」に限定された。
        //       ON_TURN_END は<b>両者の場を回す</b>(GameService.endTurn)ため、
        //       これまでは相手のターンの終わりにも4回復していた。1ターンあたり8回復である。
        //       Ver1.1 はこれを半分にした。ターンプレイヤーが持ち主のときだけ回復する。
        // ★ここで判定するのが正しい —— ON_TURN_END が両者を回すのは意図した設計であり
        //   (TriggerType の Javadoc)、「自分のターンだけ」はこのカード固有の条件である。
        register("QTE-M-EARTH-20", TriggerType.ON_SUMMON,
                ctx -> ctx.actions().healLeader(ctx.room(), ctx.owner(), 2, "QTE-M-EARTH-20"));
        register("QTE-M-EARTH-20", TriggerType.ON_TURN_END, ctx -> {
            if (ctx.state().turnPlayer() != ctx.owner()) {
                return;
            }
            ctx.actions().healLeader(ctx.room(), ctx.owner(), 4, "QTE-M-EARTH-20");
        });

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

        // ガイア・ハンマー(0142): 【召喚時】山札の上から1枚を表向きでマナに置く
        // ★Batch 57(区分4)実装変更なし: Ver1.1 で【召喚時】の印が付いた。
        //   ウェポンにとって「場に出る」は装備であり、ON_EQUIP がその発火口である
        //   (ウェポンは召喚されない)。印の追加は実装の裏付けであって変更ではない。
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

        // 大地の恵み(0158): 山札の上から1枚を表向きでマナに置く。自分のマナが10枚以上なら1枚引く
        // ★Batch 57(区分4): Ver1.1 で後半の「マナ10枚以上なら1ドロー」が追加された。
        //   判定は<b>マナに置いた後</b>である(本文の順どおり) —— 9枚のときにこれを撃つと
        //   10枚になってドローまで届く。《大地の開眼》(1ドロー→7枚以上ならもう1枚)と
        //   同じ「置いてから数える」形である。
        spellEffects.put("QTE-M-EARTH-9", ctx -> {
            ctx.actions().placeTopOfDeckInManaFaceUp(ctx.room(), ctx.owner());
            if (ctx.owner().getManaZone().size() >= 10) {
                ctx.room().addLog("【大地の恵み】: マナが10枚以上のため1枚ドロー");
                ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
            }
        });

        // ガイア・リソース(0151): 山札の上から1枚をマナに置く。【還元】(還元の処理は GameActions 側で共通)
        spellEffects.put("QTE-M-EARTH-26",
                ctx -> ctx.actions().placeTopOfDeckInManaFaceUp(ctx.room(), ctx.owner()));

        // ★Batch 58(区分5): 地脈の覚醒(QTE-M-EARTH-27)。
        // 旧: 本文が<b>空欄</b>。【還元】だけを持ち、解決時の固有処理は無かった
        //     (唱えた後に自身が裏向きでマナへ行くマナ加速が本体)。
        // 新: 「自分のマナからカードを1枚手札に加える【還元】
        //      (「地脈の覚醒」の効果はターンに1回のみ発動する)」
        // ★<b>実質の新規実装である。</b>マナから手札への回収 + ターン1回制限の2つが増えた。
        // ★候補は<b>表向き・裏向きの両方</b>である。本文が向きを限定していないためである
        //   (裁定211。《地砕きの突撃兵》と同じ扱い)。
        // ★<b>自分自身は候補に入らない。</b>【還元】でマナへ置かれるのは効果の解決より後なので、
        //   候補を作った時点ではまだマナゾーンに居ない。マナゾーンの末尾に足されるだけなので、
        //   選択待ちの間に既存の位置がずれることもない。
        // ★<b>ターン1回制限は「発動」だけを止める。</b>2枚目を使用すること自体は止めない ——
        //   本文は「効果は…発動しない」であって「使用できない」ではない。
        //   2枚目は何も起きずに【還元】だけを残す(★裁定276 として確認を依頼中)。
        // ★制限はターン番号を刻んで持つ(裁定156(3)。PlayerState.recordManaPlacement と同じ考え方)。
        spellEffects.put("QTE-M-EARTH-27", ctx -> {
            int turn = ctx.room().getGameState().getTurnNumber();
            if (!ctx.owner().tryUseLeylineAwakening(turn)) {
                ctx.room().addLog("【地脈の覚醒】: このターンは既に発動しているため、効果は発動しません");
                return;
            }
            int size = ctx.owner().getManaZone().size();
            if (size == 0) {
                ctx.room().addLog("【地脈の覚醒】: マナが無いため、手札に加えられませんでした");
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
                    PendingChoice.Kind.MANA, positions, ResumePoint.LEYLINE_AWAKENING_TO_HAND,
                    "【地脈の覚醒】: 手札に加えるマナを1枚選んでください"));
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
        if (ctx.actions().isFieldEntryBlocked(ctx.room(), ctx.owner())) {
            ctx.actions().returnToBottomOfDeck(ctx.owner(), revealed);
            ctx.room().addLog("場に出せないため、公開した4枚はすべて山札の下に置かれました");
            return;
        }
        ctx.actions().returnToBottomOfDeck(ctx.owner(), rest);
        // ★Batch 50: 「場の末尾を取る」形をやめ、出したミニオンの実体を受け取る。
        // 末尾は ON_ENTER の中でさらに場が増えると別人になり、
        // <b>関係の無いミニオンに3ダメージを与えてしまう</b>(49 設計解説 2-3 が戒めた形)
        MinionInstance summoned = ctx.actions().putIntoFieldByEffect(ctx.room(), ctx.owner(), chosenId);
        if (summoned == null) {
            ctx.room().addLog("【降臨の伝道師】: 【%s】は場に出られませんでした"
                    .formatted(cards.findById(chosenId).name()));
            return;
        }
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
                    ctx.room().addLog("【選択の追い風】: 【%s】を捨てました".formatted(cards.findById(cardId).name()));
                    ctx.actions().putIntoTrashFromElsewhere(ctx.room(), ctx.owner(), cardId);
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
            // ★Batch 55(区分3a): 捨て 1→2枚(rework-triage.md)。min=max=2 のため必ず2枚選ばれる。
            // 位置がずれないよう、削除は降順で行う(詠唱の疾風騎士と同じ形)
            case AQUA_SEARCH_DISCARD -> {
                List<Integer> positions = new ArrayList<>();
                chosen.forEach(s -> positions.add(Integer.parseInt(s)));
                positions.sort(java.util.Comparator.reverseOrder());
                for (int pos : positions) {
                    String cardId = ctx.owner().getHand().remove(pos);
                    ctx.room().addLog("【アクア・サーチ】: 【%s】を捨てました"
                            .formatted(cards.findById(cardId).name()));
                    ctx.actions().putIntoTrashFromElsewhere(ctx.room(), ctx.owner(), cardId);
                }
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
            // 潮獣コアンチ(QTE-M-WATER-37): 引いた後に捨てる手札を確定させる。★Batch 49。
            // アクア・サーチと同じ必須ディスカードだが、再開先を分けている(ResumePoint の説明)
            case TIDE_COANCHI_DISCARD -> {
                int idx = Integer.parseInt(chosen.get(0));
                String cardId = ctx.owner().getHand().remove(idx);
                ctx.room().addLog("【潮獣コアンチ】: 【%s】を捨てました"
                        .formatted(cards.findById(cardId).name()));
                ctx.actions().putIntoTrashFromElsewhere(ctx.room(), ctx.owner(), cardId);
            }
            // 仏恥義理(QTE-M-EARTH-37): 引いた後に裏向きマナへ置く手札を確定させる。★Batch 51
            case BUCCHIGIRI_MANA_PUT -> {
                int idx = Integer.parseInt(chosen.get(0));
                ctx.actions().putHandCardIntoManaFaceDown(ctx.room(), ctx.owner(), idx);
            }
            // 勝鼓美(QTE-M-EARTH-34)/ 喧嘩上等(QTE-M-EARTH-38)/ 俺等地上覇夜露死苦(QTE-M-EARTH-39):
            // マナから場に出すミニオンを確定させる。★Batch 51。
            // 3枚とも候補の絞り込みだけが違い、確定のしかたは同じである
            // (再開先を分けているのは、ログに出す名前と将来の分岐のため。ResumePoint の説明)
            case KACHIKOMI_MANA_SUMMON, KENKAJOTO_MANA_SUMMON, SEKAIWO_MANA_SUMMON ->
                    ctx.actions().putManaCardIntoField(ctx.room(), ctx.owner(),
                            Integer.parseInt(chosen.get(0)));
            // 素手喧嘩(QTE-M-EARTH-35): 攻撃時に自分をマナへ置くかを確定させる。★Batch 51。
            // ★選ばなかった(chosen が空)なら何も起きず、保留していた戦闘がそのまま解決される。
            //   置いた場合は攻撃者が場を離れるため、戦闘は起きない(マスター裁定213)
            case STEGORO_TO_MANA -> {
                if (chosen.isEmpty()) {
                    ctx.room().addLog("【素手喧嘩】: マナに置かずに攻撃を続けます");
                    break;
                }
                MinionInstance self = ctx.owner().getMinionZone().stream()
                        .filter(m -> m.getInstanceId().equals(chosen.get(0)))
                        .findFirst().orElse(null);
                if (self == null || !ctx.actions()
                        .putFieldMinionIntoManaFaceDown(ctx.room(), ctx.owner(), self)) {
                    break;
                }
                requestManaSummon(ctx, "素手喧嘩", ResumePoint.STEGORO_MANA_SUMMON,
                        mana -> {
                            CardMaster master = cards.findById(mana.getCardId());
                            return mana.isFaceUp() && master.type() == CardType.MINION
                                    && master.attack() != null && master.attack() <= 6;
                        },
                        "【素手喧嘩】: マナから場に出す表向きのAttack6以下のミニオンを1体選んでください");
            }
            // 素手喧嘩の2段目: マナから場に出すミニオンを確定させる。★Batch 51
            case STEGORO_MANA_SUMMON -> ctx.actions().putManaCardIntoField(ctx.room(), ctx.owner(),
                    Integer.parseInt(chosen.get(0)));
            // 海淵獣ラカブ(QTE-M-WATER-31): 3枚引いた後に捨てる手札を確定させる。★Batch 53
            case RAKABU_DISCARD -> discardChosenHandCards(ctx, chosen, "海淵獣ラカブ");
            // 英知の継承者(QTE-M-WATER-19): 4枚引いた後に捨てる3枚を確定させる。★Batch 58
            case WISDOM_HEIR_DISCARD -> discardChosenHandCards(ctx, chosen, "英知の継承者");
            // 地脈の覚醒(QTE-M-EARTH-27): 手札に加えるマナを確定させる。★Batch 58
            case LEYLINE_AWAKENING_TO_HAND -> ctx.actions().returnManaToHandAt(
                    ctx.room(), ctx.owner(), Integer.parseInt(chosen.get(0)));
            // カース・ボーン(QTE-M-DARK-2): 破壊する自分のミニオンを確定させる。★Batch 58。
            // 選択中に盤面が変わって候補が場から消えている場合は何も起きない
            // (《サモナーポップ・エンラ》と同じく instanceId で照合する)
            case CURSE_BONE_SACRIFICE -> {
                MinionInstance victim = ctx.owner().getMinionZone().stream()
                        .filter(m -> m.getInstanceId().equals(chosen.get(0)))
                        .findFirst().orElse(null);
                if (victim == null) {
                    ctx.room().addLog("【カース・ボーン】: 選んだミニオンが場に居ないため、何も起こりませんでした");
                    break;
                }
                resolveCurseBoneSacrifice(ctx, victim);
            }
            // 海淵獣ゾクシム(QTE-M-WATER-32): 【破壊時】に捨てる手札を確定させる。★Batch 53。
            // ★相手のターン中にも通る経路である(裁定214)
            case ZOKUSHIMU_DISCARD -> discardChosenHandCards(ctx, chosen, "海淵獣ゾクシム");
            // サモナーポップ・エンラ(QTE-M-DARK-31): 破壊する相手のミニオンを確定させる。★Batch 53。
            // 選択中に盤面が変わって候補が場から消えている場合は何も起きない
            // (喚ビ集ウ・アヤカシと同じく instanceId で照合する)
            case ENRA_DESTROY -> {
                MinionInstance victim = ctx.opponent().getMinionZone().stream()
                        .filter(m -> m.getInstanceId().equals(chosen.get(0)))
                        .findFirst().orElse(null);
                if (victim == null) {
                    ctx.room().addLog("【サモナーポップ・エンラ】: 選んだミニオンが場に居ないため、何も起こりませんでした");
                    break;
                }
                ctx.actions().destroyMinion(ctx.room(), ctx.opponent(), victim);
            }
            // 灰ノ霊呼者(QTE-M-WIND-32): 手札から場に出す【破壊時】持ちを確定させる。★Batch 53。
            // ★効果による「出す」なので【召喚時】は発動せず、登場時(ON_ENTER)のみが発動する
            case ASHINO_REIKOSHA_SUMMON -> {
                int summoned = 0;
                for (String cardId : takeHandCardsAt(ctx.owner(), chosen)) {
                    if (ctx.actions().isFieldEntryBlocked(ctx.room(), ctx.owner())) {
                        ctx.owner().getHand().add(cardId); // 出せなかった分は手札に戻す
                        continue;
                    }
                    if (ctx.actions().putIntoFieldByEffect(ctx.room(), ctx.owner(), cardId) != null) {
                        summoned++;
                    }
                }
                ctx.room().addLog("【灰ノ霊呼者】: 【破壊時】を持つミニオン%d体が場に出ました".formatted(summoned));
            }
            // 英術・スケアロック(QTE-M-LIGHT-39): 出す進化ミニオンを確定させ、素材を選ばせる。★Batch 53。
            // ★カードはまだ手札から抜かない —— 素材が確定して実際に場へ出るときに抜く。
            //   途中で盤面が変わって出せなくなっても、カードがどこにも無い状態を作らないためである
            case SCARELOCK_EVOLUTION -> {
                String cardId = ctx.owner().getHand().get(Integer.parseInt(chosen.get(0)));
                EvolutionSpec spec = evolutions.get(cardId);
                List<String> materials = ctx.owner().getMinionZone().stream()
                        .filter(spec.material())
                        .map(MinionInstance::getInstanceId)
                        .toList();
                if (materials.size() < spec.minMaterials()) {
                    // 候補を作った時点では足りていた。ここへ来るのは選択中に盤面が変わった場合だけである
                    ctx.room().addLog("【英術・スケアロック】: 進化素材が足りなくなったため、場に出せませんでした");
                    break;
                }
                ctx.owner().setPendingEvolutionCardId(cardId);
                int max = Math.min(spec.maxMaterials(), materials.size());
                ctx.actions().requestChoice(ctx.room(), ctx.owner(), new PendingChoice(
                        PendingChoice.Kind.MINION, materials, spec.minMaterials(), max,
                        ResumePoint.SCARELOCK_MATERIAL,
                        "【%s】の進化素材を選んでください(%s)"
                                .formatted(cards.findById(cardId).name(), spec.description())));
            }
            // 英術・スケアロック(QTE-M-LIGHT-39): 素材を確定させて進化ミニオンを場に出す。★Batch 53
            case SCARELOCK_MATERIAL -> resolveScarelockMaterials(ctx, chosen);
            // 白ノ霊知者(QTE-M-WIND-31): 【召喚時】に破壊するミニオンを確定させる。★Batch 54。
            // ★候補は両者の場から作ってあるので、どちら側に居るかを探し直す
            case HAKUNO_REICHISHA_DESTROY -> {
                for (PlayerState side : List.of(ctx.owner(), ctx.opponent())) {
                    MinionInstance victim = side.getMinionZone().stream()
                            .filter(m -> m.getInstanceId().equals(chosen.get(0)))
                            .findFirst().orElse(null);
                    if (victim != null) {
                        ctx.actions().destroyMinion(ctx.room(), side, victim);
                        break;
                    }
                }
            }
            // 愚乱怒土地(QTE-M-EARTH-30): 見た2枚のうち、裏向きでマナに置く1枚を確定させる。★Batch 54
            case GURANDORANDO_MANA -> resolveGurandorandoChoice(ctx, chosen);
        }
    }

    /**
     * 《愚乱怒土地》の【賢魂：3】の後始末(★Batch 54)。
     *
     * 選んだ1枚は裏向きでマナへ、残りは手札へ入る。
     * ★<b>マナが上限で置けなかった1枚は山札の上に戻す</b>(マスター裁定)。
     * 墓地に落とすと「山札から墓地へ」という経路が1つ増えてしまう。
     * ★公開領域は必ず空にする —— 残すと本人のビューに出たままになる。
     */
    private void resolveGurandorandoChoice(EffectContext ctx, List<String> chosen) {
        List<String> revealed = new ArrayList<>(ctx.owner().getRevealedZone());
        ctx.owner().getRevealedZone().clear();
        int toMana = chosen.isEmpty() ? -1 : Integer.parseInt(chosen.get(0));
        for (int i = 0; i < revealed.size(); i++) {
            String cardId = revealed.get(i);
            if (i != toMana) {
                ctx.owner().getHand().add(cardId);
                ctx.room().addLog("【愚乱怒土地】: 見た1枚を手札に加えました");
                continue;
            }
            if (!ctx.actions().placeCardInManaFaceDown(ctx.room(), ctx.owner(), cardId)) {
                // 置けなかったぶんは山札の上へ戻す(見ただけの状態に戻る)
                ctx.owner().getDeck().addFirst(cardId);
                ctx.room().addLog("【愚乱怒土地】: マナが上限のため、1枚は山札の上に戻りました");
                continue;
            }
            ctx.room().addLog("【愚乱怒土地】: 見た1枚を裏向きでマナに置きました(マナ%d枚)"
                    .formatted(ctx.owner().getManaZone().size()));
        }
    }

    /** 選ばれた手札を捨てる(★Batch 53。ラカブ・ゾクシム共通の後始末) */
    /**
     * カース・ボーン(QTE-M-DARK-2)の【召喚時】の後半(★Batch 58)。
     * 選ばれた自分のミニオンを破壊し、<b>その印刷コストと同じ枚数</b>だけ山札の上を墓地に置く。
     *
     * ★<b>コストは破壊する前に読む。</b>破壊した後では場から居なくなっており、
     * 「破壊したミニオンのコスト」を数えようがない(裁定216 の「置換される事象が起きる前の値」と
     * 同じ向きの読み方である)。
     * ★リーダーはミニオンではないのでコストが null になることはないが、
     * 印刷コストが未設定のカードが将来入っても落ちないように 0 として扱う。
     */
    private void resolveCurseBoneSacrifice(EffectContext ctx, MinionInstance victim) {
        Integer printedCost = victim.getMaster().cost();
        int millCount = printedCost == null ? 0 : printedCost;
        ctx.room().addLog("【カース・ボーン】: 【%s】(コスト%d)を破壊します"
                .formatted(victim.getMaster().name(), millCount));
        ctx.actions().destroyMinion(ctx.room(), ctx.owner(), victim);
        if (millCount > 0) {
            ctx.actions().mill(ctx.room(), ctx.owner(), millCount);
        }
    }

    private void discardChosenHandCards(EffectContext ctx, List<String> chosen, String cardName) {
        for (String cardId : takeHandCardsAt(ctx.owner(), chosen)) {
            ctx.room().addLog("【%s】: 【%s】を捨てました"
                    .formatted(cardName, cards.findById(cardId).name()));
            // 「場以外から自分の墓地へ」の入口を通す(カムバックキーパーが反応する。裁定207)
            ctx.actions().putIntoTrashFromElsewhere(ctx.room(), ctx.owner(), cardId);
        }
    }

    /**
     * 《英術・スケアロック》の最終段 —— 素材を確定させて進化ミニオンを効果で場に出す(★Batch 53)。
     *
     * ★<b>手札から抜くのは、場に出すことが確定した後である。</b>
     * 抜いてから出せないと分かると、カードがどのゾーンにも居ない瞬間が生まれる
     * (51 の「先に場から取り除くと行き先の無いカードが生まれる」と同じ形)。
     */
    private void resolveScarelockMaterials(EffectContext ctx, List<String> chosen) {
        String cardId = ctx.owner().getPendingEvolutionCardId();
        ctx.owner().setPendingEvolutionCardId(null);
        if (cardId == null) {
            return;
        }
        List<MinionInstance> materials = new ArrayList<>();
        for (String instanceId : chosen) {
            ctx.owner().getMinionZone().stream()
                    .filter(m -> m.getInstanceId().equals(instanceId))
                    .findFirst().ifPresent(materials::add);
        }
        EvolutionSpec spec = evolutions.get(cardId);
        if (spec == null || materials.size() < spec.minMaterials()) {
            ctx.room().addLog("【英術・スケアロック】: 進化素材が足りなくなったため、場に出せませんでした");
            return;
        }
        int handIndex = ctx.owner().getHand().indexOf(cardId);
        if (handIndex < 0) {
            return; // 選択中に手札を離れた(通常は起きない)
        }
        ctx.owner().getHand().remove(handIndex);
        MinionInstance put = ctx.actions()
                .putIntoFieldByEffect(ctx.room(), ctx.owner(), cardId, materials);
        if (put == null) {
            // 場に出られなかった。素材は場に残っている(裁定232)ので、カードは手札へ戻す。
            // ★モアニールの置換で山札の下へ行った場合は行き先が決まっているので戻さない
            if (!ctx.owner().getDeck().contains(cardId)) {
                ctx.owner().getHand().add(cardId);
            }
            ctx.room().addLog("【英術・スケアロック】: 【%s】は場に出られませんでした"
                    .formatted(cards.findById(cardId).name()));
            return;
        }
        ctx.room().addLog("【英術・スケアロック】: 【%s】が効果で進化召喚されました"
                .formatted(cards.findById(cardId).name()));
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

    /**
     * 進化18枚の召喚素材の条件(★Batch 52。裁定154・157)。
     *
     * <h2>★18枚すべてをこのバッチで登録する</h2>
     *
     * 効果を実装するのは Batch 52・53 に分かれるが、<b>素材条件だけは18枚まとめて書く</b>。
     * 素材条件が無い進化はデッキに入れても手札で完全な死に札になり、それは
     * 裁定166 が進化をデッキ構築で弾いていた理由そのものだからである。
     * 条件を全部書いておけば、デッキ構築の解禁を「一部だけ」にしなくて済む ——
     * 効果が未実装のものは<b>出せるが効果が起きない</b>という、裁定D2 の普通の姿になる。
     *
     * <h2>★素材は常に自分の場である(マスター裁定 A2)</h2>
     *
     * 本文が「自分の」と書いていないものが6枚あるが、素材は自分の進化ミニオンの下に
     * 置かれるものなので、相手の場から取れると事実上の最強除去になる。
     *
     * <h2>★「ミニオン」は進化ミニオンも含む(裁定211 の適用)</h2>
     *
     * 「水文明のミニオン1体」のような書き方に「進化ではない」という限定は無い。
     * 《追撃鉄人連太》がわざわざ「自分の進化ミニオン1体」と書いているのは、
     * <b>進化に限定するため</b>であって、他のカードが進化を除外する根拠にはならない。
     * 限定の有無をそのまま写す(裁定211)。
     *
     * <h2>「体力」は現在HPで見る</h2>
     *
     * 既存の {@link TargetSpec.Filter#HP_5_OR_LESS} が現在HPを見ているのに揃える。
     * ダメージを受けて体力が落ちたミニオンは、その落ちた値で判定される。
     */
    private void registerEvolutionMaterials() {
        // ---- 水文明 ----
        registerEvolution(SHIRAKA, EvolutionSpec.exactly(1,
                "水文明の【潜伏】を持たないミニオン1体",
                m -> m.getMaster().civilization() == Civilization.WATER
                        && !m.hasKeyword(Keyword.STEALTH)));
        registerEvolution("QTE-M-WATER-31", EvolutionSpec.exactly(1,
                "水文明の【潜伏】を持つミニオン1体",
                m -> m.getMaster().civilization() == Civilization.WATER
                        && m.hasKeyword(Keyword.STEALTH)));
        registerEvolution("QTE-M-WATER-32", EvolutionSpec.exactly(1,
                "水文明ではないミニオン1体",
                m -> m.getMaster().civilization() != Civilization.WATER));

        // ---- 火文明 ----
        // ★「自分ミニオン1体以上」だけが数に上限を持たない。素材を増やすほど大きくなる
        registerEvolution(TOUTA, EvolutionSpec.atLeast(1, TOUTA_STAT_PER_UNDER_CARD,
                "自分のミニオン1体以上", m -> true));
        registerEvolution("QTE-M-FIRE-31", EvolutionSpec.exactly(1,
                "自分の進化ミニオン1体", MinionInstance::isEvolution));
        registerEvolution("QTE-M-FIRE-32", EvolutionSpec.exactly(1,
                "ミニオン1体", m -> true));

        // ---- 闇文明 ----
        registerEvolution("QTE-M-DARK-30", EvolutionSpec.exactly(1,
                "闇文明の体力4以上のミニオン1体",
                m -> m.getMaster().civilization() == Civilization.DARK && m.getCurrentHp() >= 4));
        registerEvolution("QTE-M-DARK-31", EvolutionSpec.exactly(1,
                "ミニオン1体", m -> true));
        // ★18枚で唯一、素材を2体要求する。引き継ぎは全素材分を合算する(マスター裁定 B1)
        registerEvolution("QTE-M-DARK-32", EvolutionSpec.exactly(2,
                "闇文明のミニオン2体",
                m -> m.getMaster().civilization() == Civilization.DARK));

        // ---- 光文明 ----
        registerEvolution("QTE-M-LIGHT-30", EvolutionSpec.exactly(1,
                "【守護】を持つ体力2以上のミニオン1体",
                m -> m.hasKeyword(Keyword.GUARD) && m.getCurrentHp() >= 2));
        registerEvolution("QTE-M-LIGHT-31", EvolutionSpec.exactly(1,
                "光文明のミニオン1体",
                m -> m.getMaster().civilization() == Civilization.LIGHT));
        registerEvolution("QTE-M-LIGHT-32", EvolutionSpec.exactly(1,
                "自分の【守護】を持つ光文明のミニオン1体",
                m -> m.hasKeyword(Keyword.GUARD)
                        && m.getMaster().civilization() == Civilization.LIGHT));

        // ---- 風文明 ----
        registerEvolution("QTE-M-WIND-30", EvolutionSpec.exactly(1,
                "風文明のミニオン1体",
                m -> m.getMaster().civilization() == Civilization.WIND));
        registerEvolution("QTE-M-WIND-31", EvolutionSpec.exactly(1,
                "風文明のミニオン1体",
                m -> m.getMaster().civilization() == Civilization.WIND));
        registerEvolution("QTE-M-WIND-32", EvolutionSpec.exactly(1,
                "風文明のミニオン1体",
                m -> m.getMaster().civilization() == Civilization.WIND));

        // ---- 土文明 ----
        registerEvolution("QTE-M-EARTH-30", EvolutionSpec.exactly(1,
                "土文明のミニオン1体",
                m -> m.getMaster().civilization() == Civilization.EARTH));
        registerEvolution("QTE-M-EARTH-31", EvolutionSpec.exactly(1,
                "土文明のミニオン1体",
                m -> m.getMaster().civilization() == Civilization.EARTH));
        registerEvolution("QTE-M-EARTH-32", EvolutionSpec.exactly(1,
                "土文明のミニオン1体",
                m -> m.getMaster().civilization() == Civilization.EARTH));
    }

    /**
     * Batch 52 が効果まで実装する進化ミニオンと、進化スタックを操作するリーダー。
     *
     * ★<b>《海淵獣シラーカ》と《不敗鉄人闘太》はここに現れない。</b>
     * 前者は効果の文が素材条件だけ、後者は【常在】の中身が
     * {@link EvolutionSpec#statPerUnderCard()} に載っているためである。
     * どちらも {@link #IMPLEMENTED_CARDS} で名乗っている(裁定176)。
     *
     * ★《追撃鉄人連太》(2回攻撃)と《サービスブレイク・メリィナ》(コスト軽減・味方強化)は
     * {@link StatCalculator} にある。数値の評価はあちらの担当である。
     */
    private void registerEvolutionCards() {

        // ---- 飛翔鉄人走太(QTE-M-FIRE-32) ----
        // 「【進化】(ミニオン1体)【特殊召喚】(場にミニオンが3体以上いる時
        //   このカードは手札から場に0コストとして出せる)」
        //
        // ★<b>特殊召喚でも素材は要る</b>(マスター裁定 D1)。特殊召喚が代替しているのは
        //   コストだけであり、進化であることを止めるものではない。
        // ★「場に」に「自分の」が付いていないので両者の場を数える(記法規約。裁定156(2))
        specialSummons.put("QTE-M-FIRE-32", SpecialSummonSpec.of(
                (state, player, handIndex) -> countMinionsOnAnyField(state) >= 3,
                TargetSpec.of(),
                ctx -> {
                },
                "場にミニオンが3体以上います: 0コストで進化召喚します"));

        // ---- 裏雷怒乗込(QTE-M-EARTH-31) ----
        // 「【進化】(土文明のミニオン1体)【守護】攻撃時カードを1枚引く。」
        //
        // ★【守護】はテキストから抽出される(裁定158)。登録が要るのは攻撃時だけである。
        // ★攻撃時の効果が割り込みを出さないので、Batch 51 が作った戦闘の保留には掛からない
        register("QTE-M-EARTH-31", TriggerType.ON_ATTACK, ctx -> {
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
            ctx.room().addLog("【裏雷怒乗込】: 攻撃時に1枚ドロー");
        });

        // ---- 武羅須斗最終(QTE-M-EARTH-32) ----
        // 「【進化】(土文明のミニオン1体)【特殊召喚】(自分のマナが7枚以上のとき
        //   このカードを手札からコスト1として場に出せる。)【守護】【還元】」
        //
        // ★「マナが7枚以上」はマナゾーンの枚数である(裁定201 と同じ読み)。
        //   タップ・向きは問わない。支払う1マナは mpCost で表す
        specialSummons.put("QTE-M-EARTH-32", new SpecialSummonSpec(
                (state, player, handIndex) -> player.getManaZone().size() >= 7,
                1,
                TargetSpec.of(),
                ctx -> {
                },
                ctx -> {
                },
                "自分のマナが7枚以上あります: コスト1で進化召喚します"));

        // ---- 機神兵長茶爺(QTE-M-FIRE-29・リーダー) ----
        // 「【起動：1】場の進化ミニオン1枚の下に手札からミニオンを選び入れる。
        //   そうしたらカードを1枚引く。」
        //
        // ★<b>進化スタックそのものを要求する唯一の非進化カード</b>であり、
        //   裁定215 が「P3 送り」と名指ししたカードである。
        // ★「場の」に「自分の」が付いていないが、<b>自分の場に限る</b>(マスター裁定)。
        //   進化スタックを厚くするのは自分の盤面への行為であり、
        //   相手の進化を育てる読みは採らない ——「自分の」の省略はここでも自明とみなす
        //   (進化素材が常に自分の場である、と同じ判断。マスター裁定 A2)。
        // ★「そうしたら」なので、下に入れられたときだけ引く。
        //   進化ミニオンが自分の場に1体も居なければ起動能力そのものが使えない(condition)
        leaderAbilities.put("QTE-M-FIRE-29", new LeaderAbilitySpec(
                1,
                TargetSpec.of(
                        Requirement.filtered(Kind.HAND, Side.SELF, 1, false,
                                "進化ミニオンの下に入れるミニオンを選んでください",
                                TargetSpec.Filter.MINION_CARD),
                        Requirement.filtered(Kind.MINION, Side.SELF, 1, false,
                                "カードを下に入れる進化ミニオンを選んでください",
                                TargetSpec.Filter.EVOLUTION_MINION)),
                ctx -> {
                    String cardId = ctx.targets().get(0).handCardIds().get(0);
                    ResolvedTargets.TargetedMinion target = ctx.targets().get(1).minions().get(0);
                    // 手札から抜いたカードは、束の中では「入れた側の出自」を持たない ——
                    // 手札のカードは禁忌デッキ由来ではないので false で足りる
                    target.minion().putUnder(new com.example.qte.game.StackedCard(cardId, false));
                    ctx.room().addLog("【機神兵長茶爺】: 【%s】を【%s】の下に入れました"
                            .formatted(cards.findById(cardId).name(),
                                    target.minion().getMaster().name()));
                    ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
                },
                (state, player) -> player.getMinionZone().stream().anyMatch(MinionInstance::isEvolution),
                "1マナ: 自分の進化ミニオン1枚の下に手札のミニオンを入れ、1枚引く"));
    }

    // ---------------------------------------------------------------
    // ★Batch 53: 盤面を動かす効果を持つ進化ミニオン7枚と、進化を効果で出すスペル1枚。
    //
    // Batch 52 の進化7枚は「素材条件と数だけで動くもの」「特殊召喚」「リーダー」であり、
    // 効果そのものは既存の形の写しで済んだ。53 が扱うのは<b>盤面を動かす進化</b>である ——
    // 墓地から3体並べ、手札から2体並べ、相手のスペルを重くし、相手の展開を1体に縛る。
    //
    // ★《英術・スケアロック》だけがスペルであり、53 の本体でもある ——
    //   <b>効果から進化召喚を起こす初めてのカード</b>である(裁定226)。
    // ★《英霊・ニュウキロ》の常在(相手のスペルのコスト+手札の数)は StatCalculator に、
    //   《英霊・コレキ》の常在(相手は1ターンに1体しか出せない)は RuleGuards にある(裁定180)。
    // ★《リボーンライヴ・ノア》の常在(墓地から出たミニオンは【突進】)は
    //   fireMinionEnteredFromGrave に直接書いてある(演舞の墓守と同じ発火口)。
    // ---------------------------------------------------------------
    private void registerEvolutionEffectCards() {

        // ---- 海淵獣ラカブ(QTE-M-WATER-31) ----
        // 「【進化】(水文明の潜伏を持つミニオン1体)【召喚時】カードを3枚引く。
        //   その後カードを1枚捨てる。」
        //
        // 引いた後の手札から捨てるので、使用宣言時に選び終える TargetSpec では表現できない。
        // アクア・サーチ(QTE-M-WATER-25)と同じ割り込み(a9)を使う。捨てるのは必須(min=1)
        register("QTE-M-WATER-31", TriggerType.ON_SUMMON, ctx -> {
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 3);
            requestDiscard(ctx, 1, 1, ResumePoint.RAKABU_DISCARD,
                    "【海淵獣ラカブ】: 捨てる手札を1枚選んでください");
        });

        // ---- 海淵獣ゾクシム(QTE-M-WATER-32) ----
        // 「【進化】(水文明ではないミニオン1体)カードを2枚引く。【破壊時】カードを2枚捨てる。」
        //
        // ★前半に誘発の印が無いが、<b>【召喚時】として扱う</b>(マスター裁定)。
        //   同じ水の進化である《海淵獣ラカブ》が明示的に【召喚時】と書いているので、
        //   書き分けを尊重した —— 効果で場に出した場合(《英術・スケアロック》)は引かない。
        // ★【破壊時】の2枚は<b>本人が選ぶ</b>。相手のターン中にも発火するが、
        //   裁定214 により本人への問い合わせでよい(50 までは自動決定しかできなかった)。
        // ★手札が2枚に満たなければ、あるだけ捨てる(裁定191・217 と同じ形)
        register("QTE-M-WATER-32", TriggerType.ON_SUMMON,
                ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 2));
        register("QTE-M-WATER-32", TriggerType.ON_DESTROYED, ctx -> {
            int count = Math.min(2, ctx.owner().getHand().size());
            requestDiscard(ctx, count, count, ResumePoint.ZOKUSHIMU_DISCARD,
                    "【海淵獣ゾクシム】: 捨てる手札を%d枚選んでください".formatted(count));
        });

        // ---- リボーンライヴ・ノア(QTE-M-DARK-30) ----
        // 「【進化】(闇文明の体力4以上のミニオン1体)【召喚時】自分の墓地から
        //   コスト3以下のミニオンを3体場に出す。
        //   【常在】自分のミニオンが墓地から場に出た時そのミニオンは【突進】を得る」
        //
        // ★出す3体は<b>本人が選ぶ</b>(マスター裁定。裁定192 を墓地にも適用した)。
        //   《冥界神ハデス》《サモンズライト》は自動決定だが、あちらは
        //   「破壊した後に蘇生する」「相手のターンにも起きる」という構造上の理由があった。
        //   これは自分のメインフェイズの【召喚時】なので、使用宣言時に選ばせられる。
        // ★3体に満たなければ居るだけ出す(裁定191)。Requirement.upTo が「あるだけ」の既存の形。
        // ★【常在】の【突進】付与は fireMinionEnteredFromGrave にある。
        //   <b>この【召喚時】で出す3体にも乗る</b>(マスター裁定) ——
        //   ノアは既に場に居るので、あちらの「場にノアが居るか」が真になる。
        // ★表への登録は<b>カードIDのリテラルのまま</b>にする(落とし穴「効果の実装状況」) ——
        //   tools/report_effects.py は登録の<b>左辺の書式</b>を走査して数えるので、
        //   定数に置き換えると数え落とす(実際に 53 で 7枚 が 8枚 とずれた)。
        //   定数化が要るのは<b>ルール側の判定点</b>(if 文でIDを比べる箇所)である(裁定130)。
        //   ★ここに走査の対象になる書式を例として書かないこと —— 注釈が番人を騙す(裁定114)。
        targetSpecs.put("QTE-M-DARK-30", TargetSpec.of(Requirement.upTo(Kind.TRASH, Side.SELF, 3,
                "場に出す自分の墓地のミニオンを3体まで選んでください",
                Filter.MINION_CARD, Filter.COST_3_OR_LESS)));
        register("QTE-M-DARK-30", TriggerType.ON_SUMMON, ctx -> {
            int summoned = 0;
            for (String cardId : ctx.targets().get(0).trashCardIds()) {
                // 墓地から場へ出す経路は reviveFromGrave 1本である(裁定204)。
                // 「出せるか」の判定もあちらが持つ(場が満杯・踏み倒し禁止・コレキ)
                if (ctx.actions().reviveFromGrave(ctx.room(), ctx.owner(), cardId) != null) {
                    summoned++;
                }
            }
            ctx.room().addLog("【リボーンライヴ・ノア】: 墓地から%d体を場に出しました".formatted(summoned));
        });

        // ---- サモナーポップ・エンラ(QTE-M-DARK-31) ----
        // 「【進化】(ミニオン1体)【特殊召喚】(自分の墓地にミニオンが6体以上のとき
        //   自分の手札または墓地からコスト1支払って場に出せる。)
        //   場に出た時相手のコスト3以下のミニオン1体を破壊。」
        //
        // ★<b>「自分の手札または墓地から」= 墓地からの特殊召喚</b>という新しい出どころである。
        //   {@link SpecialSummonSpec#fromGrave()} を true にすると
        //   GameService.specialSummonFromGrave が使えるようになる。
        // ★墓地に居るエンラ自身も「6体」に数える(マスター裁定。裁定190 と同じ形) ——
        //   条件の評価は墓地から取り除く前に行われるので、素直に数えればそうなる。
        // ★<b>特殊召喚でも素材は要る</b>(裁定226)。代替されているのはコストだけである。
        // ★「場に出た時」は<b>登場時(ON_ENTER)</b>である(マスター裁定) ——
        //   【召喚時】と書いていない登場の誘発は経路を問わない(裁定193)。
        //   したがって対象は使用宣言時ではなく<b>解決中に</b>選ばせる ——
        //   効果で出した場合には ctx.targets() が無いためである。
        specialSummons.put("QTE-M-DARK-31", new SpecialSummonSpec(
                (state, player, handIndex) -> countMinionsInTrash(player) >= 6,
                1,
                TargetSpec.of(),
                ctx -> {
                },
                ctx -> {
                },
                "自分の墓地にミニオンが6体以上います: コスト1で進化召喚します",
                true));
        register("QTE-M-DARK-31", TriggerType.ON_ENTER, ctx -> {
            // 【潜伏】持ちは相手の効果の対象にならない(既存の原則)ので候補から外す
            List<String> candidates = ctx.opponent().getMinionZone().stream()
                    .filter(m -> !m.hasKeyword(Keyword.STEALTH))
                    .filter(m -> m.getMaster().cost() != null && m.getMaster().cost() <= 3)
                    .map(MinionInstance::getInstanceId)
                    .toList();
            if (candidates.isEmpty()) {
                ctx.room().addLog("【サモナーポップ・エンラ】: 破壊できる相手のミニオンが居ません");
                return;
            }
            ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.one(
                    PendingChoice.Kind.MINION, candidates, ResumePoint.ENRA_DESTROY,
                    "【サモナーポップ・エンラ】: 破壊する相手のコスト3以下のミニオンを1体選んでください"));
        });

        // ---- 灰ノ霊呼者(QTE-M-WIND-32) ----
        // 「【進化】(風文明のミニオン1体)【召喚時】:【破壊時】を持つミニオンを手札から2体場に出す。」
        //
        // ★「【破壊時】を持つ」の判定は<b>本文に【破壊時】と書いてあるか</b>である(マスター裁定)。
        //   キーワードの正はテキストである(裁定158)の延長で、効果がまだ未実装のカードも該当する。
        // ★<b>この判定を TargetSpec.Filter に足さなかった</b>(裁定234) ——
        //   足すと同じ文字列走査が battle.js にも生まれる(裁定163・195)。
        //   種別や文明と違って、これは<b>規則</b>である。サーバが候補を絞って送る。
        // ★2体に満たなければ居るだけ出す(裁定191)。出すのは効果なので【召喚時】は発動しない。
        register("QTE-M-WIND-32", TriggerType.ON_SUMMON, ctx -> {
            List<String> candidates = new ArrayList<>();
            for (int i = 0; i < ctx.owner().getHand().size(); i++) {
                CardMaster m = cards.findById(ctx.owner().getHand().get(i));
                if (m.type() == CardType.MINION && m.text() != null
                        && m.text().contains(ON_DESTROYED_MARK)) {
                    candidates.add(String.valueOf(i));
                }
            }
            if (candidates.isEmpty()) {
                ctx.room().addLog("【灰ノ霊呼者】: 手札に【破壊時】を持つミニオンが居ません");
                return;
            }
            ctx.actions().requestChoice(ctx.room(), ctx.owner(), new PendingChoice(
                    PendingChoice.Kind.HAND, candidates,
                    Math.min(2, candidates.size()), Math.min(2, candidates.size()),
                    ResumePoint.ASHINO_REIKOSHA_SUMMON,
                    "【灰ノ霊呼者】: 場に出す【破壊時】持ちのミニオンを選んでください"));
        });

        // ---- 英術・スケアロック(QTE-M-LIGHT-39・スペル) ----
        // 「自分の手札から光文明のコスト3以下のミニオンを1体場に出す。
        //   その後自分の手札から【進化】を持つ光文明ミニオンを1体場に出す。」
        //
        // ★<b>53 の本体である</b> —— 効果から進化を出す初めてのカードで、素材を要求する(裁定226)。
        // ★1体目は使用宣言時に選ぶ(条件が盤面に依存しないので TargetSpec で足りる)。
        //   2体目は<b>1体目を出した後でなければ候補が決まらない</b> ——
        //   直前に出した1体目を素材にできる(マスター裁定)ためである。
        // ★素材条件を満たすミニオンが場に居ない進化カードは<b>そもそも候補に入れない</b>
        //   (マスター裁定)。裁定227(条件を満たす素材が居なければ使用できない)の効果版である。
        // ★効果で出すので【召喚時】は発動しない(マスター裁定)。登場時(ON_ENTER)のみである。
        targetSpecs.put("QTE-M-LIGHT-39", TargetSpec.of(Requirement.upTo(Kind.HAND, Side.SELF, 1,
                "コストを支払わず場に出す光文明のコスト3以下のミニオンを選んでください",
                Filter.LIGHT_CIVILIZATION, Filter.COST_3_OR_LESS, Filter.MINION_CARD)));
        spellEffects.put("QTE-M-LIGHT-39", ctx -> {
            for (String id : ctx.targets().get(0).handCardIds()) {
                // 「出せるか」を呼ぶ前に自分で見る(神の福音と同じ)。
                // putIntoFieldByEffect の null には「場に出られなかった」と
                // 「山札の下へ置き換えられた」の2つの意味がある(50 の教訓)
                if (ctx.actions().isFieldEntryBlocked(ctx.room(), ctx.owner())) {
                    ctx.owner().getHand().add(id);
                    continue;
                }
                ctx.actions().putIntoFieldByEffect(ctx.room(), ctx.owner(), id);
            }
            requestScarelockEvolution(ctx);
        });
    }

    /**
     * 【賢魂】を持つ7枚(★Batch 54。裁定152)。P4 でエンジンと同時に入れる。
     *
     * <h2>1枚のカードに2つの姿がある</h2>
     *
     * <ul>
     * <li><b>ミニオンとしての姿</b>は、これまでどおり {@code triggers} / {@code targetSpecs} に載る。
     *     ★<b>ミニオンとして召喚した場合、賢魂の効果は発動しない</b>(裁定152)ので、
     *     ここで賢魂の効果をトリガーに登録してはいけない。</li>
     * <li><b>スペルとしての姿</b>は {@link #soulSpells} に載る。
     *     コスト n はカードテキストが持つ({@code CardTextKeywords.soulCost})。</li>
     * </ul>
     *
     * ★<b>進化4枚の素材条件は 52 で登録済みである</b>({@code registerEvolutionMaterials})。
     * 54 が足すのは効果だけである。
     *
     * ★<b>《勝阿外》はこの表に賢魂しか載せない。</b>【常在】の2つ
     * (相手はスペルを唱えられない / 相手の手札の枚数だけ Attack+1)は
     * {@link RuleGuards} と {@link StatCalculator} の判定点にある ——
     * あの2つのクラスの {@code IMPLEMENTED_CARDS} には<b>足していない</b>。
     * この表に載った時点で {@link #isRegistered(String)} が真を返すので、
     * 足しても<b>外して何も落ちない宣言</b>が増えるだけである(53 のノアと同じ理由)。
     */
    private void registerSoulCards() {

        // ---- グレイヴガールズファン(QTE-M-DARK-37) ----
        // 「【守護】【賢魂：１】カードを1枚引く。その後自分の山札の上から1枚目を墓地に置く」
        //
        // ★ミニオンとしての姿は【守護】だけで、効果の文を持たない(登録は賢魂だけ)。
        // ★このカードだけ n が全角の「１」で書かれている(マスター確認済み・データは直さない)。
        //   読む側の CardTextKeywords が全角も半角も取る
        soulSpells.put("QTE-M-DARK-37", SoulSpellSpec.of(ctx -> {
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
            // 「山札の上から1枚目を墓地に置く」= ミル。場を経由しない墓地送りである(裁定207)
            ctx.actions().mill(ctx.room(), ctx.owner(), 1);
        }));

        // ---- スタンディングテント(QTE-M-DARK-38) ----
        // 「【守護】【召喚時】カードを2枚引く。
        //   【賢魂：2】このミニオンを場に出す。そのミニオンの【召喚時】は使えない。そのミニオンに2ダメージ。」
        //
        // ★★<b>賢魂の効果が、使用しているカード自身を場に出す唯一の例</b>である。
        //   6コスト 1/6【守護】が、2コストで【召喚時】の2ドロー抜き・2ダメージ入りで出る。
        // ★本文の「そのミニオンの【召喚時】は使えない」は、
        //   効果による「場に出す」は【召喚時】を発動しない(裁定245)という既定と一致する ——
        //   <b>本文が念を押しているだけで、例外を作ってはいない。</b>
        register("QTE-M-DARK-38", TriggerType.ON_SUMMON,
                ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 2));
        soulSpells.put("QTE-M-DARK-38", SoulSpellSpec.of(ctx -> {
            // ★「出せるか」を呼ぶ前に自分で見る(神の福音と同じ)。
            //   putIntoFieldByEffect の null には2つの意味があり(50 の教訓)、
            //   ここで見分けないと<b>カードが2枚に増えるか、宙に浮いて消える</b>。
            // ★出せない場合、このカードは通常のスペルと同じく墓地へ行く(マスター裁定 B6-2)。
            //   行き先を書き込まずに戻れば、GameService がそうしてくれる
            if (ctx.actions().isFieldEntryBlocked(ctx.room(), ctx.owner())) {
                ctx.room().addLog("【スタンディングテント】: 場に出せないため、墓地に置かれます");
                return;
            }
            MinionInstance placed = ctx.actions().putIntoFieldByEffect(
                    ctx.room(), ctx.owner(), "QTE-M-DARK-38", List.of(), ctx.fromTaboo());
            // ここまで来たら行き先はもう決まっている ——
            // 場に出た(placed != null)か、《光霊・モアニール》が山札の下(禁忌なら消滅)へ置いたかである。
            // どちらにせよ、使用後の処理でもう一度動かしてはいけない
            ctx.owner().setPendingSpellDisposition(SpellDisposition.KEPT_BY_EFFECT);
            if (placed != null) {
                // ★2ダメージは<b>場に出た後</b>である(登場時の効果がすべて解決してから。マスター裁定 B6-3)
                ctx.actions().damageMinion(ctx.room(), ctx.owner(), placed, 2);
            }
        }));

        // ---- 英霊・タイガラム(QTE-M-LIGHT-32・進化) ----
        // 「【進化】（自分の守護を持つ光文明のミニオン1体）
        //   【召喚時】自分の手札から守護を持つ進化ではないミニオンを1体場に出す。
        //   【守護】【賢魂：3】カードを2枚引く。」
        //
        // ★<b>賢魂を持つ進化で、唯一【召喚時】も持つ</b>。
        //   これにより《英術・スケアロック》で効果から出したときに
        //   <b>【召喚時】が発動しないこと</b>が本物の入口から観測できる(53 設計解説 6-1 の宿題)。
        // ★「進化ではない」は Filter.MINION_CARD がそのまま表す
        //   (種別 EVOLUTION は MINION ではない)。走査を伴う判定ではないので
        //   クライアントへ送ってよい(裁定234 の線引き)。
        // ★候補が手札に無くても召喚できる(マスター裁定 B4-1)ので upTo である
        targetSpecs.put("QTE-M-LIGHT-32", TargetSpec.of(Requirement.upTo(Kind.HAND, Side.SELF, 1,
                "場に出す【守護】を持つ進化ではないミニオンを1体選んでください(選ばなくてもよい)",
                Filter.GUARD, Filter.MINION_CARD)));
        register("QTE-M-LIGHT-32", TriggerType.ON_SUMMON, ctx -> {
            for (String id : ctx.targets().get(0).handCardIds()) {
                if (ctx.actions().isFieldEntryBlocked(ctx.room(), ctx.owner())) {
                    ctx.owner().getHand().add(id); // 出せなかった分は手札に戻す(神の福音と同じ)
                    continue;
                }
                ctx.actions().putIntoFieldByEffect(ctx.room(), ctx.owner(), id);
            }
        });
        soulSpells.put("QTE-M-LIGHT-32",
                SoulSpellSpec.of(ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 2)));

        // ---- 黒ノ霊導者(QTE-M-WIND-30・進化) ----
        // 「【進化】（風文明のミニオン1体）【守護】
        //   【賢魂：1】自分のミニオンを1体破壊、そうしたら相手のミニオン1体に3ダメージ与える。」
        //
        // ★ミニオンとしての姿は【守護】だけで、効果の文を持たない。
        // ★<b>自分のミニオンが1体も居なくても使用できる</b>(マスター裁定 B3-1)。
        //   その場合は何も起こらない —— だから前半も upTo である。
        // ★<b>「そうしたら」は実際に破壊できたことを指す</b>(マスター裁定 B3-3)。
        //   《大天使ミカエル》《聖光の守護聖》の置換で場に残ったなら、3ダメージは与えない。
        //   destroyMinion は成否を返さないので、破壊の前後で場に居るかを見る(2種のオニと同じ形)。
        // ★対象は2つとも<b>使用宣言時にまとめて選ぶ</b>(マスター裁定 B3-4)。
        //   割り込み(PendingChoice)を1つ増やさずに済む
        soulSpells.put("QTE-M-WIND-30", SoulSpellSpec.of(
                TargetSpec.of(
                        Requirement.upTo(Kind.MINION, Side.SELF, 1, "破壊する自分のミニオンを1体選んでください"),
                        Requirement.upTo(Kind.MINION, Side.OPPONENT, 1,
                                "3ダメージを与える相手のミニオンを1体選んでください")),
                ctx -> {
                    List<ResolvedTargets.TargetedMinion> sacrifices = ctx.targets().get(0).minions();
                    if (sacrifices.isEmpty()) {
                        ctx.room().addLog("【黒ノ霊導者】: 破壊する自分のミニオンが居ないため、何も起こりませんでした");
                        return;
                    }
                    ResolvedTargets.TargetedMinion sacrifice = sacrifices.get(0);
                    ctx.actions().destroyMinion(ctx.room(), sacrifice.owner(), sacrifice.minion());
                    if (sacrifice.owner().getMinionZone().contains(sacrifice.minion())) {
                        ctx.room().addLog("【黒ノ霊導者】: 破壊されなかったため、3ダメージは与えられません");
                        return;
                    }
                    for (ResolvedTargets.TargetedMinion victim : ctx.targets().get(1).minions()) {
                        ctx.actions().damageMinion(ctx.room(), victim.owner(), victim.minion(), 3);
                    }
                }));

        // ---- 白ノ霊知者(QTE-M-WIND-31・進化) ----
        // 「【進化】（風文明のミニオン1体）【召喚時】カードを2枚引く。ミニオンを1体選び破壊する。
        //   【賢魂：2】自分のミニオン1体の攻撃力+1する。【還元】」
        //
        // ★★<b>末尾の【還元】は賢魂としての姿にだけ付いている</b>(マスター裁定 B1)。
        //   スペルとして使い終われば裏向きでマナへ、ミニオンとして破壊されれば墓地へ行く。
        //   この分岐は CardTextKeywords が【賢魂：n】でテキストを割ることで表している ——
        //   <b>ここに書くことは何も無い</b>(規則はテキストの読み方の側にある)。
        // ★【召喚時】の破壊は<b>割り込み</b>である。TargetSpec にすると、
        //   進化の素材にした自分のミニオンを対象に選べてしまう(検証は召喚より前に走る)し、
        //   候補ゼロで召喚そのものが弾かれる(48 の落とし穴)。
        // ★側の限定が無いので<b>両者の場</b>を候補にする(裁定156(2)・マスター裁定 B2-1)。
        //   進化した自身も場に居るので、候補が0体になることは無い
        register("QTE-M-WIND-31", TriggerType.ON_SUMMON, ctx -> {
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 2);
            List<String> candidates = new ArrayList<>();
            for (PlayerState side : List.of(ctx.owner(), ctx.opponent())) {
                side.getMinionZone().forEach(m -> candidates.add(m.getInstanceId()));
            }
            if (candidates.isEmpty()) {
                return;
            }
            ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.one(
                    PendingChoice.Kind.MINION, candidates, ResumePoint.HAKUNO_REICHISHA_DESTROY,
                    "【白ノ霊知者】: 破壊するミニオンを1体選んでください"));
        });
        soulSpells.put("QTE-M-WIND-31", SoulSpellSpec.of(
                TargetSpec.of(Requirement.of(Kind.MINION, Side.SELF, 1, false,
                        "攻撃力+1する自分のミニオンを1体選んでください")),
                ctx -> {
                    ResolvedTargets.TargetedMinion target = ctx.targets().get(0).minions().get(0);
                    target.minion().addModifier(new StatModifier(StatModifier.Stat.ATTACK,
                            StatModifier.Operation.ADD, 1, StatModifier.Duration.PERMANENT,
                            "QTE-M-WIND-31"));
                    ctx.room().addLog("【白ノ霊知者】: 【%s】の攻撃力が+1されました"
                            .formatted(target.minion().getMaster().name()));
                }));

        // ---- 愚乱怒土地(グランドランド)(QTE-M-EARTH-30・進化) ----
        // 「【進化】（土文明のミニオン1体）【威圧】
        //   【賢魂：3】自分の山札の上から2枚見て1枚を裏向きでマナに1枚を手札へ相手に見せず加える。」
        //
        // ★ミニオンとしての姿は【威圧】だけで、効果の文を持たない。
        // ★<b>「見る」だけなので相手には見せない。</b>公開領域(revealedZone)は本人のビューにしか
        //   出ない(GameViewBuilder が isSelf で絞る)ので、既存の器がそのまま使える。
        // ★山札が1枚しかないときは「マナへ置くか、手札に加えるか」を選ばせる(マスター裁定 B7-1)。
        //   そのため min は候補が2枚あるときだけ1になる —— 0枚なら何も起こらない
        soulSpells.put("QTE-M-EARTH-30", SoulSpellSpec.of(ctx -> {
            List<String> revealed = ctx.actions().revealFromTopOfDeck(ctx.room(), ctx.owner(), 2);
            if (revealed.isEmpty()) {
                ctx.room().addLog("【愚乱怒土地】: 山札が空のため、何も起こりませんでした");
                return;
            }
            ctx.owner().getRevealedZone().addAll(revealed);
            List<String> positions = new ArrayList<>();
            for (int i = 0; i < revealed.size(); i++) {
                positions.add(String.valueOf(i));
            }
            ctx.actions().requestChoice(ctx.room(), ctx.owner(), new PendingChoice(
                    PendingChoice.Kind.REVEALED, positions,
                    revealed.size() >= 2 ? 1 : 0, 1, ResumePoint.GURANDORANDO_MANA,
                    "【愚乱怒土地】: 裏向きでマナに置く1枚を選んでください(残りは手札に加わります)"));
        }));

        // ---- 勝阿外(カツアゲ)(QTE-M-EARTH-36) ----
        // 「【常在】相手はスペルを唱えられない。相手の手札の枚数このミニオンの攻撃力+1
        //   【賢魂：2】山札の上からカードを1枚マナゾーンに裏向きで置く。
        //   自分のマナが3枚以下のときカードを1枚引く。」
        //
        // ★【常在】2つはルール側の判定点にある(RuleGuards / StatCalculator)。
        // ★「3枚以下」は<b>1枚置いた後</b>の枚数で見る(マスター裁定 B8-4。文の順序どおり)。
        // ★マナ上限・山札切れで置けなかった場合も<b>ドローの判定は行う</b>
        //   (マスター裁定 B8-5。前半と後半が「そうしたら」で繋がっていない)
        soulSpells.put("QTE-M-EARTH-36", SoulSpellSpec.of(ctx -> {
            ctx.actions().placeTopOfDeckInManaFaceDown(ctx.room(), ctx.owner());
            if (ctx.owner().getManaZone().size() <= KATSUAGE_DRAW_MANA_LIMIT) {
                ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
            }
        }));
    }

    /**
     * 《英術・スケアロック》の後半 —— 手札の【進化】光文明ミニオンを1体選ばせる(★Batch 53)。
     *
     * ★<b>候補は「今この瞬間、素材を確保できるもの」だけである</b>(マスター裁定)。
     * 1体目が場に出た後に呼ぶので、その1体目を素材にできる進化もここに現れる。
     */
    private void requestScarelockEvolution(EffectContext ctx) {
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < ctx.owner().getHand().size(); i++) {
            CardMaster m = cards.findById(ctx.owner().getHand().get(i));
            if (m.type() != CardType.EVOLUTION || m.civilization() != Civilization.LIGHT) {
                continue;
            }
            if (!evolutionMaterialsAvailable(ctx.owner(), m.id())) {
                continue;
            }
            candidates.add(String.valueOf(i));
        }
        if (candidates.isEmpty()) {
            ctx.room().addLog("【英術・スケアロック】: 場に出せる【進化】の光文明ミニオンが手札にありません");
            return;
        }
        ctx.actions().requestChoice(ctx.room(), ctx.owner(), PendingChoice.one(
                PendingChoice.Kind.HAND, candidates, ResumePoint.SCARELOCK_EVOLUTION,
                "【英術・スケアロック】: 場に出す【進化】の光文明ミニオンを選んでください"));
    }

    /** 今の自分の場だけで、この進化ミニオンの素材の最小数をまかなえるか(★Batch 53) */
    private boolean evolutionMaterialsAvailable(PlayerState owner, String evolutionCardId) {
        EvolutionSpec spec = evolutions.get(evolutionCardId);
        if (spec == null) {
            return false;
        }
        long usable = owner.getMinionZone().stream().filter(spec.material()).count();
        return usable >= spec.minMaterials();
    }

    /** 自分の墓地にあるミニオンカードの枚数(★Batch 53。サモナーポップ・エンラの特殊召喚条件) */
    private int countMinionsInTrash(PlayerState player) {
        return (int) player.getTrash().stream()
                .filter(id -> {
                    CardType type = cards.findById(id).type();
                    return type == CardType.MINION || type == CardType.EVOLUTION;
                })
                .count();
    }

    /**
     * 手札から N 枚を捨てる問い合わせを出す(★Batch 53)。
     * 手札が空なら何も起きない(山札切れなどで捨てようがない場合)。
     */
    private void requestDiscard(EffectContext ctx, int min, int max, ResumePoint resumeAt,
            String prompt) {
        if (ctx.owner().getHand().isEmpty() || max <= 0) {
            return;
        }
        List<String> positions = new ArrayList<>();
        for (int i = 0; i < ctx.owner().getHand().size(); i++) {
            positions.add(String.valueOf(i));
        }
        ctx.actions().requestChoice(ctx.room(), ctx.owner(),
                new PendingChoice(PendingChoice.Kind.HAND, positions, min, max, resumeAt, prompt));
    }

    /**
     * 選ばれた手札の位置(複数)を、カードIDに直して手札から取り除く(★Batch 53)。
     * ★位置がずれないよう<b>降順に</b>取り除く(詠唱の疾風騎士と同じ形)。
     */
    private List<String> takeHandCardsAt(PlayerState owner, List<String> chosenPositions) {
        List<Integer> positions = new ArrayList<>();
        chosenPositions.forEach(s -> positions.add(Integer.parseInt(s)));
        positions.sort(java.util.Comparator.reverseOrder());
        List<String> taken = new ArrayList<>();
        for (int pos : positions) {
            taken.add(owner.getHand().remove(pos));
        }
        return taken;
    }

    /** 両者の場に居るミニオンの合計(★Batch 52。飛翔鉄人走太の特殊召喚条件) */
    private int countMinionsOnAnyField(GameState state) {
        return state.getPlayer1().getMinionZone().size() + state.getPlayer2().getMinionZone().size();
    }

    /** 進化の素材条件の登録(★Batch 52)。★この表は {@link #isRegistered(String)} が見ない */
    private void registerEvolution(String cardId, EvolutionSpec spec) {
        evolutions.put(cardId, spec);
    }

    /** 進化ミニオンの素材条件。進化ミニオンでないカード、未登録のカードは null */
    public EvolutionSpec evolutionOf(String cardId) {
        return evolutions.get(cardId);
    }

    /** 「自分のミニオンが破壊された」監視効果の登録 */
    private void watchOwnMinionDestroyed(String cardId, BiConsumer<EffectContext, String> effect) {
        ownMinionDestroyedWatchers.put(cardId, effect);
    }

    /**
     * 「どちらのミニオンが破壊されても」反応する監視効果を登録する(★Batch 57)。
     * 詳細は {@link #anyMinionDestroyedWatchers} を参照。
     */
    private void watchAnyMinionDestroyed(String cardId, BiConsumer<EffectContext, String> effect) {
        anyMinionDestroyedWatchers.put(cardId, effect);
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
                || anyMinionDestroyedWatchers.containsKey(cardId)
                || playConditions.containsKey(cardId)
                || soulSpells.containsKey(cardId);
    }

    /** このカードの【賢魂：n】としての仕様(★Batch 54)。持たない・未実装なら null */
    public SoulSpellSpec soulSpellOf(String cardId) {
        return soulSpells.get(cardId);
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
