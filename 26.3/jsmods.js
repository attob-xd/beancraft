/**
 * The player's JavaScript mods (ADR 0067, docs/js-mods.md): kept in this browser, run in the page,
 * handed the game's events by its script host (`incari.app.web.jsmods.WebScriptHost`) through
 * `window.incariMods`, and asking about the game through `window.incariNative`. The Mods panel (F8)
 * adds, switches and removes them, and hands Fabric jars to the dev server on this machine, which
 * builds them into the game (fabric/README.md).
 *
 * Every call into a mod is caught: an error that reached the window would end the game.
 */
import { GameLink, HudCommands, parseHeader,                } from "./jsmods-core.js";

                                            

/** A mod as the page runs it. */
                     
                    
                                     
                                                    
                                
                 
                         
                                                                                           
                   
 

/** What the game's host calls. */
                    
                                              
                      
                                             
 

                      
                                                                                            
                                             
                        
                                                          
 

const page = window                                  ;

const STORE = "incari.jsmods";
const DATA = "incari.jsmods.data.";
/** Errors before a mod is stopped. */
const MAX_ERRORS = 20;

const loaded = new Map                   ();
let ticks = 0;
let started = false;

const link = new GameLink(
  () => page.incariNative,
  (message, error) => {
    console.warn(message, error);
  },
);

/** The game's answer to `op`: see {@link GameLink.ask}. */
function ask(op        , argument = "")         {
  return link.ask(op, argument);
}

function isStoredMod(value         )                     {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const mod = value                                             ;
  return (
    typeof mod.id === "string" &&
    typeof mod.name === "string" &&
    typeof mod.version === "string" &&
    typeof mod.description === "string" &&
    typeof mod.source === "string" &&
    typeof mod.enabled === "boolean"
  );
}

function readStore()              {
  try {
    const text = localStorage.getItem(STORE);
    const parsed          = text === null ? [] : JSON.parse(text);
    return Array.isArray(parsed) ? parsed.filter(isStoredMod) : [];
  } catch (error) {
    console.warn("[Incari] JS mods: couldn't read the saved mods", error);
    return [];
  }
}

function writeStore()       {
  try {
    localStorage.setItem(STORE, JSON.stringify([...loaded.values()].map((mod) => mod.stored)));
  } catch (error) {
    console.warn("[Incari] JS mods: couldn't save the mods", error);
  }
}

function record(mod           , error         , during        )       {
  mod.errors++;
  mod.failure = `${during}: ${error instanceof Error ? error.message : String(error)}`;
  console.error(`[Incari] JS mod ${mod.stored.id} failed in ${during}:`, error);
  if (mod.errors >= MAX_ERRORS && !mod.stopped) {
    mod.stopped = true;
    console.error(`[Incari] JS mod ${mod.stored.id} stopped after ${String(MAX_ERRORS)} errors`);
  }
  refreshPanel();
}

function active(mod           )          {
  return mod.stored.enabled && !mod.stopped;
}

function emit(mod           , name        , argument         )       {
  const list = mod.listeners.get(name);
  if (list === undefined || !active(mod)) {
    return;
  }
  for (const listener of [...list]) {
    try {
      listener(argument);
    } catch (error) {
      record(mod, error, name);
    }
  }
}

function add(map                         , key        , listener         )       {
  if (typeof listener !== "function") {
    throw new TypeError(`${key}: the listener isn't a function`);
  }
  const list = map.get(key) ?? [];
  list.push(listener            );
  map.set(key, list);
}

