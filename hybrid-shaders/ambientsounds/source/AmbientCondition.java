/*
 * Ported from AmbientSounds by CreativeMD (https://github.com/CreativeMD/AmbientSounds), LGPL-3.0-only - see
 * assets/ambientsounds/LICENSE.txt. Changes: AmbientCondition, AmbientSoundProperties, AmbientSelection(Multi),
 * AmbientVolume and AmbientTime in one file, read with org.json instead of Gson; the entity condition (1.19+ mobs such
 * as the warden) never matches here.
 */
package net.minecraft.client.audio.ambient;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;


import org.json.JSONArray;
import org.json.JSONObject;

import net.minecraft.util.math.MathHelper;
import net.minecraft.world.biome.Biome;

/** a condition of AmbientSounds' engine (regions, sounds and sound categories are all conditions) */
class AmbientCondition {

	// ---- AmbientSoundProperties ----
	Integer transition;
	Double pitch = 1.0;
	Double fadeVolume, fadeInVolume, fadeOutVolume, fadePitch, fadeInPitch, fadeOutPitch, mute, mutePriority;
	boolean randomOffset = true;
	MinMax pause, length;
	Climb underwaterPitch;
	String channel;

	// ---- AmbientCondition ----
	Boolean always;
	double volume = 1.0, nightVolume = 1.0, dayVolume = 1.0;
	Time time;
	String biomeType;
	String[] biomes, badBiomes;
	AmbientBlocks.BiomeCondition[] biomesCompiled, badBiomesCompiled;
	Boolean raining, overallRaining, snowing, storming;
	MinMaxFade underwater, absoluteHeight, minHeightRelative, maxHeightRelative, light, blockLight, skyLight, air,
			temperature, sky;
	MinMaxFadeSpecial relativeHeight;
	String[] features, badFeatures;
	AmbientCondition[] variants;
	String[] regions, badRegions;
	List<AmbientEngine.Region> regionList, badRegionList;
	boolean entity;

	String regionName() {
		return null;
	}

	/** reads the fields this class knows from json (subclasses read theirs too) */
	void read(JSONObject o) {
		transition = o.has("transition") ? o.optInt("transition") : null;
		if (o.has("pitch")) {
			pitch = o.optDouble("pitch", 1.0);
		}
		fadeVolume = dbl(o, "fade-volume");
		fadeInVolume = dbl(o, "fade-in-volume");
		fadeOutVolume = dbl(o, "fade-out-volume");
		fadePitch = dbl(o, "fade-pitch");
		fadeInPitch = dbl(o, "fade-in-pitch");
		fadeOutPitch = dbl(o, "fade-out-pitch");
		mute = dbl(o, "mute");
		mutePriority = dbl(o, "mute-priority");
		randomOffset = o.optBoolean("random-offset", true);
		pause = MinMax.read(o.optJSONObject("pause"), new MinMax());
		length = MinMax.read(o.optJSONObject("length"), new MinMax());
		JSONObject up = o.optJSONObject("underwater-pitch");
		if (up != null) {
			underwaterPitch = new Climb();
			underwaterPitch.min = up.optDouble("min", 0.0);
			underwaterPitch.max = up.optDouble("max", 0.0);
			underwaterPitch.distanceFactor = up.optDouble("distance-factor", 1.0);
		}
		channel = o.optString("channel", null);

		always = o.has("always") ? o.optBoolean("always") : null;
		volume = o.optDouble("volume", 1.0);
		nightVolume = o.optDouble("night", 1.0);
		dayVolume = o.optDouble("day", 1.0);
		time = o.has("time") ? Time.read(o.get("time")) : null;
		biomeType = o.optString("biome-type", null);
		biomes = strings(o, "biomes");
		badBiomes = strings(o, "bad-biomes");
		raining = bool(o, "raining");
		overallRaining = bool(o, "overall-raining");
		snowing = bool(o, "snowing");
		storming = bool(o, "storming");
		underwater = MinMaxFade.read(o.optJSONObject("underwater"), new MinMaxFade());
		relativeHeight = MinMaxFade.read(o.optJSONObject("relative-height"), new MinMaxFadeSpecial());
		absoluteHeight = MinMaxFade.read(o.optJSONObject("absolute-height"), new MinMaxFade());
		minHeightRelative = MinMaxFade.read(o.optJSONObject("min-height-relative"), new MinMaxFade());
		maxHeightRelative = MinMaxFade.read(o.optJSONObject("max-height-relative"), new MinMaxFade());
		light = MinMaxFade.read(o.optJSONObject("light"), new MinMaxFade());
		blockLight = MinMaxFade.read(o.optJSONObject("block-light"), new MinMaxFade());
		skyLight = MinMaxFade.read(o.optJSONObject("sky-light"), new MinMaxFade());
		air = MinMaxFade.read(o.optJSONObject("air"), new MinMaxFade());
		temperature = MinMaxFade.read(o.optJSONObject("temperature"), new MinMaxFade());
		sky = MinMaxFade.read(o.optJSONObject("sky"), new MinMaxFade());
		features = strings(o, "features");
		badFeatures = strings(o, "bad-features");
		JSONArray v = o.optJSONArray("variants");
		if (v != null) {
			variants = new AmbientCondition[v.length()];
			for (int i = 0; i < v.length(); ++i) {
				variants[i] = new AmbientCondition();
				variants[i].read(v.getJSONObject(i));
			}
		}
		regions = strings(o, "regions");
		badRegions = strings(o, "bad-regions");
		entity = o.has("entity");
	}

