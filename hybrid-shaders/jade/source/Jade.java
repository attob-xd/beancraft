/*
 * Converted from Jade 5.3.2 by Snownee (Waila by ProfMobius, Hwyla by TehNut), CC BY-NC-SA 4.0 - see
 * assets/minecraft/hybrid/jade_license.txt. This file: WailaClient (the keys), WailaTickHandler (the tooltip, once a
 * client tick), OverlayRenderer and DisplayHelper (drawing it), RayTracing.getIcon, and the providers of Jade's core
 * and vanilla plugins (BaseBlockProvider, BaseEntityProvider, VanillaProvider, HarvestToolProvider, and the vanilla
 * addons for spawners, horses, item frames, player heads, enchanting tables, beehives, note blocks and TNT).
 * <p>
 * What Jade's server side would send (chest and furnace contents, a comparator's signal, a jukebox's record) needs
 * Jade on the server; a browser client only shows what it knows itself, which is everything else.
 */
package net.minecraft.client.jade;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.lax1dude.eaglercraft.opengl.GlStateManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockCocoa;
import net.minecraft.block.BlockCrops;
import net.minecraft.block.BlockLever;
import net.minecraft.block.BlockNetherWart;
import net.minecraft.block.BlockRedstoneComparator;
import net.minecraft.block.BlockRedstoneRepeater;
import net.minecraft.block.BlockStem;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityItemFrame;
import net.minecraft.entity.passive.AbstractHorse;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityMobSpawner;
import net.minecraft.tileentity.TileEntitySkull;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

/** hybrid: Jade - what you are looking at, at the top of the screen */
public final class Jade {

	static float ticks;
	private static JadeTooltip.Layout current;
	private static final Gradients GUI = new Gradients();
	/** HarvestToolProvider.resultCache: the tools for a state (a state's are always the same) */
	private static final Map<IBlockState, List<ItemStack>> TOOLS = new java.util.HashMap<>();

	private Jade() {
	}

	public static JadeConfig config() {
		return JadeConfig.get();
	}

	public static boolean enabled() {
		Minecraft mc = Minecraft.getMinecraft();
		return mc.gameSettings != null && mc.gameSettings.modJade;
	}

	// ------------------------------------------------------------------ keys (WailaClient.onKeyPressed)

	/** Minecraft.processKeyBinds */
	public static void keys(Minecraft mc) {
		while (mc.gameSettings.keyBindJadeOverlay.isPressed()) {
			if (!enabled()) {
				continue;
			}
			JadeConfig c = config();
			c.displayTooltip = !c.displayTooltip;
			if (!c.displayTooltip && c.hintOverlayToggle) {
				mc.ingameGUI.setRecordPlaying(I18n.format("toast.jade.toggle_hint.1") + " - "
						+ I18n.format("toast.jade.toggle_hint.2", net.minecraft.client.settings.GameSettings.getKeyDisplayString(mc.gameSettings.keyBindJadeOverlay.getKeyCode())), false);
				c.hintOverlayToggle = false;
			}
			c.save();
		}
	}

	// ------------------------------------------------------------------ the tooltip (WailaTickHandler.tickClient)

