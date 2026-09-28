package com.berg.orbis.dem;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes a raster tile whatever its container: PNG and JPEG through ImageIO, WebP through the TwelveMonkeys
 * reader (Java has no WebP support of its own, and Mapterhorn serves its terrain as lossless WebP).
 *
 * The reader's jars ship whole in the mod jar ({@code orbis-libs/}) and are loaded by a class loader of their own
 * whose parent is the platform loader: it sees the JDK's javax.imageio and nothing of the game or other mods.
 * With the classes unpacked onto the shared class path, YACL's bundled 3.12.0 won instead, and its lossless decoder
 * scrambled a Mapterhorn tile near Bergen into +-32768 m (a 2 km stone pillar).
 */
public final class TileImages {

    private static final String WEBP_SPI = "com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi";
    private static volatile ImageReaderSpi webp;

    private TileImages() {
    }

    private static ImageReaderSpi webp() throws IOException {
        ImageReaderSpi spi = webp;
        if (spi != null) return spi;
        synchronized (TileImages.class) {
            if (webp != null) return webp;
            try (InputStream index = TileImages.class.getResourceAsStream("/orbis-libs/index.txt")) {
                if (index == null) throw new IOException("orbis-libs/index.txt is missing from the mod jar");
                Path dir = Files.createTempDirectory("orbis-webp");
                dir.toFile().deleteOnExit();
                List<URL> urls = new ArrayList<>();
                for (String name : new String(index.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                    name = name.trim();
                    if (name.isEmpty()) continue;
                    Path target = dir.resolve(name);
                    try (InputStream in = TileImages.class.getResourceAsStream("/orbis-libs/" + name)) {
                        if (in == null) throw new IOException("orbis-libs/" + name + " is missing from the mod jar");
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    target.toFile().deleteOnExit();
                    urls.add(target.toUri().toURL());
                }
                URLClassLoader loader = new URLClassLoader("orbis-webp", urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
                webp = (ImageReaderSpi) Class.forName(WEBP_SPI, true, loader).getDeclaredConstructor().newInstance();
                return webp;
            } catch (ReflectiveOperationException | ClassCastException e) {
                throw new IOException("Cannot load the WebP decoder: " + e, e);
            }
        }
    }

    /**
     * The image's pixels as 0xRRGGBB, row by row, w wide. One Java2D blit converts the whole image with its native
     * loops; BufferedImage.getRGB pixel by pixel went through the colour model for every pixel and was 8% of all
     * pre-generation time for aerial photos alone.
     */
    public static int[] rgbPixels(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        java.awt.image.WritableRaster ras = img.getRaster();
        // JPEG photos decode to packed B, G, R bytes: read them directly (the common case, and the fastest).
        if (img.getType() == BufferedImage.TYPE_3BYTE_BGR && ras.getSampleModelTranslateX() == 0 && ras.getSampleModelTranslateY() == 0
                && ras.getDataBuffer() instanceof java.awt.image.DataBufferByte db && db.getNumBanks() == 1 && db.getOffset() == 0
                && ras.getSampleModel() instanceof java.awt.image.PixelInterleavedSampleModel sm && sm.getPixelStride() == 3
                && sm.getScanlineStride() == w * 3) {
            byte[] b = db.getData();
            int[] out = new int[w * h];
            for (int i = 0, j = 0; i < out.length; i++, j += 3) {
                out[i] = (b[j + 2] & 0xFF) << 16 | (b[j + 1] & 0xFF) << 8 | (b[j] & 0xFF);
            }
            return out;
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        try {
            g.setComposite(java.awt.AlphaComposite.Src);
            g.drawImage(img, 0, 0, null);
        } finally {
            g.dispose();
        }
        return ((java.awt.image.DataBufferInt) out.getRaster().getDataBuffer()).getData();
    }

    public static boolean isWebP(byte[] bytes) {
        return bytes.length > 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    public static BufferedImage decode(byte[] bytes) throws IOException {
        if (isWebP(bytes)) {
            ImageReader reader = webp().createReaderInstance(null);
            try (MemoryCacheImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                reader.setInput(in);
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            BufferedImage img = ImageIO.read(in);
            if (img == null) throw new IOException("Not a PNG/JPEG/WebP image (" + bytes.length + " bytes)");
            return img;
        }
    }
}
