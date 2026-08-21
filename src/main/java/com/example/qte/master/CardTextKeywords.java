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
 * <b>★この規則は169枚で機械的に検証してある。</b> Ver0.4 の台帳で
 * 人手で付けられていた {@code keywords} と、この規則が Ver1.1 のテキストから読んだ結果は
 * 169枚中160枚で一致し、残り9枚はすべて Ver1.1 での実変更である
 * (差分の全件と理由は {@code CardTextKeywordsTest} の表に書いてある)。
 * ★台帳ファイルは Batch 60 で削除したが、<b>人手のキーワード169件は
 * {@code src/test/resources/keyword-baseline.json} に凍結してある</b>ので、
 * 照合は今も毎回走っている。規則を変えるときは、まずそのテストを見ること。
 *
 * <b>扱わない語彙。</b>【常在】【進化】【賢魂：n】【起動：n】【召喚時】【破壊時】は
 * キーワード能力ではなく、エンジン側の別の仕組みに対応する。ここでは
 * {@link #vocabulary(String)} が語彙として数え上げるだけで、{@link Keyword} には変換しない。
 *
 * <h2>★Batch 54: テキストは【賢魂：n】で2つに割れる(裁定152)</h2>
 *
 * 裁定152 は「スペルとして使う場合のコストは n。<b>効果は【賢魂：n】に続くテキスト</b>」と
 * 定めている。つまり1枚のカードのテキストは、<b>【賢魂：n】を境に2つの姿へ分かれる</b> ——
 * 前半がミニオンとしての姿、後半がスペルとしての姿である。
 *
 * <pre>
 *   《白ノ霊知者》
 *   【進化】（風文明のミニオン1体）【召喚時】カードを2枚引く。ミニオンを1体選び破壊する。
 *     ↑ ここまでがミニオンの姿(extract が読む)
 *   【賢魂：2】自分のミニオン1体の攻撃力+1する。【還元】
 *     ↑ ここから先がスペルの姿(soulKeywords が読む)
 * </pre>
 *
 * ★<b>境目を無視すると《白ノ霊知者》の【還元】が本体に付く。</b>
 * マスター裁定(2026-08-20)により、あの【還元】は<b>賢魂の効果の一部</b>である ——
 * スペルとして使えばマナへ置かれ、ミニオンとして破壊されれば墓地へ行く。
 * したがって {@link #extract(String)} は<b>賢魂の直前まで</b>しか読まない。
 *
 * ★<b>コストの正もテキストである。</b> {@link #soulCost(String)} が唯一の出どころであり、
 * {@code CardEffectRegistry} は効果と対象要求だけを持つ。数値を両方に書くと、
 * カードデータとコードのどちらが正なのかが分からなくなる(裁定158 の延長)。
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
     * 【賢魂：n】の表記(★Batch 54)。
     *
     * ★<b>数字は全角も半角も取る。</b>《グレイヴガールズファン》だけが全角の「１」で書かれており、
     * 残る6枚は半角である(マスター確認済み・カードデータは書き換えない)。
     * 表記ゆれを読む側で吸収するのは、カードデータを唯一の正に保つためである。
     * ★コロンも全角「：」と半角「:」の両方を取る({@link #PARAMETER} と同じ扱い)。
     */
    private static final Pattern SOUL = Pattern.compile("【賢魂[：:]\\s*([0-9０-９]+)】");

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
        // ★Batch 54: 【賢魂：n】から先はスペルとしての姿である(裁定152)。
        // ミニオンが持つキーワードを問われているので、境目の手前までしか読まない
        return keywordsIn(minionFace(text));
    }

    /**
     * 賢魂としての姿が持つキーワード(★Batch 54)。賢魂を持たないカードは空集合を返す。
     *
     * 現在の使い手は《白ノ霊知者》の【還元】1枚だけである ——
     * スペルとして使い終わったこのカードは、墓地ではなく裏向きでマナに置かれる。
     * ★{@link #extract(String)} と<b>同じ規則を境目の反対側に当てているだけ</b>であり、
     * 参照・条件付き付与の除外もそのまま効く。
     */
    public static Set<Keyword> soulKeywords(String text) {
        String face = soulFace(text);
        return face == null ? Set.of() : keywordsIn(face);
    }

    /**
     * 【賢魂：n】のコスト n(★Batch 54)。賢魂を持たないカードは null を返す。
     *
     * ★<b>これが n の唯一の出どころである。</b>
     * {@code CardEffectRegistry} は効果と対象要求だけを登録し、数値は持たない。
     */
    public static Integer soulCost(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = SOUL.matcher(text);
        if (!m.find()) {
            return null;
        }
        return Integer.parseInt(toHalfWidthDigits(m.group(1)));
    }

    /** 【賢魂：n】を持つか(★Batch 54) */
    public static boolean hasSoul(String text) {
        return soulCost(text) != null;
    }

    /**
     * 【賢魂：n】に続くテキスト(★Batch 54)。賢魂を持たないカードは null を返す。
     *
     * 手札の確認ダイアログに「スペルとして使うと何が起きるか」を出すための文である。
     * ★<b>クライアントが自分でテキストを割らないため</b>にサーバが送る ——
     * 割り方の規則を両側に置けば、片方だけが直される日が来る(裁定234)。
     */
    public static String soulText(String text) {
        String face = soulFace(text);
        return face == null ? null : face.trim();
    }

    /** テキストのうち、ミニオンとしての姿にあたる部分(賢魂を持たなければ全文) */
    private static String minionFace(String text) {
        Matcher m = SOUL.matcher(text);
        return m.find() ? text.substring(0, m.start()) : text;
    }

    /** テキストのうち、賢魂としての姿にあたる部分(【賢魂：n】の直後から末尾まで)。無ければ null */
    private static String soulFace(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = SOUL.matcher(text);
        return m.find() ? text.substring(m.end()) : null;
    }

    /** 全角数字を半角に直す(【賢魂：１】のような表記ゆれのため) */
    private static String toHalfWidthDigits(String digits) {
        StringBuilder out = new StringBuilder(digits.length());
        for (char c : digits.toCharArray()) {
            out.append(c >= '０' && c <= '９' ? (char) (c - '０' + '0') : c);
        }
        return out.toString();
    }

    /** 与えられたテキスト片が「自身が持つ」と主張しているキーワード */
    private static Set<Keyword> keywordsIn(String text) {
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
