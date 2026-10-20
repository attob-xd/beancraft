/*
 * Ported from AmbientSounds by CreativeMD (https://github.com/CreativeMD/AmbientSounds), LGPL-3.0-only - see
 * assets/ambientsounds/LICENSE.txt. Changes: AmbientTickHandler.onTick as the client's entry point, behind the
 * "AmbientSounds" switch in the Mods screen.
 */
package net.minecraft.client.audio.ambient;

import net.minecraft.client.Minecraft;
import net.minecraft.util.SoundCategory;
import net.minecraft.world.World;

/**
 * hybrid: AmbientSounds - birds in forests, wind up high and in the open, crickets at night, waves at the beach, drips
 * and rumbles in caves, the nether's moan; each fading in and out with where you are, the time and the weather.
 * <p>
 * The engine is the mod's own "basic" engine (assets/ambientsounds/basic, made by tools/ambient/convert_ambientsounds.py
 * from the mod's jar); its sounds are downloaded the first time each one plays (ambientsounds/ next to the page), so
 * they cost nothing until you hear them.
 */
public final class HybridAmbientSounds {

	private static AmbientEngine engine;
	private static AmbientEnvironment environment;
	private static boolean failed;
	private static int timer;
	private static World lastWorld;
	private static final boolean DEBUG_LOG = false; // logs what the engine sees every 2 s

	private HybridAmbientSounds() {
	}

	public static boolean enabled() {
		Minecraft mc = Minecraft.getMinecraft();
		return mc.gameSettings != null && mc.gameSettings.modAmbientSounds;
	}

	/** resources reloaded (or the mod switched): start over with a freshly loaded engine */
	public static void reload() {
		if (engine != null) {
			engine.stopEngine();
			engine.stopAll();
		}
		engine = null;
		environment = null;
		failed = false;
	}

	/** once a game tick, from SoundManager.updateAllSounds (AmbientTickHandler.onTick) */
	public static void tick() {
		Minecraft mc = Minecraft.getMinecraft();
		World world = mc.world;
		boolean on = enabled() && world != null && mc.player != null
				&& mc.gameSettings.getSoundLevel(SoundCategory.AMBIENT) > 0.0F
				&& mc.gameSettings.getSoundLevel(SoundCategory.MASTER) > 0.0F;
		if (!on || world != lastWorld) {
			lastWorld = world;
			if (engine != null && (!engine.activeRegions.isEmpty() || !engine.streams.isEmpty())) {
				engine.stopEngine();
				engine.stopAll();
			}
			environment = null;
			if (!on) {
				return;
			}
		}
		if (mc.isGamePaused()) {
			return;
		}
		if (engine == null) {
			if (failed) {
				return;
			}
			try {
				engine = AmbientEngine.load();
			} catch (Throwable t) {
				failed = true;
				AmbientEngine.LOGGER.error("AmbientSounds' engine could not be loaded, no ambient sounds will play", t);
				return;
			}
		}
		if (environment == null) {
			environment = new AmbientEnvironment();
		}
		AmbientEngine.Dimension dim = engine.getDimension(world);
		if (environment.dimension != dim) {
			engine.changeDimension(environment);
			environment.dimension = dim;
		}
		if (timer % engine.environmentTickTime == 0) {
			environment.analyzeSlow(engine, dim, mc.player, world);
			if (DEBUG_LOG) {
				StringBuilder b = new StringBuilder("[AMBIENT] dim=" + dim.name + " night=" + environment.night + " h=" + (int) environment.dayTimeHour
						+ " rel=" + (int) environment.relativeHeight + " air=" + environment.airPocket.air + " sky=" + environment.airPocket.sky
						+ " features=" + environment.airPocket.features + " biomes=");
				for (int i = 0; i < environment.biomes.size(); ++i) {
					b.append(net.minecraft.world.biome.Biome.REGISTRY.getNameForObject(environment.biomes.get(i))).append(' ');
				}
				b.append(" types=").append(environment.biomeTypeVolumes.keySet()).append(" active=");
				for (AmbientEngine.Region r : engine.activeRegions) {
					b.append(r.name).append(r.playing.size()).append(' ');
				}
				AmbientEngine.LOGGER.info(b.toString());
			}
		}
		environment.stepScan();
		if (timer % engine.soundTickTime == 0) {
			environment.analyzeFast(engine, dim, mc.player, world, 1.0F);
			environment.dimension.manipulateEnvironment(environment);
			engine.tick(environment);
		}
		engine.fastTick(environment);
		++timer;
	}
}
