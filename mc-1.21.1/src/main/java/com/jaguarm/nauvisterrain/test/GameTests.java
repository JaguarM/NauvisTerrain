package com.jaguarm.nauvisterrain.test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * One mod's gametests, each a body handed to {@link #add}.
 *
 * <pre>
 * static void register(IEventBus bus) {
 *     GameTests tests = new GameTests(MODID, bus);
 *     tests.add("assembler_crafts", 100, helper -> { ... });
 * }
 * </pre>
 *
 * A 1.21.1 test is a {@link TestFunction}, which {@link #functions} hands the game. Every test runs
 * in the structure {@code <modid>:empty}, one block, which a gametest server in development reads
 * from {@code gameteststructures/empty.snbt}. The tests are registered only when a run's
 * {@code neoforge.enabledGameTestNamespaces} names the mod, or names none.
 */
public final class GameTests {
    private static final List<TestFunction> FUNCTIONS = new ArrayList<>();

    private final String modid;

    public GameTests(String modid, IEventBus modEventBus) {
        this.modid = modid;
        modEventBus.addListener(RegisterGameTestsEvent.class, event -> event.register(GameTests.class));
    }

    public void add(String name, int maxTicks, Consumer<GameTestHelper> body) {
        FUNCTIONS.add(new TestFunction(modid, modid + "." + name, modid + ":empty", maxTicks, 0, true, body));
    }

    @GameTestGenerator
    public static Collection<TestFunction> functions() {
        return FUNCTIONS;
    }
}
