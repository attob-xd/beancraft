/*
 * Converted from Jade 5.3.2 by Snownee, CC BY-NC-SA 4.0 - see assets/minecraft/hybrid/jade_license.txt.
 * This file: Jade's settings screens (WailaConfigScreen, PluginsConfigScreen), as one list of buttons: each press
 * moves an option to its next value. Positions and sizes are steps rather than sliders (no fiddly dragging on a
 * touchpad).
 */
package net.minecraft.client.jade;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.lax1dude.eaglercraft.Mouse;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextFormatting;

/** hybrid: Jade Settings */
public class GuiScreenJadeConfig extends GuiScreen {

	private final GuiScreen parent;
	private final List<Row> rows = new ArrayList<>();
	private int scroll, maxScroll;
	private static final int ROW = 24, TOP = 36;

	private interface Value {
		String get();

		void next();
	}

	private static final class Row {
		final String label;
		final Value value; // null: a heading

		Row(String label, Value value) {
			this.label = label;
			this.value = value;
		}
	}

	public GuiScreenJadeConfig(GuiScreen parent) {
		this.parent = parent;
	}

	private static String onOff(boolean b) {
		return b ? TextFormatting.GREEN + I18n.format("options.on") : TextFormatting.RED + I18n.format("options.off");
	}

	private interface BoolGet {
		boolean get(JadeConfig c);
	}

	private interface BoolSet {
		void set(JadeConfig c, boolean b);
	}

	private void bool(String label, BoolGet g, BoolSet s) {
		rows.add(new Row(label, new Value() {
			public String get() {
				return onOff(g.get(Jade.config()));
			}

			public void next() {
				s.set(Jade.config(), !g.get(Jade.config()));
			}
		}));
	}

	private static final float[][] POSITIONS = { { 0.5F, 1.0F, 0.5F, 0.0F }, { 0.0F, 1.0F, 0.0F, 0.0F }, { 1.0F, 1.0F, 1.0F, 0.0F },
			{ 0.0F, 0.5F, 0.0F, 0.5F }, { 1.0F, 0.5F, 1.0F, 0.5F }, { 0.0F, 0.0F, 0.0F, 1.0F }, { 1.0F, 0.0F, 1.0F, 1.0F } };
	private static final String[] POSITION_NAMES = { "Top", "Top left", "Top right", "Left", "Right", "Bottom left", "Bottom right" };
	private static final float[] SCALES = { 0.5F, 0.75F, 1.0F, 1.25F, 1.5F, 2.0F };

	private static int positionIndex(JadeConfig c) {
		for (int i = 0; i < POSITIONS.length; ++i) {
			float[] p = POSITIONS[i];
			if (Math.abs(p[0] - c.overlayPosX) < 0.01 && Math.abs(p[1] - c.overlayPosY) < 0.01) {
				return i;
			}
		}
		return 0;
	}

