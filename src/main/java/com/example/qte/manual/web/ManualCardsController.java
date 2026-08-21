package com.example.qte.manual.web;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.example.qte.manual.ManualCardMaster;
import com.example.qte.manual.ManualCardRepository;
import com.example.qte.manual.ManualCardType;

import lombok.RequiredArgsConstructor;

/**
 * 手動モードのカード一覧(Batch 17a・★Batch 61 で作り替えた)。
 *
 * <h2>17a 当時の役目(終わった)</h2>
 *
 * もとは<b>目視確認のためだけの画面</b>だった。見たいものは2つで、
 * 「235枚の画像に欠けが無いこと」と「ピュア・エレメントの画像IDが正しいこと」である。
 * どちらも Batch 17a の未決事項であり、とうに片付いている。
 *
 * <h2>★Batch 61: 「カード一覧」になった</h2>
 *
 * ロビーはこの画面を「カード一覧」と呼んでいるのに、60 まで<b>本文が1文字も出ていなかった</b> ——
 * 手動モードのカード定義がテキストを持たない、という Ver0.4 時代の前提の名残である
 * ({@link com.example.qte.manual.ManualCardMaster} の Javadoc を参照)。
 * 61 で {@code ManualCardMaster} が本文を持つようになり、
 * 表示も<b>デッキメーカー・盤面と同じカードフェイス</b>に揃えた
 * (markup の正は {@code templates/fragments/card-face.html} 1つである)。
 *
 * <p>★<b>カード画像はセルから外した</b>(マスター判断)。画像の欠けは
 * {@link #missingImageIds} が名指しで報告するので、目で見る必要が無くなったためである。
 * 裏面だけは「表の面」を持たないので画像のまま残してある。
 *
 * <p>画像は Spring Boot が {@code classpath:/static/} を自動配信するため、
 * {@code /cards/<imageId>.png} でそのまま出る。配信用の設定もコードも要らない。
 */
@Controller
@RequiredArgsConstructor
public class ManualCardsController {

    private final ManualCardRepository cards;

    @GetMapping("/manual/cards")
    public String manualCards(Model model) {
        List<ManualCardMaster> all = cards.getAllCards();

        model.addAttribute("cardsByCivilization", cards.groupByCivilization());
        model.addAttribute("totalCards", all.size());
        model.addAttribute("typeCounts", countByType(all));
        model.addAttribute("linkedCount", all.stream().filter(ManualCardMaster::isLinkedToLedger).count());
        model.addAttribute("backImageId", cards.getBackImageId());
        model.addAttribute("missingImageIds", missingImageIds(all));
        return "manual-cards";
    }

    /**
     * カード定義ファイルをそのまま配る(Batch 24)。
     *
     * デッキメーカー({@code /deck-maker})のカードデータはこのエンドポイントから取得する。
     * {@link ManualCardRepository} を経由して DTO に組み直さないのは意図的である。
     * リポジトリはサーバが使う項目(数値・画像ID)しか持たず、テキストを落とす。
     * ここで組み直すと「サーバの知っているカード」と「デッキメーカーの知っているカード」が
     * 別物になる。同じ情報を2箇所に置かない(設計判断28)——正はファイルであり、
     * 両者ともファイルを読む。
     */
    @GetMapping(value = "/manual/api/card-library", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<byte[]> cardLibrary() throws IOException {
        try (InputStream in = new ClassPathResource("cards/manual-cards.json").getInputStream()) {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noCache())
                    .body(in.readAllBytes());
        }
    }

    /** 宣言順を保った種別ごとの枚数。設計書 1-2 の表と突き合わせる。 */
    private Map<ManualCardType, Long> countByType(List<ManualCardMaster> all) {
        var counts = new LinkedHashMap<ManualCardType, Long>();
        for (ManualCardType type : ManualCardType.values()) {
            counts.put(type, all.stream().filter(c -> c.type() == type).count());
        }
        return counts;
    }

    /**
     * 画像ファイルが実在しない画像IDの集合。
     *
     * ★画像IDは内容ハッシュなので、カードを作り直すと必ず変わる(設計書 1-3)。
     * 光18・19 で実際に起きており、以後も再発しうる。変換スクリプトも同じ検査をするが、
     * 「CSV を差し替えずに画像だけ入れ替えた」場合はスクリプトを通らないため、
     * 画面側でも見る。ブラウザ上では単に画像が割れるだけで理由が分からないので、
     * ここで名指しする。
     */
    private Set<String> missingImageIds(List<ManualCardMaster> all) {
        return all.stream()
                .map(ManualCardMaster::imageId)
                .filter(id -> id == null || !new ClassPathResource("static/cards/" + id + ".png").exists())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
