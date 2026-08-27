package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.effect.TargetChoice;
import com.example.qte.game.GameService;
import com.example.qte.game.ManaCard;
import com.example.qte.game.MinionInstance;
import com.example.qte.game.PlayerState;
import com.example.qte.master.CardMasterRepository;
import com.example.qte.support.AutoGameFixture;

/**
 * ★★★Batch 70 ②: 「今プレイしているカード」を画面に出すための土台(指摘2)。
 *
 * <h2>何が要ったか</h2>
 *
 * マスターの指摘は「効果を解決している最中は、プレイ中のカードを出してほしい」である。
 * 出すには<b>カードID</b>が要るが、64 が作った問い合わせ({@code PendingChoice})は
 * 候補と案内文しか運んでいなかった。
 *
 * <p>★<b>クライアントに覚えさせるのは誤りである。</b>
 * 「直前に自分が送ったカード」を覚えておく手もあるが、
 * 割り込みは<b>相手のターンにも来る</b>(【破壊時】など)ので、
 * そのとき覚えていた値は別のカードを指す。
 *
 * <h2>どこで写し取るか</h2>
 *
 * 問い合わせを<b>作る</b>場所は20箇所以上あるが、<b>積む</b>口は
 * {@code GameActions.requestChoice} 1つしかない(64 が控え(expectedCardIds)を
 * ここで取っているのとまったく同じ理由である)。
 * だから「いま解決しているカード」を {@code GameState.resolvingCardId} に置いておき、
 * 積む口で写し取る。
 *
 * <p>★★★<b>置く側も1箇所にした。</b>{@code CardEffectRegistry.runEffect} が
 * 効果のラムダを呼ぶ唯一の口であり、直呼び({@code effect.accept(ctx)})は
 * 本番のコードに1つも残っていない —— <b>残すと、そのカードのときだけ
 * 表示が黙って出なくなる</b>(69 の教訓・途中)。それをこの試験が見張る。
 */
@SpringBootTest
class Batch70PlayingCardTest {

    private static final String PLAIN_LEADER = "QTE-M-WATER-1";
    /** 選択の追い風: 2枚引いたあと「捨ててもよい」を問う(ResumePoint.TAILWIND_DISCARD) */
    private static final String TAILWIND = "QTE-M-WIND-25";
    /** 腐臭の投擲手: 【召喚時】に相手のミニオン1体を選ぶ(68 で割り込みへ移った15枚の1つ) */
    private static final String ROT_THROWER = "QTE-M-DARK-17";
    /** マナの中身に使うスペル */
    private static final String MAGMA = "QTE-M-FIRE-10";
    /** 相手の場に置く、誘発を持たないミニオン */
    private static final String SHIELD = "QTE-M-LIGHT-2";

    @Autowired
    private GameService game;

    @Autowired
    private CardMasterRepository cards;

    private AutoGameFixture newGame() {
        AutoGameFixture f = new AutoGameFixture(cards, PLAIN_LEADER, PLAIN_LEADER);
        f.fillDeck(f.me(), 40);
        f.fillDeck(f.you(), 40);
        return f;
    }

    private void payMana(PlayerState p, int count) {
        for (int i = 0; i < count; i++) {
            p.getManaZone().add(new ManaCard(MAGMA, false));
        }
    }

    // ==================================================================
    // 1. 問い合わせが「どのカードから出たか」を運ぶ
    // ==================================================================

    /** スペルの解決中に立った問い合わせは、そのスペルのIDを運ぶ */
    @Test
    void スペルの問い合わせは出どころのカードIDを運ぶ() {
        AutoGameFixture f = newGame();
        payMana(f.me(), f.card(TAILWIND).cost());

        game.playCard(f.room(), "me", f.giveHand(f.me(), TAILWIND), List.of(), false);

        assertThat(f.me().getPendingChoice()).isNotNull();
        assertThat(f.me().getPendingChoice().sourceCardId())
                .as("★画面はこのIDから「プレイ中のカード」の面を描く")
                .isEqualTo(TAILWIND);
    }

    /**
     * ★<b>ミニオンの【召喚時】でも同じ値が入る</b>(マスター確認)。
     * 68 で【召喚時】の対象が割り込みへ移ったので、問い合わせが出る場面は
     * <b>ミニオンのほうが多い</b>(15枚)。
     */
    @Test
    void ミニオンの召喚時の問い合わせも出どころのカードIDを運ぶ() {
        AutoGameFixture f = newGame();
        payMana(f.me(), f.card(ROT_THROWER).cost());
        f.putOnField(f.you(), SHIELD);

        game.playCard(f.room(), "me", f.giveHand(f.me(), ROT_THROWER), List.of(), false);

        assertThat(f.me().getPendingChoice()).isNotNull();
        assertThat(f.me().getPendingChoice().sourceCardId())
                .as("★「今どのカードを解決しているか」はスペルかミニオンかで変わる性質ではない")
                .isEqualTo(ROT_THROWER);
    }

