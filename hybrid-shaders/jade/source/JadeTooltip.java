/*
 * Converted from Jade 5.3.2 by Snownee (Waila by ProfMobius, Hwyla by TehNut), CC BY-NC-SA 4.0 - see
 * assets/minecraft/hybrid/jade_license.txt. This file: mcp.mobius.waila.impl.Tooltip, TooltipRenderer and the ui
 * elements (TextElement, ItemStackElement, HealthElement, ArmorElement, BoxElement, SpacerElement, SubTextElement),
 * drawn with this version's GUI calls instead of PoseStack.
 */
package net.minecraft.client.jade;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import net.lax1dude.eaglercraft.opengl.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.MathHelper;

/** hybrid: Jade's tooltip - lines of elements, aligned left or right - and the elements it is made of */
public final class JadeTooltip {

	public enum Align {
		LEFT, RIGHT
	}

	public final List<Line> lines = new ArrayList<>();
	/** LITE display mode: there is more, hold Show Details to see it (the little bouncing arrow) */
	public boolean sneakyDetails;

	public boolean isEmpty() {
		return lines.isEmpty();
	}

	public int size() {
		return lines.size();
	}

	public void add(Element e) {
		add(lines.size(), e);
	}

	public void add(int index, Element e) {
		Line line = new Line();
		line.of(e.align).add(e);
		lines.add(Math.min(index, lines.size()), line);
	}

	/** to the end of a line (a new line if there is none) */
	public void append(int index, Element e) {
		if (isEmpty() || index >= size()) {
			add(e);
		} else {
			lines.get(index).of(e.align).add(e);
			lines.get(index).size = null;
		}
	}

	public void append(Element e) {
		append(size() - 1, e);
	}

	/** remove the elements with this tag (and lines left empty) */
	public void remove(String tag) {
		for (int i = lines.size() - 1; i >= 0; --i) {
			Line l = lines.get(i);
			l.left.removeIf(e -> tag.equals(e.tag));
			l.right.removeIf(e -> tag.equals(e.tag));
			l.size = null;
			if (l.left.isEmpty() && l.right.isEmpty()) {
				lines.remove(i);
			}
		}
	}

	public static final class Line {
		final List<Element> left = new ArrayList<>(), right = new ArrayList<>();
		float[] size;

		List<Element> of(Align a) {
			return a == Align.LEFT ? left : right;
		}

		float[] size() {
			if (size == null) {
				float w = 0, h = 0;
				for (Element e : left) {
					float[] s = e.cachedSize();
					w += s[0];
					h = Math.max(h, s[1]);
				}
				for (Element e : right) {
					float[] s = e.cachedSize();
					w += s[0];
					h = Math.max(h, s[1]);
				}
				size = new float[] { w, h };
			}
			return size;
		}

		void render(float x, float y, float maxWidth, float height) {
			float ox = maxWidth;
			for (int i = right.size() - 1; i >= 0; --i) {
				Element e = right.get(i);
				ox -= e.cachedSize()[0];
				e.render(ox + e.tx, y + e.ty, x + maxWidth, y + height);
			}
			float maxX = ox;
			ox = x;
			for (Element e : left) {
				e.render(ox + e.tx, y + e.ty, maxX, y + height);
				ox += e.cachedSize()[0];
			}
		}
	}

	/** the sizes and layout of a whole tooltip, as TooltipRenderer works them out */
	public static final class Layout {
		final JadeTooltip tooltip;
		final Element icon;
		float width, height, contentHeight;

		public Layout(JadeTooltip tooltip, Element icon) {
			this.tooltip = tooltip;
			this.icon = icon;
			float w = 0, h = 0;
			for (Line l : tooltip.lines) {
				float[] s = l.size();
				w = Math.max(w, s[0]);
				h += s[1];
			}
			contentHeight = h;
			if (icon != null) {
				float[] s = icon.cachedSize();
				w += 2 + s[0];
				h = Math.max(h, s[1] - 2);
			}
			width = w + 2 * padding() + 4;
			height = h + 2 * padding();
		}

		static float padding() {
			return 2.0F;
		}

