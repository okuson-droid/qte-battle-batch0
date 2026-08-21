package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.qte.master.CardMasterRepository;
import com.example.qte.master.CardTextKeywords;
import com.example.qte.master.Keyword;
import com.example.qte.support.KeywordBaseline;
import com.example.qte.support.Ver11Cards;

import tools.jackson.databind.ObjectMapper;

/**
 * キーワード抽出層({@link CardTextKeywords})の検証(Batch 46a / ★46b で照合の向きを変えた)。
 *
 * このテストが Ver1.1 移行の土台である。移行後は
 * {@code manual-cards.json} のテキストだけがキーワードの出どころになるため、
 * <b>「テキストから読んだ結果」が「人手で付けた台帳の keywords」と一致すること</b>を
 * 実データ169枚で確かめておかないと、移行の瞬間に全カードの挙動が静かに変わる。
 *
 * <p>★期待値を書き並べていない(裁定110)。突き合わせているのは
 * <b>凍結した物差し {@code keyword-baseline.json} から読んだ値</b>と
 * <b>Ver1.1 のテキストから読んだ値</b>であり、どちらもファイルから来る。
 * テストが持っているのは、両者が食い違う9枚の表だけである。
 *
 * <h2>★Batch 46b で変えたこと(マスター裁定: 照合は残す)</h2>
 *
 * 46b で {@link CardMasterRepository} が読むファイルが Ver1.1 に変わったため、
 * <b>台帳は本体からは読めなくなった</b>。照合はテスト専用の読み口を経由して続ける ——
 * 素朴な規則では22枚が狂うのだから(裁定159)、169枚の回帰検出を残す価値がある。
 *
 * <p>同時に、照合の<b>右辺を「規則の出力」から「リポジトリが実際に持っている値」へ移した</b>。
 * 46a の時点では本体がまだ台帳を読んでいたので規則を直接叩くしかなかったが、
 * 今はエンジンが持つ値そのものを測れる。<b>規則が正しくても配線を間違えれば挙動は変わる</b>ので、
 * 測るべきは配線の先である。
 *
 * <h2>★Batch 60: 台帳は消え、物差しだけが残った</h2>
 *
 * 60 で {@code qte-cards.json}(101KB・169枚・Ver0.4)を削除した。
 * <b>照合そのものは残す</b>というマスターの判断により、台帳から
 * 「人手で付けたキーワード」169件だけを抜き出して
 * {@code src/test/resources/keyword-baseline.json}(7KB)に凍結し、
 * 読み口を {@link KeywordBaseline} に置き換えてある。
 * ★物差しは<b>Ver1.1 のカードIDで引く</b>ので、{@code ledgerCardId} を経由しない ——
 * 台帳という概念はこのテストから完全に消えた。
 */
@SpringBootTest
class CardTextKeywordsTest {

    /** ★46b: これは Ver1.1(235枚)を読むリポジトリである。台帳ではない */
    @Autowired
    CardMasterRepository cards;

    @Autowired
    ObjectMapper objectMapper;

    private List<Ver11Cards.Card> ver11;
    private Map<String, Set<Keyword>> baseline;

    /** Ver1.1 のカード定義。Jackson の設定は Spring から借りるので、読み込みは初回参照時に行う */
    private List<Ver11Cards.Card> ver11Cards() {
        if (ver11 == null) {
            ver11 = Ver11Cards.load(objectMapper);
        }
        return ver11;
    }

    /** Ver1.1 のカードID → 人手で付けられていたキーワード(凍結した物差し・169件) */
    private Map<String, Set<Keyword>> baseline() {
        if (baseline == null) {
            baseline = KeywordBaseline.load(objectMapper);
        }
        return baseline;
    }

    // ------------------------------------------------------------------
    // 規則そのもの(テキストを直に渡す)
    // ------------------------------------------------------------------

    @Test
    void 並んだキーワードをそのまま拾う() {
        // 知識の守り手(QTE-M-WATER-17)
        assertThat(CardTextKeywords.extract("【知識】【還元】【守護】【突進】"))
                .containsExactlyInAnyOrder(Keyword.KNOWLEDGE, Keyword.RESTORATION,
                        Keyword.GUARD, Keyword.RUSH);
    }

    @Test
    void 効果文の末尾にあるキーワードも拾う() {
        // カース・ボーン(QTE-M-DARK-2): 【召喚時】の文のあとに【還元】が置かれている
        assertThat(CardTextKeywords.extract("【召喚時】自分のミニオンを1体破壊する。【還元】"))
                .containsExactly(Keyword.RESTORATION);
    }

