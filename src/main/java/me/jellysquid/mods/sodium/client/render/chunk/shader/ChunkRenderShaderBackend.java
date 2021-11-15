package me.jellysquid.mods.sodium.client.render.chunk.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import me.jellysquid.mods.sodium.client.gl.attribute.GlVertexFormat;
import me.jellysquid.mods.sodium.client.gl.shader.*;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.compat.LegacyFogHelper;
import me.jellysquid.mods.sodium.client.model.vertex.type.ChunkVertexType;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkGraphicsState;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderBackend;
import me.jellysquid.mods.sodium.client.render.chunk.format.ChunkMeshAttribute;
import me.jellysquid.mods.sodium.client.render.chunk.passes.BlockRenderPass;

import net.coderbot.iris.Iris;
import net.coderbot.iris.gl.program.ProgramSamplers;
import net.coderbot.iris.gl.program.ProgramUniforms;
import net.coderbot.iris.pipeline.SodiumTerrainPipeline;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.coderbot.iris.shadows.ShadowRenderingState;
import net.coderbot.iris.sodiumglue.IrisChunkShaderBindingPoints;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Optional;

public abstract class ChunkRenderShaderBackend<T extends ChunkGraphicsState>
        implements ChunkRenderBackend<T> {
    private final EnumMap<ChunkFogMode, EnumMap<BlockRenderPass, ChunkProgram>> programs = new EnumMap<>(ChunkFogMode.class);
    private final EnumMap<ChunkFogMode, EnumMap<BlockRenderPass, ChunkProgram>> shadowPrograms = new EnumMap<>(ChunkFogMode.class);

    protected final ChunkVertexType vertexType;
    protected final GlVertexFormat<ChunkMeshAttribute> vertexFormat;

    protected ChunkProgram activeProgram;

    public ChunkRenderShaderBackend(ChunkVertexType vertexType) {
        this.vertexType = vertexType;
        this.vertexFormat = vertexType.getCustomVertexFormat();
    }

    private GlShader createVertexShader(RenderDevice device, ChunkFogMode fogMode, BlockRenderPass pass, boolean shadow, SodiumTerrainPipeline pipeline) {
        if (pipeline != null) {
            Optional<String> irisVertexShader;

            if (shadow) {
                irisVertexShader = pipeline.getShadowVertexShaderSource();
            } else {
                irisVertexShader = pass.isTranslucent() ? pipeline.getTranslucentVertexShaderSource() : pipeline.getTerrainVertexShaderSource();
            }

            if (irisVertexShader.isPresent()) {
                return new GlShader(device, ShaderType.VERTEX, new Identifier("iris", "sodium-terrain.vsh"),
                        irisVertexShader.get(), ShaderConstants.builder().build());
            }
        }

        return ShaderLoader.loadShader(device, ShaderType.VERTEX,
                new Identifier("sodium", "chunk_gl20.v.glsl"), fogMode.getDefines());
    }

    private GlShader createGeometryShader(RenderDevice device, ChunkFogMode fogMode, BlockRenderPass pass, boolean shadow, SodiumTerrainPipeline pipeline) {
        if (pipeline != null) {
            Optional<String> irisGeometryShader;

            if (shadow) {
                irisGeometryShader = pipeline.getShadowGeometryShaderSource();
            } else {
                irisGeometryShader = pass.isTranslucent() ? pipeline.getTranslucentGeometryShaderSource() : pipeline.getTerrainGeometryShaderSource();
            }

            if (irisGeometryShader.isPresent()) {
                return new GlShader(device, ShaderType.GEOMETRY, new Identifier("iris", "sodium-terrain.gsh"),
                        irisGeometryShader.get(), ShaderConstants.builder().build());
            }
        }

        return null;
    }

    private GlShader createFragmentShader(RenderDevice device, ChunkFogMode fogMode, BlockRenderPass pass, boolean shadow, SodiumTerrainPipeline pipeline) {
        if (pipeline != null) {
            Optional<String> irisFragmentShader;

            if (shadow) {
                irisFragmentShader = pipeline.getShadowFragmentShaderSource();
            } else {
                irisFragmentShader = pass.isTranslucent() ? pipeline.getTranslucentFragmentShaderSource() : pipeline.getTerrainFragmentShaderSource();
            }

            if (irisFragmentShader.isPresent()) {
                return new GlShader(device, ShaderType.FRAGMENT, new Identifier("iris", "sodium-terrain.fsh"),
                        irisFragmentShader.get(), ShaderConstants.builder().build());
            }
        }

        return ShaderLoader.loadShader(device, ShaderType.FRAGMENT,
                new Identifier("sodium", "chunk_gl20.f.glsl"), fogMode.getDefines());
    }

    private ChunkProgram createShader(RenderDevice device, ChunkFogMode fogMode, BlockRenderPass pass,
                                      GlVertexFormat<ChunkMeshAttribute> vertexFormat, boolean shadow,
                                      SodiumTerrainPipeline pipeline) {
        GlShader vertShader = createVertexShader(device, fogMode, pass, shadow, pipeline);
        GlShader geomShader = createGeometryShader(device, fogMode, pass, shadow, pipeline);
        GlShader fragShader = createFragmentShader(device, fogMode, pass, shadow, pipeline);

        try {
            GlProgram.Builder builder = GlProgram.builder(new Identifier("sodium", "chunk_shader_for_" + pass.toString().toLowerCase(Locale.ROOT) + (shadow ? "_gbuffer" : "_shadow")));

            if (geomShader != null) {
                builder.attachShader(geomShader);
            }

            return builder.attachShader(vertShader)
                    .attachShader(fragShader)
                    .bindAttribute("a_Pos", ChunkShaderBindingPoints.POSITION)
                    .bindAttribute("a_Color", ChunkShaderBindingPoints.COLOR)
                    .bindAttribute("a_TexCoord", ChunkShaderBindingPoints.TEX_COORD)
                    .bindAttribute("a_LightCoord", ChunkShaderBindingPoints.LIGHT_COORD)
                    .bindAttribute("mc_Entity", IrisChunkShaderBindingPoints.BLOCK_ID)
                    .bindAttribute("mc_midTexCoord", IrisChunkShaderBindingPoints.MID_TEX_COORD)
                    .bindAttribute("at_tangent", IrisChunkShaderBindingPoints.TANGENT)
                    .bindAttribute("a_Normal", IrisChunkShaderBindingPoints.NORMAL)
                    .bindAttribute("d_ModelOffset", ChunkShaderBindingPoints.MODEL_OFFSET)
                    .build((program, name) -> {
                        ProgramUniforms uniforms = null;
                        ProgramSamplers samplers = null;

                        if (pipeline != null) {
                            uniforms = pipeline.initUniforms(name);

                            if (shadow) {
                                samplers = pipeline.initShadowSamplers(name);
                            } else {
                                samplers = pipeline.initTerrainSamplers(name);
                            }
                        }

                        return new ChunkProgram(device, program, name, fogMode.getFactory(), uniforms, samplers);
                    });
        } finally {
            vertShader.delete();
            if (geomShader != null) {
                geomShader.delete();
            }
            fragShader.delete();
        }
    }

    @Override
    public final void createShaders(RenderDevice device) {
        WorldRenderingPipeline worldRenderingPipeline = Iris.getPipelineManager().getPipeline();
        SodiumTerrainPipeline sodiumTerrainPipeline = null;

        if (worldRenderingPipeline != null) {
            sodiumTerrainPipeline = worldRenderingPipeline.getSodiumTerrainPipeline();
        }

        Iris.getPipelineManager().clearSodiumShaderReloadNeeded();

        for (ChunkFogMode fogMode : ChunkFogMode.values()) {
            this.programs.put(fogMode, createShadersForFogMode(device, fogMode, false, sodiumTerrainPipeline));
            this.shadowPrograms.put(fogMode, createShadersForFogMode(device, fogMode, true, sodiumTerrainPipeline));
        }
    }

    private EnumMap<BlockRenderPass, ChunkProgram> createShadersForFogMode(RenderDevice device, ChunkFogMode mode, boolean shadow,
                                                                           SodiumTerrainPipeline pipeline) {
        EnumMap<BlockRenderPass, ChunkProgram> shaders = new EnumMap<>(BlockRenderPass.class);

        for (BlockRenderPass pass : BlockRenderPass.VALUES) {
            shaders.put(pass, this.createShader(device, mode, pass, this.vertexFormat, shadow, pipeline));
        }

        return shaders;
    }

    @Override
    public void begin(MatrixStack matrixStack, BlockRenderPass pass) {
        if (Iris.getPipelineManager().isSodiumShaderReloadNeeded()) {
            RenderDevice device = this.programs.get(ChunkFogMode.LINEAR).get(BlockRenderPass.SOLID).getDevice();
            deleteShaders();
            createShaders(device);
        }

        if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
            this.activeProgram = this.shadowPrograms.get(LegacyFogHelper.getFogMode()).get(pass);

            // No back face culling during the shadow pass
            // TODO: Hopefully this won't be necessary in the future...
            RenderSystem.disableCull();
        } else {
            this.activeProgram = this.programs.get(LegacyFogHelper.getFogMode()).get(pass);
        }

        this.activeProgram.bind();
        this.activeProgram.setup(matrixStack, this.vertexType.getModelScale(), this.vertexType.getTextureScale());
    }

    private void deleteShaders() {
        for (EnumMap<BlockRenderPass, ChunkProgram> shaders: this.programs.values()) {
            for (ChunkProgram shader : shaders.values()) {
                shader.delete();
            }
        }

        for (EnumMap<BlockRenderPass, ChunkProgram> shaders: this.shadowPrograms.values()) {
            for (ChunkProgram shader : shaders.values()) {
                shader.delete();
            }
        }
    }

    @Override
    public void end(MatrixStack matrixStack) {
        this.activeProgram.unbind();
        this.activeProgram = null;
        ProgramUniforms.clearActiveUniforms();
    }

    @Override
    public void delete() {
        deleteShaders();
    }

    @Override
    public ChunkVertexType getVertexType() {
        return this.vertexType;
    }
}