/** What a mod gets as `api`: see docs/js-mods.md. */
function apiFor(mod           )         {
  const dataKey = (key         )         => `${DATA}${mod.stored.id}.${String(key)}`;
  const player = ()          => {
    const text = ask("player");
    return text === "" ? null : (JSON.parse(text)           );
  };
  return Object.freeze({
    mod: Object.freeze({ id: mod.stored.id, name: mod.stored.name, version: mod.stored.version }),
    on(event         , listener         )       {
      add(mod.listeners, String(event), listener);
    },
    off(event         , listener         )       {
      const list = mod.listeners.get(String(event));
      if (list !== undefined) {
        mod.listeners.set(
          String(event),
          list.filter((other) => other !== listener),
        );
      }
    },
    every(ticksApart         , listener         )       {
      if (typeof listener !== "function") {
        throw new TypeError("every: the listener isn't a function");
      }
      const apart = Math.max(1, Math.floor(Number(ticksApart)) || 1);
      add(mod.listeners, "tick", () => {
        if (ticks % apart === 0) {
          (listener              )();
        }
      });
    },
    onKey(code         , listener         )       {
      add(mod.keys, String(code), listener);
    },
    player,
    inGame: ()          => ask("screen") === "" && player() !== null,
    fps: ()         => Number(ask("fps")) || 0,
    world: ()         => ask("world"),
    screen: ()         => ask("screen"),
    key: (name         )          => ask("key", String(name)) === "true",
    ticks: ()         => ticks,
    say(text         )       {
      ask("say", String(text));
    },
    show(text         )       {
      ask("show", String(text));
    },
    notify(title         , text          = "")       {
      ask("notify", `${String(title)}\n${String(text)}`);
    },
    copy(text         )       {
      ask("copy", String(text));
    },
    storage: Object.freeze({
      get(key         , fallback          = null)          {
        try {
          const text = localStorage.getItem(dataKey(key));
          return text === null ? fallback : (JSON.parse(text)           );
        } catch {
          return fallback;
        }
      },
      set(key         , value         )       {
        localStorage.setItem(dataKey(key), JSON.stringify(value));
      },
      remove(key         )       {
        localStorage.removeItem(dataKey(key));
      },
    }),
    log(...values           )       {
      console.log(`[${mod.stored.id}]`, ...values);
    },
  });
}

/** Runs a mod's source if it's on; its errors are kept, never thrown. */
function load(stored           )            {
  const mod            = { stored, listeners: new Map(), keys: new Map(), errors: 0, failure: null, stopped: false };
  loaded.set(stored.id, mod);
  if (!stored.enabled) {
    return mod;
  }
  try {
    // eslint-disable-next-line @typescript-eslint/no-implied-eval -- a mod is code the player added to run here
    const run = new Function("api", `"use strict";\n${stored.source}\n//# sourceURL=jsmods/${stored.id}.js`)     
                  
             ;
    run(apiFor(mod));
  } catch (error) {
    record(mod, error, "loading");
  }
  if (started) {
    emit(mod, "start", "");
  }
  return mod;
}

/** Adds or replaces a mod from a file's text, on. */
function addMod(source        , fileName        )            {
  const header = parseHeader(source, fileName);
  const stored            = { ...header, source, enabled: true };
  loaded.delete(stored.id);
  load(stored);
  writeStore();
  return stored;
}

function setEnabled(id        , enabled         )       {
  const mod = loaded.get(id);
  if (mod === undefined) {
    return;
  }
  // Switching reloads it from its source: listeners from before are dropped with the old copy.
  load({ ...mod.stored, enabled });
  writeStore();
}

function removeMod(id        )       {
  loaded.delete(id);
  writeStore();
}

const pageMods           = {
  event(name, argument) {
    link.during(() => {
      if (name === "start") {
        started = true;
      } else if (name === "tick") {
        ticks++;
        link.tick();
      }
      const value          = name === "click" || name === "attack" ? Number(argument) : argument;
      for (const mod of [...loaded.values()]) {
        emit(mod, name, value);
      }
    });
  },
  drawsHud() {
    for (const mod of loaded.values()) {
      if (active(mod) && (mod.listeners.get("hud")?.length ?? 0) > 0) {
        return true;
      }
    }
    return false;
  },
  hud(width, height) {
    const commands = new HudCommands();
    const draw = Object.freeze({
      width,
      height,
      text: (text         , x         , y         , color          ) => {
        commands.text(text, x, y, color);
      },
      rect: (x         , y         , w         , h         , color          ) => {
        commands.rect(x, y, w, h, color);
      },
      circle: (x         , y         , radius         , color          ) => {
        commands.circle(x, y, radius, color);
      },
      ring: (x         , y         , radius         , lineWidth         , color          ) => {
        commands.ring(x, y, radius, lineWidth, color);
      },
      push: (x         , y         , scale          ) => {
        commands.push(x, y, scale);
      },
      pop: () => {
        commands.pop();
      },
      textWidth: (text         )         => Number(ask("textWidth", String(text))) || 0,
      lineHeight: ()         => Number(ask("lineHeight")) || 9,
    });
    link.during(() => {
      for (const mod of loaded.values()) {
        emit(mod, "hud", draw);
        commands.close();
      }
    });
    return commands.toString();
  },
};

