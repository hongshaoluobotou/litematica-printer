package me.aleksilassila.litematica.printer.enums;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.ConfigOptionListEntry;

public enum BeddingSourceModeType implements ConfigOptionListEntry<BeddingSourceModeType> {
    /**
     * 使用玩家自定义的源方块名单
     */
    CUSTOM("beddingBlockSourceMode.custom"),
    /**
     * 运行时动态扫描选区，识别所有"可刷生方块"作为源方块
     */
    MOB_SPAWNABLE("beddingBlockSourceMode.mobSpawnable"),
    /**
     * 运行时动态扫描选区，识别所有"不可刷生方块"作为源方块
     */
    MOB_NOT_SPAWNABLE("beddingBlockSourceMode.mobNotSpawnable");

    private final I18n i18n;

    BeddingSourceModeType(String translateKey) {
        this.i18n = I18n.of(translateKey);
    }

    @Override
    public I18n getI18n() {
        return i18n;
    }
}
