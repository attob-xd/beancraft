/*
 * Ported from AmbientSounds by CreativeMD (https://github.com/CreativeMD/AmbientSounds), LGPL-3.0-only - see
 * assets/ambientsounds/LICENSE.txt. Changes: AmbientEngine, AmbientRegion, AmbientDimension, AmbientSoundCategory,
 * AmbientFeature(Selection), AmbientSoundEngine and AmbientTickHandler in one file for 1.12, read with org.json from
 * the files listed in basic/index.json (the browser's assets can't be listed), without the per-sound config screens.
 */
package net.minecraft.client.audio.ambient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.geo.GeoModel;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

/** AmbientSounds' engine: the loaded "basic" engine and what is playing */
final class AmbientEngine {

	static final Logger LOGGER = LogManager.getLogger("AmbientSounds");
	/** air pocket positions scanned per game tick (the mod scans on a thread of its own) */
	static final int SCAN_STEPS_PER_TICK = 1200;
	/** the most positions one scan looks at - the mod's 50000 is a desktop thread's worth */
	private static final int MAX_AIR_POCKET = 16000;

	final LinkedHashMap<String, Dimension> dimensions = new LinkedHashMap<>();
	final LinkedHashMap<String, Region> allRegions = new LinkedHashMap<>();
	final LinkedHashMap<String, Region> generalRegions = new LinkedHashMap<>();
	final List<Region> activeRegions = new ArrayList<>();
	final LinkedHashMap<String, Category> soundCategories = new LinkedHashMap<>();
	final List<Category> sortedSoundCategories = new ArrayList<>();
	final LinkedHashMap<String, AmbientBlocks.Group> blockGroups = new LinkedHashMap<>();
	final LinkedHashMap<String, List<AmbientSound>> soundCollections = new LinkedHashMap<>();
	final LinkedHashMap<String, Feature> features = new LinkedHashMap<>();
	final List<AmbientSound.Stream> streams = new ArrayList<>();
	final Dimension silentDim = new Dimension();
	AmbientBlocks.Group considerSolid = new AmbientBlocks.Group();
	final List<Double> airPocketDistanceFactor = new ArrayList<>();
	int maxAirPocketCount;
	double squaredBiomeDistance;

	String name, version, defaultBiomeType;
	int environmentTickTime = 40, soundTickTime = 4, averageHeightScanDistance = 2, averageHeightScanCount = 5,
			biomeScanDistance = 5, biomeScanCount = 3, airPocketCount = 50000, airDistance = 25, airSkyDistance = 13,
			skyMinCount = 10, skyMaxCount = 100;
	double airSkyWeight = 50.0, airMin = 0.1, airMax = 0.5, skyDistance = 20.0, fadeVolume = 0.005, fadePitch = 0.005;
	String[] biomeTypes = new String[0];

	static String read(String path) {
		try {
			return GeoModel.readText(new ResourceLocation("ambientsounds", path));
		} catch (java.io.IOException e) {
			throw new IllegalStateException("AmbientSounds: could not read " + path, e);
		}
	}

