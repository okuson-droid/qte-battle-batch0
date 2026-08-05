package com.example.qte;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import com.example.qte.manual.ManualCardInstance;
import com.example.qte.manual.ManualCardType;
import com.example.qte.manual.ManualCivilization;
import com.example.qte.manual.ManualDeckImport;
import com.example.qte.manual.ManualDeckImporter;
import com.example.qte.manual.ManualGameService;
import com.example.qte.manual.ManualGameState;
import com.example.qte.manual.ManualHistory;
import com.example.qte.manual.ManualRoom;
import com.example.qte.manual.ManualSeat;
import com.example.qte.manual.ManualSeatId;
import com.example.qte.manual.ManualZone;

/**
 * Batch 17b のスモークテスト。
 *
 * 実サンプルのデッキ zip(main.xml 41枚 / kinki.xml 8枚)を
 * {@code src/test/resources/decks/sample-deck.zip} に置き、これを読ませる。
 * 検証したいのは「49枚すべてが manual-cards.json のカードに解決すること」であり、
 * これは設計書 1-8 が実データで確認済みだと述べている性質でもある。
 *
 * ★カードIDを文字列リテラルで書かないこと(batch17a-design-notes 3-2)。
 * {@code tools/check_all.py} の項目3 が手動モードのIDを台帳に無いIDとして報告する。
 * ここでは名前・種別・文明で確かめている。
 */
@SpringBootTest
class ManualDeckImportTest {

    @Autowired
    ManualDeckImporter importer;

    @Autowired
    ManualGameService gameService;

    private byte[] sampleDeckZip() throws IOException {
        try (InputStream in = new ClassPathResource("decks/sample-deck.zip").getInputStream()) {
            return in.readAllBytes();
        }
    }

    private ManualDeckImport importSample() throws IOException {
        return importer.importZip(sampleDeckZip());
    }

    @Test
    void 実サンプルは49枚すべてがカード定義に解決する() throws IOException {
        ManualDeckImport imported = importSample();
        assertThat(imported.totalCards()).isEqualTo(49);
        assertThat(imported.unresolvedCount()).isZero();
    }

    @Test
    void リーダーは先頭から取り出されメインは40枚になる() throws IOException {
        ManualDeckImport imported = importSample();
        assertThat(imported.leader()).isNotNull();
        assertThat(imported.leader().master().type()).isEqualTo(ManualCardType.LEADER);
        assertThat(imported.main()).hasSize(40);
        assertThat(imported.taboo()).hasSize(8);
    }

    /**
     * ★突合キーが表面画像IDのみであることの証拠。
     * 実サンプルの main.xml の先頭は「リーダー：【傷痕の闘帝】」という<b>誤った名前</b>で
     * 保存されているが、画像IDが正しいため「流転の智者」に解決する(設計書 1-3)。
     * 名前で突合していたら、ここで火文明のリーダーに化けるか、突合に失敗する。
     */
    @Test
    void 名前が誤っていても画像IDで正しいカードに解決する() throws IOException {
        ManualDeckImport imported = importSample();
        assertThat(imported.leader().rawName()).contains("傷痕の闘帝");
        assertThat(imported.leader().displayName()).isEqualTo("流転の智者");
        assertThat(imported.leader().master().civilization()).isEqualTo(ManualCivilization.WATER);
    }

    @Test
    void 構築ルールに違反しないデッキでは警告が出ない() throws IOException {
        ManualDeckImport imported = importSample();
        assertThat(imported.warnings()).isEmpty();
    }

    @Test
    void 禁忌はリーダーと異なる文明のカードだけで構成される() throws IOException {
        ManualDeckImport imported = importSample();
        ManualCivilization leaderCiv = imported.leader().master().civilization();
        assertThat(imported.taboo()).allSatisfy(
                entry -> assertThat(entry.master().civilization()).isNotEqualTo(leaderCiv));
    }

    @Test
    void 読み込むとシャッフルして4枚引きLP20で始まる() throws IOException {
        ManualRoom room = new ManualRoom("TESTRM");
        gameService.loadDeck(room, ManualSeatId.A, importSample());

        ManualSeat seat = room.getGameState().seat(ManualSeatId.A);
        assertThat(seat.getLeader()).isNotNull();
        assertThat(seat.getLp()).isEqualTo(ManualGameService.INITIAL_LP);
        assertThat(seat.zone(ManualZone.HAND)).hasSize(ManualGameService.INITIAL_HAND_SIZE);
        assertThat(seat.zone(ManualZone.DECK)).hasSize(40 - ManualGameService.INITIAL_HAND_SIZE);
        assertThat(seat.zone(ManualZone.TABOO)).hasSize(8);
        assertThat(seat.isDeckLoaded()).isTrue();

        // B席はデッキを読み込まない空席である(設計書 6-1)
        ManualSeat empty = room.getGameState().seat(ManualSeatId.B);
        assertThat(empty.isDeckLoaded()).isFalse();
        assertThat(empty.getLp()).isEqualTo(ManualGameService.INITIAL_LP);
    }

