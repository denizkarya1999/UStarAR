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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Chunky 3D Arrow Renderer (for "big red arrow" style).
 *
 * Now also renders a simple "<distance>M" label embedded
 * on the front face of the arrow body using a textured quad,
 * similar to DirectionTabletRenderer.
 */
public class ArrowRenderer {

    private static final int BYTES_PER_FLOAT = 4;
    private static final int COORDS_PER_VERTEX = 3;
    private static final int COLOR_COMPONENTS = 4;
    // shrink factor for the embedded label quad
    private static final float LABEL_SCALE = 0.18f;

    // ---- Arrow geometry (red body) ----
    private FloatBuffer vertexBuffer;
    private FloatBuffer colorBuffer;

    private int arrowProgram;
    private int arrowPosHandle;
    private int arrowColorHandle;
    private int arrowMvpHandle;

    // ---- Distance label quad (textured) ----
    private FloatBuffer labelVertexBuffer;
    private FloatBuffer labelTexBuffer;

    private int labelProgram;
    private int labelPosHandle;
    private int labelTexCoordHandle;
    private int labelMvpHandle;
    private int labelSamplerHandle;
    private int labelTextureId = 0;

    // Matrices (reused for body & label)
    private final float[] modelMatrix = new float[16];
    private final float[] tempMatrix  = new float[16];
    private final float[] finalMvp    = new float[16];

    // Transform
    private float posX = 0, posY = 0, posZ = 0;
    private float rotX = 0, rotY = 0, rotZ = 0;
    private float scale = 1f;

    // Distance for label
    private int distanceMeters = 1;  // default

    // Arrow points along +Y; shaft + head
    private static final float[] ARROW_COORDS = {

            // --------- SHAFT BOX (narrow, centered) ---------

            // Front (z = +0.05)
            -0.10f, 0.00f,  0.05f,
            0.10f, 0.00f,  0.05f,
            0.10f, 0.30f,  0.05f,

            -0.10f, 0.00f,  0.05f,
            0.10f, 0.30f,  0.05f,
            -0.10f, 0.30f,  0.05f,

            // Back (z = -0.05)
            -0.10f, 0.00f, -0.05f,
            -0.10f, 0.30f, -0.05f,
            0.10f, 0.30f, -0.05f,

            -0.10f, 0.00f, -0.05f,
            0.10f, 0.30f, -0.05f,
            0.10f, 0.00f, -0.05f,

            // Left (x = -0.10)
            -0.10f, 0.00f, -0.05f,
            -0.10f, 0.00f,  0.05f,
            -0.10f, 0.30f,  0.05f,

            -0.10f, 0.00f, -0.05f,
            -0.10f, 0.30f,  0.05f,
            -0.10f, 0.30f, -0.05f,

            // Right (x = +0.10)
            0.10f, 0.00f, -0.05f,
            0.10f, 0.30f, -0.05f,
            0.10f, 0.30f,  0.05f,

            0.10f, 0.00f, -0.05f,
            0.10f, 0.30f,  0.05f,
            0.10f, 0.00f,  0.05f,

            // Top (y = 0.30)
            -0.10f, 0.30f,  0.05f,
            0.10f, 0.30f,  0.05f,
            0.10f, 0.30f, -0.05f,

            -0.10f, 0.30f,  0.05f,
            0.10f, 0.30f, -0.05f,
            -0.10f, 0.30f, -0.05f,

            // Bottom (y = 0.00)
            -0.10f, 0.00f,  0.05f,
            -0.10f, 0.00f, -0.05f,
            0.10f, 0.00f, -0.05f,

            -0.10f, 0.00f,  0.05f,
            0.10f, 0.00f, -0.05f,
            0.10f, 0.00f,  0.05f,

            // --------- HEAD PYRAMID ---------
            // Base at y = 0.30, apex at y = 0.55

            // Front face
            0.0f, 0.55f,  0.0f,
            -0.20f, 0.30f,  0.05f,
            0.20f, 0.30f,  0.05f,

            // Right face
            0.0f, 0.55f,  0.0f,
            0.20f, 0.30f,  0.05f,
            0.20f, 0.30f, -0.05f,

            // Back face
            0.0f, 0.55f,  0.0f,
            0.20f, 0.30f, -0.05f,
            -0.20f, 0.30f, -0.05f,

            // Left face
            0.0f, 0.55f,  0.0f,
            -0.20f, 0.30f, -0.05f,
            -0.20f, 0.30f,  0.05f,
    };