// ---- The Mods panel --------------------------------------------------------------------------

let panel                     = null;

const STYLE = `
#incari-mods-button { position: fixed; right: 10px; bottom: 10px; z-index: 50; opacity: .35;
  font: 12px system-ui, sans-serif; color: #fff; background: #1c2430; border: 1px solid #3a4656;
  border-radius: 6px; padding: 5px 9px; cursor: pointer; }
#incari-mods-button:hover { opacity: 1; }
#incari-mods { position: fixed; top: 12px; right: 12px; bottom: 12px; width: min(420px, calc(100vw - 24px));
  z-index: 51; overflow: auto; font: 13px/1.45 system-ui, sans-serif; color: #e8edf3; background: #141a22f2;
  border: 1px solid #3a4656; border-radius: 10px; padding: 14px 16px; box-shadow: 0 10px 40px #0008; }
#incari-mods[hidden] { display: none; }
#incari-mods h2 { font-size: 17px; margin: 0 0 4px; }
#incari-mods h3 { font-size: 14px; margin: 16px 0 4px; }
#incari-mods p { margin: 4px 0; color: #aab6c4; }
#incari-mods button { font: inherit; color: #e8edf3; background: #26313f; border: 1px solid #3a4656;
  border-radius: 6px; padding: 4px 10px; cursor: pointer; }
#incari-mods button:hover { background: #31404f; }
#incari-mods .row { display: flex; gap: 8px; align-items: center; padding: 7px 0; border-top: 1px solid #263140; }
#incari-mods .grow { flex: 1; min-width: 0; }
#incari-mods .name { font-weight: 600; }
#incari-mods .meta { color: #8b97a6; font-size: 12px; }
#incari-mods .bad { color: #ff8a80; font-size: 12px; overflow-wrap: anywhere; }
#incari-mods textarea { width: 100%; box-sizing: border-box; min-height: 110px; font: 12px ui-monospace, monospace;
  color: #e8edf3; background: #0e1319; border: 1px solid #3a4656; border-radius: 6px; padding: 6px; }
#incari-mods pre { white-space: pre-wrap; font: 11px ui-monospace, monospace; background: #0e1319;
  border: 1px solid #263140; border-radius: 6px; padding: 6px; max-height: 260px; overflow: auto; }
#incari-mods pre:empty { display: none; }
#incari-mods .actions { display: flex; gap: 8px; flex-wrap: wrap; margin: 6px 0; }
`;

function element                                       (tag   , text = "", className = "")                           {
  const made = document.createElement(tag);
  made.textContent = text;
  if (className !== "") {
    made.className = className;
  }
  return made;
}

function button(text        , action            )                    {
  const made = element("button", text);
  made.type = "button";
  made.addEventListener("click", action);
  return made;
}

/** Runs an async panel action, its failure shown rather than ending the game. */
function task(work                     , where             )       {
  work().catch((error         ) => {
    where.textContent = `Failed: ${error instanceof Error ? error.message : String(error)}`;
  });
}

let jsList                     = null;
let jarList                     = null;
let jarStatus                     = null;
let jarReport                        = null;

function refreshPanel()       {
  if (jsList === null) {
    return;
  }
  jsList.replaceChildren();
  if (loaded.size === 0) {
    jsList.append(element("p", "No JS mods yet."));
  }
  for (const mod of loaded.values()) {
    const row = element("div", "", "row");
    const info = element("div", "", "grow");
    info.append(element("div", mod.stored.name, "name"));
    info.append(
      element(
        "div",
        `${mod.stored.id} · ${mod.stored.version}${mod.stored.description === "" ? "" : " · " + mod.stored.description}`,
        "meta",
      ),
    );
    if (mod.failure !== null) {
      info.append(element("div", (mod.stopped ? "Stopped. " : "") + mod.failure, "bad"));
    }
    const id = mod.stored.id;
    row.append(
      info,
      button(mod.stored.enabled ? "Turn off" : "Turn on", () => {
        setEnabled(id, !(loaded.get(id)?.stored.enabled ?? false));
        refreshPanel();
      }),
      button("Remove", () => {
        removeMod(id);
        refreshPanel();
      }),
    );
    jsList.append(row);
  }
}