	/** once a client tick */
	public static void tick(Minecraft mc) {
		JadeConfig c = config();
		if (!enabled() || !c.displayTooltip || mc.world == null || mc.player == null) {
			current = null;
			return;
		}
		if (mc.isGamePaused() || mc.currentScreen != null) {
			return; // keep what was there (Jade only re-reads in game)
		}
		RayTraceResult hit = mc.objectMouseOver;
		if (hit == null || hit.typeOfHit == RayTraceResult.Type.MISS) {
			current = null;
			return;
		}
		JadeTooltip head = new JadeTooltip(), body = new JadeTooltip();
		JadeTooltip.Element icon = null;
		if (hit.typeOfHit == RayTraceResult.Type.BLOCK && c.displayBlocks) {
			BlockPos pos = hit.getBlockPos();
			IBlockState state = mc.world.getBlockState(pos).getActualState(mc.world, pos);
			if (state.getMaterial() == net.minecraft.block.material.Material.AIR) {
				current = null;
				return;
			}
			ItemStack pick = pick(mc.world, pos, state);
			if (!pick.func_190926_b()) {
				icon = new JadeTooltip.Item(pick, 1);
			}
			blockHead(head, mc.world, pos, state, pick);
			blockBody(body, mc.world, pos, state, mc.player);
			blockTail(head, mc.world, pos, state, mc.player);
		} else if (hit.typeOfHit == RayTraceResult.Type.ENTITY && hit.entityHit != null && c.displayEntities) {
			Entity e = hit.entityHit;
			if ("".equals(kitCreature(e))) {
				current = null; // the horror kit's watchers stay nameless
				return;
			}
			if (e instanceof EntityItem) {
				icon = new JadeTooltip.Item(((EntityItem) e).getEntityItem(), 1);
			} else if (e instanceof EntityItemFrame && !((EntityItemFrame) e).getDisplayedItem().func_190926_b()) {
				icon = new JadeTooltip.Item(((EntityItemFrame) e).getDisplayedItem(), 1);
			}
			entityHead(head, e);
			entityBody(body, e);
			if (c.modName) {
				head.add(new JadeTooltip.Text("§9§o" + modName(e)).tag("mod_name"));
			}
		} else {
			current = null;
			return;
		}
		// HEAD, then BODY (or the sneaky-details arrow in LITE mode), then TAIL - the mod name is already last in head
		JadeTooltip tip = new JadeTooltip();
		for (JadeTooltip.Line l : head.lines) {
			tip.lines.add(l);
		}
		JadeTooltip.Line mod = null;
		for (JadeTooltip.Line l : tip.lines) {
			for (JadeTooltip.Element el : l.left) {
				if ("mod_name".equals(el.tag)) {
					mod = l;
				}
			}
		}
		if (mod != null) {
			tip.lines.remove(mod);
		}
		boolean showDetails = mc.gameSettings.keyBindJadeDetails.isKeyDown();
		if (c.displayMode == JadeConfig.DisplayMode.LITE && !body.isEmpty() && !showDetails) {
			tip.sneakyDetails = true;
		} else {
			tip.lines.addAll(body.lines);
		}
		if (mod != null) {
			tip.lines.add(mod);
		}
		current = tip.isEmpty() ? null : new JadeTooltip.Layout(tip, c.showIcon ? icon : null);
		if (c.displayMode == JadeConfig.DisplayMode.HOLD_KEY && !showDetails) {
			current = null;
		}
	}

	/** what pick block gives (RayTracing.getIcon for blocks) */
	static ItemStack pick(World w, BlockPos pos, IBlockState state) {
		try {
			ItemStack s = state.getBlock().getItem(w, pos, state);
			return s == null ? ItemStack.field_190927_a : s;
		} catch (Throwable t) {
			return ItemStack.field_190927_a;
		}
	}

	static String modName(Object o) {
		if (o instanceof Entity && kitCreature((Entity) o) != null) {
			return ((Entity) o).getCustomNameTag().startsWith("arphex:") ? "Arthropod Phobia Expansions" : "The Starved Stalker";
		}
		return "Minecraft"; // every block, item and creature here is Minecraft's own (1.12, or 1.18.2's)
	}

