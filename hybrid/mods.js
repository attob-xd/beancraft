"use strict";
/*
 * beancraft hybrid - JavaScript mods (the game side is net.minecraft.client.HybridMods).
 *
 * Mods are added from the title screen's Mods button (GuiScreenMods), kept in this browser's localStorage ("hybrid.mods"), and run
 * once when the page loads, before the game starts. Each gets the global ModAPI:
 *
 *   ModAPI.addEventListener(name, fn) / removeEventListener(name, fn)
 *     "update"             every game tick (20 a second) while the game runs
 *     "worldload"          joined a world or server        "worldunload"   left it
 *     "sendchatmessage"    e.message (change it), e.preventDefault = true to not send it
 *     "receivechatmessage" e.message (plain text), e.preventDefault = true to hide it
 *     "key"                e.key: a key pressed in-game with no screen open (compare with ModAPI.keys.G etc.)
 *   ModAPI.player   {name, x, y, z, yaw, pitch, motionX/Y/Z, health, food, onGround, sprinting, sneaking,
 *                    inWater, dimension, creative} - null outside a world (posX/posY/posZ are aliases)
 *   ModAPI.world    {time, server, singleplayer}
 *   ModAPI.displayToChat(text or {msg})   shows a line in your chat only (§ colour codes work)
 *   ModAPI.sendChat(text)                 sends it as you (commands too)
 *   ModAPI.hud.set(id, text, x, y, color) / ModAPI.hud.remove(id)
 *   ModAPI.setSetting(name, value)        gamma, fov, sensitivity, renderDistance, hideGUI
 *   ModAPI.setSprinting(true/false)
 *
 * A "// @name Something" line in the file names the mod in the list; "// @description ..." and
 * "// @author ..." lines fill in its page on the Mods screen.
 */
