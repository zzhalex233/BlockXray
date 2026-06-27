package com.zzhalex233.blockxray.client.render;

import com.zzhalex233.blockxray.common.item.ItemProspector;
import com.zzhalex233.blockxray.common.util.OreDictionaryBlocks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.client.model.pipeline.LightUtil;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SideOnly(Side.CLIENT)
public enum ProspectorXrayRenderer {
    INSTANCE;

    private static final int SCAN_BLOCK_BUDGET = 65536;
    private static final int SCAN_CLUSTER_BUDGET = 4;
    private static final int SCAN_CENTER_STEP_BLOCKS = 4;
    private static final int GROUP_SIZE_BITS = 1;
    private static final int REBUILD_GROUPS_PER_FRAME = 32;
    private static final float MODEL_OFFSET = 0.0005F;
    private static final float MODEL_SCALE = 0.999F;
    private static final long SCAN_TIME_BUDGET_NANOS = 1_000_000L;
    private static final long EMPTY_RESCAN_INTERVAL_MILLIS = 1200L;
    private static final long REFRESH_INTERVAL_MILLIS = 5000L;

    private final ProspectorOverlaySet<TrackedOre> oreBlocks = new ProspectorOverlaySet<>();
    private final Map<Long, RenderGroup> renderGroups = new HashMap<>();
    private final List<RenderGroup> visibleGroups = new ArrayList<>();
    private final Set<String> lastSelectedOres = new LinkedHashSet<>();
    private final ClientWorldListener worldListener = new ClientWorldListener();
    private OreDictionaryBlocks.Matcher oreMatcher = OreDictionaryBlocks.matcher(lastSelectedOres);
    private ScanJob scanJob;
    private long lastScanFinishedTime;
    private BlockPos lastPlayerPos = BlockPos.ORIGIN;
    private BlockPos currentPlayerPos = BlockPos.ORIGIN;
    private int lastPrunedChunkX = Integer.MIN_VALUE;
    private int lastPrunedChunkZ = Integer.MIN_VALUE;
    private int lastPrunedRange = Integer.MIN_VALUE;
    private int lastRange = -1;
    private World lastWorld;
    private boolean hasSettings;

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.world == null || minecraft.player == null || minecraft.getRenderViewEntity() == null) {
            clearCache();
            return;
        }

        if (minecraft.world != lastWorld) {
            attachWorld(minecraft.world);
        }

        ItemStack stack = findHeldProspector(minecraft.player);
        if (stack.isEmpty()) {
            clearActiveState();
            return;
        }

        long now = System.currentTimeMillis();
        Set<String> selectedOres = ItemProspector.getSelectedOres(stack);
        if (selectedOres.isEmpty()) {
            clearActiveState();
            return;
        }

        int range = ItemProspector.getRange(stack);
        BlockPos playerPos = getPlayerOnPos(minecraft.player);
        currentPlayerPos = playerPos;
        applySettings(minecraft.world, playerPos, range, selectedOres);
        pruneOutsideCurrentChunkRange(playerPos, range);
        if (shouldStartScan(playerPos, now)) {
            startScan(minecraft.world, playerPos, range);
        }

        if (scanJob != null && scanJob.scan(oreMatcher, SCAN_CLUSTER_BUDGET)) {
            lastScanFinishedTime = System.currentTimeMillis();
            scanJob = null;
        }

        if (!oreBlocks.isEmpty()) {
            drawXRayOreBlocks(event);
        }
    }

    private void attachWorld(World world) {
        detachWorld();
        lastWorld = world;
        lastWorld.addEventListener(worldListener);
        clearRenderedBlocks();
        hasSettings = false;
        scanJob = null;
        lastRange = -1;
        lastScanFinishedTime = 0L;
    }

    private void detachWorld() {
        if (lastWorld != null) {
            lastWorld.removeEventListener(worldListener);
        }
        lastWorld = null;
    }

    private void applySettings(World world, BlockPos playerPos, int range, Set<String> selectedOres) {
        boolean selectedChanged = !selectedOres.equals(lastSelectedOres);
        boolean rangeChanged = range != lastRange;
        if (hasSettings && !selectedChanged && !rangeChanged) {
            return;
        }

        lastSelectedOres.clear();
        lastSelectedOres.addAll(selectedOres);
        oreMatcher = OreDictionaryBlocks.matcher(selectedOres);
        lastRange = range;
        hasSettings = true;

        if (oreMatcher.isEmpty()) {
            clearRenderedBlocks();
            scanJob = null;
            return;
        }

        boolean removed = false;
        if (selectedChanged) {
            removed |= oreBlocks.retainSelected(lastSelectedOres, this::removeEntry);
        }
        if (rangeChanged) {
            removed |= oreBlocks.pruneOutsideBlocks(playerPos.getX(), playerPos.getZ(), rangeBlocks(range), this::removeEntry);
        }
        if (removed) {
            removeEmptyGroups();
        }
        startScan(world, playerPos, range);
    }

    private void pruneOutsideCurrentChunkRange(BlockPos playerPos, int range) {
        int centerX = Math.floorDiv(playerPos.getX(), SCAN_CENTER_STEP_BLOCKS);
        int centerZ = Math.floorDiv(playerPos.getZ(), SCAN_CENTER_STEP_BLOCKS);
        if (centerX == lastPrunedChunkX && centerZ == lastPrunedChunkZ && range == lastPrunedRange) {
            return;
        }
        lastPrunedChunkX = centerX;
        lastPrunedChunkZ = centerZ;
        lastPrunedRange = range;
        if (oreBlocks.isEmpty()) {
            return;
        }
        boolean removed = oreBlocks.pruneOutsideBlocks(playerPos.getX(), playerPos.getZ(), rangeBlocks(range), this::removeEntry);
        if (removed) {
            removeEmptyGroups();
        }
    }

    private boolean shouldStartScan(BlockPos playerPos, long now) {
        if (Math.abs(playerPos.getX() - lastPlayerPos.getX()) >= SCAN_CENTER_STEP_BLOCKS
                || Math.abs(playerPos.getZ() - lastPlayerPos.getZ()) >= SCAN_CENTER_STEP_BLOCKS) {
            return true;
        }
        if (scanJob != null) {
            return false;
        }
        long interval = oreBlocks.isEmpty() ? EMPTY_RESCAN_INTERVAL_MILLIS : REFRESH_INTERVAL_MILLIS;
        return now - lastScanFinishedTime >= interval;
    }

    private void startScan(World world, BlockPos center, int range) {
        lastPlayerPos = center;
        if (oreMatcher.isEmpty()) {
            scanJob = null;
            clearRenderedBlocks();
            return;
        }
        scanJob = new ScanJob(world, center, range);
    }

    private boolean addOrUpdateOre(int x, int y, int z, IBlockState state) {
        Set<String> oreNames = oreMatcher.matchingOreNames(state);
        return addOrUpdateOre(x, y, z, state, oreNames);
    }

    private boolean addOrUpdateOre(int x, int y, int z, IBlockState state, Set<String> oreNames) {
        if (oreNames.isEmpty()) {
            return removeOre(x, y, z);
        }

        TrackedOre existing = oreBlocks.get(x, y, z);
        if (existing != null) {
            if (!existing.state.equals(state) || !existing.oreNames.equals(oreNames)) {
                existing.update(state, oreNames);
                markEntryAndNeighborGroupsDirty(existing);
                return true;
            }
            return false;
        }

        return addNewOre(new TrackedOre(x, y, z, state, oreNames));
    }

    private boolean addNewOre(TrackedOre ore) {
        oreBlocks.put(ore);
        groupFor(ore.x, ore.y, ore.z).add(ore);
        markNeighborGroupsDirty(ore.x, ore.y, ore.z);
        return true;
    }

    private boolean removeOre(int x, int y, int z) {
        TrackedOre removed = oreBlocks.remove(x, y, z);
        if (removed == null) {
            return false;
        }
        removeEntry(removed);
        return true;
    }

    private void handleBlockUpdate(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        if (world != lastWorld || !hasSettings || oreMatcher.isEmpty()) {
            return;
        }

        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        if (isOutsideLastRange(x, y, z)) {
            removeOre(x, y, z);
        } else if (oreMatcher.matches(newState)) {
            addOrUpdateOre(x, y, z, newState);
        } else if (oreBlocks.get(x, y, z) != null || oreMatcher.matches(oldState)) {
            removeOre(x, y, z);
        }
        markNeighborGroupsDirty(x, y, z);
    }

    private void handleRenderRangeUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {
        if (!hasSettings || oreMatcher.isEmpty()) {
            return;
        }

        int minX = Math.min(x1, x2);
        int minY = Math.min(y1, y2);
        int minZ = Math.min(z1, z2);
        int maxX = Math.max(x1, x2);
        int maxY = Math.max(y1, y2);
        int maxZ = Math.max(z1, z2);
        for (TrackedOre ore : oreBlocks.values()) {
            if (ore.x >= minX - 1 && ore.x <= maxX + 1
                    && ore.y >= minY - 1 && ore.y <= maxY + 1
                    && ore.z >= minZ - 1 && ore.z <= maxZ + 1) {
                markEntryDirty(ore);
            }
        }
    }

    private boolean isOutsideLastRange(int x, int y, int z) {
        return lastRange > 0
                && (Math.abs(x - currentPlayerPos.getX()) > rangeBlocks(lastRange)
                || Math.abs(z - currentPlayerPos.getZ()) > rangeBlocks(lastRange));
    }

    private void drawXRayOreBlocks(RenderWorldLastEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        Entity viewer = minecraft.getRenderViewEntity();
        World world = minecraft.world;
        if (viewer == null || world == null) {
            return;
        }

        rebuildDirtyGroups(minecraft, world);
        if (renderGroups.isEmpty()) {
            return;
        }

        double viewerX = viewer.lastTickPosX + (viewer.posX - viewer.lastTickPosX) * event.getPartialTicks();
        double viewerY = viewer.lastTickPosY + (viewer.posY - viewer.lastTickPosY) * event.getPartialTicks();
        double viewerZ = viewer.lastTickPosZ + (viewer.posZ - viewer.lastTickPosZ) * event.getPartialTicks();
        ICamera camera = new Frustum();
        camera.setPosition(viewerX, viewerY, viewerZ);
        Vec3d view = viewer.getLook(event.getPartialTicks());

        minecraft.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        GlStateManager.pushMatrix();
        GlStateManager.translate(-viewerX, -viewerY, -viewerZ);
        prepareXRayState();
        try {
            renderGroupLists(viewerX, viewerY, viewerZ, view, camera);
        } finally {
            restoreXRayState();
            GlStateManager.popMatrix();
        }
    }

    private void rebuildDirtyGroups(Minecraft minecraft, World world) {
        minecraft.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        Iterator<RenderGroup> iterator = renderGroups.values().iterator();
        int rebuilt = 0;
        while (iterator.hasNext()) {
            RenderGroup group = iterator.next();
            if (group.isEmpty()) {
                group.delete();
                iterator.remove();
                continue;
            }
            if (group.dirty && rebuilt++ < REBUILD_GROUPS_PER_FRAME) {
                group.rebuild(minecraft, world);
            }
        }
    }

    private void renderGroupLists(double viewerX, double viewerY, double viewerZ, Vec3d view, ICamera camera) {
        GlStateManager.depthMask(false);
        visibleGroups.clear();
        for (RenderGroup group : renderGroups.values()) {
            if (!group.dirty && group.displayList >= 0 && group.renderedCount > 0 && camera.isBoundingBoxInFrustum(group.bounds)) {
                group.updateDepth(viewerX, viewerY, viewerZ, view);
                visibleGroups.add(group);
            }
        }
        visibleGroups.sort(Comparator.comparingDouble((RenderGroup group) -> group.depth).reversed());
        for (RenderGroup group : visibleGroups) {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.callList(group.displayList);
        }
    }

    private int compileOreBlockGeometry(Minecraft minecraft, World world, Collection<TrackedOre> ores) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.ITEM);

        int rendered = 0;
        for (TrackedOre ore : ores) {
            rendered += appendOreBlockGeometry(minecraft, world, buffer, ore);
        }

        buffer.setTranslation(0.0D, 0.0D, 0.0D);
        tessellator.draw();
        return rendered;
    }

    private int appendOreBlockGeometry(Minecraft minecraft, World world, BufferBuilder buffer, TrackedOre ore) {
        IBlockState current = world.getBlockState(ore.pos);
        Set<String> oreNames = oreMatcher.matchingOreNames(current);
        if (oreNames.isEmpty()) {
            ore.pendingRemoval = true;
            return 0;
        }
        if (!current.equals(ore.state) || !oreNames.equals(ore.oreNames)) {
            ore.update(current, oreNames);
            markNeighborGroupsDirty(ore.x, ore.y, ore.z);
        }

        IBakedModel model = minecraft.getBlockRendererDispatcher().getModelForState(current);
        return renderXRayModelQuads(buffer, world, model, current, ore.pos, 255,
                ore.x + MODEL_OFFSET, ore.y + MODEL_OFFSET, ore.z + MODEL_OFFSET);
    }

    private int renderXRayModelQuads(BufferBuilder buffer, World world, IBakedModel model, IBlockState state,
                                     BlockPos pos, int alpha, float offsetX, float offsetY, float offsetZ) {
        int rendered = 0;
        for (EnumFacing face : EnumFacing.values()) {
            IBlockState neighbor = world.getBlockState(pos.offset(face));
            if (oreMatcher.matches(neighbor)) {
                continue;
            }
            int color = faceShadeColor(face, alpha);
            for (BakedQuad quad : model.getQuads(state, face, 0L)) {
                renderTranslatedQuad(buffer, quad, color, offsetX, offsetY, offsetZ);
                rendered++;
            }
        }
        for (BakedQuad quad : model.getQuads(state, null, 0L)) {
            renderTranslatedQuad(buffer, quad, alpha << 24 | 0xFFFFFF, offsetX, offsetY, offsetZ);
            rendered++;
        }
        return rendered;
    }

    private void renderTranslatedQuad(BufferBuilder buffer, BakedQuad quad, int color,
                                      float offsetX, float offsetY, float offsetZ) {
        if (!quad.getFormat().equals(DefaultVertexFormats.ITEM)) {
            LightUtil.renderQuadColorSlow(buffer, translatedBakedQuad(quad, offsetX, offsetY, offsetZ), color);
            return;
        }

        buffer.addVertexData(translatedQuad(quad, offsetX, offsetY, offsetZ));
        if (buffer.getVertexFormat().hasColor()) {
            net.minecraftforge.client.ForgeHooksClient.putQuadColor(buffer, quad, color);
        }
    }

    private int[] translatedQuad(BakedQuad quad, float offsetX, float offsetY, float offsetZ) {
        int[] translated = quad.getVertexData().clone();
        VertexFormat format = quad.getFormat();
        int stride = format.getIntegerSize();
        int positionOffset = format.getOffset(0) / 4;
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * stride + positionOffset;
            translated[base] = Float.floatToRawIntBits(offsetX + Float.intBitsToFloat(translated[base]) * MODEL_SCALE);
            translated[base + 1] = Float.floatToRawIntBits(offsetY + Float.intBitsToFloat(translated[base + 1]) * MODEL_SCALE);
            translated[base + 2] = Float.floatToRawIntBits(offsetZ + Float.intBitsToFloat(translated[base + 2]) * MODEL_SCALE);
        }
        return translated;
    }

    private BakedQuad translatedBakedQuad(BakedQuad quad, float offsetX, float offsetY, float offsetZ) {
        return new BakedQuad(translatedQuad(quad, offsetX, offsetY, offsetZ), quad.getTintIndex(),
                quad.getFace(), quad.getSprite(), quad.shouldApplyDiffuseLighting(), quad.getFormat());
    }

    private static int faceShadeColor(EnumFacing face, int alpha) {
        int shade;
        switch (face) {
            case DOWN:
                shade = 128;
                break;
            case UP:
                shade = 255;
                break;
            case NORTH:
            case SOUTH:
                shade = 204;
                break;
            case WEST:
            case EAST:
            default:
                shade = 153;
                break;
        }
        return alpha << 24 | shade << 16 | shade << 8 | shade;
    }

    private static void prepareXRayState() {
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.enableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableCull();
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_GREATER);
        GlStateManager.disableBlend();
        GlStateManager.depthMask(false);
        GlStateManager.doPolygonOffset(1.0F, 1.0F);
        GlStateManager.enablePolygonOffset();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void restoreXRayState() {
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.disablePolygonOffset();
        GlStateManager.doPolygonOffset(0.0F, 0.0F);
        GlStateManager.depthMask(true);
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glLineWidth(1.0F);
    }

    private RenderGroup groupFor(int x, int y, int z) {
        long key = groupKey(x, y, z);
        RenderGroup group = renderGroups.get(key);
        if (group == null) {
            group = new RenderGroup(key);
            renderGroups.put(key, group);
        }
        return group;
    }

    private void markEntryAndNeighborGroupsDirty(TrackedOre ore) {
        markEntryDirty(ore);
        markNeighborGroupsDirty(ore.x, ore.y, ore.z);
    }

    private void markEntryDirty(TrackedOre ore) {
        RenderGroup group = renderGroups.get(groupKey(ore.x, ore.y, ore.z));
        if (group != null) {
            group.dirty = true;
        }
    }

    private void markNeighborGroupsDirty(int x, int y, int z) {
        markPositionDirty(x, y, z);
        for (EnumFacing face : EnumFacing.values()) {
            markPositionDirty(x + face.getXOffset(), y + face.getYOffset(), z + face.getZOffset());
        }
    }

    private void markPositionDirty(int x, int y, int z) {
        RenderGroup group = renderGroups.get(groupKey(x, y, z));
        if (group != null) {
            group.dirty = true;
        }
        TrackedOre ore = oreBlocks.get(x, y, z);
        if (ore != null) {
            markEntryDirty(ore);
        }
    }

    private void removeEntry(TrackedOre entry) {
        removeFromRenderGroup(entry);
        markNeighborGroupsDirty(entry.x, entry.y, entry.z);
    }

    private void removeFromRenderGroup(TrackedOre entry) {
        RenderGroup group = renderGroups.get(groupKey(entry.x, entry.y, entry.z));
        if (group == null) {
            return;
        }
        group.remove(entry);
        if (group.isEmpty()) {
            group.delete();
            renderGroups.remove(group.key);
        }
    }

    private void removeEmptyGroups() {
        Iterator<RenderGroup> iterator = renderGroups.values().iterator();
        while (iterator.hasNext()) {
            RenderGroup group = iterator.next();
            if (group.isEmpty()) {
                group.delete();
                iterator.remove();
            }
        }
    }

    private void clearActiveState() {
        if (!hasSettings && oreBlocks.isEmpty() && renderGroups.isEmpty() && scanJob == null) {
            return;
        }
        clearRenderedBlocks();
        lastSelectedOres.clear();
        oreMatcher = OreDictionaryBlocks.matcher(lastSelectedOres);
        scanJob = null;
        lastRange = -1;
        currentPlayerPos = BlockPos.ORIGIN;
        lastPrunedChunkX = Integer.MIN_VALUE;
        lastPrunedChunkZ = Integer.MIN_VALUE;
        lastPrunedRange = Integer.MIN_VALUE;
        hasSettings = false;
        lastScanFinishedTime = 0L;
    }

    private void clearRenderedBlocks() {
        oreBlocks.clear();
        for (RenderGroup group : renderGroups.values()) {
            group.delete();
        }
        renderGroups.clear();
        visibleGroups.clear();
        lastPrunedChunkX = Integer.MIN_VALUE;
        lastPrunedChunkZ = Integer.MIN_VALUE;
        lastPrunedRange = Integer.MIN_VALUE;
    }

    private void clearCache() {
        clearActiveState();
        detachWorld();
    }

    private static ItemStack findHeldProspector(EntityPlayer player) {
        ItemStack main = player.getHeldItemMainhand();
        if (main.getItem() instanceof ItemProspector) {
            return main;
        }

        ItemStack off = player.getHeldItemOffhand();
        return off.getItem() instanceof ItemProspector ? off : ItemStack.EMPTY;
    }

    private static BlockPos getPlayerOnPos(EntityPlayer player) {
        return new BlockPos(player.posX, player.posY - 0.2D, player.posZ);
    }

    private static long distanceSq(BlockPos center, int x, int y, int z) {
        return ProspectorOverlaySet.distanceSq(center.getX(), center.getY(), center.getZ(), x, y, z);
    }

    private static long groupKey(int x, int y, int z) {
        return ProspectorOverlaySet.key(x >> GROUP_SIZE_BITS, y >> GROUP_SIZE_BITS, z >> GROUP_SIZE_BITS);
    }

    private static int rangeBlocks(int chunkRange) {
        return chunkRange << 4;
    }

    private final class ScanJob {
        private final BlockPos center;
        private final int blockRadius;
        private final List<SectionScan> sections = new ArrayList<>();
        private int sectionIndex;

        private ScanJob(World world, BlockPos center, int range) {
            this.center = center;
            this.blockRadius = rangeBlocks(range);
            int centerChunkX = center.getX() >> 4;
            int centerChunkZ = center.getZ() >> 4;
            int minChunkX = centerChunkX - range;
            int minChunkZ = centerChunkZ - range;
            int maxChunkX = centerChunkX + range;
            int maxChunkZ = centerChunkZ + range;

            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    Chunk chunk = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
                    if (chunk == null) {
                        continue;
                    }

                    ExtendedBlockStorage[] storages = chunk.getBlockStorageArray();
                    int sectionMax = Math.min((world.getHeight() - 1) >> 4, storages.length - 1);
                    for (int sectionY = 0; sectionY <= sectionMax; sectionY++) {
                        ExtendedBlockStorage storage = storages[sectionY];
                        if (storage == null || storage.isEmpty()) {
                            continue;
                        }
                        addSection(storage, chunkX, chunkZ, sectionY);
                    }
                }
            }
            sections.sort(Comparator.comparingLong(section -> section.distanceSq));
        }

        private void addSection(ExtendedBlockStorage storage, int chunkX, int chunkZ, int sectionY) {
            int baseX = chunkX << 4;
            int baseY = sectionY << 4;
            int baseZ = chunkZ << 4;
            sections.add(new SectionScan(storage, baseX, baseY, baseZ, sectionDistanceSq(baseX, baseY, baseZ)));
        }

        private boolean scan(OreDictionaryBlocks.Matcher matcher, int clusterBudget) {
            if (matcher.isEmpty()) {
                return true;
            }

            int budget = SCAN_BLOCK_BUDGET;
            int remainingClusters = clusterBudget;
            long deadline = System.nanoTime() + SCAN_TIME_BUDGET_NANOS;
            while (sectionIndex < sections.size() && budget > 0 && remainingClusters > 0) {
                SectionScan section = sections.get(sectionIndex);
                ScanProgress progress = section.scan(this, matcher, budget, remainingClusters, deadline);
                budget = progress.remainingBlockBudget;
                remainingClusters = progress.remainingClusterBudget;
                if (section.isDone()) {
                    sectionIndex++;
                }
                if (System.nanoTime() >= deadline) {
                    break;
                }
            }
            return sectionIndex >= sections.size();
        }

        private long sectionDistanceSq(int baseX, int baseY, int baseZ) {
            return axisDistanceSq(center.getX(), baseX, baseX + 15)
                    + axisDistanceSq(center.getY(), baseY, baseY + 15)
                    + axisDistanceSq(center.getZ(), baseZ, baseZ + 15);
        }

        private long axisDistanceSq(int value, int min, int max) {
            if (value < min) {
                long distance = min - value;
                return distance * distance;
            }
            if (value > max) {
                long distance = value - max;
                return distance * distance;
            }
            return 0L;
        }

        private boolean contains(int x, int z) {
            return Math.abs(x - center.getX()) <= blockRadius && Math.abs(z - center.getZ()) <= blockRadius;
        }
    }

    private final class SectionScan {
        private final ExtendedBlockStorage storage;
        private final int baseX;
        private final int baseY;
        private final int baseZ;
        private final long distanceSq;
        private final List<ScanCandidate> candidates = new ArrayList<>();
        private int x;
        private int y;
        private int z;
        private boolean done;

        private SectionScan(ExtendedBlockStorage storage, int baseX, int baseY, int baseZ, long distanceSq) {
            this.storage = storage;
            this.baseX = baseX;
            this.baseY = baseY;
            this.baseZ = baseZ;
            this.distanceSq = distanceSq;
        }

        private ScanProgress scan(ScanJob job, OreDictionaryBlocks.Matcher matcher,
                                  int blockBudget, int clusterBudget, long deadline) {
            int checked = 0;
            while (!done && blockBudget > 0) {
                int worldX = baseX + x;
                int worldY = baseY + y;
                int worldZ = baseZ + z;
                long distanceSq = distanceSq(job.center, worldX, worldY, worldZ);
                if (job.contains(worldX, worldZ) && oreBlocks.get(worldX, worldY, worldZ) == null) {
                    IBlockState state = storage.get(x, y, z);
                    if (matcher.matches(state)) {
                        candidates.add(new ScanCandidate(worldX, worldY, worldZ, x, y, z, distanceSq));
                    }
                }

                blockBudget--;
                checked++;
                advance();
                if ((checked & 1023) == 0 && System.nanoTime() >= deadline) {
                    break;
                }
            }
            if (!candidates.isEmpty() && clusterBudget > 0) {
                candidates.sort(Comparator.comparingLong(candidate -> candidate.distanceSq));
                for (ScanCandidate candidate : candidates) {
                    addOrUpdateOre(candidate.x, candidate.y, candidate.z, storage.get(candidate.localX, candidate.localY, candidate.localZ));
                }
                candidates.clear();
                clusterBudget--;
            }
            return new ScanProgress(blockBudget, clusterBudget);
        }

        private void advance() {
            if (++x < 16) {
                return;
            }
            x = 0;
            if (++z < 16) {
                return;
            }
            z = 0;
            if (++y < 16) {
                return;
            }
            done = true;
        }

        private boolean isDone() {
            return done && candidates.isEmpty();
        }
    }

    private static final class ScanCandidate {
        private final int x;
        private final int y;
        private final int z;
        private final int localX;
        private final int localY;
        private final int localZ;
        private final long distanceSq;

        private ScanCandidate(int x, int y, int z, int localX, int localY, int localZ, long distanceSq) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.localX = localX;
            this.localY = localY;
            this.localZ = localZ;
            this.distanceSq = distanceSq;
        }
    }

    private static final class ScanProgress {
        private final int remainingBlockBudget;
        private final int remainingClusterBudget;

        private ScanProgress(int remainingBlockBudget, int remainingClusterBudget) {
            this.remainingBlockBudget = remainingBlockBudget;
            this.remainingClusterBudget = remainingClusterBudget;
        }
    }

    private final class RenderGroup {
        private final long key;
        private final List<TrackedOre> ores = new ArrayList<>();
        private AxisAlignedBB bounds = new AxisAlignedBB(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
        private double centerX;
        private double centerY;
        private double centerZ;
        private double depth;
        private int displayList = -1;
        private int renderedCount;
        private boolean dirty = true;

        private RenderGroup(long key) {
            this.key = key;
        }

        private void add(TrackedOre ore) {
            ores.add(ore);
            updateCenter();
            dirty = true;
        }

        private void remove(TrackedOre ore) {
            ores.remove(ore);
            updateCenter();
            dirty = true;
        }

        private boolean isEmpty() {
            return ores.isEmpty();
        }

        private void rebuild(Minecraft minecraft, World world) {
            if (displayList < 0) {
                displayList = GLAllocation.generateDisplayLists(1);
            }
            GlStateManager.glNewList(displayList, GL11.GL_COMPILE);
            renderedCount = compileOreBlockGeometry(minecraft, world, ores);
            GlStateManager.glEndList();
            dirty = false;
            removePending();
        }

        private void updateCenter() {
            if (ores.isEmpty()) {
                return;
            }

            long x = 0L;
            long y = 0L;
            long z = 0L;
            bounds = null;
            for (TrackedOre ore : ores) {
                x += ore.x;
                y += ore.y;
                z += ore.z;
                bounds = bounds == null ? ore.bounds : bounds.union(ore.bounds);
            }
            double count = ores.size();
            centerX = x / count + 0.5D;
            centerY = y / count + 0.5D;
            centerZ = z / count + 0.5D;
        }

        private void updateDepth(double viewerX, double viewerY, double viewerZ, Vec3d view) {
            double dx = centerX - viewerX;
            double dy = centerY - viewerY;
            double dz = centerZ - viewerZ;
            depth = dx * view.x + dy * view.y + dz * view.z;
        }

        private void removePending() {
            Iterator<TrackedOre> iterator = ores.iterator();
            boolean removed = false;
            while (iterator.hasNext()) {
                TrackedOre ore = iterator.next();
                if (ore.pendingRemoval) {
                    oreBlocks.remove(ore.x, ore.y, ore.z);
                    iterator.remove();
                    removed = true;
                    markNeighborGroupsDirty(ore.x, ore.y, ore.z);
                }
            }
            if (removed) {
                updateCenter();
            }
        }

        private void delete() {
            if (displayList >= 0) {
                GLAllocation.deleteDisplayLists(displayList);
                displayList = -1;
            }
        }
    }

    private static final class TrackedOre implements ProspectorOverlaySet.Entry {
        private final int x;
        private final int y;
        private final int z;
        private final BlockPos pos;
        private final AxisAlignedBB bounds;
        private IBlockState state;
        private Set<String> oreNames;
        private boolean pendingRemoval;

        private TrackedOre(int x, int y, int z, IBlockState state, Set<String> oreNames) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.pos = new BlockPos(x, y, z);
            this.bounds = new AxisAlignedBB(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D);
            this.state = state;
            this.oreNames = new LinkedHashSet<>(oreNames);
        }

        private void update(IBlockState state, Set<String> oreNames) {
            this.state = state;
            this.oreNames = new LinkedHashSet<>(oreNames);
            this.pendingRemoval = false;
        }

        @Override
        public int x() {
            return x;
        }

        @Override
        public int y() {
            return y;
        }

        @Override
        public int z() {
            return z;
        }

        @Override
        public Set<String> oreNames() {
            return Collections.unmodifiableSet(oreNames);
        }
    }

    private final class ClientWorldListener implements IWorldEventListener {
        @Override
        public void notifyBlockUpdate(World world, BlockPos pos, IBlockState oldState, IBlockState newState, int flags) {
            handleBlockUpdate(world, pos, oldState, newState);
        }

        @Override
        public void notifyLightSet(BlockPos pos) {
        }

        @Override
        public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {
            handleRenderRangeUpdate(x1, y1, z1, x2, y2, z2);
        }

        @Override
        public void playSoundToAllNearExcept(EntityPlayer player, SoundEvent soundIn, SoundCategory category, double x, double y, double z, float volume, float pitch) {
        }

        @Override
        public void playRecord(SoundEvent soundIn, BlockPos pos) {
        }

        @Override
        public void spawnParticle(int particleID, boolean ignoreRange, double xCoord, double yCoord, double zCoord, double xSpeed, double ySpeed, double zSpeed, int... parameters) {
        }

        @Override
        public void spawnParticle(int id, boolean ignoreRange, boolean minimiseParticleLevel, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed, int... parameters) {
        }

        @Override
        public void onEntityAdded(Entity entityIn) {
        }

        @Override
        public void onEntityRemoved(Entity entityIn) {
        }

        @Override
        public void broadcastSound(int soundID, BlockPos pos, int data) {
        }

        @Override
        public void playEvent(EntityPlayer player, int type, BlockPos blockPosIn, int data) {
        }

        @Override
        public void sendBlockBreakProgress(int breakerId, BlockPos pos, int progress) {
        }
    }
}
