package barcode.phomate.global.util;

import javax.imageio.*;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;

public class ImageResizeUtil {

    private ImageResizeUtil() {}

    public static byte[] toJpgResized(byte[] originalBytes, int targetShortSide, float quality) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(originalBytes));
        if (src == null) {
            throw new IOException("Unsupported image format for ImageIO decoding.");
        }

        int w = src.getWidth();
        int h = src.getHeight();

        int newW, newH;
        if (w <= h) {
            newW = targetShortSide;
            newH = (int) ((double) h * targetShortSide / w);
        } else {
            newH = targetShortSide;
            newW = (int) ((double) w * targetShortSide / h);
        }

        BufferedImage rgb = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, newW, newH, null);
        g.dispose();

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);

        ImageOutputStream ios = ImageIO.createImageOutputStream(os);
        writer.setOutput(ios);
        writer.write(null, new IIOImage(rgb, null, null), param);

        ios.close();
        writer.dispose();
        return os.toByteArray();
    }
}
