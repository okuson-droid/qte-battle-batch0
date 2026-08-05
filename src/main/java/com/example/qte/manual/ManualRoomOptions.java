package com.example.qte.manual;

/**
 * 部屋の作成時に決まる属性(Batch 21 設計書 1-2)。★作成後は変更しない。
 *
 * <h2>なぜ {@link ManualRoom} のフィールドを並べずにレコードで束ねるのか</h2>
 * この4項目は「作成フォームの入力そのもの」であり、常に一緒に生まれて一緒に読まれる。
 * 束ねておくと、部屋一覧 API・部屋作成 API・{@link ManualRoomManager#createRoom} の
 * 3箇所が同じ型を受け渡すだけで済む。項目が増えたときに触る場所も1つで済む。
 *
 * @param name             部屋名。一覧に表示する。★重複を許す(人間が区別できればよい)
 * @param type             部屋の種類(1-1)
 * @param spectatorAllowed 観戦を許可するか。false の部屋では観戦者として入室できない
 * @param requireRoomId    入室に部屋IDを要求するか。
 *                         ★部屋ID(ランダム6文字)がそのままパスワードを兼ねる(設計書 1-2)。
 *                         true の部屋は<b>一覧に部屋IDを載せない</b>。IDを知っていることが
 *                         入室の権利になるためであり、一覧に出した瞬間に鍵の意味が消える。
 */
public record ManualRoomOptions(
        String name,
        ManualRoomType type,
        boolean spectatorAllowed,
        boolean requireRoomId) {

    /** 部屋名の最大文字数。裁定ではなく入力の衛生である({@link ManualLabels} と同じ考え方)。 */
    public static final int MAX_NAME_LENGTH = 40;

    /** 名前を省略したときの既定(全公開部屋のみ。対戦部屋では名前を必須にする)。 */
    public static final String DEFAULT_NAME = "無名の部屋";

    /**
     * 入力を正規化する。★空白落とし・既定値の補完・上限の検査をここ1箇所で行う。
     *
     * ★対戦部屋では部屋名を必須にする(設計書 1-2・F4)。一覧から選ぶ以上、
     * 名前の無い部屋は人間が区別できない。全公開部屋は自分で作って自分で入る使い方が
     * 主であり、省略を許す。
     */
    public ManualRoomOptions {
        type = type == null ? ManualRoomType.OPEN : type;
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) {
            if (type.isRestricted()) {
                throw new IllegalArgumentException("対戦部屋には部屋名が必要です");
            }
            name = DEFAULT_NAME;
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("部屋名は %d 文字までです".formatted(MAX_NAME_LENGTH));
        }
    }

    /**
     * 従来どおりの全公開部屋(観戦可・鍵なし)。
     * ★Batch 20c までに作られた入口({@code POST /manual/api/rooms})はこれを使う。
     */
    public static ManualRoomOptions openDefault() {
        return new ManualRoomOptions(null, ManualRoomType.OPEN, true, false);
    }
}
