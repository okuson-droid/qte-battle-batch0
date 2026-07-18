package com.example.qte.game;

import java.util.Random;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.qte.effect.CardEffectRegistry;
import com.example.qte.effect.EffectContext;
import com.example.qte.effect.LeaderAbilitySpec;
import com.example.qte.effect.ResolvedTargets;
import com.example.qte.effect.SpecialSummonSpec;
import com.example.qte.effect.StatCalculator;
import com.example.qte.effect.TargetChoice;
import com.example.qte.effect.TargetSpec;
import com.example.qte.effect.TriggerType;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardType;
import com.example.qte.master.Keyword;
import com.example.qte.room.GameRoom;
import com.example.qte.room.PlayerSlot;

import lombok.RequiredArgsConstructor;

/**
 * ゲームルールの本体。GameStateを変更する操作の起点はこのクラスに限る。
 *
 * 役割分担(Batch 2で整理):
 * - GameService      : ルールの検証と進行の制御(いつ・誰が・何をしてよいか)
 * - CardEffectRegistry: カード効果の中身(何が起きるか)。カード追加はここに登録するだけ
 * - GameActions      : 両者が使う基本操作(ドロー・回復・破壊判定など)
 * - StatCalculator   : 動的ステータスの評価
 *
 * すべてのpublicメソッドは「呼び出し側が room.getLock() でsynchronized済み」を前提とする。
 * ルール違反の操作は IllegalStateException / IllegalArgumentException で拒否する。
 * クライアントのUIは補助にすぎず、正当性の最終判定は必ずここで行う。
 */
@Service
@RequiredArgsConstructor
public class GameService {

    private static final String PURE_ELEMENT_ID = "QTE-X001";

    private final CardMasterRepository cards;
    private final DeckFactory deckFactory;
    private final GameActions actions;
    private final CardEffectRegistry effects;
    private final StatCalculator stats;
    private final Random random = new Random();

    // ---------------------------------------------------------------
    // ゲーム開始前(Setup Phase: 総合ルール第5章)
    // ---------------------------------------------------------------

    /** 両者の準備が揃ったら試合を生成し、ダイスによる先後選択権を決める */
    public void startIfBothReady(GameRoom room) {
        if (!room.bothReady() || room.getGameState() != null) {
            return;
        }
        PlayerSlot slot1 = room.getSlots().get(0);
        PlayerSlot slot2 = room.getSlots().get(1);

        PlayerState p1 = createPlayer(slot1);
        PlayerState p2 = createPlayer(slot2);
        GameState state = new GameState(room.getRoomId(), p1, p2);
        state.setStatus(GameStatus.SETUP);
        room.setGameState(state);

        int d1;
        int d2;
        do {
            d1 = random.nextInt(6) + 1;
            d2 = random.nextInt(6) + 1;
        } while (d1 == d2);
        PlayerSlot winner = d1 > d2 ? slot1 : slot2;
        room.setDiceWinnerId(winner.getPlayerId());
        room.addLog("ダイス: %s=%d / %s=%d → %sが先攻/後攻を選択します"
                .formatted(slot1.getDisplayName(), d1, slot2.getDisplayName(), d2, winner.getDisplayName()));
    }

    private PlayerState createPlayer(PlayerSlot slot) {
        CardMaster leader = cards.findById(slot.getLeaderCardId());
        if (leader.type() != CardType.LEADER) {
            throw new IllegalArgumentException("リーダーカードではありません: " + leader.name());
        }
        PlayerState player = new PlayerState(slot.getPlayerId(), slot.getDisplayName(), leader);
        player.getDeck().addAll(deckFactory.createWaterStarterDeck());
        return player;
    }

    /** ダイス勝者による先攻/後攻の選択 → 初期ドロー → 先攻の第1ターン開始 */
    public void chooseOrder(GameRoom room, String playerId, boolean goFirst) {
        GameState state = requireState(room);
        requireStatus(state, GameStatus.SETUP);
        if (!playerId.equals(room.getDiceWinnerId())) {
            throw new IllegalStateException("先後の選択権はダイス勝者にあります");
        }
        PlayerState chooser = state.playerOf(playerId);
        PlayerState other = state.opponentOf(playerId);
        PlayerState first = goFirst ? chooser : other;
        PlayerState second = goFirst ? other : chooser;

        // 初期ドロー: 先攻4枚・後攻5枚(総合ルール5章-2)。
        // ピュア・エレメントの付与(5章-4)はマリガン(5章-3)の後に行う: ルールの手順順序に従う。
        // これによりデッキ外のカードがマリガンで山札に混入する事故も構造的に起きない
        actions.drawCards(room, first, 4);
        actions.drawCards(room, second, 5);
        state.setFirstPlayerId(first.getPlayerId());

        room.addLog("%sが先攻を選択しました".formatted(first.getDisplayName()));
        room.addLog("両プレイヤーはマリガン(手札の引き直し)を選択してください");
    }

