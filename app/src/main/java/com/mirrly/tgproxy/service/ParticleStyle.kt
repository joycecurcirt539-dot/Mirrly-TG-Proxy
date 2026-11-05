package com.mirrly.tgproxy.service

/**
 * Visual and physical simulation styles for procedural background particles.
 */
enum class ParticleStyle(val id: String) {
    /** Soft glowing micro-spheres with floating drift, pulsation, and constellation links. */
    BOKEH_DUST("BOKEH_DUST"),

    /** Discrete downward vertical pulses simulating real-time network socket data streams. */
    BITSTREAM_MATRIX("BITSTREAM_MATRIX"),

    /** High-speed radial warp jump with quadratic acceleration and dynamic size scaling. */
    STARFIELD_WARP("STARFIELD_WARP");

    companion object {
        fun fromString(value: String?): ParticleStyle {
            return entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.id.equals(value, ignoreCase = true)
            } ?: BOKEH_DUST
        }
    }
}