	/**
	 * The horror kit's creatures are zombies named for what the client draws ("starved:aggro:walk"): their proper
	 * name, "" for the ones that should stay nameless (the Man, the Stalker, Corner Entity), null for anything else
	 */
	static String kitCreature(Entity e) {
		if (!(e instanceof net.minecraft.entity.monster.EntityZombie) || !e.hasCustomName()) {
			return null;
		}
		String n = e.getCustomNameTag();
		if (n.startsWith("starved:")) {
			return n.startsWith("starved:jaws") ? "Hungrejaws" : "The Starved Stalker";
		}
		if (n.startsWith("peeker:")) {
			return ""; // a watcher: nothing gives it away
		}
		if (n.startsWith("dweller:")) {
			return n.startsWith("dweller:hidden") ? "" : "Cave Dweller"; // unseen: nothing gives it away
		}
		if (n.startsWith("arphex:")) {
			String[] p = n.split(":");
			String[][] names = { { "recluse", "Spider Recluse" }, { "lunger", "Spider Lunger" }, { "ambusher", "Spider Ambusher" },
					{ "longlegs", "Long Legs" }, { "prowler", "Spider Prowler" }, { "goliath", "Spider Goliath" },
					{ "reaper", "Spider Reaper" }, { "snatcher", "Spider Snatcher" } };
			for (String[] k : names) {
				if (p.length > 1 && k[0].equals(p[1])) {
					return k[1];
				}
			}
			return "Spider";
		}
		return n.startsWith("shadow:") || n.startsWith("corner:") || n.startsWith("playmate:") || n.startsWith("ssh:") ? ""
				: null;
	}

	// ------------------------------------------------------------------ blocks

	/** BaseBlockProvider.appendHead (+ VanillaProvider's spawner name) */
	private static void blockHead(JadeTooltip t, World w, BlockPos pos, IBlockState state, ItemStack pick) {
		JadeConfig c = config();
		Block block = state.getBlock();
		String name = null;
		TileEntity te = w.getTileEntity(pos);
		if (te instanceof net.minecraft.world.IWorldNameable && ((net.minecraft.world.IWorldNameable) te).hasCustomName()) {
			name = ((net.minecraft.world.IWorldNameable) te).getDisplayName().getUnformattedText();
		}
		if (name == null && !pick.func_190926_b()) {
			name = pick.getDisplayName();
		}
		if (name == null) {
			name = block.getLocalizedName();
		}
		if (c.spawnerType && te instanceof TileEntityMobSpawner) {
			Entity e = ((TileEntityMobSpawner) te).getSpawnerBaseLogic().getCachedEntity();
			if (e != null) {
				name = I18n.format("jade.spawner", block.getLocalizedName(), e.getDisplayName().getUnformattedText());
			}
		}
		t.add(new JadeTooltip.Text(name, c.theme().titleColor).tag("object_name"));
		if (c.registryName) {
			ResourceLocation id = Block.REGISTRY.getNameForObject(block);
			t.add(new JadeTooltip.Text(TextFormatting.GRAY + String.valueOf(id)).tag("registry_name"));
		}
		if (c.modName) {
			t.add(new JadeTooltip.Text("§9§o" + modName(block)).tag("mod_name"));
		}
	}

