package com.example.qte.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 公開URL(OGP用)を全画面のモデルに載せる助言役。
 *
 * <h3>なぜ設定に出したのか(Batch 34・裁定24)</h3>
 * Batch 33 では {@code og:image} をテンプレートへ直書きした
 * ({@code https://qte-battle-batch0.onrender.com/og-image.png})。
 * 直書きは「公開URLが変わったら人間が2枚のテンプレートを直す」という運用に依存する。
 * これは {@code qte.manual.pure-element-id} と同じ「環境依存の値は設定へ」の系列であり、
 * ホスティング先を変えた瞬間に、共有リンクのプレビュー画像だけが黙って壊れる。
 * <b>黙って壊れる箇所を主動線(Discord への貼り付け)に置かない</b>のが 33 からの一貫した方針である。
 *
 * <h3>なぜ SpEL の bean 参照ではなくこの形なのか</h3>
 * テンプレート側だけで済ませる案として
 * {@code th:content="${@environment.getProperty('qte.public-base-url')} + '/og-image.png'"}
 * があり、Java の変更はゼロにできる。採らなかった理由は<b>失敗の広さ</b>である。
 * SpEL の解決に失敗した場合、落ちるのは属性1つではなく<b>そのテンプレート全体</b>であり、
 * manual-battle と manual-lobby という主要2枚が 500 になる。
 * サンドボックスでは実サーバを起動できないため、この形の変更は入れないと決めてある(裁定23)。
 * 対してモデル属性の参照 {@code ${ogImageUrl}} は Thymeleaf の最も基本的な式であり、
 * 仮に値が無くても {@code th:content} は<b>属性が消えるだけ</b>でページは生き残る。
 *
 * <h3>なぜ base URL ではなく完成した画像URLを載せるのか</h3>
 * テンプレート側で {@code ${publicBaseUrl} + '/og-image.png'} と連結すると、
 * <b>パス {@code /og-image.png} が2枚のテンプレートに写る</b>。
 * 「同じ情報を2箇所に置かない」(設計判断28)に従い、連結はここ1箇所で行い、
 * テンプレートは完成品を受け取るだけにする。
 *
 * <h3>設定が空のとき</h3>
 * 起動は失敗させない。{@code qte.manual.pure-element-id} と同じ判断であり、
 * 「設定漏れでアプリ全体が上がらない」ほうが害が大きい。
 * 空のときは {@code ogImageUrl} が {@code null} になり、{@code og:image} の行だけが消える。
 * <b>相対URLに退化させない</b>のが要点である。相対URLの解決はクローラ依存であり(裁定22)、
 * 「壊れているかどうかが貼ってみるまで分からない」状態を作るくらいなら、
 * タグごと無いほうがクローラの挙動は予測可能である。
 */
@ControllerAdvice
public class PublicUrlAdvice {

    /** OGP画像の公開パス。★公開URLとの連結はこのクラスだけが知っている。 */
    private static final String OG_IMAGE_PATH = "/og-image.png";

    /** {@code https://example.com/og-image.png} 相当。設定が空なら null。 */
    private final String ogImageUrl;

    public PublicUrlAdvice(@Value("${qte.public-base-url:}") String publicBaseUrl) {
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        // 末尾のスラッシュは設定者の書き癖であって意味の違いではない。ここで吸収する
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.ogImageUrl = base.isEmpty() ? null : base + OG_IMAGE_PATH;
    }

    /**
     * 全画面のモデルへ {@code ogImageUrl} を載せる。
     *
     * ★{@code Model} を引数に取る形ではなく戻り値で載せる形にしてあるのは、
     * {@code @RestController} を含む全ハンドラでこの助言役が動くためである。
     * 戻り値の形なら「モデルに1つ足す」以上のことをしないことが宣言的に読める。
     */
    @ModelAttribute("ogImageUrl")
    public String ogImageUrl() {
        return ogImageUrl;
    }
}