	void init(AmbientEngine engine) {
		if (mute != null) {
			mute = MathHelper.clamp(mute, 0.0, 1.0);
		}
		volume = MathHelper.clamp(volume, 0.0, 1.0);
		nightVolume = MathHelper.clamp(nightVolume, 0.0, 1.0);
		dayVolume = MathHelper.clamp(dayVolume, 0.0, 1.0);
		if (variants != null) {
			for (AmbientCondition c : variants) {
				c.init(engine);
			}
		}
		biomesCompiled = AmbientBlocks.BiomeCondition.of(biomes);
		badBiomesCompiled = AmbientBlocks.BiomeCondition.of(badBiomes);
		if (regions != null) {
			regionList = new ArrayList<>();
			for (String n : regions) {
				AmbientEngine.Region r = engine.getRegion(n);
				if (r != null && !n.equals(regionName())) {
					regionList.add(r);
				}
			}
		}
		if (badRegions != null) {
			badRegionList = new ArrayList<>();
			for (String n : badRegions) {
				AmbientEngine.Region r = engine.getRegion(n);
				if (r != null && !n.equals(regionName())) {
					badRegionList.add(r);
				}
			}
		}
		if (biomeType == null) {
			biomeType = engine.defaultBiomeType;
		}
	}

	/** how loud this condition is right now, or null when it doesn't hold (AmbientCondition.value) */
	Selection value(AmbientEnvironment env) {
		if (env.muted) {
			return null;
		}
		if (always != null) {
			return always ? new Selection(this) : null;
		}
		if (volume <= 0.0 || (env.night ? nightVolume <= 0.0 : dayVolume <= 0.0)) {
			return null;
		}
		if (raining != null && raining != env.raining) {
			return null;
		}
		if (overallRaining != null && overallRaining != env.isRainAudibleAtSurface()) {
			return null;
		}
		if (snowing != null && snowing != env.snowing) {
			return null;
		}
		if (storming != null && storming != env.thundering) {
			return null;
		}
		if (badFeatures != null && env.airPocket.volume(badFeatures) > 0.0) {
			return null;
		}
		if (entity) {
			return null; // 1.19+ mobs (the warden): not in this version
		}
		Selection selection = new Selection(this);
		selection.mulCondition(env.night ? nightVolume : dayVolume);
		if (time != null) {
			double v = time.value(env);
			if (v <= 0.0) {
				return null;
			}
			selection.mulCondition(v);
		}
		if (badRegionList != null) {
			for (AmbientEngine.Region r : badRegionList) {
				if (r.active) {
					return null;
				}
			}
		}
		if (regionList != null) {
			Volume highest = null;
			for (AmbientEngine.Region r : regionList) {
				Selection sub = r.value(env);
				if (sub != null && (highest == null || sub.volume() > highest.volume())) {
					highest = sub;
				}
				if (highest != null && highest.volume() == 1.0) {
					break;
				}
			}
			if (highest == null) {
				return null;
			}
			selection.mulVolume(highest);
		}
		if (biomes != null || badBiomes != null) {
			Volume highest = null;
			for (int i = 0; i < env.biomes.size(); ++i) {
				Biome biome = env.biomes.get(i);
				if (biomes == null || AmbientBlocks.matches(biomesCompiled, biome)) {
					if (badBiomes != null && AmbientBlocks.matches(badBiomesCompiled, biome)) {
						return null;
					}
					if (biomes != null) {
						Volume v = env.biomeVolumes.get(i).copy();
						Volume type = env.biomeTypeVolumes.get(biomeType);
						v.mulVolume(type != null ? type : Volume.MAX);
						if (highest == null || highest.volume() < v.volume()) {
							highest = v;
						}
						if (highest.volume() == 1.0) {
							break;
						}
					}
				}
			}
			if (highest == null && biomes != null) {
				return null;
			}
			if (highest != null) {
				selection.mulVolume(highest);
			}
		}
		if (overallRaining != null && overallRaining) {
			selection.mulCondition(env.rainSurfaceVolume);
		}
		if (!mul(selection, underwater, env.underwater) || !mulSpecial(selection, env)
				|| !mul(selection, minHeightRelative, env.relativeMinHeight)
				|| !mul(selection, maxHeightRelative, env.relativeMaxHeight)
				|| !mul(selection, absoluteHeight, env.absoluteHeight)
				|| !mul(selection, light, env.airPocket.averageLight)
				|| !mul(selection, blockLight, env.airPocket.averageBlockLight)
				|| !mul(selection, skyLight, env.airPocket.averageSkyLight)
				|| !mul(selection, air, env.airPocket.air) || !mul(selection, sky, env.airPocket.sky)) {
			return null;
		}
		if (features != null) {
			double v = env.airPocket.volume(features);
			if (v <= 0.0) {
				return null;
			}
			selection.mulCondition(v);
		}
		if (!mul(selection, temperature, env.temperature)) {
			return null;
		}
		if (variants != null) {
			Selection best = null;
			for (AmbientCondition c : variants) {
				Selection sub = c.value(env);
				if (sub != null && (best == null || best.volume() < sub.volume())) {
					best = sub;
				}
			}
			if (best == null) {
				return null;
			}
			selection.subSelection = best;
		}
		return selection;
	}

