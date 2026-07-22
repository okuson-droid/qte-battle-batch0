package com.example.qte.game;

import java.util.Random;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.qte.effect.CardEffectRegistry;
import com.example.qte.effect.EffectContext;
import com.example.qte.effect.EnhancedCostSpec;
import com.example.qte.effect.LeaderAbilitySpec;
import com.example.qte.effect.MinionAbilitySpec;
import com.example.qte.effect.PendingChoice;
import com.example.qte.effect.PersistentAura;
import com.example.qte.effect.RuleGuards;
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

    /** リーダー【黄泉の召喚主】。サブフェイズ中に墓地からミニオンを召喚できる */
    private static final String GRAVE_SUMMONER_LEADER_ID = "QTE-L006";

    /** 【死者蘇生】。生贄の数だけ自身のコストが下がるため、支払い前に選択結果を渡す */
    private static final String SACRIFICE_SPELL_ID = "QTE-0080";

    private final CardMasterRepository cards;
    private final DeckFactory deckFactory;
    private final GameActions actions;
    private final CardEffectRegistry effects;
    private final StatCalculator stats;

    /** 攻撃・破壊・ドロー・使用の可否を盤面から判定する層(光文明の置換・禁止効果) */
    private final RuleGuards guards;
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
        player.setDeckName(slot.getDeckName());
        if (slot.getDeck() != null) {
            // 読み込まれたデッキファイル(検証済み)を使う
            player.getDeck().addAll(deckFactory.createMainDeckFrom(slot.getDeck()));
            player.getTabooDeck().addAll(deckFactory.createTabooDeckFrom(slot.getDeck()));
        } else {
            // デッキ未指定: 文明ごとのプリセット
            player.getDeck().addAll(deckFactory.createMainDeck(leader));
            player.getTabooDeck().addAll(deckFactory.createTabooDeck(leader));
        }
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
            case BATTLE -> {
                // 【戒律の聖堂騎士】が相手の場にいる場合、サブフェイズを飛ばして終了へ進む
                if (guards.canEnterSubPhase(state, state.playerOf(playerId))) {
                    state.setPhase(TurnPhase.SUB);
                } else {
                    room.addLog("【戒律の聖堂騎士】の効果でサブフェイズを行えません");
                    endTurn(room, playerId);
                }
            }
            case SUB -> endTurn(room, playerId);
            default -> throw new IllegalStateException("このフェイズは手動で進められません");
        }
    }

    /**
     * ターン終了処理: 期限付き効果の掃除 → ターンエンドトリガー → 相手ターンの開始。
     *
     * Batch 12a で「ターンの終了」と「次のターンの開始」を分割した(a9)。
     * ターンエンド時効果(詠唱の疾風騎士)がプレイヤーへの問い合わせで中断すると、
     * その選択の解決を待たずに相手のターンが始まってしまうためである。
     * 選択が保留された場合はここでは手番を渡さず、resolveChoice の末尾から advanceTurn を呼ぶ。
     */
    public void endTurn(GameRoom room, String playerId) {
        GameState state = requireState(room);
        requireTurnPlayer(state, playerId);
        requireStatus(state, GameStatus.PLAYING);
        state.setPhase(TurnPhase.END);

        // ターンエンドトリガー(詠唱の疾風騎士など)。「このターン5回以上スペルを撃っていたら」の
        // ように、このターンのカウンタや修正が生きているうちに評価する必要があるため、
        // 「このターン中」の効果を掃除するより前に発火する(a4)。
        // 両者の場を回すが、ターンプレイヤー側から先に処理する
        PlayerState turnPlayer = state.playerOf(playerId);
        PlayerState other = state.opponentOf(playerId);
        for (PlayerState p : new PlayerState[] { turnPlayer, other }) {
            for (MinionInstance minion : List.copyOf(p.getMinionZone())) {
                effects.fire(TriggerType.ON_TURN_END, minion,
                        contextOf(room, state, p, minion, null));
                if (state.getStatus() == GameStatus.FINISHED) {
                    return;
                }
            }
        }
        // ターンエンド効果がプレイヤーの選択待ちになったら、掃除と手番の受け渡しを保留する。
        // 選択が解決した後に advanceTurn がこの続き(掃除→次ターン)を行う
        if (turnPlayer.getPendingChoice() != null || other.getPendingChoice() != null) {
            state.setTurnHandoffPending(true);
            state.setPendingNextPlayerId(other.getPlayerId());
            return;
        }
        finishEndTurnCleanup(room, state);
        advanceTurn(room, state, other.getPlayerId());
    }

    /**
     * ターンエンドの後始末(「このターン中」の効果の除去・自壊ミニオン・一時マナの消滅)。
     * endTurn 本体と、選択解決後の advanceTurnIfPending の両方から呼ばれる。
     */
    private void finishEndTurnCleanup(GameRoom room, GameState state) {
        for (PlayerState p : new PlayerState[] { state.getPlayer1(), state.getPlayer2() }) {
            // ターンの終わりに自壊するミニオン(特殊召喚された這い寄る生霊)。
            // 破壊トリガーを正しく発火させるため、リストから消すのではなく破壊処理を通す
            for (MinionInstance dying : List.copyOf(p.getMinionZone())) {
                if (dying.isDestroyAtEndOfTurn()) {
                    actions.destroyMinion(room, p, dying);
                }
            }
            p.getMinionZone().forEach(MinionInstance::expireThisTurnModifiers);
            p.getThisTurnAuras().clear();
            // 暴風の双剣がこのターン積み上げたウェポン攻撃力の加算も、ここで落とす(a1)
            p.setWeaponAttackBonusThisTurn(0);
            // 持続効果は「このターン中」とは寿命が違うため、期限を見て個別に落とす。
            // ターン番号指定のものだけがここで切れる(スペル使用が条件のものは残る)
            p.getPersistentAuras().removeIf(aura ->
                    aura.expiry() == PersistentAura.Expiry.AFTER_TURN_NUMBER
                            && state.getTurnNumber() >= aura.expiresAfterTurn());

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
    }

    /** 次のターンを開始する(相手の手番へ)。ターン受け渡しの保留を解除する */
    private void advanceTurn(GameRoom room, GameState state, String nextPlayerId) {
        state.setTurnHandoffPending(false);
        state.setPendingNextPlayerId(null);
        beginTurn(room, nextPlayerId);
    }

    /**
     * ターンエンド中の割り込みが解決した後、保留していたターンの受け渡しを行う(a9)。
     * まだ選択待ちが残っていれば何もしない(複数段の割り込みに耐えるため)。
     * メインフェイズ中の割り込みではフラグが立っていないため、この呼び出しは素通りする。
     */
    private void advanceTurnIfPending(GameRoom room, GameState state) {
        if (!state.isTurnHandoffPending()) {
            return;
        }
        if (state.getPlayer1().getPendingChoice() != null
                || state.getPlayer2().getPendingChoice() != null) {
            return; // まだ別の選択待ちがある
        }
        finishEndTurnCleanup(room, state);
        advanceTurn(room, state, state.getPendingNextPlayerId());
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
    public void playCard(GameRoom room, String playerId, int handIndex,
            List<TargetChoice> choices, boolean enhanced) {
        GameState state = requireState(room);
        requireTurnPlayer(state, playerId);
        requireStatus(state, GameStatus.PLAYING);
        PlayerState player = state.playerOf(playerId);
        if (player.isCannotUseCardsThisTurn()) {
            throw new IllegalStateException("このターンはカードを使用できません");
        }
        CardMaster master = cards.findById(peekHand(player, handIndex));
        // 「代償を払えなければ使用できない」カード(禁忌の代償・絶望の連鎖など)。
        // コストを支払う前に判定する必要があるためここで見る
        effects.requirePlayable(master.id(), state, player);
        // 光文明による使用の禁止(断罪の聖導者のスペル封じ)
        if (master.type() == CardType.SPELL) {
            String spellDenial = guards.spellDenial(state, player);
            if (spellDenial != null) {
                throw new IllegalStateException(spellDenial);
            }
        }

        switch (master.type()) {
            case MINION -> playMinion(room, state, player, handIndex, master, choices);
            case SPELL -> playSpell(room, state, player, handIndex, master, choices, enhanced);
            case WEAPON -> playWeapon(room, state, player, handIndex, master);
            default -> throw new IllegalStateException("このカードはプレイできません");
        }
        player.setPlayedCardThisTurn(true);
        // カード1枚の使用が「解決し終えた後」に数える(裁定1: 使用カウンタは自身を含まない)。
        // 効果解決の途中で問い合わせ(pendingChoice)が発生した場合でも、
        // 効果そのものは開始しているためここで数えてよい(2枚目以降の参照に間に合う)。
        afterCardUsed(room, state, player, master.type() == CardType.SPELL);
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
        // 【剛火の将】の割引は「次に使う火文明ミニオン」1体で消費される
        if (player.getPendingFireMinionDiscount() > 0
                && master.civilization() == com.example.qte.master.Civilization.FIRE) {
            player.setPendingFireMinionDiscount(player.getPendingFireMinionDiscount() - 1);
        }
        ResolvedTargets resolved = removePlayedAndTargets(player, handIndex, validated);

        summonToField(room, state, player, master, resolved, false);
    }

    private void playSpell(GameRoom room, GameState state, PlayerState player,
            int handIndex, CardMaster master, List<TargetChoice> choices, boolean enhanced) {
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
        // 追加コストによる強化使用(a5: 回帰の風穴・風弾の跳弾)。
        // モード選択は支払いより前に確定していなければならないため、a9(解決中の割り込み)ではなく
        // 使用宣言に付随する真偽値として受け取る。強化を持たないカードにtrueが来たら弾く
        EnhancedCostSpec enhancedSpec = effects.enhancedCostOf(master.id());
        if (enhanced && enhancedSpec == null) {
            throw new IllegalStateException("このカードは追加コストを支払えません");
        }
        int extraCost = enhanced ? enhancedSpec.extraCost() : 0;

        ValidatedTargets validated = validateTargets(state, player, handIndex,
                effects.targetSpecOf(master.id()), choices);
        // 【死者蘇生】は「生贄にした自分のミニオンの数だけコスト-1」であり、
        // 支払う額が選択結果に依存する。StatCalculatorが参照できる場所に数を置いてから支払う
        if (SACRIFICE_SPELL_ID.equals(master.id())) {
            player.setPendingSacrificeCount(validated.minions().get(0).size());
        }
        try {
            payCost(player, stats.effectiveCost(state, player, master) + extraCost);
        } finally {
            player.setPendingSacrificeCount(0); // MP不足で弾かれた場合も必ず戻す
        }
        ResolvedTargets resolved = removePlayedAndTargets(player, handIndex, validated);
        // 【詠唱の宝珠】のような「次に唱えるスペル」限定の効果は、ここで使い切る。
        // コストの計算(effectiveCost)が終わった後に消すこと。順序を逆にすると軽減が乗らない
        player.getPersistentAuras().removeIf(
                aura -> aura.expiry() == PersistentAura.Expiry.ON_NEXT_SPELL);
        room.addLog("%sが【%s】を唱えました".formatted(player.getDisplayName(), master.name()));

        player.setPendingSpellDisposition(null);
        effects.resolveSpell(master.id(),
                contextOf(room, state, player, null, resolved, enhanced));
        // 使用後の行き先。効果が pendingSpellDisposition を書いていればそれで置換する(a5)。
        // 禁忌由来ではないため、置換がなければ通常どおり墓地(【還元】ならマナ)へ
        SpellDisposition disposition = player.getPendingSpellDisposition();
        player.setPendingSpellDisposition(null);
        if (disposition == null) {
            actions.disposeUsedSpell(room, player, master, false);
        } else {
            switch (disposition) {
                case TO_HAND -> {
                    player.getHand().add(master.id());
                    room.addLog("【%s】は墓地に置かれる代わりに手札へ戻りました".formatted(master.name()));
                }
                case TO_DECK_BOTTOM -> {
                    player.getDeck().addLast(master.id());
                    room.addLog("【%s】は山札の一番下に置かれました".formatted(master.name()));
                }
            }
        }
    }

    /**
     * 中断していた効果を、プレイヤーの選択結果で再開する(a9)。
     *
     * 降臨の伝道師の専用処理(旧 resolveRevealChoice)を一般化したもの。
     * どの効果の続きなのかは pendingChoice.resumeAt が持つ。
     *
     * <b>ここが担う検証。</b> 誰が・いくつ・正しい候補から選んだか、までをここで確かめ、
     * 「その結果で何が起きるか」は CardEffectRegistry.resolveChoice に委ねる
     * (GameService=ルールの検証と進行、Registry=効果の中身、という役割分担どおり)。
     *
     * @param chosenIndexes pendingChoice.candidates() のうち選んだものの位置(0起点)
     */
    public void resolveChoice(GameRoom room, String playerId, List<Integer> chosenIndexes) {
        GameState state = requireState(room);
        // この操作自身が pendingChoice を解消するため、requireTurnPlayer は経由しない
        // (経由すると「選び終えるまで塞ぐ」判定に自分自身が引っかかってしまう)
        if (!playerId.equals(state.getTurnPlayerId())) {
            throw new IllegalStateException("相手のターンです");
        }
        requireStatus(state, GameStatus.PLAYING);
        PlayerState player = state.playerOf(playerId);
        PendingChoice choice = player.getPendingChoice();
        if (choice == null) {
            throw new IllegalStateException("選択待ちの効果がありません");
        }
        List<Integer> indexes = chosenIndexes == null ? List.of() : chosenIndexes;
        // 選択数が範囲内か
        if (indexes.size() < choice.min() || indexes.size() > choice.max()) {
            throw new IllegalArgumentException("選択の数が正しくありません");
        }
        // 各インデックスが候補の範囲内で、重複していないか。候補IDへ写す
        Set<Integer> seen = new HashSet<>();
        List<String> chosen = new ArrayList<>();
        for (int idx : indexes) {
            if (idx < 0 || idx >= choice.candidates().size() || !seen.add(idx)) {
                throw new IllegalArgumentException("不正な選択です");
            }
            chosen.add(choice.candidates().get(idx));
        }
        // 解決の前に pendingChoice を解消する(効果内で例外が起きても選択待ちのまま固まらないように)
        player.setPendingChoice(null);
        effects.resolveChoice(contextOf(room, state, player, null, null), choice, chosen);

        // ターンエンド中の割り込み(詠唱の疾風騎士)だった場合、選択の解決後に
        // 保留していたターンの受け渡しを行う。それ以外(メインフェイズ中の割り込み)では
        // まだ手番が続くため、advanceTurn は呼ばれない(内部で保留フラグを見て判断する)
        advanceTurnIfPending(room, state);
    }

    // ---------------------------------------------------------------
    // 禁忌システム(総合ルール第3章)
    // ---------------------------------------------------------------

    /**
     * 禁忌カードの使用(総合ルール3-3)。自分のメインフェイズ中、手札のカードと同様に使用できる。
     *
     * 通常のプレイとの違いは3点:
     *   1. コストはMP(タップ)ではなくマナカードの状態変化で支払う(3-4/3-5)
     *   2. 使用後・場を離れた後は墓地や手札ではなく消滅(Lost)ゾーンへ行く(3-6)
     *   3. サブフェイズでは使用できない(6章-6: サブフェイズはメインデッキ由来のスペルのみ)
     * 一方で【召喚時】などの登場時能力は通常通り発動する(3-7)。
     *
     * @param tabooIndex  禁忌デッキ内の位置
     * @param manaIndexes 禁忌コストの支払いに充てるマナゾーンの位置(コストと同数)
     */
    public void playTabooCard(GameRoom room, String playerId, int tabooIndex,
            List<Integer> manaIndexes, List<TargetChoice> choices) {
        GameState state = requireState(room);
        requireTurnPlayer(state, playerId);
        requireStatus(state, GameStatus.PLAYING);
        requirePhase(state, TurnPhase.MAIN); // 3-3: 禁忌はメインフェイズのみ
        PlayerState player = state.playerOf(playerId);
        if (player.isCannotUseCardsThisTurn()) {
            throw new IllegalStateException("このターンはカードを使用できません");
        }
        if (tabooIndex < 0 || tabooIndex >= player.getTabooDeck().size()) {
            throw new IllegalArgumentException("不正な禁忌カードの指定です");
        }
        CardMaster master = cards.findById(player.getTabooDeck().get(tabooIndex));

        if (master.type() == CardType.MINION && player.isMinionZoneFull()) {
            throw new IllegalStateException("ミニオンは6体までです");
        }
        if (master.type() == CardType.SPELL && !effects.isSpellImplemented(master.id())) {
            throw new IllegalStateException("このスペルの効果は未実装です");
        }
        // 光文明による使用の禁止(断罪の聖導者)。禁忌デッキ由来のスペルも封じられる(発注者確認済み)
        if (master.type() == CardType.SPELL) {
            String spellDenial = guards.spellDenial(state, player);
            if (spellDenial != null) {
                throw new IllegalStateException(spellDenial);
            }
        }

        // 検証(状態を変えない)→ 支払い → ゾーンからの除去 → 解決、の順序は通常プレイと同じ。
        // 禁忌カード自身は手札にないため、対象検証の自己除外インデックスは-1を渡す
        ValidatedTargets validated = validateTargets(state, player, -1,
                effects.targetSpecOf(master.id()), choices);
        validateTabooCost(player, master.cost(), manaIndexes);

        payTabooCost(room, player, manaIndexes);
        ResolvedTargets resolved = removePlayedAndTargets(player, -1, validated);
        player.getTabooDeck().remove(tabooIndex);
        player.setPlayedCardThisTurn(true);
        room.addLog("%sが禁忌カード【%s】を使用".formatted(player.getDisplayName(), master.name()));

        switch (master.type()) {
            case MINION -> summonToField(room, state, player, master, resolved, true);
            case SPELL -> {
                // 禁忌由来のスペルは pendingSpellDisposition による行き先置換(a5)を受けない。
                // 総合ルール3-6により、禁忌カードは使用され終わると消滅ゾーンへ行くことが
                // 確定しているためである(disposeUsedSpell が fromTaboo=true で消滅へ送る)。
                // 効果が誤って書き込んでも通常プレイに漏らさないよう、前後でクリアする
                player.setPendingSpellDisposition(null);
                effects.resolveSpell(master.id(), contextOf(room, state, player, null, resolved));
                player.setPendingSpellDisposition(null);
                // 使用され終わった禁忌カードは消滅ゾーンへ(3-6)。【還元】は機能しない
                actions.disposeUsedSpell(room, player, master, true);
            }
            case WEAPON -> equipWeapon(room, player, master, true);
            default -> throw new IllegalStateException("このカードはプレイできません");
        }
        // 禁忌カードの使用もカードの使用として数える(a1)
        afterCardUsed(room, state, player, master.type() == CardType.SPELL);
    }

    /**
     * 禁忌コストの支払い可否を検証する(状態は変更しない)。
     * 支払い方法は2通りで、1枚につき1コスト分として数える:
     *   - 表向きのマナを裏向きにする(3-4)。マナ自体はゾーンに残る
     *   - すでに裏向きのマナを墓地へ送る(3-5)。マナが1枚永久に減る
     * ピュア・エレメント由来の一時マナは禁忌コストに使用できない(カードテキスト)。
     */
    private void validateTabooCost(PlayerState player, int cost, List<Integer> manaIndexes) {
        List<Integer> indexes = manaIndexes == null ? List.of() : manaIndexes;
        if (indexes.size() != cost) {
            throw new IllegalArgumentException("禁忌コストの支払いにはマナ%d枚の指定が必要です".formatted(cost));
        }
        Set<Integer> seen = new HashSet<>();
        for (int idx : indexes) {
            if (idx < 0 || idx >= player.getManaZone().size() || !seen.add(idx)) {
                throw new IllegalArgumentException("不正なマナの指定です");
            }
            if (player.getManaZone().get(idx).isTemporary()) {
                throw new IllegalArgumentException("【ピュア・エレメント】は禁忌のコストにできません");
            }
        }
    }

    /** 検証済みの指定に従って禁忌コストを支払う */
    private void payTabooCost(GameRoom room, PlayerState player, List<Integer> manaIndexes) {
        // 墓地送りでマナゾーンから取り除くため、位置ずれを避けて降順に処理する
        List<ManaCard> targets = manaIndexes.stream()
                .map(i -> player.getManaZone().get(i))
                .toList();
        boolean anyLeft = false;
        for (ManaCard mana : targets) {
            if (mana.isFaceUp()) {
                mana.turnFaceDown(); // 3-4: 裏向きにする(マナは残る)
                room.addLog("禁忌コスト: マナ1枚を裏向きにしました");
            } else {
                player.getManaZone().remove(mana); // 3-5: 裏向きマナを墓地へ(マナが減る)
                player.getTrash().add(mana.getCardId());
                room.addLog("禁忌コスト: 裏向きのマナ1枚を墓地へ送りました");
                anyLeft = true;
            }
        }
        // マナがマナゾーンを離れたイベント(黄泉還る水龍などのゾーン横断トリガー)
        if (anyLeft) {
            actions.manaLeft(room, player);
        }
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
        // 【秩序の執行官】は相手の特殊召喚そのものを封じる
        String summonDenial = guards.specialSummonDenial(state, player);
        if (summonDenial != null) {
            throw new IllegalStateException(summonDenial);
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
        payCost(player, spec.mpCost()); // 多くは0だが、極炎竜ヴォルカニクスのようにMPを要するものもある
        ResolvedTargets resolved = removePlayedAndTargets(player, handIndex, validated);
        room.addLog("%sが【%s】を特殊召喚".formatted(player.getDisplayName(), master.name()));
        // 代替コストの支払い(手札を山札の下へ・ミニオンを手札に戻す等)
        spec.costEffect().accept(contextOf(room, state, player, null, resolved));

        MinionInstance summoned = summonToField(room, state, player, master, resolved, false);
        // 特殊召喚で出したときのみ発生する追加効果(背水の炎壁・這い寄る生霊の自壊予約)。
        // 通常の【召喚時】とは別枠であり、出したミニオン自身をsourceとして渡す
        spec.onSpecialSummon().accept(contextOf(room, state, player, summoned, resolved));
        player.setPlayedCardThisTurn(true);
        // 特殊召喚もカードの使用として数える(a1)。ミニオンのためスペルフラグはfalse
        afterCardUsed(room, state, player, false);
    }

    /**
     * 墓地からの召喚(リーダー【黄泉の召喚主】の常在能力)。
     *
     * 総合ルール6章-6では、サブフェイズに使用できるのはメインデッキ由来のスペルのみである。
     * この能力はそのルールをリーダー単位で上書きする、初の「ルール変更型」の能力である。
     * 起動能力ではないため1ターン1回の制限はなく、MPが続く限り何度でも行える(発注者確認済み)。
     *
     * 効果による「出す」ではなく「召喚」であるため、【召喚時】(ON_SUMMON)も発動する。
     *
     * @param trashIndex 墓地の何番目のカードか
     */
    public void summonFromGrave(GameRoom room, String playerId, int trashIndex) {
        GameState state = requireState(room);
        requireTurnPlayer(state, playerId);
        requireStatus(state, GameStatus.PLAYING);
        requirePhase(state, TurnPhase.SUB);
        PlayerState player = state.playerOf(playerId);
        if (!GRAVE_SUMMONER_LEADER_ID.equals(player.getLeader().id())) {
            throw new IllegalStateException("このリーダーは墓地から召喚できません");
        }
        if (player.isCannotUseCardsThisTurn()) {
            throw new IllegalStateException("このターンはカードを使用できません");
        }
        if (trashIndex < 0 || trashIndex >= player.getTrash().size()) {
            throw new IllegalArgumentException("不正な墓地の指定です");
        }
        CardMaster master = cards.findById(player.getTrash().get(trashIndex));
        if (master.type() != CardType.MINION) {
            throw new IllegalStateException("墓地から召喚できるのはミニオンのみです");
        }
        if (player.isMinionZoneFull()) {
            throw new IllegalStateException("ミニオンは6体までです");
        }
        payCost(player, stats.effectiveCost(state, player, master));
        player.getTrash().remove(trashIndex);
        room.addLog("%sが墓地から【%s】を召喚".formatted(player.getDisplayName(), master.name()));
        summonToField(room, state, player, master, null, false);
        player.setPlayedCardThisTurn(true);
        // 墓地からの召喚もカードの使用として数える(a1)
        afterCardUsed(room, state, player, false);
    }

    /** 召喚の共通着地処理。ON_SUMMONとON_ENTERの両方が発動する(発注者確認済み裁定)。
     *  効果による「出す」(黄泉還る水龍など)を実装するときはON_ENTERのみを発火する */
    private MinionInstance summonToField(GameRoom room, GameState state, PlayerState player,
            CardMaster master, ResolvedTargets resolved, boolean fromTaboo) {
        MinionInstance minion = new MinionInstance(master, state.getTurnNumber(), fromTaboo);
        player.getMinionZone().add(minion);
        room.addLog("%sが【%s】を召喚しました".formatted(player.getDisplayName(), master.name()));

        EffectContext ctx = contextOf(room, state, player, minion, resolved);
        effects.fire(TriggerType.ON_SUMMON, minion, ctx);
        effects.fire(TriggerType.ON_ENTER, minion, ctx);
        return minion;
    }

    /** ウェポンの装備。装備済みなら古いウェポンは即座に墓地へ(総合ルール2-5) */
    private void playWeapon(GameRoom room, GameState state, PlayerState player,
            int handIndex, CardMaster master) {
        requirePhase(state, TurnPhase.MAIN);
        payCost(player, stats.effectiveCost(state, player, master));
        takeFromHand(player, handIndex);
        equipWeapon(room, player, master, false);
    }

    /** ウェポンの装備。旧ウェポンの行き先は、それが禁忌由来なら消滅・そうでなければ墓地 */
    private void equipWeapon(GameRoom room, PlayerState player, CardMaster master, boolean fromTaboo) {
        CardMaster old = player.getEquippedWeapon();
        if (old != null) {
            // 詠唱の宝珠: 破壊(destroyOwnWeapon)だけでなく、付け替えで場を離れる場合も発動する
            actions.onWeaponLeftPlay(player, old);
            if (player.isEquippedWeaponFromTaboo()) {
                player.getLostZone().add(old.id());
                room.addLog("【%s】は禁忌カードのため消滅しました".formatted(old.name()));
            } else {
                player.getTrash().add(old.id());
                room.addLog("【%s】は墓地へ送られました".formatted(old.name()));
            }
        }
        player.setEquippedWeapon(master);
        player.setEquippedWeaponFromTaboo(fromTaboo);
        room.addLog("%sが【%s】を装備しました".formatted(player.getDisplayName(), master.name()));

        // ウェポンの【知識】は装備時に発動する(発注者確認済み)
        if (master.hasKeyword(Keyword.KNOWLEDGE)) {
            actions.drawCards(room, player, 1);
            room.addLog("【知識】%sが1枚ドロー".formatted(player.getDisplayName()));
        }
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
        // 攻撃できるかの判定(未装備・攻撃済み回数・凍結・カードによる禁止)は判定層に集約した(a2)。
        // ミニオン側が11aで判定層へ移されたとき取り残されていた判定をここで揃える(設計判断34)
        String leaderDenial = guards.leaderAttackDenial(state, player);
        if (leaderDenial != null) {
            throw new IllegalStateException(leaderDenial);
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

        player.setLeaderAttacksUsedThisTurn(player.getLeaderAttacksUsedThisTurn() + 1);
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
            actions.dealCombatDamage(room, opponent, target, damage);
            actions.checkDestruction(room, opponent, target, DestructionCause.COMBAT);
        }

        // ウェポンの攻撃時効果。現状2種のためswitchで直書きし、増えたらRegistryへ移す(TODO)
        switch (weapon.id()) {
            case "QTE-0030" -> { // 真珠の三叉槍: 自分のリーダーが攻撃した時、カードを1枚引く
                actions.drawCards(room, player, 1);
            }
            case "QTE-0061" -> { // 魔剣 レーヴァテイン: 自分のリーダーが攻撃した時、自分のリーダーに3ダメージ
                actions.damageLeader(room, player, 3);
            }
            case "QTE-0073" -> { // 禁忌の冥魔剣: 裏向きマナが1枚表に戻り、相手リーダーに1ダメージ
                if (actions.turnManaFaceUp(room, player, 1) > 0) {
                    actions.damageLeader(room, opponent, 1, "QTE-0073");
                }
            }
            case "QTE-0089" -> { // 死霊の収鎌: 自分の墓地からカードを1枚手札に戻す
                // どの1枚かは自動選択(AutoChoice: 最後に墓地へ置かれたカード)
                String recovered = com.example.qte.effect.AutoChoice.recoverFromTrash(player);
                if (recovered != null) {
                    actions.returnFromTrashToHand(room, player, recovered);
                }
            }
            case "QTE-0086" -> { // 死神の大鎌: 攻撃されたミニオンは戦闘ダメージに関わらず破壊される
                if (!targetIsLeader && opponent.getMinionZone().contains(target)) {
                    actions.destroyMinion(room, opponent, target, DestructionCause.COMBAT);
                }
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
            case "QTE-0018" -> { // 聖剣エクスカリバー: 自分の【守護】ミニオンすべての体力を2回復
                player.getMinionZone().stream()
                        .filter(m -> m.hasKeyword(Keyword.GUARD))
                        .forEach(m -> m.heal(2));
                room.addLog("【聖剣エクスカリバー】: 自分の【守護】ミニオンの体力が2回復しました");
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
        // 代償を払えない能力(冥府の禁皇: 裏向きマナが必要)は、状態を変える前に弾く
        if (!spec.condition().test(state, player)) {
            throw new IllegalStateException("この能力を使用する条件を満たしていません");
        }
        ValidatedTargets validated = validateTargets(state, player, -1, spec.targets(), choices);
        payCost(player, spec.mpCost());
        ResolvedTargets resolved = removePlayedAndTargets(player, -1, validated);
        player.setLeaderAbilityUsedThisTurn(true);
        room.addLog("%sがリーダー起動能力を使用".formatted(player.getDisplayName()));
        spec.effect().accept(contextOf(room, state, player, null, resolved));
        // 起動能力の発動もカードの使用として数える(発注者確認済みの横断ルール。a1)
        afterCardUsed(room, state, player, false);
    }

    /**
     * ミニオンの起動能力(a6。静空の風使い)。メインフェイズ中・自身をタップして発動する。
     *
     * リーダーの起動能力と構造は似ているが、コスト体系(MP vs 自身のタップ)・使用制限
     * (1ターン1回のフラグ vs タップ状態)・呼び出し経路(プレイヤー単位 vs インスタンス単位)が
     * すべて異なるため、別のメソッド・別の仕様型(MinionAbilitySpec)で扱う。
     *
     * 使用制限はタップ状態そのものが担うため、回数フラグは持たせない。
     */
    public void useMinionAbility(GameRoom room, String playerId, String instanceId,
            List<TargetChoice> choices) {
        GameState state = requireState(room);
        requireTurnPlayer(state, playerId);
        requireStatus(state, GameStatus.PLAYING);
        requirePhase(state, TurnPhase.MAIN);
        PlayerState player = state.playerOf(playerId);
        if (player.isCannotUseCardsThisTurn()) {
            throw new IllegalStateException("このターンはカードを使用できません");
        }
        MinionInstance minion = findMinion(player, instanceId);
        MinionAbilitySpec spec = effects.minionAbilityOf(minion.getMaster().id());
        if (spec == null) {
            throw new IllegalStateException("このミニオンは起動能力を持ちません");
        }
        if (minion.isTapped()) {
            throw new IllegalStateException("このミニオンはすでにタップしています");
        }
        if (!spec.condition().test(state, player)) {
            throw new IllegalStateException("この能力を使用する条件を満たしていません");
        }
        ValidatedTargets validated = validateTargets(state, player, -1, spec.targets(), choices);
        payCost(player, spec.mpCost());
        ResolvedTargets resolved = removePlayedAndTargets(player, -1, validated);
        // コストとしてのタップは効果の実行より前に行う(能力自身が「アンタップ状態のマナ」を
        // 参照する場合に、自分のタップが先に反映されているべきという理由はないが、
        // 「使ったら即タップ」という直感に合わせる)
        minion.tap();
        room.addLog("%sが【%s】の能力を使用".formatted(player.getDisplayName(), minion.getMaster().name()));
        spec.effect().accept(contextOf(room, state, player, minion, resolved));
        // 起動能力の発動もカードの使用として数える(a1)
        afterCardUsed(room, state, player, false);
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

        validateAttack(state, player, attacker, target, targetIsLeader, opponent);

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
            room.addLog("【%s】⇔【%s】(%d ⇔ %d)"
                    .formatted(attacker.getMaster().name(), target.getMaster().name(), toTarget, toAttacker));
            // ダメージは同時に与え合う(4-2)。被ダメージトリガー(獄門の裁定者)は
            // 両者への適用が終わった時点で発火し、その後にまとめて破壊判定を行う
            actions.dealCombatDamage(room, opponent, target, toTarget);
            actions.dealCombatDamage(room, player, attacker, toAttacker);
            actions.checkDestruction(room, opponent, target, DestructionCause.COMBAT);
            actions.checkDestruction(room, player, attacker, DestructionCause.COMBAT);
        }
    }

    /**
     * 攻撃宣言の検証。
     *
     * 「攻撃できるか」の判定(攻撃回数・凍結・召喚酔い・カードによる禁止)は
     * RuleGuardsへ移した。ここに残っているのは「その対象を選べるか」の判定
     * (威圧・守護)であり、攻撃者側の事情と対象側の事情を分けている。
     */
    private void validateAttack(GameState state, PlayerState player, MinionInstance attacker,
            MinionInstance target, boolean targetIsLeader, PlayerState opponent) {
        String denial = guards.minionAttackDenial(state, player, attacker, targetIsLeader);
        if (denial != null) {
            throw new IllegalStateException(denial);
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

    /** 強化使用(a5)の区別を持たせる文脈。回帰の風穴・風弾の跳弾のみ enhanced=true になりうる */
    private EffectContext contextOf(GameRoom room, GameState state, PlayerState owner,
            MinionInstance source, ResolvedTargets targets, boolean enhanced) {
        return new EffectContext(room, state, owner,
                state.opponentOf(owner.getPlayerId()), source, targets, actions, enhanced);
    }

    /**
     * カード1枚(または起動能力1回)を使い終えた後の共通処理(a1)。
     * ターン内の使用カウンタを進め、ON_CARD_USED を発火する。
     *
     * <b>加算を効果解決の後に置く理由(裁定1)。</b> この位置により、使用カウンタを参照する
     * すべての効果が「自分より前に使ったカードの枚数」を見る。詠唱の風詠士の「3枚目」も、
     * 神風の大号令の全体バフも、参照時点で自身を含まない値になる。
     *
     * <b>起動能力も数える。</b> リーダー・ミニオンの起動能力の発動もカードの使用として
     * インクリメントする(発注者確認済みの横断ルール。qte-project-reference 2-9)。
     * スペルを唱えた場合のみ spellsCastThisTurn も進める(詠唱の疾風騎士が参照)。
     */
    private void afterCardUsed(GameRoom room, GameState state, PlayerState player, boolean isSpell) {
        player.setCardsUsedThisTurn(player.getCardsUsedThisTurn() + 1);
        if (isSpell) {
            player.setSpellsCastThisTurn(player.getSpellsCastThisTurn() + 1);
        }
        effects.fireCardUsed(contextOf(room, state, player, null, null));
    }

    // ---------------------------------------------------------------
    // 対象指定の検証と解決
    // ---------------------------------------------------------------

    /** 検証済みだがまだ手札から除去していない中間状態 */
    private record ValidatedTargets(
            List<TargetSpec.Requirement> requirements,
            List<List<Integer>> handIndexes,
            List<List<ResolvedTargets.TargetedMinion>> minions,
            List<List<ManaCard>> mana,
            List<List<String>> trashCardIds,
            List<List<PlayerState>> weapons) {
    }

    /**
     * クライアントの選択(choices)を仕様(spec)に照らして検証する。状態は一切変更しない。
     * ここが対象指定の「正当性の最終判定」であり、改造クライアントの不正な選択は全てここで弾く。
     */
    private ValidatedTargets validateTargets(GameState state, PlayerState player,
            int playedHandIndex, TargetSpec spec, List<TargetChoice> choices) {
        List<TargetSpec.Requirement> reqs = spec.requirements();
        if (reqs.isEmpty()) {
            return new ValidatedTargets(reqs, List.of(), List.of(), List.of(), List.of(), List.of());
        }
        if (choices == null || choices.size() != reqs.size()) {
            throw new IllegalArgumentException("対象の指定が不足しています");
        }
        List<List<Integer>> handPerReq = new ArrayList<>();
        List<List<ResolvedTargets.TargetedMinion>> minionsPerReq = new ArrayList<>();
        List<List<ManaCard>> manaPerReq = new ArrayList<>();
        List<List<String>> trashPerReq = new ArrayList<>();
        List<List<PlayerState>> weaponsPerReq = new ArrayList<>();
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
                        checkFilter(state, player, req, cards.findById(player.getHand().get(idx)));
                    }
                    handPerReq.add(List.copyOf(indexes));
                    minionsPerReq.add(List.of());
                    manaPerReq.add(List.of());
                    trashPerReq.add(List.of());
                    weaponsPerReq.add(List.of());
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
                    trashPerReq.add(List.of());
                    weaponsPerReq.add(List.of());
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
                        // 【潜伏】: 相手のカードや能力の対象にならない(自分は対象にできる)。
                        // ホーリー・シグナルはテキストでこれを上書きするため、IGNORES_STEALTHがあれば通す
                        boolean stealthBlocked = tm.owner() != player && tm.minion().hasKeyword(Keyword.STEALTH)
                                && !req.filters().contains(TargetSpec.Filter.IGNORES_STEALTH);
                        if (stealthBlocked) {
                            throw new IllegalArgumentException("【潜伏】持ちは相手の効果の対象になりません");
                        }
                        checkFilter(state, player, req, tm.minion().getMaster(), tm.minion());
                        resolved.add(tm);
                    }
                    handPerReq.add(List.of());
                    minionsPerReq.add(resolved);
                    manaPerReq.add(List.of());
                    trashPerReq.add(List.of());
                    weaponsPerReq.add(List.of());
                }
                case TRASH -> {
                    // 墓地は自分のものだけを対象にできる。選んだカードは墓地に残したまま渡し、
                    // 移動(蘇生・手札回収・マナ送り)は効果自身が行う
                    List<Integer> indexes = choice.trashIndexes();
                    requireCount(req, indexes.size());
                    List<String> trashIds = new ArrayList<>();
                    Set<Integer> seen = new HashSet<>();
                    for (int idx : indexes) {
                        if (idx < 0 || idx >= player.getTrash().size() || !seen.add(idx)) {
                            throw new IllegalArgumentException("不正な墓地の指定です");
                        }
                        String cardId = player.getTrash().get(idx);
                        checkFilter(state, player, req, cards.findById(cardId));
                        trashIds.add(cardId);
                    }
                    handPerReq.add(List.of());
                    minionsPerReq.add(List.of());
                    manaPerReq.add(List.of());
                    trashPerReq.add(trashIds);
                    weaponsPerReq.add(List.of());
                }
                case WEAPON -> {
                    // ウェポンは1人1枚のため、選ぶのは「どちら側の装備ウェポンか」だけでよい
                    // (聖光の武装解除。インスタンスIDを持たないKind.MINIONとの違い)
                    List<String> sides = choice.weaponSides();
                    requireCount(req, sides.size());
                    List<PlayerState> resolved = new ArrayList<>();
                    Set<String> seenSides = new HashSet<>();
                    for (String side : sides) {
                        if (!seenSides.add(side)) {
                            throw new IllegalArgumentException("同じウェポンを重複して選べません");
                        }
                        PlayerState target = switch (side) {
                            case "SELF" -> player;
                            case "OPPONENT" -> state.opponentOf(player.getPlayerId());
                            default -> throw new IllegalArgumentException("不正なウェポンの指定です");
                        };
                        if (target.getEquippedWeapon() == null) {
                            throw new IllegalArgumentException("装備中のウェポンがありません");
                        }
                        resolved.add(target);
                    }
                    handPerReq.add(List.of());
                    minionsPerReq.add(List.of());
                    manaPerReq.add(List.of());
                    trashPerReq.add(List.of());
                    weaponsPerReq.add(resolved);
                }
            }
        }
        // 合計指定(a7: サイクロン・リフレッシュ)。各要求は upTo で 0〜N 枚を受けており、
        // 「複数の要求を合わせてちょうど total 枚」という制約はここで最後にまとめて見る。
        // Kind ごとの検証はすでに各分岐が済ませているため、ここでは枚数の合計だけを確かめる
        if (spec.combinedTotal() > 0) {
            int total = 0;
            for (int i = 0; i < reqs.size(); i++) {
                total += handPerReq.get(i).size() + minionsPerReq.get(i).size()
                        + manaPerReq.get(i).size() + trashPerReq.get(i).size()
                        + weaponsPerReq.get(i).size();
            }
            if (total != spec.combinedTotal()) {
                throw new IllegalArgumentException(
                        "対象は合計%d枚選ぶ必要があります".formatted(spec.combinedTotal()));
            }
        }
        return new ValidatedTargets(reqs, handPerReq, minionsPerReq, manaPerReq, trashPerReq, weaponsPerReq);
        // upTo(「好きな数」「あるだけ」)は0からcountまでのどれでもよい
        boolean ok = req.upTo() ? (actual >= 0 && actual <= req.count())
                : actual == req.count() || (req.optional() && actual == 0);
        if (!ok) {
            throw new IllegalArgumentException("対象は%d体(枚)選ぶ必要があります".formatted(req.count()));
        }
    }

    /** 手札・禁忌など「カードそのもの」に対する絞り込み判定 */
    private void checkFilter(GameState state, PlayerState player, TargetSpec.Requirement req, CardMaster master) {
        checkFilter(state, player, req, master, null);
    }

    /**
     * 絞り込み判定。複数条件はAND。
     * minionがnullでない場合(場のミニオン)は、印刷値ではなく現在の状態を見る条件も評価する。
     * state/playerはHIGHEST_ATTACK_OPPONENTのように盤面全体を参照する条件のために必要。
     */
    private void checkFilter(GameState state, PlayerState player, TargetSpec.Requirement req,
            CardMaster master, MinionInstance minion) {
        for (TargetSpec.Filter filter : req.filters()) {
            switch (filter) {
                case KNOWLEDGE -> requireKeyword(master, minion, Keyword.KNOWLEDGE, "【知識】");
                case GUARD -> requireKeyword(master, minion, Keyword.GUARD, "【守護】");
                case MINION_CARD -> {
                    if (master.type() != CardType.MINION) {
                        throw new IllegalArgumentException("ミニオンカードを選んでください");
                    }
                }
                case HP_5_OR_LESS -> {
                    // 現在HPで判定する(ダメージを受けた大型ミニオンも対象になる)
                    int hp = minion != null ? minion.getCurrentHp()
                            : (master.hp() == null ? Integer.MAX_VALUE : master.hp());
                    if (hp > 5) {
                        throw new IllegalArgumentException("HP5以下のミニオンを選んでください");
                    }
                }
                case COST_4_OR_LESS -> {
                    if (master.cost() == null || master.cost() > 4) {
                        throw new IllegalArgumentException("コスト4以下のカードを選んでください");
                    }
                }
                case COST_3_OR_LESS -> {
                    if (master.cost() == null || master.cost() > 3) {
                        throw new IllegalArgumentException("コスト3以下のカードを選んでください");
                    }
                }
                case SPELL_CARD -> {
                    if (master.type() != CardType.SPELL) {
                        throw new IllegalArgumentException("スペルカードを選んでください");
                    }
                }
                case LIGHT_CIVILIZATION -> {
                    if (master.civilization() != com.example.qte.master.Civilization.LIGHT) {
                        throw new IllegalArgumentException("光文明のカードを選んでください");
                    }
                }
                case COST_7_OR_LESS -> {
                    if (master.cost() == null || master.cost() > 7) {
                        throw new IllegalArgumentException("コスト7以下のカードを選んでください");
                    }
                }
                case HIGHEST_ATTACK_OPPONENT -> {
                    // ホーリー・シグナル: 相手の場で現在攻撃力が最も高いミニオンだけを選べる
                    PlayerState targetSide = state.opponentOf(player.getPlayerId());
                    int max = targetSide.getMinionZone().stream()
                            .mapToInt(m -> stats.effectiveAttack(state, targetSide, m))
                            .max().orElse(Integer.MIN_VALUE);
                    int thisAttack = minion != null ? stats.effectiveAttack(state, targetSide, minion)
                            : Integer.MIN_VALUE;
                    if (thisAttack < max) {
                        throw new IllegalArgumentException("相手の場で最も攻撃力の高いミニオンを選んでください");
                    }
                }
                case IGNORES_STEALTH -> {
                    // 判定そのものはvalidateTargetsのMINION分岐で行う。ここではフィルタとして
                    // 素通りさせるだけ(絞り込み条件ではなく、潜伏チェックの上書き指示のため)
                }
            }
        }
    }

    private void requireKeyword(CardMaster master, MinionInstance minion, Keyword keyword, String label) {
        // 場のミニオンは付与されたキーワードも含めて判定する
        boolean has = minion != null ? minion.hasKeyword(keyword) : master.hasKeyword(keyword);
        if (!has) {
            throw new IllegalArgumentException(label + "を持つカードを選んでください");
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
                    validated.mana().get(i), validated.trashCardIds().get(i), validated.weapons().get(i)));
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
        // 割り込み選択(a9)の解決待ちの間は、他の操作を受け付けない。
        // ターンプレイヤー判定の直後に置くことで、多数ある呼び出し元を個別に触らずに済む
        // (resolveChoice 自身はこのメソッドを経由しないため、選択操作そのものは塞がない)。
        if (state.playerOf(playerId).getPendingChoice() != null) {
            throw new IllegalStateException("先に選択を解決してください");
        }
    }

    private void requirePhase(GameState state, TurnPhase expected) {
        if (state.getPhase() != expected) {
            throw new IllegalStateException("この操作は%sフェイズでのみ行えます(現在: %sフェイズ)"
                    .formatted(expected.getDisplayName(), state.getPhase().getDisplayName()));
        }
    }
}
