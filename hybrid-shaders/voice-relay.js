// hybrid: the browser half of the HybridVoice relay - voice chat carried over the game connection, so it works
// wherever the game works (Cloudflare tunnel, school networks that block WebRTC), with no port forwarding.
//
// VoiceRelay.java runs the protocol and calls tick() / incoming() every game tick through PlatformVoiceClient.
// This file does everything that needs the browser:
//  - the microphone: its own capture with the browser's noise suppression, echo cancellation and auto gain, a
//    volume, push-to-talk or voice activation (a threshold with a short hold), whispering, and a mic test;
//  - Opus with WebCodecs (20 ms frames, 48 kHz mono - the same as Simple Voice Chat's, so the server can bridge
//    the two);
//  - playback placed where each player stands: 3D audio (HRTF), a linear fade to the voice distance, muffling
//    through walls (more through wool, less through glass) and under water, an echo that grows with the cave or
//    room you are in, and a volume per player;
//  - the voice chat screen and HUD, drawn as HTML over the game.
// It never touches the network itself. Everything here is our own code and art; none of it is Simple Voice
// Chat's (All Rights Reserved), only the same ideas.
(function () {
	"use strict";
	var RATE = 48000, FRAME = 960; // 20 ms Opus frames
	var BITRATE = 24000;
	var JITTER = 0.08; // seconds of audio buffered before a speaker starts playing
	var HOLD_MS = 450; // voice activation keeps sending this long after you go quiet

	// ---- settings (this browser's; the server never sees them) ----

	var STORE = "hybridVoice.settings.v2";
	var DEF = {
		act: "ptt", thr: -45, micVol: 1, outVol: 1, denoise: true, echo: true, agc: false, device: "",
		hrtf: true, muffle: true, reverb: true, hud: "on", vols: {}
	};
	var cfg = (function () {
		var r = JSON.parse(JSON.stringify(DEF)), o = null;
		try {
			o = JSON.parse(localStorage.getItem(STORE) || "null");
		} catch (e) {}
		if (o && typeof o === "object") {
			for (var k in DEF) if (k in o) r[k] = o[k];
			r.fresh = false;
		} else {
			r.fresh = true;
		}
		return r;
	})();

	function save() {
		var o = {};
		for (var k in DEF) o[k] = cfg[k];
		try {
			localStorage.setItem(STORE, JSON.stringify(o));
		} catch (e) {}
	}

	// ---- state ----

	var support = null;
	var G = { on: false, mode: "off", deaf: false, mute: false, ptt: false, wh: false, ui: false, hud: false, icons: true,
		dist: 48, wdist: 24, v2: false, players: [], groups: [], group: "", muted: [], keys: {}, p: {}, me: null,
		env: [0, 0.6, 0] };
	var ctx = null, master = null, uwFilter = null, outMeter = null, revBus = null, revConv = null, revOut = null;
	var revDecay = 0, revMadeAt = 0;
	var mic = { stream: null, state: "off", key: "", pending: false, retryAt: 0, src: null, hp: null, gain: null,
		an: null, node: null, sink: null, test: null, testOn: false, level: -127, peak: -127 };
	var encoder = null, encTime = 0;
	var outQueue = [];
	var talking = false, whispering = false, tail = 0, holdUntil = 0;
	var muted = {};
	var speakers = {};
	var cmds = [];
	var stats = { sent: 0, recv: 0, played: 0, errors: 0, lastError: "" };

	function fail(where, e) {
		stats.errors++;
		stats.lastError = where + ": " + (e && e.message ? e.message : e);
	}

	(function probe() {
		if (typeof AudioEncoder === "undefined" || typeof AudioDecoder === "undefined" ||
				typeof AudioWorkletNode === "undefined") {
			support = false;
			return;
		}
		Promise.all([
			AudioEncoder.isConfigSupported({ codec: "opus", sampleRate: RATE, numberOfChannels: 1, bitrate: BITRATE }),
			AudioDecoder.isConfigSupported({ codec: "opus", sampleRate: RATE, numberOfChannels: 1 })
		]).then(function (r) {
			support = !!(r[0].supported && r[1].supported);
		}, function () {
			support = false;
		});
	})();

	function audio() {
		if (!ctx) {
			ctx = new AudioContext({ sampleRate: RATE, latencyHint: "interactive" });
			master = ctx.createGain();
			// everything heard goes through this: it closes down under water
			uwFilter = ctx.createBiquadFilter();
			uwFilter.type = "lowpass";
			uwFilter.frequency.value = 20000;
			uwFilter.Q.value = 0.5;
			outMeter = ctx.createAnalyser();
			master.connect(uwFilter);
			uwFilter.connect(outMeter);
			outMeter.connect(ctx.destination);
			// the shared echo: every speaker sends a little into it, more the more closed in you are
			revBus = ctx.createGain();
			revConv = ctx.createConvolver();
			revOut = ctx.createGain();
			revOut.gain.value = 0;
			revBus.connect(revConv);
			revConv.connect(revOut);
			revOut.connect(uwFilter);
			setReverb(0.8);
			// browsers start audio suspended until the page is clicked or a key is pressed
			var resume = function () {
				if (ctx.state === "suspended") ctx.resume();
			};
			["pointerdown", "keydown", "touchstart"].forEach(function (t) {
				window.addEventListener(t, resume, true);
			});
		}
		if (ctx.state === "suspended") ctx.resume().catch(function () {});
		return ctx;
	}

	/** a fresh impulse response: decaying noise, a few early reflections, darker as it fades */
	function setReverb(decay) {
		var now = performance.now();
		if (revConv.buffer && (Math.abs(decay - revDecay) < revDecay * 0.2 || now - revMadeAt < 2000)) return;
		revDecay = decay;
		revMadeAt = now;
		var len = Math.max(1, Math.floor(RATE * decay)), buf = ctx.createBuffer(2, len, RATE);
		for (var c = 0; c < 2; c++) {
			var d = buf.getChannelData(c), lp = 0;
			for (var i = 0; i < len; i++) {
				var t = i / len, env = Math.pow(1 - t, 2.4);
				// low-pass the tail more and more: stone rooms lose their highs first
				var k = 0.55 + 0.4 * t;
				lp = lp * k + (Math.random() * 2 - 1) * (1 - k);
				d[i] = lp * env * 2.2;
			}
			for (var r = 0; r < 5; r++) {
				var at = Math.floor(RATE * (0.012 + Math.random() * 0.05 * decay));
				if (at < len) d[at] += (Math.random() < 0.5 ? -1 : 1) * (0.5 - r * 0.08);
			}
		}
		revConv.buffer = buf;
	}

	// ---- microphone -> Opus ----

	var WORKLET = "class C extends AudioWorkletProcessor{constructor(){super();this.b=new Float32Array(" + FRAME +
		");this.n=0}process(i){var c=i[0]&&i[0][0];if(c){for(var k=0;k<c.length;k++){this.b[this.n++]=c[k];if(this.n===" +
		FRAME + "){this.port.postMessage(this.b,[this.b.buffer]);this.b=new Float32Array(" + FRAME +
		");this.n=0}}}return true}}registerProcessor('hybrid-voice-capture',C);";
	var workletLoaded = null;

	function micKey() {
		return [cfg.device, cfg.denoise, cfg.echo, cfg.agc].join("|");
	}

	/** the microphone is open while voice is on, or while the settings are open (for the meter and the test) */
	function wantMic() {
		return (G.on && !G.deaf) || (G.ui && UI.tab === "settings");
	}

	function openMic() {
		if (mic.pending || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
			if (!mic.pending) mic.state = "none";
			return;
		}
		if (performance.now() < mic.retryAt) return;
		mic.pending = true;
		var key = micKey();
		var c = audio();
		var want = { echoCancellation: cfg.echo, noiseSuppression: cfg.denoise, autoGainControl: cfg.agc, channelCount: 1 };
		if (cfg.device) want.deviceId = { exact: cfg.device };
		navigator.mediaDevices.getUserMedia({ audio: want }).then(function (stream) {
			mic.pending = false;
			closeMic();
			mic.stream = stream;
			mic.key = key;
			mic.state = "ok";
			if (!workletLoaded) {
				workletLoaded = c.audioWorklet.addModule(URL.createObjectURL(new Blob([WORKLET], { type: "application/javascript" })));
			}
			workletLoaded.then(function () {
				if (mic.stream !== stream) return;
				mic.src = c.createMediaStreamSource(stream);
				// rumble and handling noise never help a voice
				mic.hp = c.createBiquadFilter();
				mic.hp.type = "highpass";
				mic.hp.frequency.value = 85;
				mic.gain = c.createGain();
				mic.gain.gain.value = cfg.micVol;
				mic.an = c.createAnalyser();
				mic.an.fftSize = 1024;
				mic.node = new AudioWorkletNode(c, "hybrid-voice-capture", { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1] });
				mic.node.port.onmessage = function (ev) {
					onFrame(ev.data);
				};
				// a silent path to the speakers so the browser keeps pulling audio through the worklet
				mic.sink = c.createGain();
				mic.sink.gain.value = 0;
				// the mic test: you, straight back to your own speakers
				mic.test = c.createGain();
				mic.test.gain.value = mic.testOn ? 1 : 0;
				mic.src.connect(mic.hp);
				mic.hp.connect(mic.gain);
				mic.gain.connect(mic.an);
				mic.gain.connect(mic.node);
				mic.gain.connect(mic.test);
				mic.test.connect(c.destination);
				mic.node.connect(mic.sink);
				mic.sink.connect(c.destination);
			}, function (e) {
				fail("worklet", e);
			});
			listDevices();
		}, function (e) {
			mic.pending = false;
			mic.state = e && (e.name === "NotAllowedError" || e.name === "SecurityError") ? "denied" : "none";
			mic.retryAt = performance.now() + (mic.state === "denied" ? 15000 : 5000);
			if (cfg.device && e && e.name === "OverconstrainedError") {
				cfg.device = ""; // that microphone is gone: the default one then
				save();
				mic.retryAt = 0;
			}
			fail("microphone", e);
		});
	}

	function closeMic() {
		try {
			if (mic.src) mic.src.disconnect();
			if (mic.hp) mic.hp.disconnect();
			if (mic.gain) mic.gain.disconnect();
			if (mic.node) {
				mic.node.port.onmessage = null;
				mic.node.disconnect();
			}
			if (mic.sink) mic.sink.disconnect();
			if (mic.test) mic.test.disconnect();
			if (mic.stream) mic.stream.getTracks().forEach(function (t) { t.stop(); });
		} catch (e) {}
		mic.stream = mic.src = mic.hp = mic.gain = mic.an = mic.node = mic.sink = mic.test = null;
		mic.level = mic.peak = -127;
		if (mic.state === "ok") mic.state = "off";
	}

	function makeEncoder() {
		encoder = new AudioEncoder({
			output: function (chunk) {
				var b = new Uint8Array(chunk.byteLength);
				chunk.copyTo(b);
				if (outQueue.length < 50) outQueue.push(b);
			},
			error: function (e) {
				fail("encoder", e);
				encoder = null;
			}
		});
		encoder.configure({
			codec: "opus", sampleRate: RATE, numberOfChannels: 1, bitrate: BITRATE,
			opus: { frameDuration: 20000, useinbandfec: true }
		});
	}

	function onFrame(pcm) {
		var sum = 0;
		for (var i = 0; i < pcm.length; i++) sum += pcm[i] * pcm[i];
		var rms = Math.sqrt(sum / pcm.length);
		var db = rms > 1e-7 ? Math.max(-127, 20 * Math.log10(rms)) : -127;
		mic.level = db;
		mic.peak = Math.max(db, mic.peak - 1.5);
		var now = performance.now();
		var may = G.on && !G.deaf && !G.mute && !mic.testOn;
		var open;
		if (cfg.act === "voice") {
			if (db >= cfg.thr) holdUntil = now + HOLD_MS;
			open = now < holdUntil;
		} else {
			open = G.ptt;
		}
		talking = may && open;
		whispering = talking && G.wh;
		// a few frames after you stop are still sent so the last word is not cut off
		if (talking) tail = 3;
		else if (tail > 0) tail--;
		else return;
		if (!G.on || G.deaf) return;
		try {
			if (!encoder || encoder.state === "closed") makeEncoder();
			var data = new AudioData({ format: "f32-planar", sampleRate: RATE, numberOfFrames: pcm.length, numberOfChannels: 1, timestamp: encTime, data: pcm });
			encTime += 20000;
			encoder.encode(data);
			data.close();
		} catch (e) {
			fail("encode", e);
			encoder = null;
		}
	}

	// ---- Opus -> speakers ----

	function speaker(id) {
		var s = speakers[id];
		if (s) return s;
		var c = audio();
		s = { id: id, next: 0, last: 0, ts: 0, flags: 0, occ: -1, uw: -1 };
		s.vol = c.createGain(); // this player's own volume
		// behind walls: a low-pass filter and a little quieter, like hearing someone through a door
		s.filter = c.createBiquadFilter();
		s.filter.type = "lowpass";
		s.filter.frequency.value = 20000;
		s.filter.Q.value = 0.7;
		s.muffle = c.createGain();
		s.panner = c.createPanner();
		s.panner.panningModel = cfg.hrtf ? "HRTF" : "equalpower";
		s.panner.distanceModel = "linear"; // fades out evenly to silence at the voice distance
		s.panner.refDistance = 1;
		s.panner.maxDistance = G.dist;
		s.panner.rolloffFactor = 1;
		s.send = c.createGain();
		s.send.gain.value = 0;
		s.vol.connect(s.filter);
		s.filter.connect(s.muffle);
		s.muffle.connect(s.panner);
		s.panner.connect(master);
		s.panner.connect(s.send);
		s.send.connect(revBus);
		s.dec = new AudioDecoder({
			output: function (ad) {
				play(s, ad);
			},
			error: function (e) {
				fail("decoder", e);
				s.dec = null;
			}
		});
		s.dec.configure({ codec: "opus", sampleRate: RATE, numberOfChannels: 1 });
		speakers[id] = s;
		return s;
	}

	function play(s, ad) {
		try {
			var n = ad.numberOfFrames;
			var pcm = new Float32Array(n);
			ad.copyTo(pcm, { planeIndex: 0 });
			var buf = ctx.createBuffer(1, n, ad.sampleRate);
			buf.copyToChannel(pcm, 0);
			var now = ctx.currentTime;
			// start (or restart after a gap) a little ahead to absorb network jitter; drop a backlog after a stall
			if (s.next < now + 0.01 || s.next > now + 0.5) s.next = now + JITTER;
			var src = ctx.createBufferSource();
			src.buffer = buf;
			src.connect(s.vol);
			src.start(s.next);
			s.next += buf.duration;
			stats.played++;
		} catch (e) {
			fail("play", e);
		} finally {
			ad.close();
		}
	}

	function b64decode(str) {
		var bin = atob(str), out = new Uint8Array(bin.length);
		for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
		return out;
	}

	function b64encode(bytes) {
		var bin = "";
		for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
		return btoa(bin);
	}

	function setPos(p, x, y, z) {
		if (p.positionX) {
			p.positionX.value = x;
			p.positionY.value = y;
			p.positionZ.value = z;
		} else {
			p.setPosition(x, y, z);
		}
	}

	/** how much is between you and a speaker (0-4 blocks' worth) -> [low-pass cutoff Hz, volume] */
	function muffleOf(occ) {
		if (occ <= 0.05) return [20000, 1];
		return [Math.max(320, 16000 / (1 + 10 * Math.pow(occ, 1.2))), 1 / (1 + 0.28 * occ)];
	}

	function setListener(me) {
		var L = ctx.listener;
		var yaw = me[3] * Math.PI / 180, pitch = me[4] * Math.PI / 180;
		// Minecraft: yaw 0 looks toward +Z and turns clockwise, pitch + looks down
		var fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
		var ux = -Math.sin(yaw) * Math.sin(pitch), uy = Math.cos(pitch), uz = Math.cos(yaw) * Math.sin(pitch);
		if (L.positionX) {
			L.positionX.value = me[0]; L.positionY.value = me[1]; L.positionZ.value = me[2];
			L.forwardX.value = fx; L.forwardY.value = fy; L.forwardZ.value = fz;
			L.upX.value = ux; L.upY.value = uy; L.upZ.value = uz;
		} else {
			L.setPosition(me[0], me[1], me[2]);
			L.setOrientation(fx, fy, fz, ux, uy, uz);
		}
	}

	function updateSpeakers() {
		var t = ctx.currentTime, me = G.me, env = G.env || [0, 0.6, 0];
		master.gain.setTargetAtTime(G.deaf ? 0 : cfg.outVol, t, 0.03);
		uwFilter.frequency.setTargetAtTime(cfg.muffle && env[2] ? 650 : 20000, t, 0.08);
		if (cfg.reverb && env[0] > 0.02) {
			setReverb(env[1]);
			revOut.gain.setTargetAtTime(1, t, 0.2);
		} else {
			revOut.gain.setTargetAtTime(0, t, 0.2);
		}
		if (me) setListener(me);
		var now = performance.now();
		for (var id in speakers) {
			var s = speakers[id], p = G.p[id];
			var whisper = (s.flags & 1) && now - s.last < 1000;
			var max = whisper ? G.wdist : G.dist;
			s.panner.maxDistance = Math.max(1, max);
			var want = cfg.hrtf ? "HRTF" : "equalpower";
			if (s.panner.panningModel !== want) s.panner.panningModel = want;
			var d = p && me ? Math.hypot(p[0] - me[0], p[1] - me[1], p[2] - me[2]) : Infinity;
			// through a group or Global, or not in sight: from where you stand, no direction, full volume
			var far = !p || ((s.flags & 2) && d > max);
			if (far && me) setPos(s.panner, me[0], me[1], me[2]);
			else if (p) setPos(s.panner, p[0], p[1], p[2]);
			var occ = far || !cfg.muffle ? 0 : (p[3] || 0) + (p[4] ? 2 : 0);
			if (Math.abs(occ - s.occ) > 0.05) {
				s.occ = occ;
				var m = muffleOf(occ);
				// glide over ~0.1 s so walking past a doorway does not click
				s.filter.frequency.setTargetAtTime(m[0], t, 0.05);
				s.muffle.gain.setTargetAtTime(m[1], t, 0.05);
			}
			var v = cfg.vols[id];
			s.vol.gain.setTargetAtTime(typeof v === "number" ? v : 1, t, 0.03);
			s.send.gain.setTargetAtTime(cfg.reverb && !far ? Math.min(0.9, env[0]) : 0, t, 0.2);
			if (now - s.last > 60000) {
				try { if (s.dec) s.dec.close(); s.panner.disconnect(); s.send.disconnect(); } catch (e) {}
				delete speakers[id];
			}
		}
	}

	function speaking(id) {
		var s = speakers[id];
		return !!(s && performance.now() - s.last < 300 && !muted[id]);
	}

	// ---- the screen and the HUD ----

	var ICONS = {
		mic: '<rect x="9" y="2.5" width="6" height="11.5" rx="3"/><path d="M5.5 11a6.5 6.5 0 0 0 13 0"/><path d="M12 17.5V21"/><path d="M8.5 21h7"/>',
		micOff: '<rect x="9" y="2.5" width="6" height="11.5" rx="3"/><path d="M5.5 11a6.5 6.5 0 0 0 13 0"/><path d="M12 17.5V21"/><path d="M8.5 21h7"/><path d="M3.5 3.5l17 17"/>',
		head: '<path d="M4 16v-3.5a8 8 0 0 1 16 0V16"/><rect x="3" y="14.5" width="4.5" height="6.5" rx="2"/><rect x="16.5" y="14.5" width="4.5" height="6.5" rx="2"/>',
		headOff: '<path d="M4 16v-3.5a8 8 0 0 1 16 0V16"/><rect x="3" y="14.5" width="4.5" height="6.5" rx="2"/><rect x="16.5" y="14.5" width="4.5" height="6.5" rx="2"/><path d="M3.5 3.5l17 17"/>',
		users: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20.5c0-3.6 2.9-6.5 6.5-6.5s6.5 2.9 6.5 6.5"/><circle cx="17.5" cy="9" r="2.5"/><path d="M17 14.1c2.7.3 4.6 2.6 4.6 5.4"/>',
		group: '<circle cx="12" cy="7.5" r="3"/><circle cx="5" cy="10" r="2.2"/><circle cx="19" cy="10" r="2.2"/><path d="M6.5 20c0-3 2.5-5.5 5.5-5.5s5.5 2.5 5.5 5.5"/><path d="M1.5 18.5c0-2.2 1.6-4 3.6-4"/><path d="M22.5 18.5c0-2.2-1.6-4-3.6-4"/>',
		sliders: '<path d="M4 7h9"/><path d="M19 7h1"/><circle cx="16" cy="7" r="2.5"/><path d="M4 17h3"/><path d="M13 17h7"/><circle cx="10" cy="17" r="2.5"/>',
		x: '<path d="M6 6l12 12M18 6L6 18"/>',
		lock: '<rect x="5" y="11" width="14" height="9.5" rx="2.5"/><path d="M8.5 11V8a3.5 3.5 0 0 1 7 0v3"/>',
		vol: '<path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z"/><path d="M15.5 9a4 4 0 0 1 0 6"/><path d="M18 6.5a7.5 7.5 0 0 1 0 11"/>',
		volOff: '<path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z"/><path d="M16 9.5l5 5M21 9.5l-5 5"/>',
		globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18"/><path d="M12 3c2.6 2.6 3.9 5.6 3.9 9s-1.3 6.4-3.9 9c-2.6-2.6-3.9-5.6-3.9-9s1.3-6.4 3.9-9z"/>',
		near: '<circle cx="12" cy="12" r="2"/><path d="M7.8 7.8a6 6 0 0 0 0 8.4M16.2 7.8a6 6 0 0 1 0 8.4"/><path d="M5 5a10 10 0 0 0 0 14M19 5a10 10 0 0 1 0 14"/>',
		power: '<path d="M12 3v8"/><path d="M6.3 6.8a8 8 0 1 0 11.4 0"/>',
		eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/>',
		eyeOff: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/><path d="M3.5 3.5l17 17"/>',
		plus: '<path d="M12 5v14M5 12h14"/>',
		leave: '<path d="M14 4h4a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-4"/><path d="M9 16l-4-4 4-4"/><path d="M5 12h10"/>',
		whisper: '<path d="M4 5.5h16v10H10.5L6 19.5v-4H4z"/><path d="M8.5 10.5h.01M12 10.5h.01M15.5 10.5h.01"/>',
		check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
		alert: '<path d="M12 3.5l9.5 16.5h-19z"/><path d="M12 10v4.5"/><path d="M12 17.5h.01"/>'
	};

	function icon(name, size) {
		return '<svg viewBox="0 0 24 24" width="' + (size || 18) + '" height="' + (size || 18) +
			'" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
			ICONS[name] + "</svg>";
	}

	function esc(s) {
		return String(s).replace(/[&<>"']/g, function (c) {
			return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
		});
	}

	function hue(name) {
		var h = 0;
		for (var i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 360;
		return h;
	}

	function avatar(name, cls) {
		var h = hue(name);
		return '<div class="hv-av ' + (cls || "") + '" style="background:linear-gradient(135deg,hsl(' + h + ',68%,58%),hsl(' +
			((h + 38) % 360) + ',66%,44%))">' + esc((name || "?").charAt(0).toUpperCase()) + "</div>";
	}

	var GROUP_TYPES = [
		["Normal", "Members hear each other anywhere, and nearby players. Outsiders can't hear the group."],
		["Open", "Members hear each other anywhere, and nearby players hear the group too."],
		["Isolated", "Members only hear each other. Nobody outside the group can hear it."]
	];

	var CSS = [
		"#hv-root{position:fixed;inset:0;pointer-events:none;z-index:2147483000;font-family:Inter,'Segoe UI',Roboto,'Helvetica Neue',system-ui,-apple-system,sans-serif;color:#e8ecf4;-webkit-font-smoothing:antialiased;font-size:14px;line-height:1.35;user-select:none;-webkit-user-select:none}",
		"#hv-root *{box-sizing:border-box}",
		"#hv-root svg{display:block;flex:none}",
		".hv-scrim{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;background:radial-gradient(ellipse at center,rgba(8,10,18,.35),rgba(4,5,10,.62));opacity:0;visibility:hidden;transition:opacity .18s ease,visibility 0s linear .18s}",
		".hv-scrim.on{opacity:1;visibility:visible;pointer-events:auto;transition:opacity .18s ease}",
		".hv-panel{width:min(700px,calc(100vw - 24px));height:min(560px,calc(100vh - 24px));display:flex;flex-direction:column;background:linear-gradient(180deg,rgba(32,36,50,.94),rgba(19,22,31,.96));border:1px solid rgba(255,255,255,.09);border-radius:20px;box-shadow:0 30px 80px rgba(0,0,0,.6),inset 0 1px 0 rgba(255,255,255,.06);backdrop-filter:blur(20px) saturate(1.25);-webkit-backdrop-filter:blur(20px) saturate(1.25);transform:translateY(10px) scale(.985);transition:transform .22s cubic-bezier(.2,.8,.2,1);overflow:hidden}",
		".hv-scrim.on .hv-panel{transform:none}",
		".hv-head{display:flex;align-items:center;gap:12px;padding:18px 18px 14px}",
		".hv-logo{width:40px;height:40px;border-radius:12px;display:grid;place-items:center;color:#fff;background:linear-gradient(135deg,#6e9bff,#7c5cff);box-shadow:0 6px 18px rgba(110,130,255,.35)}",
		".hv-title{font-size:17px;font-weight:700;letter-spacing:.1px}",
		".hv-sub{font-size:12px;color:#939cb2;display:flex;align-items:center;gap:6px;margin-top:2px}",
		".hv-dot{width:8px;height:8px;border-radius:50%;background:#6b7385}",
		".hv-dot.ok{background:#3ddc97;box-shadow:0 0 0 3px rgba(61,220,151,.18)}",
		".hv-dot.bad{background:#ff5c69;box-shadow:0 0 0 3px rgba(255,92,105,.18)}",
		".hv-sp{flex:1}",
		".hv-seg{display:inline-flex;background:rgba(255,255,255,.05);border:1px solid rgba(255,255,255,.07);border-radius:12px;padding:3px;gap:2px}",
		".hv-seg button{all:unset;cursor:pointer;padding:7px 12px;border-radius:9px;font-size:13px;font-weight:600;color:#a8b1c5;display:flex;align-items:center;gap:6px;transition:background .15s,color .15s}",
		".hv-seg button:hover{color:#fff}",
		".hv-seg button.sel{background:#5b8def;color:#fff;box-shadow:0 2px 10px rgba(91,141,239,.4)}",
		".hv-seg.off button.sel{background:#3a4152;box-shadow:none}",
		".hv-ib{all:unset;cursor:pointer;width:34px;height:34px;border-radius:10px;display:grid;place-items:center;color:#a8b1c5;transition:background .15s,color .15s;flex:none}",
		".hv-ib:hover{background:rgba(255,255,255,.08);color:#fff}",
		".hv-ib.red{color:#ff7a85;background:rgba(255,92,105,.13)}",
		".hv-quick{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;padding:0 18px 14px}",
		".hv-q{all:unset;cursor:pointer;display:flex;align-items:center;gap:11px;padding:10px 12px;border-radius:14px;background:rgba(255,255,255,.04);border:1px solid rgba(255,255,255,.07);transition:background .15s,border-color .15s;min-width:0}",
		".hv-q:hover{background:rgba(255,255,255,.07)}",
		".hv-q .ic{width:36px;height:36px;border-radius:11px;display:grid;place-items:center;background:rgba(91,141,239,.16);color:#9db8ff;flex:none;transition:background .15s,color .15s}",
		".hv-q.bad .ic{background:rgba(255,92,105,.16);color:#ff7a85}",
		".hv-q.bad{border-color:rgba(255,92,105,.25)}",
		".hv-q b{display:block;font-size:13px;font-weight:650}",
		".hv-q span{display:block;font-size:11.5px;color:#8f98ad;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
		".hv-q>div{min-width:0}",
		".hv-tabs{display:flex;gap:2px;padding:0 14px;border-bottom:1px solid rgba(255,255,255,.07)}",
		".hv-tab{all:unset;cursor:pointer;padding:10px 12px 11px;font-size:13.5px;font-weight:600;color:#97a0b5;border-bottom:2px solid transparent;margin-bottom:-1px;display:flex;gap:7px;align-items:center;transition:color .15s,border-color .15s}",
		".hv-tab:hover{color:#dfe5f1}",
		".hv-tab.sel{color:#fff;border-color:#6e9bff}",
		".hv-count{font-size:11px;font-weight:700;padding:1px 7px;border-radius:999px;background:rgba(255,255,255,.08);color:#c3cbdc}",
		".hv-body{flex:1;overflow:auto;padding:12px 18px 18px;scrollbar-width:thin;scrollbar-color:rgba(255,255,255,.18) transparent}",
		".hv-body::-webkit-scrollbar{width:8px}.hv-body::-webkit-scrollbar-thumb{background:rgba(255,255,255,.16);border-radius:8px}",
		".hv-body section{display:none}.hv-body section.sel{display:block;animation:hvin .18s ease}",
		"@keyframes hvin{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:none}}",
		".hv-row{display:flex;align-items:center;gap:12px;padding:9px 10px;border-radius:13px;transition:background .15s}",
		".hv-row:hover{background:rgba(255,255,255,.04)}",
		".hv-av{width:38px;height:38px;border-radius:50%;display:grid;place-items:center;font-weight:750;font-size:15px;color:#fff;position:relative;flex:none;text-shadow:0 1px 2px rgba(0,0,0,.25)}",
		".hv-av::after{content:'';position:absolute;inset:-4px;border-radius:50%;border:2.5px solid #3ddc97;opacity:0;transform:scale(.88);transition:opacity .12s,transform .15s}",
		".talk>.hv-av::after,.hv-av.talk::after{opacity:1;transform:none}",
		".hv-av.sm{width:26px;height:26px;font-size:12px}",
		".hv-av.sm::after{inset:-3px;border-width:2px}",
		".hv-pi{flex:1;min-width:0}",
		".hv-name{font-weight:650;font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
		".hv-meta{font-size:11.5px;color:#8f98ad;display:flex;gap:6px;align-items:center;margin-top:2px;flex-wrap:wrap}",
		".hv-badge{font-size:10.5px;padding:2px 8px;border-radius:999px;background:rgba(255,255,255,.08);color:#bcc5d8;font-weight:700;letter-spacing:.2px;display:inline-flex;gap:4px;align-items:center}",
		".hv-badge.java{background:rgba(255,166,77,.16);color:#ffb870}",
		".hv-badge.grp{background:rgba(110,155,255,.17);color:#a9c2ff}",
		".hv-badge.glb{background:rgba(61,220,151,.14);color:#73e6b2}",
		".hv-badge.red{background:rgba(255,92,105,.15);color:#ff8a93}",
		".hv-badge.wh{background:rgba(167,139,250,.18);color:#c5b5ff}",
		".hv-vol{display:flex;align-items:center;gap:8px}",
		".hv-vol .pct{width:40px;text-align:right;font-size:12px;color:#aab3c6;font-variant-numeric:tabular-nums}",
		"input.hv-range{-webkit-appearance:none;appearance:none;width:130px;height:6px;border-radius:999px;background:linear-gradient(to right,#6e9bff var(--p,50%),rgba(255,255,255,.13) var(--p,50%));outline:none;cursor:pointer;margin:0}",
		"input.hv-range.wide{width:100%}",
		"input.hv-range::-webkit-slider-thumb{-webkit-appearance:none;width:16px;height:16px;border-radius:50%;background:#fff;box-shadow:0 1px 5px rgba(0,0,0,.45);border:none;transition:transform .12s}",
		"input.hv-range:active::-webkit-slider-thumb{transform:scale(1.15)}",
		"input.hv-range::-moz-range-thumb{width:16px;height:16px;border-radius:50%;background:#fff;box-shadow:0 1px 5px rgba(0,0,0,.45);border:none}",
		".hv-sw{all:unset;position:relative;width:40px;height:23px;border-radius:999px;background:rgba(255,255,255,.15);transition:background .18s;cursor:pointer;flex:none}",
		".hv-sw::after{content:'';position:absolute;top:3px;left:3px;width:17px;height:17px;border-radius:50%;background:#fff;transition:transform .2s cubic-bezier(.2,.8,.2,1);box-shadow:0 1px 3px rgba(0,0,0,.4)}",
		".hv-sw.on{background:#5b8def}.hv-sw.on::after{transform:translateX(17px)}",
		".hv-sec{font-size:11px;font-weight:750;letter-spacing:1px;text-transform:uppercase;color:#7c87a0;margin:16px 4px 6px}",
		".hv-sec:first-child{margin-top:4px}",
		".hv-card{background:rgba(255,255,255,.035);border:1px solid rgba(255,255,255,.07);border-radius:15px;padding:4px 14px}",
		".hv-set{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:12px 0;border-bottom:1px solid rgba(255,255,255,.055)}",
		".hv-set:last-child{border-bottom:none}",
		".hv-set .t{min-width:0}",
		".hv-set .t b{font-weight:650;font-size:13.5px;display:block}",
		".hv-set .t span{font-size:11.5px;color:#8f98ad;display:block;margin-top:2px}",
		".hv-set.col{flex-direction:column;align-items:stretch;gap:10px}",
		".hv-meter{position:relative;height:12px;border-radius:999px;background:rgba(255,255,255,.08);overflow:visible}",
		".hv-meter i{position:absolute;left:0;top:0;bottom:0;width:100%;border-radius:999px;background:linear-gradient(90deg,#3ddc97,#b6ef6a 70%,#ffcf5c 92%,#ff6b6b);clip-path:inset(0 100% 0 0 round 999px)}",
		".hv-meter i.dim{filter:saturate(.25) brightness(.8)}",
		".hv-meter .thr{position:absolute;top:-4px;bottom:-4px;width:3px;margin-left:-1.5px;background:#fff;border-radius:3px;box-shadow:0 0 0 2px rgba(0,0,0,.3)}",
		".hv-in,.hv-sel{all:unset;box-sizing:border-box;background:rgba(0,0,0,.28);border:1px solid rgba(255,255,255,.11);border-radius:11px;padding:9px 12px;font-size:13.5px;color:#fff;width:100%;user-select:text;-webkit-user-select:text;transition:border-color .15s,box-shadow .15s}",
		".hv-in::placeholder{color:#6f788c}",
		".hv-in:focus,.hv-sel:focus{border-color:#6e9bff;box-shadow:0 0 0 3px rgba(110,155,255,.25)}",
		".hv-sel{cursor:pointer;max-width:260px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}",
		".hv-sel option{background:#1d2130;color:#fff}",
		".hv-btn{all:unset;cursor:pointer;padding:9px 15px;border-radius:11px;background:linear-gradient(180deg,#6e9bff,#5b84ea);color:#fff;font-weight:650;font-size:13px;display:inline-flex;align-items:center;gap:7px;box-shadow:0 3px 12px rgba(91,141,239,.35);transition:filter .15s,transform .1s;white-space:nowrap}",
		".hv-btn:hover{filter:brightness(1.08)}.hv-btn:active{transform:translateY(1px)}",
		".hv-btn.ghost{background:rgba(255,255,255,.08);box-shadow:none;color:#dbe2f0}",
		".hv-btn.danger{background:rgba(255,92,105,.16);color:#ff8a93;box-shadow:none}",
		".hv-btn.on{background:linear-gradient(180deg,#3ddc97,#2fbf80);box-shadow:0 3px 12px rgba(61,220,151,.3)}",
		".hv-empty{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px;color:#8f98ad;padding:46px 10px;text-align:center}",
		".hv-empty .big{width:54px;height:54px;border-radius:16px;display:grid;place-items:center;background:rgba(255,255,255,.05);color:#aab3c6}",
		".hv-empty b{color:#dfe5f1;font-size:14.5px}",
		".hv-note{display:flex;gap:10px;align-items:flex-start;padding:10px 12px;border-radius:12px;background:rgba(255,207,92,.09);color:#ffd88a;font-size:12.5px;margin-bottom:10px}",
		".hv-note.ok{background:rgba(61,220,151,.1);color:#86ecbd}.hv-note.bad{background:rgba(255,92,105,.11);color:#ff9aa2}",
		".hv-mine{display:flex;align-items:center;gap:14px;padding:14px;border-radius:16px;background:linear-gradient(135deg,rgba(110,155,255,.16),rgba(124,92,255,.12));border:1px solid rgba(140,160,255,.22);margin-bottom:6px}",
		".hv-gic{width:44px;height:44px;border-radius:13px;display:grid;place-items:center;background:rgba(255,255,255,.1);color:#fff;flex:none}",
		".hv-gname{font-size:16px;font-weight:750}",
		".hv-members{display:flex;flex-wrap:wrap;gap:8px;padding:8px 2px 2px}",
		".hv-member{display:flex;align-items:center;gap:8px;padding:5px 11px 5px 5px;border-radius:999px;background:rgba(255,255,255,.05);font-size:12.5px;font-weight:600}",
		".hv-form{display:grid;grid-template-columns:1fr 1fr;gap:10px;padding:12px 0}",
		".hv-form .full{grid-column:1/-1}",
		".hv-tdesc{font-size:12px;color:#97a0b5;margin-top:-2px}",
		".hv-keys{display:flex;flex-wrap:wrap;gap:8px;padding:12px 0}",
		".hv-key{display:flex;align-items:center;gap:7px;font-size:12px;color:#aab3c6}",
		".hv-key kbd{font-family:inherit;font-size:11.5px;font-weight:700;padding:3px 8px;border-radius:7px;background:rgba(255,255,255,.09);border:1px solid rgba(255,255,255,.12);border-bottom-width:2px;color:#fff}",
		".hv-hud{position:absolute;left:14px;bottom:14px;display:flex;flex-direction:column;gap:8px;align-items:flex-start;transition:opacity .2s}",
		".hv-spk{position:absolute;right:14px;bottom:72px;display:flex;flex-direction:column;gap:7px;align-items:flex-end}",
		".hv-ghud{position:absolute;left:14px;top:max(76px,14vh);display:flex;flex-direction:column;gap:6px;padding:10px 12px;border-radius:15px;background:rgba(14,16,24,.58);backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);border:1px solid rgba(255,255,255,.08);min-width:150px}",
		".hv-ghud .gt{display:flex;align-items:center;gap:7px;font-size:12px;font-weight:750;color:#c8d3ff;margin-bottom:2px}",
		".hv-ghud .gm{display:flex;align-items:center;gap:8px;font-size:12.5px;font-weight:600}",
		".hv-pill{display:flex;align-items:center;gap:9px;padding:6px 13px 6px 6px;border-radius:999px;background:rgba(14,16,24,.62);backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);border:1px solid rgba(255,255,255,.09);font-size:12.5px;font-weight:650;box-shadow:0 8px 22px rgba(0,0,0,.32);animation:hvpop .18s cubic-bezier(.2,.8,.2,1)}",
		"@keyframes hvpop{from{opacity:0;transform:translateY(6px) scale(.96)}to{opacity:1;transform:none}}",
		".hv-pill .ic{width:26px;height:26px;border-radius:50%;display:grid;place-items:center;background:rgba(61,220,151,.2);color:#3ddc97;flex:none}",
		".hv-pill.talk .ic{animation:hvpulse 1.2s ease-in-out infinite}",
		"@keyframes hvpulse{0%,100%{box-shadow:0 0 0 0 rgba(61,220,151,.5)}50%{box-shadow:0 0 0 7px rgba(61,220,151,0)}}",
		".hv-pill.red .ic{background:rgba(255,92,105,.2);color:#ff7a85}",
		".hv-pill.wh .ic{background:rgba(167,139,250,.24);color:#c5b5ff}",
		".hv-pill.wh.talk .ic{animation:hvpulsew 1.2s ease-in-out infinite}",
		"@keyframes hvpulsew{0%,100%{box-shadow:0 0 0 0 rgba(167,139,250,.5)}50%{box-shadow:0 0 0 7px rgba(167,139,250,0)}}",
		".hv-bars{display:flex;gap:2.5px;align-items:center;height:14px;color:#3ddc97}",
		".hv-pill.wh .hv-bars{color:#c5b5ff}",
		".hv-bars i{width:3px;border-radius:2px;background:currentColor;height:25%;transition:height .07s}",
		".hv-sp-row{display:flex;align-items:center;gap:8px;padding:4px 12px 4px 4px;border-radius:999px;background:rgba(14,16,24,.58);backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);border:1px solid rgba(255,255,255,.08);font-size:12.5px;font-weight:650;animation:hvpop .18s cubic-bezier(.2,.8,.2,1)}",
		"@media (max-width:620px){.hv-quick{grid-template-columns:1fr}.hv-head{flex-wrap:wrap}.hv-form{grid-template-columns:1fr}input.hv-range{width:90px}}"
	].join("\n");

	var UI = { built: false, tab: "players", root: null, scrim: null, hud: null, spk: null, ghud: null, rows: {},
		joinOpen: "", createType: 0, note: null, noteAt: 0, devices: [], devListed: 0, raf: 0, wasUi: false };

	function q(sel) {
		return UI.root.querySelector(sel);
	}

	function build() {
		if (UI.built || !document.body) return;
		UI.built = true;
		var st = document.createElement("style");
		st.textContent = CSS;
		document.head.appendChild(st);
		var root = UI.root = document.createElement("div");
		root.id = "hv-root";
		root.innerHTML =
			'<div class="hv-hud"></div><div class="hv-spk"></div><div class="hv-ghud" style="display:none"></div>' +
			'<div class="hv-scrim"><div class="hv-panel" role="dialog" aria-label="Voice chat">' +
			'<div class="hv-head"><div class="hv-logo">' + icon("mic", 22) + '</div>' +
			'<div><div class="hv-title">Voice chat</div><div class="hv-sub" data-r="sub"></div></div><div class="hv-sp"></div>' +
			'<div class="hv-seg" data-r="mode">' +
			'<button data-act="mode" data-v="off">' + icon("power", 15) + 'Off</button>' +
			'<button data-act="mode" data-v="near">' + icon("near", 15) + 'Nearby</button>' +
			'<button data-act="mode" data-v="global">' + icon("globe", 15) + 'Global</button></div>' +
			'<button class="hv-ib" data-act="close" title="Close">' + icon("x", 18) + '</button></div>' +
			'<div class="hv-quick">' +
			'<button class="hv-q" data-act="qmute" data-r="qmute"><div class="ic"></div><div><b>Microphone</b><span></span></div></button>' +
			'<button class="hv-q" data-act="qdeaf" data-r="qdeaf"><div class="ic"></div><div><b>Sound</b><span></span></div></button>' +
			'<button class="hv-q" data-act="qicons" data-r="qicons"><div class="ic"></div><div><b>Icons</b><span></span></div></button></div>' +
			'<div class="hv-tabs">' +
			'<button class="hv-tab" data-act="tab" data-v="players">' + icon("users", 16) + 'Players <span class="hv-count" data-r="pcount">0</span></button>' +
			'<button class="hv-tab" data-act="tab" data-v="groups">' + icon("group", 16) + 'Groups</button>' +
			'<button class="hv-tab" data-act="tab" data-v="settings">' + icon("sliders", 16) + 'Settings</button></div>' +
			'<div class="hv-body">' +
			'<section data-tab="players"><div data-r="plist"></div><div class="hv-empty" data-r="pempty">' +
			'<div class="big">' + icon("users", 26) + '</div><b>Nobody else is in voice chat</b>' +
			'<span>Players who turn voice chat on show up here, with their own volume.</span></div></section>' +
			'<section data-tab="groups"></section>' +
			'<section data-tab="settings">' + settingsHtml() + '</section>' +
			'</div></div></div>';
		document.body.appendChild(root);
		UI.scrim = q(".hv-scrim");
		UI.hud = q(".hv-hud");
		UI.spk = q(".hv-spk");
		UI.ghud = q(".hv-ghud");
		var panel = q(".hv-panel");
		panel.addEventListener("click", onClick);
		panel.addEventListener("input", onInput);
		panel.addEventListener("change", onInput);
		// typing in a text box must not reach the game (V would close the screen, E open the inventory...)
		["keydown", "keyup", "keypress"].forEach(function (t) {
			panel.addEventListener(t, function (e) {
				var tag = e.target && e.target.tagName;
				if (tag === "INPUT" || tag === "SELECT" || tag === "TEXTAREA") {
					if (e.key === "Escape" && t === "keydown") {
						e.target.blur();
						e.preventDefault();
					}
					e.stopPropagation();
					if (e.key === "Enter" && t === "keydown") onEnter(e.target);
				}
			});
		});
		UI.scrim.addEventListener("mousedown", function (e) {
			if (e.target === UI.scrim) cmd({ t: "close" });
		});
		setTab(UI.tab);
	}

	function settingsHtml() {
		return '<div class="hv-sec">Microphone</div><div class="hv-card">' +
			'<div class="hv-set"><div class="t"><b>Input device</b><span>Which microphone to use</span></div>' +
			'<select class="hv-sel" data-in="device"></select></div>' +
			'<div class="hv-set"><div class="t"><b>Talk with</b><span data-r="actdesc"></span></div>' +
			'<div class="hv-seg" data-r="act"><button data-act="act" data-v="ptt">Push to talk</button>' +
			'<button data-act="act" data-v="voice">Voice activation</button></div></div>' +
			'<div class="hv-set col"><div style="display:flex;justify-content:space-between;align-items:center">' +
			'<div class="t"><b data-r="thrlabel">Level</b><span data-r="thrdesc">How loud your microphone is</span></div>' +
			'<span class="hv-count" data-r="dbval">-- dB</span></div>' +
			'<div class="hv-meter" data-r="meter"><i></i><div class="thr"></div></div>' +
			'<input type="range" class="hv-range wide" min="-80" max="-5" step="1" data-in="thr"></div>' +
			'<div class="hv-set"><div class="t"><b>Microphone volume</b><span>Boost a quiet microphone</span></div>' +
			'<div class="hv-vol"><input type="range" class="hv-range" min="0" max="200" step="5" data-in="micVol"><span class="pct" data-r="micVolV"></span></div></div>' +
			'<div class="hv-set"><div class="t"><b>Noise suppression</b><span>Filters out fans, keyboards and background hum</span></div><button class="hv-sw" data-sw="denoise"></button></div>' +
			'<div class="hv-set"><div class="t"><b>Echo cancellation</b><span>Stops others hearing themselves through your speakers</span></div><button class="hv-sw" data-sw="echo"></button></div>' +
			'<div class="hv-set"><div class="t"><b>Automatic gain</b><span>Evens out how loud you are</span></div><button class="hv-sw" data-sw="agc"></button></div>' +
			'<div class="hv-set"><div class="t"><b>Test your microphone</b><span>Hear yourself (use headphones). Nothing is sent while testing.</span></div>' +
			'<button class="hv-btn ghost" data-act="test" data-r="test">' + icon("mic", 15) + 'Start test</button></div></div>' +
			'<div class="hv-sec">Sound</div><div class="hv-card">' +
			'<div class="hv-set"><div class="t"><b>Voice volume</b><span>Everyone you hear</span></div>' +
			'<div class="hv-vol"><input type="range" class="hv-range" min="0" max="200" step="5" data-in="outVol"><span class="pct" data-r="outVolV"></span></div></div>' +
			'<div class="hv-set"><div class="t"><b>3D audio</b><span>Hear which way people are, front and back</span></div><button class="hv-sw" data-sw="hrtf"></button></div>' +
			'<div class="hv-set"><div class="t"><b>Muffle through walls</b><span>Voices behind blocks and under water sound muffled</span></div><button class="hv-sw" data-sw="muffle"></button></div>' +
			'<div class="hv-set"><div class="t"><b>Cave echo</b><span>Voices ring in caves and big rooms</span></div><button class="hv-sw" data-sw="reverb"></button></div></div>' +
			'<div class="hv-sec">Display</div><div class="hv-card">' +
			'<div class="hv-set"><div class="t"><b>Voice HUD</b><span>Your microphone status, who is talking, your group</span></div><button class="hv-sw" data-sw="hud"></button></div>' +
			'<div class="hv-set"><div class="t"><b>Keys</b><span>Change them in Options, Controls, Voice Chat</span></div></div>' +
			'<div class="hv-keys" data-r="keys"></div></div>';
	}

	function setTab(t) {
		UI.tab = t;
		var tabs = UI.root.querySelectorAll(".hv-tab");
		for (var i = 0; i < tabs.length; i++) tabs[i].classList.toggle("sel", tabs[i].getAttribute("data-v") === t);
		var secs = UI.root.querySelectorAll(".hv-body section");
		for (var j = 0; j < secs.length; j++) secs[j].classList.toggle("sel", secs[j].getAttribute("data-tab") === t);
		if (t === "groups") renderGroups(true);
		if (t === "settings") listDevices();
	}

	function cmd(c) {
		cmds.push(c);
	}

	function onClick(e) {
		var b = e.target.closest("[data-act],[data-sw]");
		if (!b) return;
		var act = b.getAttribute("data-act"), v = b.getAttribute("data-v");
		if (b.hasAttribute("data-sw")) {
			var k = b.getAttribute("data-sw");
			if (k === "hud") cfg.hud = cfg.hud === "off" ? "on" : "off";
			else cfg[k] = !cfg[k];
			save();
			if (k === "denoise" || k === "echo" || k === "agc") mic.key = ""; // reopen with the new processing
			renderSettings();
			return;
		}
		switch (act) {
		case "close": cmd({ t: "close" }); break;
		case "mode": cmd({ t: "mode", v: v }); G.mode = v; G.on = v !== "off"; renderHead(); break;
		case "qmute": cmd({ t: "mute", v: !G.mute }); G.mute = !G.mute; renderHead(); break;
		case "qdeaf": cmd({ t: "deaf", v: !G.deaf }); G.deaf = !G.deaf; renderHead(); break;
		case "qicons": cmd({ t: "icons", v: !G.icons }); G.icons = !G.icons; renderHead(); break;
		case "tab": setTab(v); break;
		case "pmute": {
			var id = b.closest("[data-id]").getAttribute("data-id");
			var now = !muted[id];
			cmd({ t: "pmute", id: id, v: now });
			if (now) muted[id] = true; else delete muted[id];
			renderPlayers();
			break;
		}
		case "act": cfg.act = v; save(); renderSettings(); break;
		case "test":
			mic.testOn = !mic.testOn;
			if (mic.test) mic.test.gain.setTargetAtTime(mic.testOn ? 1 : 0, ctx.currentTime, 0.02);
			renderSettings();
			break;
		case "gleave": cmd({ t: "gleave" }); break;
		case "gjoin": {
			var gid = b.getAttribute("data-g"), g = findGroup(gid);
			if (g && g[3] && UI.joinOpen !== gid) {
				UI.joinOpen = gid;
				renderGroups(true);
				var inp = q('[data-pass="' + gid + '"]');
				if (inp) inp.focus();
			} else {
				var p = q('[data-pass="' + gid + '"]');
				cmd({ t: "gjoin", id: gid, pass: p ? p.value : "" });
				UI.joinOpen = "";
			}
			break;
		}
		case "gtype": UI.createType = +v; renderCreateType(); break;
		case "gcreate": createGroup(); break;
		default: break;
		}
	}

	function onEnter(t) {
		if (t.hasAttribute("data-pass")) {
			cmd({ t: "gjoin", id: t.getAttribute("data-pass"), pass: t.value });
			UI.joinOpen = "";
		} else if (t.getAttribute("data-r") === "gname" || t.getAttribute("data-r") === "gpass") {
			createGroup();
		}
	}

	function createGroup() {
		var n = q('[data-r="gname"]'), p = q('[data-r="gpass"]');
		var name = n ? n.value.trim() : "";
		if (!name) {
			if (n) n.focus();
			return;
		}
		cmd({ t: "gcreate", name: name, pass: p ? p.value : "", type: UI.createType });
		if (n) n.value = "";
		if (p) p.value = "";
	}

	function onInput(e) {
		var t = e.target, k = t.getAttribute("data-in");
		if (t.classList.contains("hv-range")) paintRange(t);
		if (k === "thr") cfg.thr = +t.value;
		else if (k === "micVol") {
			cfg.micVol = t.value / 100;
			if (mic.gain) mic.gain.gain.setTargetAtTime(cfg.micVol, ctx.currentTime, 0.02);
		} else if (k === "outVol") cfg.outVol = t.value / 100;
		else if (k === "device" && e.type === "change") {
			cfg.device = t.value;
			mic.key = "";
		} else if (t.hasAttribute("data-pvol")) {
			var id = t.getAttribute("data-pvol"), v = t.value / 100;
			if (Math.abs(v - 1) < 0.001) delete cfg.vols[id]; else cfg.vols[id] = v;
			var pct = t.parentNode.querySelector(".pct");
			if (pct) pct.textContent = t.value + "%";
		} else {
			return;
		}
		save();
		if (k) renderSettings();
	}

	function paintRange(r) {
		var min = +r.min, max = +r.max;
		r.style.setProperty("--p", ((r.value - min) / (max - min) * 100) + "%");
	}

	function setRange(r, v) {
		if (document.activeElement !== r) r.value = v;
		paintRange(r);
	}

	function listDevices() {
		if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) return;
		var now = performance.now();
		if (now - UI.devListed < 1500) return;
		UI.devListed = now;
		navigator.mediaDevices.enumerateDevices().then(function (list) {
			UI.devices = list.filter(function (d) { return d.kind === "audioinput"; });
			if (UI.built) renderSettings();
		}, function () {});
	}

	function playerName(id) {
		for (var i = 0; i < G.players.length; i++) if (G.players[i][0] === id) return G.players[i][1];
		return "";
	}

	function findGroup(id) {
		for (var i = 0; i < G.groups.length; i++) if (G.groups[i][0] === id) return G.groups[i];
		return null;
	}

	function keyName(k) {
		var n = G.keys && G.keys[k];
		return n && n !== "NONE" ? n : "not set";
	}

	function renderHead() {
		var sub = q('[data-r="sub"]');
		var ok = G.on && mic.state !== "denied";
		var text = !G.on ? "Voice chat is off" : G.mode === "global" ? "Global - everyone on Global hears you" :
			"Nearby - you are heard " + G.dist + " blocks away";
		if (mic.state === "denied") text = "Your browser blocked the microphone";
		sub.innerHTML = '<span class="hv-dot ' + (G.on ? (ok ? "ok" : "bad") : "") + '"></span>' + esc(text);
		var seg = q('[data-r="mode"]');
		seg.classList.toggle("off", G.mode === "off");
		var bs = seg.querySelectorAll("button");
		for (var i = 0; i < bs.length; i++) bs[i].classList.toggle("sel", bs[i].getAttribute("data-v") === G.mode);
		quick("qmute", G.mute || mic.state === "denied", G.mute ? "micOff" : "mic",
			mic.state === "denied" ? "Blocked by the browser" : G.mute ? "Muted (" + keyName("mute") + ")" :
				cfg.act === "voice" ? "On - voice activation" : "On - hold " + keyName("ptt"));
		quick("qdeaf", G.deaf, G.deaf ? "headOff" : "head", G.deaf ? "Off - you hear nobody (" + keyName("deaf") + ")" : "On - you hear everyone");
		quick("qicons", !G.icons, G.icons ? "eye" : "eyeOff", G.icons ? "Shown above heads" : "Hidden");
		q('[data-r="pcount"]').textContent = G.players.length;
	}

	function quick(r, bad, ic, text) {
		var b = q('[data-r="' + r + '"]');
		b.classList.toggle("bad", !!bad);
		var i = b.querySelector(".ic");
		if (i.getAttribute("data-ic") !== ic) {
			i.innerHTML = icon(ic, 19);
			i.setAttribute("data-ic", ic);
		}
		b.querySelector("span").textContent = text;
	}

	function describe(pl) {
		var id = pl[0], flags = pl[2], grp = pl[3], b = [];
		if (flags & 8) b.push('<span class="hv-badge java">Java mod</span>');
		if (grp && grp === G.group) b.push('<span class="hv-badge grp">' + icon("group", 11) + 'Your group</span>');
		else if (grp) {
			var g = findGroup(grp);
			if (g) b.push('<span class="hv-badge grp">' + esc(g[1]) + "</span>");
		}
		if (flags & 2) b.push('<span class="hv-badge glb">Global</span>');
		if (flags & 4) b.push('<span class="hv-badge red">Sound off</span>');
		var s = speakers[id];
		if (s && (s.flags & 1) && speaking(id)) b.push('<span class="hv-badge wh">Whispering</span>');
		var p = G.p[id], me = G.me;
		if (p && me) b.push("<span>" + Math.round(Math.hypot(p[0] - me[0], p[1] - me[1], p[2] - me[2])) + " blocks away" +
			(cfg.muffle && p[3] >= 0.9 ? " - behind walls" : "") + "</span>");
		else b.push("<span>Not nearby</span>");
		return b.join("");
	}

	function renderPlayers() {
		var list = q('[data-r="plist"]'), seen = {};
		q('[data-r="pempty"]').style.display = G.players.length ? "none" : "";
		for (var i = 0; i < G.players.length; i++) {
			var pl = G.players[i], id = pl[0];
			seen[id] = true;
			var row = UI.rows[id];
			if (!row) {
				row = document.createElement("div");
				row.className = "hv-row";
				row.setAttribute("data-id", id);
				row.innerHTML = avatar(pl[1]) + '<div class="hv-pi"><div class="hv-name"></div><div class="hv-meta"></div></div>' +
					'<div class="hv-vol"><input type="range" class="hv-range" min="0" max="200" step="5" data-pvol="' + esc(id) +
					'"><span class="pct"></span></div><button class="hv-ib" data-act="pmute"></button>';
				UI.rows[id] = row;
			}
			if (row.parentNode !== list || list.children[i] !== row) list.insertBefore(row, list.children[i] || null);
			row.querySelector(".hv-name").textContent = pl[1];
			var meta = describe(pl);
			var m = row.querySelector(".hv-meta");
			if (m.getAttribute("data-h") !== meta) {
				m.innerHTML = meta;
				m.setAttribute("data-h", meta);
			}
			row.classList.toggle("talk", speaking(id));
			var v = cfg.vols[id], pct = Math.round((typeof v === "number" ? v : 1) * 100);
			var r = row.querySelector("input");
			setRange(r, pct);
			row.querySelector(".pct").textContent = pct + "%";
			var mb = row.querySelector("[data-act=pmute]");
			var mi = muted[id] ? "volOff" : "vol";
			if (mb.getAttribute("data-ic") !== mi) {
				mb.innerHTML = icon(mi, 18);
				mb.setAttribute("data-ic", mi);
				mb.title = muted[id] ? "Unmute " + pl[1] : "Mute " + pl[1] + " for you";
			}
			mb.classList.toggle("red", !!muted[id]);
		}
		for (var k in UI.rows) {
			if (!seen[k]) {
				if (UI.rows[k].parentNode) UI.rows[k].parentNode.removeChild(UI.rows[k]);
				delete UI.rows[k];
			}
		}
	}

	function groupsKey() {
		return JSON.stringify([G.v2, G.group, G.groups, UI.joinOpen, G.players.map(function (p) { return [p[0], p[3]]; })]);
	}

	function renderGroups(force) {
		var sec = q('section[data-tab="groups"]');
		var key = groupsKey();
		// keep what is being typed: only redraw when the groups changed
		if (!force && sec.getAttribute("data-k") === key) {
			paintGroupTalk();
			renderNote();
			return;
		}
		var keepName = q('[data-r="gname"]'), keepPass = q('[data-r="gpass"]');
		var nameV = keepName ? keepName.value : "", passV = keepPass ? keepPass.value : "";
		var focused = document.activeElement && document.activeElement.getAttribute && document.activeElement.getAttribute("data-r");
		sec.setAttribute("data-k", key);
		var h = '<div data-r="note"></div>';
		if (!G.v2) {
			h += '<div class="hv-empty"><div class="big">' + icon("group", 26) + '</div><b>Groups need a newer voice plugin</b>' +
				"<span>This server's HybridVoice plugin is too old for groups.</span></div>";
			sec.innerHTML = h;
			return;
		}
		var mine = G.group ? findGroup(G.group) : null;
		if (mine) {
			h += '<div class="hv-mine"><div class="hv-gic">' + icon("group", 22) + '</div><div style="flex:1;min-width:0">' +
				'<div class="hv-gname">' + esc(mine[1]) + '</div><div class="hv-meta"><span class="hv-badge grp">' +
				GROUP_TYPES[mine[2]][0] + "</span>" + (mine[3] ? '<span class="hv-badge">' + icon("lock", 11) + "Password</span>" : "") +
				"<span>" + mine[4] + (mine[4] === 1 ? " member" : " members") + "</span></div></div>" +
				'<button class="hv-btn danger" data-act="gleave">' + icon("leave", 15) + "Leave</button></div>" +
				'<div class="hv-members" data-r="members"><div class="hv-member">' + avatar("You", "sm") + "You</div>";
			for (var i = 0; i < G.players.length; i++) {
				if (G.players[i][3] === G.group) {
					h += '<div class="hv-member" data-gm="' + esc(G.players[i][0]) + '">' + avatar(G.players[i][1], "sm") + esc(G.players[i][1]) + "</div>";
				}
			}
			h += '</div><div class="hv-tdesc" style="padding:6px 4px 0">' + GROUP_TYPES[mine[2]][1] + "</div>";
		}
		var others = G.groups.filter(function (g) { return g[0] !== G.group; });
		h += '<div class="hv-sec">' + (mine ? "Other groups" : "Groups on this server") + "</div>";
		if (!others.length) {
			h += '<div class="hv-card"><div class="hv-set"><div class="t"><b>No ' + (mine ? "other " : "") + 'groups yet</b>' +
				"<span>Make one below - friends can join it from anywhere in the world.</span></div></div></div>";
		} else {
			h += '<div class="hv-card">';
			others.forEach(function (g) {
				h += '<div class="hv-set"><div style="display:flex;gap:12px;align-items:center;min-width:0"><div class="hv-gic" style="width:36px;height:36px;border-radius:11px">' +
					icon(g[3] ? "lock" : "group", 17) + '</div><div class="t"><b>' + esc(g[1]) + '</b><span>' + GROUP_TYPES[g[2]][0] + " - " +
					g[4] + (g[4] === 1 ? " member" : " members") + "</span></div></div>" +
					'<div style="display:flex;gap:8px;align-items:center">' +
					(UI.joinOpen === g[0] ? '<input class="hv-in" style="width:160px" type="password" placeholder="Password" data-pass="' + esc(g[0]) + '">' : "") +
					'<button class="hv-btn' + (UI.joinOpen === g[0] ? "" : " ghost") + '" data-act="gjoin" data-g="' + esc(g[0]) + '">Join</button></div></div>';
			});
			h += "</div>";
		}
		h += '<div class="hv-sec">Make a group</div><div class="hv-card"><div class="hv-form">' +
			'<input class="hv-in" maxlength="24" placeholder="Group name" data-r="gname">' +
			'<input class="hv-in" maxlength="32" type="password" placeholder="Password (optional)" data-r="gpass">' +
			'<div class="full"><div class="hv-seg" data-r="gtype">' +
			GROUP_TYPES.map(function (t, i) { return '<button data-act="gtype" data-v="' + i + '">' + t[0] + "</button>"; }).join("") +
			'</div></div><div class="full hv-tdesc" data-r="gtdesc"></div>' +
			'<div class="full" style="display:flex;justify-content:flex-end"><button class="hv-btn" data-act="gcreate">' + icon("plus", 15) +
			"Make group</button></div></div></div>";
		sec.innerHTML = h;
		var n = q('[data-r="gname"]'), p = q('[data-r="gpass"]');
		if (n) n.value = nameV;
		if (p) p.value = passV;
		if (focused === "gname" && n) n.focus();
		if (focused === "gpass" && p) p.focus();
		renderCreateType();
		paintGroupTalk();
		renderNote();
	}

	function renderCreateType() {
		var seg = q('[data-r="gtype"]');
		if (!seg) return;
		var bs = seg.querySelectorAll("button");
		for (var i = 0; i < bs.length; i++) bs[i].classList.toggle("sel", +bs[i].getAttribute("data-v") === UI.createType);
		q('[data-r="gtdesc"]').textContent = GROUP_TYPES[UI.createType][1];
	}

	function paintGroupTalk() {
		var ms = UI.root.querySelectorAll("[data-gm]");
		for (var i = 0; i < ms.length; i++) {
			var av = ms[i].querySelector(".hv-av");
			if (av) av.classList.toggle("talk", speaking(ms[i].getAttribute("data-gm")));
		}
	}

	function renderNote() {
		var n = q('[data-r="note"]');
		if (!n) return;
		if (UI.note && performance.now() - UI.noteAt < 5000) {
			var h = '<div class="hv-note ' + (UI.note[0] ? "ok" : "bad") + '">' + icon(UI.note[0] ? "check" : "alert", 16) + esc(UI.note[1]) + "</div>";
			if (n.getAttribute("data-h") !== h) {
				n.innerHTML = h;
				n.setAttribute("data-h", h);
			}
		} else if (n.innerHTML) {
			n.innerHTML = "";
			n.setAttribute("data-h", "");
		}
	}

	function renderSettings() {
		if (!UI.built) return;
		var seg = q('[data-r="act"]').querySelectorAll("button");
		for (var i = 0; i < seg.length; i++) seg[i].classList.toggle("sel", seg[i].getAttribute("data-v") === cfg.act);
		var voice = cfg.act === "voice";
		q('[data-r="actdesc"]').textContent = voice ? "Sends when you speak louder than the line below" :
			"Hold " + keyName("ptt") + " to talk, " + keyName("wh") + " to whisper";
		q('[data-r="thrlabel"]').textContent = voice ? "Activation level" : "Microphone level";
		q('[data-r="thrdesc"]').textContent = voice ? "Drag so your voice goes past the line and the room stays under it" :
			"Speak to see your level";
		var thr = q('[data-in="thr"]');
		thr.style.display = voice ? "" : "none";
		setRange(thr, cfg.thr);
		q('[data-r="meter"] .thr').style.display = voice ? "" : "none";
		q('[data-r="meter"] .thr').style.left = ((cfg.thr + 80) / 80 * 100) + "%";
		setRange(q('[data-in="micVol"]'), Math.round(cfg.micVol * 100));
		q('[data-r="micVolV"]').textContent = Math.round(cfg.micVol * 100) + "%";
		setRange(q('[data-in="outVol"]'), Math.round(cfg.outVol * 100));
		q('[data-r="outVolV"]').textContent = Math.round(cfg.outVol * 100) + "%";
		var sws = UI.root.querySelectorAll("[data-sw]");
		for (var j = 0; j < sws.length; j++) {
			var k = sws[j].getAttribute("data-sw");
			sws[j].classList.toggle("on", k === "hud" ? cfg.hud !== "off" : !!cfg[k]);
		}
		var tb = q('[data-r="test"]');
		tb.classList.toggle("on", mic.testOn);
		tb.innerHTML = icon(mic.testOn ? "x" : "mic", 15) + (mic.testOn ? "Stop test" : "Start test");
		var sel = q('[data-in="device"]');
		if (document.activeElement !== sel) {
			var h = '<option value="">Default microphone</option>';
			UI.devices.forEach(function (d, i) {
				if (d.deviceId === "default" || d.deviceId === "communications") return;
				h += '<option value="' + esc(d.deviceId) + '">' + esc(d.label || "Microphone " + (i + 1)) + "</option>";
			});
			if (sel.getAttribute("data-h") !== h) {
				sel.innerHTML = h;
				sel.setAttribute("data-h", h);
			}
			sel.value = cfg.device;
		}
		var keys = [["ptt", "Push to talk"], ["wh", "Whisper"], ["mute", "Mute"], ["deaf", "Sound off"], ["menu", "This screen"]];
		var kh = keys.map(function (k) { return '<span class="hv-key"><kbd>' + esc(keyName(k[0])) + "</kbd>" + k[1] + "</span>"; }).join("");
		var kr = q('[data-r="keys"]');
		if (kr.getAttribute("data-h") !== kh) {
			kr.innerHTML = kh;
			kr.setAttribute("data-h", kh);
		}
	}

	function renderHud() {
		var show = G.hud && !G.ui && cfg.hud !== "off" && G.on;
		var h = "";
		if (show) {
			if (mic.state === "denied") h = pill("red", "micOff", "Microphone blocked");
			else if (G.deaf) h = pill("red", "headOff", "Sound off");
			else if (G.mute) h = pill("red", "micOff", "Muted");
			else if (talking) h = pill((whispering ? "wh " : "") + "talk", whispering ? "whisper" : "mic", whispering ? "Whispering" : "Talking", true);
		}
		if (UI.hud.getAttribute("data-h") !== h) {
			UI.hud.innerHTML = h;
			UI.hud.setAttribute("data-h", h);
		}
		// who is talking that you can't see
		var sh = "";
		if (show && !G.deaf && G.icons) {
			var n = 0;
			for (var i = 0; i < G.players.length && n < 5; i++) {
				var id = G.players[i][0];
				// your group has its own card; the rest are people you hear but can't see
				if (G.group && G.players[i][3] === G.group) continue;
				if (speaking(id) && (!G.p[id] || (speakers[id] && speakers[id].flags & 2))) {
					n++;
					sh += '<div class="hv-sp-row">' + avatar(G.players[i][1], "sm talk") + esc(G.players[i][1]) +
						(speakers[id] && speakers[id].flags & 1 ? '<span class="hv-badge wh">whisper</span>' : "") + "</div>";
				}
			}
		}
		if (UI.spk.getAttribute("data-h") !== sh) {
			UI.spk.innerHTML = sh;
			UI.spk.setAttribute("data-h", sh);
		}
		var g = show && G.group ? findGroup(G.group) : null;
		var gh = "";
		if (g) {
			gh = '<div class="gt">' + icon("group", 14) + esc(g[1]) + '</div><div class="gm">' + avatar("You", "sm" + (talking ? " talk" : "")) + "You</div>";
			for (var j = 0; j < G.players.length; j++) {
				if (G.players[j][3] === G.group) {
					gh += '<div class="gm">' + avatar(G.players[j][1], "sm" + (speaking(G.players[j][0]) ? " talk" : "")) + esc(G.players[j][1]) + "</div>";
				}
			}
		}
		if (UI.ghud.getAttribute("data-h") !== gh) {
			UI.ghud.innerHTML = gh;
			UI.ghud.setAttribute("data-h", gh);
			UI.ghud.style.display = gh ? "" : "none";
		}
	}

	function pill(cls, ic, text, bars) {
		return '<div class="hv-pill ' + cls + '"><div class="ic">' + icon(ic, 15) + "</div>" + text +
			(bars ? '<div class="hv-bars"><i></i><i></i><i></i><i></i></div>' : "") + "</div>";
	}

	/** smooth things that move faster than game ticks: the level meter and the talking bars */
	function frame() {
		UI.raf = 0;
		if (!UI.built) return;
		var level = mic.level, pct = Math.max(0, Math.min(100, (level + 80) / 80 * 100));
		if (G.ui && UI.tab === "settings") {
			var m = q('[data-r="meter"] i');
			m.style.clipPath = "inset(0 " + (100 - pct) + "% 0 0 round 999px)";
			m.classList.toggle("dim", cfg.act === "voice" && level < cfg.thr);
			q('[data-r="dbval"]').textContent = mic.state === "ok" ? Math.round(level) + " dB" :
				mic.state === "denied" ? "blocked" : mic.state === "none" ? "no microphone" : "--";
		}
		var bars = UI.hud.querySelectorAll(".hv-bars i");
		for (var i = 0; i < bars.length; i++) {
			var w = Math.max(18, Math.min(100, pct * (0.7 + 0.6 * Math.abs(Math.sin(performance.now() / (90 + i * 37) + i)))));
			bars[i].style.height = w + "%";
		}
		if (G.ui || bars.length) UI.raf = requestAnimationFrame(frame);
	}

	function renderUi() {
		build();
		if (!UI.built) return;
		if (G.ui !== UI.wasUi) {
			UI.wasUi = G.ui;
			UI.scrim.classList.toggle("on", G.ui);
			if (G.ui) {
				renderGroups(true);
			} else {
				mic.testOn = false;
				if (mic.test) mic.test.gain.value = 0;
				// keys go back to the game, not a text box that is now hidden
				if (document.activeElement && UI.root.contains(document.activeElement)) document.activeElement.blur();
			}
		}
		if (G.ui) {
			renderHead();
			renderPlayers();
			if (UI.tab === "groups") renderGroups(false);
			if (UI.tab === "settings") renderSettings();
		}
		renderHud();
		if (!UI.raf && (G.ui || talking)) UI.raf = requestAnimationFrame(frame);
	}

	// ---- called by the game ----

	var R = {
		supported: function () {
			return support === true;
		},

		// state (VoiceRelay.tick): { on, mode, deaf, mute, ptt, wh, ui, hud, icons, dist, wdist, v2, init, keys, muted,
		//   players: [[id, name, flags, group]], groups: [[id, name, type, password, members]], group, result,
		//   me: [x,y,z,yaw,pitch], env: [echo wet, echo seconds, under water], p: { id: [x,y,z,walls,under water] } }
		// answer: { a: base64 batch of Opus frames to send, w: whispered, s: [ids heard in the last 300 ms],
		//   talk: you are talking, mic: "ok"/"denied"/"none"/"off", cmd: [what was done on the screen] }
		tick: function (json) {
			var st;
			try {
				st = JSON.parse(json);
			} catch (e) {
				return "";
			}
			for (var k in st) G[k] = st[k];
			if (!st.p) G.p = {};
			if (!st.me) G.me = null;
			if (st.init && cfg.fresh) {
				// carry over the old voice screen's settings, once
				cfg.fresh = false;
				cfg.act = st.init.act === 1 ? "voice" : "ptt";
				if (typeof st.init.thr === "number") cfg.thr = Math.max(-80, Math.min(-5, st.init.thr));
				if (typeof st.init.spk === "number") cfg.micVol = Math.max(0, Math.min(2, st.init.spk * 2));
				if (typeof st.init.lis === "number") cfg.outVol = Math.max(0, Math.min(2, st.init.lis * 2));
				save();
			}
			if (st.result) {
				UI.note = st.result;
				UI.noteAt = performance.now();
			}
			muted = {};
			(st.muted || []).forEach(function (id) {
				muted[id] = true;
			});
			if (wantMic()) {
				if (!mic.stream || mic.key !== micKey()) openMic();
			} else if (mic.stream) {
				closeMic();
			}

			var res = { a: "", w: false, s: [], talk: talking, mic: mic.state, cmd: cmds };
			cmds = [];
			if (outQueue.length) {
				var size = 1, n = Math.min(outQueue.length, 16), i;
				for (i = 0; i < n; i++) size += 2 + outQueue[i].length;
				var batch = new Uint8Array(size), o = 1;
				batch[0] = n;
				for (i = 0; i < n; i++) {
					var f = outQueue[i];
					batch[o++] = f.length >> 8;
					batch[o++] = f.length & 255;
					batch.set(f, o);
					o += f.length;
				}
				outQueue.splice(0, n);
				if (G.on) {
					res.a = b64encode(batch);
					res.w = whispering;
					stats.sent += n;
				}
			}
			if (ctx) {
				updateSpeakers();
				for (var id in speakers) if (speaking(id)) res.s.push(id);
			}
			try {
				renderUi();
			} catch (e) {
				fail("ui", e);
			}
			return JSON.stringify(res);
		},

		// one batch from one speaker: [n] then n x [len u16][opus frame]; flags: 1 whisper, 2 through a group or
		// Global, 4 from a player with the Simple Voice Chat mod
		incoming: function (id, b64, flags) {
			if (G.deaf || muted[id]) return;
			var s = speaker(id);
			s.last = performance.now();
			s.flags = flags | 0;
			var bytes = b64decode(b64), o = 1, n = bytes[0];
			for (var i = 0; i < n && o + 2 <= bytes.length; i++) {
				var len = (bytes[o] << 8) | bytes[o + 1];
				o += 2;
				if (o + len > bytes.length) break;
				if (s.dec && s.dec.state === "configured") {
					try {
						s.dec.decode(new EncodedAudioChunk({ type: "key", timestamp: s.ts, data: bytes.subarray(o, o + len) }));
					} catch (e) {
						fail("decode", e);
					}
				}
				s.ts += 20000;
				o += len;
				stats.recv++;
			}
		},

		reset: function () {
			closeMic();
			outQueue.length = 0;
			talking = whispering = false;
			G.on = G.ui = G.hud = false;
			G.players = [];
			G.groups = [];
			G.group = "";
			for (var id in speakers) {
				try { if (speakers[id].dec) speakers[id].dec.close(); speakers[id].panner.disconnect(); speakers[id].send.disconnect(); } catch (e) {}
			}
			speakers = {};
			try {
				renderUi();
			} catch (e) {}
		},

		// for testing from the console: what was sent/heard and how loud the voice output is right now
		stats: function () {
			var level = 0;
			if (outMeter) {
				var b = new Float32Array(outMeter.fftSize);
				outMeter.getFloatTimeDomainData(b);
				for (var i = 0; i < b.length; i++) level += b[i] * b[i];
				level = Math.sqrt(level / b.length);
			}
			return { sent: stats.sent, recv: stats.recv, played: stats.played, errors: stats.errors,
				lastError: stats.lastError, speakers: Object.keys(speakers).length, outputLevel: level,
				ctx: ctx ? ctx.state : "none", mic: mic.state, micLevel: mic.level, talking: talking,
				whispering: whispering, echo: revOut ? revOut.gain.value : 0, settings: cfg,
				each: Object.keys(speakers).map(function (id) {
					var s = speakers[id];
					return { id: id, flags: s.flags, walls: s.occ, cutoff: s.filter.frequency.value, send: s.send.gain.value,
						model: s.panner.panningModel, speaking: speaking(id) };
				}) };
		}
	};
	window.hybridVoiceRelay = R;
})();
