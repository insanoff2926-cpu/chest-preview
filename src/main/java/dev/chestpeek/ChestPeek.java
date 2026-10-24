package dev.chestpeek;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Lado común + servidor. El servidor es la autoridad: valida distancia y línea de visión. */
public class ChestPeek implements ModInitializer {
	public static final String MOD_ID = "chest-peek";
	public static final double MAX_DISTANCE = 6.0;

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	/** Cliente -> servidor. */
	public record Request(BlockPos pos) implements CustomPacketPayload {
		public static final Type<Request> TYPE = new Type<>(id("request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC =
				StreamCodec.composite(BlockPos.STREAM_CODEC, Request::pos, Request::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Una línea del resumen: un tipo de ítem y cuántas unidades hay en total. */
	public record Entry(ItemStack stack, int count) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC = StreamCodec.composite(
				ItemStack.OPTIONAL_STREAM_CODEC, Entry::stack,
				ByteBufCodecs.VAR_INT, Entry::count,
				Entry::new);
	}

	/** Servidor -> cliente. Lista vacía = nada que mostrar. */
	public record Response(BlockPos pos, List<Entry> entries) implements CustomPacketPayload {
		public static final Type<Response> TYPE = new Type<>(id("response"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Response> CODEC = StreamCodec.composite(
				BlockPos.STREAM_CODEC, Response::pos,
				Entry.CODEC.apply(ByteBufCodecs.list(64)), Response::entries,
				Response::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(Request.TYPE, Request.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Response.TYPE, Response.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(Request.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			player.level().getServer().execute(() -> answer(player, payload.pos()));
		});
	}

	private static void answer(ServerPlayer player, BlockPos pos) {
		Level level = player.level();
		List<Entry> entries = List.of();
		if (level.hasChunkAt(pos)
				&& player.blockPosition().closerThan(pos, MAX_DISTANCE + 1.0)
				&& canSee(player, pos)) {
			BlockState state = level.getBlockState(pos);
			if (isPeekable(state)) {
				entries = summarize(gather(level, pos, state));
			}
		}
		ServerPlayNetworking.send(player, new Response(pos, entries));
	}

	public static boolean isPeekable(BlockState state) {
		var block = state.getBlock();
		return block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock;
	}

	private static boolean canSee(ServerPlayer player, BlockPos pos) {
		Vec3 to = Vec3.atCenterOf(pos);
		BlockHitResult hit = player.level().clip(
				new ClipContext(player.getEyePosition(), to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
	}

	/** Recoge los stacks del bloque y, si es un cofre doble, también de la otra mitad. */
	private static List<ItemStack> gather(Level level, BlockPos pos, BlockState state) {
		List<ItemStack> out = new ArrayList<>();
		read(level, pos, out);
		if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			Direction facing = state.getValue(ChestBlock.FACING);
			Direction side = state.getValue(ChestBlock.TYPE) == ChestType.LEFT
					? facing.getClockWise() : facing.getCounterClockWise();
			read(level, pos.relative(side), out);
		}
		return out;
	}

	private static void read(Level level, BlockPos pos, List<ItemStack> out) {
		BlockEntity be = level.getBlockEntity(pos);
		if (be instanceof Container container) {
			for (int i = 0; i < container.getContainerSize(); i++) {
				ItemStack s = container.getItem(i);
				if (!s.isEmpty()) out.add(s);
			}
		}
	}

	/** Agrupa por tipo de ítem (mismos componentes) y ordena de mayor a menor cantidad. */
	private static List<Entry> summarize(List<ItemStack> stacks) {
		List<Entry> result = new ArrayList<>();
		for (ItemStack s : stacks) {
			boolean merged = false;
			for (int i = 0; i < result.size(); i++) {
				Entry e = result.get(i);
				if (ItemStack.isSameItemSameComponents(e.stack(), s)) {
					result.set(i, new Entry(e.stack(), e.count() + s.getCount()));
					merged = true;
					break;
				}
			}
			if (!merged) result.add(new Entry(s.copyWithCount(1), s.getCount()));
		}
		result.sort((a, b) -> Integer.compare(b.count(), a.count()));
		return result.size() > 64 ? new ArrayList<>(result.subList(0, 64)) : result;
	}
}
