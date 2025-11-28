package com.xamera.ar.core.components.java.sharedcamera;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Reads UStar_Cube_Prediction.txt via SAF Uri
 * and parses distance + orientation.
 */
public final class UStarPredictionReader {

    private static final String TAG = "UStarPredictionReader";

    private static final String DEFAULT_TEXT =
            "UStar UIOD Tag Features\n" +
                    "Prediction Date: N/A\n" +
                    "OpenCV Initialization Status: false\n" +
                    "Distance: 1M | Orientation: North";

    private UStarPredictionReader() { }

    public static UStarPrediction readFromUri(Context context, Uri uri) {
        String rawText = DEFAULT_TEXT;

        try {
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(context.getContentResolver().openInputStream(uri))
            );
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            br.close();
            String read = sb.toString().trim();
            if (!read.isEmpty()) {
                rawText = read;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading prediction file via SAF", e);
            rawText = DEFAULT_TEXT;
        }

        Integer distanceMeters = null;
        String orientationLabel = "North";

        // --- Prefer the "Distance: xM | Orientation: Y" line (last one wins) ---
        String[] lines = rawText.split("\\r?\\n");
        for (String line : lines) {
            String lower = line.toLowerCase();
            if (lower.contains("distance") && lower.contains("orientation")) {
                // e.g., "Distance: 4M | Orientation: Southwest"
                java.util.regex.Matcher pairMatcher = java.util.regex.Pattern
                        .compile("Distance:\\s*(\\d+)\\s*[mM].*?Orientation:\\s*([A-Za-z]+)",
                                java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(line);
                if (pairMatcher.find()) {
                    try {
                        distanceMeters = Integer.parseInt(pairMatcher.group(1));
                    } catch (NumberFormatException ignored) {}
                    orientationLabel = pairMatcher.group(2);
                }
            }
        }

        // --- Fallbacks if that pattern wasn't found ---
        if (distanceMeters == null) {
            java.util.regex.Matcher distMatcher = java.util.regex.Pattern
                    .compile("Distance:\\s*(\\d+)\\s*[mM]", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(rawText);
            if (distMatcher.find()) {
                try { distanceMeters = Integer.parseInt(distMatcher.group(1)); } catch (NumberFormatException ignored) {}
            }
        }

        // If we never saw an "Orientation: ..." on a Distance line, fallback to last Orientation: line
        if (orientationLabel == null || orientationLabel.isEmpty() || "North".equals(orientationLabel)) {
            String lastOrientation = null;
            for (String line : lines) {
                java.util.regex.Matcher oriMatcher = java.util.regex.Pattern
                        .compile("Orientation:\\s*([A-Za-z]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(line);
                if (oriMatcher.find()) {
                    lastOrientation = oriMatcher.group(1);
                }
            }
            if (lastOrientation != null) {
                orientationLabel = lastOrientation;
            }
        }

        if (distanceMeters == null) distanceMeters = 1;
        distanceMeters = Math.max(1, Math.min(4, distanceMeters));

        // 🔒 Clamp to one of 8 compass labels
        orientationLabel = sanitizeOrientationLabel(orientationLabel);
        float angleDeg = orientationToAngle(orientationLabel);

        return new UStarPrediction(distanceMeters, orientationLabel, angleDeg);
    }

    /**
     * Ensure the orientation label is one of:
     * North, Northeast, East, Southeast, South, Southwest, West, Northwest
     */
    private static String sanitizeOrientationLabel(String raw) {
        if (raw == null) return "North";
        String o = raw.trim().toLowerCase();

        // Exact matches
        switch (o) {
            case "north":
            case "northeast":
            case "east":
            case "southeast":
            case "south":
            case "southwest":
            case "west":
            case "northwest":
                return capitalize(o);
        }

        // Simple prefix-based fallback
        if (o.startsWith("northwest")) return "Northwest";
        if (o.startsWith("northeast")) return "Northeast";
        if (o.startsWith("southwest")) return "Southwest";
        if (o.startsWith("southeast")) return "Southeast";
        if (o.startsWith("north"))     return "North";
        if (o.startsWith("south"))     return "South";
        if (o.startsWith("east"))      return "East";
        if (o.startsWith("west"))      return "West";

        // Default
        return "North";
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        if (s.length() == 1) return s.toUpperCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Map only the 8 canonical labels to angles. */
    private static float orientationToAngle(String ori) {
        if (ori == null) ori = "North";
        String o = ori.trim().toLowerCase();
        switch (o) {
            case "north":      return   0f;
            case "northeast":  return  45f;
            case "east":       return  90f;
            case "southeast":  return 135f;
            case "south":      return 180f;
            case "southwest":  return 225f;
            case "west":       return 270f;
            case "northwest":  return 315f;
            default:           return   0f;  // should not happen due to sanitizeOrientationLabel
        }
    }
}