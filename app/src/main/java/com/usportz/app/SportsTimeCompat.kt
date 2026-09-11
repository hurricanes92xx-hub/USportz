package com.usportz.app

/** Compatibility overload used by the dashboard when sorting SportsEvent objects. */
fun eventEpoch(event: SportsEvent): Long? = eventEpoch(event.startTime)
