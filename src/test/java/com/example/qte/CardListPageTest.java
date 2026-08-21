package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * カード一覧2画面が実際に描けることの試験(★Batch 61)。
 *
 * <h2>なぜ要るのか</h2>
 *
 * 61 で2つのカード一覧を Thymeleaf の<b>フラグメント</b>で書き直した。
 * この仕組みはこのプロジェクトで初めて使うものであり、
 * <b>書き間違えても Java のコンパイルは通る</b> —— 壊れていることが分かるのは、
 * 誰かがブラウザでそのページを開いて 500 を見たときである。
 *
 * <p>60 までこの2画面には試験が1件も無かった。
 * 機械検証(verify)はテンプレートから作ったハーネスを見るだけで、
 * <b>Spring がテンプレートを解決できるか</b>は誰も測っていない。
 *
 * <h2>何を測るか</h2>
 *
 * <ul>
 * <li>2画面とも 200 で返り、カードフェイスの markup を含むこと</li>
 * <li>★<b>本文が実際に出ていること</b>(手動モードは 60 まで1文字も出ていなかった)</li>
 * <li>★<b>文明色を値で書いていないこと</b>(裁定60。正は battle.css の :root)</li>
 * <li>全235枚ぶんのセルが出ていること</li>
 * </ul>
 */
@SpringBootTest
class CardListPageTest {

    /** 《知識の守護者》。本文が2行に分かれている(改行を持つ28枚の代表) */
    private static final String TWO_LINE_CARD = "QTE-M-WATER-5";
    /** 《海淵獣シラーカ》。進化ミニオンの代表 */
    private static final String EVOLUTION_CARD = "QTE-M-WATER-30";

    /*
     * ★MockMvc は手で組み立てる。Spring Boot 4 の @AutoConfigureMockMvc を提供する
     *   モジュールがこのプロジェクトの依存に入っていないためである
     *   (spring-test の MockMvcBuilders は使える)。
     *   自動設定に頼らない分、何が組み上がっているかがこの1行で見える。
     */
    @Autowired
    WebApplicationContext context;

    private String get(String path) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        MvcResult result = mvc.perform(MockMvcRequestBuilders.get(path)).andReturn();
        assertThat(result.getResponse().getStatus()).as(path + " の応答").isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    @Test
    void 通常モードのカード一覧はカードフェイスで235枚を出す() throws Exception {
        String html = get("/cards");
        assertThat(countOf(html, "class=\"qcard-cell\"")).as("カードのセル").isEqualTo(235);
        assertThat(html).contains("mcard mcard-large");
    }

    @Test
    void 手動モードのカード一覧はカードフェイスで235枚を出す() throws Exception {
        String html = get("/manual/cards");
        assertThat(countOf(html, "class=\"qcard-cell\"")).as("カードのセル").isEqualTo(235);
        assertThat(html).contains("mcard mcard-large");
    }

    /**
     * ★<b>これが 61 の主眼である。</b>60 までの手動モードのカード一覧は
     * 画像と数値しか出しておらず、本文は1文字も出ていなかった。
     */
    @Test
    void 手動モードのカード一覧にも本文が出る() throws Exception {
        String html = get("/manual/cards");
        assertThat(html).contains("このミニオンの攻撃力は、自分の手札の枚数と同じになる。");
    }

    @Test
    void 通常モードのカード一覧にも本文が出る() throws Exception {
        String html = get("/cards");
        assertThat(html).contains("このミニオンの攻撃力は、自分の手札の枚数と同じになる。");
    }

    /**
     * ★<b>文明色の値を画面に書かない</b>(裁定60)。
     * 正は {@code battle.css} の {@code :root} であり、画面が持つのは変数の名前だけである。
     * {@code battle.js} の {@code civColor} が同じ変数を読んでいる。
     */
    @Test
    void カード一覧は文明色を値ではなく変数名で持つ() throws Exception {
        for (String path : new String[] {"/cards", "/manual/cards"}) {
            String html = get(path);
            assertThat(html).as(path).contains("--mc: var(--civ-water)");
            assertThat(html).as(path + " に色の値が直書きされている").doesNotContain("#2f6fb5");
        }
    }

    /** 進化ミニオンには斜め縞の印が付く(デッキメーカーの .tile.evo と同じ意味) */
    @Test
    void 進化ミニオンには印が付く() throws Exception {
        String html = get("/cards");
        assertThat(html).contains("qcard-face is-evolution");
        assertThat(html).contains(EVOLUTION_CARD);
    }

    /** カードIDが出ていること —— 一覧はIDで照合する画面である */
    @Test
    void カードIDが各セルに出る() throws Exception {
        String html = get("/manual/cards");
        assertThat(html).contains(TWO_LINE_CARD);
        assertThat(html).contains(EVOLUTION_CARD);
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }
}
