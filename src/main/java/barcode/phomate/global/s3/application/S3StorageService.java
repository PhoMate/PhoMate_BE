package barcode.phomate.global.s3.application;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.List;

@Service
@RequiredArgsConstructor
public class S3StorageService {

    private final S3Client s3Client;

    @Value("${app.s3.bucket}")
    private String bucket;

    public void putBytes(String key, byte[] bytes, String contentType, String cacheControl) {
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .cacheControl(cacheControl)
                .build();

        s3Client.putObject(req, RequestBody.fromBytes(bytes));
    }

    public void deleteObject(String key) {
        if (key == null || key.isBlank() || "TEMP".equals(key)) return;
        }
    // 한개 삭제
    public void deleteOne(String key) {
        if (key == null || key.isBlank()) return;
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build());
    }

    // 여러개 삭제
    public void deleteMany(List<String> keys) {
        if (keys == null || keys.isEmpty()) return;

        List<ObjectIdentifier> objects = keys.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(k -> ObjectIdentifier.builder().key(k).build())
                .toList();

        if (objects.isEmpty()) return;

        DeleteObjectsRequest req = DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(Delete.builder().objects(objects).build())
                .build();

        s3Client.deleteObjects(req);
    }
}
