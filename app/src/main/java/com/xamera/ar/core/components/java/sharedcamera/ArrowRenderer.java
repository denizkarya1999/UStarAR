package com.xamera.ar.core.components.java.sharedcamera;

import android.opengl.GLES20;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Chunky 3D Arrow Renderer (for "big red arrow" style).
 *
 * Top view silhouette:
 *
 *        /\
 *       /  \
 *      /    \
 *     |      |
 *     |  ||  |
 *     |  ||  |
 *   [======  ======]
 *
 * - Wide base box
 * - Narrow center shaft
 * - Large pyramid head
 *
 * Arrow points along +Y in object space.
 */
public class ArrowRenderer {

    private static final int BYTES_PER_FLOAT = 4;
    private static final int COORDS_PER_VERTEX = 3;
    private static final int COLOR_COMPONENTS = 4;

    private FloatBuffer vertexBuffer;
    private FloatBuffer colorBuffer;

    private int program;
    private int positionHandle;
    private int colorHandle;
    private int mvpMatrixHandle;

    // Matrices
    private final float[] modelMatrix = new float[16];
    private final float[] tempMatrix = new float[16];
    private final float[] finalMvp = new float[16];

    // Transform
    private float posX = 0, posY = 0, posZ = 0;
    private float rotX = 0, rotY = 0, rotZ = 0;
    private float scale = 1f;

    /**
     * Arrow geometry.
     * <p>
     * All units are in "meters" in local object space.
     * <p>
     * - Base box:
     * x ∈ [-0.25, 0.25], y ∈ [0.00, 0.15], z ∈ [-0.05, 0.05]
     * - Shaft box:
     * x ∈ [-0.10, 0.10], y ∈ [0.15, 0.35], z ∈ [-0.05, 0.05]
     * - Head pyramid:
     * base at y = 0.35 (x = ±0.20, z = ±0.05), apex at (0, 0.55, 0)
     */
// Arrow points along +Y; only shaft + head (no bottom rectangle).
    private static final float[] ARROW_COORDS = {

            // --------- SHAFT BOX (narrow, centered) ---------
            // x ∈ [-0.10, 0.10], y ∈ [0.00, 0.30], z ∈ [-0.05, 0.05]

            // Front (z = +0.05)
            -0.10f, 0.00f, 0.05f,
            0.10f, 0.00f, 0.05f,
            0.10f, 0.30f, 0.05f,

            -0.10f, 0.00f, 0.05f,
            0.10f, 0.30f, 0.05f,
            -0.10f, 0.30f, 0.05f,

            // Back (z = -0.05)
            -0.10f, 0.00f, -0.05f,
            -0.10f, 0.30f, -0.05f,
            0.10f, 0.30f, -0.05f,

            -0.10f, 0.00f, -0.05f,
            0.10f, 0.30f, -0.05f,
            0.10f, 0.00f, -0.05f,

            // Left (x = -0.10)
            -0.10f, 0.00f, -0.05f,
            -0.10f, 0.00f, 0.05f,
            -0.10f, 0.30f, 0.05f,

            -0.10f, 0.00f, -0.05f,
            -0.10f, 0.30f, 0.05f,
            -0.10f, 0.30f, -0.05f,

            // Right (x = +0.10)
            0.10f, 0.00f, -0.05f,
            0.10f, 0.30f, -0.05f,
            0.10f, 0.30f, 0.05f,

            0.10f, 0.00f, -0.05f,
            0.10f, 0.30f, 0.05f,
            0.10f, 0.00f, 0.05f,

            // Top (y = 0.30)
            -0.10f, 0.30f, 0.05f,
            0.10f, 0.30f, 0.05f,
            0.10f, 0.30f, -0.05f,

            -0.10f, 0.30f, 0.05f,
            0.10f, 0.30f, -0.05f,
            -0.10f, 0.30f, -0.05f,

            // Bottom (y = 0.00)
            -0.10f, 0.00f, 0.05f,
            -0.10f, 0.00f, -0.05f,
            0.10f, 0.00f, -0.05f,

            -0.10f, 0.00f, 0.05f,
            0.10f, 0.00f, -0.05f,
            0.10f, 0.00f, 0.05f,

            // --------- HEAD PYRAMID ---------
            // Base at y = 0.30, apex at y = 0.55
            // base: x ∈ [-0.20,0.20], z ∈ [-0.05,0.05]

            // Front face
            0.0f, 0.55f, 0.0f,
            -0.20f, 0.30f, 0.05f,
            0.20f, 0.30f, 0.05f,

            // Right face
            0.0f, 0.55f, 0.0f,
            0.20f, 0.30f, 0.05f,
            0.20f, 0.30f, -0.05f,

            // Back face
            0.0f, 0.55f, 0.0f,
            0.20f, 0.30f, -0.05f,
            -0.20f, 0.30f, -0.05f,

            // Left face
            0.0f, 0.55f, 0.0f,
            -0.20f, 0.30f, -0.05f,
            -0.20f, 0.30f, 0.05f,
    };

    private FloatBuffer edgeVertexBuffer;

