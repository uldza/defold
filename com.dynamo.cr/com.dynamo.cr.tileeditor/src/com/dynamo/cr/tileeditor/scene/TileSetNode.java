package com.dynamo.cr.tileeditor.scene;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;

import com.dynamo.bob.atlas.AtlasGenerator;
import com.dynamo.bob.tile.ConvexHull;
import com.dynamo.bob.tile.TileSetUtil;
import com.dynamo.bob.tile.TileSetUtil.ConvexHulls;
import com.dynamo.cr.editor.ui.EditorUIPlugin;
import com.dynamo.cr.properties.NotEmpty;
import com.dynamo.cr.properties.Property;
import com.dynamo.cr.properties.Property.EditorType;
import com.dynamo.cr.properties.Range;
import com.dynamo.cr.properties.Resource;
import com.dynamo.cr.properties.ValidatorUtil;
import com.dynamo.cr.sceneed.core.AABB;
import com.dynamo.cr.sceneed.core.ISceneModel;
import com.dynamo.cr.sceneed.core.Node;
import com.dynamo.cr.sceneed.core.TextureHandle;
import com.dynamo.cr.sceneed.ui.util.VertexBufferObject;
import com.dynamo.textureset.proto.TextureSetProto.TextureSetAnimation;
import com.sun.opengl.util.BufferUtil;

@SuppressWarnings("serial")
public class TileSetNode extends TextureSetNode {

    // TODO: Should be configurable
    private static final int PLANE_COUNT = 16;

    @Property(editorType=EditorType.RESOURCE, extensions={"jpg", "png"})
    @Resource
    private String image = "";

    @Property
    @Range(min=1)
    private int tileWidth;

    @Property
    @Range(min=1)
    private int tileHeight;

    @Property
    @Range(min=0)
    private int tileMargin;

    @Property
    @Range(min=0)
    private int tileSpacing;

    @Property(editorType=EditorType.RESOURCE, extensions={"jpg", "png"})
    @Resource
    private String collision = "";

    @Property
    @NotEmpty(severity = IStatus.ERROR)
    private String materialTag = "";

    private List<CollisionGroupNode> tileCollisionGroups;
    // Used to simulate a shorter list of convex hulls.
    // convexHulls need to be retained in some cases to keep collision groups and keep undo functionality
    private int tileCollisionGroupCount;
    private List<ConvexHull> convexHulls;
    private float[] convexHullPoints = new float[0];

    // Graphics resources
    private transient BufferedImage loadedImage;
    private transient BufferedImage loadedCollision;
    private transient TextureHandle textureHandle;
    private transient FloatBuffer texCoords;
    private transient VertexBufferObject vertexBuffer = new VertexBufferObject();
    private transient VertexBufferObject outlineVertexBuffer = new VertexBufferObject();
    private transient Map<String, TextureSetAnimation> textureSetAnimations = new HashMap<String, TextureSetAnimation>();

    public TileSetNode() {
        this.tileCollisionGroups = new ArrayList<CollisionGroupNode>();
        this.tileCollisionGroupCount = 0;
        this.convexHulls = new ArrayList<ConvexHull>();

        this.textureHandle = null;
    }

    public String getImage() {
        return image;
    }

    public IStatus validateImage() {
        if (this.image.isEmpty() && this.collision.isEmpty()) {
            return ValidatorUtil.createStatus(this, "image", IStatus.INFO, "EMPTY", null);
        }
        IStatus status = verifyImageDimensions();
        if (!status.isOK())
            return status;
        else
            return verifyTileDimensions(true, true);
    }

    public void setImage(String image) {
        if (!this.image.equals(image)) {
            this.image = image;
            this.loadedImage = null;
            if (!this.image.isEmpty()) {
                updateImage();
            }
            updateData();
        }
    }

    public int getTileWidth() {
        return this.tileWidth;
    }

    protected IStatus validateTileWidth() {
        return verifyTileDimensions(true, false);
    }

    public void setTileWidth(int tileWidth) {
        if (this.tileWidth != tileWidth) {
            this.tileWidth = tileWidth;
            updateData();
        }
    }

    public int getTileHeight() {
        return this.tileHeight;
    }

    protected IStatus validateTileHeight() {
        return verifyTileDimensions(false, true);
    }

    public void setTileHeight(int tileHeight) {
        if (this.tileHeight != tileHeight) {
            this.tileHeight = tileHeight;
            updateData();
        }
    }

    public int getTileMargin() {
        return this.tileMargin;
    }

