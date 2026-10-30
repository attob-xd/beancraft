/**
 * What the service worker (`sw.ts`) does with each request the page makes. Pure, so it can be
 * tested; the worker only carries it out.
 */

/**
 * - `build`: a build file, versioned by its hash in the URL (`?v=`): from the cache once it's there.
 * - `immutable`: a game asset named by its content hash (`assets/objects/ab/abcd...`): the same.
 * - `page`: the page and what it starts from: from the network while there is one, from the cache
 *   when offline, so a returning player can start without a connection.
 * - `pass`: not the worker's business (the client jar, which the storage worker keeps itself;
 *   other origins; the development server's own files).
 */
                                                               

/** The worker's caches. A new layout gets a new name; the worker deletes names it doesn't know. */
export const CACHES = {
  build: "incari-build-v1",
  immutable: "incari-assets-v1",
  page: "incari-page-v1",
}         ;

/** Files the page starts from, kept for offline starts. */
const PAGE_FILES = new Set([
  "",
  "index.html",
  "boot.js",
  "loader.js",
  "features.js",
  "jspi.js",
  "wakeups.js",
  "game-worker.js",
  "cache-policy.js",
  "jsmods.js",
  "jsmods-core.js",
  "build.json",
  "asset-index.json",
]);

export function strategyFor(url     , scope     , method = "GET")           {
  if (method !== "GET" || url.origin !== scope.origin || !url.pathname.startsWith(scope.pathname)) {
    return "pass";
  }
  const path = url.pathname.slice(scope.pathname.length);
  if (path === "client.jar") {
    // Kept by the storage worker (OPFS or IndexedDB, ADR 0011): caching it here would store it twice.
    return "pass";
  }
  if (url.searchParams.has("v")) {
    return "build";
  }
  if (/^assets\/objects\/[0-9a-f]{2}\/[0-9a-f]{40}$/.test(path)) {
    return "immutable";
  }
  if (PAGE_FILES.has(path)) {
    return "page";
  }
  return "pass";
}

/**
 * Which cached build files to drop once the page runs a build: every versioned URL the build
 * doesn't list. `keep` holds the URLs, relative to the scope, that the running build loads.
 */
export function staleBuildEntries(cached                   , keep                   , scope     )           {
  const wanted = new Set(keep.map((path) => new URL(path, scope).href));
  return cached.filter((href) => !wanted.has(href));
}
