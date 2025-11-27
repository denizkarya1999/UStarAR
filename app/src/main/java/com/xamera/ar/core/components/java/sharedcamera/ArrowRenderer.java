package com.xamera.ar.core.components.java.sharedcamera;

import android.opengl.GLES20;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 3D Arrow Renderer (Green Arrow).
 *
 * Shape:
 *  - Rectangular shaft (0.1 x 0.3 x 0.1)
 *  - Larger pyramid arrow head
 *  - Arrow points upward toward +Y
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
     * Arrow coordinates
     * Shaft height = 0.3
     * Head height = 0.15 (bigger head)
     *
     * Total height ≈ 0.45
     */
    private static final float[] ARROW_COORDS = {

            // ------- SHAFT (rectangular prism) -------
            // y from 0.0 to 0.3  (height 0.3)

            // Front face
            -0.05f, 0.0f,  0.05f,
            0.05f, 0.0f,  0.05f,
            0.05f, 0.3f,  0.05f,

            -0.05f, 0.0f,  0.05f,
            0.05f, 0.3f,  0.05f,
            -0.05f, 0.3f,  0.05f,

            // Back
            -0.05f, 0.0f, -0.05f,
            -0.05f, 0.3f, -0.05f,
            0.05f, 0.3f, -0.05f,

            -0.05f, 0.0f, -0.05f,
            0.05f, 0.3f, -0.05f,
            0.05f, 0.0f, -0.05f,

            // Left
            -0.05f, 0.0f, -0.05f,
            -0.05f, 0.0f,  0.05f,
            -0.05f, 0.3f,  0.05f,

            -0.05f, 0.0f, -0.05f,
            -0.05f, 0.3f,  0.05f,
            -0.05f, 0.3f, -0.05f,

            // Right
            0.05f, 0.0f, -0.05f,
            0.05f, 0.3f, -0.05f,
            0.05f, 0.3f,  0.05f,

            0.05f, 0.0f, -0.05f,
            0.05f, 0.3f,  0.05f,
            0.05f, 0.0f,  0.05f,

            // ------- ARROW HEAD (bigger pyramid) -------
            // Base at y = 0.3, apex at y = 0.45
            // Base width/depth = 0.2 (±0.10)

            // Front face
            0.0f, 0.45f,  0.0f,
            -0.10f, 0.3f,  0.10f,
            0.10f, 0.3f,  0.10f,

            // Right face
            0.0f, 0.45f,  0.0f,
            0.10f, 0.3f,  0.10f,
            0.10f, 0.3f, -0.10f,

            // Back face
            0.0f, 0.45f,  0.0f,
            0.10f, 0.3f, -0.10f,
            -0.10f, 0.3f, -0.10f,

            // Left face
            0.0f, 0.45f,  0.0f,
            -0.10f, 0.3f, -0.10f,
            -0.10f, 0.3f,  0.10f,
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

        // Color buffer — default: solid green
        int vertexCount = ARROW_COORDS.length / COORDS_PER_VERTEX;
        ByteBuffer cb = ByteBuffer.allocateDirect(vertexCount * COLOR_COMPONENTS * BYTES_PER_FLOAT);
        cb.order(ByteOrder.nativeOrder());
        colorBuffer = cb.asFloatBuffer();
        setColor(0f, 1f, 0f, 1f);  // bright green

        // Load shaders
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

    public void setRotation(float x, float y, float z) { rotX = x; rotY = y; rotZ = z; }

    public void setPosition(float x, float y, float z) { posX = x; posY = y; posZ = z; }

    public void setScale(float s) { scale = s; }

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

        // Local Model Matrix
        Matrix.setIdentityM(modelMatrix, 0);
        Matrix.translateM(modelMatrix, 0, posX, posY, posZ);
        Matrix.rotateM(modelMatrix, 0, rotX, 1, 0, 0);
        Matrix.rotateM(modelMatrix, 0, rotY, 0, 1, 0);
        Matrix.rotateM(modelMatrix, 0, rotZ, 0, 0, 1);
        Matrix.scaleM(modelMatrix, 0, scale, scale, scale);

        // anchor → model
        Matrix.multiplyMM(tempMatrix, 0, anchorMatrix, 0, modelMatrix, 0);

        // view
        Matrix.multiplyMM(finalMvp, 0, viewMatrix, 0, tempMatrix, 0);

        // projection
        Matrix.multiplyMM(finalMvp, 0, projMatrix, 0, finalMvp, 0);

        // Bind attribute
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        colorHandle = GLES20.glGetAttribLocation(program, "aColor");
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, COORDS_PER_VERTEX,
                GLES20.GL_FLOAT, false, COORDS_PER_VERTEX * BYTES_PER_FLOAT, vertexBuffer);

        GLES20.glEnableVertexAttribArray(colorHandle);
        GLES20.glVertexAttribPointer(colorHandle, COLOR_COMPONENTS,
                GLES20.GL_FLOAT, false, COLOR_COMPONENTS * BYTES_PER_FLOAT, colorBuffer);

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, finalMvp, 0);

        // Draw triangles
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0,
                ARROW_COORDS.length / COORDS_PER_VERTEX);

        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(colorHandle);
    }
}
