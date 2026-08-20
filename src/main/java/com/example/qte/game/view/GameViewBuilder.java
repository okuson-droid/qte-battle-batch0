package com.example.qte.game.view;

import java.util.List;

import org.springframework.stereotype.Component;

import com.example.qte.effect.CardEffectRegistry;
import com.example.qte.effect.LeaderAbilitySpec;
import com.example.qte.effect.EvolutionSpec;
import com.example.qte.effect.RuleGuards;
import com.example.qte.effect.SpecialSummonSpec;
import com.example.qte.effect.StatCalculator;
import com.example.qte.effect.TargetSpec;
import com.example.qte.game.GameState;
import com.example.qte.game.GameStatus;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.Keyword;
import com.example.qte.room.GameRoom;

import lombok.RequiredArgsConstructor;

/**
 * GameStateから「そのプレイヤーに見えてよい情報だけ」を抜き出してGameViewを組み立てる。
 * 情報の非対称(手札・裏向きマナ)はすべてここで一元的に処理する(設計判断9)。
 * GameStateを変更しない読み取り専用のクラスである(変更はGameServiceのみ)。
 */
@Component
@RequiredArgsConstructor
public class GameViewBuilder {

    private final CardMasterRepository cards;
    private final StatCalculator stats;
    private final CardEffectRegistry effects;

    /** 攻撃可否の判定。サーバ側の検証と同じ判定をUI表示にも使う */
    private final RuleGuards guards;

    /**
     * 「効果が未実装」の印の判定(★Batch 47)。
     * デッキ構築で弾かなくなったカード(裁定D2)を、盤面で見分けられるようにするために使う。
     * クライアントに同じ判定を持たせない —— 実装済みかどうかを知っているのはサーバだけである。
     */
    private final com.example.qte.effect.EffectImplementation implementation;

    /** viewerId のプレイヤーに配信するビューを組み立てる */
    public GameView build(GameRoom room, String viewerId) {
        GameState state = room.getGameState();
        if (state == null) {
            // 対戦相手の入室待ち: 盤面はまだ存在しない
            return new GameView(room.getRoomId(), GameStatus.WAITING.name(), 0, null, null,
                    false, false, false, null, null, null, List.copyOf(room.getLog()));
        }
        PlayerState you = state.playerOf(viewerId);
        PlayerState opponent = state.opponentOf(viewerId);

        boolean myTurn = state.getStatus() == GameStatus.PLAYING
                && viewerId.equals(state.getTurnPlayerId());
        boolean chooseOrder = state.getStatus() == GameStatus.SETUP
                && state.getFirstPlayerId() == null
                && viewerId.equals(room.getDiceWinnerId());
        boolean mulligan = state.getStatus() == GameStatus.SETUP
                && state.getFirstPlayerId() != null
                && !you.isMulliganDone();
        String winnerName = state.getWinnerPlayerId() == null ? null
                : state.playerOf(state.getWinnerPlayerId()).getDisplayName();

        return new GameView(
                room.getRoomId(),
                state.getStatus().name(),
                state.getTurnNumber(),
                state.getPhase().name(),
                state.getPhase().getDisplayName(),
                myTurn,
                chooseOrder,
                mulligan,
                winnerName,
                buildPlayerView(state, you, true, myTurn),
                buildPlayerView(state, opponent, false, myTurn),
                List.copyOf(room.getLog()));
    }