	private void build() {
		rows.clear();
		rows.add(new Row("General", null));
		bool("Show the overlay", c -> c.displayTooltip, (c, b) -> c.displayTooltip = b);
		rows.add(new Row("Display mode", new Value() {
			public String get() {
				switch (Jade.config().displayMode) {
				case HOLD_KEY:
					return "Hold " + net.minecraft.client.settings.GameSettings.getKeyDisplayString(mc.gameSettings.keyBindJadeDetails.getKeyCode());
				case LITE:
					return "Lite";
				default:
					return "Toggle (" + net.minecraft.client.settings.GameSettings.getKeyDisplayString(mc.gameSettings.keyBindJadeOverlay.getKeyCode()) + ")";
				}
			}

			public void next() {
				JadeConfig.DisplayMode[] m = JadeConfig.DisplayMode.values();
				Jade.config().displayMode = m[(Jade.config().displayMode.ordinal() + 1) % m.length];
			}
		}));
		bool("Blocks", c -> c.displayBlocks, (c, b) -> c.displayBlocks = b);
		bool("Creatures", c -> c.displayEntities, (c, b) -> c.displayEntities = b);
		bool("Icon", c -> c.showIcon, (c, b) -> c.showIcon = b);
		rows.add(new Row("Icon position", new Value() {
			public String get() {
				return Jade.config().iconTop ? "Top" : "Middle";
			}

			public void next() {
				Jade.config().iconTop = !Jade.config().iconTop;
			}
		}));
		bool("Hide with the F3 screen", c -> c.hideFromDebug, (c, b) -> c.hideFromDebug = b);
		rows.add(new Row("Overlay", null));
		rows.add(new Row("Position", new Value() {
			public String get() {
				return POSITION_NAMES[positionIndex(Jade.config())];
			}

			public void next() {
				JadeConfig c = Jade.config();
				float[] p = POSITIONS[(positionIndex(c) + 1) % POSITIONS.length];
				c.overlayPosX = p[0];
				c.overlayPosY = p[1];
				c.overlayAnchorX = p[2];
				c.overlayAnchorY = p[3];
			}
		}));
		rows.add(new Row("Size", new Value() {
			public String get() {
				return Math.round(Jade.config().overlayScale * 100) + "%";
			}

			public void next() {
				JadeConfig c = Jade.config();
				int i = 0;
				while (i < SCALES.length && SCALES[i] <= c.overlayScale + 0.01F) {
					++i;
				}
				c.overlayScale = SCALES[i % SCALES.length];
			}
		}));
		rows.add(new Row("Background", new Value() {
			public String get() {
				return Math.round(Jade.config().alpha * 100) + "%";
			}

			public void next() {
				JadeConfig c = Jade.config();
				c.alpha = Math.round(c.alpha * 10) >= 10 ? 0 : (Math.round(c.alpha * 10) + 1) / 10.0F;
			}
		}));
		rows.add(new Row("Theme", new Value() {
			public String get() {
				return Jade.config().theme().name;
			}

			public void next() {
				JadeConfig c = Jade.config();
				int i = 0;
				for (int k = 0; k < JadeConfig.THEMES.length; ++k) {
					if (JadeConfig.THEMES[k].id.equals(c.theme().id)) {
						i = k;
					}
				}
				c.theme = JadeConfig.THEMES[(i + 1) % JadeConfig.THEMES.length].id;
			}
		}));
		bool("Square corners", c -> c.overlaySquare, (c, b) -> c.overlaySquare = b);
		rows.add(new Row("Hearts per line", new Value() {
			public String get() {
				return String.valueOf(Jade.config().maxHeartsPerLine);
			}

			public void next() {
				JadeConfig c = Jade.config();
				c.maxHeartsPerLine = c.maxHeartsPerLine >= 20 ? 5 : c.maxHeartsPerLine + 5;
			}
		}));
		rows.add(new Row("Hearts up to (health)", new Value() {
			public String get() {
				return String.valueOf(Jade.config().maxHealthForRender);
			}

			public void next() {
				JadeConfig c = Jade.config();
				int[] steps = { 20, 40, 80, 200 };
				int i = 0;
				while (i < steps.length && steps[i] <= c.maxHealthForRender) {
					++i;
				}
				c.maxHealthForRender = steps[i % steps.length];
			}
		}));
		rows.add(new Row("What it shows", null));
		bool("Mod name", c -> c.modName, (c, b) -> c.modName = b);
		bool("Registry name", c -> c.registryName, (c, b) -> c.registryName = b);
		bool("Block states", c -> c.blockStates, (c, b) -> c.blockStates = b);
		bool("Health", c -> c.entityHealth, (c, b) -> c.entityHealth = b);
		bool("Armor", c -> c.entityArmor, (c, b) -> c.entityArmor = b);
		bool("Harvest tool", c -> c.harvestTool, (c, b) -> c.harvestTool = b);
		bool("Effective tool", c -> c.effectiveTool, (c, b) -> c.effectiveTool = b);
		bool("Harvest tool on its own line", c -> c.harvestToolNewLine, (c, b) -> c.harvestToolNewLine = b);
		bool("Crop growth", c -> c.cropProgress, (c, b) -> c.cropProgress = b);
		bool("Redstone", c -> c.redstone, (c, b) -> c.redstone = b);
		bool("Spawner mob", c -> c.spawnerType, (c, b) -> c.spawnerType = b);
		bool("Horse stats", c -> c.horseStats, (c, b) -> c.horseStats = b);
		bool("Item frame item", c -> c.itemFrame, (c, b) -> c.itemFrame = b);
		bool("Enchanting power", c -> c.enchantPower, (c, b) -> c.enchantPower = b);
		bool("Player head owner", c -> c.playerHead, (c, b) -> c.playerHead = b);
		bool("Beehive honey", c -> c.beehive, (c, b) -> c.beehive = b);
		bool("Note block", c -> c.noteBlock, (c, b) -> c.noteBlock = b);
		bool("Unstable TNT", c -> c.tnt, (c, b) -> c.tnt = b);
		bool("Baby mobs", c -> c.mobGrowth, (c, b) -> c.mobGrowth = b);
	}

