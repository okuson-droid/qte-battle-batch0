package com.example.qte.game;

/** ゲーム全体の進行状態。 */
public enum GameStatus {
    /** 対戦相手の入室待ち */
    WAITING,
    /** マリガン等のゲーム開始前処理中(Batch 1で使用) */
    SETUP,
    /** 対戦中 */
    PLAYING,
    /** 決着済み */
    FINISHED
}
