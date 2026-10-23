"""What the LG (webos/build.py) and Samsung (tizen/build.py) packages share: the settings and the web app's files.

The settings come from config.properties, the same file as the Android build. --generic leaves it out (the GitHub
release is built that way): no TMDB key, and the TV's time zone picks the city, the currency and the region.
"""
import json
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'tools'))
import translate  # noqa: E402  (the catalogues and the keys the web app uses)

GENERIC = {'tmdbKey': '', 'tmdbRegion': '', 'latitude': '', 'longitude': '', 'city': '', 'language': 'auto',
           'currency': '', 'temperature': 'auto', 'showCrypto': True, 'hideAnimation': False, 'hideApps': [],
           'wallpapers': False, 'wallpaperTopics': [], 'wallpaperMinutes': 3, 'localApps': False}


def config_from_args(args):
    """The settings for these command-line arguments (--generic, --config PATH or config.properties)."""
    if '--generic' in args:
        return dict(GENERIC)
    path = os.path.join(ROOT, 'config.properties')
    if '--config' in args:
        path = os.path.abspath(args[args.index('--config') + 1])
    return read_config(path)


def read_config(path):
    if not os.path.exists(path):
        shutil.copy(os.path.join(ROOT, 'config.example.properties'), path)
        print('Created config.properties: add your TMDB key and your city there (optional), then build again.')
    values = {}
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#') or '=' not in line:
                continue
            key, value = line.split('=', 1)
            values[key.strip()] = value.strip().strip('"')

    def number(key, default):
        v = values.get(key, '') or default
        if not re.match(r'^-?\d+(\.\d+)?$', v):
            sys.exit('config.properties: %s must be a number (got "%s")' % (key, v))
        return v

    return {
        'tmdbKey': values.get('tmdb_key', ''),
        'tmdbRegion': values.get('tmdb_region', 'US') or 'US',
        'latitude': number('latitude', '40.7128'),
        'longitude': number('longitude', '-74.0060'),
        'city': values.get('city', ''),
        'language': values.get('language', 'auto') or 'auto',
        'currency': values.get('currency', 'USD') or 'USD',
        'temperature': values.get('temperature', 'auto') or 'auto',
        'showCrypto': values.get('show_crypto', 'true').lower() != 'false',
        'hideAnimation': values.get('hide_animation', 'false').lower() == 'true',
        'hideApps': [s.strip() for s in values.get('hide_apps', '').split(',') if s.strip()],
        'wallpapers': values.get('wallpapers', 'false').lower() == 'true',
        'wallpaperTopics': [s.strip() for s in values.get('wallpaper_topics', '').split(',') if s.strip()]
                           or list(WALLPAPER_TOPICS),
        'wallpaperMinutes': int(float(number('wallpaper_minutes', '3'))) or 3,
        'localApps': True,   # webos-apps.local.json is this TV's: never in a --generic build
    }


# wallpapers=true: featured pictures from Wikimedia Commons (each CC BY, CC BY-SA, CC0 or public domain, credited on
# screen), by topic. They're picked when the app is built; the TV loads each photo at 1920 px from Wikimedia.
WALLPAPER_TOPICS = {
    'landscapes': 'Featured pictures of landscapes',
    'mountains': 'Featured pictures of mountains',
    'coasts': 'Featured pictures of coasts',
    'forests': 'Featured pictures of forests',
    'astronomy': 'Featured pictures of astronomy',
}
COMMONS = 'https://commons.wikimedia.org/w/api.php'
FREE_LICENSE = re.compile(r'^(CC BY(-SA)? [\d.]+|CC0|Public domain|PD)', re.I)


def commons(params):
    import urllib.parse
    import urllib.request
    query = dict(params, format='json', formatversion='2')
    req = urllib.request.Request(COMMONS + '?' + urllib.parse.urlencode(query),
                                 headers={'User-Agent': 'FreeMyTV-build/1.0 (https://freemytv.io)'})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r)


def plain(html_text):
    import html
    return re.sub(r'\s+', ' ', html.unescape(re.sub(r'<[^>]+>', '', html_text or ''))).strip()


