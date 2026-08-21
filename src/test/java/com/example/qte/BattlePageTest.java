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
 * 通常モードの盤面が実際に描けることの試験(★Batch 62)。
 *
 * <h2>なぜ要るのか</h2>
 *
 * 62 で {@code battle.html} に音の設定モーダルを足した(裁定289)。
 * ★<b>Thymeleaf の書き間違いは Java のコンパイルを通る</b> ——
 * 壊れていることが分かるのは、誰かがブラウザで盤面を開いて 500 を見たときである
 * (61 で実際に踏んだ。{@code th:each} と {@code th:replace} を同じタグに書いていた)。
 *
 * <p>★機械検証(verify)が見ているのは<b>テンプレートから作ったハーネス</b>であって、
 * Spring がテンプレートを解決できるかではない。この2つは別のものである。
 *
 * <h2>何を測るか</h2>
 *
 * <ul>
 * <li>盤面が 200 で返ること(★<b>本物の入口を通る</b> ——
 *     部屋を作ってリダイレクト先を辿る。テスト専用の抜け道を作らない)</li>
 * <li>音の設定の markup が出ていること(★62 で足したもの)</li>
 * <li>★<b>JS の版数が上がっていること</b>(裁定284 の周辺。
 *     版数を上げ忘れると、古い JS を掴んだ人には音が無いままになる)</li>
 * </ul>
 */
@SpringBootTest
class BattlePageTest {

    /**
     * 部屋を作るときに選ぶリーダー(《傷痕の闘帝》)。
     * ★リーダーの指定は<b>必須である</b>(デッキファイルを読ませない場合)。
     * どのリーダーでもこの試験の結論は変わらないので、実装済み文明の1枚を代表に使う。
     */
    private static final String LEADER_CARD_ID = "QTE-M-FIRE-15";

    @Autowired
    WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    /**
     * 部屋を作って盤面のHTMLを取る。
     * ★<b>本物の入口を通す。</b>部屋を直接組み立てると、
     * 「コントローラが 200 を返せるか」ではなく「テストが組んだ物を描けるか」を測ることになる。
     */
    private String battleHtml() throws Exception {
        MvcResult created = mvc().perform(MockMvcRequestBuilders.post("/rooms")
                .param("playerName", "テスト")
                .param("leaderCardId", LEADER_CARD_ID)).andReturn();
        String location = created.getResponse().getRedirectedUrl();
        assertThat(location).as("部屋作成のリダイレクト先").isNotNull().contains("/play");
        MvcResult page = mvc().perform(MockMvcRequestBuilders.get(location)).andReturn();
        assertThat(page.getResponse().getStatus()).as("盤面の応答").isEqualTo(200);
        return page.getResponse().getContentAsString();
    }

    @Test
    void 通常モードの盤面は200で描ける() throws Exception {
        String html = battleHtml();
        assertThat(html).contains("id=\"auto-root\"");
        assertThat(html).contains("id=\"my-hand\"");
    }

    @Test
    void 通常モードの盤面に音の設定がある() throws Exception {
        String html = battleHtml();
        assertThat(html).as("[♪] ボタン").contains("id=\"btn-sound\"");
        assertThat(html).as("音の設定モーダル").contains("id=\"sound-modal\"");
        assertThat(html).as("ミュート").contains("id=\"sound-mute\"");
        assertThat(html).as("音量").contains("id=\"sound-volume\"");
        // ★「なぜ鳴らないのか」を出す唯一の場所(裁定76)。ここが無いと理由を出す先が消える
        assertThat(html).as("状態行").contains("id=\"sound-modal-status\"");
    }

    @Test
    void 通常モードの盤面のJSの版数が上がっている() throws Exception {
        String html = battleHtml();
        // ★62 で battle.js に音を足したので v=26 のままではいけない
        assertThat(html).doesNotContain("battle.js?v=26");
        assertThat(html).contains("battle.js?v=");
    }
}
