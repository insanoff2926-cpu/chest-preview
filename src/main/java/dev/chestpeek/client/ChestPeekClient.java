package dev.chestpeek.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.chestpeek.ChestPeek;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class ChestPeekClient implements ClientModInitializer {
	private static final int MAX_ROWS = 8;
	private static final int ROW_H = 18;
	private static final int WIDTH = 132;
	private static final int REFRESH_TICKS = 10;

	private static KeyMapping toggleKey;
	private static boolean enabled = true;
	private static BlockPos target;
	private static BlockPos lastAsked;
	private static int cooldown;
	private static ChestPeek.Response latest;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category cat = KeyMapping.Category.register(ChestPeek.id("main"));
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.chest-peek.toggle", InputConstants.Type.KEYSYM, InputConstants.KEY_O, cat));

		ClientPlayNetworking.registerGlobalReceiver(ChestPeek.Response.TYPE,
				(payload, ctx) -> ctx.client().execute(() -> latest = payload));

		ClientTickEvents.END_CLIENT_TICK.register(ChestPeekClient::tick);
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, ChestPeek.id("peek"),
				(graphics, delta) -> render(graphics));
	}

	private static void tick(Minecraft mc) {
		while (toggleKey.consumeClick()) enabled = !enabled;

		target = null;
		if (enabled && mc.player != null && mc.level != null
				&& mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos pos = hit.getBlockPos();
			if (ChestPeek.isPeekable(mc.level.getBlockState(pos))
					&& mc.player.distanceToSqr(pos.getCenter()) <= ChestPeek.MAX_DISTANCE * ChestPeek.MAX_DISTANCE) {
				target = pos;
			}
		}

		if (target == null) {
			lastAsked = null;
			latest = null;
			return;
		}
		if (cooldown > 0) cooldown--;
		if (!target.equals(lastAsked) || cooldown <= 0) {
			lastAsked = target;
			cooldown = REFRESH_TICKS;
			ClientPlayNetworking.send(new ChestPeek.Request(target));
		}
	}

	private static void render(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		if (target == null || latest == null || !target.equals(latest.pos()) || latest.entries().isEmpty()) return;

		List<ChestPeek.Entry> entries = latest.entries();
		int shown = Math.min(entries.size(), MAX_ROWS);
		int hidden = entries.size() - shown;
		int height = shown * ROW_H + 6 + (hidden > 0 ? 12 : 0);

		// A la derecha del punto de mira, centrado en vertical.
		int x = g.guiWidth() / 2 + 16;
		int y = g.guiHeight() / 2 - height / 2;

		g.fill(x, y, x + WIDTH, y + height, 0xC0101010);
		g.outline(x, y, WIDTH, height, 0x60FFFFFF);

		for (int i = 0; i < shown; i++) {
			ChestPeek.Entry e = entries.get(i);
			int ry = y + 3 + i * ROW_H;
			g.item(e.stack(), x + 3, ry);
			g.text(mc.font, e.stack().getHoverName(), x + 23, ry + 5, 0xFFFFFFFF, false);
			String count = "x" + e.count();
			g.text(mc.font, count, x + WIDTH - 4 - mc.font.width(count), ry + 5, 0xFFFFD56A, false);
		}
		if (hidden > 0) {
			g.text(mc.font, Component.translatable("chest-peek.more", hidden), x + 4, y + height - 12, 0xFFA0A0A0, false);
		}
	}
}
