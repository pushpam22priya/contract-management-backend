package com.costacloud.contractmanagement.config;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import io.minio.*;
import io.minio.errors.*;
import io.minio.messages.Part;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * Extends MinioAsyncClient (which extends S3Base) so we can access the protected
 * multipart upload methods and expose them as public synchronous wrappers.
 */
public class CustomMinioClient extends MinioAsyncClient {

    protected CustomMinioClient(MinioAsyncClient client) {
        super(client);
    }

    public String startMultipartUpload(String bucket, String object)
            throws ServerException, InsufficientDataException, ErrorResponseException,
            IOException, NoSuchAlgorithmException, InvalidKeyException,
            InvalidResponseException, XmlParserException, InternalException {
        Multimap<String, String> headers = HashMultimap.create();
        headers.put("Content-Type", "application/pdf");
        CreateMultipartUploadResponse response =
                super.createMultipartUpload(bucket, null, object, headers, HashMultimap.create());
        return response.result().uploadId();
    }

    public void finishMultipartUpload(String bucket, String object, String uploadId, Part[] parts)
            throws ServerException, InsufficientDataException, ErrorResponseException,
            IOException, NoSuchAlgorithmException, InvalidKeyException,
            InvalidResponseException, XmlParserException, InternalException {
        super.completeMultipartUpload(
                bucket, null, object, uploadId, parts,
                HashMultimap.create(), HashMultimap.create());
    }

    public void cancelMultipartUpload(String bucket, String object, String uploadId)
            throws ServerException, InsufficientDataException, ErrorResponseException,
            IOException, NoSuchAlgorithmException, InvalidKeyException,
            InvalidResponseException, XmlParserException, InternalException {
        super.abortMultipartUpload(
                bucket, null, object, uploadId,
                HashMultimap.create(), HashMultimap.create());
    }
}
