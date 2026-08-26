package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 通常モードのロビーの試験(★Batch 63 で新設・★Batch 66 で全面的に書き直した)。
 *
 * <h2>なぜ要るのか</h2>
 *
 * 63 が直したのは<b>「デッキメーカーで組んだデッキが通常モードで読み込めない」</b>である。
 * {@link DeckFileReaderTest} は読み取りの単体を測るが、マスターが踏んだのは
 * <b>画面から実際に持ち込んだとき</b>である。
 * ★<b>本物の入口から通らなければ、直ったとは言えない</b>(62 の教訓7)。
 *
 * <h2>★Batch 66 で入口が動いた</h2>
 *
 * 65 までの入口は {@code POST /rooms}(HTML フォーム)で、部屋の作成とデッキの受け取りが
 * 1回の送信に束ねられていた。66 でそれを分けた ——
 * 部屋を作る({@code POST /auto/api/rooms})→ 席に着く → 盤面でデッキを読む
 * ({@code POST /auto/api/rooms/{id}/deck})。手動モードと同じ位置である。
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

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    /** 部屋を作って、作成者(席A)の playerId まで取る。★本物の入口を通す */
    private JsonNode createRoom(String roomName) throws Exception {
        MvcResult created = mvc().perform(MockMvcRequestBuilders.post("/auto/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"displayName":"テスト","roomName":"%s","seat":"A",
                         "spectatorAllowed":true,"requireRoomId":false}
                        """.formatted(roomName))).andReturn();
        assertThat(created.getResponse().getStatus()).as("部屋作成の応答").isEqualTo(200);
        return JSON.readTree(created.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MvcResult loadDeck(String roomId, String playerId, String body) throws Exception {
        return mvc().perform(MockMvcRequestBuilders
                .post("/auto/api/rooms/%s/deck".formatted(roomId))
                .param("playerId", playerId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
    }

    // ------------------------------------------------------------------
    // ★★★63 が直したもの(★66 で入口が動いた)
    // ------------------------------------------------------------------

    @Test
    void デッキメーカーで組んだデッキを席に読み込める() throws Exception {
        JsonNode room = createRoom("読み込み確認");
        String deckJson = Files.readString(SAMPLE_DECK, StandardCharsets.UTF_8);

        MvcResult loaded = loadDeck(room.get("roomId").asText(),
                room.get("playerId").asText(), deckJson);

        assertThat(loaded.getResponse().getStatus()).as("デッキ読み込みの応答").isEqualTo(200);
        JsonNode body = JSON.readTree(
                loaded.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("mainCount").asInt()).as("メインの枚数").isEqualTo(40);
        assertThat(body.get("tabooCount").asInt()).as("禁忌の枚数").isEqualTo(8);
        assertThat(body.get("seat").asText()).as("読み込んだ席").isEqualTo("A");
    }

    /**
     * ★旧形式を渡したときは、<b>直し方が読める</b>こと。
     * 62 までは理由を握りつぶして「デッキファイルの形式が正しくありません」だけを出しており、
     * 何を直せばよいのか分からなかった。
     */
    @Test
    void 旧形式のデッキを渡すと直し方が返る() throws Exception {
        JsonNode room = createRoom("旧形式");

        MvcResult result = loadDeck(room.get("roomId").asText(), room.get("playerId").asText(), """
                {"formatVersion": 1, "name": "旧", "leaderCardId": "QTE-M-DARK-29",
                 "main": [{"cardId": "QTE-M-DARK-37", "count": 4}], "taboo": []}
                """);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        String message = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(message).as("直し方").contains("デッキメーカーで保存し直してください");
    }

    // ------------------------------------------------------------------
    // ★★Batch 66: ロビーの形(手動モードに揃えたもの)
    // ------------------------------------------------------------------

    @Test
    void 部屋一覧に作った部屋が名前つきで出る() throws Exception {
        createRoom("一覧に出る部屋");

        MvcResult listed = mvc().perform(MockMvcRequestBuilders.get("/auto/api/rooms")).andReturn();

        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        String body = listed.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).as("部屋名").contains("一覧に出る部屋");
        assertThat(body).as("席Aの在席者").contains("テスト");
    }

    /** ★鍵つき部屋の部屋IDは一覧に載らない(IDが鍵を兼ねているため) */
    @Test
    void 鍵つき部屋の部屋IDは一覧に載らない() throws Exception {
        MvcResult created = mvc().perform(MockMvcRequestBuilders.post("/auto/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"displayName":"テスト","roomName":"鍵つき部屋","seat":"A",
                         "spectatorAllowed":true,"requireRoomId":true}
                        """)).andReturn();
        String roomId = JSON.readTree(
                created.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("roomId").asText();

        String body = mvc().perform(MockMvcRequestBuilders.get("/auto/api/rooms"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).as("鍵つき部屋も名前は出る").contains("鍵つき部屋");
        assertThat(body).as("★部屋IDは載らない").doesNotContain(roomId);
        // ★IDを知っている人には答える(単体取得)。鍵の意味は損なわれない
        assertThat(mvc().perform(MockMvcRequestBuilders.get("/auto/api/rooms/" + roomId))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    /** ★部屋名は必須である(全公開部屋が無いので、省略できる部屋が1つも無い) */
    @Test
    void 部屋名なしでは部屋を作れない() throws Exception {
        MvcResult result = mvc().perform(MockMvcRequestBuilders.post("/auto/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"displayName":"テスト","roomName":"","seat":"A"}
                        """)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("部屋名");
    }

    /** ★埋まっている席には座れない(画面のボタンを無効にするのは操作補助にすぎない) */
    @Test
    void 埋まっている席には座れない() throws Exception {
        JsonNode room = createRoom("満席の確認");

        MvcResult result = mvc().perform(MockMvcRequestBuilders
                .post("/auto/api/rooms/%s/occupants".formatted(room.get("roomId").asText()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"displayName":"あとから","seat":"A"}
                        """)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("埋まっています");
    }

    /** ★観戦を許可していない部屋では観戦できない(届く宛先を作らない) */
    @Test
    void 観戦を許可していない部屋では観戦できない() throws Exception {
        MvcResult created = mvc().perform(MockMvcRequestBuilders.post("/auto/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"displayName":"テスト","roomName":"観戦不可","seat":"A",
                         "spectatorAllowed":false,"requireRoomId":false}
                        """)).andReturn();
        String roomId = JSON.readTree(
                created.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("roomId").asText();

        MvcResult result = mvc().perform(MockMvcRequestBuilders
                .post("/auto/api/rooms/%s/occupants".formatted(roomId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"displayName":"見物","spectate":true}
                        """)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("観戦");
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

    /**
     * ★★Batch 66: ロビーが手動モードの形になっていること。
     * <b>画面を作ったら MockMvc で 200 と中身を測る</b>(61 の教訓7)。
     */
    @Test
    void ロビーは部屋一覧と暗色を持つ() throws Exception {
        String html = mvc().perform(MockMvcRequestBuilders.get("/auto"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).as("部屋一覧の入れ物").contains("id=\"room-list\"");
        assertThat(html).as("更新ボタン").contains("id=\"refresh-rooms\"");
        assertThat(html).as("暗色(Bootstrap の色モード)").contains("data-bs-theme=\"dark\"");
        assertThat(html).as("暗色(body)").contains("class=\"bg-dark text-light\"");
        assertThat(html).as("観戦の選択").contains("id=\"create-spectator\"");
        assertThat(html).as("鍵の選択").contains("id=\"create-locked\"");
    }

    /**
     * ★★<b>サンプルデッキ(おまかせ)の排除</b>(マスター指示)。
     * ロビーからリーダーのプルダウンと「未選択ならプリセット」の案内が消えていること。
     * ★消し忘れは「消したつもり」の形でしか現れない ——
     * 文言だけ消してプルダウンが残ると、選んでも何も起きない欄になる。
     */
    @Test
    void ロビーにプリセットデッキの導線が残っていない() throws Exception {
        String html = mvc().perform(MockMvcRequestBuilders.get("/auto"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).as("おまかせの案内").doesNotContain("おまかせ");
        assertThat(html).as("プリセットの案内").doesNotContain("プリセット");
        assertThat(html).as("リーダーのプルダウン").doesNotContain("leaderCardId");
        assertThat(html).as("★デッキが必要であることは書いてある").contains("デッキファイルが必要");
    }

    /** ★65 までの入口({@code POST /rooms})は退役している */
    @Test
    void 旧ロビーのフォーム送信先は退役している() throws Exception {
        assertThat(mvc().perform(MockMvcRequestBuilders.post("/rooms").param("playerName", "x"))
                .andReturn().getResponse().getStatus()).isNotEqualTo(200);
        assertThat(mvc().perform(MockMvcRequestBuilders.post("/rooms/join")
                .param("roomId", "AAAAAA").param("playerName", "x"))
                .andReturn().getResponse().getStatus()).isNotEqualTo(200);
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
