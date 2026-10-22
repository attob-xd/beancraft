/*
 * Converted from Jade 5.3.2 by Snownee, CC BY-NC-SA 4.0 - see assets/minecraft/hybrid/jade_license.txt.
 * This file: mcp.mobius.waila.api.config.WailaConfig and HUDTheme (the same options and defaults), and the plugin
 * options of Jade's core and vanilla plugins; kept as JSON in the browser's storage ("jade").
 */
package net.minecraft.client.jade;

import java.nio.charset.StandardCharsets;

import org.json.JSONObject;

import net.lax1dude.eaglercraft.EagRuntime;

/** hybrid: Jade's settings */
public final class JadeConfig {

	/** HUDTheme: background, border gradient, title and text colors */
	public static final class Theme {
		public final String id, name;
		public final int background, gradientStart, gradientEnd, titleColor, textColor;
		public final boolean textShadow;

		Theme(String id, String name, int background, int gradientStart, int gradientEnd, int titleColor, int textColor) {
			this.id = id;
			this.name = name;
			this.background = background;
			this.gradientStart = gradientStart;
			this.gradientEnd = gradientEnd;
			this.titleColor = titleColor;
			this.textColor = textColor;
			this.textShadow = true;
		}
	}

	public static final Theme[] THEMES = { new Theme("jade:waila", "Waila", 0x100010, 0x5000FF, 0x28007F, 0xFFFFFF, 0xA0A0A0),
			new Theme("jade:dark", "Dark", 0x131313, 0x383838, 0x242424, 0xFFFFFF, 0xA0A0A0),
			new Theme("jade:create", "Create", 0x000000, 0x2A2626, 0x1A1717, 0xFFFFFF, 0xA0A0A0),
			new Theme("jade:top", "The One Probe", 0x55006699, 0xFF999999, 0xFF999999, 0xFFFFFF, 0xA0A0A0) };

	public enum DisplayMode {
		HOLD_KEY, TOGGLE, LITE
	}

	// ConfigGeneral
	public boolean displayTooltip = true, displayBlocks = true, displayEntities = true, hideFromDebug = true, showIcon = true;
	public DisplayMode displayMode = DisplayMode.TOGGLE;
	public boolean iconTop = true;
	public int maxHealthForRender = 40, maxHeartsPerLine = 10;
	public boolean hintOverlayToggle = true;
	// ConfigOverlay
	public float overlayPosX = 0.5F, overlayPosY = 1.0F, overlayScale = 1.0F, overlayAnchorX = 0.5F, overlayAnchorY = 0.0F;
	public boolean overlaySquare = false;
	public float alpha = 0.7F;
	public String theme = "jade:dark";
	public float autoScaleThreshold = 0.5F;
	// plugin options (core, vanilla)
	public boolean modName = true, registryName = false, blockStates = false, entityHealth = true, entityArmor = true;
	public boolean harvestTool = true, effectiveTool = true, harvestToolNewLine = false, cropProgress = true, redstone = true,
			spawnerType = true, horseStats = true, villagerProfession = true, itemFrame = true, enchantPower = true,
			playerHead = true, beehive = true, tnt = true, noteBlock = true, mobGrowth = true;

	private static JadeConfig instance;

	public static JadeConfig get() {
		if (instance == null) {
			instance = new JadeConfig();
			instance.load();
		}
		return instance;
	}

	public Theme theme() {
		for (Theme t : THEMES) {
			if (t.id.equals(theme)) {
				return t;
			}
		}
		return THEMES[1];
	}

	/** ConfigOverlayColor.applyAlpha */
	public static int applyAlpha(int color, float alpha) {
		int prev = color >>> 24 & 0xFF;
		if (prev > 0) {
			alpha *= prev / 256.0F;
		}
		int a = (int) (255 * Math.max(0, Math.min(1, alpha)));
		return color & 0xFFFFFF | a << 24;
	}

	public int background() {
		return applyAlpha(theme().background, alpha);
	}

	public int gradientStart() {
		return applyAlpha(theme().gradientStart, alpha);
	}

	public int gradientEnd() {
		return applyAlpha(theme().gradientEnd, alpha);
	}

