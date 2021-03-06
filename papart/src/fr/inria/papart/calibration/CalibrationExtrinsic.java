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
package fr.inria.papart.calibration;

import static fr.inria.papart.calibration.CalibrationPopup.KINECT_ARTOOLKIT_NAME;
import fr.inria.papart.depthcam.devices.Kinect360;
import fr.inria.papart.depthcam.devices.KinectDevice;
import fr.inria.papart.depthcam.devices.KinectDevice.Type;
import fr.inria.papart.depthcam.devices.KinectOne;
import fr.inria.papart.multitouch.KinectTouchInput;
import fr.inria.papart.tracking.MarkerBoard;
import fr.inria.papart.procam.Papart;
import fr.inria.papart.procam.ProjectiveDeviceP;
import fr.inria.papart.procam.camera.Camera;
import fr.inria.papart.procam.camera.ProjectorAsCamera;
import fr.inria.papart.procam.camera.TrackedView;
import fr.inria.papart.procam.display.ProjectorDisplay;
import fr.inria.skatolo.gui.group.Textarea;
import java.util.ArrayList;
import org.bytedeco.javacpp.opencv_core;
import static org.bytedeco.javacpp.opencv_core.IPL_DEPTH_8U;
import static org.bytedeco.javacpp.opencv_imgproc.CV_BGR2GRAY;
import static org.bytedeco.javacpp.opencv_imgproc.cvCvtColor;
import processing.core.PApplet;
import static processing.core.PApplet.println;
import processing.core.PMatrix3D;
import processing.core.PVector;
import toxi.geom.Plane;
import toxi.geom.Ray3D;
import toxi.geom.ReadonlyVec3D;
import toxi.geom.Vec3D;
import static processing.core.PApplet.println;

/**
 *
 * @author Jérémy Laviole - jeremy.laviole@inria.fr
 */
public class CalibrationExtrinsic {

    private final PApplet parent;

    // Cameras
    private ProjectorDisplay projector;
    private PMatrix3D kinectCameraExtrinsics = new PMatrix3D();
    private Papart papart;

    // Kinect
    private KinectDevice.Type kinectType;
    private KinectDevice kinectDevice;

    public CalibrationExtrinsic(PApplet parent) {
        this.parent = parent;
        papart = Papart.getPapart();
    }

    void setProjector(ProjectorDisplay projector) {
        this.projector = projector;
    }

    public void setDefaultKinect() {
        this.kinectType = papart.getKinectType();
        this.kinectDevice = papart.getKinectDevice();
    }
    public void setKinect(KinectDevice device, Type type) {
        this.kinectType = type;
        this.kinectDevice = device;
    }

    public PMatrix3D getKinectCamExtrinsics() {
        return this.kinectCameraExtrinsics;
    }

    public void computeProjectorCameraExtrinsics(ArrayList<CalibrationSnapshot> snapshots) {
        PMatrix3D sum = new PMatrix3D(0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0);

        for (CalibrationSnapshot snapshot : snapshots) {
            PMatrix3D extr = computeExtrinsics(snapshot.cameraPaper,
                    snapshot.projectorPaper);
            Utils.addMatrices(sum, extr);
        }
        Utils.multMatrix(sum, 1f / (float) snapshots.size());

        saveProCamExtrinsics(sum);
    }
    
    public void saveProCamExtrinsics(PMatrix3D extr){
          papart.saveCalibration(Papart.cameraProjExtrinsics, extr);
           projector.setExtrinsics(extr);
    }

    public void calibrateKinect(ArrayList<CalibrationSnapshot> snapshots) {
        if (this.kinectType == Type.ONE) {
            calibrateKinectOne(snapshots);
        }
        if (this.kinectType == Type.X360) {
            calibrateKinect360(snapshots);
        }
    }

    protected void calibrateKinectOne(ArrayList<CalibrationSnapshot> snapshots) {
        PMatrix3D kinectExtr = kinectDevice.getStereoCalibration().get();
        kinectExtr.invert();

        PlaneCalibration planeCalibCam = computeAveragePlaneCam(snapshots);
        planeCalibCam.flipNormal();

        // identity - no external camera for ProCam calibration
        PMatrix3D kinectCameraExtrinsics = new PMatrix3D();
        // Depth -> Color calibration.
        kinectCameraExtrinsics.set(kinectExtr);

        HomographyCalibration homography = CalibrationExtrinsic.computeScreenPaperIntersection(projector, planeCalibCam, kinectCameraExtrinsics);

        if (homography == HomographyCalibration.INVALID) {
            System.err.println("No intersection");
            return;
        }

        // move the plane up a little.
        planeCalibCam.moveAlongNormal(-7f);

        saveKinectPlaneCalibration(planeCalibCam, homography);
        saveKinectCameraExtrinsics(kinectCameraExtrinsics);
    }