    private PlayerView buildPlayerView(GameState state, PlayerState player, boolean isSelf, boolean viewerTurn) {
        // 手札: 自分には中身を、相手には枚数だけを見せる
        List<CardView> hand = null;
        if (isSelf) {
            hand = new java.util.ArrayList<>();
            for (int i = 0; i < player.getHand().size(); i++) {
                hand.add(buildHandCard(state, player, i));
            }
        }

        List<ManaView> mana = player.getManaZone().stream()
                .map(m -> toManaView(m, isSelf))
                .toList();

        boolean attackerSide = isSelf && viewerTurn;
        List<MinionView> minions = player.getMinionZone().stream()
                .map(m -> toMinionView(state, player, m, attackerSide))
                .toList();

        boolean leaderFrozen = player.getLeaderCannotAttackOnTurn() == state.getTurnNumber();
        // リーダーの攻撃可否も判定層(RuleGuards)に揃える。サーバの検証と同じ判断を通すことで
        // ボタンの活性と実際の可否がずれない(a2で未装備・攻撃済み・凍結を判定層へ集約済み)
        boolean leaderCanAttack = attackerSide
                && guards.leaderAttackDenial(state, player) == null;

        // 禁忌デッキの中身は所有者のみ閲覧できる(総合ルール3-2)。相手には枚数だけを送る
        List<CardView> taboo = null;
        if (isSelf) {
            taboo = player.getTabooDeck().stream()
                    .map(id -> buildCardView(state, player, cards.findById(id), -1, false))
                    .toList();
        }

        return new PlayerView(
                player.getDisplayName(),
                player.getLeader().name(),
                player.getLeader().id(),
                player.getLp(),
                player.getDeck().size(),
                player.getHand().size(),
                hand,
                player.getAvailableMp(),
                player.getManaZone().size(),
                mana,
                minions,
                player.getTrash().size(),
                player.getTrash().stream().map(id -> cards.findById(id).name()).toList(),
                // ★Batch 53: 墓地の面には「墓地から特殊召喚できるか」を添える
                //   (《サモナーポップ・エンラ》)。相手の墓地でも同じ形で作られるが、
                //   操作できるのは自分の墓地だけなのでクライアント側が isSelf で絞る
                player.getTrash().stream()
                        .map(id -> buildCardView(state, player, cards.findById(id), -1, true))
                        .toList(),
                player.getLostZone().size(),
                player.getLostZone().stream().map(id -> cards.findById(id).name()).toList(),
                // ★Batch 44: 消滅の面(最上段の表示・一覧のフェイス化)。消滅は墓地と同じ公開情報である
                player.getLostZone().stream()
                        .map(id -> buildCardView(state, player, cards.findById(id), -1, false))
                        .toList(),
                player.getTabooDeck().size(),
                taboo,
                player.isManaChargedThisTurn(),
                player.isCannotUseCardsThisTurn(),
                player.isMulliganDone(),
                player.getLeader().text(),
                player.getDeckName(),
                player.getEquippedWeapon() == null ? null : player.getEquippedWeapon().name(),
                // ★Batch 44: 効果テキスト・文明はクライアントが card-library から引く(裁定144)。
                //   ビューにはIDだけを足す。名前で引かせないため(名前はIDと同じ、の原則)
                player.getEquippedWeapon() == null ? null : player.getEquippedWeapon().id(),
                player.getEquippedWeapon() == null ? null
                        : stats.effectiveWeaponAttack(state, player),
                leaderCanAttack,
                leaderFrozen,
                buildLeaderAbility(state, player, isSelf),
                buildRevealedCards(player),
                buildPendingChoice(state, player, isSelf));
    }

    /** 一時公開領域のカード(降臨の伝道師などが公開中の束)。空なら公開なし */
    private List<PlayerView.RevealedCardView> buildRevealedCards(PlayerState player) {
        List<String> revealed = player.getRevealedZone();
        List<PlayerView.RevealedCardView> views = new java.util.ArrayList<>();
        for (int i = 0; i < revealed.size(); i++) {
            CardMaster m = cards.findById(revealed.get(i));
            views.add(new PlayerView.RevealedCardView(i, m.name(), CardView.keywordNames(m),
                    m.hasKeyword(Keyword.GUARD)));
        }
        return views;
    }

