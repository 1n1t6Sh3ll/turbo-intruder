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
    'api', 'api/',
]

DETECT_REFLECTION = True

PATH_RE = re.compile(r'(?<!/)/[a-zA-Z0-9._~\-][a-zA-Z0-9._~\-/%+:]*(?:\?[^\s"\'<>]*)?')
MAX = 500
CANARY = randstr()

LINK_CONTENT_TYPES = ['text/html', 'text/plain', 'javascript', 'xml']

seen = set()
count = 0


def should_extract_links(response):
    headers = (response or '').split('\r\n\r\n')[0]
    for line in headers.split('\r\n'):
        if line.lower().startswith('content-type:'):
            ct = line.split(':', 1)[1].strip().lower()
            return any(t in ct for t in LINK_CONTENT_TYPES)
    return True  # no Content-Type header -> extract


def queue_path(engine, template, path):
    global count
    if count >= MAX:
        return
    dedup = path.split('?')[0]
    if dedup in seen:
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

    from burp.api.montoya.http.message.requests import HttpRequest
    base = HttpRequest.httpRequestFromUrl(target.endpoint + "/").toString()
    template = base.replace("GET / ", "GET %s ", 1)

    queue_path(engine, template, '/')
    for word in WORDLIST:
        path = word if word.startswith('/') else '/' + word
        queue_path(engine, template, path)


def handleResponse(req, interesting):
    if req.status == 0:
        return

    if should_extract_links(req.response):
        for path in PATH_RE.findall(req.response or ''):
            queue_path(req.engine, req.template, path)

    if req.status != 404:
        if DETECT_REFLECTION and CANARY in (req.response or ''):
            req.label = req.label + ' reflection'
        table.add(req)
