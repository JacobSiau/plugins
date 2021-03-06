/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.gpu;

import com.google.common.primitives.Ints;
import com.google.inject.Provides;
import com.jogamp.nativewindow.awt.AWTGraphicsConfiguration;
import com.jogamp.nativewindow.awt.JAWTWindow;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLContext;
import com.jogamp.opengl.GLDrawable;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLException;
import com.jogamp.opengl.GLFBODrawable;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.math.Matrix4;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import jogamp.nativewindow.SurfaceScaleUtils;
import jogamp.nativewindow.jawt.x11.X11JAWTWindow;
import jogamp.nativewindow.macosx.OSXUtil;
import jogamp.newt.awt.NewtFactoryAWT;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.Entity;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.NodeCache;
import net.runelite.api.Perspective;
import net.runelite.api.Scene;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import net.runelite.api.TileModel;
import net.runelite.api.TilePaint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.Hooks;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.PluginType;
import static net.runelite.client.plugins.gpu.GLUtil.*;
import net.runelite.client.plugins.gpu.config.AntiAliasingMode;
import net.runelite.client.plugins.gpu.config.UIScalingMode;
import net.runelite.client.plugins.gpu.template.Template;
import net.runelite.client.plugins.mirror.MirrorPlugin;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.OSType;
import org.apache.commons.lang3.ArrayUtils;
import org.pf4j.Extension;

@PluginDependency(MirrorPlugin.class)
@PluginDescriptor(
	name = "GPU",
	description = "Utilizes the GPU",
	enabledByDefault = false,
	tags = {"fog", "draw distance"},
	type = PluginType.MISCELLANEOUS,
	loadInSafeMode = false
)
@Slf4j
@Extension
public class GpuPlugin extends Plugin implements DrawCallbacks, MouseListener
{
	// This is the maximum number of triangles the compute shaders support
	private static final int MAX_TRIANGLE = 4096;
	private static final int SMALL_TRIANGLE_COUNT = 512;
	private static final int FLAG_SCENE_BUFFER = Integer.MIN_VALUE;
	private static final int DEFAULT_DISTANCE = 25;
	static final int MAX_DISTANCE = 90;
	static final int MAX_FOG_DEPTH = 100;
	private int mouseX = 0;
	private int mouseY = 0;
	private final Image cursor = ImageUtil.getResourceStreamFromClass(GpuPlugin.class, "cursor.png");
	private BufferedImage tempBufferedImage;
	private boolean drawThreadRunning = false;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private GpuPluginConfig config;

	@Inject
	private TextureManager textureManager;

	@Inject
	private SceneUploader sceneUploader;

	@Inject
	private DrawManager drawManager;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private MouseManager mouseManager;

	private boolean useComputeShaders;

	private Canvas canvas;
	private JAWTWindow jawtWindow;
	private GL4 gl;
	private GLContext glContext;
	private GLDrawable glDrawable;

	static final String LINUX_VERSION_HEADER =
		"#version 420\n" +
			"#extension GL_ARB_compute_shader : require\n" +
			"#extension GL_ARB_shader_storage_buffer_object : require\n" +
			"#extension GL_ARB_explicit_attrib_location : require\n";
	static final String WINDOWS_VERSION_HEADER = "#version 430\n";

	static final Shader PROGRAM = new Shader()
		.add(GL4.GL_VERTEX_SHADER, "vert.glsl")
		.add(GL4.GL_FRAGMENT_SHADER, "frag.glsl");

	static final Shader COMPUTE_PROGRAM = new Shader()
		.add(GL4.GL_COMPUTE_SHADER, "comp.glsl");

	static final Shader SMALL_COMPUTE_PROGRAM = new Shader()
		.add(GL4.GL_COMPUTE_SHADER, "comp_small.glsl");

	static final Shader UNORDERED_COMPUTE_PROGRAM = new Shader()
		.add(GL4.GL_COMPUTE_SHADER, "comp_unordered.glsl");

	static final Shader UI_PROGRAM = new Shader()
		.add(GL4.GL_VERTEX_SHADER, "vertui.glsl")
		.add(GL4.GL_FRAGMENT_SHADER, "fragui.glsl");

	private int glProgram;
	private int glComputeProgram;
	private int glSmallComputeProgram;
	private int glUnorderedComputeProgram;
	private int glUiProgram;

	private int vaoHandle;

	private int interfaceTexture;

	private int vaoUiHandle;
	private int vboUiHandle;

	private int fboSceneHandle;
	private int texSceneHandle;
	private int rboSceneHandle;

	// scene vertex buffer id
	private int bufferId;
	// scene uv buffer id
	private int uvBufferId;

	private int tmpBufferId; // temporary scene vertex buffer
	private int tmpUvBufferId; // temporary scene uv buffer
	private int tmpModelBufferId; // scene model buffer, large
	private int tmpModelBufferSmallId; // scene model buffer, small
	private int tmpModelBufferUnorderedId;
	private int tmpOutBufferId; // target vertex buffer for compute shaders
	private int tmpOutUvBufferId; // target uv buffer for compute shaders

	private int textureArrayId;

	private int uniformBufferId;
	private final IntBuffer uniformBuffer = GpuIntBuffer.allocateDirect(5 + 3 + 2048 * 4);
	private final float[] textureOffsets = new float[128];

	private GpuIntBuffer vertexBuffer;
	private GpuFloatBuffer uvBuffer;

	private GpuIntBuffer modelBufferUnordered;
	private GpuIntBuffer modelBufferSmall;
	private GpuIntBuffer modelBuffer;

	private int unorderedModels;

	/**
	 * number of models in small buffer
	 */
	private int smallModels;

	/**
	 * number of models in large buffer
	 */
	private int largeModels;

	/**
	 * offset in the target buffer for model
	 */
	private int targetBufferOffset;

	/**
	 * offset into the temporary scene vertex buffer
	 */
	private int tempOffset;

	/**
	 * offset into the temporary scene uv buffer
	 */
	private int tempUvOffset;

	private int lastViewportWidth;
	private int lastViewportHeight;
	private int lastCanvasWidth;
	private int lastCanvasHeight;
	private int lastStretchedCanvasWidth;
	private int lastStretchedCanvasHeight;
	private AntiAliasingMode lastAntiAliasingMode;
	private int lastAnisotropicFilteringLevel = -1;

	private int centerX;
	private int centerY;
	private int yaw;
	private int pitch;
	// fields for non-compute draw
	private boolean drawingModel;
	private int modelX, modelY, modelZ;
	private int modelOrientation;

	// Uniforms
	private int uniUseFog;
	private int uniFogColor;
	private int uniFogDepth;
	private int uniFogCornerRadius;
	private int uniFogDensity;
	private int uniDrawDistance;
	private int uniProjectionMatrix;
	private int uniBrightness;
	private int uniTex;
	private int uniTexSamplingMode;
	private int uniTexSourceDimensions;
	private int uniTexTargetDimensions;
	private int uniTextures;
	private int uniTextureOffsets;
	private int uniBlockSmall;
	private int uniBlockLarge;
	private int uniBlockMain;
	private int uniSmoothBanding;
	private Graphics tempGraphics;

