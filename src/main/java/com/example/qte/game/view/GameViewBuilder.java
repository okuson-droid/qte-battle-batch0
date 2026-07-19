package com.example.qte.game.view;

import java.util.List;

import org.springframework.stereotype.Component;

import com.example.qte.game.GameState;
import com.example.qte.game.GameStatus;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.game.TurnPhase;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.effect.CardEffectRegistry;
import com.example.qte.effect.LeaderAbilitySpec;
import com.example.qte.effect.SpecialSummonSpec;
import com.example.qte.effect.StatCalculator;
import com.example.qte.effect.TargetSpec;
import com.example.qte.master.CardMaster;
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
        boolean leaderCanAttack = attackerSide
                && player.getEquippedWeapon() != null
                && !player.isLeaderAttackedThisTurn()
                && !leaderFrozen;

        // 禁忌デッキの中身は所有者のみ閲覧できる(総合ルール3-2)。相手には枚数だけを送る
        List<CardView> taboo = null;
        if (isSelf) {
            taboo = player.getTabooDeck().stream()
                    .map(id -> buildCardView(state, player, cards.findById(id), -1))
                    .toList();
        }

        return new PlayerView(
                player.getDisplayName(),
                player.getLeader().name(),
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
                player.getLostZone().size(),
                player.getLostZone().stream().map(id -> cards.findById(id).name()).toList(),
                player.getTabooDeck().size(),
                taboo,
                player.isManaChargedThisTurn(),
                player.isCannotUseCardsThisTurn(),
                player.isMulliganDone(),
                player.getLeader().text(),
                player.getEquippedWeapon() == null ? null : player.getEquippedWeapon().name(),
                player.getEquippedWeapon() == null ? null
                        : stats.effectiveWeaponAttack(state, player),
                leaderCanAttack,
                leaderFrozen,
                buildLeaderAbility(state, player, isSelf));
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
                && player.getAvailableMp() >= spec.mpCost();
        return new PlayerView.LeaderAbilityView(usable, spec.mpCost(), spec.description(),
                toReqViews(spec.targets()));
    }

    /** 手札のカード1枚のビュー。実効コスト・対象仕様・特殊召喚可否はサーバで評価して添える */
    private CardView buildHandCard(GameState state, PlayerState player, int handIndex) {
        return buildCardView(state, player, cards.findById(player.getHand().get(handIndex)), handIndex);
    }

    /**
     * カード1枚のビュー。handIndexが-1のときは手札以外(禁忌デッキ)のカードで、
     * 特殊召喚は手札からの召喚のため対象外とする。
     */
    private CardView buildCardView(GameState state, PlayerState player, CardMaster master, int handIndex) {
        SpecialSummonSpec special = handIndex < 0 ? null : effects.specialSummonOf(master.id());
        boolean canSpecial = special != null
                && special.condition().test(state, player, handIndex);
        return new CardView(
                master.id(),
                master.name(),
                master.type().name(),
                master.cost(),
                master.cost() == null ? null : stats.effectiveCost(state, player, master),
                master.attack(),
                master.hp(),
                CardView.keywordNames(master),
                master.text(),
                toReqViews(effects.targetSpecOf(master.id())),
                canSpecial,
                special == null ? List.of() : toReqViews(special.targets()),
                special == null ? null : special.description());
    }

    private List<CardView.TargetReqView> toReqViews(TargetSpec spec) {
        return spec.requirements().stream()
                .map(r -> new CardView.TargetReqView(
                        r.kind().name(), r.side().name(), r.count(), r.optional(),
                        r.filter() == null ? null : r.filter().name(), r.prompt()))
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
        boolean sick = minion.getEnteredTurn() == state.getTurnNumber();
        boolean frozen = minion.getCannotAttackOnTurn() == state.getTurnNumber();
        boolean hasAttacksLeft = minion.getAttacksUsedThisTurn() < 1 && !frozen;
        // UIハイライト用の攻撃可否(正当性の最終判定はGameService側)
        boolean canAttackMinion = attackerSide && hasAttacksLeft
                && (!sick || minion.hasKeyword(Keyword.HASTE) || minion.hasKeyword(Keyword.RUSH));
        boolean canAttackLeader = attackerSide && hasAttacksLeft
                && (!sick || minion.hasKeyword(Keyword.HASTE));

        List<String> keywords = java.util.stream.Stream.concat(
                        master.keywords().stream(),
                        minion.getGrantedKeywords().stream())
                .distinct()
                .map(Keyword::getDisplayName)
                .toList();

        return new MinionView(
                minion.getInstanceId(),
                master.id(),
                master.name(),
                stats.effectiveAttack(state, owner, minion),
                minion.getCurrentHp(),
                master.hp(),
                keywords,
                canAttackMinion,
                canAttackLeader,
                frozen);
    }
}
