package cjayride.partypulse;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class PartyPulse implements ModInitializer {
	public static final String MOD_ID = "party-pulse";
	public static final Identifier STATS_SYNC_PACKET = new Identifier(MOD_ID, "stats_sync");
	public static final Identifier PARTY_SYNC_PACKET = new Identifier(MOD_ID, "party_sync");
	public static final Identifier REQUEST_PARTY_PACKET = new Identifier(MOD_ID, "request_party");

	public static final Map<UUID, PlayerStats> playerStats = new HashMap<>();

	private static File statsFile;

	@Override
	public void onInitialize() {
		System.out.println("[Party Pulse] Server combat tracking online.");

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			File configDir = new File(server.getRunDirectory(), "config");
			if (!configDir.exists()) configDir.mkdirs();
			statsFile = new File(configDir, "party-pulse_server_stats.json");
			if (statsFile.exists()) statsFile.delete();
			playerStats.clear();
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			long now = System.currentTimeMillis();

			for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
				PlayerStats stats = playerStats.computeIfAbsent(player.getUuid(), uuid -> new PlayerStats());

				if (stats.inCombat) {
					if (now - stats.lastActivityTime > 5000) {
						stats.inCombat = false;
						saveStatsToJson();
					}
				}

				if (player.age % 20 == 0) {
					broadcastStats(player, stats);
				}
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(REQUEST_PARTY_PACKET, (server, player, handler, buf, responseSender) ->
				server.execute(() -> {
					syncPartyRoster(player);
					// Refresh combat + health for this client (covers other dimensions).
					pushAllStatsTo(player);
				}));
	}

	public static synchronized void saveStatsToJson() {
		if (statsFile == null) return;
		try {
			StringBuilder json = new StringBuilder("{\n");
			int i = 0;
			for (Map.Entry<UUID, PlayerStats> entry : playerStats.entrySet()) {
				PlayerStats stats = entry.getValue();
				json.append(String.format(Locale.ROOT, "  \"%s\": {\n", entry.getKey()));
				json.append(String.format(Locale.ROOT, "    \"totalDamage\": %.4f,\n", stats.totalDamage));
				json.append(String.format(Locale.ROOT, "    \"totalHealing\": %.4f,\n", stats.totalHealing));
				json.append(String.format(Locale.ROOT, "    \"lastActivityTime\": %d\n", stats.lastActivityTime));
				json.append(i == playerStats.size() - 1 ? "  }\n" : "  },\n");
				i++;
			}
			json.append("}");
			try (FileWriter writer = new FileWriter(statsFile)) {
				writer.write(json.toString());
			}
		} catch (Exception ignored) {
		}
	}

	public static void broadcastStats(ServerPlayerEntity player, PlayerStats stats) {
		if (player == null || player.getServer() == null) return;
		for (ServerPlayerEntity recipient : player.getServer().getPlayerManager().getPlayerList()) {
			sendStatsTo(recipient, player, stats);
		}
	}

	/** Push every online player's stats/health to one recipient (party refresh). */
	public static void pushAllStatsTo(ServerPlayerEntity recipient) {
		if (recipient == null || recipient.getServer() == null) return;
		for (ServerPlayerEntity other : recipient.getServer().getPlayerManager().getPlayerList()) {
			PlayerStats stats = playerStats.computeIfAbsent(other.getUuid(), uuid -> new PlayerStats());
			sendStatsTo(recipient, other, stats);
		}
	}

	public static void sendStatsTo(ServerPlayerEntity recipient, ServerPlayerEntity subject, PlayerStats stats) {
		if (recipient == null || subject == null || stats == null) return;
		long currentTick = subject.getWorld().getTime();
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeUuid(subject.getUuid());
		buf.writeDouble(stats.totalDamage);
		buf.writeDouble(stats.totalHealing);
		buf.writeDouble(stats.getDPS(currentTick));
		buf.writeDouble(stats.getHPS(currentTick));
		buf.writeFloat(subject.getHealth());
		buf.writeFloat(subject.getMaxHealth());
		buf.writeLong(stats.lastActivityTime);
		ServerPlayNetworking.send(recipient, STATS_SYNC_PACKET, buf);
	}

	/**
	 * Resolves the player responsible for a damage source, walking projectile
	 * owners and reflective owner/caster/shooter accessors on modded entities.
	 */
	public static ServerPlayerEntity resolveAttacker(DamageSource source) {
		ServerPlayerEntity player = resolvePlayer(source.getAttacker(), 0);
		return player != null ? player : resolvePlayer(source.getSource(), 0);
	}

	private static ServerPlayerEntity resolvePlayer(Entity entity, int depth) {
		if (entity == null || depth > 3) return null;
		if (entity instanceof ServerPlayerEntity player) return player;
		if (entity instanceof ProjectileEntity projectile) {
			return resolvePlayer(projectile.getOwner(), depth + 1);
		}

		String[] ownerMethods = {"getOwner", "getCaster", "getShooter"};
		for (String methodName : ownerMethods) {
			try {
				Object owner = entity.getClass().getMethod(methodName).invoke(entity);
				if (owner instanceof Entity ownerEntity && ownerEntity != entity) {
					ServerPlayerEntity player = resolvePlayer(ownerEntity, depth + 1);
					if (player != null) return player;
				}
			} catch (Exception ignored) {
			}
		}
		return null;
	}

	/**
	 * Records effective damage dealt to a victim, resolving the attacking player
	 * from the damage source. Called from the applyDamage mixins.
	 */
	public static void recordEffectiveDamage(net.minecraft.entity.LivingEntity victim, DamageSource source, float effective) {
		if (effective <= 0.0f) return;
		ServerPlayerEntity attacker = resolveAttacker(source);
		if (attacker == null) return;
		recordDamage(attacker, effective, victim.getWorld().getTime());
	}

	public static void recordDamage(ServerPlayerEntity attacker, float amount, long tick) {
		PlayerStats stats = playerStats.computeIfAbsent(attacker.getUuid(), uuid -> new PlayerStats());
		stats.addDamage(amount, tick);
		broadcastStats(attacker, stats);
		syncPartyRoster(attacker);
	}

	public static void recordHealing(ServerPlayerEntity healer, float amount, long tick) {
		PlayerStats stats = playerStats.computeIfAbsent(healer.getUuid(), uuid -> new PlayerStats());
		stats.addHealing(amount, tick);
		broadcastStats(healer, stats);
	}

	/**
	 * Set around Spell Engine heal invokes (Inject, not Redirect) so
	 * {@link #recordEffectiveHeal} credits the caster. Avoids competing with
	 * Prominence/Prominent's {@code @Redirect} on the same heal call.
	 */
	private static final ThreadLocal<ServerPlayerEntity> SPELL_ENGINE_HEALER = new ThreadLocal<>();

	public static void setSpellEngineHealer(ServerPlayerEntity healer) {
		SPELL_ENGINE_HEALER.set(healer);
	}

	public static void clearSpellEngineHealer() {
		SPELL_ENGINE_HEALER.remove();
	}

	public static ServerPlayerEntity getSpellEngineHealer() {
		return SPELL_ENGINE_HEALER.get();
	}

	/**
	 * Credits effective (non-overheal) healing from {@link LivingEntity#heal(float)}.
	 * Spell Engine heals credit the stashed caster; otherwise the healed player
	 * (Death Strike, potions/flasks, food regen, etc.).
	 */
	public static void recordEffectiveHeal(net.minecraft.entity.LivingEntity healed, float amount) {
		if (amount <= 0.0f || healed.getWorld().isClient) return;

		ServerPlayerEntity spellHealer = getSpellEngineHealer();
		if (spellHealer != null) {
			recordHealing(spellHealer, amount, spellHealer.getWorld().getTime());
			return;
		}

		if (healed instanceof ServerPlayerEntity healer) {
			recordHealing(healer, amount, healer.getWorld().getTime());
		}
	}

	public static void syncPartyRoster(ServerPlayerEntity player) {
		if (player == null || player.getServer() == null) return;
		List<UUID> partyMembers = new ArrayList<>();

		try {
			Class<?> apiClass = Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
			Object api = apiClass.getMethod("get", net.minecraft.server.MinecraftServer.class)
					.invoke(null, player.getServer());
			Object manager = api.getClass().getMethod("getPartyManager").invoke(api);
			Object party = manager.getClass().getMethod("getPartyByMember", UUID.class)
					.invoke(manager, player.getUuid());
			if (party != null) {
				Object streamObject = party.getClass().getMethod("getMemberInfoStream").invoke(party);
				if (streamObject instanceof java.util.stream.Stream<?> members) {
					try (members) {
						members.forEach(member -> {
							try {
								UUID uuid = (UUID) member.getClass().getMethod("getUUID").invoke(member);
								if (uuid != null) partyMembers.add(uuid);
							} catch (Exception ignored) {
							}
						});
					}
				}
			}
		} catch (Exception ignored) {
		}

		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeInt(partyMembers.size());
		for (UUID uuid : partyMembers) buf.writeUuid(uuid);
		ServerPlayNetworking.send(player, PARTY_SYNC_PACKET, buf);
	}

	public static class PlayerStats {
		/** A parse ends after this many ticks without activity (15s, matches the target dummy). */
		private static final long PARSE_TIMEOUT_TICKS = 300;

		// Cumulative totals: only cleared by manual reset (client-side baselines).
		public double totalDamage;
		public double totalHealing;
		public long lastActivityTime;
		public boolean inCombat;

		// Current combat parse, tick-based, independent clocks for damage and healing.
		private double parseDamage;
		private long damageParseStartTick;
		private long lastDamageTick;
		private double parseHealing;
		private long healingParseStartTick;
		private long lastHealingTick;

		// Client-side only: values received from the server sync packet.
		public double displayDps;
		public double displayHps;

		public void addDamage(double amount, long tick) {
			if (parseDamage <= 0 || tick - lastDamageTick > PARSE_TIMEOUT_TICKS) {
				parseDamage = 0;
				damageParseStartTick = tick;
			}
			parseDamage += amount;
			lastDamageTick = tick;
			totalDamage += amount;
			markActive();
		}

		public void addHealing(double amount, long tick) {
			if (parseHealing <= 0 || tick - lastHealingTick > PARSE_TIMEOUT_TICKS) {
				parseHealing = 0;
				healingParseStartTick = tick;
			}
			parseHealing += amount;
			lastHealingTick = tick;
			totalHealing += amount;
			markActive();
		}

		private void markActive() {
			inCombat = true;
			lastActivityTime = System.currentTimeMillis();
		}

		/**
		 * Parse DPS using the target dummy's formula: damage / (elapsed seconds + 1).
		 * Freezes at the last value between hits, drops to 0 after the parse times out.
		 */
		public double getDPS(long currentTick) {
			if (parseDamage <= 0 || currentTick - lastDamageTick > PARSE_TIMEOUT_TICKS) return 0;
			return parseDamage / ((lastDamageTick - damageParseStartTick) / 20.0 + 1.0);
		}

		public double getHPS(long currentTick) {
			if (parseHealing <= 0 || currentTick - lastHealingTick > PARSE_TIMEOUT_TICKS) return 0;
			return parseHealing / ((lastHealingTick - healingParseStartTick) / 20.0 + 1.0);
		}
	}
}
