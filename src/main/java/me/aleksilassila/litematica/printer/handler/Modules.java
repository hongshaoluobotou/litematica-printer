package me.aleksilassila.litematica.printer.handler;

import com.google.common.collect.ImmutableList;
import me.aleksilassila.litematica.printer.handler.handlers.BedrockHandler;
import me.aleksilassila.litematica.printer.handler.handlers.FillHandler;
import me.aleksilassila.litematica.printer.handler.handlers.FluidHandler;
import me.aleksilassila.litematica.printer.handler.handlers.GuiHandler;
import me.aleksilassila.litematica.printer.handler.handlers.MineHandler;
import me.aleksilassila.litematica.printer.handler.handlers.BeddingHandler;
import me.aleksilassila.litematica.printer.handler.handlers.PrintHandler;

public final class Modules {
    public static final GuiHandler GUI = new GuiHandler();
    public static final PrintHandler PRINT = new PrintHandler();
    public static final FillHandler FILL = new FillHandler();
    public static final MineHandler MINE = new MineHandler();
    public static final FluidHandler FLUID = new FluidHandler();
    public static final BedrockHandler BEDROCK = new BedrockHandler();
    public static final BeddingHandler BEDDING = new BeddingHandler();

    public static final ImmutableList<Module> VALUES = ImmutableList.of(
            GUI, MINE, FLUID, PRINT, FILL, BEDROCK, BEDDING
    );

    private Modules() {
    }
}