    @Test
    void 他のカードを指す参照は拾わない() {
        // フレイム・スナイプ(スペル)。守護を持つのは相手のミニオンである
        assertThat(CardTextKeywords.extract("相手の【守護】を持つHP5以下のミニオンを1体選び破壊"))
                .isEmpty();
        // そよ風の加護(スペル)。守護を得るのは対象のミニオンである
        assertThat(CardTextKeywords.extract("自分のミニオン1体の体力を+1し、【守護】を付与"))
                .isEmpty();
        // 秩序の執行官。特殊召喚を行えないのは相手である
        assertThat(CardTextKeywords.extract("相手はメインフェーズに【特殊召喚】を行えない。"))
                .isEmpty();
        // 赫灼の重戦士。速攻を「得る」のは条件を満たしたときだけである
        assertThat(CardTextKeywords.extract("【召喚時】自分のリーダーの体力が10以下ならこれは【速攻】を得る。"))
                .isEmpty();
    }

    @Test
    void 条件付きの付与は拾わない() {
        // ガイル・フォックス。常に潜伏なのではない
        assertThat(CardTextKeywords.extract("このターン中にカードを3枚以上使用しているなら【潜伏】。"))
                .isEmpty();
        // 読点をはさんでも条件である。
        // ★この1行が、規則の中で「実データが1枚も通っていない唯一の枝」の番人である。
        //   Ver1.1 の235枚には「〜なら、【…】」という書き方が無いため、規則から読点の許容を
        //   外しても台帳との突き合わせは1枚も落ちない(裁定135 と同じ形の穴)。
        //   将来そう書かれたカードが来たときのために残している枝なので、ここで測っておく。
        assertThat(CardTextKeywords.extract("手札が5枚以上なら、【守護】。")).isEmpty();
    }

    @Test
    void 同じテキストの中で自前と参照が混ざっていても切り分ける() {
        // 聖域の案内人(QTE-M-LIGHT-3)。先頭の【知識】と末尾の【守護】は自前、
        // 途中の「【守護】を持つ」と「【知識】を行う」は参照である
        String text = "【知識】自分の場に他の【守護】を持つミニオンがいるなら、"
                + "さらにもう一度【知識】を行う。【守護】";
        assertThat(CardTextKeywords.extract(text))
                .containsExactlyInAnyOrder(Keyword.KNOWLEDGE, Keyword.GUARD);
    }

    @Test
    void 進化の召喚条件に書かれた語はキーワードにしない() {
        // 海淵獣シラーカ(QTE-M-WATER-30)。括弧の中の「潜伏を持たない」は素材の条件であり、
        // 末尾の【潜伏】【知識】だけがこのカード自身の能力である
        String text = "【進化】（水文明の潜伏を持たないミニオン1体）【潜伏】【知識】";
        assertThat(CardTextKeywords.extract(text))
                .containsExactlyInAnyOrder(Keyword.STEALTH, Keyword.KNOWLEDGE);
    }

    @Test
    void 効果テキストが無いカードは空になる() {
        assertThat(CardTextKeywords.extract(null)).isEmpty();
        assertThat(CardTextKeywords.extract("")).isEmpty();
        assertThat(CardTextKeywords.extract("   ")).isEmpty();
    }

    // ------------------------------------------------------------------
    // 実データ全件(ここが本体)
    // ------------------------------------------------------------------

    /**
     * 台帳の {@code keywords} と、Ver1.1 のテキストから読んだ結果が食い違う9枚。
     *
     * <b>すべて Ver1.1 でカードの内容そのものが変わったものであり、抽出の誤りではない。</b>
     * 移行後は右側(テキストから読んだ値)が正になる —— つまりこの9枚は
     * 通常モードでの<b>挙動が変わる</b>。遊び味に関わるので、ここに全件を残す。
     *
     * 表に載っていないカードで差が出たら、それは規則かデータのどちらかが壊れた合図である。
     */
    private static final Map<String, Set<Keyword>> VER11_KEYWORD_CHANGES = new LinkedHashMap<>();

