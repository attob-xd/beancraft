/*
 * Ported from AmbientSounds by CreativeMD (https://github.com/CreativeMD/AmbientSounds), LGPL-3.0-only - see
 * assets/ambientsounds/LICENSE.txt. Changes: AmbientEnvironment, TerrainEnvironment, BiomeEnvironment, AirPocket and
 * AirPocketScanner in one file for 1.12; the air pocket scan runs in slices across game ticks (the browser has one
 * thread for the game) with a smaller cap, and keeps positions as packed longs.
 */
package net.minecraft.client.audio.ambient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

/** what the engine knows about where the player is (AmbientEnvironment) */
final class AmbientEnvironment {

	AmbientEngine.Dimension dimension;
	boolean muted, night, raining, snowing, thundering;
	double sunAngle, dayTimeHour, rainSurfaceVolume;
	AmbientCondition.Volume biomeVolume = AmbientCondition.Volume.SILENT;
	final Map<String, AmbientCondition.Volume> biomeTypeVolumes = new HashMap<>();
	/** the biomes around, loudest first, and how strongly each is heard */
	final List<Biome> biomes = new ArrayList<>();
	final List<AmbientCondition.Volume> biomeVolumes = new ArrayList<>();
	double absoluteHeight, relativeHeight, relativeMinHeight, relativeMaxHeight, underwater, temperature;
	double averageHeight = 60.0;
	int minHeight = 60, maxHeight = 60;
	AirPocket airPocket = new AirPocket();
	Scanner scanner;

	boolean isRainAudibleAtSurface() {
		return rainSurfaceVolume > 0.0;
	}

	/** every few ticks: weather, height, water, time */
	void analyzeFast(AmbientEngine engine, AmbientEngine.Dimension dim, EntityPlayer player, World world, float partial) {
		dimension = dim;
		BlockPos feet = new BlockPos(player);
		raining = world.isRainingAt(feet.up());
		Biome biome = world.getBiome(feet);
		temperature = biome.getFloatTemperature(feet);
		snowing = temperature < 0.15F && world.isRaining();
		thundering = world.isThundering() && !snowing;
		absoluteHeight = player.posY + player.getEyeHeight();
		relativeHeight = absoluteHeight - averageHeight;
		relativeMinHeight = absoluteHeight - maxHeight;
		relativeMaxHeight = absoluteHeight - minHeight;
		int depth = 0;
		if (player.isInsideOfMaterial(Material.WATER)) {
			BlockPos p = new BlockPos(MathHelper.floor(player.posX),
					MathHelper.floor(absoluteHeight), MathHelper.floor(player.posZ));
			while (depth < 64 && world.getBlockState(p).getMaterial() == Material.WATER) {
				++depth;
				p.setPos(p.getX(), p.getY() + 1, p.getZ());
			}
		}
		underwater = depth;
		// the mod's sun angle: 0 at midnight, 180 at noon
		sunAngle = world.getCelestialAngle(partial) * 360.0 - 180.0;
		if (sunAngle < 0.0) {
			sunAngle += 360.0;
		}
		night = sunAngle < 90.0 || sunAngle > 270.0;
		dayTimeHour = sunAngle / 15.0;
	}

	/** every couple of seconds: the terrain height around, the biomes, and a new air pocket scan */
	void analyzeSlow(AmbientEngine engine, AmbientEngine.Dimension dim, EntityPlayer player, World world) {
		if (dim.averageHeight != null) {
			averageHeight = dim.averageHeight;
			minHeight = maxHeight = dim.averageHeight;
		} else {
			int sum = 0, count = 0, min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
			BlockPos center = new BlockPos(player);
			BlockPos pos = new BlockPos(0, 0, 0);
			for (int x = -engine.averageHeightScanCount; x <= engine.averageHeightScanCount; ++x) {
				for (int z = -engine.averageHeightScanCount; z <= engine.averageHeightScanCount; ++z) {
					int h = heightBlock(world, pos, center.getX() + engine.averageHeightScanDistance * x,
							center.getZ() + engine.averageHeightScanDistance * z);
					min = Math.min(h, min);
					max = Math.max(h, max);
					sum += h;
					++count;
				}
			}
			averageHeight = (double) sum / count;
			minHeight = min;
			maxHeight = max;
		}
		if (scanner == null) {
			scanner = new Scanner(engine, world, new BlockPos(player.posX, player.posY + player.getEyeHeight(), player.posZ));
		}
		scanBiomes(engine, player, world);
	}

