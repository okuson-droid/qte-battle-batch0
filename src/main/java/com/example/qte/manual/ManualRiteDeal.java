package com.example.qte.manual;

/**
 * 1つの席に対して起きた「カードの出入り」の員数(★Batch 38 設計書2章)。
 *
 * <p>初期ドローでは {@code back} が 0 で {@code drew} が 4 または 5 になる。
 * マリガンでは {@code back} が戻した枚数、{@code drew} が引き直せた枚数である。
 * 山札が尽きていれば {@code drew < back} になりうる —— 事実をそのまま運ぶ
 * (手動モードは「引けなかった」を判断で埋めない。Batch 23 1-3)。</p>
 *
 * <h2>★枚数しか持たない</h2>
 * どのカードかは持たない。演出に要らないからでもあるが、それ以上に
 * <b>持たせると非公開情報を運ぶ経路ができる</b>からである。
 * 21a の「届かないものは漏れない」を、儀式の側でも構造で守る。
 *
 * @param seat 対象の席
 * @param back 山札へ戻した枚数(初期ドローでは 0)
 * @param drew 山札から引いた枚数
 */
public record ManualRiteDeal(ManualSeatId seat, int back, int drew) {
}
