package com.example.qte.manual;

/**
 * 手動モードのカード種別。
 *
 * 通常モードの {@link com.example.qte.master.CardType} と別に定義する理由は2つある。
 *
 * 1. 手動モードには EVOLUTION(進化ミニオン)が存在する。台帳側に足すと、
 *    通常モードの switch や判定がすべて「知らない種別」を持つことになる。
 * 2. 手動モードは通常モードと別系統である(設計書 2-1)。型を共有すると、
 *    片方の都合がもう片方の制約になる。
 */
public enum ManualCardType {
    LEADER, MINION, EVOLUTION, SPELL, WEAPON
}