async function fabric(path        , init              = {})                    {
  const response = await fetch(`/fabric/${path}`, init);
  if (response.status === 404) {
    throw new Error("converting needs Incari's dev server on your PC (gradlew :app-web:serve)");
  }
  return response;
}

async function refreshJars()                {
  const list = jarList;
  if (list === null) {
    return;
  }
  const response = await fabric("mods");
  const jars          = await response.json();
  list.replaceChildren();
  if (!Array.isArray(jars) || jars.length === 0) {
    list.append(element("p", "No Fabric mods in mods/."));
    return;
  }
  for (const jar of jars                                           ) {
    const name = String(jar.name);
    const enabled = jar.enabled !== false;
    const row = element("div", "", "row");
    const info = element("div", "", "grow");
    info.append(element("div", name, "name"));
    if (!enabled) {
      info.append(element("div", "Off: left out of the next rebuild", "meta"));
    }
    row.append(
      info,
      button(enabled ? "Turn off" : "Turn on", () => {
        task(async () => {
          const query = `name=${encodeURIComponent(name)}&on=${String(!enabled)}`;
          const answer = await fabric(`enable?${query}`, { method: "POST" });
          if (jarStatus !== null) {
            jarStatus.textContent = await answer.text();
          }
          await refreshJars();
        }, list);
      }),
      button("Remove", () => {
        task(async () => {
          await fabric(`remove?name=${encodeURIComponent(name)}`, { method: "POST" });
          await refreshJars();
        }, list);
      }),
    );
    list.append(row);
  }
}

async function convert(file      )                {
  if (jarStatus === null || jarReport === null) {
    return;
  }
  jarStatus.textContent = `Converting ${file.name} on your PC…`;
  const response = await fabric(`upload?name=${encodeURIComponent(file.name)}`, {
    method: "POST",
    body: await file.arrayBuffer(),
  });
  jarReport.textContent = await response.text();
  jarStatus.textContent = response.ok
    ? `${file.name} converted. Lines starting with ! are problems. Rebuild to play with it.`
    : `${file.name} couldn't be converted.`;
  await refreshJars();
}

async function pollBuild()                {
  if (jarStatus === null || jarReport === null) {
    return;
  }
  const response = await fabric("status");
  const status = (await response.json())                                                      ;
  const minutes = status.minutes ?? 0;
  if (status.state === "building") {
    jarStatus.textContent = `Rebuilding the game on your PC (${String(minutes)} min; it takes about 30)…`;
    window.setTimeout(() => {
      task(pollBuild, jarStatus ?? document.body);
    }, 5000);
  } else if (status.state === "done") {
    jarStatus.replaceChildren(
      element("span", `Rebuilt in ${String(minutes)} min. `),
      button("Reload to play", () => {
        location.reload();
      }),
    );
  } else if (status.state === "failed") {
    jarStatus.textContent = "The rebuild failed: a mod may use what the browser can't. See the log.";
    jarReport.textContent = status.log ?? "";
  } else {
    jarStatus.textContent = "";
  }
}