    /**
     * マリガン(総合ルール5章-3): 任意の枚数をデッキに戻してシャッフルし、同じ枚数を引き直す。
     * ゲーム開始前に1回のみ。空選択=そのまま開始。両者が完了したら第1ターンへ。
     */
    public void mulligan(GameRoom room, String playerId, List<Integer> handIndexes) {
        GameState state = requireState(room);
        requireStatus(state, GameStatus.SETUP);
        if (state.getFirstPlayerId() == null) {
            throw new IllegalStateException("先後選択が完了していません");
        }
        PlayerState player = state.playerOf(playerId);
        if (player.isMulliganDone()) {
            throw new IllegalStateException("マリガンは1回のみです");
        }
        List<Integer> indexes = handIndexes == null ? List.of() : handIndexes;
        Set<Integer> seen = new HashSet<>();
        for (int idx : indexes) {
            if (idx < 0 || idx >= player.getHand().size() || !seen.add(idx)) {
                throw new IllegalArgumentException("不正な手札の指定です");
            }
        }
        int count = indexes.size();
        if (count > 0) {
            // 戻すカードを確定させ、大きいインデックスから除去(Batch 3と同じ揮発対策)
            List<String> returned = indexes.stream().map(i -> player.getHand().get(i)).toList();
            indexes.stream().sorted(java.util.Comparator.reverseOrder())
                    .forEach(i -> player.getHand().remove((int) i));
            // デッキに混ぜてシャッフルし、同じ枚数を引き直す
            List<String> deckList = new ArrayList<>(player.getDeck());
            deckList.addAll(returned);
            java.util.Collections.shuffle(deckList);
            player.getDeck().clear();
            player.getDeck().addAll(deckList);
            actions.drawCards(room, player, count);
        }
        player.setMulliganDone(true);
        room.addLog("%sがマリガンを完了(%d枚引き直し)".formatted(player.getDisplayName(), count));

        if (state.getPlayer1().isMulliganDone() && state.getPlayer2().isMulliganDone()) {
            // 後攻特典の付与(5章-4)→ リーダー公開・開始(5章-5)
            PlayerState second = state.opponentOf(state.getFirstPlayerId());
            second.getHand().add(PURE_ELEMENT_ID);
            room.addLog("後攻の%sに【ピュア・エレメント】が渡されました".formatted(second.getDisplayName()));
            state.setStatus(GameStatus.PLAYING);
            beginTurn(room, state.getFirstPlayerId());
        }
    }

    // ---------------------------------------------------------------
    // ターン進行(総合ルール第6章)
    // ---------------------------------------------------------------

    private void beginTurn(GameRoom room, String playerId) {
        GameState state = requireState(room);
        state.setTurnNumber(state.getTurnNumber() + 1);
        state.setTurnPlayerId(playerId);
        PlayerState player = state.playerOf(playerId);

        room.addLog("―― ターン%d: %s ――".formatted(state.getTurnNumber(), player.getDisplayName()));

        state.setPhase(TurnPhase.DRAW);
        actions.drawCards(room, player, 1);
        if (state.getStatus() == GameStatus.FINISHED) {
            return; // 山札切れ敗北
        }
        state.setPhase(TurnPhase.UNTAP);
        player.startTurnReset();

        state.setPhase(TurnPhase.MANA_CHARGE);
    }

    /** フェイズを1つ進める。サブフェイズの次はターン終了 */
    public void nextPhase(GameRoom room, String playerId) {
        GameState state = requireState(room);
        requireTurnPlayer(state, playerId);
        requireStatus(state, GameStatus.PLAYING);
        switch (state.getPhase()) {
            case MANA_CHARGE -> state.setPhase(TurnPhase.MAIN);
            case MAIN -> state.setPhase(TurnPhase.BATTLE);
            case BATTLE -> state.setPhase(TurnPhase.SUB);
            case SUB -> endTurn(room, playerId);
            default -> throw new IllegalStateException("このフェイズは手動で進められません");
        }
    }

