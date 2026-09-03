package com.luaide.app

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * A real OpenGL ES context via the public GLSurfaceView API (it negotiates
 * EGL config/context version for you \u2014 no manual EGL calls needed). Drives
 * three Lua callbacks (graphics_on_create/on_resize/on_frame) once per real
 * GPU frame. This intentionally doesn't try to bind the entire GLES/Vulkan
 * surface \u2014 that's a large ongoing binding effort \u2014 but every call here
 * executes on the actual GPU, not a stub.
 */
class LuaGLSurfaceView(context: Context, private val lua: LuaBridge, private val script: String) : GLSurfaceView(context) {

    init {
        setEGLContextClientVersion(3) // requests GLES 3.x; GLSurfaceView negotiates down if unsupported
        setRenderer(LuaRenderer())
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private inner class LuaRenderer : Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // Runs on the GL thread — this Lua state must never be touched from
            // any other thread (see RunnerActivity: it's a dedicated state, not
            // the one used for print()/stdout on the main thread).
            lua.eval(script, "@main.lua (graphics)")
            lua.eval("if graphics_on_create then graphics_on_create() end", "=gl_create")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            lua.eval("if graphics_on_resize then graphics_on_resize($width, $height) end", "=gl_resize")
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            lua.eval("if graphics_on_frame then graphics_on_frame() end", "=gl_frame")
        }
    }
}
