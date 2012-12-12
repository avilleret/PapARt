package fr.inria.bordeaux.rv;

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */


import codeanticode.glgraphics.GLGraphicsOffScreen;
import codeanticode.glgraphics.GLTexture;
import codeanticode.glgraphics.GLTextureFilter;
import com.googlecode.javacv.ProjectiveDevice;
import java.util.ArrayList;
import javax.media.opengl.GL;
import processing.core.PApplet;
import processing.core.PImage;
import processing.core.PMatrix3D;

/**
 *
 * @author jeremy
 */
public class ARDisplay {

    protected PApplet parent;
    public GLGraphicsOffScreen graphics;
    // TODO: this has to be useless.
    protected GLTexture finalImage;
    // Projector information
    protected ProjectiveDevice proj;
    protected PMatrix3D projIntrinsicsP3D, projExtrinsicsP3D, projExtrinsicsP3DInv;
    // Resolution
    protected int frameWidth, frameHeight;
    // OpenGL information
    protected float[] projectionMatrixGL = new float[16];
    protected GLTexture myMap;
    protected PMatrix3D projectionInit;
    protected GLTextureFilter lensFilter;
    protected GL gl = null;
    protected PMatrix3D invProjModelView;
    protected float znear;
    protected float zfar;

    public ARDisplay(PApplet parent, String calibrationYAML,
            int width, int height, float near, float far, int AA) {

        frameWidth = width;
        frameHeight = height;
        this.parent = parent;
        this.znear = near;
        this.zfar = far;

        // create the offscreen rendering for this projector.
        if (AA > 0) {
            this.graphics = new GLGraphicsOffScreen(parent, width, height, true, AA);
        } else {
            this.graphics = new GLGraphicsOffScreen(parent, width, height);
        }

        loadInternalParams(calibrationYAML);
        initProjection();
        computeInv();
        initDistortMap();
    }

    protected void loadInternalParams(String calibrationYAML) {
        // Load the camera parameters.
        try {

            ProjectiveDevice[] p = ProjectiveDevice.read(calibrationYAML);
            if (p.length > 0) {
                proj = p[0];
            }

            double[] projMat = proj.cameraMatrix.get();
            double[] projR = proj.R.get();
            double[] projT = proj.T.get();
            projIntrinsicsP3D = new PMatrix3D((float) projMat[0], (float) projMat[1], (float) projMat[2], 0,
                    (float) projMat[3], (float) projMat[4], (float) projMat[5], 0,
                    (float) projMat[6], (float) projMat[7], (float) projMat[8], 0,
                    0, 0, 0, 1);
            projExtrinsicsP3D = new PMatrix3D((float) projR[0], (float) projR[1], (float) projR[2], (float) projT[0],
                    (float) projR[3], (float) projR[4], (float) projR[5], (float) projT[1],
                    (float) projR[6], (float) projR[7], (float) projR[8], (float) projT[2],
                    0, 0, 0, 1);

            projExtrinsicsP3DInv = projExtrinsicsP3D.get();
            projExtrinsicsP3DInv.invert();

        } catch (Exception e) {
            // TODO: Exception creation !!
            System.out.println("Error !!!!!");
            System.err.println("Error reading the calibration file : " + calibrationYAML + " \n" + e);
        }
    }

    private void initProjection() {
        float p00, p11, p02, p12;

        // ----------- OPENGL --------------
        // Reusing the internal projective parameters for the scene rendering.

        PMatrix3D oldProj = this.graphics.projection.get();

        // Working params
        p00 = 2 * projIntrinsicsP3D.m00 / frameWidth;
        p11 = 2 * projIntrinsicsP3D.m11 / frameHeight;
        p02 = -((projIntrinsicsP3D.m02 / frameWidth) * 2 - 1);
//        p02 = -(2 * projIntrinsicsP3D.m02 / frameWidth  - 1);
//        p12 = -(2 * projIntrinsicsP3D.m12 / frameHeight - 1);
        p12 = -((projIntrinsicsP3D.m12 / frameHeight) * 2 - 1);

        this.graphics.beginDraw();

        // TODO: magic numbers !!!
        this.graphics.frustum(0, 0, 0, 0, znear, zfar);
        this.graphics.projection.m00 = p00;
        this.graphics.projection.m11 = p11;
        this.graphics.projection.m02 = p02;
        this.graphics.projection.m12 = p12;

        // Save these good parameters
        projectionInit = this.graphics.projection.get();

        this.graphics.projection.transpose();
        this.graphics.projection.get(projectionMatrixGL);

        this.graphics.projection.set(oldProj);
        this.graphics.endDraw();
    }

