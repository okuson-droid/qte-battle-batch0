package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.ObjectMapper;

/**
 * 通常モードの盤面が実際に描けることの試験(★Batch 62 で新設)。
 *
 * <h2>なぜ要るのか</h2>
 *
 * ★<b>Thymeleaf の書き間違いは Java のコンパイルを通る</b> ——
 * 壊れていることが分かるのは、誰かがブラウザで盤面を開いて 500 を見たときである
 * (61 で実際に踏んだ。{@code th:each} と {@code th:replace} を同じタグに書いていた)。
 *
 * <p>★機械検証(verify)が見ているのは<b>テンプレートから作ったハーネス</b>であって、
 * Spring がテンプレートを解決できるかではない。この2つは別のものである。
 *
 * <h2>★Batch 66: 盤面の入口が変わった</h2>
 *
 * 65 までの盤面は {@code /rooms/{id}/play?playerId=...} であり、
 * <b>誰であるかを URL が運んでいた</b>。66 からは運ばない ——
 * 席選択のゲートと localStorage が決める(手動モードの 19a と同じ)。
 */
@SpringBootTest
class BattlePageTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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
        MvcResult created = mvc().perform(MockMvcRequestBuilders.post("/auto/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"displayName":"テスト","roomName":"盤面の確認","seat":"A"}
                        """)).andReturn();
        assertThat(created.getResponse().getStatus()).as("部屋作成の応答").isEqualTo(200);
        String roomId = JSON.readTree(
                created.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("roomId").asText();
        MvcResult page = mvc().perform(
                MockMvcRequestBuilders.get("/rooms/%s/play".formatted(roomId))).andReturn();
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

    /**
     * ★★Batch 66: 席選択とデッキ読み込みのゲートが盤面に載っていること。
     * <b>盤面ページ内のゲートにしてあるのは、直リンクで来た人にも必ず通させるためである。</b>
     * ここが無いと、URL を知っているだけの人が席も名前も無しに盤面へ入る。
     */
    @Test
    void 通常モードの盤面に席選択とデッキ読み込みのゲートがある() throws Exception {
        String html = battleHtml();
        assertThat(html).as("席選択のゲート").contains("id=\"seat-gate\"");
        assertThat(html).as("席A").contains("id=\"seat-gate-a\"");
        assertThat(html).as("席B").contains("id=\"seat-gate-b\"");
        assertThat(html).as("観戦").contains("id=\"seat-gate-spectate\"");
        assertThat(html).as("デッキ読み込みのゲート").contains("id=\"deck-gate\"");
        assertThat(html).as("デッキファイルの入力欄").contains("id=\"deck-gate-file\"");
    }

    /**
     * ★★Batch 66: 盤面は playerId を埋め込まない。
     * <b>埋め込みが残っていると、URL を共有した相手が自分の席として入れる。</b>
     */
    @Test
    void 通常モードの盤面はplayerIdを埋め込まない() throws Exception {
        String html = battleHtml();
        assertThat(html).as("PLAYER_ID の埋め込み").doesNotContain("const PLAYER_ID");
        assertThat(html).as("ROOM_ID は今も埋め込む").contains("const ROOM_ID");
    }

    @Test
    void 通常モードの盤面のJSの版数が上がっている() throws Exception {
        String html = battleHtml();
        // ★66 で battle.js に席選択とデッキ読み込みを足したので v=29 のままではいけない
        // ★★Batch 70: 手札からのドラッグ&ドロップとクリックの確定を足した(裁定318〜323)
        assertThat(html).doesNotContain("battle.js?v=32");
        assertThat(html).contains("battle.js?v=");
    }

    /**
     * ★★★Batch 69: 65 が挙げた盤面の穴のうち、<b>テンプレートに現れる2つ</b>。
     *
     * <p>自陣と敵陣の見分けは<b>クラス2つ</b>で付けてある(色の値は
     * {@code .opponent-side} / {@code .my-side} が 8 以前から持っている)。
     * 進行表は右列の空白({@code #auto-side-mid} の中で実測 225px)を埋める箱である。
     *
     * <p>★ここで測るのは「テンプレートに在るか」だけである ——
     * 色が違って見えるか・空白が実際に埋まっているかは実測でしか測れず、
     * verify 69-1/69-2/69-7 がそちらを持つ。
     */
    @Test
    void 通常モードの盤面は自陣と敵陣を分け進行表の箱を持つ() throws Exception {
        String html = battleHtml();
        assertThat(html).as("相手の場の印").contains("auto-field-opponent");
        assertThat(html).as("自分の場の印").contains("auto-field-self");
        assertThat(html).as("フェイズの進行表").contains("id=\"phase-track\"");
        // ★色の正は今も帯の2本である(69 は値を新しく決めていない)
        assertThat(html).as("相手の帯").contains("opponent-side");
        assertThat(html).as("自分の帯").contains("my-side");
    }

    /**
     * ★★Batch 69 は CSS も変えている(地色・縦帯・0枚のバッジ・進行表)。
     * <b>版数を上げないと、既に開いている人の画面だけ 50 のままになる。</b>
     */
    @Test
    void 通常モードの盤面のCSSの版数が上がっている() throws Exception {
        String html = battleHtml();
        assertThat(html).doesNotContain("battle.css?v=50");
        // ★★Batch 71: 71 も CSS を変えた(切断オーバーレイと接続の帯)。
        //   51 のままだと、既に開いている人には帯の色が1本も届かない
        assertThat(html).doesNotContain("battle.css?v=51");
        assertThat(html).contains("battle.css?v=");
    }

    /**
     * ★★★Batch 71: 通常モードの切断(候補 H)。<b>テンプレートに現れるぶん</b>を測る。
     *
     * <p>★<b>ここで測っているのは「宣言が在るか」だけである。</b>
     * 実際に操作を止めているのは {@code battle.js} の {@code send()} のガードであり、
     * そちらは verify 71-1〜71-14 が実測で見張る(オーバーレイを畳んでも
     * ガードが効いていることまで測っている)。
     *
     * <p>★★<b>この試験を「切断が効いている」の番人だと読まないこと。</b>
     * テンプレートに箱が在ることと、切断中に操作が止まることは別である ——
     * 箱だけを先に作るのは設計判断46 が禁じた「安全側に見える簡易版」そのものであり、
     * この試験<b>だけ</b>が緑なら、それはまさにその状態を意味する。
     */
    @Test
    void 通常モードの盤面に切断オーバーレイと接続の帯がある() throws Exception {
        String html = battleHtml();
        assertThat(html).as("切断オーバーレイ").contains("id=\"auto-offline\"");
        // ★[盤面を確認する]。★<b>畳んでも安全であることが、この導線を置ける根拠である</b>
        assertThat(html).as("覗き見の導線").contains("id=\"auto-offline-peek\"");
        assertThat(html).as("接続の帯").contains("id=\"auto-conn-bar\"");
        // ★帯はヘッダ行の中に置いた(実測で決めた。battle.css の .auto-conn-bar を参照)
        assertThat(html).as("接続表示は今も在る").contains("id=\"connection-status\"");
    }

    /**
     * ★★Batch 71 は {@code battle.js} を変えている(接続の判定・send のガード・
     * 送れなかったときに畳まない扱い)。
     * <b>版数を上げないと、既に開いている人だけが 33 のままガード無しで遊び続ける。</b>
     */
    @Test
    void 通常モードの盤面のJSの版数が71で上がっている() throws Exception {
        String html = battleHtml();
        assertThat(html).doesNotContain("battle.js?v=33");
        assertThat(html).contains("battle.js?v=");
    }
}
