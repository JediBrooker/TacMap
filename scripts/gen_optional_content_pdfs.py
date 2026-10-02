#!/usr/bin/env python3
"""Tiny one-page PDFs for optional content (OCMD) visibility.

Each page has a red square at its centre wrapped in /OC /MC0. MC0 points at an
OCG or an OCMD set up a particular way. Object 5 is an OCG in /D /ON, object 6
is in /D /OFF, object 8 is an OCG in neither list. The manifest says whether the
square should be visible per ISO 32000 8.11. Poppler (pdftoppm) renders all of
them exactly as the manifest says; MuPDF 1.28 shows the AllOn and /VE cases, a
MuPDF quirk, so don't use it as the oracle here.

CoreGraphics ignores OCMDs, which is what PDFOptionalContent.swift works around.
Every case is written twice: a classic xref table, and (via pymupdf) a
compressed object stream + xref stream layout like real USGS files.

  python3 scripts/gen_optional_content_pdfs.py   (needs pymupdf for the objstm set)
"""
import json, os, sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'testdata', 'optional_content')

# name -> (MC0 target object text, /D extras, OCProperties inline?, visible)
CASES = {
    'ocg_on':             ('5 0 R', '', True, True),
    'ocg_off':            ('6 0 R', '', True, False),
    'ocmd_single_off':    ('<< /Type /OCMD /OCGs 6 0 R >>', '', True, False),
    'ocmd_single_on':     ('<< /Type /OCMD /OCGs 5 0 R >>', '', True, True),
    'ocmd_allon_on_off':  ('<< /Type /OCMD /OCGs [5 0 R 6 0 R] /P /AllOn >>', '', True, False),
    'ocmd_anyon_on_off':  ('<< /Type /OCMD /OCGs [5 0 R 6 0 R] /P /AnyOn >>', '', True, True),
    'ocmd_default_p':     ('<< /Type /OCMD /OCGs [6 0 R 5 0 R] >>', '', True, True),
    'ocmd_anyoff_on':     ('<< /Type /OCMD /OCGs [5 0 R] /P /AnyOff >>', '', True, False),
    'ocmd_alloff_off':    ('<< /Type /OCMD /OCGs [6 0 R] /P /AllOff >>', '', True, True),
    'ocmd_ve_and':        ('<< /Type /OCMD /OCGs [5 0 R] /VE [/And 5 0 R 6 0 R] >>', '', True, False),
    'ocmd_ve_or_not':     ('<< /Type /OCMD /VE [/Or [/Not 5 0 R] 6 0 R] >>', '', True, False),
    'ocmd_ve_not_off':    ('<< /Type /OCMD /VE [/Not 6 0 R] >>', '', True, True),
    'base_off_unlisted':  ('<< /Type /OCMD /OCGs 8 0 R >>', '/BaseState /OFF', True, False),
    'base_on_unlisted':   ('<< /Type /OCMD /OCGs 8 0 R >>', '', True, True),
    'ocprops_indirect':   ('<< /Type /OCMD /OCGs [5 0 R 6 0 R] /P /AllOn >>', '', False, False),
}


def build(mc, d_extra, inline_ocp):
    content = b'q /OC /MC0 BDC 1 0 0 rg 20 20 60 60 re f EMC Q'
    ocp = '<< /OCGs [5 0 R 6 0 R 8 0 R] /D << /ON [5 0 R] /OFF [6 0 R] %s >> >>' % d_extra
    target = mc if mc.endswith(' R') else '7 0 R'
    objs = {
        1: '<< /Type /Catalog /Pages 2 0 R /OCProperties %s >>' % (ocp if inline_ocp else '9 0 R'),
        2: '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        3: '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents 4 0 R /Resources << /Properties << /MC0 %s >> >> >>' % target,
        5: '<< /Type /OCG /Name (On) >>',
        6: '<< /Type /OCG /Name (Off) >>',
        7: mc if not mc.endswith(' R') else '<< /Type /OCG /Name (Unused) >>',
        8: '<< /Type /OCG /Name (Unlisted) >>',
        9: ocp,
    }
    out = bytearray(b'%PDF-1.7\n')
    offs = {}
    for n in range(1, 10):
        offs[n] = len(out)
        if n == 4:
            out += b'4 0 obj\n<< /Length %d >>\nstream\n' % len(content) + content + b'\nendstream\nendobj\n'
        else:
            out += ('%d 0 obj\n%s\nendobj\n' % (n, objs[n])).encode()
    x = len(out)
    out += b'xref\n0 10\n0000000000 65535 f \n' + b''.join(b'%010d 00000 n \n' % offs[n] for n in range(1, 10))
    out += b'trailer\n<< /Size 10 /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n' % x
    return bytes(out)


def main():
    os.makedirs(OUT, exist_ok=True)
    try:
        import pymupdf
    except ImportError:
        pymupdf = None
        print('pymupdf missing: skipping the object stream variants', file=sys.stderr)
    manifest = []
    for name, (mc, extra, inline, vis) in CASES.items():
        raw = build(mc, extra, inline)
        with open(os.path.join(OUT, f'{name}.pdf'), 'wb') as f:
            f.write(raw)
        manifest.append({'file': f'{name}.pdf', 'visible': vis, 'layout': 'xref-table'})
        if pymupdf:
            doc = pymupdf.open('pdf', raw)
            doc.save(os.path.join(OUT, f'{name}_objstm.pdf'), use_objstms=1, compression_effort=0, deflate=True)
            manifest.append({'file': f'{name}_objstm.pdf', 'visible': vis, 'layout': 'object-stream'})
    with open(os.path.join(OUT, 'manifest.json'), 'w') as f:
        json.dump({'description': 'Red square at page centre (50,50 of a 100x100 page) wrapped in /OC /MC0; visible per ISO 32000 8.11 default config', 'cases': manifest}, f, indent=1)
        f.write('\n')
    print(len(manifest), 'files')


if __name__ == '__main__':
    main()
