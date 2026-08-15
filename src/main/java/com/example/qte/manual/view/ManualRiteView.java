package com.example.qte.manual.view;

import com.example.qte.manual.ManualLogRite;

/**
 * 配信に載せる儀式1件(★Batch 38 設計書2章)。
 *
 * <h2>★{@code seq} の役目は Batch 35 とまったく同じである</h2>
 * この番号は、同じ配信に入っている {@link ManualLogView#seq()} と同じものである
 * (どちらも同じログ行から作る)。クライアントは
 * <b>番号が増えたときだけ</b>儀式を再生する。再接続の resync や、
 * 別の操作による配信で同じ儀式が載り続けても二度は出ない(裁定43 と同じ形)。
 *
 * <h2>★なぜ {@code start} の中ではなく配信の直下に置くのか</h2>
 * {@link ManualStartView} が運ぶのは「今どのフェーズか」「自分は今何を押せるか」であり、
 * <b>状態</b>である。儀式は状態ではなく<b>出来事</b>であり、しかも開始シーケンスの外(山札のシャッフル)でも起きる。
 * {@code declarations} とまったく同じ作られ方(配ったログ行の中から拾う)をする。
 * 同じ作られ方のものを同じ場所に置いておくと、ログの末尾60行と1周で作るという
 * 裁定42 の約束が1箇所で守れる。
 *
 * @param seq  元になったログ行の通し番号
 * @param rite そのとき起きた儀式。★中身の構造は {@link ManualLogRite} が唯一の正である
 */
public record ManualRiteView(int seq, ManualLogRite rite) {
}
