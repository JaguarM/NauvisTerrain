package com.jaguarm.nauvisterrain.test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import com.jaguarm.nauvisterrain.test.PackGameTest.Info;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * One mod's gametests. A 26.2 test is two registrations - the {@code MapCodec} type and the
 * instance - and a missing type passes every test and breaks a client; {@link #add} does both.
 *
 * <pre>
 * static void register(IEventBus bus) {
 *     GameTests tests = new GameTests(MODID, bus);
 *     tests.add("assembler_crafts", 100, PADDING, helper -> { ... });
 *     tests.add("pole_finds_a_machine", PoleFindsAMachineTest::new, 100, PADDING);
 *     tests.batch("alone");
 *     tests.add("research_command_moves_the_tree", 20, PADDING, helper -> { ... });
 * }
 * </pre>
 *
 * Every test runs in vanilla's {@code minecraft:empty} structure, a point, with {@code padding}
 * blocks of room around it. A batch is an environment of its own; batches run one after another,
 * so a test that must own the world's state goes in one alone. The tests are registered only when
 * a run's {@code neoforge.enabledGameTestNamespaces} names the mod, or names none.
 */
public final class GameTests {

    private static final Identifier EMPTY_STRUCTURE = Identifier.withDefaultNamespace("empty");

    private record Entry(String name, String batch, int maxTicks, int padding, boolean skyAccess,
            Function<Info, ? extends PackGameTest> create) {}

    private static final class BodyTest extends PackGameTest {
        private final Consumer<GameTestHelper> body;

        BodyTest(Info info, Consumer<GameTestHelper> body) {
            super(info);
            this.body = body;
        }

        @Override
        public void run(GameTestHelper helper) {
            body.accept(helper);
        }
    }

    private final String modid;
    private final DeferredRegister<MapCodec<? extends GameTestInstance>> types;
    private final List<Entry> entries = new ArrayList<>();
    private String batch = "default";

    public GameTests(String modid, IEventBus modEventBus) {
        this.modid = modid;
        this.types = DeferredRegister.create(Registries.TEST_INSTANCE_TYPE, modid);
        types.register(modEventBus);
        modEventBus.addListener(RegisterGameTestsEvent.class, this::registerTests);
    }

    /** Tests added after this run in a batch of their own, named {@code <modid>:<name>}. */
    public void batch(String name) {
        this.batch = name;
    }

    public void add(String name, int maxTicks, Consumer<GameTestHelper> body) {
        add(name, maxTicks, 0, false, body);
    }

    public void add(String name, int maxTicks, int padding, Consumer<GameTestHelper> body) {
        add(name, maxTicks, padding, false, body);
    }

    /** {@code skyAccess} clears the column above the test, for anything that reads the sky. */
    public void add(String name, int maxTicks, int padding, boolean skyAccess, Consumer<GameTestHelper> body) {
        add(name, info -> new BodyTest(info, body), maxTicks, padding, skyAccess);
    }

    public <T extends PackGameTest> void add(String name, Function<Info, T> factory, int maxTicks) {
        add(name, factory, maxTicks, 0, false);
    }

    public <T extends PackGameTest> void add(String name, Function<Info, T> factory, int maxTicks, int padding) {
        add(name, factory, maxTicks, padding, false);
    }

    @SuppressWarnings("unchecked")
    public <T extends PackGameTest> void add(String name, Function<Info, T> factory, int maxTicks, int padding,
            boolean skyAccess) {
        MapCodec<T>[] codec = new MapCodec[1];
        Function<Info, T> create = info -> {
            T test = factory.apply(info);
            test.codec = codec[0];
            test.name = name;
            return test;
        };
        codec[0] = RecordCodecBuilder.<T>mapCodec(i -> i.group(TestData.CODEC.forGetter((T test) -> test.data()))
                .apply(i, data -> create.apply(new Info(data))));
        types.register(name, () -> codec[0]);
        entries.add(new Entry(name, batch, maxTicks, padding, skyAccess, create));
    }

    private void registerTests(RegisterGameTestsEvent event) {
        String enabled = System.getProperty("neoforge.enabledGameTestNamespaces");
        if (enabled != null && !enabled.isBlank() && !List.of(enabled.split(",")).contains(modid)) {
            return;
        }
        Map<String, Holder<TestEnvironmentDefinition<?>>> batches = new HashMap<>();
        for (Entry entry : entries) {
            Holder<TestEnvironmentDefinition<?>> environment = batches.computeIfAbsent(entry.batch(),
                    b -> event.registerEnvironment(Identifier.fromNamespaceAndPath(modid, b),
                            new TestEnvironmentDefinition.AllOf(List.of())));
            TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(environment, Level.OVERWORLD, EMPTY_STRUCTURE,
                    entry.maxTicks(), 0, true, Rotation.NONE, false, 1, 1, entry.skyAccess(), entry.padding());
            event.registerTest(Identifier.fromNamespaceAndPath(modid, entry.name()), entry.create().apply(new Info(data)));
        }
    }
}
