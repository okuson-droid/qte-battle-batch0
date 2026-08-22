package com.example.qte.deck;

import java.util.List;

/**
 * デッキ1本の中身。<b>サーバの内部表現であり、ファイルの形そのものではない</b>。
 *
 * <h2>★Batch 63: ファイルの形と内部表現を分けた</h2>
 * 62 までは、この record が<b>そのままデッキファイルの JSON 構造</b>だった
 * ({@code formatVersion} という欄はそのためにあった)。その結果、通常モードは
 * 通常モードのデッキビルダーが書いた形しか読めず、デッキメーカー({@code /deck-maker})が
 * 書いた {@code taboo-elemental-deck} 形式のデッキは<b>手動モードでしか使えなかった</b>。
 *
 * <p>63 でデッキファイルの形式を {@code taboo-elemental-deck}(version 2)に一本化し、
 * ファイルの読み取りは {@link DeckFileReader} が受け持つことにした。
 * この record に版番号が無いのは、<b>版番号はファイルの性質であって、
 * 盤面へ渡すデッキの性質ではない</b>ためである。
 *
 * <p>デッキはサーバに永続化しない(アカウント・DBを持たない方針の帰結。
 * 保存先はユーザーのPCのファイルである)。
 *
 * @param name         デッキ名(表示用)
 * @param leaderCardId リーダーカードのID
 * @param main         メインデッキ40枚(カードIDと枚数)
 * @param taboo        禁忌デッキ8枚(カードID。同名1枚まで)
 */
public record DeckDefinition(
        String name,
        String leaderCardId,
        List<Entry> main,
        List<String> taboo) {

    public record Entry(String cardId, int count) {
    }
}
