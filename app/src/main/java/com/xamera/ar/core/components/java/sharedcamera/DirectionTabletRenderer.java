package com.xamera.ar.core.components.java.sharedcamera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Renders a "tablet" panel that displays direction + distance.
 *
 * NO file I/O here – values are pushed from SharedCameraActivity
 * via updateFromValues(distance, orientation).
 *
 * The panel is a textured quad drawn in 3D; texture contains:
 *  - "Current Direction Info" (title)
 *  - ORIENTATION: <label>
 *  - DISTANCE: <Xm>
 *
 * Tablet background is freeway green, with no arrow graphic.
 */
public class DirectionTabletRenderer {

    private static final String TAG = "DirectionTabletRenderer";

    private static final int BYTES_PER_FLOAT   = 4;
    private static final int COORDS_PER_VERTEX = 3;
    private static final int TEX_PER_VERTEX    = 2;

    private FloatBuffer vertexBuffer;
    private FloatBuffer texBuffer;

    private int program;
    private int positionHandle;
    private int texCoordHandle;
    private int mvpMatrixHandle;
    private int samplerHandle;

    private int textureId = 0;

    // Matrices
    private final float[] modelMatrix = new float[16];
    private final float[] tempMatrix  = new float[16];
    private final float[] finalMvp    = new float[16];

    // Panel transform (relative to anchor)
    // Smaller Y offset so tablet sits closer to arrow
    private float posX = 0f, posY = 0.05f, posZ = 0f;
    private float rotX = 0f, rotY = 0f, rotZ = 0f;
    private float scale = 0.15f; // overall size

    // Latest values (provided externally)
    private int    currentDistanceMeters   = 1;
    private String currentOrientationLabel = "North";

    // Simple rectangle (two triangles) in local space, centered at origin, Z=0
    // Width = 1.0, height = 0.6 (landscape panel)
    private static final float[] QUAD_COORDS = {
            //  X,     Y,    Z
            -0.5f, -0.3f, 0f,
            0.5f, -0.3f, 0f,
            0.5f,  0.3f, 0f,

            -0.5f, -0.3f, 0f,
            0.5f,  0.3f, 0f,
            -0.5f,  0.3f, 0f
    };

    private static final float[] QUAD_TEX = {
            //  U, V
            0f, 1f,
            1f, 1f,
            1f, 0f,

            0f, 1f,
            1f, 0f,
            0f, 0f
    };

    // Basic textured shader
    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;" +
                    "attribute vec4 aPosition;" +
                    "attribute vec2 aTexCoord;" +
                    "varying vec2 vTexCoord;" +
                    "void main() {" +
                    "  vTexCoord = aTexCoord;" +
                    "  gl_Position = uMVPMatrix * aPosition;" +
                    "}";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;" +
                    "varying vec2 vTexCoord;" +
                    "uniform sampler2D uTexture;" +
                    "void main() {" +
                    "  gl_FragColor = texture2D(uTexture, vTexCoord);" +
                    "}";

    public DirectionTabletRenderer() {
        Matrix.setIdentityM(modelMatrix, 0);
    }

    public void createOnGlThread() {
        // Vertex buffer
        ByteBuffer vb = ByteBuffer.allocateDirect(QUAD_COORDS.length * BYTES_PER_FLOAT);
        vb.order(ByteOrder.nativeOrder());
        vertexBuffer = vb.asFloatBuffer();
        vertexBuffer.put(QUAD_COORDS).position(0);

        // Texcoord buffer
        ByteBuffer tb = ByteBuffer.allocateDirect(QUAD_TEX.length * BYTES_PER_FLOAT);
        tb.order(ByteOrder.nativeOrder());
        texBuffer = tb.asFloatBuffer();
        texBuffer.put(QUAD_TEX).position(0);

        // Compile shaders
        int vs = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
    }

