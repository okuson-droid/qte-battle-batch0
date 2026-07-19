package com.example.qte.deck;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * デッキファイル(.json)の中身。デッキビルダーが出力し、対戦時に読み込む。
 *
 * このファイル自体が保存データであり、サーバはデッキを永続化しない
 * (アカウント・DBを持たない方針の帰結。保存先はユーザーのPC)。
 *
 * @param formatVersion 将来フォーマットを変えたときに互換性を判断するための版番号
 * @param name          デッキ名(表示用)
 * @param leaderCardId  リーダーカードのID
 * @param main          メインデッキ40枚(カードIDと枚数)
 * @param taboo         禁忌デッキ8枚(カードID。同名1枚まで)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeckDefinition(
        int formatVersion,
        String name,
        String leaderCardId,
        List<Entry> main,
        List<String> taboo) {

    public static final int CURRENT_FORMAT_VERSION = 1;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String cardId, int count) {
    }
}
