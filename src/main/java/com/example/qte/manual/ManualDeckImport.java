package com.example.qte.manual;

import java.util.List;

/**
 * デッキzipを読んだ結果(設計書 7章)。
 *
 * ★これは「読めたもの」と「気になったこと」の両方を持つ。全体を拒否する形にはしない。
 * 手動モードは効果を判定しないため、画像が無くても名前があれば遊べる。
 * 構築ルール違反も警告に留めて開始できる(設計書 7-4)。厳しく弾くほうが検証の邪魔になる。
 *
 * @param deckName デッキ名(card-stack 自身の名前。取れなければ null)
 * @param leader   リーダー。見つからなければ null
 * @param main     メインデッキ(リーダーを除く)。読めた順のまま
 * @param taboo    禁忌デッキ
 * @param warnings 人が読む警告の一覧。空なら問題なし
 */
public record ManualDeckImport(
        String deckName,
        Entry leader,
        List<Entry> main,
        List<Entry> taboo,
        List<String> warnings) {

    /**
     * デッキの1枚。
     *
     * @param master   突合できたカード定義。★突合できなければ null(未解決カード)
     * @param rawName  デッキXMLに書かれていた名前。表記ゆれを含むため突合には使わない
     * @param imageId  表面画像ID。★これが唯一の突合キーである(設計書 1-3)
     */
    public record Entry(ManualCardMaster master, String rawName, String imageId) {

        public boolean isResolved() {
            return master != null;
        }

        /** 表示名。突合できていればカード定義の名前、できていなければXMLの名前を使う。 */
        public String displayName() {
            return master == null ? rawName : master.name();
        }

        /** ゾーン上の個体を1枚起こす。未解決カードは名前だけの灰色タイルになる(設計書 7-3)。 */
        public ManualCardInstance toInstance() {
            if (master == null) {
                return ManualCardInstance.unresolved(rawName, imageId);
            }
            return ManualCardInstance.of(master);
        }
    }

    /** リーダーを含む総枚数。実サンプルは 1 + 40 + 8 = 49 になる。 */
    public int totalCards() {
        return (leader == null ? 0 : 1) + main.size() + taboo.size();
    }

    /** カード定義に突合できなかった枚数。0 が正常である。 */
    public int unresolvedCount() {
        int count = leader != null && !leader.isResolved() ? 1 : 0;
        count += (int) main.stream().filter(e -> !e.isResolved()).count();
        count += (int) taboo.stream().filter(e -> !e.isResolved()).count();
        return count;
    }
}
