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