	private static boolean mul(Selection s, MinMaxFade c, double value) {
		if (c == null) {
			return true;
		}
		double v = c.volume(value);
		if (v <= 0.0) {
			return false;
		}
		s.mulCondition(v);
		return true;
	}

	private boolean mulSpecial(Selection s, AmbientEnvironment env) {
		if (relativeHeight == null) {
			return true;
		}
		double v = ((MinMaxFadeSpecial) relativeHeight).volume(env.relativeMinHeight, env.relativeHeight,
				env.relativeMaxHeight);
		if (v <= 0.0) {
			return false;
		}
		s.mulCondition(v);
		return true;
	}

	// ---- AmbientSoundProperties' accessors ----

	double getFadeInVolume(AmbientEngine e) {
		return fadeInVolume != null ? fadeInVolume : fadeVolume != null ? fadeVolume : e.fadeVolume;
	}

	double getFadeOutVolume(AmbientEngine e) {
		return fadeOutVolume != null ? fadeOutVolume : fadeVolume != null ? fadeVolume : e.fadeVolume;
	}

	double getFadeInPitch(AmbientEngine e) {
		return fadeInPitch != null ? fadeInPitch : fadePitch != null ? fadePitch : e.fadePitch;
	}

	double getFadeOutPitch(AmbientEngine e) {
		return fadeOutPitch != null ? fadeOutPitch : fadePitch != null ? fadePitch : e.fadePitch;
	}

