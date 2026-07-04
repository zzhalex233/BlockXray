package com.zzhalex233.blockxray.client.gui;

import com.zzhalex233.blockxray.client.render.ProspectorGuiRenderUtil;
import com.zzhalex233.blockxray.common.item.ItemProspector;
import com.zzhalex233.blockxray.common.util.BlockTargets;
import com.zzhalex233.blockxray.common.util.OreDictionaryBlocks;
import com.zzhalex233.blockxray.network.BlockXrayNetwork;
import com.zzhalex233.blockxray.network.message.MessageProspectorSettings;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@SideOnly(Side.CLIENT)
public class GuiProspector extends GuiScreen {
    private static final int ROW_HEIGHT = 22;
    private static final int BUTTON_DONE = 0;
    private static final int BUTTON_CLEAR = 1;
    private static final int BUTTON_RANGE_DOWN = 2;
    private static final int BUTTON_RANGE_UP = 3;
    private static final int REMOVE_BUTTON_SIZE = 9;

    private final ItemStack stack;
    private final EnumHand hand;
    private final ItemProspector prospector;
    private final List<String> targets;
    private final Map<String, ItemStack> icons;
    private final Set<String> selectedTargets = new LinkedHashSet<>();

    private GuiTextField searchField;
    private int range;
    private int scrollOffset;
    private int selectedScrollOffset;
    private int leftW;
    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int selectedListX;
    private int selectedListY;
    private int selectedListW;
    private int selectedListH;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private boolean draggingScrollbar;
    private boolean draggingSelectedScrollbar;

    public GuiProspector(ItemStack stack, EnumHand hand) {
        this.stack = stack;
        this.hand = hand;
        this.prospector = (ItemProspector) stack.getItem();
        this.targets = prospector.isBlockProspector() ? BlockTargets.names() : OreDictionaryBlocks.oreNames();
        this.icons = prospector.isBlockProspector() ? BlockTargets.icons() : OreDictionaryBlocks.oreIcons();
        this.selectedTargets.addAll(prospector.getSelectedTargets(stack));
        this.range = ItemProspector.getRange(stack);
    }

    @Override
    public void initGui() {
        leftW = Math.min(width / 2, 250);
        panelX = leftW + 10;
        panelY = 10;
        panelW = Math.max(120, width - panelX - 20);
        panelH = height - 40;

        searchField = new GuiTextField(0, fontRenderer, 10, 10, leftW - 20, 14);
        searchField.setMaxStringLength(64);
        searchField.setFocused(true);

        listX = 10;
        listY = 30;
        listW = leftW - 20;
        listH = height - 70;

        buttonList.clear();
        buttonList.add(new GuiButton(BUTTON_CLEAR, 10, height - 30, leftW / 2 - 12, 20, I18n.format("gui.blockxray.clear")));
        buttonList.add(new GuiButton(BUTTON_DONE, 10 + leftW / 2 - 2, height - 30, leftW / 2 - 8, 20, I18n.format("gui.blockxray.done")));
        buttonList.add(new GuiButton(BUTTON_RANGE_DOWN, panelX + 6, panelY + 52, 20, 20, "-"));
        buttonList.add(new GuiButton(BUTTON_RANGE_UP, panelX + panelW - 26, panelY + 52, 20, 20, "+"));
        updateSelectedListBounds();
    }

    @Override
    public void updateScreen() {
        searchField.updateCursorCounter();
        clampScroll(filteredOres());
        clampSelectedScroll();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == BUTTON_DONE) {
            syncSettings();
            mc.displayGuiScreen(null);
        } else if (button.id == BUTTON_CLEAR) {
            selectedTargets.clear();
            syncSettings();
        } else if (button.id == BUTTON_RANGE_DOWN) {
            range = ItemProspector.clampRange(range - 1);
            syncSettings();
        } else if (button.id == BUTTON_RANGE_UP) {
            range = ItemProspector.clampRange(range + 1);
            syncSettings();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (searchField.textboxKeyTyped(typedChar, keyCode)) {
            scrollOffset = 0;
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 1 && isMouseOver(searchField.x, searchField.y, searchField.width, searchField.height, mouseX, mouseY)) {
            searchField.setText("");
            scrollOffset = 0;
        }
        searchField.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseButton == 0 && isMouseOver(listX, listY, listW, listH, mouseX, mouseY)) {
            List<String> filtered = filteredOres();
            if (overScrollbar(mouseX, listX, listW, listH, filtered.size())) {
                draggingScrollbar = true;
                scrollOffset = updateScrollFromMouse(mouseY, listY, listH, filtered.size());
                return;
            }

            int index = scrollOffset + (mouseY - listY) / ROW_HEIGHT;
            if (index >= 0 && index < filtered.size()) {
                toggleOre(filtered.get(index));
                return;
            }
        }

