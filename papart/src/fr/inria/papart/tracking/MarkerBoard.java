/*
 * Part of the PapARt project - https://project.inria.fr/papart/
 *
 * Copyright (C) 2014-2016 Inria
 * Copyright (C) 2011-2013 Bordeaux University
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation, version 2.1.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General
 * Public License along with this library; If not, see
 * <http://www.gnu.org/licenses/>.
 */
package fr.inria.papart.tracking;

import fr.inria.papart.procam.camera.Camera;
import fr.inria.papart.procam.display.ProjectorDisplay;
import fr.inria.papart.procam.display.ARDisplay;
import fr.inria.papart.multitouch.OneEuroFilter;
import fr.inria.papart.tracking.ObjectFinder;
import org.bytedeco.javacpp.ARToolKitPlus;
import org.bytedeco.javacpp.opencv_core.IplImage;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bytedeco.javacpp.ARToolKitPlus.TrackerMultiMarker;
import static org.bytedeco.javacpp.opencv_imgcodecs.cvLoadImage;
import processing.core.PApplet;
import processing.core.PMatrix3D;
import processing.core.PVector;

/**
 *
 * @author jeremylaviole
 */
public abstract class MarkerBoard {

    protected final String fileName;
    protected float width;
    protected float height;
    protected ArrayList<Camera> cameras;
    protected ArrayList<Boolean> drawingMode;
    protected ArrayList<Float> minDistanceDrawingMode;
    protected ArrayList<PMatrix3D> transfos = new ArrayList<PMatrix3D>();
    protected ArrayList trackers;
    protected ArrayList<OneEuroFilter[]> filters;
    protected ArrayList<PVector> lastPos;
    protected ArrayList<Float> lastDistance;
    protected ArrayList<Integer> nextTimeEvent;
    protected ArrayList<Integer> updateStatus;
    protected PApplet applet;

    protected MarkerType type = null;

    protected static final int updateTime = 1000; // 1 sec
    static public final int NORMAL = 1;
    static public final int BLOCK_UPDATE = 2;
    static public final int FORCE_UPDATE = 3;
    static public final PMatrix3D INVALID_LOCATION = new PMatrix3D();

    
    public enum MarkerType {

        ARTOOLKITPLUS, JAVACV_FINDER, SVG, INVALID
    }
    
    protected MarkerBoard(){
        this.fileName = "Invalid MarkerBoard";
    }
    
    public MarkerBoard(String fileName, float width, float height) {
        this.fileName = fileName;
        this.width = width;
        this.height = height;
        cameras = new ArrayList<Camera>();
        filters = new ArrayList<OneEuroFilter[]>();
        drawingMode = new ArrayList<Boolean>();
        minDistanceDrawingMode = new ArrayList<Float>();
        lastPos = new ArrayList<PVector>();
        lastDistance = new ArrayList<Float>();
        nextTimeEvent = new ArrayList<Integer>();
        updateStatus = new ArrayList<Integer>();
    }

    protected abstract void addTrackerImpl(Camera camera);
    
    public void addTracker(PApplet applet, Camera camera) {
//    public void addTracker(PApplet applet, Camera camera, ARToolKitPlus.TrackerMultiMarker tracker, float[] transfo) {
        this.applet = applet;

        addTrackerImpl(camera);

        this.cameras.add(camera);
        this.drawingMode.add(false);
        this.lastPos.add(new PVector());
        this.lastDistance.add(0f);
        this.minDistanceDrawingMode.add(2f);
        this.nextTimeEvent.add(0);
        this.updateStatus.add(NORMAL);

        OneEuroFilter[] filter = null;
        this.filters.add(filter);
    }

   
    private int getId(Camera camera) {
        return cameras.indexOf(camera);
    }

    public void setFiltering(Camera camera, double freq, double minCutOff) {
        int id = cameras.indexOf(camera);
        OneEuroFilter[] filter = createFilter(freq, minCutOff);
        filters.set(id, filter);
    }

    public void removeFiltering(Camera camera) {
        int id = cameras.indexOf(camera);
        filters.remove(id);
    }

    public void setDrawingMode(Camera camera, boolean dm) {
        setDrawingMode(camera, dm, 2);
    }

    public void setDrawingMode(Camera camera, boolean dm, float dist) {
        int id = getId(camera);

        drawingMode.set(id, dm);
        minDistanceDrawingMode.set(id, dist);
    }

    public void setFakeLocation(Camera camera, PMatrix3D location) {
        int id = cameras.indexOf(camera);
        PMatrix3D transfo = (PMatrix3D) transfos.get(id);
        transfo.set(location);
    }

