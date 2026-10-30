# Beancraft — Web Clients

The launch page offers Minecraft 1.12.2, 1.18.2, 1.18.2 Shaders, and 26.3.
The 26.3 client uses Incari and joins `wss://play.attob.lol/server` by default.

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
| `26.3/` | Incari 26.3 client, loader, and build manifest |

## Launch options

Appended to the URL as query parameters:

- `?server=ws://host:port` — join a server on load
- `?user=NAME` — preset the username
- `?cape=/path.png` — load a cape from a URL

The launch page passes query parameters to the selected client. For 26.3,
`?user=NAME` also sets Incari's `?name=NAME` unless `name` was supplied explicitly.
The `cape` option applies to the older clients.

## Notes

Player data (username, skins, capes, worlds) is stored in the browser and is
tied to the site's origin, so it does not transfer between different domains.

# beancraft
