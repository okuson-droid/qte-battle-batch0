package com.example.qte.manual.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.qte.manual.ManualLabels;
import com.example.qte.manual.ManualOccupant;
import com.example.qte.manual.ManualOccupantRole;
import com.example.qte.manual.ManualRoom;
import com.example.qte.manual.ManualRoomManager;

import lombok.RequiredArgsConstructor;

/**
 * 手動モードの盤面画面の入口(Batch 18b)。
 *
 * <h2>★これは Batch 19a の正式なロビーではない</h2>
 * 設計書 6-2 が定める「{@code /} を手動モードの新ロビーにする」は {@code LobbyController} の
 * 変更を伴うため 19a で行う。本コントローラは、盤面(4-1〜4-4)を検証するための最小限の入口である。
 * {@code /manual/battle/new} で部屋を作り、{@code occupantId} が無ければ自動入室してから
 * リダイレクトする。名前入力・在室者リスト・切断復帰などは 19a の範囲であり、ここでは行わない。
 *
 * <h2>occupantId をクエリパラメータで渡す理由</h2>
 * 19a で localStorage による復帰(設計書 6-3)に置き換わる前提の、暫定の受け渡し方法である。
 * URL を控えておけば同じ occupantId で戻れる(仮の復帰にはなる)。
 */
@Controller
@RequiredArgsConstructor
public class ManualBattleController {

    private final ManualRoomManager roomManager;

    /** 部屋を新規作成し、盤面へリダイレクトする。 */
    @GetMapping("/manual/battle/new")
    public String newRoom() {
        ManualRoom room = roomManager.createRoom();
        return "redirect:/manual/battle/" + room.getRoomId();
    }

    /**
     * 盤面を表示する。{@code occupantId} が無い、または部屋に存在しない場合は
     * 新規在室者として入室し、occupantId 付きの URL へリダイレクトする。
     */
    @GetMapping("/manual/battle/{roomId}")
    public String battle(@PathVariable String roomId,
            @RequestParam(required = false) String occupantId,
            Model model) {
        ManualRoom room = roomManager.requireRoom(roomId);

        boolean known = occupantId != null && room.findOccupant(occupantId).isPresent();
        if (!known) {
            ManualOccupant occupant;
            synchronized (room.getLock()) {
                occupant = room.join(null, ManualOccupantRole.PLAYER);
            }
            return "redirect:/manual/battle/" + room.getRoomId()
                    + "?occupantId=" + occupant.getOccupantId();
        }

        model.addAttribute("roomId", room.getRoomId());
        model.addAttribute("occupantId", occupantId);
        model.addAttribute("defaultLabels", ManualLabels.DEFAULTS);
        return "manual-battle";
    }
}
