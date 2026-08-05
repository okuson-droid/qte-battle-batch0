package com.example.qte.manual.view;

import java.util.List;
import java.util.Map;

import com.example.qte.manual.ManualSeatId;
import com.example.qte.manual.ManualZone;

/**
 * 配信用の席1つ分。
 *
 * ★ゾーンはフィールドに展開せず Map で送る。状態モデル側が
 * {@code EnumMap<ManualZone, List<ManualCardInstance>>} の一様な形で持っているのと同じ理由である。
 * ゾーンが増えても DTO・クライアントの両方に手を入れずに済む。
 * Jackson は enum を鍵とする Map を文字列鍵のオブジェクトとして出すため、
 * クライアントからは {@code seat.zones.FIELD} で引ける。
 *
 * <h2>★Batch 21a: 非公開ゾーンは {@code zones} に載らない(設計書 3-3・B1)</h2>
 * 対戦部屋で相手の HAND / DECK / TABOO / PRIVATE は、
 * <b>カードオブジェクトを一切載せず {@code counts} の枚数だけ</b>になる。
 * 「中身は送るがクライアントで隠す」形も「裏向きスタブを送る」形も採らない。
 * imageId・名前・数値・faceDown の内訳が構造的に届かないため、
 * 拡大画像・帯・検索のどの経路からも漏れない。これが設計判断9
 * 「盤面配信はプレイヤーごとにフィルタした DTO で行う」の完成形である。
 *
 * ★{@code zones} にキーが無いゾーンは「見えない」を意味する。クライアントは
 * {@code seat.zones[Z] || []} の形で読んでいる(20b/20c の描画)ため、
 * キーの欠落がそのまま空表示になり、21b/21c まで描画が壊れない。
 *
 * @param mp                マナのアンタップ枚数から算出した派生値。
 *                          直接増減する操作は存在しない(設計書 5-3)。
 *                          ★相手のMPも数値としては公開する(3-3)
 * @param counts            <b>全ゾーンの</b>枚数。見えないゾーンでも枚数は届く
 *                          (何枚あるかは公開情報であり、相手上段のチップ(4章)がこれを使う)
 * @param manaFaceDownCount マナの裏向きの枚数。★マナは特例で
 *                          「表向きカードの配列 + 裏向きの枚数」として送る(3-3)
 */
public record ManualSeatView(
        ManualSeatId id,
        int lp,
        int mp,
        boolean deckLoaded,
        String deckName,
        ManualCardView leader,
        Map<ManualZone, List<ManualCardView>> zones,
        Map<ManualZone, Integer> counts,
        int manaFaceDownCount) {
}