        if (mouseButton == 0 && isMouseOver(selectedListX, selectedListY, selectedListW, selectedListH, mouseX, mouseY)) {
            String selected = selectedTargetAt(mouseY);
            int rowY = selectedRowY(mouseY);
            if (selected != null && isMouseOver(removeButtonX(), removeButtonY(rowY), REMOVE_BUTTON_SIZE, REMOVE_BUTTON_SIZE, mouseX, mouseY)) {
                selectedTargets.remove(selected);
                clampSelectedScroll();
                syncSettings();
                return;
            }
            if (overScrollbar(mouseX, selectedListX, selectedListW, selectedListH, selectedTargets.size())) {
                draggingSelectedScrollbar = true;
                selectedScrollOffset = updateScrollFromMouse(mouseY, selectedListY, selectedListH, selectedTargets.size());
                return;
            }
        }

        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (draggingScrollbar) {
            scrollOffset = updateScrollFromMouse(mouseY, listY, listH, filteredOres().size());
        }
        if (draggingSelectedScrollbar) {
            selectedScrollOffset = updateScrollFromMouse(mouseY, selectedListY, selectedListH, selectedTargets.size());
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        draggingScrollbar = false;
        draggingSelectedScrollbar = false;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getDWheel();
        if (wheel == 0) {
            return;
        }

        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (isMouseOver(listX, listY, listW, listH, mouseX, mouseY)) {
            scrollOffset += wheel < 0 ? 1 : -1;
            clampScroll(filteredOres());
        } else if (isMouseOver(selectedListX, selectedListY, selectedListW, selectedListH, mouseX, mouseY)) {
            selectedScrollOffset += wheel < 0 ? 1 : -1;
            clampSelectedScroll();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        searchField.drawTextBox();
        drawOreList(mouseX, mouseY);
        drawRightPanel(mouseX, mouseY);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public void onGuiClosed() {
        syncSettings();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void drawOreList(int mouseX, int mouseY) {
        List<String> filtered = filteredOres();
        clampScroll(filtered);

        drawRect(listX, listY, listX + listW, listY + listH, 0x80000000);
        if (filtered.isEmpty()) {
            fontRenderer.drawString(I18n.format("gui.blockxray.no_ores"), listX + 6, listY + 6, 0xAAAAAA);
            return;
        }

        int visibleRows = listH / ROW_HEIGHT;
        int rows = Math.max(0, Math.min(filtered.size() - scrollOffset, visibleRows));
        for (int i = 0; i < rows; i++) {
            int index = scrollOffset + i;
            int y = listY + i * ROW_HEIGHT;
            String ore = filtered.get(index);
            boolean selected = selectedTargets.contains(ore);
            boolean hovered = isMouseOver(listX, y, listW - 6, ROW_HEIGHT, mouseX, mouseY);

            if (selected) {
                drawRect(listX, y, listX + listW - 6, y + ROW_HEIGHT, 0x40FFFFFF);
                drawRect(listX, y, listX + 2, y + ROW_HEIGHT, 0xFFFFD36A);
            } else if (hovered) {
                drawRect(listX, y, listX + listW - 6, y + ROW_HEIGHT, 0x20FFFFFF);
            }

            drawTargetIcon(ore, listX + 4, y + 3);

            int textW = listW - 52;
            String displayName = localizedName(ore);
            fontRenderer.drawString(trimWithDots(displayName, textW), listX + 24, y + 3, selected ? 0xFFFFA0 : 0xFFFFFF);
            fontRenderer.drawString(trimWithDots(targetLabel(ore), textW), listX + 24, y + 13, 0x888888);
            drawSelectionMark(listX + listW - 18, y + 7, selected);
        }
        drawScrollbar(listX, listY, listW, listH, filtered.size(), scrollOffset);
    }

    private void drawRightPanel(int mouseX, int mouseY) {
        drawRect(panelX, panelY, panelX + panelW, panelY + panelH, 0x80000000);
        fontRenderer.drawString(I18n.format("gui.blockxray.title"), panelX + 6, panelY + 6, 0xFFFFFF);

        String count = I18n.format("gui.blockxray.selected", selectedTargets.size());
        fontRenderer.drawString(count, panelX + panelW - 6 - fontRenderer.getStringWidth(count), panelY + 6, 0xFFFFAA);

        String rangeText = I18n.format("gui.blockxray.range", range, ItemProspector.getMaxRange());
        fontRenderer.drawString(rangeText, panelX + 6, panelY + 28, 0xCCCCCC);
        drawRangeMeter();

        updateSelectedListBounds();
        clampSelectedScroll();
        drawRect(selectedListX, selectedListY, selectedListX + selectedListW, selectedListY + selectedListH, 0x40000000);

        int rows = Math.max(0, selectedListH / ROW_HEIGHT);
        int i = 0;
        int skipped = 0;
        for (String ore : selectedTargets) {
            if (skipped++ < selectedScrollOffset) {
                continue;
            }
            if (i >= rows) {
                break;
            }
            drawSelectedOre(ore, selectedListY + i * ROW_HEIGHT, mouseX, mouseY);
            i++;
        }
        drawScrollbar(selectedListX, selectedListY, selectedListW, selectedListH, selectedTargets.size(), selectedScrollOffset);
    }

    private void drawRangeMeter() {
        int barX = panelX + 32;
        int barY = panelY + 58;
        int barW = Math.max(12, panelW - 64);
        int maxRange = Math.max(ItemProspector.MIN_RANGE, ItemProspector.getMaxRange());
        int fill = Math.max(0, Math.min(barW, (range - ItemProspector.MIN_RANGE) * barW / Math.max(1, maxRange - ItemProspector.MIN_RANGE)));

        drawRect(barX, barY, barX + barW, barY + 8, 0x40000000);
        drawRect(barX, barY, barX + fill, barY + 8, 0x80FFD36A);
        drawRect(barX, barY + 8, barX + barW, barY + 9, 0x60333333);
    }

    private void drawSelectedOre(String ore, int y, int mouseX, int mouseY) {
        drawRect(panelX + 6, y, panelX + panelW - 6, y + ROW_HEIGHT, 0x20FFFFFF);
        drawTargetIcon(ore, panelX + 10, y + 3);
        int textW = panelW - 56;
        fontRenderer.drawString(trimWithDots(localizedName(ore), textW), panelX + 30, y + 3, 0xFFFFA0);
        fontRenderer.drawString(trimWithDots(targetLabel(ore), textW), panelX + 30, y + 13, 0x888888);
        drawRemoveButton(removeButtonX(), removeButtonY(y), isMouseOver(removeButtonX(), removeButtonY(y), REMOVE_BUTTON_SIZE, REMOVE_BUTTON_SIZE, mouseX, mouseY));
    }

    private void drawRemoveButton(int x, int y, boolean hovered) {
        drawRect(x - 1, y - 1, x + REMOVE_BUTTON_SIZE + 1, y + REMOVE_BUTTON_SIZE + 1, hovered ? 0xFFFF6058 : 0xFFC83232);
        drawRect(x, y, x + REMOVE_BUTTON_SIZE, y + REMOVE_BUTTON_SIZE, hovered ? 0xFF8F1F1F : 0xFF5C1818);
        drawRect(x + 2, y + 2, x + 3, y + 3, 0xFFFFDDDD);
        drawRect(x + 3, y + 3, x + 4, y + 4, 0xFFFFDDDD);
        drawRect(x + 4, y + 4, x + 5, y + 5, 0xFFFFDDDD);
        drawRect(x + 5, y + 5, x + 6, y + 6, 0xFFFFDDDD);
        drawRect(x + 6, y + 6, x + 7, y + 7, 0xFFFFDDDD);
        drawRect(x + 6, y + 2, x + 7, y + 3, 0xFFFFDDDD);
        drawRect(x + 5, y + 3, x + 6, y + 4, 0xFFFFDDDD);
        drawRect(x + 3, y + 5, x + 4, y + 6, 0xFFFFDDDD);
        drawRect(x + 2, y + 6, x + 3, y + 7, 0xFFFFDDDD);
    }

    private void drawTargetIcon(String target, int x, int y) {
        ItemStack icon = icons.get(target);
        ProspectorGuiRenderUtil.drawBlockTargetIcon(mc, itemRender, icon, targetState(target), x, y, 16);
    }

    private IBlockState targetState(String target) {
        return prospector.isBlockProspector() ? BlockTargets.state(target) : OreDictionaryBlocks.state(target);
    }

    private void drawSelectionMark(int x, int y, boolean selected) {
        drawRect(x - 1, y - 1, x + 8, y + 8, selected ? 0xFFFFD36A : 0xFF404040);
        drawRect(x, y, x + 7, y + 7, selected ? 0xFF55BB66 : 0xFF202020);
        if (selected) {
            drawRect(x + 2, y + 4, x + 4, y + 6, 0xFFFFFFFF);
            drawRect(x + 4, y + 2, x + 6, y + 6, 0xFFFFFFFF);
        }
    }

    private void updateSelectedListBounds() {
        selectedListX = panelX + 6;
        selectedListY = panelY + 86;
        selectedListW = panelW - 12;
        selectedListH = Math.max(0, panelY + panelH - selectedListY - 8);
    }

    private void drawScrollbar(int x, int y, int w, int h, int total, int offset) {
        int visibleRows = Math.max(1, h / ROW_HEIGHT);
        if (total <= visibleRows) {
            return;
        }

        int barX1 = x + w - 4;
        int barX2 = x + w - 2;
        drawRect(barX1, y, barX2, y + h, 0x40000000);

        int barH = Math.max(8, h * visibleRows / total);
        int maxOffset = total - visibleRows;
        int barY = y + (h - barH) * offset / maxOffset;
        drawRect(barX1, barY, barX2, barY + barH, 0x80FFFFFF);
    }

    private boolean overScrollbar(int mouseX, int x, int w, int h, int total) {
        return total > Math.max(1, h / ROW_HEIGHT)
                && mouseX >= x + w - 5
                && mouseX <= x + w;
    }

    private int updateScrollFromMouse(int mouseY, int y, int h, int total) {
        int visibleRows = Math.max(1, h / ROW_HEIGHT);
        int maxOffset = Math.max(0, total - visibleRows);
        if (maxOffset <= 0) {
            return 0;
        }

        int barH = Math.max(8, h * visibleRows / total);
        int scrollable = Math.max(1, h - barH);
        int clamped = Math.max(y, Math.min(mouseY - barH / 2, y + h - barH));
        return Math.max(0, Math.min(maxOffset, Math.round((clamped - y) * maxOffset / (float) scrollable)));
    }

    private List<String> filteredOres() {
        String filter = searchField == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        List<String> filtered = new ArrayList<>();
        for (String ore : targets) {
            String displayName = localizedName(ore);
            String label = targetLabel(ore);
            if (filter.isEmpty()
                    || label.toLowerCase(Locale.ROOT).contains(filter)
                    || displayName.toLowerCase(Locale.ROOT).contains(filter)) {
                filtered.add(ore);
            }
        }
        return filtered;
    }

    private String localizedName(String ore) {
        if (prospector.isBlockProspector()) {
            return BlockTargets.displayName(ore);
        }
        ItemStack icon = icons.get(ore);
        String name = icon == null || icon.isEmpty() ? ore : icon.getDisplayName();
        return name == null || name.trim().isEmpty() ? ore : name;
    }

    private String targetLabel(String target) {
        return prospector.isBlockProspector() ? target : OreDictionaryBlocks.oreName(target);
    }

    private String trimWithDots(String text, int width) {
        if (width <= 0) {
            return "";
        }
        int dotsW = fontRenderer.getStringWidth("...");
        if (width <= dotsW) {
            return fontRenderer.trimStringToWidth(text, width);
        }

        String trimmed = fontRenderer.trimStringToWidth(text, width - dotsW);
        return trimmed.equals(text) ? trimmed : trimmed + "...";
    }

    private void clampScroll(List<String> filtered) {
        int visibleRows = Math.max(1, listH / ROW_HEIGHT);
        int max = Math.max(0, filtered.size() - visibleRows);
        scrollOffset = Math.max(0, Math.min(max, scrollOffset));
    }

    private void clampSelectedScroll() {
        int visibleRows = Math.max(1, selectedListH / ROW_HEIGHT);
        int max = Math.max(0, selectedTargets.size() - visibleRows);
        selectedScrollOffset = Math.max(0, Math.min(max, selectedScrollOffset));
    }

    private String selectedTargetAt(int mouseY) {
        int row = (mouseY - selectedListY) / ROW_HEIGHT;
        if (row < 0 || row >= selectedListH / ROW_HEIGHT) {
            return null;
        }
        int index = selectedScrollOffset + row;
        if (index >= selectedTargets.size()) {
            return null;
        }
        int i = 0;
        for (String target : selectedTargets) {
            if (i++ == index) {
                return target;
            }
        }
        return null;
    }

    private int selectedRowY(int mouseY) {
        return selectedListY + (mouseY - selectedListY) / ROW_HEIGHT * ROW_HEIGHT;
    }

    private int removeButtonX() {
        return selectedListX + selectedListW - 18;
    }

    private int removeButtonY(int rowY) {
        return rowY + 6;
    }

    private void toggleOre(String ore) {
        if (!selectedTargets.remove(ore)) {
            selectedTargets.add(ore);
        }
        syncSettings();
    }

    private void syncSettings() {
        prospector.setSettings(stack, selectedTargets, range);
        BlockXrayNetwork.getChannel().sendToServer(new MessageProspectorSettings(hand, range, selectedTargets));
    }

    private static boolean isMouseOver(int x, int y, int w, int h, int mouseX, int mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