    protected IStatus validateTileMargin() {
        if (this.tileMargin > 0)
            return verifyTileDimensions(true, true);
        else
            return Status.OK_STATUS;
    }

    public void setTileMargin(int tileMargin) {
        if (this.tileMargin != tileMargin) {
            this.tileMargin = tileMargin;
            updateData();
        }
    }

    public int getTileSpacing() {
        return this.tileSpacing;
    }

    public void setTileSpacing(int tileSpacing) {
        if (this.tileSpacing != tileSpacing) {
            this.tileSpacing = tileSpacing;
            updateData();
        }
    }

    public String getCollision() {
        return this.collision;
    }

    public IStatus validateCollision() {
        if (this.image.isEmpty() && this.collision.isEmpty()) {
            return ValidatorUtil.createStatus(this, "collision", IStatus.INFO, "EMPTY", null);
        }
        IStatus status = verifyImageDimensions();
        if (!status.isOK())
            return status;
        else
            return verifyTileDimensions(true, true);
    }

    public void setCollision(String collision) {
        if (!this.collision.equals(collision)) {
            this.collision = collision;
            this.loadedCollision = null;
            if (this.collision != null && !this.collision.equals("")) {
                updateCollision();
            }
            updateData();
        }
    }

    public String getMaterialTag() {
        return this.materialTag;
    }

    public void setMaterialTag(String materialTag) {
        this.materialTag = materialTag;
    }

    public boolean isMaterialTagVisible() {
        // Hide material tag since it's deprecated
        return false;
    }

    @Override
    protected void childAdded(Node child) {
        sortChildren();
    }

    public List<CollisionGroupNode> getCollisionGroups() {
        List<CollisionGroupNode> groups = new ArrayList<CollisionGroupNode>();
        for (Node child : getChildren()) {
            if (child instanceof CollisionGroupNode) {
                groups.add((CollisionGroupNode)child);
            }
        }
        return groups;
    }

    public List<AnimationNode> getAnimations() {
        List<AnimationNode> animations = new ArrayList<AnimationNode>();
        for (Node child : getChildren()) {
            if (child instanceof AnimationNode) {
                animations.add((AnimationNode)child);
            }
        }
        return animations;
    }

    public List<ConvexHull> getConvexHulls() {
        return Collections.unmodifiableList(this.convexHulls);
    }

    public List<CollisionGroupNode> getTileCollisionGroups() {
        int n = this.tileCollisionGroupCount;
        List<CollisionGroupNode> result = new ArrayList<CollisionGroupNode>(n);
        for (int i = 0; i < n; ++i) {
            CollisionGroupNode group = this.tileCollisionGroups.get(i);
            if (group != null && group.getParent() != null) {
                result.add(group);
            } else {
                result.add(null);
            }
        }
        return result;
    }

    public void setTileCollisionGroups(List<CollisionGroupNode> tileCollisionGroups) {
        this.tileCollisionGroups = new ArrayList<CollisionGroupNode>(tileCollisionGroups);
    }

    public float[] getConvexHullPoints() {
        return this.convexHullPoints;
    }

    public BufferedImage getLoadedImage() {
        return this.loadedImage;
    }

    public BufferedImage getLoadedCollision() {
        return this.loadedCollision;
    }

    @Override
    public TextureHandle getTextureHandle() {
        return this.textureHandle;
    }