    /** ターン終了処理: 期限付き効果の掃除 → 相手ターンの開始 */
    public void endTurn(GameRoom room, String playerId) {
        GameState state = requireState(room);
        requireTurnPlayer(state, playerId);
        requireStatus(state, GameStatus.PLAYING);
        state.setPhase(TurnPhase.END);

        // 「このターン中」の効果を除去する(設計判断12)
        for (PlayerState p : new PlayerState[] { state.getPlayer1(), state.getPlayer2() }) {
            p.getMinionZone().forEach(MinionInstance::expireThisTurnModifiers);
            p.getThisTurnAuras().clear();

            // ピュア・エレメントの一時マナはターンの終わりに消滅(Lost)ゾーンへ。
            // これも「マナが離れた」に該当するため水龍のトリガー対象になる
            List<ManaCard> expired = p.getManaZone().stream().filter(ManaCard::isTemporary).toList();
            for (ManaCard mana : expired) {
                p.getManaZone().remove(mana);
                p.getLostZone().add(mana.getCardId());
                room.addLog("一時マナ【%s】が消滅しました".formatted(cards.findById(mana.getCardId()).name()));
                actions.manaLeft(room, p);
            }
        }
        // ターンエンドトリガー(連撃の巨岩など)は該当カード実装時にここで発火する

        beginTurn(room, state.opponentOf(playerId).getPlayerId());
    }

    // ---------------------------------------------------------------
    // プレイヤーの操作
    // ---------------------------------------------------------------

    /** マナチャージ: 手札から1枚を表向きでマナゾーンへ(1ターン1回・上限15枚) */
    public void chargeMana(GameRoom room, String playerId, int handIndex) {
        GameState state = requireState(room);
        requireTurnPlayer(state, playerId);
        requirePhase(state, TurnPhase.MANA_CHARGE);
        PlayerState player = state.playerOf(playerId);
        if (player.isManaChargedThisTurn()) {
            throw new IllegalStateException("マナチャージは1ターンに1回までです");
        }
        if (player.getManaZone().size() >= PlayerState.MAX_MANA) {
            throw new IllegalStateException("マナは15枚までです");
        }
        String cardId = takeFromHand(player, handIndex);
        player.getManaZone().add(new ManaCard(cardId, false));
        player.setManaChargedThisTurn(true);
        room.addLog("%sがマナチャージしました(マナ%d枚)"
                .formatted(player.getDisplayName(), player.getManaZone().size()));
    }

    /**
     * 手札のカードをプレイする。
     * ミニオン: メインフェイズのみ / スペル: メイン・サブフェイズ(総合ルール6章)
     * 対象指定を要するカードは、選択済みの対象(choices)を添えて呼び出される。
     * ウェポンとピュア・エレメントはBatch 4で対応する。
     */
    public void playCard(GameRoom room, String playerId, int handIndex, List<TargetChoice> choices) {
        GameState state = requireState(room);
        requireTurnPlayer(state, playerId);
        requireStatus(state, GameStatus.PLAYING);
        PlayerState player = state.playerOf(playerId);
        if (player.isCannotUseCardsThisTurn()) {
            throw new IllegalStateException("このターンはカードを使用できません");
        }
        CardMaster master = cards.findById(peekHand(player, handIndex));

        switch (master.type()) {
            case MINION -> playMinion(room, state, player, handIndex, master, choices);
            case SPELL -> playSpell(room, state, player, handIndex, master, choices);
            case WEAPON -> playWeapon(room, state, player, handIndex, master);
            default -> throw new IllegalStateException("このカードはプレイできません");
        }
        player.setPlayedCardThisTurn(true);
    }

    private void playMinion(GameRoom room, GameState state, PlayerState player,
            int handIndex, CardMaster master, List<TargetChoice> choices) {
        requirePhase(state, TurnPhase.MAIN);
        if (player.isMinionZoneFull()) {
            throw new IllegalStateException("ミニオンは6体までです");
        }
        // 検証(状態を変えない)→ 支払い → 手札除去 → 場に出す → 効果、の順を守る。
        // 検証で弾かれた場合に状態が一切変わっていないことを保証するため
        ValidatedTargets validated = validateTargets(state, player, handIndex,
                effects.targetSpecOf(master.id()), choices);
        payCost(player, stats.effectiveCost(state, player, master));
        ResolvedTargets resolved = removePlayedAndTargets(player, handIndex, validated);

        summonToField(room, state, player, master, resolved);
    }

    private void playSpell(GameRoom room, GameState state, PlayerState player,
            int handIndex, CardMaster master, List<TargetChoice> choices) {
        if (state.getPhase() != TurnPhase.MAIN && state.getPhase() != TurnPhase.SUB) {
            throw new IllegalStateException("スペルはメイン/サブフェイズでのみ使用できます");
        }
        if (PURE_ELEMENT_ID.equals(master.id())) {
            playPureElement(room, player, handIndex);
            return;
        }
        if (!effects.isSpellImplemented(master.id())) {
            throw new IllegalStateException("このスペルの効果は未実装です");
        }
        ValidatedTargets validated = validateTargets(state, player, handIndex,
                effects.targetSpecOf(master.id()), choices);
        payCost(player, stats.effectiveCost(state, player, master));
        ResolvedTargets resolved = removePlayedAndTargets(player, handIndex, validated);
        room.addLog("%sが【%s】を唱えました".formatted(player.getDisplayName(), master.name()));

        effects.resolveSpell(master.id(), contextOf(room, state, player, null, resolved));
        // 使用後の行き先: 通常は墓地、【還元】ならマナへ(GameActionsが判断する)
        actions.disposeUsedSpell(room, player, master);
    }