	/** the air pocket scan's next slice; called every tick */
	void stepScan() {
		if (scanner != null && scanner.step(AmbientEngine.SCAN_STEPS_PER_TICK)) {
			airPocket = scanner.result();
			scanner = null;
		}
	}

	private static int heightBlock(World world, BlockPos pos, int x, int z) {
		pos.setPos(x, 0, z);
		if (!world.isBlockLoaded(pos)) {
			return 60;
		}
		for (int y = Math.min(255, world.getHeight(x, z)); y > 0; --y) {
			pos.setPos(pos.getX(), y, pos.getZ());
			IBlockState s = world.getBlockState(pos);
			Material m = s.getMaterial();
			if (s.isOpaqueCube() || m == Material.LEAVES || m == Material.WATER) {
				return y;
			}
		}
		return 0;
	}

	private void scanBiomes(AmbientEngine engine, EntityPlayer player, World world) {
		biomes.clear();
		biomeVolumes.clear();
		double highestRain = 0.0;
		AmbientCondition.Volume volume = biomeVolume;
		if (volume.volume() > 0.0) {
			BlockPos center = new BlockPos(player.posX, player.posY + player.getEyeHeight(), player.posZ);
			BlockPos pos = new BlockPos(0, 0, 0);
			for (int x = -engine.biomeScanCount; x <= engine.biomeScanCount; ++x) {
				for (int z = -engine.biomeScanCount; z <= engine.biomeScanCount; ++z) {
					pos.setPos(center.getX() + x * engine.biomeScanDistance, center.getY(),
							center.getZ() + z * engine.biomeScanDistance);
					Biome b = world.getBiome(pos);
					float v = (float) ((1.0 - center.distanceSq(pos) / engine.squaredBiomeDistance) * volume.conditionVolume());
					if (world.isRaining() && b.canRain() && b.getFloatTemperature(pos) >= 0.15F) {
						highestRain = Math.max(highestRain, v * volume.settingVolume());
					}
					int i = biomes.indexOf(b);
					if (i < 0) {
						biomes.add(b);
						biomeVolumes.add(new AmbientCondition.Volume(v, volume.settingVolume()));
					} else if (biomeVolumes.get(i).conditionVolume() < v) {
						biomeVolumes.get(i).setConditionDirect(v);
					}
				}
			}
			// loudest first
			for (int i = 1; i < biomes.size(); ++i) {
				for (int j = i; j > 0 && biomeVolumes.get(j).volume() > biomeVolumes.get(j - 1).volume(); --j) {
					biomes.set(j, biomes.set(j - 1, biomes.get(j)));
					biomeVolumes.set(j, biomeVolumes.set(j - 1, biomeVolumes.get(j)));
				}
			}
		}
		rainSurfaceVolume = highestRain;
	}

	/** what the last scan found: features, light, how open the space is (AirPocket) */
	static final class AirPocket {
		final Map<String, Double> features = new HashMap<>();
		final double averageLight, averageBlockLight, averageSkyLight, air, sky;

		AirPocket() {
			averageLight = averageBlockLight = averageSkyLight = 15.0;
			air = sky = 1.0;
		}

		AirPocket(AmbientEngine engine, Map<String, double[]> distribution, double light, double blockLight,
				double skyLight, double air, int sky) {
			averageLight = light;
			averageBlockLight = blockLight;
			averageSkyLight = skyLight;
			this.air = air < engine.airMin ? 0.0 : air > engine.airMax ? 1.0 : (air - engine.airMin) / (engine.airMax - engine.airMin);
			this.sky = sky < engine.skyMinCount ? 0.0 : sky > engine.skyMaxCount ? 1.0
					: (double) (sky - engine.skyMinCount) / (engine.skyMaxCount - engine.skyMinCount);
			for (AmbientEngine.Feature f : engine.features.values()) {
				double v = f.volume(distribution);
				if (v > 0.0) {
					features.put(f.name, v);
				}
			}
		}

		double volume(String[] names) {
			double v = 0.0;
			for (String n : names) {
				Double d = features.get(n);
				if (d != null) {
					v = Math.max(v, d);
				}
			}
			return v;
		}
	}