	float getPitch(AmbientEnvironment env) {
		float p = pitch != null ? (float) pitch.doubleValue() : 1.0F;
		return underwaterPitch != null ? p + (float) underwaterPitch.getValue(env.underwater) : p;
	}

	/** AmbientSelection.assignProperties: every property this condition sets (pitch and random-offset always do) */
	void assignPropertiesTo(AmbientCondition p) {
		if (transition != null) {
			p.transition = transition;
		}
		if (pitch != null) {
			p.pitch = pitch;
		}
		if (fadeVolume != null) {
			p.fadeVolume = fadeVolume;
		}
		if (fadeInVolume != null) {
			p.fadeInVolume = fadeInVolume;
		}
		if (fadeOutVolume != null) {
			p.fadeOutVolume = fadeOutVolume;
		}
		if (fadePitch != null) {
			p.fadePitch = fadePitch;
		}
		if (fadeInPitch != null) {
			p.fadeInPitch = fadeInPitch;
		}
		if (fadeOutPitch != null) {
			p.fadeOutPitch = fadeOutPitch;
		}
		if (mute != null) {
			p.mute = mute;
		}
		if (mutePriority != null) {
			p.mutePriority = mutePriority;
		}
		p.randomOffset = randomOffset;
		if (pause != null) {
			p.pause = pause;
		}
		if (length != null) {
			p.length = length;
		}
		if (underwaterPitch != null) {
			p.underwaterPitch = underwaterPitch;
		}
		if (channel != null) {
			p.channel = channel;
		}
	}

	// ---- json ----

	static Double dbl(JSONObject o, String k) {
		return o.has(k) && !o.isNull(k) ? o.optDouble(k) : null;
	}

	static Boolean bool(JSONObject o, String k) {
		return o.has(k) && !o.isNull(k) ? o.optBoolean(k) : null;
	}

	static String[] strings(JSONObject o, String k) {
		if (!o.has(k) || o.isNull(k)) {
			return null;
		}
		JSONArray a = o.optJSONArray(k);
		if (a == null) {
			return new String[] { o.optString(k) };
		}
		String[] s = new String[a.length()];
		for (int i = 0; i < s.length; ++i) {
			s[i] = a.optString(i);
		}
		return s;
	}

	// ---- AmbientVolume, AmbientSelection, AmbientSelectionMulti ----

	static class Volume {
		static final Volume SILENT = new Volume(0.0, 0.0) {
			void mulCondition(double v) {
			}

			void mulSetting(double v) {
			}
		};
		static final Volume MAX = new Volume(1.0, 1.0) {
			void mulCondition(double v) {
			}

			void mulSetting(double v) {
			}
		};
		private double conditionVolume, settingVolume;

		Volume(double conditionVolume, double settingVolume) {
			this.conditionVolume = conditionVolume;
			this.settingVolume = settingVolume;
		}

		void assign(Volume v) {
			conditionVolume = v.conditionVolume;
			settingVolume = v.settingVolume;
		}

		void setConditionDirect(double v) {
			conditionVolume = v;
		}

		void mulVolume(Volume v) {
			mulCondition(v.conditionVolume());
			mulSetting(v.settingVolume());
		}

		void mulCondition(double v) {
			conditionVolume *= v;
		}

		void mulSetting(double v) {
			settingVolume *= v;
		}

		double conditionVolume() {
			return conditionVolume;
		}

		double settingVolume() {
			return settingVolume;
		}

		double volume() {
			return settingVolume * conditionVolume;
		}

		Volume copy() {
			return new Volume(conditionVolume, settingVolume);
		}
	}

	static class Selection extends Volume {
		final AmbientCondition condition;
		Selection subSelection;

		Selection(AmbientCondition condition) {
			super(1.0, condition.volume);
			this.condition = condition;
		}