	private static void blockBody(JadeTooltip t, World w, BlockPos pos, IBlockState state, EntityPlayer player) {
		JadeConfig c = config();
		Block block = state.getBlock();
		String id = String.valueOf(Block.REGISTRY.getNameForObject(block));
		// HarvestToolProvider: unbreakable blocks say so in the body
		if (c.harvestTool && !player.capabilities.isCreativeMode && !player.isSpectator()
				&& state.getBlockHardness(w, pos) < 0) {
			t.add(new JadeTooltip.Text(TextFormatting.DARK_RED + I18n.format("jade.harvest_tool.unbreakable")));
		}
		// VanillaProvider: crops
		if (c.cropProgress) {
			Integer age = intProp(state, "age");
			if (age != null && (block instanceof BlockCrops || block instanceof BlockStem || block instanceof BlockCocoa
					|| block instanceof BlockNetherWart || id.endsWith("sweet_berry_bush"))) {
				int max = block instanceof BlockCrops ? ((BlockCrops) block).getMaxAge() : maxOf(state, "age");
				maturity(t, max <= 0 ? 1 : age / (float) max);
			}
		}
		// VanillaProvider: redstone
		if (c.redstone) {
			if (block instanceof BlockLever) {
				boolean on = state.getValue(BlockLever.POWERED);
				t.add(new JadeTooltip.Text(I18n.format("tooltip.waila.state", I18n.format("tooltip.waila.state_" + (on ? "on" : "off")))));
			} else if (block instanceof BlockRedstoneRepeater) {
				t.add(new JadeTooltip.Text(I18n.format("tooltip.waila.delay", TextFormatting.WHITE.toString() + state.getValue(BlockRedstoneRepeater.DELAY))));
			} else if (block instanceof BlockRedstoneComparator) {
				boolean compare = state.getValue(BlockRedstoneComparator.MODE) == BlockRedstoneComparator.Mode.COMPARE;
				t.add(new JadeTooltip.Text(I18n.format("tooltip.waila.mode",
						TextFormatting.WHITE + I18n.format("tooltip.waila.mode_" + (compare ? "comparator" : "subtractor")))));
			} else {
				Integer power = intProp(state, "power");
				if (power != null) {
					t.add(new JadeTooltip.Text(I18n.format("tooltip.waila.power", TextFormatting.WHITE.toString() + power)));
				}
			}
		}
		// the vanilla addons Jade has that the client can answer by itself
		if (c.beehive) {
			Integer honey = intProp(state, "honey_level");
			if (honey != null) {
				t.add(new JadeTooltip.Text(I18n.format("jade.beehive.honey", I18n.format("jade.fraction", honey, maxOf(state, "honey_level")))));
			}
		}
		if (c.noteBlock && id.endsWith("note_block")) {
			Integer note = intProp(state, "note");
			String inst = strProp(state, "instrument");
			if (note != null && inst != null) {
				t.add(new JadeTooltip.Text(I18n.format("jade.instrument." + inst) + " " + TextFormatting.WHITE + note));
			}
		}
		if (c.tnt && (block == Blocks.TNT) && state.getValue(net.minecraft.block.BlockTNT.EXPLODE)) {
			t.add(new JadeTooltip.Text(TextFormatting.RED + I18n.format("jade.tnt.unstable")));
		}
		if (c.playerHead) {
			TileEntity te = w.getTileEntity(pos);
			if (te instanceof TileEntitySkull && ((TileEntitySkull) te).getPlayerProfile() != null
					&& ((TileEntitySkull) te).getPlayerProfile().getName() != null) {
				t.add(new JadeTooltip.Text(I18n.format("jade.owner", ((TileEntitySkull) te).getPlayerProfile().getName())));
			}
		}
		if (c.enchantPower && block == Blocks.ENCHANTING_TABLE) {
			int shelves = 0;
			for (int dz = -1; dz <= 1; ++dz) {
				for (int dx = -1; dx <= 1; ++dx) {
					if ((dz != 0 || dx != 0) && w.isAirBlock(pos.add(dx, 0, dz)) && w.isAirBlock(pos.add(dx, 1, dz))) {
						shelves += shelf(w, pos.add(dx * 2, 0, dz * 2)) + shelf(w, pos.add(dx * 2, 1, dz * 2));
						if (dx != 0 && dz != 0) {
							shelves += shelf(w, pos.add(dx * 2, 0, dz)) + shelf(w, pos.add(dx * 2, 1, dz)) + shelf(w, pos.add(dx, 0, dz * 2))
									+ shelf(w, pos.add(dx, 1, dz * 2));
						}
					}
				}
			}
			t.add(new JadeTooltip.Text(I18n.format("jade.ench_power", TextFormatting.WHITE.toString() + Math.min(15, shelves))));
		}
		// BaseBlockProvider.appendBody: block states (off by default)
		if (c.blockStates && !state.getProperties().isEmpty()) {
			JadeTooltip box = new JadeTooltip();
			for (Map.Entry<IProperty<?>, Comparable<?>> e : state.getProperties().entrySet()) {
				Comparable<?> v = e.getValue();
				String col = v instanceof Boolean ? ((Boolean) v ? TextFormatting.GREEN.toString() : TextFormatting.RED.toString()) : "";
				box.add(new JadeTooltip.Text(e.getKey().getName() + ":" + col + " " + v));
			}
			t.add(new JadeTooltip.Box(box));
		}
	}

