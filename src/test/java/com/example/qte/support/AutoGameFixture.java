package com.example.qte.support;

import java.util.List;

import com.example.qte.effect.StatCalculator;
import com.example.qte.game.GameState;
import com.example.qte.game.GameStatus;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.game.TurnPhase;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.room.GameRoom;

/**
 * 通常モード(自動モード)の対戦を、テストの中で組み立てるための足場(★Batch 48 で新設)。
 *
 * <h2>なぜ新しく要るのか</h2>
 *
 * 通常モードのロジックには、これまで<b>1件もゲームプレイの試験が無かった</b>
 * (`notes/ver11-migration-plan.md` 0-4)。既存の JUnit はカードマスタの読み込み・
 * キーワード抽出・デッキ構築の検証・手動モードの操作に限られており、
 * 「このカードを召喚したら盤面がこうなる」を測るものが1つも無い。
 *
 * <p>P2(Batch 48〜53)は効果を145枚作るフェーズであり、ここから先は
 * <b>効果が書いてあるとおりに動くか</b>が主題になる。読み込みの試験では何も守れない。
 *
 * <h2>本物の入口を通す</h2>
 *
 * この足場が用意するのは<b>盤面の初期状態だけ</b>である。効果の発動は
 * {@code GameService.playCard} / {@code specialSummon} / {@code endTurn} のような
 * 実際の入口から起こす。トリガーを {@code CardEffectRegistry.fire} で直接叩くと、
 * 「発火する場所が正しいか」という<b>いちばん壊れやすいところ</b>が試験の外に出てしまう
 * (裁定: イベントは実際の入口から起こす)。
 *
 * <p>WebSocket も部屋の管理も通さない。{@link GameRoom} は状態の入れ物とログしか
 * 持たないため、直接 new して {@code setGameState} すれば十分である。
 */
public final class AutoGameFixture {

    private final CardMasterRepository cards;
    private final GameRoom room;
    private final GameState state;

    /**
     * 両者のリーダーを指定して、メインフェイズの途中から始まる盤面を作る。
     * ターン番号は 5(召喚酔いの判定に使うため、0 や 1 だと「出したターン」と
     * 紛れやすい)。ターンプレイヤーは me である。
     */
    public AutoGameFixture(CardMasterRepository cards, String myLeaderId, String opponentLeaderId) {
        this.cards = cards;
        PlayerState me = new PlayerState("me", "わたし", cards.findById(myLeaderId));
        PlayerState you = new PlayerState("you", "あいて", cards.findById(opponentLeaderId));
        // ★Batch 66: 部屋は属性(部屋名・観戦・鍵)を持つようになった。
        //   足場が組む盤面は受付を通らないので、名前は「試験」で足りる
        this.room = new GameRoom("test-room",
                new com.example.qte.room.GameRoomOptions("試験", true, false));
        this.state = new GameState("test-room", me, you);
        room.setGameState(state);
        state.setStatus(GameStatus.PLAYING);
        state.setFirstPlayerId("me");
        state.setTurnPlayerId("me");
        state.setTurnNumber(5);
        state.setPhase(TurnPhase.MAIN);
    }

    public GameRoom room() {
        return room;
    }

    public GameState state() {
        return state;
    }

    public PlayerState me() {
        return state.getPlayer1();
    }

    public PlayerState you() {
        return state.getPlayer2();
    }

    /** 手札にカードを1枚加え、その位置(手札の末尾)を返す */
    public int giveHand(PlayerState player, String cardId) {
        player.getHand().add(cardId);
        return player.getHand().size() - 1;
    }

    /** アンタップ状態・表向きのマナを n 枚置く(コストの支払い用) */
    public void giveMana(PlayerState player, int count) {
        for (int i = 0; i < count; i++) {
            player.getManaZone().add(new ManaCard("QTE-M-WIND-2", false));
        }
    }