    protected void calibrateKinect360(ArrayList<CalibrationSnapshot> snapshots) {
        calibrateKinect360Extr(snapshots);
        calibrateKinect360Plane(snapshots);
    }

    protected void calibrateKinect360Extr(ArrayList<CalibrationSnapshot> snapshots) {
        // Depth -> color  extrinsics
        PMatrix3D kinectExtr = kinectDevice.getStereoCalibration().get();

        // color -> depth  extrinsics
        kinectExtr.invert();

        // depth -> tracking
        PMatrix3D kinectCameraExtr = computeKinectCamExtrinsics(snapshots, kinectExtr);

        // // tracking -> depth
        kinectCameraExtr.invert();

        this.kinectCameraExtrinsics.set(kinectCameraExtr);
        saveKinectCameraExtrinsics(kinectCameraExtr);
    }

    public boolean calibrateKinect360Plane(ArrayList<CalibrationSnapshot> snapshots) {
        // Depth -> color  extrinsics
        PMatrix3D kinectExtr = kinectDevice.getStereoCalibration().get();

        // color -> depth  extrinsics
        kinectExtr.invert();

        PlaneCalibration planeCalibCam = computeAveragePlaneCam(snapshots);
        PlaneCalibration planeCalibKinect = computeAveragePlaneKinect(snapshots, kinectExtr);
        planeCalibCam.flipNormal();

        // Tracking --> depth
        PMatrix3D kinectCameraExtr = papart.loadCalibration(Papart.kinectTrackingCalib);

        HomographyCalibration homography = CalibrationExtrinsic.computeScreenPaperIntersection(projector,
                planeCalibCam,
                kinectCameraExtr);
        if (homography == HomographyCalibration.INVALID) {
            System.err.println("No intersection");
            return false;
        }

        // move the plane up a little.
        planeCalibKinect.flipNormal();
        planeCalibKinect.moveAlongNormal(-20f);

        saveKinectPlaneCalibration(planeCalibKinect, homography);
        return true;
    }
    
    
    public boolean calibrateKinect360PlaneOnly(ArrayList<CalibrationSnapshot> snapshots) {
        // Depth -> color  extrinsics
        PMatrix3D kinectExtr = kinectDevice.getStereoCalibration().get();

        // color -> depth  extrinsics
        kinectExtr.invert();

        PlaneCalibration planeCalibCam = computeAveragePlaneCam(snapshots);
        PlaneCalibration planeCalibKinect = computeAveragePlaneKinect(snapshots, kinectExtr);
        planeCalibCam.flipNormal();

        // Tracking --> depth
        PMatrix3D kinectCameraExtr = papart.loadCalibration(Papart.kinectTrackingCalib);

        HomographyCalibration homography = CalibrationExtrinsic.computeScreenPaperIntersection(projector,
                planeCalibCam,
                kinectCameraExtr);
        if (homography == HomographyCalibration.INVALID) {
            System.err.println("No intersection");
            return false;
        }

        // move the plane up a little.
        planeCalibKinect.flipNormal();
        planeCalibKinect.moveAlongNormal(-20f);

        saveKinectPlaneCalibration(planeCalibKinect, homography);
        return true;
    }

    private PMatrix3D computeKinectCamExtrinsics(ArrayList<CalibrationSnapshot> snapshots, PMatrix3D stereoExtr) {
        PMatrix3D sum = new PMatrix3D(0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0);

        int nbCalib = 0;
        for (CalibrationSnapshot snapshot : snapshots) {
            if (snapshot.kinectPaper == null) {
                continue;
            }

            // Color -> Paper
            PMatrix3D boardFromDepth = snapshot.kinectPaper.get();

            /// depth -> color -> color -> Paper
            boardFromDepth.preApply(stereoExtr);

            PMatrix3D extr = computeExtrinsics(boardFromDepth, snapshot.cameraPaper);

            Utils.addMatrices(sum, extr);
            nbCalib++;
        }

        Utils.multMatrix(sum, 1f / (float) nbCalib);
        return sum;
    }

