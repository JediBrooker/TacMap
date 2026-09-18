#!/usr/bin/env python3
"""Create a TacMap JSON symbol pack from a directory of PNGs or a PNG release ZIP.
Requires Pillow. SVG/code/remote references are never carried into the pack.
"""
import argparse, base64, hashlib, io, json, zipfile
from pathlib import Path
from PIL import Image


def build(source, name, attribution, prefix=''):
    if not name.strip() or len(name) > 100 or len(attribution) > 2000:
        raise ValueError('Invalid pack name or attribution length')
    archive = zipfile.ZipFile(source) if source.is_file() else None
    if archive:
        if len(archive.infolist()) > 20000:
            raise ValueError('Too many archive entries')
        items = [(n.filename, lambda n=n: archive.read(n)) for n in archive.infolist()
                 if n.filename.startswith(prefix) and n.filename.lower().endswith('.png') and n.file_size <= 4 * 1024 * 1024]
    else:
        items = [(str(p.relative_to(source)), p.read_bytes) for p in source.rglob('*.png') if p.stat().st_size <= 4 * 1024 * 1024]
    symbols = []; seen = set()
    try:
        for filename, read in sorted(items, key=lambda x: x[0]):
            with Image.open(io.BytesIO(read())) as image:
                if image.format != 'PNG' or image.width > 4096 or image.height > 4096:
                    raise ValueError('Invalid PNG dimensions: ' + filename)
                image = image.convert('RGBA'); image.thumbnail((256, 256), Image.Resampling.LANCZOS)
                clean = Image.new('RGBA', image.size); clean.paste(image)
                output = io.BytesIO(); clean.save(output, format='PNG', optimize=True)
            data = output.getvalue(); digest = hashlib.sha256(data).hexdigest()
            if len(data) > 32768:
                raise ValueError('PNG exceeds 32 KiB: ' + filename)
            if digest in seen: continue
            seen.add(digest)
            label = str(Path(filename.removeprefix(prefix)).with_suffix('')).replace('_', ' ').replace('/', ' / ')
            if len(label) > 160: raise ValueError('Symbol name exceeds 160 characters: ' + filename)
            symbols.append({'id':digest,'name':label,'png':base64.b64encode(data).decode('ascii')})
        if not 1 <= len(symbols) <= 1000: raise ValueError('A pack must contain 1–1000 distinct symbols')
        result = {'format':1,'name':name,'attribution':attribution,'symbols':symbols}
        if len(json.dumps(result, ensure_ascii=False).encode()) > 16*1024*1024: raise ValueError('Pack exceeds 16 MiB')
        return result
    finally:
        if archive: archive.close()

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source',type=Path);parser.add_argument('output',type=Path)
    parser.add_argument('--name',required=True);parser.add_argument('--attribution',default='');parser.add_argument('--prefix',default='')
    args=parser.parse_args();pack=build(args.source,args.name,args.attribution,args.prefix)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(pack,ensure_ascii=False,separators=(',',':'))+'\n')
    print(f'Created {len(pack["symbols"])} symbols: {args.output}')
