package dev.stardust.modules;

import dev.stardust.Stardust;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.fluid.FluidState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/*
    Ported from: https://github.com/BleachDrinker420/BleachHack/blob/master/BleachHack-Fabric-1.16/src/main/java/bleach/hack/module/mods/NewChunks.java
    Skidded with love to Stardust+ from Meteor rejects or trouserstreaks i honestly dont remember yes. this module COULD be done in 5 minutes yes. i STILL decide to skid it LMAO
*/
public class NewChunks extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    // general

    private final Setting<Boolean> remove = sgGeneral.add(new BoolSetting.Builder()
        .name("remove")
        .description("Removes the cached chunks when disabling the module.")
        .defaultValue(true)
        .build()
    );

    // render
    public final Setting<Integer> renderHeight = sgRender.add(new IntSetting.Builder()
        .name("render-height")
        .description("The height at which new chunks will be rendered")
        .defaultValue(0)
        .min(-64)
        .sliderRange(-64,319)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> newChunksSideColor = sgRender.add(new ColorSetting.Builder()
        .name("new-chunks-side-color")
        .description("Color of the chunks that are (most likely) completely new.")
        .defaultValue(new SettingColor(255, 0, 0, 75))
        .visible(() -> shapeMode.get() == ShapeMode.Sides || shapeMode.get() == ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> oldChunksSideColor = sgRender.add(new ColorSetting.Builder()
        .name("old-chunks-side-color")
        .description("Color of the chunks that have (most likely) been loaded before.")
        .defaultValue(new SettingColor(0, 255, 0, 75))
        .visible(() -> shapeMode.get() == ShapeMode.Sides || shapeMode.get() == ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> newChunksLineColor = sgRender.add(new ColorSetting.Builder()
        .name("new-chunks-line-color")
        .description("Color of the chunks that are (most likely) completely new.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .visible(() -> shapeMode.get() == ShapeMode.Lines || shapeMode.get() == ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> oldChunksLineColor = sgRender.add(new ColorSetting.Builder()
        .name("old-chunks-line-color")
        .description("Color of the chunks that have (most likely) been loaded before.")
        .defaultValue(new SettingColor(0, 255, 0, 255))
        .visible(() -> shapeMode.get() == ShapeMode.Lines || shapeMode.get() == ShapeMode.Both)
        .build()
    );

    private final Set<ChunkPos> newChunks = Collections.synchronizedSet(new HashSet<>());
    private final Set<ChunkPos> oldChunks = Collections.synchronizedSet(new HashSet<>());
    private static final Direction[] searchDirs = new Direction[] { Direction.EAST, Direction.NORTH, Direction.WEST, Direction.SOUTH, Direction.UP };
    private final Executor taskExecutor = Executors.newSingleThreadExecutor();

    public NewChunks() {
        super(Stardust.CATEGORY,"NewChunks", "Detects completely new chunks using certain traits of them");
    }

    @Override
    public void onDeactivate() {
        if (remove.get()) {
            newChunks.clear();
            oldChunks.clear();
        }
        super.onDeactivate();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (newChunksLineColor.get().a > 5 || newChunksSideColor.get().a > 5) {
            synchronized (newChunks) {
                for (ChunkPos c : newChunks) {
                    if (mc.getCameraEntity().getBlockPos().isWithinDistance(c.getStartPos(), 1024)) {
                        render(new Box(Vec3d.of(c.getStartPos()), Vec3d.of(c.getStartPos().add(16, renderHeight.get(), 16))), newChunksSideColor.get(), newChunksLineColor.get(), shapeMode.get(), event);
                    }
                }
            }
        }

        if (oldChunksLineColor.get().a > 5 || oldChunksSideColor.get().a > 5){
            synchronized (oldChunks) {
                for (ChunkPos c : oldChunks) {
                    if (mc.getCameraEntity().getBlockPos().isWithinDistance(c.getStartPos(), 1024)) {
                        render(new Box(Vec3d.of(c.getStartPos()), Vec3d.of(c.getStartPos().add(16, renderHeight.get(), 16))), oldChunksSideColor.get(), oldChunksLineColor.get(), shapeMode.get(), event);
                    }
                }
            }
        }
    }

    private void render(Box box, Color sides, Color lines, ShapeMode shapeMode, Render3DEvent event) {
        event.renderer.box(
            box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, sides, lines, shapeMode, 0);
    }

    @EventHandler
    private void onReadPacket(PacketEvent.Receive event) {
        if (event.packet instanceof ChunkDeltaUpdateS2CPacket) {
            ChunkDeltaUpdateS2CPacket packet = (ChunkDeltaUpdateS2CPacket) event.packet;

            packet.visitUpdates((pos, state) -> {
                if (!state.getFluidState().isEmpty() && !state.getFluidState().isStill()) {
                    ChunkPos chunkPos = new ChunkPos(pos);

                    for (Direction dir: searchDirs) {
                        if (mc.world.getBlockState(pos.offset(dir)).getFluidState().isStill() && !oldChunks.contains(chunkPos)) {
                            newChunks.add(chunkPos);
                            return;
                        }
                    }
                }
            });
        }

        else if (event.packet instanceof BlockUpdateS2CPacket) {
            BlockUpdateS2CPacket packet = (BlockUpdateS2CPacket) event.packet;

            if (!packet.getState().getFluidState().isEmpty() && !packet.getState().getFluidState().isStill()) {
                ChunkPos chunkPos = new ChunkPos(packet.getPos());

                for (Direction dir: searchDirs) {
                    if (mc.world.getBlockState(packet.getPos().offset(dir)).getFluidState().isStill() && !oldChunks.contains(chunkPos)) {
                        newChunks.add(chunkPos);
                        return;
                    }
                }
            }
        }

        else if (event.packet instanceof ChunkDataS2CPacket && mc.world != null) {
            ChunkDataS2CPacket packet = (ChunkDataS2CPacket) event.packet;

            ChunkPos pos = new ChunkPos(packet.getChunkX(), packet.getChunkZ());

            if (!newChunks.contains(pos) && mc.world.getChunkManager().getChunk(packet.getChunkX(), packet.getChunkZ()) == null) {
                WorldChunk chunk = new WorldChunk(mc.world, pos);
                try {
                    taskExecutor.execute(() -> chunk.loadFromPacket(packet.getChunkData().getSectionsDataBuf(), new NbtCompound(), packet.getChunkData().getBlockEntities(packet.getChunkX(), packet.getChunkZ())));
                } catch (ArrayIndexOutOfBoundsException e) {
                    return;
                }


                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        for (int y = mc.world.getBottomY(); y < mc.world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z); y++) {
                            FluidState fluid = chunk.getFluidState(x, y, z);

                            if (!fluid.isEmpty() && !fluid.isStill()) {
                                oldChunks.add(pos);
                                return;
                            }
                        }
                    }
                }
            }
        }
    }
}