		@Override
		void assign(Volume v) {
			if (v instanceof Selection) {
				subSelection = ((Selection) v).subSelection;
			}
			super.assign(v);
		}

		@Override
		double conditionVolume() {
			return subSelection != null ? subSelection.conditionVolume() * super.conditionVolume() : super.conditionVolume();
		}

		@Override
		double settingVolume() {
			return subSelection != null ? subSelection.settingVolume() * super.settingVolume() : super.settingVolume();
		}

		@Override
		double volume() {
			return subSelection != null ? subSelection.volume() * super.volume() : super.volume();
		}

		Selection last() {
			return subSelection == null ? this : subSelection.last();
		}

		AmbientCondition getProperties() {
			AmbientCondition p = new AmbientCondition();
			assignProperties(p);
			return p;
		}

		void assignProperties(AmbientCondition p) {
			condition.assignPropertiesTo(p);
			if (subSelection != null) {
				subSelection.assignProperties(p);
			}
		}
	}

	static final class SelectionMulti extends Selection {
		final List<Selection> selections;

		SelectionMulti(Selection selection, List<Selection> selections) {
			super(selection.condition);
			assign(selection);
			this.selections = selections;
		}

		@Override
		double conditionVolume() {
			double v = 1.0;
			for (Selection s : selections) {
				v *= s.conditionVolume();
			}
			return v * super.conditionVolume();
		}

		@Override
		double settingVolume() {
			double v = 1.0;
			for (Selection s : selections) {
				v *= s.settingVolume();
			}
			return v * super.settingVolume();
		}

		@Override
		double volume() {
			double v = 1.0;
			for (Selection s : selections) {
				v *= s.volume();
			}
			return v * super.volume();
		}

		@Override
		void assignProperties(AmbientCondition p) {
			for (Selection s : selections) {
				s.assignProperties(p);
			}
			super.assignProperties(p);
		}
	}

	// ---- the min/max conditions ----

	static class MinMax {
		Double min, max;

		static <T extends MinMax> T read(JSONObject o, T into) {
			if (o == null) {
				return null;
			}
			into.min = dbl(o, "min");
			into.max = dbl(o, "max");
			if (into instanceof MinMaxFade) {
				((MinMaxFade) into).fade = dbl(o, "fade");
			}
			return into;
		}

		boolean is(double value) {
			return (min == null || value >= min) && (max == null || value <= max);
		}

		double randomValue() {
			if (max == null) {
				return min == null ? 0.0 : min;
			}
			double lo = min == null ? 0.0 : min;
			return Math.random() * (max - lo) + lo;
		}
	}

	static class MinMaxFade extends MinMax {
		Double fade;

		double volume(double value) {
			if (!is(value)) {
				return 0.0;
			}
			if (fade == null) {
				return 1.0;
			}
			double v = 1.0;
			if (min != null) {
				v = MathHelper.clamp(Math.abs(value - min) / fade, 0.0, 1.0);
			}
			if (max != null) {
				v = Math.min(v, MathHelper.clamp(Math.abs(value - max) / fade, 0.0, 1.0));
			}
			return v;
		}
	}

	static final class MinMaxFadeSpecial extends MinMaxFade {
		double volume(double min, double value, double max) {
			if (fade == null) {
				return is(value) ? 1.0 : 0.0;
			}
			double v = volume(value);
			return v == 1.0 ? v : Math.max(volume(min), volume(max));
		}
	}

	static final class Climb {
		double min, max, distanceFactor = 1.0;

		double getValue(double value) {
			if (max <= min) {
				max = min + 1.0;
			}
			return value / (max - min) * distanceFactor;
		}
	}

	// ---- AmbientTime ----

	abstract static class Time {
		abstract double value(AmbientEnvironment env);

