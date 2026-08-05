package com.example.qte.manual;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 構造化イベントを、閲覧者1人ぶんの1行へ変換する(Batch 21 設計書 5-2)。
 *
 * <h2>★マスク規則はこの1メソッドに閉じる</h2>
 * 「移動の from か to のどちらかが、その閲覧者にとって公開ゾーンであれば名前を出す」
 * (5-2)を {@link #showsName} が唯一の実装として持つ。
 * 判定そのものは {@link ManualViewpoint#canSeeZone} に委ねており、
 * <b>盤面ビューと同じ判定を通る</b>。ログだけが独自の公開判定を持つと、
 * 盤面から隠したはずのカード名がログから漏れる。
 *
 * <h2>行自体は全員に配る(5-1)</h2>
 * 「席Aが手札から山札へ1枚戻した」という<b>回数と場所</b>は公開情報である。
 * 相手の操作が一切見えないと対戦が成立しないため、ログを自席ぶんに絞る案は採らない。
 * 隠すのはカード名と、非公開ゾーンにあるカードの数値・札の内容だけである。
 *
 * <h2>なぜ {@code @Component} なのか</h2>
 * 状態を持たないので static でもよいが、配信({@code ManualViewBuilder})と
 * ダウンロード({@code ManualLobbyController})の両方が DI で受け取る形にしておくと、
 * 「両方が同じ1つの実装を通っている」ことがコンストラクタから読み取れる。
 * 5-4 の「完全ログの裏口を作らない」は、この構造で担保する。
 */
@Component
public class ManualLogRenderer {

    /** ログにカード名を並べる上限。これを超えたら「ほかn枚」にまとめる(19a からの踏襲) */
    private static final int NAME_LIMIT = 5;

    /**
     * 1行を組み立てる。
     *
     * @param viewpoint 閲覧者の視点。★{@link ManualViewpoint#full()} を既定にしないこと。
     *                  ダウンロード経路でうっかり full を渡すと 5-4 の裏口になる
     */
    public String render(ManualLogEvent event, ManualViewpoint viewpoint) {
        if (event.kind().isPlain()) {
            return event.text();
        }
        return switch (event.kind()) {
            case MOVE -> renderMove(event, viewpoint);
            case EVOLVE -> renderEvolve(event, viewpoint);
            case STAT, STAT_RESET -> renderCardChange(event, viewpoint, "の数値を変更した");
            case LABEL_ADD -> renderCardChange(event, viewpoint, "に札を付けた");
            case LABEL_REMOVE -> renderCardChange(event, viewpoint, "の札を外した");
            case TAP, FLIP, USED -> renderFlag(event, viewpoint);
            // ★plain 種別はこの手前で返している。ここに来るのは種別を足して分岐を忘れた場合だけ。
            default -> event.text() == null ? "(記録できなかった操作)" : event.text();
        };
    }

    /** 行1件をそのまま整形して返す(ダウンロード用。時刻は呼び出し側が付ける)。 */
    public String render(ManualLogEntry entry, ManualViewpoint viewpoint) {
        return render(entry.event(), viewpoint);
    }

    // ================= 種別ごとの組み立て =================

    /**
     * 移動(5-2 の表)。
     *
     * ★移動元・移動先・枚数・表裏は常に出す。隠すのはカード名だけである。
     * 19a からの書式をそのまま保っているのは、ダウンロードしたログを読み比べる人間にとって
     * 行の形が変わらないほうがよいためである。
     */
    private String renderMove(ManualLogEvent event, ManualViewpoint viewpoint) {
        String names = names(event, viewpoint);
        String note = event.publicNote() == null ? "" : event.publicNote();
        return "%s → %s%s に %d枚 移した%s".formatted(
                describe(event.origin(), event.cards()),
                describe(event.destination(), List.of()),
                note,
                event.cards().size(),
                names.isEmpty() ? "" : " " + names);
    }

    private String renderEvolve(ManualLogEvent event, ManualViewpoint viewpoint) {
        return "%s: %s %s".formatted(
                describe(event.destination(), event.cards()),
                names(event, viewpoint),
                event.publicNote() == null ? "を重ねた" : event.publicNote());
    }

    /**
     * 1枚のカードに対する変更(数値・札)。
     *
     * ★{@code secretNote}(前後の数値・札の文字列)は、そのカードが見える閲覧者にだけ出す。
     * 「席A 手札のカード1枚の数値を変更した」までは全員に見せる — 何かが起きた事実は
     * 隠さず、内容だけを隠す。
     */
    private String renderCardChange(ManualLogEvent event, ManualViewpoint viewpoint,
            String fallbackVerb) {
        boolean visible = anyVisible(event, viewpoint);
        if (visible && event.secretNote() != null) {
            return "%s %s".formatted(names(event, viewpoint), event.secretNote());
        }
        return "%s: %s %s".formatted(describe(event.origin(), event.cards()),
                names(event, viewpoint), fallbackVerb);
    }

    private String renderFlag(ManualLogEvent event, ManualViewpoint viewpoint) {
        return "%s: %s を%s".formatted(describe(event.origin(), event.cards()),
                names(event, viewpoint), event.publicNote());
    }

    // ================= 補助 =================

    /**
     * ★名前を出してよいか(5-2)。
     *
     * 「そのカードが今いた場所」か「移動先」のどちらかが公開なら名前を出す。
     * 手札 → 場 は場が公開なので名前が出る。手札 → 山札 はどちらも非公開なので出ない。
     * マナ裏 → 墓地 は墓地が公開なので出る。すべて設計書 5-2 の表のとおりになる。
     */
    private boolean showsName(ManualLogEvent event, ManualLogCard card, ManualViewpoint viewpoint) {
        if (viewpoint.canSeeZone(card.seatId(), card.zone())) {
            return true;
        }
        ManualLogPlace to = event.destination();
        return to != null && viewpoint.canSeeZone(to.seatId(), to.zone());
    }

    private boolean anyVisible(ManualLogEvent event, ManualViewpoint viewpoint) {
        for (ManualLogCard card : event.cards()) {
            if (showsName(event, card, viewpoint)) {
                return true;
            }
        }
        return false;
    }

    /** カード名の列。見えないものは「カード」に置き換える(5-2)。 */
    private String names(ManualLogEvent event, ManualViewpoint viewpoint) {
        List<ManualLogCard> cards = event.cards();
        if (cards.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (ManualLogCard card : cards) {
            if (parts.size() >= NAME_LIMIT) {
                parts.add("ほか%d枚".formatted(cards.size() - NAME_LIMIT));
                break;
            }
            parts.add(showsName(event, card, viewpoint)
                    ? "《%s》".formatted(card.name())
                    : ManualLogCard.MASKED);
        }
        return String.join(" ", parts);
    }

    /**
     * 場所の表示。イベントが所在のまとめを持たない(複数の場所にまたがる)ときは、
     * カード側の所在から改めて求める。それでも1つに定まらなければ「複数の場所」と書く。
     */
    private String describe(ManualLogPlace place, List<ManualLogCard> cards) {
        ManualLogPlace resolved = place != null ? place : ManualLogEvent.commonPlace(cards);
        return resolved == null ? "複数の場所" : resolved.describe();
    }
}