	/** AmbientEngine.attemptToLoadEngine with the engine named in config.json */
	static AmbientEngine load() {
		JSONObject config = new JSONObject(read("config.json"));
		String engineName = config.optString("default-engine", "basic");
		AmbientEngine e = new AmbientEngine();
		JSONObject o = new JSONObject(read(engineName + "/engine.json"));
		e.name = o.optString("name", engineName);
		e.version = o.optString("version", "?");
		e.environmentTickTime = Math.max(1, o.optInt("environment-tick-time", 40));
		e.soundTickTime = Math.max(1, o.optInt("sound-tick-time", 4));
		e.averageHeightScanDistance = o.optInt("average-height-scan-distance", 2);
		e.averageHeightScanCount = o.optInt("average-height-scan-count", 5);
		e.biomeScanDistance = o.optInt("biome-scan-distance", 5);
		e.biomeScanCount = o.optInt("biome-scan-count", 3);
		e.airPocketCount = Math.min(MAX_AIR_POCKET, o.optInt("air-pocket-count", 50000));
		e.airDistance = o.optInt("air-distance", 25);
		e.airSkyDistance = Math.max(1, o.optInt("air-sky-distance", 13));
		e.airSkyWeight = o.optDouble("air-sky-weight", 50.0);
		e.airMin = o.optDouble("air-min", 0.1);
		e.airMax = o.optDouble("air-max", 0.5);
		e.skyDistance = o.optDouble("sky-distance", 20.0);
		e.skyMinCount = o.optInt("sky-min-count", 10);
		e.skyMaxCount = o.optInt("sky-max-count", 100);
		e.fadeVolume = o.optDouble("fade-volume", 0.005);
		e.fadePitch = o.optDouble("fade-pitch", 0.005);
		String[] types = AmbientCondition.strings(o, "biome-types");
		e.biomeTypes = types != null ? types : new String[0];
		e.defaultBiomeType = o.optString("default-biome-type", null);
		String[] solids = AmbientCondition.strings(o, "solids");
		if (solids != null) {
			for (String s : solids) {
				e.considerSolid.add(s);
			}
		}
		JSONArray groups = o.optJSONArray("air-pocket-groups");
		if (groups != null) {
			for (int i = 0; i < groups.length(); ++i) {
				JSONObject g = groups.getJSONObject(i);
				for (int d = 0; d < g.optInt("distance"); ++d) {
					e.airPocketDistanceFactor.add(g.optDouble("weight", 1.0));
				}
			}
		}

		JSONObject index = new JSONObject(read(engineName + "/index.json"));
		for (String f : files(index, "dimensions")) {
			JSONObject j = new JSONObject(read(engineName + "/dimensions/" + f));
			Dimension dim = new Dimension();
			dim.name = f.replace(".json", "");
			dim.read(e, j);
			e.dimensions.put(dim.name, dim);
			if (dim.loadedRegions != null) {
				for (Region r : dim.loadedRegions.values()) {
					e.addRegion(r);
				}
			}
		}
		for (String f : files(index, "regions")) {
			Region r = new Region();
			r.read(new JSONObject(read(engineName + "/regions/" + f)));
			r.name = f.replace(".json", "");
			e.generalRegions.put(r.name, r);
			e.addRegion(r);
		}
		for (String f : files(index, "blockgroups")) {
			JSONArray a = new JSONArray(read(engineName + "/blockgroups/" + f));
			AmbientBlocks.Group g = new AmbientBlocks.Group();
			for (int i = 0; i < a.length(); ++i) {
				g.add(a.optString(i));
			}
			e.blockGroups.put(f.replace(".json", ""), g);
		}
		for (String f : files(index, "sound_collections")) {
			JSONObject j = new JSONObject(read(engineName + "/sound_collections/" + f));
			List<AmbientSound> list = new ArrayList<>();
			JSONArray a = j.optJSONArray("sounds");
			if (a != null) {
				for (int i = 0; i < a.length(); ++i) {
					AmbientSound s = new AmbientSound();
					s.read(a.getJSONObject(i));
					list.add(s);
				}
			}
			e.soundCollections.put(f.replace(".json", ""), list);
		}
		for (String f : files(index, "sound_categories")) {
			Category c = new Category();
			c.read(new JSONObject(read(engineName + "/sound_categories/" + f)));
			c.name = f.replace(".json", "");
			e.soundCategories.put(c.name, c);
		}
		for (String f : files(index, "features")) {
			Feature ft = new Feature();
			ft.read(new JSONObject(read(engineName + "/features/" + f)));
			ft.name = f.replace(".json", "");
			e.features.put(ft.name, ft);
		}
		e.silentDim.name = "silent";
		e.silentDim.volumeSetting = 0.0;
		e.silentDim.mute = true;
		e.init();
		LOGGER.info("Loaded AmbientEngine '{}' v{}: {} dimensions, {} features, {} block groups, {} regions, {} sound categories",
				e.name, e.version, e.dimensions.size(), e.features.size(), e.blockGroups.size(), e.allRegions.size(),
				e.soundCategories.size());
		return e;
	}

