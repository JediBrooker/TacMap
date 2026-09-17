package com.tacmap.models

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.view.Surface
import android.view.WindowManager
import com.tacmap.map.normalizedHeadingDegrees
import com.tacmap.map.shortestHeadingDelta
import com.tacmap.map.smoothedHeadingDegrees
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

enum class HeadingNorthReference(
    val displaySuffix: String,
    val accessibilityLabel: String,
) {
    TRUE_NORTH("T", "true north"),
    MAGNETIC_NORTH("M", "magnetic north"),
}

/** Foreground-only phone-compass adapter for Heading Up map orientation. */
class HeadingService(
    context: Context,
    private val locationProvider: () -> Location? = { null },
) : SensorEventListener {
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val headingSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

    val isHeadingAvailable: Boolean get() = headingSensor != null

    private val _headingDegrees = MutableStateFlow<Double?>(null)
    val headingDegrees: StateFlow<Double?> = _headingDegrees.asStateFlow()

    private val _headingNorthReference = MutableStateFlow<HeadingNorthReference?>(null)
    val headingNorthReference: StateFlow<HeadingNorthReference?> =
        _headingNorthReference.asStateFlow()

    private var running = false
    private var filteredHeading: Double? = null
    private var declinationLocationTime = Long.MIN_VALUE
    private var cachedDeclination = 0.0

    @Synchronized
    fun start(): Boolean {
        val sensor = headingSensor ?: return false
        if (running) return true
        invalidateHeading()
        running = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        return running
    }

    @Synchronized
    fun stop() {
        if (!running) return
        sensorManager.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.sensor.type != headingSensor?.type) return
        if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
            !hasUsableHeadingError(event.values)
        ) {
            invalidateHeading()
            return
        }
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val (axisX, axisY) = displayAxes(displayRotation())
        val adjustedMatrix = if (axisX == SensorManager.AXIS_X && axisY == SensorManager.AXIS_Y) {
            rotationMatrix
        } else {
            FloatArray(9).also { adjusted ->
                if (!SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, adjusted)) {
                    return
                }
            }
        }
        val orientation = FloatArray(3)
        SensorManager.getOrientation(adjustedMatrix, orientation)
        val magnetic = Math.toDegrees(orientation[0].toDouble())
        if (!magnetic.isFinite()) return
        val correction = currentNorthCorrection()
        val heading = normalizedHeadingDegrees(magnetic + correction.declinationDegrees)
        val referenceChanged = _headingNorthReference.value != correction.reference
        if (referenceChanged) filteredHeading = null
        val filtered = smoothedHeadingDegrees(filteredHeading, heading)
        filteredHeading = filtered
        _headingNorthReference.value = correction.reference
        val published = _headingDegrees.value
        if (referenceChanged ||
            published == null ||
            abs(shortestHeadingDelta(published, filtered)) >= 0.5
        ) {
            _headingDegrees.value = filtered
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (running &&
            sensor?.type == headingSensor?.type &&
            accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
        ) {
            invalidateHeading()
        }
    }

    private fun invalidateHeading() {
        filteredHeading = null
        _headingDegrees.value = null
        _headingNorthReference.value = null
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = windowManager.defaultDisplay.rotation

    private fun currentNorthCorrection(now: Long = System.currentTimeMillis()): NorthCorrection {
        val location = locationProvider()
            ?: return NorthCorrection(0.0, HeadingNorthReference.MAGNETIC_NORTH)
        val age = if (location.time > 0) now - location.time else Long.MAX_VALUE
        if (age < 0 || age > MAX_DECLINATION_LOCATION_AGE_MS) {
            return NorthCorrection(0.0, HeadingNorthReference.MAGNETIC_NORTH)
        }
        if (location.time != declinationLocationTime) {
            val altitude = location.altitude.takeIf { it.isFinite() } ?: 0.0
            cachedDeclination = GeomagneticField(
                location.latitude.toFloat(),
                location.longitude.toFloat(),
                altitude.toFloat(),
                now,
            ).declination.toDouble()
            declinationLocationTime = location.time
        }
        return NorthCorrection(cachedDeclination, HeadingNorthReference.TRUE_NORTH)
    }

    companion object {
        private const val MAX_DECLINATION_LOCATION_AGE_MS = 60L * 60L * 1_000L

        private data class NorthCorrection(
            val declinationDegrees: Double,
            val reference: HeadingNorthReference,
        )

        /** Rotation-vector index 4 is an optional estimated heading error in radians. */
        internal fun hasUsableHeadingError(
            values: FloatArray,
            maximumErrorDegrees: Double = 45.0,
        ): Boolean {
            if (values.size <= 4) return true
            val radians = values[4]
            // A negative value means this sensor does not provide the optional estimate.
            if (radians < 0f) return true
            if (!radians.isFinite()) return false
            return Math.toDegrees(radians.toDouble()) <= maximumErrorDegrees
        }

        internal fun displayAxes(rotation: Int): Pair<Int, Int> = when (rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
    }
}
