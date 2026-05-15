package com.rokid.glass.hiddenrisk;

public class NativeInferenceStats
{
    public final int backendId;
    public final String backendName;
    public final int gpuProfileId;
    public final String gpuProfileName;
    public final int targetSize;
    public final String deviceName;
    public final int imageWidth;
    public final int imageHeight;
    public final long inferenceTimeMs;
    public final String errorStage;
    public final int errorCode;
    public final String errorMessage;
    public final int preLimitDetectionCount;
    public final int detectionCount;
    public final DetectionResult[] detections;

    public NativeInferenceStats(
            int backendId,
            String backendName,
            int gpuProfileId,
            String gpuProfileName,
            int targetSize,
            String deviceName,
            int imageWidth,
            int imageHeight,
            long inferenceTimeMs,
            String errorStage,
            int errorCode,
            String errorMessage,
            int preLimitDetectionCount,
            int detectionCount,
            DetectionResult[] detections)
    {
        this.backendId = backendId;
        this.backendName = backendName;
        this.gpuProfileId = gpuProfileId;
        this.gpuProfileName = gpuProfileName;
        this.targetSize = targetSize;
        this.deviceName = deviceName;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.inferenceTimeMs = inferenceTimeMs;
        this.errorStage = errorStage;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.preLimitDetectionCount = preLimitDetectionCount;
        this.detectionCount = detectionCount;
        this.detections = detections != null ? detections : new DetectionResult[0];
    }
}
