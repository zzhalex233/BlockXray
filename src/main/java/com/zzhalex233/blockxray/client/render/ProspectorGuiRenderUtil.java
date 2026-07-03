package com.zzhalex233.blockxray.client.render;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

@SideOnly(Side.CLIENT)
public final class ProspectorGuiRenderUtil {
    private static final BlockPos ORIGIN = BlockPos.ORIGIN;

    private ProspectorGuiRenderUtil() {
    }

    public static void drawBlockTargetIcon(Minecraft minecraft, RenderItem itemRender, ItemStack itemIcon,
                                           IBlockState state, int x, int y, int size) {
        if (state != null && drawFluidIcon(minecraft, state, x, y, size)) {
            return;
        }
        if (state != null && shouldUseNativeBlockModelIcon(minecraft, itemRender, itemIcon, state)
                && drawNativeBlockModelIcon(minecraft, itemRender, state, x, y)) {
            return;
        }
        if (state != null && shouldUseWorldIcon(minecraft, itemRender, itemIcon, state) && drawWorldIcon(minecraft, state, x, y, size)) {
            return;
        }
        if (itemIcon != null && !itemIcon.isEmpty() && !hasMissingItemModel(minecraft, itemRender, itemIcon) && drawItemIcon(itemRender, itemIcon, x, y)) {
            return;
        }
        if (state != null && state.getRenderType() != EnumBlockRenderType.INVISIBLE) {
            drawWorldIcon(minecraft, state, x, y, size);
        }
    }

    private static boolean drawItemIcon(RenderItem itemRender, ItemStack icon, int x, int y) {
        GlStateManager.pushMatrix();
        try {
            RenderHelper.enableGUIStandardItemLighting();
            GlStateManager.enableDepth();
            itemRender.renderItemAndEffectIntoGUI(icon, x, y);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableDepth();
            GlStateManager.popMatrix();
        }
    }

    private static boolean shouldUseWorldIcon(Minecraft minecraft, RenderItem itemRender, ItemStack itemIcon, IBlockState state) {
        EnumBlockRenderType renderType = state.getRenderType();
        boolean generatedFullBlockIcon = renderType == EnumBlockRenderType.MODEL
                && !isMultipartState(state)
                && hasFullBlockBounds(minecraft, state)
                && usesGeneratedItemTexture(minecraft, itemRender, itemIcon);
        boolean unusableItem = itemIcon == null || itemIcon.isEmpty()
                || hasMissingItemModel(minecraft, itemRender, itemIcon)
                || isFlatItemModel(minecraft, itemRender, itemIcon)
                || isThinItemModel(minecraft, itemRender, itemIcon)
                || generatedFullBlockIcon;
        return isMultipartState(state)
                || (renderType == EnumBlockRenderType.MODEL || renderType == EnumBlockRenderType.ENTITYBLOCK_ANIMATED) && unusableItem;
    }

    private static boolean shouldUseNativeBlockModelIcon(Minecraft minecraft, RenderItem itemRender, ItemStack itemIcon, IBlockState state) {
        boolean missingItem = itemIcon == null || itemIcon.isEmpty() || hasMissingItemModel(minecraft, itemRender, itemIcon);
        return state.getRenderType() == EnumBlockRenderType.MODEL
                && !isMultipartState(state)
                && missingItem
                && hasFullBlockBounds(minecraft, state);
    }

