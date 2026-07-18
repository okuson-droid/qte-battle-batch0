package com.example.qte.game;

import lombok.Getter;
import lombok.Setter;

/**
 * 1試合の全状態の頂点。ここから両プレイヤーの全ゾーンに到達できる。
 *
 * このクラスとその配下(PlayerState, MinionInstance...)は意図的にSpringのビーンに
 * しない。ビーンはアプリケーションと同じ寿命を持つ共有部品であり、試合ごとに
 * 生まれて消えるデータとは寿命が一致しないため(batch0-design-notes.md 2章)。
 * DBにも保存しない。試合中の状態は揮発してよい(同 1章)。
 */
@Getter
public class GameState {

    private final String roomId;
    private final PlayerState player1;
    private final PlayerState player2;

    @Setter
    private GameStatus status = GameStatus.WAITING;

    /** ターンプレイヤーのplayerId */
    @Setter
    private String turnPlayerId;

    /** 通しのターン番号(1始まり)。召喚酔い判定はミニオンのenteredTurnとの比較で行う */
    @Setter
    private int turnNumber = 0;

    @Setter
    private TurnPhase phase = TurnPhase.DRAW;

    /** 決着時の勝者。FINISHEDになるまでnull */
    @Setter
    private String winnerPlayerId;

    /** 先攻プレイヤー(先後選択後に確定)。マリガン完了後の第1ターン開始に使う */
    @Setter
    private String firstPlayerId;

    public GameState(String roomId, PlayerState player1, PlayerState player2) {
        this.roomId = roomId;
        this.player1 = player1;
        this.player2 = player2;
    }

    public PlayerState playerOf(String playerId) {
        if (player1.getPlayerId().equals(playerId)) {
            return player1;
        }
        if (player2.getPlayerId().equals(playerId)) {
            return player2;
        }
        throw new IllegalArgumentException("この試合に存在しないプレイヤー: " + playerId);
    }

    public PlayerState opponentOf(String playerId) {
        return playerOf(playerId) == player1 ? player2 : player1;
    }

    public PlayerState turnPlayer() {
        return playerOf(turnPlayerId);
    }
}
