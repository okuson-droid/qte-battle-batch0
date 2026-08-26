package com.example.qte.room;

/**
 * 部屋を作るときに決める属性(★Batch 66)。手動モードの
 * {@code ManualRoomOptions} に対応する型である。
 *
 * <h2>★種類({@code type})が無い</h2>
 * 手動モードには「全公開(一人回し)」と「対戦」の2種類がある。
 * 通常モードに<b>全公開は作らない</b>(マスター指示) ——
 * 通常モードはサーバがルールを執行し、手札・裏向きマナ・禁忌デッキを
 * プレイヤーごとに伏せる({@code GameViewBuilder} が唯一のフィルタである)。
 * 「1人で両席を操作する」は、その伏せ方を1人ぶん解くことと同義であり、
 * <b>情報の非対称そのものを壊す</b>。手動モードの全公開が成り立つのは、
 * あちらが最初から「盤面の入れ物」で、伏せる仕組みが人の目にしか無いからである。
 *
 * <p>その帰結として、通常モードでは<b>部屋名も名前も必ず要る</b>
 * (手動モードで省略できたのは全公開部屋だけだった)。
 *
 * @param name             部屋名。一覧に出る唯一の手がかりである
 * @param spectatorAllowed 観戦を許すか
 * @param requireRoomId    入室に部屋IDを要求するか(true なら一覧にIDを載せない)
 */
public record GameRoomOptions(String name, boolean spectatorAllowed, boolean requireRoomId) {

    /** 部屋名の上限。ロビーの入力欄({@code maxlength})と同じ値である */
    public static final int MAX_NAME_LENGTH = 40;

    public GameRoomOptions {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("部屋名を入力してください");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "部屋名は%d文字までです".formatted(MAX_NAME_LENGTH));
        }
    }
}
