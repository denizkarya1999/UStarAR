package com.xamera.ar.core.components.java.sharedcamera;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 * Utility to read and parse UStar_Cube_Prediction.txt into a UStarPrediction.
 */
public class UStarPredictionReader {

    private static final String TAG = "UStarPredictionReader";

    // Default content if file can't be read
    private static final String DEFAULT_TEXT =
            "Distance: 1M | Orientation: North";

    /**
     * Preferred: read from a fixed file in Documents:
     *   Documents/UStar_Cube_Prediction.txt
     */
    public static UStarPrediction readFromDocuments() {
        String rawText = DEFAULT_TEXT;

        try {
            File docDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS);
            File file = new File(docDir, "UStar_Cube_Prediction.txt");

            if (file.exists()) {
                FileInputStream fis = new FileInputStream(file);
                BufferedReader br = new BufferedReader(new InputStreamReader(fis));

                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                br.close();
                fis.close();

                String read = sb.toString().trim();
                if (!read.isEmpty()) {
                    rawText = read;
                }
            } else {
                Log.w(TAG, "Prediction file not found in Documents; using default.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading prediction file from Documents", e);
            rawText = DEFAULT_TEXT;
        }

        return parseRawText(rawText);
    }

    /**
     * Optional: still supports SAF Uri if you use it anywhere else.
     * SharedCameraActivity doesn't need this anymore, but we keep it for compatibility.
     */
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

        return parseRawText(rawText);
    }

    /**
     * Common parsing logic used by both readFromDocuments() and readFromUri().
     * Expected patterns, e.g.:
     *   "Distance: 4M | Orientation: Southwest"
     */
    private static UStarPrediction parseRawText(String rawText) {
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
                    .compile("Distance:\\s*(\\d+)\\s*[mM]",
                            java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(rawText);
            if (distMatcher.find()) {
                try {
                    distanceMeters = Integer.parseInt(distMatcher.group(1));
                } catch (NumberFormatException ignored) {}
            }
        }

        // If we never saw an "Orientation: ..." on a Distance line,
        // fallback to last Orientation: line anywhere.
        if (orientationLabel == null || orientationLabel.isEmpty() || "North".equals(orientationLabel)) {
            String lastOrientation = null;
            for (String line : lines) {
                java.util.regex.Matcher oriMatcher = java.util.regex.Pattern
                        .compile("Orientation:\\s*([A-Za-z]+)",
                                java.util.regex.Pattern.CASE_INSENSITIVE)
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

        // Clamp to one of 8 compass labels
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