    /**
     * ★<b>解決が終われば元に戻る。</b>{@code resolvingCardId} は入れ子の解決に耐えるよう
     * 前の値へ戻す形にしてあり、静まったあとに値が残っていてはいけない ——
     * 残ると<b>次の問い合わせが前のカードの名前で出る</b>。
     */
    @Test
    void 解決が終わればいま解決中のカードは残らない() {
        AutoGameFixture f = newGame();
        payMana(f.me(), f.card(ROT_THROWER).cost());
        MinionInstance victim = f.putOnField(f.you(), SHIELD);

        game.playCard(f.room(), "me", f.giveHand(f.me(), ROT_THROWER), List.of(), false);
        assertThat(f.state().getResolvingCardId())
                .as("★問い合わせを積んだ時点でも、呼び出し元は既に戻っている(64: 中断ではなく後回し)")
                .isNull();

        f.answerChoice(game, "me", victim.getInstanceId());

        assertThat(f.state().getResolvingCardId())
                .as("★答えたあとも残らない")
                .isNull();
        assertThat(f.me().getPendingChoice()).isNull();
    }

    // ==================================================================
    // 2. 効果を呼ぶ口が1つであること(69 の教訓・途中)
    // ==================================================================

    /**
     * ★★★<b>効果のラムダを呼ぶのは {@code runEffect} だけである。</b>
     *
     * <p>直呼び({@code effect.accept(ctx)})を1つ足すと、そのカードのときだけ
     * 「今解決しているカード」が空のままになり、<b>プレイ中の表示が黙って出なくなる</b>。
     * 症状はそのカードでしか出ないので、気づかれずに残る ——
     * 69 の教訓「途中」がそのまま当てはまる形である。
     *
     * <p>★測り方: 唯一の口を挟んでいる<b>合図のコメント</b>の間だけを取り除き、
     * 残りに {@code .accept(} が1つも無いことを見る。
     */
    @Test
    void 効果を呼ぶ口はrunEffectだけである() throws IOException {
        String registry = read("src/main/java/com/example/qte/effect/CardEffectRegistry.java");
        String service = read("src/main/java/com/example/qte/game/GameService.java");

        String outside = stripSingleGate(registry);
        assertThat(outside)
                .as("★CardEffectRegistry の直呼びは runEffect の中だけである")
                .doesNotContain(".accept(");
        assertThat(service)
                .as("★GameService も効果を直接呼ばない(賢魂・特殊召喚・起動能力も runEffect を通る)")
                .doesNotContain(".accept(");
        assertThat(registry)
                .as("★唯一の口そのものは残っている(合図のコメントごと消して緑にできない形にする)")
                .contains("public void runEffect(String cardId, EffectContext ctx,");
    }

    /** 合図のコメントに挟まれた「唯一の口」を取り除く */
    private String stripSingleGate(String source) {
        String open = "// ===== ★★★Batch 70: 効果を呼ぶ唯一の口 —— ここから";
        String close = "// ===== ★★★Batch 70: 効果を呼ぶ唯一の口 —— ここまで =====";
        int from = source.indexOf(open);
        int to = source.indexOf(close);
        assertThat(from).as("合図のコメント(ここから)が在る").isGreaterThanOrEqualTo(0);
        assertThat(to).as("合図のコメント(ここまで)が在る").isGreaterThan(from);
        return source.substring(0, from) + source.substring(to + close.length());
    }

    // ==================================================================
    // 3. 静的ファイルの版数(7-5)
    // ==================================================================

    /**
     * ★★★{@code battle.css} を読む<b>5枚のテンプレートの版数が揃っている</b>こと。
     *
     * <p>7-5 は 69 で「触ったら5枚とも上げる」と書いたが、
     * <b>それを測る番人は1つも無かった</b>({@code BattlePageTest} が見ているのは
     * {@code battle.html} 1枚だけである)。
     * ★1枚だけ上げると、<b>古い版数で要求したページだけ</b>が
     * ブラウザのキャッシュから古い CSS を受け取り続ける ——
     * 症状が「特定のページでだけ見た目が古い」という形で出るので、いちばん見つけにくい。
     */
    @Test
    void battleCssを読む5枚のテンプレートは同じ版数である() throws IOException {
        List<String> templates = List.of("battle.html", "cards.html", "manual-cards.html",
                "manual-battle.html", "manual-deck-maker.html");
        Pattern pattern = Pattern.compile("/css/battle\\.css\\(v=(\\d+)\\)");
        java.util.Map<String, String> versions = new java.util.LinkedHashMap<>();
        for (String name : templates) {
            String html = read("src/main/resources/templates/" + name);
            Matcher m = pattern.matcher(html);
            assertThat(m.find()).as("%s が battle.css を版数つきで読んでいる", name).isTrue();
            versions.put(name, m.group(1));
        }
        assertThat(versions.values().stream().distinct().toList())
                .as("★5枚の版数が揃っている(実際の値: %s)", versions)
                .hasSize(1);
    }

    private String read(String relative) throws IOException {
        return Files.readString(Path.of(relative), StandardCharsets.UTF_8);
    }

    /** 未使用の警告を避けるための参照(TargetChoice は答えの形として文書に出る) */
    @SuppressWarnings("unused")
    private static final Class<TargetChoice> TARGET_CHOICE = TargetChoice.class;
}
