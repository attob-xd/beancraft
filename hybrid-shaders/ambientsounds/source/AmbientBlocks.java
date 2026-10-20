/*
 * Ported from AmbientSounds by CreativeMD (https://github.com/CreativeMD/AmbientSounds), LGPL-3.0-only - see
 * assets/ambientsounds/LICENSE.txt. Changes: AmbientBlock/AmbientBlockGroup's 1.21 block names and tags, and its biome
 * names and tags, answered with 1.12's blocks and biomes.
 */
package net.minecraft.client.audio.ambient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockLeaves;
import net.minecraft.block.BlockLog;
import net.minecraft.block.BlockPane;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;

/** block groups ("#minecraft:leaves[persistent=false]", "minecraft:grass_block") and biome names/tags for 1.12 */
final class AmbientBlocks {

	interface StateTest {
		boolean is(IBlockState state);
	}

	/** a block group: any of its entries; the entries this version has no block for are left out */
	static final class Group {
		final List<StateTest> tests = new ArrayList<>();

		void add(String entry) {
			StateTest t = parse(entry);
			if (t != null) {
				tests.add(t);
			}
		}

		boolean is(IBlockState state) {
			for (int i = 0; i < tests.size(); ++i) {
				if (tests.get(i).is(state)) {
					return true;
				}
			}
			return false;
		}
	}

	/** the mod's (1.21) block names that are called something else in 1.12 */
	private static final Map<String, String[]> ALIASES = new HashMap<>();
	static {
		ALIASES.put("minecraft:grass_block", new String[] { "minecraft:grass" });
		ALIASES.put("minecraft:grass", new String[] { "minecraft:tallgrass" });
		ALIASES.put("minecraft:short_grass", new String[] { "minecraft:tallgrass" });
		ALIASES.put("minecraft:fern", new String[] { "minecraft:tallgrass" });
		ALIASES.put("minecraft:tall_grass", new String[] { "minecraft:double_plant" });
		ALIASES.put("minecraft:dead_bush", new String[] { "minecraft:deadbush" });
		ALIASES.put("minecraft:blue_ice", new String[] { "minecraft:packed_ice" });
		ALIASES.put("minecraft:water", new String[] { "minecraft:water", "minecraft:flowing_water" });
		ALIASES.put("minecraft:lava", new String[] { "minecraft:lava", "minecraft:flowing_lava" });
		ALIASES.put("minecraft:podzol", new String[0]); // a dirt variant here; all of dirt would be too much
	}

