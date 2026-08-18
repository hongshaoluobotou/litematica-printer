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

/**
 * 铺盖 Handler：在"源方块"正上方放置"铺盖方块"，
 * 典型场景是把刷怪平台/平坦区域统一铺上半砖/羊毛/地毯等。
 *
 * <h2>核心抽象</h2>
 * <ul>
 *   <li><b>source</b>：触发铺盖的方块（"在什么上面铺"）。</li>
 *   <li><b>target</b>：{@code sourcePos.above()}，放置铺盖方块的位置。</li>
 *   <li><b>铺盖方块</b>：来自 {@code BEDDING_BLOCK_LIST} 或主手物品。</li>
 * </ul>
 *
 * <h2>源方块判定模式</h2>
 * <ul>
 *   <li><b>CUSTOM</b>：用 {@code BEDDING_SOURCE_BLOCK_LIST} 显式匹配方块/Tag。</li>
 *   <li><b>MOB_SPAWNABLE</b>：{@code state.isValidSpawn(...)} = true 的方块
 *       （Mojang 刷怪脚下判定 ≈ {@code isFaceSturdy(UP) && lightEmission < 14}）。</li>
 *   <li><b>MOB_NOT_SPAWNABLE</b>：同上，判定为 false 的方块。</li>
 * </ul>
 * 关键陷阱：{@code Block} 粒度无法区分上半砖/下半砖，因此动态模式必须在执行点做"现场验证"。
 *
 * <h2>控制流</h2>
 * <pre>
 *   Module.tick()
 *     └─ preprocess()                       刷新物品/源过滤器，配置变化触发重扫
 *     └─ canIterate()                       物品就绪 + 源过滤器可用（CUSTOM 看 sourceFilters，动态模式看 dynamicFiltersReady）
 *     └─ getIterationPositions(box)         ScanCache 按玩家距离游标产出 source 候选
 *     └─ executeIteration(sourcePos)        实际放置铺盖方块
 * </pre>
 */
public class BeddingHandler extends Module {
    /** 模式名，作为 ScanCache owner 等统一标识。 */
    public final static String NAME = "bedding";

