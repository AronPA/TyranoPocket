#!/usr/bin/env python3
"""Independent spec-based ASAR fixture writer. NOT official @electron/asar output.
The official-tool cross-check is a separate, currently blocked verification gate.
"""
from pathlib import Path
import argparse, hashlib, json, shutil, struct, wave, math, zipfile, zlib

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'fixtures'

def asar(destination, files, unpack=()):
    tree = {'files': {}}
    payload = []
    offset = 0
    for path, data in sorted(files.items()):
        node = tree['files']
        parts = path.split('/')
        for part in parts[:-1]:
            node = node.setdefault(part, {'files': {}})['files']
        entry = {'size': len(data), 'integrity': {'algorithm': 'SHA256', 'hash': hashlib.sha256(data).hexdigest(), 'blockSize': 4194304, 'blocks': [hashlib.sha256(data[i:i+4194304]).hexdigest() for i in range(0,len(data),4194304)]}}
        if path in unpack:
            entry['unpacked'] = True
            target = Path(str(destination)+'.unpacked')/path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        else:
            entry['offset'] = str(offset)
            offset += len(data)
            payload.append(data)
        node[parts[-1]] = entry
    header = json.dumps(tree, ensure_ascii=False, separators=(',', ':')).encode()
    padding = b'\0' * (-len(header) % 4)
    header_pickle = struct.pack('<II', 4+len(header)+len(padding), len(header)) + header + padding
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open('wb') as out:
        out.write(struct.pack('<II', 4, len(header_pickle)))
        out.write(header_pickle)
        for data in payload:
            out.write(data)

