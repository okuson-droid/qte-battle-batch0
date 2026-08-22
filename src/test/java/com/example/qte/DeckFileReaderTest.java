package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.deck.DeckDefinition;
import com.example.qte.deck.DeckFileReader;
import com.example.qte.deck.DeckValidator;
import com.example.qte.manual.ManualDeckImport;
import com.example.qte.manual.ManualDeckImporter;

/**
 * デッキファイルの読み取り({@link DeckFileReader})の試験(★Batch 63 で新設)。
 *
 * <h2>この試験が守っているもの</h2>
 * 62 まで、デッキファイルの形式は2つあった。カードIDは両モードとも
 * {@code manual-cards.json} で同じなのに<b>欄の名前だけが違い</b>、
 * 「手動モードで使っているデッキが通常モードで読み込めない」状態だった。
 *
 * <p>63 で形式を {@code taboo-elemental-deck}(version 2)に一本化した。
 * <b>いちばん大事な試験は {@link #同じデッキファイルを両モードが同じ中身として読む()} である</b> ——
 * 片方だけを測っても「両方が読める」ことの証明にはならないからである。
 */
@SpringBootTest
class DeckFileReaderTest {

    /** リポジトリに置いてある効果確認用デッキ。★実物であることに意味がある */
    private static final Path DECKS = Path.of("decks");

    @Autowired
    DeckFileReader reader;

    @Autowired
    DeckValidator validator;

    @Autowired
    ManualDeckImporter manualImporter;

    // ------------------------------------------------------------------
    // ★★★本題: 同じファイルを両モードが読む
    // ------------------------------------------------------------------

    /**
     * ★★★これが 63 の本体である。
     *
     * <p>同じ1本のファイルを、通常モードの読み取りと手動モードの読み取りの<b>両方</b>に通し、
     * メイン40枚・禁忌8枚という同じ中身になることを測る。
     * 通常モード側はさらに {@link DeckValidator} まで通す(ルールを強制する側なので、
     * 「読めた」だけでは対戦に使えるとは言えない)。
     */
    @Test
    void 同じデッキファイルを両モードが同じ中身として読む() throws IOException {
        List<Path> files = deckFiles();
        assertThat(files).as("decks/ に確認用デッキがある").isNotEmpty();

        for (Path file : files) {
            byte[] bytes = Files.readAllBytes(file);
            String text = new String(bytes, StandardCharsets.UTF_8);

            // 通常モード: 読めて、構築ルールも通る
            DeckDefinition deck = reader.read(text);
            assertThat(mainTotal(deck)).as(file + " の通常モードでのメイン枚数").isEqualTo(40);
            assertThat(deck.taboo()).as(file + " の通常モードでの禁忌枚数").hasSize(8);
            assertThatCode(() -> validator.validate(deck))
                    .as(file + " が通常モードの構築検証を通る")
                    .doesNotThrowAnyException();

            // 手動モード: 同じファイルが同じ枚数で読める。未解決カードは0枚
            ManualDeckImport manual = manualImporter.importJson(bytes);
            assertThat(manual.main()).as(file + " の手動モードでのメイン枚数").hasSize(40);
            assertThat(manual.taboo()).as(file + " の手動モードでの禁忌枚数").hasSize(8);
            assertThat(manual.unresolvedCount()).as(file + " の未解決カード").isZero();

            // 同じリーダーを指している(★突合キーはカードIDのみ)
            assertThat(manual.leader()).as(file + " のリーダー").isNotNull();
            assertThat(manual.leader().imageId()).as(file + " のリーダーID")
                    .isEqualTo(deck.leaderCardId());
        }
    }

    // ------------------------------------------------------------------
    // 形式の門
    // ------------------------------------------------------------------

    @Test
    void デッキメーカーが書く形をそのまま読める() {
        DeckDefinition deck = reader.read("""
                {
                  "format": "taboo-elemental-deck", "version": 2,
                  "exportedAt": "2026-08-21T00:00:00.000Z",
                  "deckName": "テスト", "mainCiv": "WATER",
                  "leader": {"cardId": "QTE-M-WATER-1", "name": "蒼海の賢者"},
                  "main":  [{"cardId": "QTE-M-WATER-2", "name": "その2", "qty": 4}],
                  "taboo": [{"cardId": "QTE-M-FIRE-2", "name": "その火"}]
                }
                """);
        assertThat(deck.name()).isEqualTo("テスト");
        assertThat(deck.leaderCardId()).isEqualTo("QTE-M-WATER-1");
        assertThat(deck.main()).containsExactly(new DeckDefinition.Entry("QTE-M-WATER-2", 4));
        assertThat(deck.taboo()).containsExactly("QTE-M-FIRE-2");
        // ★知らない欄(exportedAt / mainCiv / name)は黙って無視する。
        //   デッキメーカーが欄を増やしても通常モードは壊れない。
    }

