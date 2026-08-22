package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 通常モードのロビーの試験(★Batch 63)。
 *
 * <h2>なぜ要るのか</h2>
 *
 * 63 が直したのは<b>「デッキメーカーで組んだデッキが通常モードで読み込めない」</b>である。
 * {@link DeckFileReaderTest} は読み取りの単体を測るが、マスターが踏んだのは
 * <b>ロビーの [部屋を作成して入室] を押したとき</b>である。
 * ★<b>本物の入口から通らなければ、直ったとは言えない</b>(62 の教訓7)。
 *
 * <p>合わせて、退役した {@code /deck-builder} が本当に消えていることも測る ——
 * テンプレートを消してもマッピングが残っていれば 500 になるし、
 * マッピングを消してもロビーのリンクが残っていれば 404 へ案内し続ける。
 * <b>消し忘れは「消したつもり」の形でしか現れない。</b>
 */
@SpringBootTest
class LobbyPageTest {

    /** ★実物の確認用デッキ(デッキメーカーが書く形式)。テスト用に作った偽物ではない */
    private static final Path SAMPLE_DECK = Path.of("decks/batch54-dark-check-deck.json");

    @Autowired
    WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    // ------------------------------------------------------------------
    // ★★★63 が直したもの
    // ------------------------------------------------------------------

    @Test
    void デッキメーカーで組んだデッキで部屋を作って盤面まで行ける() throws Exception {
        String deckJson = Files.readString(SAMPLE_DECK, StandardCharsets.UTF_8);
        MvcResult created = mvc().perform(MockMvcRequestBuilders.post("/rooms")
                .param("playerName", "テスト")
                .param("deckJson", deckJson)).andReturn();

        String location = created.getResponse().getRedirectedUrl();
        assertThat(location).as("部屋作成のリダイレクト先").isNotNull().contains("/play");

        MvcResult page = mvc().perform(MockMvcRequestBuilders.get(location)).andReturn();
        assertThat(page.getResponse().getStatus()).as("盤面の応答").isEqualTo(200);
    }

    /**
     * ★旧形式を渡したときは、<b>ロビーに戻って理由が読める</b>こと。
     * 62 までは理由を握りつぶして「デッキファイルの形式が正しくありません」だけを出しており、
     * 何を直せばよいのか分からなかった。
     */
    @Test
    void 旧形式のデッキを渡すと直し方が画面に出る() throws Exception {
        MvcResult result = mvc().perform(MockMvcRequestBuilders.post("/rooms")
                .param("playerName", "テスト")
                .param("deckJson", """
                        {"formatVersion": 1, "name": "旧", "leaderCardId": "QTE-M-DARK-29",
                         "main": [{"cardId": "QTE-M-DARK-37", "count": 4}], "taboo": []}
                        """)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String html = result.getResponse().getContentAsString();
        assertThat(html).as("直し方").contains("デッキメーカーで保存し直してください");
    }

    // ------------------------------------------------------------------
    // 退役の確認
    // ------------------------------------------------------------------

    @Test
    void ロビーはデッキメーカーへ案内する() throws Exception {
        MvcResult result = mvc().perform(MockMvcRequestBuilders.get("/auto")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String html = result.getResponse().getContentAsString();
        assertThat(html).as("デッキメーカーへのリンク").contains("/deck-maker");
        assertThat(html).as("退役した画面へのリンクが残っていない").doesNotContain("/deck-builder");
    }

    @Test
    void 退役したデッキビルダーは開けない() throws Exception {
        MvcResult result = mvc().perform(MockMvcRequestBuilders.get("/deck-builder")).andReturn();
        assertThat(result.getResponse().getStatus()).as("/deck-builder の応答").isEqualTo(404);
    }

    /**
     * ★{@code /api/cards} はデッキビルダー専用の口だった(この画面のためだけに
     * カードマスタを DTO へ詰め直していた)。画面と一緒に退役している ——
     * <b>使う人が居なくなった配信口を残すと、次に読む人はそれを正だと思う。</b>
     */
    @Test
    void デッキビルダー専用のカード配信口も退役している() throws Exception {
        assertThat(mvc().perform(MockMvcRequestBuilders.get("/api/cards"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc().perform(MockMvcRequestBuilders.get("/api/implemented-civilizations"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }
}
