package net.blockhost.anarchyclient.ui;

import net.blockhost.anarchyclient.AnarchyClient;
import net.blockhost.anarchyclient.config.ClientConfig;
import net.blockhost.anarchyclient.event.HudRenderEvent;
import net.blockhost.anarchyclient.module.ModuleManager;
import net.blockhost.anarchyclient.rivet.Blaze3DRenderer;
import net.blockhost.anarchyclient.rivet.GlassBackdrop;
import net.blockhost.anarchyclient.rivet.GlassPanelCommand;
import net.blockhost.anarchyclient.rivet.SoftShadowCommand;
import net.lenni0451.commons.color.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * BleachHack/Meteor-style HUD editor: renders the live HUD elements at their stored positions and lets
 * the player drag them around. No module list — element placement only; per-module settings stay in the
 * menu inspector. Opened from the button on the HUD tab. Positions are held by {@link HudLayout}.
 *
 * <p>The chrome is drawn through the same {@link Blaze3DRenderer} glass/SDF pipeline as the main menu —
 * anti-aliased rounded frames, an accent active stripe, a soft drop shadow and a frosted-glass hint chip
 * — so the editor matches the liquid-glass design instead of using raw debug rectangles.</p>
 *
 * <p>Dragging from empty space pulls a selection box; every element it touches joins the selection and
 * then moves as one group. A single dragged element still snaps to the screen edges and center, while a
 * group keeps its relative spacing and only clamps to the screen.</p>
 */
public final class HudEditorScreen extends Screen {

    private static final int SNAP = 6;
    private static final int MARGIN = 6;
    private static final float FRAME_PAD = 3F;
    private static final float FRAME_RADIUS = 6F;
    /** Below this drag distance a box selection counts as a click on empty space. */
    private static final double BOX_THRESHOLD = 3.0;
    private static final Color FRAME_FILL = Color.fromRGBA(255, 255, 255, 12);
    private static final Color FRAME_OUTLINE = Color.fromRGBA(255, 255, 255, 70);
    private static final Color SHADOW = Color.fromRGBA(0, 0, 0, 110);

    // Set only while this screen renders its own HUD preview pass, so the normal (vanilla-registered)
    // HUD path stays suppressed and nothing double-draws. Read by every HUD module's render guard.
    private static boolean rendering;

    private final ModuleManager modules;
    private final ClientConfig config;

    /** Elements picked by the last box selection or click; moved together while dragging. */
    private final Set<String> selection = new LinkedHashSet<>();
    /** Cursor-to-top-left offset per element being dragged. Empty while nothing is being dragged. */
    private final Map<String, int[]> dragOffsets = new LinkedHashMap<>();
    private double boxAnchorX;
    private double boxAnchorY;
    private double boxCursorX;
    private double boxCursorY;
    private boolean boxSelecting;
    private boolean dirty;

    public HudEditorScreen(final ModuleManager modules, final ClientConfig config) {
        super(Component.literal("HUD Editor"));
        this.modules = modules;
        this.config = config;
    }

    /** True while a HUD module must not render itself (menu open, or the editor's non-preview passes). */
    public static boolean suppressed(final Minecraft client) {
        Screen screen = client.gui.screen();
        if (screen instanceof AnarchyClientScreen) {
            return true;
        }
        if (screen instanceof HudEditorScreen) {
            return !rendering;
        }
        return false;
    }

    @Override
    protected void init() {
        // Capture a blurred copy of the frame so the frosted-glass hint chip can refract it; the game
        // itself stays sharp (GlassBackdrop restores the crisp frame after the capture).
        GlassBackdrop.activate();
    }