    /**
     * 場にミニオンを1体置く。{@code enteredTurn} は現在のターン番号より前にするため
     * 1 を入れる(召喚酔いを持たない状態で置きたいときの既定)。
     * 効果による登場ではないので、登場時トリガーは発動しない。
     */
    public MinionInstance putOnField(PlayerState player, String cardId) {
        MinionInstance minion = new MinionInstance(cards.findById(cardId), 1);
        // ★Batch 58: 本物の入口(GameActions.newFieldMinion)が場に出るミニオンへ写している
        // 常在の値を、この足場も同じように写す。写さないと《剛火の将》の常在(【速攻】持ちのHP+2)が
        // 「召喚したときだけ効いて、最初から置いたミニオンには効かない」という
        // 盤面には存在しない状態を試験が作ってしまう
        minion.setRushHpBonus(new StatCalculator(cards).rushHpBonus(state));
        player.getMinionZone().add(minion);
        return minion;
    }

    /** 山札の上に順にカードを積む(先に渡したものが先に引かれる) */
    public void stackDeck(PlayerState player, String... cardIds) {
        for (int i = cardIds.length - 1; i >= 0; i--) {
            player.getDeck().addFirst(cardIds[i]);
        }
    }

    /** 山札切れ敗北を避けるため、当たり障りのないカードで山札を埋める */
    public void fillDeck(PlayerState player, int count) {
        for (int i = 0; i < count; i++) {
            player.getDeck().addLast("QTE-M-WIND-2");
        }
    }

    /** 場のミニオンのカードIDの一覧(並び順のまま) */
    public List<String> fieldIds(PlayerState player) {
        return player.getMinionZone().stream().map(m -> m.getMaster().id()).toList();
    }

    /** カードマスタの取得(名前や印刷値をテストから読むため) */
    public CardMaster card(String cardId) {
        return cards.findById(cardId);
    }

    // ===================================================================
    // ★★★Batch 68: 割り込みの選択に答える
    // ===================================================================

    /**
     * 待っている問い合わせに<b>候補の値で</b>答える(★Batch 68)。
     *
     * <h2>なぜ「値」で答えるのか</h2>
     *
     * {@code GameService.resolveChoice} が受け取るのは<b>候補一覧の添字</b>である。
     * 試験がその添字を直接書くと、候補の並び順が変わっただけで
     * <b>別のものを選んでいるのに緑のまま</b>になる ——
     * 「試験が値を実装から読む」の裏返しの事故である(裁定298)。
     *
     * <p>そこで試験は {@code instanceId} や手札の位置といった<b>意味のある値</b>を渡し、
     * 添字への変換はここで行う。候補に無い値を渡したら、その場で落とす。
     *
     * <p>★Batch 68 で【召喚時】【登場時】の対象が宣言時から割り込みへ移った(裁定282)ため、
     * 「召喚して対象を選ぶ」は<b>2手</b>になった。この足場はその2手目を書くためのものである。
     */
    public void answerChoice(com.example.qte.game.GameService game, String playerId,
            String... candidateValues) {
        PlayerState player = state.getPlayer1().getPlayerId().equals(playerId)
                ? state.getPlayer1() : state.getPlayer2();
        var pending = player.getPendingChoice();
        if (pending == null) {
            throw new IllegalStateException(
                    "問い合わせが待っていないのに答えようとした(%s)".formatted(playerId));
        }
        List<Integer> indexes = new java.util.ArrayList<>();
        for (String value : candidateValues) {
            int index = pending.candidates().indexOf(value);
            if (index < 0) {
                throw new IllegalStateException("候補に無い値を選ぼうとした: %s(候補は %s)"
                        .formatted(value, pending.candidates()));
            }
            indexes.add(index);
        }
        game.resolveChoice(room, playerId, indexes);
    }

    /** 待っている問い合わせに「何も選ばない」で答える(★Batch 68) */
    public void answerChoiceNone(com.example.qte.game.GameService game, String playerId) {
        game.resolveChoice(room, playerId, List.of());
    }

    /** 手札の中でそのカードIDが最初に現れる位置(割り込みの候補は手札の位置である。★Batch 68) */
    public String handPosition(PlayerState player, String cardId) {
        return String.valueOf(player.getHand().indexOf(cardId));
    }
}