    private void computeInv() {
        invProjModelView = getProjectionInit().get();
        invProjModelView.scale(1, 1, -1);
        // Set to the origin, as the plane was computed from the origin
        invProjModelView.apply(getExtrinsics());

        // invert for the inverse projection
        invProjModelView.invert();
    }

    /**
     * graphics.modelview.apply(projExtrinsicsP3D);
     *
     * @return
     */
    public PMatrix3D getExtrinsics() {
        return projExtrinsicsP3D;
    }

    /**
     * This function initializes the distorsion map used by the distorsion
     * shader. The texture is of the size of the projector resolution.
     *
     * @param proj
     */
    private void initDistortMap() {
        lensFilter = new GLTextureFilter(parent, "projDistort.xml");
        finalImage = new GLTexture(parent, frameWidth, frameHeight);

        myMap = new GLTexture(parent, frameWidth, frameHeight, GLTexture.FLOAT);
        float[] mapTmp = new float[frameWidth * frameHeight * 3];
        int k = 0;

        for (int y = 0; y < frameHeight; y++) {
            for (int x = 0; x < frameWidth; x++) {

                double[] out = proj.undistort(x, y);
//                double[] out = proj.distort(x, y);
                mapTmp[k++] = (float) (out[0] / frameWidth);
                mapTmp[k++] = (float) (out[1] / frameHeight);
                mapTmp[k++] = 0;
            }
        }

        myMap.putBuffer(mapTmp, 3);
    }

    // Actual GLGraphics BUG :  projection has to be loaded directly into OpenGL.
    public void loadProjection() {
        gl = this.graphics.beginGL();
        gl.glMatrixMode(GL.GL_PROJECTION);
        gl.glPushMatrix();
        gl.glLoadMatrixf(projectionMatrixGL, 0);
        gl.glMatrixMode(GL.GL_MODELVIEW);
        this.graphics.endGL();
    }

    public void unLoadProjection() {
        gl = this.graphics.beginGL();
        gl.glMatrixMode(GL.GL_PROJECTION);
        gl.glPopMatrix();
        gl.glMatrixMode(GL.GL_MODELVIEW);
        this.graphics.endGL();
    }

    public PMatrix3D getProjectionInit() {
        return this.projectionInit;
    }

    public ProjectiveDevice getProjectiveDevice() {
        return this.proj;
    }

    public GLGraphicsOffScreen beginDraw() {

        ////////  3D PROJECTION  //////////////
        this.graphics.beginDraw();

        // clear the screen
        this.graphics.clear(0, 0);

        // load the projector parameters into OpenGL
        loadProjection();

        // make the modelview matrix as the default matrix
        this.graphics.resetMatrix();

        // Setting the projector as a projector (inverse camera)
        this.graphics.scale(1, 1, -1);

        return this.graphics;
    }

    public void endDraw() {

        // Put the projection matrix back to normal
        unLoadProjection();
        this.graphics.endDraw();

    }

    public PImage distort(boolean distort) {
//        return graphics.getTexture();

        if (!distort) {
//            System.out.println("No distort");
            return this.graphics.getTexture();
        }

        // TODO: BUG : works once, cannot be disabled and enabled
        GLTexture off = this.graphics.getTexture();
//        lensFilter.apply(new GLTexture[]{off, myMap}, off);
        lensFilter.apply(new GLTexture[]{off, myMap}, finalImage);
//        lensFilter.apply(new GLTexture[]{off, myMap}, off);

        return finalImage;
    }

    public GLGraphicsOffScreen getGraphics() {
        return this.graphics;
    }

    public int getWidth() {
        return frameWidth;
    }

    public int getHeight() {
        return frameHeight;
    }
}
