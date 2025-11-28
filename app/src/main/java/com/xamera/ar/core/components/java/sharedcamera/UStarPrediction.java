package com.xamera.ar.core.components.java.sharedcamera;

/**
 * Simple data container for the prediction coming from
 * UStar_Cube_Prediction.txt.
 */
public final class UStarPrediction {
    public final int distanceMeters;        // clamped to [1,4]
    public final String orientationLabel;   // e.g., "North"
    public final float orientationAngleDeg; // yaw in degrees, 0 = North, 90 = East

    public UStarPrediction(int distanceMeters, String orientationLabel, float orientationAngleDeg) {
        this.distanceMeters = distanceMeters;
        this.orientationLabel = orientationLabel;
        this.orientationAngleDeg = orientationAngleDeg;
    }

    @Override
    public String toString() {
        return "UStarPrediction{distance=" + distanceMeters +
                "m, orientation='" + orientationLabel +
                "', angleDeg=" + orientationAngleDeg + "}";
    }
}