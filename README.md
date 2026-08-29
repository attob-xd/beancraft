# EaglercraftX 1.12.2 — Web Client

Minecraft 1.12.2 in the browser, built with TeaVM (WASM-GC).

## Playing

Served over HTTP/HTTPS only — opening `index.html` from disk will not work.
GitHub Pages works out of the box.

## Files

| File | Purpose |
|---|---|
| `index.html` | entry point and launch options |
| `bootstrap.js` | loader |
| `assets.epw` | the client bundle (code + assets) |
| `capes/` | sample cape images |

## Launch options

Appended to the URL as query parameters:

- `?server=ws://host:port` — join a server on load
- `?user=NAME` — preset the username
- `?cape=/path.png` — load a cape from a URL

## Notes

Player data (username, skins, capes, worlds) is stored in the browser and is
tied to the site's origin, so it does not transfer between different domains.

# beancraft
