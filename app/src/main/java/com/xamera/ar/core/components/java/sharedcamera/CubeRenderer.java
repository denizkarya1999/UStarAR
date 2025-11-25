package com.xamera.ar.core.components.java.sharedcamera;

import android.opengl.GLES20;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Simple OpenGL ES 2.0 cube renderer.
 * Supports rotation, scaling, and translation.
 */
public class CubeRenderer {

    private static final int BYTES_PER_FLOAT = 4;
    private static final int COORDS_PER_VERTEX = 3;

    private FloatBuffer vertexBuffer;
    private FloatBuffer colorBuffer;

    private int program;
    private int positionHandle;
    private int colorHandle;
    private int mvpMatrixHandle;

    // Model matrix
    private final float[] modelMatrix = new float[16];
    private final float[] tempMatrix = new float[16];
    private final float[] finalMvp = new float[16];

    // Transform
    private float posX = 0, posY = 0, posZ = 0;
    private float rotX = 0, rotY = 0, rotZ = 0;
    private float scale = 1f;

    public CubeRenderer() {
        Matrix.setIdentityM(modelMatrix, 0);
    }

    // 36 vertices (6 faces * 2 triangles * 3 vertices)
    private static final float[] CUBE_COORDS = {
            // Front
            -0.5f,-0.5f,0.5f,  0.5f,-0.5f,0.5f,  0.5f,0.5f,0.5f,
            -0.5f,-0.5f,0.5f,  0.5f,0.5f,0.5f, -0.5f,0.5f,0.5f,

            // Back
            -0.5f,-0.5f,-0.5f, -0.5f,0.5f,-0.5f, 0.5f,0.5f,-0.5f,
            -0.5f,-0.5f,-0.5f, 0.5f,0.5f,-0.5f, 0.5f,-0.5f,-0.5f,

            // Left
            -0.5f,-0.5f,-0.5f, -0.5f,-0.5f,0.5f, -0.5f,0.5f,0.5f,
            -0.5f,-0.5f,-0.5f, -0.5f,0.5f,0.5f, -0.5f,0.5f,-0.5f,

            // Right
            0.5f,-0.5f,-0.5f,  0.5f,0.5f,-0.5f,  0.5f,0.5f,0.5f,
            0.5f,-0.5f,-0.5f,  0.5f,0.5f,0.5f,  0.5f,-0.5f,0.5f,

            // Top
            -0.5f,0.5f,-0.5f, -0.5f,0.5f,0.5f, 0.5f,0.5f,0.5f,
            -0.5f,0.5f,-0.5f, 0.5f,0.5f,0.5f,  0.5f,0.5f,-0.5f,

            // Bottom
            -0.5f,-0.5f,-0.5f, 0.5f,-0.5f,-0.5f, 0.5f,-0.5f,0.5f,
            -0.5f,-0.5f,-0.5f, 0.5f,-0.5f,0.5f, -0.5f,-0.5f,0.5f
    };

    // Per-vertex RGBA colors
    private static final float[] CUBE_COLORS = {
            // Front - Red
            1,0,0,1, 1,0,0,1, 1,0,0,1,
            1,0,0,1, 1,0,0,1, 1,0,0,1,

            // Back - Green
            0,1,0,1, 0,1,0,1, 0,1,0,1,
            0,1,0,1, 0,1,0,1, 0,1,0,1,

            // Left - Blue
            0,0,1,1, 0,0,1,1, 0,0,1,1,
            0,0,1,1, 0,0,1,1, 0,0,1,1,

            // Right - Yellow
            1,1,0,1, 1,1,0,1, 1,1,0,1,
            1,1,0,1, 1,1,0,1, 1,1,0,1,

            // Top - Cyan
            0,1,1,1, 0,1,1,1, 0,1,1,1,
            0,1,1,1, 0,1,1,1, 0,1,1,1,

            // Bottom - Magenta
            1,0,1,1, 1,0,1,1, 1,0,1,1,
            1,0,1,1, 1,0,1,1, 1,0,1,1
    };

    // Vertex shader
    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;" +
                    "attribute vec4 aPosition;" +
                    "attribute vec4 aColor;" +
                    "varying vec4 vColor;" +
                    "void main() {" +
                    "  vColor = aColor;" +
                    "  gl_Position = uMVPMatrix * aPosition;" +
                    "}";

    // Fragment shader
    private static final String FRAGMENT_SHADER =
            "precision mediump float;" +
                    "varying vec4 vColor;" +
                    "void main() {" +
                    "  gl_FragColor = vColor;" +
                    "}";

    public void createOnGlThread() {
        // Create buffers
        ByteBuffer vb = ByteBuffer.allocateDirect(CUBE_COORDS.length * BYTES_PER_FLOAT);
        vb.order(ByteOrder.nativeOrder());
        vertexBuffer = vb.asFloatBuffer();
        vertexBuffer.put(CUBE_COORDS).position(0);

        ByteBuffer cb = ByteBuffer.allocateDirect(CUBE_COLORS.length * BYTES_PER_FLOAT);
        cb.order(ByteOrder.nativeOrder());
        colorBuffer = cb.asFloatBuffer();
        colorBuffer.put(CUBE_COLORS).position(0);

        // Create shader program
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
    }

    private int loadShader(int type, String code) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, code);
        GLES20.glCompileShader(shader);
        return shader;
    }

    /** Set cube rotation */
    public void setRotation(float x, float y, float z) {
        rotX = x;
        rotY = y;
        rotZ = z;
    }

    /** Set cube position */
    public void setPosition(float x, float y, float z) {
        posX = x;
        posY = y;
        posZ = z;
    }

    /** Set cube scale */
    public void setScale(float s) {
        scale = s;
    }

    /** Draw the cube using AR matrices */
    public void draw(float[] viewMatrix, float[] projMatrix, float[] anchorMatrix) {
        GLES20.glUseProgram(program);

        // Build local model matrix
        Matrix.setIdentityM(modelMatrix, 0);

        // Translate
        Matrix.translateM(modelMatrix, 0, posX, posY, posZ);

        // Rotate
        Matrix.rotateM(modelMatrix, 0, rotX, 1, 0, 0);
        Matrix.rotateM(modelMatrix, 0, rotY, 0, 1, 0);
        Matrix.rotateM(modelMatrix, 0, rotZ, 0, 0, 1);

        // Scale
        Matrix.scaleM(modelMatrix, 0, scale, scale, scale);

        // Combine: anchor → model
        Matrix.multiplyMM(tempMatrix, 0, anchorMatrix, 0, modelMatrix, 0);

        // Combine: view → temp
        Matrix.multiplyMM(finalMvp, 0, viewMatrix, 0, tempMatrix, 0);

        // Combine: proj → result
        Matrix.multiplyMM(finalMvp, 0, projMatrix, 0, finalMvp, 0);

        positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        colorHandle    = GLES20.glGetAttribLocation(program, "aColor");
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(
                positionHandle, COORDS_PER_VERTEX,
                GLES20.GL_FLOAT, false,
                COORDS_PER_VERTEX * BYTES_PER_FLOAT,
                vertexBuffer
        );

        GLES20.glEnableVertexAttribArray(colorHandle);
        GLES20.glVertexAttribPointer(
                colorHandle, 4,
                GLES20.GL_FLOAT, false,
                4 * BYTES_PER_FLOAT,
                colorBuffer
        );

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, finalMvp, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36);

        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(colorHandle);
    }
}