    static {
        // 【突進】が増えた
        VER11_KEYWORD_CHANGES.put("QTE-M-WATER-17", // 知識の守り手
                Set.of(Keyword.KNOWLEDGE, Keyword.RESTORATION, Keyword.GUARD, Keyword.RUSH));
        // 【潜伏】が増えた
        VER11_KEYWORD_CHANGES.put("QTE-M-WATER-18", // 波濤の突撃兵
                Set.of(Keyword.STEALTH, Keyword.RUSH));
        // ★裁定155: 【守護】【知識】の消失は Ver1.1 の意図どおり(マスター確定)
        VER11_KEYWORD_CHANGES.put("QTE-M-WATER-19", // 英知の継承者
                Set.of());
        // 効果文が丸ごと差し替わり、【還元】が増えた
        VER11_KEYWORD_CHANGES.put("QTE-M-DARK-2", // カース・ボーン
                Set.of(Keyword.RESTORATION));
        // 発動条件が緩くなり、【知識】が増えた
        VER11_KEYWORD_CHANGES.put("QTE-M-DARK-4", // 裏切りの魔女
                Set.of(Keyword.KNOWLEDGE));
        // 【守護】が増えた
        VER11_KEYWORD_CHANGES.put("QTE-M-LIGHT-3", // 聖域の案内人
                Set.of(Keyword.KNOWLEDGE, Keyword.GUARD));
        // 【知識】が増えた
        VER11_KEYWORD_CHANGES.put("QTE-M-LIGHT-16", // 煌めきの盾
                Set.of(Keyword.GUARD, Keyword.KNOWLEDGE));
        // 【特殊召喚】が増えた(条件は括弧内に明記された)
        VER11_KEYWORD_CHANGES.put("QTE-M-WIND-8", // ストーム・カイザー
                Set.of(Keyword.HASTE, Keyword.SPECIAL_SUMMON));
        // 効果文が丸ごと差し替わり、【還元】が増えた(スペルの還元は台帳にも8枚ある)
        VER11_KEYWORD_CHANGES.put("QTE-M-WIND-12", // 神風の大号令
                Set.of(Keyword.RESTORATION));
    }

    @Test
    void 物差しと対応づく169枚のうち食い違うのは既知の9枚だけである() {
        // ★46b: 左辺は「リポジトリが実際に持っている値」である(規則の出力ではない)。
        // 規則が正しくても、リポジトリの配線を間違えれば挙動は変わる。測るのは配線の先。
        Map<String, String> unexpected = new LinkedHashMap<>();
        int compared = 0;
        for (Ver11Cards.Card card : ver11Cards()) {
            if (!baseline().containsKey(card.id())) {
                continue; // 新カード66枚。Ver0.4 に相手がいないので突き合わせられない
            }
            compared++;
            Set<Keyword> inEngine = cards.findById(card.id()).keywords();
            Set<Keyword> expected = VER11_KEYWORD_CHANGES.containsKey(card.id())
                    ? VER11_KEYWORD_CHANGES.get(card.id())
                    : baseline().get(card.id());
            if (!inEngine.equals(expected)) {
                unexpected.put(card.id() + " " + card.name(),
                        "期待 " + sorted(expected) + " / エンジン " + sorted(inEngine));
            }
        }
        assertThat(compared).isEqualTo(169);
        assertThat(unexpected).as("物差しと食い違うのは表に書いた9枚だけのはず").isEmpty();
    }

    @Test
    void リポジトリのキーワードは全235枚が抽出規則の出力そのものである() {
        // ★裁定158 の番人: 抽出結果を表に焼き付けていないこと。
        // どこかに人手の表を挟んだ瞬間、テキストと表という2つの正ができる。
        Map<String, String> mismatched = new LinkedHashMap<>();
        for (Ver11Cards.Card card : ver11Cards()) {
            Set<Keyword> inEngine = cards.findById(card.id()).keywords();
            Set<Keyword> fromText = CardTextKeywords.extract(card.text());
            if (!inEngine.equals(fromText)) {
                mismatched.put(card.id() + " " + card.name(),
                        "エンジン " + sorted(inEngine) + " / 抽出 " + sorted(fromText));
            }
        }
        assertThat(mismatched).as("リポジトリがテキスト以外からキーワードを作っている").isEmpty();
    }

    @Test
    void 既知の差分表に載っているカードは実際に差分である() {
        // ★裁定116・135 の系: 表がただの飾りになっていないことを確かめる。
        // Ver1.1 側が台帳に寄せられたのに表だけ残る、という取り残しをここで検出する。
        Map<String, Ver11Cards.Card> byId = new LinkedHashMap<>();
        ver11Cards().forEach(c -> byId.put(c.id(), c));
        for (String id : VER11_KEYWORD_CHANGES.keySet()) {
            Ver11Cards.Card card = byId.get(id);
            assertThat(card).as("差分表のカードが Ver1.1 に存在しない: " + id).isNotNull();
            assertThat(baseline()).as("差分表のカードが物差しに載っていない: " + id).containsKey(id);
            assertThat(VER11_KEYWORD_CHANGES.get(id))
                    .as("差分表の " + id + " は物差しと同じ内容になっている(表から消すこと)")
                    .isNotEqualTo(baseline().get(id));
        }
    }

    // ------------------------------------------------------------------
    // 語彙の棚卸し
    // ------------------------------------------------------------------

