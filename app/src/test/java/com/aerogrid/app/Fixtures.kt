package com.aerogrid.app

import com.aerogrid.app.engine.LocalProjection
import com.aerogrid.app.engine.MissionEditor
import com.aerogrid.app.model.GeoPoint
import com.aerogrid.app.model.Project
import org.locationtech.jts.geom.Coordinate

object Fixtures {
    val TAVUA = GeoPoint(-17.4442, 177.8646)
    private val proj = LocalProjection(TAVUA)

    /** Ring from local metre offsets (east, north) around Tavua. */
    fun ring(vararg xy: Pair<Double, Double>): List<GeoPoint> = xy.map { (x, y) -> proj.toGeo(Coordinate(x, y)) }

    val rect400x300 = ring(0.0 to 0.0, 400.0 to 0.0, 400.0 to 300.0, 0.0 to 300.0)
    /** U-shaped concave polygon (notch from the top). */
    val uShape = ring(0.0 to 0.0, 400.0 to 0.0, 400.0 to 300.0, 260.0 to 300.0, 260.0 to 100.0, 140.0 to 100.0, 140.0 to 300.0, 0.0 to 300.0)
    val exclusionCenter = ring(150.0 to 100.0, 250.0 to 100.0, 250.0 to 200.0, 150.0 to 200.0)

    fun project(boundary: List<GeoPoint> = rect400x300) = Project(name = "Tavua Test", boundaries = listOf(boundary))
    fun generated(p: Project = project()) = MissionEditor.regenerate(p, null).first
}
