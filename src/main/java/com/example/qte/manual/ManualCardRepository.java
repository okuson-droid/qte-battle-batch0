package com.example.qte.manual;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import tools.jackson.databind.ObjectMapper;

/**
 * 手動モードのカードマスタの読み込みと検索。
 *
 * 既存の {@link com.example.qte.master.CardMasterRepository} と同型である。
 * classpath 上の JSON を起動時に一度だけ読み、以後は Map の表引きで返す。
 * JSON が壊れていれば起動そのものが失敗する(実行時まで問題を持ち越さない)。
 *
 * ★読むのは {@code cards/manual-cards.json} であり、台帳 {@code qte-cards.json} とは
 * 別ファイルである。台帳には統合しない(設計書 3-1)。両者は
 * {@link ManualCardMaster#ledgerCardId()} でゆるく対応づくだけである。
 *
 * ★JSON は {@code tools/convert_manual_cards.py} が CSV から生成した成果物であり、
 * 手で編集しない。ただしピュア・エレメントの画像IDの修正のように1行で済むものは、
 * スクリプト側の定数を直してから再生成する。
 */
@Repository
public class ManualCardRepository {

    private static final String RESOURCE = "cards/manual-cards.json";

    private final Map<String, ManualCardMaster> cardsById;
    private final Map<String, ManualCardMaster> cardsByImageId;

    @Getter
    private final List<ManualCardMaster> allCards;

    /**
     * 裏面画像のID。全カード共通で1種類しかないため、カードではなくここに持つ。
     * 裏向き表示(設計書 4-4 の Shift + 左クリック)で使う。
     */
    @Getter
    private final String backImageId;

    public ManualCardRepository(ObjectMapper objectMapper) {
        ManualCardFile file = load(objectMapper);
        this.allCards = file.cards().stream().map(ManualCardJson::toMaster).toList();
        this.cardsById = allCards.stream()
                .collect(Collectors.toUnmodifiableMap(ManualCardMaster::id, Function.identity()));
        // ★画像IDはデッキ(ユドナリウム XML)との唯一の突合キーである(設計書 1-3)。
        // 重複していればここで起動が落ちる。落ちてよい。突合できない状態で動かす方が悪い。
        this.cardsByImageId = allCards.stream()
                .collect(Collectors.toUnmodifiableMap(ManualCardMaster::imageId, Function.identity()));
        this.backImageId = file.meta() == null ? null : file.meta().backImageId();
    }

    private ManualCardFile load(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return objectMapper.readValue(in, ManualCardFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException("手動モードのカードマスタの読み込みに失敗しました", e);
        }
    }

    public ManualCardMaster findById(String cardId) {
        ManualCardMaster card = cardsById.get(cardId);
        if (card == null) {
            throw new IllegalArgumentException("存在しない手動モードカードID: " + cardId);
        }
        return card;
    }

    /**
     * カードIDで引く。★見つからないことを呼び出し側が扱える形(Batch 23 5-2)。
     *
     * 起動時の設定検証(ピュア・エレメントのID)で使う。{@link #findById} は
     * 存在しないIDで例外を投げるが、設定漏れで<b>アプリ全体が上がらなくなるほうが害が大きい</b>
     * ため、あちらの経路は使えない。
     */
    public Optional<ManualCardMaster> findOptionalById(String cardId) {
        return Optional.ofNullable(cardId == null ? null : cardsById.get(cardId));
    }

    /**
     * 表面画像IDで引く。デッキ取り込み(Batch 17b)の入口になる。
     * 見つからない場合を呼び出し側が扱えるよう、例外ではなく Optional を返す。
     */
    public Optional<ManualCardMaster> findByImageId(String imageId) {
        return Optional.ofNullable(cardsByImageId.get(imageId));
    }

    public List<ManualCardMaster> findByCivilization(ManualCivilization civilization) {
        return allCards.stream()
                .filter(c -> c.civilization() == civilization)
                .toList();
    }

    /** 文明ごとに束ねた一覧。並び順は {@link ManualCivilization} の宣言順。 */
    public Map<ManualCivilization, List<ManualCardMaster>> groupByCivilization() {
        var grouped = new LinkedHashMap<ManualCivilization, List<ManualCardMaster>>();
        for (ManualCivilization civ : ManualCivilization.values()) {
            List<ManualCardMaster> list = findByCivilization(civ);
            if (!list.isEmpty()) {
                grouped.put(civ, list);
            }
        }
        return grouped;
    }

    // ---- 以下、manual-cards.json の形をそのまま受けるためのDTO ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ManualCardFile(ManualMeta meta, List<ManualCardJson> cards) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ManualMeta(Integer total, String backImageId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ManualCardJson(
            String id,
            String name,
            String type,
            String civilization,
            Integer cost,
            Integer attack,
            Integer hp,
            String imageId,
            String ledgerCardId,
            Boolean unlimitedCopies) {

        ManualCardMaster toMaster() {
            return new ManualCardMaster(id, name,
                    ManualCardType.valueOf(type),
                    ManualCivilization.valueOf(civilization),
                    cost, attack, hp, imageId, ledgerCardId,
                    Boolean.TRUE.equals(unlimitedCopies));
        }
    }
}
