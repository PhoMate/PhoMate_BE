package barcode.phomate.global.util;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

// 최종 확장자 jpg로 통일
// 투명 배경 png도 배경 흰색으로 깔아서 변환

public class ImageJpegUtil {

    private ImageJpegUtil() {}

    public static byte[] toJpg(byte[] anyImageBytes, float quality) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(anyImageBytes));
        if (src == null) {
            throw new IOException("Unsupported image format for ImageIO decoding.");
        }

        // JPEG는 알파 지원 안 하니까 RGB로 바꾸면서 배경 흰색으로
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, src.getWidth(), src.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG ImageWriter available.");
        }

        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);

        try (ImageOutputStream ios = ImageIO.createImageOutputStream(os)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }

        return os.toByteArray();
    }
}
