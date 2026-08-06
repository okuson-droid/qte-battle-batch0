package com.example.qte.manual.view;

import java.util.List;

import com.example.qte.manual.ManualSeatId;
import com.example.qte.manual.ManualStartPhase;

/**
 * 開始シーケンスのビュー(Batch 23 設計書9章)。
 *
 * <h2>★「今どのフェーズか」だけでなく「自分は今何を押せるか」を載せる</h2>
 * クライアントがフェーズと部屋の種類と作成者席から押せる人を組み立て直すと、
 * <b>判定が2箇所に分かれる</b>(21a の「公開範囲の判定を2箇所に書かない」と同型の罠)。
 * 権限の判定は {@link com.example.qte.manual.ManualPermissions} が持ち、
 * ビューはその<b>結果</b>だけを運ぶ。表示と検証が同じ関数を通るため、ズレが構造的に起きない
 * (設計判断34)。
 *
 * <h2>★待機表示はサーバが文にする(7-3)</h2>
 * 「席Bの選択を待っています」を全員に出す。盤面が固まっている理由が画面に書かれていない
 * 状態を作らない(21 設計書 3-5)。クライアントごとに文を組み立てると、
 * 誰が何を待っているのかの解釈が分かれる。
 *
 * @param phase            今のフェーズ
 * @param locking          この間は盤面を操作できない。★クライアントのドラッグ抑止はこれを見る
 *                         (操作補助にすぎず、検証はサーバにある。設計判断27)
 * @param firstSeat        先攻の席。決まるまで null。★リセットまで固定(1-4)
 * @param orderChooser     ダイスで選択権を得た席。{@code ORDER_CHOICE} の間だけ意味を持つ
 * @param subjectSeat      「自分が先攻をとる」の<b>「自分」が指す席</b>(3-1 の②③)。
 *                         ★画面のボタンはこれを使って「席A が先攻をとる」と書く。
 *                         全公開部屋でデッキが1つだけのときは<b>その席</b>になるため、
 *                         文言をクライアントで組み立てると必ず食い違う
 *                         ({@code ManualStartService.subjectSeat} が唯一の正)
 * @param canBegin         閲覧者が [ゲームを始める] を押せるか(2-3)
 * @param canChooseMethod  閲覧者が開始方法の3択を押せるか(2-4)
 * @param canChooseOrder   閲覧者が先攻 / 後攻を選べるか(3-3)
 * @param mulliganSeats    マリガンの確定を待っている席(デッキを読み込んでいる席だけ)
 * @param mulliganDone     確定済みの席。★<b>何枚選んだかは載せない</b>(P11)
 * @param myMulliganSeats  閲覧者が今すぐ確定できる席。全公開部屋では両席になりうる
 * @param waiting          待機中の説明。何も待っていなければ null
 * @param pureElement      ピュア・エレメントを配れる設定になっているか(5-2)。
 *                         false のとき画面は「配布は省略される」と案内できる
 */
public record ManualStartView(
        ManualStartPhase phase,
        boolean locking,
        ManualSeatId firstSeat,
        ManualSeatId orderChooser,
        ManualSeatId subjectSeat,
        boolean canBegin,
        boolean canChooseMethod,
        boolean canChooseOrder,
        List<ManualSeatId> mulliganSeats,
        List<ManualSeatId> mulliganDone,
        List<ManualSeatId> myMulliganSeats,
        String waiting,
        boolean pureElement) {
}
