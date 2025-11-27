/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xamera.ar.core.components.java.sharedcamera;

import static android.hardware.camera2.CaptureRequest.CONTROL_EFFECT_MODE;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Session;
import com.google.ar.core.SharedCamera;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.sharedcamera.R;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableException;
import com.xamera.ar.core.components.java.common.helpers.CameraPermissionHelper;
import com.xamera.ar.core.components.java.common.helpers.DisplayRotationHelper;
import com.xamera.ar.core.components.java.common.helpers.FullScreenHelper;
import com.xamera.ar.core.components.java.common.helpers.SnackbarHelper;
import com.xamera.ar.core.components.java.common.helpers.TapHelper;
import com.xamera.ar.core.components.java.common.helpers.TrackingStateHelper;
import com.xamera.ar.core.components.java.common.rendering.BackgroundRenderer;
import com.xamera.ar.core.components.java.common.rendering.PlaneRenderer;
import com.xamera.ar.core.components.java.common.rendering.PointCloudRenderer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class SharedCameraActivity extends AppCompatActivity
        implements GLSurfaceView.Renderer,
        ImageReader.OnImageAvailableListener,
        SurfaceTexture.OnFrameAvailableListener {

  private static final String TAG = SharedCameraActivity.class.getSimpleName();

  // AR runs automatically.
  private boolean arMode = true;
  private final AtomicBoolean isFirstFrameWithoutArcore = new AtomicBoolean(true);

  // UI elements.
  private GLSurfaceView surfaceView;
  private TextView statusTextView;       // Not used (hidden)
  private LinearLayout imageTextLinearLayout;

  // ARCore session and shared camera.
  private Session sharedSession;
  private CameraCaptureSession captureSession;
  private CameraManager cameraManager;
  private List<CaptureRequest.Key<?>> keysThatCanCauseCaptureDelaysWhenModified;
  private CameraDevice cameraDevice;
  private HandlerThread backgroundThread;
  private Handler backgroundHandler;
  private SharedCamera sharedCamera;
  private String cameraId;
  private final AtomicBoolean shouldUpdateSurfaceTexture = new AtomicBoolean(false);
  private boolean arcoreActive;
  private boolean surfaceCreated;
  private boolean errorCreatingSession = false;
  private CaptureRequest.Builder previewCaptureRequestBuilder;
  private ImageReader cpuImageReader;
  private int cpuImagesProcessed;

  // Helper classes.
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);
  private TapHelper tapHelper;

  // Renderers.
  private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
  private final PlaneRenderer planeRenderer = new PlaneRenderer();
  private final PointCloudRenderer pointCloudRenderer = new PointCloudRenderer();

  // Custom cube renderer.
  private CubeRenderer cubeRenderer;

  // Arrow renderer to draw an arrow relative to cube.
  private ArrowRenderer arrowRenderer;

  // Tablet renderer: freeway-green sign (direction + distance).
  private DirectionTabletRenderer tabletRenderer;

  // Matrix for cube/arrow anchor pose.
  private final float[] anchorMatrix = new float[16];
  private final float[] arrowMatrix = new float[16];

  // Anchors created from taps / auto placement.
  private final ArrayList<ColoredAnchor> anchors = new ArrayList<>();

  // For testing.
  private static final Short AUTOMATOR_DEFAULT = 0;
  private static final String AUTOMATOR_KEY = "automator";
  private final AtomicBoolean automatorRun = new AtomicBoolean(false);

  // Synchronization.
  private boolean captureSessionChangesPossible = true;
  private final ConditionVariable safeToExitApp = new ConditionVariable();

  // Matrices (you can reuse for future renderers if needed).
  private final float[] mModelMatrix = new float[16];
  private final float[] mMVPMatrix = new float[16];

  private boolean autoAnchorCreated = false;

  // Parsed orientation/distance from txt file (for cube + arrow + tablet).
  private float currentOrientationAngleDeg = 0f; // 0° = north
  private int currentDistanceMeters = 1;         // 1–4m
  private String currentOrientationLabelStr = "North";

  // Helper class to associate an anchor with a color.
  private static class ColoredAnchor {
    public final Anchor anchor;
    public final float[] color;
    public ColoredAnchor(Anchor a, float[] color4f) {
      this.anchor = a;
      this.color = color4f;
    }
  }

  // ----- Camera and Session Callbacks -----
  private final CameraDevice.StateCallback cameraDeviceCallback =
          new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice cameraDevice) {
              Log.d(TAG, "Camera device ID " + cameraDevice.getId() + " opened.");
              SharedCameraActivity.this.cameraDevice = cameraDevice;
              createCameraPreviewSession();
            }
            @Override
            public void onClosed(@NonNull CameraDevice cameraDevice) {
              Log.d(TAG, "Camera device ID " + cameraDevice.getId() + " closed.");
              SharedCameraActivity.this.cameraDevice = null;
              safeToExitApp.open();
            }
            @Override
            public void onDisconnected(@NonNull CameraDevice cameraDevice) {
              Log.w(TAG, "Camera device ID " + cameraDevice.getId() + " disconnected.");
              cameraDevice.close();
              SharedCameraActivity.this.cameraDevice = null;
            }
            @Override
            public void onError(@NonNull CameraDevice cameraDevice, int error) {
              Log.e(TAG, "Camera device ID " + cameraDevice.getId() + " error " + error);
              cameraDevice.close();
              SharedCameraActivity.this.cameraDevice = null;
              finish();
            }
          };

  private final CameraCaptureSession.StateCallback cameraSessionStateCallback =
          new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
              Log.d(TAG, "Camera capture session configured.");
              captureSession = session;
              setRepeatingCaptureRequest();
            }
            @Override
            public void onSurfacePrepared(@NonNull CameraCaptureSession session, @NonNull Surface surface) {
              Log.d(TAG, "Camera capture surface prepared.");
            }
            @Override
            public void onReady(@NonNull CameraCaptureSession session) {
              Log.d(TAG, "Camera capture session ready.");
            }
            @Override
            public void onActive(@NonNull CameraCaptureSession session) {
              Log.d(TAG, "Camera capture session active.");
              if (arMode && !arcoreActive) {
                resumeARCore();
              }
              synchronized (SharedCameraActivity.this) {
                captureSessionChangesPossible = true;
                SharedCameraActivity.this.notify();
              }
            }
            @Override
            public void onCaptureQueueEmpty(@NonNull CameraCaptureSession session) {
              Log.w(TAG, "Camera capture queue empty.");
            }
            @Override
            public void onClosed(@NonNull CameraCaptureSession session) {
              Log.d(TAG, "Camera capture session closed.");
            }
            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
              Log.e(TAG, "Failed to configure camera capture session.");
            }
          };

  private final CameraCaptureSession.CaptureCallback cameraCaptureCallback =
          new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                           @NonNull CaptureRequest request,
                                           @NonNull TotalCaptureResult result) {
              shouldUpdateSurfaceTexture.set(true);
            }
            @Override
            public void onCaptureBufferLost(@NonNull CameraCaptureSession session,
                                            @NonNull CaptureRequest request,
                                            @NonNull Surface target,
                                            long frameNumber) {
              Log.e(TAG, "onCaptureBufferLost: " + frameNumber);
            }
            @Override
            public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureFailure failure) {
              Log.e(TAG, "onCaptureFailed: " + failure.getFrameNumber() + " " + failure.getReason());
            }
            @Override
            public void onCaptureSequenceAborted(@NonNull CameraCaptureSession session, int sequenceId) {
              Log.e(TAG, "onCaptureSequenceAborted: " + sequenceId + " " + session);
            }
          };

  // ----- Activity Lifecycle -----
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.ar_activity);

    // Disable sleeping while this activity is visible.
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    setContentView(R.layout.ar_activity);

    // (Disable status window.)
    Bundle extraBundle = getIntent().getExtras();
    if (extraBundle != null && 1 == extraBundle.getShort(AUTOMATOR_KEY, AUTOMATOR_DEFAULT)) {
      automatorRun.set(true);
    }

    surfaceView = findViewById(R.id.glsurfaceview);
    surfaceView.setPreserveEGLContextOnPause(true);
    surfaceView.setEGLContextClientVersion(2);
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
    surfaceView.setRenderer(this);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

    displayRotationHelper = new DisplayRotationHelper(this);
    tapHelper = new TapHelper(this);
    surfaceView.setOnTouchListener(tapHelper);

    imageTextLinearLayout = findViewById(R.id.image_text_layout);

    messageSnackbarHelper.setMaxLines(4);
  }

  @Override
  protected void onDestroy() {
    if (sharedSession != null) {
      sharedSession.close();
      sharedSession = null;
    }
    super.onDestroy();
  }

  private synchronized void waitUntilCameraCaptureSessionIsActive() {
    while (!captureSessionChangesPossible) {
      try {
        this.wait();
      } catch (InterruptedException e) {
        Log.e(TAG, "Unable to wait for a safe time to make changes to the capture session", e);
      }
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    waitUntilCameraCaptureSessionIsActive();
    startBackgroundThread();
    surfaceView.onResume();
    if (surfaceCreated) openCamera();
    displayRotationHelper.onResume();
  }

  @Override
  public void onPause() {
    shouldUpdateSurfaceTexture.set(false);
    surfaceView.onPause();
    waitUntilCameraCaptureSessionIsActive();
    displayRotationHelper.onPause();
    if (arMode) pauseARCore();
    closeCamera();
    stopBackgroundThread();
    super.onPause();
  }

  // ----- Camera and ARCore Methods -----
  private void resumeCamera2() {
    setRepeatingCaptureRequest();
    sharedCamera.getSurfaceTexture().setOnFrameAvailableListener(this);
  }

  private void resumeARCore() {
    if (sharedSession == null) return;
    if (!arcoreActive) {
      try {
        backgroundRenderer.suppressTimestampZeroRendering(false);
        sharedSession.resume();
        arcoreActive = true;
        sharedCamera.setCaptureCallback(cameraCaptureCallback, backgroundHandler);
      } catch (CameraNotAvailableException e) {
        Log.e(TAG, "Failed to resume ARCore session", e);
        return;
      }
    }
  }

  private void pauseARCore() {
    if (arcoreActive) {
      sharedSession.pause();
      isFirstFrameWithoutArcore.set(true);
      arcoreActive = false;
    }
  }

  private void setRepeatingCaptureRequest() {
    try {
      setCameraEffects(previewCaptureRequestBuilder);
      captureSession.setRepeatingRequest(previewCaptureRequestBuilder.build(),
              cameraCaptureCallback, backgroundHandler);
    } catch (CameraAccessException e) {
      Log.e(TAG, "Failed to set repeating request", e);
    }
  }

  private void createCameraPreviewSession() {
    try {
      sharedSession.setCameraTextureName(backgroundRenderer.getTextureId());
      sharedCamera.getSurfaceTexture().setOnFrameAvailableListener(this);
      previewCaptureRequestBuilder =
              cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
      List<Surface> surfaceList = sharedCamera.getArCoreSurfaces();
      surfaceList.add(cpuImageReader.getSurface());
      for (Surface surface : surfaceList) {
        previewCaptureRequestBuilder.addTarget(surface);
      }
      CameraCaptureSession.StateCallback wrappedCallback =
              sharedCamera.createARSessionStateCallback(cameraSessionStateCallback, backgroundHandler);
      cameraDevice.createCaptureSession(surfaceList, wrappedCallback, backgroundHandler);
    } catch (CameraAccessException e) {
      Log.e(TAG, "CameraAccessException", e);
    }
  }

  private void startBackgroundThread() {
    backgroundThread = new HandlerThread("sharedCameraBackground");
    backgroundThread.start();
    backgroundHandler = new Handler(backgroundThread.getLooper());
  }

  private void stopBackgroundThread() {
    if (backgroundThread != null) {
      backgroundThread.quitSafely();
      try {
        backgroundThread.join();
        backgroundThread = null;
        backgroundHandler = null;
      } catch (InterruptedException e) {
        Log.e(TAG, "Interrupted while trying to join background handler thread", e);
      }
    }
  }

  private void openCamera() {
    if (cameraDevice != null) return;
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      CameraPermissionHelper.requestCameraPermission(this);
      return;
    }
    if (!isARCoreSupportedAndUpToDate()) return;

    if (sharedSession == null) {
      try {
        sharedSession = new Session(this, EnumSet.of(Session.Feature.SHARED_CAMERA));
      } catch (Exception e) {
        errorCreatingSession = true;
        messageSnackbarHelper.showError(this,
                "Failed to create ARCore session that supports camera sharing");
        Log.e(TAG, "Failed to create ARCore session that supports camera sharing", e);
        return;
      }
      errorCreatingSession = false;
      Config config = sharedSession.getConfig();
      config.setFocusMode(Config.FocusMode.AUTO);
      sharedSession.configure(config);
    }

    sharedCamera = sharedSession.getSharedCamera();
    cameraId = sharedSession.getCameraConfig().getCameraId();
    Size desiredCpuImageSize = sharedSession.getCameraConfig().getImageSize();

    cpuImageReader = ImageReader.newInstance(desiredCpuImageSize.getWidth(),
            desiredCpuImageSize.getHeight(),
            ImageFormat.YUV_420_888, 2);
    cpuImageReader.setOnImageAvailableListener(this, backgroundHandler);
    sharedCamera.setAppSurfaces(this.cameraId, Arrays.asList(cpuImageReader.getSurface()));

    try {
      CameraDevice.StateCallback wrappedCallback =
              sharedCamera.createARDeviceStateCallback(cameraDeviceCallback, backgroundHandler);
      cameraManager = (CameraManager) this.getSystemService(Context.CAMERA_SERVICE);
      CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(this.cameraId);

      if (Build.VERSION.SDK_INT >= 28) {
        keysThatCanCauseCaptureDelaysWhenModified = characteristics.getAvailableSessionKeys();
        if (keysThatCanCauseCaptureDelaysWhenModified == null) {
          keysThatCanCauseCaptureDelaysWhenModified = new ArrayList<>();
        }
      }

      captureSessionChangesPossible = false;
      cameraManager.openCamera(cameraId, wrappedCallback, backgroundHandler);
    } catch (CameraAccessException | IllegalArgumentException | SecurityException e) {
      Log.e(TAG, "Failed to open camera", e);
    }
  }

  private <T> boolean checkIfKeyCanCauseDelay(CaptureRequest.Key<T> key) {
    if (Build.VERSION.SDK_INT >= 28) {
      return keysThatCanCauseCaptureDelaysWhenModified.contains(key);
    } else {
      Log.w(TAG, "Changing " + key +
              " may cause a noticeable capture delay. Please verify actual runtime behavior on specific pre-Android P devices.");
      return false;
    }
  }

  private void setCameraEffects(CaptureRequest.Builder captureBuilder) {
    if (checkIfKeyCanCauseDelay(CONTROL_EFFECT_MODE)) {
      Log.w(TAG, "Not setting CONTROL_EFFECT_MODE since it can cause delays between transitions.");
    } else {
      Log.d(TAG, "Setting CONTROL_EFFECT_MODE to SEPIA in non-AR mode.");
      captureBuilder.set(CONTROL_EFFECT_MODE,
              CaptureRequest.CONTROL_EFFECT_MODE_SEPIA);
    }
  }

  private void closeCamera() {
    if (captureSession != null) {
      captureSession.close();
      captureSession = null;
    }
    if (cameraDevice != null) {
      waitUntilCameraCaptureSessionIsActive();
      safeToExitApp.close();
      cameraDevice.close();
      safeToExitApp.block();
    }
    if (cpuImageReader != null) {
      cpuImageReader.close();
      cpuImageReader = null;
    }
  }

  @Override
  public void onFrameAvailable(SurfaceTexture surfaceTexture) {
    // Not used in AR mode.
  }

  @Override
  public void onImageAvailable(ImageReader imageReader) {
    Image image = imageReader.acquireLatestImage();
    if (image == null) {
      Log.w(TAG, "onImageAvailable: Skipping null image.");
      return;
    }
    image.close();
    cpuImagesProcessed++;
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      Toast.makeText(getApplicationContext(),
              "Camera permission is needed to run this application", Toast.LENGTH_LONG).show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    surfaceCreated = true;
    GLES20.glClearColor(0f, 0f, 0f, 1.0f);
    GLES20.glEnable(GLES20.GL_DEPTH_TEST);

    try {
      backgroundRenderer.createOnGlThread(this);
      planeRenderer.createOnGlThread(this, "models/trigrid.png");
      pointCloudRenderer.createOnGlThread(this);

      // Initialize cube renderer.
      cubeRenderer = new CubeRenderer();
      cubeRenderer.createOnGlThread();

      // Initialize arrow renderer.
      arrowRenderer = new ArrowRenderer();
      arrowRenderer.createOnGlThread();
      arrowRenderer.setColor(0.698f, 0.871f, 0.153f, 1f);  // 178,222,39

      // Initialize tablet renderer (freeway-green sign).
      tabletRenderer = new DirectionTabletRenderer();
      tabletRenderer.createOnGlThread();
      tabletRenderer.setPosition(0f, 0.22f, 0f);
      tabletRenderer.setScale(0.18f);

      openCamera();
    } catch (IOException e) {
      Log.e(TAG, "Failed to read an asset file", e);
    }
  }

  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    GLES20.glViewport(0, 0, width, height);
    displayRotationHelper.onSurfaceChanged(width, height);
    runOnUiThread(() ->
            imageTextLinearLayout.setOrientation(width > height ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL));
  }

  @Override
  public void onDrawFrame(GL10 gl) {
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
    if (!shouldUpdateSurfaceTexture.get()) return;
    displayRotationHelper.updateSessionIfNeeded(sharedSession);
    try {
      if (arMode) {
        onDrawFrameARCore();
      } else {
        onDrawFrameCamera2();
      }
    } catch (Throwable t) {
      Log.e(TAG, "Exception on the OpenGL thread", t);
    }
  }

  public void onDrawFrameCamera2() {
    SurfaceTexture texture = sharedCamera.getSurfaceTexture();
    if (isFirstFrameWithoutArcore.getAndSet(false)) {
      try {
        texture.detachFromGLContext();
      } catch (RuntimeException e) {
        // Ignore if fails.
      }
      texture.attachToGLContext(backgroundRenderer.getTextureId());
    }
    texture.updateTexImage();
    int rotationDegrees = displayRotationHelper.getCameraSensorToDisplayRotation(cameraId);
    Size size = sharedSession.getCameraConfig().getTextureSize();
    float displayAspectRatio = displayRotationHelper.getCameraSensorRelativeViewportAspectRatio(cameraId);
    backgroundRenderer.draw(size.getWidth(), size.getHeight(), displayAspectRatio, rotationDegrees);
  }

  public void onDrawFrameARCore() throws CameraNotAvailableException {
    if (!arcoreActive || errorCreatingSession) return;

    // 1) Read txt and update distance/orientation fields.
    refreshArrowFromFile();

    // 2) Update the tablet's texture with the latest values.
    if (tabletRenderer != null) {
      tabletRenderer.updateFromValues(currentDistanceMeters, currentOrientationLabelStr);
    }

    Frame frame = sharedSession.update();
    Camera camera = frame.getCamera();

    backgroundRenderer.draw(frame);

    float[] projmtx = new float[16];
    float[] viewmtx = new float[16];
    camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f);
    camera.getViewMatrix(viewmtx, 0);

    // Camera world position (for billboard sign).
    float[] invView = new float[16];
    Matrix.invertM(invView, 0, viewmtx, 0);
    float camX = invView[12];
    float camY = invView[13];
    float camZ = invView[14];

    // Create anchor at screen center (once).
    if (!autoAnchorCreated && camera.getTrackingState() == TrackingState.TRACKING) {
      final int centerX = surfaceView.getWidth() / 2;
      final int centerY = surfaceView.getHeight() / 2;
      for (HitResult hit : frame.hitTest(centerX, centerY)) {
        Trackable trackable = hit.getTrackable();
        if (((trackable instanceof Plane)
                && ((Plane) trackable).isPoseInPolygon(hit.getHitPose())
                && (PlaneRenderer.calculateDistanceToPlane(hit.getHitPose(), camera.getPose()) > 0))
                ||
                ((trackable instanceof Point)
                        && ((Point) trackable).getOrientationMode()
                        == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)) {
          if (anchors.size() >= 20) {
            anchors.get(0).anchor.detach();
            anchors.remove(0);
          }
          anchors.add(new ColoredAnchor(hit.createAnchor(), new float[]{255f, 255f, 255f, 255f}));
          autoAnchorCreated = true;
          break;
        }
      }
    }

    if (cubeRenderer != null) {
      float angleY = currentOrientationAngleDeg;

      float distScale = 0.10f;
      float r = currentDistanceMeters * distScale;
      double rad = Math.toRadians(currentOrientationAngleDeg);

      float offsetX = (float) (r * Math.sin(rad));
      float offsetZ = (float) (r * Math.cos(rad));

      for (ColoredAnchor coloredAnchor : anchors) {
        if (coloredAnchor.anchor.getTrackingState() != TrackingState.TRACKING) {
          continue;
        }

        float[] baseMatrix = new float[16];
        coloredAnchor.anchor.getPose().toMatrix(baseMatrix, 0);
        float anchorX = baseMatrix[12];
        float anchorY = baseMatrix[13];
        float anchorZ = baseMatrix[14];

        // ----- TABLET: fixed above anchor, facing camera -----
        if (tabletRenderer != null) {
          float[] tabletMatrix = new float[16];

          float dxCam = camX - anchorX;
          float dzCam = camZ - anchorZ;
          float yawToCam = (float) Math.toDegrees(Math.atan2(dxCam, dzCam));

          Matrix.setIdentityM(tabletMatrix, 0);
          Matrix.translateM(tabletMatrix, 0, anchorX, anchorY + 0.25f, anchorZ);
          Matrix.rotateM(tabletMatrix, 0, yawToCam, 0f, 1f, 0f);

          tabletRenderer.draw(viewmtx, projmtx, tabletMatrix);
        }

        // ----- CUBE: position + rotation from txt -----
        System.arraycopy(baseMatrix, 0, anchorMatrix, 0, 16);
        Matrix.translateM(anchorMatrix, 0, offsetX, 0.10f, offsetZ);

        cubeRenderer.setScale(0.05f);
        cubeRenderer.setRotation(0f, angleY, 0f);
        cubeRenderer.setPosition(0f, 0f, 0f);
        cubeRenderer.draw(viewmtx, projmtx, anchorMatrix);

        // ----- ARROW on top of cube -----
        if (arrowRenderer != null) {
          System.arraycopy(anchorMatrix, 0, arrowMatrix, 0, 16);
          Matrix.translateM(arrowMatrix, 0, 0f, 0.07f, 0f);

          arrowRenderer.setScale(0.05f);
          arrowRenderer.setRotation(0f, angleY, 0f);
          arrowRenderer.setPosition(0f, 0f, 0f);
          arrowRenderer.draw(viewmtx, projmtx, arrowMatrix);
        }
      }
    }
  }

  private float orientationToAngle(String ori) {
    if (ori == null) return 0f;
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
      default:           return   0f;
    }
  }

  /**
   * Reads UStar_Cube_Prediction.txt and updates
   * currentOrientationAngleDeg, currentDistanceMeters, currentOrientationLabelStr.
   */
  private void refreshArrowFromFile() {
    File logFile = new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "UStar_Cube_Prediction.txt"
    );

    String rawText;
    try {
      if (!logFile.exists()) {
        rawText =
                "UStar UIOD Tag Features\n" +
                        "Prediction Date: N/A\n" +
                        "OpenCV Initialization Status: false\n" +
                        "Distance: 1M | Orientation: North";
      } else {
        FileInputStream fis = new FileInputStream(logFile);
        java.io.BufferedReader br =
                new java.io.BufferedReader(new java.io.InputStreamReader(fis));

        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
          sb.append(line).append('\n');
        }
        br.close();
        fis.close();

        String read = sb.toString().trim();
        if (read.isEmpty()) {
          rawText =
                  "UStar UIOD Tag Features\n" +
                          "Prediction Date: N/A\n" +
                          "OpenCV Initialization Status: false\n" +
                          "Distance: 1M | Orientation: North";
        } else {
          rawText = read;
        }
      }
    } catch (Exception e) {
      rawText =
              "UStar UIOD Tag Features\n" +
                      "Prediction Date: N/A\n" +
                      "OpenCV Initialization Status: false\n" +
                      "Distance: 1M | Orientation: North";
    }

    Integer distanceMeters = null;
    String orientationLabel = "North";

    for (String line : rawText.split("\n")) {
      if (line.trim().toLowerCase().startsWith("distance:")) {
        String[] parts = line.split("\\|");

        if (parts.length > 0) {
          java.util.regex.Matcher m = java.util.regex.Pattern
                  .compile("Distance:\\s*(\\d+)\\s*[Mm]")
                  .matcher(parts[0]);
          if (m.find()) {
            try {
              distanceMeters = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
          }
        }

        if (parts.length > 1) {
          java.util.regex.Matcher mOri = java.util.regex.Pattern
                  .compile("Orientation:\\s*([A-Za-z]+)")
                  .matcher(parts[1]);
          if (mOri.find()) {
            orientationLabel = mOri.group(1);
          }
        }
      }
    }

    if (distanceMeters == null) distanceMeters = 1;
    distanceMeters = Math.max(1, Math.min(4, distanceMeters));

    currentDistanceMeters = distanceMeters;
    currentOrientationAngleDeg = orientationToAngle(orientationLabel);
    currentOrientationLabelStr = orientationLabel;
  }

  private boolean isARCoreSupportedAndUpToDate() {
    ArCoreApk.Availability availability = ArCoreApk.getInstance().checkAvailability(this);
    switch (availability) {
      case SUPPORTED_INSTALLED:
        break;
      case SUPPORTED_APK_TOO_OLD:
      case SUPPORTED_NOT_INSTALLED:
        try {
          ArCoreApk.InstallStatus installStatus =
                  ArCoreApk.getInstance().requestInstall(this, true);
          if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
            Log.e(TAG, "ARCore installation requested.");
            return false;
          }
        } catch (UnavailableException e) {
          Log.e(TAG, "ARCore not installed", e);
          runOnUiThread(() ->
                  Toast.makeText(getApplicationContext(), "ARCore not installed\n" + e, Toast.LENGTH_LONG).show());
          finish();
          return false;
        }
        break;
      case UNKNOWN_ERROR:
      case UNKNOWN_CHECKING:
      case UNKNOWN_TIMED_OUT:
      case UNSUPPORTED_DEVICE_NOT_CAPABLE:
        Log.e(TAG, "ARCore not supported: " + availability);
        runOnUiThread(() ->
                Toast.makeText(getApplicationContext(), "ARCore not supported: " + availability, Toast.LENGTH_LONG).show());
        return false;
    }
    return true;
  }
}