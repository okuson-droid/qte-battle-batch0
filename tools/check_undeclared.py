#!/usr/bin/env python3
"""
JavaScript の「宣言されていない識別子を参照している」箇所を検出する。

Batch 11a で「変数の宣言だけ消して使用箇所を残した」事故が起きたため、
その再発を機械的に止めるためのスクリプトである。
Batch 12a では pendingReveal を廃止して pendingChoice に置き換えるため、
まさに同型の事故が最も起きやすい。

厳密な構文解析ではなく、次の近似で十分に事故を捕まえられる。
  - トップレベルの let/const/var/function 宣言を集める
  - 関数の仮引数・ローカル宣言・分割代入・catch 引数を集める
  - それ以外の識別子参照のうち、ブラウザ組み込み・既知のグローバルに無いものを報告する

プロパティアクセス(obj.foo の foo)、オブジェクトリテラルのキー、
文字列・コメント・テンプレートリテラルの中身は対象から除く。
"""
import re
import sys

BUILTINS = {
    # 言語・ブラウザ組み込み
    'window', 'document', 'console', 'JSON', 'Math', 'Object', 'Array', 'String',
    'Number', 'Boolean', 'Date', 'Promise', 'Map', 'Set', 'RegExp', 'Error',
    'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'location',
    'localStorage', 'sessionStorage', 'fetch', 'alert', 'confirm', 'prompt',
    'parseInt', 'parseFloat', 'isNaN', 'undefined', 'null', 'true', 'false',
    'this', 'arguments', 'typeof', 'instanceof', 'new', 'delete', 'void',
    'navigator', 'history', 'CustomEvent', 'Event', 'Element', 'Node',
    'Blob', 'File', 'FileReader', 'URL', 'FormData', 'XMLHttpRequest',
    # 外部ライブラリ・Thymeleafが埋め込むグローバル
    'StompJs', 'ROOM_ID', 'PLAYER_ID',
}

KEYWORDS = {
    'if', 'else', 'for', 'while', 'do', 'switch', 'case', 'default', 'break',
    'continue', 'return', 'function', 'var', 'let', 'const', 'class', 'extends',
    'try', 'catch', 'finally', 'throw', 'new', 'delete', 'typeof', 'instanceof',
    'in', 'of', 'this', 'super', 'yield', 'await', 'async', 'static', 'get', 'set',
    'true', 'false', 'null', 'undefined', 'void',
}


def strip_noise(src):
    """文字列・テンプレートリテラル・正規表現リテラル・コメントを空白に潰す(位置は保つ)。"""
    out = []
    i = 0
    n = len(src)
    # 直前の非空白文字が「値」で終わっていれば / は除算、そうでなければ正規表現リテラル
    def prev_significant(idx):
        j = idx - 1
        while j >= 0 and src[j] in ' \t\n':
            j -= 1
        return src[j] if j >= 0 else ''

    while i < n:
        c = src[i]
        two = src[i:i + 2]
        if two == '//':
            j = src.find('\n', i)
            j = n if j < 0 else j
            out.append(' ' * (j - i))
            i = j
        elif two == '/*':
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append(re.sub(r'[^\n]', ' ', src[i:j]))
            i = j
        elif c == '/' and prev_significant(i) not in ')]}' and not (
                prev_significant(i).isalnum() or prev_significant(i) in '_$'):
            # 正規表現リテラル。文字クラス [...] の中の / はデリミタではない
            j = i + 1
            in_class = False
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == '[':
                    in_class = True
                elif src[j] == ']':
                    in_class = False
                elif src[j] == '/' and not in_class:
                    break
                elif src[j] == '\n':
                    break
                j += 1
            # フラグ部分も飛ばす
            k = j + 1
            while k < n and src[k].isalpha():
                k += 1
            out.append(' ' * (min(k, n) - i))
            i = min(k, n)
        elif c in '"\'':
            j = i + 1
            while j < n and src[j] != c:
                if src[j] == '\\':
                    j += 1
                j += 1
            out.append(' ' * (min(j + 1, n) - i))
            i = min(j + 1, n)
        elif c == '`':
            # テンプレートリテラル。${...} の中は式なので残す
            j = i + 1
            buf = [' ']
            while j < n and src[j] != '`':
                if src[j] == '\\':
                    buf.append('  ')
                    j += 2
                    continue
                if src[j:j + 2] == '${':
                    depth = 1
                    k = j + 2
                    while k < n and depth:
                        if src[k] == '{':
                            depth += 1
                        elif src[k] == '}':
                            depth -= 1
                        k += 1
                    # 展開部の中身は式なので、そこも同じ規則で潰してから残す
                    inner = strip_noise(src[j + 2:k - 1])
                    buf.append('  ' + inner + ' ')
                    j = k
                    continue
                buf.append('\n' if src[j] == '\n' else ' ')
                j += 1
            buf.append(' ')
            out.append(''.join(buf))
            i = min(j + 1, n)
        else:
            out.append(c)
            i += 1
    return ''.join(out)


