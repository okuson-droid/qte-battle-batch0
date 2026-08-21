package com.example.qte.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;

import com.example.qte.master.Keyword;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * キーワード抽出規則の<b>回帰の物差し</b>({@code src/test/resources/keyword-baseline.json})を読む入口。
 * ★Batch 60 で {@code LedgerCards} を置き換えたものである。
 *
 * <h2>これは何か</h2>
 *
 * 退役した Ver0.4 台帳({@code qte-cards.json})で<b>人手が付けていたキーワード</b>だけを、
 * Ver1.1 のカードIDに写し替えて凍結した169件の表である。
 *
 * <h2>★これは「2つ目の正」ではない</h2>
 *
 * 持っているのは <b>カードID → キーワード名</b> の対だけで、
 * コストも攻撃力も本文も画像も無い。<b>本番のコードはこのファイルを読まない</b>
 * ({@code src/test/resources} に置いてあるので jar にも入らない)。
 * 使い道は1つだけである —— {@link com.example.qte.master.CardTextKeywords} を触ったときに、
 * 169枚の解釈が黙って変わっていないかを {@code CardTextKeywordsTest} が検出することである。
 * 素朴な規則では22枚が狂うので(裁定159)、この番人は残す価値がある。
 *
 * <h2>なぜ台帳そのものを残さなかったのか</h2>
 *
 * Batch 60 で {@code qte-cards.json}(101KB・169枚・Ver0.4)を削除した。区分5 が終わり、
 * カードの正は {@code manual-cards.json}(235枚・Ver1.1)1つになったからである。
 * ★<b>台帳を「番人のために」丸ごと残すと、次に読む人はそれを台帳として読む。</b>
 * 番人に必要なのは169行のキーワードだけなので、必要なぶんだけを抜き出して凍結した。
 *
 * <h2>更新してよい場面</h2>
 *
 * <b>原則として更新しない。</b>凍結した過去の断面であることが値打ちだからである。
 * 抽出規則を意図して変えた結果ここが赤くなったなら、
 * 直すのは<b>このファイルではなく、テスト側の「既知の差分表」</b>である
 * ({@code CardTextKeywordsTest.VER11_KEYWORD_CHANGES})。
 * 差分表に足せば「どのカードが、なぜ台帳と変わったか」が残る。
 * ここを書き換えると、変わったことそのものが消える。
 */
public final class KeywordBaseline {

    private static final String RESOURCE = "keyword-baseline.json";

    private KeywordBaseline() {
    }

    /** Ver1.1 のカードID → 人手で付けられていたキーワード(169件) */
    public static Map<String, Set<Keyword>> load(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            File file = objectMapper.readValue(in, File.class);
            Map<String, Set<Keyword>> map = new LinkedHashMap<>();
            file.keywords().forEach((cardId, names) -> map.put(cardId, names.stream()
                    .map(Keyword::fromDisplayName)
                    .collect(Collectors.toUnmodifiableSet())));
            return map;
        } catch (IOException e) {
            throw new UncheckedIOException("キーワードの物差し(keyword-baseline.json)が読めません", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record File(Map<String, java.util.List<String>> keywords) {
    }
}