    /**
     * Ver1.1 全235枚に現れる <code>【…】</code> の語彙。数値を伴う語は {@code 起動：n} の形に畳む。
     *
     * ★この集合は「エンジンが意味を知っている語彙」の一覧ではない。
     * 【常在】【進化】【賢魂：n】はまだエンジンに無く、P2〜P4 で実装する。
     * ここが守っているのは<b>「知らない語彙が黙って増えないこと」</b>だけである
     * —— カードデータを差し替えたときに新しい仕組みが要ることに、その場で気づくための番人である。
     */
    private static final Set<String> KNOWN_VOCABULARY = Set.of(
            // キーワード能力(Keyword に対応する。9種)
            "速攻", "突進", "守護", "潜伏", "威圧", "貫通", "知識", "還元", "特殊召喚",
            // 誘発のタイミング(TriggerType に対応する)
            "召喚時", "破壊時", "起動：n",
            // ★エンジンに無い語彙。Ver1.1 で新しく必要になる仕組み
            "常在", "進化", "賢魂：n");

    @Test
    void 全235枚の語彙が既知のものだけである() {
        Set<String> unknown = new TreeSet<>();
        for (Ver11Cards.Card card : ver11Cards()) {
            for (String token : CardTextKeywords.vocabulary(card.text())) {
                if (!KNOWN_VOCABULARY.contains(token)) {
                    unknown.add(token + " (" + card.id() + " " + card.name() + ")");
                }
            }
        }
        assertThat(ver11Cards()).hasSize(235);
        assertThat(unknown).as("エンジンの知らない語彙が増えている").isEmpty();
    }

    @Test
    void 既知の語彙はすべて実際に使われている() {
        // 使われなくなった語彙が一覧に残り続けると、一覧が現状を語らなくなる。
        Set<String> used = new TreeSet<>();
        ver11Cards().forEach(c -> used.addAll(CardTextKeywords.vocabulary(c.text())));
        assertThat(used).containsExactlyInAnyOrderElementsOf(KNOWN_VOCABULARY);
    }

    // ------------------------------------------------------------------
    // ★Batch 47: 効果の文が残るか(「効果未実装」の印の前提)
    // ------------------------------------------------------------------

    /**
     * ★<b>括弧の扱いが分かれる理由</b>を名指しで測る。
     *
     * <p>キーワードの直後の丸括弧は<b>ふつう注釈</b>である ——
     * 「【威圧】(相手の攻撃対象にならない)」は威圧の意味を言い換えているだけで、
     * そのカード固有の効果ではない。ここを落とさないと、
     * バニラに近いカードにまで「効果未実装」の印が付く。
     *
     * <p>ただし<b>【特殊召喚】だけは括弧の中身が発動条件そのもの</b>であり、
     * エンジンは {@code specialSummons} への登録を必要とする。落としてはいけない。
     * この1つの例外を間違えると、未実装の特殊召喚が黙って不発になる。
     */
    @Test
    void キーワードの注釈の括弧は効果の文として数えない() {
        // 注釈 = 落とす
        assertThat(CardTextKeywords.hasEffectSentence("【突進】\n【威圧】(相手の攻撃対象にならない)"))
                .isFalse();
        assertThat(CardTextKeywords.hasEffectSentence("【守護】\n【潜伏】(相手のカードや能力の対象にならない)"))
                .isFalse();
        // 【特殊召喚】の括弧 = 条件そのもの。落とさない
        assertThat(CardTextKeywords.hasEffectSentence("【特殊召喚】(自分の手札が7枚以上なら手札を3枚捨てて出せる)"))
                .isTrue();
        // 【進化】は Keyword ではないので、そもそも「キーワードの直後」に当たらない
        assertThat(CardTextKeywords.hasEffectSentence("【進化】(自分ミニオン1体以上)【潜伏】【知識】"))
                .isTrue();
    }

    @Test
    void 効果なしと書かれたカードは効果の文を持たない() {
        // ★カードIDではなく表記を見ている。同じ書き方のカードが増えても自動的に効く
        assertThat(CardTextKeywords.hasEffectSentence("効果なし")).isFalse();
        assertThat(CardTextKeywords.hasEffectSentence("【守護】")).isFalse();
        assertThat(CardTextKeywords.hasEffectSentence("")).isFalse();
        assertThat(CardTextKeywords.hasEffectSentence(null)).isFalse();
        assertThat(CardTextKeywords.hasEffectSentence("【召喚時】カードを1枚引く")).isTrue();
    }

    /**
     * Ver1.1 の235枚のうち、効果の文があるのは215枚である(裁定162 の例外。人が決めた数)。
     * ★この数は {@code tools/report_effects.py} の「うち効果の文がある」と一致する。
     * ずれたら、規則を触ったのに片方だけ直した合図である。
     */
    @Test
    void 効果の文があるカードは215枚である() {
        long withSentence = ver11Cards().stream()
                .filter(c -> CardTextKeywords.hasEffectSentence(c.text()))
                .count();
        assertThat(withSentence).isEqualTo(215);
    }

    private static List<String> sorted(Set<Keyword> keywords) {
        return keywords.stream().map(Keyword::getDisplayName).sorted().toList();
    }

}
