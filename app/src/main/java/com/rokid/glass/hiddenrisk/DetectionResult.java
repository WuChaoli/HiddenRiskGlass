package com.rokid.glass.hiddenrisk;

public class DetectionResult
{
    public final String label;
    public final float x;
    public final float y;
    public final float width;
    public final float height;
    public final float score;
    public final int labelId;

    public DetectionResult(String label, float x, float y, float width, float height, float score, int labelId)
    {
        this.label = label;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.score = score;
        this.labelId = labelId;
    }
}
