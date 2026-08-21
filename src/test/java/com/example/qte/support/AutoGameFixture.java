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
        this.room = new GameRoom("test-room");
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
}
