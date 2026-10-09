package com.jaguarm.nauvisterrain.test;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * A gametest of this mod's that needs state of its own; a test that is only a body is a lambda
 * handed to {@link GameTests#add}. {@link GameTests} registers the type and the instance from
 * one call and hands the codec and the name back here, so a subclass is its fields, a one-line
 * {@code X(Info info) { super(info); }} and {@code run}.
 */
public abstract class PackGameTest extends GameTestInstance {

    /** What a test is built from; the name is short so the constructor can be one line. */
    public record Info(TestData<Holder<TestEnvironmentDefinition<?>>> data) {}

    MapCodec<? extends GameTestInstance> codec;
    String name = "";

    protected PackGameTest(Info info) {
        super(info.data());
    }

    TestData<Holder<TestEnvironmentDefinition<?>>> data() {
        return info();
    }

    @Override
    public final MapCodec<? extends GameTestInstance> codec() {
        return codec;
    }

    @Override
    protected MutableComponent typeDescription() {
        return Component.literal(name.replace('_', ' '));
    }
}