    private PlaneCalibration computeAveragePlaneKinect(ArrayList<CalibrationSnapshot> snapshots, PMatrix3D stereoExtr) {
        PVector paperSize = new PVector(297, 210);

        Plane sumKinect = new Plane(new Vec3D(0, 0, 0),
                new Vec3D(0, 0, 0));

        int nbCalib = 0;
        for (CalibrationSnapshot snapshot : snapshots) {
            if (snapshot.kinectPaper == null) {
                continue;
            }

            //  color -> paper
            PMatrix3D boardFromDepth = snapshot.kinectPaper.get();

            // Depth -> color -> color -> paper
            boardFromDepth.preApply(stereoExtr);

            PlaneCalibration planeCalibKinect
                    = PlaneCalibration.CreatePlaneCalibrationFrom(boardFromDepth, paperSize);
            Utils.sumPlane(sumKinect, planeCalibKinect.getPlane());
            nbCalib++;
        }

        Utils.averagePlane(sumKinect, 1f / nbCalib);

        PlaneCalibration calibration = new PlaneCalibration();
        calibration.setPlane(sumKinect);
        calibration.setHeight(PlaneCalibration.DEFAULT_PLANE_HEIGHT);

//        System.out.println("Plane viewed by the kinect");
//        println(sumKinect);
        return calibration;
    }

    private PlaneCalibration computeAveragePlaneCam(ArrayList<CalibrationSnapshot> snapshots) {
        PVector paperSize = new PVector(297, 210);

        Plane sumCam = new Plane(new Vec3D(0, 0, 0),
                new Vec3D(0, 0, 0));

        int nbPlanes = 0;
        for (CalibrationSnapshot snapshot : snapshots) {

            if (snapshot.cameraPaper == null) {
                continue;
            }

            PlaneCalibration cam = PlaneCalibration.CreatePlaneCalibrationFrom(
                    snapshot.cameraPaper.get(), paperSize);

            Utils.sumPlane(sumCam, cam.getPlane());
            nbPlanes++;
        }
        Utils.averagePlane(sumCam, 1f / nbPlanes);

        PlaneCalibration calibration = new PlaneCalibration();
        calibration.setPlane(sumCam);
        calibration.setHeight(PlaneCalibration.DEFAULT_PLANE_HEIGHT);

        return calibration;
    }

    public void saveKinectCameraExtrinsics(PMatrix3D kinectCameraExtrinsics) {
        papart.saveCalibration(Papart.kinectTrackingCalib, kinectCameraExtrinsics);
    }

    public void saveKinectPlaneCalibration(PlaneCalibration planeCalib, HomographyCalibration homography) {
        PlaneAndProjectionCalibration planeProjCalib = new PlaneAndProjectionCalibration();
        planeProjCalib.setPlane(planeCalib);
        planeProjCalib.setHomography(homography);
        planeProjCalib.saveTo(parent, Papart.planeAndProjectionCalib);

        ((KinectTouchInput) papart.getTouchInput()).setPlaneAndProjCalibration(planeProjCalib);
    }

    public static PMatrix3D computeExtrinsics(PMatrix3D camPaper, PMatrix3D projPaper) {
        PMatrix3D extr = projPaper.get();
        extr.invert();
        extr.preApply(camPaper);
        extr.invert();
        return extr;
    }

    /**
     * Computes the intersection of the corners of the projector viewed by a
     * camera
     *
     * @param projector
     * @param planeCalibCam
     * @param kinectCameraExtrinsics
     * @return
     */
    public static HomographyCalibration computeScreenPaperIntersection(ProjectorDisplay projector, PlaneCalibration planeCalibCam, PMatrix3D kinectCameraExtrinsics) {
        // generate coordinates...
        float step = 0.5f;
        int nbPoints = (int) ((1 + 1.0F / step) * (1 + 1.0F / step));
        HomographyCreator homographyCreator = new HomographyCreator(3, 2, nbPoints);

        // Creates 3D points on the corner of the screen
        for (float i = 0; i <= 1.0; i += step) {
            for (float j = 0; j <= 1.0; j += step) {
                PVector screenPoint = new PVector(i, j);
                PVector kinectPoint = new PVector();

                PVector inter = projector.getProjectedPointOnPlane(planeCalibCam, i, j);

                if (inter == null) {
                    return HomographyCalibration.INVALID;
                }

                // get the point from the Kinect's point of view. 
                kinectCameraExtrinsics.mult(inter, kinectPoint);

                homographyCreator.addPoint(kinectPoint, screenPoint);
            }
        }
        return homographyCreator.getHomography();
    }

}
