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
    '<link th:href="@{/css/battle.css(v=27)}" rel="stylesheet">',
    '<link href="/css/battle.css" rel="stylesheet">',
)
html = html.replace(
    '<script th:src="@{/js/manual-battle.js(v=21)}"></script>',
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
  body { font-family: sans-serif; }
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
  .text-muted { color: #adb5bd; }
</style>
<script>
  window.__sent = [];
  window.StompJs = {
    Client: class {
      constructor(options) { this.options = options; }
      activate() { /* 検証では接続しない。renderAll を直接呼ぶ */ }
      deactivate() {}
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