	private static int shelf(World w, BlockPos p) {
		return w.getBlockState(p).getBlock() == Blocks.BOOKSHELF ? 1 : 0;
	}

	/** HarvestToolProvider: the tools that break it best (the lowest tier that drops it), and whether yours does */
	private static void blockTail(JadeTooltip t, World w, BlockPos pos, IBlockState state, EntityPlayer player) {
		JadeConfig c = config();
		if (!c.harvestTool || player.capabilities.isCreativeMode || player.isSpectator() || state.getBlockHardness(w, pos) < 0) {
			return;
		}
		List<ItemStack> tools = TOOLS.computeIfAbsent(state, Jade::tools);
		if (tools.isEmpty()) {
			return;
		}
		boolean needsTool = !state.getMaterial().isToolNotRequired();
		if (!needsTool && !c.effectiveTool) {
			return;
		}
		int offsetY = c.harvestToolNewLine ? 0 : -3;
		List<JadeTooltip.Element> els = new ArrayList<>();
		els.add(new JadeTooltip.Spacer(5, 0));
		for (ItemStack tool : tools) {
			els.add(new JadeTooltip.Item(tool, 0.75F).translate(-1, offsetY).size(10, 0));
		}
		ItemStack held = player.getHeldItemMainhand();
		boolean canHarvest = !held.func_190926_b() && held.canHarvestBlock(state);
		if (held.getItem() == Items.SHEARS && tools.stream().anyMatch(s -> s.getItem() == Items.SHEARS)) {
			canHarvest = true;
		}
		if (needsTool) {
			// on the tool icon's bottom-right corner (Jade's -2 drew it past this box's narrower right padding)
			els.add(new JadeTooltip.SubText(canHarvest ? TextFormatting.GREEN + "✔" : TextFormatting.RED + "✕").translate(-8, 7 + offsetY));
		} else if (canHarvest || (!held.func_190926_b() && held.getStrVsBlock(state) > 1.0F)) {
			els.add(new JadeTooltip.SubText(TextFormatting.GREEN + "✔").translate(-8, 7 + offsetY));
		}
		if (c.harvestToolNewLine) {
			for (JadeTooltip.Element e : els) {
				t.append(t.size() > 1 ? t.size() - 1 : 0, e);
			}
		} else {
			for (JadeTooltip.Element e : els) {
				e.align(JadeTooltip.Align.RIGHT);
				t.append(0, e);
			}
		}
	}

	/** SimpleToolHandler and the shears / sword handlers, in this version's terms (no mineable tags in 1.12) */
	private static List<ItemStack> tools(IBlockState state) {
		List<ItemStack> out = new ArrayList<>();
		net.minecraft.item.Item[][] kinds = {
				{ Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE },
				{ Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE },
				{ Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL } };
		boolean needsTool = !state.getMaterial().isToolNotRequired();
		for (net.minecraft.item.Item[] tiers : kinds) {
			ItemStack best = new ItemStack(tiers[3]);
			boolean effective = tiers[3].getStrVsBlock(best, state) > 1.0F || (needsTool && tiers[3].canHarvestBlock(state));
			if (!effective) {
				continue;
			}
			ItemStack pickTier = best;
			for (net.minecraft.item.Item tier : tiers) {
				ItemStack s = new ItemStack(tier);
				if (!needsTool || tier.canHarvestBlock(state) || tier.getStrVsBlock(s, state) > 1.0F && !needsTool) {
					pickTier = s;
					break;
				}
			}
			out.add(pickTier);
		}
		ItemStack shears = new ItemStack(Items.SHEARS);
		if (Items.SHEARS.getStrVsBlock(shears, state) > 1.0F && state.getBlock() != Blocks.WEB) {
			out.add(shears);
		}
		if (state.getBlock() == Blocks.WEB) {
			out.add(new ItemStack(Items.IRON_SWORD));
		}
		return out;
	}