    /**
     * 【特殊召喚】: カード記載の条件・代替コストによる代替召喚(キーワード定義)。
     * 召喚として扱われるため、着地後はON_SUMMON/ON_ENTERの両方が発動する。
     */
    public void specialSummon(GameRoom room, String playerId, int handIndex, List<TargetChoice> choices) {
        GameState state = requireState(room);
        requireTurnPlayer(state, playerId);
        requireStatus(state, GameStatus.PLAYING);
        requirePhase(state, TurnPhase.MAIN);
        PlayerState player = state.playerOf(playerId);
        if (player.isCannotUseCardsThisTurn()) {
            throw new IllegalStateException("このターンはカードを使用できません");
        }
        CardMaster master = cards.findById(peekHand(player, handIndex));
        SpecialSummonSpec spec = effects.specialSummonOf(master.id());
        if (spec == null) {
            throw new IllegalStateException("このカードは特殊召喚できません");
        }
        if (!spec.condition().test(state, player, handIndex)) {
            throw new IllegalStateException("特殊召喚の条件を満たしていません");
        }
        // 代替コストが自分のミニオンを場から離す場合(知恵の双翼)は枠が空くため、
        // それ以外のカードのみ事前に上限チェックする
        boolean costFreesZone = spec.targets().requirements().stream()
                .anyMatch(r -> r.kind() == TargetSpec.Kind.MINION && r.side() == TargetSpec.Side.SELF);
        if (player.isMinionZoneFull() && !costFreesZone) {
            throw new IllegalStateException("ミニオンは6体までです");
        }

        ValidatedTargets validated = validateTargets(state, player, handIndex, spec.targets(), choices);
        ResolvedTargets resolved = removePlayedAndTargets(player, handIndex, validated);
        room.addLog("%sが【%s】を特殊召喚".formatted(player.getDisplayName(), master.name()));
        // 代替コストの支払い(手札を山札の下へ・ミニオンを手札に戻す等)。MPは支払わない
        spec.costEffect().accept(contextOf(room, state, player, null, resolved));

        summonToField(room, state, player, master, resolved);
        player.setPlayedCardThisTurn(true);
    }

    /** 召喚の共通着地処理。ON_SUMMONとON_ENTERの両方が発動する(発注者確認済み裁定)。
     *  効果による「出す」(黄泉還る水龍など)を実装するときはON_ENTERのみを発火する */
    private void summonToField(GameRoom room, GameState state, PlayerState player,
            CardMaster master, ResolvedTargets resolved) {
        MinionInstance minion = new MinionInstance(master, state.getTurnNumber());
        player.getMinionZone().add(minion);
        room.addLog("%sが【%s】を召喚しました".formatted(player.getDisplayName(), master.name()));

        EffectContext ctx = contextOf(room, state, player, minion, resolved);
        effects.fire(TriggerType.ON_SUMMON, minion, ctx);
        effects.fire(TriggerType.ON_ENTER, minion, ctx);
    }

    /** ウェポンの装備。装備済みなら古いウェポンは即座に墓地へ(総合ルール2-5) */
    private void playWeapon(GameRoom room, GameState state, PlayerState player,
            int handIndex, CardMaster master) {
        requirePhase(state, TurnPhase.MAIN);
        payCost(player, stats.effectiveCost(state, player, master));
        takeFromHand(player, handIndex);
        if (player.getEquippedWeapon() != null) {
            player.getTrash().add(player.getEquippedWeapon().id());
            room.addLog("【%s】は墓地へ送られました".formatted(player.getEquippedWeapon().name()));
        }
        player.setEquippedWeapon(master);
        room.addLog("%sが【%s】を装備しました".formatted(player.getDisplayName(), master.name()));
    }

    /**
     * ピュア・エレメント: 使用時このカード自身を裏向きの一時マナとしてマナゾーンに置く。
     * 通常のスペルと違い墓地へ行かない(カード自体がマナになる)。ターン終了時に消滅する。
     */
    private void playPureElement(GameRoom room, PlayerState player, int handIndex) {
        if (player.getManaZone().size() >= PlayerState.MAX_MANA) {
            throw new IllegalStateException("マナは15枚までです");
        }
        takeFromHand(player, handIndex); // コスト0
        ManaCard mana = new ManaCard(PURE_ELEMENT_ID, true);
        mana.turnFaceDown();
        player.getManaZone().add(mana);
        room.addLog("%sが【ピュア・エレメント】を使用: このターンの間マナが1枚増えます"
                .formatted(player.getDisplayName()));
    }

