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
import com.example.qte.effect.EvolutionSpec;
import com.example.qte.effect.LeaderAbilitySpec;
import com.example.qte.effect.MinionAbilitySpec;
import com.example.qte.effect.PendingChoice;
import com.example.qte.effect.PersistentAura;
import com.example.qte.effect.ResumePoint;
import com.example.qte.effect.RuleGuards;
import com.example.qte.effect.ResolvedTargets;
import com.example.qte.effect.SoulSpellSpec;
import com.example.qte.effect.SpecialSummonSpec;
import com.example.qte.effect.StatCalculator;
import com.example.qte.effect.TargetCandidates;
import com.example.qte.effect.TargetChoice;
import com.example.qte.effect.TargetSpec;
import com.example.qte.effect.TriggerType;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardTextKeywords;
import com.example.qte.master.CardType;
import com.example.qte.master.Keyword;
import com.example.qte.room.GameRoom;
import com.example.qte.room.PlayerSlot;
import com.example.qte.room.SeatId;
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

    private static final String PURE_ELEMENT_ID = "QTE-M-NONE-01";

    /** リーダー【黄泉の召喚主】。サブフェイズ中に墓地からミニオンを召喚できる */
    private static final String GRAVE_SUMMONER_LEADER_ID = "QTE-M-DARK-15";

    /** 【死者蘇生】。生贄の数だけ自身のコストが下がるため、支払い前に選択結果を渡す */
    private static final String SACRIFICE_SPELL_ID = "QTE-M-DARK-12";

    // ---------------------------------------------------------------
    // ★Batch 47: リーダーの攻撃時に発動するウェポン。
    // 下の attackWithLeader の switch にリテラルで直接書かれていたものを定数にした。
    // IMPLEMENTED_CARDS が同じIDを書き写す形になるのを避けるためである(裁定130)。
    // ---------------------------------------------------------------

    private static final String PEARL_TRIDENT = "QTE-M-WATER-13";  // 真珠の三叉槍
    private static final String WRAITH_SCYTHE = "QTE-M-DARK-28";   // 死霊の収鎌
    private static final String REAPER_SCYTHE = "QTE-M-DARK-13";   // 死神の大鎌
    private static final String FREEZE_ROD = "QTE-M-WATER-14";     // 氷結の杖
    private static final String EXCALIBUR = "QTE-M-LIGHT-14";      // 聖剣 エクスカリバー
    private static final String QUAKE_HAMMER = "QTE-M-EARTH-28";   // 地響きの槌
    private static final String GUARD_STAFF = "QTE-M-WIND-28";     // 風護の杖

    /**
     * このクラスが挙動を実装しているカード(★Batch 47)。
     * 趣旨と番人は {@link com.example.qte.effect.RuleGuards#IMPLEMENTED_CARDS} の説明を参照。
     *
     * ★{@link #SACRIFICE_SPELL_ID}(死者蘇生)を入れていないのは、ここにあるのが
     * 「使用宣言のときに生贄の選択を先に受け取る」という<b>手続きの都合</b>であって、
     * 効果そのものは {@code CardEffectRegistry} に登録されているためである。
     */
    public static final java.util.Set<String> IMPLEMENTED_CARDS = java.util.Set.of(
            PURE_ELEMENT_ID, GRAVE_SUMMONER_LEADER_ID,
            PEARL_TRIDENT, WRAITH_SCYTHE, REAPER_SCYTHE, FREEZE_ROD,
            EXCALIBUR, QUAKE_HAMMER, GUARD_STAFF);

    private final CardMasterRepository cards;
    private final DeckFactory deckFactory;
    private final GameActions actions;
    private final CardEffectRegistry effects;
    private final StatCalculator stats;

    /** 攻撃・破壊・ドロー・使用の可否を盤面から判定する層(光文明の置換・禁止効果) */
    private final RuleGuards guards;

    /**
     * 対象の絞り込みと候補の列挙(★Batch 68。裁定282)。
     *
     * <p>★<b>宣言時の検証(このクラス)と、割り込みの候補列挙({@code CardEffectRegistry})が
     * 同じものを見る。</b>規則が2箇所に分かれないための1本である(裁定130)。
     */
    private final TargetCandidates candidates;
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

    // ---------------------------------------------------------------
    // ★★★試合の出入り(Batch 72): 席・退室・投了・再戦
    // ---------------------------------------------------------------
    //
    // ★<b>この4つは同じ1つの話題である。</b>「この試合に居るかどうか」を
    //   人が自分で決められるようにする、という話である ——
    //   66 は入るところだけを作り、<b>出るところを1つも作らなかった</b>。
    //
    // ★★<b>どの操作がどの時間帯に通るかは、GameState の有無で決まる。</b>
    //
    //   | 状態                        | 席を立つ | 席に着く | 退室 | 投了 | 再戦 |
    //   |-----------------------------|---------|---------|------|------|------|
    //   | WAITING (GameState == null) | ○(観戦可)| ○      | ○   | ×   | ×   |
    //   | SETUP / PLAYING             | ×      | ×      | 観戦者のみ | ○ | × |
    //   | FINISHED                    | ×      | ×      | ○   | ×   | ○   |
    //
    //   ★<b>席を動かせるのは GameState が無い間だけである。</b>
    //     これは 66 が「席を立てない」と書いた理由そのものであり、72 でも変えていない ——
    //     通常モードの席は {@code GameState} の2人と1対1であり、
    //     試合の途中で動かすと盤面の持ち主が消える。
    //   ★<b>決着後に抜けたい人は「退室」する。</b>席は空くが盤面は残るので、
    //     残ったほうは決着した盤面を読み続けられる(手動モードの裁定44 と同じ性質)。

    /**
     * 投了する(★Batch 72)。決着の5つ目の入口である。
     *
     * <h2>★★★いつでも押せる(マスター確認)</h2>
     * 相手のターン中でも、割り込み待ちでも、マリガン中でも通る ——
     * <b>{@code requireTurnPlayer} を通さない</b>。
     * これは手動モードの「リセットは絶対に止めない」と同じ筋である:
     * <b>詰まったときの逃げ道は、詰まりの原因になっている規則に左右されてはいけない。</b>
     * ★もし手番とフェイズを条件にすると、
     * 「割り込みが解決できなくなって盤面が固まった」ときに<b>投了もできない</b>。
     *
     * <h2>★決着後の待ちは finish がたたむ</h2>
     * 割り込み待ちのまま決着できるようになったのは 72 が初めてである。
     * 掃除は {@code GameActions#finish} に置いた(決着の口は1本・裁定130)。
     */
    public void concede(GameRoom room, String playerId) {
        GameState state = requireState(room);
        if (state.getStatus() == GameStatus.FINISHED) {
            throw new IllegalStateException("この対戦は既に決着しています");
        }
        if (!state.hasPlayer(playerId)) {
            throw new IllegalArgumentException("この対戦のプレイヤーではありません");
        }
        PlayerState me = state.playerOf(playerId);
        room.addLog("%s が投了しました".formatted(me.getDisplayName()));
        actions.finish(room, state.opponentOf(playerId));
    }

    /**
     * 席を立って観戦に降りる(★Batch 72)。
     * ★判定そのものは {@code GameRoom.standUp} が持つ —— 受付の帳簿の話であり、
     * ルールの執行ではない(このメソッドはログを添えるだけである)。
     */
    public void standUp(GameRoom room, String playerId) {
        PlayerSlot slot = room.findSlot(playerId).orElseThrow(
                () -> new IllegalArgumentException("この部屋の席に着いていません"));
        SeatId before = slot.getSeat();
        String name = slot.getDisplayName();
        // ★ログは standUp が通ってから書く(断られたのに行が残るのを避ける)
        room.standUp(playerId);
        room.addLog("%s が席%s を離れて観戦に移りました".formatted(name, before));
    }

    /** 観戦者が空席に着く(★Batch 72)。判定は {@code GameRoom.takeSeat} が持つ */
    public void takeSeat(GameRoom room, String spectatorId, SeatId seat) {
        PlayerSlot slot = room.takeSeat(spectatorId, seat);
        room.addLog("%s が席%s に着きました".formatted(slot.getDisplayName(), seat));
    }

    /**
     * 退室する(★Batch 72)。★席に着いていた人も観戦者も同じ口を通る。
     *
     * <p>★<b>ログを先に書く。</b>{@code room.leave} を先に呼ぶと、
     * 名前を引く相手が居なくなる。
     */
    public void leave(GameRoom room, String occupantId) {
        String name = room.findSlot(occupantId).map(PlayerSlot::getDisplayName)
                .orElseGet(() -> room.findSpectator(occupantId)
                        .map(com.example.qte.room.Spectator::displayName)
                        .orElseThrow(() -> new IllegalArgumentException("この部屋に入室していません")));
        // ★★退室できるかの判定は room.leave が持つ(対戦中の着席者は断られる)。
        //   ここで先にログを書いてしまうと、断られたのに行が残る —— だから
        //   <b>名前だけを先に引き、ログは leave が通ってから書く</b>
        room.leave(occupantId);
        room.addLog("%s が退室しました".formatted(name));
    }

    /**
     * 再戦の申し込み・承諾・辞退(★Batch 72)。
     *
     * <h2>★★なぜ2段なのか(マスター確認)</h2>
     * 片方が押しただけで盤面を捨てる形も採れた。捨てたところで
     * 両者がデッキを読み直すまで試合は始まらないので、
     * 「デッキを読むこと」が同意を兼ねる —— という筋も通る。
     * <b>採らなかったのは、それが「相手の見ている画面を相手の同意なしに消す」からである。</b>
     * 決着した盤面はまだ読まれている最中かもしれない。
     *
     * <h2>★申し込みの旗は1本である</h2>
     * {@code GameRoom.rematchOfferedBy}。倒すのは承諾・辞退・退室の3つであり、
     * <b>立てる人も倒す人も居る</b> —— 71 が {@code connectionFatal} を作らなかった
     * 判断(裁定178)の裏返しである。
     */
    public void rematch(GameRoom room, String playerId, RematchAction action) {
        GameState state = requireState(room);
        if (state.getStatus() != GameStatus.FINISHED) {
            throw new IllegalStateException("対戦が決着してから申し込んでください");
        }
        PlayerSlot me = room.findSlot(playerId).orElseThrow(
                () -> new IllegalArgumentException("この部屋の席に着いていません"));
        switch (action) {
            case OFFER -> {
                if (!room.isFull()) {
                    throw new IllegalStateException("相手が席に居ません");
                }
                if (room.getRematchOfferedBy() != null) {
                    throw new IllegalStateException("すでに再戦の申し込みがあります");
                }
                room.setRematchOfferedBy(playerId);
                room.addLog("%s が再戦を申し込みました".formatted(me.getDisplayName()));
            }
            case ACCEPT -> {
                requireOfferFromOpponent(room, playerId);
                // ★★resetForRematch がログを消す。だから行を足すのは<b>そのあと</b>である
                room.resetForRematch();
                room.addLog("再戦: 両者がデッキを読み込むと始まります");
            }
            case DECLINE -> {
                requireOfferFromOpponent(room, playerId);
                room.setRematchOfferedBy(null);
                room.addLog("%s が再戦を断りました".formatted(me.getDisplayName()));
            }
        }
    }

    /**
     * 返事ができる立場かを確かめる(★Batch 72)。
     * ★<b>自分の申し込みには自分で答えられない。</b>答えられると、
     * 2段にした意味(相手の同意)が消える。
     */
    private void requireOfferFromOpponent(GameRoom room, String playerId) {
        String offeredBy = room.getRematchOfferedBy();
        if (offeredBy == null) {
            throw new IllegalStateException("再戦の申し込みがありません");
        }
        if (offeredBy.equals(playerId)) {
            throw new IllegalStateException("自分の申し込みには答えられません");
        }
    }

    /** 再戦の3手(★Batch 72)。宛先を3つに割らず、1つの話題として1本で受ける */
    public enum RematchAction {
        /** 申し込む */
        OFFER,
        /** 応じる(盤面を捨てて受付へ戻る) */
        ACCEPT,
        /** 断る(旗だけを倒す) */
        DECLINE
    }

    private PlayerState createPlayer(PlayerSlot slot) {
        CardMaster leader = cards.findById(slot.getLeaderCardId());
        if (leader.type() != CardType.LEADER) {
            throw new IllegalArgumentException("リーダーカードではありません: " + leader.name());
        }
        PlayerState player = new PlayerState(slot.getPlayerId(), slot.getDisplayName(), leader);
        player.setDeckName(slot.getDeckName());
        // ★★Batch 66: デッキファイル(検証済み)しか無い。
        //   プリセット(おまかせ)は退役したので、分岐そのものが消えている。
        //   ここへ来る時点でデッキが載っていることは GameRoom.bothReady() が保証する
        //   (載っていなければ試合が生成されない)。
        player.getDeck().addAll(deckFactory.createMainDeckFrom(slot.getDeck()));
        player.getTabooDeck().addAll(deckFactory.createTabooDeckFrom(slot.getDeck()));
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
        // 試合単位のターン内カウンタ(★Batch 48)。プレイヤー単位のものは startTurnReset が戻す
        state.setMinionsDestroyedThisTurn(0);

        // ターン開始時トリガー(★Batch 48。ハク霊・コク霊の自壊)。
        // 発火するのはターンプレイヤーの場だけである(TriggerType.ON_TURN_START の説明を参照)。
        // 反復は場のコピーに対して行う: 自壊した【破壊時】が相方を場に出すため、
        // 反復中に場が増える。この開始時に出てきたミニオンは、この開始時には処理されない
        for (MinionInstance minion : List.copyOf(player.getMinionZone())) {
            effects.fire(TriggerType.ON_TURN_START, minion,
                    contextOf(room, state, player, minion, null));
            if (state.getStatus() == GameStatus.FINISHED) {
                return;
            }
        }

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
            // ウェポンの寿命(Ver.0.4の総則変更): このターン攻撃したウェポンはここで破壊される。
            // 禁忌由来なら墓地ではなく消滅ゾーンへ行くが、その判断は destroyOwnWeapon が呼ぶ
            // sendToTrashOrRestore が既に持っているため、ここでは区別せず破壊するだけでよい。
            // フラグは destroyOwnWeapon → onWeaponLeftPlay の中で落ちる。
            // 相手プレイヤー側のフラグは立ちようがない(リーダーの攻撃は自ターンのみ)が、
            // 掃除は両者に対して行うというこのメソッドの形はそのまま守っている
            if (p.isWeaponAttackedThisTurn() && p.getEquippedWeapon() != null) {
                room.addLog("【%s】は攻撃したためターンの終わりに破壊されます"
                        .formatted(p.getEquippedWeapon().name()));
                actions.destroyOwnWeapon(room, p);
            }
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
        // 配置経路を GameActions に集約する(土文明: 配置回数の計数と豊穣の地霊主の発火のため)。
        // マナチャージも「マナに置かれた回数」に含む(発注者確認済み)
        actions.placeCardInManaFaceUp(room, player, cardId);
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
    /** 進化以外のカードの使用(素材の指定を伴わない従来の入口) */
    public void playCard(GameRoom room, String playerId, int handIndex,
            List<TargetChoice> choices, boolean enhanced) {
        playCard(room, playerId, handIndex, choices, enhanced, List.of());
    }

    public void playCard(GameRoom room, String playerId, int handIndex,
            List<TargetChoice> choices, boolean enhanced, List<String> materialIds) {
        playCard(room, playerId, handIndex, choices, enhanced, materialIds, List.of());
    }

    /**
     * 手札のカードをプレイする。
     *
     * @param manaIndexes ★Batch 70(裁定319): 払うマナの位置。
     *                    <b>空なら自動</b>({@link ManaPayment#normalOrder} の順)であり、
     *                    69 までの呼び出しはすべてこちらである。
     *                    クリックからのプレイだけが位置を指定して送ってくる。
     */
    public void playCard(GameRoom room, String playerId, int handIndex,
            List<TargetChoice> choices, boolean enhanced, List<String> materialIds,
            List<Integer> manaIndexes) {
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
            case MINION -> playMinion(room, state, player, handIndex, master, choices, manaIndexes);
            case EVOLUTION -> playEvolution(room, state, player, handIndex, master, choices,
                    materialIds, manaIndexes);
            case SPELL -> playSpell(room, state, player, handIndex, master, choices, enhanced, manaIndexes);
            case WEAPON -> playWeapon(room, state, player, handIndex, master, manaIndexes);
            default -> throw new IllegalStateException("このカードはプレイできません");
        }
        player.setPlayedCardThisTurn(true);
        // カード1枚の使用が「解決し終えた後」に数える(裁定1: 使用カウンタは自身を含まない)。
        // 効果解決の途中で問い合わせ(pendingChoice)が発生した場合でも、
        // 効果そのものは開始しているためここで数えてよい(2枚目以降の参照に間に合う)。
        afterCardUsed(room, state, player, master.type() == CardType.SPELL);
    }

    /**
     * 手札のミニオンを【賢魂：n】として使う(★Batch 54。裁定152)。
     *
     * <blockquote>賢魂：n を持つミニオンは、スペルとしても使うことができる。</blockquote>
     *
     * <h2>なぜ {@code playCard} と別の入口なのか</h2>
     *
     * {@code playCard} は<b>カードの種別</b>で分岐する。賢魂は同じ種別のカードに
     * 2つの使い方があるという話であり、種別からは決まらない ——
     * <b>どちらの姿で使うかはプレイヤーの宣言</b>である。
     * 宣言を引数の真偽値で運ぶこともできるが、そうすると {@code playCard} の
     * 冒頭の検証(ミニオンとして出せるか)を賢魂のときだけ飛ばす分岐が中に増える。
     * 入口を分ければ、どちらの規則を通るかが<b>呼び出し位置そのもの</b>で決まる(裁定207)。
     *
     * <h2>ここから先はスペルの使用である</h2>
     *
     * 使用回数の計上・{@code ON_CARD_USED}・コスト軽減・スペル封じ・使用後の行き先は、
     * すべて通常のスペルと同じ道具を通る(マスター裁定 A2)。
     */
    public void playSoulCard(GameRoom room, String playerId, int handIndex, List<TargetChoice> choices) {
        playSoulCard(room, playerId, handIndex, choices, List.of());
    }

    /** ★Batch 70(裁定319): 払うマナを指定できる入口。空なら自動({@link ManaPayment#normalOrder}) */
    public void playSoulCard(GameRoom room, String playerId, int handIndex,
            List<TargetChoice> choices, List<Integer> manaIndexes) {
        GameState state = requireState(room);
        requireTurnPlayer(state, playerId);
        requireStatus(state, GameStatus.PLAYING);
        if (state.getPhase() != TurnPhase.MAIN && state.getPhase() != TurnPhase.SUB) {
            throw new IllegalStateException("スペルはメイン/サブフェイズでのみ使用できます");
        }
        PlayerState player = state.playerOf(playerId);
        if (player.isCannotUseCardsThisTurn()) {
            throw new IllegalStateException("このターンはカードを使用できません");
        }
        CardMaster master = cards.findById(peekHand(player, handIndex));
        SoulSpellSpec soul = requireSoul(state, player, master);

        ValidatedTargets validated = validateTargets(state, player, handIndex, soul.targets(), choices);
        payCost(player, stats.effectiveSoulCost(state, player, master, soulCostOf(master)), manaIndexes);
        ResolvedTargets resolved = removePlayedAndTargets(player, handIndex, validated);
        resolveSoulSpell(room, state, player, master, soul, resolved, false);

        player.setPlayedCardThisTurn(true);
        // 賢魂はスペルの使用である —— spellsCastThisTurn も進む(マスター裁定 A2(1))
        afterCardUsed(room, state, player, true);
    }

    /**
     * このカードを賢魂として使えるかを確かめ、仕様を返す(★Batch 54)。
     *
     * ★<b>{@code playConditions}(「代償を払えなければ使用できない」)は見ない。</b>
     * あの表はミニオンとしての姿に紐づく条件であり(《禁忌の代償》《絶望の連鎖》)、
     * 賢魂を持つ7枚は1枚も登録していない。姿ごとに条件を分けるのが本筋なので、
     * 将来そういうカードが出たら {@link SoulSpellSpec} 側に条件を足すこと。
     */
    private SoulSpellSpec requireSoul(GameState state, PlayerState player, CardMaster master) {
        if (!CardTextKeywords.hasSoul(master.text())) {
            throw new IllegalStateException("このカードは【賢魂】を持ちません");
        }
        SoulSpellSpec soul = effects.soulSpellOf(master.id());
        if (soul == null) {
            throw new IllegalStateException("この【賢魂】の効果は未実装です");
        }
        // 賢魂の使用は「スペルの使用」である —— スペルを封じるものに止められる(マスター裁定 A2(3))
        String spellDenial = guards.spellDenial(state, player);
        if (spellDenial != null) {
            throw new IllegalStateException(spellDenial);
        }
        return soul;
    }

    /** 【賢魂：n】の n。テキストが唯一の出どころである(裁定158 の延長) */
    private int soulCostOf(CardMaster master) {
        Integer cost = CardTextKeywords.soulCost(master.text());
        if (cost == null) {
            throw new IllegalStateException("このカードは【賢魂】を持ちません");
        }
        return cost;
    }

    /**
     * 賢魂の効果の解決と、使い終わったカードの行き先(★Batch 54)。
     *
     * ★<b>行き先は通常のスペルと同じ道具を通る</b>(マスター裁定 A1) ——
     * 墓地、【還元】ならマナ、禁忌由来なら消滅である。
     * ★<b>ただし【還元】の判定に {@code master.hasKeyword} を使わない。</b>
     * 賢魂を持つカードは2つの姿を持ち、キーワードは姿ごとに違う(マスター裁定 B1)。
     * 《白ノ霊知者》の【還元】は賢魂の姿にだけ付いている。
     */
    private void resolveSoulSpell(GameRoom room, GameState state, PlayerState player,
            CardMaster master, SoulSpellSpec soul, ResolvedTargets resolved, boolean fromTaboo) {
        room.addLog("%sが【%s】を【賢魂：%d】として唱えました"
                .formatted(player.getDisplayName(), master.name(), soulCostOf(master)));
        player.setPendingSpellDisposition(null);
        player.setPendingExtraSpellCasts(0);   // ★Batch 74: 前の使用が残した値を持ち込まない
        effects.runEffect(master.id(), new EffectContext(room, state, player,
                state.opponentOf(player.getPlayerId()), null, resolved, actions, false, fromTaboo),
                soul.effect());
        SpellDisposition disposition = player.getPendingSpellDisposition();
        player.setPendingSpellDisposition(null);
        // ★効果自身がこのカードの行き先を決めきったなら、もう動かさない(《スタンディングテント》)
        if (disposition == SpellDisposition.KEPT_BY_EFFECT) {
            return;
        }
        if (fromTaboo) {
            // 禁忌由来のカードは行き先の置換(a5)を受けず、必ず消滅する(3-6)。
            // 【還元】も機能しない —— 墓地を経由しないためである
            actions.disposeUsedCard(room, player, master, true, false);
            return;
        }
        if (disposition == null) {
            // ★【還元】は賢魂としての姿が持つものだけを見る(マスター裁定 B1)
            boolean restoration = CardTextKeywords.soulKeywords(master.text())
                    .contains(Keyword.RESTORATION);
            actions.disposeUsedCard(room, player, master, false, restoration);
            return;
        }
        switch (disposition) {
            case TO_HAND -> {
                player.getHand().add(master.id());
                room.addLog("【%s】は墓地に置かれる代わりに手札へ戻りました".formatted(master.name()));
            }
            case TO_DECK_BOTTOM -> {
                player.getDeck().addLast(master.id());
                room.addLog("【%s】は山札の一番下に置かれました".formatted(master.name()));
            }
            // ★Batch 59(裁定276): 【還元】を働かせずに墓地へ。
            // 賢魂の姿が持つ【還元】も同じく働かせない —— この指示は姿ではなく
            // 「このカードは何も起こさなかった」という事実に掛かるものだからである
            case TO_TRASH -> actions.disposeUsedCard(room, player, master, false, false);
            // KEPT_BY_EFFECT は上で return 済みである
            default -> throw new IllegalStateException("未知の行き先です: " + disposition);
        }
    }

    private void playMinion(GameRoom room, GameState state, PlayerState player,
            int handIndex, CardMaster master, List<TargetChoice> choices,
            List<Integer> manaIndexes) {
        requirePhase(state, TurnPhase.MAIN);
        if (player.isMinionZoneFull()) {
            throw new IllegalStateException("ミニオンは%d体までです".formatted(player.getMinionZoneLimit()));
        }
        requireCanEnterField(state, player);
        // 検証(状態を変えない)→ 支払い → 手札除去 → 場に出す → 効果、の順を守る。
        // 検証で弾かれた場合に状態が一切変わっていないことを保証するため
        ValidatedTargets validated = validateTargets(state, player, handIndex,
                effects.declarationTargetSpecOf(master.id()), choices);
        payCost(player, stats.effectiveCost(state, player, master), manaIndexes);
        // ★Batch 58: ここにあった【剛火の将】の割引の消費は、Ver1.1 で起動能力そのものが
        // 本文から消えたため削除した(rework-triage.md 区分5)
        ResolvedTargets resolved = removePlayedAndTargets(player, handIndex, validated);

        summonToField(room, state, player, master, resolved, false);
    }

    /**
     * 進化召喚(★Batch 52。裁定154・157)。
     *
     * <b>これは召喚である</b>(マスター裁定 A1)。メインフェイズにコストを支払って手札から出し、
     * 【召喚時】(ON_SUMMON)も登場時(ON_ENTER)も発動する。通常召喚と違うのは
     * <b>自分の場のミニオンを素材として下に置く</b>ことだけである。
     *
     * <p>★<b>場の上限を見ていない。</b>素材を最低1体は消費するので、
     * 進化召喚で場のミニオンが増えることは構造的に起こらない。
     *
     * <p>★順序は通常召喚と同じく<b>検証 → 支払い → 手札除去 → 場に出す</b>である。
     * 素材を場から外すのは {@code summonToField} の中、
     * <b>「場に出られる」ことが確定した後</b>である。
     */
    private void playEvolution(GameRoom room, GameState state, PlayerState player,
            int handIndex, CardMaster master, List<TargetChoice> choices,
            List<String> materialIds, List<Integer> manaIndexes) {
        requirePhase(state, TurnPhase.MAIN);
        requireCanEnterField(state, player);
        List<MinionInstance> materials = resolveMaterials(player, master, materialIds);
        ValidatedTargets validated = validateTargets(state, player, handIndex,
                effects.declarationTargetSpecOf(master.id()), choices);
        payCost(player, stats.effectiveCost(state, player, master), manaIndexes);
        ResolvedTargets resolved = removePlayedAndTargets(player, handIndex, validated);
        summonToField(room, state, player, master, resolved, false, materials);
    }

    /**
     * 進化素材の検証(★Batch 52)。状態は1つも変えない。
     *
     * <ul>
     * <li>素材は<b>自分の場のミニオン</b>に限る(マスター裁定 A2)。</li>
     * <li>同じミニオンを2回選べない。</li>
     * <li>数は {@link EvolutionSpec#minMaterials()} 以上
     *     {@link EvolutionSpec#maxMaterials()} 以下。</li>
     * <li>条件を満たす素材が場に居なければ、そもそも使用できない(マスター裁定 D3)。</li>
     * </ul>
     *
     * ★クライアントは {@code CardView.evolutionMaterialIds} が届けた候補からしか選ばないが、
     * <b>ここは届いた値を信用しない</b>。判定に使う述語は候補を作ったのと同じ
     * {@link EvolutionSpec#material()} であり、規則は1箇所にしかない(裁定163)。
     */
    private List<MinionInstance> resolveMaterials(PlayerState player, CardMaster master,
            List<String> materialIds) {
        EvolutionSpec spec = effects.evolutionOf(master.id());
        if (spec == null) {
            throw new IllegalStateException("この進化ミニオンの召喚条件は未実装です");
        }
        List<String> ids = materialIds == null ? List.of() : materialIds;
        if (ids.size() < spec.minMaterials() || ids.size() > spec.maxMaterials()) {
            throw new IllegalArgumentException("進化素材は%sを選んでください: %s"
                    .formatted(spec.minMaterials() == spec.maxMaterials()
                            ? "%d体".formatted(spec.minMaterials())
                            : "%d体以上".formatted(spec.minMaterials()),
                            spec.description()));
        }
        List<MinionInstance> materials = new java.util.ArrayList<>();
        for (String instanceId : ids) {
            MinionInstance material = findMinion(player, instanceId);
            if (materials.contains(material)) {
                throw new IllegalArgumentException("同じミニオンを2回素材にはできません");
            }
            if (!spec.material().test(material)) {
                throw new IllegalArgumentException("進化素材にできません(条件: %s)"
                        .formatted(spec.description()));
            }
            materials.add(material);
        }
        return materials;
    }

    private void playSpell(GameRoom room, GameState state, PlayerState player,
            int handIndex, CardMaster master, List<TargetChoice> choices, boolean enhanced,
            List<Integer> manaIndexes) {
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
                effects.declarationTargetSpecOf(master.id()), choices);
        // 【死者蘇生】は「生贄にした自分のミニオンの数だけコスト-1」であり、
        // 支払う額が選択結果に依存する。StatCalculatorが参照できる場所に数を置いてから支払う
        if (SACRIFICE_SPELL_ID.equals(master.id())) {
            player.setPendingSacrificeCount(validated.minions().get(0).size());
        }
        try {
            payCost(player, stats.effectiveCost(state, player, master) + extraCost, manaIndexes);
        } finally {
            player.setPendingSacrificeCount(0); // MP不足で弾かれた場合も必ず戻す
        }
        ResolvedTargets resolved = removePlayedAndTargets(player, handIndex, validated);
        // ★★★Batch 73: 【詠唱の宝珠】をここで「使い切る」処理は無くなった。
        // Ver1.1 の本文は「次の自分のターンに唱える光のスペル<b>すべて</b>」であり、
        // 1枚で消える形は Ver0.4 の姿だった(ver0.4-transcription-notes 4章 #9)。
        // 期限はターン番号で持ち、落とすのはターン終了処理1箇所である。
        room.addLog("%sが【%s】を唱えました".formatted(player.getDisplayName(), master.name()));

        player.setPendingSpellDisposition(null);
        player.setPendingExtraSpellCasts(0);   // ★Batch 74: 前の使用が残した値を持ち込まない
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
                // ★Batch 59(裁定276): 【還元】を働かせずに墓地へ。
                // restoration=false を渡すのが「還元しない」の言い方である
                case TO_TRASH -> actions.disposeUsedCard(room, player, master, false, false);
                // 効果自身が行き先を決めきった場合は何もしない(★Batch 54 からの挙動を据え置く)
                case KEPT_BY_EFFECT -> {
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
        // (経由すると「選び終えるまで塞ぐ」判定に自分自身が引っかかってしまう)。
        //
        // ★Batch 51: ターンプレイヤーであることを要求しなくなった(マスター裁定214)。
        // 【破壊時】は相手のターン中にも起きるため、「ターンプレイヤーでなければ拒否する」形だと
        // 相手ターンに発火する効果は本人に選ばせられず、自動決定にするしかなかった
        // (50 のサモンズライトがそれである。50 設計解説 6-2)。
        // <b>選択待ちであること自体が、この操作を行ってよい根拠である</b> ——
        // pendingChoice を持たない者は下の判定で弾かれ、持つ者は自分の分だけを解決できる。
        // 手番の側は requireTurnPlayer が「相手の選択待ち」を見て塞ぐので、
        // 選んでいる最中に盤面が動くこともない
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
        // 解決の前に待ち行列から取り出す(効果内で例外が起きても選択待ちのまま固まらないように)。
        // ★Batch 64: 取り出すのは先頭1件だけである。積まれている残りは次の resolveChoice が扱う
        player.pollPendingChoice();

        // ★★Batch 64: 選んだ位置が、問い合わせを作った瞬間と同じものを指しているか。
        //
        // 1人が複数の問い合わせを同時に持てるようになったので、
        // <b>手前の選択の解決で、後ろの選択の候補が指す先が動きうる</b>
        // (墓地から蘇生してから、別の問い合わせで墓地の位置を選ぶ)。
        // ★<b>1件でもずれていたら、その問い合わせは何も起こさずに終える。</b>
        //   ずれた候補だけを落とす形にすると、再開先ごとに「空で来たとき」の分岐が要る ——
        //   25個ある再開先の全部に同じ但し書きを書くことになる(裁定163 が戒めた形)。
        if (hasDriftedCandidate(player, choice, chosen)) {
            room.addLog("%s: 選ぼうとしたカードが盤面から動いたため、この選択は何も起こしませんでした"
                    .formatted(player.getDisplayName()));
        } else {
            effects.resolveChoice(contextOf(room, state, player, null, null), choice, chosen);
        }

        // ★Batch 51: 攻撃時の割り込みだった場合、保留していた戦闘をここで解決する。
        // 下のターン受け渡しより先に置くのは、戦闘のほうが同じターンの中の事象だからである
        resumePendingAttack(room, state);

        // ターンエンド中の割り込み(詠唱の疾風騎士)だった場合、選択の解決後に
        // 保留していたターンの受け渡しを行う。それ以外(メインフェイズ中の割り込み)では
        // まだ手番が続くため、advanceTurn は呼ばれない(内部で保留フラグを見て判断する)
        advanceTurnIfPending(room, state);
    }

    /**
     * 選んだ候補が、問い合わせを作った瞬間と別のカードを指していないか(★Batch 64)。
     *
     * ★<b>控えを取るのも読むのも {@code GameActions} の同じメソッドである。</b>
     * 控えるときと照合するときでゾーンの読み方が違ったら、番人が番人でなくなる(裁定110)。
     *
     * @return 1件でもずれていれば true
     */
    private boolean hasDriftedCandidate(PlayerState player, PendingChoice choice, List<String> chosen) {
        if (choice.expectedCardIds().isEmpty()) {
            return false; // 位置を指さない選択(MINION / CONFIRM)は照合しない
        }
        for (String position : chosen) {
            int index = choice.candidates().indexOf(position);
            String expected = choice.expectedCardIds().get(index);
            String actual = actions.cardIdAtZonePosition(player, choice.kind(),
                    Integer.parseInt(position));
            if (!java.util.Objects.equals(expected, actual)) {
                return true;
            }
        }
        return false;
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
    /** 進化以外の禁忌カードの使用(素材の指定を伴わない従来の入口) */
    public void playTabooCard(GameRoom room, String playerId, int tabooIndex,
            List<Integer> manaIndexes, List<TargetChoice> choices) {
        playTabooCard(room, playerId, tabooIndex, manaIndexes, choices, List.of());
    }

    public void playTabooCard(GameRoom room, String playerId, int tabooIndex,
            List<Integer> manaIndexes, List<TargetChoice> choices, List<String> materialIds) {
        playTabooCard(room, playerId, tabooIndex, manaIndexes, choices, materialIds, false);
    }

    /**
     * 禁忌デッキのカードを【賢魂：n】として使う(★Batch 54。マスター裁定 A6)。
     *
     * <b>退けるマナは n 枚である。</b> 禁忌の支払いは「そのカードのコストの枚数」であり、
     * 賢魂として使うならコストは n だからである(裁定152)。
     *
     * ★<b>使い終わったカードは必ず消滅する</b>(総合ルール3-6)。
     * 【還元】も行き先の置換(a5)も効かない —— どちらも墓地を経由する仕組みだからである。
     * ★ただし《スタンディングテント》のように<b>効果自身が場に出す</b>場合は場に残り、
     * その後<b>禁忌由来のミニオンとして</b>場を離れるときに消滅する
     * ({@code EffectContext.fromTaboo} が印を運ぶ)。
     */
    public void playTabooSoulCard(GameRoom room, String playerId, int tabooIndex,
            List<Integer> manaIndexes, List<TargetChoice> choices) {
        playTabooCard(room, playerId, tabooIndex, manaIndexes, choices, List.of(), true);
    }

    private void playTabooCard(GameRoom room, String playerId, int tabooIndex,
            List<Integer> manaIndexes, List<TargetChoice> choices, List<String> materialIds,
            boolean asSoul) {
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

        // ★Batch 54: 賢魂としての使用は、種別に関係なくスペルの使用として進む(裁定152)。
        // 進化素材も場の空きも要らない —— 場に出るわけではないからである
        if (asSoul) {
            SoulSpellSpec soul = requireSoul(state, player, master);
            ValidatedTargets soulTargets = validateTargets(state, player, -1, soul.targets(), choices);
            List<Integer> soulPay = resolveTabooPayment(player, soulCostOf(master), manaIndexes);
            validateTabooCost(player, soulCostOf(master), soulPay);
            payTabooCost(room, player, soulPay);
            ResolvedTargets soulResolved = removePlayedAndTargets(player, -1, soulTargets);
            player.getTabooDeck().remove(tabooIndex);
            player.setPlayedCardThisTurn(true);
            room.addLog("%sが禁忌カード【%s】を使用".formatted(player.getDisplayName(), master.name()));
            resolveSoulSpell(room, state, player, master, soul, soulResolved, true);
            afterCardUsed(room, state, player, true);
            return;
        }

        // ★進化は素材を必ず1体消費するので、場が満杯でも枠は空く
        if (master.type() == CardType.MINION && player.isMinionZoneFull()) {
            throw new IllegalStateException("ミニオンは%d体までです".formatted(player.getMinionZoneLimit()));
        }
        if (master.type() == CardType.MINION || master.type() == CardType.EVOLUTION) {
            requireCanEnterField(state, player);
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
        // ★Batch 52: 禁忌デッキに入れた進化ミニオンも、出し方は通常と同じである
        //   (マスター裁定 E1)。素材は自分の場から取り、コストの支払い方だけが禁忌の作法になる
        List<MinionInstance> materials = master.type() == CardType.EVOLUTION
                ? resolveMaterials(player, master, materialIds) : List.of();
        ValidatedTargets validated = validateTargets(state, player, -1,
                effects.declarationTargetSpecOf(master.id()), choices);
        List<Integer> pay = resolveTabooPayment(player, master.cost(), manaIndexes);
        validateTabooCost(player, master.cost(), pay);

        payTabooCost(room, player, pay);
        ResolvedTargets resolved = removePlayedAndTargets(player, -1, validated);
        player.getTabooDeck().remove(tabooIndex);
        player.setPlayedCardThisTurn(true);
        room.addLog("%sが禁忌カード【%s】を使用".formatted(player.getDisplayName(), master.name()));

        switch (master.type()) {
            case MINION -> summonToField(room, state, player, master, resolved, true);
            case EVOLUTION -> summonToField(room, state, player, master, resolved, true, materials);
            case SPELL -> {
                // 禁忌由来のスペルは pendingSpellDisposition による行き先置換(a5)を受けない。
                // 総合ルール3-6により、禁忌カードは使用され終わると消滅ゾーンへ行くことが
                // 確定しているためである(disposeUsedSpell が fromTaboo=true で消滅へ送る)。
                // 効果が誤って書き込んでも通常プレイに漏らさないよう、前後でクリアする
                player.setPendingSpellDisposition(null);
                player.setPendingExtraSpellCasts(0);   // ★Batch 74: 前の使用が残した値を持ち込まない
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
    /**
     * 禁忌コストの支払いを決める(★Batch 70。裁定317・321)。
     *
     * <p>69 までは<b>クライアントが必ず位置を指定してくる</b>前提だった
     * (禁忌の支払いは 43 から手で選ぶ形だったためである)。
     * 裁定317 が「禁忌も自動で払えるようにする」と決め、
     * 裁定321 がドラッグに確認を挟まないと決めたので、<b>指定が空で来る道</b>ができた。
     *
     * <p>★空のときは {@link ManaPayment#tabooOrder}(表向き → 裏向き)の先頭から取る。
     * ★<b>順序をここに書かない</b> —— クライアントの強調表示も同じ順序をビューから読む(裁定130)。
     */
    private List<Integer> resolveTabooPayment(PlayerState player, int cost, List<Integer> manaIndexes) {
        List<Integer> given = manaIndexes == null ? List.of() : manaIndexes;
        if (!given.isEmpty() || cost == 0) {
            return given;
        }
        List<Integer> order = ManaPayment.tabooOrder(player);
        if (order.size() < cost) {
            throw new IllegalStateException(
                    "禁忌コストの支払いに使えるマナが足りません(必要%d枚/使用可能%d枚)"
                            .formatted(cost, order.size()));
        }
        return List.copyOf(order.subList(0, cost));
    }

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
    /** 進化以外の特殊召喚(素材の指定を伴わない従来の入口) */
    public void specialSummon(GameRoom room, String playerId, int handIndex, List<TargetChoice> choices) {
        specialSummon(room, playerId, handIndex, choices, List.of());
    }

    public void specialSummon(GameRoom room, String playerId, int handIndex,
            List<TargetChoice> choices, List<String> materialIds) {
        specialSummon(room, playerId, handIndex, choices, materialIds, List.of());
    }

    /**
     * ★Batch 70(裁定319): 払うマナを指定できる入口。空なら自動である。
     * ★<b>ここで払うのは代替コストの MP 部分</b>({@code spec.mpCost()})であり、
     *   多くのカードでは 0 である —— 0 のときは指定も空でよい。
     */
    public void specialSummon(GameRoom room, String playerId, int handIndex,
            List<TargetChoice> choices, List<String> materialIds, List<Integer> manaIndexes) {
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
        // ★Batch 52: 進化は素材を必ず1体は消費するので、場が満杯でも枠は空く(マスター裁定 D1)
        boolean evolution = master.type() == CardType.EVOLUTION;
        if (player.isMinionZoneFull() && !costFreesZone && !evolution) {
            throw new IllegalStateException("ミニオンは%d体までです".formatted(player.getMinionZoneLimit()));
        }
        requireCanEnterField(state, player);
        // ★特殊召喚が代替しているのはコストだけであり、素材は通常の進化召喚と同じく要る
        List<MinionInstance> materials = evolution
                ? resolveMaterials(player, master, materialIds) : List.of();

        ValidatedTargets validated = validateTargets(state, player, handIndex, spec.targets(), choices);
        // 多くは0だが、極炎竜ヴォルカニクスのようにMPを要するものもある
        payCost(player, spec.mpCost(), manaIndexes);
        ResolvedTargets resolved = removePlayedAndTargets(player, handIndex, validated);
        room.addLog("%sが【%s】を特殊召喚".formatted(player.getDisplayName(), master.name()));
        // 代替コストの支払い(手札を山札の下へ・ミニオンを手札に戻す等)
        effects.runEffect(master.id(), contextOf(room, state, player, null, resolved),
                spec.costEffect());

        MinionInstance summoned = summonToField(room, state, player, master, resolved, false, materials);
        // 特殊召喚で出したときのみ発生する追加効果(背水の炎壁・這い寄る生霊の自壊予約)。
        // 通常の【召喚時】とは別枠であり、出したミニオン自身をsourceとして渡す
        effects.runEffect(master.id(), contextOf(room, state, player, summoned, resolved),
                spec.onSpecialSummon());
        player.setPlayedCardThisTurn(true);
        // 特殊召喚もカードの使用として数える(a1)。ミニオンのためスペルフラグはfalse
        afterCardUsed(room, state, player, false);
    }

    /**
     * <b>墓地からの</b>【特殊召喚】(★Batch 53。《サモナーポップ・エンラ》)。
     *
     * <blockquote>【特殊召喚】(自分の墓地にミニオンが6体以上のとき
     * <b>自分の手札または墓地から</b>コスト1支払って場に出せる。)</blockquote>
     *
     * <h2>これは4つ目の「場に出す」入口ではない</h2>
     *
     * 手札からの特殊召喚({@link #specialSummon})と<b>違うのは出どころだけ</b>である。
     * 条件・代替コスト・素材・着地はすべて同じ道具を使い、最後は
     * {@link #summonToField} に合流する。ここで新しく書いているのは
     * 「墓地から取り除く」ことと、「そのカードが墓地からも出せると宣言しているか」の確認だけである。
     *
     * <h2>★墓地に居る自分自身も「6体」に数える(マスター裁定)</h2>
     *
     * 条件の評価は<b>墓地から取り除く前</b>に行う。裁定190(「今の手札の枚数」に自身を含む)と
     * 同じ形であり、まだ場に出ていないカードは今の墓地の中身の一部である。
     *
     * <h2>★素材は要る(裁定226)</h2>
     *
     * 特殊召喚が代替しているのはコストだけである。墓地から出す場合も、
     * 素材は<b>自分の場の</b>ミニオンから取る(裁定221)。
     *
     * @param trashIndex 墓地の何番目のカードか
     */
    public void specialSummonFromGrave(GameRoom room, String playerId, int trashIndex,
            List<TargetChoice> choices, List<String> materialIds) {
        GameState state = requireState(room);
        requireTurnPlayer(state, playerId);
        requireStatus(state, GameStatus.PLAYING);
        requirePhase(state, TurnPhase.MAIN);
        PlayerState player = state.playerOf(playerId);
        if (player.isCannotUseCardsThisTurn()) {
            throw new IllegalStateException("このターンはカードを使用できません");
        }
        // 【秩序の執行官】は相手の特殊召喚そのものを封じる(出どころを問わない)
        String summonDenial = guards.specialSummonDenial(state, player);
        if (summonDenial != null) {
            throw new IllegalStateException(summonDenial);
        }
        if (trashIndex < 0 || trashIndex >= player.getTrash().size()) {
            throw new IllegalArgumentException("不正な墓地の指定です");
        }
        CardMaster master = cards.findById(player.getTrash().get(trashIndex));
        SpecialSummonSpec spec = effects.specialSummonOf(master.id());
        if (spec == null || !spec.fromGrave()) {
            throw new IllegalStateException("このカードは墓地から特殊召喚できません");
        }
        // 手札の位置を持たないので -1 を渡す。墓地から出せると宣言しているカードの条件は
        // 手札の位置を参照しない(参照するなら手札からしか出せないはずである)
        if (!spec.condition().test(state, player, -1)) {
            throw new IllegalStateException("特殊召喚の条件を満たしていません");
        }
        boolean evolution = master.type() == CardType.EVOLUTION;
        if (player.isMinionZoneFull() && !evolution) {
            throw new IllegalStateException("ミニオンは%d体までです".formatted(player.getMinionZoneLimit()));
        }
        requireCanEnterField(state, player);
        List<MinionInstance> materials = evolution
                ? resolveMaterials(player, master, materialIds) : List.of();
        // 墓地のカード自身は手札に無いため、対象検証の自己除外インデックスは -1 である。
        // ★★Batch 68: ここにあった requireTrashSourceNotTargeted の呼び出しを撤去した
        //   (理由は summonFromGrave の同じ箇所に書いてある)
        ValidatedTargets validated = validateTargets(state, player, -1, spec.targets(), choices);
        payCost(player, spec.mpCost());
        ResolvedTargets resolved = removePlayedAndTargets(player, -1, validated);
        player.getTrash().remove(trashIndex);
        room.addLog("%sが墓地から【%s】を特殊召喚".formatted(player.getDisplayName(), master.name()));
        effects.runEffect(master.id(), contextOf(room, state, player, null, resolved),
                spec.costEffect());

        MinionInstance summoned = summonToField(room, state, player, master, resolved, false, materials);
        effects.runEffect(master.id(), contextOf(room, state, player, summoned, resolved),
                spec.onSpecialSummon());
        player.setPlayedCardThisTurn(true);
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
     * <h2>★Batch 60(裁定278(c)): 対象を選ぶ【召喚時】もここから通る</h2>
     *
     * Batch 57 から 59 までのあいだ、この入口は
     * 「【召喚時】に対象を選ぶミニオンは墓地からは召喚できません」と理由を返して止めていた。
     * 止めていたのは<b>対象の選択({@link TargetChoice})を受け取る口が無かった</b>ためであり、
     * ルールとしてそう決まっていたからではない。裁定275 が「手札にあるかのように」を
     * 狭く読んだ結果、そのガードは恒久のルールになる<b>はずだった</b>が、
     * マスター裁定278 は (c) ——<b>導線を新設する</b>—— を採った。
     * カードテキストのどこにも書かれていない制限を1つ増やすより、
     * 《黄泉の召喚主》と《執念の暗殺者》が同じスターターに入っている遊び味を採る、という判断である。
     *
     * <p>新設したといっても、道具は1つも増えていない ——
     * {@link #specialSummonFromGrave}(Batch 53)が既に持っていた
     * 「墓地の位置 + 対象」の形をそのまま借りただけである
     * ({@code GraveSummonRequest} も {@code battle.js} の {@code beginSelection} も共用する)。
     *
     * @param trashIndex 墓地の何番目のカードか
     * @param choices    【召喚時】が要求する対象の選択。要求が無いカードでは空でよい
     */
    public void summonFromGrave(GameRoom room, String playerId, int trashIndex,
            List<TargetChoice> choices) {
        summonFromGrave(room, playerId, trashIndex, choices, List.of());
    }

    /**
     * 墓地からの召喚(★Batch 74 で進化素材を受けるようになった。裁定341)。
     *
     * <p>★<b>「ミニオン」に進化ミニオンが含まれる</b>(裁定310・総合ルール2-1)。
     * 73 まで、ここは {@code != CardType.MINION} で進化を弾いていた ——
     * 本文は「<b>ミニオン</b>を墓地から召喚してもよい」であり、除外する根拠が無い。
     * ★★<b>これは「召喚」である</b>(本文が「召喚してもよい」と書いている)ので、
     * 通常の進化召喚とまったく同じ扱いになる —— <b>素材は宣言のときに選ぶ</b>。
     * 効果による「出す」(墓地・マナ・山札)が割り込みで素材を問うのとは形が違う。
     * ★したがって【召喚時】も発動する(2-9 の表の「召喚」の行である)。
     *
     * @param materialIds 進化召喚の素材にする自分の場のミニオンの instanceId
     */
    public void summonFromGrave(GameRoom room, String playerId, int trashIndex,
            List<TargetChoice> choices, List<String> materialIds) {
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
        // ★★★Batch 74(裁定341): 進化ミニオンもミニオンである(裁定310)
        if (!master.type().isMinion()) {
            throw new IllegalStateException("墓地から召喚できるのはミニオンのみです");
        }
        boolean evolution = master.type() == CardType.EVOLUTION;
        // ★進化は素材を場から取り除いてその上に乗るので、場が埋まっていても召喚できる
        if (!evolution && player.isMinionZoneFull()) {
            throw new IllegalStateException("ミニオンは%d体までです".formatted(player.getMinionZoneLimit()));
        }
        requireCanEnterField(state, player);
        // ★Batch 60(裁定278(c)): ここから先は通常召喚(playMinion)と同じ順序である ——
        // 検証(状態を変えない)→ 支払い → 墓地から取り除く → 場に出す → 効果。
        // ★★★Batch 68(裁定282): この spec は<b>常に空になった</b> ——
        //   ミニオンの宣言時対象は無くなり、【召喚時】の対象は場に出てから問われる。
        //   したがってこの入口が受け取る choices も常に空である(引数は互換のために残す)。
        //   ★<b>Batch 60 の門(requireTrashSourceNotTargeted)はここで撤去した</b> ——
        //   「出どころ自身が墓地の候補に混じる」は、検証より前にカードが墓地を離れる
        //   ようになったことで<b>構造ごと起こらなくなった</b>。
        //   守っていた性質は Batch68SummonTargetTest が
        //   「墓地から出したカード自身は【召喚時】の候補に現れない」として測り直している(裁定196)。
        TargetSpec spec = effects.declarationTargetSpecOf(master.id());
        // 墓地のカード自身は手札に無いため、対象検証の自己除外インデックスは -1 である
        ValidatedTargets validated = validateTargets(state, player, -1, spec, choices);
        // ★Batch 74: 素材の検証は支払いより前に済ませる(通常召喚・特殊召喚と同じ順序)
        List<MinionInstance> materials = evolution
                ? resolveMaterials(player, master, materialIds) : List.of();
        payCost(player, stats.effectiveCost(state, player, master));
        ResolvedTargets resolved = removePlayedAndTargets(player, -1, validated);
        player.getTrash().remove(trashIndex);
        room.addLog("%sが墓地から【%s】を召喚".formatted(player.getDisplayName(), master.name()));
        MinionInstance summoned =
                summonToField(room, state, player, master, resolved, false, materials);
        // 【演舞の墓守】(★Batch 50): 自分の墓地から出たミニオンはそのターンAttack+1。
        // ★経路を問わない(マスター裁定204)ので、効果による「出す」(GameActions.reviveFromGrave)
        // だけでなく、この「墓地からの召喚」でも乗る
        if (summoned != null) {
            effects.fireMinionEnteredFromGrave(contextOf(room, state, player, summoned, resolved));
        }
        player.setPlayedCardThisTurn(true);
        // 墓地からの召喚もカードの使用として数える(a1)
        afterCardUsed(room, state, player, false);
    }

    // ===================================================================
    // ★★★Batch 68(裁定196): 撤去した番人 —— requireTrashSourceNotTargeted
    // ===================================================================
    //
    // Batch 60 が置いた門である。「墓地から出すカード自身を、そのカードの
    // 【召喚時】の対象に選べてしまう」を弾いていた。
    //
    // ★<b>なぜ要らなくなったか。</b>あの穴は<b>順序</b>から生まれていた ——
    //   66 までは「対象を検証してから墓地のカードを取り除く」順だったので、
    //   検証の瞬間、出すカード自身がまだ墓地に居た。
    //   裁定282 で【召喚時】の対象は<b>ミニオンが場に出てから</b>選ぶことになり、
    //   候補を数える時点ではカードはとっくに墓地を離れている。
    //   ★つまり<b>塞ぐ穴そのものが構造ごと消えた</b>。
    //   守るコードを消したのではなく、守る必要のある状態が作れなくなった。
    //
    // ★<b>測っていた性質は減らしていない。</b>
    //   {@code Batch68SummonTargetTest#墓地から召喚したカード自身は召喚時の候補に現れない}
    //   が同じことを、今度は<b>候補の側から</b>測る。
    //   例外の文言「墓地から出すカード自身は対象に選べません」は、もうどこにも無い。
    // ===================================================================

    /** 素材を取らない召喚(通常のミニオン・蘇生・禁忌)。進化以外はすべてこちらを通る */
    private MinionInstance summonToField(GameRoom room, GameState state, PlayerState player,
            CardMaster master, ResolvedTargets resolved, boolean fromTaboo) {
        return summonToField(room, state, player, master, resolved, fromTaboo, List.of());
    }

    /** 召喚の共通着地処理。ON_SUMMONとON_ENTERの両方が発動する(発注者確認済み裁定)。
     *  効果による「出す」(黄泉還る水龍など)を実装するときはON_ENTERのみを発火する。
     *
     *  <p>★Batch 52: {@code materials} が空でなければ進化召喚である(裁定154)。
     *  素材を場から取り除いて束にし、付与されていた効果だけを新しい面へ移す(裁定157)。
     *  ★<b>素材を外すのは「場に出る」ことが確定した後である</b> ——
     *  光霊・モアニールの置換で場に出られなかった場合、素材は場に残る
     *  (下に置かれるのは「場に出る」ことの一部であり、出ないなら下にも置かれない)。 */
    private MinionInstance summonToField(GameRoom room, GameState state, PlayerState player,
            CardMaster master, ResolvedTargets resolved, boolean fromTaboo,
            List<MinionInstance> materials) {
        // 【光霊・モアニール】(★Batch 50): 自分のマナよりコストの大きいミニオンは、
        // 場に出る代わりに山札の下へ置かれる。コストは既に支払われており、
        // カードも手札(または禁忌デッキ)から取り除かれた後である —— 置換されるのは
        // 「場に出る」ことだけであって、召喚の宣言そのものは成立している。
        // ★禁忌由来のカードは山札に戻せない(総合ルール3-6: 禁忌カードは消滅する)ため消滅ゾーンへ送る
        if (guards.isEntryToDeckBottom(state, player, master)) {
            if (fromTaboo) {
                player.getLostZone().add(master.id());
                room.addLog("【光霊・モアニール】: 【%s】は場に出られず、禁忌カードのため消滅しました"
                        .formatted(master.name()));
            } else {
                player.getDeck().addLast(master.id());
                room.addLog("【光霊・モアニール】: 【%s】は場に出る代わりに山札の下へ置かれました"
                        .formatted(master.name()));
            }
            return null;
        }
        // ★Batch 58: 実体を作る入口は GameActions.newFieldMinion 1本である
        // (《剛火の将》の常在の加算量を場に出る瞬間に写すため)
        MinionInstance minion = actions.newFieldMinion(state, master, fromTaboo);
        // ★Batch 52: 進化の素材を下に置き、付与されていた効果だけを引き継ぐ。
        // ★Batch 53: その処理そのものを GameActions へ移した ——
        //   効果による「出す」(《英術・スケアロック》)でも同じ束を作る必要があり、
        //   2箇所に書くと必ずどちらかが引き継ぎ(裁定224)を忘れる
        actions.attachEvolutionMaterials(room, player, minion, materials);
        player.getMinionZone().add(minion);
        room.addLog("%sが【%s】を召喚しました".formatted(player.getDisplayName(), master.name()));

        EffectContext ctx = contextOf(room, state, player, minion, resolved);
        effects.fire(TriggerType.ON_SUMMON, minion, ctx);
        // 登場の数え上げと ON_ENTER 以降の発火は、効果による「出す」と共通である(★Batch 53)。
        // 召喚だけが持つのは、この直前の ON_SUMMON 1つだけである
        actions.fireEntryTriggers(room, player, minion, ctx);
        return minion;
    }

    /** ウェポンの装備。装備済みなら古いウェポンは即座に墓地へ(総合ルール2-5) */
    private void playWeapon(GameRoom room, GameState state, PlayerState player,
            int handIndex, CardMaster master, List<Integer> manaIndexes) {
        requirePhase(state, TurnPhase.MAIN);
        payCost(player, stats.effectiveCost(state, player, master), manaIndexes);
        takeFromHand(player, handIndex);
        equipWeapon(room, player, master, false);
    }

    /** ウェポンの装備。旧ウェポンの行き先は、それが禁忌由来なら消滅・そうでなければ墓地 */
    private void equipWeapon(GameRoom room, PlayerState player, CardMaster master, boolean fromTaboo) {
        CardMaster old = player.getEquippedWeapon();
        if (old != null) {
            // 詠唱の宝珠: 破壊(destroyOwnWeapon)だけでなく、付け替えで場を離れる場合も発動する
            // ★★Batch 74(裁定336): <b>付け替えは破壊扱いである。</b>
            // ただし禁忌由来なら消滅するので、そのときは破壊にならない。
            actions.onWeaponLeftPlay(room, player, old, true, player.isEquippedWeaponFromTaboo());
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
        // ウェポンの装備時効果(土文明のガイア・ハンマー等)。召喚時ではなく装備の瞬間に発動する
        effects.fireEquip(master.id(), contextOf(room, room.getGameState(), player, null, null));
    }

    /**
     * ピュア・エレメント: 使用時このカード自身を裏向きの一時マナとしてマナゾーンに置く。
     * 通常のスペルと違い墓地へ行かない(カード自体がマナになる)。ターン終了時に消滅する。
     *
     * <p>★<b>Batch 60: これも「マナにカードが置かれた」1回として数える。</b>
     * 一時マナであることは本文の「マナゾーンに置く」を打ち消さない ——
     * 《豊穣の地霊主》は<b>置かれたこと</b>に反応するのであって、
     * 置かれたものがターンをまたぐかどうかは見ていない。
     * ★一時マナを作るのはこの1枚だけなので、{@code GameActions} の裏向きの共通入口には
     * 合流させず、置いたあとで通知({@code manaPlaced})だけを送っている。
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
        actions.manaPlaced(room, player);
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
        // ウェポンの寿命(Ver.0.4の総則変更)。攻撃宣言が成立した時点で記録し、
        // 実際の破壊はターン終了時(finishEndTurnCleanup)に行う。
        // 記録するのはリーダーの攻撃だけであり、ミニオンの攻撃では立てない(発注者確認済み)
        player.setWeaponAttackedThisTurn(true);
        int damage = stats.effectiveWeaponAttack(state, player);
        room.addLog("リーダーが【%s】で攻撃(%dダメージ)".formatted(weapon.name(), damage));

        if (targetIsLeader) {
            // 大地の守護盾(土文明): リーダーへの攻撃をウェポンの破壊で肩代わりする(ダメージ無効)。
            // 攻撃宣言は成立しているため、下のウェポン攻撃時効果は肩代わりの有無に関わらず発動する。
            // ★光霊・モアニール(★Batch 50)も同じ位置で肩代わりする。短絡評価により、
            // 守護盾が先に肩代わりした場合はモアニールを消費しない
            if (!actions.tryInterceptLeaderAttackWithShield(room, opponent)
                    && !actions.tryReplaceLeaderDamageWithGuardian(room, opponent)) {
                opponent.setLp(opponent.getLp() - damage);
                room.addLog("相手リーダーに%dダメージ(残りLP %d)".formatted(damage, opponent.getLp()));
                if (opponent.getLp() <= 0) {
                    actions.finish(room, player);
                    return;
                }
            }
        } else {
            // 一方的にダメージを与える(反撃なし: 4-3)
            actions.dealCombatDamage(room, opponent, target, damage);
            actions.checkDestruction(room, opponent, target, DestructionCause.COMBAT);
        }

        // ウェポンの攻撃時効果。現状2種のためswitchで直書きし、増えたらRegistryへ移す(TODO)
        switch (weapon.id()) {
            case PEARL_TRIDENT -> { // 真珠の三叉槍: 自分のリーダーが攻撃した時、カードを1枚引く
                actions.drawCards(room, player, 1);
            }
            // 魔剣レーヴァテイン(QTE-M-FIRE-14)・禁忌の冥魔剣(QTE-M-DARK-14)は Ver.0.4 で発火元が
            // 「自分のリーダーの攻撃」から「自分のミニオンの攻撃/登場」へ移ったため、
            // このswitchから外して CardEffectRegistry のトリガー登録に移設した
            // (ON_ALLY_MINION_ATTACK / ON_ALLY_MINION_ENTER)
            case WRAITH_SCYTHE -> requestWraithScytheRecover(room, player);
            case REAPER_SCYTHE -> { // 死神の大鎌: 攻撃されたミニオンは戦闘ダメージに関わらず破壊される
                if (!targetIsLeader && opponent.getMinionZone().contains(target)) {
                    actions.destroyMinion(room, opponent, target, DestructionCause.COMBAT);
                }
            }
            case FREEZE_ROD -> { // 氷結の杖: 攻撃されたリーダーまたはミニオンは、次のターン攻撃できない
                int nextTurn = state.getTurnNumber() + 1;
                if (targetIsLeader) {
                    opponent.setLeaderCannotAttackOnTurn(nextTurn);
                    room.addLog("相手リーダーは凍結しました(次のターン攻撃不可)");
                } else if (opponent.getMinionZone().contains(target)) {
                    target.setCannotAttackOnTurn(nextTurn);
                    room.addLog("【%s】は凍結しました(次のターン攻撃不可)".formatted(target.getMaster().name()));
                }
            }
            // 聖剣エクスカリバー: 自分の【守護】ミニオンすべての体力を<b>全て回復</b>する。
            //
            // ★Batch 67(裁定303 の3例目): 2回復 → <b>全快</b>。
            // Ver1.1 の本文は「体力を全て回復」であり、66 までの実装(2回復)は
            // Ver0.4 のままだった。コメントも「2回復」と書いており、両方が古かった。
            // ★<b>回復量に最大体力そのものを渡している。</b>MinionInstance.heal は
            // ダメージを減らす形なので、最大体力ぶん渡せば damage は必ず 0 になる
            // (0 未満にはならない)—— 「全快」に専用の器を増やす必要はない(裁定178)。
            case EXCALIBUR -> {
                player.getMinionZone().stream()
                        .filter(m -> m.hasKeyword(Keyword.GUARD))
                        .forEach(m -> m.heal(m.getMaxHp()));
                room.addLog("【聖剣エクスカリバー】: 自分の【守護】ミニオンの体力が全回復しました");
            }
            case QUAKE_HAMMER -> resolveQuakeHammerAttack(room, player, opponent);
            default -> {
            }
        }

        // 風護の杖(QTE-M-WIND-28): 攻撃時、自分のミニオンを1体選んでそのミニオンの体力+1・守護付与。
        // 既存7件と違い、風文明では割り込み選択(a9)を経由させる方針のため、上のswitchには足さない
        // (0体なら不発・1体なら自動決定・2体以上ならプレイヤーが選ぶ。降臨の伝道師と同じ流儀)
        if (GUARD_STAFF.equals(weapon.id())) {
            resolveGuardStaffAttack(room, player);
        }
    }

    /**
     * 地響きの槌(QTE-M-EARTH-28)の攻撃時効果(★Batch 59・区分5)。
     *
     * <pre>
     *   旧: 「攻撃時相手のミニオン全てに5ダメージ。」
     *   新: 「攻撃時ミニオン全てに2ダメージ与える。この効果で破壊したミニオンの数
     *        山札の上から裏向きでマナを1枚増やす。」
     * </pre>
     *
     * ★<b>「相手の」の限定が消えた。</b>マスター裁定274 により本文どおり
     * <b>自分のミニオンも巻き込む</b>。巻き込んで破壊した分もマナ加速の数に含める ——
     * 本文が「この効果で破壊したミニオンの数」としか書いておらず、
     * どちらの側かを問うていないためである(裁定211: 書かれていない限定を足さない)。
     *
     * <p>★<b>破壊の数え方は「ダメージの前後で場から消えた数」ではない。</b>
     * ダメージを与えてから破壊判定を回し、<b>その判定で実際に場を離れた数</b>を数える。
     * 前後の差で数えると、ダメージとは無関係に場を離れたミニオン
     * (被ダメージ誘発《獄門の裁定者》の巻き添えなど)まで数に入ってしまう。
     *
     * <p>★<b>ダメージの適用と破壊判定を1体ずつ回す</b>(既存の全体ダメージ効果
     * 《墓穴の呪い》《天変地異のタイタン》と同じ形。{@code GameActions.damageMinion} が
     * 適用と判定を1組で持っているためである)。巻き込む順は自分→相手だが、
     * <b>順序に意味は無い</b> —— どちらから数えても破壊された総数は変わらない。
     *
     * <p>★<b>解決の途中で場に出たミニオンは巻き込まない。</b>
     * 対象はダメージを与え始める前の盤面のスナップショットで確定する。
     */
    private void resolveQuakeHammerAttack(GameRoom room, PlayerState player, PlayerState opponent) {
        room.addLog("【地響きの槌】: ミニオン全てに2ダメージ");
        int destroyed = 0;
        for (PlayerState side : List.of(player, opponent)) {
            for (MinionInstance minion : List.copyOf(side.getMinionZone())) {
                if (!side.getMinionZone().contains(minion)) {
                    continue; // 直前の解決で既に場を離れていた
                }
                actions.damageMinion(room, side, minion, 2);
                if (!side.getMinionZone().contains(minion)) {
                    destroyed++;
                }
            }
        }
        if (destroyed == 0) {
            return;
        }
        for (int i = 0; i < destroyed; i++) {
            if (!actions.placeTopOfDeckInManaFaceDown(room, player)) {
                break; // 山札切れ・マナ上限。置けなかった分は諦める(既存のマナ加速と同じ)
            }
        }
        room.addLog("【地響きの槌】: %d体を破壊し、山札の上から裏向きでマナを増やしました"
                .formatted(destroyed));
    }

    /**
     * 風護の杖の攻撃時効果を解決する。候補が0体なら不発・1体なら自動決定・
     * 2体以上ならプレイヤーの選択を待つ(a9)。選択結果の解決本体(体力+1・守護付与)は
     * CardEffectRegistry.resolveChoice の GUARD_STAFF_TARGET 分岐に別途持たせている
     * (GameServiceとCardEffectRegistryは相互に依存できないため、3行程度の小さな処理を
     * 両側に置く形にした。処理内容は完全に同一)。
     */
    private void resolveGuardStaffAttack(GameRoom room, PlayerState player) {
        List<MinionInstance> zone = player.getMinionZone();
        if (zone.isEmpty()) {
            return;
        }
        if (zone.size() == 1) {
            applyGuardStaffBuff(room, zone.get(0));
            return;
        }
        actions.requestChoice(room, player, PendingChoice.one(
                PendingChoice.Kind.MINION,
                zone.stream().map(MinionInstance::getInstanceId).toList(),
                ResumePoint.GUARD_STAFF_TARGET,
                "【風護の杖】: 体力+1・守護を与えるミニオンを選んでください"));
    }

    /**
     * 死霊の収鎌の攻撃時効果(★Batch 64)。墓地から手札に戻す1枚を本人が選ぶ(裁定299)。
     *
     * ★63 までは自動選択(最後に墓地へ置かれたカード)だった。
     * 本文は「自分の墓地からカードを1枚手札に戻す」としか書いておらず、
     * 「最後に置かれたもの」は実装が足した規則である。
     * ★<b>戦闘の保留({@code GameState.pendingAttack})には乗らない。</b>
     * ウェポンの攻撃時効果はダメージの<b>後</b>に走るので、保留すべき戦闘がもう残っていない。
     * ★候補が1枚なら選ぶ余地が無いので問い合わせない(風護の杖と同じ流儀)。
     */
    private void requestWraithScytheRecover(GameRoom room, PlayerState player) {
        List<String> trash = player.getTrash();
        if (trash.isEmpty()) {
            return;
        }
        if (trash.size() == 1) {
            actions.returnFromTrashToHand(room, player, trash.get(0));
            return;
        }
        List<String> positions = new ArrayList<>();
        for (int i = 0; i < trash.size(); i++) {
            positions.add(String.valueOf(i));
        }
        actions.requestChoice(room, player, PendingChoice.one(
                PendingChoice.Kind.TRASH, positions,
                ResumePoint.WRAITH_SCYTHE_RECOVER,
                "【死霊の収鎌】: 手札に戻すカードを墓地から1枚選んでください"));
    }

    /** 風護の杖の効果本体(体力+1・守護付与)。GameServiceに置くのはminionZoneへの直接アクセスのため */
    private void applyGuardStaffBuff(GameRoom room, MinionInstance minion) {
        minion.addModifier(new StatModifier(StatModifier.Stat.HP, StatModifier.Operation.ADD, 1,
                StatModifier.Duration.PERMANENT, GUARD_STAFF));
        minion.grantKeyword(Keyword.GUARD);
        room.addLog("【風護の杖】: 【%s】の体力が+1され、【守護】を得ました".formatted(minion.getMaster().name()));
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
        effects.runEffect(player.getLeader().id(),
                contextOf(room, state, player, null, resolved), spec.effect());
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
        effects.runEffect(minion.getMaster().id(),
                contextOf(room, state, player, minion, resolved), spec.effect());
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
        // 場全体の攻撃宣言の回数(★Batch 50。英術・バンユーの「合計1回まで」が読む)。
        // 個体の攻撃回数(countAttack)とは数えている量が違うため、別に数える
        player.setMinionAttacksUsedThisTurn(player.getMinionAttacksUsedThisTurn() + 1);
        room.addLog("【%s】が攻撃を宣言".formatted(attacker.getMaster().name()));
        EffectContext attackCtx = contextOf(room, state, player, attacker, null);
        effects.fire(TriggerType.ON_ATTACK, attacker, attackCtx);
        // 装備中のウェポンが「自分のミニオンが攻撃した」に反応する(魔剣レーヴァテイン)。
        // 攻撃宣言ごとに発火するため、2回攻撃するミニオンでは2回発動する(発注者確認済み)
        effects.fireAllyMinionEvent(TriggerType.ON_ALLY_MINION_ATTACK, attackCtx);

        // 攻撃時効果でゲームが決着した場合(山札切れ・自傷でのLP0等)は戦闘を解決しない
        if (state.getStatus() == GameStatus.FINISHED) {
            return;
        }

        // ★Batch 51: 攻撃時効果が割り込み選択を作ったら、戦闘の解決を選択の後まで保留する。
        //
        // 51 より前は、選択待ちのまま戦闘まで解決していた(地砕きの突撃兵は「マナを手札に戻す」
        // だけで攻撃者が場を離れないため、その順序でも辻褄が合っていた)。
        // 素手喧嘩(QTE-M-EARTH-35)は攻撃時に<b>攻撃者自身がマナへ移る</b>ため、
        // 選択の答えが「戦闘を行うかどうか」を決める(マスター裁定213)。
        // したがって、答えを待たずに戦闘へ進んではならない。
        //
        // ★この保留は素手喧嘩専用の分岐ではなく、攻撃時の割り込み全体に効く構造である。
        //   地砕きの突撃兵も 51 からは「マナを選んでから戦闘」に変わる —— 挙動の変更だが、
        //   選んだ結果が戦闘に影響しないカードなので、遊びの上では順序が見えるだけである。
        if (player.getPendingChoice() != null) {
            state.setPendingAttack(new PendingAttack(playerId, attackerInstanceId,
                    targetInstanceId, targetIsLeader));
            return;
        }

        resolveCombat(room, state, player, opponent, attacker, target, targetIsLeader);
    }

    /**
     * 戦闘の解決(★Batch 51 で attack から切り出した)。
     *
     * 攻撃宣言と攻撃時効果の発火までを {@link #attack} が行い、そこから先をここが担う。
     * 分けたのは、攻撃時の割り込み選択を挟んだ場合に<b>同じ処理を後から呼び直す</b>必要が
     * 生じたためである({@link #resumePendingAttack})。
     */
    private void resolveCombat(GameRoom room, GameState state, PlayerState player,
            PlayerState opponent, MinionInstance attacker, MinionInstance target,
            boolean targetIsLeader) {
        // ★Batch 51: 攻撃宣言のあとに攻撃者が場を離れていたら、戦闘は起きない。
        // 素手喧嘩が自分をマナへ置いた場合がこれにあたる(マスター裁定213)。
        // 「場に居ないミニオンの攻撃力で殴る」ことを構造として封じるため、
        // 素手喧嘩の分岐ではなく場に居るかどうかで判定する
        if (!player.getMinionZone().contains(attacker)) {
            room.addLog("【%s】が場を離れたため、戦闘は行われませんでした"
                    .formatted(attacker.getMaster().name()));
            return;
        }
        // 攻撃対象のミニオンが場を離れていた場合も同様(攻撃時効果で対象が消えることがある)
        if (!targetIsLeader && !opponent.getMinionZone().contains(target)) {
            room.addLog("攻撃対象が場を離れたため、戦闘は行われませんでした");
            return;
        }

        if (targetIsLeader) {
            // 大地の守護盾(土文明): リーダーへの攻撃をウェポンの破壊で肩代わりする(ダメージ無効)。
            // ★光霊・モアニール(★Batch 50)も同じ位置で肩代わりする(leaderAttack と同じ順序)
            if (!actions.tryInterceptLeaderAttackWithShield(room, opponent)
                    && !actions.tryReplaceLeaderDamageWithGuardian(room, opponent)) {
                int damage = stats.effectiveAttack(state, player, attacker);
                opponent.setLp(opponent.getLp() - damage);
                room.addLog("リーダーに%dダメージ(残りLP %d)".formatted(damage, opponent.getLp()));
                if (opponent.getLp() <= 0) {
                    actions.finish(room, player);
                }
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
            // 戦闘での撃破トリガー(土文明のタイタン・ウォリアー)。破壊判定がすべて終わった後、
            // 「相手が場を離れ、かつ自分が場に残っている」側にのみ ON_COMBAT_KILL を発火する。
            // 攻撃側・防御側のどちらが撃破した場合も対称に扱う(設計判断31: トリガーには向きがある)
            boolean targetGone = !opponent.getMinionZone().contains(target);
            boolean attackerGone = !player.getMinionZone().contains(attacker);
            if (targetGone && !attackerGone) {
                effects.fire(TriggerType.ON_COMBAT_KILL, attacker,
                        contextOf(room, state, player, attacker, null));
            }
            if (attackerGone && !targetGone) {
                effects.fire(TriggerType.ON_COMBAT_KILL, target,
                        contextOf(room, state, opponent, target, null));
            }
        }
    }

    /**
     * 保留していた戦闘の再開(★Batch 51)。割り込み選択が解決するたびに呼ばれる。
     *
     * <b>まだ再開しない条件が3つある。</b>
     * (1) 保留された戦闘がそもそも無い。
     * (2) 割り込みが連鎖しており、どちらかのプレイヤーがまだ選択待ちである
     *     (素手喧嘩は「マナに置くか」→「マナから出すミニオン」の2段になる)。
     * (3) 既に決着している。
     *
     * 攻撃者・対象は instanceId から引き直す。見つからなければ場を離れているということであり、
     * {@link #resolveCombat} の先頭が「戦闘は起きない」と判断する。
     */
    private void resumePendingAttack(GameRoom room, GameState state) {
        PendingAttack pending = state.getPendingAttack();
        if (pending == null) {
            return;
        }
        if (state.getPlayer1().getPendingChoice() != null
                || state.getPlayer2().getPendingChoice() != null) {
            return; // 連鎖した割り込みの解決を待つ
        }
        state.setPendingAttack(null);
        if (state.getStatus() != GameStatus.PLAYING) {
            return;
        }
        PlayerState player = state.playerOf(pending.attackerPlayerId());
        PlayerState opponent = state.opponentOf(pending.attackerPlayerId());
        MinionInstance attacker = player.getMinionZone().stream()
                .filter(m -> m.getInstanceId().equals(pending.attackerInstanceId()))
                .findFirst().orElse(null);
        if (attacker == null) {
            room.addLog("攻撃したミニオンが場を離れたため、戦闘は行われませんでした");
            return;
        }
        MinionInstance target = null;
        if (!pending.targetIsLeader()) {
            target = opponent.getMinionZone().stream()
                    .filter(m -> m.getInstanceId().equals(pending.targetInstanceId()))
                    .findFirst().orElse(null);
            if (target == null) {
                room.addLog("攻撃対象が場を離れたため、戦闘は行われませんでした");
                return;
            }
        }
        resolveCombat(room, state, player, opponent, attacker, target, pending.targetIsLeader());
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
        // ★★★Batch 74(裁定334): 解決中に追加で唱えられたスペル(《回帰の風穴》の2回目)を数える。
        // 効果側が pendingExtraSpellCasts に書き、ここで消費する ——
        // pendingSpellDisposition とまったく同じ受け渡しであり、カードIDはエンジンに現れない。
        // ★<b>1回目を数え終わってから数える</b>ので、2回目の ON_CARD_USED は
        //   1回目を含んだカウンタを見る(裁定1 の順序が保たれる)。
        int extra = player.getPendingExtraSpellCasts();
        player.setPendingExtraSpellCasts(0);
        for (int i = 0; i < extra; i++) {
            player.setCardsUsedThisTurn(player.getCardsUsedThisTurn() + 1);
            player.setSpellsCastThisTurn(player.getSpellsCastThisTurn() + 1);
            effects.fireCardUsed(contextOf(room, state, player, null, null));
        }
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
                        // ★Batch 68: 潜伏の判定も TargetCandidates 1箇所にある(候補の列挙側と共有)
                        if (candidates.isStealthBlocked(player, req, tm.owner(), tm.minion())) {
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
                        // ★Batch 57: 要求が指定した側と一致しているかを<b>サーバが</b>検証する。
                        // ここが抜けていたため、Kind.WEAPON だけは Requirement.side() が
                        // クライアントへの助言にしかなっていなかった(TargetSpec の Javadoc が謳う
                        // 「サーバはこれに照らして選択の正当性を検証する」が守られていない状態)。
                        // 実害として、Side.OPPONENT の《天界の守護神 ゾディアック》で
                        // 自分のウェポンを破壊させることが細工したクライアントから可能だった。
                        // ★壊し検証(tools/batch56_break_check.py ケース1)が見つけた穴である。
                        boolean sideMismatch = switch (req.side()) {
                            case SELF -> target != player;
                            case OPPONENT -> target == player;
                            case ANY -> false;
                        };
                        if (sideMismatch) {
                            throw new IllegalArgumentException("このカードでは選べない側のウェポンです");
                        }
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
    }

    private void requireCount(TargetSpec.Requirement req, int actual) {
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
     *
     * <p>★★<b>Batch 68: 規則そのものは {@link TargetCandidates} へ移した。</b>
     * 裁定282 により【召喚時】【登場時】の対象が割り込みへ移り、
     * 「今の盤面でどれが選べるか」を<b>列挙する側</b>が同じ規則を要るようになったためである。
     * ここに規則を残したまま列挙側にも書けば、<b>同じ絞り込みが2箇所に生まれる</b>(裁定130)。
     *
     * <p>★<b>断る文言もあちらにある。</b>検証はその戻り値を例外にするだけであり、
     * 文言で照合している試験({@code Batch67TextImplTest} など)はそのまま通る。
     */
    private void checkFilter(GameState state, PlayerState player, TargetSpec.Requirement req,
            CardMaster master, MinionInstance minion) {
        String reason = candidates.rejectReason(state, player, req, master, minion);
        if (reason != null) {
            throw new IllegalArgumentException(reason);
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

    /** 指定なしの支払い(自動)。★順序の正は {@link ManaPayment} である(★Batch 70) */
    private void payCost(PlayerState player, int cost) {
        payCost(player, cost, List.of());
    }

    /**
     * 通常のコスト(MP)を支払う。
     *
     * <h2>★★★Batch 70: 「どれを払うか」という概念が入った</h2>
     *
     * 69 まではここが<b>マナゾーンの先頭から未タップのものを順にタップする</b>だけで、
     * 表裏も一時マナも1つも見ていなかった。裁定315・316 が順序を決め、
     * 裁定319 が<b>人が選べる道</b>を足したので、この1本が2つの入口を受ける形になった。
     *
     * <ul>
     *   <li>{@code manaIndexes} が空 …… 自動。{@link ManaPayment#normalOrder} の先頭から払う
     *       (ドラッグでのプレイ・効果からの支払い・従来の呼び出しすべて)</li>
     *   <li>{@code manaIndexes} に指定あり …… その位置のマナをタップする(クリックからのプレイ)</li>
     * </ul>
     *
     * ★<b>順序の規則はここに書かない。</b>{@link ManaPayment} が唯一の正であり、
     * クライアントの強調表示も同じ順序をビュー経由で読む(裁定130)。
     *
     * @param manaIndexes 払うマナの位置。空なら自動
     */
    private void payCost(PlayerState player, int cost, List<Integer> manaIndexes) {
        if (player.getAvailableMp() < cost) {
            throw new IllegalStateException("MPが足りません(必要%d/使用可能%d)"
                    .formatted(cost, player.getAvailableMp()));
        }
        List<Integer> chosen = manaIndexes == null ? List.of() : manaIndexes;
        List<Integer> indexes = chosen.isEmpty()
                ? ManaPayment.normalOrder(player).subList(0, cost)
                : validateManaSelection(player, cost, chosen);
        for (int index : indexes) {
            player.getManaZone().get(index).tap();
        }
    }

    /**
     * 人が選んだ支払いを検証する(★Batch 70。裁定319)。
     *
     * ★<b>クライアントが送ってきた位置をそのまま信じない</b>(設計判断27)。
     * 枚数・範囲・重複・タップ済みのすべてをここで弾く ——
     * 通っていない位置でタップすると、盤面のマナと支払いが静かにずれる。
     */
    private List<Integer> validateManaSelection(PlayerState player, int cost, List<Integer> manaIndexes) {
        if (manaIndexes.size() != cost) {
            throw new IllegalArgumentException("払うマナは%d枚を指定してください(指定%d枚)"
                    .formatted(cost, manaIndexes.size()));
        }
        Set<Integer> seen = new HashSet<>();
        for (int index : manaIndexes) {
            if (index < 0 || index >= player.getManaZone().size() || !seen.add(index)) {
                throw new IllegalArgumentException("不正なマナの指定です");
            }
            if (player.getManaZone().get(index).isTapped()) {
                throw new IllegalArgumentException("タップ済みのマナは支払いに使えません");
            }
        }
        return manaIndexes;
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
        // ★Batch 51: 相手が選択待ちのあいだも手番の操作を止める(マスター裁定214 の対)。
        // 相手ターンにも本人へ問い合わせるようになった以上、答えが返る前に手番の側が
        // 盤面を動かせてしまうと、候補が指す先(マナゾーンの位置・instanceId)が
        // 変わってしまう。「選択待ちの間は誰も盤面を動かさない」を両側で守る
        if (state.opponentOf(playerId).getPendingChoice() != null) {
            throw new IllegalStateException("相手が選択中です。解決を待ってください");
        }
    }

    /**
     * ミニオンを場に出せない状態なら拒否する(★Batch 53。《英霊・コレキ》)。
     *
     * ★<b>召喚の入口すべてが呼ぶ</b>(通常・進化・特殊召喚・禁忌・墓地からの召喚)。
     * 「場が満杯」の判定がそれぞれの入口に置かれているのと同じ形であり、
     * 判定そのものは {@link RuleGuards#minionEntryDenial} 1箇所にしかない。
     * ★効果による「出す」はここを通らない —— あちらは例外を投げずに
     * 「出せなかった」を返す({@code GameActions.isFieldEntryBlocked})。
     * 召喚は宣言そのものを弾き、効果は出せたぶんだけ出す、という違いである。
     */
    private void requireCanEnterField(GameState state, PlayerState player) {
        String denial = guards.minionEntryDenial(state, player);
        if (denial != null) {
            throw new IllegalStateException(denial);
        }
    }

    private void requirePhase(GameState state, TurnPhase expected) {
        if (state.getPhase() != expected) {
            throw new IllegalStateException("この操作は%sフェイズでのみ行えます(現在: %sフェイズ)"
                    .formatted(expected.getDisplayName(), state.getPhase().getDisplayName()));
        }
    }
}