    /**
     * fallback 放置面搜索顺序：优先在 source 顶面（DOWN 方向）点击放置，
     * 但最终成 BOTTOM_SLAB 还是 TOP_SLAB 由原版 {@code SlabBlock#getPlacementState}
     * 根据 clickedFace/clickY 决定。
     */
    private static final Direction[] BEDDING_SIDE_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH,
            Direction.EAST, Direction.WEST, Direction.UP
    };

    // ============================ 状态缓存 ============================

    /** BEDDING_BLOCK_LIST 缓存，配置比对用。 */
    private List<String> beddingCacheBlocklist = new ArrayList<>();
    /** BEDDING_SOURCE_BLOCK_LIST 缓存（CUSTOM 模式用）。 */
    private List<String> sourceCacheBlocklist = new ArrayList<>();
    /** CUSTOM 模式编译后的源过滤器。 */
    private String[] sourceFilters = new String[0];
    /** 已解析的铺盖方块物品列表。 */
    @Getter
    private Item[] beddingModeItemList = new Item[0];

    // ============================ 扫描盒 ============================

    /** 裁剪到玩家交互范围/选区后的扫描盒（单 box）。 */
    private PrinterBox beddingScanBox;
    /** 扫描盒多 box 列表（跨 chunk/分片场景）。 */
    private List<PrinterBox> beddingScanBoxes = List.of();
    /** 扫描配置哈希：变化即触发重扫。 */
    private int beddingScanConfigHash;
    /** 上次观测到的扫描配置哈希。 */
    private int observedBeddingScanConfigHash = Integer.MIN_VALUE;

    // ============================ 动态刷怪方块识别 ============================

    private BeddingSourceModeType currentSourceMode = BeddingSourceModeType.CUSTOM;
    /** 动态扫描：{@code isValidSpawn = true} 的方块集合（Block 粒度，仅用于粗筛与 HUD 统计）。 */
    private Set<Block> dynamicSpawnableBlocks = Set.of();
    /** 动态扫描：{@code isValidSpawn = false} 的方块集合。 */
    private Set<Block> dynamicNotSpawnableBlocks = Set.of();
    /** 上次动态扫描的 box 哈希。 */
    private int dynamicScanBoxHash = Integer.MIN_VALUE;
    /** 动态模式扫描结果是否就绪（替代原 {@code sourceFilters = {"__dynamic__"}} 哨兵，供 {@link #canIterate} 使用）。 */
    private boolean dynamicFiltersReady = false;

    public BeddingHandler() {
        super(NAME, PrintModeType.BEDDING, Configs.Core.BEDDING, Configs.Bedding.BEDDING_SELECTION_TYPE, true);
    }

    // ====================== Module 钩子：调度 ======================

    @Override
    protected int getTickInterval() {
        return Configs.Placement.PLACE_INTERVAL.getIntegerValue();
    }

    @Override
    protected int getMaxEffectiveExecutionsPerTick() {
        return Configs.Placement.PLACE_BLOCKS_PER_TICK.getIntegerValue();
    }

    // ====================== Module 钩子：preprocess / 重置 ======================

    /**
     * 每 tick 前置：刷新物品列表、源过滤器、必要时做动态扫描，
     * 并在配置哈希变化时清缓存 + 触发 {@link ScanCache} 重扫。
     */
    @Override
    protected void preprocess() {
        this.refreshActiveSourceFilters();

        FillBlockModeType mode = (FillBlockModeType) Configs.Bedding.BEDDING_BLOCK_MODE.getOptionListValue();
        switch (mode) {
            case BLOCKLIST -> {
                List<String> strings = Configs.Bedding.BEDDING_BLOCK_LIST.getStrings();
                if (strings.equals(beddingCacheBlocklist)) {
                    break;
                }
                beddingCacheBlocklist = new ArrayList<>(strings);
                beddingModeItemList = new Item[0];
                if (strings.isEmpty()) {
                    HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.BEDDING, "铺盖列表为空");
                    return;
                }
                List<Item> items = RegistryFilterResolver.resolveItems(beddingCacheBlocklist);
                beddingModeItemList = items.toArray(new Item[0]);
            }
            case HANDHELD -> {
                ItemStack heldStack = player.getMainHandItem();
                if (!heldStack.isEmpty() && heldStack.getCount() > 0) {
                    beddingModeItemList = new Item[]{heldStack.getItem()};
                    HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.BEDDING, "运行中");
                } else {
                    beddingModeItemList = new Item[0];
                    HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.BEDDING, "主手无可铺盖方块");
                }
            }
        }
        if (beddingModeItemList.length == 0 && mode == FillBlockModeType.BLOCKLIST && !beddingCacheBlocklist.isEmpty()) {
            HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.BEDDING, "列表无匹配方块");
        }

        // 配置哈希变化 → 清缓存 + 触发 ScanCache 全量重扫
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
        this.dynamicSpawnableBlocks = Set.of();
        this.dynamicNotSpawnableBlocks = Set.of();
        this.dynamicScanBoxHash = Integer.MIN_VALUE;
        this.dynamicFiltersReady = false;
    }

    // ====================== 源过滤器刷新（含动态扫描） ======================

    /**
     * 按当前 {@link BeddingSourceModeType} 刷新源过滤器。
     * <ul>
     *   <li>CUSTOM：编译 BEDDING_SOURCE_BLOCK_LIST。</li>
     *   <li>动态模式：必要时扫描 box 内方块，结果写 {@link #dynamicSpawnableBlocks}
     *       / {@link #dynamicNotSpawnableBlocks}；实际匹配由 {@link #isBeddingSource(BlockPos)} 现场完成。
     *       是否就绪通过 {@link #dynamicFiltersReady} 显式表达，由 {@link #scanDynamicBlocks} 内部统一维护。</li>
     * </ul>
     */
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
            this.dynamicFiltersReady = false;
        } else {
            // 动态模式：mode/box/dynamicScanBoxHash 三者与上次一致才算命中缓存。
            // beddingScanBox==null 或 dynamicScanBoxHash==MIN_VALUE 视为未扫过，必须扫。
            if (!modeChanged
                    && this.beddingScanBox != null
                    && this.dynamicScanBoxHash != Integer.MIN_VALUE
                    && this.dynamicScanBoxHash == this.beddingScanBoxHash()) {
                return;
            }
            this.sourceFilters = new String[0];
            this.scanDynamicBlocks();
        }
    }

    /** 扫描盒哈希（用于检测 box 变化）。 */
    private int beddingScanBoxHash() {
        if (this.beddingScanBox == null) return Integer.MIN_VALUE;
        return scanBoxHash(this.beddingScanBox);
    }

    /** 给定 box 的哈希（独立工具方法，便于在非 beddingScanBox 场景下复用）。 */
    private static int scanBoxHash(PrinterBox box) {
        if (box == null) return Integer.MIN_VALUE;
        return Arrays.hashCode(new int[]{
                box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ
        });
    }

    /**
     * 对 box 内方块按 {@code isValidSpawn} 二分类，填入 dynamic set。
     * 扫描粒度为 {@link Block}（同 Block 的上半砖/下半砖共享实例），
     * 精确判定在 {@link #isBeddingSource(BlockPos)} 中现场完成。
     *
     * <p>box 优先取 {@link #beddingScanBox}；若为 null（例如刚进存档、
     * 玩家 box 尚未建立），回退到 {@link #playerInteractionBox}，保证
     * preprocess 与 canIterate 之间的扫描链路不会因 box 未就绪而死锁。
     * {@link #dynamicFiltersReady} 由本方法统一维护。
     */
    private void scanDynamicBlocks() {
        PrinterBox box = this.beddingScanBox;
        if (box == null && this.playerInteractionBox != null) {
            box = this.playerInteractionBox.get();
        }
        if (this.level == null || box == null) {
            this.dynamicSpawnableBlocks = Set.of();
            this.dynamicNotSpawnableBlocks = Set.of();
            this.dynamicScanBoxHash = Integer.MIN_VALUE;
            this.dynamicFiltersReady = false;
            return;
        }
        Set<Block> spawnable = new HashSet<>();
        Set<Block> notSpawnable = new HashSet<>();
        BlockPos.betweenClosed(
                box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ
        ).forEach(pos -> {
            // 排除上方已有方块（液体除外）的列：它们已是完成态，不应纳入源集。
            BlockState aboveState = this.level.getBlockState(pos.above());
            if (!aboveState.isAir() && !(aboveState.getBlock() instanceof LiquidBlock)) {
                return;
            }
            Block block = this.level.getBlockState(pos).getBlock();
            if (BlockUtils.isMobSpawnGround(this.level, pos)) {
                spawnable.add(block);
            } else {
                notSpawnable.add(block);
            }
        });
        this.dynamicSpawnableBlocks = spawnable;
        this.dynamicNotSpawnableBlocks = notSpawnable;
        this.dynamicScanBoxHash = scanBoxHash(box);
        this.dynamicFiltersReady = !spawnable.isEmpty() || !notSpawnable.isEmpty();
        HudStatsManager.INSTANCE.recordStatus(
                HudStatsManager.Mode.BEDDING,
                "动态扫描: " + spawnable.size() + " 可刷生 / " + notSpawnable.size() + " 不可刷生"
        );
    }

    // ====================== Module 钩子：迭代前置条件 ======================

    /** 物品列表非空且源过滤器可用：CUSTOM 看 sourceFilters，动态模式看 {@link #dynamicFiltersReady}。 */
    @Override
    protected boolean canIterate() {
        if (beddingModeItemList.length == 0) return false;
        return this.currentSourceMode == BeddingSourceModeType.CUSTOM
                ? this.sourceFilters.length > 0
                : this.dynamicFiltersReady;
    }

    /** 启用 reach + selection 前置过滤：候选位置须在交互距离内且在选区内。 */
    @Override
    protected boolean iterationPositionsPrefilterReachAndSelection() {
        return true;
    }

    /** 不做 cooldown 预过滤：源方块冷却在 executeIteration 内部维护。 */
    @Override
    protected boolean iterationPositionsPrefilterCooldown() {
        return false;
    }

    /**
     * 候选位置是否需要"精确校验"：HANDHELD 模式免校验，BLOCKLIST 模式需要
     * ScanCache 对每个 pos 走 {@link #canIterationBlockPos} 二次校验。
     */
    @Override
    protected boolean iterationPositionsAreExactCandidates() {
        return Configs.Bedding.BEDDING_BLOCK_MODE.getOptionListValue() != FillBlockModeType.HANDHELD;
    }

    // ====================== Module 钩子：候选位置迭代器 ======================

    /**
     * 返回本 tick 候选 source 位置序列。
     *
     * <p>实现要点：
     * <ul>
     *   <li>底层交给 {@code ScanCache#iterable}，按"玩家距离游标"产出位置（远近交替）。</li>
     *   <li>两层过滤：flagPredicate（reach + selection 快过滤）→ exactPredicate（动态模式现场验证 / CUSTOM 模式 FilterUtils）。</li>
     *   <li>扫描盒 box 在配置变化或玩家进入新 box 时通过 {@link #resetBeddingScan} 重置。</li>
     *   <li>动态模式下若 box 哈希变化，在此处补一次 {@link #scanDynamicBlocks()}。</li>
     * </ul>
     */
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
            // 仅在确实没扫过（hash=MIN_VALUE）或 box 真的变了时才重扫；
            // 否则在 beddingScanBox==null 场景下会与 refreshActiveSourceFilters 的兜底扫描互踢形成死循环。
            if (this.dynamicScanBoxHash == Integer.MIN_VALUE
                    || this.beddingScanBox == null
                    || this.dynamicScanBoxHash != this.scanBoxHash(this.beddingScanBox)) {
                this.scanDynamicBlocks();
            }
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

    /**
     * 构造 ScanCache 迭代器：
     * <ul>
     *   <li>{@link ScanIntent}：PLACE_IN_AIR 时用 CUSTOM（任意方块），否则用 FILL（仅空气/液体）。</li>
     *   <li>exactPredicate：每个候选 pos 二次校验，含动态模式现场验证。</li>
     *   <li>flagPredicate：reach + selection 快过滤。</li>
     * </ul>
     */
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

    /** 候选位置快过滤：reach + selection 都通过。 */
    private boolean isBeddingCandidatePreFilter(
            BlockPos blockPos,
            Predicate<BlockPos> reachPredicate,
            Predicate<BlockPos> selectionPredicate
    ) {
        return reachPredicate.test(blockPos)
                && selectionPredicate.test(blockPos);
    }

    /** 初始化/重置扫描盒。 */
    private void resetBeddingScan(
            PrinterBox playerInteractionBox,
            List<PrinterBox> sourceBoxes,
            int configHash
    ) {
        this.beddingScanBox = this.copyScanBox(playerInteractionBox);
        this.beddingScanBoxes = List.copyOf(sourceBoxes);
        this.beddingScanConfigHash = configHash;
    }

    /** 清空扫描盒。 */
    private void clearBeddingTargets() {
        this.beddingScanBox = null;
        this.beddingScanBoxes = List.of();
    }

    /** 浅拷贝 PrinterBox，避免外部修改污染缓存。 */
    private PrinterBox copyScanBox(PrinterBox box) {
        return new PrinterBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    /**
     * 扫描配置哈希：所有可能影响"哪些位置该被扫 / 哪些 source 算 source"的字段。
     * 字段变化时由 {@link #preprocess()} 触发重扫。
     */
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

    // ====================== Module 钩子：精确候选二次校验 ======================

    /**
     * 当 {@link #iterationPositionsAreExactCandidates()} = true 时被 ScanCache 调用。
     * 统一走 {@link #isBeddingSource(BlockPos)} 路径，确保动态模式获得现场验证；
     * HANDHELD 模式额外校验主手非空。
     */
    @Override
    public boolean canIterationBlockPos(BlockPos blockPos) {
        if (!this.isBeddingSource(blockPos)) {
            return false;
        }
        if (Configs.Bedding.BEDDING_BLOCK_MODE.getOptionListValue() == FillBlockModeType.HANDHELD) {
            ItemStack heldStack = player.getMainHandItem();
            return !heldStack.isEmpty() && heldStack.getCount() > 0;
        }
        return true;
    }

    // ====================== Module 钩子：executeIteration ======================

    /**
     * 对单个 source 位置执行铺盖：校验 → 物品准备 → 决策放置面 → 提交点击 → 冷却。
     *
     * <p>关键校验：
     * <ol>
     *   <li>source 仍为有效 source（含"上方已铺盖"检查）。</li>
     *   <li>target 为空气/液体。</li>
     *   <li>target 正上方无方块（防御性，避免连续垂直堆叠与现有结构冲突）。</li>
     *   <li>下落方块支撑检查。</li>
     * </ol>
     *
     * <p>放置面决策见 {@link #getBeddingPlacementSide}；
     * 实际点击由 {@link ActionManager#queueClick} 入队，{@code setLook} + {@code sendQueue} 发送。
     */
    @Override
    protected void executeIteration(BlockPos sourcePos, AtomicReference<Boolean> skipIteration) {
        if (this.level == null || this.player == null || sourcePos == null) {
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        if (!this.isBeddingSource(sourcePos)) {
            setIterationConsumedEffectiveExecution(false);
            return;
        }
        BlockPos targetPos = sourcePos.above();
        BlockState targetState = this.level.getBlockState(targetPos);
        if (!targetState.isAir() && !(targetState.getBlock() instanceof LiquidBlock)) {
            setIterationConsumedEffectiveExecution(false);
            return;
        }

        // 防御性：target 正上方若已有方块，此处存在垂直堆叠结构，跳过。
        BlockState aboveTargetState = this.level.getBlockState(targetPos.above());
        if (!aboveTargetState.isAir() && !(aboveTargetState.getBlock() instanceof LiquidBlock)) {
            setIterationConsumedEffectiveExecution(false);
            return;
        }

        // 下落方块（如沙/砾石）若 source 顶面 isFree，无支撑则提示用户
        if (Configs.Placement.FALLING_CHECK.getBooleanValue()
                && player.getMainHandItem().getItem() instanceof BlockItem item
                && item.getBlock() instanceof FallingBlock block
                && FallingBlock.isFree(this.level.getBlockState(sourcePos))
        ) {
            HudStatsManager.INSTANCE.recordDeferred(HudStatsManager.Mode.BEDDING, "下落方块无支撑");
            MessageUtils.setOverlayMessage(I18n.FALLING_BLOCK_NO_SUPPORT.getName(block.getName().getString()));
            return;
        }

        // 物品准备：BLOCKLIST 模式需切到铺盖物品；HANDHELD 模式直接用主手
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
        // PLACE_IN_AIR = true → 点击 targetPos 自己；否则点击 targetPos 的某个邻居
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
        // 成功路径：HUD 状态 + source 位置冷却
        HudStatsManager.INSTANCE.trackExpectedBlockChange(HudStatsManager.Mode.BEDDING, targetPos, targetState);
        HudStatsManager.INSTANCE.recordRateUnit(HudStatsManager.Mode.BEDDING, 1);
        HudStatsManager.INSTANCE.recordStatus(HudStatsManager.Mode.BEDDING, "运行中");
        this.setBlockPosCooldown(sourcePos, ConfigUtils.getPlaceCooldown());
    }

    // ====================== 放置面决策 ======================

    /**
     * 决定玩家在 targetPos 放置铺盖方块时应点击哪个邻居方块、面朝哪面。
     *
     * <p>决策优先级：
     * <ol>
     *   <li>用户配置了 BEDDING_BLOCK_FACING → 强制使用。</li>
     *   <li>PLACE_IN_AIR = true → 配置或玩家当前朝向。</li>
     *   <li>默认 → 按 {@link #BEDDING_SIDE_ORDER} 顺序找第一个可点击且不可替换的邻居面。</li>
     * </ol>
     *
     * <p>返回值是"被点击邻居相对 targetPos 的方向"（side = 邻居相对 targetPos），
     * 实际 queueClick 传入的 clickSide 是 {@code side.getOpposite()}（玩家入射方向）。
     */
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

    /** BEDDING_BLOCK_FACING 配置 → Direction，NONE 返 null。 */
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

    /**
     * 检查 targetPos 在指定 side 方向的邻居方块是否"可被点击且不可替换"：
     * {@code canBeClicked}（outlineShape 非空）+ 非 {@code isReplaceable}（避免点空气/草/花）。
     */
    private boolean isValidBeddingPlacementSide(BlockPos targetPos, Direction side) {
        BlockPos neighborPos = targetPos.relative(side);
        BlockState neighborState = this.level.getBlockState(neighborPos);
        return PrinterUtils.canBeClicked(this.level, neighborPos) && !BlockUtils.isReplaceable(neighborState);
    }

    // ====================== 源方块判定 ======================

    /**
     * 判定入口（BlockPos 粒度，判断上/下半砖）。
     *
     * <p>对所有模式先做"上方已铺盖"检查：{@code pos.above()} 若非空气/液体，
     * 说明该列已完成铺盖，直接视为非 source，杜绝 cooldown 过期或跑远回来后的重复触发。
     *
     * <p>动态模式额外做"现场验证"：直接调 {@link BlockUtils#isMobSpawnGround}，
     * 避免上半砖/下半砖因共享 {@link Block} 实例而误判。
     * CUSTOM 模式委托给 {@link #isBeddingSource(BlockState)} 走过滤器匹配。
     */
    private boolean isBeddingSource(BlockPos blockPos) {
        if (this.level == null || blockPos == null) {
            return false;
        }

        BlockState aboveState = this.level.getBlockState(blockPos.above());
        if (!aboveState.isAir() && !(aboveState.getBlock() instanceof LiquidBlock)) {
            return false;
        }

        if (this.currentSourceMode == BeddingSourceModeType.MOB_SPAWNABLE) {
            return BlockUtils.isMobSpawnGround(this.level, blockPos);
        }
        if (this.currentSourceMode == BeddingSourceModeType.MOB_NOT_SPAWNABLE) {
            return !BlockUtils.isMobSpawnGround(this.level, blockPos);
        }

        return this.isBeddingSource(this.level.getBlockState(blockPos));
    }

    /**
     * BlockState 粒度的源判定：按当前 mode 显式分枝。
     *
     * <p>动态模式仅作 ScanCache flag 阶段的 Block 粒度粗筛（精确判定必须走 BlockPos 版本
     * 现场执行 {@link BlockUtils#isMobSpawnGround}），CUSTOM 模式走 {@link FilterUtils#matchName}。
     */
    private boolean isBeddingSource(BlockState state) {
        Block block = state.getBlock();
        if (this.currentSourceMode == BeddingSourceModeType.MOB_SPAWNABLE) {
            return this.dynamicSpawnableBlocks.contains(block);
        }
        if (this.currentSourceMode == BeddingSourceModeType.MOB_NOT_SPAWNABLE) {
            return this.dynamicNotSpawnableBlocks.contains(block);
        }
        // CUSTOM 模式
        if (this.sourceFilters.length == 0) return false;
        for (String filter : this.sourceFilters) {
            if (FilterUtils.matchName(filter, state)) {
                return true;
            }
        }
        return false;
    }

}