	@Override
	protected void startUp()
	{
		clientThread.invoke(() ->
		{
			try
			{
				bufferId = uvBufferId = uniformBufferId = tmpBufferId = tmpUvBufferId = tmpModelBufferId = tmpModelBufferSmallId = tmpModelBufferUnorderedId = tmpOutBufferId = tmpOutUvBufferId = -1;
				texSceneHandle = fboSceneHandle = rboSceneHandle = -1; // AA FBO
				unorderedModels = smallModels = largeModels = 0;
				drawingModel = false;

				canvas = client.getCanvas();

				if (!canvas.isDisplayable())
				{
					return false;
				}

				// OSX supports up to OpenGL 4.1, however 4.3 is required for compute shaders
				useComputeShaders = config.useComputeShaders() && OSType.getOSType() != OSType.MacOS;

				canvas.setIgnoreRepaint(true);

				vertexBuffer = new GpuIntBuffer();
				uvBuffer = new GpuFloatBuffer();

				modelBufferUnordered = new GpuIntBuffer();
				modelBufferSmall = new GpuIntBuffer();
				modelBuffer = new GpuIntBuffer();

				if (log.isDebugEnabled())
				{
					System.setProperty("jogl.debug", "true");
				}

				GLProfile.initSingleton();

				invokeOnMainThread(() ->
				{
					GLProfile glProfile = GLProfile.get(GLProfile.GL4);

					GLCapabilities glCaps = new GLCapabilities(glProfile);
					AWTGraphicsConfiguration config = AWTGraphicsConfiguration.create(canvas.getGraphicsConfiguration(), glCaps, glCaps);

					jawtWindow = NewtFactoryAWT.getNativeWindow(canvas, config);
					canvas.setFocusable(true);

					GLDrawableFactory glDrawableFactory = GLDrawableFactory.getFactory(glProfile);

					jawtWindow.lockSurface();
					try
					{
						glDrawable = glDrawableFactory.createGLDrawable(jawtWindow);
						glDrawable.setRealized(true);

						glContext = glDrawable.createContext(null);
						if (log.isDebugEnabled())
						{
							// Debug config on context needs to be set before .makeCurrent call
							glContext.enableGLDebugMessage(true);
						}
					}
					finally
					{
						jawtWindow.unlockSurface();
					}

					int res = glContext.makeCurrent();
					if (res == GLContext.CONTEXT_NOT_CURRENT)
					{
						throw new GLException("Unable to make context current");
					}

					// Surface needs to be unlocked on X11 window otherwise input is blocked
					if (jawtWindow instanceof X11JAWTWindow && jawtWindow.getLock().isLocked())
					{
						jawtWindow.unlockSurface();
					}

					this.gl = glContext.getGL().getGL4();
					gl.setSwapInterval(0);

					if (log.isDebugEnabled())
					{
						gl.glEnable(gl.GL_DEBUG_OUTPUT);

						// Suppress warning messages which flood the log on NVIDIA systems.
						gl.getContext().glDebugMessageControl(gl.GL_DEBUG_SOURCE_API, gl.GL_DEBUG_TYPE_OTHER,
							gl.GL_DEBUG_SEVERITY_NOTIFICATION, 0, null, 0, false);
					}

					initVao();
					try
					{
						initProgram();
					}
					catch (ShaderException ex)
					{
						throw new RuntimeException(ex);
					}
					initInterfaceTexture();
					initUniformBuffer();
					initBuffers();
				});

				client.setDrawCallbacks(this);
				client.setGpu(true);

				// force rebuild of main buffer provider to enable alpha channel
				client.resizeCanvas();

				lastViewportWidth = lastViewportHeight = lastCanvasWidth = lastCanvasHeight = -1;
				lastStretchedCanvasWidth = lastStretchedCanvasHeight = -1;
				lastAntiAliasingMode = null;

				textureArrayId = -1;

				// increase size of model cache for dynamic objects since we are extending scene size
				NodeCache cachedModels2 = client.getCachedModels2();
				cachedModels2.setCapacity(256);
				cachedModels2.setRemainingCapacity(256);
				cachedModels2.reset();

				if (client.getGameState() == GameState.LOGGED_IN)
				{
					uploadScene();
				}
				mouseManager.registerMouseListener(this);
			}
			catch (Throwable e)
			{
				log.error("Error starting GPU plugin", e);

				SwingUtilities.invokeLater(() ->
				{
					try
					{
						pluginManager.setPluginEnabled(this, false);
						pluginManager.stopPlugin(this);
					}
					catch (PluginInstantiationException ex)
					{
						log.error("error stopping plugin", ex);
					}
				});

				shutDown();
			}
			return true;
		});
	}

	@Override
	protected void shutDown()
	{
		clientThread.invoke(() ->
		{
			client.setGpu(false);
			client.setDrawCallbacks(null);

			invokeOnMainThread(() ->
			{
				if (gl != null)
				{
					if (textureArrayId != -1)
					{
						textureManager.freeTextureArray(gl, textureArrayId);
						textureArrayId = -1;
					}

					if (uniformBufferId != -1)
					{
						GLUtil.glDeleteBuffer(gl, uniformBufferId);
						uniformBufferId = -1;
					}

					shutdownBuffers();
					shutdownInterfaceTexture();
					shutdownProgram();
					shutdownVao();
					shutdownAAFbo();
				}

				if (jawtWindow != null)
				{
					if (!jawtWindow.getLock().isLocked())
					{
						jawtWindow.lockSurface();
					}

					if (glContext != null)
					{
						glContext.destroy();
					}

					// this crashes on osx when the plugin is turned back on, don't know why
					// we'll just leak the window...
					if (OSType.getOSType() != OSType.MacOS)
					{
						NewtFactoryAWT.destroyNativeWindow(jawtWindow);
					}
				}
			});

			GLProfile.shutdown();

			jawtWindow = null;
			gl = null;
			glDrawable = null;
			glContext = null;

			vertexBuffer = null;
			uvBuffer = null;

			modelBufferSmall = null;
			modelBuffer = null;
			modelBufferUnordered = null;

			// force main buffer provider rebuild to turn off alpha channel
			client.resizeCanvas();
			mouseManager.unregisterMouseListener(this);
		});
	}