    // Small quad centered at origin for the label; we will place it on the arrow front
    private static final float[] LABEL_QUAD_COORDS = {
            -0.5f, -0.25f, 0f,
            0.5f, -0.25f, 0f,
            0.5f,  0.25f, 0f,

            -0.5f, -0.25f, 0f,
            0.5f,  0.25f, 0f,
            -0.5f,  0.25f, 0f
    };

    private static final float[] LABEL_TEX_COORDS = {
            0f, 1f,
            1f, 1f,
            1f, 0f,

            0f, 1f,
            1f, 0f,
            0f, 0f
    };

    // Arrow: position + color
    private static final String ARROW_VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;" +
                    "attribute vec4 aPosition;" +
                    "attribute vec4 aColor;" +
                    "varying vec4 vColor;" +
                    "void main() {" +
                    "  vColor = aColor;" +
                    "  gl_Position = uMVPMatrix * aPosition;" +
                    "}";

    private static final String ARROW_FRAGMENT_SHADER =
            "precision mediump float;" +
                    "varying vec4 vColor;" +
                    "void main() {" +
                    "  gl_FragColor = vColor;" +
                    "}";

    // Label: textured quad
    private static final String LABEL_VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;" +
                    "attribute vec4 aPosition;" +
                    "attribute vec2 aTexCoord;" +
                    "varying vec2 vTexCoord;" +
                    "void main() {" +
                    "  vTexCoord = aTexCoord;" +
                    "  gl_Position = uMVPMatrix * aPosition;" +
                    "}";

    private static final String LABEL_FRAGMENT_SHADER =
            "precision mediump float;" +
                    "varying vec2 vTexCoord;" +
                    "uniform sampler2D uTexture;" +
                    "void main() {" +
                    "  gl_FragColor = texture2D(uTexture, vTexCoord);" +
                    "}";

    public ArrowRenderer() {
        Matrix.setIdentityM(modelMatrix, 0);
    }

    /** Call this from SharedCameraActivity when distance changes. */
    public void setDistance(int meters) {
        distanceMeters = Math.max(1, Math.min(4, meters));
        updateLabelTexture();
    }

    public void createOnGlThread() {
        // ---- Arrow buffers ----
        ByteBuffer vb = ByteBuffer.allocateDirect(ARROW_COORDS.length * BYTES_PER_FLOAT);
        vb.order(ByteOrder.nativeOrder());
        vertexBuffer = vb.asFloatBuffer();
        vertexBuffer.put(ARROW_COORDS).position(0);

        int vertexCount = ARROW_COORDS.length / COORDS_PER_VERTEX;
        ByteBuffer cb = ByteBuffer.allocateDirect(vertexCount * COLOR_COMPONENTS * BYTES_PER_FLOAT);
        cb.order(ByteOrder.nativeOrder());
        colorBuffer = cb.asFloatBuffer();
        setColor(1f, 0f, 0f, 1f); // bright red

        // ---- Label quad buffers ----
        ByteBuffer lvb = ByteBuffer.allocateDirect(LABEL_QUAD_COORDS.length * BYTES_PER_FLOAT);
        lvb.order(ByteOrder.nativeOrder());
        labelVertexBuffer = lvb.asFloatBuffer();
        labelVertexBuffer.put(LABEL_QUAD_COORDS).position(0);

        ByteBuffer ltb = ByteBuffer.allocateDirect(LABEL_TEX_COORDS.length * BYTES_PER_FLOAT);
        ltb.order(ByteOrder.nativeOrder());
        labelTexBuffer = ltb.asFloatBuffer();
        labelTexBuffer.put(LABEL_TEX_COORDS).position(0);

        // ---- Compile shader programs ----
        arrowProgram = createProgram(ARROW_VERTEX_SHADER, ARROW_FRAGMENT_SHADER);
        labelProgram = createProgram(LABEL_VERTEX_SHADER, LABEL_FRAGMENT_SHADER);

        // Build initial label texture for default distance
        updateLabelTexture();
    }