    @Override
    public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY,
                                  final float partialTick) {
        // Triggers the vanilla blur pass GlassBackdrop samples. The game stays fully visible and crisp.
        graphics.blurBeforeThisStratum();
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY,
                                   final float partialTick) {
        // Draw the HUD elements ourselves so they appear over the game while this screen is open.
        HudLayout.clearBounds();
        rendering = true;
        try {
            AnarchyClient.MODULES.call(new HudRenderEvent(this.minecraft, graphics));
        } catch (Throwable throwable) {
            AnarchyClient.LOGGER.error("HUD editor preview render failed", throwable);
        } finally {
            rendering = false;
        }

        Blaze3DRenderer renderer = new Blaze3DRenderer(this.minecraft, graphics);
        Color accent = GlassTheme.accent();
        for (HudLayout.Element element : HudLayout.elements()) {
            boolean active = this.selection.contains(element.id())
                    || this.dragOffsets.containsKey(element.id())
                    || (this.selection.isEmpty() && element.contains(mouseX, mouseY));
            this.renderHandle(renderer, graphics, element, active, accent);
        }
        if (this.boxSelecting) {
            this.renderSelectionBox(renderer, accent);
        }
        this.renderHint(renderer, graphics);
    }

    /** A rounded, anti-aliased frame around an element with an accent active stripe and floating label. */
    private void renderHandle(final Blaze3DRenderer renderer, final GuiGraphicsExtractor graphics,
                              final HudLayout.Element element, final boolean active, final Color accent) {
        float x = element.x() - FRAME_PAD;
        float y = element.y() - FRAME_PAD;
        float width = element.width() + FRAME_PAD * 2F;
        float height = element.height() + FRAME_PAD * 2F;
        // A short element cannot carry the full corner radius: half its smaller side is the most a
        // rounded rect can take before the arcs overlap and the sides bulge.
        float radius = Math.max(1F, Math.min(FRAME_RADIUS, Math.min(width, height) / 2F));

        renderer.fillRoundedRect(x, y, width, height, radius, active ? accent.multiplyAlpha(0.16F) : FRAME_FILL);
        renderer.outlineRoundedRect(x, y, width, height, radius, 1F, active ? accent : FRAME_OUTLINE);
        if (active) {
            renderer.fillRoundedRect(x + 1.5F, y + 4F, 2F, Math.max(2F, height - 8F), 1F, accent);
        }
        int labelColor = (active ? accent : GlassTheme.MUTED).toARGB();
        graphics.text(this.font, element.name(), Math.round(x) + 4, Math.round(y) - 11, labelColor, true);
    }

    /** The rubber-band rectangle dragged across empty space. */
    private void renderSelectionBox(final Blaze3DRenderer renderer, final Color accent) {
        float left = (float) Math.min(this.boxAnchorX, this.boxCursorX);
        float top = (float) Math.min(this.boxAnchorY, this.boxCursorY);
        float width = (float) Math.abs(this.boxCursorX - this.boxAnchorX);
        float height = (float) Math.abs(this.boxCursorY - this.boxAnchorY);
        float radius = Math.max(1F, Math.min(FRAME_RADIUS, Math.min(width, height) / 2F));

        renderer.fillRoundedRect(left, top, width, height, radius, accent.multiplyAlpha(0.12F));
        renderer.outlineRoundedRect(left, top, width, height, radius, 1F, accent);
    }

    /** Frosted-glass hint chip, bottom-center, matching the menu's panels. */
    private void renderHint(final Blaze3DRenderer renderer, final GuiGraphicsExtractor graphics) {
        String hint = "Drag to move  •  Box-select  •  Right-click resets  •  Esc saves";
        float textWidth = this.font.width(hint);
        float width = textWidth + 24F;
        float height = 20F;
        float x = (graphics.guiWidth() - width) / 2F;
        float y = graphics.guiHeight() - height - 10F;
        float radius = Math.min(GlassTheme.cornerRadius(), height / 2F);

        renderer.custom(new SoftShadowCommand(x, y, width, height, radius, 10F, 3F, SHADOW));
        renderer.custom(new GlassPanelCommand(x, y, width, height, radius, GlassTheme.glass()));
        graphics.text(this.font, hint, Math.round(x + 12F), Math.round(y + (height - 8F) / 2F), GlassTheme.TEXT.toARGB(), false);
    }

    @Override
    public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
        HudLayout.Element hit = topElementAt(event.x(), event.y());
        if (hit == null) {
            if (event.button() == 0) {
                // Empty space: start a rubber band. The old selection only clears once the drag ends,
                // so a plain click keeps feeling like "deselect".
                this.boxSelecting = true;
                this.boxAnchorX = event.x();
                this.boxAnchorY = event.y();
                this.boxCursorX = event.x();
                this.boxCursorY = event.y();
                return true;
            }
            return super.mouseClicked(event, doubleClick);
        }
        if (event.button() == 1) {
            // Right-clicking a selected element resets the whole selection, otherwise just that one.
            for (String id : this.selection.contains(hit.id()) ? Set.copyOf(this.selection) : Set.of(hit.id())) {
                HudLayout.reset(id);
            }
            this.dirty = true;
            return true;
        }
        if (event.button() == 0) {
            if (!this.selection.contains(hit.id())) {
                this.selection.clear();
                this.selection.add(hit.id());
            }
            this.beginDrag(event.x(), event.y());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** Record where the cursor sits inside every selected element, so the group moves rigidly. */
    private void beginDrag(final double mouseX, final double mouseY) {
        this.dragOffsets.clear();
        for (HudLayout.Element element : HudLayout.elements()) {
            if (this.selection.contains(element.id())) {
                this.dragOffsets.put(element.id(),
                        new int[]{(int) Math.round(mouseX) - element.x(), (int) Math.round(mouseY) - element.y()});
            }
        }
    }

    @Override
    public boolean mouseDragged(final MouseButtonEvent event, final double dragX, final double dragY) {
        if (this.boxSelecting) {
            this.boxCursorX = event.x();
            this.boxCursorY = event.y();
            return true;
        }
        if (this.dragOffsets.isEmpty()) {
            return super.mouseDragged(event, dragX, dragY);
        }
        boolean single = this.dragOffsets.size() == 1;
        for (HudLayout.Element element : HudLayout.elements()) {
            int[] offset = this.dragOffsets.get(element.id());
            if (offset == null) {
                // The element stopped rendering mid-drag; keep its stored position instead of writing
                // a zero-sized snap over it.
                continue;
            }
            int x = (int) Math.round(event.x()) - offset[0];
            int y = (int) Math.round(event.y()) - offset[1];
            // Snapping a group per element would tear it apart, so only a lone element snaps.
            HudLayout.move(element.id(),
                    single ? snap(x, element.width(), this.width) : clampToScreen(x, element.width(), this.width),
                    single ? snap(y, element.height(), this.height) : clampToScreen(y, element.height(), this.height));
        }
        this.dirty = true;
        return true;
    }

    @Override
    public boolean mouseReleased(final MouseButtonEvent event) {
        if (this.boxSelecting) {
            this.boxSelecting = false;
            this.selection.clear();
            if (Math.abs(event.x() - this.boxAnchorX) >= BOX_THRESHOLD
                    || Math.abs(event.y() - this.boxAnchorY) >= BOX_THRESHOLD) {
                double left = Math.min(this.boxAnchorX, event.x());
                double top = Math.min(this.boxAnchorY, event.y());
                double right = Math.max(this.boxAnchorX, event.x());
                double bottom = Math.max(this.boxAnchorY, event.y());
                for (HudLayout.Element element : HudLayout.elements()) {
                    if (element.intersects(left, top, right, bottom)) {
                        this.selection.add(element.id());
                    }
                }
            }
            return true;
        }
        if (!this.dragOffsets.isEmpty()) {
            this.dragOffsets.clear();
            return true;
        }
        return super.mouseReleased(event);
    }

    /** Keep an element fully on screen without pulling it to an edge or the center. */
    static int clampToScreen(final int value, final int size, final int screen) {
        return size >= screen ? 0 : Math.max(0, Math.min(value, screen - size));
    }

    @Override
    public void onClose() {
        if (this.dirty) {
            HudLayout.save();
        }
        GlassBackdrop.deactivate();
        // Return to the client menu rather than the game, so the editor feels like part of the menu.
        this.minecraft.gui.setScreen(new AnarchyClientScreen(this.modules, this.config));
    }

    @Override
    public void removed() {
        // Screens swapped via setScreen() skip onClose(); the glass capture must still stop.
        GlassBackdrop.deactivate();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private HudLayout.Element topElementAt(final double px, final double py) {
        HudLayout.Element found = null;
        for (HudLayout.Element element : HudLayout.elements()) {
            if (element.contains(px, py)) {
                found = element;
            }
        }
        return found;
    }

    /** Snap an axis to the near screen edge (inset {@value #MARGIN}) or center within {@value #SNAP} px. */
    static int snap(final int value, final int size, final int screen) {
        if (size >= screen) {
            // Does not fit on this axis: 0 is the only position that is not off-screen.
            return 0;
        }
        int clamped = Math.max(0, Math.min(value, screen - size));
        if (Math.abs(clamped - MARGIN) <= SNAP) {
            return MARGIN;
        }
        if (Math.abs(clamped + size - (screen - MARGIN)) <= SNAP) {
            return Math.max(0, screen - MARGIN - size);
        }
        int centered = (screen - size) / 2;
        if (Math.abs(clamped - centered) <= SNAP) {
            return Math.max(0, centered);
        }
        return clamped;
    }
}