def collect_declared(src):
    declared = set()
    # let / const / var (分割代入を含む)
    for m in re.finditer(r'\b(?:let|const|var)\s+([\w$]+)', src):
        declared.add(m.group(1))
    for m in re.finditer(r'\b(?:let|const|var)\s*\{([^}]*)\}', src):
        for part in m.group(1).split(','):
            name = part.split(':')[-1].split('=')[0].strip()
            if re.fullmatch(r'[\w$]+', name):
                declared.add(name)
    for m in re.finditer(r'\b(?:let|const|var)\s*\[([^\]]*)\]', src):
        for part in m.group(1).split(','):
            name = part.split('=')[0].strip()
            if re.fullmatch(r'[\w$]+', name):
                declared.add(name)
    # function 宣言と仮引数
    for m in re.finditer(r'\bfunction\s+([\w$]+)\s*\(([^)]*)\)', src):
        declared.add(m.group(1))
        for part in m.group(2).split(','):
            name = part.split('=')[0].strip().lstrip('.')
            if re.fullmatch(r'[\w$]+', name):
                declared.add(name)
    # 無名関数・アロー関数の仮引数
    for m in re.finditer(r'\bfunction\s*\(([^)]*)\)', src):
        for part in m.group(1).split(','):
            name = part.split('=')[0].strip()
            if re.fullmatch(r'[\w$]+', name):
                declared.add(name)
    for m in re.finditer(r'\(([^()]*)\)\s*=>', src):
        for part in m.group(1).split(','):
            name = part.split('=')[0].strip()
            if re.fullmatch(r'[\w$]+', name):
                declared.add(name)
    for m in re.finditer(r'(?:^|[^\w$.])([\w$]+)\s*=>', src, re.M):
        declared.add(m.group(1))
    # catch (e)
    for m in re.finditer(r'\bcatch\s*\(\s*([\w$]+)', src):
        declared.add(m.group(1))
    # for (const x of ...) は上の let/const/var で拾える
    return declared


def collect_used(src):
    """識別子の参照箇所を (name, lineno) で返す。プロパティ・キーは除く。"""
    used = []
    for m in re.finditer(r'([.\w$]?)\s*\b([A-Za-z_$][\w$]*)\b', src):
        prev = m.group(1)
        name = m.group(2)
        if prev == '.':
            continue  # プロパティアクセス
        # オブジェクトリテラルのキー (name:) は除く。ただし三項演算子との誤判定を避けるため
        # 直後が : で、さらにその前が { か , の場合のみ
        tail = src[m.end():m.end() + 2]
        if re.match(r'\s*:', tail):
            head = src[:m.start(2)].rstrip()
            if head.endswith('{') or head.endswith(','):
                continue
        if name in KEYWORDS:
            continue
        lineno = src.count('\n', 0, m.start(2)) + 1
        used.append((name, lineno))
    return used


def main(paths):
    bad = 0
    for path in paths:
        src = open(path, encoding='utf-8').read()
        clean = strip_noise(src)
        declared = collect_declared(clean) | BUILTINS
        seen = {}
        for name, lineno in collect_used(clean):
            if name not in declared:
                seen.setdefault(name, []).append(lineno)
        if seen:
            print(f'--- {path}')
            for name, lines in sorted(seen.items()):
                head = ', '.join(str(x) for x in lines[:6])
                more = '...' if len(lines) > 6 else ''
                print(f'  未宣言の可能性: {name}  (行 {head}{more})')
                bad += 1
        else:
            print(f'--- {path}: 未宣言の識別子は検出されませんでした')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
