package com.example.qte.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * テストから Ver1.1 のカード定義({@code manual-cards.json})を読むための最小の入口(Batch 46a)。
 *
 * <b>なぜリポジトリを使わないのか。</b> 46a の時点では
 * {@link com.example.qte.master.CardMasterRepository} はまだ台帳
 * {@code qte-cards.json} を読んでおり、{@code ManualCardRepository} は
 * テキストを持たない(手動モードの設計書 3-1)。Ver1.1 のテキストを見る口が
 * どこにも無いので、テスト側でファイルを直に読む。
 *
 * ★46b で本体が {@code manual-cards.json} を読むようになったら、このクラスは
 * <b>役目を終える</b>。そのときテストは本体のリポジトリ越しに見るべきである
 * —— テスト専用の読み口が残ると、本体の読み方が壊れてもテストが気づかなくなる。
 */
public final class Ver11Cards {

    private static final String RESOURCE = "cards/manual-cards.json";

    private Ver11Cards() {
    }

    /**
     * Ver1.1 の全カード(ファイルに書かれた順)。
     *
     * @param objectMapper Spring が組み立てたものを渡すこと(本体と同じ設定で読むため)
     */
    public static List<Card> load(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return objectMapper.readValue(in, File.class).cards();
        } catch (IOException e) {
            throw new UncheckedIOException("Ver1.1 のカード定義が読めません", e);
        }
    }

    /**
     * カード1件。テストが見る項目だけを持つ。
     *
     * @param ledgerCardId 台帳の対応カードID。新カード66枚は null
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Card(
            String id,
            String name,
            String type,
            String civilization,
            Integer cost,
            Integer attack,
            Integer hp,
            String imageId,
            String ledgerCardId,
            String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record File(List<Card> cards) {
    }
}
