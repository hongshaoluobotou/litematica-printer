package me.aleksilassila.litematica.printer.utils.minecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
//#if MC >= 260200
//$$ import net.minecraft.world.entity.EntityTypes;
//#else
import net.minecraft.world.entity.EntityType;
//#endif
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

public class BlockUtils {
    public static boolean isReplaceable(BlockState blockState) {
        //#if MC > 11902
        return blockState.canBeReplaced();
        //#else
        //$$ return blockState.getMaterial().isReplaceable();
        //#endif
    }

    public static @NotNull Block getBlock(Identifier blockId) {
        //#if MC > 12101
        return BuiltInRegistries.BLOCK.getValue(blockId);
        //#else
        //$$ return BuiltInRegistries.BLOCK.get(blockId);
        //#endif
    }

    public static String getBlockName(Block block) {
        return block.getName().getString();
    }

    public static Identifier getKey(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block);
    }

    public static String getKeyString(Block block) {
        return getKey(block).toString();
    }

    public static boolean canSupportCenter(LevelReader levelReader, BlockPos blockPos, Direction direction) {
        return Block.canSupportCenter(levelReader, blockPos, direction);
    }

    /**
     * 等价于 Mojang 内部刷怪 ground 判定（{@code state.isValidSpawn}）。
     * 跨 1.18.2 → 26.2 签名稳定（{@code BlockState.isValidSpawn(BlockGetter, BlockPos, EntityType)}），
     * 默认实现为 {@code isFaceSturdy(UP) + lightEmission < 14}，与官方 NaturalSpawner 一致。
     *
     * <p>对以下方块返回 false（与官方规则一致）：玻璃/玻璃板、基岩、屏障、活塞臂、栅栏门/活板门（开/关）、
     * 脚手架、铜格栅、树叶、冰/霜冰、地毯、铁轨、压力板/按钮/红石元件、半砖（未倒置）、栅栏/墙/围栏、
     * 漏斗、睡莲等。
     *
     * <p>对以下方块返回 true：完整 1×1×1 实体方块、上半砖、倒置楼梯、灵魂沙、活塞等。
     */
    public static boolean isMobSpawnGround(LevelReader level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        //#if MC >= 260200
        //$$ return state.isValidSpawn(level, pos, EntityTypes.CREEPER);
        //#else
        return state.isValidSpawn(level, pos, EntityType.CREEPER);
        //#endif
    }
}