    @Test
    void 読み込みは履歴を空にしログを残す() throws IOException {
        ManualRoom room = new ManualRoom("TESTRM");
        room.getHistory().push(room.getGameState(), ManualSeatId.A);
        assertThat(room.getHistory().canUndo()).isTrue();

        gameService.loadDeck(room, ManualSeatId.A, importSample());
        assertThat(room.getHistory().canUndo()).isFalse();
        assertThat(room.getLog()).isNotEmpty();
    }

    @Test
    void 状態のコピーは元と独立している() throws IOException {
        ManualRoom room = new ManualRoom("TESTRM");
        gameService.loadDeck(room, ManualSeatId.A, importSample());

        ManualGameState original = room.getGameState();
        ManualGameState snapshot = original.copy();

        ManualSeat seat = original.seat(ManualSeatId.A);
        ManualCardInstance card = seat.zone(ManualZone.HAND).get(0);
        card.setAttack(99);
        card.getLabels().add("凍結");
        seat.setLp(3);
        seat.zone(ManualZone.HAND).remove(0);

        ManualSeat copied = snapshot.seat(ManualSeatId.A);
        assertThat(copied.getLp()).isEqualTo(ManualGameService.INITIAL_LP);
        assertThat(copied.zone(ManualZone.HAND)).hasSize(ManualGameService.INITIAL_HAND_SIZE);
        ManualCardInstance copiedCard = copied.zone(ManualZone.HAND).get(0);
        assertThat(copiedCard.getInstanceId()).isEqualTo(card.getInstanceId());
        // 印刷値が空欄(スペル)のカードを引くこともあるため、null 安全な Object 比較にする
        assertThat(copiedCard.getAttack()).isNotEqualTo(Integer.valueOf(99));
        assertThat(copiedCard.getLabels()).isEmpty();
    }

    @Test
    void 進化スタックは平坦に積まれコピーでも保たれる() {
        ManualGameState state = new ManualGameState("TESTRM");
        ManualSeat seat = state.seat(ManualSeatId.A);
        ManualCardInstance top = ManualCardInstance.unresolved("進化", "img-top");
        top.getMaterials().add(ManualCardInstance.unresolved("素材1", "img-1"));
        top.getMaterials().add(ManualCardInstance.unresolved("素材2", "img-2"));
        top.getMaterials().add(ManualCardInstance.unresolved("素材3", "img-3"));
        seat.zone(ManualZone.FIELD).add(top);

        assertThat(top.materialCount()).isEqualTo(3);
        assertThat(top.stackSize()).isEqualTo(4);

        ManualCardInstance copied = state.copy().seat(ManualSeatId.A).zone(ManualZone.FIELD).get(0);
        assertThat(copied.materialCount()).isEqualTo(3);
        assertThat(copied.getMaterials().get(0).getFallbackName()).isEqualTo("素材1");
        assertThat(copied.getMaterials().get(0)).isNotSameAs(top.getMaterials().get(0));
    }

    @Test
    void 履歴は積んで戻してやり直せる() {
        ManualHistory history = new ManualHistory();
        ManualGameState first = new ManualGameState("TESTRM");
        first.setTurnNumber(1);

        history.push(first, ManualSeatId.A);
        ManualGameState second = first.copy();
        second.setTurnNumber(2);

        assertThat(history.canUndo()).isTrue();
        assertThat(history.canRedo()).isFalse();

        ManualGameState back = history.undo(second, ManualSeatId.A).orElseThrow();
        assertThat(back.getTurnNumber()).isEqualTo(1);
        assertThat(history.canRedo()).isTrue();

        ManualGameState forward = history.redo(back, ManualSeatId.A).orElseThrow();
        assertThat(forward.getTurnNumber()).isEqualTo(2);
    }

    @Test
    void 履歴は深さ200で打ち切る() {
        ManualHistory history = new ManualHistory();
        ManualGameState state = new ManualGameState("TESTRM");
        for (int i = 0; i < ManualHistory.MAX_DEPTH + 50; i++) {
            history.push(state, ManualSeatId.A);
        }
        assertThat(history.undoDepth()).isEqualTo(ManualHistory.MAX_DEPTH);
    }

    @Test
    void MPはマナのアンタップ枚数から算出される() {
        ManualSeat seat = new ManualSeat(ManualSeatId.A);
        for (int i = 0; i < 5; i++) {
            seat.zone(ManualZone.MANA).add(ManualCardInstance.unresolved("マナ", "img-" + i));
        }
        seat.zone(ManualZone.MANA).get(0).setTapped(true);
        seat.zone(ManualZone.MANA).get(1).setTapped(true);
        seat.zone(ManualZone.MANA).get(2).setFaceDown(true);

        // 裏向きでもアンタップならMPになる(総合ルール 2-3)
        assertThat(seat.availableMp()).isEqualTo(3);
    }

    @Test
    void 空のファイルは取り込めない() {
        assertThat(catchImportFailure(new byte[0])).isNotNull();
    }

    private String catchImportFailure(byte[] bytes) {
        try {
            importer.importZip(bytes);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }
}
