/*
 * Ported from AmbientSounds by CreativeMD (https://github.com/CreativeMD/AmbientSounds), LGPL-3.0-only - see
 * assets/ambientsounds/LICENSE.txt. Changes: AmbientSound and its SoundStream for 1.12's sound system (a MovingSound
 * the engine turns up and down each tick); no random start offset (the browser plays a clip from its start).
 */
package net.minecraft.client.audio.ambient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.json.JSONObject;

import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.MovingSound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.MathHelper;

/** one sound of a region or sound collection, fading in and out with its conditions (AmbientSound) */
final class AmbientSound extends AmbientCondition {

	private static final Random RANDOM = new Random();

	double volumeSetting = 1.0;
	String name;
	ResourceLocation[] files;
	double[] chances;
	String[] category;
	Stream stream1, stream2;
	boolean active;
	float cachedAimedConditionVolume, cachedAimedOutputVolume, currentConditionVolume, currentOutputVolume, aimedPitch;
	Volume aimedVolume;
	int transitionTick, transitionTime, pauseTimer = -1;
	AmbientCondition currentProperties = new AmbientCondition();
	AmbientEngine engine;
	List<AmbientEngine.Category> categories;
	private JSONObject json;

	@Override
	void read(JSONObject o) {
		super.read(o);
		json = o;
		name = o.optString("name", null);
		String[] f = strings(o, "files");
		if (f != null) {
			files = new ResourceLocation[f.length];
			for (int i = 0; i < f.length; ++i) {
				files[i] = new ResourceLocation(f[i]);
			}
		}
		if (o.optJSONArray("chances") != null) {
			chances = new double[o.getJSONArray("chances").length()];
			for (int i = 0; i < chances.length; ++i) {
				chances[i] = o.getJSONArray("chances").optDouble(i);
			}
		}
		category = strings(o, "category");
	}

	@Override
	void init(AmbientEngine engine) {
		if (files == null || files.length == 0) {
			AmbientEngine.LOGGER.warn("Ambient sound {} has no sound files", name);
			files = new ResourceLocation[0];
			return;
		}
		super.init(engine);
		this.engine = engine;
		if (chances == null || chances.length != files.length) {
			chances = new double[files.length];
			Arrays.fill(chances, 1.0 / files.length);
		}
		if (category != null && category.length > 0) {
			categories = new ArrayList<>();
			for (String c : category) {
				AmbientEngine.Category cat = engine.getSoundCategory(c);
				if (cat != null) {
					categories.add(cat);
				} else {
					AmbientEngine.LOGGER.warn("Ambient sound {}: no sound category {}", name, c);
				}
			}
		}
	}

	AmbientSound copy() {
		AmbientSound s = new AmbientSound();
		s.read(json);
		s.name = name;
		s.init(engine);
		return s;
	}

	private int randomFile() {
		return files.length == 1 ? 0 : RANDOM.nextInt(files.length);
	}

	private int randomFileExcept(int i) {
		if (files.length == 2) {
			return i == 0 ? 1 : 0;
		}
		int index = RANDOM.nextInt(files.length - 1);
		return index >= i ? index + 1 : index;
	}

	/** every tick: fade towards the aimed volume and pitch, start, cross-fade or stop the streams */
	boolean fastTick(AmbientEnvironment env) {
		if (files.length == 0) {
			return false;
		}
		if (currentConditionVolume < cachedAimedConditionVolume) {
			currentConditionVolume += Math.min(currentProperties.getFadeInVolume(engine), cachedAimedConditionVolume - currentConditionVolume);
		} else if (currentConditionVolume > cachedAimedConditionVolume) {
			currentConditionVolume -= Math.min(currentProperties.getFadeOutVolume(engine), currentConditionVolume - cachedAimedConditionVolume);
		}
		if (currentOutputVolume < cachedAimedOutputVolume) {
			currentOutputVolume += Math.min(currentProperties.getFadeInVolume(engine), cachedAimedOutputVolume - currentOutputVolume);
		} else if (currentOutputVolume > cachedAimedOutputVolume) {
			currentOutputVolume -= Math.min(currentProperties.getFadeOutVolume(engine), currentOutputVolume - cachedAimedOutputVolume);
		}
		if (stream1 != null) {
			if (stream2 != null) {
				stream1.transitionVolume = 1.0 - (double) transitionTick / transitionTime;
				stream2.transitionVolume = (double) transitionTick / transitionTime;
				if (transitionTick >= transitionTime) {
					engine.stop(stream1);
					stream1 = stream2;
					stream2 = null;
				}
				++transitionTick;
			} else {
				if (stream1.duration == -1 && currentProperties.length != null) {
					stream1.duration = (int) currentProperties.length.randomValue();
				} else if (stream1.duration > 0 && currentProperties.length == null) {
					stream1.duration = -1;
				}
				stream1.transitionVolume = 1.0;
				if (currentProperties.length != null) {
					if (currentProperties.pause != null || files.length <= 1) {
						int fadeOutTime = (int) Math.ceil(cachedAimedConditionVolume / currentProperties.getFadeOutVolume(engine));
						if (stream1.remaining() <= 0) {
							engine.stop(stream1);
							stream1 = null;
							pauseTimer = -1;
						} else if (fadeOutTime > stream1.remaining()) {
							stream1.transitionVolume = (double) stream1.remaining() / fadeOutTime;
						}
					} else if (stream1.remaining() <= 0) {
						transitionTick = 0;
						stream2 = play(randomFileExcept(stream1.index), true);
						transitionTime = currentProperties.transition != null ? currentProperties.transition : 60;
					}
				}
			}
			if (stream1 != null) {
				stream1.glidePitch();
				++stream1.ticksPlayed;
			}
			if (stream2 != null) {
				stream2.glidePitch();
				++stream2.ticksPlayed;
			}
		} else {
			if (stream2 != null) {
				engine.stop(stream2);
				stream2 = null;
			}
			if (pauseTimer == -1 && currentProperties.pause != null) {
				pauseTimer = (int) currentProperties.pause.randomValue();
			}
			if (pauseTimer <= 0) {
				stream1 = play(randomFile(), false);
			} else {
				--pauseTimer;
			}
		}
		return isAudible();
	}