    /**
     * ★旧形式(62 までの通常モードのデッキビルダーが書いた形)は受け付けない。
     * <b>黙って別のものとして読まないこと</b>が肝心である ——
     * {@code count} を無視して読み進めると「メイン0枚」の意味不明なデッキになる。
     */
    @Test
    void 旧形式のデッキファイルは理由を言って拒否する() {
        assertThatThrownBy(() -> reader.read("""
                {"formatVersion": 1, "name": "旧", "leaderCardId": "QTE-M-WATER-1",
                 "main": [{"cardId": "QTE-M-WATER-2", "count": 4}], "taboo": []}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format: taboo-elemental-deck")
                .hasMessageContaining("デッキメーカーで保存し直して");
    }

    /**
     * ★version 1(Verβ 由来・カード名で書かれた形式)は通常モードでは受け付けない。
     * 手動モードは検証の道具なので読むが、ルールを強制する側が名前で解決してはならない
     * (表記ゆれ1文字で別のカードになる)。
     */
    @Test
    void 名前で書かれた古い版のデッキファイルは拒否する() {
        assertThatThrownBy(() -> reader.read("""
                {"format": "taboo-elemental-deck", "version": 1,
                 "leader": {"name": "蒼海の賢者", "civ": "WATER"}, "main": [], "taboo": []}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version=1");
    }

    @Test
    void JSONでないものは拒否する() {
        assertThatThrownBy(() -> reader.read("PKこれはzip"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSONではありません");
    }

    @Test
    void リーダーの無いデッキファイルは拒否する() {
        assertThatThrownBy(() -> reader.read("""
                {"format": "taboo-elemental-deck", "version": 2, "main": [], "taboo": []}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("リーダー");
    }

    // ------------------------------------------------------------------
    // 手動モードと揃えた寛容さ(★読み方は同じ。違うのは裁き方だけである)
    // ------------------------------------------------------------------

    @Test
    void リーダーはleaderId文字列でも読める() {
        DeckDefinition deck = reader.read("""
                {"format": "taboo-elemental-deck", "version": 2,
                 "leaderId": "QTE-M-WATER-1", "main": [], "taboo": []}
                """);
        assertThat(deck.leaderCardId()).isEqualTo("QTE-M-WATER-1");
    }

    @Test
    void 禁忌はID文字列の配列でも読める() {
        DeckDefinition deck = reader.read("""
                {"format": "taboo-elemental-deck", "version": 2,
                 "leader": {"cardId": "QTE-M-WATER-1"},
                 "main": [], "taboo": ["QTE-M-FIRE-2", "QTE-M-FIRE-3"]}
                """);
        assertThat(deck.taboo()).containsExactly("QTE-M-FIRE-2", "QTE-M-FIRE-3");
    }

    @Test
    void qtyの無い行は1枚として読む() {
        DeckDefinition deck = reader.read("""
                {"format": "taboo-elemental-deck", "version": 2,
                 "leader": {"cardId": "QTE-M-WATER-1"},
                 "main": [{"cardId": "QTE-M-WATER-2"}], "taboo": []}
                """);
        assertThat(deck.main()).containsExactly(new DeckDefinition.Entry("QTE-M-WATER-2", 1));
    }

    // ------------------------------------------------------------------
    // ★読み取りが親切であるほど、その先の検証は無力になる
    // ------------------------------------------------------------------

    /**
     * ★★同じカードIDの行を読み取りでまとめない。
     *
     * <p>まとめると {@link DeckValidator} の「同じカードの行が重複しています」が
     * 誰にも当たらなくなる —— 検証を1箇所に集める設計は、その1箇所へ壊れたものが
     * 届くことを前提にしている。
     */
    @Test
    void 同じカードIDの行はまとめずに検証へ渡す() {
        DeckDefinition deck = reader.read("""
                {"format": "taboo-elemental-deck", "version": 2,
                 "leader": {"cardId": "QTE-M-WATER-1"},
                 "main": [{"cardId": "QTE-M-WATER-2", "qty": 2},
                          {"cardId": "QTE-M-WATER-2", "qty": 2}],
                 "taboo": []}
                """);
        assertThat(deck.main()).hasSize(2);
        assertThatThrownBy(() -> validator.validate(deck))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重複");
    }

    @Test
    void cardIdの無い行は拒否する() {
        assertThatThrownBy(() -> reader.read("""
                {"format": "taboo-elemental-deck", "version": 2,
                 "leader": {"cardId": "QTE-M-WATER-1"},
                 "main": [{"name": "名前だけ", "qty": 4}], "taboo": []}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cardId");
    }

    /** ★外から来るデータには上限を置く(設計判断27)。実デッキはメイン最大40行である */
    @Test
    void 行数が多すぎるファイルは拒否する() {
        StringBuilder main = new StringBuilder();
        for (int i = 0; i <= DeckFileReader.MAX_ENTRIES; i++) {
            main.append(i == 0 ? "" : ",").append("{\"cardId\":\"QTE-M-WATER-2\",\"qty\":1}");
        }
        String json = """
                {"format": "taboo-elemental-deck", "version": 2,
                 "leader": {"cardId": "QTE-M-WATER-1"},
                 "main": [%s], "taboo": []}
                """.formatted(main);
        assertThatThrownBy(() -> reader.read(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("行数");
    }

    // ------------------------------------------------------------------

    private List<Path> deckFiles() throws IOException {
        try (Stream<Path> files = Files.list(DECKS)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    private int mainTotal(DeckDefinition deck) {
        return deck.main().stream().mapToInt(DeckDefinition.Entry::count).sum();
    }
}
