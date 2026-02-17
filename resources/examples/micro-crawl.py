import re

WORDLIST = [
    'robots.txt', 'sitemap.xml', 'security.txt', 'static', 'public',
    'images/', 'img', 'css', 'css/', 'js/', 'fonts', 'media',
    'humans.txt', 'ads.txt', 'favicon.ico', 'manifest.json',
    'site.webmanifest', 'apple-touch-icon.png', 'browserconfig.xml',
    'crossdomain.xml', 'clientaccesspolicy.xml',
    '.well-known', '.well-known/security.txt', '.well-known/change-password',
    '.well-known/assetlinks.json', '.well-known/apple-app-site-association',
    'index.html', 'index.php', 'home', 'about', 'contact', 'pricing',
    'products', 'product', 'services', 'service', 'features',
    'blog', 'news', 'articles', 'press', 'careers', 'jobs',
    'support', 'help', 'docs', 'documentation', 'faq',
    'terms', 'privacy', 'search', 'login', 'logout',
    'signup', 'register', 'account', 'profile', 'settings',
    'dashboard', 'portal', 'app', 'assets',
]

DETECT_REFLECTION = True

SKIP_EXT = ('.png', '.jpg', '.jpeg', '.gif', '.css', '.js', '.woff', '.woff2',
            '.svg', '.ico', '.ttf', '.eot', '.mp4', '.mp3', '.webp', '.avif')
PATH_RE = re.compile(r'(?<!/)/[a-zA-Z0-9._\-][a-zA-Z0-9._\-/]*(?:\?[^\s"\'<>]*)?')
MAX = 500
CANARY = randstr()

seen = set()
count = 0


def queue_path(engine, template, path, skip_static=True):
    global count
    if count >= MAX:
        return
    dedup = path.split('?')[0]
    if dedup in seen:
        return
    if skip_static and dedup.lower().endswith(SKIP_EXT):
        return
    seen.add(dedup)
    count += 1
    if DETECT_REFLECTION:
        sep = '&' if '?' in path else '?'
        path = path + sep + 'z=' + CANARY
    engine.queue(template, path, label=dedup)


def queueRequests(target, wordlists):
    engine = RequestEngine(endpoint=target.endpoint,
                           concurrentConnections=1,
                           requestsPerConnection=100,
                           pipeline=False,
                           engine=Engine.BURP,
                           maxQueueSize=MAX
                           )

    queue_path(engine, target.req, '/', skip_static=False)
    for word in WORDLIST:
        path = word if word.startswith('/') else '/' + word
        queue_path(engine, target.req, path, skip_static=False)


def handleResponse(req, interesting):
    if req.status == 0:
        return

    for path in PATH_RE.findall(req.response or ''):
        queue_path(req.engine, req.template, path)

    if req.status != 404:
        if DETECT_REFLECTION and CANARY in (req.response or ''):
            req.label = req.label + ' reflection'
        table.add(req)
