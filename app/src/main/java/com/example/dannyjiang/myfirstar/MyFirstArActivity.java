package com.example.dannyjiang.myfirstar;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Toast;

import com.example.dannyjiang.myfirstar.rendering.BackgroundRenderer;
import com.example.dannyjiang.myfirstar.rendering.ObjectRenderer;
import com.example.dannyjiang.myfirstar.rendering.PlaneRenderer;
import com.example.dannyjiang.myfirstar.utils.CameraPermissionHelper;
import com.example.dannyjiang.myfirstar.utils.TapHelper;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;

import java.io.IOException;
import java.util.ArrayList;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MyFirstArActivity extends AppCompatActivity implements GLSurfaceView.Renderer {

    private static final String TAG = MyFirstArActivity.class.getSimpleName();

    // Surface View
    private GLSurfaceView surfaceView;
    private TapHelper tapHelper;

    /*
     * AR world
      */
    // ARCore中的核心类，用来处理与设备Camera的一系列交互
    private Session session;
    // 用来绘制背景的Renderer封装类
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    // 用来绘制AR Plane的Renderer封装类
    private final PlaneRenderer planeRenderer = new PlaneRenderer();
    // 用来绘制Virtual Object的Renderer封装类
    private final ObjectRenderer virtualObject = new ObjectRenderer();
    // Anchors created from taps used for object placing.
    private final ArrayList<Anchor> anchors = new ArrayList<>();
    // Temporary matrix allocated here to reduce number of allocations for each frame.
    private final float[] anchorMatrix = new float[16];

    // Permission stuff
    private boolean installRequested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my_first_ar);

        // 初始化GLSurfaceView，并跟TapHelper绑定
        surfaceView = findViewById(R.id.glSurfaceView);
        tapHelper = new TapHelper(/*context=*/ this);
        surfaceView.setOnTouchListener(tapHelper);

        // 配置GLSurfaceView基本属性, 并设置renderer.
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (session == null) {
            try {
                switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    case INSTALL_REQUESTED:
                        installRequested = true;
                        return;
                    case INSTALLED:
                        break;
                }

                // ARCore需要申请并处理Camera的操作，因此必须动态申请Camera相关的权限
                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this);
                    return;
                }

                // 创建session对象，Session是ARCore中真正用来与设备Camera进行打交道的类
                // 内部实现中进行了Camera的相关读取与计算，之后通过它可以获取Camera的当前帧Frame
                session = new Session(/* context= */ this);
            } catch (Exception e) {
                Log.e(TAG, "Failed to create AR session: " + e.getMessage());
                return;
            }
        }

        try {
            session.resume();
        } catch (CameraNotAvailableException e) {
            // 有些情况下，手机Camera正在被其它的App所使用。这种情况下可能会报Camera Not Available异常
            session = null;
            return;
        }

        surfaceView.onResume();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
                    .show();
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this);
            }
            finish();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (session != null) {
            // 注意：顺序不能改变！必须先暂停GLSurfaceView, 否则GLSurfaceView会继续调用Session的update方法。
            // 但是Session已经pause状态，所以会报SessionPausedException异常
            surfaceView.onPause();
            session.pause();
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        try {
            // 初始化用来画背景以及Virtual Object的OpenGL设置
            // 主要包括各种OpenGL需要使用的textureId, Texture Coordinates, Shader, Program等
            backgroundRenderer.createOnGlThread(this);

            // 初始化用来绘制Plane的Renderer对象
            planeRenderer.createOnGlThread(/*context=*/ this, "models/trigrid.png");

            // 初始化用来绘制3D Virtual Object的Renderer对象
            virtualObject.createOnGlThread(/*context=*/ this, "models/andy.obj", "models/andy.png");
            virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read an asset file", e);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);

        // 当SurfaceView发生change时，需要重新设置Session的rotation, width, height
        int displayRotation = getSystemService(WindowManager.class).getDefaultDisplay().getRotation();
        session.setDisplayGeometry(displayRotation, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (session == null) {
            return;
        }

        try {
            // 将在'createOnGlThread'方法中已经初始化好的Texture Handle(句柄)传给AR Session
            // 如果没有设置此句柄，则会显示黑屏。
            session.setCameraTextureName(backgroundRenderer.getTextureId());

            // 通过AR Session获取当前手机摄像头(Camera)的当前帧(Frame)。
            Frame frame = session.update();
            // 通过当前帧Frame对象，可以获取ARCore所捕捉到的Camera对象
            Camera camera = frame.getCamera();

            /**
             * 使用TapHelper从队列中获取一次点击事件，并根据此点击事件的坐标创建一个Anchor
             * Anchor可以自行记录它在AR世界中的位置，后续绘制Virtual Object时就可以
             * 根据此Anchor来确定Virtual Object的位置，
             * 具体API为：anchor.getPose().toMatrix(anchorMatrix, 0); 通过这一行代码
             * 就可以将Anchor所对应的位置保存在anchorMatrix数组中
             */
            MotionEvent tap = tapHelper.poll();
            if (tap != null && camera.getTrackingState() == TrackingState.TRACKING) {
                for (HitResult hit : frame.hitTest(tap)) {
                    // Check if any plane was hit, and if it was hit inside the plane polygon
                    Trackable trackable = hit.getTrackable();
                    // Creates an anchor if a plane or an oriented point was hit.
                    if ((trackable instanceof Plane
                            && ((Plane) trackable).isPoseInPolygon(hit.getHitPose())
                            && (PlaneRenderer.calculateDistanceToPlane(hit.getHitPose(), camera.getPose())
                            > 0))
                            || (trackable instanceof Point
                            && ((Point) trackable).getOrientationMode()
                            == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL)) {
                        // 如果队列的长度已经等于20，则将第一个Anchor给detach掉, 并从队里中移除
                        if (anchors.size() >= 20) {
                            anchors.get(0).detach();
                            anchors.remove(0);
                        }
                        // 使用HitResult创建出一个Anchor对象，并添加到队列中
                        // 因为HitResult是由点击事件MotionEvent而创建出的
                        // 所以此Anchor会记录它所在AR World中的具体位置
                        anchors.add(hit.createAnchor());
                        break;
                    }
                }
            }

            // 将当前帧Frame当做背景来draw到SurfaceView上，因此我们能在手机屏幕上看到摄像头中的实时内容
            backgroundRenderer.draw(frame);

            // 在具体使用Camera对象之前需要先判断当前Camera是否处于Tracking状态
            // 如果不是，则不需要绘制3D Virtual Object
            if (camera.getTrackingState() == TrackingState.PAUSED) {
                return;
            }
            // Get projection matrix.
            float[] projmtx = new float[16];
            camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f);

            // Get camera matrix and draw.
            float[] viewmtx = new float[16];
            camera.getViewMatrix(viewmtx, 0);

            // 绘制ARCore识别出的Planes.
            planeRenderer.drawPlanes(
                    session.getAllTrackables(Plane.class), camera.getDisplayOrientedPose(), projmtx);

            // Compute lighting from average intensity of the image.
            // The first three components are color scaling factors.
            // The last one is the average pixel intensity in gamma space.
            final float[] colorCorrectionRgba = new float[4];
            frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

            for (Anchor anchor : anchors) {
                if (anchor.getTrackingState() != TrackingState.TRACKING) {
                    continue;
                }

                // Get the current pose of an Anchor in world space. The Anchor pose is updated
                // during calls to session.update() as ARCore refines its estimate of the world.
                anchor.getPose().toMatrix(anchorMatrix, 0);

                // Update and draw the model and its shadow.
                virtualObject.updateModelMatrix(anchorMatrix, 1.0f);
                virtualObject.draw(viewmtx, projmtx, colorCorrectionRgba);
            }
        } catch (Exception e) {

        }
    }
}