def wallpapers(config, per_topic=40):
    """[{url, title, credit}] for config['wallpaperTopics']: landscape-shaped (near 16:9), at least 1920 px wide."""
    import random
    out = []
    for topic in config['wallpaperTopics']:
        category = WALLPAPER_TOPICS.get(topic.lower())
        if not category:
            print('wallpaper_topics: unknown topic "%s" (known: %s)' % (topic, ', '.join(WALLPAPER_TOPICS)))
            continue
        titles = [m['title'] for m in commons({'action': 'query', 'list': 'categorymembers', 'cmtype': 'file',
                                               'cmtitle': 'Category:' + category, 'cmlimit': '500'})['query']['categorymembers']]
        random.shuffle(titles)
        found = 0
        for start in range(0, len(titles), 50):
            if found >= per_topic:
                break
            pages = commons({'action': 'query', 'titles': '|'.join(titles[start:start + 50]), 'prop': 'imageinfo',
                             'iiprop': 'url|size|extmetadata', 'iiurlwidth': '1920',
                             'iiextmetadatafilter': 'Artist|LicenseShortName|ObjectName'})['query']['pages']
            for page in pages:
                info = (page.get('imageinfo') or [{}])[0]
                meta = info.get('extmetadata', {})
                w, h = info.get('width', 0), info.get('height', 0)
                license_name = plain(meta.get('LicenseShortName', {}).get('value'))
                if not (w >= 1920 and h and 1.45 <= w / h <= 1.95 and FREE_LICENSE.match(license_name)):
                    continue
                name = plain(meta.get('ObjectName', {}).get('value')) or re.sub(r'^File:|\.\w+$', '', page['title'])
                artist = plain(meta.get('Artist', {}).get('value'))
                credit = ', '.join(x for x in (artist, license_name, 'Wikimedia Commons') if x)
                out.append({'url': info['thumburl'], 'title': name, 'credit': credit})
                found += 1
                if found >= per_topic:
                    break
    return out


def installed_apps():
    """webos-apps.local.json (tools/lg_apps.mjs), for TVs that won't list their apps to Free My TV: [] without it."""
    path = os.path.join(ROOT, 'webos-apps.local.json')
    if not os.path.exists(path):
        return []
    with open(path, encoding='utf-8') as f:
        return json.load(f)


def coins():
    """The coins' logos from the Android app's vector drawables, as SVG paths (BTC, ETH, SOL, USDT)."""
    out = []
    for name in ('btc', 'eth', 'sol', 'usdt'):
        with open(os.path.join(ROOT, 'app', 'res', 'drawable', 'coin_%s.xml' % name), encoding='utf-8') as f:
            out.append(re.findall(r'android:pathData="([^"]+)"', f.read()))
    return out


def catalogues():
    """Each language's catalogue (app/assets/i18n), with only the keys the web app uses: {lang: {key: text}}."""
    keys = translate.web_keys()
    return {lang: {k: v for k, v in sorted(translate.load(lang).items()) if k in keys} for lang in translate.languages()}


def files(config, made_by):
    """The web app's files, {path inside the app: bytes}: web/, the settings, the catalogues, the app's symbols and font."""
    out = {}
    web = os.path.join(ROOT, 'web')
    for name in sorted(os.listdir(web)):
        if os.path.isdir(os.path.join(web, name)):
            continue
        with open(os.path.join(web, name), 'rb') as f:
            out[name] = f.read()
    icons = os.path.join(web, 'icons')
    for name in sorted(os.listdir(icons)):
        with open(os.path.join(icons, name), 'rb') as f:
            out['icons/' + name] = f.read()
    config = dict(config)
    if config.get('wallpapers'):
        try:
            config['wallpapers'] = wallpapers(config)
            print('Wallpapers: %d photos from Wikimedia Commons (%s)' % (len(config['wallpapers']),
                                                                       ', '.join(config['wallpaperTopics'])))
        except Exception as e:
            print('Wallpapers: couldn\'t reach Wikimedia Commons (%s); building without them.' % e)
            config['wallpapers'] = []
    else:
        config['wallpapers'] = []
    config['installedApps'] = installed_apps() if config.pop('localApps', False) else []
    app_icons = os.path.join(ROOT, 'webos-icons.local')
    if config['installedApps'] and os.path.isdir(app_icons):
        for name in sorted(os.listdir(app_icons)):
            with open(os.path.join(app_icons, name), 'rb') as f:
                out['appicons/' + name] = f.read()
    out['config.js'] = ('/* Generated by %s. */\nwindow.CONFIG = %s;\n'
                        % (made_by, json.dumps(config, ensure_ascii=False))).encode('utf-8')
    out['coins.js'] = ('window.COINS = %s;\n' % json.dumps(coins())).encode('utf-8')
    out['i18n.js'] = ('/* Generated by %s from app/assets/i18n. */\nwindow.I18N_CATALOGUES = %s;\n'
                      % (made_by, json.dumps(catalogues(), ensure_ascii=False, separators=(',', ':')))).encode('utf-8')
    symbols = os.path.join(ROOT, 'app', 'assets', 'symbols')
    for name in sorted(os.listdir(symbols)):
        if name.endswith('.png') or name.startswith('LICENSE'):
            with open(os.path.join(symbols, name), 'rb') as f:
                out['symbols/' + name] = f.read()
    for name in ('InterVariable.ttf', 'OFL.txt'):
        with open(os.path.join(ROOT, 'app', 'assets', 'fonts', name), 'rb') as f:
            out['fonts/' + name] = f.read()
    return out


def preview(app, out_dir):
    """Leaves the app's files in out_dir, to open index.html in a browser."""
    if os.path.isdir(out_dir):
        shutil.rmtree(out_dir)
    for path, content in app.items():
        target = os.path.join(out_dir, path)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, 'wb') as f:
            f.write(content)
