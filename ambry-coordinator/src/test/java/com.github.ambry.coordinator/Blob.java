package com.github.ambry.coordinator;

import com.github.ambry.messageformat.BlobOutput;
import com.github.ambry.messageformat.BlobProperties;

import java.nio.ByteBuffer;

/**
 * Blob stored in Ambry. Blob consists of properties, user metadata, and data.
 */
public class Blob {
  final BlobProperties blobProperties;
  final ByteBuffer userMetadata;
  final BlobOutput blobOutput;

  public Blob(BlobProperties blobProperties, ByteBuffer userMetadata, BlobOutput blobOutput) {
    this.blobProperties = blobProperties;
    this.userMetadata = userMetadata;
    this.blobOutput = blobOutput;
  }

  public BlobProperties getBlobProperties() {
    return blobProperties;
  }

  public ByteBuffer getUserMetadata() {
    return userMetadata;
  }

  public BlobOutput getBlobOutput() {
    return blobOutput;
  }
}