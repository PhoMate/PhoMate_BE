package barcode.phomate.global.util;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * EXIF 촬영일(DateTimeOriginal) 추출 유틸.
 * EXIF DateTimeOriginal은 타임존이 없는 "촬영 로컬 벽시계 시각"이므로
 * LocalDateTime(naive)으로 그대로 반환한다. (KST 해석은 호출부에서 수행)
 */
public final class ExifUtil {

    private static final DateTimeFormatter EXIF_FMT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

    private ExifUtil() {}

    public static Optional<LocalDateTime> extractShotAt(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) return Optional.empty();
        try {
            Metadata meta = ImageMetadataReader.readMetadata(new ByteArrayInputStream(imageBytes));
            ExifSubIFDDirectory dir = meta.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (dir == null) return Optional.empty();
            String s = dir.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
            if (s == null || s.isBlank()) return Optional.empty();
            return Optional.of(LocalDateTime.parse(s.trim(), EXIF_FMT));
        } catch (Exception e) {
            // 파싱 실패/EXIF 없음 → 촬영일 미확인
            return Optional.empty();
        }
    }
}
