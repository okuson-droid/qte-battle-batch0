package com.example.qte.effect;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.example.qte.effect.TargetSpec.Filter;
import com.example.qte.effect.TargetSpec.Kind;
import com.example.qte.effect.TargetSpec.Requirement;
import com.example.qte.effect.TargetSpec.Side;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.master.CardMasterRepository;
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

    /** キーワード判定(知識カードの枚数条件など)にマスタ参照が必要 */
    private final CardMasterRepository cards;

    public CardEffectRegistry(CardMasterRepository cards) {
        this.cards = cards;
        registerSpells();
        registerMinionTriggers();
        registerTargetedCards();
        registerSpecialSummons();
        registerLeaderAbilities();
        registerFireTabooCards();
    }

    // ---------------------------------------------------------------
    // 登録: 火文明(Batch 7では禁忌デッキに入る8枚分のみ。全面実装はBatch 8)
    // ---------------------------------------------------------------

    private void registerFireTabooCards() {

        // 血誓のバーサーカー: 【召喚時】自分のリーダーに1ダメージ。
        // 体力が10以上なら追加で2ダメージ(判定は1ダメージを与えた後: 発注者確認済み)
        register("QTE-0044", TriggerType.ON_SUMMON, ctx -> {
            ctx.actions().damageLeader(ctx.room(), ctx.owner(), 1);
            if (ctx.owner().getLp() >= 10) {
                ctx.actions().damageLeader(ctx.room(), ctx.owner(), 2);
            }
        });

        // ブラッドレイジの突撃兵: 【召喚時】自分のリーダーに2ダメージ
        register("QTE-0055", TriggerType.ON_SUMMON,
                ctx -> ctx.actions().damageLeader(ctx.room(), ctx.owner(), 2));

        // 赫灼の重戦士: 【召喚時】自分のリーダーの体力が10以下ならこれは【速攻】を得る
        register("QTE-0050", TriggerType.ON_SUMMON, ctx -> {
            if (ctx.owner().getLp() <= 10 && ctx.source() != null) {
                ctx.source().grantKeyword(Keyword.HASTE);
                ctx.room().addLog("【赫灼の重戦士】は【速攻】を得た");
            }
        });

        // イグニッション・バースト: 自分のリーダーに1ダメージ。カードを2枚引く
        spellEffects.put("QTE-0064", ctx -> {
            ctx.actions().damageLeader(ctx.room(), ctx.owner(), 1);
            ctx.actions().drawCards(ctx.room(), ctx.owner(), 2);
        });

        // マグマ・ストレート: ミニオン1体に3ダメージ(対象は限定なし=両者の場)
        targetSpecs.put("QTE-0046", TargetSpec.of(
                new Requirement(Kind.MINION, Side.ANY, 1, false, null,
                        "3ダメージを与えるミニオンを選んでください")));
        spellEffects.put("QTE-0046", ctx -> ctx.targets().get(0).minions().forEach(
                t -> ctx.actions().damageMinion(ctx.room(), t.owner(), t.minion(), 3)));
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
                new Requirement(Kind.HAND, Side.SELF, 1, false, null, "捨てるカードを選んでください"),
                new Requirement(Kind.MINION, Side.OPPONENT, 1, false, null, "手札に戻す相手のミニオンを選んでください")));
        register("QTE-0034", TriggerType.ON_SUMMON, ctx -> {
            // 選択済み手札は除去済みで届くため、行き先(墓地)を決めるだけでよい
            ctx.targets().get(0).handCardIds().forEach(id -> ctx.owner().getTrash().add(id));
            ctx.room().addLog("%sが手札を1枚捨てました".formatted(ctx.owner().getDisplayName()));
            ctx.targets().get(1).minions().forEach(
                    t -> ctx.actions().bounceToHand(ctx.room(), t.owner(), t.minion()));
        });

        // 英知の継承者: 【召喚時】【知識】を持つカードを1枚手札から捨てても良い。そうしたら【知識】を行う
        targetSpecs.put("QTE-0021", TargetSpec.of(
                new Requirement(Kind.HAND, Side.SELF, 1, true, Filter.KNOWLEDGE,
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
                new Requirement(Kind.MINION, Side.ANY, 2, false, null, "手札に戻すミニオンを2体選んでください")));
        register("QTE-0041", TriggerType.ON_SUMMON, ctx -> ctx.targets().get(0).minions().forEach(
                t -> ctx.actions().bounceToHand(ctx.room(), t.owner(), t.minion())));
    }

    // ---------------------------------------------------------------
    // 登録: 【特殊召喚】(Batch 3)
    // ---------------------------------------------------------------

    private void registerSpecialSummons() {

        // 深海神 プレサージュ: 自分の知識を持つカードを手札から5枚山札の下に置いて0コストで出せる
        specialSummons.put("QTE-0020", new SpecialSummonSpec(
                (state, player, handIndex) -> countKnowledgeInHandExcluding(player, handIndex) >= 5,
                TargetSpec.of(new Requirement(Kind.HAND, Side.SELF, 5, false, Filter.KNOWLEDGE,
                        "山札の下に置く【知識】カードを5枚選んでください")),
                ctx -> {
                    ctx.targets().get(0).handCardIds().forEach(id -> ctx.owner().getDeck().addLast(id));
                    ctx.room().addLog("%sが手札5枚を山札の下に置きました".formatted(ctx.owner().getDisplayName()));
                },
                "手札の【知識】カード5枚を山札の下に置き、0コストで召喚します"));

        // 知恵の双翼: 自分の【知識】を持つミニオンを2体手札に戻して0コストで出せる
        specialSummons.put("QTE-0032", new SpecialSummonSpec(
                (state, player, handIndex) -> player.getMinionZone().stream()
                        .filter(m -> m.hasKeyword(Keyword.KNOWLEDGE)).count() >= 2,
                TargetSpec.of(new Requirement(Kind.MINION, Side.SELF, 2, false, Filter.KNOWLEDGE,
                        "手札に戻す自分の【知識】ミニオンを2体選んでください")),
                ctx -> ctx.targets().get(0).minions().forEach(
                        t -> ctx.actions().bounceToHand(ctx.room(), t.owner(), t.minion())),
                "自分の【知識】ミニオン2体を手札に戻し、0コストで召喚します"));

        // 智将 ポセイドン・コア: 自分の【知識】ミニオンの合計体力が12以上なら0コストで出せる
        specialSummons.put("QTE-0035", new SpecialSummonSpec(
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
        specialSummons.put("QTE-0038", new SpecialSummonSpec(
                (state, player, handIndex) -> player.getHand().size() >= 7 && !player.isPlayedCardThisTurn(),
                TargetSpec.of(new Requirement(Kind.HAND, Side.SELF, 3, false, null,
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
        leaderAbilities.put("QTE-L002", new LeaderAbilitySpec(0,
                TargetSpec.of(new TargetSpec.Requirement(TargetSpec.Kind.HAND, TargetSpec.Side.SELF,
                        1, false, null, "山札の一番下に戻すカードを選んでください")),
                ctx -> {
                    ctx.targets().get(0).handCardIds().forEach(id -> ctx.owner().getDeck().addLast(id));
                    ctx.actions().healLeader(ctx.room(), ctx.owner(), 2);
                },
                "手札1枚を山札の下に戻し、リーダーを2回復"));

        // 流転の智者: コスト2支払っても良い。そうしたら、マナを1枚手札に戻して2ドロー
        leaderAbilities.put("QTE-L003", new LeaderAbilitySpec(2,
                TargetSpec.of(new TargetSpec.Requirement(TargetSpec.Kind.MANA, TargetSpec.Side.SELF,
                        1, false, null, "手札に戻すマナを選んでください")),
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
    // 照会・発火
    // ---------------------------------------------------------------

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
