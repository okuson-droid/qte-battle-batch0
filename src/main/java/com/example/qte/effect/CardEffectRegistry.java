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
import com.example.qte.game.TurnPhase;
import com.example.qte.game.StatModifier;
import com.example.qte.game.PlayerState;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardType;
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
    }

    // ---------------------------------------------------------------
    // 登録: 対象指定なしのスペル(Batch 2)
    // ---------------------------------------------------------------

    private void registerSpells() {
        spellEffects.put("QTE-0028", // アクア・サーチ: カードを1枚引く
                ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 1));

        spellEffects.put("QTE-0025", // スプラッシュ・ドロー: カードを2枚引く
                ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 2));

        spellEffects.put("QTE-0002", // 恵みの雨: リーダーを4回復。1枚引く
                ctx -> {
                    ctx.actions().healLeader(ctx.room(), ctx.owner(), 4);
                    ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
                });

        spellEffects.put("QTE-0036", // 流転の書: 1枚引く(【還元】の処理はGameActions側で共通)
                ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 1));

        spellEffects.put("QTE-0033", // 静寂の瞑想: 3枚引く。このターンカードを使用できない
                ctx -> {
                    ctx.actions().drawCards(ctx.room(), ctx.owner(), 3);
                    ctx.owner().setCannotUseCardsThisTurn(true);
                    ctx.room().addLog("%sはこのターンカードを使用できません"
                            .formatted(ctx.owner().getDisplayName()));
                });

        spellEffects.put("QTE-0024", // 溢れ出る英知: 3枚引く。ターン中、手札枚数分だけ全ミニオン攻撃+1
                ctx -> {
                    ctx.actions().drawCards(ctx.room(), ctx.owner(), 3);
                    ctx.owner().getThisTurnAuras().add("QTE-0024");
                    ctx.room().addLog("このターン中、%sのミニオンは手札の枚数分攻撃力が上がります"
                            .formatted(ctx.owner().getDisplayName()));
                });

        spellEffects.put("QTE-0023", // タイダルウェーブ: 相手のコスト4以下のミニオンを全て手札に戻す
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
        register("QTE-0042", TriggerType.ON_SUMMON, // 水鏡の幻術師: カードを2枚引く
                ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 2));

        register("QTE-0029", TriggerType.ON_SUMMON, // 潮流の魔導士: 手札5枚以上ならリーダーを3回復
                ctx -> {
                    if (ctx.owner().getHand().size() >= 5) {
                        ctx.actions().healLeader(ctx.room(), ctx.owner(), 3);
                    }
                });

        register("QTE-0003", TriggerType.ON_ATTACK, // 波濤の突撃兵: 攻撃時1枚引く
                ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 1));
    }

    // ---------------------------------------------------------------
    // 登録: 対象指定を要するカード(Batch 3)
    // ---------------------------------------------------------------

    private void registerTargetedCards() {

        // 手札を喰らう大蟹: 【召喚時】自分の手札を1枚捨てる。相手のミニオン1体を持ち主の手札に戻す
        targetSpecs.put("QTE-0034", TargetSpec.of(
                new Requirement(Kind.HAND, Side.SELF, 1, false, false, List.of(), "捨てるカードを選んでください"),
                new Requirement(Kind.MINION, Side.OPPONENT, 1, false, false, List.of(), "手札に戻す相手のミニオンを選んでください")));
        register("QTE-0034", TriggerType.ON_SUMMON, ctx -> {
            // 選択済み手札は除去済みで届くため、行き先(墓地)を決めるだけでよい
            ctx.targets().get(0).handCardIds().forEach(id -> ctx.owner().getTrash().add(id));
            ctx.room().addLog("%sが手札を1枚捨てました".formatted(ctx.owner().getDisplayName()));
            ctx.targets().get(1).minions().forEach(
                    t -> ctx.actions().bounceToHand(ctx.room(), t.owner(), t.minion()));
        });

        // 英知の継承者: 【召喚時】【知識】を持つカードを1枚手札から捨てても良い。そうしたら【知識】を行う
        targetSpecs.put("QTE-0021", TargetSpec.of(
                new Requirement(Kind.HAND, Side.SELF, 1, true, false, List.of(Filter.KNOWLEDGE),
                        "捨てる【知識】カードを選んでください(任意)")));
        register("QTE-0021", TriggerType.ON_SUMMON, ctx -> {
            var selection = ctx.targets().get(0);
            if (selection.isEmpty()) {
                return; // 「〜してもよい」なので捨てなくてもよい
            }
            selection.handCardIds().forEach(id -> ctx.owner().getTrash().add(id));
            // 「【知識】を行う」= 知識のキーワードアクション(1ドロー)を実行する
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
            ctx.room().addLog("【知識】%sが1枚ドロー".formatted(ctx.owner().getDisplayName()));
        });

        // 双流の幻術師: 場に居る知識の数Cost-1。【召喚時】ミニオンを2体選び持ち主の手札に戻す
        // (数え方・対象とも両者の場を参照する: 発注者確認済み)
        targetSpecs.put("QTE-0041", TargetSpec.of(
                new Requirement(Kind.MINION, Side.ANY, 2, false, false, List.of(), "手札に戻すミニオンを2体選んでください")));
        register("QTE-0041", TriggerType.ON_SUMMON, ctx -> ctx.targets().get(0).minions().forEach(
                t -> ctx.actions().bounceToHand(ctx.room(), t.owner(), t.minion())));
    }

    // ---------------------------------------------------------------
    // 登録: 【特殊召喚】(Batch 3)
    // ---------------------------------------------------------------

    private void registerSpecialSummons() {

        // 深海神 プレサージュ: 自分の知識を持つカードを手札から5枚山札の下に置いて0コストで出せる
        specialSummons.put("QTE-0020", SpecialSummonSpec.of(
                (state, player, handIndex) -> countKnowledgeInHandExcluding(player, handIndex) >= 5,
                TargetSpec.of(new Requirement(Kind.HAND, Side.SELF, 5, false, false, List.of(Filter.KNOWLEDGE),
                        "山札の下に置く【知識】カードを5枚選んでください")),
                ctx -> {
                    ctx.targets().get(0).handCardIds().forEach(id -> ctx.owner().getDeck().addLast(id));
                    ctx.room().addLog("%sが手札5枚を山札の下に置きました".formatted(ctx.owner().getDisplayName()));
                },
                "手札の【知識】カード5枚を山札の下に置き、0コストで召喚します"));

        // 知恵の双翼: 自分の【知識】を持つミニオンを2体手札に戻して0コストで出せる
        specialSummons.put("QTE-0032", SpecialSummonSpec.of(
                (state, player, handIndex) -> player.getMinionZone().stream()
                        .filter(m -> m.hasKeyword(Keyword.KNOWLEDGE)).count() >= 2,
                TargetSpec.of(new Requirement(Kind.MINION, Side.SELF, 2, false, false, List.of(Filter.KNOWLEDGE),
                        "手札に戻す自分の【知識】ミニオンを2体選んでください")),
                ctx -> ctx.targets().get(0).minions().forEach(
                        t -> ctx.actions().bounceToHand(ctx.room(), t.owner(), t.minion())),
                "自分の【知識】ミニオン2体を手札に戻し、0コストで召喚します"));

        // 智将 ポセイドン・コア: 自分の【知識】ミニオンの合計体力が12以上なら0コストで出せる
        specialSummons.put("QTE-0035", SpecialSummonSpec.of(
                (state, player, handIndex) -> player.getMinionZone().stream()
                        .filter(m -> m.hasKeyword(Keyword.KNOWLEDGE))
                        .mapToInt(MinionInstance::getCurrentHp).sum() >= 12,
                TargetSpec.of(),
                ctx -> {
                },
                "【知識】ミニオンの合計体力12以上: 代替コストなしで0コスト召喚します"));
        // ポセイドン・コアの【召喚時】: 自分のミニオンは【突進】を得る
        // (召喚時点で場にいるミニオンにのみ永続付与: 発注者確認済み。自身も場にいるため含まれる)
        register("QTE-0035", TriggerType.ON_SUMMON, ctx -> {
            ctx.owner().getMinionZone().forEach(m -> m.grantKeyword(Keyword.RUSH));
            ctx.room().addLog("%sのミニオンは【突進】を得ました".formatted(ctx.owner().getDisplayName()));
        });

        // 海皇 ポセイドン: メインフェーズ開始時、手札7枚以上なら手札3枚を捨ててコストなしで出せる
        // 「開始時」の厳密な実装は「このターンまだカードをプレイしていない」で近似する(設計解説4章)
        specialSummons.put("QTE-0038", SpecialSummonSpec.of(
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
        leaderAbilities.put("QTE-L002", LeaderAbilitySpec.of(0,
                TargetSpec.of(new TargetSpec.Requirement(TargetSpec.Kind.HAND, TargetSpec.Side.SELF,
                        1, false, false, List.of(), "山札の一番下に戻すカードを選んでください")),
                ctx -> {
                    ctx.targets().get(0).handCardIds().forEach(id -> ctx.owner().getDeck().addLast(id));
                    ctx.actions().healLeader(ctx.room(), ctx.owner(), 2);
                },
                "手札1枚を山札の下に戻し、リーダーを2回復"));

        // 流転の智者: コスト2支払っても良い。そうしたら、マナを1枚手札に戻して2ドロー
        leaderAbilities.put("QTE-L003", LeaderAbilitySpec.of(2,
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
        while (ctx.owner().getTrash().contains("QTE-0040") && !ctx.owner().isMinionZoneFull()) {
            ctx.owner().getTrash().remove("QTE-0040");
            ctx.actions().putIntoFieldByEffect(ctx.room(), ctx.owner(), "QTE-0040");
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
        for (MinionInstance watcher : List.copyOf(ctx.owner().getMinionZone())) {
            BiConsumer<EffectContext, String> effect =
                    ownMinionDestroyedWatchers.get(watcher.getMaster().id());
            if (effect == null || !ctx.owner().getMinionZone().contains(watcher)) {
                continue;
            }
            effect.accept(ctx, destroyedCardId);
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
        register("QTE-0044", TriggerType.ON_SUMMON, ctx -> {
            ctx.actions().damageLeader(ctx.room(), ctx.owner(), 1, "QTE-0044");
            if (ctx.owner().getLp() >= 10) {
                ctx.actions().damageLeader(ctx.room(), ctx.owner(), 2, "QTE-0044");
            }
        });

        // ブラッドレイジの突撃兵: 自分のリーダーに2ダメージ
        register("QTE-0055", TriggerType.ON_SUMMON,
                ctx -> ctx.actions().damageLeader(ctx.room(), ctx.owner(), 2, "QTE-0055"));

        // 赫灼の重戦士: 自分のリーダーの体力が10以下ならこれは【速攻】を得る
        register("QTE-0050", TriggerType.ON_SUMMON, ctx -> {
            if (ctx.owner().getLp() <= 10 && ctx.source() != null) {
                ctx.source().grantKeyword(Keyword.HASTE);
                ctx.room().addLog("【赫灼の重戦士】は【速攻】を得た");
            }
        });

        // 痛撃の炎術師: 自分のリーダーの体力が10以上なら自分のリーダーに1ダメージ
        register("QTE-0049", TriggerType.ON_SUMMON, ctx -> {
            if (ctx.owner().getLp() >= 10) {
                ctx.actions().damageLeader(ctx.room(), ctx.owner(), 1, "QTE-0049");
            }
        });

        // 相打ちの咎人: 以下を2回行う。自分のリーダーに1ダメージ、相手のリーダーに1ダメージ
        register("QTE-0059", TriggerType.ON_SUMMON, ctx -> {
            for (int i = 0; i < 2; i++) {
                ctx.actions().damageLeader(ctx.room(), ctx.owner(), 1, "QTE-0059");
                ctx.actions().damageLeader(ctx.room(), ctx.opponent(), 1, "QTE-0059");
            }
        });

        // 背水の烈火使い: 手札をすべて捨てる
        register("QTE-0063", TriggerType.ON_SUMMON, ctx -> {
            int count = ctx.owner().getHand().size();
            ctx.owner().getTrash().addAll(ctx.owner().getHand());
            ctx.owner().getHand().clear();
            ctx.room().addLog("%sは手札%d枚をすべて捨てた".formatted(ctx.owner().getDisplayName(), count));
        });

        // 背水の炎壁: 【召喚時】1回復(特殊召喚で出した場合の追加1回復は下のspecで別途)
        register("QTE-0057", TriggerType.ON_SUMMON,
                ctx -> ctx.actions().healLeader(ctx.room(), ctx.owner(), 1));

        // 逆境の猛火者: 体力10以下なら手札からコスト4以下のミニオンを1体場に出す。
        // 条件を満たさないときのために選択は任意(optional)としている
        targetSpecs.put("QTE-0066", TargetSpec.of(Requirement.filtered(
                Kind.HAND, Side.SELF, 1, true, "場に出すコスト4以下のミニオンを選んでください(体力10以下のときのみ有効)",
                Filter.MINION_CARD, Filter.COST_4_OR_LESS)));
        register("QTE-0066", TriggerType.ON_SUMMON, ctx -> {
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
        spellEffects.put("QTE-0043", ctx -> {
            if (ctx.actions().destroyOwnWeapon(ctx.room(), ctx.owner())) {
                ctx.actions().healLeader(ctx.room(), ctx.owner(), 2);
            } else {
                ctx.room().addLog("破壊するウェポンがなかった");
            }
        });

        // マグマ・ストレート: ミニオン1体に3ダメージ(対象は限定なし=両者の場)
        targetSpecs.put("QTE-0046", TargetSpec.of(Requirement.of(
                Kind.MINION, Side.ANY, 1, false, "3ダメージを与えるミニオンを選んでください")));
        spellEffects.put("QTE-0046", ctx -> ctx.targets().get(0).minions().forEach(
                t -> ctx.actions().damageMinion(ctx.room(), t.owner(), t.minion(), 3)));

        // イグニッション・バースト: 自分のリーダーに1ダメージ。カードを2枚引く
        spellEffects.put("QTE-0064", ctx -> {
            ctx.actions().damageLeader(ctx.room(), ctx.owner(), 1, "QTE-0064");
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 2);
        });

        // 再起の炎陣: 1枚捨てる。そうしたら1枚引く。【還元】
        targetSpecs.put("QTE-0052", TargetSpec.of(Requirement.of(
                Kind.HAND, Side.SELF, 1, true, "捨てるカードを選んでください")));
        spellEffects.put("QTE-0052", ctx -> {
            var selection = ctx.targets().get(0);
            if (selection.isEmpty()) {
                return;
            }
            selection.handCardIds().forEach(id -> ctx.owner().getTrash().add(id));
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
        });

        // 血の対価: 手札を1枚捨てる。そうしたら3回復
        targetSpecs.put("QTE-0065", TargetSpec.of(Requirement.of(
                Kind.HAND, Side.SELF, 1, true, "捨てるカードを選んでください")));
        spellEffects.put("QTE-0065", ctx -> {
            var selection = ctx.targets().get(0);
            if (selection.isEmpty()) {
                return;
            }
            selection.handCardIds().forEach(id -> ctx.owner().getTrash().add(id));
            ctx.actions().healLeader(ctx.room(), ctx.owner(), 3);
        });

        // 捨て身の猛進: このターン中、自分のミニオンすべての攻撃力+1、および【突進】付与
        spellEffects.put("QTE-0053", ctx -> {
            ctx.owner().getMinionZone().forEach(m -> {
                m.addModifier(new StatModifier(StatModifier.Stat.ATTACK, StatModifier.Operation.ADD,
                        1, StatModifier.Duration.THIS_TURN, "QTE-0053"));
                m.grantKeywordThisTurn(Keyword.RUSH);
            });
            ctx.room().addLog("%sのミニオンは攻撃力+1と【突進】を得た(このターン中)"
                    .formatted(ctx.owner().getDisplayName()));
        });

        // フレイム・スナイプ: 相手の【守護】を持つHP5以下のミニオンを1体選び破壊
        targetSpecs.put("QTE-0054", TargetSpec.of(Requirement.filtered(
                Kind.MINION, Side.OPPONENT, 1, false, "破壊する相手の【守護】ミニオン(HP5以下)を選んでください",
                Filter.GUARD, Filter.HP_5_OR_LESS)));
        spellEffects.put("QTE-0054", ctx -> ctx.targets().get(0).minions().forEach(
                t -> ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion())));

        // 命を削る烈火: 自分のリーダーに3ダメージ。相手の場のミニオンすべてに2ダメージ
        spellEffects.put("QTE-0056", ctx -> {
            ctx.actions().damageLeader(ctx.room(), ctx.owner(), 3, "QTE-0056");
            List.copyOf(ctx.opponent().getMinionZone()).forEach(
                    m -> ctx.actions().damageMinion(ctx.room(), ctx.opponent(), m, 2));
        });

        // 命喰いの火種: 自分のリーダーに3ダメージ。その後カードを2枚引く。【還元】
        spellEffects.put("QTE-0060", ctx -> {
            ctx.actions().damageLeader(ctx.room(), ctx.owner(), 3, "QTE-0060");
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 2);
        });

        // ---- 【特殊召喚】 ----

        // 極炎竜 ヴォルカニクス: ターン中に自分のリーダーが4回以上ダメージを受けている時、コスト1で出せる
        specialSummons.put("QTE-0051", new SpecialSummonSpec(
                (state, player, handIndex) -> player.getLeaderDamagedCountThisTurn() >= 4,
                1, TargetSpec.of(), ctx -> {
                }, ctx -> {
                },
                "このターン4回以上ダメージを受けている: コスト1で召喚します"));

        // 背水の炎壁: ターン中3回以上ダメージを受けていた場合0コストで出せる。これで出したとき1回復
        specialSummons.put("QTE-0057", new SpecialSummonSpec(
                (state, player, handIndex) -> player.getLeaderDamagedCountThisTurn() >= 3,
                0, TargetSpec.of(), ctx -> {
                },
                ctx -> ctx.actions().healLeader(ctx.room(), ctx.owner(), 1),
                "このターン3回以上ダメージを受けている: 0コストで召喚し、追加で1回復します"));

        // 鳳凰神 ヴォルカニクスレヴォ: このターン5回以上回復したとき0コストで出せる
        specialSummons.put("QTE-0058", SpecialSummonSpec.of(
                (state, player, handIndex) -> player.getHealedCountThisTurn() >= 5,
                TargetSpec.of(), ctx -> {
                },
                "このターン5回以上回復している: 0コストで召喚します"));

        // 覚醒の炎童: 自分のリーダーの体力が10以下のときコスト0にする
        specialSummons.put("QTE-0062", SpecialSummonSpec.of(
                (state, player, handIndex) -> player.getLp() <= 10,
                TargetSpec.of(), ctx -> {
                },
                "体力10以下: 0コストで召喚します"));

        // ---- リーダー起動能力 ----

        // 傷痕の闘帝: 自分のリーダーに1ダメージ。そうしたら1枚ドローする
        leaderAbilities.put("QTE-L001", LeaderAbilitySpec.of(0, TargetSpec.of(), ctx -> {
            ctx.actions().damageLeader(ctx.room(), ctx.owner(), 1, "QTE-L001");
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
        }, "自分のリーダーに1ダメージ、1枚ドロー"));

        // 剛火の将: 自分のライフを2減らす(ダメージ扱い: 発注者確認済み)。
        // このターン中、次に手札から使用する火文明ミニオンのコストを-1する(0にはならない)
        leaderAbilities.put("QTE-L004", LeaderAbilitySpec.of(0, TargetSpec.of(), ctx -> {
            ctx.actions().damageLeader(ctx.room(), ctx.owner(), 2, "QTE-L004");
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
                .filter(m -> "QTE-0045".equals(m.getMaster().id()))
                .forEach(m -> m.addModifier(new StatModifier(StatModifier.Stat.ATTACK,
                        StatModifier.Operation.ADD, 2, StatModifier.Duration.PERMANENT, "QTE-0045")));

        // 反転の炎鏡: 自分のターン中、このカード以外の「効果で」ダメージを受けたとき
        // 自分のリーダーに1ダメージ、その後1回復。
        // 自己誘発を除外しないと無限ループになるため、発生源が炎鏡自身なら発動しない
        var weapon = ctx.owner().getEquippedWeapon();
        boolean mirrorEquipped = weapon != null && "QTE-0048".equals(weapon.id());
        boolean ownTurn = ctx.owner().getPlayerId().equals(ctx.state().getTurnPlayerId());
        boolean byOtherCard = sourceCardId != null && !"QTE-0048".equals(sourceCardId);
        if (mirrorEquipped && ownTurn && byOtherCard) {
            ctx.room().addLog("【反転の炎鏡】が反応した");
            ctx.actions().damageLeader(ctx.room(), ctx.owner(), 1, "QTE-0048");
            ctx.actions().healLeader(ctx.room(), ctx.owner(), 1);
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
        leaderAbilities.put("QTE-L005", new LeaderAbilitySpec(0, TargetSpec.of(),
                ctx -> {
                    if (ctx.actions().returnFaceDownManaToHand(ctx.room(), ctx.owner())) {
                        ctx.actions().drawCards(ctx.room(), ctx.owner(), 2);
                    }
                },
                (state, player) -> player.getFaceDownManaCount() > 0,
                "裏向きのマナ1枚を手札に戻し、カードを2枚引く"));

        // 黄泉の召喚主(QTE-L006)は起動能力ではなく常在能力(サブフェイズの墓地召喚)。
        // ルールそのものを書き換えるため GameService.summonFromGrave が担当する

        // ---- ミニオン ----

        // 執念の暗殺者: 【召喚時】ミニオン1体に3ダメージ。自分のミニオンが破壊されるたび1枚引いてもよい
        targetSpecs.put("QTE-0005", TargetSpec.of(
                new Requirement(Kind.MINION, Side.ANY, 1, true, false, List.of(),
                        "3ダメージを与えるミニオンを選んでください(自分のミニオンも選べます)")));
        register("QTE-0005", TriggerType.ON_SUMMON, ctx -> ctx.targets().get(0).minions()
                .forEach(t -> ctx.actions().damageMinion(ctx.room(), t.owner(), t.minion(), 3)));
        watchOwnMinionDestroyed("QTE-0005", (ctx, destroyedCardId) -> {
            // 「引いてもよい」= 山札が空でなければ引く(AutoChoice)
            if (AutoChoice.shouldDrawOptional(ctx.owner())) {
                ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
                ctx.room().addLog("【執念の暗殺者】が1枚ドロー");
            }
        });

        // ゾンストライカー: 【召喚時】墓地の「ゾンストライカー」を全て出す(ゾーン上限まで)。
        // 効果による「出す」なので【召喚時】は再発動しない(無限ループにならない)
        register("QTE-0012", TriggerType.ON_SUMMON, ctx -> {
            while (ctx.owner().getTrash().contains("QTE-0012") && !ctx.owner().isMinionZoneFull()) {
                ctx.owner().getTrash().remove("QTE-0012");
                ctx.actions().putIntoFieldByEffect(ctx.room(), ctx.owner(), "QTE-0012");
            }
        });

        // 腐敗の投擲者: 【召喚時】相手のミニオン1体に1ダメージ
        targetSpecs.put("QTE-0019", TargetSpec.of(
                new Requirement(Kind.MINION, Side.OPPONENT, 1, true, false, List.of(),
                        "1ダメージを与える相手のミニオンを選んでください")));
        register("QTE-0019", TriggerType.ON_SUMMON, ctx -> ctx.targets().get(0).minions()
                .forEach(t -> ctx.actions().damageMinion(ctx.room(), t.owner(), t.minion(), 1)));

        // 墓場の怨念集合体: 【召喚時】墓地のスペルを1枚手札に加える(攻撃力の加算はStatCalculator)
        targetSpecs.put("QTE-0071", TargetSpec.of(
                new Requirement(Kind.TRASH, Side.SELF, 1, true, false, List.of(Filter.SPELL_CARD),
                        "手札に加えるスペルを墓地から選んでください")));
        register("QTE-0071", TriggerType.ON_SUMMON, ctx -> ctx.targets().get(0).trashCardIds()
                .forEach(id -> ctx.actions().returnFromTrashToHand(ctx.room(), ctx.owner(), id)));

        // 不滅のネクロマンサー: 自分の他のミニオンが破壊されるたび、裏向きマナ1枚を破壊して
        // そのミニオンを蘇生し【突進】を付与してもよい。
        // 「してもよい」の判断はAutoChoice。マナを無駄にしないよう、蘇生できる見込みを先に確かめる
        watchOwnMinionDestroyed("QTE-0072", (ctx, destroyedCardId) -> {
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
        register("QTE-0074", TriggerType.ON_DESTROYED_BY_COMBAT,
                ctx -> ctx.actions().drawCards(ctx.room(), ctx.owner(), 1));

        // カース・ボーン: 【召喚時】表向きマナ1枚を裏向きにする。できなければ自身を破壊する
        register("QTE-0076", TriggerType.ON_SUMMON, ctx -> {
            if (ctx.actions().turnManaFaceDown(ctx.room(), ctx.owner(), 1) == 0) {
                ctx.room().addLog("表向きのマナが無いため【カース・ボーン】は破壊されます");
                ctx.actions().destroyMinion(ctx.room(), ctx.owner(), ctx.source());
            }
        });

        // 冥界神ハデス: 【召喚時】ハデス以外の全ミニオンを破壊し、その後
        // 裏向きマナの枚数だけ、このターン破壊された味方ミニオンを墓地から出す。
        // 破壊が先・蘇生が後という順序のため、自分が今破壊したミニオンも蘇生候補に入る
        register("QTE-0077", TriggerType.ON_SUMMON, ctx -> {
            for (PlayerState side : List.of(ctx.owner(), ctx.opponent())) {
                for (MinionInstance minion : List.copyOf(side.getMinionZone())) {
                    if (!"QTE-0077".equals(minion.getMaster().id())) {
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
        targetSpecs.put("QTE-0078", TargetSpec.of(
                new Requirement(Kind.MINION, Side.OPPONENT, 1, true, false, List.of(Filter.COST_3_OR_LESS),
                        "破壊する相手のコスト3以下のミニオンを選んでください(裏向きマナ2枚以上が必要)")));
        register("QTE-0078", TriggerType.ON_SUMMON, ctx -> {
            if (ctx.owner().getFaceDownManaCount() < 2) {
                ctx.room().addLog("裏向きのマナが2枚未満のため【裏切りの魔女】の効果は発動しません");
                return;
            }
            ctx.targets().get(0).minions()
                    .forEach(t -> ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion()));
        });

        // 獄門の裁定者: 【守護】このミニオンがダメージを受けた時、相手のリーダーに2ダメージ
        register("QTE-0079", TriggerType.ON_MINION_DAMAGED,
                ctx -> ctx.actions().damageLeader(ctx.room(), ctx.opponent(), 2, "QTE-0079"));

        // 這い寄る生霊: 【特殊召喚】自分のターン中に自分のミニオンが破壊されていればコスト0で使用できる。
        // 特殊召喚で出た場合のみ、そのターンの終わりに破壊される
        specialSummons.put("QTE-0085", new SpecialSummonSpec(
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
        targetSpecs.put("QTE-0088", TargetSpec.of(
                new Requirement(Kind.MINION, Side.SELF, 1, true, false, List.of(),
                        "生贄にする自分のミニオンを選んでください(選ばない場合このミニオンが破壊されます)")));
        register("QTE-0088", TriggerType.ON_SUMMON, ctx -> {
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
        playConditions.put("QTE-0006",
                (state, player) -> player.getManaZone().stream().anyMatch(ManaCard::isFaceUp));
        spellEffects.put("QTE-0006", ctx -> {
            ctx.actions().turnManaFaceDown(ctx.room(), ctx.owner(), 2);
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 3);
        });

        // 墓穴の呪い: 山札の上から3枚を墓地に置く。墓地の枚数以下のHPを持つミニオンを全て破壊。
        // 枚数の判定は3枚を置いた後に行う(発注者確認済み)。自分のミニオンも巻き込む
        spellEffects.put("QTE-0068", ctx -> {
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
        playConditions.put("QTE-0069",
                (state, player) -> !state.opponentOf(player.getPlayerId()).getMinionZone().isEmpty());
        targetSpecs.put("QTE-0069", TargetSpec.of(
                new Requirement(Kind.MINION, Side.OPPONENT, 1, false, false, List.of(),
                        "破壊する相手のミニオンを選んでください")));
        spellEffects.put("QTE-0069", ctx -> ctx.targets().get(0).minions()
                .forEach(t -> ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion())));

        // 悪夢: コスト軽減はStatCalculatorが行う。本体効果はサブフェイズに使ったときのみ。
        // 「このターン、ミニオンの召喚コストを-4」はターン中オーラとして表現する
        spellEffects.put("QTE-0070", ctx -> {
            if (ctx.state().getPhase() != TurnPhase.SUB) {
                ctx.room().addLog("【悪夢】はサブフェイズ以外で使用されたため、効果は発動しませんでした");
                return;
            }
            ctx.owner().getThisTurnAuras().add("QTE-0070");
            ctx.room().addLog("このターン、%sのミニオンの召喚コストが4下がります"
                    .formatted(ctx.owner().getDisplayName()));
        });

        // 禁忌の代償: 裏向きマナ1枚を破壊する。相手のミニオン1体を破壊する
        playConditions.put("QTE-0075", (state, player) -> player.getFaceDownManaCount() > 0
                && !state.opponentOf(player.getPlayerId()).getMinionZone().isEmpty());
        targetSpecs.put("QTE-0075", TargetSpec.of(
                new Requirement(Kind.MINION, Side.OPPONENT, 1, false, false, List.of(),
                        "破壊する相手のミニオンを選んでください")));
        spellEffects.put("QTE-0075", ctx -> {
            ctx.actions().destroyFaceDownMana(ctx.room(), ctx.owner(), 1);
            ctx.targets().get(0).minions()
                    .forEach(t -> ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion()));
        });

        // 死者蘇生: 好きな数の自分のミニオンを破壊してもよい(その数だけコスト-1)。
        // 墓地からミニオン1体を【突進】付きで蘇生する。
        // 生贄はコスト計算に影響するためGameServiceが支払い前に数を読む
        playConditions.put("QTE-0080", (state, player) -> !player.getMinionZone().isEmpty()
                || player.getTrash().stream().anyMatch(id -> cards.findById(id).type() == CardType.MINION));
        targetSpecs.put("QTE-0080", TargetSpec.of(
                Requirement.upTo(Kind.MINION, Side.SELF, PlayerState.MAX_MINIONS,
                        "生贄にする自分のミニオンを選んでください(1体につきコスト-1)")));
        spellEffects.put("QTE-0080", ctx -> {
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
        playConditions.put("QTE-0081", (state, player) -> !player.getMinionZone().isEmpty());
        targetSpecs.put("QTE-0081", TargetSpec.of(
                new Requirement(Kind.MINION, Side.SELF, 1, false, false, List.of(),
                        "破壊する自分のミニオンを選んでください"),
                new Requirement(Kind.MINION, Side.OPPONENT, 1, true, false, List.of(),
                        "破壊する相手のミニオンを選んでください")));
        spellEffects.put("QTE-0081", ctx -> {
            ctx.targets().get(0).minions()
                    .forEach(t -> ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion()));
            ctx.targets().get(1).minions()
                    .forEach(t -> ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion()));
        });

        // 禁忌の墓地利用: 墓地のスペルを2枚選び、マナゾーンに裏向きで置く。
        // 墓地に1枚しかなければ1枚だけ置く(発注者確認済み)ため upTo で表現する
        playConditions.put("QTE-0084", (state, player) -> player.getManaZone().size() < PlayerState.MAX_MANA
                && player.getTrash().stream().anyMatch(id -> cards.findById(id).type() == CardType.SPELL));
        targetSpecs.put("QTE-0084", TargetSpec.of(
                Requirement.upTo(Kind.TRASH, Side.SELF, 2,
                        "マナに置くスペルを墓地から2枚まで選んでください", Filter.SPELL_CARD)));
        spellEffects.put("QTE-0084", ctx -> ctx.targets().get(0).trashCardIds()
                .forEach(id -> ctx.actions().putTrashCardIntoManaFaceDown(ctx.room(), ctx.owner(), id)));

        // ---- ウェポン ----
        // 禁忌の冥魔剣(QTE-0073)・死神の大鎌(QTE-0086)・死霊の収鎌(QTE-0089)は
        // 「リーダーが攻撃した時」の効果であり、GameService.leaderAttack内で解決する
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
        leaderAbilities.put("QTE-L007", LeaderAbilitySpec.of(4, TargetSpec.of(), ctx -> {
            ctx.opponent().setSpellSealedOnTurn(ctx.state().getTurnNumber() + 1);
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
            ctx.room().addLog("次の%sのターン、スペルを唱えられません".formatted(ctx.opponent().getDisplayName()));
        }, "コスト4を支払う: 次の相手のターン、相手はスペルを唱えられません。カードを1枚引く"));

        // 聖光の守護聖: コスト2を支払う。次の相手のターン終了時まで、自分は相手の効果による
        // 破壊を受けない(戦闘破壊・自分自身の効果による破壊は防げない)。自分のターンをまたぐ持続効果
        leaderAbilities.put("QTE-L008", LeaderAbilitySpec.of(2, TargetSpec.of(), ctx -> {
            int expireTurn = ctx.state().getTurnNumber() + 1;
            ctx.owner().getPersistentAuras().add(PersistentAura.untilEndOfTurn("QTE-L008", expireTurn));
            ctx.room().addLog("次の相手のターン終了時まで、%sは相手の効果で破壊されなくなりました"
                    .formatted(ctx.owner().getDisplayName()));
        }, "コスト2を支払う: 次の相手のターン終了時まで、自分は相手の効果による破壊を受けません"));

        // ---- ミニオン ----

        // 聖域の案内人: 【知識】自分の場に【守護】を持つミニオンがいるなら、もう一度【知識】を行う。
        // 1回目のドローはfire()が自動処理する(自身がKNOWLEDGEを持つため)。ここでは2回目だけを扱う。
        // 守護の有無は「登場時」(ON_ENTER)に判定し、召喚か効果で出したかを問わない(発注者確認済み)
        register("QTE-0093", TriggerType.ON_ENTER, ctx -> {
            boolean hasGuard = ctx.owner().getMinionZone().stream().anyMatch(m -> m.hasKeyword(Keyword.GUARD));
            if (hasGuard) {
                ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
                ctx.room().addLog("【聖域の案内人】: 【守護】がいるためもう一度【知識】");
            }
        });

        // 光の召喚士: 【召喚時】の表記が無いためON_ENTER型として扱う(発注者確認済み)。
        // 自分の手札からコスト3以下のミニオンを1体、コストを支払わず場に出す。
        // 効果で「出す」のでON_SUMMONは発動しない
        targetSpecs.put("QTE-0105", TargetSpec.of(Requirement.filtered(
                Kind.HAND, Side.SELF, 1, true, "場に出すコスト3以下のミニオンを選んでください(いなければ確定)",
                Filter.MINION_CARD, Filter.COST_3_OR_LESS)));
        register("QTE-0105", TriggerType.ON_ENTER, ctx -> {
            var selection = ctx.targets().get(0);
            if (selection.isEmpty()) {
                return;
            }
            selection.handCardIds().forEach(id -> ctx.actions().putIntoFieldByEffect(ctx.room(), ctx.owner(), id));
        });

        // 降臨の伝道師: 【召喚時】山札の上から4枚を公開。【守護】ミニオンを1体場に出し
        // (0体なら不発・1体なら自動決定・2体以上ならプレイヤーが選ぶ)、残りは山札の下へ。
        // 出したミニオンはその後3ダメージを受ける
        register("QTE-0112", TriggerType.ON_SUMMON, ctx -> {
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
        spellEffects.put("QTE-0014", ctx -> {
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
        playConditions.put("QTE-0090",
                (state, player) -> !state.opponentOf(player.getPlayerId()).getMinionZone().isEmpty());
        targetSpecs.put("QTE-0090", TargetSpec.of(new Requirement(
                Kind.MINION, Side.OPPONENT, 1, false, false,
                List.of(Filter.HIGHEST_ATTACK_OPPONENT, Filter.IGNORES_STEALTH),
                "相手の場で最も攻撃力の高いミニオンを選んでください")));
        spellEffects.put("QTE-0090", ctx -> ctx.targets().get(0).minions()
                .forEach(t -> ctx.actions().destroyMinion(ctx.room(), t.owner(), t.minion())));

        // 聖光の武装解除: ウェポンを1枚破壊する。【還元】。自分のウェポンも選べ、
        // 誰も装備していなければ空撃ちになる(発注者確認済み)
        targetSpecs.put("QTE-0091", TargetSpec.of(
                Requirement.upTo(Kind.WEAPON, Side.ANY, 1, "破壊するウェポンを選んでください(いなければ確定)")));
        spellEffects.put("QTE-0091", ctx -> ctx.targets().get(0).weapons()
                .forEach(owner -> ctx.actions().destroyOwnWeapon(ctx.room(), owner)));

        // 神の福音: 手札から光文明の【守護】ミニオンを最大2体、コストを支払わず場に出す。
        // 出した数だけ引く。ゾーンの空きが足りなければ出せた数だけ出し、その数だけ引く(発注者確認済み)
        targetSpecs.put("QTE-0097", TargetSpec.of(Requirement.upTo(Kind.HAND, Side.SELF, 2,
                "コストを支払わず場に出す光文明の【守護】ミニオンを2体まで選んでください",
                Filter.LIGHT_CIVILIZATION, Filter.GUARD)));
        spellEffects.put("QTE-0097", ctx -> {
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
        playConditions.put("QTE-0109", (state, player) -> player.getHand().stream().anyMatch(id -> {
            var m = cards.findById(id);
            return m.hasKeyword(Keyword.GUARD) && m.cost() != null && m.cost() <= 7;
        }));
        targetSpecs.put("QTE-0109", TargetSpec.of(Requirement.filtered(
                Kind.HAND, Side.SELF, 1, false, "コストを支払わず場に出すコスト7以下の【守護】ミニオンを選んでください",
                Filter.GUARD, Filter.COST_7_OR_LESS)));
        spellEffects.put("QTE-0109", ctx -> ctx.targets().get(0).handCardIds()
                .forEach(id -> ctx.actions().putIntoFieldByEffect(ctx.room(), ctx.owner(), id)));

        // 光の戒め: 相手のミニオン1体を次のターン攻撃できなくする(氷結の杖と同じ仕組み)。1枚引く
        playConditions.put("QTE-0110",
                (state, player) -> !state.opponentOf(player.getPlayerId()).getMinionZone().isEmpty());
        targetSpecs.put("QTE-0110", TargetSpec.of(Requirement.of(
                Kind.MINION, Side.OPPONENT, 1, false, "凍結させる相手のミニオンを選んでください")));
        spellEffects.put("QTE-0110", ctx -> {
            ctx.targets().get(0).minions().forEach(t -> {
                int nextTurn = ctx.state().getTurnNumber() + 1;
                t.minion().setCannotAttackOnTurn(nextTurn);
                ctx.room().addLog("【%s】は凍結しました(次のターン攻撃不可)"
                        .formatted(t.minion().getMaster().name()));
            });
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 1);
        });

        // ---- ウェポン ----
        // 聖剣エクスカリバー(QTE-0018)は「リーダーが攻撃した時」の効果であり、
        // 既存の6件と同じくGameService.leaderAttack内のswitchで解決する(11bでは移設しない)
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
            // 以下5件は Batch 12b でカード効果と同時に実装する。
            // 12a で用意したのは器(中断・再開の経路)までである
            case TAILWIND_DISCARD, MANA_CONVERT_PUT, WINDHOLE_SECOND,
                    GUARD_STAFF_TARGET, GALE_KNIGHT_RECOVER ->
                throw new IllegalStateException("この選択の解決はまだ実装されていません(Batch 12b)");
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

    /** スペルがプレイ可能か(効果が登録済みか) */
    public boolean isSpellImplemented(String cardId) {
        return spellEffects.containsKey(cardId);
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