	/**
	 * AirPocketScanner: spreads out from the player's head through everything sound passes through (air, plants,
	 * water - not full blocks, doors, slabs, glass), always away from the start, nearest first. It counts the blocks it
	 * meets (weighted by how near), the light on the faces of the walls, how much open air there is and how much of
	 * it sees the sky. Done a slice per tick.
	 */
	static final class Scanner {
		private static final EnumFacing[] DIRS = { EnumFacing.EAST, EnumFacing.WEST, EnumFacing.UP, EnumFacing.DOWN,
				EnumFacing.SOUTH, EnumFacing.NORTH };
		/** the bit of each of DIRS in a position's "entered from" flags */
		private static final int UP_BIT = 1 << 2;
		private static final int SKY_SIZE = 256, SKY_HALF = 128;

		final AmbientEngine engine;
		final World world;
		final BlockPos origin;
		private final List<LongByteMap> layers = new ArrayList<>();
		private final Map<IBlockState, double[]> found = new HashMap<>();
		private final boolean[] sky = new boolean[SKY_SIZE * SKY_SIZE], skyTested = new boolean[SKY_SIZE * SKY_SIZE];
		private final BlockPos mutable = new BlockPos(0, 0, 0);
		private double distributionCounter;
		private int totalSize, currentDistance, layerIndex, skyCounter, air;
		private long lightSum, blockLightSum, skyLightSum, faces;

		Scanner(AmbientEngine engine, World world, BlockPos origin) {
			this.engine = engine;
			this.world = world;
			this.origin = origin;
			LongByteMap first = new LongByteMap();
			first.put(origin.toLong(), (byte) 0x3F);
			layers.add(first);
		}

		/** scans up to budget positions; true once finished */
		boolean step(int budget) {
			while (currentDistance < layers.size()) {
				LongByteMap layer = layers.get(currentDistance);
				while (layerIndex < layer.capacity()) {
					int i = layerIndex++;
					if (!layer.used[i]) {
						continue;
					}
					scan(currentDistance, BlockPos.fromLong(layer.keys[i]), layer.vals[i]);
					if (--budget <= 0) {
						return false;
					}
				}
				layers.set(currentDistance, null); // done with it
				++currentDistance;
				layerIndex = 0;
			}
			return true;
		}

		AirPocket result() {
			Map<String, double[]> distribution = new HashMap<>();
			for (Map.Entry<String, AmbientBlocks.Group> g : engine.blockGroups.entrySet()) {
				double count = 0.0;
				for (Map.Entry<IBlockState, double[]> s : found.entrySet()) {
					if (g.getValue().is(s.getKey())) {
						count += s.getValue()[0];
					}
				}
				distribution.put(g.getKey(), new double[] { count, distributionCounter > 0.0 ? count / distributionCounter : 0.0 });
			}
			double f = faces == 0 ? 1.0 : faces;
			return new AirPocket(engine, distribution, faces == 0 ? 15.0 : lightSum / f, faces == 0 ? 15.0 : blockLightSum / f,
					faces == 0 ? 15.0 : skyLightSum / f, (double) air / engine.maxAirPocketCount, skyCounter);
		}

		private int skyIndex(int x, int z) {
			int dx = x - origin.getX() + SKY_HALF, dz = z - origin.getZ() + SKY_HALF;
			return dx < 0 || dz < 0 || dx >= SKY_SIZE || dz >= SKY_SIZE ? -1 : dx * SKY_SIZE + dz;
		}

		private boolean isSky(int x, int z) {
			int i = skyIndex(x, z);
			return i >= 0 && sky[i];
		}

		private void findState(IBlockState state, int distance) {
			double factor = engine.airWeightFactor(distance);
			distributionCounter += factor;
			double[] c = found.get(state);
			if (c == null) {
				found.put(state, new double[] { factor });
			} else {
				c[0] += factor;
			}
		}

		private boolean hearThrough(IBlockState state, BlockPos pos) {
			return state.getMaterial() == Material.AIR || (!state.isFullCube() && !engine.considerSolid.is(state));
		}

