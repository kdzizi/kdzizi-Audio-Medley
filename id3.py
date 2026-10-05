"""从 MP3 的 ID3v2 标签提取 标题/歌手，写到 <同名>.json 供 server.js 读取。"""
import json, struct, sys, os

def parse_id3(path):
    with open(path, 'rb') as f:
        head = f.read(10)
        if head[:3] != b'ID3':
            return {}
        ver = head[3]
        size = ((head[6] & 0x7f) << 21) | ((head[7] & 0x7f) << 14) | ((head[8] & 0x7f) << 7) | (head[9] & 0x7f)
        body = f.read(size)

    def text_frame(fid):
        i = 0
        while i + 10 <= len(body):
            fr = body[i:i+4]
            fsz = body[i+4:i+8]
            if ver == 4:
                fsz = ((fsz[0] & 0x7f) << 21) | ((fsz[1] & 0x7f) << 14) | ((fsz[2] & 0x7f) << 7) | (fsz[3] & 0x7f)
            else:
                fsz = struct.unpack('>I', fsz)[0]
            if fr == fid:
                data = body[i+10:i+10+fsz]
                enc, txt = data[0], data[1:]
                try:
                    if enc == 0: return txt.decode('latin-1').strip('\x00 ')
                    if enc == 1: return txt.decode('utf-16').strip('\x00 ')
                    if enc == 2: return txt.decode('utf-16-be').strip('\x00 ')
                    return txt.decode('utf-8').strip('\x00 ')
                except Exception:
                    return ''
            i += 10 + fsz
        return ''

    return {'title': text_frame(b'TIT2'), 'artist': text_frame(b'TPE1'), 'album': text_frame(b'TALB')}

src = sys.argv[1]
info = parse_id3(src)
out = os.path.splitext(src)[0] + '.json'
with open(out, 'w', encoding='utf8') as f:
    json.dump(info, f, ensure_ascii=False)
print(out, '->', info)