    private void updateVertexData() {
        TileSetUtil.Metrics metrics = calculateMetrics(this);
        if (metrics == null) {
            return;
        }

        int tile = -1;
        boolean flipHorizontal = false;
        boolean flipVertical = false;
        List<AnimationNode> animations = getAnimations();

        FloatBuffer vertices = FloatBuffer.allocate(AtlasGenerator.COMPONENT_COUNT * 6 * animations.size());
        FloatBuffer outlineVertices = FloatBuffer.allocate(AtlasGenerator.COMPONENT_COUNT * 4 * animations.size());

        textureSetAnimations.clear();
        int index = 0;
        for (AnimationNode animation : animations) {
            // NOTE: Yet another index conversion... from [1,n] to [0,n-1]
            tile = animation.getStartTile() - 1;
            flipHorizontal = animation.isFlipHorizontally();
            flipVertical = animation.isFlipVertically();

            float recipImageWidth = 1.0f / metrics.tileSetWidth;
            float recipImageHeight = 1.0f / metrics.tileSetHeight;

            int tileWidth = getTileWidth();
            int tileHeight = getTileHeight();

            float f = 0.5f;
            float x0 = -f * tileWidth;
            float x1 = f * tileWidth;
            float y0 = -f * tileHeight;
            float y1 = f * tileHeight;

            int tileMargin = getTileMargin();
            int tileSpacing = getTileSpacing();
            int x = tile % metrics.tilesPerRow;
            int y = tile / metrics.tilesPerRow;
            float u0 = (x * (tileSpacing + 2*tileMargin + tileWidth) + tileMargin) * recipImageWidth;
            float u1 = u0 + tileWidth * recipImageWidth;
            float v0 = (y * (tileSpacing + 2*tileMargin + tileHeight) + tileMargin) * recipImageHeight;
            float v1 = v0 + tileHeight * recipImageHeight;
            if (flipHorizontal) {
                float u = u0;
                u0 = u1;
                u1 = u;
            }
            if (flipVertical) {
                float v = v0;
                v0 = v1;
                v1 = v;
            }

            float z = 0.0f;
            FloatBuffer v = outlineVertices;
            v.put(u0); v.put(v1); v.put(x0); v.put(y0); v.put(z);
            v.put(u0); v.put(v0); v.put(x0); v.put(y1); v.put(z);
            v.put(u1); v.put(v0); v.put(x1); v.put(y1); v.put(z);
            v.put(u1); v.put(v1); v.put(x1); v.put(y0); v.put(z);

            FloatBuffer ov = vertices;
            ov.put(u0); ov.put(v1); ov.put(x0); ov.put(y0); ov.put(z);
            ov.put(u0); ov.put(v0); ov.put(x0); ov.put(y1); ov.put(z);
            ov.put(u1); ov.put(v0); ov.put(x1); ov.put(y1); ov.put(z);

            ov.put(u1); ov.put(v0); ov.put(x1); ov.put(y1); ov.put(z);
            ov.put(u1); ov.put(v1); ov.put(x1); ov.put(y0); ov.put(z);
            ov.put(u0); ov.put(v1); ov.put(x0); ov.put(y0); ov.put(z);

            TextureSetAnimation textureSetAnimation = createTextureSetAnimation(animation, index);
            this.textureSetAnimations.put(animation.getId(), textureSetAnimation);
            ++index;
        }
        vertices.rewind();
        outlineVertices.rewind();
        this.vertexBuffer.bufferData(vertices, vertices.capacity() * 4);
        this.outlineVertexBuffer.bufferData(outlineVertices, outlineVertices.capacity() * 4);
    }

    @Override
    public VertexBufferObject getVertexBuffer() {
        return this.vertexBuffer;
    }

    @Override
    public VertexBufferObject getOutlineVertexBuffer() {
        return this.outlineVertexBuffer;
    }

    private TextureSetAnimation createTextureSetAnimation(AnimationNode animation, int index) {
        String id = animation.getId();
        int w = getTileWidth();
        int h = getTileHeight();
        return TextureSetAnimation.newBuilder()
                .setId(id)
                .setWidth(w)
                .setHeight(h)
                .setPlayback(animation.getPlayback())
                .setStart(animation.getStartTile()-1)
                .setEnd(animation.getEndTile()-1)
                .setFps(animation.getFps())
                .setFlipHorizontal(animation.isFlipHorizontally() ? 1 : 0)
                .setFlipVertical(animation.isFlipVertically() ? 1 : 0)
                .build();
    }

    @Override
    public TextureSetAnimation getAnimation(String id) {
        return this.textureSetAnimations.get(id);
    }

    @Override
    public TextureSetAnimation getAnimation(Comparable<String> comparable) {
        for (String id : this.textureSetAnimations.keySet()) {
            if (comparable.compareTo(id) == 0) {
                return this.textureSetAnimations.get(id);
            }
        }
        return null;
    }

    @Override
    public AABB getTextureBounds(String id) {
        AABB aabb = new AABB();
        float w2 = getTileWidth() * 0.5f;
        float h2 = getTileHeight() * 0.5f;
        aabb.union(-w2, -h2, 0);
        aabb.union(w2, h2, 0);
        return aabb;
    }

    @Override
    public FloatBuffer getTexCoords() {
        return this.texCoords;
    }

    @Override
    public void setModel(ISceneModel model) {
        super.setModel(model);
        if (model != null) {
            updateImage();
            updateCollision();
            updateData();
        }
    }

    private void updateImage() {
        if (!this.image.isEmpty() && getModel() != null) {
            this.loadedImage = getModel().getImage(this.image);
            this.textureHandle = getModel().getTexture(this.image);
        }
    }

    private void updateCollision() {
        if (!this.collision.isEmpty() && getModel() != null) {
            this.loadedCollision = getModel().getImage(this.collision);
        }
    }

