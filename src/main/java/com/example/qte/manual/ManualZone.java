package com.example.qte.manual;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 手動モードのゾーン(設計書 2-3)。
 *
 * ★リーダーはここに含めない。リーダーは席に1枚だけ存在し、枚数が増減せず、
 * 他のゾーンへ移動しないためである({@link ManualSeat#getLeader()} が持つ)。
 * ゾーンを「0枚以上のカードが出入りする入れ物」に限定しておくと、
 * {@link ManualSeat#zone(ManualZone)} が全ゾーンで同じ型を返せる。
 * Batch 18a の操作13項目はほとんどがゾーン間移動であり、この一様性がそのまま効く。
 *
 * WEAPON は仕様上1枚しか置かないが、他と同じくリストとして持つ。
 * 状態モデルが枚数を強制すると、人間が一時的に2枚置いて考えることすらできなくなる。
 * ★Batch 20b で「装備済みの枠には落とせない」という画面側の規約(旧 設計書 4-5)は
 * 撤回した。リーダータイル自体がウェポンのドロップ先になったため、装備の有無で
 * 当たり判定が変わると人間に説明できないからである。代わりに、ウェポンゾーンへ
 * 移したときは既にそこに居たカードを墓地へ送る
 * ({@link ManualOperationService#move} の「付け替え」)。
 *
 * <h2>★共有ゾーン(Batch 20b)</h2>
 * {@link #PLAY} と {@link #REVEAL} は席に属さず、ゲーム全体で1つだけ存在する。
 * どちらも「そこに置いたカードは相手に見えている」という一貫した意味を持ち、
 * 画面では両ミニオン行の間のセンターラインに描かれる(20b 設計書 2-3)。
 *
 * <h2>★中身の公開範囲(Batch 21 設計書 3-3)</h2>
 * {@link #isContentsPublic()} は「対戦部屋で、相手と『公開のみ』観戦者にも中身が見えるゾーンか」
 * である。この1つのフラグから、盤面ビューのフィルタ
 * ({@link com.example.qte.manual.view.ManualViewBuilder})とログのマスク
 * ({@link ManualLogRenderer})の<b>両方</b>が決まる。判定を2箇所に書くと必ず食い違い、
 * 「盤面からは隠したのにログには名前が出る」という形で漏れる。
 *
 * ★{@link #MANA} だけは false だが特例である。マナは表向きと裏向きが混在するゾーンであり、
 * <b>表向きのカードは相手にも見える</b>。ビューは MANA を個別に扱い
 * 「表向きカードの配列 + 裏向きの枚数」を送る(3-3)。ログでは非公開として扱う。
 * 裏向きのマナに対する操作で名前が漏れないことを優先し、迷ったら隠す側へ倒す判断である。
 *
 * 共有ゾーンの実体は {@link ManualGameState#sharedZones} が持ち、
 * {@link ManualSeat} は共有ゾーンのリストを<b>作らない</b>。
 * 「A席の zones に入れておいて共有として扱う」という慣習方式は、モデルとして嘘になり
 * 将来の読み手を必ず混乱させるため採らなかった(20b 設計書 3-1)。
 * したがって {@link ManualSeat#zone(ManualZone)} に共有ゾーンを渡すと例外になる。
 * 席の区別を意識せずに引きたいときは {@link ManualGameState#cards(ManualSeatId, ManualZone)}
 * を使うこと。
 */
@Getter
@RequiredArgsConstructor
public enum ManualZone {

    DECK("山札", false, false),
    HAND("手札", false, false),
    /** ★特例。表向きのカードだけ相手にも見える(3-3)。ログ上は非公開として扱う */
    MANA("マナ", false, false),
    FIELD("ミニオン", false, true),
    WEAPON("ウェポン", false, true),
    TRASH("墓地", false, true),
    LOST("消滅", false, true),
    TABOO("禁忌", false, false),
    /** 一時公開。★20b でプレイヤー間の共有ゾーンに変更した(旧: 席ごと) */
    REVEAL("公開", true, true),
    /** プレイ中。スペルの解決中など、処理の途中であることを示す一時置き場(20b 2-4) */
    PLAY("プレイ中", true, true),
    /** 確認。自分だけが中身を見るゾーン(相手には枚数のみ。20b 2-4 / 21 3-3) */
    PRIVATE("確認", false, false);

    private final String displayName;

    /**
     * プレイヤー間で共有されるゾーンか(20b 3-1)。
     * true のものは {@link ManualSeat} ではなく {@link ManualGameState} が持つ。
     */
    private final boolean shared;

    /**
     * 対戦部屋で中身が相手にも見えるゾーンか(21 3-3)。
     * ★共有ゾーンは定義上すべて true である(そこに置くこと自体が「見せる」意思表示である)。
     */
    private final boolean contentsPublic;
}
