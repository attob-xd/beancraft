Incari web build (Minecraft 26.3)
===================================

DO NOT SHARE, UPLOAD OR PUBLISH THIS FOLDER.

This folder contains Minecraft's code and files (the compiled game, the client jar, its
sounds and translated shaders). Mojang owns them and doesn't allow redistributing them. The
Incari project publishes only patches and source code, never builds, and gives nobody
permission to distribute a build: not on a public website, a file host, a mirror, a Discord
server, a GitHub repository, Pages or Releases, or as a downloadable copy.

You built this folder yourself from your own download of the game, for your own use. If you
put it on a web server, it's your server and your responsibility: keep it to yourself or to
people you know, behind a login or on a private network, and take it down when you're done.
Anyone who shares or hosts a build does so on their own, not on behalf of the Incari project
or its contributors.

Incari is not affiliated with or endorsed by Mojang or Microsoft. Minecraft is a trademark of
Mojang Studios. Players need to own Minecraft: Java Edition.


Serving it
----------

Any static web server works: serve this folder as it is and open index.html. Everything is
fetched by relative path, so a sub-folder (https://example.test/incari/) works too.

  * HTTPS (or localhost) is strongly recommended. Over plain HTTP from another machine the
    browser turns off the service worker (no caching between visits) and the origin private
    file system (saves fall back to IndexedDB).
  * Send .wasm as application/wasm. Without it the browser can't compile while downloading.
  * Turn on gzip or brotli for .wasm, .js and .json. The game module compresses to about a
    fifth of its size.
  * Optional but faster: these two headers on every response make the page cross-origin
    isolated, so the game's workers share memory instead of copying.

      Cross-Origin-Opener-Policy: same-origin
      Cross-Origin-Embedder-Policy: require-corp

Apache: the .htaccess in this folder sets all of it (it needs mod_headers and AllowOverride).

nginx:

  location /incari/ {
      alias /path/to/this/folder/;
      types { application/wasm wasm; text/javascript js; application/json json; text/html html; }
      gzip on;
      gzip_types application/wasm text/javascript application/json;
      add_header Cross-Origin-Opener-Policy same-origin always;
      add_header Cross-Origin-Embedder-Policy require-corp always;
      add_header Cache-Control no-cache always;
  }

Caddy:

  example.test {
      root * /path/to/this/folder
      encode zstd gzip
      header Cross-Origin-Opener-Policy same-origin
      header Cross-Origin-Embedder-Policy require-corp
      header Cache-Control no-cache
      file_server
  }

A quick test on one machine, without the headers (it still runs, without shared memory):

  python3 -m http.server 8080 --directory /path/to/this/folder

Joining servers: the browser connects over WebSocket, so a server needs a WebSocket
front (EaglerXServer, or a WebSocket bridge). A page served over HTTPS can only open wss://
connections.

Browsers: a current Chrome or Edge (what Incari is tested on) with WebGL 2 and WebAssembly GC.
The Fast build needs Chrome or Edge 137 or Firefox 153; the Compatible build runs in Chrome 119,
Firefox 120 and Safari 18.2 and later.
