package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.example.qte.game.TurnPhase;

/**
 * ★★★Batch 69: 通常モードの盤面(65 が挙げた穴)の番人のうち、
 * <b>Eclipse の JUnit だけで回る側</b>である。
 *
 * <h2>なぜ verify と重ねるのか</h2>
 *
 * 69 が足した性質の大半は <b>実測でしか測れない</b>(色の差・要素の位置・実マウスのホバー)。
 * それらは {@code verify/verify.js} が Playwright で測っており、ここでは測らない。
 *
 * <p>★ただし verify は <b>マスターの手元では回らない</b> ——
 * 実機確認は Eclipse の [Run As → JUnit Test] だからである。
 * だから「壊れたら致命的で、しかもファイルを読むだけで測れる」2つだけをこちらへ置く。
 *
 * <ol>
 *   <li><b>フェイズの進行表が {@link TurnPhase} から離れていないこと</b> ——
 *       書き写しは黙って離れていく(67 の教訓・写し)。
 *       フェイズが増えても {@code AUTO_PHASES} は自分では増えない。</li>
 *   <li><b>パイルの枚数バッジの書き込みが1本を通ること</b>(裁定130) ——
 *       44 は書き込みを8箇所に散らしており、69 が {@code setPileCount} に寄せた。
 *       直書きに戻ると「0枚は暗くする」が<b>その1箇所だけ効かなくなる</b>。</li>
 * </ol>
 *
 * <p>★<b>作らなかった番人</b>(裁定196)——
 * 「場のミニオンと手札に {@code attachHover} が付いているか」を
 * ここで文字列として測ることもできたが、採らなかった。
 * 呼び出しが在ることと<b>プレビューが実際に出ること</b>は別であり、
 * 文字列で測ると「呼んでいるが出ない」を緑にしてしまう。
 * あれは verify 69-3 が実マウスで測る性質である。
 */
class Batch69BoardTest {

    private static final Path BATTLE_JS = Path.of("src/main/resources/static/js/battle.js");

    private static String battleJs() throws IOException {
        return Files.readString(BATTLE_JS, StandardCharsets.UTF_8);
    }

    /**
     * {@code battle.js} の {@code AUTO_PHASES} から {@code {phase, label}} を順に取り出す。
     * ★<b>抽出が0件のときは空振りである</b>(裁定186)。呼び出し側で件数も測る。
     */
    private static List<String> autoPhases(String js) {
        int start = js.indexOf("const AUTO_PHASES = [");
        assertThat(start).as("battle.js に AUTO_PHASES が在る").isGreaterThanOrEqualTo(0);
        int end = js.indexOf("];", start);
        assertThat(end).as("AUTO_PHASES の終わり").isGreaterThan(start);
        String body = js.substring(start, end);
        Matcher m = Pattern.compile("\\{\\s*phase:\\s*'([A-Z_]+)',\\s*label:\\s*'([^']+)'\\s*\\}")
                .matcher(body);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add(m.group(1) + "=" + m.group(2));
        }
        return out;
    }

    @Test
    void フェイズの進行表はTurnPhaseと同じ並びと表示名である() throws IOException {
        List<String> expected = new ArrayList<>();
        for (TurnPhase phase : TurnPhase.values()) {
            expected.add(phase.name() + "=" + phase.getDisplayName());
        }
        List<String> actual = autoPhases(battleJs());
        assertThat(actual).as("★抽出が空振りでないこと(裁定186)").isNotEmpty();
        assertThat(actual)
                .as("★★★battle.js の AUTO_PHASES は TurnPhase の書き写しである。"
                        + "フェイズを増やしたら進行表も増やすこと(裁定130)")
                .containsExactlyElementsOf(expected);
    }

    /**
     * ★★★パイルの枚数バッジに数を書き込む口は {@code setPileCount} 1つである。
     *
     * <p>44 は「数字を書き込む既存のコードは1行も変えずに、書き込み先だけが移る」と書いて
     * <b>書き込みを8箇所に散らしたまま</b>にした。69 が「0枚なら暗くする」を足すにあたり、
     * 規則を8箇所に書く形を避けて1本へ寄せている。
     * <b>直書きに戻すと、その1箇所だけ 0枚でも金色のままになる。</b>
     */
    @Test
    void パイルの枚数バッジの書き込みは一本を通る() throws IOException {
        String js = battleJs();
        Matcher direct = Pattern.compile(
                "getElementById\\('(?:my|opp)-(?:deck|trash|lost|taboo)-count'\\)\\s*\\.textContent\\s*=")
                .matcher(js);
        List<String> found = new ArrayList<>();
        while (direct.find()) {
            found.add(direct.group());
        }
        assertThat(found)
                .as("★枚数バッジへの直書きは残っていない(書き込みは setPileCount 1本を通る)")
                .isEmpty();
        int calls = js.split("setPileCount\\('", -1).length - 1;
        assertThat(calls)
                .as("★★8つのパイル(両席 × 山札・墓地・消滅・禁忌)がすべて1本を通っている")
                .isEqualTo(8);
    }
}
