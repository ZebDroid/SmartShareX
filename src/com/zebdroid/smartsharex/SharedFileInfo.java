package com.zebdroid.smartsharex;

/**
 * Plain data holder for one file received via ACTION_SEND / ACTION_SEND_MULTIPLE.
 * Kept dependency-free (no Android Parcelable) since it only ever travels
 * in-process through the static ShareDataHolder — never across a process
 * boundary, so Parcelable overhead isn't needed.
 */
public class SharedFileInfo {

  public final String uri;
  public final String fileName;
  public final String mimeType;
  public final long sizeBytes;

  public SharedFileInfo(String uri, String fileName, String mimeType, long sizeBytes) {
    this.uri = uri == null ? "" : uri;
    this.fileName = fileName == null ? "" : fileName;
    this.mimeType = mimeType == null ? "*/*" : mimeType;
    this.sizeBytes = sizeBytes;
  }
}