    // Front-outline of the arrow (only visible silhouette, no internal X)
    private static final float[] EDGE_COORDS = {
            // bottom shaft left  -> bottom shaft right
            -0.10f, 0.00f,  0.05f,
            0.10f, 0.00f,  0.05f,
            // up right shaft
            0.10f, 0.30f,  0.05f,
            // head base right
            0.20f, 0.30f,  0.05f,
            // head apex
            0.00f, 0.55f,  0.00f,
            // head base left
            -0.20f, 0.30f,  0.05f,
            // top shaft left
            -0.10f, 0.30f,  0.05f,
            // back down to bottom shaft left (GL_LINE_LOOP closes it)
    };

    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;" +
                    "attribute vec4 aPosition;" +
                    "attribute vec4 aColor;" +
                    "varying vec4 vColor;" +
                    "void main() {" +
                    "  vColor = aColor;" +
                    "  gl_Position = uMVPMatrix * aPosition;" +
                    "}";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;" +
                    "varying vec4 vColor;" +
                    "void main() {" +
                    "  gl_FragColor = vColor;" +
                    "}";

    public ArrowRenderer() {
        Matrix.setIdentityM(modelMatrix, 0);
    }

    public void createOnGlThread() {
        // Vertex buffer
        ByteBuffer vb = ByteBuffer.allocateDirect(ARROW_COORDS.length * BYTES_PER_FLOAT);
        vb.order(ByteOrder.nativeOrder());
        vertexBuffer = vb.asFloatBuffer();
        vertexBuffer.put(ARROW_COORDS).position(0);

        // Color buffer (we'll fill via setColor)
        int vertexCount = ARROW_COORDS.length / COORDS_PER_VERTEX;
        ByteBuffer cb = ByteBuffer.allocateDirect(vertexCount * COLOR_COMPONENTS * BYTES_PER_FLOAT);
        cb.order(ByteOrder.nativeOrder());
        colorBuffer = cb.asFloatBuffer();

        // Default: bright red
        setColor(1f, 0f, 0f, 1f);

        // Outline buffer
        ByteBuffer evb = ByteBuffer.allocateDirect(EDGE_COORDS.length * BYTES_PER_FLOAT);
        evb.order(ByteOrder.nativeOrder());
        edgeVertexBuffer = evb.asFloatBuffer();
        edgeVertexBuffer.put(EDGE_COORDS).position(0);

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

    /**
     * Solid color for now – but you could later vary per-face to fake highlights.
     */
    public void setColor(float r, float g, float b, float a) {
        colorBuffer.position(0);
        int vertexCount = ARROW_COORDS.length / COORDS_PER_VERTEX;
        for (int i = 0; i < vertexCount; i++) {
            colorBuffer.put(r).put(g).put(b).put(a);
        }
        colorBuffer.position(0);
    }

    public void draw(float[] viewMatrix, float[] projMatrix, float[] anchorMatrix) {
        GLES20.glUseProgram(program);

        // Build local model matrix (pos, rot, scale)
        Matrix.setIdentityM(modelMatrix, 0);
        Matrix.translateM(modelMatrix, 0, posX, posY, posZ);
        Matrix.rotateM(modelMatrix, 0, rotX, 1, 0, 0);
        Matrix.rotateM(modelMatrix, 0, rotY, 0, 1, 0);
        Matrix.rotateM(modelMatrix, 0, rotZ, 0, 0, 1);
        Matrix.scaleM(modelMatrix, 0, scale, scale, scale);

        // anchor → model → view → projection
        Matrix.multiplyMM(tempMatrix, 0, anchorMatrix, 0, modelMatrix, 0);
        Matrix.multiplyMM(finalMvp, 0, viewMatrix, 0, tempMatrix, 0);
        Matrix.multiplyMM(finalMvp, 0, projMatrix, 0, finalMvp, 0);

        positionHandle  = GLES20.glGetAttribLocation(program, "aPosition");
        colorHandle     = GLES20.glGetAttribLocation(program, "aColor");
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, finalMvp, 0);

        // === Common position setup for fills ===
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(
                positionHandle,
                COORDS_PER_VERTEX,
                GLES20.GL_FLOAT,
                false,
                COORDS_PER_VERTEX * BYTES_PER_FLOAT,
                vertexBuffer
        );

        // --- 1) FILL PASS: solid red triangles ---
        GLES20.glEnableVertexAttribArray(colorHandle);
        GLES20.glVertexAttribPointer(
                colorHandle,
                COLOR_COMPONENTS,
                GLES20.GL_FLOAT,
                false,
                COLOR_COMPONENTS * BYTES_PER_FLOAT,
                colorBuffer
        );

        int vertexCount = ARROW_COORDS.length / COORDS_PER_VERTEX;
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount);

        // --- 2) OUTLINE PASS: white silhouette only ---
        // Use the EDGE_COORDS buffer
        edgeVertexBuffer.position(0);
        GLES20.glVertexAttribPointer(
                positionHandle,
                COORDS_PER_VERTEX,
                GLES20.GL_FLOAT,
                false,
                COORDS_PER_VERTEX * BYTES_PER_FLOAT,
                edgeVertexBuffer
        );

        // Constant white color for lines
        GLES20.glDisableVertexAttribArray(colorHandle);
        GLES20.glVertexAttrib4f(colorHandle, 1f, 1f, 1f, 1f);

        GLES20.glLineWidth(3.0f); // adjust thickness if you like
        GLES20.glDrawArrays(
                GLES20.GL_LINE_LOOP,
                0,
                EDGE_COORDS.length / COORDS_PER_VERTEX
        );

        GLES20.glDisableVertexAttribArray(positionHandle);
    }
}