package com.example.qte.deck;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * デッキファイル({@code format: taboo-elemental-deck})を通常モードの
 * {@link DeckDefinition} へ読み取る(★Batch 63 で新設)。
 *
 * <h2>★★★なぜこのクラスができたのか</h2>
 * 62 まで、デッキファイルの形式は<b>2つあった</b>。
 *
 * <ul>
 * <li>デッキメーカー({@code /deck-maker})が書く {@code taboo-elemental-deck}(version 2)
 *     —— 手動モードの盤面が読む形</li>
 * <li>通常モードのデッキビルダーが書く {@code formatVersion: 1} の形
 *     —— 通常モードのロビーが読む形</li>
 * </ul>
 *
 * どちらもカードIDは同じ({@code manual-cards.json} が両モード共通の正である)のに、
 * <b>欄の名前だけが違った</b>({@code leaderCardId} と {@code leader.cardId}、
 * {@code count} と {@code qty}、禁忌がID文字列の配列かオブジェクトの配列か)。
 * そのため「手動モードで使っているデッキが通常モードで読み込めない」という、
 * 中身は同じなのに通らない状態になっていた。
 *
 * <p>63 で<b>形式を {@code taboo-elemental-deck} に一本化した</b>。通常モードのデッキビルダー
 * ({@code /deck-builder})は退役し、デッキを組む場所はデッキメーカー1つになった。
 *
 * <h2>★★規約は共有し、コードは複製した(裁定111 と同じ形)</h2>
 * 読み取りの実体は手動モードの
 * {@code ManualDeckImporter#importJson} にもある。1つに寄せなかったのは、
 * 突合先のカードマスタの型が違う(こちらは {@code CardMaster}、あちらは
 * {@code ManualCardMaster})ためであり、共通化すると通常モードが手動モードの層に
 * 依存することになる。<b>共有するのは「ファイルの欄の名前」という規約のほうであり、
 * 一致していることは機械検証が両方から読んで突き合わせる</b>(裁定110)。
 *
 * <h2>手動モードとの違いは「読み方」ではなく「裁き方」である</h2>
 * 読み取りの寛容さ({@code leaderId} でも {@code leader.cardId} でもよい、
 * 禁忌の要素はID文字列でもオブジェクトでもよい)は手動モードと揃えてある。
 * <b>違うのはこの先である</b> —— 手動モードは構築ルール違反を警告に留めて遊ばせるが、
 * 通常モードは {@link DeckValidator} が拒否する。ルールを強制するモードだからである。
 */
@Component
@RequiredArgsConstructor
public class DeckFileReader {

    /** デッキファイルの形式名。デッキメーカーが書き、手動モードの盤面も同じ値を見る */
    public static final String FORMAT = "taboo-elemental-deck";

    /**
     * 受け付ける最小の版番号。
     *
     * <p>version 1 は Verβ 由来の<b>カード名で書かれた</b>形式である。通常モードでは
     * 受け付けない —— 名前を突合キーにするのはIDをリテラルで書くのと本質的に同じであり、
     * 表記ゆれ1文字で別のカードになる。手動モードは検証の道具なので v1 も読むが、
     * ルールを強制する側が名前で解決してはならない。
     */
    public static final int MIN_VERSION = 2;

    /**
     * 1ファイルが持てる行数の上限(異常なデータへの防波堤。実デッキはメイン最大40行・禁忌8行)。
     * 手動モードの {@code ManualDeckImporter.MAX_JSON_CARDS} と同じ役割である。
     */
    public static final int MAX_ENTRIES = 200;

    private final ObjectMapper objectMapper;

    /**
     * デッキファイルの中身(JSON文字列)を読み取る。
     * 構築ルールの検証は行わない —— それは {@link DeckValidator} 1箇所の仕事である。
     *
     * @throws IllegalArgumentException 読めない・形式が違う・版が古いとき
     */
    public DeckDefinition read(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("デッキファイルが空です");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("デッキファイルとして読めませんでした(JSONではありません)");
        }
        if (root == null || !root.isObject() || !FORMAT.equals(textOrNull(root.path("format")))) {
            throw new IllegalArgumentException(
                    "デッキファイルの形式が違います(format: %s のみ)。デッキメーカーで保存し直してください"
                            .formatted(FORMAT));
        }
        int version = root.path("version").asInt(0);
        if (version < MIN_VERSION) {
            throw new IllegalArgumentException(
                    "対応していないデッキファイル形式です(version=%d)。デッキメーカーで保存し直してください"
                            .formatted(version));
        }
        return new DeckDefinition(
                textOrNull(root.path("deckName")),
                readLeaderCardId(root),
                readMain(root.path("main")),
                readTaboo(root.path("taboo")));
    }

    /** リーダーは {@code leader: {cardId}} が正。過渡期の {@code leaderId} 文字列も読む */
    private String readLeaderCardId(JsonNode root) {
        JsonNode leader = root.path("leader");
        String cardId = leader.isObject()
                ? textOrNull(leader.path("cardId"))
                : textOrNull(root.path("leaderId"));
        if (cardId == null) {
            throw new IllegalArgumentException("デッキファイルにリーダーがありません");
        }
        return cardId;
    }

    /**
     * メインデッキ。{@code qty} が枚数である(既定は1)。
     *
     * <p>★<b>同じカードIDの行をここでまとめない。</b> まとめると
     * {@link DeckValidator} の「同じカードの行が重複しています」が誰にも当たらなくなる ——
     * <b>読み取りが親切であるほど、その先の検証は無力になる</b>。
     * 枚数の上限も総数もこのクラスは裁かない。
     */
    private List<DeckDefinition.Entry> readMain(JsonNode array) {
        List<DeckDefinition.Entry> result = new ArrayList<>();
        if (!array.isArray()) {
            return result;
        }
        for (JsonNode element : array) {
            requireRoom(result.size());
            result.add(new DeckDefinition.Entry(requireCardId(element), readQty(element)));
        }
        return result;
    }

    /** 禁忌デッキ。ハイランダー(同名1枚)なので {@code qty} は見ない */
    private List<String> readTaboo(JsonNode array) {
        List<String> result = new ArrayList<>();
        if (!array.isArray()) {
            return result;
        }
        for (JsonNode element : array) {
            requireRoom(result.size());
            result.add(requireCardId(element));
        }
        return result;
    }

    private int readQty(JsonNode element) {
        return element.isObject() ? element.path("qty").asInt(1) : 1;
    }

    private String requireCardId(JsonNode element) {
        String cardId = element.isObject() ? textOrNull(element.path("cardId")) : textOrNull(element);
        if (cardId == null) {
            throw new IllegalArgumentException("デッキファイルに cardId の無い行があります");
        }
        return cardId;
    }

    private void requireRoom(int size) {
        if (size >= MAX_ENTRIES) {
            throw new IllegalArgumentException(
                    "デッキファイルの行数が多すぎます(%d行まで)".formatted(MAX_ENTRIES));
        }
    }

    /** ★手動モードの {@code ManualDeckImporter.textOrNull} と同じ規約(空白のみは無い扱い) */
    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? null : text;
    }
}
