package com.dynamo.cr.contenteditor.manipulator;

import javax.vecmath.Matrix4d;
import javax.vecmath.Vector4d;

import com.dynamo.cr.contenteditor.manipulator.ManipulatorContext.Pivot;
import com.dynamo.cr.scene.graph.Node;
import com.dynamo.cr.scene.math.MathUtil;
import com.dynamo.cr.scene.math.Transform;
import com.dynamo.cr.scene.operations.TransformNodeOperation;

// http://caad.arch.ethz.ch/info/maya/manual/UserGuide/Overview/TransformingObjects.fm.html
public abstract class TransformManipulator implements IManipulator {

    /**
     * GL name to use to select view oriented handles.
     */
    public static final int VIEW_HANDLE_NAME = 3;

    /**
     * Axis of the selected manipulator handle in world (global) space.
     */
    protected Vector4d handleAxisMS = new Vector4d();
    /**
     * Transforms of the 3 manipulator handles in manipulator space.
     */
    protected final Matrix4d[] handleTransforms = new Matrix4d[3];
    /**
     * Original transform of the manipulator itself, before the manipulation took place.
     */
    protected Matrix4d originalManipulatorTransformWS = new Matrix4d();
    /**
     * Transform of the manipulator itself.
     */
    protected Matrix4d manipulatorTransformWS = new Matrix4d();
    /**
     * Name of the manipulator.
     */
    private String name;
    /**
     * The original local space transforms of the selected nodes, used for undo.
     */
    protected Transform[] originalNodeTransformsLS;
    /**
     * The transforms of the selected nodes relative the manipulator.
     */
    protected Matrix4d[] nodeTransformsMS;

    /**
     * If the manipulator is being moved or not, disables the ability to switch manipulation space.
     */
    private boolean active = false;

    public TransformManipulator() {
        for (int i = 0; i < 3; ++i) {
            this.handleTransforms[i] = new Matrix4d();
            MathUtil.basisMatrix(this.handleTransforms[i], i);
        }
    }

    Vector4d getHandlePosition(Node[] nodes, Pivot pivot) {
        Vector4d position = new Vector4d();
        switch (pivot) {
        case LOCAL:
            Matrix4d nodeWS = new Matrix4d();
            nodes[0].getWorldTransform(nodeWS);
            nodeWS.getColumn(3, position);
            break;
        default:
            Matrix4d nodeTransform = new Matrix4d();
            Vector4d nodePosition = new Vector4d();
            for (Node n : nodes) {
                n.getWorldTransform(nodeTransform);
                nodeTransform.getColumn(3, nodePosition);
                position.add(nodePosition);
            }
            position.scale(1.0 / nodes.length);
            break;
        }

        return position;
    }

    @Override
    public void draw(ManipulatorDrawContext context) {
        if (!this.active) {
            int orientation = context.orientation;
            if (orientation == ManipulatorController.LOCAL && context.nodes.length > 1)
                orientation = ManipulatorController.GLOBAL;

            if (orientation == ManipulatorController.GLOBAL) {
                Vector4d translation = getHandlePosition(context.nodes, context.pivot);
                this.manipulatorTransformWS.setIdentity();
                this.manipulatorTransformWS.setColumn(3, translation);
            } else {
                context.nodes[0].getWorldTransform(this.manipulatorTransformWS);
            }
        }
    }

    @Override
    public void mouseDown(ManipulatorContext context) {
        this.active = true;

        int orientation = context.orientation;
        if (orientation == ManipulatorController.LOCAL && context.nodes.length > 1)
            orientation = ManipulatorController.GLOBAL;

        this.originalManipulatorTransformWS.set(this.manipulatorTransformWS);
        if (context.manipulatorHandle < 3) {
            this.handleAxisMS.set(1.0, 0.0, 0.0, 0.0);
            this.handleTransforms[context.manipulatorHandle].transform(handleAxisMS);
        }

        Matrix4d invManipWS = new Matrix4d(this.manipulatorTransformWS);
        invManipWS.invert();
        this.originalNodeTransformsLS = new Transform[context.nodes.length];
        this.nodeTransformsMS = new Matrix4d[context.nodes.length];
        Matrix4d nodeTransformWS = new Matrix4d();
        int i = 0;
        for (Node n : context.nodes) {
            this.originalNodeTransformsLS[i] = new Transform();
            n.getLocalTransform(this.originalNodeTransformsLS[i]);
            this.nodeTransformsMS[i] = new Matrix4d(invManipWS);
            n.getWorldTransform(nodeTransformWS);
            this.nodeTransformsMS[i].mul(nodeTransformWS);
            ++i;
        }
    }

    @Override
    public abstract void mouseMove(ManipulatorContext context);

    @Override
    public void mouseUp(ManipulatorContext context) {
        this.active = false;

        Transform[] t = new Transform[context.nodes.length];
        for (int i = 0; i < context.nodes.length; ++i) {
            t[i] = new Transform();
            context.nodes[i].getLocalTransform(t[i]);
        }
        TransformNodeOperation op = new TransformNodeOperation("move", context.nodes, this.originalNodeTransformsLS, t);
        context.editor.executeOperation(op);
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public boolean isActive() {
        return this.active;
    }
}