	private static void maturity(JadeTooltip t, float growth) {
		growth *= 100;
		t.add(new JadeTooltip.Text(I18n.format("tooltip.waila.crop_growth", growth < 100 ? Math.round(growth) + "%"
				: TextFormatting.GREEN + I18n.format("tooltip.waila.crop_mature"))));
	}

	private static IProperty<?> prop(IBlockState s, String name) {
		for (IProperty<?> p : s.getPropertyNames()) {
			if (p.getName().equals(name)) {
				return p;
			}
		}
		return null;
	}

	private static Integer intProp(IBlockState s, String name) {
		IProperty<?> p = prop(s, name);
		Object v = p == null ? null : s.getValue(p);
		return v instanceof Integer ? (Integer) v : null;
	}

	private static String strProp(IBlockState s, String name) {
		IProperty<?> p = prop(s, name);
		if (p == null) {
			return null;
		}
		Object v = s.getValue(p);
		return v instanceof net.minecraft.util.IStringSerializable ? ((net.minecraft.util.IStringSerializable) v).getName() : String.valueOf(v).toLowerCase();
	}

	private static int maxOf(IBlockState s, String name) {
		IProperty<?> p = prop(s, name);
		int max = 0;
		if (p instanceof PropertyInteger) {
			for (Integer i : ((PropertyInteger) p).getAllowedValues()) {
				max = Math.max(max, i);
			}
		}
		return max;
	}

	// ------------------------------------------------------------------ entities

	/** BaseEntityProvider.appendHead */
	private static void entityHead(JadeTooltip t, Entity e) {
		JadeConfig c = config();
		String kit = kitCreature(e);
		String name = kit != null ? kit
				: e instanceof EntityItem && !e.hasCustomName() ? ((EntityItem) e).getEntityItem().getDisplayName()
						: e.getDisplayName().getUnformattedText();
		t.add(new JadeTooltip.Text(name, c.theme().titleColor).tag("object_name"));
		if (c.registryName) {
			t.add(new JadeTooltip.Text(TextFormatting.GRAY + String.valueOf(net.minecraft.entity.EntityList.func_191301_a(e))).tag("registry_name"));
		}
	}

	/** BaseEntityProvider.appendBody (health above armor) and the vanilla entity addons */
	private static void entityBody(JadeTooltip t, Entity e) {
		JadeConfig c = config();
		if (e instanceof EntityLivingBase) {
			EntityLivingBase l = (EntityLivingBase) e;
			if (c.entityArmor && l.getTotalArmorValue() != 0) {
				t.add(0, new JadeTooltip.Armor(l.getTotalArmorValue()));
			}
			if (c.entityHealth && !(l instanceof EntityArmorStand)) {
				t.add(0, new JadeTooltip.Health(l.getMaxHealth(), l.getHealth()));
			}
		}
		if (c.horseStats && e instanceof AbstractHorse) {
			AbstractHorse h = (AbstractHorse) e;
			double jump = h.getHorseJumpStrength();
			double height = -0.1817584952 * jump * jump * jump + 3.689713992 * jump * jump + 2.128599134 * jump - 0.343930367;
			double speed = h.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue() * 43.17;
			t.add(new JadeTooltip.Text(I18n.format("jade.horseStat.jump", TextFormatting.WHITE.toString() + Math.round(height * 10) / 10.0)));
			t.add(new JadeTooltip.Text(I18n.format("jade.horseStat.speed", TextFormatting.WHITE.toString() + Math.round(speed * 10) / 10.0)));
		}
		if (c.itemFrame && e instanceof EntityItemFrame) {
			ItemStack s = ((EntityItemFrame) e).getDisplayedItem();
			if (!s.func_190926_b()) {
				t.add(new JadeTooltip.Text(s.getDisplayName()));
			}
		}
		if (c.mobGrowth && e instanceof net.minecraft.entity.EntityAgeable && ((net.minecraft.entity.EntityAgeable) e).isChild()) {
			t.add(new JadeTooltip.Text(TextFormatting.GRAY + I18n.format("jade.baby")));
		}
	}

