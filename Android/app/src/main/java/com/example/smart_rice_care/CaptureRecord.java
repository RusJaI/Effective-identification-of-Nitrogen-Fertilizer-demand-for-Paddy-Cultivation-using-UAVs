package com.example.smart_rice_care;

import java.io.File;

/** Durable metadata needed to retry an online field capture after connectivity returns. */
public final class CaptureRecord {
    public final String id;
    public final String imagePath;
    public final String requestId;
    public final String officerId;
    public final double latitude;
    public final double longitude;
    public final long timestamp;

    CaptureRecord(String id, String imagePath, String requestId, String officerId,
                  double latitude, double longitude, long timestamp) {
        this.id = id;
        this.imagePath = imagePath;
        this.requestId = requestId;
        this.officerId = officerId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.timestamp = timestamp;
    }

    public File getImageFile() {
        return new File(imagePath);
    }
}
