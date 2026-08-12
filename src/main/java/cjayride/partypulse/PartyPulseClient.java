package cjayride.partypulse;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Pair;
import net.minecraft.util.math.Box;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PartyPulseClient implements ClientModInitializer {
    public static final int MODE_DAMAGE = 0;
    public static final int MODE_DPS = 1;
    public static final int MODE_HEALING = 2;
    public static final int MODE_HPS = 3;
    private static final int MODE_COUNT = 4;

    public static int hudCorner;
    public static int displayMode;
    public static boolean showAllNearby;
    public static boolean truncateNumbers = true;
    public static boolean hideHudEntirely;
    public static boolean hideNumbersOnly;
    public static boolean hideHpText;
    public static float hudScale = 1.0f;
    public static float hudOpacity = 1.0f;
    public static int sortingType = 2;
    public static float hpTextScale = 0.66f;
    public static float hpBgOpacity = 0.55f;
    public static int hpColorType;
    public static int hudPaddingX;
    public static int hudPaddingY = 30;
    public static int hudBarHeight = 6;
    public static long localSessionResetTimestamp;

    public static final List<UUID> openPacPartyMembers = new ArrayList<>();
    /** Written from net thread / client.execute; read while rendering HUD. */
    public static final Map<UUID, Float> serverSyncedHealth = new ConcurrentHashMap<>();
    public static final Map<UUID, Float> serverSyncedMaxHealth = new ConcurrentHashMap<>();
    public static final Map<UUID, PartyPulse.PlayerStats> clientPlayerStats = new ConcurrentHashMap<>();

    private static final Map<UUID, Double> damageResetBaselines = new HashMap<>();
    private static final Map<UUID, Double> healingResetBaselines = new HashMap<>();
    /** Damage/healing shown on this client; only grows while the player is in metric range. */
    private static final Map<UUID, Double> displayedDamage = new HashMap<>();
    private static final Map<UUID, Double> displayedHealing = new HashMap<>();
    private static final Map<UUID, Double> displayedDps = new HashMap<>();
    private static final Map<UUID, Double> displayedHps = new HashMap<>();
    private static final Map<UUID, Double> damageRangeAnchor = new HashMap<>();
    private static final Map<UUID, Double> healingRangeAnchor = new HashMap<>();
    private static final Set<UUID> playersInMetricRange = new HashSet<>();
    private static final Map<UUID, ItemStack> lastKnownTrinketCache = new HashMap<>();
    private static final Map<UUID, Long> trinketCheckCooldowns = new HashMap<>();
    private static KeyBinding cycleMetricKey;
    private static KeyBinding toggleFilterKey;
    private static KeyBinding resetSessionKey;
    private static File configFile;
    private static Screen screenScheduledToOpen;

    @Override
    public void onInitializeClient() {
        configFile = new File(MinecraftClient.getInstance().runDirectory, "config/party-pulse.json");
        loadConfig();

        cycleMetricKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("Cycle HUD Metric", GLFW.GLFW_KEY_HOME, "Party Pulse"));
        toggleFilterKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("Toggle Party/Nearby Filter", GLFW.GLFW_KEY_END, "Party Pulse"));
        resetSessionKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("Reset Combat Session", GLFW.GLFW_KEY_DELETE, "Party Pulse"));

        registerHudRenderer();
        registerClientTick();
        registerCommands();
        registerPacketReceivers();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearSessionCaches());
    }

    public static void cycleDisplayMode() {
        displayMode = (displayMode + 1) % MODE_COUNT;
    }

    public static String getDisplayModeLabel() {
        return switch (displayMode) {
            case MODE_DPS -> "DPS";
            case MODE_HEALING -> "Healing";
            case MODE_HPS -> "HPS";
            default -> "Damage";
        };
    }

    public static KeyBinding getCycleMetricKey() {
        return cycleMetricKey;
    }

    public static KeyBinding getToggleFilterKey() {
        return toggleFilterKey;
    }

    public static KeyBinding getResetSessionKey() {
        return resetSessionKey;
    }

    public static String formatCtrlHotkey(KeyBinding binding) {
        return binding == null ? "Ctrl+?" : "Ctrl+" + binding.getBoundKeyLocalizedText().getString();
    }

    public static void triggerLocalReset() {
        localSessionResetTimestamp = System.currentTimeMillis();
        clientPlayerStats.forEach((uuid, stats) -> {
            damageResetBaselines.merge(uuid, stats.totalDamage, Double::sum);
            healingResetBaselines.merge(uuid, stats.totalHealing, Double::sum);
        });
        clientPlayerStats.clear();
        clearDisplayedMetrics();
    }

    private static void clearSessionCaches() {
        clientPlayerStats.clear();
        damageResetBaselines.clear();
        healingResetBaselines.clear();
        clearDisplayedMetrics();
        openPacPartyMembers.clear();
        serverSyncedHealth.clear();
        serverSyncedMaxHealth.clear();
        lastKnownTrinketCache.clear();
        trinketCheckCooldowns.clear();
        localSessionResetTimestamp = 0L;
    }

    private static void clearDisplayedMetrics() {
        displayedDamage.clear();
        displayedHealing.clear();
        displayedDps.clear();
        displayedHps.clear();
        damageRangeAnchor.clear();
        healingRangeAnchor.clear();
        playersInMetricRange.clear();
    }

    /**
     * In range: keep updating the on-screen totals from new combat.
     * Out of range: freeze the last on-screen totals (never wipe to 0), and
     * ignore damage/healing the player does while away so it does not jump
     * the moment they walk back into range.
     * <p>
     * Wait until at least one server stats packet exists for this player before
     * anchoring — otherwise the first sync can look like a huge damage spike
     * (anchor was 0, then server total jumps to the real cumulative value).
     */
    private static void syncDisplayedMetrics(MinecraftClient client, PlayerData player) {
        PartyPulse.PlayerStats stats = clientPlayerStats.get(player.uuid);
        boolean inRange = isWithinMetricRange(client, player);
        boolean wasInRange = playersInMetricRange.contains(player.uuid);

        if (!inRange) {
            playersInMetricRange.remove(player.uuid);
            return;
        }

        // Not yet: avoid anchoring to 0 before the first STATS_SYNC arrives.
        if (stats == null) return;

        double serverDamage = stats.totalDamage;
        double serverHealing = stats.totalHealing;
        double serverDps = stats.displayDps;
        double serverHps = stats.displayHps;

        if (!wasInRange) {
            // Entering range: only damage/healing from this moment forward counts.
            damageRangeAnchor.put(player.uuid, serverDamage);
            healingRangeAnchor.put(player.uuid, serverHealing);
            displayedDamage.putIfAbsent(player.uuid, 0.0);
            displayedHealing.putIfAbsent(player.uuid, 0.0);
        } else {
            double damageAnchor = damageRangeAnchor.getOrDefault(player.uuid, serverDamage);
            double healingAnchor = healingRangeAnchor.getOrDefault(player.uuid, serverHealing);
            displayedDamage.put(player.uuid,
                    displayedDamage.getOrDefault(player.uuid, 0.0) + (serverDamage - damageAnchor));
            displayedHealing.put(player.uuid,
                    displayedHealing.getOrDefault(player.uuid, 0.0) + (serverHealing - healingAnchor));
            damageRangeAnchor.put(player.uuid, serverDamage);
            healingRangeAnchor.put(player.uuid, serverHealing);
        }
        displayedDps.put(player.uuid, serverDps);
        displayedHps.put(player.uuid, serverHps);
        playersInMetricRange.add(player.uuid);
    }

    private static double getDisplayedMetric(UUID uuid) {
        return switch (displayMode) {
            case MODE_DPS -> displayedDps.getOrDefault(uuid, 0.0);
            case MODE_HEALING -> displayedHealing.getOrDefault(uuid, 0.0);
            case MODE_HPS -> displayedHps.getOrDefault(uuid, 0.0);
            default -> displayedDamage.getOrDefault(uuid, 0.0);
        };
    }

    private void registerClientTick() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            if (screenScheduledToOpen != null) {
                client.setScreen(screenScheduledToOpen);
                screenScheduledToOpen = null;
            }
            if (cycleMetricKey.wasPressed() && Screen.hasControlDown()) {
                cycleDisplayMode();
                client.player.sendMessage(Text.literal("§b[Party Pulse] Metric: " + getDisplayModeLabel()), true);
                saveConfig();
            }
            if (toggleFilterKey.wasPressed() && Screen.hasControlDown()) {
                showAllNearby = !showAllNearby;
                client.player.sendMessage(Text.literal("§b[Party Pulse] Filter: " + getFilterLabel()), true);
                saveConfig();
            }
            if (resetSessionKey.wasPressed() && Screen.hasControlDown()) {
                triggerLocalReset();
                client.player.sendMessage(Text.literal("§c[Party Pulse] Combat session cleared."), true);
            }
            if (client.player.age % 40 == 0 && client.getNetworkHandler() != null) {
                ClientPlayNetworking.send(PartyPulse.REQUEST_PARTY_PACKET, net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create());
            }
        });
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("pulse")
                        .then(ClientCommandManager.literal("menu").executes(context -> {
                            screenScheduledToOpen = new PartyPulseConfigScreen();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("corner").executes(context -> {
                            hudCorner = (hudCorner + 1) % 4;
                            saveConfig();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("mode").executes(context -> {
                            cycleDisplayMode();
                            saveConfig();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("filter").executes(context -> {
                            showAllNearby = !showAllNearby;
                            saveConfig();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("toggle").executes(context -> {
                            hideHudEntirely = !hideHudEntirely;
                            saveConfig();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("numbersonly").executes(context -> {
                            hideNumbersOnly = !hideNumbersOnly;
                            saveConfig();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("hptext").executes(context -> {
                            hideHpText = !hideHpText;
                            saveConfig();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("sorting").executes(context -> {
                            sortingType = (sortingType + 1) % 3;
                            saveConfig();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("values").executes(context -> {
                            truncateNumbers = !truncateNumbers;
                            saveConfig();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("reset").executes(context -> {
                            triggerLocalReset();
                            return 1;
                        }))));
    }

    private void registerPacketReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(PartyPulse.STATS_SYNC_PACKET, (client, handler, buf, sender) -> {
            UUID id = buf.readUuid();
            double damage = buf.readDouble();
            double healing = buf.readDouble();
            double dps = buf.readDouble();
            double hps = buf.readDouble();
            float health = buf.readFloat();
            float maxHealth = buf.readFloat();
            long serverLastActivity = buf.readLong();

            client.execute(() -> {
                if (serverLastActivity < localSessionResetTimestamp && !damageResetBaselines.containsKey(id)) {
                    damageResetBaselines.put(id, damage);
                    healingResetBaselines.put(id, healing);
                }

                PartyPulse.PlayerStats stats = clientPlayerStats.computeIfAbsent(id, uuid -> new PartyPulse.PlayerStats());
                stats.totalDamage = Math.max(0.0, damage - damageResetBaselines.getOrDefault(id, 0.0));
                stats.totalHealing = Math.max(0.0, healing - healingResetBaselines.getOrDefault(id, 0.0));
                stats.displayDps = dps;
                stats.displayHps = hps;
                stats.lastActivityTime = serverLastActivity;
                serverSyncedHealth.put(id, health);
                serverSyncedMaxHealth.put(id, maxHealth);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(PartyPulse.PARTY_SYNC_PACKET, (client, handler, buf, sender) -> {
            int size = buf.readInt();
            List<UUID> freshMembers = new ArrayList<>();
            for (int i = 0; i < size; i++) freshMembers.add(buf.readUuid());
            client.execute(() -> {
                synchronized (openPacPartyMembers) {
                    openPacPartyMembers.clear();
                    openPacPartyMembers.addAll(freshMembers);
                }
            });
        });
    }

    private static List<UUID> fetchNativeOpenPacParty() {
        List<UUID> partyMembers = new ArrayList<>();
        try {
            Class<?> apiClass = Class.forName("xaero.pac.client.api.OpenPACClientAPI");
            Object api = apiClass.getMethod("get").invoke(null);
            if (api == null) return partyMembers;
            Object storage = api.getClass().getMethod("getClientPartyStorage").invoke(api);
            if (storage == null) return partyMembers;
            Object party = storage.getClass().getMethod("getParty").invoke(storage);
            if (party == null) return partyMembers;
            java.util.stream.Stream<?> members =
                    (java.util.stream.Stream<?>) party.getClass().getMethod("getMemberInfoStream").invoke(party);
            if (members != null) {
                members.forEach(member -> {
                    try {
                        UUID uuid = (UUID) member.getClass().getMethod("getUUID").invoke(member);
                        if (uuid != null) partyMembers.add(uuid);
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (Exception ignored) {
        }
        return partyMembers;
    }

    private void registerHudRenderer() {
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null || client.options.hudHidden || hideHudEntirely) return;

            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, hudOpacity);
            drawContext.getMatrices().push();
            drawContext.getMatrices().scale(hudScale, hudScale, 1.0f);

            int scaledWidth = (int) (client.getWindow().getScaledWidth() / hudScale);
            int scaledHeight = (int) (client.getWindow().getScaledHeight() / hudScale);
            int startX = (hudCorner == 1 || hudCorner == 3) ? scaledWidth - 160 - hudPaddingX : 10 + hudPaddingX;
            List<PlayerData> players = collectVisiblePlayers(client);
            for (PlayerData player : players) {
                syncDisplayedMetrics(client, player);
            }
            players.sort((first, second) -> comparePlayers(client, first, second));
            int contentHeight = players.size() * (20 + hudBarHeight);
            int startY = (hudCorner == 2 || hudCorner == 3)
                    ? scaledHeight - contentHeight - 10 - hudPaddingY
                    : 40 + hudPaddingY;
            int yOffset = startY;

            String mainTag = "§b" + (showAllNearby ? "[Nearby]" : "[Party]");
            drawContext.drawText(client.textRenderer, Text.literal(mainTag), startX, yOffset - 12, 0xFFFFFF, true);
            if (!hideNumbersOnly) {
                drawSmallText(drawContext, client, " - " + getDisplayModeLabel(),
                        startX + client.textRenderer.getWidth(mainTag), yOffset - 11, 0x9CA3AF);
            }

            for (PlayerData player : players) {
                renderPlayerRow(client, drawContext, player, startX, yOffset);
                yOffset += 20 + hudBarHeight;
            }

            drawContext.getMatrices().pop();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        });
    }

    private static List<PlayerData> collectVisiblePlayers(MinecraftClient client) {
        List<PlayerData> players = new ArrayList<>();
        Box range = new Box(client.player.getX() - 128, client.player.getY() - 128, client.player.getZ() - 128,
                client.player.getX() + 128, client.player.getY() + 128, client.player.getZ() + 128);

        if (showAllNearby) {
            for (PlayerEntity player : client.world.getEntitiesByClass(PlayerEntity.class, range, entity -> true)) {
                players.add(createPlayerData(player.getUuid(), player.getName().getString(), player));
            }
            return players;
        }

        if (client.getNetworkHandler() == null) return players;
        List<UUID> partyMembers = fetchNativeOpenPacParty();
        if (partyMembers.isEmpty()) partyMembers = openPacPartyMembers;

        for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
            UUID uuid = entry.getProfile().getId();
            PlayerEntity worldPlayer = client.world.getPlayerByUuid(uuid);
            if (uuid.equals(client.player.getUuid()) || partyMembers.contains(uuid)) {
                players.add(createPlayerData(uuid, entry.getProfile().getName(), worldPlayer));
            }
        }
        return players;
    }

    private static PlayerData createPlayerData(UUID uuid, String name, PlayerEntity player) {
        Float syncedHealth = serverSyncedHealth.get(uuid);
        Float syncedMaxHealth = serverSyncedMaxHealth.get(uuid);

        // Prefer server sync so other-dimension party members keep real HP
        // (client world has no entity there — the old fallback was a fake 20).
        float health;
        float maxHealth;
        if (syncedHealth != null && syncedMaxHealth != null) {
            health = syncedHealth;
            maxHealth = syncedMaxHealth;
        } else if (player != null) {
            health = player.getHealth();
            maxHealth = player.getMaxHealth();
        } else {
            health = 0.0f;
            maxHealth = 0.0f;
        }
        return new PlayerData(uuid, name, health, maxHealth, player);
    }

    /**
     * Same dimension and within 128 blocks. A missing entity means other
     * dimension / out of render distance — treat as out of metric range.
     */
    private static boolean isWithinMetricRange(MinecraftClient client, PlayerData player) {
        if (player.uuid.equals(client.player.getUuid())) return true;
        return player.entity != null && client.player.distanceTo(player.entity) <= 128.0f;
    }

    private static int comparePlayers(MinecraftClient client, PlayerData first, PlayerData second) {
        if (sortingType == 2) {
            if (first.uuid.equals(client.player.getUuid()) && !second.uuid.equals(client.player.getUuid())) return -1;
            if (!first.uuid.equals(client.player.getUuid()) && second.uuid.equals(client.player.getUuid())) return 1;
            return first.name.compareToIgnoreCase(second.name);
        }
        if (sortingType == 1) return first.name.compareToIgnoreCase(second.name);
        int comparison = Double.compare(getDisplayedMetric(second.uuid), getDisplayedMetric(first.uuid));
        return comparison == 0 ? first.name.compareToIgnoreCase(second.name) : comparison;
    }

    private static void renderPlayerRow(MinecraftClient client, DrawContext context, PlayerData player, int x, int y) {
        PlayerListEntry entry = client.getNetworkHandler() == null ? null : client.getNetworkHandler().getPlayerListEntry(player.uuid);
        if (entry != null) context.drawTexture(entry.getSkinTexture(), x, y, 8, 8, 8, 8, 8, 8, 64, 64);

        ItemStack trinket = findSpellbookTrinket(player);
        if (!trinket.isEmpty()) context.drawItem(trinket, x + 12, y - 4);
        context.drawText(client.textRenderer, Text.literal(player.name), x + 32, y, 0xFFFFFF, true);

        if (!hideNumbersOnly) {
            drawSmallText(context, client, " - " + formatMetric(getDisplayedMetric(player.uuid)),
                    x + 32 + client.textRenderer.getWidth(player.name), y + 1, 0x9CA3AF);
        }

        int barLeft = x + 32;
        int barTop = y + 10;
        float ratio = player.maxHealth > 0 ? Math.min(1.0f, Math.max(0.0f, player.health / player.maxHealth)) : 0.0f;
        int fillColor = ratio < 0.25f ? 0xFFEF4444 : ratio < 0.50f ? 0xFFEAB308 : 0xFF22C55E;
        context.fill(barLeft, barTop, barLeft + 100, barTop + hudBarHeight, 0x55000000);
        context.fill(barLeft, barTop, barLeft + (int) (ratio * 100), barTop + hudBarHeight, fillColor);
        context.drawBorder(barLeft - 1, barTop - 1, 102, hudBarHeight + 2, 0xFF374151);

        if (!hideHpText) drawHealthText(client, context, player, barLeft, barTop);
    }

    private static ItemStack findSpellbookTrinket(PlayerData player) {
        long now = System.currentTimeMillis();
        long lastCheck = trinketCheckCooldowns.getOrDefault(player.uuid, 0L);
        if (player.entity == null || now - lastCheck <= 2000) {
            return lastKnownTrinketCache.getOrDefault(player.uuid, ItemStack.EMPTY);
        }

        trinketCheckCooldowns.put(player.uuid, now);
        try {
            Class<?> trinketsApi = Class.forName("dev.emi.trinkets.api.TrinketsApi");
            Object optional = trinketsApi
                    .getMethod("getTrinketComponent", net.minecraft.entity.LivingEntity.class)
                    .invoke(null, player.entity);
            if (optional instanceof java.util.Optional<?> componentOptional && componentOptional.isPresent()) {
                Object component = componentOptional.get();
                List<?> equipped = (List<?>) component.getClass().getMethod("getAllEquipped").invoke(component);
                for (Object rawPair : equipped) {
                    if (rawPair instanceof Pair<?, ?> pair) {
                        ItemStack stack = (ItemStack) pair.getRight();
                        String name = stack.getItem().toString().toLowerCase();
                        if (name.contains("tome") || name.contains("knowledge") || name.contains("spell_book")) {
                            lastKnownTrinketCache.put(player.uuid, stack.copy());
                            return stack;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        lastKnownTrinketCache.remove(player.uuid);
        return lastKnownTrinketCache.getOrDefault(player.uuid, ItemStack.EMPTY);
    }

    private static void drawHealthText(MinecraftClient client, DrawContext context, PlayerData player, int barLeft, int barTop) {
        String text = String.format("%.0f/%.0f", player.health, player.maxHealth);
        int textWidth = client.textRenderer.getWidth(text);
        int color = switch (hpColorType) {
            case 1 -> 0x22D3EE;
            case 2 -> 0xFBBF24;
            case 3 -> 0xEF4444;
            case 4 -> 0x4ADE80;
            case 5 -> 0x9CA3AF;
            default -> 0xFFFFFF;
        };
        int centerX = barLeft + 50 - (int) ((textWidth * hpTextScale) / 2);
        context.getMatrices().push();
        if (hpBgOpacity > 0.01f) {
            int alpha = ((int) (hpBgOpacity * 255)) << 24;
            int top = barTop + hudBarHeight / 2 - (int) ((9 * hpTextScale) / 2) - 2;
            context.fill(centerX - 2, top, centerX + (int) (textWidth * hpTextScale) + 2,
                    top + (int) (9 * hpTextScale) + 4, alpha | 0x111827);
        }
        context.getMatrices().scale(hpTextScale, hpTextScale, 1.0f);
        context.drawText(client.textRenderer, Text.literal(text), (int) (centerX / hpTextScale),
                (int) (((barTop + hudBarHeight / 2.0f) / hpTextScale) - 4.0f), color, false);
        context.getMatrices().pop();
    }

    private static void drawSmallText(DrawContext context, MinecraftClient client, String text, int x, int y, int color) {
        context.getMatrices().push();
        context.getMatrices().scale(0.75f, 0.75f, 1.0f);
        context.drawText(client.textRenderer, Text.literal(text), (int) (x / 0.75f), (int) (y / 0.75f), color, true);
        context.getMatrices().pop();
    }

    private static String formatMetric(double value) {
        if (!truncateNumbers || value <= 9999.0) return String.format("%.0f", value);
        if (value >= 1_000_000) return String.format("%.1fM", value / 1_000_000.0);
        return String.format("%.1fK", value / 1_000.0);
    }

    public static String getFilterLabel() {
        return showAllNearby ? "Nearby" : "Party";
    }

    public static void saveConfig() {
        try {
            if (!configFile.getParentFile().exists()) configFile.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(configFile)) {
                writer.write(String.format(Locale.ROOT,
                        "{\n  \"hudCorner\": %d,\n  \"displayMode\": %d,\n  \"showAllNearby\": %b,\n  \"truncateNumbers\": %b,\n  \"hideHudEntirely\": %b,\n  \"hideNumbersOnly\": %b,\n  \"hideHpText\": %b,\n  \"hudScale\": %.2f,\n  \"hudOpacity\": %.2f,\n  \"sortingType\": %d,\n  \"hpTextScale\": %.2f,\n  \"hpBgOpacity\": %.2f,\n  \"hpColorType\": %d,\n  \"hudPaddingX\": %d,\n  \"hudPaddingY\": %d,\n  \"hudBarHeight\": %d\n}",
                        hudCorner, displayMode, showAllNearby, truncateNumbers, hideHudEntirely,
                        hideNumbersOnly, hideHpText, hudScale, hudOpacity, sortingType, hpTextScale,
                        hpBgOpacity, hpColorType, hudPaddingX, hudPaddingY, hudBarHeight));
            }
        } catch (Exception ignored) {
        }
    }

    private void loadConfig() {
        if (!configFile.exists()) return;
        try (FileReader reader = new FileReader(configFile)) {
            StringBuilder json = new StringBuilder();
            int character;
            while ((character = reader.read()) != -1) json.append((char) character);
            String content = json.toString();
            hudCorner = intValue(content, "hudCorner", 0);
            displayMode = Math.floorMod(intValue(content, "displayMode", 0), MODE_COUNT);
            showAllNearby = booleanValue(content, "showAllNearby", false);
            truncateNumbers = booleanValue(content, "truncateNumbers", true);
            hideHudEntirely = booleanValue(content, "hideHudEntirely", false);
            hideNumbersOnly = booleanValue(content, "hideNumbersOnly", false);
            hideHpText = booleanValue(content, "hideHpText", false);
            hudScale = floatValue(content, "hudScale", 1.0f);
            hudOpacity = floatValue(content, "hudOpacity", 1.0f);
            sortingType = intValue(content, "sortingType", 2);
            hpTextScale = floatValue(content, "hpTextScale", 0.66f);
            hpBgOpacity = floatValue(content, "hpBgOpacity", 0.55f);
            hpColorType = intValue(content, "hpColorType", 0);
            hudPaddingX = intValue(content, "hudPaddingX", 0);
            hudPaddingY = intValue(content, "hudPaddingY", 30);
            hudBarHeight = intValue(content, "hudBarHeight", 6);
        } catch (Exception ignored) {
        }
    }

    private static String jsonValue(String json, String key, String fallback) {
        try {
            String target = "\"" + key + "\":";
            int start = json.indexOf(target);
            if (start < 0) return fallback;
            start += target.length();
            int comma = json.indexOf(",", start);
            int brace = json.indexOf("}", start);
            int end = comma < 0 ? brace : brace < 0 ? comma : Math.min(comma, brace);
            return json.substring(start, end).trim().replace("\"", "");
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int intValue(String json, String key, int fallback) {
        try {
            return Integer.parseInt(jsonValue(json, key, String.valueOf(fallback)));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static float floatValue(String json, String key, float fallback) {
        try {
            return Float.parseFloat(jsonValue(json, key, String.valueOf(fallback)));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean booleanValue(String json, String key, boolean fallback) {
        return Boolean.parseBoolean(jsonValue(json, key, String.valueOf(fallback)));
    }

    private record PlayerData(UUID uuid, String name, float health, float maxHealth, PlayerEntity entity) {
    }
}
