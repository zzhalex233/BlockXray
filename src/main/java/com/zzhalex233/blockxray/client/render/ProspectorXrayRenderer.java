package com.zzhalex233.blockxray.client.render;

import com.zzhalex233.blockxray.common.item.ItemProspector;
import com.zzhalex233.blockxray.common.util.BlockTargets;
import com.zzhalex233.blockxray.common.util.OreDictionaryBlocks;
import com.zzhalex233.blockxray.common.util.ProspectorMatcher;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.MinecraftForgeClient;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@SideOnly(Side.CLIENT)
public enum ProspectorXrayRenderer {
    INSTANCE;

    private static final int SCAN_CENTER_STEP_BLOCKS = 4;
    private static final int SNAPSHOT_SECTIONS_PER_FRAME = 2;
    private static final int MAX_PENDING_SECTION_TASKS = 6;
    private static final int SCAN_RESULT_APPLY_BUDGET = 768;
    private static final int GROUP_SIZE_BITS = 2;
    private static final int REBUILD_GROUPS_PER_FRAME = 32;
    private static final int BLOCK_VERTEX_STRIDE = 28;
    private static final int BLOCK_VERTEX_COLOR_OFFSET = 12;
    private static final int BLOCK_VERTEX_UV_OFFSET = 16;
    private static final int BLOCK_VERTEX_LIGHTMAP_OFFSET = 24;
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final long SNAPSHOT_TIME_BUDGET_NANOS = 1_000_000L;
    private static final long EMPTY_RESCAN_INTERVAL_MILLIS = 1200L;
    private static final long REFRESH_INTERVAL_MILLIS = 5000L;

