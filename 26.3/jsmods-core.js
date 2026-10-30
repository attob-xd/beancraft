/**
 * The JavaScript mods' rules that need no page (ADR 0067), so Node tests them: a mod's header,
 * the colours mods give, when the game can be asked, and the HUD drawing the page hands the game
 * (`incari.app.web.jsmods`).
 */

/** A mod as the Mods panel keeps it: its header, its source, and whether it's on. */
                            
             
               
                  
                      
                 
                   
 

/** What a mod's header says: `// @id`, `// @name`, `// @version`, `// @description` lines. */
                            
             
               
                  
                      
 

/**
 * A mod's header, read from `// @key value` comment lines at the top of its source; what's missing
 * comes from the file's name. The id is lower case letters, digits, `-` and `_`.
 */
export function parseHeader(source        , fileName        )            {
  const values = new Map                ();
  for (const line of source.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (trimmed === "") {
      continue;
    }
    const match = /^\/\/\s*@(\w+)\s+(.+)$/.exec(trimmed);
    if (match !== null) {
      const [, key, value] = match;
      if (key !== undefined && value !== undefined && !values.has(key)) {
        values.set(key, value.trim());
      }
    } else if (!trimmed.startsWith("//")) {
      // The header ends at the first line of code.
      break;
    }
  }
  const base = fileName.replace(/\.[^.]*$/, "");
  const name = values.get("name") ?? (base === "" ? "Untitled mod" : base);
  return {
    id: toId(values.get("id") ?? name),
    name,
    version: values.get("version") ?? "1.0",
    description: values.get("description") ?? "",
  };
}

/** `My Cool Mod!` as `my-cool-mod`: what ids and storage keys allow. */
export function toId(text        )         {
  const id = text
    .toLowerCase()
    .replace(/[^a-z0-9_-]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 48);
  return id === "" ? "mod" : id;
}

/**
 * A colour as the game takes it, eight hex digits of ARGB: from a number (`0xffffcc00`, or
 * `0xffcc00` taken as opaque) or a CSS-style `#rgb`, `#rrggbb` or `#aarrggbb` string.
 */
export function toColor(color         , fallback = "ffffffff")         {
  if (typeof color === "number" && Number.isFinite(color)) {
    const value = color > 0xffffff ? color >>> 0 : (0xff000000 | color) >>> 0;
    return value.toString(16).padStart(8, "0");
  }
  if (typeof color === "string") {
    const hex = color.trim().replace(/^#/, "").toLowerCase();
    if (/^[0-9a-f]{3}$/.test(hex)) {
      return "ff" + hex.replace(/[0-9a-f]/g, (digit) => digit + digit);
    }
    if (/^[0-9a-f]{6}$/.test(hex)) {
      return "ff" + hex;
    }
    if (/^[0-9a-f]{8}$/.test(hex)) {
      return hex;
    }
  }
  return fallback;
}

/** The game's `window.incariNative`: an answer to `op`, or what doing it says. */
                                                                  

/** Asks that change something in the game, done in the order asked. */
const ACTIONS = new Set(["say", "show", "notify", "copy"]);

/** Asks whose last answer is kept for asks from outside the game's calls. */
const KEPT = new Set(["player", "fps", "world", "screen", "key", "lineHeight", "clipboard"]);

/**
 * The page's way to ask the game. TeaVM can only be entered while the game is itself calling the
 * page (an event, the HUD): from a key listener, a timer or after an `await`, a call fails inside
 * the runtime. So asks from outside get the game's answer from its last tick, and actions wait for
 * its next tick.
 */
export class GameLink {
                   native                              ;
                   warn                                           ;
                   answers = new Map                ();
                   later                     = [];
          inside = 0;

  constructor(native                              , warn                                           ) {
    this.native = native;
    this.warn = warn;
  }

  /** Runs `work` as part of a call the game made into the page, when it can be asked. */
  during   (work         )    {
    this.inside++;
    try {
      return work();
    } finally {
      this.inside--;
    }
  }

  /**
   * In a game tick: does what was asked from outside and renews the kept answers, the open screen's
   * always (the page's key handling reads it).
   */
  tick()       {
    for (const [op, argument] of this.later.splice(0)) {
      this.ask(op, argument);
    }
    for (const key of new Set([...this.answers.keys(), "screen\n"])) {
      const split = key.indexOf("\n");
      this.ask(key.slice(0, split), key.slice(split + 1));
    }
  }

  /** The game's answer to `op`; empty before the game runs, or when it can't say from here. */
  ask(op        , argument = "")         {
    const call = this.native();
    if (call === undefined) {
      return "";
    }
    const key = `${op}\n${argument}`;
    if (this.inside === 0) {
      if (ACTIONS.has(op)) {
        this.later.push([op, argument]);
      }
      return this.answers.get(key) ?? "";
    }
    try {
      const answer = call(op, argument);
      if (KEPT.has(op)) {
        this.answers.set(key, answer);
      }
      return answer;
    } catch (error) {
      this.warn(`[Incari] JS mods: ${op} failed`, error);
      return "";
    }
  }
}

/** A number for a command: finite, or 0. */
function num(value         )         {
  const number = Number(value);
  return Number.isFinite(number) ? String(number) : "0";
}

/**
 * The HUD drawing for one frame, as the game's host reads it: a command a line, fields split by
 * tabs, a text command's text last (its tabs and line breaks made spaces).
 */
export class HudCommands {
                   lines           = [];
          depth = 0;

  text(text         , x         , y         , color          )       {
    const line = String(text).replace(/[\t\r\n]+/g, " ");
    this.lines.push(`T\t${num(x)}\t${num(y)}\t${toColor(color)}\t${line}`);
  }

  rect(x         , y         , width         , height         , color          )       {
    this.lines.push(`F\t${num(x)}\t${num(y)}\t${num(width)}\t${num(height)}\t${toColor(color, "80000000")}`);
  }

  circle(x         , y         , radius         , color          )       {
    this.lines.push(`D\t${num(x)}\t${num(y)}\t${num(radius)}\t${toColor(color)}`);
  }

  ring(x         , y         , radius         , width         , color          )       {
    this.lines.push(`R\t${num(x)}\t${num(y)}\t${num(radius)}\t${num(width)}\t${toColor(color)}`);
  }

  /** Moves and scales what follows until the matching {@link pop}. */
  push(x         , y         , scale          = 1)       {
    this.lines.push(`P\t${num(x)}\t${num(y)}\t${num(scale)}`);
    this.depth++;
  }

  pop()       {
    if (this.depth > 0) {
      this.lines.push("Q");
      this.depth--;
    }
  }

  /** Ends any pushes a mod left open, so the next mod draws where it expects. */
  close()       {
    while (this.depth > 0) {
      this.pop();
    }
  }

  toString()         {
    this.close();
    return this.lines.join("\n");
  }
}
