package com.example.qte.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import com.example.qte.deck.DeckDefinition;

/**
 * 検証済みのデッキファイルを、盤面が使う「カードIDの並び」に変える。
 *
 * <h2>★★Batch 66: プリセットデッキ(おまかせ)を退役させた</h2>
 *
 * 65 まで、このクラスの本体は<b>6文明ぶんのスターターデッキ(各40枚)と
 * 禁忌デッキ2本</b>をコードに書き並べたものだった(約430行)。
 * ロビーで「デッキファイルを選ばずに [部屋を作成して入室] を押した人」に
 * 配るためのデッキである。
 *
 * <p>★<b>66 でその入口が無くなった。</b>デッキは盤面に入ってから
 * デッキファイルで読み込む形になり、読み込まれるまで試合が始まらない
 * ({@link com.example.qte.room.GameRoom#bothReady()})。
 * 使い手を失った器はそのバッチで撤去する(裁定178) ——
 * 残しておくと、次に読む人はそれを「今も配られているデッキ」だと思う。
 *
 * <p>★<b>一緒に消えた番人</b>(何を測らなくなったかを書き残す。裁定196):
 * <ul>
 * <li>{@code Batch60Test} の3件(プリセットが Ver1.1 の新カード10種と進化3種を積み、
 *     進化の素材が同じデッキに居ること)</li>
 * <li>{@code EffectImplementationTest} の1件(プリセットに載っていることは
 *     実装済みの根拠にならない) —— ★これは<b>走査の対象を
 *     「デッキに入りうる全カード」へ広げて残した</b>。プリセットより広い。</li>
 * <li>{@code DeckValidatorTest} は、プリセットを組み立て直す代わりに
 *     {@code support.SampleDecks} がカードマスタから 40+8 枚を組む</li>
 * </ul>
 *
 * <p>残っているのはデッキファイルを並べ替える2つのメソッドだけである。
 * ★どちらもカードマスタを引かないので、このクラスは依存を1つも持たなくなった。
 */
@Component
public class DeckFactory {

    /**
     * デッキファイル(検証済み)からメインデッキを生成する。
     * 検証はDeckValidatorが済ませている前提で、ここでは並べてシャッフルするだけ。
     */
    public List<String> createMainDeckFrom(DeckDefinition deck) {
        List<String> list = new ArrayList<>(40);
        deck.main().forEach(e -> {
            for (int i = 0; i < e.count(); i++) {
                list.add(e.cardId());
            }
        });
        Collections.shuffle(list);
        return list;
    }

    /** デッキファイルの禁忌デッキ(順序は保持する。所有者が並べた順に表示される) */
    public List<String> createTabooDeckFrom(DeckDefinition deck) {
        return new ArrayList<>(deck.taboo());
    }
}