	@Provides
	GpuPluginConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GpuPluginConfig.class);
	}

	private void initProgram() throws ShaderException
	{
		String versionHeader = OSType.getOSType() == OSType.Linux ? LINUX_VERSION_HEADER : WINDOWS_VERSION_HEADER;
		Template template = new Template();
		template.add(key ->
		{
			if ("version_header".equals(key))
			{
				return versionHeader;
			}
			return null;
		});
		template.addInclude(GpuPlugin.class);

		glProgram = PROGRAM.compile(gl, template);
		glUiProgram = UI_PROGRAM.compile(gl, template);

		if (useComputeShaders)
		{
			glComputeProgram = COMPUTE_PROGRAM.compile(gl, template);
			glSmallComputeProgram = SMALL_COMPUTE_PROGRAM.compile(gl, template);
			glUnorderedComputeProgram = UNORDERED_COMPUTE_PROGRAM.compile(gl, template);
		}

		initUniforms();
	}

	private void initUniforms()
	{
		uniProjectionMatrix = gl.glGetUniformLocation(glProgram, "projectionMatrix");
		uniBrightness = gl.glGetUniformLocation(glProgram, "brightness");
		uniSmoothBanding = gl.glGetUniformLocation(glProgram, "smoothBanding");
		uniUseFog = gl.glGetUniformLocation(glProgram, "useFog");
		uniFogColor = gl.glGetUniformLocation(glProgram, "fogColor");
		uniFogDepth = gl.glGetUniformLocation(glProgram, "fogDepth");
		uniFogCornerRadius = gl.glGetUniformLocation(glProgram, "fogCornerRadius");
		uniFogDensity = gl.glGetUniformLocation(glProgram, "fogDensity");
		uniDrawDistance = gl.glGetUniformLocation(glProgram, "drawDistance");

		uniTex = gl.glGetUniformLocation(glUiProgram, "tex");
		uniTexSamplingMode = gl.glGetUniformLocation(glUiProgram, "samplingMode");
		uniTexTargetDimensions = gl.glGetUniformLocation(glUiProgram, "targetDimensions");
		uniTexSourceDimensions = gl.glGetUniformLocation(glUiProgram, "sourceDimensions");
		uniTextures = gl.glGetUniformLocation(glProgram, "textures");
		uniTextureOffsets = gl.glGetUniformLocation(glProgram, "textureOffsets");

		uniBlockSmall = gl.glGetUniformBlockIndex(glSmallComputeProgram, "uniforms");
		uniBlockLarge = gl.glGetUniformBlockIndex(glComputeProgram, "uniforms");
		uniBlockMain = gl.glGetUniformBlockIndex(glProgram, "uniforms");
	}

	private void shutdownProgram()
	{
		gl.glDeleteProgram(glProgram);
		glProgram = -1;

		gl.glDeleteProgram(glComputeProgram);
		glComputeProgram = -1;

		gl.glDeleteProgram(glSmallComputeProgram);
		glSmallComputeProgram = -1;

		gl.glDeleteProgram(glUnorderedComputeProgram);
		glUnorderedComputeProgram = -1;

		gl.glDeleteProgram(glUiProgram);
		glUiProgram = -1;
	}

	private void initVao()
	{
		// Create VAO
		vaoHandle = glGenVertexArrays(gl);

		// Create UI VAO
		vaoUiHandle = glGenVertexArrays(gl);
		// Create UI buffer
		vboUiHandle = glGenBuffers(gl);
		gl.glBindVertexArray(vaoUiHandle);

		FloatBuffer vboUiBuf = GpuFloatBuffer.allocateDirect(5 * 4);
		vboUiBuf.put(new float[]{
			// positions     // texture coords
			1f, 1f, 0.0f, 1.0f, 0f, // top right
			1f, -1f, 0.0f, 1.0f, 1f, // bottom right
			-1f, -1f, 0.0f, 0.0f, 1f, // bottom left
			-1f, 1f, 0.0f, 0.0f, 0f  // top left
		});
		vboUiBuf.rewind();
		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, vboUiHandle);
		gl.glBufferData(gl.GL_ARRAY_BUFFER, vboUiBuf.capacity() * Float.BYTES, vboUiBuf, gl.GL_STATIC_DRAW);

		// position attribute
		gl.glVertexAttribPointer(0, 3, gl.GL_FLOAT, false, 5 * Float.BYTES, 0);
		gl.glEnableVertexAttribArray(0);

		// texture coord attribute
		gl.glVertexAttribPointer(1, 2, gl.GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
		gl.glEnableVertexAttribArray(1);

		// unbind VBO
		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, 0);
	}

	private void shutdownVao()
	{
		glDeleteVertexArrays(gl, vaoHandle);
		vaoHandle = -1;

		glDeleteBuffer(gl, vboUiHandle);
		vboUiHandle = -1;

		glDeleteVertexArrays(gl, vaoUiHandle);
		vaoUiHandle = -1;
	}

	private void initBuffers()
	{
		bufferId = glGenBuffers(gl);
		uvBufferId = glGenBuffers(gl);
		tmpBufferId = glGenBuffers(gl);
		tmpUvBufferId = glGenBuffers(gl);
		tmpModelBufferId = glGenBuffers(gl);
		tmpModelBufferSmallId = glGenBuffers(gl);
		tmpModelBufferUnorderedId = glGenBuffers(gl);
		tmpOutBufferId = glGenBuffers(gl);
		tmpOutUvBufferId = glGenBuffers(gl);
	}

	private void shutdownBuffers()
	{
		if (bufferId != -1)
		{
			glDeleteBuffer(gl, bufferId);
			bufferId = -1;
		}

		if (uvBufferId != -1)
		{
			glDeleteBuffer(gl, uvBufferId);
			uvBufferId = -1;
		}

		if (tmpBufferId != -1)
		{
			glDeleteBuffer(gl, tmpBufferId);
			tmpBufferId = -1;
		}

		if (tmpUvBufferId != -1)
		{
			glDeleteBuffer(gl, tmpUvBufferId);
			tmpUvBufferId = -1;
		}

		if (tmpModelBufferId != -1)
		{
			glDeleteBuffer(gl, tmpModelBufferId);
			tmpModelBufferId = -1;
		}

		if (tmpModelBufferSmallId != -1)
		{
			glDeleteBuffer(gl, tmpModelBufferSmallId);
			tmpModelBufferSmallId = -1;
		}

		if (tmpModelBufferUnorderedId != -1)
		{
			glDeleteBuffer(gl, tmpModelBufferUnorderedId);
			tmpModelBufferUnorderedId = -1;
		}

		if (tmpOutBufferId != -1)
		{
			glDeleteBuffer(gl, tmpOutBufferId);
			tmpOutBufferId = -1;
		}

		if (tmpOutUvBufferId != -1)
		{
			glDeleteBuffer(gl, tmpOutUvBufferId);
			tmpOutUvBufferId = -1;
		}
	}

	private void initInterfaceTexture()
	{
		interfaceTexture = glGenTexture(gl);
		gl.glBindTexture(gl.GL_TEXTURE_2D, interfaceTexture);
		gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_WRAP_S, gl.GL_CLAMP_TO_EDGE);
		gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_WRAP_T, gl.GL_CLAMP_TO_EDGE);
		gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MIN_FILTER, gl.GL_LINEAR);
		gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MAG_FILTER, gl.GL_LINEAR);
		gl.glBindTexture(gl.GL_TEXTURE_2D, 0);
	}

	private void shutdownInterfaceTexture()
	{
		glDeleteTexture(gl, interfaceTexture);
		interfaceTexture = -1;
	}

	private void initUniformBuffer()
	{
		uniformBufferId = glGenBuffers(gl);
		gl.glBindBuffer(gl.GL_UNIFORM_BUFFER, uniformBufferId);
		uniformBuffer.clear();
		uniformBuffer.put(new int[8]);
		final int[] pad = new int[2];
		for (int i = 0; i < 2048; i++)
		{
			uniformBuffer.put(Perspective.SINE[i]);
			uniformBuffer.put(Perspective.COSINE[i]);
			uniformBuffer.put(pad);
		}
		uniformBuffer.flip();

		gl.glBufferData(gl.GL_UNIFORM_BUFFER, uniformBuffer.limit() * Integer.BYTES, uniformBuffer, gl.GL_DYNAMIC_DRAW);
		gl.glBindBuffer(gl.GL_UNIFORM_BUFFER, 0);
	}

	private void initAAFbo(int width, int height, int aaSamples)
	{
		// Create and bind the FBO
		fboSceneHandle = glGenFrameBuffer(gl);
		gl.glBindFramebuffer(gl.GL_FRAMEBUFFER, fboSceneHandle);

		// Create color render buffer
		rboSceneHandle = glGenRenderbuffer(gl);
		gl.glBindRenderbuffer(gl.GL_RENDERBUFFER, rboSceneHandle);
		gl.glRenderbufferStorageMultisample(gl.GL_RENDERBUFFER, aaSamples, gl.GL_RGBA, width, height);
		gl.glFramebufferRenderbuffer(gl.GL_FRAMEBUFFER, gl.GL_COLOR_ATTACHMENT0, gl.GL_RENDERBUFFER, rboSceneHandle);

		// Create texture
		texSceneHandle = glGenTexture(gl);
		gl.glBindTexture(gl.GL_TEXTURE_2D_MULTISAMPLE, texSceneHandle);
		gl.glTexImage2DMultisample(gl.GL_TEXTURE_2D_MULTISAMPLE, aaSamples, gl.GL_RGBA, width, height, true);

		// Bind texture
		gl.glFramebufferTexture2D(gl.GL_FRAMEBUFFER, gl.GL_COLOR_ATTACHMENT0, gl.GL_TEXTURE_2D_MULTISAMPLE, texSceneHandle, 0);

		// Reset
		gl.glBindTexture(gl.GL_TEXTURE_2D_MULTISAMPLE, 0);
		gl.glBindFramebuffer(gl.GL_FRAMEBUFFER, 0);
		gl.glBindRenderbuffer(gl.GL_RENDERBUFFER, 0);
	}

	private void shutdownAAFbo()
	{
		if (texSceneHandle != -1)
		{
			glDeleteTexture(gl, texSceneHandle);
			texSceneHandle = -1;
		}

		if (fboSceneHandle != -1)
		{
			glDeleteFrameBuffer(gl, fboSceneHandle);
			fboSceneHandle = -1;
		}

		if (rboSceneHandle != -1)
		{
			glDeleteRenderbuffers(gl, rboSceneHandle);
			rboSceneHandle = -1;
		}
	}

	@Override
	public void drawScene(int cameraX, int cameraY, int cameraZ, int cameraPitch, int cameraYaw, int plane)
	{
		centerX = client.getCenterX();
		centerY = client.getCenterY();
		yaw = client.getCameraYaw();
		pitch = client.getCameraPitch();

		final Scene scene = client.getScene();
		scene.setDrawDistance(getDrawDistance());
	}

	@Override
	public void drawScenePaint(int orientation, int pitchSin, int pitchCos, int yawSin, int yawCos, int x, int y, int z,
							TilePaint paint, int tileZ, int tileX, int tileY,
							int zoom, int centerX, int centerY)
	{
		if (!useComputeShaders)
		{
			targetBufferOffset += sceneUploader.upload(paint,
				tileZ, tileX, tileY,
				vertexBuffer, uvBuffer,
				Perspective.LOCAL_TILE_SIZE * tileX,
				Perspective.LOCAL_TILE_SIZE * tileY,
				true
			);
		}
		else if (paint.getBufferLen() > 0)
		{
			x = tileX * Perspective.LOCAL_TILE_SIZE;
			y = 0;
			z = tileY * Perspective.LOCAL_TILE_SIZE;

			GpuIntBuffer b = modelBufferUnordered;
			++unorderedModels;

			b.ensureCapacity(8);
			IntBuffer buffer = b.getBuffer();
			buffer.put(paint.getBufferOffset());
			buffer.put(paint.getUvBufferOffset());
			buffer.put(2);
			buffer.put(targetBufferOffset);
			buffer.put(FLAG_SCENE_BUFFER);
			buffer.put(x).put(y).put(z);

			targetBufferOffset += 2 * 3;
		}
	}

	@Override
	public void drawSceneModel(int orientation, int pitchSin, int pitchCos, int yawSin, int yawCos, int x, int y, int z,
							TileModel model, int tileZ, int tileX, int tileY,
							int zoom, int centerX, int centerY)
	{
		if (!useComputeShaders)
		{
			targetBufferOffset += sceneUploader.upload(model,
				tileX, tileY,
				vertexBuffer, uvBuffer,
				tileX << Perspective.LOCAL_COORD_BITS, tileY << Perspective.LOCAL_COORD_BITS, true);
		}
		else if (model.getBufferLen() > 0)
		{
			x = tileX * Perspective.LOCAL_TILE_SIZE;
			y = 0;
			z = tileY * Perspective.LOCAL_TILE_SIZE;

			GpuIntBuffer b = modelBufferUnordered;
			++unorderedModels;

			b.ensureCapacity(8);
			IntBuffer buffer = b.getBuffer();
			buffer.put(model.getBufferOffset());
			buffer.put(model.getUvBufferOffset());
			buffer.put(model.getBufferLen() / 3);
			buffer.put(targetBufferOffset);
			buffer.put(FLAG_SCENE_BUFFER);
			buffer.put(x).put(y).put(z);

			targetBufferOffset += model.getBufferLen();
		}
	}

	@Override
	public void draw()
	{
		invokeOnMainThread(this::drawFrame);
	}

	private void resize(int canvasWidth, int canvasHeight, int viewportWidth, int viewportHeight)
	{
		if (canvasWidth != lastCanvasWidth || canvasHeight != lastCanvasHeight)
		{
			lastCanvasWidth = canvasWidth;
			lastCanvasHeight = canvasHeight;

			gl.glBindTexture(gl.GL_TEXTURE_2D, interfaceTexture);
			gl.glTexImage2D(gl.GL_TEXTURE_2D, 0, gl.GL_RGBA, canvasWidth, canvasHeight, 0, gl.GL_BGRA, gl.GL_UNSIGNED_INT_8_8_8_8_REV, null);
			gl.glBindTexture(gl.GL_TEXTURE_2D, 0);

			if (OSType.getOSType() == OSType.MacOS && glDrawable instanceof GLFBODrawable)
			{
				// GLDrawables created with createGLDrawable() do not have a resize listener
				// I don't know why this works with Windows/Linux, but on OSX
				// it prevents JOGL from resizing its FBOs and underlying GL textures. So,
				// we manually trigger a resize here.
				GLFBODrawable glfboDrawable = (GLFBODrawable) glDrawable;
				glfboDrawable.resetSize(gl);
			}
		}
	}

	private void drawFrame()
	{
		if (jawtWindow.getAWTComponent() != client.getCanvas())
		{
			// We inject code in the game engine mixin to prevent the client from doing canvas replacement,
			// so this should not ever be hit
			log.warn("Canvas invalidated!");
			shutDown();
			startUp();
			return;
		}

		if (client.getGameState() == GameState.LOADING || client.getGameState() == GameState.HOPPING)
		{
			// While the client is loading it doesn't draw
			return;
		}

		final int canvasHeight = client.getCanvasHeight();
		final int canvasWidth = client.getCanvasWidth();

		final int viewportHeight = client.getViewportHeight();
		final int viewportWidth = client.getViewportWidth();

		resize(canvasWidth, canvasHeight, viewportWidth, viewportHeight);

		// Setup anti-aliasing
		final AntiAliasingMode antiAliasingMode = config.antiAliasingMode();
		final boolean aaEnabled = antiAliasingMode != AntiAliasingMode.DISABLED;

		if (aaEnabled)
		{
			gl.glEnable(gl.GL_MULTISAMPLE);

			final Dimension stretchedDimensions = client.getStretchedDimensions();

			final int stretchedCanvasWidth = client.isStretchedEnabled() ? stretchedDimensions.width : canvasWidth;
			final int stretchedCanvasHeight = client.isStretchedEnabled() ? stretchedDimensions.height : canvasHeight;

			// Re-create fbo
			if (lastStretchedCanvasWidth != stretchedCanvasWidth
				|| lastStretchedCanvasHeight != stretchedCanvasHeight
				|| lastAntiAliasingMode != antiAliasingMode)
			{
				shutdownAAFbo();

				final int maxSamples = glGetInteger(gl, gl.GL_MAX_SAMPLES);
				final int samples = Math.min(antiAliasingMode.getSamples(), maxSamples);

				initAAFbo(stretchedCanvasWidth, stretchedCanvasHeight, samples);

				lastStretchedCanvasWidth = stretchedCanvasWidth;
				lastStretchedCanvasHeight = stretchedCanvasHeight;
			}

			gl.glBindFramebuffer(gl.GL_DRAW_FRAMEBUFFER, fboSceneHandle);
		}
		else
		{
			gl.glDisable(gl.GL_MULTISAMPLE);
			shutdownAAFbo();
		}

		lastAntiAliasingMode = antiAliasingMode;

		// Clear scene
		int sky = client.getSkyboxColor();
		gl.glClearColor((sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f, 1f);
		gl.glClear(gl.GL_COLOR_BUFFER_BIT);

		// Upload buffers
		vertexBuffer.flip();
		uvBuffer.flip();
		modelBuffer.flip();
		modelBufferSmall.flip();
		modelBufferUnordered.flip();

		IntBuffer vertexBuffer = this.vertexBuffer.getBuffer();
		FloatBuffer uvBuffer = this.uvBuffer.getBuffer();
		IntBuffer modelBuffer = this.modelBuffer.getBuffer();
		IntBuffer modelBufferSmall = this.modelBufferSmall.getBuffer();
		IntBuffer modelBufferUnordered = this.modelBufferUnordered.getBuffer();

		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, tmpBufferId);
		gl.glBufferData(gl.GL_ARRAY_BUFFER, vertexBuffer.limit() * Integer.BYTES, vertexBuffer, gl.GL_DYNAMIC_DRAW);

		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, tmpUvBufferId);
		gl.glBufferData(gl.GL_ARRAY_BUFFER, uvBuffer.limit() * Float.BYTES, uvBuffer, gl.GL_DYNAMIC_DRAW);

		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, tmpModelBufferId);
		gl.glBufferData(gl.GL_ARRAY_BUFFER, modelBuffer.limit() * Integer.BYTES, modelBuffer, gl.GL_DYNAMIC_DRAW);

		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, tmpModelBufferSmallId);
		gl.glBufferData(gl.GL_ARRAY_BUFFER, modelBufferSmall.limit() * Integer.BYTES, modelBufferSmall, gl.GL_DYNAMIC_DRAW);

		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, tmpModelBufferUnorderedId);
		gl.glBufferData(gl.GL_ARRAY_BUFFER, modelBufferUnordered.limit() * Integer.BYTES, modelBufferUnordered, gl.GL_DYNAMIC_DRAW);

		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, tmpOutBufferId);
		gl.glBufferData(gl.GL_ARRAY_BUFFER,
			targetBufferOffset * 16, // each vertex is an ivec4, which is 16 bytes
			null,
			gl.GL_STREAM_DRAW);

		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, tmpOutUvBufferId);
		gl.glBufferData(gl.GL_ARRAY_BUFFER,
			targetBufferOffset * 16,
			null,
			gl.GL_STREAM_DRAW);

		// UBO. Only the first 32 bytes get modified here, the rest is the constant sin/cos table.
		gl.glBindBuffer(gl.GL_UNIFORM_BUFFER, uniformBufferId);
		uniformBuffer.clear();
		uniformBuffer
			.put(yaw)
			.put(pitch)
			.put(centerX)
			.put(centerY)
			.put(client.getScale())
			.put(client.getCameraX2())
			.put(client.getCameraY2())
			.put(client.getCameraZ2());
		uniformBuffer.flip();

		gl.glBufferSubData(gl.GL_UNIFORM_BUFFER, 0, uniformBuffer.limit() * Integer.BYTES, uniformBuffer);
		gl.glBindBuffer(gl.GL_UNIFORM_BUFFER, 0);

		gl.glBindBufferBase(gl.GL_UNIFORM_BUFFER, 0, uniformBufferId);

		// Draw 3d scene
		final TextureProvider textureProvider = client.getTextureProvider();
		if (textureProvider != null)
		{
			if (useComputeShaders)
			{
				gl.glUniformBlockBinding(glSmallComputeProgram, uniBlockSmall, 0);
				gl.glUniformBlockBinding(glComputeProgram, uniBlockLarge, 0);

				/*
				 * Compute is split into two separate programs 'small' and 'large' to
				 * save on GPU resources. Small will sort <= 512 faces, large will do <= 4096.
				 */

				// unordered
				gl.glUseProgram(glUnorderedComputeProgram);

				gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 0, tmpModelBufferUnorderedId);
				gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 1, this.bufferId);
				gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 2, tmpBufferId);
				gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 3, tmpOutBufferId);
				gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 4, tmpOutUvBufferId);
				gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 5, this.uvBufferId);
				gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 6, tmpUvBufferId);

				gl.glDispatchCompute(unorderedModels, 1, 1);

				// small
				gl.glUseProgram(glSmallComputeProgram);

				gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 0, tmpModelBufferSmallId);
				gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 1, this.bufferId);
				gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 2, tmpBufferId);
				gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 3, tmpOutBufferId);
				gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 4, tmpOutUvBufferId);
				gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 5, this.uvBufferId);
				gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 6, tmpUvBufferId);

				gl.glDispatchCompute(smallModels, 1, 1);

				// large
				gl.glUseProgram(glComputeProgram);

				gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 0, tmpModelBufferId);
				gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 1, this.bufferId);
				gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 2, tmpBufferId);
				gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 3, tmpOutBufferId);
				gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 4, tmpOutUvBufferId);
				gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 5, this.uvBufferId);
				gl.glBindBufferBase(gl.GL_SHADER_STORAGE_BUFFER, 6, tmpUvBufferId);

				gl.glDispatchCompute(largeModels, 1, 1);

				gl.glMemoryBarrier(gl.GL_SHADER_STORAGE_BARRIER_BIT);
			}

			if (textureArrayId == -1)
			{
				// lazy init textures as they may not be loaded at plugin start.
				// this will return -1 and retry if not all textures are loaded yet, too.
				textureArrayId = textureManager.initTextureArray(textureProvider, gl);
			}

			final Texture[] textures = textureProvider.getTextures();
			int renderHeightOff = client.getViewportYOffset();
			int renderWidthOff = client.getViewportXOffset();
			int renderCanvasHeight = canvasHeight;
			int renderViewportHeight = viewportHeight;
			int renderViewportWidth = viewportWidth;

			// Setup anisotropic filtering
			final int anisotropicFilteringLevel = config.anisotropicFilteringLevel();

			if (textureArrayId != -1 && lastAnisotropicFilteringLevel != anisotropicFilteringLevel)
			{
				textureManager.setAnisotropicFilteringLevel(textureArrayId, anisotropicFilteringLevel, gl);
				lastAnisotropicFilteringLevel = anisotropicFilteringLevel;
			}

			if (client.isStretchedEnabled())
			{
				Dimension dim = client.getStretchedDimensions();
				renderCanvasHeight = dim.height;

				double scaleFactorY = dim.getHeight() / canvasHeight;
				double scaleFactorX = dim.getWidth() / canvasWidth;

				// Pad the viewport a little because having ints for our viewport dimensions can introduce off-by-one errors.
				final int padding = 1;

				// Ceil the sizes because even if the size is 599.1 we want to treat it as size 600 (i.e. render to the x=599 pixel).
				renderViewportHeight = (int) Math.ceil(scaleFactorY * (renderViewportHeight)) + padding * 2;
				renderViewportWidth = (int) Math.ceil(scaleFactorX * (renderViewportWidth)) + padding * 2;

				// Floor the offsets because even if the offset is 4.9, we want to render to the x=4 pixel anyway.
				renderHeightOff = (int) Math.floor(scaleFactorY * (renderHeightOff)) - padding;
				renderWidthOff = (int) Math.floor(scaleFactorX * (renderWidthOff)) - padding;
			}

			glDpiAwareViewport(renderWidthOff, renderCanvasHeight - renderViewportHeight - renderHeightOff, renderViewportWidth, renderViewportHeight);

			gl.glUseProgram(glProgram);

			final int drawDistance = getDrawDistance();
			final int fogDepth = config.fogDepth();
			final int fogCornerRadius = config.fogCornerRadius();
			final int fogDensity = config.fogDensity();
			final float effectiveDrawDistance = Perspective.LOCAL_TILE_SIZE * (Math.min(Constants.SCENE_SIZE / 2, drawDistance) + 1);
			gl.glUniform1i(uniUseFog, fogDepth > 0 ? 1 : 0);
			gl.glUniform4f(uniFogColor, (sky >> 16 & 0xFF) / 255f, (sky >> 8 & 0xFF) / 255f, (sky & 0xFF) / 255f, 1f);
			gl.glUniform1f(uniFogDepth, fogDepth * 0.01f * effectiveDrawDistance);
			gl.glUniform1f(uniFogCornerRadius, fogCornerRadius * 0.01f * effectiveDrawDistance);
			gl.glUniform1f(uniFogDensity, fogDensity * 0.1f);
			gl.glUniform1i(uniDrawDistance, drawDistance * Perspective.LOCAL_TILE_SIZE);

			// Brightness happens to also be stored in the texture provider, so we use that
			gl.glUniform1f(uniBrightness, (float) textureProvider.getBrightness());
			gl.glUniform1f(uniSmoothBanding, config.smoothBanding() ? 0f : 1f);

			// Calculate projection matrix
			Matrix4 projectionMatrix = new Matrix4();
			projectionMatrix.scale(client.getScale(), client.getScale(), 1);
			projectionMatrix.multMatrix(makeProjectionMatrix(viewportWidth, viewportHeight, 50));
			projectionMatrix.rotate((float) (Math.PI - pitch * Perspective.UNIT), -1, 0, 0);
			projectionMatrix.rotate((float) (yaw * Perspective.UNIT), 0, 1, 0);
			projectionMatrix.translate(-client.getCameraX2(), -client.getCameraY2(), -client.getCameraZ2());
			gl.glUniformMatrix4fv(uniProjectionMatrix, 1, false, projectionMatrix.getMatrix(), 0);

			for (int id = 0; id < textures.length; ++id)
			{
				Texture texture = textures[id];
				if (texture == null)
				{
					continue;
				}

				textureProvider.load(id); // trips the texture load flag which lets textures animate

				textureOffsets[id * 2] = texture.getU();
				textureOffsets[id * 2 + 1] = texture.getV();
			}

			// Bind uniforms
			gl.glUniformBlockBinding(glProgram, uniBlockMain, 0);
			gl.glUniform1i(uniTextures, 1); // texture sampler array is bound to texture1
			gl.glUniform2fv(uniTextureOffsets, 128, textureOffsets, 0);

			// We just allow the GL to do face culling. Note this requires the priority renderer
			// to have logic to disregard culled faces in the priority depth testing.
			gl.glEnable(gl.GL_CULL_FACE);

			// Enable blending for alpha
			gl.glEnable(gl.GL_BLEND);
			gl.glBlendFunc(gl.GL_SRC_ALPHA, gl.GL_ONE_MINUS_SRC_ALPHA);

			// Draw buffers
			gl.glBindVertexArray(vaoHandle);

			// When using compute shaders, draw using the output buffer of the compute. Otherwise
			// only use the temporary buffers, which will contain the full scene.
			gl.glEnableVertexAttribArray(0);
			gl.glBindBuffer(gl.GL_ARRAY_BUFFER, useComputeShaders ? tmpOutBufferId : tmpBufferId);
			gl.glVertexAttribIPointer(0, 4, gl.GL_INT, 0, 0);

			gl.glEnableVertexAttribArray(1);
			gl.glBindBuffer(gl.GL_ARRAY_BUFFER, useComputeShaders ? tmpOutUvBufferId : tmpUvBufferId);
			gl.glVertexAttribPointer(1, 4, gl.GL_FLOAT, false, 0, 0);

			gl.glDrawArrays(gl.GL_TRIANGLES, 0, targetBufferOffset);

			gl.glDisable(gl.GL_BLEND);
			gl.glDisable(gl.GL_CULL_FACE);

			gl.glUseProgram(0);
		}

		if (aaEnabled)
		{
			gl.glBindFramebuffer(gl.GL_READ_FRAMEBUFFER, fboSceneHandle);
			gl.glBindFramebuffer(gl.GL_DRAW_FRAMEBUFFER, 0);
			gl.glBlitFramebuffer(0, 0, lastStretchedCanvasWidth, lastStretchedCanvasHeight,
				0, 0, lastStretchedCanvasWidth, lastStretchedCanvasHeight,
				gl.GL_COLOR_BUFFER_BIT, gl.GL_NEAREST);

			// Reset
			gl.glBindFramebuffer(gl.GL_READ_FRAMEBUFFER, 0);
		}

		vertexBuffer.clear();
		uvBuffer.clear();
		modelBuffer.clear();
		modelBufferSmall.clear();
		modelBufferUnordered.clear();

		targetBufferOffset = 0;
		smallModels = largeModels = unorderedModels = 0;
		tempOffset = 0;
		tempUvOffset = 0;

		// Texture on UI
		drawUi(canvasHeight, canvasWidth);

		glDrawable.swapBuffers();

		drawManager.processDrawComplete(this::screenshot);
	}

	private float[] makeProjectionMatrix(float w, float h, float n)
	{
		return new float[]
			{
				2 / w, 0, 0, 0,
				0, 2 / h, 0, 0,
				0, 0, -1, -1,
				0, 0, -2 * n, 0
			};
	}

	private void drawUi(final int canvasHeight, final int canvasWidth)
	{
		final BufferProvider bufferProvider = client.getBufferProvider();
		int[] pixels = bufferProvider.getPixels();
		final int width = bufferProvider.getWidth();
		final int height = bufferProvider.getHeight();

		// Handle Mirror
		// We still need to draw AFTER_MIRROR regardless of Mirror being active
		if (client.isMirrored())
		{
			if (MirrorPlugin.bufferedImage == null)
			{
				MirrorPlugin.bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			}

			tempGraphics = MirrorPlugin.bufferedImage.getGraphics();

			if (tempGraphics != null)
			{
				if (MirrorPlugin.canvas.getWidth() != width || MirrorPlugin.canvas.getHeight() != height)
				{
					MirrorPlugin.bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
					MirrorPlugin.canvas.setSize(width, height);
					MirrorPlugin.jframe.setSize(width + 15, height + 40);
				}

				Image i = currentRender();
				int[] a = ArrayUtils.clone(pixels);

				Thread t = new Thread(() ->
				{
					drawThreadRunning = true;
					tempGraphics.drawImage(i, 0, 0, null);
					tempGraphics.drawImage(toBufferedImage(a, width, height), 0, 0, null);
					tempGraphics.drawImage(cursor, mouseX, mouseY, null);
					MirrorPlugin.canvas.getGraphics().drawImage(MirrorPlugin.bufferedImage, 0, 0, null);
					drawThreadRunning = false;
				});

				if (!drawThreadRunning)
				{
					t.start();
				}
			}
		}

		Hooks.renderer.render(Hooks.lastGraphics, OverlayLayer.AFTER_MIRROR);

		gl.glEnable(gl.GL_BLEND);

		vertexBuffer.clear(); // reuse vertex buffer for interface
		vertexBuffer.ensureCapacity(pixels.length);

		IntBuffer interfaceBuffer = vertexBuffer.getBuffer();

		interfaceBuffer.put(pixels);

		vertexBuffer.flip();

		gl.glBlendFunc(gl.GL_ONE, gl.GL_ONE_MINUS_SRC_ALPHA);
		gl.glBindTexture(gl.GL_TEXTURE_2D, interfaceTexture);

		gl.glTexSubImage2D(gl.GL_TEXTURE_2D, 0, 0, 0, width, height, gl.GL_BGRA, gl.GL_UNSIGNED_INT_8_8_8_8_REV, interfaceBuffer);

		// Use the texture bound in the first pass
		final UIScalingMode uiScalingMode = config.uiScalingMode();
		gl.glUseProgram(glUiProgram);
		gl.glUniform1i(uniTex, 0);
		gl.glUniform1i(uniTexSamplingMode, uiScalingMode.getMode());
		gl.glUniform2i(uniTexSourceDimensions, canvasWidth, canvasHeight);

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			glDpiAwareViewport(0, 0, dim.width, dim.height);
			gl.glUniform2i(uniTexTargetDimensions, dim.width, dim.height);
		}
		else
		{
			glDpiAwareViewport(0, 0, canvasWidth, canvasHeight);
			gl.glUniform2i(uniTexTargetDimensions, canvasWidth, canvasHeight);
		}

		// Set the sampling function used when stretching the UI.
		// This is probably better done with sampler objects instead of texture parameters, but this is easier and likely more portable.
		// See https://www.khronos.org/opengl/wiki/Sampler_Object for details.
		if (client.isStretchedEnabled())
		{
			// GL_NEAREST makes sampling for bicubic/xBR simpler, so it should be used whenever linear isn't
			final int function = uiScalingMode == UIScalingMode.LINEAR ? gl.GL_LINEAR : gl.GL_NEAREST;
			gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MIN_FILTER, function);
			gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MAG_FILTER, function);
		}

		// Texture on UI
		gl.glBindVertexArray(vaoUiHandle);
		gl.glDrawArrays(gl.GL_TRIANGLE_FAN, 0, 4);

		// Reset
		gl.glBindTexture(gl.GL_TEXTURE_2D, 0);
		gl.glBindVertexArray(0);
		gl.glUseProgram(0);
		gl.glBlendFunc(gl.GL_SRC_ALPHA, gl.GL_ONE_MINUS_SRC_ALPHA);
		gl.glDisable(gl.GL_BLEND);

		vertexBuffer.clear();
	}

	public BufferedImage toBufferedImage(int[] pixels, int width, int height)
	{
		if (tempBufferedImage == null || tempBufferedImage.getWidth() != width || tempBufferedImage.getHeight() != height)
		{
			tempBufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		}

		tempBufferedImage.setRGB(0, 0, width, height, pixels, 0, width);
		return tempBufferedImage;
	}

	/**
	 * Convert the front framebuffer to an Image
	 *
	 * @return
	 */
	private Image screenshot()
	{
		int width = client.getCanvasWidth();
		int height = client.getCanvasHeight();

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			width = dim.width;
			height = dim.height;
		}

		if (OSType.getOSType() != OSType.MacOS)
		{
			final Graphics2D graphics = (Graphics2D) canvas.getGraphics();
			final AffineTransform t = graphics.getTransform();
			width = getScaledValue(t.getScaleX(), width);
			height = getScaledValue(t.getScaleY(), height);
			graphics.dispose();
		}

		ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4)
			.order(ByteOrder.nativeOrder());

		gl.glReadBuffer(gl.GL_FRONT);
		gl.glReadPixels(0, 0, width, height, GL.GL_RGBA, gl.GL_UNSIGNED_BYTE, buffer);

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

		for (int y = 0; y < height; ++y)
		{
			for (int x = 0; x < width; ++x)
			{
				int r = buffer.get() & 0xff;
				int g = buffer.get() & 0xff;
				int b = buffer.get() & 0xff;
				buffer.get(); // alpha

				pixels[(height - y - 1) * width + x] = (r << 16) | (g << 8) | b;
			}
		}

		return image;
	}

	private Image currentRender()
	{
		int width;
		int height;

		if (client.isStretchedEnabled())
		{
			width = (int) (((float) client.getCanvasWidth() * client.getScalingFactor()) * ((float) config.windowsScale().getScale() / 100));
			height = (int) (((float) client.getCanvasHeight() * client.getScalingFactor()) * ((float) config.windowsScale().getScale() / 100));
		}
		else
		{
			width = (int) (client.getCanvasWidth() * ((float) config.windowsScale().getScale() / 100));
			height = (int) (client.getCanvasHeight() * ((float) config.windowsScale().getScale() / 100));
		}

		ByteBuffer tempBuffer = ByteBuffer.allocateDirect(width * height * 4)
			.order(ByteOrder.nativeOrder());

		gl.glReadBuffer(gl.GL_BACK);
		gl.glReadPixels(0, 0, width, height, GL.GL_RGBA, gl.GL_UNSIGNED_BYTE, tempBuffer);

		BufferedImage mirrorImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] pixels = ((DataBufferInt) mirrorImage.getRaster().getDataBuffer()).getData();

		for (int y = 0; y < height; ++y)
		{
			for (int x = 0; x < width; ++x)
			{
				int r = tempBuffer.get() & 0xff;
				int g = tempBuffer.get() & 0xff;
				int b = tempBuffer.get() & 0xff;
				tempBuffer.get(); // alpha

				pixels[(height - y - 1) * width + x] = (r << 16) | (g << 8) | b;
			}
		}
		return mirrorImage;
	}

	@Override
	public void animate(Texture texture, int diff)
	{
		textureManager.animate(texture, diff);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (!useComputeShaders || gameStateChanged.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		uploadScene();
	}

	private void uploadScene()
	{
		vertexBuffer.clear();
		uvBuffer.clear();

		sceneUploader.upload(client.getScene(), vertexBuffer, uvBuffer);

		vertexBuffer.flip();
		uvBuffer.flip();

		IntBuffer vertexBuffer = this.vertexBuffer.getBuffer();
		FloatBuffer uvBuffer = this.uvBuffer.getBuffer();

		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, bufferId);
		gl.glBufferData(gl.GL_ARRAY_BUFFER, vertexBuffer.limit() * Integer.BYTES, vertexBuffer, gl.GL_STATIC_COPY);

		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, uvBufferId);
		gl.glBufferData(gl.GL_ARRAY_BUFFER, uvBuffer.limit() * Float.BYTES, uvBuffer, gl.GL_STATIC_COPY);

		gl.glBindBuffer(gl.GL_ARRAY_BUFFER, 0);

		vertexBuffer.clear();
		uvBuffer.clear();
	}

	/**
	 * Check is a model is visible and should be drawn.
	 */
	private boolean isVisible(Model model, int orientation, int pitchSin, int pitchCos, int yawSin, int yawCos, int _x, int _y, int _z, long hash)
	{
		final int XYZMag = model.getXYZMag();
		final int zoom = client.get3dZoom();
		final int modelHeight = model.getModelHeight();

		int Rasterizer3D_clipMidX2 = client.getRasterizer3D_clipMidX2();
		int Rasterizer3D_clipNegativeMidX = client.getRasterizer3D_clipNegativeMidX();
		int Rasterizer3D_clipNegativeMidY = client.getRasterizer3D_clipNegativeMidY();
		int Rasterizer3D_clipMidY2 = client.getRasterizer3D_clipMidY2();

		int var11 = yawCos * _z - yawSin * _x >> 16;
		int var12 = pitchSin * _y + pitchCos * var11 >> 16;
		int var13 = pitchCos * XYZMag >> 16;
		int var14 = var12 + var13;
		if (var14 > 50)
		{
			int var15 = _z * yawSin + yawCos * _x >> 16;
			int var16 = (var15 - XYZMag) * zoom;
			if (var16 / var14 < Rasterizer3D_clipMidX2)
			{
				int var17 = (var15 + XYZMag) * zoom;
				if (var17 / var14 > Rasterizer3D_clipNegativeMidX)
				{
					int var18 = pitchCos * _y - var11 * pitchSin >> 16;
					int var19 = pitchSin * XYZMag >> 16;
					int var20 = (var18 + var19) * zoom;
					if (var20 / var14 > Rasterizer3D_clipNegativeMidY)
					{
						int var21 = (pitchCos * modelHeight >> 16) + var19;
						int var22 = (var18 - var21) * zoom;
						return var22 / var14 < Rasterizer3D_clipMidY2;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Draw a renderable in the scene
	 *
	 * @param renderable
	 * @param orientation
	 * @param pitchSin
	 * @param pitchCos
	 * @param yawSin
	 * @param yawCos
	 * @param x
	 * @param y
	 * @param z
	 * @param hash
	 */
	@Override
	public void draw(Entity renderable, int orientation, int pitchSin, int pitchCos, int yawSin, int yawCos, int x, int y, int z, long hash)
	{
		if (!useComputeShaders)
		{
			Model model = renderable instanceof Model ? (Model) renderable : renderable.getModel();
			if (model != null)
			{
				model.calculateBoundsCylinder();
				model.calculateExtreme(orientation);

				if (!isVisible(model, orientation, pitchSin, pitchCos, yawSin, yawCos, x, y, z, hash))
				{
					return;
				}

				client.checkClickbox(model, orientation, pitchSin, pitchCos, yawSin, yawCos, x, y, z, hash);

				modelX = x + client.getCameraX2();
				modelY = y + client.getCameraY2();
				modelZ = z + client.getCameraZ2();
				modelOrientation = orientation;
				int triangleCount = model.getTrianglesCount();
				vertexBuffer.ensureCapacity(12 * triangleCount);
				uvBuffer.ensureCapacity(12 * triangleCount);

				drawingModel = true;

				renderable.draw(orientation, pitchSin, pitchCos, yawSin, yawCos, x, y, z, hash);

				drawingModel = false;
			}
		}
		// Model may be in the scene buffer
		else if (renderable instanceof Model && ((Model) renderable).getSceneId() == sceneUploader.sceneId)
		{
			Model model = (Model) renderable;

			model.calculateBoundsCylinder();
			model.calculateExtreme(orientation);

			if (!isVisible(model, orientation, pitchSin, pitchCos, yawSin, yawCos, x, y, z, hash))
			{
				return;
			}

			client.checkClickbox(model, orientation, pitchSin, pitchCos, yawSin, yawCos, x, y, z, hash);

			int tc = Math.min(MAX_TRIANGLE, model.getTrianglesCount());
			int uvOffset = model.getUvBufferOffset();

			GpuIntBuffer b = bufferForTriangles(tc);

			b.ensureCapacity(8);
			IntBuffer buffer = b.getBuffer();
			buffer.put(model.getBufferOffset());
			buffer.put(uvOffset);
			buffer.put(tc);
			buffer.put(targetBufferOffset);
			buffer.put(FLAG_SCENE_BUFFER | (model.getRadius() << 12) | orientation);
			buffer.put(x + client.getCameraX2()).put(y + client.getCameraY2()).put(z + client.getCameraZ2());

			targetBufferOffset += tc * 3;
		}
		else
		{
			// Temporary model (animated or otherwise not a static Model on the scene)
			Model model = renderable instanceof Model ? (Model) renderable : renderable.getModel();
			if (model != null)
			{
				// Apply height to renderable from the model
				model.setModelHeight(model.getModelHeight());

				model.calculateBoundsCylinder();
				model.calculateExtreme(orientation);

				if (!isVisible(model, orientation, pitchSin, pitchCos, yawSin, yawCos, x, y, z, hash))
				{
					return;
				}

				client.checkClickbox(model, orientation, pitchSin, pitchCos, yawSin, yawCos, x, y, z, hash);

				boolean hasUv = model.getFaceTextures() != null;

				int faces = Math.min(MAX_TRIANGLE, model.getTrianglesCount());
				vertexBuffer.ensureCapacity(12 * faces);
				uvBuffer.ensureCapacity(12 * faces);
				int len = 0;
				for (int i = 0; i < faces; ++i)
				{
					len += sceneUploader.pushFace(model, i, false, vertexBuffer, uvBuffer, 0, 0, 0, 0);
				}

				GpuIntBuffer b = bufferForTriangles(faces);

				b.ensureCapacity(8);
				IntBuffer buffer = b.getBuffer();
				buffer.put(tempOffset);
				buffer.put(hasUv ? tempUvOffset : -1);
				buffer.put(len / 3);
				buffer.put(targetBufferOffset);
				buffer.put((model.getRadius() << 12) | orientation);
				buffer.put(x + client.getCameraX2()).put(y + client.getCameraY2()).put(z + client.getCameraZ2());

				tempOffset += len;
				if (hasUv)
				{
					tempUvOffset += len;
				}

				targetBufferOffset += len;
			}
		}
	}

	@Override
	public boolean drawFace(Model model, int face)
	{
		if (!drawingModel)
		{
			return false;
		}

		targetBufferOffset += sceneUploader.pushFace(model, face, true, vertexBuffer, uvBuffer, modelX, modelY, modelZ, modelOrientation);
		return true;
	}

	/**
	 * returns the correct buffer based on triangle count and updates model count
	 *
	 * @param triangles
	 * @return
	 */
	private GpuIntBuffer bufferForTriangles(int triangles)
	{
		if (triangles <= SMALL_TRIANGLE_COUNT)
		{
			++smallModels;
			return modelBufferSmall;
		}
		else
		{
			++largeModels;
			return modelBuffer;
		}
	}

	private int getScaledValue(final double scale, final int value)
	{
		return SurfaceScaleUtils.scale(value, (float) scale);
	}

	private void glDpiAwareViewport(final int x, final int y, final int width, final int height)
	{
		if (OSType.getOSType() == OSType.MacOS)
		{
			// JOGL seems to handle DPI scaling for us already
			gl.glViewport(x, y, width, height);
		}
		else
		{
			final Graphics2D graphics = (Graphics2D) canvas.getGraphics();
			final AffineTransform t = graphics.getTransform();
			gl.glViewport(
				getScaledValue(t.getScaleX(), x),
				getScaledValue(t.getScaleY(), y),
				getScaledValue(t.getScaleX(), width),
				getScaledValue(t.getScaleY(), height));
			graphics.dispose();
		}
	}

	private int getDrawDistance()
	{
		final int limit = useComputeShaders ? MAX_DISTANCE : DEFAULT_DISTANCE;
		return Ints.constrainToRange(config.drawDistance(), 0, limit);
	}

	private static void invokeOnMainThread(Runnable runnable)
	{
		if (OSType.getOSType() == OSType.MacOS)
		{
			OSXUtil.RunOnMainThread(true, false, runnable);
		}
		else
		{
			runnable.run();
		}
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent mouseEvent)
	{
		mouseX = mouseEvent.getX();
		mouseY = mouseEvent.getY();
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent mouseEvent)
	{
		mouseX = mouseEvent.getX();
		mouseY = mouseEvent.getY();
		return mouseEvent;
	}
}
