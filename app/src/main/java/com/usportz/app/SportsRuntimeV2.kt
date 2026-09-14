package com.usportz.app

import android.content.Context

/** Shared, app-private intelligence services. No credentials or stream URLs are uploaded anywhere. */
object SportsRuntimeV2 {
    @Volatile private var initialized = false
    private lateinit var eventDna: EventDnaStore
    private lateinit var telemetry: StreamTelemetryStore

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            eventDna = EventDnaStore(context.applicationContext)
            telemetry = StreamTelemetryStore(context.applicationContext)
            initialized = true
        }
    }

    fun eventDna(): EventDnaStore = eventDna
    fun telemetry(): StreamTelemetryStore = telemetry
    fun close() { if (initialized) { eventDna.close(); telemetry.close(); initialized = false } }
}