		static final Time NONE = new Time() {
			double value(AmbientEnvironment env) {
				return 0.0;
			}
		};
		static final java.util.HashMap<String, Curve> PREMADE = new java.util.HashMap<>();
		static {
			premade("day", 1.0, 6.0, 18.0);
			PREMADE.put("night", new Curve(false, 0.0, 1.0, 4.0, 1.0, 5.0, 0.0, 19.0, 0.0, 19.0, 1.0, 24.0, 1.0));
			premade("sunrise", 1.0, 5.0, 6.0);
			premade("sunset", 1.0, 18.0, 19.0);
			premade("noon", 0.5, 10.5, 13.5);
			PREMADE.put("midnight", new Curve(false, 0.0, 1.0, 1.0, 1.0, 1.5, 0.0, 22.5, 0.0, 23.0, 1.0, 24.0, 1.0));
		}

		private static void premade(String name, double fade, double begin, double end) {
			PREMADE.put(name, new Curve(false, begin, 0.0, begin + fade, 1.0, end - fade, 1.0, end, 0.0));
		}

		static Time read(Object json) {
			if (json instanceof String) {
				Curve c = PREMADE.get(json);
				return c != null ? c : NONE;
			}
			if (json instanceof JSONArray) {
				JSONArray a = (JSONArray) json;
				if (a.length() == 0) {
					return NONE;
				}
				final List<Curve> all = new ArrayList<>();
				for (int i = 0; i < a.length(); ++i) {
					Curve c = PREMADE.get(a.optString(i));
					if (c != null) {
						all.add(c);
					}
				}
				return new Time() {
					double value(AmbientEnvironment env) {
						double v = 0.0;
						for (Curve c : all) {
							v = Math.max(v, c.value(env));
						}
						return v;
					}
				};
			}
			if (json instanceof JSONObject) {
				JSONObject o = (JSONObject) json;
				List<double[]> pts = new ArrayList<>();
				for (Iterator<String> it = o.keys(); it.hasNext();) {
					String k = it.next();
					try {
						pts.add(new double[] { Double.parseDouble(k), o.getDouble(k) });
					} catch (Exception e) {
						// "type"
					}
				}
				// org.json does not keep the file's order: the points are in time order
				pts.sort((x, y) -> Double.compare(x[0], y[0]));
				double[] t = new double[pts.size() * 2];
				for (int i = 0; i < pts.size(); ++i) {
					t[i * 2] = pts.get(i)[0];
					t[i * 2 + 1] = pts.get(i)[1];
				}
				return new Curve("angle".equalsIgnoreCase(o.optString("type", "")), t);
			}
			return NONE;
		}
	}

	/** AmbientTimeHour / AmbientTimeSunAngle: a closed piecewise-linear curve over the day */
	static final class Curve extends Time {
		final boolean angle;
		final double[] xs, ys;

		Curve(boolean angle, double... times) {
			this.angle = angle;
			double period = angle ? 360.0 : 24.0;
			List<double[]> p = new ArrayList<>();
			int n = times.length;
			if (n < 2) {
				xs = new double[] { 0.0, period };
				ys = new double[] { 0.0, 0.0 };
				return;
			}
			if (angle || times[n - 2] < period) {
				p.add(new double[] { times[n - 2] - period, times[n - 1] });
			}
			for (int i = 0; i + 1 < n; i += 2) {
				p.add(new double[] { times[i], times[i + 1] });
			}
			if (angle || times[0] > 0.0) {
				p.add(new double[] { times[0] + period, times[1] });
			}
			xs = new double[p.size()];
			ys = new double[p.size()];
			for (int i = 0; i < p.size(); ++i) {
				xs[i] = p.get(i)[0];
				ys[i] = p.get(i)[1];
			}
		}

		double value(AmbientEnvironment env) {
			double x = angle ? env.sunAngle : env.dayTimeHour;
			if (x <= xs[0]) {
				return ys[0];
			}
			for (int i = 1; i < xs.length; ++i) {
				if (x <= xs[i]) {
					double span = xs[i] - xs[i - 1];
					return span <= 0.0 ? ys[i] : ys[i - 1] + (ys[i] - ys[i - 1]) * (x - xs[i - 1]) / span;
				}
			}
			return ys[ys.length - 1];
		}
	}
}
