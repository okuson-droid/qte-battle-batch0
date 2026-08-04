package com.example.qte.manual;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

/**
 * ゾーン上のカード1枚。手動モードの状態の最小単位である。
 *
 * <h2>数値は attack / hp の現在値2つだけ(設計書 4-3)</h2>
 * v2 の maxHp / damage の2軸案は撤回済みである。人間が数字を直接書き換える方式では
 * 軸は1本でよく、「最大HPを超えて回復しない」の管理は人間が行う(印刷値は拡大画像に書いてある)。
 * 印刷値との比較(増減の白チップ表示)は、表示のたびに
 * {@link ManualCardRepository} から引き直せば足りるため、ここには持たない。
 *
 * <h2>進化スタック(設計書 4-5-1)</h2>
 * 進化ミニオンは場のミニオンに重ねて出し、重ねたら一体として扱う。素材は1体とは限らず、
 * 進化ミニオンの上にさらに進化ミニオンを重ねられる。
 * これを「このインスタンス(= 最上段)が {@link #getMaterials()} を持つ」形で表す。
 *
 * ★materials は<b>平坦</b>である。入れ子にしない。設計書 4-5-1 の {@code +n} バッジは
 * 「3体を素材にすれば +3、その上にさらに進化を重ねれば +4」と定めており、
 * 入れ子だと n の計算が再帰になるうえ、4-5-2 の「束の中身を帯として開き1枚ずつ抜く」が
 * 階層を持つことになってしまう。並び順はミニオンゾーンの左からの順(設計書 4-5-1)で、
 * 先頭が最下段、末尾が最上段のすぐ下である。
 *
 * <h2>未解決カード(設計書 7-3)</h2>
 * デッキの画像IDが {@code manual-cards.json} に無い場合、カードは捨てずに
 * 「名前だけの灰色タイル」として取り込む。この状態を {@code cardId == null} で表す。
 * 手動モードは効果を判定しないため、名前さえあれば遊べる。
 */
@Getter
public class ManualCardInstance {

    /** 個体識別ID。クライアントとのやり取り(ドラッグ・数値編集)はこのIDで行う */
    private final String instanceId;

    /** {@link ManualCardMaster#id()}。★未解決カードは null */
    private final String cardId;

    /** 未解決カードの表示名(デッキXMLに書かれていた名前)。解決済みなら null */
    private final String fallbackName;

    /**
     * 未解決カードの画像ID。解決済みなら null。
     * 画像は出さない(出せる保証が無い)。突合できなかった理由を人が追うための情報である。
     */
    private final String fallbackImageId;

    /** 現在のAttack。印刷値が空欄(スペル・リーダー)なら null のまま */
    @Setter
    private Integer attack;

    /** 現在のHP。印刷値が空欄(スペル・リーダー・ウェポン)なら null のまま */
    @Setter
    private Integer hp;

    /** タップ状態。ミニオン・マナ・リーダーが対象(設計書 4-4) */
    @Setter
    private boolean tapped;

    /** 裏向きか。マナ・山札の上・手札の公開などで切り替わる(設計書 4-4) */
    @Setter
    private boolean faceDown;

    /** ウェポンの使用済みフラグ(設計書 5-3 の8)。ミニオンでは使わない */
    @Setter
    private boolean used;

    /**
     * 札(設計書 5-4)。キーワード9種も凍結などの一時状態も、すべて短いテキストに統一する。
     * アプリは意味を解釈しない。カードが何枚増えても実装を変えなくてよい形である。
     */
    private final List<String> labels = new ArrayList<>();

    /** 進化スタックの下段。先頭が最下段。空なら単体のカードである */
    private final List<ManualCardInstance> materials = new ArrayList<>();

    private ManualCardInstance(String instanceId, String cardId,
            String fallbackName, String fallbackImageId) {
        this.instanceId = instanceId;
        this.cardId = cardId;
        this.fallbackName = fallbackName;
        this.fallbackImageId = fallbackImageId;
    }

    /** カードマスタから1枚作る。数値は印刷値で初期化する(設計書 4-5)。 */
    public static ManualCardInstance of(ManualCardMaster master) {
        ManualCardInstance instance = new ManualCardInstance(
                UUID.randomUUID().toString(), master.id(), null, null);
        instance.attack = master.attack();
        instance.hp = master.hp();
        return instance;
    }

    /** 画像IDで突合できなかったカードを、名前だけ持つ個体として作る(設計書 7-3)。 */
    public static ManualCardInstance unresolved(String name, String imageId) {
        return new ManualCardInstance(UUID.randomUUID().toString(), null, name, imageId);
    }

    /** カードマスタに突合できているか。false なら灰色タイルとして表示する。 */
    public boolean isResolved() {
        return cardId != null;
    }

    /** 素材を含めた総枚数。単体なら1。 */
    public int stackSize() {
        return materials.size() + 1;
    }

    /** {@code +n} バッジの n(設計書 4-5-1)。最上段以外の枚数であり、進化ミニオンも数に含める。 */
    public int materialCount() {
        return materials.size();
    }

    /**
     * 深いコピー。{@link ManualGameState#copy()} から呼ばれる。
     *
     * ★instanceId は引き継ぐ。Undo は「同じ盤面に戻す」操作であり、
     * 戻した先で個体が別物になっていると、クライアントが保持している選択状態や
     * ドラッグ中の参照がすべて外れる。個体の同一性は履歴をまたいで保たれなければならない。
     */
    public ManualCardInstance copy() {
        ManualCardInstance clone = new ManualCardInstance(
                instanceId, cardId, fallbackName, fallbackImageId);
        clone.attack = attack;
        clone.hp = hp;
        clone.tapped = tapped;
        clone.faceDown = faceDown;
        clone.used = used;
        clone.labels.addAll(labels);
        for (ManualCardInstance material : materials) {
            clone.materials.add(material.copy());
        }
        return clone;
    }
}
