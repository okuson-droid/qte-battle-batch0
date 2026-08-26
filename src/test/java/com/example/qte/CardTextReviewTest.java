package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.master.CardMaster;
import com.example.qte.master.CardMasterRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 本文と実装の突き合わせ台帳({@code src/test/resources/text-impl-review.json})の番人
 * (★Batch 67 で新設)。
 *
 * <h2>なぜ要るのか —— 「未実装0枚」が守ってくれなかったもの</h2>
 *
 * {@code tools/report_effects.py} の「未実装0枚」は、<b>登録が在るか</b>しか見ていない
 * (裁定303)。「登録が本文どおりか」は測っていない。
 * その結果、Ver1.1 で本文が差し替わったのに実装が Ver0.4 のまま残ったカードが
 * <b>63 まで動き続けた</b> ——
 *
 * <ul>
 *   <li>Batch 64: 《不滅のネクロマンサー》(蘇生 → 相手が1枚引く)</li>
 *   <li>★Batch 67: 《大地震》(3以下 → 4以下)・《聖剣 エクスカリバー》(2回復 → 全快)・
 *       《生贄を求める邪鬼》(自壊 → 自分2体+相手1体)・
 *       《禁忌の墓地利用》(スペル → ミニオンでないカード)・
 *       《ツイン・ストライク》(文明の絞り込み)</li>
 * </ul>
 *
 * <h2>この試験が測っていること(と、測っていないこと)</h2>
 *
 * 「本文と実装が一致しているか」は<b>機械には測れない</b>。
 * 機械に測れるのは<b>人が突き合わせたかどうか</b>である。
 * この台帳は、突き合わせた時点の本文のハッシュを覚えておき、
 * <b>本文が変わったのに突き合わせ直されていないカード</b>を赤にする。
 *
 * <p>★<b>46b の一括移行のときにこの番人が在れば、235枚が一斉に赤くなっていた。</b>
 * 《不滅のネクロマンサー》も、67 が見つけた5枚も、その赤の中に居たはずである。
 *
 * <p>★<b>この台帳は「過去」を保証しない。</b>初回登録の {@code reviewedIn} は
 * 当時の記録からの推定であり、当時の本文のハッシュは残っていない
 * (Ver0.4 の台帳 {@code qte-cards.json} は Batch 60 で削除した)。
 * 保証するのは未来だけである —— 以後、本文が1文字でも変われば必ず赤くなる。
 *
 * <h2>★赤くなったときにやること</h2>
 *
 * <b>ハッシュを更新して緑にするのではない。</b>そのカードの本文と実装を
 * 突き合わせ直し、直すべきものを直してから記録すること。
 *
 * <pre>
 *   python3 tools/mark_text_reviewed.py --card QTE-M-XXX-N --batch NN --note "何を確かめたか"
 * </pre>
 *
 * <p>{@code --note} を必須にしてあるのは、<b>理由を書かずに緑にできないようにする</b>ためである。
 * 台帳は緑にするための書類ではなく、点検の記録である(裁定196 の精神)。
 */
@SpringBootTest
class CardTextReviewTest {

    private static final String LEDGER = "/text-impl-review.json";

    @Autowired
    CardMasterRepository cards;

    private Map<String, JsonNode> reviews() {
        try (InputStream in = getClass().getResourceAsStream(LEDGER)) {
            assertThat(in).as("台帳 %s がクラスパスにある", LEDGER).isNotNull();
            JsonNode root = new ObjectMapper().readTree(in);
            Map<String, JsonNode> out = new TreeMap<>();
            root.get("reviews").properties().forEach(e -> out.put(e.getKey(), e.getValue()));
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("台帳を読めませんでした", e);
        }
    }