	private void load() {
		try {
			byte[] b = EagRuntime.getStorage("jade");
			if (b == null) {
				return;
			}
			JSONObject o = new JSONObject(new String(b, StandardCharsets.UTF_8));
			displayTooltip = o.optBoolean("displayTooltip", displayTooltip);
			displayBlocks = o.optBoolean("displayBlocks", displayBlocks);
			displayEntities = o.optBoolean("displayEntities", displayEntities);
			hideFromDebug = o.optBoolean("hideFromDebug", hideFromDebug);
			showIcon = o.optBoolean("showIcon", showIcon);
			try {
				displayMode = DisplayMode.valueOf(o.optString("displayMode", displayMode.name()));
			} catch (IllegalArgumentException e) {
				displayMode = DisplayMode.TOGGLE;
			}
			iconTop = o.optBoolean("iconTop", iconTop);
			maxHealthForRender = o.optInt("maxHealthForRender", maxHealthForRender);
			maxHeartsPerLine = Math.max(1, o.optInt("maxHeartsPerLine", maxHeartsPerLine));
			hintOverlayToggle = o.optBoolean("hintOverlayToggle", hintOverlayToggle);
			overlayPosX = (float) o.optDouble("overlayPosX", overlayPosX);
			overlayPosY = (float) o.optDouble("overlayPosY", overlayPosY);
			overlayScale = (float) Math.max(0.2, Math.min(2, o.optDouble("overlayScale", overlayScale)));
			overlayAnchorX = (float) o.optDouble("overlayAnchorX", overlayAnchorX);
			overlayAnchorY = (float) o.optDouble("overlayAnchorY", overlayAnchorY);
			overlaySquare = o.optBoolean("overlaySquare", overlaySquare);
			alpha = (float) Math.max(0, Math.min(1, o.optDouble("alpha", alpha)));
			theme = o.optString("theme", theme);
			modName = o.optBoolean("modName", modName);
			registryName = o.optBoolean("registryName", registryName);
			blockStates = o.optBoolean("blockStates", blockStates);
			entityHealth = o.optBoolean("entityHealth", entityHealth);
			entityArmor = o.optBoolean("entityArmor", entityArmor);
			harvestTool = o.optBoolean("harvestTool", harvestTool);
			effectiveTool = o.optBoolean("effectiveTool", effectiveTool);
			harvestToolNewLine = o.optBoolean("harvestToolNewLine", harvestToolNewLine);
			cropProgress = o.optBoolean("cropProgress", cropProgress);
			redstone = o.optBoolean("redstone", redstone);
			spawnerType = o.optBoolean("spawnerType", spawnerType);
			horseStats = o.optBoolean("horseStats", horseStats);
			villagerProfession = o.optBoolean("villagerProfession", villagerProfession);
			itemFrame = o.optBoolean("itemFrame", itemFrame);
			enchantPower = o.optBoolean("enchantPower", enchantPower);
			playerHead = o.optBoolean("playerHead", playerHead);
			beehive = o.optBoolean("beehive", beehive);
			tnt = o.optBoolean("tnt", tnt);
			noteBlock = o.optBoolean("noteBlock", noteBlock);
			mobGrowth = o.optBoolean("mobGrowth", mobGrowth);
		} catch (Throwable t) {
			// unreadable: the defaults
		}
	}

	public void save() {
		JSONObject o = new JSONObject();
		o.put("displayTooltip", displayTooltip).put("displayBlocks", displayBlocks).put("displayEntities", displayEntities)
				.put("hideFromDebug", hideFromDebug).put("showIcon", showIcon).put("displayMode", displayMode.name())
				.put("iconTop", iconTop).put("maxHealthForRender", maxHealthForRender).put("maxHeartsPerLine", maxHeartsPerLine)
				.put("hintOverlayToggle", hintOverlayToggle).put("overlayPosX", overlayPosX).put("overlayPosY", overlayPosY)
				.put("overlayScale", overlayScale).put("overlayAnchorX", overlayAnchorX).put("overlayAnchorY", overlayAnchorY)
				.put("overlaySquare", overlaySquare).put("alpha", alpha).put("theme", theme).put("modName", modName)
				.put("registryName", registryName).put("blockStates", blockStates).put("entityHealth", entityHealth)
				.put("entityArmor", entityArmor).put("harvestTool", harvestTool).put("effectiveTool", effectiveTool)
				.put("harvestToolNewLine", harvestToolNewLine).put("cropProgress", cropProgress).put("redstone", redstone)
				.put("spawnerType", spawnerType).put("horseStats", horseStats).put("villagerProfession", villagerProfession)
				.put("itemFrame", itemFrame).put("enchantPower", enchantPower).put("playerHead", playerHead).put("beehive", beehive)
				.put("tnt", tnt).put("noteBlock", noteBlock).put("mobGrowth", mobGrowth);
		try {
			EagRuntime.setStorage("jade", o.toString().getBytes(StandardCharsets.UTF_8));
		} catch (Throwable t) {
			// storage full or off: the settings last this session
		}
	}
}
