package barcode.phomate.global.util;

import java.util.Locale;

public class ImageTypeUtil {

    private ImageTypeUtil() {}

    public static String extFromContentType(String contentType) {
        if (contentType == null) return null;
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/heic", "image/heif" -> "heic";
            default -> null;
        };
    }

    public static String safeExt(String contentType, String originalFilename) {
        String ext = extFromContentType(contentType);
        if (ext != null) return ext;

        if (originalFilename != null) {
            int idx = originalFilename.lastIndexOf('.');
            if (idx > -1 && idx < originalFilename.length() - 1) {
                String nameExt = originalFilename.substring(idx + 1).toLowerCase(Locale.ROOT);
                if (nameExt.length() <= 5) return nameExt;
            }
        }
        return "bin";
    }

    public static String normalizeContentType(String contentType, String ext) {
        if (contentType != null && contentType.startsWith("image/")) return contentType;
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "heic" -> "image/heic";
            default -> "application/octet-stream";
        };
    }
}

