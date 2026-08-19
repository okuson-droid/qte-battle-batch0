package com.example.qte.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * 退役した台帳({@code qte-cards.json}・169枚・Ver0.4)をテストから読むための入口(Batch 46b)。
 *
 * <h2>なぜ本体のリポジトリを使わないのか</h2>
 *
 * 46b で {@link com.example.qte.master.CardMasterRepository} は
 * {@code manual-cards.json}(235枚・Ver1.1)を読むようになった。台帳を読む口は
 * <b>本体にもう存在しない</b>。したがってテスト側でファイルを直に読むしかない。
 *
 * <h2>★これは「2つ目の正」ではない</h2>
 *
 * 台帳はもう何の正でもない。<b>凍結した過去の断面</b>であり、ここで使う目的は1つだけである
 * —— キーワード抽出規則({@link com.example.qte.master.CardTextKeywords})を触ったときに、
 * 169枚の解釈が黙って変わっていないかを検出することである
 * ({@code CardTextKeywordsTest} の全件照合)。素朴な規則では22枚が狂うので(裁定159)、
 * この番人は無料で残せるうちは残す価値がある。
 *
 * <p><b>★{@code qte-cards.json} を削除するバッチで、このクラスと照合テストも一緒に消すこと。</b>
 * 片方だけ残すと、次の人は「まだ台帳が生きている」と読む。
 */
public final class LedgerCards {

    private static final String RESOURCE = "cards/qte-cards.json";

    private LedgerCards() {
    }

    /** 台帳の全カード(ファイルに書かれた順) */
    public static List<Card> load(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return objectMapper.readValue(in, File.class).cards();
        } catch (IOException e) {
            throw new UncheckedIOException("台帳(qte-cards.json)が読めません", e);
        }
    }

    /**
     * 台帳のカード1件。テストが見る項目だけを持つ。
     *
     * @param keywords 人手で付けたキーワードの表示名。★これが照合の相手である
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Card(
            String id,
            String name,
            String type,
            String civilization,
            List<String> keywords,
            String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record File(List<Card> cards) {
    }
}