    private static boolean drawFluidIcon(Minecraft minecraft, IBlockState state, int x, int y, int size) {
        Fluid fluid = fluidFor(state);
        if (fluid == null) {
            return false;
        }
        FluidStack stack = new FluidStack(fluid, 1000);
        ResourceLocation texture = fluid.getFlowing(stack);
        if (texture == null) {
            texture = fluid.getFlowing();
        }
        if (texture == null) {
            return false;
        }

        TextureAtlasSprite sprite = minecraft.getTextureMapBlocks().getAtlasSprite(texture.toString());
        if (sprite == null || isMissingSprite(sprite)) {
            return false;
        }

        GlStateManager.pushMatrix();
        try {
            minecraft.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableLighting();
            GlStateManager.disableDepth();
            GlStateManager.enableTexture2D();
            GlStateManager.enableAlpha();
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);

            int color = fluid.getColor(stack);
            float alpha = ((color >>> 24) & 255) / 255.0F;
            if (alpha == 0.0F) {
                alpha = 1.0F;
            }
            GlStateManager.color(((color >> 16) & 255) / 255.0F, ((color >> 8) & 255) / 255.0F, (color & 255) / 255.0F, alpha);

            float minU = sprite.getMinU();
            float minV = sprite.getMinV();
            float maxU = minU + (sprite.getMaxU() - minU) * Math.min(16, sprite.getIconWidth()) / Math.max(1, sprite.getIconWidth());
            float maxV = minV + (sprite.getMaxV() - minV) * Math.min(16, sprite.getIconHeight()) / Math.max(1, sprite.getIconHeight());
            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.getBuffer();
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
            buffer.pos(x, y + size, 0).tex(minU, maxV).endVertex();
            buffer.pos(x + size, y + size, 0).tex(maxU, maxV).endVertex();
            buffer.pos(x + size, y, 0).tex(maxU, minV).endVertex();
            buffer.pos(x, y, 0).tex(minU, minV).endVertex();
            tessellator.draw();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.disableBlend();
            GlStateManager.disableAlpha();
            GlStateManager.enableDepth();
            GlStateManager.popMatrix();
        }
    }

    private static Fluid fluidFor(IBlockState state) {
        Block block = state.getBlock();
        Fluid fluid = FluidRegistry.lookupFluidForBlock(block);
        if (fluid != null) {
            return fluid;
        }
        Material material = state.getMaterial();
        if (material == Material.WATER) {
            return FluidRegistry.WATER;
        }
        return material == Material.LAVA ? FluidRegistry.LAVA : null;
    }

    private static boolean drawNativeBlockModelIcon(Minecraft minecraft, RenderItem itemRender, IBlockState state, int x, int y) {
        ITextureObject texture = null;
        GlStateManager.pushMatrix();
        try {
            IBakedModel model = minecraft.getBlockRendererDispatcher().getModelForState(state);
            if (model == null || model.isBuiltInRenderer()) {
                return false;
            }

            minecraft.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            texture = minecraft.getTextureManager().getTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            if (texture != null) {
                texture.setBlurMipmap(false, false);
            }

            RenderHelper.enableGUIStandardItemLighting();
            GlStateManager.enableRescaleNormal();
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.translate(x, y, 100.0F);
            GlStateManager.translate(8.0F, 8.0F, 0.0F);
            GlStateManager.scale(1.0F, -1.0F, 1.0F);
            GlStateManager.scale(16.0F, 16.0F, 16.0F);
            if (model.isGui3d()) {
                GlStateManager.enableLighting();
            } else {
                GlStateManager.disableLighting();
            }
            model = ForgeHooksClient.handleCameraTransforms(model, ItemCameraTransforms.TransformType.GUI, false);
            GlStateManager.translate(-0.5F, -0.5F, -0.5F);
            renderNativeBlockModelQuads(itemRender, model, state);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.disableBlend();
            GlStateManager.disableAlpha();
            GlStateManager.disableRescaleNormal();
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableLighting();
            GlStateManager.popMatrix();
            if (texture != null) {
                texture.restoreLastBlurMipmap();
            }
        }
    }

    private static void renderNativeBlockModelQuads(RenderItem itemRender, IBakedModel model, IBlockState state) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.ITEM);
        for (EnumFacing facing : EnumFacing.values()) {
            itemRender.renderQuads(buffer, model.getQuads(state, facing, 0L), -1, ItemStack.EMPTY);
        }
        itemRender.renderQuads(buffer, model.getQuads(state, null, 0L), -1, ItemStack.EMPTY);
        tessellator.draw();
    }

    private static boolean drawWorldIcon(Minecraft minecraft, IBlockState state, int x, int y, int size) {
        IconScene scene = IconScene.create(minecraft, state);
        if (scene == null || scene.isEmpty()) {
            return false;
        }

        GlStateManager.pushMatrix();
        try {
            minecraft.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);

            AxisAlignedBB bounds = scene.bounds;
            double span = Math.max(bounds.maxX - bounds.minX, Math.max(bounds.maxY - bounds.minY, bounds.maxZ - bounds.minZ));
            IconView view = IconView.from(state, bounds);
            float scale = (float) (size * view.coverage / Math.max(1.0D, span));
            double centerX = (bounds.minX + bounds.maxX) * 0.5D;
            double centerY = (bounds.minY + bounds.maxY) * 0.5D;
            double centerZ = (bounds.minZ + bounds.maxZ) * 0.5D;

            prepareWorldIconState(view.topDownLight);
            GlStateManager.translate(x + size * 0.5F, y + size * view.centerY, 120.0F);
            if (view.pitch != 0.0F) {
                GlStateManager.rotate(view.pitch, 1.0F, 0.0F, 0.0F);
            }
            if (view.yaw != 0.0F) {
                GlStateManager.rotate(view.yaw, 0.0F, 1.0F, 0.0F);
            }
            GlStateManager.scale(scale, -scale, scale);
            GlStateManager.translate(-centerX, -centerY, -centerZ);
            scene.render(minecraft, view.topDownLight);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            restoreWorldIconState();
            GlStateManager.popMatrix();
        }
    }

    private static void prepareWorldIconState(boolean topDownLight) {
        if (topDownLight) {
            enableTopDownIconLighting();
        } else {
            RenderHelper.enableGUIStandardItemLighting();
        }
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(true);
        GlStateManager.enableTexture2D();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableCull();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void enableTopDownIconLighting() {
        GlStateManager.enableLighting();
        GlStateManager.enableLight(0);
        GlStateManager.disableLight(1);
        GlStateManager.enableColorMaterial();
        GlStateManager.colorMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_AMBIENT_AND_DIFFUSE);
        GlStateManager.glLight(GL11.GL_LIGHT0, GL11.GL_POSITION, RenderHelper.setColorBuffer(0.0F, 1.0F, 0.18F, 0.0F));
        GlStateManager.glLight(GL11.GL_LIGHT0, GL11.GL_DIFFUSE, RenderHelper.setColorBuffer(0.9F, 0.9F, 0.9F, 1.0F));
        GlStateManager.glLight(GL11.GL_LIGHT0, GL11.GL_AMBIENT, RenderHelper.setColorBuffer(0.35F, 0.35F, 0.35F, 1.0F));
        GlStateManager.glLight(GL11.GL_LIGHT0, GL11.GL_SPECULAR, RenderHelper.setColorBuffer(0.0F, 0.0F, 0.0F, 1.0F));
        GlStateManager.glLightModel(GL11.GL_LIGHT_MODEL_AMBIENT, RenderHelper.setColorBuffer(0.45F, 0.45F, 0.45F, 1.0F));
    }

    private static void restoreWorldIconState() {
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.disableRescaleNormal();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableDepth();
    }

    private static void renderBlockModel(Minecraft minecraft, IBlockState state) {
        IBakedModel model = minecraft.getBlockRendererDispatcher().getModelForState(state);
        minecraft.getBlockRendererDispatcher().getBlockModelRenderer()
                .renderModelBrightnessColor(state, model, 1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static TileEntity createTileEntity(Minecraft minecraft, IBlockState state) {
        try {
            TileEntity tileEntity = state.getBlock().createTileEntity(minecraft.world, state);
            if (tileEntity != null) {
                tileEntity.setPos(ORIGIN);
            }
            return tileEntity;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isFlatItemModel(Minecraft minecraft, RenderItem itemRender, ItemStack icon) {
        try {
            IBakedModel model = itemRender.getItemModelWithOverrides(icon, minecraft.world, minecraft.player);
            return model == null || !model.isBuiltInRenderer() && !model.isGui3d();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private static boolean hasMissingItemModel(Minecraft minecraft, RenderItem itemRender, ItemStack icon) {
        try {
            IBakedModel model = itemRender.getItemModelWithOverrides(icon, minecraft.world, minecraft.player);
            return model == null || !model.isBuiltInRenderer() && hasMissingParticle(model);
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private static boolean isThinItemModel(Minecraft minecraft, RenderItem itemRender, ItemStack icon) {
        try {
            IBakedModel model = itemRender.getItemModelWithOverrides(icon, minecraft.world, minecraft.player);
            AxisAlignedBB bounds = model == null || model.isBuiltInRenderer() ? null : modelBounds(model, null);
            if (bounds == null) {
                return false;
            }
            double width = bounds.maxX - bounds.minX;
            double height = bounds.maxY - bounds.minY;
            double depth = bounds.maxZ - bounds.minZ;
            return Math.min(width, Math.min(height, depth)) <= 0.125D && Math.max(width, Math.max(height, depth)) >= 0.5D;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean usesGeneratedItemTexture(Minecraft minecraft, RenderItem itemRender, ItemStack icon) {
        if (icon == null || icon.isEmpty()) {
            return false;
        }
        try {
            IBakedModel model = itemRender.getItemModelWithOverrides(icon, minecraft.world, minecraft.player);
            if (model == null || model.isBuiltInRenderer()) {
                return false;
            }
            TextureAtlasSprite sprite = model.getParticleTexture();
            if (sprite == null) {
                return false;
            }
            String iconName = sprite.getIconName();
            return iconName != null && (iconName.startsWith("items/") || iconName.contains(":items/"));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean hasMissingParticle(IBakedModel model) {
        TextureAtlasSprite sprite = model.getParticleTexture();
        return sprite == null || isMissingSprite(sprite);
    }

    private static boolean isMissingSprite(TextureAtlasSprite sprite) {
        if (sprite == null) {
            return true;
        }
        String iconName = sprite.getIconName();
        return iconName == null || "missingno".equals(iconName) || "minecraft:missingno".equals(iconName);
    }

    private static boolean isMultipartState(IBlockState state) {
        return namedPairProperty(state, "lower", "upper") != null || namedPairProperty(state, "foot", "head") != null;
    }

    private static IProperty<?> namedPairProperty(IBlockState state, String first, String second) {
        for (IProperty<?> property : state.getPropertyKeys()) {
            if (hasNamedValue(property, first) && hasNamedValue(property, second)) {
                return property;
            }
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean hasNamedValue(IProperty property, String name) {
        for (Object value : property.getAllowedValues()) {
            if (name.equalsIgnoreCase(property.getName((Comparable) value))) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static IBlockState withNamedValue(IBlockState state, IProperty property, String name) {
        for (Object value : property.getAllowedValues()) {
            if (name.equalsIgnoreCase(property.getName((Comparable) value))) {
                try {
                    return state.withProperty(property, (Comparable) value);
                } catch (RuntimeException ignored) {
                    return state;
                }
            }
        }
        return state;
    }

    private static EnumFacing horizontalFacing(IBlockState state) {
        for (IProperty<?> property : state.getPropertyKeys()) {
            Comparable<?> value = state.getValue(property);
            if (value instanceof EnumFacing && ((EnumFacing) value).getHorizontalIndex() >= 0) {
                return (EnumFacing) value;
            }
        }
        return EnumFacing.SOUTH;
    }

    private static AxisAlignedBB modelBounds(IBakedModel model, IBlockState state) {
        ModelBounds bounds = new ModelBounds();
        for (EnumFacing facing : EnumFacing.values()) {
            bounds.include(model.getQuads(state, facing, 0L));
        }
        bounds.include(model.getQuads(state, null, 0L));
        return bounds.toBox();
    }

    private static boolean hasFullBlockBounds(Minecraft minecraft, IBlockState state) {
        try {
            AxisAlignedBB box = state.getBlock().getBoundingBox(state, minecraft.world, ORIGIN);
            return box != null
                    && Math.abs(box.minX) < 0.001D
                    && Math.abs(box.minY) < 0.001D
                    && Math.abs(box.minZ) < 0.001D
                    && Math.abs(box.maxX - 1.0D) < 0.001D
                    && Math.abs(box.maxY - 1.0D) < 0.001D
                    && Math.abs(box.maxZ - 1.0D) < 0.001D;
        } catch (RuntimeException ignored) {
            return state.isFullCube();
        }
    }

    private static final class ModelBounds {
        private double minX = Double.POSITIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double minZ = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;
        private double maxZ = Double.NEGATIVE_INFINITY;

        private void include(List<BakedQuad> quads) {
            for (BakedQuad quad : quads) {
                int[] data = quad.getVertexData();
                int stride = Math.max(1, quad.getFormat().getIntegerSize());
                for (int vertex = 0; vertex < 4; vertex++) {
                    int offset = vertex * stride;
                    if (offset + 2 >= data.length) {
                        break;
                    }
                    include(Float.intBitsToFloat(data[offset]), Float.intBitsToFloat(data[offset + 1]), Float.intBitsToFloat(data[offset + 2]));
                }
            }
        }

        private void include(double x, double y, double z) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        private AxisAlignedBB toBox() {
            return minX == Double.POSITIVE_INFINITY ? null : new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private static final class IconView {
        private final float pitch;
        private final float yaw;
        private final float centerY;
        private final double coverage;
        private final boolean topDownLight;

        private IconView(float pitch, float yaw, float centerY, double coverage, boolean topDownLight) {
            this.pitch = pitch;
            this.yaw = yaw;
            this.centerY = centerY;
            this.coverage = coverage;
            this.topDownLight = topDownLight;
        }

        private static IconView from(IBlockState state, AxisAlignedBB bounds) {
            double width = bounds.maxX - bounds.minX;
            double height = bounds.maxY - bounds.minY;
            double depth = bounds.maxZ - bounds.minZ;
            if (!state.isFullCube() && width <= 1.01D && height <= 1.01D && depth <= 1.01D) {
                return new IconView(-30.0F, 230.0F, 0.54F, 0.70D, true);
            }
            if (!state.isFullCube() || height > Math.max(width, depth) * 1.35D) {
                return new IconView(-30.0F, 230.0F, 0.54F, 0.88D, true);
            }
            if (Math.max(width, depth) > height * 1.35D) {
                return new IconView(-30.0F, 230.0F, 0.56F, 0.88D, true);
            }
            return new IconView(30.0F, 225.0F, 0.58F, 0.78D, false);
        }
    }

    private static final class IconScene {
        private final List<BlockEntry> blocks = new ArrayList<>();
        private final List<TileEntry> tiles = new ArrayList<>();
        private AxisAlignedBB bounds;

        private static IconScene create(Minecraft minecraft, IBlockState state) {
            IconScene scene = new IconScene();
            EnumBlockRenderType renderType = state.getRenderType();
            if (renderType == EnumBlockRenderType.ENTITYBLOCK_ANIMATED) {
                TileEntity tileEntity = createTileEntity(minecraft, state);
                if (tileEntity == null) {
                    return null;
                }
                scene.tiles.add(new TileEntry(tileEntity, 0.0D, 0.0D, 0.0D));
                scene.include(entityBounds(state));
                return scene;
            }

            if (renderType != EnumBlockRenderType.MODEL) {
                return null;
            }

            IProperty<?> vertical = namedPairProperty(state, "lower", "upper");
            if (vertical != null) {
                scene.addBlock(withNamedValue(state, vertical, "lower"), 0, 0, 0);
                scene.addBlock(withNamedValue(state, vertical, "upper"), 0, 1, 0);
                return scene;
            }

            IProperty<?> horizontal = namedPairProperty(state, "foot", "head");
            if (horizontal != null) {
                EnumFacing facing = horizontalFacing(state);
                scene.addBlock(withNamedValue(state, horizontal, "foot"), 0, 0, 0);
                scene.addBlock(withNamedValue(state, horizontal, "head"), facing.getXOffset(), 0, facing.getZOffset());
                return scene;
            }

            scene.addBlock(state, 0, 0, 0);
            return scene;
        }

        private static AxisAlignedBB entityBounds(IBlockState state) {
            if (namedPairProperty(state, "foot", "head") != null) {
                return new AxisAlignedBB(0.0D, 0.0D, -1.0D, 1.0D, 1.0D, 1.0D);
            }
            return new AxisAlignedBB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
        }

        private void addBlock(IBlockState state, int x, int y, int z) {
            blocks.add(new BlockEntry(state, x, y, z));
            include(new AxisAlignedBB(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D));
        }

        private void include(AxisAlignedBB box) {
            bounds = bounds == null ? box : bounds.union(box);
        }

        private boolean isEmpty() {
            return bounds == null || blocks.isEmpty() && tiles.isEmpty();
        }

        private void render(Minecraft minecraft, boolean topDownLight) {
            for (BlockEntry entry : blocks) {
                GlStateManager.pushMatrix();
                GlStateManager.translate(entry.x, entry.y, entry.z);
                renderBlockModel(minecraft, entry.state);
                GlStateManager.popMatrix();
            }

            if (!tiles.isEmpty()) {
                TileEntityRendererDispatcher dispatcher = TileEntityRendererDispatcher.instance;
                dispatcher.renderEngine = minecraft.getTextureManager();
                dispatcher.fontRenderer = minecraft.fontRenderer;
                for (TileEntry entry : tiles) {
                    prepareWorldIconState(topDownLight);
                    dispatcher.render(entry.tileEntity, entry.x, entry.y, entry.z, 0.0F, -1, 1.0F);
                }
            }
        }
    }

    private static final class BlockEntry {
        private final IBlockState state;
        private final int x;
        private final int y;
        private final int z;

        private BlockEntry(IBlockState state, int x, int y, int z) {
            this.state = state;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class TileEntry {
        private final TileEntity tileEntity;
        private final double x;
        private final double y;
        private final double z;

        private TileEntry(TileEntity tileEntity, double x, double y, double z) {
            this.tileEntity = tileEntity;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
