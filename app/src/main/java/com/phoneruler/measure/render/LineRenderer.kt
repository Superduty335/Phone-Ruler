package com.phoneruler.measure.render

import android.opengl.GLES20
import java.nio.FloatBuffer

/** Draws world-space points and lines in a flat color. */
class LineRenderer {
    private var program = 0
    private var positionAttrib = 0
    private var mvpUniform = 0
    private var colorUniform = 0
    private var pointSizeUniform = 0
    private var buffer: FloatBuffer = ShaderUtil.floatBuffer(256)

    fun createOnGlThread() {
        program = ShaderUtil.program(VERTEX, FRAGMENT)
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_MVP")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
        pointSizeUniform = GLES20.glGetUniformLocation(program, "u_PointSize")
    }

    /** [xyz] holds 3 floats per vertex; [mode] is GL_LINES, GL_LINE_STRIP, GL_LINE_LOOP or GL_POINTS. */
    fun draw(mode: Int, xyz: FloatArray, viewProj: FloatArray, color: Int, width: Float) {
        val count = xyz.size / 3
        if (count == 0) return
        if (buffer.capacity() < xyz.size) buffer = ShaderUtil.floatBuffer(xyz.size * 2)
        buffer.clear()
        buffer.put(xyz)
        buffer.position(0)

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, viewProj, 0)
        GLES20.glUniform4f(
            colorUniform,
            ((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f,
            (color and 0xFF) / 255f, ((color ushr 24) and 0xFF) / 255f
        )
        GLES20.glUniform1f(pointSizeUniform, width)
        GLES20.glLineWidth(width)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glEnableVertexAttribArray(positionAttrib)
        GLES20.glDrawArrays(mode, 0, count)
        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private companion object {
        const val VERTEX = """
            uniform mat4 u_MVP;
            uniform float u_PointSize;
            attribute vec3 a_Position;
            void main() {
                gl_Position = u_MVP * vec4(a_Position, 1.0);
                gl_PointSize = u_PointSize;
            }
        """
        const val FRAGMENT = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """
    }
}
