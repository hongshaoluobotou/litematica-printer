package me.aleksilassila.litematica.printer.handler.handlers;

import lombok.Getter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BeddingSourceModeType;
import me.aleksilassila.litematica.printer.enums.FillBlockModeType;
import me.aleksilassila.litematica.printer.enums.FillModeFacingType;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.HudStatsManager;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.handler.Module;
import me.aleksilassila.litematica.printer.handler.scan.ScanCache;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.printer.PrinterUtils;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.FilterUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.RegistryFilterResolver;
import me.aleksilassila.litematica.printer.utils.minecraft.BlockUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public class BeddingHandler extends Module {
    public final static String NAME = "bedding";
    private static final Direction[] BEDDING_SIDE_ORDER = {
            Direction.DOWN,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.EAST,
            Direction.WEST,
            Direction.UP
    };

    private List<String> beddingCacheBlocklist = new ArrayList<>();
    private List<String> sourceCacheBlocklist = new ArrayList<>();
    private String[] sourceFilters = new String[0];
    @Getter
    private Item[] beddingModeItemList = new Item[0];
    private PrinterBox beddingScanBox;
    private List<PrinterBox> beddingScanBoxes = List.of();
    private int beddingScanConfigHash;
    private int observedBeddingScanConfigHash = Integer.MIN_VALUE;
    private BeddingSourceModeType currentSourceMode = BeddingSourceModeType.CUSTOM;
    private Set<Block> dynamicSpawnableBlocks = Set.of();
    private Set<Block> dynamicNotSpawnableBlocks = Set.of();
    private int dynamicScanBoxHash = Integer.MIN_VALUE;

    public BeddingHandler() {
        super(NAME, PrintModeType.BEDDING, Configs.Core.BEDDING, Configs.Bedding.BEDDING_SELECTION_TYPE, true);
    }

    @Override
    protected int getTickInterval() {
        return Configs.Placement.PLACE_INTERVAL.getIntegerValue();
    }

    @Override
    protected int getMaxEffectiveExecutionsPerTick() {
        return Configs.Placement.PLACE_BLOCKS_PER_TICK.getIntegerValue();
    }

    @Override
    protected void preprocess() {
        this.refreshActiveSourceFilters();
        FillBlockModeType mode = (FillBlockModeType) Configs.Bedding.BEDDING_BLOCK_MODE.getOptionListValue();
        switch (mode) {
            case BLOCKLIST:
                List<String> strings = Configs.Bedding.BEDDING_BLOCK_LIST.getStrings();
                if (!strings.equals(beddingCacheBlocklist)) {
                    beddingCacheBlocklist = new ArrayList<>(strings);
                    beddingModeItemList = new Item[0];
                    if (strings.isEmpty()) {
                        HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.BEDDING, "铺盖列表为空");
                        return;
                    }
                    List<Item> items = RegistryFilterResolver.resolveItems(beddingCacheBlocklist);
                    beddingModeItemList = items.toArray(new Item[0]);
                }
                break;
            case HANDHELD:
                ItemStack heldStack = player.getMainHandItem();
                if (!heldStack.isEmpty() && heldStack.getCount() > 0) {
                    beddingModeItemList = new Item[]{heldStack.getItem()};
                    HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.BEDDING, "运行中");
                } else {
                    beddingModeItemList = new Item[0];
                    HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.BEDDING, "主手无可铺盖方块");
                }
                break;
        }
        if (beddingModeItemList.length == 0 && mode == FillBlockModeType.BLOCKLIST && !beddingCacheBlocklist.isEmpty()) {
            HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.BEDDING, "列表无匹配方块");
        }
        int scanConfigHash = this.getBeddingScanConfigHash();
        if (this.observedBeddingScanConfigHash != Integer.MIN_VALUE
                && this.observedBeddingScanConfigHash != scanConfigHash) {
            this.clearBeddingTargets();
            ScanCache.INSTANCE.resetOwner(NAME);
            this.requestFullScan();
        }
        this.observedBeddingScanConfigHash = scanConfigHash;
    }

    @Override
    protected void onRuntimeReset() {
        this.clearBeddingTargets();
        this.beddingScanConfigHash = 0;
        this.observedBeddingScanConfigHash = Integer.MIN_VALUE;
    }

    private void refreshActiveSourceFilters() {
        BeddingSourceModeType mode = (BeddingSourceModeType) Configs.Bedding.BEDDING_SOURCE_BLOCK_MODE.getOptionListValue();
        boolean modeChanged = mode != this.currentSourceMode;
        this.currentSourceMode = mode;

        if (mode == BeddingSourceModeType.CUSTOM) {
            List<String> sourceList = Configs.Bedding.BEDDING_SOURCE_BLOCK_LIST.getStrings();
            if (sourceList.equals(this.sourceCacheBlocklist)) {
                return;
            }
            this.sourceCacheBlocklist = new ArrayList<>(sourceList);
            this.sourceFilters = this.sourceCacheBlocklist.toArray(new String[0]);
        } else {
            if (!modeChanged && this.dynamicScanBoxHash == this.beddingScanBoxHash()) {
                return;
            }
            this.sourceFilters = new String[]{"__dynamic__"};
            if (this.beddingScanBox != null) {
                this.scanDynamicBlocks();
            } else {
                this.dynamicSpawnableBlocks = Set.of();
                this.dynamicNotSpawnableBlocks = Set.of();
//                System.out.println("debug: beddingScanBox is null, dynamicSpawnableBlocks=" + dynamicSpawnableBlocks);
//                System.out.println("debug: beddingScanBox is null, dynamicNotSpawnableBlocks=" + dynamicNotSpawnableBlocks);
            }
        }
    }

    private int beddingScanBoxHash() {
        if (this.beddingScanBox == null) return Integer.MIN_VALUE;
        return Arrays.hashCode(new int[]{
                this.beddingScanBox.minX, this.beddingScanBox.minY, this.beddingScanBox.minZ,
                this.beddingScanBox.maxX, this.beddingScanBox.maxY, this.beddingScanBox.maxZ
        });
    }

    private void scanDynamicBlocks() {
        if (this.level == null || this.beddingScanBox == null) {
            this.dynamicSpawnableBlocks = Set.of();
            this.dynamicNotSpawnableBlocks = Set.of();
            this.dynamicScanBoxHash = this.beddingScanBoxHash();
            return;
        }
        Set<Block> spawnable = new HashSet<>();
        Set<Block> notSpawnable = new HashSet<>();
        BlockPos.betweenClosed(
                this.beddingScanBox.minX, this.beddingScanBox.minY, this.beddingScanBox.minZ,
                this.beddingScanBox.maxX, this.beddingScanBox.maxY, this.beddingScanBox.maxZ
        ).forEach(pos -> {
            Block block = this.level.getBlockState(pos).getBlock();
            if (BlockUtils.isMobSpawnGround(this.level, pos)) {
                spawnable.add(block);
            } else {
                notSpawnable.add(block);
            }
        });
        this.dynamicSpawnableBlocks = spawnable;
        this.dynamicNotSpawnableBlocks = notSpawnable;
        this.dynamicScanBoxHash = this.beddingScanBoxHash();
        HudStatsManager.INSTANCE.recordStatus(
                HudStatsManager.Mode.BEDDING,
                "动态扫描: " + spawnable.size() + " 可刷生 / " + notSpawnable.size() + " 不可刷生"
        );
    }

    @Override
    protected boolean canIterate() {
        return beddingModeItemList.length > 0 && this.sourceFilters.length > 0;
    }

    @Override
    protected boolean iterationPositionsPrefilterReachAndSelection() {
        return true;
    }

    @Override
    protected boolean iterationPositionsPrefilterCooldown() {
        return false;
    }

    @Override
    protected boolean iterationPositionsAreExactCandidates() {
        return Configs.Bedding.BEDDING_BLOCK_MODE.getOptionListValue() != FillBlockModeType.HANDHELD;
    }

    @Override
    protected Iterable<BlockPos> getIterationPositions(PrinterBox playerInteractionBox) {
        List<PrinterBox> scanSourceBoxes = this.getScanSourceBoxes(playerInteractionBox);
        PrinterBox fullInteractionBox = this.playerInteractionBox == null ? null : this.playerInteractionBox.get();
        List<PrinterBox> fullScanSourceBoxes = this.getScanSourceBoxes(fullInteractionBox);
        PrinterBox fullScanSourceBox = this.getScanSourceBox(fullInteractionBox);
        if (scanSourceBoxes.isEmpty() || fullScanSourceBoxes.isEmpty() || fullScanSourceBox == null) {
            this.clearBeddingTargets();
            return List.of();
        }

        int configHash = this.getBeddingScanConfigHash();
        if (this.beddingScanBox == null || this.beddingScanConfigHash != configHash) {
            this.resetBeddingScan(fullScanSourceBox, fullScanSourceBoxes, configHash);
        } else if (!this.beddingScanBox.equals(fullScanSourceBox)
                || !this.beddingScanBoxes.equals(fullScanSourceBoxes)) {
            this.beddingScanBox = this.copyScanBox(fullScanSourceBox);
            this.beddingScanBoxes = List.copyOf(fullScanSourceBoxes);
        }

        if (this.currentSourceMode != BeddingSourceModeType.CUSTOM
                && this.dynamicScanBoxHash != this.beddingScanBoxHash()) {
            this.scanDynamicBlocks();
        }

        Predicate<BlockPos> selectionPredicate = this.createSelectionRangePredicate();
        Predicate<BlockPos> reachPredicate = this.createScanReachPredicate();
        return () -> new Iterator<>() {
            private final Iterator<BlockPos> sourceIterator =
                    createSourceIterator(scanSourceBoxes, reachPredicate, selectionPredicate);
            private BlockPos next;
            private boolean prepared;
            private boolean nextAvailable;

            private void prepare() {
                if (this.prepared) {
                    return;
                }
                this.prepared = true;
                if (this.sourceIterator.hasNext()) {
                    this.next = this.sourceIterator.next();
                    this.nextAvailable = true;
                }
            }

            @Override
            public boolean hasNext() {
                this.prepare();
                return this.nextAvailable;
            }

            @Override
            public BlockPos next() {
                if (!this.hasNext()) {
                    throw new NoSuchElementException();
                }
                BlockPos result = this.next;
                this.next = null;
                this.prepared = false;
                this.nextAvailable = false;
                return result;
            }
        };
    }

    private Iterator<BlockPos> createSourceIterator(
            List<PrinterBox> scanSourceBoxes,
            Predicate<BlockPos> reachPredicate,
            Predicate<BlockPos> selectionPredicate
    ) {
        ScanIntent scanIntent = Configs.Print.PLACE_IN_AIR.getBooleanValue()
                ? ScanIntent.CUSTOM
                : ScanIntent.FILL;
        return ScanCache.INSTANCE.iterable(
                NAME,
                scanSourceBoxes,
                this.level,
                null,
                this.player,
                this.getScanGuardLimit(),
                scanIntent,
                this::isBeddingSource,
                pos -> this.isBeddingCandidatePreFilter(pos, reachPredicate, selectionPredicate)
        ).iterator();
    }

    private boolean isBeddingCandidatePreFilter(
            BlockPos blockPos,
            Predicate<BlockPos> reachPredicate,
            Predicate<BlockPos> selectionPredicate
    ) {
        return reachPredicate.test(blockPos)
                && selectionPredicate.test(blockPos);
    }

    private void resetBeddingScan(
            PrinterBox playerInteractionBox,
            List<PrinterBox> sourceBoxes,
            int configHash
    ) {
        this.beddingScanBox = this.copyScanBox(playerInteractionBox);
        this.beddingScanBoxes = List.copyOf(sourceBoxes);
        this.beddingScanConfigHash = configHash;
    }

    private void clearBeddingTargets() {
        this.beddingScanBox = null;
        this.beddingScanBoxes = List.of();
    }

    private PrinterBox copyScanBox(PrinterBox box) {
        return new PrinterBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private int getBeddingScanConfigHash() {
        int result = Arrays.hashCode(this.beddingModeItemList);
        result = 31 * result + Arrays.hashCode(this.sourceFilters);
        result = 31 * result + Configs.Bedding.BEDDING_BLOCK_MODE.getOptionListValue().hashCode();
        result = 31 * result + Configs.Bedding.BEDDING_SELECTION_TYPE.getOptionListValue().hashCode();
        result = 31 * result + Boolean.hashCode(Configs.Print.PLACE_IN_AIR.getBooleanValue());
        result = 31 * result + Configs.Bedding.BEDDING_BLOCK_FACING.getOptionListValue().hashCode();
        result = 31 * result + Boolean.hashCode(Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue());
        result = 31 * result + Configs.Bedding.BEDDING_SOURCE_BLOCK_MODE.getOptionListValue().hashCode();
        return result;
    }

    @Override
    public boolean canIterationBlockPos(BlockPos blockPos) {
        if (!this.isBeddingSource(this.level.getBlockState(blockPos))) {
            return false;
        }
        if (Configs.Bedding.BEDDING_BLOCK_MODE.getOptionListValue() == FillBlockModeType.HANDHELD) {
            ItemStack heldStack = player.getMainHandItem();
            return !heldStack.isEmpty() && heldStack.getCount() > 0;
        }
        return true;
    }

    @Override
    protected void executeIteration(BlockPos sourcePos, AtomicReference<Boolean> skipIteration) {
        if (this.level == null || this.player == null || sourcePos == null) {
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        BlockState sourceState = this.level.getBlockState(sourcePos);
        if (!this.isBeddingSource(sourceState)) {
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        BlockPos targetPos = sourcePos.above();
        BlockState aboveState = this.level.getBlockState(targetPos);
        if (!aboveState.isAir() && !(aboveState.getBlock() instanceof LiquidBlock)) {
            setIterationConsumedEffectiveExecution(false);
            return;
        }

        if (Configs.Placement.FALLING_CHECK.getBooleanValue()
                && player.getMainHandItem().getItem() instanceof BlockItem item
                && item.getBlock() instanceof FallingBlock block
                && FallingBlock.isFree(sourceState)
        ) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.BEDDING, "下落方块无支撑");
            MessageUtils.setOverlayMessage(I18n.FALLING_BLOCK_NO_SUPPORT.getName(block.getName().getString()));
            return;
        }

        boolean handheld = Configs.Bedding.BEDDING_BLOCK_MODE.getOptionListValue() == FillBlockModeType.HANDHELD;
        if (!handheld && !InventoryUtils.switchToItems(player, this.beddingModeItemList)) {
            boolean retrievalPending =
                    me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.shouldPauseForSwitchRequest()
                            || me.aleksilassila.litematica.printer.utils.mods.TakeItOutUtils.isAwaitingStack();
            if (retrievalPending) {
                HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.BEDDING, "等待取货");
                MissingMaterialTracker.INSTANCE.resolve(this.beddingModeItemList, null);
            } else {
                HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.BEDDING, "缺少铺盖材料");
                MissingMaterialTracker.INSTANCE.recordMissing(
                        this.beddingModeItemList,
                        null,
                        null,
                        level.getGameTime()
                );
            }
            setIterationConsumedEffectiveExecution(false);
            if (retrievalPending) {
                skipIteration.set(true);
            }
            return;
        }
        if (!handheld) {
            MissingMaterialTracker.INSTANCE.resolve(this.beddingModeItemList, null);
        }

        Direction side = this.getBeddingPlacementSide(targetPos);
        if (side == null) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.BEDDING, "无有效放置面");
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        BlockPos clickTarget = Configs.Print.PLACE_IN_AIR.getBooleanValue() ? targetPos : targetPos.relative(side);
        Direction clickSide = side.getOpposite();
        Item[] expectedItems = handheld
                ? new Item[]{player.getMainHandItem().getItem()}
                : this.beddingModeItemList;
        if (!ActionManager.INSTANCE.queueClick(
                clickTarget,
                clickSide,
                Vec3.ZERO,
                false,
                1,
                expectedItems,
                ActionManager.ActionSource.FILL
        )) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.BEDDING, "动作队列占用");
            setIterationConsumedEffectiveExecution(false);
            skipIteration.set(true);
            return;
        }
        ActionManager.INSTANCE.setLook(new PlayerLook(clickSide));
        ActionManager.INSTANCE.setWaitForHorizontalLook(false);
        ActionManager.SendResult sendResult = ActionManager.INSTANCE.sendQueue(player);
        if (sendResult.isWaiting()) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.BEDDING, "等待转头");
            skipIteration.set(true);
            return;
        }
        if (!sendResult.isSent()) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.BEDDING, "放置动作未发送");
            setIterationConsumedEffectiveExecution(false);
            skipIteration.set(true);
            return;
        }
        HudStatsManager.INSTANCE.trackExpectedBlockChange(HudStatsManager.Mode.BEDDING, targetPos, aboveState);
        HudStatsManager.INSTANCE.recordRateUnit(HudStatsManager.Mode.BEDDING, 1);
        HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.BEDDING, "运行中");
        this.setBlockPosCooldown(sourcePos, ConfigUtils.getPlaceCooldown());
    }

    private Direction getBeddingPlacementSide(BlockPos targetPos) {
        if (this.level == null || this.player == null || targetPos == null) {
            return null;
        }
        Direction configuredFacing = this.getBeddingConfiguredFacing();
        if (Configs.Print.PLACE_IN_AIR.getBooleanValue()) {
            return configuredFacing != null ? configuredFacing : getPlayerPlacementDirection();
        }
        if (configuredFacing != null) {
            return this.isValidBeddingPlacementSide(targetPos, configuredFacing) ? configuredFacing : null;
        }
        for (Direction side : BEDDING_SIDE_ORDER) {
            if (this.isValidBeddingPlacementSide(targetPos, side)) {
                return side;
            }
        }
        return null;
    }

    private Direction getBeddingConfiguredFacing() {
        if (Configs.Bedding.BEDDING_BLOCK_FACING.getOptionListValue() instanceof FillModeFacingType type) {
            return switch (type) {
                case DOWN -> Direction.DOWN;
                case UP -> Direction.UP;
                case WEST -> Direction.WEST;
                case EAST -> Direction.EAST;
                case NORTH -> Direction.NORTH;
                case SOUTH -> Direction.SOUTH;
                default -> null;
            };
        }
        return null;
    }

    private boolean isValidBeddingPlacementSide(BlockPos targetPos, Direction side) {
        BlockPos neighborPos = targetPos.relative(side);
        BlockState neighborState = this.level.getBlockState(neighborPos);
        return PrinterUtils.canBeClicked(this.level, neighborPos) && !BlockUtils.isReplaceable(neighborState);
    }

    private boolean isBeddingSource(BlockPos blockPos) {
        return this.level != null && this.isBeddingSource(this.level.getBlockState(blockPos));
    }

    private boolean isBeddingSource(BlockState state) {
        if (this.sourceFilters.length == 0) {
            return false;
        }
        Block block = state.getBlock();
        if (this.currentSourceMode == BeddingSourceModeType.MOB_SPAWNABLE) {
            return this.dynamicSpawnableBlocks.contains(block);
        }
        if (this.currentSourceMode == BeddingSourceModeType.MOB_NOT_SPAWNABLE) {
            return this.dynamicNotSpawnableBlocks.contains(block);
        }
        for (String filter : this.sourceFilters) {
            if (FilterUtils.matchName(filter, state)) {
                return true;
            }
        }
        return false;
    }

}