    /**
     * 割り込み選択の問い合わせ(a9)。選択待ちでなければnull。
     * 候補の識別子(手札の位置・instanceId・墓地の位置・公開領域の位置)を、
     * 表示用のラベルに変換して届ける。クライアントは選んだ候補の位置を送り返す。
     */
    private PlayerView.PendingChoiceView buildPendingChoice(GameState state, PlayerState player, boolean isSelf) {
        // 選択の問い合わせは本人にしか見せない(相手のビューには出さない)
        if (!isSelf) {
            return null;
        }
        com.example.qte.effect.PendingChoice choice = player.getPendingChoice();
        if (choice == null) {
            return null;
        }
        List<PlayerView.PendingChoiceView.ChoiceCandidateView> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < choice.candidates().size(); i++) {
            String id = choice.candidates().get(i);
            String label;
            List<String> keywords = List.of();
            String minionInstanceId = null;
            switch (choice.kind()) {
                case HAND -> {
                    int idx = Integer.parseInt(id);
                    CardMaster m = cards.findById(player.getHand().get(idx));
                    label = m.name();
                    keywords = CardView.keywordNames(m);
                }
                case TRASH -> {
                    int idx = Integer.parseInt(id);
                    label = cards.findById(player.getTrash().get(idx)).name();
                }
                case REVEALED -> {
                    int idx = Integer.parseInt(id);
                    CardMaster m = cards.findById(player.getRevealedZone().get(idx));
                    label = m.name();
                    keywords = CardView.keywordNames(m);
                }
                case MINION -> {
                    // 候補は自分・相手どちらの場にもありうる(回帰の風穴の2回目対象など。
                    // 記法規約により「ミニオン」に側の限定が無い効果は両者の場を参照するため)
                    PlayerState opponent = state.opponentOf(player.getPlayerId());
                    MinionInstance minion = java.util.stream.Stream
                            .concat(player.getMinionZone().stream(), opponent.getMinionZone().stream())
                            .filter(mi -> mi.getInstanceId().equals(id))
                            .findFirst().orElse(null);
                    // 場を離れている場合(通常は起きない)は識別子をそのまま出す
                    label = minion != null ? minion.getMaster().name() : id;
                    minionInstanceId = id;
                }
                case MANA -> {
                    // 候補は自分のマナゾーン内の位置。裏向きのマナも本人には中身が見えるため
                    // (toManaView の contentVisible と同じ扱い)、カード名を出したうえで向きを添える
                    int idx = Integer.parseInt(id);
                    ManaCard mana = player.getManaZone().get(idx);
                    CardMaster m = cards.findById(mana.getCardId());
                    label = mana.isFaceUp() ? m.name() : m.name() + "(裏向き)";
                }
                default -> label = id;
            }
            candidates.add(new PlayerView.PendingChoiceView.ChoiceCandidateView(i, label, keywords, minionInstanceId));
        }
        return new PlayerView.PendingChoiceView(choice.kind().name(), candidates,
                choice.min(), choice.max(), choice.prompt());
    }

    /** リーダー起動能力の状態。使用可否はサーバで評価する(UIはボタンの活性に使うだけ) */
    private PlayerView.LeaderAbilityView buildLeaderAbility(GameState state, PlayerState player, boolean isSelf) {
        LeaderAbilitySpec spec = effects.leaderAbilityOf(player.getLeader().id());
        if (spec == null) {
            return null;
        }
        boolean usable = isSelf
                && state.getStatus() == com.example.qte.game.GameStatus.PLAYING
                && player.getPlayerId().equals(state.getTurnPlayerId())
                && !player.isLeaderAbilityUsedThisTurn()
                && player.getAvailableMp() >= spec.mpCost()
                // 代償を払えない能力(冥府の禁皇: 裏向きマナが必要)はボタンを押せなくする
                && spec.condition().test(state, player);
        return new PlayerView.LeaderAbilityView(usable, spec.mpCost(), spec.description(),
                toReqViews(spec.targets()));
    }

    /** 手札のカード1枚のビュー。実効コスト・対象仕様・特殊召喚可否はサーバで評価して添える */
    private CardView buildHandCard(GameState state, PlayerState player, int handIndex) {
        return buildCardView(state, player, cards.findById(player.getHand().get(handIndex)), handIndex, false);
    }

    /**
     * カード1枚のビュー。handIndexが-1のときは手札以外(禁忌デッキ)のカードで、
     * 特殊召喚は手札からの召喚のため対象外とする。
     */
    private CardView buildCardView(GameState state, PlayerState player, CardMaster master,
            int handIndex, boolean inTrash) {
        SpecialSummonSpec special = handIndex < 0 ? null : effects.specialSummonOf(master.id());
        boolean canSpecial = special != null
                && special.condition().test(state, player, handIndex);
        // ★Batch 53: 墓地からの特殊召喚(《サモナーポップ・エンラ》)。
        // 手札の位置を持たないので -1 を渡す —— 墓地から出せると宣言しているカードの条件は
        // 手札の位置を参照しない(参照するなら手札からしか出せないはずである)。
        // 判定は GameService.specialSummonFromGrave が同じ述語でやり直す
        SpecialSummonSpec fromGrave = inTrash ? effects.specialSummonOf(master.id()) : null;
        boolean canSpecialFromGrave = fromGrave != null && fromGrave.fromGrave()
                && fromGrave.condition().test(state, player, -1);
        // 対象要求と確認の文言は、手札からでも墓地からでも同じ仕様を見せる
        SpecialSummonSpec shown = special != null ? special : (canSpecialFromGrave ? fromGrave : null);
        // ★Batch 52: 進化ミニオンの素材条件。手札以外(禁忌デッキ)のカードにも添える ——
        // 禁忌からの進化召喚も出し方は同じである(マスター裁定 E1)
        EvolutionSpec evolution = effects.evolutionOf(master.id());
        TargetSpec spec = effects.targetSpecOf(master.id());
        com.example.qte.effect.EnhancedCostSpec enhanced = effects.enhancedCostOf(master.id());
        return new CardView(
                master.id(),
                master.name(),
                master.type().name(),
                master.civilization().name(),
                master.cost(),
                master.cost() == null ? null : stats.effectiveCost(state, player, master),
                master.attack(),
                master.hp(),
                CardView.keywordNames(master),
                master.text(),
                toReqViews(spec),
                canSpecial,
                shown == null ? List.of() : toReqViews(shown.targets()),
                shown == null ? null : shown.description(),
                spec.combinedTotal(),
                enhanced == null ? 0 : enhanced.extraCost(),
                enhanced == null ? null : enhanced.prompt(),
                implementation.isUnimplemented(master),
                evolutionMaterialIds(player, evolution),
                evolution == null ? 0 : evolution.minMaterials(),
                evolutionMax(player, evolution),
                evolution == null ? null : evolution.description(),
                canSpecialFromGrave);
    }

    /**
     * 今この瞬間、この進化ミニオンの素材にできる自分の場のミニオン(★Batch 52)。
     *
     * ★<b>絞り込みそのものをサーバが行い、結果だけを送る。</b>
     * クライアントは条件を1つも知らないので、素材条件を増やしても
     * {@code battle.js} を直す必要がない(裁定163・195 の回避)。
     * 検証は {@code GameService.resolveMaterials} が<b>同じ述語で</b>やり直す ——
     * 届いた値を信用しないのは他の対象選択と同じである。
     */
    private List<String> evolutionMaterialIds(PlayerState player, EvolutionSpec evolution) {
        if (evolution == null) {
            return List.of();
        }
        return player.getMinionZone().stream()
                .filter(evolution.material())
                .map(MinionInstance::getInstanceId)
                .toList();
    }

    /** 素材の最大数。「1体以上」(不敗鉄人闘太)は今の場のミニオン数が実際の上限になる */
    private int evolutionMax(PlayerState player, EvolutionSpec evolution) {
        if (evolution == null) {
            return 0;
        }
        return Math.min(evolution.maxMaterials(), player.getMinionZone().size());
    }

    private List<CardView.TargetReqView> toReqViews(TargetSpec spec) {
        return spec.requirements().stream()
                .map(r -> new CardView.TargetReqView(
                        r.kind().name(), r.side().name(), r.count(), r.optional(), r.upTo(),
                        r.filters().stream().map(Enum::name).toList(), r.prompt()))
                .toList();
    }

    /** 裏向きマナの中身は持ち主にのみ公開する(発注者確認済みルール) */
    private ManaView toManaView(ManaCard mana, boolean isSelf) {
        boolean contentVisible = mana.isFaceUp() || isSelf;
        String cardId = contentVisible ? mana.getCardId() : null;
        String name = contentVisible ? cards.findById(mana.getCardId()).name() : null;
        return new ManaView(mana.isFaceUp(), mana.isTapped(), mana.isTemporary(), cardId, name);
    }

    private MinionView toMinionView(GameState state, PlayerState owner, MinionInstance minion, boolean attackerSide) {
        CardMaster master = minion.getMaster();
        // 凍結状態は攻撃可否とは別に、盤面に印を出すためだけに使う
        boolean frozen = minion.getCannotAttackOnTurn() == state.getTurnNumber();  
        // UIハイライト用の攻撃可否。サーバ側の判定(RuleGuards)をそのまま呼ぶことで、
        // 「押せるのに弾かれる」「押せないはずが押せる」というズレが構造的に起きないようにする
        boolean canAttackMinion = attackerSide
                && guards.minionAttackDenial(state, owner, minion, false) == null;
        boolean canAttackLeader = attackerSide
                && guards.minionAttackDenial(state, owner, minion, true) == null;

        List<String> keywords = java.util.stream.Stream.concat(
                        master.keywords().stream(),
                        minion.getGrantedKeywords().stream())
                .distinct()
                .map(Keyword::getDisplayName)
                .toList();

        // ミニオンの起動能力(a6)。使えるかの判定はサーバに揃える(押せるのに弾かれるズレを防ぐ)。
        // メインフェイズ・自分の手番・タップしていない・条件を満たす、が揃ってはじめて使える
        com.example.qte.effect.MinionAbilitySpec ability = effects.minionAbilityOf(master.id());
        boolean canUseAbility = ability != null
                && attackerSide
                && state.getPhase() == com.example.qte.game.TurnPhase.MAIN
                && !owner.isCannotUseCardsThisTurn()
                && ability.usableBy(state, owner, minion);

        return new MinionView(
                minion.getInstanceId(),
                master.id(),
                master.name(),
                stats.effectiveAttack(state, owner, minion),
                minion.getCurrentHp(),
                minion.getMaxHp(),
                keywords,
                canAttackMinion,
                canAttackLeader,
                frozen,
                minion.isTapped(),
                canUseAbility,
                ability == null ? null : ability.description(),
                implementation.isUnimplemented(master),
                minion.isEvolution(),
                minion.getUnder().stream()
                        .map(com.example.qte.game.StackedCard::cardId)
                        .toList());
    }
}
