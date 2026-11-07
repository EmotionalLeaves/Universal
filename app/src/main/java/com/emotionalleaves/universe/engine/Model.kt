package com.emotionalleaves.universe.engine

import com.emotionalleaves.universe.core.UPos
import com.emotionalleaves.universe.core.Units

enum class Kind(val label: String) {
    STAR("Star"),
    PLANET("Planet"),
    DWARF_PLANET("Dwarf planet"),
    MOON("Moon"),
    GALAXY("Galaxy"),
    NEBULA("Nebula"),
    CLUSTER("Star cluster"),
    MARKER("Marker"),
    UNIVERSE("Universe"),
}

/** Where an object's data came from (rule 5 in docs/PLAN.md). */
enum class Source(val label: String) { REAL("Real"), GENERATED("Generated") }

/** Anything you can select, focus, search for and bookmark. */
abstract class Body {
    /** Stable id used for bookmarks and search, e.g. "sol:earth", "hyg:32263", "gs:mw:12,4,-3:17". */
    abstract val id: String
    abstract val name: String
    abstract val kind: Kind
    abstract val source: Source

    /** Physical radius in metres (0 for point-like objects). */
    open val radius: Double = 0.0

    /** Position at Julian date [jd]. */
    abstract fun position(jd: Double): UPos

    /** Short subtitle for info cards, e.g. "Planet of the Sun". */
    open val subtitle: String get() = kind.label

    /** Label/value lines for the info card. */
    open fun facts(jd: Double): List<Pair<String, String>> = emptyList()

    /** A comfortable distance to view this object from. */
    open fun viewDistance(): Double = if (radius > 0) radius * 3.2 else Units.AU

    /** Display colour as 0xRRGGBB. */
    open val color: Int = 0xFFFFFF

    override fun equals(other: Any?) = other is Body && other.id == id
    override fun hashCode() = id.hashCode()
}

/** A fixed point in space (scale-test markers, bookmarks of empty space). */
class MarkerBody(
    override val id: String,
    override val name: String,
    private val pos: UPos,
    override val radius: Double = 0.0,
    private val sub: String = "Marker",
    override val source: Source = Source.GENERATED,
) : Body() {
    override val kind = Kind.MARKER
    override fun position(jd: Double) = pos
    override val subtitle get() = sub
    override fun viewDistance() = if (radius > 0) radius * 6 else 1.0e3
}