    /**
     * リーダーの攻撃(総合ルール4-3)。装備状態のリーダーのみ・1ターンに1回。
     * 攻撃側がターンプレイヤーのリーダーの場合、反撃ダメージを受けない(一方的)。
     */
    public void leaderAttack(GameRoom room, String playerId, String targetInstanceId) {
        GameState state = requireState(room);
        requireTurnPlayer(state, playerId);
        requirePhase(state, TurnPhase.BATTLE);
        PlayerState player = state.playerOf(playerId);
        PlayerState opponent = state.opponentOf(playerId);
        CardMaster weapon = player.getEquippedWeapon();
        if (weapon == null) {
            throw new IllegalStateException("戦闘を行えるのはウェポンを装備したリーダーのみです");
        }
        if (player.isLeaderAttackedThisTurn()) {
            throw new IllegalStateException("リーダーはこのターン既に攻撃しています");
        }
        if (player.getLeaderCannotAttackOnTurn() == state.getTurnNumber()) {
            throw new IllegalStateException("リーダーは凍結していて攻撃できません");
        }
        boolean targetIsLeader = targetInstanceId == null;
        MinionInstance target = targetIsLeader ? null : findMinion(opponent, targetInstanceId);

        // 攻撃対象の検証はミニオンの攻撃と同じ規則(威圧・守護)。貫通はウェポン側の所持で判定
        if (target != null && target.hasKeyword(Keyword.INTIMIDATE)) {
            throw new IllegalStateException("【威圧】持ちは攻撃対象にできません");
        }
        boolean opponentHasGuard = opponent.getMinionZone().stream()
                .anyMatch(m -> m.hasKeyword(Keyword.GUARD));
        boolean targetIsGuard = target != null && target.hasKeyword(Keyword.GUARD);
        if (opponentHasGuard && !targetIsGuard && !weapon.hasKeyword(Keyword.PIERCE)) {
            throw new IllegalStateException("相手の【守護】持ちを先に攻撃する必要があります");
        }

        player.setLeaderAttackedThisTurn(true);
        int damage = stats.effectiveWeaponAttack(state, player);
        room.addLog("リーダーが【%s】で攻撃(%dダメージ)".formatted(weapon.name(), damage));

        if (targetIsLeader) {
            opponent.setLp(opponent.getLp() - damage);
            room.addLog("相手リーダーに%dダメージ(残りLP %d)".formatted(damage, opponent.getLp()));
            if (opponent.getLp() <= 0) {
                actions.finish(room, player);
                return;
            }
        } else {
            // 一方的にダメージを与える(反撃なし: 4-3)
            target.takeDamage(damage);
            actions.checkDestruction(room, opponent, target);
        }

        // ウェポンの攻撃時効果。現状2種のためswitchで直書きし、増えたらRegistryへ移す(TODO)
        switch (weapon.id()) {
            case "QTE-0030" -> { // 真珠の三叉槍: 自分のリーダーが攻撃した時、カードを1枚引く
                actions.drawCards(room, player, 1);
            }
            case "QTE-0031" -> { // 氷結の杖: 攻撃されたリーダーまたはミニオンは、次のターン攻撃できない
                int nextTurn = state.getTurnNumber() + 1;
                if (targetIsLeader) {
                    opponent.setLeaderCannotAttackOnTurn(nextTurn);
                    room.addLog("相手リーダーは凍結しました(次のターン攻撃不可)");
                } else if (opponent.getMinionZone().contains(target)) {
                    target.setCannotAttackOnTurn(nextTurn);
                    room.addLog("【%s】は凍結しました(次のターン攻撃不可)".formatted(target.getMaster().name()));
                }
            }
            default -> {
            }
        }
    }

    /**
     * リーダーの起動能力。メインフェイズ中のみ・1ターンに1回(発注者確認済みルール)。
     * カードの「使用」ではないため、静寂の瞑想の使用制限の影響は受けない。
     */
    public void useLeaderAbility(GameRoom room, String playerId, List<TargetChoice> choices) {
        GameState state = requireState(room);
        requireTurnPlayer(state, playerId);
        requireStatus(state, GameStatus.PLAYING);
        requirePhase(state, TurnPhase.MAIN);
        PlayerState player = state.playerOf(playerId);
        LeaderAbilitySpec spec = effects.leaderAbilityOf(player.getLeader().id());
        if (spec == null) {
            throw new IllegalStateException("このリーダーは起動能力を持ちません");
        }
        if (player.isLeaderAbilityUsedThisTurn()) {
            throw new IllegalStateException("起動能力は1ターンに1回までです");
        }
        ValidatedTargets validated = validateTargets(state, player, -1, spec.targets(), choices);
        payCost(player, spec.mpCost());
        ResolvedTargets resolved = removePlayedAndTargets(player, -1, validated);
        player.setLeaderAbilityUsedThisTurn(true);
        room.addLog("%sがリーダー起動能力を使用".formatted(player.getDisplayName()));
        spec.effect().accept(contextOf(room, state, player, null, resolved));
    }