	// ------------------------------------------------------------------ drawing (OverlayRenderer)

	/** GuiIngame, after the HUD */
	public static void render(Minecraft mc, ScaledResolution sr, float partialTicks) {
		JadeTooltip.Layout tip = current;
		JadeConfig c = config();
		if (tip == null || !enabled() || !c.displayTooltip || mc.world == null || mc.gameSettings.hideGUI
				|| (mc.gameSettings.showDebugInfo && c.hideFromDebug) || mc.currentScreen != null) {
			return;
		}
		ticks += partialTicks;
		float w = tip.width, h = tip.height;
		if (!c.overlaySquare) {
			w += 2;
			h += 2;
		}
		float x = sr.getScaledWidth() * c.overlayPosX, y = sr.getScaledHeight() * (1 - c.overlayPosY);
		if (!c.overlaySquare) {
			x += 1;
			y += 1;
		}
		float scale = c.overlayScale;
		float threshold = sr.getScaledHeight() * c.autoScaleThreshold;
		if (h * scale > threshold) {
			scale = Math.max(scale * 0.5F, threshold / h);
		}
		GlStateManager.pushMatrix();
		GlStateManager.disableDepth();
		GlStateManager.translate(x, y, 0);
		GlStateManager.scale(scale, scale, 1);
		GlStateManager.translate(-w * c.overlayAnchorX, -h * c.overlayAnchorY, 0);
		if (c.alpha > 0) {
			box(0, 0, (int) w, (int) h, c.background(), c.gradientStart(), c.gradientEnd(), c.overlaySquare);
		}
		GlStateManager.enableBlend();
		GlStateManager.blendFunc(770, 771);
		tip.draw();
		if (tip.icon != null) {
			float[] s = tip.icon.cachedSize();
			float oy = c.iconTop ? tip.icon.ty + JadeTooltip.Layout.padding() : (h - s[1]) / 2 - 1;
			float ox = tip.icon.tx + JadeTooltip.Layout.padding() + 2;
			tip.icon.render(ox, oy, ox + s[0], oy + s[1]);
		}
		GlStateManager.enableBlend();
		GlStateManager.color(1, 1, 1, 1);
		GlStateManager.enableDepth();
		GlStateManager.popMatrix();
	}

	/** OverlayRenderer.drawTooltipBox */
	static void box(int x, int y, int w, int h, int bg, int g1, int g2, boolean square) {
		if (!square) {
			w -= 2;
			h -= 2;
		}
		GUI.grad(x + 1, y + 1, w - 2, h - 2, bg, bg);
		if (!square) {
			GUI.grad(x, y - 1, w, 1, bg, bg);
			GUI.grad(x, y + h, w, 1, bg, bg);
			GUI.grad(x - 1, y, 1, h, bg, bg);
			GUI.grad(x + w, y, 1, h, bg, bg);
		}
		GUI.grad(x, y + 1, 1, h - 2, g1, g2);
		GUI.grad(x + w - 1, y + 1, 1, h - 2, g1, g2);
		GUI.grad(x, y, w, 1, g1, g1);
		GUI.grad(x, y + h - 1, w, 1, g2, g2);
	}

	/** DisplayHelper.drawGradientRect: x, y, width, height, top colour, bottom colour */
	private static final class Gradients extends Gui {
		void grad(int x, int y, int w, int h, int top, int bottom) {
			if (w > 0 && h > 0) {
				drawGradientRect(x, y, x + w, y + h, top, bottom);
			}
		}
	}

	static int ticksOf(float f) {
		return MathHelper.floor(f);
	}
}