    private IStatus verifyImageDimensions() {
        if (this.loadedImage != null && this.loadedCollision != null) {
            if (this.loadedImage.getWidth() != this.loadedCollision.getWidth() || this.loadedImage.getHeight() != this.loadedCollision.getHeight()) {
                return createErrorStatus(Messages.TileSetNode_DIFF_IMG_DIMS, new Object[] {
                        this.loadedImage.getWidth(),
                        this.loadedImage.getHeight(),
                        this.loadedCollision.getWidth(),
                        this.loadedCollision.getHeight() });
            }
        }
        return Status.OK_STATUS;
    }

    private IStatus verifyTileDimensions(boolean verifyWidth, boolean verifyHeight) {
        if ((verifyWidth || verifyHeight) && (this.loadedImage != null || this.loadedCollision != null)) {
            int imageWidth = 0;
            int imageHeight = 0;
            if (this.loadedImage != null) {
                imageWidth = this.loadedImage.getWidth();
                imageHeight = this.loadedImage.getHeight();
            } else {
                imageWidth = this.loadedCollision.getWidth();
                imageHeight = this.loadedCollision.getHeight();
            }
            if (verifyWidth) {
                int totalTileWidth = this.tileWidth + this.tileMargin;
                if (totalTileWidth > imageWidth) {
                    return createErrorStatus(Messages.TileSetNode_TILE_WIDTH_GT_IMG, new Object[] {
                            totalTileWidth, imageWidth });
                }
            }
            if (verifyHeight) {
                int totalTileHeight = this.tileHeight + this.tileMargin;
                if (totalTileHeight > imageHeight) {
                    return createErrorStatus(Messages.TileSetNode_TILE_HEIGHT_GT_IMG, new Object[] {
                            totalTileHeight, imageHeight });
                }
            }
        }
        return Status.OK_STATUS;
    }

    private IStatus createErrorStatus(String message, Object[] binding) {
        return new Status(IStatus.ERROR, EditorUIPlugin.PLUGIN_ID, NLS.bind(message, binding));
    }

    public void updateData() {
        updateConvexHulls();
        updateTexCoords();
        updateVertexData();
    }

    public void updateConvexHulls() {
        if (getModel() != null) {
            ConvexHulls result = null;
            if (this.loadedCollision != null && this.tileWidth > 0 && this.tileHeight > 0 && this.tileMargin >= 0 && this.tileSpacing >= 0) {
                result = TileSetUtil.calculateConvexHulls(loadedCollision.getAlphaRaster(), PLANE_COUNT,
                    loadedCollision.getWidth(), loadedCollision.getHeight(),
                    tileWidth, tileHeight, tileMargin, tileSpacing);
            }
            if (result != null) {
                int tileCount = result.hulls.length;
                this.convexHulls = Arrays.asList(result.hulls);
                // Add additional slots for tile collision groups (never shrink
                // the tile collision group list since we might lose edited
                // groups and break undo)
                for (int i = this.tileCollisionGroups.size(); i < tileCount; ++i) {
                    this.tileCollisionGroups.add(null);
                }
                // Simulate a shorter list (see convexHullCount and getConvexHulls)
                this.tileCollisionGroupCount = tileCount;
                this.convexHullPoints = result.points;
                return;
            }
        }
        this.convexHullPoints = new float[0];
        if (!this.convexHulls.isEmpty()) {
            this.convexHulls = new ArrayList<ConvexHull>();
        }
        this.tileCollisionGroupCount = 0;
    }

    @Override
    public boolean handleReload(IFile file) {
        boolean reloaded = false;
        if (image != null && image.length() > 0) {
            IFile imgFile = getModel().getFile(image);
            if (file.equals(imgFile)) {
                updateImage();
                reloaded = true;
            }
        }
        if (collision != null && collision.length() > 0) {
            IFile collisionFile = getModel().getFile(collision);
            if (file.equals(collisionFile)) {
                updateCollision();
                reloaded = true;
            }
        }
        if (reloaded) {
            updateData();
        }
        return reloaded;
    }

    public int calculateTileCount() {
        int imageWidth = 0;
        int imageHeight = 0;
        if (this.loadedImage != null) {
            imageWidth = this.loadedImage.getWidth();
            imageHeight = this.loadedImage.getHeight();
        } else if (this.loadedCollision != null) {
            imageWidth = this.loadedCollision.getWidth();
            imageHeight = this.loadedCollision.getHeight();
        }
        if (imageWidth > 0 && imageHeight > 0) {
            int tilesPerRow = TileSetUtil.calculateTileCount(this.tileWidth, imageWidth, this.tileMargin, this.tileSpacing);
            int tilesPerColumn = TileSetUtil.calculateTileCount(this.tileHeight, imageHeight, this.tileMargin, this.tileSpacing);
            return tilesPerRow * tilesPerColumn;
        }
        return 0;
    }