    /**
     * 攻撃宣言と戦闘解決(総合ルール第4章)。
     *
     * @param targetInstanceId 攻撃対象。リーダー攻撃の場合はnull
     */
    public void attack(GameRoom room, String playerId, String attackerInstanceId, String targetInstanceId) {
        GameState state = requireState(room);
        requireTurnPlayer(state, playerId);
        requirePhase(state, TurnPhase.BATTLE);
        PlayerState player = state.playerOf(playerId);
        PlayerState opponent = state.opponentOf(playerId);

        MinionInstance attacker = findMinion(player, attackerInstanceId);
        boolean targetIsLeader = targetInstanceId == null;
        MinionInstance target = targetIsLeader ? null : findMinion(opponent, targetInstanceId);

        validateAttack(state, attacker, target, targetIsLeader, opponent);

        attacker.countAttack();
        room.addLog("【%s】が攻撃を宣言".formatted(attacker.getMaster().name()));
        effects.fire(TriggerType.ON_ATTACK, attacker, contextOf(room, state, player, attacker, null));

        // 攻撃時効果でゲームが決着した場合(山札切れ等)は戦闘を解決しない
        if (state.getStatus() == GameStatus.FINISHED) {
            return;
        }

        if (targetIsLeader) {
            int damage = stats.effectiveAttack(state, player, attacker);
            opponent.setLp(opponent.getLp() - damage);
            room.addLog("リーダーに%dダメージ(残りLP %d)".formatted(damage, opponent.getLp()));
            if (opponent.getLp() <= 0) {
                actions.finish(room, player);
            }
        } else {
            // ミニオン同士: お互いのAttackを同時に与え合う(4-2)。
            // ダメージ適用と破壊判定は別ステップ(設計判断2)
            int toTarget = stats.effectiveAttack(state, player, attacker);
            int toAttacker = stats.effectiveAttack(state, opponent, target);
            target.takeDamage(toTarget);
            attacker.takeDamage(toAttacker);
            room.addLog("【%s】⇔【%s】(%d ⇔ %d)"
                    .formatted(attacker.getMaster().name(), target.getMaster().name(), toTarget, toAttacker));
            actions.checkDestruction(room, opponent, target);
            actions.checkDestruction(room, player, attacker);
        }
    }

    private void validateAttack(GameState state, MinionInstance attacker, MinionInstance target,
            boolean targetIsLeader, PlayerState opponent) {
        if (attacker.getAttacksUsedThisTurn() >= 1) {
            throw new IllegalStateException("このミニオンはこのターン既に攻撃しています");
        }
        if (attacker.getCannotAttackOnTurn() == state.getTurnNumber()) {
            throw new IllegalStateException("このミニオンは凍結していて攻撃できません");
        }
        boolean enteredThisTurn = attacker.getEnteredTurn() == state.getTurnNumber();
        if (enteredThisTurn) {
            boolean allowed = attacker.hasKeyword(Keyword.HASTE)
                    || (attacker.hasKeyword(Keyword.RUSH) && !targetIsLeader);
            if (!allowed) {
                throw new IllegalStateException(targetIsLeader
                        ? "出たターンにリーダーへ攻撃できるのは【速攻】持ちのみです"
                        : "出たターンのミニオンは攻撃できません(【速攻】【突進】を除く)");
            }
        }
        if (target != null && target.hasKeyword(Keyword.INTIMIDATE)) {
            throw new IllegalStateException("【威圧】持ちは攻撃対象にできません");
        }
        boolean opponentHasGuard = opponent.getMinionZone().stream()
                .anyMatch(m -> m.hasKeyword(Keyword.GUARD));
        boolean targetIsGuard = target != null && target.hasKeyword(Keyword.GUARD);
        if (opponentHasGuard && !targetIsGuard && !attacker.hasKeyword(Keyword.PIERCE)) {
            throw new IllegalStateException("相手の【守護】持ちを先に攻撃する必要があります");
        }
    }

    // ---------------------------------------------------------------
    // 内部ヘルパー
    // ---------------------------------------------------------------

