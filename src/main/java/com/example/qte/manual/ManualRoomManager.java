package com.example.qte.manual;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.example.qte.room.RoomIds;

/**
 * 手動モードの全部屋の台帳。
 *
 * ★通常モードの {@code GameRoomManager} とは別の台帳である(設計書 2-1)。
 * 同居させない理由は、両者の部屋が持つものが違うからではなく、
 * 「通常モードの部屋を探す処理が手動モードの部屋を見つけてしまう」経路を
 * 一切作らないためである。宛先の前置詞({@code /app/room} と {@code /app/manual})も
 * 分かれているため、どの層でも取り違えが起きない。
 *
 * 部屋IDの生成だけは {@link RoomIds} で共用する。IDの形(口頭で伝えられる6文字)は
 * 手動モードでも同じ要件だからである。両台帳のIDが偶然一致しても、
 * 引く先が別の Map なので実害は無い。
 */
@Component
public class ManualRoomManager {

    private final Map<String, ManualRoom> rooms = new ConcurrentHashMap<>();

    public ManualRoom createRoom() {
        // 万一の衝突時は生成し直す。putIfAbsent で「確認と登録」を原子的に行う
        while (true) {
            String roomId = RoomIds.generate();
            ManualRoom room = new ManualRoom(roomId);
            if (rooms.putIfAbsent(roomId, room) == null) {
                return room;
            }
        }
    }

    public Optional<ManualRoom> findRoom(String roomId) {
        String key = RoomIds.normalize(roomId);
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(rooms.get(key));
    }

    /** 部屋を引く。無ければ例外。HTTP 側の入口で使う。 */
    public ManualRoom requireRoom(String roomId) {
        return findRoom(roomId)
                .orElseThrow(() -> new IllegalArgumentException("部屋が見つかりません: " + roomId));
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
