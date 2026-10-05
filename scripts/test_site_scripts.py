"""Website asset scripts and repo ignore rules. Run from the repo root:

    python3 -m unittest discover -s scripts -p 'test_site_scripts.py'
"""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import build_site_gallery  # noqa: E402
import extract_site_hero  # noqa: E402


# last commit with the 2.2 slides (parent of #55). shallow CI clones won't have it
HISTORY_REV = '0d88f3092a9a944a38e2df3d4b02215bb0c8119d'


def rev_available(rev):
    return subprocess.run(['git', 'cat-file', '-e', rev], cwd=ROOT, capture_output=True).returncode == 0


class GalleryTests(unittest.TestCase):
    def test_missing_sources_leave_the_gallery_alone(self):
        # a checkout where the slides can't be found, ie no git history and no pngs.
        # the old script wiped the gallery here and then crashed
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / 'scripts').mkdir()
            shutil.copy(ROOT / 'scripts/build_site_gallery.py', tmp / 'scripts')
            gallery = tmp / 'site/public/assets/store'
            gallery.mkdir(parents=True)
            for name in ('01-hero.jpg', '01-hero.webp', 'android-08-export.webp'):
                (gallery / name).write_bytes(b'live')
            env = dict(os.environ, GIT_CEILING_DIRECTORIES=str(tmp.parent))
            run = subprocess.run([sys.executable, 'scripts/build_site_gallery.py'], cwd=tmp, env=env,
                                 capture_output=True, text=True)
            self.assertNotEqual(run.returncode, 0)
            self.assertIn('docs/store/ios/iphone-6.9/01-hero.png', run.stderr)
            self.assertEqual(sorted(p.name for p in gallery.iterdir()),
                             ['01-hero.jpg', '01-hero.webp', 'android-08-export.webp'])
            self.assertEqual((gallery / '01-hero.jpg').read_bytes(), b'live')

    @unittest.skipUnless(rev_available(HISTORY_REV), 'needs full git history')
    def test_rebuilds_the_linked_gallery_from_history(self):
        cwd = os.getcwd()
        os.chdir(ROOT)
        try:
            with tempfile.TemporaryDirectory() as out:
                Path(out, 'stale-old-set.webp').write_bytes(b'x')
                self.assertEqual(build_site_gallery.main([], out_dir=out), 0)
                written = sorted(os.listdir(out))
        finally:
            os.chdir(cwd)
        # same stems the site links and site/test/security.test.mjs checks
        stems = [n if p == 'ios' else f'android-{n}' for n, p in build_site_gallery.SLIDES]
        self.assertEqual(written, sorted(f'{s}.{ext}' for s in stems for ext in ('jpg', 'webp')))
        for stem in stems:
            self.assertTrue((ROOT / 'site/public/assets/store' / f'{stem}.webp').exists(), stem)


class HeroTests(unittest.TestCase):
    @unittest.skipUnless(rev_available(HISTORY_REV), 'needs full git history')
    def test_hero_source_still_resolves(self):
        cwd = os.getcwd()
        os.chdir(ROOT)
        try:
            image = extract_site_hero.load_source()
        finally:
            os.chdir(cwd)
        self.assertEqual(image.size, (extract_site_hero.W, extract_site_hero.H))


class IgnoreRuleTests(unittest.TestCase):
    def ignored(self, path):
        return subprocess.run(['git', 'check-ignore', '-q', '--no-index', path], cwd=ROOT).returncode == 0

    def test_store_keys_and_bytecode_are_ignored(self):
        for path in ('AuthKey_ABC123.p8', 'scripts/AuthKey_ABC123.p8', 'scripts/tacmap-sa.json',
                     'play-service-account.json', 'scripts/store_capture/__pycache__/x.cpython-314.pyc',
                     'testdata/tools/__pycache__/gen.cpython-312.pyc', 'scripts/stray.pyc'):
            self.assertTrue(self.ignored(path), path)

    def test_no_bytecode_is_tracked(self):
        tracked = subprocess.run(['git', 'ls-files', '*.pyc', '**/__pycache__/**'], cwd=ROOT,
                                 capture_output=True, text=True, check=True).stdout.split()
        self.assertEqual(tracked, [])


if __name__ == '__main__':
    unittest.main()