	@Override
	Selection value(AmbientEnvironment env) {
		if (volumeSetting == 0.0) {
			return null;
		}
		Selection v = super.value(env);
		if (v != null && categories != null) {
			List<Selection> collected = new ArrayList<>();
			for (AmbientEngine.Category c : categories) {
				if (c.selection == null) {
					return null;
				}
				collected.add(c.selection);
			}
			v = new SelectionMulti(v, collected);
		}
		return v;
	}

	boolean isAudible() {
		return cachedAimedConditionVolume > 0.0F || currentConditionVolume > 0.0F || currentOutputVolume > 0.0F;
	}

	/** every few ticks: how loud it should be, given its region's selection (null when the region is silent) */
	boolean tick(AmbientEnvironment env, Selection selection) {
		if (files.length == 0) {
			return false;
		}
		Selection soundSelection = selection != null ? value(env) : null;
		if (soundSelection != null) {
			Selection last = selection.last();
			last.subSelection = soundSelection;
			aimedVolume = selection;
			cachedAimedConditionVolume = (float) selection.conditionVolume();
			cachedAimedOutputVolume = (float) (aimedVolume.volume() * volumeSetting * env.dimension.volumeSetting);
			currentProperties = selection.getProperties();
			last.subSelection = null;
			aimedPitch = MathHelper.clamp(currentProperties.getPitch(env), 0.5F, 2.0F);
		} else {
			aimedVolume = Volume.SILENT;
			cachedAimedConditionVolume = cachedAimedOutputVolume = 0.0F;
		}
		return isAudible();
	}

	private Stream play(int index, boolean transition) {
		Stream s = new Stream(index);
		s.pitchValue = aimedPitch;
		if (currentProperties.length != null) {
			s.duration = (int) currentProperties.length.randomValue();
		}
		if (transition) {
			s.transitionVolume = 0.0;
		}
		s.effectiveVolume = (float) s.combinedVolume();
		s.sync();
		engine.play(s);
		return s;
	}

	void deactivate() {
		active = false;
		if (stream1 != null) {
			engine.stop(stream1);
			stream1 = null;
		}
		if (stream2 != null) {
			engine.stop(stream2);
			stream2 = null;
		}
	}

	void onSoundFinished(Stream s) {
		if (stream1 == s) {
			stream1 = null;
			pauseTimer = -1;
		} else if (stream2 == s) {
			stream2 = null;
		}
	}

	boolean loop() {
		return currentProperties.length != null || (currentProperties.pause == null && files.length == 1);
	}

	private static SoundCategory category(String channel) {
		if (channel != null) {
			SoundCategory c = SoundCategory.getByName(channel);
			if (c != null) {
				return c;
			}
		}
		return SoundCategory.AMBIENT;
	}

	/** one playing file: not positioned, its volume and pitch set by the engine every tick (SoundStream) */
	final class Stream extends MovingSound {
		final int index;
		float effectiveVolume;
		double transitionVolume = 1.0, pitchValue;
		int duration = -1, ticksPlayed, waitTicks;
		boolean playedOnce, finished;

		Stream(int index) {
			super(new SoundEvent(files[index]), category(currentProperties.channel));
			this.index = index;
			this.attenuationType = ISound.AttenuationType.NONE;
			this.repeat = loop();
			this.repeatDelay = 0;
		}

		int remaining() {
			return duration - ticksPlayed;
		}

		double combinedVolume() {
			return currentOutputVolume * transitionVolume;
		}

		double mute() {
			return currentProperties.mute == null ? 0.0 : currentProperties.mute * currentConditionVolume;
		}

		double mutePriority() {
			return currentProperties.mutePriority != null ? currentProperties.mutePriority : 0.0;
		}

		void glidePitch() {
			if (pitchValue < aimedPitch) {
				pitchValue += Math.min(currentProperties.getFadeInPitch(engine), aimedPitch - pitchValue);
			} else if (pitchValue > aimedPitch) {
				pitchValue -= Math.min(currentProperties.getFadeOutPitch(engine), pitchValue - aimedPitch);
			}
		}

		void onStart() {
			finished = false;
			playedOnce = false;
		}

		void onFinished() {
			finished = true;
			onSoundFinished(this);
		}

		void stopNow() {
			donePlaying = true;
		}

		void sync() {
			volume = Math.max(0.0F, Math.min(1.0F, effectiveVolume));
			pitch = (float) pitchValue;
		}

		@Override
		public void update() {
			sync();
		}
	}
}
