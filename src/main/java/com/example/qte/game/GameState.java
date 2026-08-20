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

    /**
     * このターンに破壊されたミニオンの数(★Batch 48。両者の合計)。
     * 天翔ケル霊鬼・シュテンの【特殊召喚】条件「このターンミニオンが8体以上破壊されていれば」が読む。
     *
     * <b>プレイヤー単位ではなく試合単位で持つ理由。</b> カードテキストが「自分の」と
     * 書いていない誘発・条件は両者を見る(裁定156(2))。どちらの場で破壊されたかを
     * 区別しないので、片側の {@code PlayerState} に置くと必ず両方を足す処理が要る。
     * 数える場所は {@code GameActions.leaveFieldByDestruction} の1箇所であり、
     * 破壊された後の行き先(墓地・消滅・還元)は問わない —— 「破壊された」事実だけを数える。
     * バウンス・マナ送りのように破壊を経由しない移動は数えない(そもそもここを通らない)。
     *
     * リセットは {@code GameService.beginTurn} で行う。
     * ターン内カウンタをプレイヤー単位で持つもの({@code PlayerState.startTurnReset})とは
     * リセットの主体が違うため、置き場所も分けている。
     */
    @Setter
    private int minionsDestroyedThisTurn = 0;

    /**
     * ターンエンド処理が「次のターンの開始」を保留しているか(a9)。
     *
     * ターンエンド時効果(詠唱の疾風騎士)がプレイヤーへの問い合わせで中断した場合、
     * その選択が解決するまで相手のターンを始めてはならない。endTurn は後始末までを行って
     * このフラグを立て、選択が解決した後に advanceTurn が相手のターンを始める。
     * 「ターンの終了」と「次のターンの開始」は総合ルール上も別の事象であるため、
     * この分割は構造としてもむしろ正しい。
     */
    @Setter
    private boolean turnHandoffPending = false;

    /** turnHandoffPending が立っているとき、次に手番を渡す相手のplayerId */
    @Setter
    private String pendingNextPlayerId;

    /**
     * 攻撃時効果が割り込み選択を作ったために、解決を保留している戦闘(★Batch 51)。
     *
     * {@link #turnHandoffPending} と同じ性質の保留である —— 割り込みの答えが出るまで、
     * その先の事象(あちらは次のターンの開始、こちらは戦闘の解決)を進めてはならない。
     * 選択が解決した時点で {@code GameService.resumePendingAttack} が戦闘を再開する。
     * 保留中でなければ null。
     */
    @Setter
    private PendingAttack pendingAttack;

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
