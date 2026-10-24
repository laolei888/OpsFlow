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
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.List;

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
}