	/** a folder's files from index.json, in name order (the mod's resource listing order) */
	private static List<String> files(JSONObject index, String folder) {
		List<String> out = new ArrayList<>();
		JSONArray a = index.optJSONArray(folder);
		if (a != null) {
			for (int i = 0; i < a.length(); ++i) {
				out.add(a.optString(i));
			}
		}
		out.sort(null);
		return out;
	}

	Region getRegion(String n) {
		return allRegions.get(n);
	}

	Category getSoundCategory(String n) {
		return n == null ? null : soundCategories.get(n);
	}

	private void addRegion(Region r) {
		allRegions.put((r.dimension != null ? r.dimension.name + "." : "") + r.name, r);
		r.volumeSetting = 1.0;
	}

	/** the mod's airPocketVolume, kept as it is */
	static int airPocketVolume(int r) {
		int res = 0;
		for (int i = r; r > 0; --r) {
			int f = i == r ? 1 : i == r - 1 ? 4 : i == r - 2 ? 7 : 8;
			res = (int) (res + f * r * (r + 1) * 0.5);
		}
		return res;
	}

	private void init() {
		maxAirPocketCount = airPocketVolume(airDistance);
		for (Map.Entry<String, List<AmbientSound>> g : soundCollections.entrySet()) {
			for (AmbientSound s : g.getValue()) {
				s.name = g.getKey() + "." + s.name;
				s.init(this);
			}
		}
		for (Dimension d : dimensions.values()) {
			d.init(this);
		}
		for (Region r : allRegions.values()) {
			r.init(this);
		}
		for (Category c : soundCategories.values()) {
			c.init(this);
		}
		HashSet<String> unsorted = new HashSet<>(soundCategories.keySet());
		for (Category c : soundCategories.values()) {
			if (c.parent == null || c.parent.trim().isEmpty()) {
				c.postInit(unsorted);
				sortedSoundCategories.add(c);
			} else if (c.parentCategory == null) {
				LOGGER.error("Sound category {}: its parent '{}' does not exist", c.name, c.parent);
			}
		}
		squaredBiomeDistance = Math.pow(biomeScanCount * biomeScanDistance * 2, 2.0);
	}

	double airWeightFactor(int distance) {
		return distance >= airPocketDistanceFactor.size() ? 0.0 : airPocketDistanceFactor.get(distance);
	}

	Dimension getDimension(World world) {
		String n = "minecraft:" + world.provider.getDimensionType().getName();
		for (Dimension d : dimensions.values()) {
			if (d.is(n)) {
				return d;
			}
		}
		return silentDim;
	}

	void stopEngine() {
		for (Region r : activeRegions) {
			r.deactivate();
		}
		activeRegions.clear();
	}

	void tick(AmbientEnvironment env) {
		for (Category c : sortedSoundCategories) {
			c.tick(env, null);
		}
		if (env.dimension.loadedRegions != null) {
			for (Region r : env.dimension.loadedRegions.values()) {
				tickRegion(r, env);
			}
		}
		for (Region r : generalRegions.values()) {
			tickRegion(r, env);
		}
	}

	private void tickRegion(Region r, AmbientEnvironment env) {
		if (r.tick(env)) {
			if (!r.active) {
				r.active = true;
				activeRegions.add(r);
			}
		} else if (r.active) {
			r.deactivate();
			activeRegions.remove(r);
		}
	}

	void fastTick(AmbientEnvironment env) {
		mix();
		Iterator<Region> it = activeRegions.iterator();
		while (it.hasNext()) {
			Region r = it.next();
			if (!r.fastTick(env)) {
				r.deactivate();
				it.remove();
			}
		}
	}

	void changeDimension(AmbientEnvironment env) {
		if (env.dimension != null && env.dimension.loadedRegions != null) {
			for (Region r : env.dimension.loadedRegions.values()) {
				if (r.active) {
					r.deactivate();
					activeRegions.remove(r);
				}
			}
		}
	}

	// ---- AmbientSoundEngine: the streams playing, and the louder sounds muting the others ----

