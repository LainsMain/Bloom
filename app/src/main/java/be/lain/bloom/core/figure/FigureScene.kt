package be.lain.bloom.core.figure

import android.content.Context
import android.view.Surface
import android.view.TextureView
import be.lain.bloom.domain.motion.Motion
import be.lain.bloom.domain.motion.Prop
import be.lain.bloom.domain.motion.PropKind
import be.lain.bloom.domain.motion.SkeletonPose
import be.lain.bloom.domain.motion.Vec3
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.ToneMapper
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Filament scene for the exercise figure, drawn into a transparent [TextureView] so the app's own
 * background shows through. Only the figure, its props and its shadow are rendered.
 */
class FigureScene(context: Context, private val textureView: TextureView, private val model: FigureModel) {
    private val engine: Engine = Engine.create()
    private val renderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView()
    private val cameraEntity = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(cameraEntity)
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val displayHelper = DisplayHelper(context)
    private var swapChain: SwapChain? = null

    private val materials = UbershaderProvider(engine)
    private val assetLoader = AssetLoader(engine, materials, EntityManager.get())
    private val resourceLoader = ResourceLoader(engine, true)
    private val asset: FilamentAsset
    private var loaded = false
    private val jointEntities: IntArray

    private val sun = EntityManager.get().create()
    private val ibl: IndirectLight
    private val iblCubemap: com.google.android.filament.Texture?
    private val shadowMaterial: Material
    private val shadowInstance: MaterialInstance
    private val shadowTexture: Texture
    private val contact = ContactShadow(model.rig)
    private val shadowBuffers = Array(3) { ByteBuffer.allocateDirect(contact.size * contact.size) }
    private var shadowFrame = 0
    private val floor = EntityManager.get().create()
    private val floorVb: VertexBuffer
    private val floorIb: IndexBuffer
    private var toLight = Vec3(-0.4, 1.0, 0.3).normalized()
    private val colorGrading: ColorGrading

    private val propBoxes: IntArray
    private val propMat: Int
    private val propTowel: Int

    // Smoothed camera
    private var camCenter: Vec3? = null
    private var camDist = 3.0
    private var camYaw = 30.0
    private var camPitch = 10.0
    private var lastFrameNanos = 0L
    private var width = 1
    private var height = 1

    init {
        view.scene = scene
        view.camera = camera
        view.blendMode = View.BlendMode.TRANSLUCENT
        view.multiSampleAntiAliasingOptions = view.multiSampleAntiAliasingOptions.apply { enabled = true; sampleCount = 4 }
        view.antiAliasing = View.AntiAliasing.NONE
        view.ambientOcclusionOptions = view.ambientOcclusionOptions.apply { enabled = true; radius = 0.25f; intensity = 0.8f }
        view.isShadowingEnabled = false
        colorGrading = ColorGrading.Builder().toneMapper(ToneMapper.PBRNeutral()).build(engine)
        view.colorGrading = colorGrading
        renderer.clearOptions = renderer.clearOptions.apply { clear = true; clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0) }

        // Figure + props
        asset = assetLoader.createAsset(direct(model.glbBytes)) ?: error("Couldn't load the figure")
        resourceLoader.asyncBeginLoad(asset)
        scene.addEntities(asset.entities)
        jointEntities = IntArray(model.rig.size) { asset.getFirstEntityByName(model.rig.names[it]) }
        propBoxes = IntArray(6) { asset.getFirstEntityByName("prop_box_$it") }
        propMat = asset.getFirstEntityByName("prop_mat")
        propTowel = asset.getFirstEntityByName("prop_towel")
        val rm = engine.renderableManager
        for (e in asset.renderableEntities) {
            val ri = rm.getInstance(e)
            rm.setCastShadows(ri, false)
            rm.setReceiveShadows(ri, false)
            // Skinned parts are culled by their standing bind-pose bounds; the figure is always in view.
            rm.setCulling(ri, false)
        }