    private int loadShader(int type, String code) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, code);
        GLES20.glCompileShader(shader);
        return shader;
    }

    public void setPosition(float x, float y, float z) { posX = x; posY = y; posZ = z; }
    public void setRotation(float x, float y, float z) { rotX = x; rotY = y; rotZ = z; }
    public void setScale(float s) { scale = s; }

    /**
     * Update text from external values (distance + orientation)
     * and rebuild the texture.
     */
    public void updateFromValues(int distanceMeters, String orientationLabel) {
        currentDistanceMeters = distanceMeters;
        currentOrientationLabel = (orientationLabel != null && !orientationLabel.isEmpty())
                ? orientationLabel
                : "North";

        Bitmap panelBmp = buildPanelBitmap(currentDistanceMeters, currentOrientationLabel);
        uploadTexture(panelBmp);
        panelBmp.recycle();
    }

    /**
     * Build a 1024x512 bitmap with a title + text, on a freeway green tablet.
     * Vertical padding and line spacing are tightened so there is less
     * empty space between lines and at the bottom.
     */
    private Bitmap buildPanelBitmap(int distanceMeters, String orientationLabel) {
        Bitmap bmp = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // Clear
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

        // FREEWAY SIGN GREEN — #006A4E (RGB 0,106,78)
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.rgb(0, 106, 78));  // deep green freeway color

        // Slightly reduced bottom padding (was bottom=472f)
        canvas.drawRoundRect(40f, 40f, 984f, 440f, 30f, 30f, bgPaint);

        // --- Title: "Current Direction Info" at top ---
        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.WHITE);
        titlePaint.setTextSize(72f);
        titlePaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        titlePaint.setTextAlign(Paint.Align.CENTER);

        float centerX = bmp.getWidth() / 2f;
        float titleY  = 120f; // slightly higher = tighter top spacing

        canvas.drawText("Current Direction Info", centerX, titleY, titlePaint);

        // --- Body text (orientation + distance), left aligned ---
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(64f);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.LEFT);

        float leftTextX   = 80f;
        float baseY       = 230f;  // just under title
        float lineSpacing = 72f;   // was 90f -> lines closer together

        canvas.drawText(
                "ORIENTATION: " + orientationLabel.toUpperCase(),
                leftTextX,
                baseY,
                textPaint
        );

        canvas.drawText(
                "DISTANCE: " + distanceMeters + "m",
                leftTextX,
                baseY + lineSpacing,
                textPaint
        );

        return bmp;
    }

    private void uploadTexture(Bitmap bmp) {
        if (textureId != 0) {
            int[] oldTex = new int[]{textureId};
            GLES20.glDeleteTextures(1, oldTex, 0);
        }

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        textureId = tex[0];

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
    }

    /**
     * Draws the tablet panel near the given anchor.
     * Make sure updateFromValues(...) has been called at least once.
     */
    public void draw(float[] viewMatrix, float[] projMatrix, float[] anchorMatrix) {
        if (textureId == 0) {
            // No texture yet; nothing to draw
            return;
        }

        GLES20.glUseProgram(program);

        // Local model matrix: position + rotation + scale
        Matrix.setIdentityM(modelMatrix, 0);
        Matrix.translateM(modelMatrix, 0, posX, posY, posZ);
        Matrix.rotateM(modelMatrix, 0, rotX, 1f, 0f, 0f);
        Matrix.rotateM(modelMatrix, 0, rotY, 0f, 1f, 0f);
        Matrix.rotateM(modelMatrix, 0, rotZ, 0f, 0f, 1f);
        Matrix.scaleM(modelMatrix, 0, scale, scale, scale);

        // anchor * model
        Matrix.multiplyMM(tempMatrix, 0, anchorMatrix, 0, modelMatrix, 0);
        // view * (anchor*model)
        Matrix.multiplyMM(finalMvp, 0, viewMatrix, 0, tempMatrix, 0);
        // proj * ...
        Matrix.multiplyMM(finalMvp, 0, projMatrix, 0, finalMvp, 0);

        positionHandle  = GLES20.glGetAttribLocation(program, "aPosition");
        texCoordHandle  = GLES20.glGetAttribLocation(program, "aTexCoord");
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        samplerHandle   = GLES20.glGetUniformLocation(program, "uTexture");

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(
                positionHandle,
                COORDS_PER_VERTEX,
                GLES20.GL_FLOAT,
                false,
                COORDS_PER_VERTEX * BYTES_PER_FLOAT,
                vertexBuffer
        );

        GLES20.glEnableVertexAttribArray(texCoordHandle);
        GLES20.glVertexAttribPointer(
                texCoordHandle,
                TEX_PER_VERTEX,
                GLES20.GL_FLOAT,
                false,
                TEX_PER_VERTEX * BYTES_PER_FLOAT,
                texBuffer
        );

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, finalMvp, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glUniform1i(samplerHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, QUAD_COORDS.length / COORDS_PER_VERTEX);

        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(texCoordHandle);
    }
}