    private int createProgram(String vsSrc, String fsSrc) {
        int vs = loadShader(GLES20.GL_VERTEX_SHADER, vsSrc);
        int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fsSrc);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        return program;
    }

    private int loadShader(int type, String code) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, code);
        GLES20.glCompileShader(shader);
        return shader;
    }

    public void setRotation(float x, float y, float z) {
        rotX = x;
        rotY = y;
        rotZ = z;
    }

    public void setPosition(float x, float y, float z) {
        posX = x;
        posY = y;
        posZ = z;
    }

    public void setScale(float s) {
        scale = s;
    }

    /** Solid color for the arrow body. */
    public void setColor(float r, float g, float b, float a) {
        colorBuffer.position(0);
        int vertexCount = ARROW_COORDS.length / COORDS_PER_VERTEX;
        for (int i = 0; i < vertexCount; i++) {
            colorBuffer.put(r).put(g).put(b).put(a);
        }
        colorBuffer.position(0);
    }

    /** Build / rebuild the distance label texture: "1M", "2M", ... */
    private void updateLabelTexture() {
        // 512 x 256 bitmap with centered "<distance>M"
        Bitmap bmp = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // Fill ENTIRE texture with arrow red (no transparent padding)
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setStyle(Paint.Style.FILL);

        // match your arrow color – tweak if needed
        bgPaint.setColor(Color.rgb(255, 0, 0));
        canvas.drawRect(0f, 0f, bmp.getWidth(), bmp.getHeight(), bgPaint);

        // White bold text
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(310f);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);

        String label = distanceMeters + "M";

        // Proper vertical centering
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float cx = bmp.getWidth() / 2f;
        float cy = (bmp.getHeight() / 2f) - ((fm.ascent + fm.descent) / 2f);

        canvas.drawText(label, cx, cy, textPaint);


        // Upload texture
        if (labelTextureId != 0) {
            int[] old = new int[]{labelTextureId};
            GLES20.glDeleteTextures(1, old, 0);
        }
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        labelTextureId = tex[0];

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, labelTextureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();
    }

    public void draw(float[] viewMatrix, float[] projMatrix, float[] anchorMatrix) {
        // ---------- 1) DRAW RED ARROW BODY ----------
        GLES20.glUseProgram(arrowProgram);

        // Build local model matrix (pos, rot, scale)
        Matrix.setIdentityM(modelMatrix, 0);
        Matrix.translateM(modelMatrix, 0, posX, posY, posZ);
        Matrix.rotateM(modelMatrix, 0, rotX, 1f, 0f, 0f);
        Matrix.rotateM(modelMatrix, 0, rotY, 0f, 1f, 0f);
        Matrix.rotateM(modelMatrix, 0, rotZ, 0f, 0f, 1f);
        Matrix.scaleM(modelMatrix, 0, scale, scale, scale);

        // Arrow in world space: anchor * model
        Matrix.multiplyMM(tempMatrix, 0, anchorMatrix, 0, modelMatrix, 0);

        // MVP for arrow body (normal viewMatrix → arrow rotates with camera)
        Matrix.multiplyMM(finalMvp, 0, viewMatrix, 0, tempMatrix, 0);
        Matrix.multiplyMM(finalMvp, 0, projMatrix, 0, finalMvp, 0);

        arrowPosHandle   = GLES20.glGetAttribLocation(arrowProgram, "aPosition");
        arrowColorHandle = GLES20.glGetAttribLocation(arrowProgram, "aColor");
        arrowMvpHandle   = GLES20.glGetUniformLocation(arrowProgram, "uMVPMatrix");

        GLES20.glUniformMatrix4fv(arrowMvpHandle, 1, false, finalMvp, 0);

        GLES20.glEnableVertexAttribArray(arrowPosHandle);
        vertexBuffer.position(0);
        GLES20.glVertexAttribPointer(
                arrowPosHandle,
                COORDS_PER_VERTEX,
                GLES20.GL_FLOAT,
                false,
                COORDS_PER_VERTEX * BYTES_PER_FLOAT,
                vertexBuffer
        );

        GLES20.glEnableVertexAttribArray(arrowColorHandle);
        colorBuffer.position(0);
        GLES20.glVertexAttribPointer(
                arrowColorHandle,
                COLOR_COMPONENTS,
                GLES20.GL_FLOAT,
                false,
                COLOR_COMPONENTS * BYTES_PER_FLOAT,
                colorBuffer
        );

        int fillVertexCount = ARROW_COORDS.length / COORDS_PER_VERTEX;
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, fillVertexCount);

        GLES20.glDisableVertexAttribArray(arrowPosHandle);
        GLES20.glDisableVertexAttribArray(arrowColorHandle);


        // ---------- 2) DRAW DISTANCE LABEL TEXTURED QUAD (EMBEDDED "2M") ----------
        if (labelTextureId == 0) {
            return; // nothing to draw yet
        }

        GLES20.glUseProgram(labelProgram);

        // Rebuild arrow model matrix (same as above)
        Matrix.setIdentityM(modelMatrix, 0);
        Matrix.translateM(modelMatrix, 0, posX, posY, posZ);
        Matrix.rotateM(modelMatrix, 0, rotX, 1f, 0f, 0f);
        Matrix.rotateM(modelMatrix, 0, rotY, 0f, 1f, 0f);
        Matrix.rotateM(modelMatrix, 0, rotZ, 0f, 0f, 1f);
        Matrix.scaleM(modelMatrix, 0, scale, scale, scale);

        // Arrow in world space again
        float[] arrowWorld = new float[16];
        Matrix.multiplyMM(arrowWorld, 0, anchorMatrix, 0, modelMatrix, 0);

        // Local transform that glues the label onto the shaft front face.
        // Shaft front ≈ z = +0.05, y in [0.00, 0.30], so center is y ≈ 0.15.
        float[] labelLocal = new float[16];
        Matrix.setIdentityM(labelLocal, 0);

        // Place label in the middle of the chest, just on the front surface.
        Matrix.translateM(labelLocal, 0,
                0f,     // center in X
                0.15f,  // mid of shaft height
                0.051f  // just in front of front face (0.05) to avoid z-fighting
        );

        // Scale quad so it covers a nice rectangle on the shaft.
        Matrix.scaleM(labelLocal, 0,
                0.18f,  // label width on arrow
                0.15f,  // label height
                1f
        );

        // Label in world space: arrowWorld * labelLocal
        float[] labelWorld = new float[16];
        Matrix.multiplyMM(labelWorld, 0, arrowWorld, 0, labelLocal, 0);

        // Final MVP for label – same viewMatrix, so it rotates with arrow.
        Matrix.multiplyMM(finalMvp, 0, viewMatrix, 0, labelWorld, 0);
        Matrix.multiplyMM(finalMvp, 0, projMatrix, 0, finalMvp, 0);

        labelPosHandle      = GLES20.glGetAttribLocation(labelProgram, "aPosition");
        labelTexCoordHandle = GLES20.glGetAttribLocation(labelProgram, "aTexCoord");
        labelMvpHandle      = GLES20.glGetUniformLocation(labelProgram, "uMVPMatrix");
        labelSamplerHandle  = GLES20.glGetUniformLocation(labelProgram, "uTexture");

        GLES20.glUniformMatrix4fv(labelMvpHandle, 1, false, finalMvp, 0);

        GLES20.glEnableVertexAttribArray(labelPosHandle);
        labelVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(
                labelPosHandle,
                COORDS_PER_VERTEX,
                GLES20.GL_FLOAT,
                false,
                COORDS_PER_VERTEX * BYTES_PER_FLOAT,
                labelVertexBuffer
        );

        GLES20.glEnableVertexAttribArray(labelTexCoordHandle);
        labelTexBuffer.position(0);
        GLES20.glVertexAttribPointer(
                labelTexCoordHandle,
                2,
                GLES20.GL_FLOAT,
                false,
                2 * BYTES_PER_FLOAT,
                labelTexBuffer
        );

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, labelTextureId);
        GLES20.glUniform1i(labelSamplerHandle, 0);

        GLES20.glDrawArrays(
                GLES20.GL_TRIANGLES,
                0,
                LABEL_QUAD_COORDS.length / COORDS_PER_VERTEX
        );

        GLES20.glDisableVertexAttribArray(labelPosHandle);
        GLES20.glDisableVertexAttribArray(labelTexCoordHandle);
    }
}