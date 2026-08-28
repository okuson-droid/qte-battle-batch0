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

    /**
     * ★★★Batch 72: 試合の出入り(席・退室・投了・再戦)。<b>テンプレートに現れるぶん</b>を測る。
     *
     * <p>★<b>ここで測っているのは「箱が在るか」だけである。</b>
     * どのボタンがいつ出るかは {@code battle.js} の {@code renderRoomControls} が決め、
     * 実際に押せるか・重ならないか・確認を通すかは verify 72-1〜72-16 が実測で見張る。
     * ★★<b>この試験を「席を立てる」の番人だと読まないこと</b>(71 と同じ注意)——
     * 箱が在ることと、席が動くことは別である。
     * ★席が動くこと自体は {@code Batch72SeatTest} が<b>サーバの状態で</b>測っている。
     */
    @Test
    void 通常モードの盤面に試合の出入りの導線がある() throws Exception {
        String html = battleHtml();
        assertThat(html).as("席を立つ / 席に着く").contains("id=\"btn-seat\"");
        assertThat(html).as("投了").contains("id=\"btn-concede\"");
        assertThat(html).as("退室").contains("id=\"btn-leave\"");
        assertThat(html).as("決着の面").contains("id=\"auto-result\"");
        assertThat(html).as("再戦の申し込み").contains("id=\"btn-rematch-offer\"");
        assertThat(html).as("再戦に応じる").contains("id=\"btn-rematch-accept\"");
        assertThat(html).as("再戦を断る").contains("id=\"btn-rematch-decline\"");
        // ★★確認モーダル(裁定53 を通常モードでも守る)。
        //   ★<b>CSS は複製していない</b> —— .auto-confirm は battle.css の
        //     .manual-confirm と同じ宣言ブロックを共有している(71 の「規則は1つ、名前は2つ」)
        assertThat(html).as("確認モーダル").contains("id=\"auto-confirm\"");
        assertThat(html).as("確認の実行").contains("id=\"auto-confirm-ok\"");
        assertThat(html).as("確認の取り消し").contains("id=\"auto-confirm-close\"");
    }

    /**
     * ★★Batch 72 は {@code battle.js} を変えている(確認モーダル・試合の出入り・
     * 席替えのゲート・LEFT の受け取り)。
     * <b>版数を上げないと、既に開いている人だけが 34 のまま投了もできずに遊び続ける。</b>
     */
    @Test
    void 通常モードの盤面のJSの版数が72で上がっている() throws Exception {
        String html = battleHtml();
        assertThat(html).doesNotContain("battle.js?v=34");
        assertThat(html).contains("battle.js?v=");
    }

    /**
     * ★★Batch 72 は CSS も変えている(確認モーダルの重ね順に .auto-confirm を相乗りさせた)。
     * <b>版数を上げないと、既に開いている人の確認モーダルだけが
     * 切断オーバーレイの下ではなく {@code .info-modal} の 1000 で出る。</b>
     */
    @Test
    void 通常モードの盤面のCSSの版数が72で上がっている() throws Exception {
        String html = battleHtml();
        assertThat(html).doesNotContain("battle.css?v=52");
        assertThat(html).contains("battle.css?v=");
    }

    /**
     * ★★★Batch 74 は {@code battle.js} を変えている ——
     * {@code MINION_CARD} の絞り込みが進化ミニオンも通すようになり(裁定341)、
     * 墓地からの召喚の一覧に進化が並び、進化を選んだら素材選択へ進むようになった。
     *
     * <p><b>版数を上げないと、既に開いている人だけが 35 のまま
     * 「サーバは進化を候補として送ってくるのに、画面が灰色のまま押せない」状態になる。</b>
     * ★★<b>Batch 73 とはここが逆である</b> —— 73 は静的ファイルを1つも触っていないので
     * 版数を据え置いた。<b>上げるのも据え置くのも、触ったかどうかだけで決まる</b>(7-5)。
     */
    @Test
    void 通常モードの盤面のJSの版数が74で上がっている() throws Exception {
        String html = battleHtml();
        assertThat(html).doesNotContain("battle.js?v=35");
        assertThat(html).contains("battle.js?v=");
    }

    /**
     * ★★★Batch 75 は {@code battle.js} を変えている ——
     * 部屋消失({@code ROOM_LOST})を受け取って画面を畳む経路が増えた(裁定344・345)。
     *
     * <p><b>版数を上げないと、既に開いている人だけが 36 のまま
     * 「サーバは部屋が消えたと言っているのに、画面は接続済みのまま古い盤面を出し続ける」
     * 状態になる。</b>75 が潰したのはまさにその状態であり、
     * <b>古い JS を掴んだ人にはその修正が1文字も届かない</b>。
     */
    @Test
    void 通常モードの盤面のJSの版数が75で上がっている() throws Exception {
        String html = battleHtml();
        assertThat(html).doesNotContain("battle.js?v=36");
        assertThat(html).contains("battle.js?v=");
    }

    /**
     * ★★★Batch 76(裁定196): 撤去した番人が2つある ——
     * {@code 通常モードの盤面のCSSの版数は74で据え置きである} と
     * {@code …は75でも据え置きである}。
     *
     * <p>どちらも <b>{@code battle.css?v=53} を名指しで要求する</b>形であり、
     * 74・75 が CSS を1文字も触っていないことを測っていた。
     * ★<b>76 は CSS を触った</b>(裏向きマナの名前・裁定351)ので、その値はもう画面に無い ——
     * <b>据え置きの番人は、据え置かなくなった日に役目を終える</b>。
     * ★★守っていた性質(「触っていないのに上げない」)は消えていない:
     * 下の2つが<b>「触ったから上げた」側</b>から同じ規則を見張る。
     * ★★★<b>5枚のテンプレートが同じ版数であること</b>は
     * {@code Batch70PlayingCardTest} が引き続き見張っている(値を持たない番人である)。
     *
     * <p>★<b>2つを消さずにハッシュだけ 54 へ書き換えるのは、番人を殺すことである</b> ——
     * 「74 は触っていない」という<b>過去の事実</b>を測る試験が、
     * <b>76 の値を要求する試験に化ける</b>(リファレンス 1-7)。
     *
     * <p>ここで測るのは 76 の側である。{@code battle.js} は
     * 使用条件の印(裁定350)・マナのホバーと名前(裁定351)・
     * 4枚ぶんの割り込み(裁定346〜349)で変わった。
     * <b>版数を上げないと、既に開いている人だけが 37 のまま
     * 「サーバは選ばせようとしているのに、画面が古い」状態になる。</b>
     */
    @Test
    void 通常モードの盤面のJSの版数が76で上がっている() throws Exception {
        String html = battleHtml();
        assertThat(html).doesNotContain("battle.js?v=37");
        assertThat(html).contains("battle.js?v=");
    }

    /**
     * ★★★Batch 76 は {@code battle.css} を<b>2バッチぶりに触った</b> ——
     * 裏向きマナの名前({@code .mana-tile-back-name})を裏面画像の上に重ねる規則である。
     *
     * <p><b>版数を上げないと、既に開いている人の裏向きマナだけ名前が絵の下に沈む</b> ——
     * 要素は増えるが、重なりの順を決めるのは CSS の側だからである。
     * ★<b>上げるのも据え置くのも「触ったかどうか」だけで決まる</b>(リファレンス 7-5)。
     */
    @Test
    void 通常モードの盤面のCSSの版数が76で上がっている() throws Exception {
        String html = battleHtml();
        assertThat(html).doesNotContain("battle.css?v=53");
        assertThat(html).contains("battle.css?v=");
    }
}