	private static StateTest parse(String entry) {
		String name = entry;
		String props = null;
		int b = entry.indexOf('[');
		if (b >= 0 && entry.endsWith("]")) {
			name = entry.substring(0, b);
			props = entry.substring(b + 1, entry.length() - 1);
		}
		StateTest base = name.startsWith("#") ? tag(name.substring(1)) : block(name);
		if (base == null || props == null || props.isEmpty()) {
			return base;
		}
		final StateTest inner = base;
		final List<String[]> wanted = new ArrayList<>();
		for (String p : props.split(",")) {
			String[] kv = p.split("=");
			if (kv.length == 2) {
				wanted.add(kv);
			}
		}
		return state -> {
			if (!inner.is(state)) {
				return false;
			}
			for (String[] kv : wanted) {
				if (!hasProperty(state, kv[0], kv[1])) {
					return false;
				}
			}
			return true;
		};
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static boolean hasProperty(IBlockState state, String key, String value) {
		if ("persistent".equals(key) && state.getBlock() instanceof BlockLeaves) {
			// 1.13+ "persistent" (placed by a player) is 1.12's leaves without "decayable"
			return state.getValue(BlockLeaves.DECAYABLE) != Boolean.parseBoolean(value);
		}
		for (IProperty p : state.getPropertyNames()) {
			if (p.getName().equals(key)) {
				return p.getName(state.getValue(p)).equals(value);
			}
		}
		return false;
	}

	private static StateTest block(String name) {
		String[] names = ALIASES.get(name.indexOf(':') < 0 ? "minecraft:" + name : name);
		if (names == null) {
			names = new String[] { name };
		}
		final List<Block> blocks = new ArrayList<>();
		for (String n : names) {
			ResourceLocation rl = new ResourceLocation(n);
			if (Block.REGISTRY.containsKey(rl)) {
				blocks.add(Block.REGISTRY.getObject(rl));
			}
		}
		if (blocks.isEmpty()) {
			return null; // lush caves, dripstone, sculk, other mods' blocks: not in this version
		}
		return state -> blocks.contains(state.getBlock());
	}

	private static StateTest tag(String tag) {
		switch (tag) {
		case "minecraft:leaves":
			return state -> state.getBlock() instanceof BlockLeaves;
		case "minecraft:logs":
			return state -> state.getBlock() instanceof BlockLog;
		case "minecraft:base_stone_overworld":
			return state -> state.getBlock() == Blocks.STONE;
		case "minecraft:doors":
			return state -> state.getBlock() instanceof BlockDoor;
		case "minecraft:impermeable":
			return state -> state.getBlock() == Blocks.GLASS || state.getBlock() == Blocks.STAINED_GLASS;
		case "minecraft:stairs":
			return state -> state.getBlock() instanceof BlockStairs;
		case "minecraft:slabs":
			return state -> state.getBlock() instanceof BlockSlab;
		case "minecraft:trapdoors":
			return state -> state.getBlock() instanceof BlockTrapDoor;
		case "c:glass_panes":
			return state -> state.getBlock() instanceof BlockPane;
		default:
			return null;
		}
	}

	// ---- biomes ----

	/** AmbientSounds' BiomeCondition: a name pattern, or (#) a tag pattern */
	static final class BiomeCondition {
		final boolean tag;
		final Pattern pattern;

		BiomeCondition(String name) {
			tag = name.startsWith("#");
			pattern = Pattern.compile(".*" + Pattern.quote(tag ? name.substring(1) : name).replace("*", "\\E.*\\Q") + ".*");
		}

		static BiomeCondition[] of(String[] names) {
			if (names == null) {
				return null;
			}
			BiomeCondition[] c = new BiomeCondition[names.length];
			for (int i = 0; i < names.length; ++i) {
				c[i] = new BiomeCondition(names[i]);
			}
			return c;
		}
	}

	/** what 1.13+ calls 1.12's biomes, so the mod's biome names still find them */
	private static final Map<String, String> NEW_NAMES = new HashMap<>();
	static {
		NEW_NAMES.put("roofed_forest", "dark_forest");
		NEW_NAMES.put("mutated_roofed_forest", "dark_forest");
		NEW_NAMES.put("mutated_forest", "flower_forest");
		NEW_NAMES.put("mesa", "badlands");
		NEW_NAMES.put("mesa_rock", "wooded_badlands");
		NEW_NAMES.put("mesa_clear_rock", "badlands");
		NEW_NAMES.put("mutated_mesa", "eroded_badlands");
		NEW_NAMES.put("extreme_hills", "windswept_hills");
		NEW_NAMES.put("smaller_extreme_hills", "windswept_hills");
		NEW_NAMES.put("extreme_hills_with_trees", "windswept_forest");
		NEW_NAMES.put("mutated_extreme_hills", "windswept_gravelly_hills");
		NEW_NAMES.put("mushroom_island", "mushroom_fields");
		NEW_NAMES.put("mushroom_island_shore", "mushroom_fields");
		NEW_NAMES.put("ice_flats", "snowy_plains");
		NEW_NAMES.put("ice_mountains", "snowy_slopes");
		NEW_NAMES.put("mutated_ice_flats", "ice_spikes");
		NEW_NAMES.put("taiga_cold", "snowy_taiga");
		NEW_NAMES.put("taiga_cold_hills", "snowy_taiga");
		NEW_NAMES.put("redwood_taiga", "old_growth_pine_taiga");
		NEW_NAMES.put("mutated_redwood_taiga", "old_growth_spruce_taiga");
		NEW_NAMES.put("swampland", "swamp");
		NEW_NAMES.put("mutated_swampland", "swamp");
		NEW_NAMES.put("beaches", "beach");
		NEW_NAMES.put("cold_beach", "snowy_beach");
		NEW_NAMES.put("stone_beach", "stony_shore");
		NEW_NAMES.put("savanna_rock", "savanna_plateau");
		NEW_NAMES.put("mutated_savanna", "windswept_savanna");
		NEW_NAMES.put("mutated_plains", "sunflower_plains");
		NEW_NAMES.put("hell", "nether_wastes");
		NEW_NAMES.put("sky", "the_end");
		NEW_NAMES.put("void", "the_void");
		NEW_NAMES.put("jungle_edge", "sparse_jungle");
		NEW_NAMES.put("mutated_birch_forest", "old_growth_birch_forest");
		NEW_NAMES.put("frozen_river", "frozen_river");
	}

	/** the 1.13+ biome tags (is_forest, is_ocean...) that 1.12 doesn't have, from the biome's name */
	private static List<String> tags(String n) {
		List<String> t = new ArrayList<>();
		if (n.contains("forest")) {
			t.add("minecraft:is_forest");
		}
		if (n.contains("taiga")) {
			t.add("minecraft:is_taiga");
			t.add("minecraft:is_forest");
		}
		if (n.contains("savanna")) {
			t.add("minecraft:is_savanna");
		}
		if (n.contains("river")) {
			t.add("minecraft:is_river");
		}
		if (n.contains("ocean")) {
			t.add("minecraft:is_ocean");
			if (n.contains("deep")) {
				t.add("minecraft:is_deep_ocean");
			}
		}
		if (n.contains("extreme_hills")) {
			t.add("minecraft:is_mountain");
		}
		if (n.contains("hills")) {
			t.add("minecraft:is_hill");
		}
		if (n.contains("jungle")) {
			t.add("minecraft:is_jungle");
		}
		if (n.contains("beach")) {
			t.add("minecraft:is_beach");
		}
		if (n.contains("mesa")) {
			t.add("minecraft:is_badlands");
		}
		if (n.equals("hell")) {
			t.add("minecraft:is_nether");
		}
		if (n.equals("sky")) {
			t.add("minecraft:is_end");
		}
		return t;
	}

	private static final Map<Biome, String[][]> NAMES = new HashMap<>();

	/** [names to match a plain condition against, tags] */
	private static String[][] names(Biome biome) {
		String[][] n = NAMES.get(biome);
		if (n == null) {
			ResourceLocation rl = Biome.REGISTRY.getNameForObject(biome);
			String path = rl == null ? "unknown" : rl.getResourcePath();
			String alias = NEW_NAMES.get(path);
			n = new String[][] { alias == null ? new String[] { "minecraft:" + path }
					: new String[] { "minecraft:" + path, "minecraft:" + alias }, tags(path).toArray(new String[0]) };
			NAMES.put(biome, n);
		}
		return n;
	}

	static boolean matches(BiomeCondition[] conditions, Biome biome) {
		String[][] n = names(biome);
		for (BiomeCondition c : conditions) {
			for (String s : c.tag ? n[1] : n[0]) {
				if (c.pattern.matcher(s).matches()) {
					return true;
				}
			}
		}
		return false;
	}
}