function buildPanel()              {
  const style = element("style", STYLE);
  document.head.append(style);
  const root = element("div");
  root.id = "incari-mods";
  root.hidden = true;
  // Keys typed here stay here: the game listens on the window.
  for (const type of ["keydown", "keyup", "keypress"]) {
    root.addEventListener(type, (event) => {
      event.stopPropagation();
    });
  }

  root.append(
    element("h2", "Mods"),
    element("p", "F8 opens and closes this. JS mods work at once; Fabric mods are converted on your PC."),
  );

  root.append(element("h3", "JavaScript mods"));
  const fileInput = element("input");
  fileInput.type = "file";
  fileInput.accept = ".js,text/javascript";
  fileInput.multiple = true;
  fileInput.hidden = true;
  const jsStatus = element("p");
  fileInput.addEventListener("change", () => {
    const files = [...(fileInput.files ?? [])];
    fileInput.value = "";
    task(async () => {
      for (const file of files) {
        addMod(await file.text(), file.name);
      }
      jsStatus.textContent = `Added ${String(files.length)} mod${files.length === 1 ? "" : "s"}.`;
      refreshPanel();
    }, jsStatus);
  });
  const code = element("textarea");
  code.placeholder = '// @name My mod\napi.on("hud", (draw) => draw.text("Hello!", 4, 4));';
  code.hidden = true;
  const addCode = button("Add this code", () => {
    if (code.value.trim() !== "") {
      const stored = addMod(code.value, "pasted.js");
      jsStatus.textContent = `Added ${stored.name}.`;
      code.value = "";
      code.hidden = true;
      addCode.hidden = true;
      refreshPanel();
    }
  });
  addCode.hidden = true;
  root.append(element("div", "", "actions"), fileInput, code);
  const jsActions = root.querySelector(".actions");
  jsActions?.append(
    button("Add .js files", () => {
      fileInput.click();
    }),
    button("Write one", () => {
      code.hidden = !code.hidden;
      addCode.hidden = code.hidden;
      if (!code.hidden) {
        code.focus();
      }
    }),
    addCode,
  );
  jsList = element("div");
  root.append(jsStatus, jsList);

  root.append(
    element("h3", "Fabric mods (.jar)"),
    element(
      "p",
      "Mods made for Minecraft 26.3. Your PC applies them and rebuilds the game (about 30 minutes); only some work. The report says what didn't.",
    ),
  );
  const jarInput = element("input");
  jarInput.type = "file";
  jarInput.accept = ".jar";
  jarInput.hidden = true;
  jarStatus = element("p");
  jarReport = element("pre");
  jarList = element("div");
  jarInput.addEventListener("change", () => {
    const file = jarInput.files?.[0];
    jarInput.value = "";
    if (file !== undefined) {
      task(() => convert(file), jarStatus ?? root);
    }
  });
  const jarActions = element("div", "", "actions");
  jarActions.append(
    button("Convert a .jar", () => {
      jarInput.click();
    }),
    button("Rebuild with these", () => {
      task(async () => {
        await fabric("build", { method: "POST" });
        await pollBuild();
      }, jarStatus ?? root);
    }),
  );
  root.append(jarActions, jarInput, jarStatus, jarList, jarReport);

  root.append(element("div", "", "actions"));
  root.lastElementChild?.append(
    button("Close", () => {
      root.hidden = true;
    }),
  );
  document.body.append(root);

  const opener = element("button", "Mods (F8)");
  opener.id = "incari-mods-button";
  opener.type = "button";
  opener.addEventListener("click", () => {
    toggle();
  });
  document.body.append(opener);
  return root;
}

function toggle()       {
  panel ??= buildPanel();
  panel.hidden = !panel.hidden;
  if (!panel.hidden) {
    document.exitPointerLock();
    refreshPanel();
    const where = jarStatus ?? panel;
    task(async () => {
      await refreshJars();
      await pollBuild();
    }, where);
  }
}

/**
 * Has the dev server keep a Fabric mod's pipeline the build had no shaders for (ADR 0065): the
 * build makes them at the next translation. Pages not on the dev server just say nothing.
 */
function keepModPipeline(line        )       {
  void fetch("/fabric/shaders", { method: "POST", body: `${line}\n` })
    .then((response) => {
      if (response.ok) {
        console.info("[Incari] Saved for the next build's shaders:", line.split("\t")[0]);
      }
    })
    .catch(() => undefined);
}

/** Loads the saved mods, gives the game its way in, and listens for F8 and the mods' keys. */
export function installMods()       {
  page.incariMods = pageMods;
  page.incariModPipeline = keepModPipeline;
  for (const stored of readStore()) {
    load(stored);
  }
  window.addEventListener(
    "keydown",
    (event) => {
      if (event.code === "F8") {
        event.preventDefault();
        event.stopImmediatePropagation();
        toggle();
        return;
      }
      if ((panel !== null && !panel.hidden) || event.repeat || ask("screen") !== "") {
        return;
      }
      for (const mod of loaded.values()) {
        const list = mod.keys.get(event.code);
        if (list === undefined || !active(mod)) {
          continue;
        }
        for (const listener of [...list]) {
          try {
            listener(event.code);
          } catch (error) {
            record(mod, error, `key ${event.code}`);
          }
        }
      }
    },
    true,
  );
  // The panel's button, from the start; the panel itself is made when first opened.
  if (document.readyState === "loading") {
    window.addEventListener("load", () => {
      panel ??= buildPanel();
    });
  } else {
    panel ??= buildPanel();
  }
  console.log(`[Incari] JS mods: ${String(loaded.size)} saved, F8 for the Mods panel`);
}