    private OneEuroFilter[] createFilter(double freq, double minCutOff) {
        OneEuroFilter[] f = new OneEuroFilter[12];
        try {
            for (int i = 0; i < 12; i++) {
                f[i] = new OneEuroFilter(freq);
                f[i].setFrequency(freq);
                f[i].setMinCutoff(minCutOff);
            }
        } catch (Exception e) {
            System.out.println("Filter init error" + e);
        }

        return f;
    }

//    public MultiTracker getTracker() {
//        return this.tracker;
//    }
    public void forceUpdate(Camera camera, int time) {
        int id = getId(camera);
        nextTimeEvent.set(id, applet.millis() + time);
        updateStatus.set(id, FORCE_UPDATE);
    }

    public void blockUpdate(Camera camera, int time) {
        int id = getId(camera);
        nextTimeEvent.set(id, applet.millis() + time);
        updateStatus.set(id, BLOCK_UPDATE);
    }

    public boolean isMoving(Camera camera) {
        int id = getId(camera);
//        nextTimeEvent.set(id, applet.millis() + time);
        int mode = updateStatus.get(id);

        if (mode == BLOCK_UPDATE) {
            return false;
        }
        if (mode == FORCE_UPDATE || mode == NORMAL) {
            return true;
        }

        return true;
    }

    public float lastMovementDistance(Camera camera) {
        int id = getId(camera);
        return lastDistance.get(id);
    }

    public boolean isTrackedBy(Camera camera) {
        return cameras.contains(camera);
    }

    private PVector getPositionVector(int id) {
        PMatrix3D transfo = (PMatrix3D) transfos.get(id);
        return new PVector(transfo.m03, transfo.m13, transfo.m23);
    }

    // We suppose that the ARDisplay is the one of the camera...
    public PVector getBoardLocation(Camera camera, ARDisplay display) {
        int id = cameras.indexOf(camera);
        PVector v = getPositionVector(id);

        // Apply extrinsics if required.
        PMatrix3D extr = display.getExtrinsics();
        if (extr != null) {
            PVector v2 = new PVector();
            extr.mult(v, v2);
            v = v2;
        }
        PVector px = display.getProjectiveDeviceP().worldToPixel(v, true);
        return px;
    }

    public boolean isSeenBy(Camera camera, ProjectorDisplay projector, float error) {
        PVector px = this.getBoardLocation(camera, projector);
        return !(px.x < (0 - error)
                || px.x > projector.getWidth()
                || px.y < (0 - error)
                || px.y > (projector.getHeight() + error));
    }

    public synchronized void updateLocation(Camera camera, IplImage img, Object globalTracking) {

        int id = cameras.indexOf(camera);
        if (id == -1) {
            throw new RuntimeException("The board " + this.fileName + " is"
                    + " not registered with the camera you asked");
        }

        int currentTime = applet.millis();
        int endTime = nextTimeEvent.get(id);
        int mode = updateStatus.get(id);

        // If the update is still blocked
        if (mode == BLOCK_UPDATE && currentTime < endTime) {
            return;
        }
        
        updatePositionImpl(id, currentTime, endTime, mode, camera, img, globalTracking);

    }
    protected abstract void updatePositionImpl(int id, int currentTime, int endTime, int mode, Camera camera, IplImage img, Object globalTracking);
    

    public PMatrix3D getTransfoMat(Camera camera) {
        return transfos.get(cameras.indexOf(camera));
    }

    public PMatrix3D getTransfoRelativeTo(Camera camera, MarkerBoard board2) {

        PMatrix3D tr1 = getTransfoMat(camera);
        PMatrix3D tr2 = board2.getTransfoMat(camera);

        tr2.apply(tr1);
        return tr2;
    }

    public ObjectFinder getObjectTracking(Camera camera) {
        assert (this.useJavaCVFinder());
        return (ObjectFinder) getTracking(camera);
    }

    public ARToolKitPlus.TrackerMultiMarker getARToolkitTracking(Camera camera) {
        assert (this.useGrayscaleImages());
        return (ARToolKitPlus.TrackerMultiMarker) getTracking(camera);
    }

    private Object getTracking(Camera camera) {
        return trackers.get(cameras.indexOf(camera));
    }

    public boolean useJavaCVFinder() {
        return this.type == MarkerType.JAVACV_FINDER;
    }

    public boolean useGrayscaleImages() {
        return this.type == MarkerType.ARTOOLKITPLUS || this.type == MarkerType.SVG;
    }
  
    public boolean useCustomARToolkitBoard() {
        return this.type == MarkerType.SVG;
    }

    public String getFileName() {
        return fileName;
    }

    public float getWidth() {
        return width;
    }

    public float getHeight() {
        return height;
    }

    public String toString() {
        return "MarkerBoard " + getFileName();
    }
}