    public String getChildId(Node node) {
        if (node instanceof AnimationNode) {
            return ((AnimationNode)node).getId();
        } else if (node instanceof CollisionGroupNode) {
            return ((CollisionGroupNode)node).getId();
        } else {
            return null;
        }
    }

    public void sortChildren() {
        sortChildren(new Comparator<Node>() {
            @Override
            public int compare(Node o1, Node o2) {
                if ((o1 instanceof AnimationNode && o2 instanceof CollisionGroupNode)
                        || (o1 instanceof CollisionGroupNode && o2 instanceof AnimationNode)) {
                    if (o1 instanceof CollisionGroupNode) {
                        return 1;
                    } else {
                        return -1;
                    }
                } else {
                    String id1 = getChildId(o1);
                    String id2 = getChildId(o2);
                    return id1.compareTo(id2);
                }

            }
        });
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.writeObject(this.image);
        out.writeInt(this.tileWidth);
        out.writeInt(this.tileHeight);
        out.writeInt(this.tileMargin);
        out.writeInt(this.tileSpacing);
        out.writeObject(this.collision);
        out.writeObject(this.materialTag);
        out.writeObject(this.tileCollisionGroups);
    }

    @SuppressWarnings({"unchecked"})
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        this.image = (String)in.readObject();
        this.tileWidth = in.readInt();
        this.tileHeight = in.readInt();
        this.tileMargin = in.readInt();
        this.tileSpacing = in.readInt();
        this.collision = (String)in.readObject();
        this.materialTag = (String)in.readObject();
        this.tileCollisionGroups = (List<CollisionGroupNode>)in.readObject();
        this.convexHulls = new ArrayList<ConvexHull>();
        this.vertexBuffer = new VertexBufferObject();
        this.outlineVertexBuffer = new VertexBufferObject();
        this.textureSetAnimations = new HashMap<String, TextureSetAnimation>();
    }

    private void updateTexCoords() {
        IStatus status = getStatus();
        if (status == null) {
            updateStatus();
            status = getStatus();
        }
        if (getLoadedImage() == null || !status.isOK()) {
            this.texCoords = null;
            return;
        }

        TileSetUtil.Metrics metrics = calculateMetrics(this);
        if (metrics == null) {
            this.texCoords = null;
            return;
        }

        float recipImageWidth = 1.0f / metrics.tileSetWidth;
        float recipImageHeight = 1.0f / metrics.tileSetHeight;

        int tileCount = metrics.tilesPerRow * metrics.tilesPerColumn;
        final int componentCount = 4;
        this.texCoords = BufferUtil.newFloatBuffer(tileCount * componentCount);
        for (int tile = 0; tile < tileCount; ++tile) {
            int x = tile % metrics.tilesPerRow;
            int y = tile / metrics.tilesPerRow;
            float u0 = (x * (tileSpacing + 2 * tileMargin + tileWidth) + tileMargin) * recipImageWidth;
            float u1 = u0 + tileWidth * recipImageWidth;
            float v0 = (y * (tileSpacing + 2 * tileMargin + tileHeight) + tileMargin) * recipImageHeight;
            float v1 = v0 + tileHeight * recipImageHeight;

            this.texCoords.put(u0).put(v0).put(u1).put(v1);
        }
        this.texCoords.flip();
    }

    private static TileSetUtil.Metrics calculateMetrics(TileSetNode node) {
        return TileSetUtil.calculateMetrics(node.getLoadedImage(), node.getTileWidth(), node.getTileHeight(),
                node.getTileMargin(), node.getTileSpacing(), null, 1.0f, 0.0f);
    }

    @Override
    public int getVertexStart(TextureSetAnimation anim) {
        return anim.getStart() * 6;
    }

    @Override
    public int getVertexCount(TextureSetAnimation anim) {
        return (anim.getEnd() - anim.getStart() + 1) * 6;
    }

    @Override
    public int getOutlineVertexStart(TextureSetAnimation anim) {
        return anim.getStart() * 4;
    }

    @Override
    public int getOutlineVertexCount(TextureSetAnimation anim) {
        return (anim.getEnd() - anim.getStart() + 1) * 4;
    }
}