	void play(AmbientSound.Stream s) {
		Minecraft.getMinecraft().getSoundHandler().playSound(s);
		s.onStart();
		streams.add(s);
	}

	void stop(AmbientSound.Stream s) {
		s.stopNow();
		Minecraft.getMinecraft().getSoundHandler().stopSound(s);
		streams.remove(s);
	}

	void stopAll() {
		for (AmbientSound.Stream s : new ArrayList<>(streams)) {
			stop(s);
			s.onFinished();
		}
	}

	private void mix() {
		TreeMap<Double, Double> mutes = new TreeMap<>((a, b) -> Double.compare(b, a));
		for (AmbientSound.Stream s : streams) {
			double m = s.mute();
			if (m > 0.0) {
				mutes.merge(s.mutePriority(), m, Math::max);
			}
		}
		Iterator<AmbientSound.Stream> it = streams.iterator();
		while (it.hasNext()) {
			AmbientSound.Stream s = it.next();
			boolean playing = Minecraft.getMinecraft().getSoundHandler().isSoundPlaying(s);
			if (!playing && !s.playedOnce) {
				// not started yet - its file may still be downloading; give up on one that never comes
				if (++s.waitTicks > 600) {
					it.remove();
					s.stopNow();
					Minecraft.getMinecraft().getSoundHandler().stopSound(s);
					s.onFinished();
				}
				continue;
			}
			if (s.playedOnce && !playing) {
				it.remove();
				s.onFinished();
				Minecraft.getMinecraft().getSoundHandler().stopSound(s);
				continue;
			}
			s.playedOnce = true;
			if (mutes.isEmpty()) {
				s.effectiveVolume = (float) s.combinedVolume();
			} else {
				double mute = 0.0;
				for (Map.Entry<Double, Double> m : mutes.entrySet()) {
					if (s.mutePriority() < m.getKey() || s.mute() == 0.0) {
						mute = Math.max(m.getValue(), mute);
					} else {
						break;
					}
				}
				s.effectiveVolume = (float) (s.combinedVolume() * (1.0 - mute));
			}
		}
	}

	// ---- AmbientRegion ----

	static final class Region extends AmbientCondition {
		String name;
		AmbientSound[] sounds;
		String[] soundCollectionNames;
		double volumeSetting = 1.0;
		boolean active;
		LinkedHashMap<String, AmbientSound> loadedSounds;
		final List<AmbientSound> playing = new ArrayList<>();
		Dimension dimension;

		@Override
		void read(JSONObject o) {
			super.read(o);
			name = o.optString("name", name);
			JSONArray a = o.optJSONArray("sounds");
			if (a != null) {
				loadedSounds = new LinkedHashMap<>();
				for (int i = 0; i < a.length(); ++i) {
					AmbientSound s = new AmbientSound();
					s.read(a.getJSONObject(i));
					loadedSounds.put(s.name, s);
				}
			}
			soundCollectionNames = strings(o, "sound-collections");
		}

		@Override
		String regionName() {
			return name;
		}

		@Override
		void init(AmbientEngine engine) {
			super.init(engine);
			if (loadedSounds != null) {
				for (AmbientSound s : loadedSounds.values()) {
					s.init(engine);
				}
			}
			if (soundCollectionNames != null) {
				if (loadedSounds == null) {
					loadedSounds = new LinkedHashMap<>();
				}
				for (String c : soundCollectionNames) {
					List<AmbientSound> list = engine.soundCollections.get(c);
					if (list != null) {
						for (AmbientSound s : list) {
							loadedSounds.put(s.name, s.copy());
						}
					}
				}
			}
		}

		@Override
		Selection value(AmbientEnvironment env) {
			if (dimension != null && dimension != env.dimension) {
				return null;
			}
			if (volumeSetting == 0.0) {
				return null;
			}
			Selection s = super.value(env);
			if (s != null) {
				s.mulSetting(volumeSetting);
			}
			return s;
		}

		boolean fastTick(AmbientEnvironment env) {
			Iterator<AmbientSound> it = playing.iterator();
			while (it.hasNext()) {
				AmbientSound s = it.next();
				if (!s.fastTick(env)) {
					s.deactivate();
					it.remove();
				}
			}
			return !playing.isEmpty();
		}