	@Override
	public void initGui() {
		build();
		layout();
	}

	private int colW() {
		return Math.min(150, (this.width - 40) / 2);
	}

	private void layout() {
		this.buttonList.clear();
		int cw = colW(), x0 = this.width / 2 - cw - 5, y = TOP - scroll, col = 0;
		int bottom = this.height - 34;
		for (int i = 0; i < rows.size(); ++i) {
			Row r = rows.get(i);
			if (r.value == null) {
				if (col == 1) {
					y += ROW;
					col = 0;
				}
				y += 14;
				continue;
			}
			int x = col == 0 ? x0 : x0 + cw + 10;
			if (y >= TOP - 4 && y + 20 <= bottom) {
				this.buttonList.add(new GuiButton(1000 + i, x, y, cw, 20, r.label + ": " + r.value.get()));
			}
			if (col == 1) {
				y += ROW;
			}
			col ^= 1;
		}
		if (col == 1) {
			y += ROW;
		}
		maxScroll = Math.max(0, y + scroll - bottom + 6);
		this.buttonList.add(new GuiButton(1, this.width / 2 - 100, this.height - 27, 200, 20, I18n.format("gui.done")));
	}

	@Override
	protected void actionPerformed(GuiButton b) throws IOException {
		if (b.id == 1) {
			Jade.config().save();
			this.mc.displayGuiScreen(parent);
			return;
		}
		int i = b.id - 1000;
		if (i >= 0 && i < rows.size() && rows.get(i).value != null) {
			rows.get(i).value.next();
			Jade.config().save();
			layout();
		}
	}

	@Override
	public void handleMouseInput() throws IOException {
		super.handleMouseInput();
		int d = Mouse.getEventDWheel();
		if (d != 0) {
			scroll = Math.max(0, Math.min(maxScroll, scroll - (d > 0 ? 1 : -1) * ROW));
			layout();
		}
	}

	@Override
	public void onGuiClosed() {
		Jade.config().save();
	}

	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		this.drawDefaultBackground();
		this.drawCenteredString(this.fontRendererObj, "Jade Settings", this.width / 2, 12, 0xFFFFFF);
		// the headings, where they fall between the buttons
		int cw = colW(), x0 = this.width / 2 - cw - 5, y = TOP - scroll, col = 0;
		for (Row r : rows) {
			if (r.value == null) {
				if (col == 1) {
					y += ROW;
					col = 0;
				}
				if (y >= TOP - 14 && y < this.height - 40) {
					this.drawString(this.fontRendererObj, TextFormatting.YELLOW + r.label, x0, y + 3, 0xFFFFFF);
				}
				y += 14;
				continue;
			}
			if (col == 1) {
				y += ROW;
			}
			col ^= 1;
		}
		super.drawScreen(mouseX, mouseY, partialTicks);
	}
}
