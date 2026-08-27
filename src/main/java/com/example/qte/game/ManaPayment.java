package com.example.qte.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * マナを「どの順で払うか」という規則(★Batch 70。裁定315・316・317)。
 *
 * <h2>なぜ独立した器にしたか</h2>
 *
 * 69 までの {@code GameService.payCost} は
 * 「マナゾーンの先頭から、未タップのものを順にタップする」だけであり、
 * <b>表裏も一時マナも1つも見ていなかった</b>。裁定315〜317 はそこに順序を持ち込む ——
 * 通常のプレイは <b>一時マナ → 裏向き → 表向き</b>、
 * 禁忌は <b>表向き → 裏向き</b> である。
 *
 * <p>★<b>同じ順序をクライアントも要る。</b>裁定318・321 により、
 * カードをドラッグしている最中に「これから払われるマナ」を強調表示するからである。
 * ここでクライアントに規則を書き写すと、<b>サーバの払い方が変わった日に
 * 強調表示だけが黙って嘘になる</b>(67 の教訓・写し)。
 * したがってクライアントには<b>順序そのもの</b>を送り、
 * 向こうは「先頭から n 枚」を取るだけにしてある(規則を1つも持たない・裁定234)。
 *
 * <p>★<b>この器が唯一の正である</b>(裁定130)。支払う側({@code GameService.payCost} /
 * {@code payTabooCost})とビューに載せる側({@code GameViewBuilder})が同じここを呼ぶ。
 * 一致は {@code Batch70ManaPaymentTest} が「実際に払ったマナが順序の先頭 n 枚である」
 * という形で見張る —— <b>期待する順序を書き写した試験にしない</b>(裁定41 の筋)。
 *
 * <h2>順序の理由(裁定315〜317)</h2>
 *
 * <ul>
 *   <li><b>一時マナが最優先</b>(裁定316)…… 【ピュア・エレメント】の一時マナは
 *       ターン終了で消える。先に使わなければ捨てるのと同じである。</li>
 *   <li><b>次が裏向き</b>(裁定315)…… <b>表向きマナは禁忌の弾でもある</b>。
 *       禁忌の支払いは表向きなら「裏返す」で済むが、裏向きしか無ければ
 *       <b>墓地へ送る</b>ことになりマナが永久に減る。したがって表向きを温存する。</li>
 *   <li><b>禁忌は逆で表向きから</b>(裁定317)…… 同じ理由の裏返しである。
 *       裏向きを使えばマナが減るので、減らずに済む表向きから使う。
 *       ★裏向きを使わざるをえないときは<b>警告を出す</b>(取り返しがつかない支払いである)。</li>
 * </ul>
 */
public final class ManaPayment {

    private ManaPayment() {
    }

    /**
     * 通常のコスト(MP)の支払いに充てるマナを、払う順に並べた位置の一覧(裁定315・316)。
     *
     * <p>候補は<b>未タップのマナだけ</b>である(支払いはタップだからである)。
     * 並びは 一時マナ → 裏向き → 表向き で、同順位はマナゾーンの並び順を保つ。
     *
     * @return マナゾーン内の位置(0起点)。払える枚数だけ入る
     */
    public static List<Integer> normalOrder(PlayerState player) {
        return order(player, m -> !m.isTapped(), ManaPayment::normalRank);
    }

    /**
     * 禁忌コストの支払いに充てるマナを、払う順に並べた位置の一覧(裁定317)。
     *
     * <p>候補は<b>一時マナ以外のすべて</b>である ——
     * 禁忌の支払いはタップではなく「裏返す / 墓地へ送る」なので、
     * <b>タップ済みのマナも使える</b>(69 までの {@code validateTabooCost} も同じ扱いである)。
     * 並びは 表向き → 裏向き で、同順位はマナゾーンの並び順を保つ。
     *
     * @return マナゾーン内の位置(0起点)。払える枚数だけ入る
     */
    public static List<Integer> tabooOrder(PlayerState player) {
        return order(player, m -> !m.isTemporary(), ManaPayment::tabooRank);
    }

    /** 通常の支払いの優先度。小さいほど先に払う */
    private static int normalRank(ManaCard mana) {
        if (mana.isTemporary()) {
            return 0;   // 裁定316: 期限付きなので先に使う
        }
        return mana.isFaceUp() ? 2 : 1;   // 裁定315: 表向きは禁忌の弾として温存する
    }

    /** 禁忌の支払いの優先度。小さいほど先に払う */
    private static int tabooRank(ManaCard mana) {
        return mana.isFaceUp() ? 0 : 1;   // 裁定317: 裏向きは墓地送りになるので後回し
    }

    /**
     * 位置の一覧を作る共通部分。
     * ★<b>安定ソートである</b>(List.sort の保証)。同順位のときマナゾーンの並び順が残るので、
     * 「同じ盤面なら毎回同じ順で払う」ことが保証される —— 強調表示と実際の支払いが
     * 食い違わないために必要な性質である。
     */
    private static List<Integer> order(PlayerState player,
            java.util.function.Predicate<ManaCard> usable,
            java.util.function.ToIntFunction<ManaCard> rank) {
        List<ManaCard> zone = player.getManaZone();
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < zone.size(); i++) {
            if (usable.test(zone.get(i))) {
                indexes.add(i);
            }
        }
        indexes.sort(Comparator.comparingInt(i -> rank.applyAsInt(zone.get(i))));
        return List.copyOf(indexes);
    }
}
