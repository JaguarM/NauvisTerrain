package com.jaguarm.nauvisterrain.noise;

import com.jaguarm.nauvisterrain.noise.NoiseProgram.Prototype;

import java.awt.image.BufferedImage;

/**
 * A picture of the map as Factorio's map preview draws it: one tile to a pixel, north up, each tile
 * in its map colour, cliffs, rocks and ores over the tiles, trees in the chart's translucent green.
 */
final class MapPreview {
    private MapPreview() {
    }

    static BufferedImage render(Terrain terrain, int centreX, int centreY, int size) {
        int x0 = centreX - size / 2;
        int y0 = centreY - size / 2;
        Terrain.Area area = terrain.area(x0, y0, size, size);
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, area.tile(x0 + x, y0 + y).mapColor());
            }
        }
        int tree = terrain.evaluator.program.chartColors.get("tree");
        for (Terrain.Placed p : area.entities) {
            Prototype proto = p.prototype();
            int px = p.x() - x0;
            int py = p.y() - y0;
            if (proto.type().equals("tree")) {
                image.setRGB(px, py, blend(image.getRGB(px, py), tree));
            } else if (proto.mapColor() >= 0) {
                image.setRGB(px, py, proto.mapColor());
            }
        }
        int cliff = terrain.evaluator.program.cliff.mapColor();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int level = area.cliffLevel(x0 + x, y0 + y);
                boolean edge = level != area.cliffLevel(x0 + x + 1, y0 + y) || level != area.cliffLevel(x0 + x, y0 + y + 1);
                if (edge && area.cliffiness(x0 + x, y0 + y) > 0.5) {
                    image.setRGB(x, y, cliff);
                }
            }
        }
        return image;
    }

    private static int blend(int under, int argb) {
        double alpha = (argb >>> 24) / 255.0;
        int r = (int) Math.round(((under >> 16) & 255) * (1 - alpha) + ((argb >> 16) & 255) * alpha);
        int g = (int) Math.round(((under >> 8) & 255) * (1 - alpha) + ((argb >> 8) & 255) * alpha);
        int b = (int) Math.round((under & 255) * (1 - alpha) + (argb & 255) * alpha);
        return r << 16 | g << 8 | b;
    }
}