		boolean tick(AmbientEnvironment env) {
			if (loadedSounds == null) {
				return false;
			}
			Selection selection = value(env);
			for (AmbientSound s : loadedSounds.values()) {
				if (s.tick(env, selection)) {
					if (!s.active) {
						s.active = true;
						playing.add(s);
					}
				} else if (s.active) {
					s.deactivate();
					playing.remove(s);
				}
			}
			return !playing.isEmpty();
		}

		void deactivate() {
			active = false;
			for (AmbientSound s : playing) {
				s.deactivate();
			}
			playing.clear();
		}
	}

	// ---- AmbientDimension ----

	static final class Dimension {
		String name;
		double volumeSetting = 1.0;
		boolean mute;
		LinkedHashMap<String, Region> loadedRegions;
		final LinkedHashMap<String, AmbientCondition> biomeTypeSelectors = new LinkedHashMap<>();
		AmbientCondition biomeSelector;
		Boolean night, rain, storm;
		Pattern[] dimensionPatterns, badDimensionPatterns;
		String[] dimensionNames, badDimensionNames;
		Integer averageHeight;

		void read(AmbientEngine engine, JSONObject o) {
			mute = o.optBoolean("mute", false);
			if (o.optJSONObject("biome-selector") != null) {
				biomeSelector = new AmbientCondition();
				biomeSelector.read(o.getJSONObject("biome-selector"));
			}
			night = AmbientCondition.bool(o, "night");
			rain = AmbientCondition.bool(o, "rain");
			storm = AmbientCondition.bool(o, "storm");
			dimensionNames = AmbientCondition.strings(o, "dimension-names");
			badDimensionNames = AmbientCondition.strings(o, "bad-dimension-names");
			averageHeight = o.has("average-height") ? o.optInt("average-height") : null;
			JSONArray rs = o.optJSONArray("regions");
			if (rs != null) {
				loadedRegions = new LinkedHashMap<>();
				for (int i = 0; i < rs.length(); ++i) {
					Region r = new Region();
					r.read(rs.getJSONObject(i));
					r.dimension = this;
					loadedRegions.put(r.name, r);
				}
			}
			for (String type : engine.biomeTypes) {
				JSONObject sel = o.optJSONObject(type + "-selector");
				if (sel != null) {
					AmbientCondition c = new AmbientCondition();
					c.read(sel);
					biomeTypeSelectors.put(type, c);
				}
			}
		}

		void init(AmbientEngine engine) {
			if (biomeSelector != null) {
				biomeSelector.init(engine);
			}
			for (AmbientCondition c : biomeTypeSelectors.values()) {
				c.init(engine);
			}
			badDimensionPatterns = patterns(badDimensionNames);
			dimensionPatterns = patterns(dimensionNames);
		}

		private static Pattern[] patterns(String[] names) {
			if (names == null) {
				return null;
			}
			Pattern[] p = new Pattern[names.length];
			for (int i = 0; i < names.length; ++i) {
				p[i] = Pattern.compile(".*" + names[i].toLowerCase().replace("*", ".*").replace("?", "\\?") + ".*");
			}
			return p;
		}

		boolean is(String dimensionName) {
			if (badDimensionPatterns != null) {
				for (Pattern p : badDimensionPatterns) {
					if (p.matcher(dimensionName).matches()) {
						return false;
					}
				}
			}
			if (dimensionPatterns != null) {
				for (Pattern p : dimensionPatterns) {
					if (p.matcher(dimensionName).matches()) {
						return true;
					}
				}
			}
			return dimensionNames == null;
		}

