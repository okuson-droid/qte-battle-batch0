#!/usr/bin/env python3
"""Batch 20b 検証用ハーネスの生成。

Thymeleaf テンプレート `manual-battle.html` を、実ファイルの `battle.css` /
`manual-battle.js` をそのまま読み込む素の HTML へ変換する。

★合成 DragEvent は使わない。ここで作るのは「実ファイルを実際のブラウザに載せた盤面」であり、
検証そのものは Playwright の page.mouse による実マウス操作で行う(19b hotfix2・20a 3-1/3-2)。

外部 CDN(Bootstrap / StompJs)はサンドボックスのネットワーク制限で読み込めないため、
- Bootstrap: 検証に必要な `.d-none` だけを最小限のスタイルで代替する
- StompJs: 送信内容を window.__sent へ捕捉するスタブに差し替える
"""
import re
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
TEMPLATE = ROOT / "src/main/resources/templates/manual-battle.html"
OUT = pathlib.Path(__file__).resolve().parent / "harness.html"
LOBBY_TEMPLATE = ROOT / "src/main/resources/templates/manual-lobby.html"
LOBBY_OUT = pathlib.Path(__file__).resolve().parent / "harness-lobby.html"

html = TEMPLATE.read_text(encoding="utf-8")

# CDN を落とす(読み込めないうえ、待ち時間が検証を不安定にする)
html = html.replace(
    '<link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css" rel="stylesheet">',
    "",
)
html = html.replace(
    '<script src="https://cdn.jsdelivr.net/npm/@stomp/stompjs@7.0.0/bundles/stomp.umd.min.js"></script>',
    "",
)

# 実ファイルを相対パスで読ませる
html = html.replace(
    '<link th:href="@{/css/battle.css(v=32)}" rel="stylesheet">',
    '<link href="/css/battle.css" rel="stylesheet">',
)
html = html.replace(
    '<script th:src="@{/js/manual-battle.js(v=24)}"></script>',
    '<script src="/js/manual-battle.js"></script>',
)

# Thymeleaf 属性を落とす(値は静的なもので置き換える)
html = html.replace('th:text="${roomId}">------', ">TESTRM")
html = re.sub(r'\s+th:href="[^"]*"', ' href="#"', html)
html = html.replace("/*[[${roomId}]]*/ ''", "'TESTRM'")
html = html.replace("/*[[${defaultLabels}]]*/ []", "['凍結', '守護']")

