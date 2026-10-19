// hybrid: the browser half of the HybridVoice relay - voice chat carried over the game connection, so it works
// wherever the game works (Cloudflare tunnel, school networks that block WebRTC), with no port forwarding.
//
// VoiceRelay.java runs the protocol and calls tick() / incoming() every game tick through PlatformVoiceClient.
// This file compresses the gated microphone (window.__hybridVoiceStream, set by PlatformVoiceClient after its
// volume and push-to-talk/voice-activation gate) to Opus with WebCodecs, and plays what other players said,
// positioned where they stand. It never touches the network itself.
(function () {
	"use strict";
	var RATE = 48000, FRAME = 960; // 20 ms Opus frames
	var BITRATE = 24000;
	var JITTER = 0.08; // seconds of audio buffered before a speaker starts playing
	var support = null;
	var ctx = null, master = null, outMeter = null;
	var cap = null; // { stream, src, node, sink }
	var encoder = null, encTime = 0;
	var outQueue = [];
	var talking = false, tail = 0, on = false, deaf = false;
	var distance = 48;
	var muted = {};
	var speakers = {};
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
			outMeter = ctx.createAnalyser();
			master.connect(outMeter);
			outMeter.connect(ctx.destination);
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

	// ---- microphone -> Opus ----

	var WORKLET = "class C extends AudioWorkletProcessor{constructor(){super();this.b=new Float32Array(" + FRAME +
		");this.n=0}process(i){var c=i[0]&&i[0][0];if(c){for(var k=0;k<c.length;k++){this.b[this.n++]=c[k];if(this.n===" +
		FRAME + "){this.port.postMessage(this.b,[this.b.buffer]);this.b=new Float32Array(" + FRAME +
		");this.n=0}}}return true}}registerProcessor('hybrid-voice-capture',C);";
	var workletLoaded = null;

	function startCapture(stream) {
		if (cap && cap.stream === stream) return;
		stopCapture();
		var c = audio();
		cap = { stream: stream };
		var mine = cap;
		if (!workletLoaded) {
			workletLoaded = c.audioWorklet.addModule(URL.createObjectURL(new Blob([WORKLET], { type: "application/javascript" })));
		}
		workletLoaded.then(function () {
			if (cap !== mine) return;
			mine.src = c.createMediaStreamSource(stream);
			mine.node = new AudioWorkletNode(c, "hybrid-voice-capture", { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1] });
			mine.node.port.onmessage = function (ev) {
				onFrame(ev.data);
			};
			// a silent path to the speakers so the browser keeps pulling audio through the worklet
			mine.sink = c.createGain();
			mine.sink.gain.value = 0;
			mine.src.connect(mine.node);
			mine.node.connect(mine.sink);
			mine.sink.connect(c.destination);
		}, function (e) {
			fail("worklet", e);
		});
	}

	function stopCapture() {
		if (!cap) return;
		try {
			if (cap.src) cap.src.disconnect();
			if (cap.node) {
				cap.node.port.onmessage = null;
				cap.node.disconnect();
			}
			if (cap.sink) cap.sink.disconnect();
		} catch (e) {}
		cap = null;
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
		// the gate already silences the mic when you are not talking; this just avoids sending that silence.
		// A few frames after you stop are still sent so the last word is not cut off.
		if (talking) tail = 4;
		else if (tail > 0) tail--;
		else return;
		if (!on || deaf) return;
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
		s = { id: id, next: 0, last: 0, ts: 0 };
		s.gain = c.createGain();
		// behind walls: a low-pass filter (and a little quieter), like hearing someone through a door
		s.filter = c.createBiquadFilter();
		s.filter.type = "lowpass";
		s.filter.frequency.value = 20000;
		s.filter.Q.value = 0.7;
		s.walls = 0;
		s.panner = c.createPanner();
		s.panner.panningModel = "equalpower";
		s.panner.distanceModel = "linear"; // fades out evenly to silence at the server's voice distance, like SVC
		s.panner.refDistance = 1;
		s.panner.maxDistance = distance;
		s.panner.rolloffFactor = 1;
		s.gain.connect(s.filter);
		s.filter.connect(s.panner);
		s.panner.connect(master);
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
			src.connect(s.gain);
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

	// blocks between you and the speaker -> [low-pass cutoff Hz, volume]: none, one (a door), two, three or more
	var MUFFLE = [[20000, 1], [1400, 0.8], [750, 0.62], [420, 0.5]];

	function setWalls(s, walls) {
		walls = Math.max(0, Math.min(3, walls | 0));
		if (walls === s.walls) return;
		s.walls = walls;
		var t = ctx.currentTime;
		// glide over ~0.1 s so walking past a doorway does not click
		s.filter.frequency.setTargetAtTime(MUFFLE[walls][0], t, 0.05);
		s.gain.gain.setTargetAtTime(MUFFLE[walls][1], t, 0.05);
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

	// ---- called by the game ----

	var R = {
		supported: function () {
			return support === true;
		},

		// state: { on, talk, vol, deaf, dist, muted: [ids], me: [x,y,z,yaw,pitch], p: { id: [x,y,z,walls] } }
		// answer: { a: base64 batch of Opus frames to send, s: [ids heard in the last 300 ms] }
		tick: function (json) {
			var st;
			try {
				st = JSON.parse(json);
			} catch (e) {
				return "";
			}
			on = !!st.on;
			talking = !!st.talk;
			deaf = !!st.deaf;
			distance = st.dist || 48;
			muted = {};
			(st.muted || []).forEach(function (id) {
				muted[id] = true;
			});
			var stream = window.__hybridVoiceStream;
			if (on && stream) startCapture(stream);
			else if (!on) stopCapture();

			var res = { a: "", s: [] };
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
				if (on) {
					res.a = b64encode(batch);
					stats.sent += n;
				}
			}
			if (ctx) {
				master.gain.value = deaf ? 0 : (typeof st.vol === "number" ? st.vol : 1);
				if (st.me) setListener(st.me);
				var now = performance.now(), pos = st.p || {};
				for (var id in speakers) {
					var s = speakers[id];
					s.panner.maxDistance = distance;
					var p = pos[id];
					// someone not in sight (Global, far away) plays from where you stand: no direction, full volume
					if (p) setPos(s.panner, p[0], p[1], p[2]);
					else if (st.me) setPos(s.panner, st.me[0], st.me[1], st.me[2]);
					setWalls(s, p ? p[3] : 0);
					if (now - s.last < 300 && !muted[id]) res.s.push(id);
					if (now - s.last > 60000) {
						try { if (s.dec) s.dec.close(); s.panner.disconnect(); } catch (e) {}
						delete speakers[id];
					}
				}
			}
			return JSON.stringify(res);
		},

		// one batch from one speaker: [n] then n x [len u16][opus frame]
		incoming: function (id, b64) {
			if (deaf || muted[id]) return;
			var s = speaker(id);
			s.last = performance.now();
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
			stopCapture();
			outQueue.length = 0;
			talking = false;
			on = false;
			for (var id in speakers) {
				try { if (speakers[id].dec) speakers[id].dec.close(); speakers[id].panner.disconnect(); } catch (e) {}
			}
			speakers = {};
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
				ctx: ctx ? ctx.state : "none", capturing: !!(cap && cap.node) };
		}
	};
	window.hybridVoiceRelay = R;
})();