        // Lighting: soft studio IBL + a key light (the floor shadow is painted by ContactShadow).
        val loaded = KtxIbl.load(engine, model.ibl)
        ibl = loaded.light.apply { intensity = 22_000f }
        iblCubemap = loaded.cubemap
        scene.indirectLight = ibl
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.97f, 0.93f)
            .intensity(65_000f)
            .direction(0.3f, -1f, -0.4f)
            .castShadows(false)
            .build(engine, sun)
        scene.addEntity(sun)

        // Contact-shadow quad: 1×1 m (scaled each frame), textured with the painted shadow.
        shadowMaterial = Material.Builder().payload(direct(model.shadowMaterial), model.shadowMaterial.size).build(engine)
        shadowTexture = Texture.Builder().width(contact.size).height(contact.size).levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.R8).build(engine)
        shadowInstance = shadowMaterial.createInstance().apply {
            setParameter("strength", 0.42f)
            setParameter("shadow", shadowTexture, TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE))
        }
        // Texture row 0 is the top (v = 1) in Filament, and row 0 holds the lowest z.
        val verts = floatArrayOf(-0.5f, 0f, -0.5f, 0f, 1f, 0.5f, 0f, -0.5f, 1f, 1f, 0.5f, 0f, 0.5f, 1f, 0f, -0.5f, 0f, 0.5f, 0f, 0f)
        floorVb = VertexBuffer.Builder().bufferCount(1).vertexCount(4)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 20)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, 20)
            .build(engine)
        floorVb.setBufferAt(engine, 0, ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(verts).flip())
        val idx = shortArrayOf(0, 2, 1, 0, 3, 2)
        floorIb = IndexBuffer.Builder().indexCount(6).bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
        floorIb.setBuffer(engine, ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder()).asShortBuffer().put(idx).flip())
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, 0.5f, 0.01f, 0.5f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, floorVb, floorIb, 0, 6)
            .material(0, shadowInstance)
            .castShadows(false).receiveShadows(false).culling(false)
            .build(engine, floor)
        scene.addEntity(floor)

        uiHelper.isOpaque = false
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
                textureView.display?.let { displayHelper.attach(renderer, it) }
            }

            override fun onDetachedFromSurface() {
                displayHelper.detach()
                swapChain?.let { engine.destroySwapChain(it); engine.flushAndWait() }
                swapChain = null
            }

            override fun onResized(w: Int, h: Int) {
                width = max(1, w); height = max(1, h)
                view.viewport = Viewport(0, 0, width, height)
            }
        }
        uiHelper.attachTo(textureView)
    }

    /** Draws one frame. [orbit] adds the user's drag rotation (degrees) to the motion's camera. */
    fun render(frameTimeNanos: Long, motion: Motion, pose: SkeletonPose, mirrored: Boolean, orbit: Double) {
        if (!loaded) {
            resourceLoader.asyncUpdateLoad()
            if (resourceLoader.asyncGetLoadProgress() >= 1f) { loaded = true; asset.releaseSourceData() }
        }
        // The surface may already exist when UiHelper attaches (no onResized): follow the view's size.
        if (textureView.width > 0 && (textureView.width != width || textureView.height != height)) {
            width = textureView.width; height = textureView.height
            view.viewport = Viewport(0, 0, width, height)
        }
        applyPose(pose)
        applyProps(motion.props(mirrored))
        updateCamera(frameTimeNanos, motion, orbit)
        updateShadow(motion, pose)
        asset.instance.animator.updateBoneMatrices()
        val sc = swapChain ?: return
        if (!uiHelper.isReadyToRender) return
        if (renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    private fun applyPose(p: SkeletonPose) {
        val tm = engine.transformManager
        val m = FloatArray(16)
        for (i in jointEntities.indices) {
            val e = jointEntities[i]
            if (e == 0 || model.rig.names[i].startsWith("prop")) continue
            val q = p.local[i]; val t = p.translation[i]; val s = model.rig.restS[i]
            trs(m, t.x, t.y, t.z, q.x, q.y, q.z, q.w, s.x, s.y, s.z)
            tm.setTransform(tm.getInstance(e), m)
        }
    }

    private fun applyProps(props: List<Prop>) {
        val tm = engine.transformManager
        val m = FloatArray(16)
        var box = 0
        var matUsed = false
        var towelUsed = false
        fun place(e: Int, p: Prop?) {
            if (e == 0) return
            if (p == null) trs(m, 0.0, -10.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.001, 0.001, 0.001)
            else {
                val h = Math.toRadians(p.yaw) / 2
                trs(m, p.center.x, p.center.y, p.center.z, 0.0, sin(h), 0.0, cos(h), p.size.x, p.size.y, p.size.z)
            }
            tm.setTransform(tm.getInstance(e), m)
        }
        for (p in props) when (p.kind) {
            PropKind.BOX -> if (box < propBoxes.size) place(propBoxes[box++], p)
            PropKind.MAT -> { place(propMat, p); matUsed = true }
            PropKind.TOWEL -> { place(propTowel, p); towelUsed = true }
        }
        while (box < propBoxes.size) place(propBoxes[box++], null)
        if (!matUsed) place(propMat, null)
        if (!towelUsed) place(propTowel, null)
    }

    private fun updateCamera(frameTimeNanos: Long, motion: Motion, orbit: Double) {
        val b = model.bounds(motion)
        val yaw = motion.camera.yaw + orbit
        val pitch = motion.camera.pitch
        val fov = 30.0
        val aspect = width.toDouble() / height
        val (fwd, right, up) = basis(yaw, pitch)
        var hx = 0.0; var hy = 0.0; var hz = 0.0
        val c = b.center
        for (ix in 0..1) for (iy in 0..1) for (iz in 0..1) {
            val p = Vec3(if (ix == 0) b.lo.x else b.hi.x, if (iy == 0) b.lo.y else b.hi.y, if (iz == 0) b.lo.z else b.hi.z) - c
            hx = max(hx, kotlin.math.abs(p dot right)); hy = max(hy, kotlin.math.abs(p dot up)); hz = max(hz, kotlin.math.abs(p dot fwd))
        }
        val tv = tan(Math.toRadians(fov / 2))
        val dist = max(hy / tv, hx / (tv * aspect)) * 1.02 + hz * 0.6
        // Ease towards the new framing (smooth changes between exercises).
        val dt = if (lastFrameNanos == 0L) 1.0 else ((frameTimeNanos - lastFrameNanos) / 1e9).coerceIn(0.0, 0.1)
        lastFrameNanos = frameTimeNanos
        val k = if (camCenter == null) 1.0 else 1 - exp(-dt / 0.35)
        camCenter = camCenter?.lerp(c, k) ?: c
        camDist += (dist - camDist) * k
        camYaw += (yaw - camYaw) * if (orbit != 0.0) 1.0 else k
        camPitch += (pitch - camPitch) * k
        val (f2, r2, _) = basis(camYaw, camPitch)
        val cc = camCenter!!
        val eye = cc - f2 * camDist
        camera.setProjection(fov, aspect, 0.05, 20.0, Camera.Fov.VERTICAL)
        camera.lookAt(eye.x, eye.y, eye.z, cc.x, cc.y, cc.z, 0.0, 1.0, 0.0)
        // Key light from high up on the viewer's left, slightly in front, so the shadow falls to the
        // right where it can be seen; the IBL turns with the camera too.
        toLight = (r2 * -0.55 + Vec3.Y * 1.3 + f2 * -0.3).normalized()
        engine.lightManager.let { lm -> lm.setDirection(lm.getInstance(sun), (-toLight.x).toFloat(), (-toLight.y).toFloat(), (-toLight.z).toFloat()) }
        val a = Math.toRadians(camYaw)
        ibl.setRotation(floatArrayOf(cos(a).toFloat(), 0f, -sin(a).toFloat(), 0f, 1f, 0f, sin(a).toFloat(), 0f, cos(a).toFloat()))
    }

    private fun updateShadow(motion: Motion, pose: SkeletonPose) {
        val b = model.bounds(motion)
        val c = b.center
        val extent = maxOf(b.hi.x - b.lo.x, b.hi.z - b.lo.z) + 0.9
        contact.paint(pose.world, c.x, c.z, extent, toLight)
        val buf = shadowBuffers[shadowFrame++ % shadowBuffers.size]
        buf.clear(); buf.put(contact.bytes); buf.flip()
        shadowTexture.setImage(engine, 0, Texture.PixelBufferDescriptor(buf, Texture.Format.R, Texture.Type.UBYTE))
        val m = FloatArray(16)
        // Just above the mat (6 mm) so mat exercises get the shadow too.
        trs(m, c.x, 0.008, c.z, 0.0, 0.0, 0.0, 1.0, extent, 1.0, extent)
        engine.transformManager.let { tm -> tm.setTransform(tm.getInstance(floor), m) }
    }

    private fun basis(yawDeg: Double, pitchDeg: Double): Triple<Vec3, Vec3, Vec3> {
        val y = Math.toRadians(yawDeg); val p = Math.toRadians(pitchDeg)
        val fwd = Vec3(-sin(y) * cos(p), -sin(p), -cos(y) * cos(p))
        val right = Vec3(cos(y), 0.0, -sin(y))
        return Triple(fwd, right, right cross fwd)
    }

    fun destroy() {
        uiHelper.detach()
        engine.destroyEntity(floor)
        engine.renderableManager.destroy(floor)
        engine.destroyVertexBuffer(floorVb)
        engine.destroyIndexBuffer(floorIb)
        engine.destroyMaterialInstance(shadowInstance)
        engine.destroyTexture(shadowTexture)
        engine.destroyMaterial(shadowMaterial)
        engine.destroyEntity(sun)
        engine.destroyIndirectLight(ibl)
        iblCubemap?.let { engine.destroyTexture(it) }
        engine.destroyColorGrading(colorGrading)
        assetLoader.destroyAsset(asset)
        resourceLoader.destroy()
        assetLoader.destroy()
        materials.destroyMaterials()
        materials.destroy()
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)
        EntityManager.get().destroy(sun)
        EntityManager.get().destroy(floor)
        engine.destroy()
    }

    companion object {
        init { com.google.android.filament.Filament.init(); com.google.android.filament.gltfio.Gltfio.init() }

        private fun direct(bytes: ByteArray): ByteBuffer =
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).flip() as ByteBuffer

        /** Column-major TRS matrix. */
        fun trs(m: FloatArray, tx: Double, ty: Double, tz: Double, x: Double, y: Double, z: Double, w: Double, sx: Double, sy: Double, sz: Double) {
            m[0] = ((1 - 2 * (y * y + z * z)) * sx).toFloat(); m[1] = ((2 * (x * y + z * w)) * sx).toFloat(); m[2] = ((2 * (x * z - y * w)) * sx).toFloat(); m[3] = 0f
            m[4] = ((2 * (x * y - z * w)) * sy).toFloat(); m[5] = ((1 - 2 * (x * x + z * z)) * sy).toFloat(); m[6] = ((2 * (y * z + x * w)) * sy).toFloat(); m[7] = 0f
            m[8] = ((2 * (x * z + y * w)) * sz).toFloat(); m[9] = ((2 * (y * z - x * w)) * sz).toFloat(); m[10] = ((1 - 2 * (x * x + y * y)) * sz).toFloat(); m[11] = 0f
            m[12] = tx.toFloat(); m[13] = ty.toFloat(); m[14] = tz.toFloat(); m[15] = 1f
        }
    }
}