(function () {
	const STORE = "hybrid.mods";
	const listeners = {};
	let queue = [];
	const loaded = [];   // {name, error}

	function readMods() {
		try {
			const v = JSON.parse(window.localStorage.getItem(STORE) || "[]");
			return Array.isArray(v) ? v : [];
		} catch (e) {
			return [];
		}
	}

	function writeMods(mods) {
		try {
			window.localStorage.setItem(STORE, JSON.stringify(mods));
			return true;
		} catch (e) {
			alert("Could not save the mod list: " + e);
			return false;
		}
	}

	const keys = {};
	"ESCAPE=1 1=2 2=3 3=4 4=5 5=6 6=7 7=8 8=9 9=10 0=11 Q=16 W=17 E=18 R=19 T=20 Y=21 U=22 I=23 O=24 P=25 A=30 S=31 D=32 F=33 G=34 H=35 J=36 K=37 L=38 Z=44 X=45 C=46 V=47 B=48 N=49 M=50 SPACE=57 F1=59 F2=60 F3=61 F4=62 F5=63 F6=64 F7=65 F8=66 F9=67 F10=68 F11=87 F12=88 UP=200 LEFT=203 RIGHT=205 DOWN=208 LSHIFT=42 LCONTROL=29 TAB=15 BACK=14 RETURN=28"
		.split(" ").forEach(function (kv) { const p = kv.split("="); keys[p[0]] = parseInt(p[1], 10); });

	const ModAPI = {
		version: "beancraft-hybrid-1",
		player: null,
		world: null,
		keys: keys,
		addEventListener: function (name, fn) {
			(listeners[name] = listeners[name] || []).push(fn);
		},
		removeEventListener: function (name, fn) {
			const l = listeners[name];
			if (l) {
				const i = l.indexOf(fn);
				if (i >= 0) l.splice(i, 1);
			}
		},
		displayToChat: function (m) {
			const text = (m !== null && typeof m === "object") ? m.msg : m;
			queue.push({ c: "chat", message: String(text) });
		},
		sendChat: function (text) {
			queue.push({ c: "send", message: String(text) });
		},
		hud: {
			set: function (id, text, x, y, color) {
				queue.push({ c: "hud", id: String(id), text: String(text), x: x | 0, y: y | 0,
					color: color === undefined ? 0xFFFFFF : color | 0 });
			},
			remove: function (id) {
				queue.push({ c: "hudRemove", id: String(id) });
			}
		},
		setSetting: function (name, value) {
			queue.push({ c: "setting", name: String(name), value: Number(value) });
		},
		setSprinting: function (on) {
			queue.push({ c: "sprint", value: !!on });
		},
		// EaglerForge-style mods call these; nothing to load here
		require: function () { },
		log: function () { console.log.apply(console, ["[mod]"].concat(Array.prototype.slice.call(arguments))); }
	};
	window.ModAPI = ModAPI;

	function fire(name, ev) {
		const l = listeners[name];
		if (!l) return ev;
		for (let i = 0; i < l.length; ++i) {
			try {
				l[i](ev);
			} catch (e) {
				console.error("[mod] a \"" + name + "\" listener failed:", e);
			}
		}
		return ev;
	}

	// ---------------------------------------------------------------- messages from the game (via the hooks)

	function handle(msg) {
		if (msg.charAt(0) !== "{" && msg !== "hello") {
			return manage(msg);
		}
		if (msg === "hello") {
			return loaded.length ? loaded.map(function (m) { return m.name; }).join(", ") : null;
		}
		let m;
		try {
			m = JSON.parse(msg);
		} catch (e) {
			return null;
		}
		switch (m.e) {
		case "update": {
			if (m.player) {
				const p = m.player;
				p.posX = p.x; p.posY = p.y; p.posZ = p.z;
				ModAPI.player = p;
				ModAPI.world = m.world || null;
			}
			fire("update", {});
			if (!queue.length) return null;
			const out = JSON.stringify(queue);
			queue = [];
			return out;
		}
		case "worldload":
			fire("worldload", {});
			return null;
		case "worldunload":
			ModAPI.player = null;
			ModAPI.world = null;
			fire("worldunload", {});
			return null;
		case "sendchatmessage": {
			const ev = fire("sendchatmessage", { message: m.message, preventDefault: false });
			return JSON.stringify({ cancel: !!ev.preventDefault, message: String(ev.message) });
		}
		case "receivechatmessage": {
			const ev = fire("receivechatmessage", { message: m.message, preventDefault: false });
			return ev.preventDefault ? JSON.stringify({ cancel: true }) : null;
		}
		case "key":
			fire("key", { key: m.key });
			return null;
		default:
			return null;
		}
	}

	// ---------------------------------------------------------------- run the installed mods now, before the game

	readMods().forEach(function (mod) {
		if (!mod.enabled) return;
		const rec = { name: mod.name, error: null };
		try {
			(new Function("ModAPI", mod.code + "\n//# sourceURL=mod-" + encodeURIComponent(mod.name) + ".js"))(ModAPI);
		} catch (e) {
			rec.error = String(e);
			console.error("[mod] " + mod.name + " failed to start:", e);
		}
		loaded.push(rec);
	});

	// ---------------------------------------------------------------- the manager (title screen -> Mods)

	const EXAMPLE = [
		"// @name Coordinates + Fullbright (example)",
		"// @description An example of what a mod can do: your coordinates on the HUD, and G toggles fullbright.",
		"// @author beancraft",
		"// Shows your position in the top-left corner; G toggles fullbright.",
		"let bright = false;",
		"ModAPI.addEventListener(\"update\", function () {",
		"  const p = ModAPI.player;",
		"  if (p) ModAPI.hud.set(\"coords\", \"XYZ \" + p.x.toFixed(1) + \" / \" + p.y.toFixed(1) + \" / \" + p.z.toFixed(1), 2, 24, 0xFFFF55);",
		"});",
		"ModAPI.addEventListener(\"key\", function (e) {",
		"  if (e.key === ModAPI.keys.G) {",
		"    bright = !bright;",
		"    ModAPI.setSetting(\"gamma\", bright ? 10 : 1);",
		"    ModAPI.displayToChat(\"\\u00a7eFullbright \" + (bright ? \"on\" : \"off\"));",
		"  }",
		"});",
		""
	].join("\n");

	/** "// @description ..." and "// @author ..." lines, for the Mods screen */
	function metaOf(code, key) {
		const m = new RegExp("//\\s*@" + key + "\\s+(.+)").exec(code || "");
		return m ? m[1].trim().slice(0, 300) : "";
	}

	function nameOf(code, fallback) {
		const m = /\/\/\s*@name\s+(.+)/.exec(code);
		return (m ? m[1] : fallback).trim().slice(0, 60) || "Unnamed mod";
	}

	// ---------------------------------------------------------------- the Mods screen (GuiScreenMods) asks these

	let dirty = false;      // the list changed since the page loaded: the screen offers "Apply & Reload"
	let downloading = 0;
	let status = "";

	function addMod(code, fallbackName, url) {
		if (!code || !code.trim()) {
			status = "That file is empty.";
			return false;
		}
		const mods = readMods();
		mods.push({ id: Date.now() + "-" + Math.random().toString(36).slice(2, 8), name: nameOf(code, fallbackName),
			enabled: true, code: code, url: url || null });
		if (!writeMods(mods)) {
			status = "Could not save the mod list (browser storage is full or blocked).";
			return false;
		}
		dirty = true;
		status = "Added " + nameOf(code, fallbackName) + ".";
		return true;
	}

	function manage(cmd) {
		const colon = cmd.indexOf(":");
		const verb = colon < 0 ? cmd : cmd.substring(0, colon);
		const arg = colon < 0 ? "" : cmd.substring(colon + 1);
		const running = {};
		loaded.forEach(function (m) { running[m.name] = m; });
		switch (verb) {
		case "list":
			return JSON.stringify(readMods().map(function (mod) {
				const r = running[mod.name];
				return { name: mod.name, enabled: !!mod.enabled,
					description: metaOf(mod.code, "description"), author: metaOf(mod.code, "author"),
					status: !mod.enabled ? "Off" : r ? (r.error ? "Error: " + r.error : "Running") : "Starts after reload",
					error: !!(r && r.error) };
			}));
		case "state":
			return JSON.stringify({ dirty: dirty, downloading: downloading > 0, status: status });
		case "add": {
			let o;
			try { o = JSON.parse(arg); } catch (e) { return "bad"; }
			return addMod(o.code, o.name || "Mod", null) ? "ok" : "bad";
		}
		case "addurl": {
			const url = arg.trim();
			if (!url) return "bad";
			++downloading;
			status = "Downloading " + url + " ...";
			fetch(url, { cache: "no-store" }).then(function (r) {
				if (!r.ok) throw new Error("HTTP " + r.status);
				return r.text();
			}).then(function (t) {
				addMod(t, url.split("/").pop().replace(/\.js.*$/i, ""), url);
			}).catch(function (e) {
				status = "Could not download that mod: " + e.message;
			}).then(function () {
				--downloading;
			});
			return "pending";
		}
		case "example":
			return addMod(EXAMPLE, "Example", null) ? "ok" : "bad";
		case "toggle":
		case "remove": {
			const all = readMods();
			const i = parseInt(arg, 10);
			if (!(i >= 0 && i < all.length)) return "bad";
			if (verb === "toggle") {
				all[i].enabled = !all[i].enabled;
				status = all[i].name + (all[i].enabled ? " turned on." : " turned off.");
			} else {
				status = "Removed " + all[i].name + ".";
				all.splice(i, 1);
			}
			if (!writeMods(all)) return "bad";
			dirty = true;
			return "ok";
		}
		case "reload":
			setTimeout(function () { window.location.reload(); }, 50);
			return "ok";
		default:
			return null;
		}
	}

	window.hybridMods = { handle: handle };
})();
