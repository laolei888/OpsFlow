package com.opsflow.attachment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3AttachmentObjectStoreTest {
    @Mock private S3Client client;
    @Mock private S3Presigner presigner;

    private S3AttachmentObjectStore store;

    @BeforeEach
    void setUp() {
        AttachmentStorageProperties properties = new AttachmentStorageProperties();
        properties.getS3().setBucket("opsflow-test");
        store = new S3AttachmentObjectStore(client, presigner, properties);
    }

    @Test
    void removesCopiedTargetWhenSourceDeletionFails() {
        String sourceKey = "quarantine/1/7/file.txt";
        String targetKey = "clean/1/7/file.txt";
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("missing").build());
        when(client.copyObject(any(CopyObjectRequest.class)))
                .thenReturn(CopyObjectResponse.builder().build());
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(invocation -> {
            DeleteObjectRequest request = invocation.getArgument(0);
            if (sourceKey.equals(request.key())) {
                throw S3Exception.builder().statusCode(500).message("source delete failed").build();
            }
            return DeleteObjectResponse.builder().build();
        });

        assertThrows(AttachmentStorageException.class, () -> store.move(sourceKey, targetKey));

        ArgumentCaptor<DeleteObjectRequest> requests = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client, times(2)).deleteObject(requests.capture());
        assertEquals(List.of(sourceKey, targetKey),
                requests.getAllValues().stream().map(DeleteObjectRequest::key).toList());
    }

    @Test
    void supportsMultipartLifecycleAndObjectMetadata() {
        when(client.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
                .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-1").build());
        when(client.listParts(any(ListPartsRequest.class))).thenReturn(ListPartsResponse.builder()
                .isTruncated(false)
                .parts(Part.builder().partNumber(1).eTag("etag-1").size(8L).build(),
                        Part.builder().partNumber(2).eTag("etag-2").size(3L).build())
                .build());
        when(client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
                .thenReturn(CompleteMultipartUploadResponse.builder().eTag("final-etag").versionId("v1").build());
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(11L).eTag("final-etag").versionId("v1").build());
        when(client.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
                .thenReturn(AbortMultipartUploadResponse.builder().build());

        MultipartUploadObject upload = store.createMultipartUpload("quarantine/1/9/file.txt",
                "text/plain", Map.of("tenant-id", "1"));
        List<MultipartUploadedPart> parts = store.listMultipartParts(
                "quarantine/1/9/file.txt", upload.uploadId());
        StoredAttachmentObject completed = store.completeMultipartUpload("quarantine/1/9/file.txt",
                upload.uploadId(), parts.stream()
                        .map(part -> new CompletedMultipartPart(part.partNumber(), part.etag())).toList());
        StoredObjectMetadata metadata = store.metadata("quarantine/1/9/file.txt");
        store.abortMultipartUpload("quarantine/1/9/file.txt", upload.uploadId());

        assertEquals("upload-1", upload.uploadId());
        assertEquals(2, parts.size());
        assertEquals("v1", completed.versionId());
        assertEquals(11L, metadata.sizeBytes());
    }

    @Test
    void inventoriesAndDeletesSpecificObjectVersions() {
        when(client.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(ListObjectVersionsResponse.builder()
                        .isTruncated(false)
                        .versions(ObjectVersion.builder().key("clean/1/7/file.txt").versionId("v2")
                                        .isLatest(true).size(12L).eTag("new").build(),
                                ObjectVersion.builder().key("clean/1/7/file.txt").versionId("v1")
                                        .isLatest(false).size(10L).eTag("old").build())
                        .build());
        when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        StoredObjectInventory inventory = store.inventory("clean/1/", 10);
        store.deleteVersion("clean/1/7/file.txt", "v1");

        assertEquals(2, inventory.items().size());
        assertEquals("v2", inventory.items().get(0).versionId());
        ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).deleteObject(request.capture());
        assertEquals("v1", request.getValue().versionId());
    }
}
