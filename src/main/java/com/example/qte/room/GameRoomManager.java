package com.example.qte.room;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 全対戦部屋の台帳。アプリ内で唯一のsingletonビーン。
 *
 * ここが「ゲーム状態をメモリに持つ」の物理的な置き場所である:
 * singletonビーンのフィールド(ConcurrentHashMap)に可変データをぶら下げる。
 * EM-Springで学んだ「singletonビーンはステートレスに」の原則の、意図的な例外。
 * なぜ例外が許されるか・どう安全にするかは batch0-design-notes.md 2章を参照。
 *
 * <h2>★Batch 66: 部屋IDの生成を {@link RoomIds} へ寄せた</h2>
 * 17b が「通常モードと手動モードで共用する」ために {@link RoomIds} を作り、
 * <b>そのクラスの Javadoc に「既存ファイルの変更が許されるバッチ(19a)で差し替える」と
 * 書いてあった</b>。19a は来たが、差し替えは行われなかった ——
 * 64 の教訓(「暫定」は前提が消えても自分では戻らない)と
 * 65 の教訓(「簡易版」は本家が直っても自分では直らない)の<b>3例目</b>である。
 * 文字集合も長さも同一だったので実害は出ていないが、
 * <b>実害が出ていないことは、2つあってよい理由にはならない</b>(設計判断28)。
 */
@Component
public class GameRoomManager {

    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();

    /** 部屋を作る。★Batch 66 から属性(部屋名・観戦・鍵)が必須である */
    public GameRoom createRoom(GameRoomOptions options) {
        // 万一の衝突時は生成し直す。putIfAbsentで「確認と登録」を原子的に行う
        while (true) {
            String roomId = RoomIds.generate();
            GameRoom room = new GameRoom(roomId, options);
            if (rooms.putIfAbsent(roomId, room) == null) {
                return room;
            }
        }
    }

    public Optional<GameRoom> findRoom(String roomId) {
        String key = RoomIds.normalize(roomId);
        return key == null ? Optional.empty() : Optional.ofNullable(rooms.get(key));
    }

    /**
     * 部屋を引く。無ければ理由を返して止める(★Batch 66)。
     * ★手動モードの {@code ManualRoomManager.requireRoom} と同じ形にしてある ——
     * 「無いときの文言」を呼び出し側ごとに書くと、経路によって違う言葉が出る。
     */
    public GameRoom requireRoom(String roomId) {
        return findRoom(roomId).orElseThrow(
                () -> new IllegalArgumentException("部屋が見つかりません: " + roomId));
    }

    /** 台帳にある全部屋(★Batch 66・ロビーの一覧が読む) */
    public Collection<GameRoom> allRooms() {
        return List.copyOf(new ArrayList<>(rooms.values()));
    }

    public void removeRoom(String roomId) {
        String key = RoomIds.normalize(roomId);
        if (key != null) {
            rooms.remove(key);
        }
    }

    public int roomCount() {
        return rooms.size();
    }
}
