package com.jaguarm.nauvisterrain.client;

import com.jaguarm.nauvisterrain.noise.NoiseProgram;
import com.jaguarm.nauvisterrain.noise.NoiseProgram.Preset;
import com.jaguarm.nauvisterrain.noise.NoiseProgram.Slider;
import com.jaguarm.nauvisterrain.world.Nauvis;
import com.jaguarm.nauvisterrain.world.NauvisGenerator;
import com.jaguarm.nauvisterrain.world.NauvisMap;
import com.mojang.serialization.Codec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationContext;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Factorio's map generator screen for a Nauvis world: a preset, then each slider of each control in
 * Factorio's words and steps (docs/ARCHITECTURE.md, the world). Done writes them into the world's
 * generator settings.
 */
public final class NauvisMapScreen extends OptionsSubScreen {
    /** Factorio's steps for a frequency, size or richness: 17% to 600%. */
    private static final List<Double> FACTORS = List.of(1 / 6.0, 0.25, 1 / 3.0, 0.5, Math.sqrt(0.5), 1.0, Math.sqrt(2), 2.0, 3.0,
            4.0, 6.0);
    private static final List<Double> FACTORS_OR_NONE;
    private static final List<Double> BIASES;

    static {
        List<Double> none = new ArrayList<>(FACTORS);
        none.addFirst(0.0);
        FACTORS_OR_NONE = List.copyOf(none);
        List<Double> biases = new ArrayList<>();
        for (int i = -10; i <= 10; i++) {
            biases.add(i * 0.05);
        }
        BIASES = List.copyOf(biases);
    }

    private final CreateWorldScreen parent;
    private final NauvisGenerator generator;
    private final NoiseProgram program = Nauvis.program();
    private final Map<String, Double> sliders;
    private Map<String, String> properties;
    private double cliffSmoothing;
    private String preset;

    public NauvisMapScreen(CreateWorldScreen parent, WorldCreationContext context) {
        super(parent, Minecraft.getInstance().options, Component.translatable("nauvis_terrain.map.title"));
        this.parent = parent;
        if (!(context.selectedDimensions().overworld() instanceof NauvisGenerator nauvis)) {
            throw new IllegalStateException("the Nauvis map screen opened on a world that is not Nauvis");
        }
        this.generator = nauvis;
        NauvisMap map = nauvis.settings().map();
        this.sliders = new HashMap<>(map.sliders());
        this.properties = map.properties();
        this.cliffSmoothing = map.cliffSmoothing();
        this.preset = program.presets.stream().filter(p -> matches(p, map)).map(Preset::name).findFirst().orElse("");
    }

    @Override
    protected void addOptions() {
        if (list == null) {
            return;
        }
        List<String> names = new ArrayList<>(program.presets.stream().map(Preset::name).toList());
        if (!names.contains(preset)) {
            names.add(preset);
        }
        list.addBig(new OptionInstance<>("nauvis_terrain.map.preset", OptionInstance.noTooltip(),
                (caption, name) -> Component.translatable("options.generic_value", caption, presetTitle(name)),
                new OptionInstance.Enum<>(names, Codec.STRING), preset, this::choose));
        for (NoiseProgram.Control control : program.controls) {
            List<OptionInstance<?>> row = new ArrayList<>();
            for (Slider slider : control.sliders()) {
                row.add(slider(control, slider));
            }
            for (int i = 0; i < row.size(); i += 2) {
                list.addSmall(i + 1 < row.size() ? new OptionInstance<?>[]{row.get(i), row.get(i + 1)} : new OptionInstance<?>[]{row.get(i)});
            }
        }
    }

    private OptionInstance<Double> slider(NoiseProgram.Control control, Slider slider) {
        boolean bias = slider.key().endsWith(":bias");
        List<Double> steps = bias ? BIASES
                : slider.key().endsWith(":size") || slider.key().equals("cliff_frequency") ? FACTORS_OR_NONE : FACTORS;
        double current = sliders.getOrDefault(slider.key(), bias ? 0.0 : 1.0);
        double nearest = steps.stream().min((a, b) -> Double.compare(Math.abs(a - current), Math.abs(b - current))).orElseThrow();
        String caption = control.title() + " " + slider.label().toLowerCase(Locale.ROOT);
        return new OptionInstance<>(caption, value -> Tooltip.create(Component.literal(slider.tooltip())),
                (c, value) -> Component.translatable("options.generic_value", c, bias
                        ? Component.literal(String.format(Locale.ROOT, "%+.2f", value))
                        : value == 0 ? Component.translatable("nauvis_terrain.map.none")
                        : Component.literal(Math.round(value * 100) + "%")),
                new OptionInstance.SliderableEnum<>(steps, Codec.DOUBLE), nearest, value -> {
                    sliders.put(slider.key(), value);
                    preset = "";
                });
    }

    /** A preset's sliders and properties, everything else back to normal. */
    private void choose(String name) {
        program.presets.stream().filter(p -> p.name().equals(name)).findFirst().ifPresent(p -> {
            sliders.clear();
            sliders.putAll(p.sliders());
            properties = p.properties();
            cliffSmoothing = p.cliffSmoothing();
            preset = name;
            minecraft.execute(this::rebuildWidgets);
        });
    }

    private Component presetTitle(String name) {
        return program.presets.stream().filter(p -> p.name().equals(name)).findFirst()
                .<Component>map(p -> Component.literal(p.title()))
                .orElse(Component.translatable("nauvis_terrain.map.custom"));
    }

    private static boolean matches(Preset preset, NauvisMap map) {
        return preset.sliders().equals(map.sliders()) && preset.properties().equals(map.properties())
                && preset.cliffSmoothing() == map.cliffSmoothing();
    }

    @Override
    public void onClose() {
        if (list != null) {
            list.applyUnsavedChanges();
        }
        NauvisMap map = new NauvisMap(sliders, properties, cliffSmoothing);
        parent.getUiState().updateDimensions((registryAccess, dimensions) -> dimensions.replaceOverworldGenerator(registryAccess,
                new NauvisGenerator(generator.getBiomeSource(), generator.settings().withMap(map))));
        super.onClose();
    }
}