    private final ProspectorOverlaySet<TrackedOre> oreBlocks = new ProspectorOverlaySet<>();
    private final Map<Long, RenderGroup> renderGroups = new HashMap<>();
    private final List<RenderGroup> visibleGroups = new ArrayList<>();
    private final OverlayBlockAccess overlayBlockAccess = new OverlayBlockAccess();
    private final Set<String> lastSelectedOres = new LinkedHashSet<>();
    private final ClientWorldListener worldListener = new ClientWorldListener();
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "BlockXray Prospector Scan");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentLinkedQueue<ScanBatch> pendingScanBatches = new ConcurrentLinkedQueue<>();
    private ProspectorMatcher oreMatcher = OreDictionaryBlocks.matcher(lastSelectedOres);
    private AsyncScanJob scanJob;
    private ScanBatch activeScanBatch;
    private long lastScanFinishedTime;
    private BlockPos lastPlayerPos = BlockPos.ORIGIN;
    private BlockPos currentPlayerPos = BlockPos.ORIGIN;
    private int lastPrunedChunkX = Integer.MIN_VALUE;
    private int lastPrunedChunkZ = Integer.MIN_VALUE;
    private int lastPrunedRange = Integer.MIN_VALUE;
    private int lastRange = -1;
    private int stencilMask = -1;
    private World lastWorld;
    private boolean hasSettings;
    private boolean lastBlockProspector;
    private volatile int scanGeneration;

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
        ItemProspector prospector = (ItemProspector) stack.getItem();
        Set<String> selectedOres = prospector.getSelectedTargets(stack);
        if (selectedOres.isEmpty()) {
            clearActiveState();
            return;
        }

        int range = ItemProspector.getRange(stack);
        BlockPos playerPos = getPlayerOnPos(minecraft.player);
        currentPlayerPos = playerPos;
        applySettings(minecraft.world, playerPos, range, selectedOres, prospector.isBlockProspector());
        pruneOutsideCurrentChunkRange(playerPos, range);
        if (shouldStartScan(playerPos, now)) {
            startScan(minecraft.world, playerPos, range);
        }

        pumpScanJob(minecraft.world);
        applyScanResults(minecraft.world);

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
        overlayBlockAccess.setWorld(null);
    }

    private void applySettings(World world, BlockPos playerPos, int range, Set<String> selectedOres, boolean blockProspector) {
        boolean selectedChanged = !selectedOres.equals(lastSelectedOres);
        boolean rangeChanged = range != lastRange;
        boolean modeChanged = blockProspector != lastBlockProspector;
        if (hasSettings && !selectedChanged && !rangeChanged && !modeChanged) {
            return;
        }

        lastSelectedOres.clear();
        lastSelectedOres.addAll(selectedOres);
        lastBlockProspector = blockProspector;
        oreMatcher = blockProspector ? BlockTargets.matcher(selectedOres) : OreDictionaryBlocks.matcher(selectedOres);
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
        if (scanJob != null || hasPendingScanResults()) {
            return false;
        }
        long interval = oreBlocks.isEmpty() ? EMPTY_RESCAN_INTERVAL_MILLIS : REFRESH_INTERVAL_MILLIS;
        return now - lastScanFinishedTime >= interval;
    }

    private boolean hasPendingScanResults() {
        return activeScanBatch != null && !activeScanBatch.isDone() || !pendingScanBatches.isEmpty();
    }

    private void startScan(World world, BlockPos center, int range) {
        lastPlayerPos = center;
        if (oreMatcher.isEmpty()) {
            scanJob = null;
            pendingScanBatches.clear();
            activeScanBatch = null;
            clearRenderedBlocks();
            return;
        }
        scanJob = new AsyncScanJob(world, center, range, oreMatcher, ++scanGeneration);
        pendingScanBatches.clear();
        activeScanBatch = null;
    }

    private void pumpScanJob(World world) {
        if (scanJob == null) {
            return;
        }
        if (scanJob.cancelled()) {
            scanJob = null;
            return;
        }
        if (scanJob.pump(world)) {
            lastScanFinishedTime = System.currentTimeMillis();
            scanJob = null;
        }
    }

    private void applyScanResults(World world) {
        int applied = 0;
        while (applied < SCAN_RESULT_APPLY_BUDGET) {
            if (activeScanBatch == null || activeScanBatch.isDone()) {
                activeScanBatch = pendingScanBatches.poll();
                if (activeScanBatch == null) {
                    return;
                }
                if (activeScanBatch.generation != scanGeneration) {
                    activeScanBatch = null;
                    continue;
                }
            }
            applied += activeScanBatch.apply(world, SCAN_RESULT_APPLY_BUDGET - applied);
        }
    }

    private boolean addOrUpdateOre(int x, int y, int z, IBlockState state) {
        Set<String> oreNames = oreMatcher.matchingNames(state);
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

    private boolean addOrUpdateVisibleOre(World world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!world.isBlockLoaded(pos, false) || !hasVisibleMatchedFace(world, pos)) {
            return removeOre(x, y, z);
        }
        return addOrUpdateOre(x, y, z, world.getBlockState(pos));
    }

    private boolean hasVisibleMatchedFace(World world, BlockPos pos) {
        if (!oreMatcher.matches(world.getBlockState(pos))) {
            return false;
        }
        for (EnumFacing face : EnumFacing.values()) {
            if (!oreMatcher.matches(world.getBlockState(pos.offset(face)))) {
                return true;
            }
        }
        return false;
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

        double viewerX = viewer.lastTickPosX + (viewer.posX - viewer.lastTickPosX) * event.getPartialTicks();
        double viewerY = viewer.lastTickPosY + (viewer.posY - viewer.lastTickPosY) * event.getPartialTicks();
        double viewerZ = viewer.lastTickPosZ + (viewer.posZ - viewer.lastTickPosZ) * event.getPartialTicks();
        ICamera camera = new Frustum();
        camera.setPosition(viewerX, viewerY, viewerZ);
        Vec3d view = viewer.getLook(event.getPartialTicks());

        rebuildDirtyGroups(minecraft, world);
        if (renderGroups.isEmpty()) {
            return;
        }

        overlayBlockAccess.setWorld(world);
        minecraft.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        GlStateManager.pushMatrix();
        GlStateManager.translate(-viewerX, -viewerY, -viewerZ);
        prepareXRayState();
        try {
            renderGroupLists(world, viewerX, viewerY, viewerZ, event.getPartialTicks(), view, camera);
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

    private void renderGroupLists(World world, double viewerX, double viewerY, double viewerZ, float partialTicks, Vec3d view, ICamera camera) {
        GlStateManager.depthMask(false);
        visibleGroups.clear();
        for (RenderGroup group : renderGroups.values()) {
            if (!group.dirty && group.hasRenderableGeometry() && camera.isBoundingBoxInFrustum(group.bounds)) {
                group.updateDepth(viewerX, viewerY, viewerZ, view);
                visibleGroups.add(group);
            }
        }
        visibleGroups.sort(Comparator.comparingDouble((RenderGroup group) -> group.depth).reversed());

        if (!visibleGroups.isEmpty()) {
            if (hasVisibleTileEntityOverlays() && renderStencilDepthOverlays(world, partialTicks)) {
                return;
            }
            enableBlockVboClientState();
            try {
                renderLayer(BlockRenderLayer.SOLID);
                renderLayer(BlockRenderLayer.CUTOUT_MIPPED);
                renderLayer(BlockRenderLayer.CUTOUT);
                renderLayer(BlockRenderLayer.TRANSLUCENT);
            } finally {
                disableBlockVboClientState();
            }
            renderTileEntityOverlays(world, partialTicks);
        }
    }

    private boolean hasVisibleTileEntityOverlays() {
        for (RenderGroup group : visibleGroups) {
            if (group.hasTileEntityOverlay()) {
                return true;
            }
        }
        return false;
    }

    private void renderLayer(BlockRenderLayer layer) {
        if (layer == BlockRenderLayer.TRANSLUCENT) {
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                    GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ZERO);
        } else {
            GlStateManager.disableBlend();
        }
        for (RenderGroup group : visibleGroups) {
            group.renderLayer(layer);
        }
        if (layer == BlockRenderLayer.TRANSLUCENT) {
            GlStateManager.disableBlend();
        }
    }

    private boolean renderStencilDepthOverlays(World world, float partialTicks) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!ensureStencilBuffer(minecraft)) {
            return false;
        }

        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilMask(stencilMask);
        GL11.glClearStencil(0);
        GlStateManager.clear(GL11.GL_STENCIL_BUFFER_BIT);
        try {
            renderStencilMask();
            renderOverlayDepthPass(world, partialTicks);
        } finally {
            GlStateManager.colorMask(true, true, true, true);
            GL11.glStencilMask(0xFF);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            minecraft.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            prepareXRayState();
        }
        return true;
    }

    private boolean ensureStencilBuffer(Minecraft minecraft) {
        Framebuffer framebuffer = minecraft.getFramebuffer();
        if (framebuffer == null || !framebuffer.isStencilEnabled() && !framebuffer.enableStencil()) {
            return false;
        }
        if (stencilMask < 0) {
            int stencilBit = MinecraftForgeClient.reserveStencilBit();
            if (stencilBit < 0) {
                return false;
            }
            stencilMask = 1 << stencilBit;
        }
        return true;
    }

    private void renderStencilMask() {
        GlStateManager.colorMask(false, false, false, false);
        GlStateManager.depthMask(false);
        GlStateManager.depthFunc(GL11.GL_GREATER);
        GL11.glStencilFunc(GL11.GL_ALWAYS, stencilMask, stencilMask);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);

        enableBlockVboClientState();
        try {
            renderLayer(BlockRenderLayer.SOLID);
            renderLayer(BlockRenderLayer.CUTOUT_MIPPED);
            renderLayer(BlockRenderLayer.CUTOUT);
            renderLayer(BlockRenderLayer.TRANSLUCENT);
        } finally {
            disableBlockVboClientState();
        }

        renderTileEntityStencilMasks();
    }

    private void renderOverlayDepthPass(World world, float partialTicks) {
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.depthMask(true);
        GlStateManager.clearDepth(1.0D);
        GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GL11.glStencilMask(0x00);
        GL11.glStencilFunc(GL11.GL_EQUAL, stencilMask, stencilMask);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

        enableBlockVboClientState();
        try {
            renderDepthLayer(BlockRenderLayer.SOLID, true);
            renderDepthLayer(BlockRenderLayer.CUTOUT_MIPPED, true);
            renderDepthLayer(BlockRenderLayer.CUTOUT, true);
            renderDepthLayer(BlockRenderLayer.TRANSLUCENT, false);
        } finally {
            disableBlockVboClientState();
        }

        renderTileEntityDepthOverlays(world, partialTicks);
    }

    private void renderDepthLayer(BlockRenderLayer layer, boolean writeDepth) {
        GlStateManager.depthMask(writeDepth);
        if (layer == BlockRenderLayer.TRANSLUCENT) {
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(
                    GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE,
                    GlStateManager.DestFactor.ZERO);
        } else {
            GlStateManager.disableBlend();
        }
        for (RenderGroup group : visibleGroups) {
            group.renderLayer(layer);
        }
        if (layer == BlockRenderLayer.TRANSLUCENT) {
            GlStateManager.disableBlend();
        }
        GlStateManager.depthMask(true);
    }

    private int compileOreBlockGeometry(Minecraft minecraft, World world, Collection<TrackedOre> ores, EnumMap<BlockRenderLayer, VertexBuffer> buffers) {
        int rendered = 0;
        for (TrackedOre ore : ores) {
            IBlockState current = world.getBlockState(ore.pos);
            Set<String> oreNames = oreMatcher.matchingNames(current);
            if (oreNames.isEmpty() || !hasVisibleMatchedFace(world, ore.pos)) {
                ore.pendingRemoval = true;
                continue;
            }
            if (!current.equals(ore.state) || !oreNames.equals(ore.oreNames)) {
                ore.update(current, oreNames);
                markNeighborGroupsDirty(ore.x, ore.y, ore.z);
            }

            IBakedModel model = minecraft.getBlockRendererDispatcher().getModelForState(current);
            if (usesTileEntityRenderer(current, model)) {
                if (world.getTileEntity(ore.pos) != null) {
                    rendered++;
                }
                continue;
            }
            if (model == null) {
                continue;
            }

            rendered++;
        }

        if (rendered > 0) {
            buildLayerBuffers(minecraft.getBlockRendererDispatcher(), ores, buffers);
        }
        return rendered;
    }

    private static boolean usesTileEntityRenderer(IBlockState state, IBakedModel model) {
        return state.getRenderType() == EnumBlockRenderType.ENTITYBLOCK_ANIMATED
                || model != null && model.isBuiltInRenderer();
    }

    private void buildLayerBuffers(BlockRendererDispatcher dispatcher, Collection<TrackedOre> ores, EnumMap<BlockRenderLayer, VertexBuffer> buffers) {
        for (BlockRenderLayer layer : BlockRenderLayer.values()) {
            VertexBuffer vbo = buildLayerBuffer(dispatcher, ores, layer);
            if (vbo != null) {
                buffers.put(layer, vbo);
            }
        }
    }

    private VertexBuffer buildLayerBuffer(BlockRendererDispatcher dispatcher, Collection<TrackedOre> ores, BlockRenderLayer layer) {
        BufferBuilder buffer = new BufferBuilder(1 << 16);
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
        try {
            ForgeHooksClient.setRenderLayer(layer);
            for (TrackedOre ore : ores) {
                if (ore.pendingRemoval || usesTileEntityRenderer(ore.state, dispatcher.getModelForState(ore.state))) {
                    continue;
                }

                IBlockState renderState = actualRenderState(ore.state, ore.pos);
                if (!renderState.getBlock().canRenderInLayer(renderState, layer)) {
                    continue;
                }

                try {
                    dispatcher.renderBlock(renderState, ore.pos, overlayBlockAccess, buffer);
                } catch (RuntimeException ignored) {
                    // Broken mod models should not poison the whole overlay cache.
                }
            }
        } finally {
            ForgeHooksClient.setRenderLayer(null);
        }

        if (buffer.getVertexCount() <= 0) {
            return null;
        }

        buffer.finishDrawing();
        VertexBuffer vbo = new VertexBuffer(DefaultVertexFormats.BLOCK);
        vbo.bufferData(buffer.getByteBuffer());
        return vbo;
    }

    private IBlockState actualRenderState(IBlockState state, BlockPos pos) {
        try {
            return copySharedProperties(state, state.getActualState(overlayBlockAccess, pos));
        } catch (RuntimeException ignored) {
            return state;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static IBlockState copySharedProperties(IBlockState raw, IBlockState actual) {
        IBlockState result = actual;
        for (IProperty rawProperty : raw.getPropertyKeys()) {
            IProperty actualProperty = propertyByName(actual, rawProperty.getName());
            if (actualProperty == null) {
                continue;
            }
            try {
                Comparable value = raw.getValue(rawProperty);
                result = result.withProperty(actualProperty, value);
            } catch (RuntimeException ignored) {
                // Some actual-state properties expose the same name but a different value domain.
            }
        }
        return result;
    }

    private static IProperty<?> propertyByName(IBlockState state, String name) {
        for (IProperty<?> property : state.getPropertyKeys()) {
            if (property.getName().equals(name)) {
                return property;
            }
        }
        return null;
    }

    private void enableBlockVboClientState() {
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);

        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    private void disableBlockVboClientState() {
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

        GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
    }

    private void drawBlockVbo(VertexBuffer vbo) {
        vbo.bindBuffer();
        GL11.glVertexPointer(3, GL11.GL_FLOAT, BLOCK_VERTEX_STRIDE, 0L);
        GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, BLOCK_VERTEX_STRIDE, BLOCK_VERTEX_COLOR_OFFSET);

        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, BLOCK_VERTEX_STRIDE, BLOCK_VERTEX_UV_OFFSET);

        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glTexCoordPointer(2, GL11.GL_SHORT, BLOCK_VERTEX_STRIDE, BLOCK_VERTEX_LIGHTMAP_OFFSET);

        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
        vbo.drawArrays(GL11.GL_QUADS);
        vbo.unbindBuffer();
    }

    private void renderTileEntityOverlays(World world, float partialTicks) {
        RenderHelper.disableStandardItemLighting();
        float lastBrightnessX = OpenGlHelper.lastBrightnessX;
        float lastBrightnessY = OpenGlHelper.lastBrightnessY;
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
        try {
            for (RenderGroup group : visibleGroups) {
                group.renderTileEntities(world, partialTicks, false);
            }
        } finally {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lastBrightnessX, lastBrightnessY);
        }
        Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        prepareXRayState();
    }

    private void renderTileEntityStencilMasks() {
        GlStateManager.disableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        GlStateManager.depthFunc(GL11.GL_GREATER);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        boolean rendered = false;
        for (RenderGroup group : visibleGroups) {
            rendered |= group.renderTileEntityMasks(buffer);
        }
        if (rendered) {
            tessellator.draw();
        } else {
            buffer.finishDrawing();
        }
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
    }

    private void renderTileEntityDepthOverlays(World world, float partialTicks) {
        RenderHelper.disableStandardItemLighting();
        float lastBrightnessX = OpenGlHelper.lastBrightnessX;
        float lastBrightnessY = OpenGlHelper.lastBrightnessY;
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
        try {
            for (RenderGroup group : visibleGroups) {
                group.renderTileEntities(world, partialTicks, true);
            }
        } finally {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lastBrightnessX, lastBrightnessY);
        }
        Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
    }

    private void prepareTileEntityDepthState() {
        GL11.glStencilMask(0x00);
        GL11.glStencilFunc(GL11.GL_EQUAL, stencilMask, stencilMask);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.enableTexture2D();
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(true);
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void prepareXRayState() {
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.enableTexture2D();
        GlStateManager.disableLighting();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.enableCull();
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_GREATER);
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
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
        GlStateManager.disableAlpha();
        GlStateManager.enableDepth();
        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glLineWidth(1.0F);
    }

    private static void putFilledBox(BufferBuilder buffer, AxisAlignedBB box) {
        double minX = box.minX;
        double minY = box.minY;
        double minZ = box.minZ;
        double maxX = box.maxX;
        double maxY = box.maxY;
        double maxZ = box.maxZ;

        quad(buffer, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ);
        quad(buffer, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, minX, minY, maxZ);
        quad(buffer, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, minX, minY, minZ);
        quad(buffer, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ);
        quad(buffer, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ);
        quad(buffer, minX, minY, maxZ, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ);
    }

    private static void quad(BufferBuilder buffer,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2,
                             double x3, double y3, double z3,
                             double x4, double y4, double z4) {
        vertex(buffer, x1, y1, z1);
        vertex(buffer, x2, y2, z2);
        vertex(buffer, x3, y3, z3);
        vertex(buffer, x4, y4, z4);
    }

    private static void vertex(BufferBuilder buffer, double x, double y, double z) {
        buffer.pos(x, y, z).color(1.0F, 1.0F, 1.0F, 1.0F).endVertex();
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
        activeScanBatch = null;
        pendingScanBatches.clear();
        scanGeneration++;
        lastRange = -1;
        lastBlockProspector = false;
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

    private static int snapshotIndex(int x, int y, int z) {
        return (y * SectionInfo.SNAPSHOT_WIDTH + z) * SectionInfo.SNAPSHOT_WIDTH + x;
    }

    private final class AsyncScanJob {
        private final BlockPos center;
        private final int blockRadius;
        private final ProspectorMatcher matcher;
        private final int generation;
        private final AtomicInteger pendingTasks = new AtomicInteger();
        private final List<SectionInfo> sections = new ArrayList<>();
        private int sectionIndex;

        private AsyncScanJob(World world, BlockPos center, int range, ProspectorMatcher matcher, int generation) {
            this.center = center;
            this.blockRadius = rangeBlocks(range);
            this.matcher = matcher;
            this.generation = generation;
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
                        if (storage != null && !storage.isEmpty()) {
                            addSection(chunkX, chunkZ, sectionY);
                        }
                    }
                }
            }
            sections.sort(Comparator.comparingLong(section -> section.distanceSq));
        }

        private void addSection(int chunkX, int chunkZ, int sectionY) {
            int baseX = chunkX << 4;
            int baseY = sectionY << 4;
            int baseZ = chunkZ << 4;
            sections.add(new SectionInfo(baseX, baseY, baseZ, sectionDistanceSq(baseX, baseY, baseZ)));
        }

        private boolean pump(World world) {
            if (cancelled()) {
                return true;
            }

            int submitted = 0;
            long deadline = System.nanoTime() + SNAPSHOT_TIME_BUDGET_NANOS;
            while (sectionIndex < sections.size()
                    && submitted < SNAPSHOT_SECTIONS_PER_FRAME
                    && pendingTasks.get() < MAX_PENDING_SECTION_TASKS) {
                SectionSnapshot snapshot = sections.get(sectionIndex++).snapshot(world, center, blockRadius, generation);
                pendingTasks.incrementAndGet();
                scanExecutor.execute(() -> {
                    try {
                        ScanBatch batch = snapshot.scan(matcher);
                        if (batch.generation == scanGeneration && !batch.isDone()) {
                            pendingScanBatches.add(batch);
                        }
                    } finally {
                        pendingTasks.decrementAndGet();
                    }
                });
                submitted++;
                if (System.nanoTime() >= deadline) {
                    break;
                }
            }
            return sectionIndex >= sections.size() && pendingTasks.get() == 0;
        }

        private boolean cancelled() {
            return generation != scanGeneration;
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
    }

    private final class SectionInfo {
        private static final int SNAPSHOT_WIDTH = 18;

        private final int baseX;
        private final int baseY;
        private final int baseZ;
        private final long distanceSq;

        private SectionInfo(int baseX, int baseY, int baseZ, long distanceSq) {
            this.baseX = baseX;
            this.baseY = baseY;
            this.baseZ = baseZ;
            this.distanceSq = distanceSq;
        }

        private SectionSnapshot snapshot(World world, BlockPos center, int blockRadius, int generation) {
            IBlockState[] states = new IBlockState[SNAPSHOT_WIDTH * SNAPSHOT_WIDTH * SNAPSHOT_WIDTH];
            IBlockState air = Blocks.AIR.getDefaultState();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int height = world.getHeight();
            for (int y = 0; y < SNAPSHOT_WIDTH; y++) {
                int worldY = baseY + y - 1;
                for (int z = 0; z < SNAPSHOT_WIDTH; z++) {
                    int worldZ = baseZ + z - 1;
                    for (int x = 0; x < SNAPSHOT_WIDTH; x++) {
                        int worldX = baseX + x - 1;
                        IBlockState state = air;
                        if (worldY >= 0 && worldY < height) {
                            pos.setPos(worldX, worldY, worldZ);
                            if (world.isBlockLoaded(pos, false)) {
                                state = world.getBlockState(pos);
                            }
                        }
                        states[snapshotIndex(x, y, z)] = state;
                    }
                }
            }
            return new SectionSnapshot(baseX, baseY, baseZ, center, blockRadius, generation, states);
        }
    }

    private final class SectionSnapshot {
        private final int baseX;
        private final int baseY;
        private final int baseZ;
        private final BlockPos center;
        private final int blockRadius;
        private final int generation;
        private final IBlockState[] states;

        private SectionSnapshot(int baseX, int baseY, int baseZ, BlockPos center, int blockRadius, int generation, IBlockState[] states) {
            this.baseX = baseX;
            this.baseY = baseY;
            this.baseZ = baseZ;
            this.center = center;
            this.blockRadius = blockRadius;
            this.generation = generation;
            this.states = states;
        }

        private ScanBatch scan(ProspectorMatcher matcher) {
            List<ScanResult> results = new ArrayList<>();
            for (int y = 1; y <= 16; y++) {
                int worldY = baseY + y - 1;
                for (int z = 1; z <= 16; z++) {
                    int worldZ = baseZ + z - 1;
                    for (int x = 1; x <= 16; x++) {
                        int worldX = baseX + x - 1;
                        if (Math.abs(worldX - center.getX()) > blockRadius || Math.abs(worldZ - center.getZ()) > blockRadius) {
                            continue;
                        }

                        IBlockState state = state(x, y, z);
                        if (matcher.matches(state) && hasVisibleFace(matcher, x, y, z)) {
                            Set<String> names = matcher.matchingNames(state);
                            if (!names.isEmpty()) {
                                results.add(new ScanResult(worldX, worldY, worldZ));
                            }
                        }
                    }
                }
            }
            return new ScanBatch(generation, results);
        }

        private boolean hasVisibleFace(ProspectorMatcher matcher, int x, int y, int z) {
            return !matcher.matches(state(x - 1, y, z))
                    || !matcher.matches(state(x + 1, y, z))
                    || !matcher.matches(state(x, y - 1, z))
                    || !matcher.matches(state(x, y + 1, z))
                    || !matcher.matches(state(x, y, z - 1))
                    || !matcher.matches(state(x, y, z + 1));
        }

        private IBlockState state(int x, int y, int z) {
            return states[snapshotIndex(x, y, z)];
        }
    }

    private final class ScanBatch {
        private final int generation;
        private final List<ScanResult> results;
        private int index;

        private ScanBatch(int generation, List<ScanResult> results) {
            this.generation = generation;
            this.results = results;
        }

        private boolean isDone() {
            return index >= results.size();
        }

        private int apply(World world, int budget) {
            int applied = 0;
            while (applied < budget && index < results.size()) {
                ScanResult result = results.get(index++);
                addOrUpdateVisibleOre(world, result.x, result.y, result.z);
                applied++;
            }
            return applied;
        }
    }

    private static final class ScanResult {
        private final int x;
        private final int y;
        private final int z;

        private ScanResult(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final class OverlayBlockAccess implements IBlockAccess {
        private World world;

        private void setWorld(World world) {
            this.world = world;
        }

        @Override
        public TileEntity getTileEntity(BlockPos pos) {
            TrackedOre ore = oreBlocks.get(pos.getX(), pos.getY(), pos.getZ());
            return ore == null || ore.pendingRemoval || world == null ? null : world.getTileEntity(pos);
        }

        @Override
        public int getCombinedLight(BlockPos pos, int lightValue) {
            return FULL_BRIGHT;
        }

        @Override
        public IBlockState getBlockState(BlockPos pos) {
            TrackedOre ore = oreBlocks.get(pos.getX(), pos.getY(), pos.getZ());
            return ore == null || ore.pendingRemoval ? Blocks.AIR.getDefaultState() : ore.state;
        }

        @Override
        public boolean isAirBlock(BlockPos pos) {
            IBlockState state = getBlockState(pos);
            return state.getMaterial() == Material.AIR || state.getBlock() == Blocks.AIR;
        }

        @Override
        public Biome getBiome(BlockPos pos) {
            return world == null ? Biomes.PLAINS : world.getBiome(pos);
        }

        @Override
        public int getStrongPower(BlockPos pos, EnumFacing direction) {
            return 0;
        }

        @Override
        public WorldType getWorldType() {
            return world == null ? WorldType.DEFAULT : world.getWorldType();
        }

        @Override
        public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean defaultValue) {
            return getBlockState(pos).isSideSolid(this, pos, side);
        }
    }

    private final class RenderGroup {
        private final long key;
        private final List<TrackedOre> ores = new ArrayList<>();
        private final EnumMap<BlockRenderLayer, VertexBuffer> layerBuffers = new EnumMap<>(BlockRenderLayer.class);
        private AxisAlignedBB bounds = new AxisAlignedBB(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
        private double centerX;
        private double centerY;
        private double centerZ;
        private double depth;
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
            deleteBuffers();
            renderedCount = compileOreBlockGeometry(minecraft, world, ores, layerBuffers);
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
            deleteBuffers();
        }

        private void deleteBuffers() {
            for (VertexBuffer buffer : layerBuffers.values()) {
                try {
                    buffer.deleteGlBuffers();
                } catch (RuntimeException ignored) {
                }
            }
            layerBuffers.clear();
        }

        private boolean hasRenderableGeometry() {
            return renderedCount > 0 && (!layerBuffers.isEmpty() || hasTileEntityOverlay());
        }

        private boolean hasTileEntityOverlay() {
            for (TrackedOre ore : ores) {
                if (!ore.pendingRemoval && usesTileEntityRenderer(ore.state, Minecraft.getMinecraft().getBlockRendererDispatcher().getModelForState(ore.state))) {
                    return true;
                }
            }
            return false;
        }

        private void renderLayer(BlockRenderLayer layer) {
            VertexBuffer buffer = layerBuffers.get(layer);
            if (buffer != null) {
                drawBlockVbo(buffer);
            }
        }

        private boolean renderTileEntityMasks(BufferBuilder buffer) {
            boolean rendered = false;
            for (TrackedOre ore : ores) {
                if (ore.pendingRemoval || !usesTileEntityRenderer(ore.state, Minecraft.getMinecraft().getBlockRendererDispatcher().getModelForState(ore.state))) {
                    continue;
                }
                putFilledBox(buffer, ore.bounds);
                rendered = true;
            }
            return rendered;
        }

        private void renderTileEntities(World world, float partialTicks, boolean depthPass) {
            for (TrackedOre ore : ores) {
                if (ore.pendingRemoval || !usesTileEntityRenderer(ore.state, Minecraft.getMinecraft().getBlockRendererDispatcher().getModelForState(ore.state))) {
                    continue;
                }
                TileEntity tileEntity = world.getTileEntity(ore.pos);
                if (tileEntity == null) {
                    continue;
                }
                if (depthPass) {
                    prepareTileEntityDepthState();
                } else {
                    prepareXRayState();
                }
                try {
                    TileEntityRendererDispatcher.instance.render(tileEntity, ore.x, ore.y, ore.z, partialTicks, -1, 1.0F);
                } catch (RuntimeException ignored) {
                    // Keep one broken TESR from breaking the overlay pass for every other block.
                }
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