		private void scan(int distance, BlockPos pos, byte flags) {
			if (!world.isBlockLoaded(pos)) {
				return;
			}
			IBlockState state = world.getBlockState(pos);
			int si = skyIndex(pos.getX(), pos.getZ());
			if (si >= 0 && !skyTested[si] && (flags & UP_BIT) != 0) {
				skyTested[si] = true;
				if (canSeeSkyConsiderSolids(pos)) {
					sky[si] = true;
					if (distance < engine.skyDistance) {
						skyCounter = (int) (skyCounter + (engine.skyDistance - distance));
					}
					if (distance < engine.airSkyDistance) {
						// the mod divides two ints here: each sky column near enough counts the full weight
						air = (int) (air + (1 - distance / engine.airSkyDistance) * engine.airSkyWeight);
					}
				}
			}
			if (hearThrough(state, pos)) {
				if (state.getMaterial() != Material.AIR) {
					findState(state, distance);
				}
				if (distance < engine.airDistance) {
					++air;
				}
				++distance;
				int x = pos.getX(), z = pos.getZ();
				if (isSky(x, z) && isSky(x - 1, z) && isSky(x, z - 1) && isSky(x + 1, z) && isSky(x, z + 1)) {
					return; // open sky all around: nothing more to learn going this way
				}
				for (int d = 0; d < DIRS.length; ++d) {
					if (totalSize > engine.airPocketCount) {
						return;
					}
					EnumFacing dir = DIRS[d];
					int p, o;
					switch (dir.getAxis()) {
					case X:
						p = pos.getX();
						o = origin.getX();
						break;
					case Y:
						p = pos.getY();
						o = origin.getY();
						break;
					default:
						p = pos.getZ();
						o = origin.getZ();
						break;
					}
					if (dir.getAxisDirection() == EnumFacing.AxisDirection.POSITIVE ? p + 1 > o : p - 1 < o) {
						BlockPos next = pos.offset(dir);
						if (next.getY() < 0 || next.getY() > 255) {
							continue;
						}
						while (layers.size() <= distance) {
							layers.add(new LongByteMap());
						}
						layers.get(distance).or(next.toLong(), (byte) (1 << opposite(d)));
						++totalSize;
					}
				}
			} else {
				for (int d = 0; d < DIRS.length; ++d) {
					if ((flags & (1 << d)) == 0) {
						continue;
					}
					BlockPos other = pos.offset(DIRS[d]);
					int bl = world.getLightFor(EnumSkyBlock.BLOCK, other), sl = world.getLightFor(EnumSkyBlock.SKY, other);
					lightSum += Math.max(bl, sl);
					blockLightSum += bl;
					skyLightSum += sl;
					++faces;
				}
				findState(state, distance);
			}
		}

		private static int opposite(int d) {
			return d ^ 1; // DIRS pairs each side with its opposite
		}

		private boolean canSeeSkyConsiderSolids(BlockPos pos) {
			mutable.setPos(pos.getX(), pos.getY() + 1, pos.getZ());
			if (!world.canSeeSky(mutable)) {
				return false;
			}
			int top = Math.min(255, world.getHeight(pos.getX(), pos.getZ()));
			for (int y = mutable.getY(); y <= top; ++y) {
				mutable.setPos(mutable.getX(), y, mutable.getZ());
				if (!hearThrough(world.getBlockState(mutable), mutable)) {
					return false;
				}
			}
			return true;
		}
	}

	/** an open-addressing map of packed block positions to a few flag bits */
	static final class LongByteMap {
		long[] keys = new long[64];
		byte[] vals = new byte[64];
		boolean[] used = new boolean[64];
		int size;

		int capacity() {
			return keys.length;
		}

		void put(long k, byte v) {
			or(k, v);
		}

		void or(long k, byte v) {
			if ((size + 1) * 2 > keys.length) {
				grow();
			}
			int mask = keys.length - 1;
			int i = (int) (mix(k) & mask);
			while (used[i]) {
				if (keys[i] == k) {
					vals[i] |= v;
					return;
				}
				i = (i + 1) & mask;
			}
			used[i] = true;
			keys[i] = k;
			vals[i] = v;
			++size;
		}

		private void grow() {
			long[] ok = keys;
			byte[] ov = vals;
			boolean[] ou = used;
			keys = new long[ok.length * 2];
			vals = new byte[ok.length * 2];
			used = new boolean[ok.length * 2];
			size = 0;
			for (int i = 0; i < ok.length; ++i) {
				if (ou[i]) {
					or(ok[i], ov[i]);
				}
			}
		}

		private static long mix(long k) {
			k ^= k >>> 33;
			k *= 0xff51afd7ed558ccdL;
			k ^= k >>> 33;
			return k;
		}
	}
}