# Bootstrap の代替(検証で効いている必要があるのは非表示制御だけ)+ STOMP スタブ
stub = """
<style>
  /* ★★Batch 21c: Bootstrap Reboot の box-sizing。これが無いと min-height / height に
     padding と border が加算され、ハーネスだけ盤面が縦に伸びる。
     20c の「Bootstrap 代替の漏れはハーネスでだけ壊れる」と同じ罠であり、
     相手上段の 148px 制約(4章)を測ろうとして初めて表面化した。 */
  *, *::before, *::after { box-sizing: border-box; }
  .d-none { display: none !important; }
  /* ★★★Batch 31: 実ページの<b>背景色</b>を再現する。
     テンプレートは <body class="bg-dark text-light"> であり、実際の盤面は<b>黒背景</b>である。
     ここが抜けていたためハーネスは白背景で描画されており、
     25c から 30 までの<b>文字色の判断がすべて誤った背景に対して行われた</b>。
     30 で入れた「コントラスト比 4.5:1 以上」の機械判定さえ白背景で測っていたため、
     黒背景の上では読めない文字を「合格」と報告していた。
     ★見た目の検証は、実ページと同じ背景の上でしか意味を持たない。
     ★ロビー(manual-lobby.html)は <body class="bg-light"> なので下のスタブは白のままでよい。 */
  body { font-family: sans-serif; background: #212529; color: #f8f9fa; }
  .bg-dark { background-color: #212529; }
  .text-light { color: #f8f9fa; }
  .text-danger { color: #ea868f; }
  .btn-outline-light { color: #f8f9fa; border: 1px solid #f8f9fa; background: transparent; }
  .btn-outline-secondary { color: #dee2e6; border: 1px solid #6c757d; background: transparent; }
  .info-modal { position: fixed; inset: 0; background: rgba(0,0,0,.6); z-index: 2000;
                display: flex; align-items: center; justify-content: center; }
  .info-modal-body { background: #222; padding: 16px; border-radius: 6px; }
  /* ★Bootstrap の代替。ここに漏れがあると「ハーネスでだけ壊れる」ため、
     テンプレートで使っているユーティリティは必ず足すこと(20cで flex-column の
     欠落によりログ幅が16pxになり、実態とズレた検証をしかけた)。 */
  .container-fluid { width: 100%; box-sizing: border-box; }
  .d-flex { display: flex; } .flex-wrap { flex-wrap: wrap; }
  .flex-column { flex-direction: column; }
  .ms-auto { margin-left: auto; } .gap-1 { gap: 4px; } .gap-2 { gap: 8px; }
  .align-items-center { align-items: center; } .align-items-end { align-items: flex-end; }
  .justify-content-between { justify-content: space-between; }
  .justify-content-end { justify-content: flex-end; }
  .justify-content-center { justify-content: center; }
  .mb-0 { margin-bottom: 0; } .mb-1 { margin-bottom: 4px; } .mb-2 { margin-bottom: 8px; }
  .mt-1 { margin-top: 4px; } .mt-2 { margin-top: 8px; }
  .p-2 { padding: 8px; } .py-1 { padding-top: 4px; padding-bottom: 4px; }
  .py-2 { padding: 8px 0; } .px-2 { padding-left: 8px; padding-right: 8px; }
  .w-100 { width: 100%; } .small { font-size: 0.875em; }
  .btn-group { display: inline-flex; }
  .form-control { width: 100%; box-sizing: border-box; }
  /* ★Batch 21b で足したユーティリティ。ロビー・席選択・在室者ポップオーバーが使う */
  .badge { display: inline-block; padding: 2px 6px; border-radius: 4px; background: #6c757d; }
  .alert { border-radius: 4px; background: #842029; }
  .form-label { display: block; }
  .form-control-sm { font-size: 0.875em; }
  .table { width: 100%; border-collapse: collapse; }
  .table-sm td, .table-sm th { padding: 2px 6px; }
  /* ★Batch 22: 操作説明を「場所 × 左/右/ドラッグ」の表へ書き直したときに使った代替。
     ハーネスに足りないと「ハーネスでだけ見た目が違う」状態になる(21c 2章の教訓)。 */
  .table-bordered td, .table-bordered th { border: 1px solid #495057; }
  thead th { text-align: center; vertical-align: bottom; }
  .align-middle td, .align-middle th { vertical-align: middle; }
  .fw-bold { font-weight: 700; }
  .ps-4 { padding-left: 1.5rem; }
  .py-0 { padding-top: 0; padding-bottom: 0; }
  .px-1 { padding-left: 4px; padding-right: 4px; }
  .mb-1 { margin-bottom: 4px; }
  /* ★Batch 23: 開始モーダル・マリガンオーバーレイが使うユーティリティ。
     代替に漏れがあると「ハーネスでだけ壊れる」(20c・21c・22 と同じ罠)。 */
  .flex-fill { flex: 1 1 auto; }
  .btn-warning { background: #ffc107; color: #000; }
  /* ★Batch 31: 暗い背景の上のアウトラインボタン。代替が無いと文字が地に沈み、
     ハーネスだけ「読めない文字」に見える(20c 以来の「代替の漏れ」の罠) */
  .btn-outline-warning { color: #ffda6a; border: 1px solid #ffc107; background: transparent; }
  .btn-outline-danger { color: #ea868f; border: 1px solid #dc3545; background: transparent; }
  .btn-outline-primary { color: #6ea8fe; border: 1px solid #0d6efd; background: transparent; }
  .text-muted { color: #adb5bd; }
  /* ★Batch 33: 切断オーバーレイと共有導線が使うユーティリティ。
     代替に漏れがあると「ハーネスでだけ壊れる」(20c 以来の罠) */
  .mb-0 { margin-bottom: 0; }
  p { margin-top: 0; margin-bottom: 1rem; }
</style>
<script>
  window.__sent = [];
  window.StompJs = {
    Client: class {
      // ★★Batch 33: 接続状態を持たせる。send() が接続を見るようになったため、
      //   ここが無いと<b>すべての操作が「切断中」として捨てられ</b>、
      //   32 までの全項目が一斉に落ちる。実物と同じく connected を名乗らせる。
      //   ★onConnect は呼ばない(呼ぶと ready が __sent に混ざる)。
      //     切断は verify.js から client.onWebSocketClose() を直接呼んで作る。
      constructor(options) { this.options = options; this.connected = true; }
      activate() { this.connected = true; }
      deactivate() { this.connected = false; }
      subscribe() {}
      publish(message) {
        window.__sent.push({
          destination: message.destination,
          body: JSON.parse(message.body),
        });
      }
    },
  };
  // ★入室ダイアログ(prompt)を出さないため、occupant を先に保存しておく
  localStorage.setItem('qte-manual-occupant-TESTRM',
      JSON.stringify({ occupantId: 'occ-test', displayName: 'テスト' }));
</script>
"""
html = html.replace("</head>", stub + "</head>")

OUT.write_text(html, encoding="utf-8")
print(f"wrote {OUT} ({len(html)} bytes)")

# ---------------------------------------------------------------------------
# ★Batch 21b: ロビー(manual-lobby.html)のハーネス。
# 盤面と違い、この画面は自分で /manual/api/rooms を叩いて一覧を描く。
# fetch はスタブせず、verify.js の HTTP サーバが実際に JSON を返す形にしてある
# (スタブに置き換えると「一覧APIの形が変わったのに検証は通る」状態を作ってしまう)。
# ---------------------------------------------------------------------------
lobby = LOBBY_TEMPLATE.read_text(encoding="utf-8")
lobby = lobby.replace(
    '<link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css" rel="stylesheet">',
    "",
)
lobby = re.sub(r'\s+th:href="[^"]*"', ' href="#"', lobby)

lobby_stub = """
<style>
  .d-none { display: none !important; }
  body { font-family: sans-serif; }
  .d-flex { display: flex; } .ms-auto { margin-left: auto; }
  .align-items-center { align-items: center; } .align-items-end { align-items: flex-end; }
  .table { width: 100%; border-collapse: collapse; }
  .form-control, .form-select { width: 100%; box-sizing: border-box; }
  .row { display: block; }
</style>
<script>
  // ★遷移させない。location.href の代入を捕まえて記録するだけにする
  window.__navigated = [];
  window.__origAssign = null;
</script>
"""
lobby = lobby.replace("</head>", lobby_stub + "</head>")
# goToRoom の遷移を記録に差し替える(実際に遷移すると検証が続けられない)
lobby = lobby.replace(
    "    function goToRoom(roomId) {\n        location.href = '/manual/battle/' + roomId;\n    }",
    "    function goToRoom(roomId) {\n        window.__navigated.push(roomId);\n    }",
)
LOBBY_OUT.write_text(lobby, encoding="utf-8")
print(f"wrote {LOBBY_OUT} ({len(lobby)} bytes)")
