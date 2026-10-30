/**
 * The page's service worker: keeps the build and the sounds the game has played, so a returning
 * player downloads nothing that hasn't changed and can start offline. The decisions are in
 * `cache-policy.ts`; this file only carries them out.
 *
 * Registered as a module worker by `boot.ts`. It never delays a request it can't help: anything it
 * doesn't cache goes straight to the network.
 */
import { CACHES, staleBuildEntries, strategyFor,               } from "./cache-policy.js";

/** The parts of a service worker's global scope this file uses (the DOM library doesn't have them). */
                       
                                                    
                                               
                               
                                                                                                   
                                                                               
                                                                                            
 

                           
                                          
 

                                              
                            
                                                 
 

const worker = self                          ;
const scope = new URL(worker.registration.scope);

worker.addEventListener("install", (event) => {
  // A new worker takes over at once: its caches are keyed by version, so nothing the old one
  // stored can be served by mistake.
  event.waitUntil(worker.skipWaiting());
});

worker.addEventListener("activate", (event) => {
  event.waitUntil(
    (async () => {
      const known = new Set        (Object.values(CACHES));
      for (const name of await caches.keys()) {
        if (name.startsWith("incari-") && !known.has(name)) {
          await caches.delete(name);
        }
      }
      // So the page that registered it loads the build through it on the first visit.
      await worker.clients.claim();
    })(),
  );
});

worker.addEventListener("fetch", (event) => {
  const request = event.request;
  const strategy = strategyFor(new URL(request.url), scope, request.method);
  if (strategy !== "pass") {
    event.respondWith(respond(request, strategy));
  }
});

/** The page names the files its build loads; older versions of them are dropped. */
worker.addEventListener("message", (event) => {
  const data = event.data                                             ;
  if (data?.type !== "build" || !Array.isArray(data.keep)) {
    return;
  }
  const keep = data.keep.filter((path)                 => typeof path === "string");
  void (async () => {
    const cache = await caches.open(CACHES.build);
    const cached = (await cache.keys()).map((r) => r.url);
    for (const href of staleBuildEntries(cached, keep, scope)) {
      await cache.delete(href);
    }
  })();
});

async function respond(request         , strategy                           )                    {
  if (strategy === "page") {
    const cache = await caches.open(CACHES.page);
    try {
      const response = await fetch(request, { cache: "no-cache" });
      if (response.ok) {
        await cache.put(request, response.clone());
      }
      return response;
    } catch (error) {
      const cached = await cache.match(request);
      if (cached !== undefined) {
        return cached;
      }
      throw error;
    }
  }
  const cache = await caches.open(strategy === "build" ? CACHES.build : CACHES.immutable);
  const cached = await cache.match(request);
  if (cached !== undefined) {
    return cached;
  }
  const response = await fetch(request);
  if (response.ok) {
    // Stored while the page reads its copy: the module still compiles as it downloads.
    void cache.put(request, response.clone());
  }
  return response;
}
