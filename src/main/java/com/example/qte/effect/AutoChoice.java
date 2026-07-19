package com.example.qte.effect;

import java.util.Comparator;
import java.util.List;

import com.example.qte.game.PlayerState;
import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;

/**
 * 効果の解決中に発生する選択を、プレイヤーに問い合わせずに自動で決める方針の集約。
 *
 * 現在の対象選択システムは「カードを使う瞬間に全ての選択を終える」前提で作られており、
 * 効果の解決を中断してクライアントに問い合わせる経路が存在しない。
 * しかし闇文明には、解決の途中でしか決められない選択を持つカードがある。
 *
 *   - 冥界神ハデス: 全体破壊を終えるまで蘇生候補が確定しない
 *   - 死者蘇生: 生贄を破壊した後でなければ蘇生候補が確定しない
 *   - 執念の暗殺者 / 不滅のネクロマンサー: 「〜してもよい」の可否を破壊の瞬間に問う
 *   - 死霊の収鎌: 攻撃の最中に墓地から1枚選ぶ
 *
 * これらを「割り込み選択システム」で正しく解くと、効果の解決を中断・再開できる仕組み
 * (保留中の選択と継続の保存、WebSocketでの往復、再開時の状態検証)が必要になり、
 * 影響範囲が解決経路全体に及ぶ。そこで暫定方針として自動解決を選んだ(発注者確認済み)。
 *
 * <b>このクラスに閉じ込めることが目的である。</b>
 * 将来、割り込み選択システムを実装するときは、各カードの効果ではなく
 * ここのメソッドの呼び出し元だけを差し替えればよい形にしてある。
 */
public final class AutoChoice {

    private AutoChoice() {
    }

    /**
     * 「カードを1枚引いてもよい」の自動判断(執念の暗殺者)。
     *
     * 原則はYES。ただし山札が空のときに引くと敗北するため、そこだけは引かない。
     * 「してもよい」は本来プレイヤーの利益のための選択肢なので、
     * 明確に不利益になるケースだけを除外する、という考え方である。
     */
    public static boolean shouldDrawOptional(PlayerState player) {
        return !player.getDeck().isEmpty();
    }

    /**
     * 「代償を払って蘇生してもよい」の自動判断(不滅のネクロマンサー)。
     *
     * 原則はYES。裏向きマナを1枚失う代わりに、破壊されたミニオンが【突進】付きで戻る。
     * 盤面の損失を取り返す方が価値が高い、という判断である
     * (裏向きマナはMP支払いには引き続き使えるため、失うのはマナの枚数そのもの)。
     */
    public static boolean shouldRevivePayingMana(PlayerState player) {
        return player.getFaceDownManaCount() > 0 && !player.isMinionZoneFull();
    }

    /**
     * 墓地から蘇生する候補の優先順位(冥界神ハデス・死者蘇生)。
     *
     * コストの高い順に選ぶ。コストは大まかな強さの指標であり、
     * 「蘇生させるなら重いものから」という直感に最も近いためである。
     * 同コストならカードIDの順で安定させる(実行のたびに結果が変わらないように)。
     *
     * @param candidateCardIds 蘇生候補のカードID(墓地にあるミニオン)
     */
    public static List<String> reviveOrder(CardMasterRepository cards, List<String> candidateCardIds) {
        return candidateCardIds.stream()
                .sorted(Comparator
                        .comparingInt((String id) -> costOf(cards.findById(id))).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    /**
     * 墓地から手札に戻す1枚の自動選択(死霊の収鎌)。
     * 最後に墓地へ置かれたカード(=直前に失ったもの)を戻す。
     *
     * @return 戻すカードID。墓地が空ならnull
     */
    public static String recoverFromTrash(PlayerState player) {
        List<String> trash = player.getTrash();
        return trash.isEmpty() ? null : trash.get(trash.size() - 1);
    }

    private static int costOf(CardMaster card) {
        return card.cost() == null ? 0 : card.cost();
    }
}
