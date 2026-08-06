package com.example.qte.manual;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * デッキファイルの読み込み。JSON(Batch 24)と、ユドナリウムの card-stack XML zip
 * (設計書 7章)の2形式を受け付ける。
 *
 * <h2>★Batch 24: JSON が標準形式、zip は後方互換</h2>
 * デッキメーカーがアプリに組み込まれたため({@code /deck-maker})、デッキの標準形式は
 * カードIDで書かれた JSON({@code format: taboo-elemental-deck})になった。
 * 突合キーは {@code manual-cards.json} のカードIDそのものである。
 * ユドナリウム由来の zip も従来どおり読める。形式は先頭バイトで判別する
 * ({@link #importAuto})ため、受け口({@code /manual/api/rooms/{id}/deck})は1つのままである。
 * 以下の説明は zip 経路のものである。
 *
 * <hr>
 *
 * ユドナリウムの card-stack XML(zip)を読み込む(設計書 7章)。
 *
 * <h2>★突合キーは表面画像IDのみである</h2>
 * 名前は使わない。理由は3つある。
 * (1) 表記ゆれが実在する(全角スペース・中黒・{@code 【} の欠落)。
 * (2) 名前が誤っているカードが実在する(水15のリーダーは画像が正で名前が誤っていた。
 *     実サンプルの main.xml の先頭も「傷痕の闘帝」という誤った名前のままである)。
 * (3) 画像IDは内容に対応する不透明なキーであり、突合が成立すれば確実に同じカードである。
 *
 * <h2>階層をたどらない</h2>
 * ユドナリウムの XML は {@code data} タグの入れ子による汎用フォーマットで、階層が固定されない。
 * したがって {@code <card>} 要素の配下を<b>全走査</b>し、
 * {@code name="front"} と {@code name="name"} を拾う。
 * 「card の下の image の下の front」のように道順を書くと、
 * ユドナリウム側の構造が変わった瞬間に無言で0枚になる。
 *
 * <h2>アップロードされた XML を信用しない</h2>
 * DTD・外部実体を無効化する。これは利用者を疑う話ではなく、
 * 「他人から受け取ったデッキファイル」が普通に流通する道具だからである。
 */
@Component
@RequiredArgsConstructor
public class ManualDeckImporter {

    /** メインデッキのファイル名 */
    private static final String MAIN_FILE = "main.xml";

    /** 禁忌デッキのファイル名 */
    private static final String TABOO_FILE = "kinki.xml";

    /** 1エントリの展開上限。デッキXMLは実測40KB程度であり、これを超えるものは読まない */
    private static final int MAX_ENTRY_BYTES = 8 * 1024 * 1024;

    /** メインデッキの規定枚数(リーダーを除く) */
    private static final int MAIN_DECK_SIZE = 40;

    /** 禁忌デッキの規定枚数 */
    private static final int TABOO_DECK_SIZE = 8;

    /** メインデッキの同名上限 */
    private static final int MAIN_NAME_LIMIT = 4;

    /** 禁忌デッキの同名上限(ハイランダー) */
    private static final int TABOO_NAME_LIMIT = 1;

    /** JSON デッキ1件が持てるカード枚数の上限(異常な qty への防波堤。実デッキは49枚) */
    private static final int MAX_JSON_CARDS = 200;

    private final ManualCardRepository cards;

    private final ObjectMapper objectMapper;

    /**
     * 形式を判別してデッキを読み込む(Batch 24)。
     *
     * zip はマジックナンバー {@code PK} で始まることが仕様で保証されている。
     * 拡張子やContent-Typeはクライアントの自己申告であり、判別に使わない
     * (設計判断27「外部から来るデータはすべて検証する」)。
     */
    public ManualDeckImport importAuto(byte[] body) {
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("デッキファイルが空です");
        }
        if (body.length >= 2 && body[0] == 'P' && body[1] == 'K') {
            return importZip(body);
        }
        return importJson(body);
    }

    /**
     * JSON デッキ({@code format: taboo-elemental-deck})を読み込む(Batch 24)。
     *
     * <h3>受け付ける形</h3>
     * <pre>
     * { "format": "taboo-elemental-deck", "version": 2, "deckName": "...",
     *   "leader": {"cardId": "...", "name": "..."},
     *   "main":  [ {"cardId": "...", "name": "...", "qty": 4}, ... ],
     *   "taboo": [ {"cardId": "...", "name": "..."}, ... ] }
     * </pre>
     * 過渡期の揺れを許す: {@code leader} の代わりに {@code leaderId} 文字列、
     * {@code taboo} の要素がID文字列だけ、でも読める。名前(name)は解決失敗時の
     * 表示用にだけ使う。<b>突合はカードIDのみで行う</b>(名前を突合キーにしない理由は
     * クラスコメントの(1)(2)と同じである)。
     *
     * <h3>zip 経路と同じ扱い</h3>
     * 解決できないIDは灰色タイル(名前だけ)として通し、構築ルール違反は警告に留める。
     * 検証は zip 経路と同じ {@link #validate} を通る。判定を2箇所に書かない。
     */
    public ManualDeckImport importJson(byte[] body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("デッキファイルとして読めませんでした(JSONでもzipでもない)");
        }
        if (root == null || !root.isObject()
                || !"taboo-elemental-deck".equals(textOrNull(root.path("format")))) {
            throw new IllegalArgumentException(
                    "デッキファイルの形式が違います(format: taboo-elemental-deck のみ)");
        }
        List<String> warnings = new ArrayList<>();

        ManualDeckImport.Entry leader = readJsonLeader(root, warnings);
        List<ManualDeckImport.Entry> mainCards = readJsonEntries(root.path("main"), true, warnings);
        List<ManualDeckImport.Entry> tabooCards = readJsonEntries(root.path("taboo"), false, warnings);

        validate(leader, mainCards, tabooCards, warnings);
        return new ManualDeckImport(
                textOrNull(root.path("deckName")), leader, mainCards, tabooCards, warnings);
    }

    // ---- JSON ----

    private ManualDeckImport.Entry readJsonLeader(JsonNode root, List<String> warnings) {
        JsonNode leaderNode = root.path("leader");
        String cardId = leaderNode.isObject()
                ? textOrNull(leaderNode.path("cardId"))
                : textOrNull(root.path("leaderId"));
        String name = leaderNode.isObject() ? textOrNull(leaderNode.path("name")) : null;
        if (cardId == null) {
            warnings.add("リーダーが見つからなかった");
            return null;
        }
        return toEntry(cardId, name);
    }

    /**
     * main / taboo の配列を Entry の列に展開する。
     * {@code qty} はメインのみ有効(禁忌はハイランダーなので常に1枚として扱う)。
     */
    private List<ManualDeckImport.Entry> readJsonEntries(JsonNode array, boolean allowQty,
            List<String> warnings) {
        List<ManualDeckImport.Entry> result = new ArrayList<>();
        if (!array.isArray()) {
            return result;
        }
        for (JsonNode element : array) {
            String cardId = element.isObject() ? textOrNull(element.path("cardId"))
                    : textOrNull(element);
            if (cardId == null) {
                warnings.add("cardId の無いエントリを無視した");
                continue;
            }
            String name = element.isObject() ? textOrNull(element.path("name")) : null;
            int qty = allowQty && element.isObject() ? element.path("qty").asInt(1) : 1;
            if (qty < 1) {
                qty = 1;
            }
            for (int i = 0; i < qty; i++) {
                if (result.size() >= MAX_JSON_CARDS) {
                    warnings.add("カード枚数が %d 枚を超えたため以降を打ち切った".formatted(MAX_JSON_CARDS));
                    return result;
                }
                result.add(toEntry(cardId, name));
            }
        }
        return result;
    }

    /**
     * カードIDを台帳と突合して Entry にする。解決できない場合は名前(無ければID)の
     * 灰色タイルになる。★Entry の imageId には慣例上カードIDを入れる。
     * {@link #checkUnresolved} の警告文がこの欄で個体を名指しするためである
     * (zip 経路では画像ID、JSON経路ではカードIDが「突合に失敗したキー」に当たる)。
     */
    private ManualDeckImport.Entry toEntry(String cardId, String name) {
        Optional<ManualCardMaster> found = cards.findOptionalById(cardId);
        String rawName = name != null ? name : cardId;
        return new ManualDeckImport.Entry(found.orElse(null), rawName, cardId);
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? null : text;
    }

    /**
     * zip のバイト列からデッキを組み立てる。
     * 読めるところまで読み、問題は {@link ManualDeckImport#warnings()} に積む(設計書 7-3)。
     */
    public ManualDeckImport importZip(byte[] zipBytes) {
        List<String> warnings = new ArrayList<>();
        if (zipBytes == null || zipBytes.length == 0) {
            throw new IllegalArgumentException("デッキファイルが空です");
        }
        Map<String, byte[]> xmlEntries = readXmlEntries(zipBytes, warnings);
        if (xmlEntries.isEmpty()) {
            throw new IllegalArgumentException("zip の中に xml が1つもありません");
        }

        Map<String, List<RawCard>> parsed = new LinkedHashMap<>();
        Map<String, String> stackNames = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : xmlEntries.entrySet()) {
            Document doc = parseXml(entry.getKey(), entry.getValue());
            parsed.put(entry.getKey(), readCards(doc));
            stackNames.put(entry.getKey(), readStackName(doc));
        }

        Assignment assignment = assign(parsed, warnings);
        List<RawCard> mainRaw = assignment.main() == null
                ? List.of() : parsed.get(assignment.main());
        List<RawCard> tabooRaw = assignment.taboo() == null
                ? List.of() : parsed.get(assignment.taboo());

        List<ManualDeckImport.Entry> mainCards = resolveAll(mainRaw);
        List<ManualDeckImport.Entry> tabooCards = resolveAll(tabooRaw);

        ManualDeckImport.Entry leader = extractLeader(mainCards, warnings);
        String deckName = assignment.main() == null ? null : stackNames.get(assignment.main());

        validate(leader, mainCards, tabooCards, warnings);
        return new ManualDeckImport(deckName, leader, mainCards, tabooCards, warnings);
    }

    // ---- zip ----

    /**
     * zip から xml エントリだけを取り出す。xml 以外は無視する(レビューL反映。
     * ユドナリウムの保存 zip は画像を同梱することがある)。
     *
     * ★エントリ名の文字コードで2回試す。ZipInputStream は UTF-8 として不正なバイト列の
     * エントリ名に出会うと例外で止まるため、Windows のエクスプローラで作った
     * (日本語ファイル名が CP932 の)zip を1回目で落としてしまう。
     * 落ちた場合だけ MS932 で読み直す。デッキXML自体の文字コードは XML 宣言に従う。
     */
    private Map<String, byte[]> readXmlEntries(byte[] zipBytes, List<String> warnings) {
        try {
            return readXmlEntriesWith(zipBytes, StandardCharsets.UTF_8, warnings);
        } catch (IOException | IllegalArgumentException e) {
            warnings.clear();
            try {
                return readXmlEntriesWith(zipBytes, Charset.forName("windows-31j"), warnings);
            } catch (IOException | IllegalArgumentException second) {
                throw new IllegalArgumentException("zip として読めませんでした: " + second.getMessage());
            }
        }
    }

    private Map<String, byte[]> readXmlEntriesWith(byte[] zipBytes, Charset nameCharset,
            List<String> warnings) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        int ignored = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes), nameCharset)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String base = baseNameOf(entry.getName());
                if (!base.toLowerCase(Locale.ROOT).endsWith(".xml")) {
                    ignored++;
                    continue;
                }
                result.put(base, readLimited(zip));
            }
        }
        if (ignored > 0) {
            warnings.add("xml 以外のエントリを %d 件無視した".formatted(ignored));
        }
        return result;
    }

    private byte[] readLimited(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = zip.read(buffer)) > 0) {
            total += read;
            if (total > MAX_ENTRY_BYTES) {
                throw new IllegalArgumentException("zip 内のファイルが大きすぎます");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** ディレクトリ部分を落としたファイル名。zip の中で入れ子になっていても拾えるようにする。 */
    private String baseNameOf(String path) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }

    // ---- ファイルの割り当て ----

    /** どの xml をメイン / 禁忌として扱うか */
    private record Assignment(String main, String taboo) {
    }

    /**
     * メインと禁忌をファイル名で判別する(設計書 7-2)。
     * 想定と違う名前でも、手掛かりがあれば読める側だけ読む(設計書 7-3)。
     */
    private Assignment assign(Map<String, List<RawCard>> parsed, List<String> warnings) {
        String main = null;
        String taboo = null;
        List<String> rest = new ArrayList<>();
        for (String name : parsed.keySet()) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (main == null && lower.equals(MAIN_FILE)) {
                main = name;
            } else if (taboo == null && lower.equals(TABOO_FILE)) {
                taboo = name;
            } else {
                rest.add(name);
            }
        }
        List<String> stillRest = new ArrayList<>();
        for (String name : rest) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (main == null && (lower.contains("main") || name.contains("メイン"))) {
                main = name;
                warnings.add("メインデッキのファイル名が %s ではない: %s".formatted(MAIN_FILE, name));
            } else if (taboo == null && (lower.contains("kinki") || name.contains("禁忌"))) {
                taboo = name;
                warnings.add("禁忌デッキのファイル名が %s ではない: %s".formatted(TABOO_FILE, name));
            } else {
                stillRest.add(name);
            }
        }
        // 名前から判別できなかったものは枚数で当てる。多い方がメインである
        stillRest.sort(Comparator.comparingInt((String n) -> parsed.get(n).size()).reversed());
        for (String name : stillRest) {
            if (main == null) {
                main = name;
                warnings.add("ファイル名から判別できなかったため、枚数の多い %s をメインデッキとした".formatted(name));
            } else if (taboo == null) {
                taboo = name;
                warnings.add("ファイル名から判別できなかったため、%s を禁忌デッキとした".formatted(name));
            } else {
                warnings.add("使い道が判別できなかったため無視した: " + name);
            }
        }
        if (main == null) {
            warnings.add("メインデッキが見つからなかった");
        }
        if (taboo == null) {
            warnings.add("禁忌デッキが見つからなかった");
        }
        return new Assignment(main, taboo);
    }

    // ---- XML ----

    /** XML から読み取った1枚(突合前) */
    private record RawCard(String imageId, String name) {
    }

    private Document parseXml(String fileName, byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // ★外部から届くファイルなので DTD と外部実体を切る
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new IllegalArgumentException("%s を XML として読めませんでした".formatted(fileName));
        }
    }

    /**
     * {@code <card>} 要素を全走査し、配下の {@code name="front"} と {@code name="name"} を拾う。
     * 階層はたどらない(設計書 1-8)。
     */
    private List<RawCard> readCards(Document doc) {
        List<RawCard> result = new ArrayList<>();
        NodeList cardNodes = doc.getElementsByTagName("card");
        for (int i = 0; i < cardNodes.getLength(); i++) {
            Element card = (Element) cardNodes.item(i);
            String front = firstDataValue(card, "front");
            String name = firstDataValue(card, "name");
            if (front == null || front.isBlank()) {
                continue;
            }
            result.add(new RawCard(front.trim(), name == null ? "" : name.trim()));
        }
        return result;
    }

    /** 配下(自分自身は含まない)の {@code data} 要素のうち、指定の name を持つ最初のものの中身。 */
    private String firstDataValue(Element parent, String dataName) {
        NodeList dataNodes = parent.getElementsByTagName("data");
        for (int i = 0; i < dataNodes.getLength(); i++) {
            Element data = (Element) dataNodes.item(i);
            if (dataName.equals(data.getAttribute("name"))) {
                return data.getTextContent();
            }
        }
        return null;
    }

    /**
     * card-stack 自身の名前(「水_メイン」など)。表示に使うだけなので、
     * 取れなければ黙って null を返す。
     * ここだけは直下の子をたどる。カードと違って {@code <card>} という目印が無く、
     * 全走査すると1枚目のカード名を拾ってしまうためである。
     */
    private String readStackName(Document doc) {
        Element root = doc.getDocumentElement();
        if (root == null) {
            return null;
        }
        Element stack = childData(root, "card-stack");
        Element common = stack == null ? null : childData(stack, "common");
        Element name = common == null ? null : childData(common, "name");
        if (name == null) {
            return null;
        }
        String text = name.getTextContent();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private Element childData(Element parent, String dataName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) node;
            if ("data".equals(element.getTagName()) && dataName.equals(element.getAttribute("name"))) {
                return element;
            }
        }
        return null;
    }

    // ---- 突合と検証 ----

    private List<ManualDeckImport.Entry> resolveAll(List<RawCard> raws) {
        List<ManualDeckImport.Entry> result = new ArrayList<>();
        for (RawCard raw : raws) {
            Optional<ManualCardMaster> found = cards.findByImageId(raw.imageId());
            result.add(new ManualDeckImport.Entry(found.orElse(null), raw.name(), raw.imageId()));
        }
        return result;
    }

    /**
     * メインデッキの先頭をリーダーとして取り出す(設計書 7-2)。
     * 先頭がリーダーでなければ、種別がリーダーの行を探して代用する(設計書 7-3)。
     */
    private ManualDeckImport.Entry extractLeader(List<ManualDeckImport.Entry> mainCards,
            List<String> warnings) {
        if (mainCards.isEmpty()) {
            warnings.add("リーダーが見つからなかった");
            return null;
        }
        ManualDeckImport.Entry head = mainCards.get(0);
        if (head.master() != null && head.master().type() == ManualCardType.LEADER) {
            mainCards.remove(0);
            return head;
        }
        for (int i = 0; i < mainCards.size(); i++) {
            ManualDeckImport.Entry candidate = mainCards.get(i);
            if (candidate.master() != null && candidate.master().type() == ManualCardType.LEADER) {
                mainCards.remove(i);
                warnings.add("リーダーが先頭になかったため %d 枚目の %s を代用した"
                        .formatted(i + 1, candidate.displayName()));
                return candidate;
            }
        }
        warnings.add("リーダーが見つからなかった");
        return null;
    }

    /**
     * 構築ルールの検証(設計書 7-4)。
     * ★違反はすべて警告に留める。{@code DeckValidator} には触らず、
     * 実装済み文明・実装済みスペルの判定も一切行わない。
     * 手動モードは未実装のカードを場に出して試すための場所である。
     */
    private void validate(ManualDeckImport.Entry leader, List<ManualDeckImport.Entry> mainCards,
            List<ManualDeckImport.Entry> tabooCards, List<String> warnings) {
        if (mainCards.size() != MAIN_DECK_SIZE) {
            warnings.add("メインデッキが %d 枚ではない(%d 枚)".formatted(MAIN_DECK_SIZE, mainCards.size()));
        }
        if (tabooCards.size() != TABOO_DECK_SIZE) {
            warnings.add("禁忌デッキが %d 枚ではない(%d 枚)".formatted(TABOO_DECK_SIZE, tabooCards.size()));
        }
        checkNameLimit(mainCards, MAIN_NAME_LIMIT, "メインデッキ", warnings);
        checkNameLimit(tabooCards, TABOO_NAME_LIMIT, "禁忌デッキ", warnings);
        checkCivilization(leader, mainCards, tabooCards, warnings);
        checkUnresolved(leader, mainCards, tabooCards, warnings);
    }

    /**
     * 同名上限の検証。★カードテキストによる上書きを許す(総合ルール 7-3 は
     * デッキ構築検証にも及ぶ。例: ゾンストライカー「4枚以上入れられる」)。
     * 上書きの宣言はコードではなくカード定義({@code manual-cards.json} の
     * {@code unlimitedCopies})が持つ。IDや名前をここに書かない。
     */
    private void checkNameLimit(List<ManualDeckImport.Entry> deck, int limit, String label,
            List<String> warnings) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Boolean> exempt = new LinkedHashMap<>();
        for (ManualDeckImport.Entry entry : deck) {
            counts.merge(entry.displayName(), 1, Integer::sum);
            if (entry.master() != null && entry.master().unlimitedCopies()) {
                exempt.put(entry.displayName(), true);
            }
        }
        for (Map.Entry<String, Integer> count : counts.entrySet()) {
            if (count.getValue() > limit && !exempt.containsKey(count.getKey())) {
                warnings.add("%s の同名上限 %d 枚を超えている: %s(%d 枚)"
                        .formatted(label, limit, count.getKey(), count.getValue()));
            }
        }
    }

    private void checkCivilization(ManualDeckImport.Entry leader, List<ManualDeckImport.Entry> mainCards,
            List<ManualDeckImport.Entry> tabooCards, List<String> warnings) {
        if (leader == null || leader.master() == null) {
            return;
        }
        ManualCivilization civ = leader.master().civilization();
        List<String> offMain = new ArrayList<>();
        for (ManualDeckImport.Entry entry : mainCards) {
            if (entry.master() != null && entry.master().civilization() != civ) {
                offMain.add(entry.displayName());
            }
        }
        if (!offMain.isEmpty()) {
            warnings.add("メインデッキにリーダー(%s文明)と異なる文明のカードがある: %s"
                    .formatted(civ.getDisplayName(), String.join(" / ", offMain.stream().distinct().toList())));
        }
        List<String> sameTaboo = new ArrayList<>();
        for (ManualDeckImport.Entry entry : tabooCards) {
            if (entry.master() != null && entry.master().civilization() == civ) {
                sameTaboo.add(entry.displayName());
            }
        }
        if (!sameTaboo.isEmpty()) {
            warnings.add("禁忌デッキにリーダー(%s文明)と同じ文明のカードがある: %s"
                    .formatted(civ.getDisplayName(), String.join(" / ", sameTaboo.stream().distinct().toList())));
        }
    }

    private void checkUnresolved(ManualDeckImport.Entry leader, List<ManualDeckImport.Entry> mainCards,
            List<ManualDeckImport.Entry> tabooCards, List<String> warnings) {
        List<ManualDeckImport.Entry> all = new ArrayList<>();
        if (leader != null) {
            all.add(leader);
        }
        all.addAll(mainCards);
        all.addAll(tabooCards);
        for (ManualDeckImport.Entry entry : all) {
            if (!entry.isResolved()) {
                String head = entry.imageId().length() > 8
                        ? entry.imageId().substring(0, 8) : entry.imageId();
                warnings.add("カード定義に無い画像IDのカードがある(名前だけの灰色タイルとして扱う): %s(%s…)"
                        .formatted(entry.rawName(), head));
            }
        }
    }
}