    private EffectContext contextOf(GameRoom room, GameState state, PlayerState owner,
            MinionInstance source, ResolvedTargets targets) {
        return new EffectContext(room, state, owner,
                state.opponentOf(owner.getPlayerId()), source, targets, actions);
    }

    // ---------------------------------------------------------------
    // 対象指定の検証と解決
    // ---------------------------------------------------------------

    /** 検証済みだがまだ手札から除去していない中間状態 */
    private record ValidatedTargets(
            List<TargetSpec.Requirement> requirements,
            List<List<Integer>> handIndexes,
            List<List<ResolvedTargets.TargetedMinion>> minions,
            List<List<ManaCard>> mana) {
    }

    /**
     * クライアントの選択(choices)を仕様(spec)に照らして検証する。状態は一切変更しない。
     * ここが対象指定の「正当性の最終判定」であり、改造クライアントの不正な選択は全てここで弾く。
     */
    private ValidatedTargets validateTargets(GameState state, PlayerState player,
            int playedHandIndex, TargetSpec spec, List<TargetChoice> choices) {
        List<TargetSpec.Requirement> reqs = spec.requirements();
        if (reqs.isEmpty()) {
            return new ValidatedTargets(reqs, List.of(), List.of(), List.of());
        }
        if (choices == null || choices.size() != reqs.size()) {
            throw new IllegalArgumentException("対象の指定が不足しています");
        }
        List<List<Integer>> handPerReq = new ArrayList<>();
        List<List<ResolvedTargets.TargetedMinion>> minionsPerReq = new ArrayList<>();
        List<List<ManaCard>> manaPerReq = new ArrayList<>();
        Set<Integer> usedHandIndexes = new HashSet<>();
        Set<String> usedMinionIds = new HashSet<>();

        for (int i = 0; i < reqs.size(); i++) {
            TargetSpec.Requirement req = reqs.get(i);
            TargetChoice choice = choices.get(i);
            switch (req.kind()) {
                case HAND -> {
                    List<Integer> indexes = choice.handIndexes();
                    requireCount(req, indexes.size());
                    for (int idx : indexes) {
                        if (idx < 0 || idx >= player.getHand().size()) {
                            throw new IllegalArgumentException("不正な手札の指定です");
                        }
                        if (idx == playedHandIndex) {
                            throw new IllegalArgumentException("プレイするカード自身は対象にできません");
                        }
                        if (!usedHandIndexes.add(idx)) {
                            throw new IllegalArgumentException("同じカードを重複して選べません");
                        }
                        checkFilter(req, cards.findById(player.getHand().get(idx)));
                    }
                    handPerReq.add(List.copyOf(indexes));
                    minionsPerReq.add(List.of());
                    manaPerReq.add(List.of());
                }
                case MANA -> {
                    List<Integer> indexes = choice.manaIndexes();
                    requireCount(req, indexes.size());
                    List<ManaCard> manaList = new ArrayList<>();
                    Set<Integer> seen = new HashSet<>();
                    for (int idx : indexes) {
                        if (idx < 0 || idx >= player.getManaZone().size() || !seen.add(idx)) {
                            throw new IllegalArgumentException("不正なマナの指定です");
                        }
                        manaList.add(player.getManaZone().get(idx));
                    }
                    handPerReq.add(List.of());
                    minionsPerReq.add(List.of());
                    manaPerReq.add(manaList);
                }
                case MINION -> {
                    List<String> ids = choice.minionIds();
                    requireCount(req, ids.size());
                    List<ResolvedTargets.TargetedMinion> resolved = new ArrayList<>();
                    for (String id : ids) {
                        if (!usedMinionIds.add(id)) {
                            throw new IllegalArgumentException("同じミニオンを重複して選べません");
                        }
                        ResolvedTargets.TargetedMinion tm = findOnSide(state, player, req.side(), id);
                        // 【潜伏】: 相手のカードや能力の対象にならない(自分は対象にできる)
                        if (tm.owner() != player && tm.minion().hasKeyword(Keyword.STEALTH)) {
                            throw new IllegalArgumentException("【潜伏】持ちは相手の効果の対象になりません");
                        }
                        checkFilter(req, tm.minion().getMaster());
                        resolved.add(tm);
                    }
                    handPerReq.add(List.of());
                    minionsPerReq.add(resolved);
                    manaPerReq.add(List.of());
                }
            }
        }
        return new ValidatedTargets(reqs, handPerReq, minionsPerReq, manaPerReq);
    }

    private void requireCount(TargetSpec.Requirement req, int actual) {
        boolean ok = actual == req.count() || (req.optional() && actual == 0);
        if (!ok) {
            throw new IllegalArgumentException("対象は%d体(枚)選ぶ必要があります".formatted(req.count()));
        }
    }

