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

public class DirectionTabletRenderer {

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

    // Panel transform
    private float posX = 0f, posY = 0.05f, posZ = 0f;
    private float rotX = 0f, rotY = 0f, rotZ = 0f;
    private float scale = 0.85f;

    // Latest value from SharedCameraActivity
    private String currentOrientationLabel = "North";

    // Quad geometry
    private static final float[] QUAD_COORDS = {
            -0.5f, -0.3f, 0f,
            0.5f, -0.3f, 0f,
            0.5f,  0.3f, 0f,

            -0.5f, -0.3f, 0f,
            0.5f,  0.3f, 0f,
            -0.5f,  0.3f, 0f
    };

    private static final float[] QUAD_TEX = {
            0f, 1f,
            1f, 1f,
            1f, 0f,

            0f, 1f,
            1f, 0f,
            0f, 0f
    };

    // Shaders
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

    /** Converts long names into compass abbreviations (N, NE, E, SE, S, SW, W, NW). */
    private String toCompassAbbrev(String label) {
        if (label == null) return "N";
        String s = label.trim().toLowerCase();

        if (s.startsWith("northwest")) return "NW";
        if (s.startsWith("northeast")) return "NE";
        if (s.startsWith("southwest")) return "SW";
        if (s.startsWith("southeast")) return "SE";
        if (s.startsWith("north"))     return "N";
        if (s.startsWith("south"))     return "S";
        if (s.startsWith("east"))      return "E";
        if (s.startsWith("west"))      return "W";

        return "N";
    }

    public void createOnGlThread() {
        // Vertex buffer
        ByteBuffer vb = ByteBuffer.allocateDirect(QUAD_COORDS.length * BYTES_PER_FLOAT);
        vb.order(ByteOrder.nativeOrder());
        vertexBuffer = vb.asFloatBuffer();
        vertexBuffer.put(QUAD_COORDS).position(0);

        // Texture coordinates
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

    /** Only orientation is used now. */
    public void updateFromValues(int distanceMetersIgnored, String orientationLabel) {
        currentOrientationLabel = toCompassAbbrev(orientationLabel);
        Bitmap bmp = buildPanelBitmap(currentOrientationLabel);
        uploadTexture(bmp);
        bmp.recycle();
    }

    /** Build bitmap showing ONLY orientation (N, NE, E, SE, ...) with no black padding. */
    private Bitmap buildPanelBitmap(String shortOrientation) {
        int w = 1024;
        int h = 384;  // taller for a big, clear letter

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // Fill ENTIRE texture with freeway green (no border)
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setStyle(Paint.Style.FILL);
        bg.setColor(Color.rgb(0, 106, 78));  // freeway green
        canvas.drawRect(0f, 0f, w, h, bg);

        // Orientation (large, centered)
        Paint ori = new Paint(Paint.ANTI_ALIAS_FLAG);
        ori.setColor(Color.WHITE);
        ori.setTextSize(300f);               // big indicator
        ori.setTextAlign(Paint.Align.CENTER);
        ori.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));

        Paint.FontMetrics fm = ori.getFontMetrics();
        float cx = w / 2f;
        float cy = (h / 2f) - ((fm.ascent + fm.descent) / 2f);

        canvas.drawText(shortOrientation, cx, cy, ori);

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

    public void draw(float[] viewMatrix, float[] projMatrix, float[] anchorMatrix) {
        if (textureId == 0) return;

        GLES20.glUseProgram(program);

        Matrix.setIdentityM(modelMatrix, 0);
        Matrix.translateM(modelMatrix, 0, posX, posY, posZ);
        Matrix.rotateM(modelMatrix, 0, rotX, 1f, 0f, 0f);
        Matrix.rotateM(modelMatrix, 0, rotY, 0f, 1f, 0f);
        Matrix.rotateM(modelMatrix, 0, rotZ, 0f, 0f, 1f);
        Matrix.scaleM(modelMatrix, 0, scale, scale, scale);

        Matrix.multiplyMM(tempMatrix, 0, anchorMatrix, 0, modelMatrix, 0);
        Matrix.multiplyMM(finalMvp, 0, viewMatrix, 0, tempMatrix, 0);
        Matrix.multiplyMM(finalMvp, 0, projMatrix, 0, finalMvp, 0);

        positionHandle  = GLES20.glGetAttribLocation(program, "aPosition");
        texCoordHandle  = GLES20.glGetAttribLocation(program, "aTexCoord");
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        samplerHandle   = GLES20.glGetUniformLocation(program, "uTexture");

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, finalMvp, 0);

        GLES20.glEnableVertexAttribArray(positionHandle);
        vertexBuffer.position(0);
        GLES20.glVertexAttribPointer(
                positionHandle,
                COORDS_PER_VERTEX,
                GLES20.GL_FLOAT,
                false,
                COORDS_PER_VERTEX * BYTES_PER_FLOAT,
                vertexBuffer
        );

        GLES20.glEnableVertexAttribArray(texCoordHandle);
        texBuffer.position(0);
        GLES20.glVertexAttribPointer(
                texCoordHandle,
                TEX_PER_VERTEX,
                GLES20.GL_FLOAT,
                false,
                TEX_PER_VERTEX * BYTES_PER_FLOAT,
                texBuffer
        );

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glUniform1i(samplerHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, QUAD_COORDS.length / COORDS_PER_VERTEX);

        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(texCoordHandle);
    }
}