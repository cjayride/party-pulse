package cjayride.partypulse;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

public class PartyPulseConfigScreen extends Screen {
    private static final int PANEL_WIDTH = 280;
    private static final int PANEL_HEIGHT = 262;
    private static final int PADDING = 12;
    private static final int COLUMN_WIDTH = 124;
    private static final int COLUMN_GAP = 8;
    private static final int FULL_WIDTH = COLUMN_WIDTH * 2 + COLUMN_GAP;
    private static final int ROW_HEIGHT = 16;
    private static final int PANEL_BACKGROUND = 0xEE111827;
    private static final int PANEL_BORDER = 0xFF374151;
    private static final int TITLE_COLOR = 0x38BDF8;
    private static final int MUTED_COLOR = 0x9CA3AF;
    private static final int SECTION_COLOR = 0x6B7280;

    // Section header Y offsets (also used by render()).
    private static final int Y_METER = 30;
    private static final int Y_PARTY = 117;
    private static final int Y_HEALTH = 170;
    private static final int Y_DIVIDER = 235;

    public PartyPulseConfigScreen() {
        super(Text.literal("Party Pulse"));
    }

    @Override
    protected void init() {
        int panelLeft = width / 2 - PANEL_WIDTH / 2;
        int panelTop = height / 2 - PANEL_HEIGHT / 2;
        int left = panelLeft + PADDING;
        int right = left + COLUMN_WIDTH + COLUMN_GAP;

        // --- METER ---
        addDrawableChild(scaleSlider(left, panelTop + Y_METER + 10));
        addDrawableChild(opacitySlider(right, panelTop + Y_METER + 10));

        addDrawableChild(ButtonWidget.builder(metricText(), button -> {
            PartyPulseClient.cycleDisplayMode();
            button.setMessage(metricText());
            PartyPulseClient.saveConfig();
        }).dimensions(left, panelTop + Y_METER + 27, COLUMN_WIDTH, ROW_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("Cycle Damage, DPS, Healing, and HPS"))).build());

