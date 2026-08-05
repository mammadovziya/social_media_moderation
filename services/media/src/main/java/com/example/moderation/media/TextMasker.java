package com.example.moderation.media;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Produces a deterministic background fingerprint input by replacing OCR boxes. */
@Component
class TextMasker {
    MaskResult mask(BufferedImage source, List<OcrSpan> spans) {
        if (spans.isEmpty()) {
            return new MaskResult(source, false, 0);
        }

        BufferedImage masked = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = masked.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }

        int padding = Math.min(
                16, Math.max(2, Math.min(source.getWidth(), source.getHeight()) / 200));
        List<MaskRegion> regions = new ArrayList<>();
        for (OcrSpan span : spans) {
            int left = Math.max(0, span.x() - padding);
            int top = Math.max(0, span.y() - padding);
            int right = Math.min(source.getWidth(), span.x() + span.width() + padding);
            int bottom = Math.min(source.getHeight(), span.y() + span.height() + padding);
            if (right <= left || bottom <= top) {
                continue;
            }
            int color = borderAverage(source, left, top, right, bottom);
            for (int y = top; y < bottom; y++) {
                for (int x = left; x < right; x++) {
                    masked.setRGB(x, y, color);
                }
            }
            regions.add(new MaskRegion(left, top, right - left, bottom - top));
        }
        return regions.isEmpty()
                ? new MaskResult(source, false, 0)
                : new MaskResult(masked, true, regions.size());
    }

    private static int borderAverage(
            BufferedImage source, int left, int top, int right, int bottom) {
        long red = 0;
        long green = 0;
        long blue = 0;
        long count = 0;

        int above = top - 1;
        int below = bottom;
        for (int x = left; x < right; x++) {
            if (above >= 0) {
                int rgb = source.getRGB(x, above);
                red += (rgb >>> 16) & 0xff;
                green += (rgb >>> 8) & 0xff;
                blue += rgb & 0xff;
                count++;
            }
            if (below < source.getHeight()) {
                int rgb = source.getRGB(x, below);
                red += (rgb >>> 16) & 0xff;
                green += (rgb >>> 8) & 0xff;
                blue += rgb & 0xff;
                count++;
            }
        }

        int before = left - 1;
        int after = right;
        for (int y = top; y < bottom; y++) {
            if (before >= 0) {
                int rgb = source.getRGB(before, y);
                red += (rgb >>> 16) & 0xff;
                green += (rgb >>> 8) & 0xff;
                blue += rgb & 0xff;
                count++;
            }
            if (after < source.getWidth()) {
                int rgb = source.getRGB(after, y);
                red += (rgb >>> 16) & 0xff;
                green += (rgb >>> 8) & 0xff;
                blue += rgb & 0xff;
                count++;
            }
        }

        if (count == 0) {
            int[] corners = {
                source.getRGB(0, 0),
                source.getRGB(source.getWidth() - 1, 0),
                source.getRGB(0, source.getHeight() - 1),
                source.getRGB(source.getWidth() - 1, source.getHeight() - 1)
            };
            for (int rgb : corners) {
                red += (rgb >>> 16) & 0xff;
                green += (rgb >>> 8) & 0xff;
                blue += rgb & 0xff;
                count++;
            }
        }

        int averageRed = (int) (red / count);
        int averageGreen = (int) (green / count);
        int averageBlue = (int) (blue / count);
        return (averageRed << 16) | (averageGreen << 8) | averageBlue;
    }

    record MaskResult(BufferedImage image, boolean applied, int regionCount) {}

    private record MaskRegion(int x, int y, int width, int height) {}
}
