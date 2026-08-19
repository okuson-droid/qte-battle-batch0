package com.example.qte.master;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * カードテキストからキーワード能力を読み取る層(Batch 46a)。
 *
 * <b>なぜこの層が要るのか。</b> Ver1.1 の正である {@code manual-cards.json} は
 * {@code keywords} フィールドを持たない(Batch 24 hotfix2 で廃止した。テキストが正である)。
 * 一方、通常モードのエンジンは構造化されたキーワードを要求する
 * ({@link com.example.qte.effect.RuleGuards} の【守護】判定など)。
 * 両者をつなぐには「テキストからキーワードを決める」規則がどこかに要る。
 * その規則を1箇所に閉じ込めたのがこのクラスである。
 *
 * <b>状態を持たず、入力はテキストだけである。</b> したがって完全に決定的で、
 * カード1枚を渡せば答えが定まり、テストで全枚数を突き合わせられる。
 * 抽出結果をどこかにキャッシュした表を作らないこと —— 表を作った瞬間に
 * 「テキスト」と「表」という2つの正ができる(設計判断28)。
 *
 * <h2>規則</h2>
 *
 * テキスト中の <code>【…】</code> のうち {@link Keyword} の表示名と一致するものを候補とし、
 * 次の2つを候補から外す。残ったものが<b>そのカード自身が持つ</b>キーワードである。
 *
 * <ol>
 * <li><b>参照</b>: 直後が {@code を持/を与/を付/付与/を行/を得/を失} で始まるもの。
 *     「相手の【守護】を持つミニオンを破壊」「【突進】を付与」「相手は【特殊召喚】を行えない」
 *     は、いずれもそのカードが守護や突進を持つという意味ではない。</li>
 * <li><b>条件付きの付与</b>: 直前が {@code なら/たら/とき/時/場合} で終わるもの。
 *     「カードを3枚以上使用しているなら【潜伏】。」は常に潜伏なのではなく、
 *     条件を満たしたときに得る。<b>常時持つキーワードではないので、ここでは拾わない</b>
 *     (条件付き付与は効果として {@code CardEffectRegistry} 側で表現する)。</li>
 * </ol>
 *
 * <b>★この規則は台帳169枚で機械的に検証してある。</b> 台帳 {@code qte-cards.json} が
 * 人手で付けた {@code keywords} と、この規則が Ver1.1 のテキストから読んだ結果は
 * 169枚中160枚で一致し、残り9枚はすべて Ver1.1 での実変更である
 * (差分の全件と理由は {@code CardTextKeywordsTest} の表に書いてある)。
 * 規則を変えるときは、まずそのテストを見ること。
 *
 * <b>扱わない語彙。</b>【常在】【進化】【賢魂：n】【起動：n】【召喚時】【破壊時】は
 * キーワード能力ではなく、エンジン側の別の仕組みに対応する。ここでは
 * {@link #vocabulary(String)} が語彙として数え上げるだけで、{@link Keyword} には変換しない。
 */
public final class CardTextKeywords {

    private CardTextKeywords() {
    }

    /** テキスト中の <code>【…】</code> 1個 */
    private static final Pattern TOKEN = Pattern.compile("【([^】]*)】");

    /**
     * 参照であることを示す、トークン直後の語。
     * 「【守護】<b>を持つ</b>ミニオン」のように、他のカードの性質を指している。
     */
    private static final Pattern REFERENCE = Pattern.compile("^(を持|を与|を付|付与|を行|を得|を失)");

    /**
     * 条件付き付与であることを示す、トークン直前の語。
     * 末尾の空白と読点は無視する(「〜なら、【潜伏】」も条件である)。
     */
    private static final Pattern CONDITION = Pattern.compile("(なら|たら|とき|時|場合)[\\s、,]*$");

    /** 【起動：１】【賢魂：2】のように数値を伴う語彙を、数値を伏せた形に畳むための分割位置 */
    private static final Pattern PARAMETER = Pattern.compile("[：:].*$");

    /**
     * キーワード表記の直後に続く丸括弧(注釈)。
     * 「【威圧】(相手の攻撃対象にならない)」の括弧はキーワードの意味を言い換えているだけで、
     * そのカード固有の効果ではない。
     */
    private static final Pattern TRAILING_NOTE = Pattern.compile("^\\s*[（(][^）)]*[）)]");

    /**
     * 効果の文が1つも無いことを示すカードデータ側の慣用表現。
     * Ver1.1 の235枚では「フレア・ポーン」1枚だけが使う。
     * ★カードIDではなく<b>表記</b>を見ている。同じ書き方のカードが増えても自動的に効く。
     */
    private static final Pattern NO_EFFECT = Pattern.compile("効果\\s*なし");

    /** 効果の文が残っているかの判定で、意味を持たないとみなす文字 */
    private static final Pattern PUNCTUATION = Pattern.compile("[\\s。、,\\.]+");

    /**
     * そのカード自身が持つキーワード能力。
     *
     * @param text カードの効果テキスト。null・空文字は空集合を返す(リーダーや無能力カード)
     */
    public static Set<Keyword> extract(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<Keyword> found = new LinkedHashSet<>();
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            Keyword keyword = keywordOf(m.group(1).trim());
            if (keyword == null) {
                continue;
            }
            if (REFERENCE.matcher(text.substring(m.end())).find()) {
                continue;
            }
            if (CONDITION.matcher(text.substring(0, m.start())).find()) {
                continue;
            }
            found.add(keyword);
        }
        return Collections.unmodifiableSet(found);
    }

    /**
     * テキストに現れる <code>【…】</code> の語彙(出現順・重複あり)。
     *
     * 数値を伴う語は {@code 起動：n} のように畳む。エンジンが知らない語彙が
     * カードデータに紛れ込んだことに気づくための、棚卸し用の出口である
     * (テストが全カードぶんを既知の語彙集合と突き合わせている)。
     */
    public static List<String> vocabulary(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return TOKEN.matcher(text).results()
                .map(r -> PARAMETER.matcher(r.group(1).trim()).replaceAll("：n"))
                .toList();
    }

    /**
     * キーワード表記を取り除いてもなお、そのカード固有の効果の文が残るか(★Batch 47)。
     *
     * <b>何のための判定か。</b>「効果が未実装」の印({@code CardView.effectUnimplemented})は、
     * <b>効果の文があるのにエンジンが処理を持っていない</b>カードにだけ付ける。
     * キーワードしか書かれていないカードは、データが正しければキーワードの仕組みだけで
     * 正しく動くので、エンジンにカードIDが現れなくても未実装ではない。
     *
     * <b>なぜここに置くか。</b> これはテキストの読み方の規則であり、キーワードの語彙を
     * 知っていないと書けない。抽出規則と同じ場所に置く(裁定158 の延長)。
     *
     * <h3>丸括弧の扱い</h3>
     * キーワード表記の直後の丸括弧は原則として注釈であり、落とす。
     * <b>ただし【特殊召喚】だけは例外で、括弧の中身が発動条件そのもの</b>であり、
     * エンジンは {@code specialSummons} への登録を必要とする。
     * 【進化】も括弧に素材条件を書くが、こちらは {@link Keyword} ではないため
     * 「キーワード表記の直後」に当たらず、そのまま残る。
     *
     * <pre>
     *   【突進】【威圧】(相手の攻撃対象にならない)      → 文は残らない(印を付けない)
     *   【特殊召喚】(手札が7枚以上なら…)               → 文が残る(登録が要る)
     *   【進化】(自分ミニオン1体以上)【常在】…          → 文が残る
     *   効果なし                                        → 文は残らない
     * </pre>
     */
    public static boolean hasEffectSentence(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return !PUNCTUATION.matcher(stripKeywordNotation(text)).replaceAll("").isEmpty();
    }

    /**
     * キーワード表記(と、その注釈の括弧・「効果なし」の表記)を取り除いた残り。
     * {@link #hasEffectSentence(String)} のためだけの内部処理である。
     */
    private static String stripKeywordNotation(String text) {
        StringBuilder rest = new StringBuilder();
        Matcher m = TOKEN.matcher(text);
        int cursor = 0;
        while (m.find(cursor)) {
            rest.append(text, cursor, m.start());
            cursor = m.end();
            Keyword keyword = keywordOf(m.group(1).trim());
            // 【特殊召喚】の括弧は条件そのものなので落とさない(上のjavadoc参照)
            if (keyword != null && keyword != Keyword.SPECIAL_SUMMON) {
                Matcher note = TRAILING_NOTE.matcher(text.substring(cursor));
                if (note.find()) {
                    cursor += note.end();
                }
            }
        }
        rest.append(text.substring(cursor));
        return NO_EFFECT.matcher(rest).replaceAll("");
    }

    /** 表示名に一致する {@link Keyword}。キーワードでない語は null を返す(例外にしない) */
    private static Keyword keywordOf(String displayName) {
        for (Keyword keyword : Keyword.values()) {
            if (keyword.getDisplayName().equals(displayName)) {
                return keyword;
            }
        }
        return null;
    }
}