    /** 本文のハッシュ。改行も空白もそのまま含める(表記の揺れも差である) */
    static String textHash(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append("%02x".formatted(d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 全てのカードが突き合わせ台帳に載っている() {
        Map<String, JsonNode> reviews = reviews();
        List<String> missing = cards.getAllCards().stream()
                .map(CardMaster::id)
                .filter(id -> !reviews.containsKey(id))
                .toList();
        assertThat(missing)
                .as("★台帳に無いカード。新しいカードを足したら、本文と実装を突き合わせて "
                        + "tools/mark_text_reviewed.py で記録すること")
                .isEmpty();
    }

    @Test
    void 台帳にカードマスタの外のIDが残っていない() {
        List<String> known = cards.getAllCards().stream().map(CardMaster::id).toList();
        List<String> unknown = reviews().keySet().stream()
                .filter(id -> !known.contains(id))
                .toList();
        assertThat(unknown)
                .as("★カードマスタから消えたカードの記録が台帳に残っている。"
                        + "誰も守っていない行は消すこと(裁定178)")
                .isEmpty();
    }

    @Test
    void 突き合わせたときから本文が変わっていない() {
        Map<String, JsonNode> reviews = reviews();
        List<String> changed = new ArrayList<>();
        for (CardMaster card : cards.getAllCards()) {
            JsonNode review = reviews.get(card.id());
            if (review == null) {
                continue; // 上の試験が別に落ちる
            }
            String recorded = review.get("textHash").asString();
            if (!recorded.equals(textHash(card.text()))) {
                changed.add("%s(%s)".formatted(card.id(), card.name()));
            }
        }
        assertThat(changed)
                .as("★★本文が変わったのに、本文と実装が突き合わせ直されていないカード。"
                        + "ハッシュを更新して緑にするのではなく、<b>実装が新しい本文どおりか</b>を"
                        + "確かめてから tools/mark_text_reviewed.py で記録すること")
                .isEmpty();
    }

    /**
     * ★空振りでないことの証拠(裁定186)。
     *
     * <p>上の3件は、台帳が空でも「カードが0枚」でも通ってしまう形をしている。
     * ここで台帳が実際に全枚数ぶんを持ち、ハッシュが本文の違いを実際に写していることを測る ——
     * 「常に同じ値を返すだけのハッシュ」では、上の試験は何も守らない。
     *
     * <p>★<b>「ハッシュはカードごとに違う」と書いてはいけない。</b>
     * 本文が完全に同じカードが実際に居る ——《ライト・シールド》と《背水の烈火使い》は
     * どちらも本文が「【守護】」の1語であり、ほかにも【知識】だけの4枚などがある。
     * 測るべきは<b>異なるハッシュの数 = 異なる本文の数</b>であって、枚数ではない。
     */
    @Test
    void 台帳は全枚数ぶんの記録を持ちハッシュは本文の違いを写している() {
        Map<String, JsonNode> reviews = reviews();
        assertThat(reviews).as("台帳の件数はカードの枚数と同じ").hasSize(cards.getAllCards().size());
        long distinctTexts = cards.getAllCards().stream()
                .map(CardMaster::text).distinct().count();
        long distinctHashes = reviews.values().stream()
                .map(n -> n.get("textHash").asString())
                .distinct().count();
        assertThat(distinctHashes)
                .as("★異なるハッシュの数が、異なる本文の数と一致する"
                        + "(常に同じ値を返すハッシュなら 1 になる)")
                .isEqualTo(distinctTexts);
        assertThat(distinctHashes)
                .as("★空振りでないことの証拠: 本文はカードごとにほとんど違う")
                .isGreaterThan(200);
    }

    /**
     * ★記録には理由が要る。
     *
     * <p>{@code reviewedIn} と {@code note} が空の行を許すと、
     * 台帳は「緑にするために埋めた表」に退化する。
     */
    @Test
    void 台帳のすべての行が突き合わせたバッチと理由を持つ() {
        List<String> bad = new ArrayList<>();
        reviews().forEach((id, node) -> {
            boolean ok = node.has("reviewedIn") && !node.get("reviewedIn").asString().isBlank()
                    && node.has("note") && !node.get("note").asString().isBlank();
            if (!ok) {
                bad.add(id);
            }
        });
        assertThat(bad).as("★reviewedIn と note が空の行").isEmpty();
    }
}