def create():
    if OUT.exists(): shutil.rmtree(OUT)
    OUT.mkdir()
    html = '''<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>작은 별의 이야기 · 구조 테스트</title><link rel="stylesheet" href="data/others/plugin/demo/style.css"></head><body><h1>작은 별의 이야기</h1><p>가져오기 검증용 합성 게임입니다. 실제 TyranoScript 엔진이 아닙니다.</p><img id="picture" src="data/bgimage/character01.png" width="180"><p id="line">준비 중</p><button id="next">다음 대사</button><button id="save">저장</button><button id="sound">음악 재생</button><p id="status"></p><audio id="bgm" src="data/bgm/test.wav" loop></audio><script src="tyrano/tyrano.js"></script><script src="data/others/plugin/demo/plugin.js"></script></body></html>'''
    script = '''// SYNTHETIC TEST DRIVER, NOT THE TYRANOSCRIPT ENGINE.
window.fixture={ready:false};let i=Number(localStorage.getItem('line')||0),lines=[];
fetch('data/scenario/first.ks').then(r=>{if(!r.ok)throw Error(r.status);return r.text()}).then(s=>{lines=s.trim().split('\\n');window.fixture.ready=true;show()});
function show(){document.getElementById('line').textContent=lines[i%lines.length]}
document.getElementById('next').onclick=()=>{i++;show()};
document.getElementById('save').onclick=()=>{localStorage.setItem('line',String(i));document.getElementById('status').textContent='저장했습니다'};
document.getElementById('sound').onclick=()=>document.getElementById('bgm').play().then(()=>document.getElementById('status').textContent='음악 재생 중');
window.addEventListener('error',e=>console.error(e.message));
'''
    files = {
        'package.json': json.dumps({'name':'fixture','productName':'작은 별의 이야기','main':'main.js'},ensure_ascii=False).encode(),
        'main.js': b'const {BrowserWindow}=require("electron"); new BrowserWindow().loadFile("index.html");',
        'index.html': html.encode(),
        'tyrano/tyrano.js': script.encode(),
        'data/scenario/first.ks': '안녕하세요. 작은 별을 따라가 볼까요?\n별빛이 조용히 길을 비춥니다.\n다음에 다시 만나도 이야기는 남아 있어요.\n'.encode(),
        'data/image/shared.txt': b'inside',
        'data/image/日本語 (한글)+%.txt': b'\xef\xbb\xbfUTF-8 BOM preserved',
        'data/image/legacy.txt': 'こんにちは'.encode('shift_jis')
    }
    def chunk(kind, data):
        return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
    pixels=b''.join(b'\0'+b''.join(bytes([88+x,80+y,170]) for x in range(64)) for y in range(64))
    png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',64,64,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(pixels))+chunk(b'IEND',b'')
    for kind in ['A','B','C','E']:
        game=OUT/kind/'Game'
        for folder in ['bgimage','bgm','fgimage','image','others/plugin/demo']:(game/'data'/folder).mkdir(parents=True)
        (game/'data/bgimage/Character01.png').write_bytes(png)
        (game/'data/image/shared.txt').write_bytes(b'outside')
        (game/'data/others/plugin/demo/style.css').write_text('body{background:#111827;color:#f1f5f9;font:20px sans-serif;padding:24px}button{font-size:22px;margin:8px;padding:16px}img{image-rendering:pixelated}',encoding='utf8')
        (game/'data/others/plugin/demo/plugin.js').write_text("fetch('data/others/plugin/demo/data.json').then(r=>r.json()).then(j=>window.fixture.plugin=j.ok);",encoding='utf8')
        (game/'data/others/plugin/demo/data.json').write_text('{"ok":true}')
        with wave.open(str(game/'data/bgm/test.wav'),'wb') as wav:
            wav.setnchannels(1);wav.setsampwidth(2);wav.setframerate(16000)
            wav.writeframes(b''.join(struct.pack('<h',int(3000*math.sin(2*math.pi*440*t/16000))) for t in range(16000)))
        (game/'Game.exe').write_bytes(b'Fixture only; not an executable')
        archive=game/'resources/app.asar'
        asar(archive,files,unpack=('data/image/日本語 (한글)+%.txt',) if kind=='B' else ())
        if kind=='C':
            shutil.copyfile(archive,Path(str(archive)+'.bak'));archive.write_bytes(b'broken archive')
        if kind=='E':
            (game/'data/image/Pair.png').write_bytes(png);(game/'data/image/pair.png').write_bytes(png)
    for name,folder,prefix in [('D',OUT/'A/Game','MyGame'),('A',OUT/'A/Game','Game'),('B',OUT/'B/Game','Game'),('C',OUT/'C/Game','Game'),('E',OUT/'E/Game','Game')]:
        with zipfile.ZipFile(OUT/f'{name}.zip','w',zipfile.ZIP_DEFLATED) as z:
            for f in sorted(folder.rglob('*')):
                if f.is_file():z.write(f,str(Path(prefix)/f.relative_to(folder)))
    for label,entries in [('traversal',{'../escape.txt':b'x'}),('absolute',{'/absolute.txt':b'x'}),('windows',{'C:\\temp\\x':b'x'}),('duplicate', [('x',b'a'),('x',b'b')])]:
        with zipfile.ZipFile(OUT/f'bad-{label}.zip','w') as z:
            for n,b in entries.items() if isinstance(entries,dict) else entries:z.writestr(n,b)
    # Force a ZIP64 end record with Python's standard writer, without large payloads.
    old_limit=zipfile.ZIP64_LIMIT
    try:
        zipfile.ZIP64_LIMIT=10
        with zipfile.ZipFile(OUT/'zip64.zip','w',allowZip64=True) as z:z.writestr('long-file.txt',b'01234567890123456789')
    finally:zipfile.ZIP64_LIMIT=old_limit
    with zipfile.ZipFile(OUT/'legacy-sjis.zip','w') as z:z.writestr('abcdefghij',b'legacy filename')
    p=OUT/'legacy-sjis.zip';p.write_bytes(p.read_bytes().replace(b'abcdefghij','日本語.txt'.encode('cp932')))
    with zipfile.ZipFile(OUT/'bad-crc.zip','w') as z:z.writestr('file.txt',b'unique-payload')
    p=OUT/'bad-crc.zip';p.write_bytes(p.read_bytes().replace(b'unique-payload',b'broken-payload'))
    with zipfile.ZipFile(OUT/'bad-link.zip','w') as z:
        link=zipfile.ZipInfo('escape');link.create_system=3;link.external_attr=0o120777<<16;z.writestr(link,b'../../outside')
    (OUT/'README.txt').write_text('Fixtures A–E are generated from documented Electron ASAR Pickle format by our independent writer. They are NOT generated by @electron/asar and contain a synthetic VN test driver, NOT the TyranoScript engine. Official ASAR interoperability and real TyranoScript Android tests remain required.\n')

if __name__=='__main__': create()