        addDrawableChild(ButtonWidget.builder(sortText(), button -> {
            PartyPulseClient.sortingType = (PartyPulseClient.sortingType + 1) % 3;
            button.setMessage(sortText());
            PartyPulseClient.saveConfig();
        }).dimensions(right, panelTop + Y_METER + 27, COLUMN_WIDTH, ROW_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("Change party-frame sorting"))).build());

        addDrawableChild(ButtonWidget.builder(cornerText(), button -> {
            PartyPulseClient.hudCorner = (PartyPulseClient.hudCorner + 1) % 4;
            button.setMessage(cornerText());
            PartyPulseClient.saveConfig();
        }).dimensions(left, panelTop + Y_METER + 44, COLUMN_WIDTH, ROW_HEIGHT).build());

        addDrawableChild(ButtonWidget.builder(toggleText("Scores", !PartyPulseClient.hideNumbersOnly), button -> {
            PartyPulseClient.hideNumbersOnly = !PartyPulseClient.hideNumbersOnly;
            button.setMessage(toggleText("Scores", !PartyPulseClient.hideNumbersOnly));
            PartyPulseClient.saveConfig();
        }).dimensions(right, panelTop + Y_METER + 44, COLUMN_WIDTH, ROW_HEIGHT).build());

        addDrawableChild(ButtonWidget.builder(toggleText("Compact", PartyPulseClient.truncateNumbers), button -> {
            PartyPulseClient.truncateNumbers = !PartyPulseClient.truncateNumbers;
            button.setMessage(toggleText("Compact", PartyPulseClient.truncateNumbers));
            PartyPulseClient.saveConfig();
        }).dimensions(left, panelTop + Y_METER + 61, COLUMN_WIDTH, ROW_HEIGHT).build());

        addDrawableChild(ButtonWidget.builder(toggleText("HUD", !PartyPulseClient.hideHudEntirely), button -> {
            PartyPulseClient.hideHudEntirely = !PartyPulseClient.hideHudEntirely;
            button.setMessage(toggleText("HUD", !PartyPulseClient.hideHudEntirely));
            PartyPulseClient.saveConfig();
        }).dimensions(right, panelTop + Y_METER + 61, COLUMN_WIDTH, ROW_HEIGHT).build());

        // --- PARTY FRAMES ---
        addDrawableChild(paddingSlider(left, panelTop + Y_PARTY + 10, true));
        addDrawableChild(paddingSlider(right, panelTop + Y_PARTY + 10, false));

        addDrawableChild(ButtonWidget.builder(filterText(), button -> {
            PartyPulseClient.showAllNearby = !PartyPulseClient.showAllNearby;
            button.setMessage(filterText());
            PartyPulseClient.saveConfig();
        }).dimensions(left, panelTop + Y_PARTY + 27, FULL_WIDTH, ROW_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("Show party members or all nearby players"))).build());

        // --- HEALTH BAR ---
        addDrawableChild(barHeightSlider(left, panelTop + Y_HEALTH + 10));
        addDrawableChild(hpTextScaleSlider(right, panelTop + Y_HEALTH + 10));
        addDrawableChild(backPlateSlider(left, panelTop + Y_HEALTH + 27));

        addDrawableChild(ButtonWidget.builder(toggleText("HP Text", !PartyPulseClient.hideHpText), button -> {
            PartyPulseClient.hideHpText = !PartyPulseClient.hideHpText;
            button.setMessage(toggleText("HP Text", !PartyPulseClient.hideHpText));
            PartyPulseClient.saveConfig();
        }).dimensions(left, panelTop + Y_HEALTH + 44, COLUMN_WIDTH, ROW_HEIGHT).build());

        addDrawableChild(ButtonWidget.builder(colorText(), button -> {
            PartyPulseClient.hpColorType = (PartyPulseClient.hpColorType + 1) % 6;
            button.setMessage(colorText());
            PartyPulseClient.saveConfig();
        }).dimensions(right, panelTop + Y_HEALTH + 44, COLUMN_WIDTH, ROW_HEIGHT).build());

        // --- RESET ---
        addDrawableChild(ButtonWidget.builder(Text.literal("§cReset Combat Data"), button -> showResetConfirmation())
                .dimensions(left, panelTop + Y_DIVIDER + 5, FULL_WIDTH, ROW_HEIGHT)
                .tooltip(Tooltip.of(Text.literal("Clear locally displayed combat totals"))).build());
    }

    private void showResetConfirmation() {
        if (client == null) return;
        client.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                PartyPulseClient.triggerLocalReset();
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("§c[Party Pulse] Combat metrics cleared."), true);
                }
            }
            client.setScreen(new PartyPulseConfigScreen());
        }, Text.literal("Reset combat data?"),
                Text.literal("This clears your locally displayed session totals.")));
    }

    private SliderWidget scaleSlider(int x, int y) {
        return new SliderWidget(x, y, COLUMN_WIDTH, ROW_HEIGHT,
                Text.literal(String.format("Scale: %.2fx", PartyPulseClient.hudScale)),
                (PartyPulseClient.hudScale - 0.50f) / 1.5f) {
            @Override
            protected void updateMessage() {
                setMessage(Text.literal(String.format("Scale: %.2fx", PartyPulseClient.hudScale)));
            }

            @Override
            protected void applyValue() {
                PartyPulseClient.hudScale = 0.50f + (float) value * 1.5f;
                PartyPulseClient.saveConfig();
            }
        };
    }

    private SliderWidget opacitySlider(int x, int y) {
        return new SliderWidget(x, y, COLUMN_WIDTH, ROW_HEIGHT,
                Text.literal("Opacity: " + Math.round(PartyPulseClient.hudOpacity * 100) + "%"),
                (PartyPulseClient.hudOpacity - 0.10f) / 0.9f) {
            @Override
            protected void updateMessage() {
                setMessage(Text.literal("Opacity: " + Math.round(PartyPulseClient.hudOpacity * 100) + "%"));
            }

            @Override
            protected void applyValue() {
                PartyPulseClient.hudOpacity = 0.10f + (float) value * 0.9f;
                PartyPulseClient.saveConfig();
            }
        };
    }

    private SliderWidget paddingSlider(int x, int y, boolean horizontal) {
        int current = horizontal ? PartyPulseClient.hudPaddingX : PartyPulseClient.hudPaddingY;
        return new SliderWidget(x, y, COLUMN_WIDTH, ROW_HEIGHT,
                Text.literal("Padding " + (horizontal ? "X: " : "Y: ") + current),
                current / 100.0f) {
            @Override
            protected void updateMessage() {
                int padding = horizontal ? PartyPulseClient.hudPaddingX : PartyPulseClient.hudPaddingY;
                setMessage(Text.literal("Padding " + (horizontal ? "X: " : "Y: ") + padding));
            }

            @Override
            protected void applyValue() {
                if (horizontal) PartyPulseClient.hudPaddingX = (int) (value * 100);
                else PartyPulseClient.hudPaddingY = (int) (value * 100);
                PartyPulseClient.saveConfig();
            }
        };
    }

    private SliderWidget barHeightSlider(int x, int y) {
        return new SliderWidget(x, y, COLUMN_WIDTH, ROW_HEIGHT,
                Text.literal("Bar: " + PartyPulseClient.hudBarHeight + "px"),
                (PartyPulseClient.hudBarHeight - 2) / 10.0f) {
            @Override
            protected void updateMessage() {
                setMessage(Text.literal("Bar: " + PartyPulseClient.hudBarHeight + "px"));
            }

            @Override
            protected void applyValue() {
                PartyPulseClient.hudBarHeight = 2 + (int) (value * 10);
                PartyPulseClient.saveConfig();
            }
        };
    }

    private SliderWidget hpTextScaleSlider(int x, int y) {
        return new SliderWidget(x, y, COLUMN_WIDTH, ROW_HEIGHT,
                Text.literal(String.format("HP Size: %.2fx", PartyPulseClient.hpTextScale)),
                (PartyPulseClient.hpTextScale - 0.35f) / 0.85f) {
            @Override
            protected void updateMessage() {
                setMessage(Text.literal(String.format("HP Size: %.2fx", PartyPulseClient.hpTextScale)));
            }

            @Override
            protected void applyValue() {
                PartyPulseClient.hpTextScale = 0.35f + (float) value * 0.85f;
                PartyPulseClient.saveConfig();
            }
        };
    }

    private SliderWidget backPlateSlider(int x, int y) {
        return new SliderWidget(x, y, FULL_WIDTH, ROW_HEIGHT,
                Text.literal("HP Back Plate: " + Math.round(PartyPulseClient.hpBgOpacity * 100) + "%"),
                PartyPulseClient.hpBgOpacity) {
            @Override
            protected void updateMessage() {
                setMessage(Text.literal("HP Back Plate: " + Math.round(PartyPulseClient.hpBgOpacity * 100) + "%"));
            }

            @Override
            protected void applyValue() {
                PartyPulseClient.hpBgOpacity = (float) value;
                PartyPulseClient.saveConfig();
            }
        };
    }

    private static Text metricText() {
        return Text.literal("Metric: §b" + PartyPulseClient.getDisplayModeLabel());
    }

    private static Text filterText() {
        return Text.literal("Filter: §b" + PartyPulseClient.getFilterLabel());
    }

    private static Text toggleText(String label, boolean enabled) {
        return Text.literal((enabled ? "§a" : "§7") + label + ": " + (enabled ? "ON" : "OFF"));
    }

    private static Text sortText() {
        String value = switch (PartyPulseClient.sortingType) {
            case 0 -> "Ranked";
            case 1 -> "A-Z";
            default -> "Self Top";
        };
        return Text.literal("Sort: " + value);
    }

    private static Text cornerText() {
        String value = switch (PartyPulseClient.hudCorner) {
            case 1 -> "Top Right";
            case 2 -> "Bottom Left";
            case 3 -> "Bottom Right";
            default -> "Top Left";
        };
        return Text.literal("Corner: " + value);
    }

    private static Text colorText() {
        String value = switch (PartyPulseClient.hpColorType) {
            case 1 -> "Cyan";
            case 2 -> "Gold";
            case 3 -> "Red";
            case 4 -> "Lime";
            case 5 -> "Gray";
            default -> "White";
        };
        return Text.literal("Color: " + value);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        int panelLeft = width / 2 - PANEL_WIDTH / 2;
        int panelTop = height / 2 - PANEL_HEIGHT / 2;
        int left = panelLeft + PADDING;

        context.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + PANEL_HEIGHT, PANEL_BACKGROUND);
        context.drawBorder(panelLeft, panelTop, PANEL_WIDTH, PANEL_HEIGHT, PANEL_BORDER);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Party Pulse"), width / 2, panelTop + 6, TITLE_COLOR);

        // Hotkey reference row, small and clearly labeled.
        context.getMatrices().push();
        context.getMatrices().scale(0.6f, 0.6f, 1.0f);
        String hotkeys = "§8HOTKEYS§r  "
                + PartyPulseClient.formatCtrlHotkey(PartyPulseClient.getCycleMetricKey()) + " Metric  •  "
                + PartyPulseClient.formatCtrlHotkey(PartyPulseClient.getToggleFilterKey()) + " Filter  •  "
                + PartyPulseClient.formatCtrlHotkey(PartyPulseClient.getResetSessionKey()) + " Reset";
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(hotkeys),
                (int) ((width / 2.0f) / 0.6f), (int) ((panelTop + 18) / 0.6f), MUTED_COLOR);
        context.getMatrices().pop();

        drawSection(context, left, panelTop + Y_METER, "\u2694", "METER");
        drawSection(context, left, panelTop + Y_PARTY, "\u263B", "PARTY FRAMES");
        drawSection(context, left, panelTop + Y_HEALTH, "\u2665", "HEALTH BAR");
        context.fill(left, panelTop + Y_DIVIDER, left + FULL_WIDTH, panelTop + Y_DIVIDER + 1, PANEL_BORDER);

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawSection(DrawContext context, int x, int y, String icon, String label) {
        context.drawText(textRenderer, Text.literal(icon), x, y, TITLE_COLOR, false);
        int iconWidth = textRenderer.getWidth(icon) + 4;
        context.drawText(textRenderer, Text.literal(label), x + iconWidth, y, SECTION_COLOR, false);
    }
}