    private void checkFilter(TargetSpec.Requirement req, CardMaster master) {
        if (req.filter() == TargetSpec.Filter.KNOWLEDGE && !master.hasKeyword(Keyword.KNOWLEDGE)) {
            throw new IllegalArgumentException("【知識】を持つカードを選んでください");
        }
    }

    private ResolvedTargets.TargetedMinion findOnSide(GameState state, PlayerState player,
            TargetSpec.Side side, String instanceId) {
        PlayerState opponent = state.opponentOf(player.getPlayerId());
        if (side != TargetSpec.Side.OPPONENT) {
            var found = player.getMinionZone().stream()
                    .filter(m -> m.getInstanceId().equals(instanceId)).findFirst();
            if (found.isPresent()) {
                return new ResolvedTargets.TargetedMinion(player, found.get());
            }
        }
        if (side != TargetSpec.Side.SELF) {
            var found = opponent.getMinionZone().stream()
                    .filter(m -> m.getInstanceId().equals(instanceId)).findFirst();
            if (found.isPresent()) {
                return new ResolvedTargets.TargetedMinion(opponent, found.get());
            }
        }
        throw new IllegalArgumentException("指定されたミニオンが対象範囲にいません");
    }

    /**
     * プレイするカードと手札対象をまとめて手札から取り除き、対象を確定させる。
     * インデックスは大きい順に取り除く(小さい順に消すと後続の位置がずれる)。
     */
    private ResolvedTargets removePlayedAndTargets(PlayerState player, int playedHandIndex,
            ValidatedTargets validated) {
        // 除去前にカードIDを確定させる(除去後はインデックスが無効になるため)
        List<List<String>> handIdsPerReq = new ArrayList<>();
        List<Integer> allIndexes = new ArrayList<>();
        if (playedHandIndex >= 0) {
            allIndexes.add(playedHandIndex); // リーダー起動能力(-1)ではプレイするカードが存在しない
        }
        for (List<Integer> indexes : validated.handIndexes()) {
            handIdsPerReq.add(indexes.stream().map(i -> player.getHand().get(i)).toList());
            allIndexes.addAll(indexes);
        }
        allIndexes.sort(java.util.Comparator.reverseOrder());
        for (int idx : allIndexes) {
            player.getHand().remove(idx);
        }

        List<ResolvedTargets.Selection> selections = new ArrayList<>();
        for (int i = 0; i < validated.requirements().size(); i++) {
            List<String> handIds = validated.handIndexes().get(i).isEmpty()
                    ? List.of() : handIdsPerReq.get(i);
            selections.add(new ResolvedTargets.Selection(handIds, validated.minions().get(i),
                    validated.mana().get(i)));
        }
        return new ResolvedTargets(selections);
    }

    private void payCost(PlayerState player, int cost) {
        if (player.getAvailableMp() < cost) {
            throw new IllegalStateException("MPが足りません(必要%d/使用可能%d)"
                    .formatted(cost, player.getAvailableMp()));
        }
        int remaining = cost;
        for (ManaCard mana : player.getManaZone()) {
            if (remaining == 0) {
                break;
            }
            if (!mana.isTapped()) {
                mana.tap();
                remaining--;
            }
        }
    }

    private String peekHand(PlayerState player, int handIndex) {
        if (handIndex < 0 || handIndex >= player.getHand().size()) {
            throw new IllegalArgumentException("不正な手札の指定です");
        }
        return player.getHand().get(handIndex);
    }

    private String takeFromHand(PlayerState player, int handIndex) {
        peekHand(player, handIndex);
        return player.getHand().remove(handIndex);
    }

    private MinionInstance findMinion(PlayerState owner, String instanceId) {
        return owner.getMinionZone().stream()
                .filter(m -> m.getInstanceId().equals(instanceId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("指定されたミニオンが場にいません"));
    }

    private GameState requireState(GameRoom room) {
        GameState state = room.getGameState();
        if (state == null) {
            throw new IllegalStateException("対戦がまだ開始されていません");
        }
        return state;
    }

    private void requireStatus(GameState state, GameStatus expected) {
        if (state.getStatus() != expected) {
            throw new IllegalStateException("この操作は現在の状態(%s)では行えません".formatted(state.getStatus()));
        }
    }

    private void requireTurnPlayer(GameState state, String playerId) {
        if (!playerId.equals(state.getTurnPlayerId())) {
            throw new IllegalStateException("相手のターンです");
        }
    }

    private void requirePhase(GameState state, TurnPhase expected) {
        if (state.getPhase() != expected) {
            throw new IllegalStateException("この操作は%sフェイズでのみ行えます(現在: %sフェイズ)"
                    .formatted(expected.getDisplayName(), state.getPhase().getDisplayName()));
        }
    }
}