		void manipulateEnvironment(AmbientEnvironment env) {
			env.muted = mute;
			if (night != null) {
				env.night = night;
			}
			if (rain != null) {
				env.raining = rain;
			}
			if (storm != null) {
				env.thundering = storm;
			}
			if (biomeSelector != null) {
				AmbientCondition.Selection s = biomeSelector.value(env);
				env.biomeVolume = s != null ? s : AmbientCondition.Volume.SILENT;
			}
			env.biomeTypeVolumes.clear();
			for (Map.Entry<String, AmbientCondition> e : biomeTypeSelectors.entrySet()) {
				AmbientCondition.Selection s = e.getValue().value(env);
				env.biomeTypeVolumes.put(e.getKey(), s != null ? s : AmbientCondition.Volume.SILENT);
			}
		}
	}

	// ---- AmbientSoundCategory ----

	static final class Category extends AmbientCondition {
		String name, parent;
		double volumeSetting = 1.0;
		Category parentCategory;
		final List<Category> children = new ArrayList<>();
		Selection selection;

		@Override
		void read(JSONObject o) {
			super.read(o);
			parent = o.optString("parent", null);
		}

		@Override
		void init(AmbientEngine engine) {
			super.init(engine);
			parentCategory = engine.getSoundCategory(parent);
			if (parentCategory == this) {
				parentCategory = null;
			} else if (parentCategory != null) {
				parentCategory.children.add(this);
			}
		}

		void postInit(HashSet<String> remaining) {
			remaining.remove(name);
			for (Category c : children) {
				c.postInit(remaining);
			}
		}

		void tick(AmbientEnvironment env, Selection parentSelection) {
			selection = value(env);
			if (selection != null) {
				if (parentSelection != null) {
					selection.last().subSelection = parentSelection;
				}
				selection.mulSetting(volumeSetting);
			}
			for (Category c : children) {
				c.tick(env, selection);
			}
		}
	}

	// ---- AmbientFeature / AmbientFeatureSelection ----

	static class Feature {
		String name;
		String[] groups;
		Feature[] and, or, not;
		Double lowWeight, highWeight, lowCount, highCount;

		void read(JSONObject o) {
			groups = AmbientCondition.strings(o, "groups");
			and = list(o.optJSONArray("and"));
			or = list(o.optJSONArray("or"));
			not = list(o.optJSONArray("not"));
			lowWeight = AmbientCondition.dbl(o, "low-weight");
			highWeight = AmbientCondition.dbl(o, "high-weight");
			lowCount = AmbientCondition.dbl(o, "low-count");
			highCount = AmbientCondition.dbl(o, "high-count");
		}

		private static Feature[] list(JSONArray a) {
			if (a == null) {
				return null;
			}
			Feature[] f = new Feature[a.length()];
			for (int i = 0; i < f.length; ++i) {
				f[i] = new Feature();
				f[i].read(a.getJSONObject(i));
			}
			return f;
		}

		/** distribution: group -> [count, share] */
		double volume(Map<String, double[]> distribution) {
			double volume = 1.0;
			if (lowWeight != null || highWeight != null || lowCount != null || highCount != null) {
				double count = 0.0, share = 0.0;
				if (groups != null) {
					for (String g : groups) {
						double[] d = distribution.get(g);
						if (d != null) {
							count += d[0];
							share += d[1];
						}
					}
				}
				if (lowWeight != null || highWeight != null) {
					if (lowWeight != null && lowWeight > share) {
						return 0.0;
					}
					if (highWeight != null && !(highWeight < share)) {
						double low = lowWeight == null ? 0.0 : lowWeight;
						volume *= (share - low) / (highWeight - low);
					}
				}
				if (lowCount != null || highCount != null) {
					if (lowCount != null && lowCount > count) {
						return 0.0;
					}
					if (highCount != null && !(highCount < count)) {
						double low = lowCount == null ? 0.0 : lowCount;
						volume *= (count - low) / (highCount - low);
					}
				}
			}
			if (and != null) {
				for (Feature f : and) {
					volume *= f.volume(distribution);
				}
			}
			if (or != null) {
				double v = 0.0;
				for (Feature f : or) {
					v = Math.max(v, f.volume(distribution));
				}
				volume *= v;
			}
			if (not != null) {
				for (Feature f : not) {
					if (f.volume(distribution) > 0.0) {
						return 0.0;
					}
				}
			}
			return volume;
		}
	}
}