		void draw() {
			float x = padding() + 3, y = padding() + 1;
			if (icon != null) {
				float[] s = icon.cachedSize();
				x += s[0] + 2;
				if (s[1] > contentHeight) {
					y += (s[1] - contentHeight) / 2 - 1;
				}
			}
			for (Line l : tooltip.lines) {
				float[] s = l.size();
				l.render(x, y, width - padding() - 1, s[1]);
				y += s[1];
			}
			if (tooltip.sneakyDetails) {
				FontRenderer f = Minecraft.getMinecraft().fontRendererObj;
				float yOffset = Jade.ticks / 5.0F % 8.0F - 2.0F;
				if (yOffset <= 4.0F) {
					int a = (int) (255 * MathHelper.clamp(1.0F - Math.abs(yOffset) / 2.0F, 0, 1));
					if (a > 4) {
						f.drawString("▾", (int) ((width - f.getStringWidth("▾") + 1) / 2), (int) (height - 6 + yOffset), 0xFFFFFF | a << 24, false);
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------ elements

	public abstract static class Element {
		Align align = Align.LEFT;
		float tx, ty;
		float[] fixedSize, cached;
		String tag;

		public Element align(Align a) {
			this.align = a;
			return this;
		}

		public Element translate(float x, float y) {
			this.tx = x;
			this.ty = y;
			return this;
		}

		public Element size(float w, float h) {
			this.fixedSize = new float[] { w, h };
			this.cached = null;
			return this;
		}

		public Element tag(String t) {
			this.tag = t;
			return this;
		}

		float[] cachedSize() {
			if (cached == null) {
				cached = fixedSize != null ? fixedSize : size();
			}
			return cached;
		}

		abstract float[] size();

		abstract void render(float x, float y, float maxX, float maxY);
	}

	static FontRenderer font() {
		return Minecraft.getMinecraft().fontRendererObj;
	}

	/** a line of text in the theme's text color (formatting codes in it win) */
	public static final class Text extends Element {
		final String text;
		final int color;

		public Text(String text) {
			this(text, Jade.config().theme().textColor);
		}

		public Text(String text, int color) {
			this.text = text;
			this.color = color;
		}

		float[] size() {
			return new float[] { font().getStringWidth(text), text.isEmpty() ? 0 : 10 };
		}

		void render(float x, float y, float maxX, float maxY) {
			font().drawString(text, (int) x, (int) y, color, Jade.config().theme().textShadow);
		}
	}

	/** the small check or cross under the harvest tool's icon */
	public static final class SubText extends Element {
		final String text;

		public SubText(String text) {
			this.text = text;
		}

		float[] size() {
			return new float[] { 0, 0 };
		}

		void render(float x, float y, float maxX, float maxY) {
			GlStateManager.pushMatrix();
			GlStateManager.translate(x + 6, y + 0.5F, 0);
			GlStateManager.scale(0.75F, 0.75F, 0);
			font().drawString(text, 0, 0, 0xFFFFFF, true);
			GlStateManager.popMatrix();
		}
	}

	public static final class Spacer extends Element {
		final float w, h;

		public Spacer(float w, float h) {
			this.w = w;
			this.h = h;
		}

		float[] size() {
			return new float[] { w, h };
		}

		void render(float x, float y, float maxX, float maxY) {
		}
	}

	/** an item, 18 px a side times the scale (DisplayHelper.drawItem) */
	public static final class Item extends Element {
		final ItemStack stack;
		final float scale;

		public Item(ItemStack stack, float scale) {
			this.stack = stack;
			this.scale = scale == 0 ? 1 : scale;
		}

		float[] size() {
			int s = MathHelper.floor(18 * scale);
			return new float[] { s, s };
		}

		void render(float x, float y, float maxX, float maxY) {
			if (stack.func_190926_b()) {
				return;
			}
			Minecraft mc = Minecraft.getMinecraft();
			GlStateManager.pushMatrix();
			GlStateManager.translate(x + 1, y + 1, 0);
			GlStateManager.scale(scale, scale, 1);
			RenderHelper.enableGUIStandardItemLighting();
			GlStateManager.enableDepth();
			mc.getRenderItem().renderItemAndEffectIntoGUI(stack, 0, 0);
			mc.getRenderItem().renderItemOverlays(font(), stack, 0, 0);
			RenderHelper.disableStandardItemLighting();
			GlStateManager.disableDepth();
			GlStateManager.popMatrix();
			GlStateManager.enableBlend();
		}
	}

	static final DecimalFormat DF = new DecimalFormat("#.##");

	/** IconUI: hearts and armor from the vanilla icons texture (u, v of the 9 px sprite, and its container) */
	static void icon(float x, float y, int u, int v, int bu, int bv) {
		Minecraft mc = Minecraft.getMinecraft();
		mc.getTextureManager().bindTexture(Gui.ICONS);
		GlStateManager.color(1, 1, 1, 1);
		GlStateManager.enableBlend();
		GlStateManager.pushMatrix();
		GlStateManager.translate(x, y, 0);
		GlStateManager.scale(8 / 9.0F, 8 / 9.0F, 1);
		if (bu >= 0) {
			mc.ingameGUI.drawTexturedModalRect(0, 0, bu, bv, 9, 9);
		}
		mc.ingameGUI.drawTexturedModalRect(0, 0, u, v, 9, 9);
		GlStateManager.popMatrix();
	}

	/** HealthElement: hearts, or one heart and a number above maxHealthForRender */
	public static final class Health extends Element {
		final float max, health;
		final String text;

		public Health(float max, float health) {
			this.max = max;
			this.health = health;
			this.text = "  " + DF.format(health) + "/" + DF.format(max);
		}

		float[] size() {
			JadeConfig c = Jade.config();
			if (max > c.maxHealthForRender) {
				return new float[] { 8 + font().getStringWidth(text), 10 };
			}
			float half = max * 0.5F;
			int perLine = (int) Math.min(c.maxHeartsPerLine, Math.ceil(half));
			int lines = (int) Math.ceil(half / c.maxHeartsPerLine);
			return new float[] { 8 * perLine, 10 * lines };
		}

		void render(float x, float y, float maxX, float maxY) {
			JadeConfig c = Jade.config();
			boolean big = max > c.maxHealthForRender;
			int hearts = big ? 1 : MathHelper.ceil(max * 0.5F);
			float h = big ? 1 : health * 0.5F;
			int perLine = (int) Math.min(c.maxHeartsPerLine, Math.ceil(max));
			int ox = 0;
			for (int i = 1; i <= hearts; ++i) {
				if (i <= MathHelper.floor(h)) {
					icon(x + ox, y, 52, 0, 16, 0);
					ox += 8;
				}
				if (i > h && i < h + 1) {
					icon(x + ox, y, 61, 0, 16, 0);
					ox += 8;
				}
				if (i >= h + 1) {
					icon(x + ox, y, 16, 0, -1, -1);
					ox += 8;
				}
				if (i % perLine == 0) {
					y += 10;
					ox = 0;
				}
			}
			if (big) {
				font().drawString(text, (int) (x + 8), (int) y, c.theme().textColor, c.theme().textShadow);
			}
		}
	}

	/** ArmorElement */
	public static final class Armor extends Element {
		final float armor;

		public Armor(float armor) {
			this.armor = armor;
		}

		float[] size() {
			JadeConfig c = Jade.config();
			if (armor > c.maxHealthForRender) {
				return new float[] { 8 + font().getStringWidth("  " + DF.format(armor)), 10 };
			}
			int lines = (int) Math.ceil(armor / c.maxHeartsPerLine * 0.5F);
			return new float[] { 8 * c.maxHeartsPerLine, 10 * lines };
		}

		void render(float x, float y, float maxX, float maxY) {
			JadeConfig c = Jade.config();
			if (armor > c.maxHealthForRender) {
				icon(x, y, 34, 9, -1, -1);
				font().drawString("  " + DF.format(armor), (int) (x + 8), (int) y, c.theme().textColor, c.theme().textShadow);
				return;
			}
			float a = armor * 0.5F;
			int max = c.maxHeartsPerLine;
			int count = (int) Math.ceil(a / max) * max;
			int ox = 0;
			for (int i = 1; i <= count; ++i) {
				if (i <= MathHelper.floor(a)) {
					icon(x + ox, y, 34, 9, -1, -1);
					ox += 8;
				}
				if (i > a && i < a + 1) {
					icon(x + ox, y, 25, 9, -1, -1);
					ox += 8;
				}
				if (i >= a + 1) {
					icon(x + ox, y, 16, 9, -1, -1);
					ox += 8;
				}
				if (i % max == 0) {
					y += 10;
					ox = 0;
				}
			}
		}
	}

	/** BoxElement: a tooltip inside a thin border (block states) */
	public static final class Box extends Element {
		final Layout inner;

		public Box(JadeTooltip t) {
			this.inner = new Layout(t, null);
		}

		float[] size() {
			return inner.tooltip.isEmpty() ? new float[] { 0, 0 } : new float[] { inner.width + 2, inner.height + 4 };
		}

		void render(float x, float y, float maxX, float maxY) {
			if (inner.tooltip.isEmpty()) {
				return;
			}
			GlStateManager.pushMatrix();
			GlStateManager.translate(x, y, 0);
			int w = (int) inner.width, h = (int) inner.height, c = 0xFF808080;
			Gui.drawRect(0, 0, w, 1, c);
			Gui.drawRect(0, h - 1, w, h, c);
			Gui.drawRect(0, 1, 1, h - 1, c);
			Gui.drawRect(w - 1, 1, w, h - 1, c);
			inner.draw();
			GlStateManager.popMatrix();
		}
	}
}
